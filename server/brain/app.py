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
from . import paybox
from . import protocol
from . import ruleconfig
from . import settlement
from . import signed_request
from .db import Brain

MAX_BODY = 256 * 1024
#: How long an obligation may wait to be paid.
SETTLEMENT_TTL_MS = 30 * 24 * 3600 * 1000

#: A changed destination waits before it is used, so a stolen phone cannot redirect
#: payments instantly. Must match DestinationClaim.CHANGE_COOLING_MS on the phones.
DESTINATION_COOLING_MS = 10 * 60 * 1000

#: The public key phones pin for receipt-parser rules. DELIBERATELY NOT the server's own
#: transport identity: a compromised Brain may then withhold a config but never forge one.
#: Empty means this deployment publishes no rules, and the built-in ones keep working.
CONFIG_PUBLIC_KEY = ""
RATE_WINDOW_MS = 60_000
RATE_MAX_PER_NODE = 30


class State:
    def __init__(self, db_path: str):
        self.brain = Brain(db_path)
        self.settlements = settlement.Settlements(db_path)
        self.pay = paybox.PayBox(db_path)
        self.rules = ruleconfig.RuleStore(self.pay.db, CONFIG_PUBLIC_KEY)
        self.lock = threading.Lock()
        self.rate = {}          # node_id -> [timestamps]
        self.started = int(time.time() * 1000)
        # No operator signing secret ships in this repository, so no webhook can be
        # verified here yet and none may therefore confirm a payment. Set it from the
        # environment in production.
        self.webhook_secret = ""
        # v0.16.2: replay protection that survives a restart. In memory it vanished when
        # the process did, which made every recent signed request replayable again.
        self.nonces = signed_request.Nonces(self.pay.db)
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

    def _query(self, key: str) -> str:
        if "?" not in self.path:
            return ""
        for part in self.path.split("?", 1)[1].split("&"):
            if part.startswith(key + "="):
                from urllib.parse import unquote
                return unquote(part[len(key) + 1:])
        return ""

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

    def _who(self):
        """The verified identity behind a GET, or None.

        v0.16.2: everything that returns somebody's money, debts, payments or device risk
        must prove who is asking. A GET has no body, so the signature covers the empty
        body plus the method and path.
        """
        try:
            with STATE.lock:
                return signed_request.verify(self.headers, b"", STATE.nonces,
                                             method="GET", path=self.path.split("?")[0])
        except signed_request.AuthError:
            return None

    def do_GET(self):
        # ---- v0.16.2: private payment state, authenticated and authorised ----
        if self.path.startswith("/v1/pay/"):
            who = self._who()
            if who is None:
                self._json(401, {"error": "this endpoint requires a signed request"})
                return
            now = int(time.time() * 1000)
            try:
                with STATE.lock:
                    if self.path.startswith("/v1/pay/destination"):
                        seller = self._query("seller")
                        rail = self._query("rail") or "MTN_MOMO"
                        # only somebody who actually owes this seller may ask where it is paid
                        if not STATE.settlements.db.execute(
                                "SELECT 1 FROM settlements WHERE buyer_id=? AND seller_id=? LIMIT 1",
                                (who, seller)).fetchone() and who != seller:
                            self._json(403, {"error": "you have no obligation to this seller"})
                            return
                        d = STATE.pay.destination_for_buyer(seller, rail, now, DESTINATION_COOLING_MS)
                        self._json(200, {"destination": d})
                        return
                    if self.path.startswith("/v1/pay/expectations"):
                        # the seller's pending inbox; only its own
                        self._json(200, {"expectations": STATE.pay.pending_expectations(who, now)})
                        return
                    if self.path.startswith("/v1/pay/receipts"):
                        self._json(200, {"receipts": STATE.pay.pending_receipts(who)})
                        return
                    if self.path.startswith("/v1/pay/reply"):
                        r = STATE.pay.reply_for(self._query("payment"), who)
                        self._json(200 if r else 404, r or {"error": "unknown expectation"})
                        return
                    if self.path.startswith("/v1/pay/rules"):
                        self._json(200, {"rules": STATE.rules.current()})
                        return
            except paybox.PayError as e:
                self._json(400, {"error": str(e)})
                return
            self._json(404, {"error": "not found"})
            return

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
            # v0.16.2: a wallet is somebody's money. It used to be readable by anybody who
            # knew a node id, which is not a secret. Now you may read only your own.
            who = self._who()
            if who is None:
                self._json(401, {"error": "this endpoint requires a signed request"})
                return
            node = self._query("node") or who
            if node != who:
                self._json(403, {"error": "you may only read your own wallet"})
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
        if (self.path.startswith("/v1/settlements") or self.path.startswith("/v1/payments")
                or self.path.startswith("/v1/pay/") or self.path.startswith("/v1/device/")):
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
                    submitter = signed_request.verify(self.headers, raw, STATE.nonces, now,
                                                      method="POST", path=self.path.split("?")[0])
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
                # v0.15.3: the signed request proves who sent it. Believing buyer_id from
                # the JSON body would let any valid Prok identity start a payment in
                # somebody else's name, so the two must be the same person.
                claimed_buyer = body.get("buyer_id", "")
                if claimed_buyer != submitter:
                    self._json(403, {"error": "a payment may only be initiated by its own buyer"})
                    return
                # one real operator transfer, allocated across the obligations it settles
                allocations = [(a.get("settlement_id", ""), int(a.get("allocated", 0)))
                               for a in body.get("allocations", [])]
                self._json(200, STATE.settlements.open_payment(
                    body.get("rail", "NONE"), body.get("operator_ref", ""),
                    submitter, body.get("seller_id", ""),
                    int(body.get("amount", 0)), allocations, now, body.get("destination", "")))
                return

            # ---- v0.16.2: the Brain as a carrier for signed payment objects ----
            if self.path.startswith("/v1/pay/") or self.path.startswith("/v1/device/"):
                try:
                    self._json(200, self._pay_post(body, submitter, now))
                except paybox.PayError as e:
                    self._json(400, {"error": str(e)})
                except ruleconfig.ConfigError as e:
                    self._json(400, {"error": str(e)})
                return

            if self.path == "/v1/payments/webhook":
                # verified=False until an operator signing secret exists. A webhook that
                # cannot be verified is recorded for the audit trail and changes nothing.
                verified = bool(STATE.webhook_secret) and _webhook_signature_ok(
                    self.headers.get("X-Prok-Signature", ""), body, STATE.webhook_secret)
                # v0.16.2: an UNVERIFIED webhook must change nothing at all. It used to
                # push the payment and every obligation it touched into SECURITY_REVIEW,
                # which meant anybody who could reach this endpoint could freeze a real
                # seller's money by posting nonsense. It is now audited and ignored.
                if not verified:
                    STATE.settlements.note_unverified_webhook(body, now)
                    self._json(202, {"ok": False, "error": "signature not verified"})
                    return
                pid = body.get("payment_id", "")
                if not pid and body.get("rail") and body.get("reference"):
                    pid = STATE.settlements.payment_id(body["rail"], body["reference"])
                out = STATE.settlements.confirm_payment(pid, int(body.get("amount", 0)), now, True)
                self._json(200 if out.get("ok") else 202, out)
                return
        self._json(404, {"error": "not found"})

    def _pay_post(self, body: dict, submitter: str, now: int) -> dict:
        """Carry and validate; never author.

        The server verifies every signature and refuses anything inconsistent, but the
        seller stays the final authority on ACCEPTED versus BUSY, because only the seller
        can see every buyer's live window at once.
        """
        p = self.path
        if p == "/v1/pay/destination":
            return STATE.pay.put_destination(body.get("line", ""), body.get("seller_pub", ""),
                                             submitter, now)
        if p == "/v1/pay/expectation":
            outstanding = {}
            for sid in body.get("settlement_ids", []) or []:
                row = STATE.settlements.get(sid)
                if row is not None:
                    outstanding[sid] = STATE.settlements.remaining(sid)
            return STATE.pay.put_expectation(body.get("line", ""), body.get("buyer_pub", ""),
                                             submitter, now, DESTINATION_COOLING_MS,
                                             outstanding or None)
        if p == "/v1/pay/reply":
            return STATE.pay.put_reply(body.get("payment_id", ""), body.get("reply", ""), submitter, now)
        if p == "/v1/pay/receipt":
            return STATE.pay.put_receipt(body.get("line", ""), body.get("seller_pub", ""), submitter, now)
        if p == "/v1/pay/receipt/ack":
            return STATE.pay.ack_receipt(body.get("payment_id", ""), submitter)
        if p == "/v1/pay/end":
            return STATE.pay.end_expectation(body.get("payment_id", ""), submitter, now)
        if p == "/v1/pay/rules":
            return STATE.rules.publish(int(body.get("version", 0)), int(body.get("validFrom", 0)),
                                       body.get("terms", {}), body.get("signature", ""), now)
        if p == "/v1/device/risk":
            # the pseudonym is a domain-separated hash; no hardware identifier reaches here
            pseudonym = str(body.get("pseudonym", ""))[:64]
            STATE.pay.note_device(pseudonym, submitter, now)
            # derived from OUR OWN verified settlement state; a client saying it owes
            # nothing changes nothing
            def unresolved_for(identity):
                r = STATE.settlements.db.execute(
                    "SELECT COALESCE(SUM(gross), 0) s FROM settlements WHERE buyer_id=? AND status IN (?,?,?)",
                    (identity, settlement.PENDING, settlement.PAYMENT_INITIATED, settlement.PAYMENT_SEEN)).fetchone()
                return int(r["s"])
            return STATE.pay.device_risk(pseudonym, submitter, unresolved_for)
        raise paybox.PayError("not found")

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
                now_ms = int(time.time() * 1000)
                n["settlements_expired"] = state.settlements.expire(now_ms)
                n["payment_windows_swept"] = state.pay.sweep(now_ms)
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
