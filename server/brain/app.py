"""The ProkNet Network Brain, pilot server. Standard library HTTP + SQLite; `cryptography` for the signatures.

    python -m brain.app --host 127.0.0.1 --port 8080 --db brain.db

Two routes:
    GET  /health      -> plain text: ok, version, counts (deployment diagnostics)
    POST /v1/sync     -> body: a signed prok-sync/1 upload; response: a prok-sync/1 download

Put it behind an HTTPS reverse proxy (Caddy, nginx). It never listens for
anything else. Cleanup runs on a timer; every TTL is deterministic and tested
with an injected clock.
"""
import argparse
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import protocol
from .db import Brain

MAX_BODY = 256 * 1024
RATE_WINDOW_MS = 60_000
RATE_MAX_PER_NODE = 30


class State:
    def __init__(self, db_path: str):
        self.brain = Brain(db_path)
        self.lock = threading.Lock()
        self.rate = {}          # node_id -> [timestamps]
        self.started = int(time.time() * 1000)
        self.syncs = 0
        self.rejected = 0

    def allow(self, node_id: str, now: int) -> bool:
        with self.lock:
            hits = [t for t in self.rate.get(node_id, []) if now - t < RATE_WINDOW_MS]
            if len(hits) >= RATE_MAX_PER_NODE:
                self.rate[node_id] = hits
                return False
            hits.append(now)
            self.rate[node_id] = hits
            return True


STATE: State = None


class Handler(BaseHTTPRequestHandler):
    server_version = "ProkNetBrain/0.13"

    def _send(self, code: int, text: str):
        data = text.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "text/plain; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("X-Prok-Protocol", "prok-sync/%d" % protocol.VERSION)
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path == "/health":
            c = STATE.brain.counts()
            lines = ["ok", "protocol prok-sync/%d" % protocol.VERSION, "uptime_s %d" % ((int(time.time() * 1000) - STATE.started) // 1000),
                     "syncs %d" % STATE.syncs, "rejected %d" % STATE.rejected] + ["%s %d" % (k, v) for k, v in c.items()]
            self._send(200, "\n".join(lines) + "\n")
        else:
            self._send(404, "not found\n")

    def do_POST(self):
        if self.path != "/v1/sync":
            self._send(404, "not found\n")
            return
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length <= 0 or length > MAX_BODY:
            self._send(413, "body too large or empty\n")
            return
        body = self.rfile.read(length).decode("utf-8", errors="replace")
        now = int(time.time() * 1000)
        try:
            up = protocol.parse_upload(body, now)
        except protocol.ProtocolError as e:
            STATE.rejected += 1
            self._send(400, "rejected: %s\n" % e)
            return
        if not STATE.allow(up.node_id, now):
            self._send(429, "too many syncs\n")
            return
        with STATE.lock:
            d = STATE.brain.sync(up, now)
            STATE.syncs += 1
        self._send(200, protocol.render_download(d["server_time"], d["cells"], d["requests"], d["jobs"], d["statuses"], d["advice"]))

    def log_message(self, fmt, *args):
        print("%s %s" % (self.address_string(), fmt % args), flush=True)


def cleanup_loop(state: State, every_s: int):
    while True:
        time.sleep(every_s)
        try:
            with state.lock:
                n = state.brain.cleanup()
            if any(n.values()):
                print("cleanup: %s" % n, flush=True)
        except Exception as e:  # never let the timer die
            print("cleanup failed: %s" % e, flush=True)


def main():
    global STATE
    ap = argparse.ArgumentParser(description="ProkNet Network Brain")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--db", default="brain.db")
    ap.add_argument("--cleanup-every", type=int, default=60, help="seconds between TTL sweeps")
    a = ap.parse_args()
    STATE = State(a.db)
    threading.Thread(target=cleanup_loop, args=(STATE, a.cleanup_every), daemon=True).start()
    srv = ThreadingHTTPServer((a.host, a.port), Handler)
    print("ProkNet Network Brain listening on http://%s:%d (db %s)" % (a.host, a.port, a.db), flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
