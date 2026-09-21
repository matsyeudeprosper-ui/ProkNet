"""v0.15.1: the server decides what a session owed, from the evidence, itself.

Until now `/v1/settlements` believed the amounts a phone sent. A phone could invent a
session, recompute a matching settlement id from its own invented fields, and create
debt. That is fine for a diagnostic and unacceptable for real money.

So the phone no longer reports amounts. It submits **evidence** — the exact signed bytes
of the contract and the closing checkpoint, plus the four signatures over them — and this
module independently re-derives everything the money depends on:

    gross, Prok fee, seller net, settlement id

A submitted amount is never trusted when the server can compute it. If the two disagree,
the submission is rejected rather than reconciled, because a mismatch means the sender is
either broken or lying and neither deserves a row in the settlements table.

The binary layouts mirror `Market.Contract` and `Market.Checkpoint` on the phones exactly.
If either side changes a field, these tests fail loudly, which is the point.
"""
import hashlib
import struct

from . import protocol

# ---- the wire layouts, identical to the phones ------------------------------------------------------

CONTRACT_DOMAIN = b"ProkNet-contract-1"
CHECKPOINT_DOMAIN = b"ProkNet-usage-1"
SETTLEMENT_DOMAIN = "ProkNet-settlement-1"

CONTRACT_LEN_V1 = 62
# v1 body plus rate(4) budget(8) ceiling(8) sourceCost(4) policy(1) mode(1)
CONTRACT_LEN_V2 = CONTRACT_LEN_V1 + 4 + 8 + 8 + 4 + 1 + 1
CHECKPOINT_LEN = 45

PRICING_VERSION = 1
PRICING_VERSION_BUDGET = 2

MB = 1024 * 1024
MAX_BILLABLE_BYTES = 1 << 40
MAX_BUDGET_CENTIMES = 100_000_000


class EvidenceError(Exception):
    """The evidence does not support the claim. The message is safe to return to a phone."""


def body_len_for(version: int) -> int:
    if version == PRICING_VERSION:
        return CONTRACT_LEN_V1
    if version == PRICING_VERSION_BUDGET:
        return CONTRACT_LEN_V2
    return -1


class Contract:
    """`Market.Contract`, decoded. Only what settlement needs."""

    def __init__(self, raw: bytes):
        if len(raw) == 0:
            raise EvidenceError("empty contract")
        self.version = raw[0]
        if len(raw) != body_len_for(self.version):
            raise EvidenceError("contract length does not match its declared version")
        self.raw = raw
        (self.session_id, self.buyer_id, self.seller_id, self.price_per_mb, self.min_price,
         self.max_mb, self.fee_pct, self.start_ts) = struct.unpack(">8s16s16sIIIBq", raw[1:CONTRACT_LEN_V1])
        if self.version == PRICING_VERSION_BUDGET:
            (self.rate_centimes_per_mb, self.buyer_budget_centimes, self.max_billable_bytes,
             self.source_cost_basis, self.seller_policy, self.pricing_mode) = struct.unpack(">IqqIBB", raw[CONTRACT_LEN_V1:CONTRACT_LEN_V2])
        else:
            self.rate_centimes_per_mb = 0
            self.buyer_budget_centimes = 0
            self.max_billable_bytes = 0
            self.source_cost_basis = 0
            self.seller_policy = 0
            self.pricing_mode = 0

    @property
    def session_hex(self) -> str:
        return self.session_id.hex()

    @property
    def buyer_hex(self) -> str:
        return self.buyer_id.hex()

    @property
    def seller_hex(self) -> str:
        return self.seller_id.hex()

    @property
    def budget_session(self) -> bool:
        return self.version == PRICING_VERSION_BUDGET and self.pricing_mode == 1

    def hash_hex(self) -> str:
        return hashlib.sha256(self.raw).hexdigest()

    def cost_for(self, byte_count: int) -> int:
        """`Contract.costFor`, to the centime, including both clamps."""
        if self.rate_centimes_per_mb <= 0 or byte_count <= 0:
            return 0
        ceiling = min(self.max_billable_bytes, MAX_BILLABLE_BYTES) if self.max_billable_bytes > 0 else MAX_BILLABLE_BYTES
        capped = min(byte_count, ceiling)
        raw = (capped * self.rate_centimes_per_mb + MB // 2) // MB
        if self.buyer_budget_centimes > 0:
            return min(raw, self.buyer_budget_centimes)
        return raw

    def valid(self) -> bool:
        if self.buyer_id == self.seller_id or self.start_ts <= 0:
            return False
        if not (0 <= self.fee_pct <= 50):
            return False
        if self.version == PRICING_VERSION_BUDGET:
            return (0 <= self.rate_centimes_per_mb <= 100_000
                    and 0 <= self.buyer_budget_centimes <= MAX_BUDGET_CENTIMES
                    and 0 <= self.max_billable_bytes <= MAX_BILLABLE_BYTES
                    and (self.rate_centimes_per_mb == 0
                         or (self.max_billable_bytes > 0 and self.buyer_budget_centimes > 0)))
        return True


class Checkpoint:
    """`Market.Checkpoint`, decoded."""

    def __init__(self, raw: bytes):
        if len(raw) != CHECKPOINT_LEN:
            raise EvidenceError("checkpoint length is wrong")
        self.raw = raw
        (self.session_id, self.seq, self.bytes_up, self.bytes_down,
         self.cost_centimes, self.ts, final) = struct.unpack(">8sIqqqqB", raw)
        self.final = final != 0

    @property
    def billable(self) -> int:
        return self.bytes_up + self.bytes_down

    def hash_hex(self) -> str:
        return hashlib.sha256(self.raw).hexdigest()


def settlement_id(session_hex: str, contract_hash: str, checkpoint_hash: str) -> str:
    raw = "%s|%s|%s|%s" % (SETTLEMENT_DOMAIN, session_hex, contract_hash, checkpoint_hash)
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()[:32]


def split(gross: int, fee_pct: int):
    if gross <= 0:
        return 0, 0, 0
    fee = (gross * fee_pct + 50) // 100
    return gross, fee, gross - fee


# ---- the verifier -----------------------------------------------------------------------------------

REQUIRED = ("contract", "buyer_contract_sig", "seller_contract_sig",
            "checkpoint", "seller_checkpoint_sig", "buyer_checkpoint_sig",
            "buyer_pub", "seller_pub", "submitter_pub")


def verify(ev: dict, now: int, ttl_ms: int) -> dict:
    """Re-derive a settlement from signed evidence, or raise [EvidenceError].

    Returns the obligation the server itself computed. Nothing the phone claimed about
    money is carried through.
    """
    for k in REQUIRED:
        if not ev.get(k):
            raise EvidenceError("missing %s" % k)

    try:
        contract = Contract(bytes.fromhex(ev["contract"]))
        checkpoint = Checkpoint(bytes.fromhex(ev["checkpoint"]))
    except ValueError:
        raise EvidenceError("evidence is not valid hex")

    # 1. the contract must be a real, paid, budget session
    if not contract.budget_session:
        raise EvidenceError("not a v2 budget session")
    if not contract.valid():
        raise EvidenceError("contract fails its own validity rules")
    if contract.rate_centimes_per_mb <= 0:
        raise EvidenceError("a free session owes nothing")

    # 2. the keys must be the parties named in the contract
    buyer_pub, seller_pub = ev["buyer_pub"], ev["seller_pub"]
    if protocol.node_id(buyer_pub) != contract.buyer_hex:
        raise EvidenceError("buyer key does not match the contract")
    if protocol.node_id(seller_pub) != contract.seller_hex:
        raise EvidenceError("seller key does not match the contract")

    # 3. both parties signed exactly these contract bytes
    contract_data = CONTRACT_DOMAIN + contract.raw
    if not protocol.verify(buyer_pub, contract_data, ev["buyer_contract_sig"]):
        raise EvidenceError("buyer contract signature is invalid")
    if not protocol.verify(seller_pub, contract_data, ev["seller_contract_sig"]):
        raise EvidenceError("seller contract signature is invalid")

    # 4. the checkpoint belongs to THAT session and is the closing one
    if checkpoint.session_id != contract.session_id:
        raise EvidenceError("checkpoint belongs to a different session")
    if not checkpoint.final:
        raise EvidenceError("checkpoint is not the closing one")
    if checkpoint.seq < 1:
        raise EvidenceError("checkpoint sequence is invalid")
    if checkpoint.billable < 0 or checkpoint.billable > MAX_BILLABLE_BYTES:
        raise EvidenceError("checkpoint byte count is out of range")
    if contract.max_billable_bytes > 0 and checkpoint.billable > contract.max_billable_bytes:
        raise EvidenceError("checkpoint exceeds the signed byte ceiling")

    # 5. both parties signed exactly those checkpoint bytes
    checkpoint_data = CHECKPOINT_DOMAIN + checkpoint.raw
    if not protocol.verify(seller_pub, checkpoint_data, ev["seller_checkpoint_sig"]):
        raise EvidenceError("seller checkpoint signature is invalid")
    if not protocol.verify(buyer_pub, checkpoint_data, ev["buyer_checkpoint_sig"]):
        raise EvidenceError("buyer countersignature is invalid")

    # 6. the cost the phones signed must be the cost the terms give, and fit the budget
    derived_cost = contract.cost_for(checkpoint.billable)
    if checkpoint.cost_centimes != derived_cost:
        raise EvidenceError("signed cost does not match the agreed terms")
    if derived_cost <= 0:
        raise EvidenceError("the session owes nothing")
    if contract.buyer_budget_centimes > 0 and derived_cost > contract.buyer_budget_centimes:
        raise EvidenceError("cost exceeds the signed budget")

    # 7. the submitter must be one of the two parties
    submitter = protocol.node_id(ev["submitter_pub"])
    if submitter == contract.buyer_hex:
        actor = "buyer"
    elif submitter == contract.seller_hex:
        actor = "seller"
    else:
        raise EvidenceError("submitter is neither the buyer nor the seller")

    # 8. everything the money depends on, computed HERE
    gross, fee, net = split(derived_cost, contract.fee_pct)
    sid = settlement_id(contract.session_hex, contract.hash_hex(), checkpoint.hash_hex())

    # a claimed amount or id is allowed, but only as a cross-check: a disagreement means
    # the sender is broken or lying, so it is refused rather than quietly corrected
    if "gross" in ev and int(ev["gross"]) != gross:
        raise EvidenceError("claimed amount does not match the evidence")
    if "settlement_id" in ev and ev["settlement_id"] != sid:
        raise EvidenceError("claimed settlement id does not match the evidence")

    return {
        "settlement_id": sid,
        "session_id": contract.session_hex,
        "buyer_id": contract.buyer_hex,
        "seller_id": contract.seller_hex,
        "checkpoint_hash": checkpoint.hash_hex(),
        "contract_hash": contract.hash_hex(),
        "gross": gross,
        "prok_fee": fee,
        "seller_net": net,
        "fee_pct": contract.fee_pct,
        "actor": actor,
        "expires_at": now + ttl_ms,
    }
