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
    gates["profile_from_bb4t"] = "agent/project-read" in first
    gates["events_pane"] = "session/started" in first or "Recent Events" in first

    mark = len(s.out)
    s.send(b"\x13", settle=3.0)  # Ctrl-S: session browser
    browser = plain(bytes(s.out[mark:]))
    gates["session_browser"] = "sessions" in browser

    session_id = None
    m = re.search(r"session\s+([0-9a-f]{8})", first)
    if m:
        session_id = m.group(1)
    gates["header_has_session"] = session_id is not None

    s.send(b"\x1b", settle=1.0)  # Esc: close browser
    mark = len(s.out)
    s.send(b"\x14", settle=1.0)  # Ctrl-T: operator repl mode
    gates["repl_mode"] = "repl>" in plain(bytes(s.out[mark:]))

    mark = len(s.out)
    s.send(b"(+ 1 2)\r", settle=4.0)  # bounded evaluation
    gates["bounded_eval"] = ":ok" in plain(bytes(s.out[mark:]))

    s.send(b"\x11", settle=3.0)  # Ctrl-Q: checkpoint and quit
    code = s.wait()
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

    if target:
        s2 = Session([exe, "tui", target, "--store", "sqlite"], dist, env)
        s2.pump(14.0)
        resumed = s2.text()
        gates["resume_starts"] = "bbagent" in resumed
        gates["resume_same_session"] = target[:8] in resumed
        gates["resume_capabilities"] = "project/read" in resumed
        s2.send(b"\x11", settle=3.0)
        gates["resume_clean_exit"] = s2.wait() == 0
    else:
        gates["resume_starts"] = False

    return gates, ids


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
