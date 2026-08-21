#!/usr/bin/env python3
"""A1 native TUI proof.

Drives the real bbagent executable's `tui` command inside a PTY and checks the
milestone's product paths: the TUI starts, the bounded context and capability
view render from real bb4t metadata, the event pane fills incrementally, the
session browser lists SQLite sessions, quitting checkpoints cleanly, and a
second process resumes the same session.

Usage:
  tui-native-proof.py DIST_DIR STATE_ROOT PROJECT_ROOT
"""
import os
import pty
import re
import select
import signal
import struct
import sys
import termios
import fcntl
import time

ANSI = re.compile(r"\x1b\[[0-9;?]*[a-zA-Z]|\x1b\][^\x07]*\x07|\x1b[()][A-Z0-9]")


def plain(data: bytes) -> str:
    return ANSI.sub("", data.decode("utf-8", "replace"))


def set_size(fd, rows, cols):
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))


class Session:
    def __init__(self, argv, cwd, env):
        self.pid, self.fd = pty.fork()
        if self.pid == 0:
            os.chdir(cwd)
            os.execvpe(argv[0], argv, env)
        set_size(self.fd, 40, 120)
        self.out = bytearray()

    def pump(self, seconds):
        end = time.time() + seconds
        while time.time() < end:
            r, _, _ = select.select([self.fd], [], [], 0.1)
            if r:
                try:
                    d = os.read(self.fd, 65536)
                except OSError:
                    return False
                if not d:
                    return False
                self.out.extend(d)
        return True

    def send(self, data: bytes, settle=1.5):
        os.write(self.fd, data)
        self.pump(settle)

    def text(self):
        return plain(bytes(self.out))

    def wait(self, timeout=20):
        deadline = time.time() + timeout
        while time.time() < deadline:
            wpid, status = os.waitpid(self.pid, os.WNOHANG)
            if wpid:
                return os.waitstatus_to_exitcode(status)
            self.pump(0.3)
        os.kill(self.pid, signal.SIGKILL)
        os.waitpid(self.pid, 0)
        return "TIMEOUT"


def run(dist, state_root, project_root):
    # The TUI constructs a provider at startup exactly as `run` does.  This
    # proof never submits a message, so no request is ever issued; the
    # unroutable endpoint below exists only to satisfy that construction and
    # to guarantee that a model call could not silently succeed.
    env = dict(
        os.environ,
        TERM="xterm-256color",
        BBAGENT_STATE_ROOT=state_root,
        OPENAI_BASE_URL="https://127.0.0.1:1",
        OPENAI_MODEL="a1-native-proof-no-call",
        OPENAI_API_KEY="a1-native-proof-unused",
    )
    exe = os.path.join(dist, "bbagent")
    gates = {}

    # --- first process: create a new SQLite-backed session ------------------
    s = Session([exe, "tui", "--project", project_root], dist, env)
    s.pump(12.0)
    first = s.text()
    gates["tui_starts"] = "bbagent" in first
    gates["header_shows_sqlite"] = "sqlite" in first
    gates["capability_pane"] = (
        "project/read" in first and "data.json/read" in first
    )
    # A2: the capability pane is a projection of the real context description,
    # so the operations added to the profile have to appear without the pane
    # being told about them.
    gates["capability_pane_a2"] = (
        "project/list" in first and "project/search" in first
    )
    gates["capability_pane_write"] = (
        "project/stat" in first and "project/edit" in first
    )
    gates["profile_from_bb4t"] = "agent/project-develop" in first
    gates["events_pane"] = "session/started" in first or "Recent Events" in first

    mark = len(s.out)
    s.send(b"\x13", settle=3.0)  # Ctrl-S: session browser
    browser = plain(bytes(s.out[mark:]))
    gates["session_browser"] = "sessions" in browser

    s.send(b"\x1b", settle=1.0)  # Esc: close browser
    mark = len(s.out)
    s.send(b"\x14", settle=1.0)  # Ctrl-T: operator repl mode
    gates["repl_mode"] = "repl>" in plain(bytes(s.out[mark:]))

    # Asserted against the whole stream, not a slice since the last send.
    # charm redraws incrementally: once a pane is full a new line is rewritten
    # in place rather than emitted contiguously, so a slice can miss content
    # that is plainly on screen. Every marker below is unique to its own
    # evaluation, so the stream cannot supply it from anywhere else.
    s.send(b'(str "EVAL-" (+ 1 2))\r', settle=4.0)  # bounded evaluation
    gates["bounded_eval"] = "EVAL-3" in s.text()  # pane is still short here

    # A2 capabilities, driven through the real terminal against the real
    # image.  The JVM suite proves the semantics; this proves they survive
    # native compilation and reach an operator at a terminal.
    # Each probe renders a value unique to itself. The pane redraws several
    # previous entries on every keystroke, so a gate that looks for ":ok"
    # could be satisfied by an older line rather than by its own result.
    s.send(b'(str "LIST-" (count (project/list ".")))\r', settle=5.0)

    s.send(b'(str "SEARCH-" (count (project/search "fixture")))\r', settle=6.0)

    # The expanded vocabulary: a definition the agent could have written,
    # applied to a capability result, with the string namespace under its
    # ordinary alias.
    s.send(b"(defn names [es] (mapv :name es))\r", settle=5.0)

    s.send(b'(str "COMPOSE-" (str/join "," (names (project/list "."))))\r',
           settle=6.0)

    # A lazy result must describe as data rather than as an opaque host object,
    # or the vocabulary above produces nothing an operator can see.
    s.send(b'(str "LAZY-" (first (take 1 (map :name (project/list ".")))))\r',
           settle=5.0)

    # The write path runs in its own session further down, so that this
    # session's resume exercises reconstruction of observations while that
    # one exercises reconstruction of a change.

    # Operator and model share one bounded Context, so an operator definition
    # must be journaled and reconstructed on resume.  Define it here and read
    # it back in the second process below.
    s.send(b"(def native-operator-value 41)\r", settle=6.0)

    s.send(b"\x11", settle=3.0)  # Ctrl-Q: checkpoint and quit
    code = s.wait(timeout=60)
    gates["clean_exit"] = code == 0

    # --- second process: list sessions, then resume the same one -----------
    import subprocess

    listing = subprocess.run(
        [exe, "sessions", "--store", "sqlite"],
        cwd=dist, env=env, capture_output=True, text=True, timeout=120,
    )
    ids = [line.strip() for line in listing.stdout.splitlines() if line.strip()]
    gates["sessions_listed"] = len(ids) >= 1
    target = ids[0] if ids else None

    # charm redraws incrementally, so the header's label and value are not
    # guaranteed to be contiguous in the byte stream.  Assert on the id the
    # store actually recorded instead of on adjacency in the rendering.
    gates["header_has_session"] = bool(target) and target[:8] in first

    if target:
        s2 = Session([exe, "tui", target, "--store", "sqlite"], dist, env)
        s2.pump(14.0)
        resumed = s2.text()
        gates["resume_starts"] = "bbagent" in resumed
        gates["resume_same_session"] = target[:8] in resumed
        gates["resume_capabilities"] = "project/read" in resumed
        gates["resume_capabilities_a2"] = (
            "project/list" in resumed and "project/search" in resumed
            and "project/edit" in resumed
        )
        # Prove the operator definition survived into the rebuilt Context.
        s2.send(b"\x14", settle=1.5)  # Ctrl-T: operator repl mode
        s2.send(b'(str "RESUMED-" (+ native-operator-value 1))\r', settle=5.0)

        # The agent-authored helper is computational state too: it must be
        # reconstructed by replay exactly as a def is.
        s2.send(b'(str "HELPER-" (first (names (project/list "."))))\r',
                settle=6.0)

        s2.send(b"\x11", settle=3.0)
        gates["resume_clean_exit"] = s2.wait(timeout=60) == 0
    else:
        gates["resume_starts"] = False

    # --- third process: the write path, in a session of its own -------------
    # Every form is one line: a real newline inside a byte literal is an Enter
    # keypress, which submits a half-typed form and corrupts everything after
    # it.
    s3 = Session([exe, "tui", "--project", project_root], dist, env)
    s3.pump(12.0)
    s3.send(b"\x14", settle=1.5)  # Ctrl-T: operator repl mode
    s3.send(b'(project/edit {:path "native-proof.txt" :base :absent '
            b':content "one"})\r', settle=6.0)
    s3.send(b'(def before (project/stat "native-proof.txt"))\r', settle=5.0)
    s3.send(b'(str "STAT-" (:kind before) "-" (subs (:digest before) 0 10))\r',
            settle=5.0)
    s3.send(b'(def applied (project/edit {:path "native-proof.txt" '
            b':base {:digest (:digest before)} :content "twotwo"}))\r',
            settle=6.0)
    s3.send(b'(str "EDIT-" (:bytes applied))\r', settle=5.0)
    # `before` is now stale: the edit above consumed it.
    s3.send(b'(project/edit {:path "native-proof.txt" '
            b':base {:digest (:digest before)} :content "clobbered"})\r',
            settle=6.0)
    s3.send(b'(str "KEPT-" (project/read "native-proof.txt"))\r', settle=6.0)
    s3.send(b"\x11", settle=3.0)
    gates["write_session_clean_exit"] = s3.wait(timeout=60) == 0

    # --- fourth process: resume the session that changed the project --------
    # A2's blocking defect was that this was impossible: replay re-executed
    # the edit against a world it had already changed, version anchoring
    # refused it, and recovery refused the session. Recovery now substitutes
    # the operation's recorded receipt, so the binding comes back and the
    # write is not issued again.
    after_write = subprocess.run(
        [exe, "sessions", "--store", "sqlite"],
        cwd=dist, env=env, capture_output=True, text=True, timeout=120,
    )
    write_ids = [line.strip() for line in after_write.stdout.splitlines()
                 if line.strip() and line.strip() not in ids]
    gates["write_session_listed"] = len(write_ids) == 1
    if write_ids:
        edited_before_resume = open(
            os.path.join(project_root, "native-proof.txt")).read()
        s4 = Session([exe, "tui", write_ids[0], "--store", "sqlite"], dist, env)
        s4.pump(14.0)
        gates["resume_after_edit_starts"] = "bbagent" in s4.text()
        s4.send(b"\x14", settle=1.5)  # Ctrl-T: operator repl mode
        # `applied` is the value the edit returned. Its reconstruction is the
        # whole point: the session resumes holding what it computed, without
        # the computation being performed against the project a second time.
        s4.send(b'(str "REPLAYED-" (:bytes applied))\r', settle=6.0)
        s4.send(b'(str "UNCHANGED-" (project/read "native-proof.txt"))\r',
                settle=6.0)
        s4.send(b"\x11", settle=3.0)
        gates["resume_after_edit_clean_exit"] = s4.wait(timeout=60) == 0
        gates["resume_after_edit_wrote_nothing"] = (
            edited_before_resume
            == open(os.path.join(project_root, "native-proof.txt")).read()
        )
        ids = ids + write_ids
    else:
        gates["resume_after_edit_starts"] = False

    # Semantic gates read the journal, not the screen.
    #
    # The screen is the wrong instrument for them. charm redraws incrementally:
    # once the REPL pane is full, a value line is rewritten in place rather
    # than emitted contiguously, so a marker plainly visible to a person never
    # appears in the byte stream. Asserting on the durable journal proves what
    # the bounded context actually computed, which is what these gates were
    # ever trying to say; the screen gates above prove the interface renders,
    # accepts input, and exits, which is what a terminal can attest.
    gates.update(journal_gates(state_root, ids))
    return gates, ids


def journal_gates(state_root, ids):
    """What the bounded context actually did, from the durable journal."""
    import sqlite3
    import glob

    results = {}
    for path in glob.glob(os.path.join(state_root, "**", "*"), recursive=True):
        if not os.path.isfile(path):
            continue
        try:
            connection = sqlite3.connect(f"file:{path}?mode=ro", uri=True)
            rows = connection.execute(
                "select payload from event order by session_id, seq"
            ).fetchall()
            connection.close()
        except Exception:
            continue
        for (blob,) in rows:
            text = blob if isinstance(blob, str) else blob.decode("utf-8", "replace")
            results.setdefault("all", []).append(text)

    blob = "\n".join(results.get("all", []))
    return {
        "journal_eval": '"EVAL-3"' in blob,
        "journal_project_list": '"LIST-1"' in blob,
        "journal_project_search": '"SEARCH-1"' in blob,
        "journal_composition": '"COMPOSE-README.md"' in blob,
        "journal_lazy_result": '"LAZY-README.md"' in blob,
        "journal_stat": '"STAT-:file-sha256:' in blob,
        "journal_edit_applies": '"EDIT-6"' in blob,
        "journal_edit_conflict_refused": "conflict: file changed since it was read" in blob,
        "journal_conflict_kept_content": '"KEPT-twotwo"' in blob,
        "journal_operator_state_survives_resume": '"RESUMED-42"' in blob,
        "journal_helper_survives_resume": '"HELPER-README.md"' in blob,
        # A2's blocking defect, now its property: the edit's own result comes
        # back on resume, and the file it wrote is not written again.
        "journal_edit_result_survives_resume": '"REPLAYED-6"' in blob,
        "journal_edit_not_reapplied": '"UNCHANGED-twotwo"' in blob,
        # Payloads are stored as the canonical tagged tree, not as EDN maps,
        # so the claim is spelled the way the store actually spells it.
        "journal_replay_exact": (
            '[[:keyword nil "exact?"] [:boolean true]]' in blob
        ),
    }


if __name__ == "__main__":
    dist, state_root, project_root = sys.argv[1], sys.argv[2], sys.argv[3]
    gates, ids = run(dist, state_root, project_root)
    print("=== sessions ===")
    for i in ids:
        print(" ", i)
    print("=== gates ===")
    ok = True
    for name, passed in gates.items():
        print(f"{name:24} {'PASS' if passed else 'FAIL'}")
        ok = ok and bool(passed)
    print("RESULT:", "PASS" if ok else "FAIL")
    sys.exit(0 if ok else 1)
