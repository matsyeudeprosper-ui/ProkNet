"""The ProkNet Network Brain, pilot server. Standard library HTTP + SQLite; `cryptography` for the signatures.

    python -m brain.app --host 127.0.0.1 --port 8080 --db brain.db

Routes:
    GET  /health              -> plain text: ok, version, counts (deployment diagnostics)
    POST /v1/sync             -> body: a signed prok-sync/1 upload; response: a download
    POST /v1/settlements      -> a phone reports what a finished session owed
    GET  /v1/settlements/{id} -> one obligation and its audit trail (signed; parties only)
    POST /v1/payments/initiate-> record that a payment was started (starting is not paying)
    POST /v1/payments/webhook -> a rail reports an outcome; only a VERIFIED one may confirm
    GET  /v1/wallet?node=     -> what one node owes and is owed

v0.17.0 adds the live control plane under /v1/network/ - presence, demand, activation and
zone colour. It coordinates; it never carries anybody's Internet, and it never changes
signed money. See network.py.

The settlement routes are JSON. Nothing a phone says is taken as payment: only a webhook
whose signature this server verified may move an obligation to CONFIRMED. See
settlement.py for the three rules the service exists to enforce.

Put it behind an HTTPS reverse proxy (Caddy, nginx). It never listens for
anything else. Cleanup runs on a timer; every TTL is deterministic and tested
with an injected clock.
"""
import argparse
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import evidence
from . import network
from . import paybox
from . import protocol
from . import ruleconfig
from . import settlement
from . import signed_request
from .db import Brain

#: Reported by /health, so an operator can see which build is actually running.
VERSION = "0.17.0"

MAX_BODY = 256 * 1024
#: How long an obligation may wait to be paid.
SETTLEMENT_TTL_MS = 30 * 24 * 3600 * 1000

#: v0.17.0: how many control-plane requests one node may make per minute, per kind.
#: Presence is a heartbeat every thirty to sixty seconds and must never be throttled into
#: uselessness; creating a demand is rare and is where abuse would show up. Generous
#: enough that ordinary retry and recovery never trips it.
NETWORK_RATE = {"presence": 20, "demand": 10, "activation": 60, "read": 120}
NETWORK_RATE_WINDOW_MS = 60_000

#: A changed destination waits before it is used, so a stolen phone cannot redirect
#: payments instantly. Must match DestinationClaim.CHANGE_COOLING_MS on the phones.
DESTINATION_COOLING_MS = 10 * 60 * 1000

#: The public key phones pin for receipt-parser rules, key id 9410c707.
#:
#: DELIBERATELY NOT the server's own transport identity, and deliberately not any user
#: identity. A compromised Brain may withhold or delay a configuration - phones survive
#: that, because the built-in rules keep working - but it can never forge one.
#:
#: The matching PRIVATE key is not in this repository and must never be. It lives only on
#: the admin machine and is used only by `brain.publish_rules`; see docs/OPERATIONS.md.
#: This server holds the public half and nothing else.
#:
#: Must equal `ReceiptRules.PINNED_CONFIG_KEY` in the app, byte for byte.
CONFIG_PUBLIC_KEY = os.environ.get("PROK_CONFIG_PUBLIC_KEY", "").strip() or (
    "9d536299f0c879aa6025e37b37efff18d7262bf53b434f23dfce26181367cd64"
    "5450473b16377d9c53d7049c5dd6de7e2de78b88f745cff23a0e9bdb9533aa0c")
RATE_WINDOW_MS = 60_000
RATE_MAX_PER_NODE = 30


class State:
    def __init__(self, db_path: str):
        self.brain = Brain(db_path)
        self.settlements = settlement.Settlements(db_path)
        self.pay = paybox.PayBox(db_path)
        # v0.17.0: the control plane shares the Brain's own connection, because its tables
        # are part of the Brain's schema and its sweep runs on the Brain's timer.
        self.net = network.NetworkPlane(self.brain.db)
        self.rules = ruleconfig.RuleStore(self.pay.db, CONFIG_PUBLIC_KEY)
        self.lock = threading.Lock()
        self.rate = {}          # node_id -> [timestamps]
        self.netrate = {}       # (node_id, kind) -> [timestamps]
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

    def allow_network(self, node_id: str, kind: str, now: int) -> bool:
        """Per node, per kind of control-plane request.

        Separate from the /v1/sync limiter on purpose: a provider heartbeat and a buyer
        asking for Internet are different budgets, and one must never exhaust the other.
        """
        cap = NETWORK_RATE.get(kind, 30)
        with self.lock:
            key = (node_id, kind)
            hits = [t for t in self.netrate.get(key, []) if now - t < NETWORK_RATE_WINDOW_MS]
            if len(hits) >= cap:
                self.netrate[key] = hits
                return False
            hits.append(now)
            self.netrate[key] = hits
            return True

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

    def _network_get(self, who: str, now: int):
        """Reads. Each one answers about the caller and nobody else.

        There is deliberately no "list the providers near me": a consumer map needs a
        zone's colour, not the identities of the households in it.
        """
        p = self.path

        if p.startswith("/v1/network/presence/me"):
            row = STATE.net.presence(who)
            self._json(200, {"presence": None if row is None else {
                "zone": row.zone, "updatedAt": int(row.updated_at),
                "expiresAt": int(row.expires_at), "fresh": row.fresh(now),
                "sharingEnabled": bool(int(row.sharing_enabled)),
                "currentLoad": int(row.current_load), "maxBuyers": int(row.max_buyers)}})
            return

        if p.startswith("/v1/network/demand"):
            # a buyer reads its own, by id or simply "whatever I have live"
            wanted = self._query("id")
            d = STATE.net.demand(wanted) if wanted else STATE.net.active_demand_for(who)
            if d is None:
                self._json(404, {"error": "no such request"})
                return
            if d["buyer_id"] != who:
                self._json(403, {"error": "that request belongs to somebody else"})
                return
            act = STATE.net.live_activation_for(d["demand_id"])
            self._json(200, {"demand": {
                "demandId": d["demand_id"], "status": d["status"], "zone": d["zone"],
                "createdAt": int(d["created_at"]), "expiresAt": int(d["expires_at"]),
                "updatedAt": int(d["updated_at"]), "attempts": int(d["attempts"]),
                "requestedClass": d["requested_class"]},
                # the buyer learns that SOMEBODY is preparing, not who. A provider's
                # identity is not part of the buyer's screen.
                "activation": None if act is None else {
                    "activationId": act["activation_id"], "state": act["state"],
                    "expiresAt": int(act["expires_at"])}})
            return

        if p.startswith("/v1/network/jobs"):
            # a provider's own inbox, and only its own
            self._json(200, {"jobs": [{
                "activationId": j["activation_id"], "demandId": j["demand_id"],
                "zone": j["zone"], "state": j["state"],
                "createdAt": int(j["created_at"]), "expiresAt": int(j["expires_at"]),
            } for j in STATE.net.jobs_for_provider(who, now)]})
            return

        if p.startswith("/v1/network/coverage"):
            # zone colour only. Counts are omitted from the consumer answer because in a
            # thin zone "1 provider" is one identifiable household.
            zone = self._query("zone")
            if not zone:
                self._json(400, {"error": "name a zone"})
                return
            z = STATE.net.zone_status(zone, now)
            self._json(200, {"zone": z["zone"], "state": z["status"], "updatedAt": now})
            return

        if p.startswith("/v1/network/diagnostics"):
            # what /health used to leak, now behind a signature
            self._json(200, {"network": STATE.net.counts(),
                             "brain": STATE.brain.counts(),
                             "schema": STATE.brain.schema_version(),
                             "syncs": STATE.syncs, "rejected": STATE.rejected,
                             "uptimeS": (now - STATE.started) // 1000})
            return

        self._json(404, {"error": "not found"})

    def _network_post(self, body: dict, who: str, now: int) -> dict:
        """Writes. The verified identity is authoritative; the body never names the actor.

        A demand signed by A that says `buyerId: B` is A trying to act as B, so the field
        is not read at all rather than checked - there is nothing for it to disagree with.
        """
        p = self.path

        if p == "/v1/network/presence":
            if not STATE.allow_network(who, "presence", now):
                raise network.NetworkError("too many heartbeats")
            return STATE.net.put_presence(who, body, now)

        if p == "/v1/network/presence/stop":
            return STATE.net.drop_presence(who, now)

        if p == "/v1/network/demand":
            if not STATE.allow_network(who, "demand", now):
                raise network.NetworkError("too many requests; please wait a moment")
            out = STATE.net.put_demand(who, body, now)
            # match straight away: the buyer is standing there waiting
            served = STATE.net.serve(out["demandId"], now)
            out["status"] = served.get("status", out.get("status"))
            if "activationId" in served:
                out["activationId"] = served["activationId"]
            out["candidates"] = served.get("candidates", 0)
            return out

        if p == "/v1/network/demand/cancel":
            return STATE.net.cancel_demand(str(body.get("demandId", "")), who, now)

        if p == "/v1/network/demand/poll":
            # the buyer nudges the matcher: used when a provider declined or went quiet
            d = STATE.net.demand(str(body.get("demandId", "")))
            if d is None:
                raise network.NetworkError("unknown demand")
            if d["buyer_id"] != who:
                raise network.NetworkError("that request belongs to somebody else")
            return STATE.net.serve(d["demand_id"], now)

        if p == "/v1/network/jobs/accept" or p == "/v1/network/jobs/decline":
            if not STATE.allow_network(who, "activation", now):
                raise network.NetworkError("too many answers")
            accept = p.endswith("accept")
            out = STATE.net.answer(str(body.get("activationId", "")), who, accept, now)
            if not accept:
                # do not leave the buyer waiting on somebody who said no
                a = STATE.net.activation(str(body.get("activationId", "")))
                if a is not None:
                    STATE.net.set_demand_status(a["demand_id"], network.SEARCHING, now, "declined")
                    STATE.net.serve(a["demand_id"], now)
            return out

        if p == "/v1/network/jobs/state":
            if not STATE.allow_network(who, "activation", now):
                raise network.NetworkError("too many reports")
            return STATE.net.report(str(body.get("activationId", "")), who,
                                    str(body.get("result", "")), now)

        raise network.NetworkError("not found")

    def _owes(self, who: str, seller: str) -> bool:
        """May `who` see where `seller` is paid?

        Only somebody who owes them **right now**, or the seller itself. A seller's Mobile
        Money number is a real-world identifier; a node id is not a secret, so it must not
        be enough to look one up - and neither is a debt that was settled months ago.

        The state list lives in settlement.py, so there is one answer to "is this still
        owed" rather than a copy here that can drift away from it.
        """
        if not seller:
            return False
        if who == seller:
            return True
        return STATE.settlements.has_outstanding_between(who, seller)

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

        Everything that returns somebody's money, debts, payments or device risk must
        prove who is asking. A GET has no body, so the signature covers the empty body
        plus the method and the canonical request target - the QUERY included, because
        `?payment=A` and `?payment=B` ask about two different people's money.
        """
        try:
            with STATE.lock:
                return signed_request.verify(self.headers, b"", STATE.nonces,
                                             method="GET", path=self.path, require_bound=True)
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
                    if self.path.startswith("/v1/pay/destinations"):
                        # v0.16.3: every rail this seller actually publishes on. The buyer
                        # does not name one: it cannot know whether a seller uses MTN or
                        # Airtel, and guessing MTN made Airtel sellers unpayable.
                        seller = self._query("seller")
                        if not self._owes(who, seller):
                            self._json(403, {"error": "you have no obligation to this seller"})
                            return
                        self._json(200, {"destinations": STATE.pay.destinations_for_buyer(
                            seller, now, DESTINATION_COOLING_MS)})
                        return
                    if self.path.startswith("/v1/pay/destination"):
                        # kept for a named rail; the rail must be asked for, never assumed
                        seller = self._query("seller")
                        rail = self._query("rail")
                        if not rail:
                            self._json(400, {"error": "name a rail, or ask /v1/pay/destinations"})
                            return
                        if not self._owes(who, seller):
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

        # ---- v0.17.0: the live control plane --------------------------------------
        if self.path.startswith("/v1/network/"):
            who = self._who()
            if who is None:
                self._json(401, {"error": "this endpoint requires a signed request"})
                return
            if not STATE.allow_network(who, "read", int(time.time() * 1000)):
                self._json(429, {"error": "too many requests"})
                return
            now = int(time.time() * 1000)
            try:
                with STATE.lock:
                    self._network_get(who, now)
            except network.NetworkError as e:
                self._json(400, {"error": str(e)})
            return

        if self.path.startswith("/v1/settlements/"):
            # v0.16.3: this used to be open. A settlement id is not a secret - it is
            # derived from signed session bytes and both phones hold it - so knowing one
            # must not reveal the amount, the two parties, the payment state or the audit
            # trail to whoever asks.
            who = self._who()
            if who is None:
                self._json(401, {"error": "this endpoint requires a signed request"})
                return
            sid = self.path[len("/v1/settlements/"):].split("?")[0]
            with STATE.lock:
                row = STATE.settlements.get(sid)
                if row is None:
                    # said the same way to everybody, so this cannot be used to discover
                    # which settlement ids exist
                    self._json(404, {"error": "unknown settlement"})
                    return
                if who != row["buyer_id"] and who != row["seller_id"]:
                    self._json(403, {"error": "you are not a party to this settlement"})
                    return
                trail = STATE.settlements.audit(sid)
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
            # v0.17.0: deliberately almost empty, and unauthenticated. It used to report
            # how many nodes, requests and sources the Brain knew about, which told anybody
            # who asked how many people were using ProkNet and where. A liveness probe does
            # not need that. Counts moved to /v1/network/diagnostics, which is signed.
            self._json(200, {"ok": True, "version": VERSION,
                             "protocol": "prok-sync/%d" % protocol.VERSION,
                             "schema": STATE.brain.schema_version()})
        else:
            self._send(404, "not found\n")

    def do_POST(self):
        if (self.path.startswith("/v1/settlements") or self.path.startswith("/v1/payments")
                or self.path.startswith("/v1/pay/") or self.path.startswith("/v1/device/")
                or self.path.startswith("/v1/network/")):
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
                    # v0.16.3: require_bound. A body-only signature is valid at every
                    # endpoint that accepts that body, and every route reaching here
                    # moves money or changes what somebody owes.
                    submitter = signed_request.verify(self.headers, raw, STATE.nonces, now,
                                                      method="POST", path=self.path,
                                                      require_bound=True)
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

            # ---- v0.17.0: the live control plane ----
            #
            # A separate module and a separate failure domain: a control-plane fault must
            # not stop a payment reaching the Brain, and vice versa.
            if self.path.startswith("/v1/network/"):
                try:
                    self._json(200, self._network_post(body, submitter, now))
                except network.NetworkError as e:
                    self._json(400, {"error": str(e)})
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
                n.update(state.net.sweep(now_ms))
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
