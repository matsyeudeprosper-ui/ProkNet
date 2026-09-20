"""v0.15.0: the settlement service.

What a finished ProkNet session owes, recorded server-side so it can be reconciled
between the two phones and, later, verified against a payment rail.

Three rules this file exists to enforce:

1. **A phone saying "paid" is not payment.** Only a rail or an operator webhook whose
   signature this server verified may move an obligation to CONFIRMED.
2. **One session, one obligation.** The settlement id is a hash of the signed session
   facts, so a replay, a retry or a second phone reporting the same session all land on
   the same row.
3. **A mismatch is never resolved in favour of the larger number.** If the buyer and the
   seller report different figures for the same session, it is DISPUTED and a human
   decides.

Integer centimes throughout. No floating point comes near money.
"""
import hashlib
import sqlite3

DOMAIN = "ProkNet-settlement-1"

PENDING = "PENDING"
PAYMENT_INITIATED = "PAYMENT_INITIATED"
PAYMENT_SEEN = "PAYMENT_SEEN"
CONFIRMED = "CONFIRMED"
FAILED = "FAILED"
EXPIRED = "EXPIRED"
DISPUTED = "DISPUTED"

OUTSTANDING = (PENDING, PAYMENT_INITIATED, PAYMENT_SEEN)
FINAL = (CONFIRMED, FAILED, EXPIRED)


def settlement_id(session_hex: str, contract_hash: str, checkpoint_hash: str) -> str:
    """Identical to Settlement.idFor on the phones. Both must agree or nothing reconciles."""
    raw = "%s|%s|%s|%s" % (DOMAIN, session_hex, contract_hash, checkpoint_hash)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:32]


def split(gross: int, fee_pct: int):
    """The same half-up split the phones use: fee rounded half up, seller gets the rest."""
    if gross <= 0:
        return 0, 0, 0
    fee = (gross * fee_pct + 50) // 100
    return gross, fee, gross - fee


SCHEMA = """
CREATE TABLE IF NOT EXISTS settlements(
    settlement_id   TEXT PRIMARY KEY,
    session_id      TEXT NOT NULL,
    buyer_id        TEXT NOT NULL,
    seller_id       TEXT NOT NULL,
    checkpoint_hash TEXT NOT NULL,
    gross           INTEGER NOT NULL,
    seller_net      INTEGER NOT NULL,
    prok_fee        INTEGER NOT NULL,
    status          TEXT NOT NULL,
    rail            TEXT NOT NULL DEFAULT 'NONE',
    payment_ref     TEXT NOT NULL DEFAULT '',
    note            TEXT NOT NULL DEFAULT '',
    created_at      INTEGER NOT NULL,
    expires_at      INTEGER NOT NULL,
    buyer_reported  INTEGER NOT NULL DEFAULT 0,
    seller_reported INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_settlements_status ON settlements(status);
CREATE INDEX IF NOT EXISTS idx_settlements_buyer ON settlements(buyer_id);
CREATE INDEX IF NOT EXISTS idx_settlements_seller ON settlements(seller_id);

-- Every payment event ever accepted, so a retried webhook is recognised rather than
-- applied twice. The idempotency key is the rail's own reference.
CREATE TABLE IF NOT EXISTS payment_events(
    event_key    TEXT PRIMARY KEY,
    settlement_id TEXT NOT NULL,
    rail         TEXT NOT NULL,
    reference    TEXT NOT NULL,
    status       TEXT NOT NULL,
    amount       INTEGER NOT NULL,
    received_at  INTEGER NOT NULL,
    source       TEXT NOT NULL
);

-- Append-only. Nothing here is ever updated or deleted; it is what an auditor reads.
CREATE TABLE IF NOT EXISTS settlement_audit(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    settlement_id TEXT NOT NULL,
    at            INTEGER NOT NULL,
    actor         TEXT NOT NULL,
    was           TEXT NOT NULL,
    now           TEXT NOT NULL,
    detail        TEXT NOT NULL DEFAULT ''
);
"""


class Settlements:
    """SQLite-backed settlement store. One connection, guarded by the caller's lock."""

    def __init__(self, path: str):
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)
        self.db.commit()

    # ---- reporting -------------------------------------------------------------------

    def report(self, claim: dict, actor: str, now: int) -> dict:
        """One phone reports what a session owed.

        `actor` is "buyer" or "seller", established by the caller from a verified
        signature. The first report creates the obligation; the second is compared
        against it rather than trusted.
        """
        sid = claim["settlement_id"]
        expected = settlement_id(claim["session_id"], claim["contract_hash"], claim["checkpoint_hash"])
        if sid != expected:
            return {"ok": False, "error": "settlement id does not match the signed session"}

        gross = int(claim["gross"])
        fee_pct = int(claim.get("fee_pct", 5))
        g, fee, net = split(gross, fee_pct)
        if gross <= 0 or fee + net != gross:
            return {"ok": False, "error": "amounts do not balance"}

        row = self.get(sid)
        if row is None:
            self.db.execute(
                "INSERT INTO settlements(settlement_id, session_id, buyer_id, seller_id, checkpoint_hash,"
                " gross, seller_net, prok_fee, status, created_at, expires_at, buyer_reported, seller_reported)"
                " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (sid, claim["session_id"], claim["buyer_id"], claim["seller_id"], claim["checkpoint_hash"],
                 gross, net, fee, PENDING, now, int(claim["expires_at"]),
                 1 if actor == "buyer" else 0, 1 if actor == "seller" else 0))
            self._audit(sid, now, actor, "-", PENDING, "first report")
            self.db.commit()
            return {"ok": True, "settlement_id": sid, "status": PENDING, "agreed": False}

        # already known: this is the reconciliation, not a second obligation
        if row["status"] == DISPUTED:
            return {"ok": True, "settlement_id": sid, "status": DISPUTED, "agreed": False}
        if int(row["gross"]) != gross or row["checkpoint_hash"] != claim["checkpoint_hash"]:
            self.db.execute("UPDATE settlements SET status=?, note=? WHERE settlement_id=?",
                            (DISPUTED, "reported %d and %d" % (int(row["gross"]), gross), sid))
            self._audit(sid, now, actor, row["status"], DISPUTED, "amounts differ")
            self.db.commit()
            # the larger figure is NOT adopted; the stored one stands until a human decides
            return {"ok": True, "settlement_id": sid, "status": DISPUTED, "agreed": False}

        col = "buyer_reported" if actor == "buyer" else "seller_reported"
        self.db.execute("UPDATE settlements SET %s=1 WHERE settlement_id=?" % col, (sid,))
        self.db.commit()
        row = self.get(sid)
        agreed = bool(row["buyer_reported"]) and bool(row["seller_reported"])
        return {"ok": True, "settlement_id": sid, "status": row["status"], "agreed": agreed}

    def get(self, sid: str):
        c = self.db.execute("SELECT * FROM settlements WHERE settlement_id=?", (sid,))
        return c.fetchone()

    # ---- payment ---------------------------------------------------------------------

    def initiate(self, sid: str, rail: str, reference: str, now: int) -> dict:
        """Record that a payment was started. Starting is not paying."""
        row = self.get(sid)
        if row is None:
            return {"ok": False, "error": "unknown settlement"}
        if row["status"] == CONFIRMED:
            return {"ok": True, "status": CONFIRMED, "note": "already paid"}
        if row["status"] == DISPUTED:
            return {"ok": False, "error": "disputed"}
        if now >= int(row["expires_at"]):
            self._set(sid, EXPIRED, now, "server", row["status"], "expired before payment")
            return {"ok": False, "error": "expired"}
        self.db.execute("UPDATE settlements SET status=?, rail=?, payment_ref=? WHERE settlement_id=?",
                        (PAYMENT_INITIATED, rail, reference, sid))
        self._audit(sid, now, "buyer", row["status"], PAYMENT_INITIATED, rail)
        self.db.commit()
        return {"ok": True, "status": PAYMENT_INITIATED}

    def webhook(self, event: dict, now: int, verified: bool) -> dict:
        """A payment rail reports an outcome.

        `verified` must be the result of checking the rail's signature. An unverified
        event is recorded for the audit trail and changes nothing, because an
        unauthenticated webhook is exactly how a fraudster would mark a session paid.
        """
        sid = event.get("settlement_id", "")
        reference = event.get("reference", "")
        rail = event.get("rail", "NONE")
        status = event.get("status", PENDING)
        amount = int(event.get("amount", 0))
        key = "%s|%s|%s" % (rail, reference, sid)

        seen = self.db.execute("SELECT * FROM payment_events WHERE event_key=?", (key,)).fetchone()
        if seen is not None:
            # a retried webhook is normal and must be a no-op
            row = self.get(sid)
            return {"ok": True, "duplicate": True, "status": row["status"] if row else PENDING}

        self.db.execute(
            "INSERT INTO payment_events(event_key, settlement_id, rail, reference, status, amount, received_at, source)"
            " VALUES(?,?,?,?,?,?,?,?)",
            (key, sid, rail, reference, status, amount, now, "verified" if verified else "unverified"))

        row = self.get(sid)
        if row is None:
            self.db.commit()
            return {"ok": False, "error": "unknown settlement"}
        if not verified:
            self._audit(sid, now, "webhook", row["status"], row["status"], "signature not verified: ignored")
            self.db.commit()
            return {"ok": False, "error": "signature not verified"}
        if row["status"] == CONFIRMED:
            self.db.commit()
            return {"ok": True, "duplicate": True, "status": CONFIRMED}
        if status == CONFIRMED and amount != int(row["gross"]):
            self._set(sid, DISPUTED, now, "webhook", row["status"], "paid %d, owed %d" % (amount, int(row["gross"])))
            return {"ok": False, "error": "amount does not match", "status": DISPUTED}
        self._set(sid, status, now, "webhook", row["status"], reference)
        if status == CONFIRMED:
            self.db.execute("UPDATE settlements SET rail=?, payment_ref=? WHERE settlement_id=?", (rail, reference, sid))
            self.db.commit()
        return {"ok": True, "status": status}

    def expire(self, now: int) -> int:
        """Obligations nobody paid inside their window. Run on the cleanup timer."""
        rows = self.db.execute(
            "SELECT settlement_id, status FROM settlements WHERE expires_at<=? AND status IN (?,?,?)",
            (now, PENDING, PAYMENT_INITIATED, PAYMENT_SEEN)).fetchall()
        for r in rows:
            self._set(r["settlement_id"], EXPIRED, now, "server", r["status"], "window closed")
        return len(rows)

    # ---- wallet ----------------------------------------------------------------------

    def wallet(self, node_id: str) -> dict:
        """What one node owes and is owed. Obligations, never a balance."""
        out = {"to_pay": 0, "to_receive": 0, "paid": 0, "received": 0, "pending": 0, "disputed": 0}
        for r in self.db.execute(
                "SELECT * FROM settlements WHERE buyer_id=? OR seller_id=?", (node_id, node_id)):
            buyer = r["buyer_id"] == node_id
            if r["status"] == DISPUTED:
                out["disputed"] += 1
                continue
            if r["status"] in OUTSTANDING:
                out["pending"] += 1
                if buyer:
                    out["to_pay"] += int(r["gross"])
                else:
                    out["to_receive"] += int(r["seller_net"])
            elif r["status"] == CONFIRMED:
                if buyer:
                    out["paid"] += int(r["gross"])
                else:
                    out["received"] += int(r["seller_net"])
        return out

    def audit(self, sid: str):
        return [dict(r) for r in self.db.execute(
            "SELECT * FROM settlement_audit WHERE settlement_id=? ORDER BY id", (sid,))]

    # ---- internals -------------------------------------------------------------------

    def _set(self, sid: str, status: str, now: int, actor: str, was: str, detail: str):
        self.db.execute("UPDATE settlements SET status=? WHERE settlement_id=?", (status, sid))
        self._audit(sid, now, actor, was, status, detail)
        self.db.commit()

    def _audit(self, sid: str, now: int, actor: str, was: str, now_status: str, detail: str = ""):
        self.db.execute(
            "INSERT INTO settlement_audit(settlement_id, at, actor, was, now, detail) VALUES(?,?,?,?,?,?)",
            (sid, now, actor, was, now_status, detail))
