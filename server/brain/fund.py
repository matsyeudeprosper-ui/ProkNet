"""v0.19.0: the Free Internet Fund (contract §5 "Free Internet Fund", §10.3 last paragraph).

The fund is accounts in the SAME double-entry ledger as customer credit, kept apart by
name, so one invariant covers everything and a fund posting can never be a customer
posting by accident. Every movement goes through the ledger's own `post` (a callback:
`post(now, kind, debit, credit, amount, ref, memo, actor)`); balances come back through
`balance(account)`. This module never writes ledger rows itself.

Accounts:
    market:revenue              net money the market module collected (liability, credited by market)
    fund:budget                 cleared money allocated to the fund and not reserved     (liability)
    fund:reserved:<ref>         one reservation, the maximum promised for one job/session (liability)
    fund:spent                  pass-through every settlement crosses, so the spend trail is one query
    promo:<node>                non-withdrawable promotional credit (a payee account)
    campaign:<id>:budget        a sponsor's cleared, unreserved delivery budget         (liability)
    campaign:<id>:reserved:<ref> one campaign reservation
    prok:revenue                the sponsor's platform fee lands here when the campaign clears

The rules, in the words of the contract:
  * 40 % of NET collected market revenue goes to the fund - a dated rule in RateConfig
    (`fund_allocation_pct`), applied by `allocate_market_revenue` which the daily job calls
    with the net figure a treasurer reconciled. Never more than market:revenue holds.
  * A reservation comes ONLY from cleared, unreserved budget (general fund or a cleared
    campaign): never from customer credit, never negative, never above a campaign's cap,
    its daily cap or its zone's daily cap. Otherwise FundError "budget_exhausted".
  * Settlement pays the VERIFIED amount (<= reserved) to the named payee account and
    releases the rest. Release returns everything. Both leave the reservation row with its
    final state; nothing is deleted.
  * `low(now)` says when unreserved budget is under the threshold so callers stop offering
    sponsored delivery - accepted work stays funded because it was reserved first.

Money is integer centimes; times are ms. Days are counted in Brazzaville time (UTC+1).
"""
import hashlib
import json
import sqlite3
from typing import Callable, Dict, List, Optional

from brain import quotes as _quotes

SCHEMA = """
CREATE TABLE IF NOT EXISTS fund_campaigns (
    id TEXT PRIMARY KEY, sponsor_id TEXT NOT NULL, label TEXT NOT NULL, zones_json TEXT NOT NULL DEFAULT '[]',
    starts_at INTEGER NOT NULL, ends_at INTEGER NOT NULL, max_spend_centimes INTEGER NOT NULL,
    platform_fee_centimes INTEGER NOT NULL DEFAULT 0, daily_cap_centimes INTEGER NOT NULL DEFAULT 0,
    zone_daily_cap_centimes INTEGER NOT NULL DEFAULT 0,
    cleared INTEGER NOT NULL DEFAULT 0, cleared_by TEXT NOT NULL DEFAULT '', cleared_at INTEGER NOT NULL DEFAULT 0,
    state TEXT NOT NULL, created_at INTEGER NOT NULL, created_by TEXT NOT NULL, updated_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS fund_reservations (
    id TEXT PRIMARY KEY, campaign_id TEXT NOT NULL, purpose TEXT NOT NULL, ref TEXT NOT NULL UNIQUE,
    zone TEXT NOT NULL DEFAULT '', amount INTEGER NOT NULL, settled_amount INTEGER NOT NULL DEFAULT 0,
    payee TEXT NOT NULL DEFAULT '', state TEXT NOT NULL, at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS fund_reservations_campaign ON fund_reservations(campaign_id, at);
CREATE TABLE IF NOT EXISTS fund_allocations (
    id TEXT PRIMARY KEY, ts INTEGER NOT NULL, net_centimes INTEGER NOT NULL, pct INTEGER NOT NULL,
    amount INTEGER NOT NULL, actor TEXT NOT NULL, memo TEXT NOT NULL DEFAULT '');
CREATE TABLE IF NOT EXISTS fund_audit (
    id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, actor TEXT NOT NULL,
    action TEXT NOT NULL, target TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', allowed INTEGER NOT NULL);
"""

GENERAL = "fund"                       # campaign_id of a reservation from the general fund
BUDGET = "fund:budget"
SPENT = "fund:spent"
MARKET_REVENUE = "market:revenue"
PROK_REVENUE = "prok:revenue"

# reservation purposes
SESSION = "SESSION"
BLOCK = "BLOCK"
MOVE = "MOVE"
SCOUT = "SCOUT"
PURPOSES = (SESSION, BLOCK, MOVE, SCOUT)

# reservation states
RESERVED = "RESERVED"
SETTLED = "SETTLED"
RELEASED = "RELEASED"

# campaign states
ACTIVE = "ACTIVE"
PAUSED = "PAUSED"
CLOSED = "CLOSED"

# reasons
BUDGET_EXHAUSTED = "budget_exhausted"
NOT_CLEARED = "not_cleared"
NOT_TREASURY = "not_treasury"
OUTSIDE_ZONE = "outside_zone"
OUTSIDE_DATES = "outside_dates"
WRONG_STATE = "wrong_state"
BAD_INPUT = "bad_input"
REVENUE_NOT_COLLECTED = "revenue_not_collected"
DUPLICATE = "duplicate"

DAY_MS = 24 * 3_600_000
TZ_OFFSET_MS = 3_600_000               # Brazzaville is UTC+1 all year


class FundError(Exception):
    def __init__(self, message: str, reason: str = "", code: int = 400):
        super().__init__(message)
        self.reason = reason
        self.code = code


def _id(*parts) -> str:
    return hashlib.sha256("|".join(str(p) for p in parts).encode("utf-8")).hexdigest()[:24]


def day_of(now: int) -> int:
    return (now + TZ_OFFSET_MS) // DAY_MS


def day_start(now: int) -> int:
    return day_of(now) * DAY_MS - TZ_OFFSET_MS


class Fund:
    def __init__(self, db: sqlite3.Connection, post: Callable, balance: Callable[[str], int],
                 config: "_quotes.RateConfig", treasury_ids=()):
        self.db = db
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)
        self.db.commit()
        self.post = post
        self.balance = balance
        self.config = config
        self.treasury_ids = set(x for x in treasury_ids if x)

    # ---- audit ------------------------------------------------------------------------------------

    def _audit(self, now: int, actor: str, action: str, target: str, detail: str = "", allowed: bool = True):
        self.db.execute("INSERT INTO fund_audit(ts, actor, action, target, detail, allowed) VALUES(?,?,?,?,?,?)",
                        (now, actor or "", action, target, detail, 1 if allowed else 0))
        if not self.db.in_transaction:
            self.db.commit()

    def _refuse(self, now: int, actor: str, action: str, target: str, detail: str, error: FundError):
        self._audit(now, actor, action, target, "refused: " + detail, allowed=False)
        self.db.commit()
        raise error

    def _require_treasury(self, who: str, action: str, target: str, now: int):
        if who not in self.treasury_ids:
            self._refuse(now, who, action, target, "not a treasury identity",
                         FundError("only a treasury identity may do this", NOT_TREASURY, 403))

    # ---- the 40 % rule ------------------------------------------------------------------------------

    def allocation_pct(self, now: int) -> int:
        return int(self.config.get(_quotes.K_FUND_PCT, now))

    def allocate_market_revenue(self, now: int, net_centimes: int, treasurer: str, memo: str = "") -> dict:
        """40 % (the dated `fund_allocation_pct`) of a NET collected amount moves
        market:revenue -> fund:budget. `net_centimes` is what a treasurer reconciled after
        refunds, taxes and collection costs (the daily job passes it); never more than the
        revenue account actually holds, because uncollected money funds nothing."""
        self._require_treasury(treasurer, "fund.allocate", "-", now)
        net = int(net_centimes)
        if net <= 0:
            raise FundError("net revenue must be positive", BAD_INPUT)
        pct = self.allocation_pct(now)
        amount = net * pct // 100
        if amount <= 0:
            return {"ok": True, "allocated": 0, "pct": pct, "net": net}
        if self.balance(MARKET_REVENUE) < amount:
            self._refuse(now, treasurer, "fund.allocate", "-", "market:revenue holds %d, allocation needs %d" % (self.balance(MARKET_REVENUE), amount),
                         FundError("the revenue account does not hold that much collected money", REVENUE_NOT_COLLECTED, 409))
        aid = _id("alloc", now, net, treasurer)
        with self.db:
            self.post(now, "FUND_ALLOCATE", MARKET_REVENUE, BUDGET, amount, "alloc:" + aid,
                      "%d%% of net %d (%s)" % (pct, net, memo or "daily"), treasurer)
            self.db.execute("INSERT INTO fund_allocations(id, ts, net_centimes, pct, amount, actor, memo) VALUES(?,?,?,?,?,?,?)",
                            (aid, now, net, pct, amount, treasurer, memo))
            self._audit(now, treasurer, "fund.allocate", aid, "%d%% of %d = %d" % (pct, net, amount))
        return {"ok": True, "allocation_id": aid, "allocated": amount, "pct": pct, "net": net, "budget": self.balance(BUDGET)}

    # ---- campaigns -----------------------------------------------------------------------------------

    @staticmethod
    def _budget_account(campaign_id: str) -> str:
        return BUDGET if campaign_id in ("", GENERAL) else "campaign:%s:budget" % campaign_id

    @staticmethod
    def _reserved_account(campaign_id: str, ref: str) -> str:
        return ("fund:reserved:%s" % ref) if campaign_id in ("", GENERAL) else ("campaign:%s:reserved:%s" % (campaign_id, ref))

    def create_campaign(self, who: str, sponsor_id: str, label: str, zones: List[str], starts_at: int, ends_at: int,
                        max_spend_centimes: int, platform_fee_centimes: int, now: int, campaign_id: str = "",
                        daily_cap_centimes: int = 0, zone_daily_cap_centimes: int = 0) -> dict:
        """A sponsor's contract: zone(s), dates, maximum spend, platform fee, itemised. It is
        created UNCLEARED; nothing can be reserved from it until a treasurer records the money.
        The daily cap defaults to max_spend / days (rounded up)."""
        self._require_treasury(who, "fund.campaign.create", label, now)
        if not sponsor_id or not label.strip() or int(max_spend_centimes) <= 0 or int(ends_at) <= int(starts_at):
            raise FundError("sponsor, label, dates and a positive maximum spend are required", BAD_INPUT)
        cid = campaign_id or _id("campaign", sponsor_id, label, now)
        if self.db.execute("SELECT 1 FROM fund_campaigns WHERE id=?", (cid,)).fetchone():
            raise FundError("campaign exists", DUPLICATE, 409)
        days = max(1, (int(ends_at) - int(starts_at) + DAY_MS - 1) // DAY_MS)
        daily = int(daily_cap_centimes) or (int(max_spend_centimes) + days - 1) // days
        with self.db:
            self.db.execute("INSERT INTO fund_campaigns(id, sponsor_id, label, zones_json, starts_at, ends_at, max_spend_centimes, platform_fee_centimes,"
                            " daily_cap_centimes, zone_daily_cap_centimes, cleared, state, created_at, created_by, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,0,?,?,?,?)",
                            (cid, sponsor_id, label.strip()[:80], json.dumps([z for z in zones if z]), int(starts_at), int(ends_at),
                             int(max_spend_centimes), int(platform_fee_centimes), daily, int(zone_daily_cap_centimes), ACTIVE, now, who, now))
            self._audit(now, who, "fund.campaign.create", cid, "%s max %d fee %d daily %d" % (label, int(max_spend_centimes), int(platform_fee_centimes), daily))
        return self.campaign_view(cid, now)

    def clear_campaign(self, who: str, campaign_id: str, rail: str, evidence: str, now: int) -> dict:
        """A treasurer received and reconciled the sponsor's money: the delivery budget is
        credited to the campaign's own pot and the platform fee to Prok, both from the wallet
        that received it (float:<rail>). Once. The evidence (operator reference) is kept."""
        self._require_treasury(who, "fund.campaign.clear", campaign_id, now)
        c = self._campaign(campaign_id)
        if int(c["cleared"]):
            raise FundError("already cleared", WRONG_STATE, 409)
        if not evidence.strip():
            raise FundError("the operator reference of the received transfer is required", BAD_INPUT)
        acct = "float:" + rail.lower()
        with self.db:
            self.post(now, "CAMPAIGN_FUND", acct, self._budget_account(campaign_id), int(c["max_spend_centimes"]),
                      "campaign:" + campaign_id, "sponsor budget " + evidence.strip()[:64], who)
            if int(c["platform_fee_centimes"]) > 0:
                self.post(now, "CAMPAIGN_FEE", acct, PROK_REVENUE, int(c["platform_fee_centimes"]),
                          "campaign-fee:" + campaign_id, "sponsor platform fee " + evidence.strip()[:64], who)
            self.db.execute("UPDATE fund_campaigns SET cleared=1, cleared_by=?, cleared_at=?, updated_at=? WHERE id=?", (who, now, now, campaign_id))
            self._audit(now, who, "fund.campaign.clear", campaign_id, rail + " " + evidence.strip()[:64])
        return self.campaign_view(campaign_id, now)

    def set_campaign_state(self, who: str, campaign_id: str, state: str, now: int) -> dict:
        """PAUSED stops new reservations; CLOSED also returns the unreserved remainder to
        the wallet it came from is a treasury matter outside the ledger, so CLOSED only
        stops reservations and shows what is left."""
        self._require_treasury(who, "fund.campaign.state", campaign_id, now)
        if state not in (ACTIVE, PAUSED, CLOSED):
            raise FundError("unknown state", BAD_INPUT)
        self._campaign(campaign_id)
        with self.db:
            self.db.execute("UPDATE fund_campaigns SET state=?, updated_at=? WHERE id=?", (state, now, campaign_id))
            self._audit(now, who, "fund.campaign.state", campaign_id, state)
        return self.campaign_view(campaign_id, now)

    def _campaign(self, campaign_id: str) -> sqlite3.Row:
        row = self.db.execute("SELECT * FROM fund_campaigns WHERE id=?", (campaign_id,)).fetchone()
        if row is None:
            raise FundError("unknown campaign", code=404)
        return row

    def _reserved_today(self, campaign_id: str, now: int, zone: str = "") -> int:
        q = "SELECT COALESCE(SUM(amount),0) AS s FROM fund_reservations WHERE campaign_id=? AND at>=? AND at<? AND state IN (?,?)"
        args = [campaign_id, day_start(now), day_start(now) + DAY_MS, RESERVED, SETTLED]
        if zone:
            q += " AND zone=?"
            args.append(zone)
        return int(self.db.execute(q, args).fetchone()["s"])

    def campaign_available(self, campaign_id: str, now: int, zone: str = "") -> int:
        """What can still be reserved from this campaign right now: the cleared unreserved
        pot, capped by today's remaining daily cap (and the zone's, when set)."""
        c = self._campaign(campaign_id)
        if not int(c["cleared"]):
            return 0
        avail = self.balance(self._budget_account(campaign_id))
        avail = min(avail, int(c["daily_cap_centimes"]) - self._reserved_today(campaign_id, now))
        if zone and int(c["zone_daily_cap_centimes"]) > 0:
            avail = min(avail, int(c["zone_daily_cap_centimes"]) - self._reserved_today(campaign_id, now, zone))
        return max(0, avail)

    def campaign_view(self, campaign_id: str, now: int) -> dict:
        c = self._campaign(campaign_id)
        agg = self.db.execute("SELECT state, COALESCE(SUM(amount),0) AS a, COALESCE(SUM(settled_amount),0) AS s, COUNT(*) AS n"
                              " FROM fund_reservations WHERE campaign_id=? GROUP BY state", (campaign_id,)).fetchall()
        by = {r["state"]: r for r in agg}
        reserved = int(by[RESERVED]["a"]) if RESERVED in by else 0
        spent = int(by[SETTLED]["s"]) if SETTLED in by else 0
        return {"id": c["id"], "sponsor_id": c["sponsor_id"], "label": c["label"], "zones": json.loads(c["zones_json"]),
                "starts_at": int(c["starts_at"]), "ends_at": int(c["ends_at"]), "max_spend": int(c["max_spend_centimes"]),
                "platform_fee": int(c["platform_fee_centimes"]), "daily_cap": int(c["daily_cap_centimes"]),
                "zone_daily_cap": int(c["zone_daily_cap_centimes"]), "cleared": bool(int(c["cleared"])), "state": c["state"],
                "budget_unreserved": self.balance(self._budget_account(campaign_id)) if int(c["cleared"]) else 0,
                "reserved": reserved, "spent": spent, "reserved_today": self._reserved_today(campaign_id, now),
                "available_now": self.campaign_available(campaign_id, now) if int(c["cleared"]) else 0,
                # aggregate only: the sponsor never sees identities or browsing (§5)
                "connections_delivered": int(by[SETTLED]["n"]) if SETTLED in by else 0}

    def campaigns(self, now: int) -> List[dict]:
        return [self.campaign_view(r["id"], now) for r in self.db.execute("SELECT id FROM fund_campaigns ORDER BY created_at").fetchall()]

    # ---- reservations ------------------------------------------------------------------------------

    def reserve(self, purpose: str, ref: str, amount: int, campaign_id: Optional[str], now: int, zone: str = "", actor: str = "") -> dict:
        """The maximum promised for one job or session, taken out of cleared UNRESERVED
        budget before anything is offered. General fund when campaign_id is empty/'fund'.
        Refused - never partially granted - when the money is not there."""
        if purpose not in PURPOSES:
            raise FundError("unknown purpose", BAD_INPUT)
        amount = int(amount)
        if amount <= 0:
            raise FundError("a reservation must be positive", BAD_INPUT)
        if not ref:
            raise FundError("a reference is required", BAD_INPUT)
        cid = (campaign_id or GENERAL).strip()
        if self.db.execute("SELECT 1 FROM fund_reservations WHERE ref=?", (ref,)).fetchone():
            raise FundError("already reserved under this reference", DUPLICATE, 409)
        if cid != GENERAL:
            c = self._campaign(cid)
            if not int(c["cleared"]):
                self._refuse(now, actor, "fund.reserve", ref, "campaign not cleared", FundError("the sponsor's money is not cleared yet", NOT_CLEARED, 409))
            if c["state"] != ACTIVE:
                self._refuse(now, actor, "fund.reserve", ref, "campaign " + c["state"], FundError("campaign is " + c["state"], WRONG_STATE, 409))
            if not (int(c["starts_at"]) <= now < int(c["ends_at"])):
                self._refuse(now, actor, "fund.reserve", ref, "outside campaign dates", FundError("outside the campaign's dates", OUTSIDE_DATES, 409))
            zones = json.loads(c["zones_json"])
            if zones and zone and zone not in zones:
                self._refuse(now, actor, "fund.reserve", ref, "zone %s not in campaign" % zone, FundError("this campaign does not cover that zone", OUTSIDE_ZONE, 409))
            available = self.campaign_available(cid, now, zone)
        else:
            available = self.balance(BUDGET)
        if available < amount:
            self._refuse(now, actor, "fund.reserve", ref, "%s has %d, needs %d" % (cid, available, amount),
                         FundError("not enough cleared, unreserved budget", BUDGET_EXHAUSTED, 409))
        rid = _id("res", ref)
        with self.db:
            self.post(now, "FUND_RESERVE", self._budget_account(cid), self._reserved_account(cid, ref), amount, "res:" + ref, purpose.lower(), actor)
            self.db.execute("INSERT INTO fund_reservations(id, campaign_id, purpose, ref, zone, amount, state, at, updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                            (rid, cid, purpose, ref, zone or "", amount, RESERVED, now, now))
            self._audit(now, actor, "fund.reserve", ref, "%s %d from %s" % (purpose, amount, cid))
        return {"ok": True, "reservation_id": rid, "ref": ref, "amount": amount, "campaign_id": cid, "purpose": purpose}

    def reservation(self, ref: str) -> Optional[dict]:
        r = self.db.execute("SELECT * FROM fund_reservations WHERE ref=?", (ref,)).fetchone()
        return dict(r) if r else None

    def _open(self, ref: str) -> sqlite3.Row:
        r = self.db.execute("SELECT * FROM fund_reservations WHERE ref=?", (ref,)).fetchone()
        if r is None:
            raise FundError("unknown reservation", code=404)
        if r["state"] != RESERVED:
            raise FundError("reservation is " + r["state"], WRONG_STATE, 409)
        return r

    def settle(self, ref: str, actual_amount: int, payee_account: str, now: int, actor: str = "", memo: str = "") -> dict:
        """The verified amount (<= reserved) goes reserved -> fund:spent -> payee (earned:<relay>,
        promo:<scout>); the remainder goes back to the budget it came from. A zero actual
        is a release. The payee is a ledger account name the CALLER verified the work for."""
        r = self._open(ref)
        actual = int(actual_amount)
        if actual < 0 or actual > int(r["amount"]):
            raise FundError("settled amount must be between 0 and the reserved amount", BAD_INPUT)
        if actual > 0 and not (payee_account.startswith("earned:") or payee_account.startswith("promo:")):
            raise FundError("a fund settlement pays an earned: or promo: account", BAD_INPUT)
        cid = r["campaign_id"]
        reserved_acct = self._reserved_account(cid, ref)
        with self.db:
            if actual > 0:
                self.post(now, "FUND_SPEND", reserved_acct, SPENT, actual, "spend:" + ref, memo or r["purpose"].lower(), actor)
                self.post(now, "FUND_PAY", SPENT, payee_account, actual, "pay:" + ref, memo or r["purpose"].lower(), actor)
            rest = int(r["amount"]) - actual
            if rest > 0:
                self.post(now, "FUND_RELEASE", reserved_acct, self._budget_account(cid), rest, "release:" + ref, "unused", actor)
            self.db.execute("UPDATE fund_reservations SET state=?, settled_amount=?, payee=?, updated_at=? WHERE ref=?",
                            (SETTLED if actual > 0 else RELEASED, actual, payee_account if actual > 0 else "", now, ref))
            self._audit(now, actor, "fund.settle", ref, "%d of %d to %s" % (actual, int(r["amount"]), payee_account))
        return {"ok": True, "ref": ref, "settled": actual, "released": int(r["amount"]) - actual, "payee": payee_account if actual > 0 else ""}

    def release(self, ref: str, now: int, actor: str = "", memo: str = "") -> dict:
        """Nothing was earned: the whole reservation goes back to its budget."""
        r = self._open(ref)
        cid = r["campaign_id"]
        with self.db:
            self.post(now, "FUND_RELEASE", self._reserved_account(cid, ref), self._budget_account(cid), int(r["amount"]), "release:" + ref, memo or "released", actor)
            self.db.execute("UPDATE fund_reservations SET state=?, updated_at=? WHERE ref=?", (RELEASED, now, ref))
            self._audit(now, actor, "fund.release", ref, memo)
        return {"ok": True, "ref": ref, "released": int(r["amount"])}

    # ---- the dashboard and the low-water mark -----------------------------------------------------------

    def low(self, now: int, campaign_id: Optional[str] = None) -> bool:
        """True when the unreserved budget is under `fund_low_threshold_centimes`: stop
        offering sponsored delivery and paid rewards (accepted work is already reserved)."""
        threshold = int(self.config.get(_quotes.K_FUND_LOW, now))
        if campaign_id and campaign_id != GENERAL:
            return self.campaign_available(campaign_id, now) < threshold
        return self.balance(BUDGET) < threshold

    def dashboard(self, now: int) -> dict:
        """Prok's internal numbers (§5): collected revenue, allocated, reserved, spent,
        remaining, per campaign. Sums over this module's own rows plus ledger balances."""
        alloc = self.db.execute("SELECT COALESCE(SUM(net_centimes),0) AS net, COALESCE(SUM(amount),0) AS a, COUNT(*) AS n FROM fund_allocations").fetchone()
        gen = self.db.execute("SELECT state, COALESCE(SUM(amount),0) AS a, COALESCE(SUM(settled_amount),0) AS s FROM fund_reservations WHERE campaign_id=? GROUP BY state", (GENERAL,)).fetchall()
        by = {r["state"]: r for r in gen}
        reserved = int(by[RESERVED]["a"]) if RESERVED in by else 0
        spent = int(by[SETTLED]["s"]) if SETTLED in by else 0
        return {"pct": self.allocation_pct(now), "low_threshold": int(self.config.get(_quotes.K_FUND_LOW, now)),
                "collected_net": int(alloc["net"]), "allocations": int(alloc["n"]), "allocated": int(alloc["a"]),
                "market_revenue_unallocated": self.balance(MARKET_REVENUE),
                "budget_unreserved": self.balance(BUDGET), "reserved": reserved, "spent": spent,
                "remaining": self.balance(BUDGET), "low": self.low(now),
                "promo_outstanding": 0,   # the lead may sum promo:* through the ledger; not this module's book
                "campaigns": self.campaigns(now)}

    # ---- HTTP dispatch (operator screens; the lead gates by role) -----------------------------------------

    def handle_get(self, path: str, who: str, query: dict, now: int):
        """GET /v1/fund/dashboard (treasury)  GET /v1/fund/campaigns (treasury)
           GET /v1/fund/campaign?id= (treasury or the campaign's sponsor: aggregate only)
           GET /v1/fund/low -> {low: bool} (any signed caller)"""
        try:
            if path == "/v1/fund/dashboard":
                self._require_treasury(who, "fund.dashboard", "-", now)
                return 200, self.dashboard(now)
            if path == "/v1/fund/campaigns":
                self._require_treasury(who, "fund.campaigns", "-", now)
                return 200, {"campaigns": self.campaigns(now)}
            if path == "/v1/fund/campaign":
                cid = str(query.get("id", ""))
                c = self._campaign(cid)
                if who not in self.treasury_ids and c["sponsor_id"] != who:
                    return 403, {"error": "not your campaign", "reason": NOT_TREASURY}
                return 200, self.campaign_view(cid, now)
            if path == "/v1/fund/low":
                return 200, {"low": self.low(now, str(query.get("campaign_id", "")) or None)}
            return 404, {"error": "not found"}
        except FundError as e:
            return e.code, {"error": str(e), "reason": e.reason}

    def handle_post(self, path: str, who: str, body: dict, now: int):
        """POST /v1/fund/allocate {net, memo}                       (treasury; the daily job)
           POST /v1/fund/campaign {sponsor_id, label, zones, starts_at, ends_at, max_spend, platform_fee, daily_cap, zone_daily_cap}
           POST /v1/fund/campaign/clear {campaign_id, rail, evidence}
           POST /v1/fund/campaign/state {campaign_id, state}"""
        s = lambda k, d="": str(body.get(k, d) or d)
        n = lambda k, d=0: int(body.get(k, d) or 0)
        try:
            if path == "/v1/fund/allocate":
                return 200, self.allocate_market_revenue(now, n("net"), who, memo=s("memo"))
            if path == "/v1/fund/campaign":
                zones = body.get("zones") or []
                if isinstance(zones, str):
                    zones = [z.strip() for z in zones.split(",") if z.strip()]
                return 200, self.create_campaign(who, s("sponsor_id"), s("label"), list(zones), n("starts_at"), n("ends_at"),
                                                 n("max_spend"), n("platform_fee"), now, campaign_id=s("campaign_id"),
                                                 daily_cap_centimes=n("daily_cap"), zone_daily_cap_centimes=n("zone_daily_cap"))
            if path == "/v1/fund/campaign/clear":
                return 200, self.clear_campaign(who, s("campaign_id"), s("rail", "MTN"), s("evidence"), now)
            if path == "/v1/fund/campaign/state":
                return 200, self.set_campaign_state(who, s("campaign_id"), s("state"), now)
            return 404, {"error": "not found"}
        except FundError as e:
            return e.code, {"error": str(e), "reason": e.reason}
