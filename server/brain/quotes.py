"""v0.19.0: the end-to-end quote engine and the versioned rate configuration (contract §4,
§6, §10.3).

Two product rules this module exists to keep:

1. **A rate is a dated business rule, never a server setting.** Every amount here (the
   customer cap, the splits, the block reward, the fund percentage, the scout caps, the
   operator bundles) is a row in `quote_config` with an effective date, who set it and
   why. A change is a NEW row; nothing is edited; `history(key)` is the change log. A quote
   carries the `config_version` it was computed under and its own HMAC signature, so a
   later change cannot reinterpret it: `verify()` checks the bytes that were signed, not
   today's settings.

2. **No lossy quote.** The customer price is the cap (25 centimes per decimal MB = 250 FCFA
   per GB). If the source's minimum, the relay's minimum, the platform share and the
   reserve cannot all fit under it, the route is refused with reason `uneconomic` - it is
   never quoted at a loss, never subsidised silently. The shares are allocation ceilings
   inside a feasible quote: platform and reserve are fixed percentages of the customer
   amount, the rest is the source's (and the relay's, at least its minimum).

The free-source cases the contract decides explicitly (§6 last paragraph, §4 "free
upstream"): a free source with no relay is free and needs no quote; a free source with an
unsponsored paid relay means the customer buys DELIVERY (relay + platform + reserve, the
source gets zero); a sponsor campaign makes the SPONSOR the paying party, the customer sees
"Free - sponsored", and the sponsor's promised amount must be reservable from cleared fund
money first (`fund_reserve` callback) or the quote is refused `sponsor_exhausted`.

Money is integer centimes; a decimal MB is 1,000,000 bytes (Market.MB on the phones);
totals round half up per quote, never per MB. Times are ms.
"""
import hashlib
import hmac
import json
import os
import sqlite3
from typing import Callable, Dict, List, Optional

# ---- schema ---------------------------------------------------------------------------------

SCHEMA = """
CREATE TABLE IF NOT EXISTS quote_config (
    id INTEGER PRIMARY KEY AUTOINCREMENT, key TEXT NOT NULL, value_json TEXT NOT NULL,
    effective_from INTEGER NOT NULL, set_by TEXT NOT NULL, reason TEXT NOT NULL, at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS quote_config_key ON quote_config(key, effective_from);
CREATE TABLE IF NOT EXISTS quotes (
    id TEXT PRIMARY KEY, issued_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, config_version TEXT NOT NULL,
    paying_party TEXT NOT NULL, source_kind TEXT NOT NULL, quote_json TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS quote_acceptances (
    quote_id TEXT NOT NULL, role TEXT NOT NULL, party TEXT NOT NULL, decision TEXT NOT NULL, at INTEGER NOT NULL,
    PRIMARY KEY (quote_id, role));
CREATE TABLE IF NOT EXISTS quote_audit (
    id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, actor TEXT NOT NULL,
    action TEXT NOT NULL, target TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', allowed INTEGER NOT NULL);
"""

MB = 1_000_000                      # decimal megabyte, as Market.MB on the phones
GB = 1_000 * MB
QUOTE_TTL_MS = 10 * 60_000

# source kinds
FREE = "FREE"
APPROVED_PAID = "APPROVED_PAID"
MOBILE_DATA = "MOBILE_DATA"
SOURCE_KINDS = (FREE, APPROVED_PAID, MOBILE_DATA)

# paying party
CUSTOMER = "CUSTOMER"
SPONSOR = "SPONSOR"
NONE = "NONE"

# labels (what the quote is FOR)
LABEL_FREE = "free"
LABEL_DELIVERY = "delivery"
LABEL_INTERNET = "internet"
LABEL_SPONSORED = "sponsored"

# refusal reasons
UNECONOMIC = "uneconomic"
SPONSOR_EXHAUSTED = "sponsor_exhausted"
BAD_INPUT = "bad_input"
BACKDATED = "backdated"
UNKNOWN_SETTING = "unknown_setting"
EXPIRED = "expired"
BAD_SIGNATURE = "bad_signature"
UNKNOWN_QUOTE = "unknown_quote"

# bundle verdicts
CHEAPER = "cheaper"
NOT_CHEAPER = "not cheaper"
COMPARABLE = "comparable"
#: within this band of the bundle price the honest word is "comparable", not "cheaper"
COMPARABLE_BAND_PCT = 10

# ---- the launch settings, exactly §10.3 / §6 / §10.2 ------------------------------------------

CONTRACT_DATE = "2026-09-26"
DEFAULTS_SET_BY = "launch-contract"
DEFAULTS_REASON = "ProkNet launch contract " + CONTRACT_DATE + " §10.3 / §6 / §10.2"

K_CAP = "customer_cap_centimes_per_mb"
K_SPLIT_DIRECT = "split_direct_pct"
K_SPLIT_RELAY = "split_one_relay_pct"
K_WITHDRAW_MIN = "withdraw_min_centimes"
K_BLOCK_REWARD = "readiness_block_centimes"
K_BLOCK_MINUTES = "readiness_block_minutes"
K_BLOCKS_PER_DAY = "readiness_blocks_per_day"
K_MOVE_REWARD = "move_reward_centimes"
K_MOVE_CANCEL_PCT = "move_cancel_after_departure_pct"
K_FUND_PCT = "fund_allocation_pct"
K_FUND_LOW = "fund_low_threshold_centimes"
K_SCOUT_FIRST = "scout_first_centimes"
K_SCOUT_REFRESH = "scout_refresh_centimes"
K_SCOUT_DAY = "scout_cap_day_centimes"
K_SCOUT_MONTH = "scout_cap_month_centimes"
K_SCOUT_GLOBAL = "scout_cap_global_month_centimes"
K_SCOUT_EXPIRY = "scout_expiry_days"
K_QUOTE_TTL = "quote_ttl_ms"
K_BUNDLES = "operator_bundles"

DEFAULTS: Dict[str, object] = {
    K_CAP: 25,                                                   # 0.25 FCFA/MB = 250 FCFA/GB
    K_SPLIT_DIRECT: {"source": 80, "platform": 10, "reserve": 10},
    K_SPLIT_RELAY: {"source": 64, "relay": 20, "platform": 10, "reserve": 6},
    K_WITHDRAW_MIN: 1_000 * 100,
    K_BLOCK_REWARD: 20 * 100,
    K_BLOCK_MINUTES: 30,
    K_BLOCKS_PER_DAY: 4,
    K_MOVE_REWARD: 100 * 100,
    K_MOVE_CANCEL_PCT: 50,
    K_FUND_PCT: 40,
    K_FUND_LOW: 500 * 100,                                       # below 500 FCFA unreserved: stop offering sponsored delivery
    K_SCOUT_FIRST: 10 * 100,
    K_SCOUT_REFRESH: 3 * 100,
    K_SCOUT_DAY: 30 * 100,
    K_SCOUT_MONTH: 100 * 100,
    K_SCOUT_GLOBAL: 5_000 * 100,
    K_SCOUT_EXPIRY: 90,
    K_QUOTE_TTL: QUOTE_TTL_MS,
    # §6 "current public comparison", dated. Offers change; this is a dated entry, not a fact.
    K_BUNDLES: {"as_of": CONTRACT_DATE, "offers": [
        {"operator": "MTN", "label": "MTN 1 Go", "bytes": 1 * GB, "price_centimes": 350 * 100, "validity": ""},
        {"operator": "MTN", "label": "MTN 1,5 Go", "bytes": 1_500 * MB, "price_centimes": 400 * 100, "validity": ""},
        {"operator": "MTN", "label": "MTN 6 Go jour + 6 Go nuit (1 jour)", "bytes": 6 * GB, "price_centimes": 650 * 100, "validity": "1 jour"},
        {"operator": "AIRTEL", "label": "Airtel 110 Mo", "bytes": 110 * MB, "price_centimes": 150 * 100, "validity": ""},
        {"operator": "AIRTEL", "label": "Airtel 450 Mo", "bytes": 450 * MB, "price_centimes": 250 * 100, "validity": ""},
    ]},
}

#: The fields whose bytes the signature covers, in this order. Anything else in the dict
#: (examples, per-100 MB lines, the bundle comparison, labels) is display, recomputable.
SIGNED_FIELDS = ("quote_id", "config_version", "source_kind", "label", "paying_party", "rate_centimes_per_mb",
                 "allowance_bytes", "gross_total", "customer_total", "sponsor_total", "source_share", "relay_share",
                 "platform_share", "reserve_share", "movement_fee", "sponsor_campaign_id", "issued_at", "expires_at")


class QuoteError(Exception):
    def __init__(self, message: str, reason: str = "", code: int = 400):
        super().__init__(message)
        self.reason = reason
        self.code = code


def _id(*parts) -> str:
    return hashlib.sha256("|".join(str(p) for p in parts).encode("utf-8")).hexdigest()[:24]


def money(rate_centimes_per_mb: int, allowance_bytes: int) -> int:
    """Centimes for [allowance_bytes] at [rate] per decimal MB, rounded half up ONCE on the
    total - never per MB, so 20 MB at 25 is exactly 500 (5 FCFA)."""
    return (int(rate_centimes_per_mb) * int(allowance_bytes) + MB // 2) // MB


# ---- RateConfig ------------------------------------------------------------------------------

class RateConfig:
    """Versioned settings. Each value is a dated row; `current(now)` is the latest row per
    key whose effective_from <= now, seeded with the launch defaults at effective_from 0."""

    def __init__(self, db: sqlite3.Connection):
        self.db = db
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)
        with self.db:
            for key, value in DEFAULTS.items():
                if self.db.execute("SELECT 1 FROM quote_config WHERE key=?", (key,)).fetchone() is None:
                    self.db.execute("INSERT INTO quote_config(key, value_json, effective_from, set_by, reason, at) VALUES(?,?,?,?,?,?)",
                                    (key, json.dumps(value, sort_keys=True), 0, DEFAULTS_SET_BY, DEFAULTS_REASON, 0))

    def _audit(self, now: int, actor: str, action: str, target: str, detail: str = "", allowed: bool = True):
        self.db.execute("INSERT INTO quote_audit(ts, actor, action, target, detail, allowed) VALUES(?,?,?,?,?,?)",
                        (now, actor, action, target, detail, 1 if allowed else 0))
        if not self.db.in_transaction:
            self.db.commit()

    def _effective_rows(self, now: int) -> List[sqlite3.Row]:
        return self.db.execute(
            "SELECT c.* FROM quote_config c JOIN (SELECT key, MAX(effective_from) AS ef FROM quote_config WHERE effective_from<=? GROUP BY key) m"
            " ON c.key=m.key AND c.effective_from=m.ef ORDER BY c.key, c.id", (now,)).fetchall()

    def current(self, now: int) -> dict:
        """Every setting in force at [now], plus `config_version`: a hash of exactly those
        dated rows, so two quotes computed under the same rules share a version and a
        change (even a scheduled one coming into force) is a different version."""
        out = {}
        latest = {}
        for r in self._effective_rows(now):
            latest[r["key"]] = r          # same key, same effective_from: the later row wins
        canon = []
        for key in sorted(latest):
            r = latest[key]
            out[key] = json.loads(r["value_json"])
            canon.append("%s=%s@%d" % (key, r["value_json"], int(r["effective_from"])))
        out["config_version"] = hashlib.sha256("\n".join(canon).encode("utf-8")).hexdigest()[:16]
        return out

    def get(self, key: str, now: int):
        return self.current(now)[key]

    def set(self, key: str, value, effective_from: int, who: str, reason: str, now: int) -> dict:
        """A dated change. Refused when backdated (a rule cannot start before it was made),
        when unknown, or when nobody said why. Never edits; always a new row."""
        if key not in DEFAULTS:
            raise QuoteError("unknown setting " + key, UNKNOWN_SETTING)
        if not who or not reason.strip():
            raise QuoteError("who and why are required for a rate change", BAD_INPUT)
        if effective_from < now:
            self._audit(now, who, "config.set", key, "refused: backdated", allowed=False)
            raise QuoteError("a rate change cannot be backdated", BACKDATED)
        with self.db:
            self.db.execute("INSERT INTO quote_config(key, value_json, effective_from, set_by, reason, at) VALUES(?,?,?,?,?,?)",
                            (key, json.dumps(value, sort_keys=True), effective_from, who, reason.strip()[:200], now))
            self._audit(now, who, "config.set", key, "effective %d: %s" % (effective_from, reason.strip()[:120]))
        return {"ok": True, "key": key, "effective_from": effective_from, "config_version_after": self.current(effective_from)["config_version"]}

    def history(self, key: str) -> List[dict]:
        """The change log for one key, oldest first: who, when, why, from when."""
        rows = self.db.execute("SELECT * FROM quote_config WHERE key=? ORDER BY id", (key,)).fetchall()
        return [{"value": json.loads(r["value_json"]), "effective_from": int(r["effective_from"]), "set_by": r["set_by"],
                 "reason": r["reason"], "at": int(r["at"])} for r in rows]


# ---- Quote -----------------------------------------------------------------------------------

class Quote:
    """The quote engine. `secret` signs quotes (HMAC-SHA256 over the `|`-joined SIGNED_FIELDS);
    `fund_reserve(purpose, ref, amount, campaign_id, now)` reserves a sponsor's promised
    amount from cleared fund money and raises (or returns a falsy value) when it cannot."""

    def __init__(self, db: sqlite3.Connection, config: RateConfig, secret: bytes,
                 fund_reserve: Optional[Callable[[str, str, int, str, int], object]] = None):
        if not secret:
            raise QuoteError("a signing secret is required")
        self.db = db
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)
        self.config = config
        self.secret = secret if isinstance(secret, bytes) else str(secret).encode("utf-8")
        self.fund_reserve = fund_reserve

    @staticmethod
    def secret_from_env() -> bytes:
        s = os.environ.get("PROK_QUOTE_SECRET", "")
        return s.encode("utf-8") if s else b""

    # ---- signing -----------------------------------------------------------------------------

    def _canonical(self, q: dict) -> str:
        return "|".join(str(q.get(f, "")) for f in SIGNED_FIELDS)

    def sign(self, q: dict) -> str:
        return hmac.new(self.secret, self._canonical(q).encode("utf-8"), hashlib.sha256).hexdigest()

    def verify(self, q: dict) -> bool:
        """The signature over the quote's own signed fields - under whatever configuration
        it was issued with. A rate change after issue does not touch this answer."""
        try:
            sig = str(q.get("signature", ""))
            return bool(sig) and hmac.compare_digest(sig, self.sign(q))
        except Exception:
            return False

    @staticmethod
    def expired(q: dict, now: int) -> bool:
        return int(q.get("expires_at", 0)) <= now

    def valid(self, q: dict, now: int) -> bool:
        return self.verify(q) and not self.expired(q, now)

    # ---- the quote -----------------------------------------------------------------------------

    def quote(self, source_kind: str, source_min_centimes_per_mb: int, relay_min_centimes_per_mb: Optional[int],
              relay_present: bool, sponsor_campaign_id: Optional[str], allowance_bytes: int, now: int,
              move_to_help: bool = False, actor: str = "") -> dict:
        """One signed end-to-end quote, or a refusal with a reason. See the module docstring
        for the cases; the numbers:

        paid source, direct:    rate = cap; platform 10 %, reserve 10 %, source the rest (>= its min)
        paid source, one relay: rate = cap; platform 10 %, reserve 6 %, relay 20 % (>= its min), source the rest (>= its min)
        free source, no relay:  free, paying_party NONE, every amount 0
        free source, relay:     the customer buys delivery: rate = relay (>= min) + platform + reserve, source 0
        any sponsored route:    paying_party SPONSOR, customer_total 0, sponsor_total = the gross (+ movement fee),
                                reserved from the campaign first
        move_to_help:           movement_fee = the fixed move reward, added to whoever pays (all-in)
        """
        if source_kind not in SOURCE_KINDS:
            raise QuoteError("unknown source kind", BAD_INPUT)
        allowance_bytes = int(allowance_bytes)
        if allowance_bytes <= 0:
            raise QuoteError("an allowance in bytes is required", BAD_INPUT)
        if int(source_min_centimes_per_mb or 0) < 0 or int(relay_min_centimes_per_mb or 0) < 0:
            raise QuoteError("a minimum cannot be negative", BAD_INPUT)
        if move_to_help and not relay_present:
            raise QuoteError("a move-to-help needs a relay", BAD_INPUT)
        cfg = self.config.current(now)
        cap = int(cfg[K_CAP])
        ttl = int(cfg[K_QUOTE_TTL])
        relay_min = int(relay_min_centimes_per_mb or 0) if relay_present else 0
        source_min = int(source_min_centimes_per_mb or 0)
        movement_fee = int(cfg[K_MOVE_REWARD]) if move_to_help else 0
        sponsor = (sponsor_campaign_id or "").strip()

        q = {"quote_id": _id("quote", now, source_kind, allowance_bytes, os.urandom(8).hex()),
             "config_version": cfg["config_version"], "source_kind": source_kind,
             "allowance_bytes": allowance_bytes, "movement_fee": movement_fee,
             "sponsor_campaign_id": sponsor, "issued_at": now, "expires_at": now + ttl,
             "mb_bytes": MB, "cap_centimes_per_mb": cap, "relay_present": bool(relay_present)}

        if source_kind == FREE and not relay_present:
            # a free direct source is free: nothing to quote, nothing to sign for money
            q.update(label=LABEL_FREE, paying_party=NONE, rate_centimes_per_mb=0, gross_total=0, customer_total=0,
                     sponsor_total=0, source_share=0, relay_share=0, platform_share=0, reserve_share=0, movement_fee=0)
            return self._finish(q, cfg, now, actor)

        if source_kind == FREE:
            # the customer buys DELIVERY: relay (at least its minimum) + platform + reserve of the
            # one-relay split, computed against the cap; the free source gets zero
            split = cfg[K_SPLIT_RELAY]
            platform_pm = cap * int(split["platform"]) // 100
            reserve_pm = cap * int(split["reserve"]) // 100
            relay_pm = max(cap * int(split["relay"]) // 100, relay_min)
            rate = relay_pm + platform_pm + reserve_pm
            if rate > cap:
                self._refuse(now, actor, "relay minimum + platform + reserve exceed the cap")
            gross = money(rate, allowance_bytes)
            platform = money(platform_pm, allowance_bytes)
            reserve = money(reserve_pm, allowance_bytes)
            relay = gross - platform - reserve
            q.update(label=LABEL_DELIVERY, rate_centimes_per_mb=rate, gross_total=gross,
                     source_share=0, relay_share=relay, platform_share=platform, reserve_share=reserve)
        else:
            split = cfg[K_SPLIT_RELAY] if relay_present else cfg[K_SPLIT_DIRECT]
            rate = cap
            gross = money(rate, allowance_bytes)
            platform = gross * int(split["platform"]) // 100
            reserve = gross * int(split["reserve"]) // 100
            pool = gross - platform - reserve
            src_min = money(source_min, allowance_bytes)
            rel_min = money(relay_min, allowance_bytes)
            if src_min + rel_min > pool:
                self._refuse(now, actor, "source min %d + relay min %d + platform %d + reserve %d > %d at the cap"
                             % (src_min, rel_min, platform, reserve, gross))
            if relay_present:
                relay = max(rel_min, gross * int(split["relay"]) // 100)
                relay = min(relay, pool - src_min)     # the source keeps at least its minimum
            else:
                relay = 0
            source = pool - relay
            q.update(label=LABEL_INTERNET, rate_centimes_per_mb=rate, gross_total=gross,
                     source_share=source, relay_share=relay, platform_share=platform, reserve_share=reserve)

        if sponsor:
            q.update(paying_party=SPONSOR, customer_total=0, sponsor_total=q["gross_total"] + movement_fee, label=LABEL_SPONSORED)
            self._reserve_sponsor(q, now, actor)
        else:
            q.update(paying_party=CUSTOMER, customer_total=q["gross_total"], sponsor_total=0)
        return self._finish(q, cfg, now, actor)

    def _refuse(self, now: int, actor: str, detail: str):
        self._audit(now, actor, "quote", "-", "refused: " + detail, allowed=False)
        raise QuoteError("no feasible price for this route - it is not offered", UNECONOMIC, 409)

    def _reserve_sponsor(self, q: dict, now: int, actor: str):
        """The sponsor's promise must be reservable from CLEARED budget before the quote
        exists. Two reservations when a move is attached, so the move job can settle its
        fee on its own terms (ref = quote_id + ':move')."""
        if self.fund_reserve is None:
            self._audit(now, actor, "quote", q["sponsor_campaign_id"], "refused: no fund wired", allowed=False)
            raise QuoteError("sponsored delivery is not available", SPONSOR_EXHAUSTED, 409)
        try:
            ok = self.fund_reserve("SESSION", q["quote_id"], int(q["gross_total"]), q["sponsor_campaign_id"], now)
            if ok and q["movement_fee"] > 0:
                ok = self.fund_reserve("MOVE", q["quote_id"] + ":move", int(q["movement_fee"]), q["sponsor_campaign_id"], now)
        except Exception as e:      # the fund says why in its own error; the quote says "exhausted"
            ok = None
            detail = getattr(e, "reason", "") or str(e)
            self._audit(now, actor, "quote", q["sponsor_campaign_id"], "refused: sponsor reserve failed: " + detail, allowed=False)
        if not ok:
            raise QuoteError("the sponsor's budget cannot cover this - ask for a new quote", SPONSOR_EXHAUSTED, 409)
        q["reservation_ref"] = q["quote_id"]

    def _finish(self, q: dict, cfg: dict, now: int, actor: str) -> dict:
        q["all_in_total"] = int(q["customer_total"]) + (int(q["movement_fee"]) if q["paying_party"] == CUSTOMER else 0)
        q["signature"] = self.sign(q)
        rate = int(q["rate_centimes_per_mb"])
        gross = int(q["gross_total"])
        # what the owner's screen says per 100 MB, scaled from the signed shares (exact when the
        # allowance is 100 MB, proportional otherwise; display only, not signed)
        per100 = money(rate, 100 * MB)
        def part(share):
            return (int(share) * per100 + gross // 2) // gross if gross > 0 else 0
        q["per_100mb_customer"] = per100 if q["paying_party"] == CUSTOMER else 0
        q["per_100mb_source"] = part(q["source_share"])
        q["per_100mb_relay"] = part(q["relay_share"])
        q["per_100mb_platform"] = part(q["platform_share"])
        q["per_100mb_reserve"] = part(q["reserve_share"])
        q["examples"] = [{"label": "200 Mo", "example_bytes": 200 * MB, "example_total": money(rate, 200 * MB) if q["paying_party"] == CUSTOMER else 0},
                         {"label": "1 Go", "example_bytes": 1 * GB, "example_total": money(rate, 1 * GB) if q["paying_party"] == CUSTOMER else 0}]
        q["comparison"] = self.compare_with_bundles(int(q["allowance_bytes"]), int(q["all_in_total"]), now) if q["paying_party"] == CUSTOMER else None
        q["nobody_uses_it"] = "Si personne ne se connecte, rien n'est facturé et rien n'est gagné."
        with self.db:
            self.db.execute("INSERT INTO quotes(id, issued_at, expires_at, config_version, paying_party, source_kind, quote_json) VALUES(?,?,?,?,?,?,?)",
                            (q["quote_id"], now, q["expires_at"], q["config_version"], q["paying_party"], q["source_kind"], json.dumps(q, sort_keys=True)))
            self._audit(now, actor, "quote", q["quote_id"], "%s %s %d bytes gross %d %s" % (q["source_kind"], q["label"], q["allowance_bytes"], gross, q["paying_party"]))
        return q

    def _audit(self, now: int, actor: str, action: str, target: str, detail: str = "", allowed: bool = True):
        self.db.execute("INSERT INTO quote_audit(ts, actor, action, target, detail, allowed) VALUES(?,?,?,?,?,?)",
                        (now, actor or "", action, target, detail, 1 if allowed else 0))
        if not self.db.in_transaction:
            self.db.commit()

    # ---- stored quotes and acceptance ------------------------------------------------------------

    def get(self, quote_id: str) -> Optional[dict]:
        row = self.db.execute("SELECT quote_json FROM quotes WHERE id=?", (quote_id,)).fetchone()
        return json.loads(row["quote_json"]) if row else None

    def accept(self, quote_id: str, role: str, party: str, now: int, decision: str = "ACCEPT", signature: str = "") -> dict:
        """A party (OWNER, CUSTOMER) accepts or declines a stored quote by id and signature.
        Nobody types a tariff here: the only inputs are the quote's id, its signature and
        yes/no. Refused when unknown, tampered or expired. One decision per role per quote."""
        q = self.get(quote_id)
        if q is None:
            raise QuoteError("unknown quote", UNKNOWN_QUOTE, 404)
        if signature and signature != q["signature"]:
            raise QuoteError("that is not this quote's signature", BAD_SIGNATURE, 403)
        if not self.verify(q):
            raise QuoteError("the stored quote does not verify", BAD_SIGNATURE, 409)
        if decision not in ("ACCEPT", "DECLINE"):
            raise QuoteError("decision must be ACCEPT or DECLINE", BAD_INPUT)
        if decision == "ACCEPT" and self.expired(q, now):
            raise QuoteError("this quote has expired - ask for a new one", EXPIRED, 409)
        role = role.upper()
        with self.db:
            self.db.execute("INSERT OR REPLACE INTO quote_acceptances(quote_id, role, party, decision, at) VALUES(?,?,?,?,?)",
                            (quote_id, role, party, decision, now))
            self._audit(now, party, "quote." + decision.lower(), quote_id, role)
        return {"ok": True, "quote_id": quote_id, "role": role, "decision": decision, "at": now}

    def accepted_by(self, quote_id: str, role: str) -> str:
        """The party that ACCEPTED this quote in [role], or ''."""
        row = self.db.execute("SELECT party FROM quote_acceptances WHERE quote_id=? AND role=? AND decision='ACCEPT'", (quote_id, role.upper())).fetchone()
        return row["party"] if row else ""

    # ---- the honest bundle comparison ---------------------------------------------------------------

    def compare_with_bundles(self, allowance_bytes: int, customer_total: int, now: int) -> dict:
        """The suitable current operator bundle for this amount (the cheapest one that covers
        it; when none does, the biggest one prorated) against the full Prok total, and one
        of three words: cheaper / not cheaper / comparable (within 10 %). Dated; no claim
        beyond this one amount. §6: "ProkNet cannot claim to beat every bundle at every amount."
        """
        cfg = self.config.current(now)
        b = cfg[K_BUNDLES]
        offers = list(b.get("offers", []))
        allowance_bytes = int(allowance_bytes)
        customer_total = int(customer_total)
        covering = [o for o in offers if int(o["bytes"]) >= allowance_bytes]
        prorated = False
        if covering:
            best = min(covering, key=lambda o: int(o["price_centimes"]))
            bundle_price = int(best["price_centimes"])
        elif offers:
            best = max(offers, key=lambda o: int(o["bytes"]))
            bundle_price = (int(best["price_centimes"]) * allowance_bytes + int(best["bytes"]) // 2) // int(best["bytes"])
            prorated = True
        else:
            return {"verdict": COMPARABLE, "bundle_label": "", "bundle_price_centimes": 0, "prok_total_centimes": customer_total,
                    "as_of": b.get("as_of", ""), "sentence": "Aucune offre opérateur enregistrée pour comparer.", "prorated": False}
        if customer_total <= bundle_price * (100 - COMPARABLE_BAND_PCT) // 100:
            verdict = CHEAPER
        elif customer_total >= bundle_price * (100 + COMPARABLE_BAND_PCT) // 100:
            verdict = NOT_CHEAPER
        else:
            verdict = COMPARABLE
        fcfa = lambda c: "%d FCFA" % ((c + 50) // 100)
        word = {CHEAPER: "moins cher que", NOT_CHEAPER: "plus cher que", COMPARABLE: "comparable à"}[verdict]
        sentence = "Pour cette quantité, Prok (%s) est %s %s (%s%s). Offres du %s ; elles changent. Ce n'est pas une promesse d'économie." % (
            fcfa(customer_total), word, best["label"], fcfa(bundle_price), " au prorata" if prorated else "", b.get("as_of", ""))
        if verdict == NOT_CHEAPER:
            sentence += " Préférez le forfait de l'opérateur."
        return {"verdict": verdict, "bundle_label": best["label"], "bundle_operator": best["operator"], "bundle_bytes": int(best["bytes"]),
                "bundle_price_centimes": bundle_price, "prok_total_centimes": customer_total, "prorated": prorated,
                "as_of": b.get("as_of", ""), "sentence": sentence}

    # ---- HTTP dispatch (the lead routes /v1/quotes/... here) -----------------------------------------

    def handle_get(self, path: str, who: str, query: dict, now: int):
        """GET /v1/quotes/config           -> the settings in force (and their version)
           GET /v1/quotes/history?key=     -> the change log of one key
           GET /v1/quotes/compare?bytes=&total= -> the bundle comparison
           GET /v1/quotes/get?id=          -> a stored quote (display copy)
        Returns (code, dict)."""
        try:
            if path == "/v1/quotes/config":
                return 200, self.config.current(now)
            if path == "/v1/quotes/history":
                key = str(query.get("key", ""))
                if key not in DEFAULTS:
                    return 404, {"error": "unknown setting", "reason": UNKNOWN_SETTING}
                return 200, {"key": key, "history": self.config.history(key)}
            if path == "/v1/quotes/compare":
                return 200, self.compare_with_bundles(int(query.get("bytes", 0) or 0), int(query.get("total", 0) or 0), now)
            if path == "/v1/quotes/get":
                q = self.get(str(query.get("id", "")))
                return (200, q) if q else (404, {"error": "unknown quote", "reason": UNKNOWN_QUOTE})
            return 404, {"error": "not found"}
        except QuoteError as e:
            return e.code, {"error": str(e), "reason": e.reason}

    def handle_post(self, path: str, who: str, body: dict, now: int):
        """POST /v1/quotes/quote  {source_kind, source_min, relay_min, relay_present, sponsor_campaign_id, allowance_bytes, move}
                -> the signed quote (the OWNER asks; the source minimum is the owner's validated cost, not a tariff)
           POST /v1/quotes/accept {quote_id, signature, role OWNER|CUSTOMER, decision ACCEPT|DECLINE}
                -> records the caller's decision on that exact quote; nobody types an amount
           POST /v1/quotes/config/set {key, value, effective_from, reason}  (operator: the lead gates by role)
        Returns (code, dict)."""
        s = lambda k, d="": str(body.get(k, d) or d)
        n = lambda k, d=0: int(body.get(k, d) or 0)
        try:
            if path == "/v1/quotes/quote":
                q = self.quote(s("source_kind"), n("source_min"), n("relay_min") if body.get("relay_min") is not None else None,
                               bool(body.get("relay_present", False)), s("sponsor_campaign_id") or None, n("allowance_bytes"), now,
                               move_to_help=bool(body.get("move", False)), actor=who)
                return 200, q
            if path == "/v1/quotes/accept":
                return 200, self.accept(s("quote_id"), s("role", "OWNER"), who, now, decision=s("decision", "ACCEPT"), signature=s("signature"))
            if path == "/v1/quotes/config/set":
                return 200, self.config.set(s("key"), body.get("value"), n("effective_from", now), who, s("reason"), now)
            return 404, {"error": "not found"}
        except QuoteError as e:
            return e.code, {"error": str(e), "reason": e.reason}


# ---- the cross-language fixture --------------------------------------------------------------------

FIXTURE_CASES = (("20 Mo", 20 * MB), ("200 Mo", 200 * MB), ("1 Go", 1 * GB), ("6 Go", 6 * GB))


def fixture_examples() -> dict:
    """The §10.3 examples at the default rate, with both splits, computed by the same code
    that quotes. Python writes; OwnerQuoteViewTest.kt reads and must reproduce every number."""
    cap = int(DEFAULTS[K_CAP])
    d = DEFAULTS[K_SPLIT_DIRECT]
    r = DEFAULTS[K_SPLIT_RELAY]
    rows = []
    for label, b in FIXTURE_CASES:
        total = money(cap, b)
        dp, dr = total * d["platform"] // 100, total * d["reserve"] // 100
        rp, rr, rl = total * r["platform"] // 100, total * r["reserve"] // 100, total * r["relay"] // 100
        rows.append({"label": label, "bytes": b, "customer_total": total, "fcfa": total // 100,
                     "direct_source": total - dp - dr, "direct_platform": dp, "direct_reserve": dr,
                     "relay_source": total - rp - rr - rl, "relay_relay": rl, "relay_platform": rp, "relay_reserve": rr})
    return {"note": "written by brain.quotes.write_fixture; read by tests/test_quotes.py and OwnerQuoteViewTest.kt",
            "as_of": CONTRACT_DATE, "mb": MB, "rate_centimes_per_mb": cap,
            "split_direct": dict(d), "split_one_relay": dict(r), "examples": rows}


def write_fixture(path: str) -> dict:
    data = fixture_examples()
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        json.dump(data, f, indent=1, sort_keys=True)
        f.write("\n")
    return data
