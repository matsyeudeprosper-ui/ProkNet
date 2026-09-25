"""v0.18.0: the Prok ledger - credit, holds, earnings, withdrawals, observed top-ups.

The product rule this module exists to keep: **a ledger cannot send money.** Every
amount here is a record of something the operators' own messages or a verified session
proved, or a request a person made. The only things that move Mobile Money are people,
by hand, and the ledger's job is to make that list short and honest: N withdrawals to
send is N manual sends, and the summary says so in those words.

Money is integer centimes (1/100 CFA), like `Market` and `settlement`. Every movement is
one double-entry posting: an amount leaves one account and arrives in another, so the
sum over all accounts is always zero and a balance is a sum, never an assignment.
Nothing is deleted; a mistake is reversed by another posting with a memo.

Accounts are strings:
    credit:<node>       what a customer may spend           (liability)
    held:<node>         credit reserved by an active hold   (liability)
    earned:<node>       what a provider or relay may withdraw (liability)
    inflight:withdrawals  reserved by open withdrawals      (liability)
    prok:revenue        Prok's share                        (liability, Prok's own)
    float:mtn float:airtel  the treasury wallets            (asset)
    fees                operator fees Prok paid             (expense)
    prok:testcredit     source of audited pilot test credit (expense)

A liability balance is credits minus debits; an asset or expense balance is the reverse.
The invariant the daily report checks: float >= credit + held + earned + inflight.

Roles are the server's, not the app's. Only allow-listed TREASURY identities may record
a top-up, adjust, or move a withdrawal past REQUESTED. Only allow-listed TEST identities
may receive test credit. Anything else is a 403 and an audit row.

`payments_live` is the pilot switch. While it is off, a top-up observed by the treasury
phone is recorded as REJECTED with reason `payments_disabled` and credits nobody; holds
can only be funded by test credit. Everything else - sessions, earnings, withdrawal
requests, the queue - runs, because none of it moves money.
"""
import hashlib
import os
import sqlite3
import time
from typing import Callable, Dict, List, Optional

# ---- schema, migration 5 ----------------------------------------------------------------

SCHEMA = """
CREATE TABLE IF NOT EXISTS ledger_postings (
    id TEXT PRIMARY KEY, ts INTEGER NOT NULL, kind TEXT NOT NULL,
    debit_account TEXT NOT NULL, credit_account TEXT NOT NULL, amount INTEGER NOT NULL,
    ref TEXT NOT NULL DEFAULT '', memo TEXT NOT NULL DEFAULT '', actor TEXT NOT NULL DEFAULT '');
CREATE INDEX IF NOT EXISTS ledger_postings_debit ON ledger_postings(debit_account);
CREATE INDEX IF NOT EXISTS ledger_postings_credit ON ledger_postings(credit_account);
CREATE UNIQUE INDEX IF NOT EXISTS ledger_postings_once ON ledger_postings(kind, ref) WHERE ref != '';
CREATE TABLE IF NOT EXISTS ledger_holds (
    id TEXT PRIMARY KEY, customer_id TEXT NOT NULL, seller_id TEXT NOT NULL, amount INTEGER NOT NULL,
    state TEXT NOT NULL, created_at INTEGER NOT NULL, last_keepalive INTEGER NOT NULL,
    session_hex TEXT NOT NULL DEFAULT '', settled_ref TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS ledger_holds_customer ON ledger_holds(customer_id, state);
CREATE UNIQUE INDEX IF NOT EXISTS ledger_holds_one_open ON ledger_holds(customer_id)
    WHERE state IN ('PRE_SESSION', 'IN_SESSION', 'STALE');
CREATE INDEX IF NOT EXISTS ledger_holds_session ON ledger_holds(session_hex);
CREATE TABLE IF NOT EXISTS ledger_withdrawals (
    id TEXT PRIMARY KEY, payee_id TEXT NOT NULL, rail TEXT NOT NULL, msisdn_hash TEXT NOT NULL,
    msisdn TEXT NOT NULL DEFAULT '', amount INTEGER NOT NULL, state TEXT NOT NULL,
    requested_at INTEGER NOT NULL, approved_at INTEGER NOT NULL DEFAULT 0, approved_by TEXT NOT NULL DEFAULT '',
    sent_at INTEGER NOT NULL DEFAULT 0, sent_by TEXT NOT NULL DEFAULT '',
    paid_at INTEGER NOT NULL DEFAULT 0, paid_evidence TEXT NOT NULL DEFAULT '',
    memo TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL);
CREATE UNIQUE INDEX IF NOT EXISTS ledger_withdrawals_one_open ON ledger_withdrawals(payee_id)
    WHERE state IN ('REQUESTED', 'APPROVED', 'SENT', 'NEEDS_ATTENTION');
CREATE TABLE IF NOT EXISTS ledger_topups (
    id TEXT PRIMARY KEY, rail TEXT NOT NULL, sender_hash TEXT NOT NULL, amount INTEGER NOT NULL,
    sms_hash TEXT NOT NULL UNIQUE, observed_at INTEGER NOT NULL, observed_by TEXT NOT NULL,
    state TEXT NOT NULL, customer_id TEXT NOT NULL DEFAULT '', matched_by TEXT NOT NULL DEFAULT '',
    claim_ref TEXT NOT NULL DEFAULT '', reason TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS ledger_topups_state ON ledger_topups(state);
CREATE TABLE IF NOT EXISTS ledger_intents (
    id TEXT PRIMARY KEY, customer_id TEXT NOT NULL, rail TEXT NOT NULL, amount INTEGER NOT NULL,
    amount_tag INTEGER NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
    state TEXT NOT NULL, topup_id TEXT NOT NULL DEFAULT '');
CREATE INDEX IF NOT EXISTS ledger_intents_open ON ledger_intents(rail, amount_tag, state);
CREATE TABLE IF NOT EXISTS ledger_bindings (
    customer_id TEXT NOT NULL, rail TEXT NOT NULL, sender_hash TEXT NOT NULL,
    bound_at INTEGER NOT NULL, bound_by TEXT NOT NULL, PRIMARY KEY (rail, sender_hash));
CREATE TABLE IF NOT EXISTS ledger_balance_checks (
    id TEXT PRIMARY KEY, ts INTEGER NOT NULL, rail TEXT NOT NULL, typed INTEGER NOT NULL,
    expected INTEGER NOT NULL, actor TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS ledger_audit (
    id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, actor TEXT NOT NULL,
    action TEXT NOT NULL, target TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', allowed INTEGER NOT NULL);
"""

# ---- constants (all configurable; these are the pilot's examples) --------------------------

RAILS = ("MTN", "AIRTEL")
#: Money that arrived and nobody has been credited for yet. A liability like any credit.
UNASSIGNED_ACCOUNT = "unassigned:topups"

#: A hold that never became a session goes back to the customer after this.
HOLD_PRE_TTL_MS = 30 * 60_000
#: Keepalives stopped for this long: the hold is STALE - still reserved, no longer live.
HOLD_KEEPALIVE_TTL_MS = 15 * 60_000
#: A stale hold with no settlement for this long is released. A session that settles after
#: that still posts; the customer may then be negative by at most one hold, and the report says so.
HOLD_STALE_TTL_MS = 24 * 3_600_000
#: Rate limits that close the repeated-tiny-session loop regardless of pricing.
MAX_HOLDS_PER_HOUR = 6
MAX_HOLDS_PER_PAIR_PER_HOUR = 3
HOLD_WINDOW_MS = 3_600_000

#: The provider chooses when to withdraw; below this it is not worth an operator fee.
WITHDRAW_MIN_CENTIMES = 500 * 100
#: A withdrawal marked sent with no evidence for a day turns amber; after a week Mike is told.
SENT_AMBER_MS = 24 * 3_600_000
SENT_ATTENTION_MS = 7 * 24 * 3_600_000

#: A top-up intent, and the unique amount tag that binds an unknown sender.
INTENT_TTL_MS = 24 * 3_600_000
TAG_RANGE = 99          # 1..99 CFA added on top of the amount
UNCLAIMED_REVIEW_MS = 7 * 24 * 3_600_000

# hold states
PRE_SESSION = "PRE_SESSION"
IN_SESSION = "IN_SESSION"
STALE = "STALE"
CONSUMED = "CONSUMED"
RELEASED = "RELEASED"
EXPIRED = "EXPIRED"
HOLD_OPEN = (PRE_SESSION, IN_SESSION, STALE)

# withdrawal states
REQUESTED = "REQUESTED"
APPROVED = "APPROVED"
SENT = "SENT"
PAID = "PAID"
DENIED = "DENIED"
CANCELLED = "CANCELLED"
NEEDS_ATTENTION = "NEEDS_ATTENTION"
WITHDRAWAL_OPEN = (REQUESTED, APPROVED, SENT, NEEDS_ATTENTION)
#: What the provider's screen says, and nothing else. One text per state; the Kotlin side
#: reads the same fixture (server/tests/fixtures/withdrawal_states.txt).
WITHDRAWAL_TEXT = {
    REQUESTED: "Retrait demandé",
    APPROVED: "Retrait demandé",
    SENT: "Envoi en cours",
    PAID: "Payé",
    DENIED: "Refusé",
    CANCELLED: "Annulé",
    NEEDS_ATTENTION: "En vérification",
}

# top-up states
MATCHED = "MATCHED"
UNASSIGNED = "UNASSIGNED"
NEEDS_REVIEW = "NEEDS_REVIEW"
REJECTED = "REJECTED"
#: reason slugs, machine-readable like network.py's
PAYMENTS_DISABLED = "payments_disabled"
NOT_TREASURY = "not_treasury"
NOT_TEST_IDENTITY = "not_test_identity"
INSUFFICIENT_CREDIT = "insufficient_credit"
HOLD_EXISTS = "hold_exists"
TOO_MANY_HOLDS = "too_many_holds"
BELOW_MINIMUM = "below_minimum"
WITHDRAWAL_OPEN_REASON = "withdrawal_open"
WRONG_STATE = "wrong_state"
CLAIM_INCOMPLETE = "claim_incomplete"


class LedgerError(Exception):
    def __init__(self, message: str, reason: str = "", code: int = 400):
        super().__init__(message)
        self.reason = reason
        self.code = code


def _id(*parts) -> str:
    return hashlib.sha256("|".join(str(p) for p in parts).encode("utf-8")).hexdigest()[:24]


def msisdn_digits(raw: str) -> str:
    """The nine national digits of a Congolese Mobile Money number, however it was typed
    or printed: "+242 06 612 34 56", "00242066123456", "066123456" all become 066123456.
    Empty when there are not nine digits to find."""
    d = "".join(ch for ch in (raw or "") if ch.isdigit())
    if d.startswith("00242"):
        d = d[5:]
    elif d.startswith("242") and len(d) > 9:
        d = d[3:]
    # exactly nine national digits with the leading zero, or nothing: guessing a missing
    # digit would send somebody's money to a number nobody typed
    return d if len(d) == 9 and d[0] == "0" else ""


def msisdn_hash(raw: str) -> str:
    """The one hash both sides compute: the treasury phone from the operator's message, the
    payee's phone from what they typed. Domain-separated, over the nine national digits.
    Must equal core/Msisdn.kt on the phone - the fixture msisdn_hash.txt holds that line."""
    d = msisdn_digits(raw)
    if not d:
        return ""
    return hashlib.sha256(("ProkNet-msisdn-1|" + d).encode("utf-8")).hexdigest()


def _liability(account: str) -> bool:
    return not (account.startswith("float:") or account in ("fees", "prok:testcredit"))


class Ledger:
    def __init__(self, db: sqlite3.Connection, treasury_ids=(), test_ids=(), payments_live: bool = False,
                 clock: Callable[[], int] = None):
        self.db = db
        self.db.row_factory = sqlite3.Row
        # a bare connection (tests) may not have run the numbered migration
        self.db.executescript(SCHEMA)
        self.db.commit()
        self.treasury_ids = set(x for x in treasury_ids if x)
        self.test_ids = set(x for x in test_ids if x)
        self.payments_live = bool(payments_live)
        self.clock = clock or (lambda: int(time.time() * 1000))

    @staticmethod
    def from_env(db: sqlite3.Connection) -> "Ledger":
        split = lambda k: [x.strip() for x in os.environ.get(k, "").split(",") if x.strip()]
        return Ledger(db, treasury_ids=split("PROK_TREASURY_IDS"), test_ids=split("PROK_TEST_IDS"),
                      payments_live=os.environ.get("PROK_PAYMENTS_LIVE", "").strip() == "1")

    # ---- roles -----------------------------------------------------------------------------

    def is_treasury(self, who: str) -> bool:
        return who in self.treasury_ids

    def _require_treasury(self, who: str, action: str, target: str, now: int):
        if who not in self.treasury_ids:
            self._refuse(now, who, action, target, "not a treasury identity",
                         LedgerError("only a treasury identity may do this", NOT_TREASURY, 403))

    def _audit(self, now: int, actor: str, action: str, target: str, detail: str = "", allowed: bool = True):
        self.db.execute("INSERT INTO ledger_audit(ts, actor, action, target, detail, allowed) VALUES(?,?,?,?,?,?)",
                        (now, actor, action, target, detail, 1 if allowed else 0))
        # a refusal written inside a transaction that is about to roll back would vanish
        # with it - and a refusal is the audit row that matters most
        if not self.db.in_transaction:
            self.db.commit()

    def _refuse(self, now: int, actor: str, action: str, target: str, detail: str, error: "LedgerError"):
        """Audit a refusal durably, then raise. Call this OUTSIDE any open transaction."""
        self._audit(now, actor, action, target, "refused: " + detail, allowed=False)
        self.db.commit()
        raise error

    # ---- postings and balances -------------------------------------------------------------

    def _post(self, now: int, kind: str, debit: str, credit: str, amount: int, ref: str = "",
              memo: str = "", actor: str = "") -> Optional[str]:
        """One movement. Returns the posting id, or None when (kind, ref) was already posted."""
        if amount <= 0:
            raise LedgerError("a posting must move a positive amount")
        pid = _id(kind, ref or now, debit, credit, amount, now if not ref else "")
        try:
            self.db.execute("INSERT INTO ledger_postings(id, ts, kind, debit_account, credit_account, amount, ref, memo, actor)"
                            " VALUES(?,?,?,?,?,?,?,?,?)", (pid, now, kind, debit, credit, amount, ref, memo, actor))
        except sqlite3.IntegrityError:
            return None
        return pid

    def balance(self, account: str) -> int:
        cr = self.db.execute("SELECT COALESCE(SUM(amount),0) AS s FROM ledger_postings WHERE credit_account=?", (account,)).fetchone()["s"]
        dr = self.db.execute("SELECT COALESCE(SUM(amount),0) AS s FROM ledger_postings WHERE debit_account=?", (account,)).fetchone()["s"]
        return int(cr - dr) if _liability(account) else int(dr - cr)

    def _sum_prefix(self, prefix: str) -> int:
        cr = self.db.execute("SELECT COALESCE(SUM(amount),0) AS s FROM ledger_postings WHERE credit_account LIKE ?", (prefix + "%",)).fetchone()["s"]
        dr = self.db.execute("SELECT COALESCE(SUM(amount),0) AS s FROM ledger_postings WHERE debit_account LIKE ?", (prefix + "%",)).fetchone()["s"]
        return int(cr - dr)

    def net_of_all_accounts(self) -> int:
        """Double entry: every posting leaves one account and enters another, so summing every
        account with its own sign convention comes to zero. A test holds that line."""
        accounts = set()
        for r in self.db.execute("SELECT debit_account AS a FROM ledger_postings UNION SELECT credit_account FROM ledger_postings").fetchall():
            accounts.add(r["a"])
        total = 0
        for a in accounts:
            b = self.balance(a)
            total += b if _liability(a) else -b
        return total

    def invariant(self) -> dict:
        """float >= credit + held + earned + inflight. Shortfall > 0 blocks approvals."""
        float_total = self.balance("float:mtn") + self.balance("float:airtel")
        liabilities = (self._sum_prefix("credit:") + self._sum_prefix("held:") + self._sum_prefix("earned:")
                       + self.balance("inflight:withdrawals") + self.balance(UNASSIGNED_ACCOUNT))
        return {"float": float_total, "liabilities": liabilities, "shortfall": max(0, liabilities - float_total),
                "test_credit_issued": self.balance("prok:testcredit")}

    # ---- sessions ----------------------------------------------------------------------------

    def post_settlement(self, derived: dict, now: int, relay_id: str = "", relay_centimes: int = 0) -> dict:
        """Verified evidence becomes earnings. Idempotent per settlement id.

        `derived` is `evidence.verify`'s output: the server re-derived gross, seller_net and
        prok_fee from the signed bytes; nothing here trusts a phone's number. The buyer's
        side comes from its hold when one exists for the session (and the remainder of the
        hold is released), otherwise straight from credit - which may go negative, and the
        report shows that rather than hiding a session that really happened.
        """
        sid = derived["settlement_id"]
        buyer = derived["buyer_id"]
        seller = derived["seller_id"]
        gross = int(derived["gross"])
        seller_net = int(derived["seller_net"])
        fee = int(derived["prok_fee"])
        if gross <= 0:
            return {"posted": False, "reason": "zero"}
        if relay_centimes < 0 or relay_centimes > seller_net:
            raise LedgerError("relay share out of range")
        if self.db.execute("SELECT 1 FROM ledger_postings WHERE kind='SESSION' AND ref=?", (sid,)).fetchone():
            return {"posted": False, "reason": "already"}
        session_hex = derived.get("session_id", "")
        hold = self._hold_for_session(buyer, seller, session_hex)
        with self.db:
            from_held = 0
            if hold is not None:
                from_held = min(gross, int(hold["amount"]))
                if from_held > 0:
                    self._post(now, "SESSION", "held:" + buyer, "earned:" + seller, from_held, ref=sid, memo="session", actor=seller)
                rest = gross - from_held
                if rest > 0:
                    self._post(now, "SESSION_OVER", "credit:" + buyer, "earned:" + seller, rest, ref=sid, memo="beyond hold", actor=seller)
                leftover = int(hold["amount"]) - from_held
                if leftover > 0:
                    self._post(now, "HOLD_RELEASE", "held:" + buyer, "credit:" + buyer, leftover, ref=hold["id"], memo="remainder")
                self.db.execute("UPDATE ledger_holds SET state=?, settled_ref=?, updated_at=? WHERE id=?", (CONSUMED, sid, now, hold["id"]))
            else:
                self._post(now, "SESSION", "credit:" + buyer, "earned:" + seller, gross, ref=sid, memo="session, no hold", actor=seller)
            # the seller's gross is split: Prok's share and, when a relay carried it, the relay's
            if fee > 0:
                self._post(now, "PROK_SHARE", "earned:" + seller, "prok:revenue", fee, ref=sid, actor=seller)
            if relay_id and relay_centimes > 0:
                self._post(now, "RELAY_SHARE", "earned:" + seller, "earned:" + relay_id, relay_centimes, ref=sid, actor=seller)
        return {"posted": True, "settlement_id": sid, "gross": gross, "seller_net": seller_net - relay_centimes,
                "prok_fee": fee, "relay": relay_centimes, "from_hold": from_held,
                "buyer_credit": self.balance("credit:" + buyer)}

    # ---- holds --------------------------------------------------------------------------------

    def _hold_for_session(self, buyer: str, seller: str, session_hex: str):
        if session_hex:
            row = self.db.execute("SELECT * FROM ledger_holds WHERE session_hex=? AND customer_id=? AND state IN (?,?,?)",
                                  (session_hex, buyer, PRE_SESSION, IN_SESSION, STALE)).fetchone()
            if row is not None:
                return row
        # the seller may not have reported the start before the session ended
        return self.db.execute("SELECT * FROM ledger_holds WHERE customer_id=? AND seller_id=? AND session_hex='' AND state IN (?,?,?)"
                               " ORDER BY created_at DESC LIMIT 1", (buyer, seller, PRE_SESSION, IN_SESSION, STALE)).fetchone()

    def open_hold(self, customer: str) -> Optional[sqlite3.Row]:
        return self.db.execute("SELECT * FROM ledger_holds WHERE customer_id=? AND state IN (?,?,?)",
                               (customer, PRE_SESSION, IN_SESSION, STALE)).fetchone()

    def hold(self, customer: str, seller: str, amount: int, now: int) -> dict:
        """The seller reserves the buyer's credit before admitting a paid session. Atomic."""
        if amount <= 0:
            raise LedgerError("amount must be positive")
        if customer == seller:
            raise LedgerError("a seller cannot hold its own credit")
        # BEGIN IMMEDIATE: the read of the balance and the insert of the hold must be one
        # unit against every other connection too, not just this one. The partial unique
        # index on open holds is the second lock on the same door.
        self.db.execute("BEGIN IMMEDIATE")
        try:
            if self.open_hold(customer) is not None:
                raise LedgerError("this customer already has an active hold", HOLD_EXISTS, 409)
            since = now - HOLD_WINDOW_MS
            n_all = self.db.execute("SELECT COUNT(*) AS n FROM ledger_holds WHERE customer_id=? AND created_at>?", (customer, since)).fetchone()["n"]
            n_pair = self.db.execute("SELECT COUNT(*) AS n FROM ledger_holds WHERE customer_id=? AND seller_id=? AND created_at>?",
                                     (customer, seller, since)).fetchone()["n"]
            if n_all >= MAX_HOLDS_PER_HOUR or n_pair >= MAX_HOLDS_PER_PAIR_PER_HOUR:
                raise LedgerError("too many sessions this hour - try again later", TOO_MANY_HOLDS, 429)
            credit = self.balance("credit:" + customer)
            if credit < amount:
                raise LedgerError("not enough credit", INSUFFICIENT_CREDIT)
            hid = _id("hold", customer, seller, now, n_all)
            try:
                self.db.execute("INSERT INTO ledger_holds(id, customer_id, seller_id, amount, state, created_at, last_keepalive, updated_at)"
                                " VALUES(?,?,?,?,?,?,?,?)", (hid, customer, seller, amount, PRE_SESSION, now, now, now))
            except sqlite3.IntegrityError:
                raise LedgerError("this customer already has an active hold", HOLD_EXISTS, 409)
            self._post(now, "HOLD", "credit:" + customer, "held:" + customer, amount, ref=hid, actor=seller)
            self.db.commit()
        except Exception:
            self.db.rollback()
            raise
        return {"ok": True, "hold_id": hid, "amount": amount, "state": PRE_SESSION, "credit_left": credit - amount}

    def _hold_row(self, hold_id: str, seller: str) -> sqlite3.Row:
        row = self.db.execute("SELECT * FROM ledger_holds WHERE id=?", (hold_id,)).fetchone()
        if row is None:
            raise LedgerError("unknown hold", code=404)
        if row["seller_id"] != seller:
            raise LedgerError("not your hold", code=403)
        return row

    def hold_started(self, hold_id: str, seller: str, session_hex: str, now: int) -> dict:
        row = self._hold_row(hold_id, seller)
        if row["state"] not in (PRE_SESSION, IN_SESSION, STALE):
            raise LedgerError("hold is " + row["state"], WRONG_STATE, 409)
        with self.db:
            self.db.execute("UPDATE ledger_holds SET state=?, session_hex=?, last_keepalive=?, updated_at=? WHERE id=?",
                            (IN_SESSION, session_hex or row["session_hex"], now, now, hold_id))
        return {"ok": True, "hold_id": hold_id, "state": IN_SESSION}

    def keepalive(self, hold_id: str, seller: str, now: int) -> dict:
        """While the seller keeps saying the session is live, the hold never expires."""
        row = self._hold_row(hold_id, seller)
        if row["state"] not in (IN_SESSION, STALE):
            raise LedgerError("hold is " + row["state"], WRONG_STATE, 409)
        with self.db:
            self.db.execute("UPDATE ledger_holds SET state=?, last_keepalive=?, updated_at=? WHERE id=?", (IN_SESSION, now, now, hold_id))
        return {"ok": True, "hold_id": hold_id, "state": IN_SESSION}

    def release_unused(self, hold_id: str, seller: str, now: int) -> dict:
        """Only a hold that never became a session may be released by the seller. Once a
        session ran, the credit stays reserved until evidence settles it or the stale
        clock runs out - a session may still settle from either phone."""
        row = self._hold_row(hold_id, seller)
        if row["state"] != PRE_SESSION:
            raise LedgerError("a session may still settle against this hold", WRONG_STATE, 409)
        with self.db:
            self._post(now, "HOLD_RELEASE", "held:" + row["customer_id"], "credit:" + row["customer_id"], int(row["amount"]), ref=hold_id, memo="never started")
            self.db.execute("UPDATE ledger_holds SET state=?, updated_at=? WHERE id=?", (RELEASED, now, hold_id))
        return {"ok": True, "hold_id": hold_id, "state": RELEASED}

    def sweep(self, now: int) -> Dict[str, int]:
        n = {"holds_expired_unstarted": 0, "holds_stale": 0, "holds_expired_stale": 0,
             "withdrawals_attention": 0, "intents_expired": 0}
        with self.db:
            for row in self.db.execute("SELECT * FROM ledger_holds WHERE state=? AND created_at<=?", (PRE_SESSION, now - HOLD_PRE_TTL_MS)).fetchall():
                self._post(now, "HOLD_RELEASE", "held:" + row["customer_id"], "credit:" + row["customer_id"], int(row["amount"]), ref=row["id"], memo="never started, expired")
                self.db.execute("UPDATE ledger_holds SET state=?, updated_at=? WHERE id=?", (EXPIRED, now, row["id"]))
                n["holds_expired_unstarted"] += 1
            cur = self.db.execute("UPDATE ledger_holds SET state=?, updated_at=? WHERE state=? AND last_keepalive<=?",
                                  (STALE, now, IN_SESSION, now - HOLD_KEEPALIVE_TTL_MS))
            n["holds_stale"] = cur.rowcount
            for row in self.db.execute("SELECT * FROM ledger_holds WHERE state=? AND last_keepalive<=?", (STALE, now - HOLD_STALE_TTL_MS)).fetchall():
                self._post(now, "HOLD_RELEASE", "held:" + row["customer_id"], "credit:" + row["customer_id"], int(row["amount"]), ref=row["id"], memo="stale a day, no settlement")
                self.db.execute("UPDATE ledger_holds SET state=?, updated_at=? WHERE id=?", (EXPIRED, now, row["id"]))
                n["holds_expired_stale"] += 1
            cur = self.db.execute("UPDATE ledger_withdrawals SET state=?, updated_at=? WHERE state=? AND sent_at<=?",
                                  (NEEDS_ATTENTION, now, SENT, now - SENT_ATTENTION_MS))
            n["withdrawals_attention"] = cur.rowcount
            cur = self.db.execute("UPDATE ledger_intents SET state='EXPIRED' WHERE state='OPEN' AND expires_at<=?", (now,))
            n["intents_expired"] = cur.rowcount
        return n

    # ---- wallet view --------------------------------------------------------------------------

    def wallet(self, node: str, now: int) -> dict:
        w = self.db.execute("SELECT * FROM ledger_withdrawals WHERE payee_id=? AND state IN (?,?,?,?)",
                            (node,) + WITHDRAWAL_OPEN).fetchone()
        last = self.db.execute("SELECT * FROM ledger_withdrawals WHERE payee_id=? ORDER BY updated_at DESC LIMIT 1", (node,)).fetchone()
        earned = self.balance("earned:" + node)
        paid_total = self.db.execute("SELECT COALESCE(SUM(amount),0) AS s FROM ledger_withdrawals WHERE payee_id=? AND state=?", (node, PAID)).fetchone()["s"]
        return {
            "credit": self.balance("credit:" + node),
            "held": self.balance("held:" + node),
            "earned": earned,                          # withdrawable now (reserved amounts already left this account)
            "earned_lifetime": earned + int(paid_total) + self._reserved_for(node),
            "withdrawable": earned,
            "withdraw_min": WITHDRAW_MIN_CENTIMES,
            "withdrawal": self._withdrawal_view(w or last, now) if (w or last) else None,
            "hold": self._hold_view(self.open_hold(node)),
            "treasury": node in self.treasury_ids,
            "payments_live": self.payments_live,
        }

    def _reserved_for(self, node: str) -> int:
        return int(self.db.execute("SELECT COALESCE(SUM(amount),0) AS s FROM ledger_withdrawals WHERE payee_id=? AND state IN (?,?,?,?)",
                                   (node,) + WITHDRAWAL_OPEN).fetchone()["s"])

    @staticmethod
    def _hold_view(row) -> Optional[dict]:
        if row is None:
            return None
        return {"hold_id": row["id"], "amount": int(row["amount"]), "state": row["state"], "seller_id": row["seller_id"]}

    def _withdrawal_view(self, row, now: int, for_treasury: bool = False) -> dict:
        amber = row["state"] == SENT and now - int(row["sent_at"]) >= SENT_AMBER_MS
        out = {"id": row["id"], "amount": int(row["amount"]), "rail": row["rail"], "state": row["state"],
               "text": WITHDRAWAL_TEXT[row["state"]], "requested_at": int(row["requested_at"]),
               "sent_at": int(row["sent_at"]), "paid_at": int(row["paid_at"]), "paid_evidence": row["paid_evidence"],
               "memo": row["memo"], "amber": bool(amber), "payee_id": row["payee_id"], "msisdn_hash": row["msisdn_hash"],
               "msisdn_tail": row["msisdn"][-4:] if row["msisdn"] else ""}
        if for_treasury:
            out["msisdn"] = row["msisdn"]       # the treasurer has to type it; nobody else sees it
        return out

    # ---- withdrawals: the payee's side -------------------------------------------------------

    def request_withdrawal(self, payee: str, rail: str, msisdn: str, amount: int, now: int) -> dict:
        """The payee names the rail, the number and the amount. The number is kept on this
        row only, returned only to treasury identities (the treasurer has to type it into
        the operator's app), and never logged; the hash is what SMS matching uses."""
        if rail not in RAILS:
            raise LedgerError("unknown rail")
        digits = msisdn_digits(msisdn)
        if not digits:
            raise LedgerError("a valid nine-digit payout number is required")
        h = msisdn_hash(digits)
        if amount < WITHDRAW_MIN_CENTIMES:
            raise LedgerError("below the minimum withdrawal", BELOW_MINIMUM)
        with self.db:
            if self.db.execute("SELECT 1 FROM ledger_withdrawals WHERE payee_id=? AND state IN (?,?,?,?)", (payee,) + WITHDRAWAL_OPEN).fetchone():
                raise LedgerError("a withdrawal is already open", WITHDRAWAL_OPEN_REASON, 409)
            if self.balance("earned:" + payee) < amount:
                raise LedgerError("not that much to withdraw", INSUFFICIENT_CREDIT)
            wid = _id("withdrawal", payee, now, amount)
            self.db.execute("INSERT INTO ledger_withdrawals(id, payee_id, rail, msisdn_hash, msisdn, amount, state, requested_at, updated_at)"
                            " VALUES(?,?,?,?,?,?,?,?,?)", (wid, payee, rail, h, digits, amount, REQUESTED, now, now))
            # reserve now, so the same earnings cannot be requested twice
            self._post(now, "WITHDRAW_RESERVE", "earned:" + payee, "inflight:withdrawals", amount, ref=wid, actor=payee)
            self._audit(now, payee, "withdraw.request", wid, "%d" % amount)
        return {"ok": True, "withdrawal": self._withdrawal_view(self._w(wid), now)}

    def cancel_withdrawal(self, payee: str, wid: str, now: int) -> dict:
        row = self._w(wid)
        if row["payee_id"] != payee:
            raise LedgerError("not your withdrawal", code=403)
        if row["state"] != REQUESTED:
            raise LedgerError("only a requested withdrawal can be cancelled", WRONG_STATE, 409)
        with self.db:
            self._post(now, "WITHDRAW_CANCEL", "inflight:withdrawals", "earned:" + payee, int(row["amount"]), ref=wid, actor=payee)
            self.db.execute("UPDATE ledger_withdrawals SET state=?, updated_at=? WHERE id=?", (CANCELLED, now, wid))
            self._audit(now, payee, "withdraw.cancel", wid)
        return {"ok": True, "withdrawal": self._withdrawal_view(self._w(wid), now)}

    def my_withdrawals(self, payee: str, now: int, limit: int = 20) -> List[dict]:
        rows = self.db.execute("SELECT * FROM ledger_withdrawals WHERE payee_id=? ORDER BY requested_at DESC LIMIT ?", (payee, limit)).fetchall()
        return [self._withdrawal_view(r, now) for r in rows]

    def _w(self, wid: str) -> sqlite3.Row:
        row = self.db.execute("SELECT * FROM ledger_withdrawals WHERE id=?", (wid,)).fetchone()
        if row is None:
            raise LedgerError("unknown withdrawal", code=404)
        return row

    # ---- withdrawals: the treasurer's side ---------------------------------------------------

    def queue(self, who: str, now: int) -> dict:
        self._require_treasury(who, "treasury.queue", "-", now)
        rows = self.db.execute("SELECT * FROM ledger_withdrawals WHERE state IN (?,?,?,?) ORDER BY requested_at", WITHDRAWAL_OPEN).fetchall()
        return {"rows": [self._withdrawal_view(r, now, for_treasury=True) for r in rows], "summary": self.summary(now)}

    def summary(self, now: int) -> dict:
        """The honest numbers. `manual_sends_pending` is the count of withdrawals a person
        still has to send by hand: the ledger cannot send them, and the screen says so."""
        def count(*states):
            return int(self.db.execute("SELECT COUNT(*) AS n FROM ledger_withdrawals WHERE state IN (%s)" % ",".join("?" * len(states)), states).fetchone()["n"])
        def total(*states):
            return int(self.db.execute("SELECT COALESCE(SUM(amount),0) AS s FROM ledger_withdrawals WHERE state IN (%s)" % ",".join("?" * len(states)), states).fetchone()["s"])
        pending = count(REQUESTED, APPROVED)
        inv = self.invariant()
        return {
            "manual_sends_pending": pending,
            "manual_sends_pending_centimes": total(REQUESTED, APPROVED),
            "sentence": "%d retrait%s en attente = %d envoi%s manuel%s" % (pending, "s" if pending != 1 else "", pending, "s" if pending != 1 else "", "s" if pending != 1 else ""),
            "sent_unconfirmed": count(SENT),
            "needs_attention": count(NEEDS_ATTENTION),
            "paid_total_centimes": total(PAID),
            "topups_unassigned": int(self.db.execute("SELECT COUNT(*) AS n FROM ledger_topups WHERE state IN (?,?)", (UNASSIGNED, NEEDS_REVIEW)).fetchone()["n"]),
            "float_mtn": self.balance("float:mtn"), "float_airtel": self.balance("float:airtel"),
            "liabilities": inv["liabilities"], "shortfall": inv["shortfall"],
            "payments_live": self.payments_live,
        }

    def treasury_withdrawal(self, who: str, wid: str, action: str, now: int, memo: str = "", evidence: str = "") -> dict:
        self._require_treasury(who, "treasury." + action, wid, now)
        row = self._w(wid)
        state = row["state"]
        if action == "sent" and state != APPROVED:
            # duplicate-payment guard, audited durably before anything else happens
            self._refuse(now, who, "treasury.sent", wid, "state is " + state,
                         LedgerError("already marked sent" if state in (SENT, PAID) else "approve it first", WRONG_STATE, 409))
        with self.db:
            if action == "approve":
                if state != REQUESTED:
                    raise LedgerError("only a requested withdrawal can be approved", WRONG_STATE, 409)
                if self.payments_live and self.balance("float:" + row["rail"].lower()) < self._committed_on(row["rail"]) + int(row["amount"]):
                    raise LedgerError("that rail's float does not cover this", INSUFFICIENT_CREDIT, 409)
                self.db.execute("UPDATE ledger_withdrawals SET state=?, approved_at=?, approved_by=?, updated_at=? WHERE id=?", (APPROVED, now, who, now, wid))
            elif action == "deny":
                if state not in (REQUESTED, APPROVED, NEEDS_ATTENTION):
                    raise LedgerError("cannot deny from " + state, WRONG_STATE, 409)
                self._post(now, "WITHDRAW_CANCEL", "inflight:withdrawals", "earned:" + row["payee_id"], int(row["amount"]), ref=wid, memo=memo, actor=who)
                self.db.execute("UPDATE ledger_withdrawals SET state=?, memo=?, updated_at=? WHERE id=?", (DENIED, memo, now, wid))
            elif action == "sent":
                # only an APPROVED row can be marked sent, exactly once (checked above)
                self.db.execute("UPDATE ledger_withdrawals SET state=?, sent_at=?, sent_by=?, updated_at=? WHERE id=?", (SENT, now, who, now, wid))
            elif action == "unsent":
                if state != SENT:
                    raise LedgerError("only a sent withdrawal can be marked not sent", WRONG_STATE, 409)
                self.db.execute("UPDATE ledger_withdrawals SET state=?, sent_at=0, sent_by='', memo=?, updated_at=? WHERE id=?", (APPROVED, memo, now, wid))
            elif action == "paid":
                if state not in (SENT, NEEDS_ATTENTION):
                    raise LedgerError("mark it sent first", WRONG_STATE, 409)
                if not evidence:
                    raise LedgerError("the operator reference is required to confirm by hand")
                self._mark_paid(row, now, "treasurer:" + evidence, who)
            else:
                raise LedgerError("unknown action")
            self._audit(now, who, "treasury." + action, wid, memo or evidence)
        return {"ok": True, "withdrawal": self._withdrawal_view(self._w(wid), now, for_treasury=True), "summary": self.summary(now)}

    def _committed_on(self, rail: str) -> int:
        return int(self.db.execute("SELECT COALESCE(SUM(amount),0) AS s FROM ledger_withdrawals WHERE rail=? AND state IN (?,?)",
                                   (rail, APPROVED, SENT)).fetchone()["s"])

    def _mark_paid(self, row, now: int, evidence: str, actor: str):
        self._post(now, "WITHDRAW_PAID", "inflight:withdrawals", "float:" + row["rail"].lower(), int(row["amount"]), ref=row["id"], actor=actor)
        self.db.execute("UPDATE ledger_withdrawals SET state=?, paid_at=?, paid_evidence=?, updated_at=? WHERE id=?",
                        (PAID, now, evidence, now, row["id"]))

    def observe_debit(self, who: str, rail: str, counterparty_hash: str, amount: int, sms_hash: str, now: int) -> dict:
        """The treasury phone saw "vous avez envoyé N F à 06…". Exactly one SENT row with that
        rail, number and amount becomes PAID; two candidates means a person decides."""
        self._require_treasury(who, "treasury.debit", sms_hash[:12], now)
        if rail not in RAILS or amount <= 0 or not sms_hash:
            raise LedgerError("rail, amount and sms hash are required")
        if self.db.execute("SELECT 1 FROM ledger_withdrawals WHERE paid_evidence=?", (sms_hash,)).fetchone():
            return {"ok": True, "matched": "already"}
        rows = self.db.execute("SELECT * FROM ledger_withdrawals WHERE rail=? AND msisdn_hash=? AND amount=? AND state=?",
                               (rail, counterparty_hash, amount, SENT)).fetchall()
        with self.db:
            if len(rows) == 1:
                self._mark_paid(rows[0], now, sms_hash, who)
                self._audit(now, who, "treasury.debit.matched", rows[0]["id"], sms_hash[:12])
                return {"ok": True, "matched": "one", "withdrawal_id": rows[0]["id"]}
            if len(rows) > 1:
                self._audit(now, who, "treasury.debit.ambiguous", ",".join(r["id"] for r in rows), sms_hash[:12])
                return {"ok": True, "matched": "ambiguous", "candidates": [r["id"] for r in rows]}
            # a fee line or a transfer the ledger does not know: recorded, not applied
            self._post(now, "FEE", "fees", "float:" + rail.lower(), amount, ref="sms:" + sms_hash, memo="unmatched debit", actor=who)
            self._audit(now, who, "treasury.debit.unmatched", sms_hash[:12], "%s %d" % (rail, amount))
        return {"ok": True, "matched": "none"}

    # ---- top-ups -------------------------------------------------------------------------------

    def create_intent(self, customer: str, rail: str, amount: int, now: int) -> dict:
        """What the customer is about to send. A sender the ledger has never matched gets a
        unique amount tag - "envoyez exactement 503 F" - so the operator's message itself
        identifies them. The tag is credited in full; nothing is taken."""
        if rail not in RAILS or amount <= 0:
            raise LedgerError("rail and amount are required")
        bound = self.db.execute("SELECT 1 FROM ledger_bindings WHERE customer_id=? AND rail=?", (customer, rail)).fetchone() is not None
        with self.db:
            self.db.execute("UPDATE ledger_intents SET state='REPLACED' WHERE customer_id=? AND state='OPEN'", (customer,))
            tag = amount
            if not bound:
                taken = set(int(r["amount_tag"]) for r in self.db.execute(
                    "SELECT amount_tag FROM ledger_intents WHERE rail=? AND state='OPEN' AND expires_at>?", (rail, now)).fetchall())
                tag = 0
                for k in range(1, TAG_RANGE + 1):
                    cand = amount + k * 100
                    if cand not in taken:
                        tag = cand
                        break
                if tag == 0:
                    raise LedgerError("no free amount tag right now - try again in a few minutes")
            iid = _id("intent", customer, rail, now)
            self.db.execute("INSERT INTO ledger_intents(id, customer_id, rail, amount, amount_tag, created_at, expires_at, state)"
                            " VALUES(?,?,?,?,?,?,?,'OPEN')", (iid, customer, rail, amount, tag, now, now + INTENT_TTL_MS))
        return {"ok": True, "intent_id": iid, "rail": rail, "amount": amount, "send_exactly": tag, "bound": bound,
                "expires_at": now + INTENT_TTL_MS, "payments_live": self.payments_live}

    def observe_credit(self, who: str, rail: str, sender_hash: str, amount: int, sms_hash: str, now: int, source: str = "sms") -> dict:
        """The treasury phone saw "vous avez reçu N F de 06…". Credit is posted only from this."""
        self._require_treasury(who, "treasury.topup", sms_hash[:12], now)
        if rail not in RAILS or amount <= 0 or not sms_hash or not sender_hash:
            raise LedgerError("rail, sender, amount and sms hash are required")
        existing = self.db.execute("SELECT * FROM ledger_topups WHERE sms_hash=?", (sms_hash,)).fetchone()
        if existing is not None:
            return {"ok": True, "state": existing["state"], "topup_id": existing["id"], "duplicate": True}
        tid = _id("topup", sms_hash)
        with self.db:
            if not self.payments_live:
                self.db.execute("INSERT INTO ledger_topups(id, rail, sender_hash, amount, sms_hash, observed_at, observed_by, state, reason, updated_at)"
                                " VALUES(?,?,?,?,?,?,?,?,?,?)", (tid, rail, sender_hash, amount, sms_hash, now, who, REJECTED, PAYMENTS_DISABLED, now))
                self._audit(now, who, "treasury.topup", tid, "rejected: " + PAYMENTS_DISABLED, allowed=False)
                return {"ok": True, "state": REJECTED, "reason": PAYMENTS_DISABLED, "topup_id": tid}
            binding = self.db.execute("SELECT * FROM ledger_bindings WHERE rail=? AND sender_hash=?", (rail, sender_hash)).fetchone()
            customer = ""
            matched_by = ""
            if binding is not None:
                customer = binding["customer_id"]
                matched_by = "binding"
                self.db.execute("UPDATE ledger_intents SET state='USED', topup_id=? WHERE customer_id=? AND state='OPEN'", (tid, customer))
            else:
                intent = self.db.execute("SELECT * FROM ledger_intents WHERE rail=? AND amount_tag=? AND state='OPEN' AND expires_at>?",
                                         (rail, amount, now)).fetchone()
                if intent is not None:
                    customer = intent["customer_id"]
                    matched_by = "tag"
                    self.db.execute("UPDATE ledger_intents SET state='USED', topup_id=? WHERE id=?", (tid, intent["id"]))
                    self.db.execute("INSERT OR REPLACE INTO ledger_bindings(customer_id, rail, sender_hash, bound_at, bound_by) VALUES(?,?,?,?,?)",
                                    (customer, rail, sender_hash, now, tid))
            state = MATCHED if customer else UNASSIGNED
            self.db.execute("INSERT INTO ledger_topups(id, rail, sender_hash, amount, sms_hash, observed_at, observed_by, state, customer_id, matched_by, updated_at)"
                            " VALUES(?,?,?,?,?,?,?,?,?,?,?)", (tid, rail, sender_hash, amount, sms_hash, now, who, state, customer, matched_by, now))
            # the money arrived either way. Unmatched, it sits in a suspense account that
            # counts as a liability until a person assigns it - never in anybody's credit.
            self._post(now, "TOPUP", "float:" + rail.lower(), ("credit:" + customer) if customer else UNASSIGNED_ACCOUNT,
                       amount, ref=tid, memo=matched_by or "unassigned", actor=who)
            self._audit(now, who, "treasury.topup", tid, state + " " + matched_by)
        return {"ok": True, "state": state, "topup_id": tid, "customer_id": customer, "matched_by": matched_by}

    def claim(self, customer: str, rail: str, sender_hash: str, amount: int, reference: str, now: int) -> dict:
        """A customer says an unassigned top-up is theirs. A number alone proves nothing: the
        exact amount and the operator's reference (or the time from their own SMS) are also
        required, and even then nothing is credited until a treasurer confirms it."""
        if not (rail in RAILS and sender_hash and amount > 0 and reference.strip()):
            raise LedgerError("number, exact amount and the operator reference are all required", CLAIM_INCOMPLETE)
        row = self.db.execute("SELECT * FROM ledger_topups WHERE rail=? AND sender_hash=? AND amount=? AND state=? ORDER BY observed_at DESC LIMIT 1",
                              (rail, sender_hash, amount, UNASSIGNED)).fetchone()
        if row is None:
            raise LedgerError("no unassigned payment matches", code=404)
        with self.db:
            self.db.execute("UPDATE ledger_topups SET state=?, customer_id=?, claim_ref=?, updated_at=? WHERE id=?",
                            (NEEDS_REVIEW, customer, reference.strip()[:64], now, row["id"]))
            self._audit(now, customer, "topup.claim", row["id"], "needs treasurer review")
        return {"ok": True, "state": NEEDS_REVIEW, "topup_id": row["id"]}

    def review(self, who: str, topup_id: str, confirm: bool, now: int, memo: str = "") -> dict:
        self._require_treasury(who, "treasury.review", topup_id, now)
        row = self.db.execute("SELECT * FROM ledger_topups WHERE id=?", (topup_id,)).fetchone()
        if row is None:
            raise LedgerError("unknown top-up", code=404)
        if row["state"] not in (NEEDS_REVIEW, UNASSIGNED):
            raise LedgerError("nothing to review", WRONG_STATE, 409)
        with self.db:
            if confirm:
                if not row["customer_id"]:
                    raise LedgerError("no customer on this claim")
                if not self.payments_live:
                    raise LedgerError("payments are disabled", PAYMENTS_DISABLED, 409)
                self.db.execute("UPDATE ledger_topups SET state=?, matched_by='treasurer', updated_at=? WHERE id=?", (MATCHED, now, topup_id))
                self.db.execute("INSERT OR REPLACE INTO ledger_bindings(customer_id, rail, sender_hash, bound_at, bound_by) VALUES(?,?,?,?,?)",
                                (row["customer_id"], row["rail"], row["sender_hash"], now, topup_id))
                # the float already rose when the message was observed; this moves the
                # amount out of suspense into the customer's credit
                self._post(now, "TOPUP_ASSIGN", UNASSIGNED_ACCOUNT, "credit:" + row["customer_id"], int(row["amount"]), ref=topup_id, memo="treasurer", actor=who)
            else:
                self.db.execute("UPDATE ledger_topups SET state=?, customer_id='', reason=?, updated_at=? WHERE id=?", (UNASSIGNED, memo, now, topup_id))
            self._audit(now, who, "treasury.review", topup_id, ("confirmed" if confirm else "rejected ") + memo)
        return {"ok": True, "state": MATCHED if confirm else UNASSIGNED}

    def review_list(self, who: str, now: int) -> List[dict]:
        self._require_treasury(who, "treasury.review_list", "-", now)
        rows = self.db.execute("SELECT * FROM ledger_topups WHERE state IN (?,?) ORDER BY observed_at", (NEEDS_REVIEW, UNASSIGNED)).fetchall()
        return [{"topup_id": r["id"], "rail": r["rail"], "amount": int(r["amount"]), "state": r["state"],
                 "observed_at": int(r["observed_at"]), "customer_id": r["customer_id"], "claim_ref": r["claim_ref"],
                 "stale": now - int(r["observed_at"]) >= UNCLAIMED_REVIEW_MS} for r in rows]

    # ---- treasury: test credit and the typed balance -------------------------------------------

    def test_credit(self, who: str, target: str, amount: int, now: int, memo: str = "") -> dict:
        """Pilot only: credit a named TEST identity so holds and sessions can be exercised
        without anybody's money. Audited, sourced from its own account so the invariant
        always shows how much test credit exists."""
        self._require_treasury(who, "treasury.test_credit", target, now)
        if target not in self.test_ids:
            self._refuse(now, who, "treasury.test_credit", target, "not a test identity",
                         LedgerError("test credit may only go to a listed test identity", NOT_TEST_IDENTITY, 403))
        if amount <= 0:
            raise LedgerError("amount must be positive")
        with self.db:
            self._post(now, "ADJUSTMENT", "prok:testcredit", "credit:" + target, amount, memo="test credit " + memo, actor=who)
            self._audit(now, who, "treasury.test_credit", target, "%d %s" % (amount, memo))
        return {"ok": True, "credit": self.balance("credit:" + target)}

    def balance_check(self, who: str, rail: str, typed: int, now: int) -> dict:
        self._require_treasury(who, "treasury.balance", rail, now)
        if rail not in RAILS:
            raise LedgerError("unknown rail")
        expected = self.balance("float:" + rail.lower())
        with self.db:
            self.db.execute("INSERT INTO ledger_balance_checks(id, ts, rail, typed, expected, actor) VALUES(?,?,?,?,?,?)",
                            (_id("bal", rail, now), now, rail, typed, expected, who))
            self._audit(now, who, "treasury.balance", rail, "typed %d expected %d" % (typed, expected))
        return {"ok": True, "rail": rail, "typed": typed, "expected": expected, "delta": typed - expected}

    def audit_rows(self, who: str, now: int, limit: int = 100) -> List[dict]:
        self._require_treasury(who, "treasury.audit", "-", now)
        rows = self.db.execute("SELECT * FROM ledger_audit ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
        return [dict(r) for r in rows]
