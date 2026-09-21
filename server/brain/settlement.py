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

SECURITY_REVIEW = "SECURITY_REVIEW"

PENDING = "PENDING"
PAYMENT_INITIATED = "PAYMENT_INITIATED"
PAYMENT_SEEN = "PAYMENT_SEEN"
CONFIRMED = "CONFIRMED"
FAILED = "FAILED"
EXPIRED = "EXPIRED"
DISPUTED = "DISPUTED"

OUTSTANDING = (PENDING, PAYMENT_INITIATED, PAYMENT_SEEN)
NEEDS_HUMAN = (DISPUTED, SECURITY_REVIEW)
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

-- v0.15.1: ONE real operator transfer. A buyer may settle three tiny sessions with a
-- single MTN payment, so an obligation is not the unit of payment; this is.
--
-- The uniqueness rule that matters: (rail, operator_reference) is UNIQUE. One real
-- operator transaction can therefore never produce two independent payment records, and
-- the same reference can never be presented separately against unrelated obligations.
-- It may cover several obligations, but only through allocations of THIS row.
CREATE TABLE IF NOT EXISTS payment_transactions(
    payment_id   TEXT PRIMARY KEY,
    rail         TEXT NOT NULL,
    operator_ref TEXT NOT NULL,
    buyer_id     TEXT NOT NULL,
    seller_id    TEXT NOT NULL,
    destination  TEXT NOT NULL DEFAULT '',
    gross_paid   INTEGER NOT NULL,
    status       TEXT NOT NULL,
    created_at   INTEGER NOT NULL,
    verified_at  INTEGER NOT NULL DEFAULT 0,
    note         TEXT NOT NULL DEFAULT '',
    UNIQUE(rail, operator_ref)
);

-- How much of one payment settles which obligation. The primary key stops the same
-- payment being allocated twice to the same obligation.
CREATE TABLE IF NOT EXISTS payment_allocations(
    payment_id    TEXT NOT NULL,
    settlement_id TEXT NOT NULL,
    allocated     INTEGER NOT NULL,
    PRIMARY KEY(payment_id, settlement_id)
);
CREATE INDEX IF NOT EXISTS idx_alloc_settlement ON payment_allocations(settlement_id);

-- Every payment event ever accepted, so a retried webhook is recognised rather than
-- applied twice. The idempotency key is the real transaction, not the obligation.
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

-- v0.15.3: where a seller says it wants to be paid, per rail. A payment naming a
-- different destination for a seller we already know is refused rather than sent
-- somewhere new on a phone's say-so.
CREATE TABLE IF NOT EXISTS payment_destinations(
    seller_id   TEXT NOT NULL,
    rail        TEXT NOT NULL,
    destination TEXT NOT NULL,
    updated_at  INTEGER NOT NULL,
    PRIMARY KEY(seller_id, rail)
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

    def record(self, derived: dict, now: int) -> dict:
        """v0.15.1: store an obligation the SERVER derived from verified evidence.

        Nothing here comes from a phone's claim about money. `derived` is the output of
        `evidence.verify`, which re-computed the amounts and the settlement id from the
        signed contract and the signed closing checkpoint.

        The first party to submit creates the row. The second is a corroboration: because
        both derivations came from the same signed bytes, they must agree, and a
        disagreement means one of them is presenting different evidence for the same
        session, which is a dispute rather than an update.
        """
        sid = derived["settlement_id"]
        actor = derived["actor"]
        row = self.get(sid)
        if row is None:
            # the obligation and the audit line that explains it are one change or neither
            with self.db:
                self.db.execute(
                    "INSERT INTO settlements(settlement_id, session_id, buyer_id, seller_id, checkpoint_hash,"
                    " gross, seller_net, prok_fee, status, created_at, expires_at, buyer_reported, seller_reported)"
                    " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    (sid, derived["session_id"], derived["buyer_id"], derived["seller_id"],
                     derived["checkpoint_hash"], derived["gross"], derived["seller_net"], derived["prok_fee"],
                     PENDING, now, int(derived["expires_at"]),
                     1 if actor == "buyer" else 0, 1 if actor == "seller" else 0))
                self._audit(sid, now, actor, "-", PENDING, "verified evidence")
            return {"ok": True, "settlement_id": sid, "status": PENDING, "agreed": False,
                    "gross": derived["gross"], "seller_net": derived["seller_net"], "prok_fee": derived["prok_fee"]}

        if row["status"] in NEEDS_HUMAN:
            return {"ok": True, "settlement_id": sid, "status": row["status"], "agreed": False}
        if int(row["gross"]) != derived["gross"] or row["checkpoint_hash"] != derived["checkpoint_hash"]:
            # two verified derivations cannot differ unless the evidence differs
            with self.db:
                self.db.execute("UPDATE settlements SET status=?, note=? WHERE settlement_id=?",
                                (DISPUTED, "verified %d and %d" % (int(row["gross"]), derived["gross"]), sid))
                self._audit(sid, now, actor, row["status"], DISPUTED, "conflicting verified evidence")
            return {"ok": True, "settlement_id": sid, "status": DISPUTED, "agreed": False}

        col = "buyer_reported" if actor == "buyer" else "seller_reported"
        with self.db:
            self.db.execute("UPDATE settlements SET %s=1 WHERE settlement_id=?" % col, (sid,))
        row = self.get(sid)
        return {"ok": True, "settlement_id": sid, "status": row["status"],
                "agreed": bool(row["buyer_reported"]) and bool(row["seller_reported"]),
                "gross": int(row["gross"]), "seller_net": int(row["seller_net"]), "prok_fee": int(row["prok_fee"])}


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
            with self.db:
                self.db.execute(
                    "INSERT INTO settlements(settlement_id, session_id, buyer_id, seller_id, checkpoint_hash,"
                    " gross, seller_net, prok_fee, status, created_at, expires_at, buyer_reported, seller_reported)"
                    " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    (sid, claim["session_id"], claim["buyer_id"], claim["seller_id"], claim["checkpoint_hash"],
                     gross, net, fee, PENDING, now, int(claim["expires_at"]),
                     1 if actor == "buyer" else 0, 1 if actor == "seller" else 0))
                self._audit(sid, now, actor, "-", PENDING, "first report")
            return {"ok": True, "settlement_id": sid, "status": PENDING, "agreed": False}

        # already known: this is the reconciliation, not a second obligation
        if row["status"] == DISPUTED:
            return {"ok": True, "settlement_id": sid, "status": DISPUTED, "agreed": False}
        if int(row["gross"]) != gross or row["checkpoint_hash"] != claim["checkpoint_hash"]:
            with self.db:
                self.db.execute("UPDATE settlements SET status=?, note=? WHERE settlement_id=?",
                                (DISPUTED, "reported %d and %d" % (int(row["gross"]), gross), sid))
                self._audit(sid, now, actor, row["status"], DISPUTED, "amounts differ")
            # the larger figure is NOT adopted; the stored one stands until a human decides
            return {"ok": True, "settlement_id": sid, "status": DISPUTED, "agreed": False}

        col = "buyer_reported" if actor == "buyer" else "seller_reported"
        with self.db:
            self.db.execute("UPDATE settlements SET %s=1 WHERE settlement_id=?" % col, (sid,))
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
            with self.db:
                self._set(sid, EXPIRED, now, "server", row["status"], "expired before payment")
            return {"ok": False, "error": "expired"}
        with self.db:
            self.db.execute("UPDATE settlements SET status=?, rail=?, payment_ref=? WHERE settlement_id=?",
                            (PAYMENT_INITIATED, rail, reference, sid))
            self._audit(sid, now, "buyer", row["status"], PAYMENT_INITIATED, rail)
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

        # recording the event and whatever it changes is one transaction: an event row
        # without its consequence would look like a webhook that was already handled
        row = self.get(sid)
        with self.db:
            self.db.execute(
                "INSERT INTO payment_events(event_key, settlement_id, rail, reference, status, amount, received_at, source)"
                " VALUES(?,?,?,?,?,?,?,?)",
                (key, sid, rail, reference, status, amount, now, "verified" if verified else "unverified"))
            if row is None:
                out = {"ok": False, "error": "unknown settlement"}
            elif not verified:
                self._audit(sid, now, "webhook", row["status"], row["status"], "signature not verified: ignored")
                out = {"ok": False, "error": "signature not verified"}
            elif row["status"] == CONFIRMED:
                out = {"ok": True, "duplicate": True, "status": CONFIRMED}
            elif status == CONFIRMED and amount != int(row["gross"]):
                self._set(sid, DISPUTED, now, "webhook", row["status"],
                          "paid %d, owed %d" % (amount, int(row["gross"])))
                out = {"ok": False, "error": "amount does not match", "status": DISPUTED}
            else:
                self._set(sid, status, now, "webhook", row["status"], reference)
                if status == CONFIRMED:
                    self.db.execute("UPDATE settlements SET rail=?, payment_ref=? WHERE settlement_id=?",
                                    (rail, reference, sid))
                out = {"ok": True, "status": status}
        return out

    def note_unverified_webhook(self, event: dict, now: int):
        """v0.16.2: record it and change nothing.

        An unverified webhook used to freeze the payment and every obligation it named.
        That turned an unauthenticated endpoint into a way to stop a real seller being
        paid, so it is now audit only.
        """
        key = "unverified|%s|%s|%s" % (event.get("rail", ""), event.get("reference", ""),
                                       event.get("payment_id", ""))
        try:
            with self.db:
                self.db.execute(
                    "INSERT OR IGNORE INTO payment_events(event_key, settlement_id, rail, reference,"
                    " status, amount, received_at, source) VALUES(?,?,?,?,?,?,?,?)",
                    (key, event.get("settlement_id", ""), event.get("rail", "NONE"),
                     event.get("reference", ""), "IGNORED", int(event.get("amount", 0) or 0),
                     now, "unverified"))
        except Exception:
            pass

    def expire(self, now: int) -> int:
        """Obligations nobody paid inside their window. Run on the cleanup timer."""
        rows = self.db.execute(
            "SELECT settlement_id, status FROM settlements WHERE expires_at<=? AND status IN (?,?,?)",
            (now, PENDING, PAYMENT_INITIATED, PAYMENT_SEEN)).fetchall()
        with self.db:
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
            if r["status"] in NEEDS_HUMAN:
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

    # ---- v0.15.1: real transfers, and what they settle ---------------------------------

    def payment_id(self, rail: str, operator_ref: str) -> str:
        return hashlib.sha256(("ProkNet-payment-1|%s|%s" % (rail, operator_ref)).encode("utf-8")).hexdigest()[:32]

    def open_payment(self, rail: str, operator_ref: str, buyer_id: str, seller_id: str,
                     amount: int, allocations: list, now: int, destination: str = "") -> dict:
        """Record one real operator transfer and what it is meant to settle.

        `allocations` is [(settlement_id, centimes)]. The invariants enforced here are the
        ones that stop money going missing or being counted twice:

        - the same (rail, reference) is ONE transaction, never two;
        - the allocations may not exceed the amount actually transferred;
        - no obligation may be allocated more than it still owes;
        - a confirmed obligation cannot be paid again.
        """
        if not operator_ref:
            return {"ok": False, "error": "an operator reference is required"}
        if amount <= 0:
            return {"ok": False, "error": "amount must be positive"}

        pid = self.payment_id(rail, operator_ref)
        existing = self.payment(pid)
        if existing is not None:
            # the same real transfer presented again
            if int(existing["gross_paid"]) != amount or existing["buyer_id"] != buyer_id or existing["seller_id"] != seller_id:
                self._flag_payment(pid, now, "same reference, different amount or parties")
                return {"ok": False, "error": "this reference is already used for a different payment",
                        "status": SECURITY_REVIEW, "payment_id": pid}
            return {"ok": True, "duplicate": True, "payment_id": pid, "status": existing["status"]}

        total = sum(int(a[1]) for a in allocations)
        if total <= 0:
            return {"ok": False, "error": "nothing allocated"}
        if total > amount:
            return {"ok": False, "error": "allocations exceed the amount paid"}
        if not seller_id:
            return {"ok": False, "error": "a payment must name its seller"}
        # v0.15.3: one real transfer goes to ONE destination, so it may only settle
        # obligations owed to that seller. A batch spanning two sellers would pay one of
        # them for the other's work.
        if destination:
            known = self.destination_for(seller_id, rail)
            if known and known != destination:
                return {"ok": False, "error": "destination does not match the seller on record",
                        "status": SECURITY_REVIEW}

        for sid, centimes in allocations:
            row = self.get(sid)
            if row is None:
                return {"ok": False, "error": "unknown settlement %s" % sid}
            if row["status"] == CONFIRMED:
                return {"ok": False, "error": "an obligation in this payment is already paid"}
            if row["status"] in (DISPUTED, SECURITY_REVIEW):
                return {"ok": False, "error": "an obligation in this payment is under review"}
            if row["buyer_id"] != buyer_id or row["seller_id"] != seller_id:
                return {"ok": False, "error": "an obligation in this payment belongs to other parties"}
            if int(centimes) <= 0:
                return {"ok": False, "error": "an allocation must be positive"}
            if int(centimes) > self.remaining(sid):
                return {"ok": False, "error": "an allocation exceeds what that session still owes"}

        # The transaction row, every allocation and every obligation it moves are ONE
        # change. Half of this committed is worse than none of it: a payment row with
        # only some of its allocations reads as a real transfer that settles less than it
        # paid for, and nothing later can tell that the rest was lost rather than never
        # intended.
        with self.db:
            self.db.execute(
                "INSERT INTO payment_transactions(payment_id, rail, operator_ref, buyer_id, seller_id,"
                " destination, gross_paid, status, created_at) VALUES(?,?,?,?,?,?,?,?,?)",
                (pid, rail, operator_ref, buyer_id, seller_id, destination, amount, PAYMENT_INITIATED, now))
            for sid, centimes in allocations:
                self.db.execute(
                    "INSERT INTO payment_allocations(payment_id, settlement_id, allocated) VALUES(?,?,?)",
                    (pid, sid, int(centimes)))
                row = self.get(sid)
                self._set(sid, PAYMENT_INITIATED, now, "buyer", row["status"], "payment %s" % pid[:12])
        return {"ok": True, "payment_id": pid, "status": PAYMENT_INITIATED, "allocated": total}

    # ---- where a seller is paid ------------------------------------------------------

    def set_destination(self, seller_id: str, rail: str, destination: str, now: int):
        """Recorded once, then used to check later payments against it."""
        with self.db:
            self.db.execute(
                "INSERT INTO payment_destinations(seller_id, rail, destination, updated_at) VALUES(?,?,?,?)"
                " ON CONFLICT(seller_id, rail) DO UPDATE SET destination=excluded.destination, updated_at=excluded.updated_at",
                (seller_id, rail, destination, now))

    def destination_for(self, seller_id: str, rail: str):
        r = self.db.execute("SELECT destination FROM payment_destinations WHERE seller_id=? AND rail=?",
                            (seller_id, rail)).fetchone()
        return r["destination"] if r else None

    def payment(self, pid: str):
        return self.db.execute("SELECT * FROM payment_transactions WHERE payment_id=?", (pid,)).fetchone()

    def allocations(self, pid: str):
        return [dict(r) for r in self.db.execute(
            "SELECT * FROM payment_allocations WHERE payment_id=?", (pid,))]

    def remaining(self, sid: str) -> int:
        """What an obligation still owes after every allocation already made against it."""
        row = self.get(sid)
        if row is None:
            return 0
        if row["status"] == CONFIRMED:
            return 0
        allocated = self.db.execute(
            "SELECT COALESCE(SUM(a.allocated), 0) s FROM payment_allocations a"
            " JOIN payment_transactions p ON p.payment_id = a.payment_id"
            " WHERE a.settlement_id=? AND p.status=?", (sid, CONFIRMED)).fetchone()["s"]
        return max(0, int(row["gross"]) - int(allocated))

    def confirm_payment(self, pid: str, amount: int, now: int, verified: bool) -> dict:
        """A rail verified one real transfer. Every obligation it covers settles together."""
        p = self.payment(pid)
        if p is None:
            return {"ok": False, "error": "unknown payment"}
        if not verified:
            self._flag_payment(pid, now, "confirmation could not be verified")
            return {"ok": False, "error": "signature not verified"}
        if p["status"] == CONFIRMED:
            return {"ok": True, "duplicate": True, "status": CONFIRMED}
        if int(p["gross_paid"]) != amount:
            self._flag_payment(pid, now, "confirmed %d, expected %d" % (amount, int(p["gross_paid"])))
            return {"ok": False, "error": "amount does not match", "status": SECURITY_REVIEW}

        allocs = self.allocations(pid)
        if sum(a["allocated"] for a in allocs) > amount:
            self._flag_payment(pid, now, "allocations exceed the verified amount")
            return {"ok": False, "error": "allocations exceed the verified amount", "status": SECURITY_REVIEW}

        # One verified transfer settles every obligation it covers, or none of them. A
        # partial commit here would leave a CONFIRMED payment beside obligations that
        # still read as owing, and the buyer would be asked to pay twice.
        settled = []
        with self.db:
            self.db.execute("UPDATE payment_transactions SET status=?, verified_at=? WHERE payment_id=?",
                            (CONFIRMED, now, pid))
            for a in allocs:
                sid = a["settlement_id"]
                row = self.get(sid)
                if row is None or row["status"] == CONFIRMED:
                    continue
                # a partial allocation leaves the obligation owing the rest
                still = self.remaining(sid)
                if still <= 0:
                    self.db.execute("UPDATE settlements SET rail=?, payment_ref=? WHERE settlement_id=?",
                                    (p["rail"], p["operator_ref"], sid))
                    self._set(sid, CONFIRMED, now, "payment", row["status"], "paid by %s" % pid[:12])
                    settled.append(sid)
                else:
                    self._set(sid, PAYMENT_SEEN, now, "payment", row["status"],
                              "partly paid, %d still owed" % still)
        return {"ok": True, "status": CONFIRMED, "settled": settled}

    def _flag_payment(self, pid: str, now: int, why: str):
        """Send a payment and everything it touches for review, together.

        Only ever called at a return point, never inside another transaction, so it owns
        this one. Flagging the payment without flagging its obligations would leave them
        payable while the transfer behind them is under suspicion.
        """
        with self.db:
            self.db.execute("UPDATE payment_transactions SET status=?, note=? WHERE payment_id=?",
                            (SECURITY_REVIEW, why, pid))
            for a in self.allocations(pid):
                row = self.get(a["settlement_id"])
                if row is not None and row["status"] != CONFIRMED:
                    self._set(a["settlement_id"], SECURITY_REVIEW, now, "server", row["status"], why)

    def audit(self, sid: str):
        return [dict(r) for r in self.db.execute(
            "SELECT * FROM settlement_audit WHERE settlement_id=? ORDER BY id", (sid,))]

    # ---- internals -------------------------------------------------------------------

    def _set(self, sid: str, status: str, now: int, actor: str, was: str, detail: str):
        """Change one obligation and record why. **Does not commit.**

        v0.16.3: it used to. That meant a payment touching three obligations committed
        three times on the way through, so a failure in the middle left a transaction row
        with some of its obligations moved and the rest not - money half-settled, and no
        way to tell from the audit trail which half was real. The caller now owns the
        transaction and this only ever participates in it.
        """
        self.db.execute("UPDATE settlements SET status=? WHERE settlement_id=?", (status, sid))
        self._audit(sid, now, actor, was, status, detail)

    def _audit(self, sid: str, now: int, actor: str, was: str, now_status: str, detail: str = ""):
        self.db.execute(
            "INSERT INTO settlement_audit(settlement_id, at, actor, was, now, detail) VALUES(?,?,?,?,?,?)",
            (sid, now, actor, was, now_status, detail))
