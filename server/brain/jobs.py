"""v0.19.0: relay offers and availability blocks (contract §4 "Relay job and exact earning
moment", §10.3 "Commissioned relay/source readiness" and "Move-to-help offer").

Three offers, each quoted before acceptance, each with ONE exact earning moment:

  CARRY  (carry now)  The relay is between customer and source and accepts one session. It
         earns ONLY through the signed settlement: the ledger posts the quote's relay_share
         (scaled to the verified bytes) after both endpoints' evidence settles. This module
         only exposes `relay_share_for(settlement)` reading the quote; the lead's ledger
         replaces its fixed 10 % with that. No bytes, no delivery earning.

  STAY   (stay available)  A 30-minute block in a zone Prok or a sponsor commissioned. The
         reward (2000 centimes = 20 FCFA) is RESERVED from cleared fund money (fund.reserve,
         purpose BLOCK) before the offer exists, so a block that completes is always paid.
         Verified = the phone answered >= 3 reachability probes spread over the block (one in
         each 10-minute third) with at most one failed probe, and 30 minutes elapsed. Two
         consecutive failed probes, or ten minutes without any probe, END the block FAILED:
         nothing is paid and the reservation goes back. At most four blocks per relay per
         day (Brazzaville days). Ordinary online time earns nothing - only a commissioned,
         accepted, verified block does.

  MOVE   (move to help)  Created only when the paying party (customer or sponsor) accepted
         an all-in quote that shows the fixed movement fee (10000 centimes = 100 FCFA) and
         the fee is reserved (a fund reservation, purpose MOVE, ref quote_id + ':move'; or the
         customer's hold, checked through `hold_covers`). Earned on verified arrival AND the
         customer's confirmation of readiness. Cancelled before the relay departed: 0.
         Cancelled by the customer after departure: 50 % compensation (a dated setting) from
         the same reservation. The relay may decline any offer, at any time before it starts.

The relay's screen shows one word per state (OFFER_TEXT), identical to the phone's, from
the fixture server/tests/fixtures/relay_offer_states.txt, and the pipeline "pending
contract -> verified block -> earned balance -> withdrawal requested -> paid". A pending
job is never spendable cash.

Reachability probes: the Brain issues a nonce (`challenge`) that the phone must echo back
within CHALLENGE_TTL_MS (`answer`); a correct, timely answer is a successful probe, a wrong
or late one a failed probe. The lead's server may also call `probe(offer_id, ok, now)`
directly from its own peer-side reachability check.
"""
import hashlib
import json
import os
import sqlite3
from typing import Callable, Dict, List, Optional

from brain import fund as _fund
from brain import quotes as _quotes

SCHEMA = """
CREATE TABLE IF NOT EXISTS relay_offers (
    id TEXT PRIMARY KEY, kind TEXT NOT NULL, relay_id TEXT NOT NULL, zone TEXT NOT NULL DEFAULT '',
    quote_id TEXT NOT NULL DEFAULT '', amount_centimes INTEGER NOT NULL, extra_centimes INTEGER NOT NULL DEFAULT 0,
    funding_kind TEXT NOT NULL DEFAULT '', funding_ref TEXT NOT NULL DEFAULT '', campaign_id TEXT NOT NULL DEFAULT '',
    state TEXT NOT NULL, created_at INTEGER NOT NULL, accepted_at INTEGER NOT NULL DEFAULT 0, expires_at INTEGER NOT NULL,
    ended_at INTEGER NOT NULL DEFAULT 0, earned_centimes INTEGER NOT NULL DEFAULT 0, terms_json TEXT NOT NULL DEFAULT '{}',
    created_by TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS relay_offers_relay ON relay_offers(relay_id, created_at);
CREATE INDEX IF NOT EXISTS relay_offers_state ON relay_offers(state);
CREATE TABLE IF NOT EXISTS relay_blocks (
    id TEXT PRIMARY KEY, offer_id TEXT NOT NULL UNIQUE, relay_id TEXT NOT NULL, zone TEXT NOT NULL DEFAULT '',
    started_at INTEGER NOT NULL, ended_at INTEGER NOT NULL DEFAULT 0, verified INTEGER NOT NULL DEFAULT 0,
    probes_json TEXT NOT NULL DEFAULT '[]', state TEXT NOT NULL, earned_centimes INTEGER NOT NULL DEFAULT 0,
    fail_reason TEXT NOT NULL DEFAULT '', nonce TEXT NOT NULL DEFAULT '', nonce_at INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS relay_blocks_relay ON relay_blocks(relay_id, started_at);
CREATE TABLE IF NOT EXISTS move_jobs (
    offer_id TEXT PRIMARY KEY, customer_id TEXT NOT NULL, rendezvous_cell TEXT NOT NULL, departed_at INTEGER NOT NULL DEFAULT 0,
    arrived_at INTEGER NOT NULL DEFAULT 0, ready_at INTEGER NOT NULL DEFAULT 0, confirmed_at INTEGER NOT NULL DEFAULT 0,
    cancelled_at INTEGER NOT NULL DEFAULT 0, cancel_by TEXT NOT NULL DEFAULT '', compensation_centimes INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS relay_settings (
    relay_id TEXT PRIMARY KEY, min_payout_centimes INTEGER NOT NULL DEFAULT 0, battery_floor_pct INTEGER NOT NULL DEFAULT 20,
    window_start_min INTEGER NOT NULL DEFAULT 0, window_end_min INTEGER NOT NULL DEFAULT 1440, available INTEGER NOT NULL DEFAULT 1,
    updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS jobs_audit (
    id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, actor TEXT NOT NULL,
    action TEXT NOT NULL, target TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', allowed INTEGER NOT NULL);
"""

# kinds
CARRY = "CARRY"
STAY = "STAY"
MOVE = "MOVE"

# offer states
OFFERED = "OFFERED"
ACCEPTED = "ACCEPTED"
DECLINED = "DECLINED"
IN_PROGRESS = "IN_PROGRESS"
COMPLETED = "COMPLETED"
CANCELLED = "CANCELLED"
EXPIRED = "EXPIRED"
FAILED = "FAILED"
OPEN_STATES = (OFFERED, ACCEPTED, IN_PROGRESS)

# block states
RUNNING = "RUNNING"
# COMPLETED / FAILED shared with offers

#: One text per state, the relay's screen and nothing else. The Kotlin side reads the
#: same fixture (server/tests/fixtures/relay_offer_states.txt).
OFFER_TEXT = {
    OFFERED: "Proposé",
    ACCEPTED: "Accepté — en attente",
    IN_PROGRESS: "En cours",
    COMPLETED: "Vérifié — gagné",
    DECLINED: "Refusé",
    CANCELLED: "Annulé",
    EXPIRED: "Expiré",
    FAILED: "Non vérifié — rien gagné",
}
#: The earning pipeline in the relay's words (§4): a pending job is never spendable cash.
PIPELINE = ("Contrat en attente", "Vérifié", "Gagné", "Retrait demandé", "Payé")

# funding kinds for a MOVE
FUND = "FUND"
CUSTOMER = "CUSTOMER"

# reasons
NO_RESERVATION = "no_reservation"
NOT_ACCEPTED = "not_accepted"
CAP_REACHED = "daily_cap"
WRONG_STATE = "wrong_state"
NOT_YOURS = "not_yours"
BELOW_MINIMUM = "below_relay_minimum"
OUTSIDE_WINDOW = "outside_window"
UNAVAILABLE = "relay_unavailable"
BAD_INPUT = "bad_input"
BAD_QUOTE = "bad_quote"
NOT_OPERATOR = "not_operator"
NOT_READY = "not_ready"
EXPIRED_REASON = "expired"

OFFER_TTL_MS = 15 * 60_000
CHALLENGE_TTL_MS = 60_000
PROBE_SILENCE_MS = 10 * 60_000
PROBE_MIN_OK = 3
PROBE_MAX_FAIL = 1
PROBE_CONSECUTIVE_FAIL = 2
DAY_MS = _fund.DAY_MS


class JobsError(Exception):
    def __init__(self, message: str, reason: str = "", code: int = 400):
        super().__init__(message)
        self.reason = reason
        self.code = code


def _id(*parts) -> str:
    return hashlib.sha256("|".join(str(p) for p in parts).encode("utf-8")).hexdigest()[:24]


def minute_of_day(now: int) -> int:
    return int(((now + _fund.TZ_OFFSET_MS) % DAY_MS) // 60_000)


class Jobs:
    def __init__(self, db: sqlite3.Connection, fund: "_fund.Fund", quotes: "_quotes.Quote", config: "_quotes.RateConfig",
                 post: Optional[Callable] = None, hold_covers: Optional[Callable[[str, str, int], bool]] = None, operator_ids=()):
        self.db = db
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)
        self.db.commit()
        self.fund = fund
        self.quotes = quotes
        self.config = config
        #: the ledger's post, for a customer-funded move fee (held:<customer> -> earned:<relay>)
        self.post = post
        #: `hold_covers(hold_id, customer_id, amount) -> bool`: does the customer's hold cover the fee?
        self.hold_covers = hold_covers
        self.operator_ids = set(x for x in operator_ids if x)

    # ---- audit ------------------------------------------------------------------------------------

    def _audit(self, now: int, actor: str, action: str, target: str, detail: str = "", allowed: bool = True):
        self.db.execute("INSERT INTO jobs_audit(ts, actor, action, target, detail, allowed) VALUES(?,?,?,?,?,?)",
                        (now, actor or "", action, target, detail, 1 if allowed else 0))
        if not self.db.in_transaction:
            self.db.commit()

    def _refuse(self, now: int, actor: str, action: str, target: str, detail: str, error: JobsError):
        self._audit(now, actor, action, target, "refused: " + detail, allowed=False)
        self.db.commit()
        raise error

    # ---- the relay's settings ---------------------------------------------------------------------

    def settings(self, relay_id: str) -> dict:
        r = self.db.execute("SELECT * FROM relay_settings WHERE relay_id=?", (relay_id,)).fetchone()
        if r is None:
            return {"relay_id": relay_id, "min_payout_centimes": 0, "battery_floor_pct": 20, "window_start_min": 0,
                    "window_end_min": 1440, "available": True}
        return {"relay_id": relay_id, "min_payout_centimes": int(r["min_payout_centimes"]), "battery_floor_pct": int(r["battery_floor_pct"]),
                "window_start_min": int(r["window_start_min"]), "window_end_min": int(r["window_end_min"]), "available": bool(int(r["available"]))}

    def set_settings(self, relay_id: str, now: int, min_payout_centimes: int = None, battery_floor_pct: int = None,
                     window_start_min: int = None, window_end_min: int = None, available: bool = None) -> dict:
        """What the volunteer controls (§4): minimum acceptable payout, battery floor, the
        time window, and a stop switch. Never a tariff."""
        cur = self.settings(relay_id)
        vals = {"min_payout_centimes": cur["min_payout_centimes"] if min_payout_centimes is None else max(0, int(min_payout_centimes)),
                "battery_floor_pct": cur["battery_floor_pct"] if battery_floor_pct is None else min(100, max(0, int(battery_floor_pct))),
                "window_start_min": cur["window_start_min"] if window_start_min is None else min(1440, max(0, int(window_start_min))),
                "window_end_min": cur["window_end_min"] if window_end_min is None else min(1440, max(0, int(window_end_min))),
                "available": cur["available"] if available is None else bool(available)}
        with self.db:
            self.db.execute("INSERT OR REPLACE INTO relay_settings(relay_id, min_payout_centimes, battery_floor_pct, window_start_min, window_end_min, available, updated_at)"
                            " VALUES(?,?,?,?,?,?,?)", (relay_id, vals["min_payout_centimes"], vals["battery_floor_pct"], vals["window_start_min"],
                                                     vals["window_end_min"], 1 if vals["available"] else 0, now))
            self._audit(now, relay_id, "jobs.settings", relay_id, json.dumps(vals, sort_keys=True))
        return self.settings(relay_id)

    def _in_window(self, s: dict, now: int) -> bool:
        m = minute_of_day(now)
        a, b = int(s["window_start_min"]), int(s["window_end_min"])
        if a == b:
            return True                      # an empty window means "any time"
        return a <= m < b if a < b else (m >= a or m < b)   # a window may cross midnight

    def _check_terms(self, relay_id: str, total_reward: int, now: int, action: str, target: str) -> dict:
        s = self.settings(relay_id)
        if not s["available"]:
            self._refuse(now, relay_id, action, target, "relay unavailable", JobsError("this relay has switched availability off", UNAVAILABLE, 409))
        if not self._in_window(s, now):
            self._refuse(now, relay_id, action, target, "outside the relay's window", JobsError("outside the relay's availability window", OUTSIDE_WINDOW, 409))
        if total_reward < int(s["min_payout_centimes"]):
            self._refuse(now, relay_id, action, target, "reward %d below relay minimum %d" % (total_reward, s["min_payout_centimes"]),
                         JobsError("below this relay's minimum payout - not offered", BELOW_MINIMUM, 409))
        return s

    # ---- rows and views --------------------------------------------------------------------------------

    def _offer(self, offer_id: str) -> sqlite3.Row:
        r = self.db.execute("SELECT * FROM relay_offers WHERE id=?", (offer_id,)).fetchone()
        if r is None:
            raise JobsError("unknown offer", code=404)
        return r

    def _mine(self, offer_id: str, relay_id: str) -> sqlite3.Row:
        r = self._offer(offer_id)
        if r["relay_id"] != relay_id:
            raise JobsError("not your offer", NOT_YOURS, 403)
        return r

    def _set_state(self, offer_id: str, state: str, now: int, **cols):
        sets = ["state=?", "updated_at=?"]
        args = [state, now]
        for k, v in cols.items():
            sets.append(k + "=?")
            args.append(v)
        args.append(offer_id)
        self.db.execute("UPDATE relay_offers SET %s WHERE id=?" % ", ".join(sets), args)

    def offer_view(self, r: sqlite3.Row, now: int) -> dict:
        cfg = self.config.current(now)
        terms = json.loads(r["terms_json"] or "{}")
        out = {"id": r["id"], "kind": r["kind"], "zone": r["zone"], "quote_id": r["quote_id"], "amount": int(r["amount_centimes"]),
               "extra": int(r["extra_centimes"]), "state": r["state"], "text": OFFER_TEXT.get(r["state"], OFFER_TEXT[FAILED]),
               "created_at": int(r["created_at"]), "accepted_at": int(r["accepted_at"]), "expires_at": int(r["expires_at"]),
               "ended_at": int(r["ended_at"]), "earned": int(r["earned_centimes"]), "funding_kind": r["funding_kind"],
               "campaign_id": r["campaign_id"], "battery_floor_pct": int(terms.get("battery_floor_pct", 0)),
               "min_payout_centimes": int(terms.get("min_payout_centimes", 0)), "window_start_min": int(terms.get("window_start_min", 0)),
               "window_end_min": int(terms.get("window_end_min", 1440)), "block_minutes": int(cfg[_quotes.K_BLOCK_MINUTES]),
               "blocks_per_day": int(cfg[_quotes.K_BLOCKS_PER_DAY]), "probes_required": PROBE_MIN_OK, "probes_max_failed": PROBE_MAX_FAIL,
               "pipeline_stage": self._stage(r["state"]), "rendezvous_cell": ""}
        if r["kind"] == STAY:
            b = self.db.execute("SELECT * FROM relay_blocks WHERE offer_id=?", (r["id"],)).fetchone()
            if b is not None:
                probes = json.loads(b["probes_json"])
                out.update(block_started_at=int(b["started_at"]), block_ended_at=int(b["ended_at"]), block_state=b["state"],
                           probes_ok=sum(1 for p in probes if p["ok"]), probes_failed=sum(1 for p in probes if not p["ok"]),
                           block_elapsed_ms=max(0, (int(b["ended_at"]) or now) - int(b["started_at"])), fail_reason=b["fail_reason"])
        if r["kind"] == MOVE:
            m = self.db.execute("SELECT * FROM move_jobs WHERE offer_id=?", (r["id"],)).fetchone()
            if m is not None:
                out.update(rendezvous_cell=m["rendezvous_cell"], departed_at=int(m["departed_at"]), arrived_at=int(m["arrived_at"]),
                           ready_at=int(m["ready_at"]), confirmed_at=int(m["confirmed_at"]), cancel_by=m["cancel_by"],
                           compensation=int(m["compensation_centimes"]))
        return out

    @staticmethod
    def _stage(state: str) -> int:
        """Index into PIPELINE: 0 pending contract, 1 verified, 2 earned; withdrawal stages are the ledger's."""
        return {OFFERED: 0, ACCEPTED: 0, IN_PROGRESS: 0, COMPLETED: 2}.get(state, -1)

    def my_offers(self, relay_id: str, now: int, limit: int = 30) -> dict:
        """The relay's list, newest first, with the state word and the pipeline: what is
        pending, what was verified and earned, and the earnings this module knows of.
        Withdrawal states belong to the ledger's wallet; the screen joins the two."""
        rows = self.db.execute("SELECT * FROM relay_offers WHERE relay_id=? ORDER BY created_at DESC LIMIT ?", (relay_id, limit)).fetchall()
        offers = [self.offer_view(r, now) for r in rows]
        pending = sum(o["amount"] for o in offers if o["state"] in OPEN_STATES)
        earned = int(self.db.execute("SELECT COALESCE(SUM(earned_centimes),0) AS s FROM relay_offers WHERE relay_id=?", (relay_id,)).fetchone()["s"])
        return {"offers": offers, "settings": self.settings(relay_id), "blocks_today": self._blocks_today(relay_id, now),
                "blocks_per_day": int(self.config.get(_quotes.K_BLOCKS_PER_DAY, now)),
                "pipeline": {"stages": list(PIPELINE), "pending_centimes": pending, "earned_centimes": earned},
                "states": dict(OFFER_TEXT)}

    # ---- CARRY -------------------------------------------------------------------------------------------

    def offer_carry(self, relay_id: str, zone: str, quote: dict, now: int, actor: str = "") -> dict:
        """A stationary relay is offered one session under a signed quote. The amount shown
        is the quote's relay_share for the full allowance; it is earned only when the
        settlement posts it, scaled to the verified bytes."""
        if not self.quotes.verify(quote):
            raise JobsError("the quote does not verify", BAD_QUOTE, 403)
        if self.quotes.expired(quote, now):
            raise JobsError("the quote has expired", EXPIRED_REASON, 409)
        if not quote.get("relay_present") or int(quote.get("relay_share", 0)) <= 0:
            raise JobsError("this quote has no relay share", BAD_QUOTE)
        s = self._check_terms(relay_id, int(quote["relay_share"]), now, "jobs.offer_carry", relay_id)
        oid = _id("offer", CARRY, relay_id, quote["quote_id"], now)
        with self.db:
            self.db.execute("INSERT INTO relay_offers(id, kind, relay_id, zone, quote_id, amount_centimes, state, created_at, expires_at, terms_json, created_by, updated_at)"
                            " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)", (oid, CARRY, relay_id, zone, quote["quote_id"], int(quote["relay_share"]), OFFERED, now,
                                                                min(now + OFFER_TTL_MS, int(quote["expires_at"])), json.dumps(s), actor, now))
            self._audit(now, actor, "jobs.offer", oid, "CARRY %d for %s" % (int(quote["relay_share"]), relay_id))
        return self.offer_view(self._offer(oid), now)

    def relay_share_for(self, settlement: dict, quote: Optional[dict] = None) -> int:
        """The relay's centimes for a settled session, from the SIGNED QUOTE named by the
        settlement (`quote_id`) or passed in - not a fixed percentage. Scaled to what was
        verified: gross / customer_total of the quote when the customer paid; delivered
        bytes / allowance when a sponsor paid (gross is then zero on the customer side).
        Zero when nothing was delivered or no quote names a relay."""
        q = quote or (self.quotes.get(str(settlement.get("quote_id", ""))) if settlement.get("quote_id") else None)
        if q is None or not self.quotes.verify(q):
            return 0
        share = int(q.get("relay_share", 0))
        if share <= 0:
            return 0
        gross = int(settlement.get("gross", 0))
        total = int(q.get("customer_total", 0))
        if total > 0:
            return min(share, (share * gross + total // 2) // total) if gross > 0 else 0
        allowance = int(q.get("allowance_bytes", 0))
        down = int(settlement.get("bytes_down", 0))
        if allowance <= 0 or down <= 0:
            return 0
        return min(share, (share * min(down, allowance) + allowance // 2) // allowance)

    # ---- STAY ---------------------------------------------------------------------------------------------

    def _blocks_today(self, relay_id: str, now: int) -> int:
        start = _fund.day_start(now)
        return int(self.db.execute("SELECT COUNT(*) AS n FROM relay_offers WHERE relay_id=? AND kind=? AND created_at>=? AND created_at<? AND state IN (?,?,?,?)",
                                   (relay_id, STAY, start, start + DAY_MS, OFFERED, ACCEPTED, IN_PROGRESS, COMPLETED)).fetchone()["n"])

    def offer_stay(self, who: str, relay_id: str, zone: str, campaign_id: Optional[str], now: int) -> dict:
        """Prok (an operator identity) or a sponsor's campaign commissions one 30-minute
        block. The reward is reserved FIRST (fund.reserve BLOCK); no reservation, no offer.
        Refused past four blocks today, outside the relay's window, below its minimum."""
        if who not in self.operator_ids:
            self._refuse(now, who, "jobs.offer_stay", relay_id, "not an operator", JobsError("only an operator commissions availability", NOT_OPERATOR, 403))
        if not zone:
            raise JobsError("a zone is required", BAD_INPUT)
        cfg = self.config.current(now)
        reward = int(cfg[_quotes.K_BLOCK_REWARD])
        cap = int(cfg[_quotes.K_BLOCKS_PER_DAY])
        if self._blocks_today(relay_id, now) >= cap:
            self._refuse(now, who, "jobs.offer_stay", relay_id, "daily cap %d" % cap, JobsError("this relay has reached today's block cap", CAP_REACHED, 409))
        s = self._check_terms(relay_id, reward, now, "jobs.offer_stay", relay_id)
        oid = _id("offer", STAY, relay_id, zone, now, os.urandom(4).hex())
        try:
            res = self.fund.reserve(_fund.BLOCK, oid, reward, campaign_id, now, zone=zone, actor=who)
        except _fund.FundError as e:
            self._refuse(now, who, "jobs.offer_stay", relay_id, "fund: " + (e.reason or str(e)),
                         JobsError("no cleared budget for this block - not offered", NO_RESERVATION, 409))
        with self.db:
            self.db.execute("INSERT INTO relay_offers(id, kind, relay_id, zone, amount_centimes, funding_kind, funding_ref, campaign_id, state, created_at, expires_at, terms_json, created_by, updated_at)"
                            " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", (oid, STAY, relay_id, zone, reward, FUND, res["ref"], res["campaign_id"], OFFERED, now,
                                                                     now + OFFER_TTL_MS, json.dumps(s), who, now))
            self._audit(now, who, "jobs.offer", oid, "STAY %d in %s for %s" % (reward, zone, relay_id))
        return self.offer_view(self._offer(oid), now)

    def start_block(self, offer_id: str, relay_id: str, now: int) -> dict:
        """The relay is in the zone and ready: the 30 minutes start now. Accepted first."""
        r = self._mine(offer_id, relay_id)
        if r["kind"] != STAY or r["state"] != ACCEPTED:
            raise JobsError("offer is " + r["state"], WRONG_STATE, 409)
        if now >= int(r["expires_at"]):
            self._expire(r, now)
            raise JobsError("this offer has expired", EXPIRED_REASON, 409)
        with self.db:
            self.db.execute("INSERT INTO relay_blocks(id, offer_id, relay_id, zone, started_at, state, updated_at) VALUES(?,?,?,?,?,?,?)",
                            (_id("block", offer_id), offer_id, relay_id, r["zone"], now, RUNNING, now))
            self._set_state(offer_id, IN_PROGRESS, now)
            self._audit(now, relay_id, "jobs.block.start", offer_id)
        return self.offer_view(self._offer(offer_id), now)

    def challenge(self, offer_id: str, relay_id: str, now: int) -> dict:
        """A fresh nonce the phone must echo within CHALLENGE_TTL_MS. Asking for a new one
        while the previous is unanswered and past its TTL counts that one as failed."""
        r = self._mine(offer_id, relay_id)
        b = self._running_block(r, now)
        if b["nonce"] and now - int(b["nonce_at"]) > CHALLENGE_TTL_MS:
            self._record_probe(r, b, False, now, "unanswered challenge")
            r = self._offer(offer_id)
            b = self._running_block(r, now)
        nonce = os.urandom(8).hex()
        with self.db:
            self.db.execute("UPDATE relay_blocks SET nonce=?, nonce_at=?, updated_at=? WHERE offer_id=?", (nonce, now, now, offer_id))
        return {"offer_id": offer_id, "nonce": nonce, "answer_by": now + CHALLENGE_TTL_MS}

    def answer(self, offer_id: str, relay_id: str, nonce: str, now: int) -> dict:
        r = self._mine(offer_id, relay_id)
        b = self._running_block(r, now)
        ok = bool(b["nonce"]) and nonce == b["nonce"] and now - int(b["nonce_at"]) <= CHALLENGE_TTL_MS
        with self.db:
            self.db.execute("UPDATE relay_blocks SET nonce='', nonce_at=0 WHERE offer_id=?", (offer_id,))
        return self.probe(offer_id, ok, now, detail="challenge " + ("answered" if ok else "wrong or late"))

    def _running_block(self, r: sqlite3.Row, now: int) -> sqlite3.Row:
        if r["kind"] != STAY or r["state"] != IN_PROGRESS:
            raise JobsError("no block is running on this offer", WRONG_STATE, 409)
        b = self.db.execute("SELECT * FROM relay_blocks WHERE offer_id=? AND state=?", (r["id"], RUNNING)).fetchone()
        if b is None:
            raise JobsError("no block is running on this offer", WRONG_STATE, 409)
        return b

    def probe(self, offer_id: str, ok: bool, now: int, detail: str = "") -> dict:
        """One reachability result on the running block (the lead's server calls this from
        its own probe, or `answer` calls it). Applies the ending rules immediately."""
        r = self._offer(offer_id)
        b = self._running_block(r, now)
        return self._record_probe(r, b, bool(ok), now, detail)

    def _record_probe(self, r: sqlite3.Row, b: sqlite3.Row, ok: bool, now: int, detail: str) -> dict:
        probes = json.loads(b["probes_json"])
        probes.append({"at": now, "ok": bool(ok), "detail": detail[:60]})
        with self.db:
            self.db.execute("UPDATE relay_blocks SET probes_json=?, updated_at=? WHERE offer_id=?", (json.dumps(probes), now, r["id"]))
        consecutive_fail = len(probes) >= PROBE_CONSECUTIVE_FAIL and all(not p["ok"] for p in probes[-PROBE_CONSECUTIVE_FAIL:])
        if consecutive_fail:
            self._end_block(r, b, now, verified=False, reason="%d consecutive failed probes" % PROBE_CONSECUTIVE_FAIL)
        elif now - int(b["started_at"]) >= self._block_ms(now):
            self._evaluate(r, self.db.execute("SELECT * FROM relay_blocks WHERE offer_id=?", (r["id"],)).fetchone(), now)
        return self.offer_view(self._offer(r["id"]), now)

    def _block_ms(self, now: int) -> int:
        return int(self.config.get(_quotes.K_BLOCK_MINUTES, now)) * 60_000

    @staticmethod
    def verified_by_probes(probes: List[dict], started_at: int, block_ms: int) -> bool:
        """THE verification rule: >= 3 successful probes spread over the block (at least one
        in each third), at most one failed probe. Pure, so a test can hold the boundaries."""
        oks = [int(p["at"]) for p in probes if p["ok"]]
        fails = sum(1 for p in probes if not p["ok"])
        if len(oks) < PROBE_MIN_OK or fails > PROBE_MAX_FAIL:
            return False
        third = block_ms / 3.0
        for i in range(3):
            lo, hi = started_at + i * third, started_at + (i + 1) * third
            if not any(lo <= t < hi or (i == 2 and t == started_at + block_ms) for t in oks):
                return False
        return True

    def _evaluate(self, r: sqlite3.Row, b: sqlite3.Row, now: int):
        probes = json.loads(b["probes_json"])
        ok = self.verified_by_probes(probes, int(b["started_at"]), self._block_ms(now))
        self._end_block(r, b, now, verified=ok, reason="" if ok else "probes not spread or too many failed")

    def _end_block(self, r: sqlite3.Row, b: sqlite3.Row, now: int, verified: bool, reason: str):
        """The earning moment of STAY: a verified block settles its reservation to earned:<relay>.
        Anything else releases it; nothing is paid."""
        reward = int(r["amount_centimes"])
        with self.db:
            if verified:
                self.fund.settle(r["funding_ref"], reward, "earned:" + r["relay_id"], now, actor=r["relay_id"], memo="verified block")
                self.db.execute("UPDATE relay_blocks SET state=?, ended_at=?, verified=1, earned_centimes=?, updated_at=? WHERE offer_id=?",
                                (COMPLETED, now, reward, now, r["id"]))
                self._set_state(r["id"], COMPLETED, now, ended_at=now, earned_centimes=reward)
                self._audit(now, r["relay_id"], "jobs.block.verified", r["id"], "%d" % reward)
            else:
                self.fund.release(r["funding_ref"], now, actor=r["relay_id"], memo="block failed: " + reason)
                self.db.execute("UPDATE relay_blocks SET state=?, ended_at=?, verified=0, fail_reason=?, updated_at=? WHERE offer_id=?",
                                (FAILED, now, reason, now, r["id"]))
                self._set_state(r["id"], FAILED, now, ended_at=now)
                self._audit(now, r["relay_id"], "jobs.block.failed", r["id"], reason)

    # ---- MOVE ---------------------------------------------------------------------------------------------

    def request_move(self, customer_id: str, relay_id: str, rendezvous_cell: str, quote_id: str, funding_kind: str, funding_ref: str, now: int) -> dict:
        """The customer asked for help and accepted the all-in quote (movement_fee shown); the
        fee is reserved. Only then is the relay invited to the coarse rendezvous cell."""
        q = self.quotes.get(quote_id)
        if q is None or not self.quotes.verify(q):
            raise JobsError("unknown or tampered quote", BAD_QUOTE, 403)
        if self.quotes.expired(q, now):
            raise JobsError("the quote has expired", EXPIRED_REASON, 409)
        # the fee is a SIGNED field of the quote: whatever the setting was when it was issued
        fee = int(q.get("movement_fee", 0))
        if fee <= 0:
            raise JobsError("this quote carries no movement fee - a move needs an all-in quote", BAD_QUOTE, 409)
        if not rendezvous_cell or not relay_id or relay_id == customer_id:
            raise JobsError("a rendezvous cell and a distinct relay are required", BAD_INPUT)
        payer = q["paying_party"]
        if payer == _quotes.CUSTOMER:
            if self.quotes.accepted_by(quote_id, "CUSTOMER") != customer_id:
                self._refuse(now, customer_id, "jobs.request_move", quote_id, "customer has not accepted the all-in quote",
                             JobsError("accept the all-in quote first", NOT_ACCEPTED, 409))
        elif payer != _quotes.SPONSOR:
            raise JobsError("nobody pays this quote", BAD_QUOTE, 409)
        # the fee must already be reserved from a named budget (§4: "reserved ... before they move")
        if funding_kind == FUND:
            res = self.fund.reservation(funding_ref)
            if res is None or res["state"] != _fund.RESERVED or res["purpose"] != _fund.MOVE or int(res["amount"]) < fee:
                self._refuse(now, customer_id, "jobs.request_move", quote_id, "no MOVE reservation " + funding_ref,
                             JobsError("the movement fee is not reserved", NO_RESERVATION, 409))
        elif funding_kind == CUSTOMER:
            if payer != _quotes.CUSTOMER:
                raise JobsError("a sponsored move is funded by the sponsor's reservation", BAD_INPUT)
            if self.hold_covers is None or not self.hold_covers(funding_ref, customer_id, int(q.get("all_in_total", fee))):
                self._refuse(now, customer_id, "jobs.request_move", quote_id, "hold does not cover the all-in total",
                             JobsError("the customer's credit hold does not cover the all-in quote", NO_RESERVATION, 409))
        else:
            raise JobsError("funding_kind must be FUND or CUSTOMER", BAD_INPUT)
        s = self._check_terms(relay_id, fee + int(q.get("relay_share", 0)), now, "jobs.request_move", relay_id)
        oid = _id("offer", MOVE, relay_id, quote_id, now)
        with self.db:
            self.db.execute("INSERT INTO relay_offers(id, kind, relay_id, zone, quote_id, amount_centimes, extra_centimes, funding_kind, funding_ref, campaign_id, state, created_at, expires_at, terms_json, created_by, updated_at)"
                            " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", (oid, MOVE, relay_id, rendezvous_cell, quote_id, fee, int(q.get("relay_share", 0)), funding_kind, funding_ref,
                                                                        q.get("sponsor_campaign_id", ""), OFFERED, now, min(now + OFFER_TTL_MS, int(q["expires_at"])), json.dumps(s), customer_id, now))
            self.db.execute("INSERT INTO move_jobs(offer_id, customer_id, rendezvous_cell) VALUES(?,?,?)", (oid, customer_id, rendezvous_cell))
            self._audit(now, customer_id, "jobs.offer", oid, "MOVE %d + data %d for %s at %s" % (fee, int(q.get("relay_share", 0)), relay_id, rendezvous_cell))
        return self.offer_view(self._offer(oid), now)

    def _move(self, offer_id: str) -> sqlite3.Row:
        m = self.db.execute("SELECT * FROM move_jobs WHERE offer_id=?", (offer_id,)).fetchone()
        if m is None:
            raise JobsError("not a move job", WRONG_STATE, 409)
        return m

    def departed(self, offer_id: str, relay_id: str, now: int) -> dict:
        r = self._mine(offer_id, relay_id)
        if r["kind"] != MOVE or r["state"] != ACCEPTED:
            raise JobsError("offer is " + r["state"], WRONG_STATE, 409)
        with self.db:
            self.db.execute("UPDATE move_jobs SET departed_at=? WHERE offer_id=?", (now, offer_id))
            self._set_state(offer_id, IN_PROGRESS, now)
            self._audit(now, relay_id, "jobs.move.departed", offer_id)
        return self.offer_view(self._offer(offer_id), now)

    def arrived(self, offer_id: str, relay_id: str, now: int) -> dict:
        r = self._mine(offer_id, relay_id)
        if r["kind"] != MOVE or r["state"] != IN_PROGRESS:
            raise JobsError("offer is " + r["state"], WRONG_STATE, 409)
        with self.db:
            self.db.execute("UPDATE move_jobs SET arrived_at=? WHERE offer_id=? AND arrived_at=0", (now, offer_id))
            self._audit(now, relay_id, "jobs.move.arrived", offer_id)
        return self.offer_view(self._offer(offer_id), now)

    def ready(self, offer_id: str, relay_id: str, now: int) -> dict:
        """The relay says it is serving. Not yet earned: the customer confirms."""
        r = self._mine(offer_id, relay_id)
        m = self._move(offer_id)
        if r["state"] != IN_PROGRESS or not int(m["arrived_at"]):
            raise JobsError("arrive first", WRONG_STATE, 409)
        with self.db:
            self.db.execute("UPDATE move_jobs SET ready_at=? WHERE offer_id=? AND ready_at=0", (now, offer_id))
            self._audit(now, relay_id, "jobs.move.ready", offer_id)
        return self.offer_view(self._offer(offer_id), now)

    def customer_confirms_ready(self, customer_id: str, offer_id: str, now: int) -> dict:
        """THE earning moment of MOVE: verified arrival + the relay's readiness confirmed by
        the customer side. The fixed fee settles to earned:<relay> from the reservation."""
        r = self._offer(offer_id)
        m = self._move(offer_id)
        if m["customer_id"] != customer_id:
            raise JobsError("not your job", NOT_YOURS, 403)
        if r["state"] != IN_PROGRESS:
            raise JobsError("offer is " + r["state"], WRONG_STATE, 409)
        if not int(m["arrived_at"]) or not int(m["ready_at"]):
            raise JobsError("the relay has not arrived and declared ready yet", NOT_READY, 409)
        fee = int(r["amount_centimes"])
        with self.db:
            self._pay_move(r, m, fee, now, "arrived and ready, confirmed by the customer")
            self.db.execute("UPDATE move_jobs SET confirmed_at=? WHERE offer_id=?", (now, offer_id))
            self._set_state(offer_id, COMPLETED, now, ended_at=now, earned_centimes=fee)
            self._audit(now, customer_id, "jobs.move.confirmed", offer_id, "%d" % fee)
        return self.offer_view(self._offer(offer_id), now)

    def _pay_move(self, r: sqlite3.Row, m: sqlite3.Row, amount: int, now: int, memo: str):
        """Pay [amount] of the move fee from the reservation named on the offer; release the rest."""
        if r["funding_kind"] == FUND:
            self.fund.settle(r["funding_ref"], amount, "earned:" + r["relay_id"], now, actor=r["relay_id"], memo=memo)
        else:
            if amount > 0:
                if self.post is None:
                    raise JobsError("a customer-funded move needs the ledger wired", NO_RESERVATION, 500)
                self.post(now, "MOVE_FEE", "held:" + m["customer_id"], "earned:" + r["relay_id"], amount, "move:" + r["id"], memo, r["relay_id"])
            # the remainder of the customer's hold stays for the session; the ledger releases it at settlement

    def cancel(self, offer_id: str, by: str, now: int) -> dict:
        """Cancellation: before departure, nobody earns; by the customer after the relay
        departed, the relay gets the dated compensation (50 %) from the same reservation;
        by the relay after departing, nothing. Never after completion."""
        r = self._offer(offer_id)
        if r["state"] not in OPEN_STATES:
            raise JobsError("offer is " + r["state"], WRONG_STATE, 409)
        m = self.db.execute("SELECT * FROM move_jobs WHERE offer_id=?", (offer_id,)).fetchone()
        is_customer = m is not None and m["customer_id"] == by
        is_relay = r["relay_id"] == by
        if not (is_customer or is_relay or by in self.operator_ids):
            raise JobsError("not a party to this offer", NOT_YOURS, 403)
        comp = 0
        with self.db:
            if r["kind"] == MOVE:
                if is_customer and int(m["departed_at"]) > 0:
                    comp = int(r["amount_centimes"]) * int(self.config.get(_quotes.K_MOVE_CANCEL_PCT, now)) // 100
                if r["funding_kind"] == FUND:
                    if comp > 0:
                        self.fund.settle(r["funding_ref"], comp, "earned:" + r["relay_id"], now, actor=by, memo="cancelled after departure")
                    else:
                        self.fund.release(r["funding_ref"], now, actor=by, memo="move cancelled")
                elif comp > 0:
                    self._pay_move(r, m, comp, now, "cancelled by the customer after departure")
                self.db.execute("UPDATE move_jobs SET cancelled_at=?, cancel_by=?, compensation_centimes=? WHERE offer_id=?",
                                (now, "customer" if is_customer else ("relay" if is_relay else "operator"), comp, offer_id))
            elif r["kind"] == STAY:
                b = self.db.execute("SELECT * FROM relay_blocks WHERE offer_id=? AND state=?", (offer_id, RUNNING)).fetchone()
                if b is not None:
                    self.db.execute("UPDATE relay_blocks SET state=?, ended_at=?, fail_reason='cancelled', updated_at=? WHERE offer_id=?", (FAILED, now, now, offer_id))
                self.fund.release(r["funding_ref"], now, actor=by, memo="stay cancelled")
            self._set_state(offer_id, CANCELLED, now, ended_at=now, earned_centimes=comp)
            self._audit(now, by, "jobs.cancel", offer_id, "compensation %d" % comp)
        return self.offer_view(self._offer(offer_id), now)

    # ---- accept / decline / expiry --------------------------------------------------------------------------

    def accept(self, offer_id: str, relay_id: str, now: int) -> dict:
        r = self._mine(offer_id, relay_id)
        if r["state"] != OFFERED:
            raise JobsError("offer is " + r["state"], WRONG_STATE, 409)
        if now >= int(r["expires_at"]):
            self._expire(r, now)
            raise JobsError("this offer has expired", EXPIRED_REASON, 409)
        with self.db:
            self._set_state(offer_id, ACCEPTED, now, accepted_at=now)
            self._audit(now, relay_id, "jobs.accept", offer_id)
        return self.offer_view(self._offer(offer_id), now)

    def decline(self, offer_id: str, relay_id: str, now: int) -> dict:
        """The volunteer may decline any offer that has not started; the reservation returns."""
        r = self._mine(offer_id, relay_id)
        if r["state"] not in (OFFERED, ACCEPTED):
            raise JobsError("offer is " + r["state"], WRONG_STATE, 409)
        with self.db:
            if r["funding_kind"] == FUND and r["funding_ref"]:
                self.fund.release(r["funding_ref"], now, actor=relay_id, memo="declined")
            self._set_state(offer_id, DECLINED, now, ended_at=now)
            self._audit(now, relay_id, "jobs.decline", offer_id)
        return self.offer_view(self._offer(offer_id), now)

    def _expire(self, r: sqlite3.Row, now: int):
        with self.db:
            if r["funding_kind"] == FUND and r["funding_ref"]:
                res = self.fund.reservation(r["funding_ref"])
                if res and res["state"] == _fund.RESERVED:
                    self.fund.release(r["funding_ref"], now, memo="offer expired")
            self._set_state(r["id"], EXPIRED, now, ended_at=now)
            self._audit(now, "", "jobs.expire", r["id"])

    def sweep(self, now: int) -> Dict[str, int]:
        """The clock's rules: offers past their expiry expire (reservation back); accepted
        blocks never started by expiry expire; running blocks with no probe for ten minutes
        fail; running blocks past thirty minutes are evaluated."""
        n = {"expired": 0, "blocks_failed_silent": 0, "blocks_evaluated": 0}
        for r in self.db.execute("SELECT * FROM relay_offers WHERE state IN (?,?) AND expires_at<=?", (OFFERED, ACCEPTED, now)).fetchall():
            self._expire(r, now)
            n["expired"] += 1
        for b in self.db.execute("SELECT * FROM relay_blocks WHERE state=?", (RUNNING,)).fetchall():
            r = self._offer(b["offer_id"])
            probes = json.loads(b["probes_json"])
            last = max([int(p["at"]) for p in probes] + [int(b["started_at"])])
            if now - int(b["started_at"]) >= self._block_ms(now):
                # the block is over: the spread rule (one ok probe in each third) is the judge,
                # so a late sweep cannot fail a block for silence AFTER it ended
                self._evaluate(r, b, now)
                n["blocks_evaluated"] += 1
            elif now - last > PROBE_SILENCE_MS:
                self._end_block(r, b, now, verified=False, reason="no probe for %d min" % (PROBE_SILENCE_MS // 60_000))
                n["blocks_failed_silent"] += 1
        return n

    # ---- HTTP dispatch ---------------------------------------------------------------------------------------

    def handle_get(self, path: str, who: str, query: dict, now: int):
        """GET /v1/jobs/mine                 -> my_offers (the caller is the relay)
           GET /v1/jobs/offer?id=            -> one offer (relay or the move's customer)
           GET /v1/jobs/settings             -> the caller's relay settings
           GET /v1/jobs/states               -> the state words (fixture) and the pipeline
           GET /v1/jobs/stay/challenge?offer_id= -> a nonce to echo (running block only)
        Returns (code, dict)."""
        try:
            if path == "/v1/jobs/mine":
                return 200, self.my_offers(who, now)
            if path == "/v1/jobs/offer":
                r = self._offer(str(query.get("id", "")))
                m = self.db.execute("SELECT customer_id FROM move_jobs WHERE offer_id=?", (r["id"],)).fetchone()
                if r["relay_id"] != who and not (m and m["customer_id"] == who) and who not in self.operator_ids:
                    return 403, {"error": "not your offer", "reason": NOT_YOURS}
                return 200, self.offer_view(r, now)
            if path == "/v1/jobs/settings":
                return 200, self.settings(who)
            if path == "/v1/jobs/states":
                return 200, {"states": dict(OFFER_TEXT), "pipeline": list(PIPELINE)}
            if path == "/v1/jobs/stay/challenge":
                return 200, self.challenge(str(query.get("offer_id", "")), who, now)
            return 404, {"error": "not found"}
        except JobsError as e:
            return e.code, {"error": str(e), "reason": e.reason}
        except _fund.FundError as e:
            return e.code, {"error": str(e), "reason": e.reason}

    def handle_post(self, path: str, who: str, body: dict, now: int):
        """POST /v1/jobs/settings      {min_payout, battery_floor, window_start, window_end, available}
           POST /v1/jobs/accept        {offer_id}        POST /v1/jobs/decline {offer_id}
           POST /v1/jobs/cancel        {offer_id}        (relay, the move's customer, or an operator)
           POST /v1/jobs/stay/start    {offer_id}        POST /v1/jobs/stay/answer {offer_id, nonce}
           POST /v1/jobs/stay/probe    {offer_id, ok}    (operator only: a server-side reachability result)
           POST /v1/jobs/move/request  {relay_id, rendezvous_cell, quote_id, funding_kind, funding_ref}  (the customer)
           POST /v1/jobs/move/departed {offer_id}  /arrived {offer_id}  /ready {offer_id}   (the relay)
           POST /v1/jobs/move/confirm  {offer_id}        (the customer: the earning moment)
           POST /v1/jobs/operator/stay {relay_id, zone, campaign_id}   (operator: commission a block)
           POST /v1/jobs/operator/carry {relay_id, zone, quote_id}     (operator or the session's source)
        Returns (code, dict)."""
        s = lambda k, d="": str(body.get(k, d) or d)
        try:
            if path == "/v1/jobs/settings":
                g = lambda k: int(body[k]) if body.get(k) is not None and body.get(k) != "" else None
                return 200, self.set_settings(who, now, min_payout_centimes=g("min_payout"), battery_floor_pct=g("battery_floor"),
                                              window_start_min=g("window_start"), window_end_min=g("window_end"),
                                              available=None if body.get("available") is None else bool(body.get("available")))
            if path == "/v1/jobs/accept":
                return 200, self.accept(s("offer_id"), who, now)
            if path == "/v1/jobs/decline":
                return 200, self.decline(s("offer_id"), who, now)
            if path == "/v1/jobs/cancel":
                return 200, self.cancel(s("offer_id"), who, now)
            if path == "/v1/jobs/stay/start":
                return 200, self.start_block(s("offer_id"), who, now)
            if path == "/v1/jobs/stay/answer":
                return 200, self.answer(s("offer_id"), who, s("nonce"), now)
            if path == "/v1/jobs/stay/probe":
                if who not in self.operator_ids:
                    return 403, {"error": "only an operator reports a probe", "reason": NOT_OPERATOR}
                return 200, self.probe(s("offer_id"), bool(body.get("ok", False)), now, detail="operator probe")
            if path == "/v1/jobs/move/request":
                return 200, self.request_move(who, s("relay_id"), s("rendezvous_cell"), s("quote_id"), s("funding_kind", FUND), s("funding_ref"), now)
            if path == "/v1/jobs/move/departed":
                return 200, self.departed(s("offer_id"), who, now)
            if path == "/v1/jobs/move/arrived":
                return 200, self.arrived(s("offer_id"), who, now)
            if path == "/v1/jobs/move/ready":
                return 200, self.ready(s("offer_id"), who, now)
            if path == "/v1/jobs/move/confirm":
                return 200, self.customer_confirms_ready(who, s("offer_id"), now)
            if path == "/v1/jobs/operator/stay":
                return 200, self.offer_stay(who, s("relay_id"), s("zone"), s("campaign_id") or None, now)
            if path == "/v1/jobs/operator/carry":
                q = self.quotes.get(s("quote_id"))
                if q is None:
                    return 404, {"error": "unknown quote", "reason": BAD_QUOTE}
                return 200, self.offer_carry(s("relay_id"), s("zone"), q, now, actor=who)
            return 404, {"error": "not found"}
        except JobsError as e:
            return e.code, {"error": str(e), "reason": e.reason}
        except (_fund.FundError, _quotes.QuoteError) as e:
            return e.code, {"error": str(e), "reason": e.reason}
