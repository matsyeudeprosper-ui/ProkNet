"""v0.16.2: the Brain as a carrier for signed payment objects.

The loop already works when two phones can reach each other. This is the other half: the
buyer finishes a session, walks away, and only later decides to pay. By then the seller is
somewhere else entirely, so somebody has to hold the signed objects in between.

**The server is a carrier and a validator, never an author.** It never writes a
destination, never accepts an expectation on the seller's behalf, and above all never
manufactures a receipt. Every object it stores was signed by a phone, and every phone
verifies that signature again for itself before acting on what it downloads. If this
server were compromised tomorrow, the worst it could do is withhold or delay messages —
it cannot move anybody's money, redirect a payment, or invent a debt.

Two authorities are deliberately left on the phones:

- the **seller** decides ACCEPTED or BUSY_SAME_AMOUNT, because only the seller can see
  every buyer's live window at once;
- the **buyer** decides whether a receipt clears its debt, by checking it against its own
  expectation.

Integer centimes throughout. No floating point comes near money.
"""
import hashlib
import sqlite3

from . import protocol

# ---- the wire, mirrored from PayWire.kt ---------------------------------------------------------

T_DESTINATION = "pay1.dest"
T_EXPECTATION = "pay1.exp"
T_REPLY = "pay1.expreply"
T_RECEIPT = "pay1.receipt"
T_END = "pay1.expend"

D_DESTINATION_CLAIM = "ProkNet-destination-claim-1"
D_EXPECTATION = "ProkNet-payment-expectation-1"
D_DEVICE_RECEIPT = "ProkNet-device-receipt-1"

#: Only these two may ever appear on a stored receipt. The operator level is future work
#: and this server must never write it, whatever a phone sends.
DEVICE_CONFIDENCE = ("DEVICE_SMS_VERIFIED", "DEVICE_NOTIFICATION_VERIFIED")

#: A buyer may be away for a long time. Financial evidence is not a control message and
#: must not expire like one.
RECEIPT_RETENTION_MS = 180 * 24 * 3600 * 1000
EXPECTATION_SANE_WINDOW_MS = 2 * 20 * 60 * 1000

MAX_LINE = 4096


class PayError(Exception):
    """The object does not support the claim. The message is safe to return to a phone."""


def _fields(line, expected_type, count):
    if not line or len(line) > MAX_LINE:
        raise PayError("payload missing or too large")
    p = line.split("|")
    if p[0] != expected_type:
        raise PayError("wrong message type")
    if len(p) != count:
        raise PayError("malformed payload")
    return p


def normalize_msisdn(raw: str) -> str:
    digits = "".join(c for c in raw if c.isdigit())
    return digits[-9:] if len(digits) > 9 else digits


def destination_hash(rail: str, msisdn: str) -> str:
    raw = "ProkNet-destination-1|%s|%s" % (rail, normalize_msisdn(msisdn))
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


# ---- parsing and verification --------------------------------------------------------------------

class Destination:
    """`dest1|sellerId|rail|msisdn|version|createdAt|sig`, wrapped in the PayWire envelope."""

    def __init__(self, line: str):
        p = line.split("|")
        if len(p) != 8 or p[0] != T_DESTINATION or p[1] != "dest1":
            raise PayError("malformed destination claim")
        self.line = line
        self.seller_id, self.rail, self.msisdn = p[2], p[3], p[4]
        try:
            self.version, self.created_at = int(p[5]), int(p[6])
        except ValueError:
            raise PayError("malformed destination claim")
        self.sig = p[7]
        if self.version < 1 or self.created_at <= 0:
            raise PayError("invalid destination version")
        if not 6 <= len(normalize_msisdn(self.msisdn)) <= 15:
            raise PayError("invalid destination number")

    def sign_data(self) -> bytes:
        return ("%s|%s|%s|%s|%d" % (D_DESTINATION_CLAIM, self.seller_id, self.rail,
                                    normalize_msisdn(self.msisdn), self.version)).encode("utf-8")

    def hash(self) -> str:
        return destination_hash(self.rail, self.msisdn)

    def verify(self, seller_pub: str):
        if protocol.node_id(seller_pub) != self.seller_id:
            raise PayError("destination is not signed by the seller it names")
        if not protocol.verify(seller_pub, self.sign_data(), self.sig):
            raise PayError("destination signature is invalid")


class Expectation:
    def __init__(self, line: str):
        p = _fields(line, T_EXPECTATION, 13)
        if p[1] != "1":
            raise PayError("unsupported expectation version")
        self.line = line
        (self.payment_id, self.buyer_id, self.seller_id, self.rail, self.destination_hash) = p[2:7]
        try:
            self.amount = int(p[7])
            self.created_at, self.valid_from, self.expires_at = int(p[8]), int(p[9]), int(p[10])
        except ValueError:
            raise PayError("malformed expectation")
        self.settlement_ids = [s for s in p[11].split(",") if s]
        self.sig = p[12]

    def sign_data(self) -> bytes:
        return ("%s|1|%s|%s|%s|%s|%s|%d|%d|%d|%d|%s" % (
            D_EXPECTATION, self.payment_id, self.buyer_id, self.seller_id, self.rail,
            self.destination_hash, self.amount, self.created_at, self.valid_from,
            self.expires_at, ",".join(sorted(self.settlement_ids)))).encode("utf-8")

    def verify(self, buyer_pub: str):
        if protocol.node_id(buyer_pub) != self.buyer_id:
            raise PayError("expectation is not signed by the buyer it names")
        if self.buyer_id == self.seller_id:
            raise PayError("buyer and seller are the same identity")
        if not protocol.verify(buyer_pub, self.sign_data(), self.sig):
            raise PayError("expectation signature is invalid")


class Receipt:
    def __init__(self, line: str):
        p = _fields(line, T_RECEIPT, 18)
        if p[1] != "1":
            raise PayError("unsupported receipt version")
        self.line = line
        (self.payment_id, self.seller_id, self.buyer_id, self.rail, self.destination_hash) = p[2:7]
        try:
            self.expected, self.observed, self.observed_at = int(p[7]), int(p[8]), int(p[9])
            self.parser_version = int(p[13])
        except ValueError:
            raise PayError("malformed receipt")
        self.source, self.source_package, self.evidence_hash = p[10], p[11], p[12]
        self.confidence = p[14]
        self.settlement_ids = [s for s in p[15].split(",") if s]
        self.reference, self.sig = p[16], p[17]

    def sign_data(self) -> bytes:
        return ("%s|%s|%s|%s|%s|%s|%d|%d|%d|%s|%s|%d|%s|%s" % (
            D_DEVICE_RECEIPT, self.payment_id, self.seller_id, self.buyer_id, self.rail,
            self.destination_hash, self.expected, self.observed, self.observed_at,
            self.source, self.evidence_hash, self.parser_version, self.confidence,
            ",".join(sorted(self.settlement_ids)))).encode("utf-8")

    def verify(self, seller_pub: str):
        if protocol.node_id(seller_pub) != self.seller_id:
            raise PayError("receipt is not signed by the seller it names")
        if not protocol.verify(seller_pub, self.sign_data(), self.sig):
            raise PayError("receipt signature is invalid")
        if self.confidence not in DEVICE_CONFIDENCE:
            # the server must never store, let alone mint, an operator attestation
            raise PayError("unsupported confidence level")
        if self.expected <= 0 or self.observed != self.expected:
            raise PayError("receipt amount does not match itself")
        if not self.settlement_ids:
            raise PayError("receipt clears nothing")


SCHEMA = """
-- The seller's signed destination claims, every version kept so cooling and replay
-- checks have something to compare against.
CREATE TABLE IF NOT EXISTS pay_destinations(
    seller_id  TEXT NOT NULL,
    rail       TEXT NOT NULL,
    version    INTEGER NOT NULL,
    msisdn     TEXT NOT NULL,
    dest_hash  TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    stored_at  INTEGER NOT NULL,
    line       TEXT NOT NULL,
    seller_pub TEXT NOT NULL DEFAULT '',
    PRIMARY KEY(seller_id, rail, version)
);

CREATE TABLE IF NOT EXISTS pay_expectations(
    payment_id  TEXT PRIMARY KEY,
    buyer_id    TEXT NOT NULL,
    seller_id   TEXT NOT NULL,
    rail        TEXT NOT NULL,
    dest_hash   TEXT NOT NULL,
    amount      INTEGER NOT NULL,
    created_at  INTEGER NOT NULL,
    expires_at  INTEGER NOT NULL,
    line        TEXT NOT NULL,
    buyer_pub   TEXT NOT NULL DEFAULT '',
    reply       TEXT NOT NULL DEFAULT '',
    replied_at  INTEGER NOT NULL DEFAULT 0,
    ended       INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_exp_seller ON pay_expectations(seller_id, ended);
CREATE INDEX IF NOT EXISTS idx_exp_buyer ON pay_expectations(buyer_id);

-- Financial evidence. Kept until the buyer acknowledges it, not expired like a control
-- message: a buyer may be away for a week and the money is still theirs.
CREATE TABLE IF NOT EXISTS pay_receipts(
    payment_id  TEXT PRIMARY KEY,
    buyer_id    TEXT NOT NULL,
    seller_id   TEXT NOT NULL,
    amount      INTEGER NOT NULL,
    observed_at INTEGER NOT NULL,
    stored_at   INTEGER NOT NULL,
    confidence  TEXT NOT NULL,
    line        TEXT NOT NULL,
    seller_pub  TEXT NOT NULL DEFAULT '',
    acked       INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_receipt_buyer ON pay_receipts(buyer_id, acked);

-- Which Prok identities have been seen on which phone. Only the domain-separated
-- pseudonym ever reaches here; no hardware identifier leaves a device.
CREATE TABLE IF NOT EXISTS device_identities(
    pseudonym TEXT NOT NULL,
    identity  TEXT NOT NULL,
    first_seen INTEGER NOT NULL,
    last_seen  INTEGER NOT NULL,
    PRIMARY KEY(pseudonym, identity)
);
CREATE INDEX IF NOT EXISTS idx_device_identity ON device_identities(identity);
"""


class PayBox:
    """Signed payment objects in transit. One connection; the caller holds the lock."""

    def __init__(self, path: str):
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)
        self.db.commit()

    # ---- destinations ---------------------------------------------------------------

    def put_destination(self, line: str, seller_pub: str, submitter: str, now: int) -> dict:
        d = Destination(line)
        if d.seller_id != submitter:
            raise PayError("only the seller may publish its own destination")
        d.verify(seller_pub)

        row = self.db.execute(
            "SELECT * FROM pay_destinations WHERE seller_id=? AND rail=? AND version=?",
            (d.seller_id, d.rail, d.version)).fetchone()
        if row is not None:
            # Same version: idempotent when it is the same CLAIM, not the same bytes.
            # ECDSA signatures are randomised, so a phone re-uploading the claim it already
            # holds produces different bytes every time; comparing raw lines would have
            # rejected an honest retry while still catching a genuinely different claim.
            same = (row["msisdn"] == normalize_msisdn(d.msisdn) and row["dest_hash"] == d.hash())
            if not same:
                raise PayError("a different claim already exists at this version")
            return {"ok": True, "duplicate": True, "version": d.version}

        top = self.db.execute(
            "SELECT MAX(version) v FROM pay_destinations WHERE seller_id=? AND rail=?",
            (d.seller_id, d.rail)).fetchone()["v"]
        if top is not None and d.version <= top:
            raise PayError("a newer destination already exists")

        with self.db:
            self.db.execute(
                "INSERT INTO pay_destinations(seller_id, rail, version, msisdn, dest_hash,"
                " created_at, stored_at, line, seller_pub) VALUES(?,?,?,?,?,?,?,?,?)",
                (d.seller_id, d.rail, d.version, normalize_msisdn(d.msisdn), d.hash(),
                 d.created_at, now, line, seller_pub))
        return {"ok": True, "version": d.version, "hash": d.hash()}

    def active_destination(self, seller_id: str, rail: str, now: int, cooling_ms: int):
        """The claim a payment should use right now.

        During the cooling period after a change this is still the OLD number, so there is
        never a moment when neither works and a transfer already on its way still lands
        somewhere valid.
        """
        rows = self.db.execute(
            "SELECT * FROM pay_destinations WHERE seller_id=? AND rail=? ORDER BY version DESC LIMIT 2",
            (seller_id, rail)).fetchall()
        if not rows:
            return None
        newest = rows[0]
        if len(rows) == 1:
            return newest
        if now >= int(newest["stored_at"]) + cooling_ms:
            return newest
        return rows[1]

    def destination_for_buyer(self, seller_id: str, rail: str, now: int, cooling_ms: int):
        row = self.active_destination(seller_id, rail, now, cooling_ms)
        # the seller's public key travels with the claim. It is not a secret, and the
        # buyer re-derives the seller id from it before believing anything, so a Brain
        # that swapped it would simply produce a claim that verifies against nobody.
        return None if row is None else {
            "line": row["line"], "version": int(row["version"]), "hash": row["dest_hash"],
            "seller_pub": row["seller_pub"]}

    # ---- expectations ---------------------------------------------------------------

    def put_expectation(self, line: str, buyer_pub: str, submitter: str, now: int,
                        cooling_ms: int, outstanding=None) -> dict:
        e = Expectation(line)
        if e.buyer_id != submitter:
            raise PayError("only the buyer may publish its own expectation")
        e.verify(buyer_pub)
        if now >= e.expires_at:
            raise PayError("expectation has already expired")
        if e.expires_at - e.created_at > EXPECTATION_SANE_WINDOW_MS:
            raise PayError("expectation window is too long")
        if e.amount <= 0 or not e.settlement_ids:
            raise PayError("expectation covers nothing")

        dest = self.active_destination(e.seller_id, e.rail, now, cooling_ms)
        if dest is None:
            raise PayError("this seller has no destination on record")
        if e.destination_hash != dest["dest_hash"]:
            raise PayError("expectation names a destination this seller is not using")

        # where the server knows the settlements, the amount must agree; where it does not,
        # the seller checks it anyway and remains the final authority
        if outstanding is not None:
            known = [outstanding.get(s) for s in e.settlement_ids]
            if all(v is not None for v in known):
                if any(v <= 0 for v in known) or sum(known) != e.amount:
                    raise PayError("amount does not match what those sessions owe")

        row = self.db.execute("SELECT * FROM pay_expectations WHERE payment_id=?", (e.payment_id,)).fetchone()
        if row is not None:
            # content, not bytes: see put_destination
            same = (row["buyer_id"] == e.buyer_id and row["seller_id"] == e.seller_id
                    and int(row["amount"]) == e.amount and row["dest_hash"] == e.destination_hash)
            if not same:
                raise PayError("a different expectation already exists with this id")
            return {"ok": True, "duplicate": True, "payment_id": e.payment_id}

        with self.db:
            self.db.execute(
                "INSERT INTO pay_expectations(payment_id, buyer_id, seller_id, rail, dest_hash,"
                " amount, created_at, expires_at, line, buyer_pub) VALUES(?,?,?,?,?,?,?,?,?,?)",
                (e.payment_id, e.buyer_id, e.seller_id, e.rail, e.destination_hash,
                 e.amount, e.created_at, e.expires_at, line, buyer_pub))
        return {"ok": True, "payment_id": e.payment_id}

    def pending_expectations(self, seller_id: str, now: int, limit: int = 20):
        return [dict(r) for r in self.db.execute(
            "SELECT payment_id, line, buyer_pub FROM pay_expectations WHERE seller_id=? AND reply='' "
            "AND ended=0 AND expires_at>? ORDER BY created_at LIMIT ?",
            (seller_id, now, limit))]

    def put_reply(self, payment_id: str, reply: str, submitter: str, now: int) -> dict:
        """The seller's answer. Only the seller it names may give it."""
        row = self.db.execute("SELECT * FROM pay_expectations WHERE payment_id=?", (payment_id,)).fetchone()
        if row is None:
            raise PayError("unknown expectation")
        if row["seller_id"] != submitter:
            raise PayError("only the seller may answer its own expectation")
        if row["reply"]:
            return {"ok": True, "duplicate": True, "reply": row["reply"]}
        with self.db:
            self.db.execute("UPDATE pay_expectations SET reply=?, replied_at=? WHERE payment_id=?",
                            (reply, now, payment_id))
        return {"ok": True, "reply": reply}

    def reply_for(self, payment_id: str, asker: str):
        row = self.db.execute("SELECT * FROM pay_expectations WHERE payment_id=?", (payment_id,)).fetchone()
        if row is None:
            return None
        if asker not in (row["buyer_id"], row["seller_id"]):
            raise PayError("not your payment")
        return {"payment_id": payment_id, "reply": row["reply"], "ended": bool(row["ended"])}

    def end_expectation(self, payment_id: str, submitter: str, now: int) -> dict:
        """Either party may close a window; the seller then frees the amount at once."""
        row = self.db.execute("SELECT * FROM pay_expectations WHERE payment_id=?", (payment_id,)).fetchone()
        if row is None:
            raise PayError("unknown expectation")
        if submitter not in (row["buyer_id"], row["seller_id"]):
            raise PayError("not your payment")
        with self.db:
            self.db.execute("UPDATE pay_expectations SET ended=1 WHERE payment_id=?", (payment_id,))
        return {"ok": True}

    # ---- receipts --------------------------------------------------------------------

    def put_receipt(self, line: str, seller_pub: str, submitter: str, now: int) -> dict:
        r = Receipt(line)
        if r.seller_id != submitter:
            raise PayError("only the seller may publish a receipt it signed")
        r.verify(seller_pub)

        e = self.db.execute("SELECT * FROM pay_expectations WHERE payment_id=?", (r.payment_id,)).fetchone()
        if e is not None:
            # where we know the expectation, the receipt must match it exactly
            if e["buyer_id"] != r.buyer_id or e["seller_id"] != r.seller_id:
                raise PayError("receipt parties do not match the expectation")
            if int(e["amount"]) != r.expected:
                raise PayError("receipt amount does not match the expectation")
            if e["dest_hash"] != r.destination_hash:
                raise PayError("receipt destination does not match the expectation")
            known = [s for s in e["line"].split("|")[11].split(",") if s]
            if not set(r.settlement_ids).issubset(set(known)):
                raise PayError("receipt names a session the expectation never covered")

        row = self.db.execute("SELECT * FROM pay_receipts WHERE payment_id=?", (r.payment_id,)).fetchone()
        if row is not None:
            # content, not bytes: see put_destination
            same = (row["buyer_id"] == r.buyer_id and row["seller_id"] == r.seller_id
                    and int(row["amount"]) == r.expected)
            if not same:
                raise PayError("a different receipt already exists for this payment")
            return {"ok": True, "duplicate": True, "payment_id": r.payment_id}

        # storing the receipt and closing its window are one change or neither
        with self.db:
            self.db.execute(
                "INSERT INTO pay_receipts(payment_id, buyer_id, seller_id, amount, observed_at,"
                " stored_at, confidence, line, seller_pub) VALUES(?,?,?,?,?,?,?,?,?)",
                (r.payment_id, r.buyer_id, r.seller_id, r.expected, r.observed_at, now,
                 r.confidence, line, seller_pub))
            self.db.execute("UPDATE pay_expectations SET ended=1 WHERE payment_id=?", (r.payment_id,))
        return {"ok": True, "payment_id": r.payment_id}

    def pending_receipts(self, buyer_id: str, limit: int = 20):
        return [dict(r) for r in self.db.execute(
            "SELECT payment_id, line, seller_pub FROM pay_receipts WHERE buyer_id=? AND acked=0 "
            "ORDER BY observed_at LIMIT ?", (buyer_id, limit))]

    def ack_receipt(self, payment_id: str, submitter: str) -> dict:
        row = self.db.execute("SELECT * FROM pay_receipts WHERE payment_id=?", (payment_id,)).fetchone()
        if row is None:
            raise PayError("unknown receipt")
        if row["buyer_id"] != submitter:
            raise PayError("only the buyer may acknowledge its own receipt")
        with self.db:
            self.db.execute("UPDATE pay_receipts SET acked=1 WHERE payment_id=?", (payment_id,))
        return {"ok": True}

    def sweep(self, now: int) -> int:
        """Expired windows go; receipts stay far longer, because they are money."""
        with self.db:
            c = self.db.execute("DELETE FROM pay_expectations WHERE expires_at<? AND ended=1",
                                (now - 24 * 3600 * 1000,))
            n = c.rowcount
            self.db.execute("DELETE FROM pay_receipts WHERE acked=1 AND stored_at<?",
                            (now - RECEIPT_RETENTION_MS,))
        return n

    # ---- device risk -------------------------------------------------------------------

    def note_device(self, pseudonym: str, identity: str, now: int):
        """Record that this identity was seen on this phone. Pseudonym only, never a serial."""
        if not pseudonym or len(pseudonym) > 64:
            return
        with self.db:
            self.db.execute(
                "INSERT INTO device_identities(pseudonym, identity, first_seen, last_seen)"
                " VALUES(?,?,?,?) ON CONFLICT(pseudonym, identity) DO UPDATE SET last_seen=excluded.last_seen",
                (pseudonym, identity, now, now))

    def device_risk(self, pseudonym: str, identity: str, unresolved_for) -> dict:
        """What this phone still owes, under **any** identity it has used.

        Derived here from verified settlement state. A phone claiming it owes nothing
        changes nothing: the client's opinion is never an input.
        """
        rows = self.db.execute("SELECT identity FROM device_identities WHERE pseudonym=?",
                               (pseudonym,)).fetchall()
        identities = [r["identity"] for r in rows]
        if identity and identity not in identities:
            identities.append(identity)
        unresolved = 0
        for i in identities:
            unresolved += max(0, unresolved_for(i))
        return {
            "unresolvedCentimes": unresolved,
            "hasUnresolvedDebt": unresolved > 0,
            "priorIdentityCount": max(0, len([i for i in identities if i != identity])),
        }
