"""v0.19.0: Prok Market - paid classifieds. Publication is what Prok sells; the trade is not.

The product rule this module keeps (launch contract §5, §10.3): **a listing is published
only after its fee cleared AND a moderator approved it**, and a seller is never charged for
a service that never started. Prok takes no part of the item's price: `price_centimes` on
a listing is information for buyers, nothing here ever moves it. Buyer and seller meet,
check the item, and pay each other directly; the safety guide on the phone says so.

Money is integer centimes, time is integer milliseconds, like `ledger.py`. This module
never posts to the ledger itself: the lead injects `ledger_post(now, kind, debit, credit,
amount, ref=, memo=)` and it is called exactly twice per invoice at most - the MARKET_FEE
posting when a treasurer confirms the payment, and its mirror MARKET_REFUND when a refund
completes. The 40 % Free Internet Fund allocation is computed from `market:revenue` by the
ledger, not here.

Fees are a versioned price table with effective dates and a change log
(`market_price_versions`): an invoice carries the amount it was created with and is never
reinterpreted after a price change. The launch table is written to
server/tests/fixtures/market_prices.txt and the phone reads the same file.

Invoices have no operator API behind them. The seller is told "send exactly N F to the
Prok number with the reference PK-XXXXXX in the note"; the treasurer looks at the
operator's own statement and calls `confirm_invoice` with the operator transaction id.
One operator transaction id pays one invoice (UNIQUE); an amount that differs, an invoice
that is not OPEN, or one past its 24 h expiry is refused. `match_message` helps the
treasury phone find the invoice a message is about; it auto-confirms only when exactly one
candidate is found.

Paths (dispatched by `handle_get` / `handle_post`; the lead wires them under /v1/market/).
`who` is the signed caller's node id, "" for an unsigned request. PUBLIC = works with "".

    GET  /v1/market/prices                      PUBLIC  the price table in force now
    GET  /v1/market/search                      PUBLIC  q, category, min_price, max_price, neighbourhood, lat, lon, limit, offset
    GET  /v1/market/browse                      PUBLIC  alias of search (no q)
    GET  /v1/market/listing?id=                 PUBLIC  one listing + seller history
    GET  /v1/market/photo?listing=&n=           PUBLIC  {"path", "content_type"} - the lead streams the file
    GET  /v1/market/me                          signed  seller status: verified, trusted, voucher, packages, prices
    GET  /v1/market/my/listings                 signed  the caller's listings, every state
    GET  /v1/market/my/invoices                 signed  the caller's invoices
    GET  /v1/market/invoice?id=                 signed  one invoice (own only)
    GET  /v1/market/thread?listing=&other=&since=   signed  chat thread; marks the caller's side read
    GET  /v1/market/inbox                       signed  the caller's threads
    GET  /v1/market/operator/queue              operator  listings awaiting review + flagged trusted publications
    GET  /v1/market/operator/reports            operator  open reports
    GET  /v1/market/treasury/invoices?state=    treasury  invoices by state (default OPEN)
    GET  /v1/market/treasury/audit              treasury  last audit rows

    POST /v1/market/seller                      {phone, adult_attested, business_proof}
    POST /v1/market/listing                     {title, description, category, price_centimes, condition, city, neighbourhood,
                                                 lat, lon, pickup_options, pay_with: AUTO|INVOICE|PACKAGE|VOUCHER, rail}
    POST /v1/market/listing/edit                {id, ...fields}      material edit -> AWAITING_REVIEW
    POST /v1/market/listing/withdraw            {id}                 no refund for time served
    POST /v1/market/listing/pay                 {id, rail}           a new POST invoice for a listing awaiting payment
    POST /v1/market/listing/renew               {id, rail}           a new POST invoice; +30 d when PAID
    POST /v1/market/listing/boost               {id, zone, rail}     a BOOST invoice; needs PUBLISHED + zone
    POST /v1/market/package                     {kind: PACKAGE5|STOREFRONT, rail}
    POST /v1/market/invoice/cancel              {id}                 OPEN only
    POST /v1/market/photo?listing=              raw JPEG bytes (body is bytes, Content-Type image/jpeg)
    POST /v1/market/message                     {listing, to, body, offer_centimes}
    POST /v1/market/block                       {user, on}
    POST /v1/market/report                      {listing | user, kind, text}   kind SAFETY hides the listing at once
    POST /v1/market/operator/review             {listing, approve, note}
    POST /v1/market/operator/trust              {seller, trusted}
    POST /v1/market/operator/block_seller       {seller, blocked}
    POST /v1/market/operator/report             {report, action: restore|reject|dismiss}
    POST /v1/market/operator/prices             {effective_from, prices}  a new dated version, audited
    POST /v1/market/treasury/confirm            {invoice, operator_txn_id, amount_seen, payer_hash, rail}
    POST /v1/market/treasury/match              {text, amount, operator_txn_id, payer_hash, rail}
    POST /v1/market/treasury/refund             {invoice, evidence}  two different treasurers, one call each
    POST /v1/market/treasury/verify_seller      {seller}             a phone verified by other means

Every dispatcher returns (http_code, dict). `sweep(now)` expires invoices at 24 h, expires
listings at published_at + 30 d, ends boosts and package validity.
"""
import hashlib
import json
import os
import re
import sqlite3
import unicodedata
from typing import Callable, Dict, List, Optional

from brain.ledger import msisdn_digits, msisdn_hash

# ---- schema ---------------------------------------------------------------------------------

SCHEMA = """
CREATE TABLE IF NOT EXISTS market_price_versions (
    version INTEGER PRIMARY KEY, effective_from INTEGER NOT NULL, prices_json TEXT NOT NULL,
    changed_by TEXT NOT NULL DEFAULT '', changed_at INTEGER NOT NULL, note TEXT NOT NULL DEFAULT '');
CREATE TABLE IF NOT EXISTS market_sellers (
    node_id TEXT PRIMARY KEY, phone_hash TEXT NOT NULL DEFAULT '', verified INTEGER NOT NULL DEFAULT 0,
    adult_attested INTEGER NOT NULL DEFAULT 0, business_proof TEXT NOT NULL DEFAULT '',
    trusted INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, blocked INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS market_sellers_phone ON market_sellers(phone_hash);
CREATE TABLE IF NOT EXISTS market_listings (
    id TEXT PRIMARY KEY, seller_id TEXT NOT NULL, title TEXT NOT NULL, description TEXT NOT NULL DEFAULT '',
    category TEXT NOT NULL, price_centimes INTEGER NOT NULL DEFAULT 0, condition TEXT NOT NULL DEFAULT '',
    city TEXT NOT NULL DEFAULT 'Brazzaville', neighbourhood TEXT NOT NULL DEFAULT '',
    approx_lat REAL NOT NULL DEFAULT 0, approx_lon REAL NOT NULL DEFAULT 0,
    pickup_options TEXT NOT NULL DEFAULT '', photos_json TEXT NOT NULL DEFAULT '[]',
    state TEXT NOT NULL, created_at INTEGER NOT NULL, published_at INTEGER NOT NULL DEFAULT 0,
    expires_at INTEGER NOT NULL DEFAULT 0, review_note TEXT NOT NULL DEFAULT '',
    boosted_until INTEGER NOT NULL DEFAULT 0, boost_zone TEXT NOT NULL DEFAULT '',
    edited_material INTEGER NOT NULL DEFAULT 0, paid_by TEXT NOT NULL DEFAULT '',
    review_flag INTEGER NOT NULL DEFAULT 0, approved_once INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS market_listings_seller ON market_listings(seller_id, state);
CREATE INDEX IF NOT EXISTS market_listings_state ON market_listings(state, published_at);
CREATE TABLE IF NOT EXISTS market_invoices (
    id TEXT PRIMARY KEY, reference TEXT NOT NULL UNIQUE, seller_id TEXT NOT NULL, service TEXT NOT NULL,
    listing_id TEXT NOT NULL DEFAULT '', amount_centimes INTEGER NOT NULL, rail TEXT NOT NULL DEFAULT 'ANY',
    zone TEXT NOT NULL DEFAULT '', state TEXT NOT NULL, created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL,
    paid_at INTEGER NOT NULL DEFAULT 0, paid_by TEXT NOT NULL DEFAULT '', paid_rail TEXT NOT NULL DEFAULT '',
    operator_txn_id TEXT NOT NULL DEFAULT '', payer_hash TEXT NOT NULL DEFAULT '', evidence TEXT NOT NULL DEFAULT '',
    price_version INTEGER NOT NULL DEFAULT 0,
    refund_requested_by TEXT NOT NULL DEFAULT '', refund_first_approver TEXT NOT NULL DEFAULT '',
    refund_second_approver TEXT NOT NULL DEFAULT '', refunded_at INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL);
CREATE UNIQUE INDEX IF NOT EXISTS market_invoices_txn ON market_invoices(operator_txn_id) WHERE operator_txn_id != '';
CREATE INDEX IF NOT EXISTS market_invoices_seller ON market_invoices(seller_id, state);
CREATE INDEX IF NOT EXISTS market_invoices_open ON market_invoices(state, amount_centimes);
CREATE TABLE IF NOT EXISTS market_packages (
    id TEXT PRIMARY KEY, seller_id TEXT NOT NULL, kind TEXT NOT NULL, slots_total INTEGER NOT NULL,
    slots_used INTEGER NOT NULL DEFAULT 0, valid_until INTEGER NOT NULL, state TEXT NOT NULL,
    invoice_id TEXT NOT NULL DEFAULT '', created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS market_packages_seller ON market_packages(seller_id, state);
CREATE TABLE IF NOT EXISTS market_vouchers (
    seller_id TEXT PRIMARY KEY, granted_at INTEGER NOT NULL, used_at INTEGER NOT NULL DEFAULT 0,
    listing_id TEXT NOT NULL DEFAULT '');
CREATE TABLE IF NOT EXISTS market_messages (
    id INTEGER PRIMARY KEY AUTOINCREMENT, listing_id TEXT NOT NULL, from_id TEXT NOT NULL, to_id TEXT NOT NULL,
    body TEXT NOT NULL, offer_centimes INTEGER NOT NULL DEFAULT 0, at INTEGER NOT NULL, read INTEGER NOT NULL DEFAULT 0);
CREATE INDEX IF NOT EXISTS market_messages_thread ON market_messages(listing_id, from_id, to_id, id);
CREATE INDEX IF NOT EXISTS market_messages_to ON market_messages(to_id, read);
CREATE TABLE IF NOT EXISTS market_blocks (
    blocker TEXT NOT NULL, blocked TEXT NOT NULL, at INTEGER NOT NULL, PRIMARY KEY (blocker, blocked));
CREATE TABLE IF NOT EXISTS market_reports (
    id TEXT PRIMARY KEY, listing_id TEXT NOT NULL DEFAULT '', user_id TEXT NOT NULL DEFAULT '', reporter TEXT NOT NULL,
    kind TEXT NOT NULL, text TEXT NOT NULL DEFAULT '', state TEXT NOT NULL, reviewed_by TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS market_reports_state ON market_reports(state, created_at);
CREATE TABLE IF NOT EXISTS market_audit (
    id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, actor TEXT NOT NULL,
    action TEXT NOT NULL, target TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', allowed INTEGER NOT NULL);
"""

# ---- constants -------------------------------------------------------------------------------

DAY_MS = 86_400_000
HOUR_MS = 3_600_000
#: An invoice the seller has not paid within a day is dead; a new one carries a new reference.
INVOICE_TTL_MS = 24 * HOUR_MS
MAX_PHOTOS = 5
MAX_PHOTO_BYTES = 300 * 1024
JPEG_MAGIC = b"\xff\xd8\xff"
MAX_TITLE = 80
MAX_DESCRIPTION = 2000
MAX_MESSAGE = 500
#: Search: a boosted row takes at most one slot in every block of this many results.
BOOST_BLOCK = 4
#: A listing's public position is rounded to this grid (about 500 m): never a house.
COORD_STEP = 0.005

# services (what an invoice pays for) and package kinds
POST = "POST"
BOOST = "BOOST"
PACKAGE5 = "PACKAGE5"
STOREFRONT = "STOREFRONT"
VOUCHER = "VOUCHER"
SERVICES = (POST, BOOST, PACKAGE5, STOREFRONT)
RAILS = ("MTN", "AIRTEL", "ANY")

#: §10.3, exactly. Centimes, days, and what the service includes. Version 1 is in force
#: from the beginning of time; a change is a NEW version with a later effective date.
DEFAULT_PRICES: Dict[str, dict] = {
    POST: {"centimes": 10_000, "duration_days": 30, "photos": MAX_PHOTOS},
    BOOST: {"centimes": 20_000, "duration_days": 7, "zones": 1},
    PACKAGE5: {"centimes": 40_000, "duration_days": 90, "slots": 5},
    STOREFRONT: {"centimes": 200_000, "duration_days": 30, "slots": 30, "catalog": 1},
    #: face_value is what the discount costs Prok, budgeted apart; limit is the auto-off.
    VOUCHER: {"centimes": 0, "duration_days": 30, "face_value": 10_000, "limit": 100},
}

# listing states
DRAFT = "DRAFT"
AWAITING_PAYMENT = "AWAITING_PAYMENT"
AWAITING_REVIEW = "AWAITING_REVIEW"
PUBLISHED = "PUBLISHED"
REJECTED = "REJECTED"
EXPIRED = "EXPIRED"
WITHDRAWN = "WITHDRAWN"
HIDDEN = "HIDDEN"
LISTING_TEXT = {
    DRAFT: "Brouillon",
    AWAITING_PAYMENT: "En attente de paiement",
    AWAITING_REVIEW: "En attente de modération",
    PUBLISHED: "Publiée",
    REJECTED: "Refusée",
    EXPIRED: "Expirée",
    WITHDRAWN: "Retirée",
    HIDDEN: "Masquée",
}

# invoice states
OPEN = "OPEN"
PAID = "PAID"
CANCELLED = "CANCELLED"
REFUND_REQUESTED = "REFUND_REQUESTED"
REFUNDED = "REFUNDED"
INVOICE_TEXT = {
    OPEN: "En attente de confirmation",
    PAID: "Payé",
    EXPIRED: "Expiré",
    CANCELLED: "Annulé",
    REFUND_REQUESTED: "Remboursement en cours",
    REFUNDED: "Remboursé",
}

# package states
ACTIVE = "ACTIVE"
EXHAUSTED = "EXHAUSTED"

# report kinds and states
REPORT_KINDS = ("SAFETY", "SCAM", "PROHIBITED", "SPAM", "OTHER")
REPORT_OPEN = "OPEN"
REPORT_REVIEWED = "REVIEWED"
REPORT_DISMISSED = "DISMISSED"

CATEGORIES = ("PHONES", "ELECTRONICS", "HOME", "FASHION", "VEHICLES", "PROPERTY", "SERVICES",
              "KIDS", "BEAUTY", "FOOD", "OTHER")
CONDITIONS = ("NEW", "LIKE_NEW", "GOOD", "USED", "FOR_PARTS", "")

#: Refused at submission with the kind named. Matched on accent-folded whole words, in
#: the title, the description and the category. Small on purpose: moderation reads the
#: rest. Never a substring match ("armée" is not "arme", "drogué" is not "drogue").
PROHIBITED: Dict[str, tuple] = {
    "weapons": ("arme", "armes", "pistolet", "fusil", "kalachnikov", "kalach", "munition", "munitions",
                "grenade", "gun", "weapon", "rifle", "ammo"),
    "drugs": ("drogue", "drogues", "cannabis", "chanvre", "cocaine", "heroine", "tramadol", "ecstasy",
              "drug", "drugs", "weed", "marijuana"),
    "stolen": ("vole", "volee", "voles", "volees", "stolen"),
    "counterfeit": ("contrefacon", "contrefait", "contrefaite", "counterfeit", "replique", "replica", "fake"),
    "sexual_services": ("escort", "escorte", "sexe", "sexuel", "sexuelle", "erotique", "prostitution",
                        "sex", "sexual"),
    "identity_documents": ("cni", "passeport", "passport", "permis", "acte de naissance", "carte d'identite",
                           "carte grise", "identity card"),
}
PROHIBITED_CATEGORIES = ("WEAPONS", "DRUGS", "SEXUAL_SERVICES", "IDENTITY_DOCUMENTS", "STOLEN", "COUNTERFEIT")

# reason slugs, machine-readable like ledger.py's
NOT_OPERATOR = "not_operator"
NOT_TREASURY = "not_treasury"
NOT_SELLER = "not_seller"
NOT_ADULT = "not_adult"
NO_PHONE = "no_phone"
SELLER_BLOCKED = "seller_blocked"
PROHIBITED_CONTENT = "prohibited_content"
PRIVATE_DATA = "private_data"
WRONG_STATE = "wrong_state"
AMOUNT_MISMATCH = "amount_mismatch"
TXN_USED = "txn_used"
INVOICE_EXPIRED = "invoice_expired"
SAME_APPROVER = "same_approver"
NO_VOUCHER = "no_voucher"
NO_SLOT = "no_slot"
NO_ZONE = "no_zone"
BLOCKED = "blocked"
NOT_A_PARTY = "not_a_party"
TOO_LONG = "too_long"
PHOTO_LIMIT = "photo_limit"
NOT_JPEG = "not_jpeg"
BAD_CATEGORY = "bad_category"
UNKNOWN_RAIL = "unknown_rail"


class MarketError(Exception):
    def __init__(self, message: str, reason: str = "", code: int = 400):
        super().__init__(message)
        self.reason = reason
        self.code = code


def _id(*parts) -> str:
    return hashlib.sha256("|".join(str(p) for p in parts).encode("utf-8")).hexdigest()[:24]


def fold(text: str) -> str:
    """Lower-case, accents stripped, apostrophes normalised: what the word lists match on."""
    t = unicodedata.normalize("NFKD", text or "")
    t = "".join(ch for ch in t if not unicodedata.combining(ch))
    return t.lower().replace("’", "'")


def prohibited_kind(*texts: str) -> Optional[tuple]:
    """(kind, word) for the first prohibited whole word found, else None."""
    folded = " ".join(fold(t) for t in texts)
    for kind, words in PROHIBITED.items():
        for w in words:
            if re.search(r"(?<![a-z0-9])" + re.escape(w) + r"(?![a-z0-9])", folded):
                return kind, w
    return None


_PHONE_RUN = re.compile(r"(?:\d[\s.\-]?){8,}\d")


def looks_like_phone(text: str) -> bool:
    """A run of nine or more digits, however spaced: a Congolese number, or a card number.
    Neither belongs in a public field; the chat is where numbers may be exchanged."""
    for m in _PHONE_RUN.finditer(text or ""):
        if len(re.sub(r"\D", "", m.group(0))) >= 9:
            return True
    return False


def round_coord(x: float) -> float:
    if not x:
        return 0.0
    return round(round(float(x) / COORD_STEP) * COORD_STEP, 3)


def _distance_km(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Equirectangular approximation: plenty for ranking within one town."""
    import math
    dlat = (lat2 - lat1) * 111.0
    dlon = (lon2 - lon1) * 111.0 * math.cos(math.radians((lat1 + lat2) / 2.0))
    return math.sqrt(dlat * dlat + dlon * dlon)


def render_prices_fixture(prices: Dict[str, dict]) -> List[str]:
    """The lines both halves hold: KEY|centimes|duration_days|k=v;k=v then the state texts."""
    out = []
    for key in (POST, BOOST, PACKAGE5, STOREFRONT, VOUCHER):
        p = prices[key]
        extras = ";".join("%s=%s" % (k, v) for k, v in sorted(p.items()) if k not in ("centimes", "duration_days"))
        out.append("%s|%d|%d|%s" % (key, p["centimes"], p["duration_days"], extras))
    for state, text in INVOICE_TEXT.items():
        out.append("INVOICE_STATE|%s|%s" % (state, text))
    for state, text in LISTING_TEXT.items():
        out.append("LISTING_STATE|%s|%s" % (state, text))
    return out


def parse_prices_fixture(lines) -> dict:
    """The inverse of render_prices_fixture: {"prices": {...}, "invoice_text": {...}, "listing_text": {...}}."""
    prices, inv, lst = {}, {}, {}
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("|")
        if parts[0] == "INVOICE_STATE":
            inv[parts[1]] = parts[2]
        elif parts[0] == "LISTING_STATE":
            lst[parts[1]] = parts[2]
        else:
            p = {"centimes": int(parts[1]), "duration_days": int(parts[2])}
            for kv in (parts[3] if len(parts) > 3 else "").split(";"):
                if kv:
                    k, v = kv.split("=", 1)
                    p[k] = int(v) if v.isdigit() else v
            prices[parts[0]] = p
    return {"prices": prices, "invoice_text": inv, "listing_text": lst}


class Market:
    """`is_operator(who)` / `is_treasury(who)` are the server's role checks (allow-lists in
    the environment, like the ledger's). `media_dir` is where photos live. `ledger_post` is
    the ledger's `_post`-shaped callable, or None in tests that do not care about revenue.
    `config` may carry {"prices": {...}, "effective_from": ms} to seed a different version 1."""

    def __init__(self, db: sqlite3.Connection, is_operator: Callable[[str], bool], is_treasury: Callable[[str], bool],
                 media_dir: str, ledger_post: Optional[Callable] = None, config: Optional[dict] = None):
        self.db = db
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)
        self.is_operator = is_operator
        self.is_treasury = is_treasury
        self.media_dir = media_dir
        self.ledger_post = ledger_post
        seed = (config or {}).get("prices") or DEFAULT_PRICES
        if self.db.execute("SELECT 1 FROM market_price_versions").fetchone() is None:
            self.db.execute("INSERT INTO market_price_versions(version, effective_from, prices_json, changed_by, changed_at, note)"
                            " VALUES(1, ?, ?, 'launch', 0, 'launch contract 2026-09-26 §10.3')",
                            ((config or {}).get("effective_from", 0), json.dumps(seed, sort_keys=True)))
        self.db.commit()

    # ---- audit and roles ---------------------------------------------------------------------

    def _audit(self, now: int, actor: str, action: str, target: str, detail: str = "", allowed: bool = True):
        self.db.execute("INSERT INTO market_audit(ts, actor, action, target, detail, allowed) VALUES(?,?,?,?,?,?)",
                        (now, actor, action, target, detail[:400], 1 if allowed else 0))
        if not self.db.in_transaction:
            self.db.commit()

    def _refuse(self, now: int, actor: str, action: str, target: str, detail: str, error: MarketError):
        """Audit a refusal durably, then raise. Call OUTSIDE any open transaction."""
        self._audit(now, actor, action, target, "refused: " + detail, allowed=False)
        self.db.commit()
        raise error

    def _require_signed(self, who: str):
        if not who:
            raise MarketError("this endpoint requires a signed request", code=401)

    def _require_operator(self, who: str, action: str, target: str, now: int):
        self._require_signed(who)
        if not self.is_operator(who):
            self._refuse(now, who, action, target, "not an operator", MarketError("only an operator may do this", NOT_OPERATOR, 403))

    def _require_treasury(self, who: str, action: str, target: str, now: int):
        self._require_signed(who)
        if not self.is_treasury(who):
            self._refuse(now, who, action, target, "not a treasury identity", MarketError("only a treasury identity may do this", NOT_TREASURY, 403))

    # ---- prices: versioned, dated, logged ------------------------------------------------------

    def prices_at(self, now: int) -> dict:
        """The version in force at `now`: the latest whose effective date has passed."""
        row = self.db.execute("SELECT * FROM market_price_versions WHERE effective_from<=? ORDER BY version DESC LIMIT 1", (now,)).fetchone()
        if row is None:
            row = self.db.execute("SELECT * FROM market_price_versions ORDER BY version LIMIT 1").fetchone()
        return {"version": int(row["version"]), "effective_from": int(row["effective_from"]), "prices": json.loads(row["prices_json"])}

    def price_of(self, service: str, now: int) -> dict:
        p = self.prices_at(now)
        if service not in p["prices"]:
            raise MarketError("unknown service")
        return dict(p["prices"][service], version=p["version"])

    def set_prices(self, operator: str, effective_from: int, prices: dict, now: int, note: str = "") -> dict:
        """A dated business rule, never a silent edit: every key of the table must be given,
        the new version applies from `effective_from`, and old invoices keep their amounts."""
        self._require_operator(operator, "market.prices", "-", now)
        for key in (POST, BOOST, PACKAGE5, STOREFRONT, VOUCHER):
            if key not in prices or "centimes" not in prices[key] or "duration_days" not in prices[key]:
                raise MarketError("every service needs centimes and duration_days: " + key)
            if int(prices[key]["centimes"]) < 0:
                raise MarketError("a price cannot be negative")
        with self.db:
            v = int(self.db.execute("SELECT COALESCE(MAX(version),0) AS v FROM market_price_versions").fetchone()["v"]) + 1
            self.db.execute("INSERT INTO market_price_versions(version, effective_from, prices_json, changed_by, changed_at, note) VALUES(?,?,?,?,?,?)",
                            (v, int(effective_from), json.dumps(prices, sort_keys=True), operator, now, note[:200]))
            self._audit(now, operator, "market.prices", "v%d" % v, "effective %d %s" % (effective_from, note[:100]))
        return {"ok": True, "version": v, "effective_from": int(effective_from)}

    def price_log(self) -> List[dict]:
        return [dict(r) for r in self.db.execute("SELECT * FROM market_price_versions ORDER BY version").fetchall()]

    # ---- sellers -------------------------------------------------------------------------------

    def register_seller(self, who: str, phone: str, adult_attested: bool, business_proof: str, now: int) -> dict:
        """The phone is kept as a hash only (the same one the ledger computes from an operator
        message). Verification is not claimed here: it comes from a matched invoice."""
        self._require_signed(who)
        digits = msisdn_digits(phone)
        if phone and not digits:
            raise MarketError("a valid nine-digit Mobile Money number is required", NO_PHONE)
        h = msisdn_hash(digits) if digits else ""
        with self.db:
            row = self._seller(who)
            if row is None:
                self.db.execute("INSERT INTO market_sellers(node_id, phone_hash, adult_attested, business_proof, created_at, updated_at) VALUES(?,?,?,?,?,?)",
                                (who, h, 1 if adult_attested else 0, business_proof[:400], now, now))
            else:
                self.db.execute("UPDATE market_sellers SET phone_hash=COALESCE(NULLIF(?,''), phone_hash), adult_attested=?, business_proof=?, updated_at=? WHERE node_id=?",
                                (h, 1 if adult_attested else 0, business_proof[:400] or row["business_proof"], now, who))
            self._audit(now, who, "market.seller", who, "adult=%d phone=%s" % (1 if adult_attested else 0, "yes" if h else "no"))
        return self.seller_status(who, now)

    def _seller(self, node: str):
        return self.db.execute("SELECT * FROM market_sellers WHERE node_id=?", (node,)).fetchone()

    def _require_seller(self, who: str, now: int, action: str):
        self._require_signed(who)
        s = self._seller(who)
        if s is None:
            raise MarketError("register as a seller first (phone and adult attestation)", NOT_SELLER, 403)
        if s["blocked"]:
            self._refuse(now, who, action, who, "seller blocked", MarketError("this seller is blocked", SELLER_BLOCKED, 403))
        if not s["adult_attested"]:
            raise MarketError("sellers must attest they are adults", NOT_ADULT, 403)
        if not s["phone_hash"]:
            raise MarketError("a Mobile Money number is required to sell", NO_PHONE, 403)
        return s

    def voucher_available(self, who: str, now: int) -> bool:
        """One free standard post for the first N VERIFIED sellers; off by itself at N."""
        s = self._seller(who)
        if s is None or not s["verified"] or s["blocked"]:
            return False
        if self.db.execute("SELECT 1 FROM market_vouchers WHERE seller_id=?", (who,)).fetchone():
            return False
        limit = int(self.price_of(VOUCHER, now).get("limit", 0))
        granted = int(self.db.execute("SELECT COUNT(*) AS n FROM market_vouchers").fetchone()["n"])
        return granted < limit

    def seller_status(self, who: str, now: int) -> dict:
        self._require_signed(who)
        s = self._seller(who)
        pk = self.my_packages(who, now)
        return {
            "registered": s is not None,
            "verified": bool(s and s["verified"]), "trusted": bool(s and s["trusted"]),
            "adult_attested": bool(s and s["adult_attested"]), "blocked": bool(s and s["blocked"]),
            "has_phone": bool(s and s["phone_hash"]), "member_since": int(s["created_at"]) if s else 0,
            "voucher_available": self.voucher_available(who, now),
            "packages": pk, "slots_free": sum(p["slots_free"] for p in pk),
            "storefront": any(p["kind"] == STOREFRONT for p in pk),
            "prices": self.prices_at(now),
            "published_count": self._published_count(who),
        }

    def _published_count(self, node: str) -> int:
        return int(self.db.execute("SELECT COUNT(*) AS n FROM market_listings WHERE seller_id=? AND approved_once=1", (node,)).fetchone()["n"])

    def seller_history(self, node: str, now: int) -> dict:
        """What a buyer may know about a seller: how many listings were published and since when. Never the phone."""
        s = self._seller(node)
        return {"published_count": self._published_count(node), "member_since": int(s["created_at"]) if s else 0,
                "verified": bool(s and s["verified"]), "trusted": bool(s and s["trusted"]),
                "storefront": bool(self.db.execute("SELECT 1 FROM market_packages WHERE seller_id=? AND kind=? AND state=? AND valid_until>?",
                                                   (node, STOREFRONT, ACTIVE, now)).fetchone())}

    def verify_seller(self, treasurer: str, node: str, now: int) -> dict:
        """A phone the treasury verified by other means (an operator message from that number)."""
        self._require_treasury(treasurer, "market.verify_seller", node, now)
        with self.db:
            if self._seller(node) is None:
                raise MarketError("unknown seller", code=404)
            self.db.execute("UPDATE market_sellers SET verified=1, updated_at=? WHERE node_id=?", (now, node))
            self._audit(now, treasurer, "market.verify_seller", node)
        return {"ok": True, "verified": True}

    def set_trusted(self, operator: str, node: str, trusted: bool, now: int) -> dict:
        self._require_operator(operator, "market.trust", node, now)
        with self.db:
            if self._seller(node) is None:
                raise MarketError("unknown seller", code=404)
            self.db.execute("UPDATE market_sellers SET trusted=?, updated_at=? WHERE node_id=?", (1 if trusted else 0, now, node))
            self._audit(now, operator, "market.trust", node, "trusted=%d" % (1 if trusted else 0))
        return {"ok": True, "trusted": bool(trusted)}

    def block_seller(self, operator: str, node: str, blocked: bool, now: int) -> dict:
        """Escalation: a blocked seller's live listings are hidden at once."""
        self._require_operator(operator, "market.block_seller", node, now)
        with self.db:
            if self._seller(node) is None:
                raise MarketError("unknown seller", code=404)
            self.db.execute("UPDATE market_sellers SET blocked=?, updated_at=? WHERE node_id=?", (1 if blocked else 0, now, node))
            if blocked:
                self.db.execute("UPDATE market_listings SET state=?, updated_at=? WHERE seller_id=? AND state=?", (HIDDEN, now, node, PUBLISHED))
            self._audit(now, operator, "market.block_seller", node, "blocked=%d" % (1 if blocked else 0))
        return {"ok": True, "blocked": bool(blocked)}

    # ---- content rules -----------------------------------------------------------------------

    @staticmethod
    def check_content(title: str, description: str, category: str):
        """Refuse what moderation must never see published, with a reason the seller can read."""
        title = (title or "").strip()
        description = (description or "").strip()
        if not title:
            raise MarketError("a title is required")
        if len(title) > MAX_TITLE or len(description) > MAX_DESCRIPTION:
            raise MarketError("title up to %d characters, description up to %d" % (MAX_TITLE, MAX_DESCRIPTION), TOO_LONG)
        if category in PROHIBITED_CATEGORIES:
            raise MarketError("refusé : catégorie interdite (%s)" % category.lower(), PROHIBITED_CONTENT)
        if category not in CATEGORIES:
            raise MarketError("unknown category", BAD_CATEGORY)
        hit = prohibited_kind(title, description)
        if hit is not None:
            raise MarketError("refusé : contenu interdit (%s : « %s »)" % (hit[0], hit[1]), PROHIBITED_CONTENT)
        if looks_like_phone(title) or looks_like_phone(description):
            raise MarketError("refusé : pas de numéro de téléphone ni de document d'identité dans l'annonce - échangez-les dans la messagerie", PRIVATE_DATA)

    # ---- listings: the seller's side -------------------------------------------------------------

    MATERIAL_FIELDS = ("title", "description", "category", "condition", "photos")

    def submit_listing(self, who: str, fields: dict, now: int, pay_with: str = "AUTO", rail: str = "ANY") -> dict:
        """A new listing. Content is checked first (a refusal costs nothing); then the way it
        will be paid decides its first state:
            VOUCHER  -> AWAITING_REVIEW (the voucher is marked used only at publication)
            PACKAGE  -> AWAITING_REVIEW (a slot is consumed only at publication)
            INVOICE  -> AWAITING_PAYMENT with an OPEN POST invoice
            AUTO     -> voucher if available, else a package slot, else an invoice
        """
        s = self._require_seller(who, now, "market.submit")
        f = self._clean_fields(fields)
        self.check_content(f["title"], f["description"], f["category"])
        if rail not in RAILS:
            raise MarketError("unknown rail", UNKNOWN_RAIL)
        lid = _id("listing", who, now, f["title"])
        with self.db:
            self.db.execute("INSERT INTO market_listings(id, seller_id, title, description, category, price_centimes, condition, city, neighbourhood,"
                            " approx_lat, approx_lon, pickup_options, photos_json, state, created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'[]',?,?,?)",
                            (lid, who, f["title"], f["description"], f["category"], f["price_centimes"], f["condition"], f["city"], f["neighbourhood"],
                             f["approx_lat"], f["approx_lon"], f["pickup_options"], DRAFT, now, now))
            how = self._choose_payment(who, pay_with, now)
            invoice = None
            if how == VOUCHER:
                self.db.execute("INSERT INTO market_vouchers(seller_id, granted_at, listing_id) VALUES(?,?,?)", (who, now, lid))
                self.db.execute("UPDATE market_listings SET state=?, paid_by='voucher', updated_at=? WHERE id=?", (AWAITING_REVIEW, now, lid))
            elif how == "PACKAGE":
                pk = self._package_with_slot(who, now)
                self.db.execute("UPDATE market_listings SET state=?, paid_by=?, updated_at=? WHERE id=?", (AWAITING_REVIEW, "package:" + pk["id"], now, lid))
            else:
                invoice = self._new_invoice(who, POST, lid, rail, now)
                self.db.execute("UPDATE market_listings SET state=?, paid_by=?, updated_at=? WHERE id=?", (AWAITING_PAYMENT, "invoice:" + invoice["id"], now, lid))
            self._audit(now, who, "market.submit", lid, how)
            if s["trusted"] and how in (VOUCHER, "PACKAGE"):
                self._publish(self._listing(lid), now, flagged=True)
        out = {"ok": True, "listing": self.listing_view(self._listing(lid), now, owner=True), "paid_with": how}
        if invoice is not None:
            out["invoice"] = self.invoice_view(self._invoice(invoice["id"]), now)
        return out

    def _choose_payment(self, who: str, pay_with: str, now: int) -> str:
        pay_with = (pay_with or "AUTO").upper()
        if pay_with == VOUCHER:
            if not self.voucher_available(who, now):
                raise MarketError("no voucher available for this seller", NO_VOUCHER)
            return VOUCHER
        if pay_with == "PACKAGE":
            if self._package_with_slot(who, now) is None:
                raise MarketError("no package slot available", NO_SLOT)
            return "PACKAGE"
        if pay_with == "INVOICE":
            return "INVOICE"
        if pay_with != "AUTO":
            raise MarketError("pay_with must be AUTO, INVOICE, PACKAGE or VOUCHER")
        if self.voucher_available(who, now):
            return VOUCHER
        if self._package_with_slot(who, now) is not None:
            return "PACKAGE"
        return "INVOICE"

    def _clean_fields(self, fields: dict, base=None) -> dict:
        g = lambda k, d="": (fields.get(k) if fields.get(k) is not None else (base[k] if base is not None and k in base.keys() else d))
        price = g("price_centimes", 0)
        try:
            price = int(price)
        except (TypeError, ValueError):
            raise MarketError("price_centimes must be an integer")
        if price < 0:
            raise MarketError("price cannot be negative")
        cond = str(g("condition", "")).upper()
        if cond not in CONDITIONS:
            raise MarketError("unknown condition")
        lat = fields.get("lat", fields.get("approx_lat", base["approx_lat"] if base is not None else 0))
        lon = fields.get("lon", fields.get("approx_lon", base["approx_lon"] if base is not None else 0))
        try:
            lat, lon = float(lat or 0), float(lon or 0)
        except (TypeError, ValueError):
            raise MarketError("lat/lon must be numbers")
        return {"title": str(g("title")).strip(), "description": str(g("description")).strip(),
                "category": str(g("category")).upper().strip(), "price_centimes": price, "condition": cond,
                "city": str(g("city", "Brazzaville")).strip()[:40] or "Brazzaville",
                "neighbourhood": str(g("neighbourhood")).strip()[:60],
                "approx_lat": round_coord(lat), "approx_lon": round_coord(lon),
                "pickup_options": str(g("pickup_options")).strip()[:200]}

    def _listing(self, lid: str) -> sqlite3.Row:
        row = self.db.execute("SELECT * FROM market_listings WHERE id=?", (lid,)).fetchone()
        if row is None:
            raise MarketError("unknown listing", code=404)
        return row

    def _own_listing(self, who: str, lid: str) -> sqlite3.Row:
        self._require_signed(who)
        row = self._listing(lid)
        if row["seller_id"] != who:
            raise MarketError("not your listing", code=403)
        return row

    def edit_listing(self, who: str, lid: str, fields: dict, now: int) -> dict:
        """Price, neighbourhood and pickup change freely. Title, description, category,
        condition or photos are MATERIAL: a published listing goes back to review and keeps
        its expiry - an edit never buys time."""
        row = self._own_listing(who, lid)
        if row["state"] in (REJECTED, WITHDRAWN, EXPIRED, HIDDEN):
            raise MarketError("this listing can no longer be edited (" + LISTING_TEXT[row["state"]] + ")", WRONG_STATE, 409)
        f = self._clean_fields(fields, base=row)
        self.check_content(f["title"], f["description"], f["category"])
        material = any(k in fields and f[k] != row[k] for k in ("title", "description", "category", "condition"))
        with self.db:
            self.db.execute("UPDATE market_listings SET title=?, description=?, category=?, price_centimes=?, condition=?, city=?, neighbourhood=?,"
                            " approx_lat=?, approx_lon=?, pickup_options=?, updated_at=? WHERE id=?",
                            (f["title"], f["description"], f["category"], f["price_centimes"], f["condition"], f["city"], f["neighbourhood"],
                             f["approx_lat"], f["approx_lon"], f["pickup_options"], now, lid))
            if material:
                self._material_edit(row, now)
            self._audit(now, who, "market.edit", lid, "material" if material else "minor")
        return {"ok": True, "listing": self.listing_view(self._listing(lid), now, owner=True), "material": material}

    def _material_edit(self, row, now: int):
        if row["state"] == PUBLISHED:
            self.db.execute("UPDATE market_listings SET state=?, edited_material=1, review_flag=0, updated_at=? WHERE id=?", (AWAITING_REVIEW, now, row["id"]))
        else:
            self.db.execute("UPDATE market_listings SET edited_material=1, updated_at=? WHERE id=?", (now, row["id"]))

    def withdraw_listing(self, who: str, lid: str, now: int) -> dict:
        """The seller takes it down. No refund for time served; an OPEN invoice is cancelled,
        an unused voucher or slot is simply not consumed."""
        row = self._own_listing(who, lid)
        if row["state"] not in (DRAFT, AWAITING_PAYMENT, AWAITING_REVIEW, PUBLISHED, HIDDEN):
            raise MarketError("nothing to withdraw (" + LISTING_TEXT[row["state"]] + ")", WRONG_STATE, 409)
        with self.db:
            self.db.execute("UPDATE market_invoices SET state=?, updated_at=? WHERE listing_id=? AND state=?", (CANCELLED, now, lid, OPEN))
            if row["paid_by"] == "voucher" and row["published_at"] == 0:
                self.db.execute("DELETE FROM market_vouchers WHERE seller_id=? AND listing_id=? AND used_at=0", (who, lid))
            self.db.execute("UPDATE market_listings SET state=?, boosted_until=0, boost_zone='', updated_at=? WHERE id=?", (WITHDRAWN, now, lid))
            self._audit(now, who, "market.withdraw", lid, "no refund")
        return {"ok": True, "listing": self.listing_view(self._listing(lid), now, owner=True)}

    def pay_listing(self, who: str, lid: str, rail: str, now: int) -> dict:
        """A fresh POST invoice for a listing still waiting for payment (its first invoice
        expired or was cancelled). One OPEN invoice per listing at a time."""
        row = self._own_listing(who, lid)
        self._require_seller(who, now, "market.pay")
        if row["state"] not in (DRAFT, AWAITING_PAYMENT):
            raise MarketError("this listing is not waiting for payment", WRONG_STATE, 409)
        if rail not in RAILS:
            raise MarketError("unknown rail", UNKNOWN_RAIL)
        with self.db:
            if self.db.execute("SELECT 1 FROM market_invoices WHERE listing_id=? AND state=?", (lid, OPEN)).fetchone():
                raise MarketError("an invoice is already open for this listing", WRONG_STATE, 409)
            inv = self._new_invoice(who, POST, lid, rail, now)
            self.db.execute("UPDATE market_listings SET state=?, paid_by=?, updated_at=? WHERE id=?", (AWAITING_PAYMENT, "invoice:" + inv["id"], now, lid))
        return {"ok": True, "invoice": self.invoice_view(self._invoice(inv["id"]), now)}

    def renew_listing(self, who: str, lid: str, rail: str, now: int) -> dict:
        """Thirty more days cost a new POST invoice; nothing else resets expiry."""
        row = self._own_listing(who, lid)
        self._require_seller(who, now, "market.renew")
        if row["state"] not in (PUBLISHED, EXPIRED):
            raise MarketError("only a published or expired listing can be renewed", WRONG_STATE, 409)
        if rail not in RAILS:
            raise MarketError("unknown rail", UNKNOWN_RAIL)
        with self.db:
            if self.db.execute("SELECT 1 FROM market_invoices WHERE listing_id=? AND service=? AND state=?", (lid, POST, OPEN)).fetchone():
                raise MarketError("a renewal invoice is already open", WRONG_STATE, 409)
            inv = self._new_invoice(who, POST, lid, rail, now)
        return {"ok": True, "invoice": self.invoice_view(self._invoice(inv["id"]), now)}

    def request_boost(self, who: str, lid: str, zone: str, rail: str, now: int) -> dict:
        """A boost needs a PUBLISHED listing, a zone, and its own PAID invoice. The boost
        starts when the treasurer confirms the payment, and is labelled in every result."""
        row = self._own_listing(who, lid)
        self._require_seller(who, now, "market.boost")
        if row["state"] != PUBLISHED:
            raise MarketError("only a published listing can be boosted", WRONG_STATE, 409)
        zone = (zone or "").strip()[:60]
        if not zone:
            raise MarketError("a boost names one zone (neighbourhood)", NO_ZONE)
        if rail not in RAILS:
            raise MarketError("unknown rail", UNKNOWN_RAIL)
        with self.db:
            if self.db.execute("SELECT 1 FROM market_invoices WHERE listing_id=? AND service=? AND state=?", (lid, BOOST, OPEN)).fetchone():
                raise MarketError("a boost invoice is already open", WRONG_STATE, 409)
            inv = self._new_invoice(who, BOOST, lid, rail, now, zone=zone)
        return {"ok": True, "invoice": self.invoice_view(self._invoice(inv["id"]), now)}

    def buy_package(self, who: str, kind: str, rail: str, now: int) -> dict:
        self._require_seller(who, now, "market.package")
        if kind not in (PACKAGE5, STOREFRONT):
            raise MarketError("kind must be PACKAGE5 or STOREFRONT")
        if rail not in RAILS:
            raise MarketError("unknown rail", UNKNOWN_RAIL)
        with self.db:
            inv = self._new_invoice(who, kind, "", rail, now)
        return {"ok": True, "invoice": self.invoice_view(self._invoice(inv["id"]), now)}

    def my_listings(self, who: str, now: int) -> List[dict]:
        self._require_signed(who)
        rows = self.db.execute("SELECT * FROM market_listings WHERE seller_id=? ORDER BY created_at DESC", (who,)).fetchall()
        return [self.listing_view(r, now, owner=True) for r in rows]

    # ---- packages and vouchers ----------------------------------------------------------------

    def _package_with_slot(self, who: str, now: int):
        """The soonest-expiring active package that still has a free slot, counting the
        listings already waiting for review on it (a slot is consumed only at publication,
        but two submissions cannot both count on the same one)."""
        rows = self.db.execute("SELECT * FROM market_packages WHERE seller_id=? AND state=? AND valid_until>? ORDER BY valid_until", (who, ACTIVE, now)).fetchall()
        for p in rows:
            if self._slots_free(p) > 0:
                return p
        return None

    def _slots_free(self, p) -> int:
        pending = int(self.db.execute("SELECT COUNT(*) AS n FROM market_listings WHERE paid_by=? AND state=? AND published_at=0",
                                      ("package:" + p["id"], AWAITING_REVIEW)).fetchone()["n"])
        return max(0, int(p["slots_total"]) - int(p["slots_used"]) - pending)

    def my_packages(self, who: str, now: int) -> List[dict]:
        rows = self.db.execute("SELECT * FROM market_packages WHERE seller_id=? AND state=? AND valid_until>? ORDER BY valid_until", (who, ACTIVE, now)).fetchall()
        return [{"id": p["id"], "kind": p["kind"], "slots_total": int(p["slots_total"]), "slots_used": int(p["slots_used"]),
                 "slots_free": self._slots_free(p), "valid_until": int(p["valid_until"]), "catalog": p["kind"] == STOREFRONT} for p in rows]

    def _consume_slot(self, listing, now: int):
        pid = listing["paid_by"][len("package:"):]
        p = self.db.execute("SELECT * FROM market_packages WHERE id=?", (pid,)).fetchone()
        if p is None or p["state"] != ACTIVE or int(p["valid_until"]) <= now or int(p["slots_used"]) >= int(p["slots_total"]):
            raise MarketError("the package this listing counted on is no longer valid - pay for the post instead", NO_SLOT, 409)
        used = int(p["slots_used"]) + 1
        self.db.execute("UPDATE market_packages SET slots_used=?, state=?, updated_at=? WHERE id=?",
                        (used, EXHAUSTED if used >= int(p["slots_total"]) else ACTIVE, now, pid))

    def _restore_slot(self, listing, now: int):
        pid = listing["paid_by"][len("package:"):]
        p = self.db.execute("SELECT * FROM market_packages WHERE id=?", (pid,)).fetchone()
        if p is None or int(p["slots_used"]) <= 0:
            return
        used = int(p["slots_used"]) - 1
        state = p["state"] if p["state"] != EXHAUSTED else (ACTIVE if int(p["valid_until"]) > now else EXPIRED)
        self.db.execute("UPDATE market_packages SET slots_used=?, state=?, updated_at=? WHERE id=?", (used, state, now, pid))

    # ---- invoices ------------------------------------------------------------------------------

    def _reference(self, seed: str) -> str:
        """PK- and six characters a person can read back over the phone. Unique, and never
        reused: a reference identifies one payment expectation, forever."""
        n = 0
        while True:
            h = hashlib.sha256(("%s|%d" % (seed, n)).encode("utf-8")).hexdigest().upper()
            ref = "PK-" + h[:6]
            if self.db.execute("SELECT 1 FROM market_invoices WHERE reference=?", (ref,)).fetchone() is None:
                return ref
            n += 1

    def _new_invoice(self, who: str, service: str, listing_id: str, rail: str, now: int, zone: str = "") -> dict:
        price = self.price_of(service, now)
        iid = _id("invoice", who, service, listing_id, now, zone)
        ref = self._reference(iid)
        self.db.execute("INSERT INTO market_invoices(id, reference, seller_id, service, listing_id, amount_centimes, rail, zone, state, created_at, expires_at, price_version, updated_at)"
                        " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        (iid, ref, who, service, listing_id, int(price["centimes"]), rail, zone, OPEN, now, now + INVOICE_TTL_MS, int(price["version"]), now))
        self._audit(now, who, "market.invoice", iid, "%s %d %s" % (service, price["centimes"], ref))
        return {"id": iid, "reference": ref}

    def _invoice(self, iid: str) -> sqlite3.Row:
        row = self.db.execute("SELECT * FROM market_invoices WHERE id=?", (iid,)).fetchone()
        if row is None:
            raise MarketError("unknown invoice", code=404)
        return row

    def invoice_view(self, row, now: int, for_treasury: bool = False) -> dict:
        state = row["state"]
        if state == OPEN and now >= int(row["expires_at"]):
            state = EXPIRED       # the sweep may not have run yet; the screen must not say "waiting"
        out = {"id": row["id"], "reference": row["reference"], "service": row["service"], "listing_id": row["listing_id"],
               "amount": int(row["amount_centimes"]), "rail": row["rail"], "zone": row["zone"], "state": state,
               "text": INVOICE_TEXT[state], "created_at": int(row["created_at"]), "expires_at": int(row["expires_at"]),
               "paid_at": int(row["paid_at"]), "price_version": int(row["price_version"]),
               "instruction": self.pay_instruction(int(row["amount_centimes"]), row["reference"], row["rail"])}
        if for_treasury:
            out.update({"seller_id": row["seller_id"], "operator_txn_id": row["operator_txn_id"], "payer_hash": row["payer_hash"],
                        "paid_by": row["paid_by"], "paid_rail": row["paid_rail"], "evidence": row["evidence"],
                        "refund_first_approver": row["refund_first_approver"], "refund_second_approver": row["refund_second_approver"]})
        return out

    @staticmethod
    def pay_instruction(amount_centimes: int, reference: str, rail: str = "ANY") -> str:
        """The sentence the seller reads. The phone builds the identical one (MarketView)."""
        cfa = amount_centimes // 100
        via = {"MTN": " MTN MoMo", "AIRTEL": " Airtel Money"}.get(rail, "")
        return "Payer %d F : envoyez exactement %d F au numéro Prok%s avec la référence %s dans le motif." % (cfa, cfa, via, reference)

    def my_invoices(self, who: str, now: int, limit: int = 30) -> List[dict]:
        self._require_signed(who)
        rows = self.db.execute("SELECT * FROM market_invoices WHERE seller_id=? ORDER BY created_at DESC LIMIT ?", (who, limit)).fetchall()
        return [self.invoice_view(r, now) for r in rows]

    def get_invoice(self, who: str, iid: str, now: int) -> dict:
        self._require_signed(who)
        row = self._invoice(iid)
        if row["seller_id"] != who and not self.is_treasury(who):
            raise MarketError("not your invoice", code=403)
        return self.invoice_view(row, now, for_treasury=self.is_treasury(who))

    def cancel_invoice(self, who: str, iid: str, now: int) -> dict:
        row = self._invoice(iid)
        if row["seller_id"] != who:
            raise MarketError("not your invoice", code=403)
        if row["state"] != OPEN:
            raise MarketError("only an open invoice can be cancelled", WRONG_STATE, 409)
        with self.db:
            self.db.execute("UPDATE market_invoices SET state=?, updated_at=? WHERE id=?", (CANCELLED, now, iid))
            self._audit(now, who, "market.invoice.cancel", iid)
        return {"ok": True, "invoice": self.invoice_view(self._invoice(iid), now)}

    def treasury_invoices(self, who: str, now: int, state: str = OPEN, limit: int = 100) -> List[dict]:
        self._require_treasury(who, "market.treasury.invoices", state, now)
        rows = self.db.execute("SELECT * FROM market_invoices WHERE state=? ORDER BY created_at DESC LIMIT ?", (state or OPEN, limit)).fetchall()
        return [self.invoice_view(r, now, for_treasury=True) for r in rows]

    def confirm_invoice(self, treasurer: str, invoice_id: str, operator_txn_id: str, amount_seen: int, payer_hash: str, now: int,
                        rail: str = "", evidence: str = "") -> dict:
        """The operator's statement showed a transfer. It pays THIS invoice only if: the
        invoice is OPEN and not expired, the amount is exactly the invoice's, and the
        operator transaction id has never paid anything (UNIQUE). Then the payer's phone
        becomes a verified seller phone, the service starts, and the fee is posted."""
        self._require_treasury(treasurer, "market.confirm", invoice_id, now)
        operator_txn_id = (operator_txn_id or "").strip()
        if not operator_txn_id:
            raise MarketError("the operator transaction id is required")
        row = self._invoice(invoice_id)
        if row["state"] != OPEN:
            self._refuse(now, treasurer, "market.confirm", invoice_id, "state is " + row["state"],
                         MarketError("this invoice is " + INVOICE_TEXT[row["state"]], WRONG_STATE, 409))
        if now >= int(row["expires_at"]):
            self._refuse(now, treasurer, "market.confirm", invoice_id, "expired",
                         MarketError("this invoice expired - the seller needs a new one; record the money as unassigned", INVOICE_EXPIRED, 409))
        if int(amount_seen) != int(row["amount_centimes"]):
            self._refuse(now, treasurer, "market.confirm", invoice_id, "amount %d != %d" % (int(amount_seen), int(row["amount_centimes"])),
                         MarketError("the amount seen does not equal the invoice amount", AMOUNT_MISMATCH, 409))
        if self.db.execute("SELECT 1 FROM market_invoices WHERE operator_txn_id=?", (operator_txn_id,)).fetchone():
            self._refuse(now, treasurer, "market.confirm", invoice_id, "txn already used " + operator_txn_id,
                         MarketError("this operator transaction already paid an invoice", TXN_USED, 409))
        paid_rail = (rail or "").upper() or row["rail"]
        if paid_rail not in ("MTN", "AIRTEL"):
            raise MarketError("name the rail the money arrived on (MTN or AIRTEL)", UNKNOWN_RAIL)
        with self.db:
            try:
                self.db.execute("UPDATE market_invoices SET state=?, paid_at=?, paid_by=?, paid_rail=?, operator_txn_id=?, payer_hash=?, evidence=?, updated_at=? WHERE id=?",
                                (PAID, now, treasurer, paid_rail, operator_txn_id, payer_hash or "", (evidence or "")[:200], now, invoice_id))
            except sqlite3.IntegrityError:
                raise MarketError("this operator transaction already paid an invoice", TXN_USED, 409)
            if payer_hash:
                self.db.execute("UPDATE market_sellers SET verified=1, updated_at=? WHERE phone_hash=?", (now, payer_hash))
            self._audit(now, treasurer, "market.confirm", invoice_id, "%s %d txn=%s" % (row["service"], int(amount_seen), operator_txn_id))
            self._service_paid(self._invoice(invoice_id), now)
            if self.ledger_post is not None:
                self.ledger_post(now, "MARKET_FEE", "float:" + paid_rail.lower(), "market:revenue", int(row["amount_centimes"]),
                                 ref=invoice_id, memo=row["service"])
        return {"ok": True, "invoice": self.invoice_view(self._invoice(invoice_id), now, for_treasury=True)}

    def _service_paid(self, inv, now: int):
        """What a PAID invoice starts. Inside the caller's transaction."""
        service = inv["service"]
        seller = self._seller(inv["seller_id"])
        trusted = bool(seller and seller["trusted"])
        if service in (PACKAGE5, STOREFRONT):
            price = self._price_version(inv)[service]
            pid = _id("package", inv["seller_id"], inv["id"])
            self.db.execute("INSERT INTO market_packages(id, seller_id, kind, slots_total, slots_used, valid_until, state, invoice_id, created_at, updated_at)"
                            " VALUES(?,?,?,?,0,?,?,?,?,?)",
                            (pid, inv["seller_id"], service, int(price["slots"]), now + int(price["duration_days"]) * DAY_MS, ACTIVE, inv["id"], now, now))
            return
        listing = self.db.execute("SELECT * FROM market_listings WHERE id=?", (inv["listing_id"],)).fetchone()
        if listing is None:
            self._refund_requested(inv, now, "system", "listing missing")
            return
        if service == BOOST:
            if listing["state"] != PUBLISHED:
                self._refund_requested(inv, now, "system", "listing not published at payment")
                return
            days = int(self._price_version(inv)[BOOST]["duration_days"])
            self.db.execute("UPDATE market_listings SET boosted_until=?, boost_zone=?, updated_at=? WHERE id=?",
                            (now + days * DAY_MS, inv["zone"], now, listing["id"]))
            return
        # POST: first publication or renewal
        days = int(self._price_version(inv)[POST]["duration_days"])
        if listing["state"] == AWAITING_PAYMENT:
            self.db.execute("UPDATE market_listings SET state=?, paid_by=?, updated_at=? WHERE id=?", (AWAITING_REVIEW, "invoice:" + inv["id"], now, listing["id"]))
            if trusted:
                self._publish(self._listing(listing["id"]), now, flagged=True)
        elif listing["state"] == PUBLISHED:
            self.db.execute("UPDATE market_listings SET expires_at=?, updated_at=? WHERE id=?", (max(now, int(listing["expires_at"])) + days * DAY_MS, now, listing["id"]))
        elif listing["state"] == EXPIRED:
            # an approved listing comes back for its paid period; an edited one is reviewed again
            if listing["edited_material"]:
                self.db.execute("UPDATE market_listings SET state=?, paid_by=?, updated_at=? WHERE id=?", (AWAITING_REVIEW, "invoice:" + inv["id"], now, listing["id"]))
            else:
                self.db.execute("UPDATE market_listings SET state=?, published_at=?, expires_at=?, paid_by=?, updated_at=? WHERE id=?",
                                (PUBLISHED, now, now + days * DAY_MS, "invoice:" + inv["id"], now, listing["id"]))
        else:
            self._refund_requested(inv, now, "system", "listing is " + listing["state"])

    def _price_version(self, inv) -> dict:
        row = self.db.execute("SELECT prices_json FROM market_price_versions WHERE version=?", (int(inv["price_version"]),)).fetchone()
        return json.loads(row["prices_json"]) if row else DEFAULT_PRICES

    def _refund_requested(self, inv, now: int, by: str, why: str):
        self.db.execute("UPDATE market_invoices SET state=?, refund_requested_by=?, evidence=?, updated_at=? WHERE id=? AND state=?",
                        (REFUND_REQUESTED, by, why[:200], now, inv["id"], PAID))
        self._audit(now, by, "market.refund_requested", inv["id"], why)

    def refund_invoice(self, treasurer: str, invoice_id: str, evidence: str, now: int) -> dict:
        """Two-person control. The first treasurer's call records an approval; a DIFFERENT
        treasurer's call completes the refund (the manual send happened, its reference is
        the evidence) and posts the mirror of the fee. The same identity twice is refused."""
        self._require_treasury(treasurer, "market.refund", invoice_id, now)
        row = self._invoice(invoice_id)
        if row["state"] != REFUND_REQUESTED:
            raise MarketError("no refund is requested on this invoice", WRONG_STATE, 409)
        first = row["refund_first_approver"]
        if not first:
            with self.db:
                self.db.execute("UPDATE market_invoices SET refund_first_approver=?, updated_at=? WHERE id=?", (treasurer, now, invoice_id))
                self._audit(now, treasurer, "market.refund.first", invoice_id, evidence[:100])
            return {"ok": True, "state": REFUND_REQUESTED, "needs_second_approver": True, "invoice": self.invoice_view(self._invoice(invoice_id), now, for_treasury=True)}
        if first == treasurer:
            self._refuse(now, treasurer, "market.refund", invoice_id, "same approver twice",
                         MarketError("a refund needs a second, different treasurer", SAME_APPROVER, 403))
        if not (evidence or "").strip():
            raise MarketError("the reference of the manual send is required to complete a refund")
        with self.db:
            self.db.execute("UPDATE market_invoices SET state=?, refund_second_approver=?, refunded_at=?, evidence=?, updated_at=? WHERE id=?",
                            (REFUNDED, treasurer, now, evidence.strip()[:200], now, invoice_id))
            self._audit(now, treasurer, "market.refund.second", invoice_id, evidence[:100])
            if self.ledger_post is not None:
                self.ledger_post(now, "MARKET_REFUND", "market:revenue", "float:" + (row["paid_rail"] or "mtn").lower(), int(row["amount_centimes"]),
                                 ref=invoice_id, memo=row["service"])
        return {"ok": True, "state": REFUNDED, "needs_second_approver": False, "invoice": self.invoice_view(self._invoice(invoice_id), now, for_treasury=True)}

    _REF_RE = re.compile(r"PK-([0-9A-F]{6})", re.IGNORECASE)

    def match_message(self, treasurer: str, text: str, amount_seen: int, now: int, operator_txn_id: str = "", payer_hash: str = "", rail: str = "") -> dict:
        """The treasury phone saw a message. Candidates are OPEN, unexpired invoices: the one
        whose reference is in the text, else every one with exactly that amount. With an
        operator txn id and exactly ONE candidate the invoice is confirmed; two or more
        candidates on amount alone are returned for a person to choose. Never pays twice."""
        self._require_treasury(treasurer, "market.match", "-", now)
        by = "none"
        cands = []
        m = self._REF_RE.search(text or "")
        if m:
            ref = "PK-" + m.group(1).upper()
            row = self.db.execute("SELECT * FROM market_invoices WHERE reference=? AND state=? AND expires_at>?", (ref, OPEN, now)).fetchone()
            if row is not None:
                by = "reference"
                cands = [row]
        if not cands and int(amount_seen or 0) > 0:
            cands = self.db.execute("SELECT * FROM market_invoices WHERE state=? AND amount_centimes=? AND expires_at>? ORDER BY created_at",
                                    (OPEN, int(amount_seen), now)).fetchall()
            by = "amount" if cands else "none"
        out = {"ok": True, "by": by, "candidates": [self.invoice_view(r, now, for_treasury=True) for r in cands], "confirmed": None}
        if len(cands) == 1 and operator_txn_id:
            out["confirmed"] = self.confirm_invoice(treasurer, cands[0]["id"], operator_txn_id, int(amount_seen), payer_hash, now, rail=rail)["invoice"]
        else:
            self._audit(now, treasurer, "market.match", by, "%d candidates" % len(cands))
        return out

    # ---- moderation ----------------------------------------------------------------------------

    def review_queue(self, operator: str, now: int) -> List[dict]:
        self._require_operator(operator, "market.queue", "-", now)
        rows = self.db.execute("SELECT * FROM market_listings WHERE state=? OR (state=? AND review_flag=1) ORDER BY updated_at",
                               (AWAITING_REVIEW, PUBLISHED)).fetchall()
        return [self.listing_view(r, now, owner=True) for r in rows]

    def review(self, operator: str, lid: str, approve: bool, note: str, now: int) -> dict:
        """Approve publishes (and only now consumes the slot or the voucher). Reject never
        leaves the seller charged: a PAID invoice goes to REFUND_REQUESTED, a consumed slot
        comes back, an unused voucher is released."""
        self._require_operator(operator, "market.review", lid, now)
        row = self._listing(lid)
        if not (row["state"] == AWAITING_REVIEW or (row["state"] == PUBLISHED and row["review_flag"])):
            raise MarketError("this listing is not awaiting review", WRONG_STATE, 409)
        with self.db:
            if approve:
                if row["state"] == AWAITING_REVIEW:
                    self._publish(row, now, flagged=False)
                else:
                    self.db.execute("UPDATE market_listings SET review_flag=0, approved_once=1, updated_at=? WHERE id=?", (now, lid))
                self.db.execute("UPDATE market_listings SET review_note=?, edited_material=0 WHERE id=?", ((note or "")[:300], lid))
            else:
                self._undo_charge(row, now, operator)
                self.db.execute("UPDATE market_listings SET state=?, review_note=?, review_flag=0, boosted_until=0, boost_zone='', updated_at=? WHERE id=?",
                                (REJECTED, (note or "")[:300], now, lid))
            self._audit(now, operator, "market.review", lid, ("approved " if approve else "rejected ") + (note or "")[:100])
        return {"ok": True, "listing": self.listing_view(self._listing(lid), now, owner=True)}

    def _publish(self, row, now: int, flagged: bool):
        """The moment the service starts: the slot or voucher is consumed HERE, not before.
        A re-review of an already published listing keeps its expiry."""
        days = int(self.price_of(POST, now)["duration_days"])
        if row["paid_by"].startswith("package:") and row["published_at"] == 0:
            self._consume_slot(row, now)
        elif row["paid_by"] == "voucher" and row["published_at"] == 0:
            self.db.execute("UPDATE market_vouchers SET used_at=?, listing_id=? WHERE seller_id=?", (now, row["id"], row["seller_id"]))
        elif row["paid_by"].startswith("invoice:"):
            inv = self.db.execute("SELECT state FROM market_invoices WHERE id=?", (row["paid_by"][len("invoice:"):],)).fetchone()
            if inv is None or inv["state"] != PAID:
                raise MarketError("this listing's invoice is not paid", WRONG_STATE, 409)
        # approved_once records a MODERATOR's approval; a trusted seller's flagged publication
        # is live but not yet approved, so a later rejection still undoes the charge
        approved = 0 if flagged else 1
        if row["published_at"] == 0 or row["state"] == EXPIRED:
            self.db.execute("UPDATE market_listings SET state=?, published_at=?, expires_at=?, review_flag=?, approved_once=MAX(approved_once,?), edited_material=0, updated_at=? WHERE id=?",
                            (PUBLISHED, now, now + days * DAY_MS, 1 if flagged else 0, approved, now, row["id"]))
        else:
            self.db.execute("UPDATE market_listings SET state=?, review_flag=?, approved_once=MAX(approved_once,?), edited_material=0, updated_at=? WHERE id=?",
                            (PUBLISHED, 1 if flagged else 0, approved, now, row["id"]))

    def _undo_charge(self, row, now: int, by: str):
        """A rejected listing never leaves the seller charged - unless a moderator had
        already approved it and it served its time (a material edit that fails review):
        then, like a withdrawal, there is no refund for time served."""
        if row["approved_once"]:
            return
        if row["paid_by"].startswith("package:"):
            if row["published_at"] != 0:
                self._restore_slot(row, now)
        elif row["paid_by"] == "voucher":
            self.db.execute("DELETE FROM market_vouchers WHERE seller_id=? AND listing_id=?", (row["seller_id"], row["id"]))
        elif row["paid_by"].startswith("invoice:"):
            inv = self.db.execute("SELECT * FROM market_invoices WHERE id=?", (row["paid_by"][len("invoice:"):],)).fetchone()
            if inv is not None and inv["state"] == PAID:
                self._refund_requested(inv, now, by, "listing rejected")
            elif inv is not None and inv["state"] == OPEN:
                self.db.execute("UPDATE market_invoices SET state=?, updated_at=? WHERE id=?", (CANCELLED, now, inv["id"]))

    # ---- reports and blocks --------------------------------------------------------------------

    def report(self, who: str, now: int, listing_id: str = "", user_id: str = "", kind: str = "OTHER", text: str = "") -> dict:
        """A SAFETY report hides the listing at once, pending review; the rest queue."""
        self._require_signed(who)
        kind = (kind or "OTHER").upper()
        if kind not in REPORT_KINDS:
            raise MarketError("unknown report kind")
        if not listing_id and not user_id:
            raise MarketError("name a listing or a user")
        rid = _id("report", who, listing_id, user_id, now)
        with self.db:
            if listing_id:
                row = self._listing(listing_id)
                if row["seller_id"] == who:
                    raise MarketError("you cannot report your own listing")
                if kind == "SAFETY" and row["state"] == PUBLISHED:
                    self.db.execute("UPDATE market_listings SET state=?, updated_at=? WHERE id=?", (HIDDEN, now, listing_id))
            self.db.execute("INSERT INTO market_reports(id, listing_id, user_id, reporter, kind, text, state, created_at, updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                            (rid, listing_id, user_id, who, kind, (text or "")[:500], REPORT_OPEN, now, now))
            self._audit(now, who, "market.report", listing_id or user_id, kind)
        return {"ok": True, "report_id": rid, "hidden": kind == "SAFETY" and bool(listing_id)}

    def open_reports(self, operator: str, now: int) -> List[dict]:
        self._require_operator(operator, "market.reports", "-", now)
        rows = self.db.execute("SELECT * FROM market_reports WHERE state=? ORDER BY created_at", (REPORT_OPEN,)).fetchall()
        return [dict(r) for r in rows]

    def resolve_report(self, operator: str, report_id: str, action: str, now: int) -> dict:
        """restore: the listing is fine, back to PUBLISHED. reject: the listing is REJECTED
        (charge undone as in review). dismiss: report closed, listing untouched."""
        self._require_operator(operator, "market.report.resolve", report_id, now)
        r = self.db.execute("SELECT * FROM market_reports WHERE id=?", (report_id,)).fetchone()
        if r is None:
            raise MarketError("unknown report", code=404)
        if r["state"] != REPORT_OPEN:
            raise MarketError("already resolved", WRONG_STATE, 409)
        if action not in ("restore", "reject", "dismiss"):
            raise MarketError("action must be restore, reject or dismiss")
        with self.db:
            if r["listing_id"] and action != "dismiss":
                row = self._listing(r["listing_id"])
                if action == "restore" and row["state"] == HIDDEN:
                    self.db.execute("UPDATE market_listings SET state=?, updated_at=? WHERE id=?", (PUBLISHED if int(row["expires_at"]) > now else EXPIRED, now, row["id"]))
                elif action == "reject" and row["state"] in (HIDDEN, PUBLISHED, AWAITING_REVIEW):
                    self._undo_charge(row, now, operator)
                    self.db.execute("UPDATE market_listings SET state=?, review_note=?, boosted_until=0, boost_zone='', updated_at=? WHERE id=?",
                                    (REJECTED, "signalement " + r["kind"], now, row["id"]))
            self.db.execute("UPDATE market_reports SET state=?, reviewed_by=?, updated_at=? WHERE id=?",
                            (REPORT_DISMISSED if action == "dismiss" else REPORT_REVIEWED, operator, now, report_id))
            self._audit(now, operator, "market.report.resolve", report_id, action)
        return {"ok": True, "action": action}

    def block(self, who: str, other: str, on: bool, now: int) -> dict:
        self._require_signed(who)
        if not other or other == who:
            raise MarketError("name another user")
        with self.db:
            if on:
                self.db.execute("INSERT OR IGNORE INTO market_blocks(blocker, blocked, at) VALUES(?,?,?)", (who, other, now))
            else:
                self.db.execute("DELETE FROM market_blocks WHERE blocker=? AND blocked=?", (who, other))
        return {"ok": True, "blocked": bool(on)}

    def _blocked_between(self, a: str, b: str) -> bool:
        return self.db.execute("SELECT 1 FROM market_blocks WHERE (blocker=? AND blocked=?) OR (blocker=? AND blocked=?)", (a, b, b, a)).fetchone() is not None

    # ---- chat (server-relayed) ---------------------------------------------------------------

    def send_message(self, who: str, listing_id: str, to: str, body: str, now: int, offer_centimes: int = 0) -> dict:
        """Only between a listing's seller and one other person, neither having blocked the
        other, 500 characters at most. An offer is a message that carries an amount; Prok
        records it and takes nothing."""
        self._require_signed(who)
        body = (body or "").strip()
        if not body:
            raise MarketError("an empty message is not sent")
        if len(body) > MAX_MESSAGE:
            raise MarketError("a message is at most %d characters" % MAX_MESSAGE, TOO_LONG)
        if not to or to == who:
            raise MarketError("name the person you write to")
        row = self._listing(listing_id)
        if who != row["seller_id"] and to != row["seller_id"]:
            raise MarketError("a conversation is between the seller and one buyer", NOT_A_PARTY, 403)
        if who != row["seller_id"] and row["state"] not in (PUBLISHED,):
            raise MarketError("this listing is not published", WRONG_STATE, 409)
        if self._blocked_between(who, to):
            raise MarketError("this conversation is blocked", BLOCKED, 403)
        offer = max(0, int(offer_centimes or 0))
        with self.db:
            cur = self.db.execute("INSERT INTO market_messages(listing_id, from_id, to_id, body, offer_centimes, at) VALUES(?,?,?,?,?,?)",
                                  (listing_id, who, to, body, offer, now))
            mid = cur.lastrowid
        return {"ok": True, "message": {"id": int(mid), "listing_id": listing_id, "from_id": who, "to_id": to, "body": body,
                                        "offer_centimes": offer, "at": now, "read": False}}

    def thread(self, who: str, listing_id: str, other: str, now: int, since: int = 0) -> dict:
        self._require_signed(who)
        rows = self.db.execute("SELECT * FROM market_messages WHERE listing_id=? AND ((from_id=? AND to_id=?) OR (from_id=? AND to_id=?)) AND id>? ORDER BY id",
                               (listing_id, who, other, other, who, int(since or 0))).fetchall()
        with self.db:
            self.db.execute("UPDATE market_messages SET read=1 WHERE listing_id=? AND to_id=? AND from_id=? AND read=0", (listing_id, who, other))
        return {"listing_id": listing_id, "other": other, "blocked": self._blocked_between(who, other),
                "messages": [{"id": int(r["id"]), "from_id": r["from_id"], "to_id": r["to_id"], "body": r["body"],
                              "offer_centimes": int(r["offer_centimes"]), "at": int(r["at"]), "read": bool(r["read"])} for r in rows]}

    def inbox(self, who: str, now: int) -> List[dict]:
        self._require_signed(who)
        rows = self.db.execute(
            "SELECT listing_id, CASE WHEN from_id=? THEN to_id ELSE from_id END AS other, MAX(id) AS last_id, MAX(at) AS last_at,"
            " SUM(CASE WHEN to_id=? AND read=0 THEN 1 ELSE 0 END) AS unread"
            " FROM market_messages WHERE from_id=? OR to_id=? GROUP BY listing_id, other ORDER BY last_at DESC", (who, who, who, who)).fetchall()
        out = []
        for r in rows:
            l = self.db.execute("SELECT title, state FROM market_listings WHERE id=?", (r["listing_id"],)).fetchone()
            last = self.db.execute("SELECT body FROM market_messages WHERE id=?", (int(r["last_id"]),)).fetchone()
            out.append({"listing_id": r["listing_id"], "title": l["title"] if l else "", "other": r["other"], "last_at": int(r["last_at"]),
                        "unread": int(r["unread"]), "last_body": last["body"][:80] if last else ""})
        return out

    # ---- photos --------------------------------------------------------------------------------

    def store_photo(self, who: str, listing_id: str, data: bytes, now: int) -> dict:
        """JPEG only (magic bytes), 300 KB at most, five per listing, written under
        media_dir/<listing>/<n>.jpg. A photo added to a PUBLISHED listing is a material edit."""
        row = self._own_listing(who, listing_id)
        if row["state"] in (REJECTED, WITHDRAWN, EXPIRED, HIDDEN):
            raise MarketError("this listing can no longer be changed", WRONG_STATE, 409)
        if not data or not bytes(data[:3]) == JPEG_MAGIC:
            raise MarketError("only JPEG photos are accepted", NOT_JPEG, 415)
        if len(data) > MAX_PHOTO_BYTES:
            raise MarketError("a photo is at most %d KB" % (MAX_PHOTO_BYTES // 1024), TOO_LONG, 413)
        photos = json.loads(row["photos_json"] or "[]")
        limit = int(self.price_of(POST, now).get("photos", MAX_PHOTOS))
        if len(photos) >= min(limit, MAX_PHOTOS):
            raise MarketError("at most %d photos per listing" % min(limit, MAX_PHOTOS), PHOTO_LIMIT, 409)
        n = (max(int(p.split(".")[0]) for p in photos) + 1) if photos else 1
        folder = os.path.join(self.media_dir, listing_id)
        os.makedirs(folder, exist_ok=True)
        name = "%d.jpg" % n
        with open(os.path.join(folder, name), "wb") as f:
            f.write(data)
        photos.append(name)
        with self.db:
            self.db.execute("UPDATE market_listings SET photos_json=?, updated_at=? WHERE id=?", (json.dumps(photos), now, listing_id))
            if row["state"] == PUBLISHED:
                self._material_edit(row, now)
            self._audit(now, who, "market.photo", listing_id, name)
        return {"ok": True, "n": n, "photos": photos, "listing": self.listing_view(self._listing(listing_id), now, owner=True)}

    def photo_path(self, listing_id: str, n) -> Optional[str]:
        """The file to stream for a public photo, or None. Only a listed photo of a listing
        that is (or was) public: nothing under media_dir is reachable by path guessing."""
        try:
            n = int(n)
        except (TypeError, ValueError):
            return None
        row = self.db.execute("SELECT photos_json, state FROM market_listings WHERE id=?", (listing_id,)).fetchone()
        if row is None:
            return None
        name = "%d.jpg" % n
        if name not in json.loads(row["photos_json"] or "[]"):
            return None
        path = os.path.join(self.media_dir, listing_id, name)
        return path if os.path.isfile(path) else None

    # ---- the public side: browse and search ------------------------------------------------------

    def listing_view(self, row, now: int, owner: bool = False) -> dict:
        photos = json.loads(row["photos_json"] or "[]")
        state = row["state"]
        if state == PUBLISHED and int(row["expires_at"]) <= now:
            state = EXPIRED
        boosted = int(row["boosted_until"]) > now and state == PUBLISHED
        out = {"id": row["id"], "seller_id": row["seller_id"], "title": row["title"], "description": row["description"],
               "category": row["category"], "price_centimes": int(row["price_centimes"]), "condition": row["condition"],
               "city": row["city"], "neighbourhood": row["neighbourhood"], "approx_lat": float(row["approx_lat"]), "approx_lon": float(row["approx_lon"]),
               "pickup_options": row["pickup_options"], "photos": [int(p.split(".")[0]) for p in photos],
               "photo_urls": ["/v1/market/photo?listing=%s&n=%d" % (row["id"], int(p.split(".")[0])) for p in photos],
               "state": state, "state_text": LISTING_TEXT[state], "created_at": int(row["created_at"]),
               "published_at": int(row["published_at"]), "expires_at": int(row["expires_at"]),
               "boosted": boosted, "boost_zone": row["boost_zone"] if boosted else "", "boosted_until": int(row["boosted_until"]) if boosted else 0}
        if owner:
            out.update({"review_note": row["review_note"], "edited_material": bool(row["edited_material"]), "paid_by": row["paid_by"],
                        "review_flag": bool(row["review_flag"])})
        return out

    def get_listing(self, listing_id: str, now: int, who: str = "") -> dict:
        row = self._listing(listing_id)
        public = row["state"] == PUBLISHED and int(row["expires_at"]) > now
        if not public and row["seller_id"] != who and not (who and self.is_operator(who)):
            raise MarketError("this listing is not public", code=404)
        out = self.listing_view(row, now, owner=(row["seller_id"] == who))
        out["seller"] = self.seller_history(row["seller_id"], now)
        return out

    def search(self, now: int, q: str = "", category: str = "", min_price: int = 0, max_price: int = 0, neighbourhood: str = "",
               lat: float = 0.0, lon: float = 0.0, limit: int = 20, offset: int = 0) -> dict:
        """Organic results ranked by recency and distance; boosted listings for the searched
        zone are marked `boosted: true` and take at most one slot in every BOOST_BLOCK
        results, so organic results are never displaced wholesale."""
        limit = max(1, min(int(limit or 20), 50))
        offset = max(0, int(offset or 0))
        sql = "SELECT * FROM market_listings WHERE state=? AND expires_at>?"
        args: list = [PUBLISHED, now]
        if category:
            sql += " AND category=?"; args.append(category.upper())
        if int(min_price or 0) > 0:
            sql += " AND price_centimes>=?"; args.append(int(min_price))
        if int(max_price or 0) > 0:
            sql += " AND price_centimes<=?"; args.append(int(max_price))
        if neighbourhood:
            sql += " AND neighbourhood=? COLLATE NOCASE"; args.append(neighbourhood.strip())
        rows = self.db.execute(sql, args).fetchall()
        terms = [t for t in fold(q or "").split() if t]
        if terms:
            rows = [r for r in rows if all(t in fold(r["title"] + " " + r["description"]) for t in terms)]

        def score(r) -> float:
            age_days = max(0.0, (now - int(r["published_at"])) / float(DAY_MS))
            dist = _distance_km(lat, lon, float(r["approx_lat"]), float(r["approx_lon"])) if (lat and lon and r["approx_lat"] and r["approx_lon"]) else 0.0
            return age_days + 2.0 * dist       # one day of age weighs like 500 m

        def is_boosted(r) -> bool:
            if int(r["boosted_until"]) <= now:
                return False
            return (not neighbourhood) or fold(r["boost_zone"]) == fold(neighbourhood)

        boosted = sorted([r for r in rows if is_boosted(r)], key=score)
        organic = sorted([r for r in rows if not is_boosted(r)], key=score)
        merged = []
        bi = oi = 0
        while bi < len(boosted) or oi < len(organic):
            if bi < len(boosted) and oi < len(organic) and len(merged) % BOOST_BLOCK == 0:
                merged.append((boosted[bi], True)); bi += 1
            elif oi < len(organic):
                merged.append((organic[oi], False)); oi += 1
            else:
                merged.append((boosted[bi], True)); bi += 1
        page = merged[offset:offset + limit]
        items = []
        for r, b in page:
            v = self.listing_view(r, now)
            v["boosted"] = bool(b)
            if not b:
                v["boost_zone"] = ""; v["boosted_until"] = 0
            if lat and lon and r["approx_lat"] and r["approx_lon"]:
                v["distance_km"] = round(_distance_km(lat, lon, float(r["approx_lat"]), float(r["approx_lon"])), 1)
            items.append(v)
        return {"items": items, "total": len(merged), "offset": offset, "limit": limit, "categories": list(CATEGORIES)}

    # ---- sweep ---------------------------------------------------------------------------------

    def sweep(self, now: int) -> Dict[str, int]:
        n = {"invoices_expired": 0, "listings_expired": 0, "boosts_ended": 0, "packages_expired": 0}
        with self.db:
            n["invoices_expired"] = self.db.execute("UPDATE market_invoices SET state=?, updated_at=? WHERE state=? AND expires_at<=?", (EXPIRED, now, OPEN, now)).rowcount
            n["listings_expired"] = self.db.execute("UPDATE market_listings SET state=?, review_flag=0, updated_at=? WHERE state=? AND expires_at<=?", (EXPIRED, now, PUBLISHED, now)).rowcount
            n["boosts_ended"] = self.db.execute("UPDATE market_listings SET boosted_until=0, boost_zone='', updated_at=? WHERE boosted_until>0 AND boosted_until<=?", (now, now)).rowcount
            n["packages_expired"] = self.db.execute("UPDATE market_packages SET state=?, updated_at=? WHERE state IN (?,?) AND valid_until<=?", (EXPIRED, now, ACTIVE, EXHAUSTED, now)).rowcount
        return n

    def audit_rows(self, who: str, now: int, limit: int = 100) -> List[dict]:
        self._require_treasury(who, "market.audit", "-", now)
        return [dict(r) for r in self.db.execute("SELECT * FROM market_audit ORDER BY id DESC LIMIT ?", (limit,)).fetchall()]

    # ---- dispatch ------------------------------------------------------------------------------

    def handle_get(self, who: str, path: str, query: dict, now: int):
        """(code, dict). `query` is the parsed query string, str -> str."""
        p = path.split("?", 1)[0]
        g = lambda k, d="": str(query.get(k, d) or d)
        num = lambda k: int(g(k, "0") or 0) if g(k, "0").lstrip("-").isdigit() else 0
        flt = lambda k: float(g(k, "0") or 0)
        try:
            if p == "/v1/market/prices":
                return 200, self.prices_at(now)
            if p in ("/v1/market/search", "/v1/market/browse"):
                return 200, self.search(now, q=g("q") if p.endswith("search") else "", category=g("category"), min_price=num("min_price"),
                                        max_price=num("max_price"), neighbourhood=g("neighbourhood"), lat=flt("lat"), lon=flt("lon"),
                                        limit=num("limit") or 20, offset=num("offset"))
            if p == "/v1/market/listing":
                return 200, self.get_listing(g("id"), now, who)
            if p == "/v1/market/photo":
                path_ = self.photo_path(g("listing"), g("n"))
                return (200, {"path": path_, "content_type": "image/jpeg"}) if path_ else (404, {"error": "no such photo"})
            self._require_signed(who)
            if p == "/v1/market/me":
                return 200, self.seller_status(who, now)
            if p == "/v1/market/my/listings":
                return 200, {"listings": self.my_listings(who, now)}
            if p == "/v1/market/my/invoices":
                return 200, {"invoices": self.my_invoices(who, now)}
            if p == "/v1/market/invoice":
                return 200, self.get_invoice(who, g("id"), now)
            if p == "/v1/market/thread":
                return 200, self.thread(who, g("listing"), g("other"), now, since=num("since"))
            if p == "/v1/market/inbox":
                return 200, {"threads": self.inbox(who, now)}
            if p == "/v1/market/operator/queue":
                return 200, {"listings": self.review_queue(who, now)}
            if p == "/v1/market/operator/reports":
                return 200, {"reports": self.open_reports(who, now)}
            if p == "/v1/market/treasury/invoices":
                return 200, {"invoices": self.treasury_invoices(who, now, state=g("state", OPEN))}
            if p == "/v1/market/treasury/audit":
                return 200, {"rows": self.audit_rows(who, now)}
            return 404, {"error": "not found"}
        except MarketError as e:
            return e.code, self._error(e)

    def handle_post(self, who: str, path: str, body, now: int):
        """(code, dict). `body` is the parsed JSON object, or raw bytes for /photo."""
        p, _, qs = path.partition("?")
        query = dict(kv.split("=", 1) for kv in qs.split("&") if "=" in kv) if qs else {}
        d = body if isinstance(body, dict) else {}
        s = lambda k, default="": (d.get(k) if isinstance(d.get(k), str) else default)
        n = lambda k: int(d.get(k)) if isinstance(d.get(k), (int, float)) and not isinstance(d.get(k), bool) else (int(d.get(k)) if isinstance(d.get(k), str) and d.get(k).lstrip("-").isdigit() else 0)
        b = lambda k: d.get(k) is True or (isinstance(d.get(k), str) and d.get(k).lower() in ("1", "true", "oui"))
        try:
            self._require_signed(who)
            if p == "/v1/market/seller":
                return 200, self.register_seller(who, s("phone"), b("adult_attested"), s("business_proof"), now)
            if p == "/v1/market/listing":
                return 200, self.submit_listing(who, d, now, pay_with=s("pay_with", "AUTO"), rail=s("rail", "ANY").upper() or "ANY")
            if p == "/v1/market/listing/edit":
                return 200, self.edit_listing(who, s("id"), {k: v for k, v in d.items() if k != "id"}, now)
            if p == "/v1/market/listing/withdraw":
                return 200, self.withdraw_listing(who, s("id"), now)
            if p == "/v1/market/listing/pay":
                return 200, self.pay_listing(who, s("id"), s("rail", "ANY").upper() or "ANY", now)
            if p == "/v1/market/listing/renew":
                return 200, self.renew_listing(who, s("id"), s("rail", "ANY").upper() or "ANY", now)
            if p == "/v1/market/listing/boost":
                return 200, self.request_boost(who, s("id"), s("zone"), s("rail", "ANY").upper() or "ANY", now)
            if p == "/v1/market/package":
                return 200, self.buy_package(who, s("kind").upper(), s("rail", "ANY").upper() or "ANY", now)
            if p == "/v1/market/invoice/cancel":
                return 200, self.cancel_invoice(who, s("id"), now)
            if p == "/v1/market/photo":
                if not isinstance(body, (bytes, bytearray)):
                    return 415, {"error": "send the JPEG bytes as the body"}
                return 200, self.store_photo(who, query.get("listing", ""), bytes(body), now)
            if p == "/v1/market/message":
                return 200, self.send_message(who, s("listing"), s("to"), s("body"), now, offer_centimes=n("offer_centimes"))
            if p == "/v1/market/block":
                return 200, self.block(who, s("user"), b("on") if "on" in d else True, now)
            if p == "/v1/market/report":
                return 200, self.report(who, now, listing_id=s("listing"), user_id=s("user"), kind=s("kind", "OTHER"), text=s("text"))
            if p == "/v1/market/operator/review":
                return 200, self.review(who, s("listing"), b("approve"), s("note"), now)
            if p == "/v1/market/operator/trust":
                return 200, self.set_trusted(who, s("seller"), b("trusted"), now)
            if p == "/v1/market/operator/block_seller":
                return 200, self.block_seller(who, s("seller"), b("blocked") if "blocked" in d else True, now)
            if p == "/v1/market/operator/report":
                return 200, self.resolve_report(who, s("report"), s("action"), now)
            if p == "/v1/market/operator/prices":
                prices = d.get("prices") if isinstance(d.get("prices"), dict) else {}
                return 200, self.set_prices(who, n("effective_from"), prices, now, note=s("note"))
            if p == "/v1/market/treasury/confirm":
                return 200, self.confirm_invoice(who, s("invoice"), s("operator_txn_id"), n("amount_seen"), s("payer_hash"), now, rail=s("rail"), evidence=s("evidence"))
            if p == "/v1/market/treasury/match":
                return 200, self.match_message(who, s("text"), n("amount"), now, operator_txn_id=s("operator_txn_id"), payer_hash=s("payer_hash"), rail=s("rail"))
            if p == "/v1/market/treasury/refund":
                return 200, self.refund_invoice(who, s("invoice"), s("evidence"), now)
            if p == "/v1/market/treasury/verify_seller":
                return 200, self.verify_seller(who, s("seller"), now)
            return 404, {"error": "not found"}
        except MarketError as e:
            return e.code, self._error(e)

    @staticmethod
    def _error(e: MarketError) -> dict:
        out = {"error": str(e)}
        if e.reason:
            out["reason"] = e.reason
        return out
