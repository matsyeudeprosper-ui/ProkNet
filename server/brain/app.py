"""The ProkNet Network Brain, pilot server. Standard library HTTP + SQLite; `cryptography` for the signatures.

    python -m brain.app --host 127.0.0.1 --port 8080 --db brain.db

Routes:
    GET  /health              -> plain text: ok, version, counts (deployment diagnostics)
    POST /v1/sync             -> body: a signed prok-sync/1 upload; response: a download
    POST /v1/settlements      -> a phone reports what a finished session owed
    GET  /v1/settlements/{id} -> one obligation and its audit trail
    POST /v1/payments/initiate-> record that a payment was started (starting is not paying)
    POST /v1/payments/webhook -> a rail reports an outcome; only a VERIFIED one may confirm
    GET  /v1/wallet?node=     -> what one node owes and is owed

The settlement routes are JSON. Nothing a phone says is taken as payment: only a webhook
whose signature this server verified may move an obligation to CONFIRMED. See
settlement.py for the three rules the service exists to enforce.

Put it behind an HTTPS reverse proxy (Caddy, nginx). It never listens for
anything else. Cleanup runs on a timer; every TTL is deterministic and tested
with an injected clock.
"""
import argparse
import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import evidence
from . import protocol
from . import settlement
from . import signed_request
from .db import Brain

MAX_BODY = 256 * 1024
#: How long an obligation may wait to be paid.
SETTLEMENT_TTL_MS = 30 * 24 * 3600 * 1000
RATE_WINDOW_MS = 60_000
RATE_MAX_PER_NODE = 30


class State:
    def __init__(self, db_path: str):
        self.brain = Brain(db_path)
        self.settlements = settlement.Settlements(db_path)
        self.lock = threading.Lock()
        self.rate = {}          # node_id -> [timestamps]
        self.started = int(time.time() * 1000)
        # No operator signing secret ships in this repository, so no webhook can be
        # verified here yet and none may therefore confirm a payment. Set it from the
        # environment in production.
        self.webhook_secret = ""
        # v0.15.1: replay protection for signed settlement submissions
        self.nonces = signed_request.Nonces()
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

    def _json(self, code: int, obj):
        data = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _body(self):
        """Returns (parsed, raw_bytes). The raw bytes are what the signature covers."""
        length = int(self.headers.get("Content-Length", "0") or "0")
        if length <= 0 or length > MAX_BODY:
            return None, b""
        raw = self.rfile.read(length)
        try:
            return json.loads(raw.decode("utf-8", errors="replace")), raw
        except Exception:
            return None, raw

    def do_GET(self):
        if self.path.startswith("/v1/settlements/"):
            sid = self.path[len("/v1/settlements/"):].split("?")[0]
            with STATE.lock:
                row = STATE.settlements.get(sid)
                trail = STATE.settlements.audit(sid)
            if row is None:
                self._json(404, {"error": "unknown settlement"})
            else:
                self._json(200, {"settlement": dict(row), "audit": trail})
            return
        if self.path.startswith("/v1/wallet"):
            node = ""
            if "?" in self.path:
                for part in self.path.split("?", 1)[1].split("&"):
                    if part.startswith("node="):
                        node = part[5:]
            if not node:
                self._json(400, {"error": "node required"})
                return
            with STATE.lock:
                self._json(200, STATE.settlements.wallet(node))
            return
        if self.path == "/health":
            c = STATE.brain.counts()
            lines = ["ok", "protocol prok-sync/%d" % protocol.VERSION, "uptime_s %d" % ((int(time.time() * 1000) - STATE.started) // 1000),
                     "syncs %d" % STATE.syncs, "rejected %d" % STATE.rejected] + ["%s %d" % (k, v) for k, v in c.items()]
            self._send(200, "\n".join(lines) + "\n")
        else:
            self._send(404, "not found\n")

    def do_POST(self):
        if self.path.startswith("/v1/settlements") or self.path.startswith("/v1/payments"):
            self._settlement_post()
            return
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

    def _settlement_post(self):
        body, raw = self._body()
        if body is None:
            self._json(400, {"error": "bad or oversized JSON body"})
            return
        now = int(time.time() * 1000)

        # v0.15.1: a webhook comes from an operator, not from a Prok identity, so it is
        # authenticated by the rail's own signature instead of ours.
        if self.path != "/v1/payments/webhook":
            try:
                with STATE.lock:
                    submitter = signed_request.verify(self.headers, raw, STATE.nonces, now)
            except signed_request.AuthError as e:
                self._json(401, {"error": str(e)})
                return
        else:
            submitter = ""

        with STATE.lock:
            if self.path == "/v1/settlements":
                # The phone submits EVIDENCE, not amounts. The server re-derives the
                # money and the settlement id from the signed contract and the signed
                # closing checkpoint, and refuses anything it cannot verify itself.
                try:
                    derived = evidence.verify(body, now, SETTLEMENT_TTL_MS)
                except evidence.EvidenceError as e:
                    self._json(400, {"error": str(e)})
                    return
                if protocol.node_id(body["submitter_pub"]) != submitter:
                    self._json(401, {"error": "the signing identity is not the submitter in the evidence"})
                    return
                self._json(200, STATE.settlements.record(derived, now))
                return

            if self.path == "/v1/payments/initiate":
                # one real operator transfer, allocated across the obligations it settles
                allocations = [(a.get("settlement_id", ""), int(a.get("allocated", 0)))
                               for a in body.get("allocations", [])]
                self._json(200, STATE.settlements.open_payment(
                    body.get("rail", "NONE"), body.get("operator_ref", ""),
                    body.get("buyer_id", ""), body.get("seller_id", ""),
                    int(body.get("amount", 0)), allocations, now, body.get("destination", "")))
                return

            if self.path == "/v1/payments/webhook":
                # verified=False until an operator signing secret exists. A webhook that
                # cannot be verified is recorded for the audit trail and changes nothing.
                verified = bool(STATE.webhook_secret) and _webhook_signature_ok(
                    self.headers.get("X-Prok-Signature", ""), body, STATE.webhook_secret)
                pid = body.get("payment_id", "")
                if not pid and body.get("rail") and body.get("reference"):
                    pid = STATE.settlements.payment_id(body["rail"], body["reference"])
                out = STATE.settlements.confirm_payment(pid, int(body.get("amount", 0)), now, verified)
                self._json(200 if out.get("ok") else 202, out)
                return
        self._json(404, {"error": "not found"})

    def log_message(self, fmt, *args):
        print("%s %s" % (self.address_string(), fmt % args), flush=True)


def _webhook_signature_ok(header: str, body: dict, secret: str) -> bool:
    """HMAC over the exact JSON body, as the operator signed it.

    Production integration point: the operator's documented signing scheme. Until that is
    configured this is never reached, because `webhook_secret` is empty and the caller
    short-circuits to unverified.
    """
    import hashlib
    import hmac
    expected = hmac.new(secret.encode("utf-8"),
                        json.dumps(body, sort_keys=True, separators=(",", ":")).encode("utf-8"),
                        hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, header.strip())


def cleanup_loop(state: State, every_s: int):
    while True:
        time.sleep(every_s)
        try:
            with state.lock:
                n = state.brain.cleanup()
                n["settlements_expired"] = state.settlements.expire(int(time.time() * 1000))
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
