"""Pure matching and economics, mirroring core/Jobs.kt and core/InternetRequest.kt on the phone.

Nothing here touches the database or the clock: inputs in, a decision out.
"""
from dataclasses import dataclass
from typing import List, Optional

HEARTBEAT_MAX_AGE_MS = 15 * 60_000
FRESH_MS = 10 * 60_000
RECENT_MS = 24 * 3_600_000
NO_ZONE = "z?"

DIRECT_SOURCE = "DIRECT_SOURCE"
ACTIVATE_PROVIDER = "ACTIVATE_PROVIDER"
WAIT_FOR_SUPPLY = "WAIT_FOR_SUPPLY"
NO_PLAN = "NO_PLAN"

COMMERCIAL = "COMMERCIAL"
SPONSORED = "SPONSORED"
GROWTH_SUBSIDY = "GROWTH_SUBSIDY"

REDISTRIBUTABLE = ("OPEN_REUSABLE", "AUTHORIZED_PRIVATE")


@dataclass
class Provider:
    node_id: str
    zone: str
    potential: bool
    sharing: bool
    price: int
    last_heartbeat: int
    busy: bool
    upstream_validated: bool = True
    reachable_locally: bool = False


@dataclass
class Decision:
    plan: str
    provider: Optional[str]
    reason: str


def admissible(delivery_cost: int, customer_ceiling: int, cost_class: str = COMMERCIAL, budget: int = 0):
    """Do not spend 300 CFA delivering a 50 CFA session unless it is explicitly subsidised."""
    if cost_class == COMMERCIAL:
        ok = delivery_cost <= customer_ceiling
        return ok, ("delivery %d within ceiling %d" if ok else "delivery %d exceeds ceiling %d") % (delivery_cost, customer_ceiling)
    if cost_class == SPONSORED:
        ok = delivery_cost <= budget
        return ok, ("sponsored within budget" if ok else "sponsored: delivery %d exceeds budget %d" % (delivery_cost, budget))
    need = max(0, delivery_cost - customer_ceiling)
    ok = need <= budget
    return ok, ("growth subsidy of %d accepted" if ok else "growth subsidy of %d exceeds budget") % need


def match(request_open: bool, request_expired: bool, zone: str, ceiling: Optional[int], direct_usable: bool,
          providers: List[Provider], now: int, expected_mb: int = 10) -> Decision:
    """Direct first; then the cheapest opted-in, alive, validated, free provider in the zone (or seen locally) within the ceiling."""
    if direct_usable:
        return Decision(DIRECT_SOURCE, None, "a usable source is reachable now")
    if not request_open or request_expired:
        return Decision(NO_PLAN, None, "request is not open")
    ok = [p for p in providers
          if p.potential and not p.busy and not p.sharing and p.upstream_validated
          and now - p.last_heartbeat <= HEARTBEAT_MAX_AGE_MS
          and (p.reachable_locally or (p.zone == zone and zone != NO_ZONE))
          and (ceiling is None or p.price <= ceiling)]
    if not ok:
        return Decision(WAIT_FOR_SUPPLY, None, "no eligible provider (%d known)" % len(providers) if providers else "no potential provider known")
    ok.sort(key=lambda p: (not p.reachable_locally, p.price, -p.last_heartbeat))
    best = ok[0]
    ceiling_centimes = (ceiling if ceiling is not None else best.price) * expected_mb
    admitted, why = admissible(best.price * expected_mb, ceiling_centimes)
    if not admitted:
        return Decision(WAIT_FOR_SUPPLY, None, "provider %s rejected: %s" % (best.node_id, why))
    return Decision(ACTIVATE_PROVIDER, best.node_id, ("seen locally" if best.reachable_locally else "same zone") + ", %d centimes/MB" % best.price)


def freshness(age_ms: int) -> float:
    """1.0 within 10 minutes, decaying to 0.2 at 24 hours, then 0."""
    if age_ms <= FRESH_MS:
        return 1.0
    if age_ms >= RECENT_MS:
        return 0.0
    return 1.0 - 0.8 * (age_ms - FRESH_MS) / (RECENT_MS - FRESH_MS)


def cell_status(sharing_now: int, recent_potential: int, last_seen: int, now: int) -> str:
    """GREEN only with a provider sharing right now and seen within 10 min; YELLOW for anything recent; RED otherwise."""
    if sharing_now > 0 and now - last_seen <= FRESH_MS:
        return "GREEN"
    if recent_potential > 0 and now - last_seen <= RECENT_MS:
        return "YELLOW"
    return "RED"


def usable_source(kind: str, trust: str, validated: bool) -> bool:
    """A ProkNet provider is supply; a Wi-Fi network only when its access is legitimate and validated. Detection is not authorization."""
    if kind == "PROKNET":
        return validated
    return validated and trust in REDISTRIBUTABLE
