#!/usr/bin/env python3
"""Drive a TUI binary in a real PTY: start, draw, key input, resize, clean exit."""
import os, pty, sys, time, select, signal, fcntl, termios, struct, subprocess

def set_size(fd, rows, cols):
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))

def run(argv, cwd=None):
    pid, fd = pty.fork()
    if pid == 0:
        if cwd: os.chdir(cwd)
        env = dict(os.environ, TERM="xterm-256color", LINES="24", COLUMNS="80")
        os.execvpe(argv[0], argv, env)
    set_size(fd, 24, 80)
    out = bytearray()
    def pump(seconds):
        end = time.time() + seconds
        while time.time() < end:
            r, _, _ = select.select([fd], [], [], 0.1)
            if r:
                try:
                    d = os.read(fd, 65536)
                except OSError:
                    return False
                if not d: return False
                out.extend(d)
        return True

    pump(6.0)                       # startup + first draw
    stage = {}
    stage["drew"] = b"SPIKE-READY" in out
    mark = len(out)

    os.write(fd, b"ab")             # key input
    pump(1.5)
    stage["keys"] = b'["a" "b"]' in out[mark:] or b'"b"' in out[mark:]
    mark = len(out)

    set_size(fd, 30, 100)           # resize
    os.kill(pid, signal.SIGWINCH)
    pump(2.0)
    stage["resize"] = b"[100 30]" in out[mark:]
    mark = len(out)

    os.write(fd, b"q")              # quit
    pump(3.0)
    stage["exit_line"] = b"SPIKE-EXIT-CLEAN" in out[mark:] or b"SPIKE-EXIT-CLEAN" in out

    deadline = time.time() + 10
    status = None
    while time.time() < deadline:
        wpid, st = os.waitpid(pid, os.WNOHANG)
        if wpid: status = st; break
        pump(0.3)
    if status is None:
        os.kill(pid, signal.SIGKILL); os.waitpid(pid, 0)
        stage["clean_exit"] = False
        code = "TIMEOUT"
    else:
        code = os.waitstatus_to_exitcode(status)
        stage["clean_exit"] = (code == 0)
    os.close(fd)
    return stage, code, bytes(out)

if __name__ == "__main__":
    cwd = sys.argv[1]; argv = sys.argv[2:]
    stage, code, out = run(argv, cwd)
    print("=== captured (tail) ===")
    sys.stdout.write(out[-1200:].decode("utf-8", "replace"))
    print("\n=== gates ===")
    for k in ["drew","keys","resize","exit_line","clean_exit"]:
        print(f"{k:12} {'PASS' if stage.get(k) else 'FAIL'}")
    print("exit code:", code)
    sys.exit(0 if all(stage.get(k) for k in ["drew","keys","resize","exit_line","clean_exit"]) else 1)
