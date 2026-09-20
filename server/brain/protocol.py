"""prok-sync/1: the one batched, signed, idempotent sync between a phone and the Network Brain.

Line protocol, tab-separated, the same shapes the Android codecs use (see
core/SyncProtocol.kt and core/NetRequest.kt). Signatures are ECDSA P-256 /
SHA-256, DER-encoded, exactly what java.security "SHA256withECDSA" produces;
public keys are the 64 raw bytes X||Y; a node id is SHA-256(pub)[:16].
"""
import hashlib
import struct
from dataclasses import dataclass, field
from typing import Dict, List, Optional

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec

VERSION = 1
CLOCK_SKEW_MS = 10 * 60_000
MAX_HOPS = 6

TOMBSTONES = ("CANCELLED", "FULFILLED")
OPEN_STATES = ("CREATED", "SEARCHING_LOCAL", "DIRECT_SOURCE_FOUND", "NETWORK_REQUESTED", "CARRIED", "UPLOADED",
               "SUPPLY_POSSIBLE", "PROVIDER_ACTIVATING", "CONNECTING")


# ---- keys and signatures ---------------------------------------------------------------------------------

def node_id(pub_hex: str) -> str:
    return hashlib.sha256(bytes.fromhex(pub_hex)).hexdigest()[:32]


def short_id(pub_hex: str) -> str:
    return node_id(pub_hex)[:8]


def public_key(pub_hex: str):
    raw = bytes.fromhex(pub_hex)
    if len(raw) != 64:
        raise ValueError("public key must be 64 raw bytes")
    x = int.from_bytes(raw[:32], "big")
    y = int.from_bytes(raw[32:], "big")
    return ec.EllipticCurvePublicNumbers(x, y, ec.SECP256R1()).public_key()


def verify(pub_hex: str, data: bytes, sig_hex: str) -> bool:
    try:
        public_key(pub_hex).verify(bytes.fromhex(sig_hex), data, ec.ECDSA(hashes.SHA256()))
        return True
    except (InvalidSignature, ValueError):
        return False


# ---- the request --------------------------------------------------------------------------------------------

@dataclass
class Request:
    id: str
    origin_short: str
    origin_pub: str
    created_at: int
    updated_at: int
    expires_at: int
    zone: str
    desired_mb: int
    desired_minutes: int
    ceiling: int
    urgency: int
    state: str
    generation: int
    hops: int
    signature: str

    @property
    def tombstone(self) -> bool:
        return self.state in TOMBSTONES

    @property
    def open(self) -> bool:
        return self.state in OPEN_STATES

    def expired(self, now: int) -> bool:
        return now >= self.expires_at

    def line(self) -> str:
        return "\t".join(str(v) for v in [
            self.id, self.origin_short, self.origin_pub, self.created_at, self.updated_at, self.expires_at, self.zone.replace("\t", " "),
            self.desired_mb, self.desired_minutes, self.ceiling, self.urgency, self.state, self.generation, self.hops, self.signature])


def parse_request(fields: List[str]) -> Optional[Request]:
    if len(fields) < 15:
        return None
    try:
        return Request(fields[0], fields[1], fields[2], int(fields[3]), int(fields[4]), int(fields[5]), fields[6], int(fields[7]),
                       int(fields[8]), int(fields[9]), int(fields[10]), fields[11], int(fields[12]), int(fields[13]), fields[14])
    except ValueError:
        return None


def request_signed_bytes(r: Request) -> bytes:
    """Byte-for-byte NetRequest.signedBytes on the phone."""
    zone = r.zone.encode("utf-8")
    return (struct.pack(">B", VERSION) + bytes.fromhex(r.id) + bytes.fromhex(r.origin_short)
            + struct.pack(">qqq", r.created_at, r.updated_at, r.expires_at)
            + struct.pack(">iiiB", r.desired_mb, r.desired_minutes, r.ceiling, r.urgency & 0xFF)
            + struct.pack(">iB", r.generation, 1 if r.tombstone else 0)
            + struct.pack(">h", len(zone)) + zone)


def verify_request(r: Request) -> bool:
    if len(r.origin_pub) != 128 or not r.signature:
        return False
    if short_id(r.origin_pub) != r.origin_short.lower():
        return False
    return verify(r.origin_pub, request_signed_bytes(r), r.signature)


# ---- the upload ------------------------------------------------------------------------------------------------

@dataclass
class Coverage:
    key: str
    kind: str
    zone: str
    last_seen: int
    validated: bool
    price: int
    trust: str
    observations: int


@dataclass
class Availability:
    zone: str
    potential: bool
    sharing: bool
    upstream: int
    price: int
    busy: bool
    capable: bool


@dataclass
class Upload:
    node_id: str
    pub: str
    ts: int
    zone: str = "z?"
    coverage: List[Coverage] = field(default_factory=list)
    availability: Optional[Availability] = None
    requests: List[Request] = field(default_factory=list)
    jobs: List[tuple] = field(default_factory=list)


class ProtocolError(Exception):
    pass


def _b(s: str) -> bool:
    return s.lower() == "true"


def parse_upload(message: str, now: int) -> Upload:
    """Split, verify the signature, the identity behind it and the clock, then parse. Raises ProtocolError."""
    idx = message.rfind("S\t")
    if idx < 0 or (idx > 0 and message[idx - 1] != "\n"):
        raise ProtocolError("no signature")
    body = message[:idx]
    sig = message[idx + 2:].strip()
    up: Optional[Upload] = None
    lines = body.split("\n")
    version_ok = False
    for line in lines:
        if not line:
            continue
        f = line.split("\t")
        if f[0] == "V":
            if f[1] != str(VERSION):
                raise ProtocolError("unsupported version " + f[1])
            version_ok = True
        elif f[0] == "N":
            if len(f) < 4:
                raise ProtocolError("bad node line")
            try:
                ts = int(f[3])
            except ValueError:
                raise ProtocolError("bad timestamp")
            if node_id(f[2]) != f[1].lower():
                raise ProtocolError("node id does not match the key")
            if abs(now - ts) > CLOCK_SKEW_MS:
                raise ProtocolError("clock out of bounds")
            up = Upload(f[1].lower(), f[2], ts)
    if not version_ok or up is None:
        raise ProtocolError("missing version or node line")
    if not verify(up.pub, body.encode("utf-8"), sig):
        raise ProtocolError("invalid signature")
    for line in lines:
        if not line:
            continue
        f = line.split("\t")
        try:
            if f[0] == "Z":
                up.zone = f[1]
            elif f[0] == "C" and len(f) >= 9:
                up.coverage.append(Coverage(f[1], f[2], f[3], int(f[4]), _b(f[5]), int(f[6]), f[7], int(f[8])))
            elif f[0] == "P" and len(f) >= 8:
                up.availability = Availability(f[1], _b(f[2]), _b(f[3]), int(f[4]), int(f[5]), _b(f[6]), _b(f[7]))
            elif f[0] == "R":
                r = parse_request(f[1:])
                if r is not None:
                    up.requests.append(r)
            elif f[0] == "J" and len(f) >= 4:
                up.jobs.append((f[1], f[2], int(f[3])))
        except ValueError:
            continue  # one bad line never loses the rest
    return up


# ---- the download ---------------------------------------------------------------------------------------------------

def render_download(server_time: int, cells: List[dict], requests: List[Request], jobs: List[dict], statuses: List[tuple], advice: List[str]) -> str:
    out = ["V\t%d\t%d" % (VERSION, server_time)]
    for c in cells:
        out.append("X\t%s\t%s\t%d\t%d\t%d\t%d\t%d" % (c["zone"], c["status"], c["direct"], c["potential"], c["best_price"], c["last_seen"], c["observers"]))
    for r in requests:
        out.append("R\t" + r.line())
    for j in jobs:
        out.append("J\t%s\t%s\t%s\t%s\t%s\t%d" % (j["id"], j["type"], j["zone"], j["request_id"], j["state"], j["expires_at"]))
    for (rid, state, gen) in statuses:
        out.append("Q\t%s\t%s\t%d" % (rid, state, gen))
    for a in advice:
        out.append("A\t" + a.replace("\t", " ").replace("\n", " "))
    return "\n".join(out) + "\n"
