#!/usr/bin/env python3
"""
Switchboard runner: lets the Switchboard app's model run commands and edit files on THIS machine.

Run it in Termux (on the phone) or on a computer:
    python3 runner.py                  # listens on 127.0.0.1:8765, prints a token
    python3 runner.py --host 0.0.0.0   # reachable from other devices on your network (only on networks you trust)

Paste the printed URL and token into Switchboard > Settings > Tools.
Anything the model runs here has the same permissions as the user who started this script.
Stop it with Ctrl+C when you're done.
"""
import argparse
import base64
import json
import os
import platform
import secrets
import shutil
import signal
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

MAX_OUTPUT = 20_000      # characters of command output returned (the tail is kept)
MAX_READ = 100_000       # characters returned by /read
MAX_TIMEOUT = 3600       # seconds

ARGS = None
TOKEN = ""
WORKSPACE = ""


def resolve(path):
    """Relative paths are taken from the workspace; ~ is expanded."""
    path = os.path.expanduser(path or ".")
    return path if os.path.isabs(path) else os.path.normpath(os.path.join(WORKSPACE, path))


def shell_cmd(command):
    sh = shutil.which("bash") or shutil.which("sh")
    if os.name == "nt" or not sh:
        return command, True
    return [sh, "-lc", command], False


def run(command, cwd, timeout):
    args, use_shell = shell_cmd(command)
    os.makedirs(cwd, exist_ok=True)
    proc = subprocess.Popen(
        args, shell=use_shell, cwd=cwd, stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        start_new_session=(os.name != "nt"),
        env={**os.environ, "CI": "1", "DEBIAN_FRONTEND": "noninteractive"},
    )
    timed_out = False
    try:
        out, _ = proc.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        timed_out = True
        try:
            if os.name != "nt":
                os.killpg(proc.pid, signal.SIGKILL)
            else:
                proc.kill()
        except OSError:
            pass
        out, _ = proc.communicate()
    text = out.decode("utf-8", "replace")
    truncated = len(text) > MAX_OUTPUT
    return {
        "exit_code": None if timed_out else proc.returncode,
        "timed_out": timed_out,
        "truncated": truncated,
        "output": text[-MAX_OUTPUT:],
    }


def info():
    return {
        "runner": "switchboard",
        "version": 2,
        "terminals": os.name != "nt",
        "os": f"{platform.system()} {platform.release()} ({platform.machine()})",
        "termux": "com.termux" in os.environ.get("PREFIX", ""),
        "python": sys.version.split()[0],
        "workspace": WORKSPACE,
        "shell": shutil.which("bash") or shutil.which("sh") or "cmd",
    }


# ---------- interactive terminals (pseudo-terminals) ----------

TERMS = {}
TERM_BUFFER = 1_000_000   # bytes of output kept per terminal for replay
MAX_TERMS = 8


class Term:
    """A program running in a pseudo-terminal, so prompts, colors and full-screen programs work."""

    def __init__(self, tid, command, cwd, cols, rows):
        import pty
        self.id, self.command = tid, command or "shell"
        self.master, slave = pty.openpty()
        self.resize(cols, rows)
        sh = shutil.which("bash") or shutil.which("sh") or "/bin/sh"
        args = [sh, "-l"] if not command else [sh, "-lc", command]
        env = {**os.environ, "TERM": "xterm-256color", "COLORTERM": "truecolor"}

        def child_setup():  # new session with the pty as controlling terminal, so Ctrl+C and job control work
            import fcntl, termios
            os.setsid()
            fcntl.ioctl(0, termios.TIOCSCTTY, 0)

        os.makedirs(cwd, exist_ok=True)
        self.proc = subprocess.Popen(args, stdin=slave, stdout=slave, stderr=slave, cwd=cwd, env=env,
                                     preexec_fn=child_setup, close_fds=True)
        os.close(slave)
        self.buf, self.base, self.exit = bytearray(), 0, None
        self.cond = threading.Condition()
        self.started = time.time()
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        while True:
            try:
                data = os.read(self.master, 65536)
            except OSError:
                data = b""
            if not data:
                break
            with self.cond:
                self.buf += data
                if len(self.buf) > TERM_BUFFER:
                    drop = len(self.buf) - TERM_BUFFER
                    del self.buf[:drop]
                    self.base += drop
                self.cond.notify_all()
        code = self.proc.wait()
        with self.cond:
            self.exit = code
            self.cond.notify_all()

    def read(self, since, wait):
        """Output from byte offset `since` (waiting up to `wait` seconds for more), the new offset, and the exit code."""
        with self.cond:
            if since >= self.base + len(self.buf) and self.exit is None and wait > 0:
                self.cond.wait(wait)
            start = max(since, self.base)
            return bytes(self.buf[start - self.base:]), self.base + len(self.buf), self.exit

    def write(self, data):
        os.write(self.master, data)

    def resize(self, cols, rows):
        import fcntl, struct, termios
        fcntl.ioctl(self.master, termios.TIOCSWINSZ, struct.pack("HHHH", max(rows, 2), max(cols, 10), 0, 0))

    def kill(self):
        for sig in (signal.SIGHUP, signal.SIGKILL):
            try:
                os.killpg(self.proc.pid, sig)
            except OSError:
                pass
            if self.proc.poll() is not None:
                break
            time.sleep(0.5)

    def info(self):
        return {"id": self.id, "command": self.command, "alive": self.exit is None, "exit_code": self.exit, "started": self.started}


class Handler(BaseHTTPRequestHandler):
    server_version = "SwitchboardRunner/1"

    def log_message(self, fmt, *a):
        sys.stderr.write("[runner] %s\n" % (fmt % a))

    def _send(self, code, obj=None, raw=None, ctype="application/json"):
        body = raw if raw is not None else json.dumps(obj).encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self):
        if secrets.compare_digest(self.headers.get("Authorization", ""), f"Bearer {TOKEN}"):
            return True
        self._send(401, {"error": "bad or missing token"})
        return False

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return json.loads(self.rfile.read(n) or b"{}")

    def do_GET(self):
        if not self._authorized():
            return
        url = urlparse(self.path)
        if url.path == "/info":
            return self._send(200, info())
        if url.path == "/term/read":
            q = parse_qs(url.query)
            t = TERMS.get(q.get("id", [""])[0])
            if not t:
                return self._send(404, {"error": "no such terminal"})
            data, offset, code = t.read(int(q.get("since", ["0"])[0]), min(float(q.get("wait", ["0"])[0]), 25))
            return self._send(200, {"output": base64.b64encode(data).decode(), "offset": offset, "alive": code is None, "exit_code": code})
        if url.path == "/term/list":
            return self._send(200, {"terminals": [t.info() for t in TERMS.values()]})
        if url.path == "/download":
            path = resolve(parse_qs(url.query).get("path", [""])[0])
            if not os.path.isfile(path):
                return self._send(404, {"error": f"no such file: {path}"})
            with open(path, "rb") as f:
                return self._send(200, raw=f.read(), ctype="application/octet-stream")
        self._send(404, {"error": "unknown endpoint"})

    def do_POST(self):
        if not self._authorized():
            return
        try:
            req = self._body()
            path = urlparse(self.path).path
            if path == "/exec":
                command = req.get("command") or ""
                if not command.strip():
                    return self._send(400, {"error": "command is required"})
                timeout = max(1, min(int(req.get("timeout_sec") or 600), MAX_TIMEOUT))
                print(f"[runner] $ {command}", file=sys.stderr, flush=True)
                return self._send(200, run(command, resolve(req.get("cwd")), timeout))
            if path == "/term/start":
                if os.name == "nt":
                    return self._send(400, {"error": "Interactive terminals need Linux, macOS or Termux (pseudo-terminals aren't available on Windows)."})
                for k in [k for k, t in TERMS.items() if t.exit is not None and time.time() - t.started > 3600]:
                    TERMS.pop(k)
                if sum(1 for t in TERMS.values() if t.exit is None) >= MAX_TERMS:
                    return self._send(400, {"error": f"Too many terminals running (max {MAX_TERMS}); stop one first."})
                tid = secrets.token_hex(4)
                TERMS[tid] = Term(tid, req.get("command") or "", resolve(req.get("cwd")), int(req.get("cols") or 80), int(req.get("rows") or 24))
                return self._send(200, TERMS[tid].info())
            if path in ("/term/write", "/term/resize", "/term/kill"):
                t = TERMS.get(req.get("id") or "")
                if not t:
                    return self._send(404, {"error": "no such terminal"})
                if path == "/term/write":
                    t.write(base64.b64decode(req.get("data") or ""))
                elif path == "/term/resize":
                    t.resize(int(req.get("cols") or 80), int(req.get("rows") or 24))
                else:
                    t.kill()
                return self._send(200, t.info())
            if path == "/write":
                target = resolve(req.get("path"))
                os.makedirs(os.path.dirname(target) or ".", exist_ok=True)
                raw = req.get("content") or ""
                data = base64.b64decode(raw) if req.get("encoding") == "base64" else raw.encode()
                with open(target, "wb") as f:
                    f.write(data)
                return self._send(200, {"ok": True, "path": target, "bytes": len(data)})
            if path == "/read":
                target = resolve(req.get("path"))
                if not os.path.isfile(target):
                    return self._send(404, {"error": f"no such file: {target}"})
                with open(target, "rb") as f:
                    text = f.read(MAX_READ + 1).decode("utf-8", "replace")
                return self._send(200, {"path": target, "content": text[:MAX_READ], "truncated": len(text) > MAX_READ})
            self._send(404, {"error": "unknown endpoint"})
        except Exception as e:  # report, don't crash the server
            self._send(500, {"error": f"{type(e).__name__}: {e}"})


def main():
    global ARGS, TOKEN, WORKSPACE
    p = argparse.ArgumentParser(description="Switchboard command runner")
    p.add_argument("--host", default="127.0.0.1")
    p.add_argument("--port", type=int, default=8765)
    p.add_argument("--token", default=os.environ.get("SWITCHBOARD_TOKEN") or secrets.token_urlsafe(18))
    p.add_argument("--workspace", default=os.path.expanduser("~/switchboard-workspace"))
    ARGS = p.parse_args()
    TOKEN, WORKSPACE = ARGS.token, os.path.abspath(ARGS.workspace)
    os.makedirs(WORKSPACE, exist_ok=True)
    srv = ThreadingHTTPServer((ARGS.host, ARGS.port), Handler)
    shown = "127.0.0.1" if ARGS.host in ("127.0.0.1", "0.0.0.0") else ARGS.host
    print(f"Switchboard runner ready\n  URL:       http://{shown}:{ARGS.port}\n  Token:     {TOKEN}\n  Workspace: {WORKSPACE}", flush=True)
    if ARGS.host == "0.0.0.0":
        print("  Listening on all interfaces: use this machine's LAN IP in the app, and only on a network you trust.", flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
