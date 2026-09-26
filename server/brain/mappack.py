"""The Brain's view of the offline map packs built by `tools.build_map_pack`.

A pack directory holds, per city, `<city>.prokmap` and `<city>.manifest.json`. The
Brain serves them as they are - public data (ODbL), no signature on the request:

    GET /v1/map/{city}/manifest   -> the manifest JSON
    GET /v1/map/{city}/pack       -> the bytes, application/octet-stream

`MapPacks` is the small helper the handler calls; `validate` is what the builder runs
before calling a pack done, and what an operator can run on a copied file.
"""
import hashlib
import json
import os
import re

from tools import prokmap

_CITY = re.compile(r"^[a-z][a-z0-9_-]{0,31}$")


class MapPacks:
    def __init__(self, directory: str):
        self.dir = directory

    def _ok(self, city: str) -> bool:
        return bool(city) and _CITY.match(city) is not None

    def manifest_path(self, city: str) -> str:
        return os.path.join(self.dir, city + ".manifest.json")

    def pack_path(self, city: str) -> str:
        return os.path.join(self.dir, city + ".prokmap")

    def cities(self):
        if not os.path.isdir(self.dir):
            return []
        out = []
        for f in sorted(os.listdir(self.dir)):
            if f.endswith(".manifest.json") and self._ok(f[:-len(".manifest.json")]):
                out.append(f[:-len(".manifest.json")])
        return out

    def manifest(self, city: str):
        """The manifest dict, or None when the city has no pack (or the name is not a city)."""
        if not self._ok(city):
            return None
        try:
            with open(self.manifest_path(city), "r", encoding="utf-8") as f:
                m = json.load(f)
        except (OSError, ValueError):
            return None
        return m if isinstance(m, dict) and m.get("city") == city else None

    def open(self, city: str):
        """(path, byte length) of the pack, or None. The caller streams the file itself."""
        m = self.manifest(city)
        if m is None:
            return None
        p = self.pack_path(city)
        try:
            n = os.path.getsize(p)
        except OSError:
            return None
        if n != m.get("bytes"):
            return None        # a half-copied pack is not served
        return p, n


def validate(path: str, manifest_path: str = "", signer_hex: str = "") -> dict:
    """Read a pack fully and check it against its manifest. Raises ValueError with a plain
    reason on the first problem; returns the decoded header on success.

    Checks: magic, format version, counts consistent with the byte length, every table
    parses, every edge points at a real node and name, name 0 empty, bbox contains the
    nodes, sha256 equal to the manifest's, byte count equal to the manifest's, and - if a
    signer public key is given - the manifest signature over the sha256.
    """
    with open(path, "rb") as f:
        data = f.read()
    try:
        head = prokmap.decode_header(data)
        g = prokmap.decode(data)
    except prokmap.FormatError as e:
        raise ValueError("pack invalid: " + str(e))
    lo_lat, lo_lon, hi_lat, hi_lon = head["bbox"]
    for lat, lon in g.nodes:
        if not (lo_lat <= lat <= hi_lat and lo_lon <= lon <= hi_lon):
            raise ValueError("pack invalid: node outside the header bbox")
    sha = hashlib.sha256(data).hexdigest()
    head["sha256"] = sha
    head["bytes"] = len(data)
    if not manifest_path:
        manifest_path = path[:-len(".prokmap")] + ".manifest.json" if path.endswith(".prokmap") else ""
    if manifest_path:
        try:
            with open(manifest_path, "r", encoding="utf-8") as f:
                m = json.load(f)
        except (OSError, ValueError) as e:
            raise ValueError("manifest unreadable: " + str(e))
        if m.get("sha256") != sha:
            raise ValueError("sha256 mismatch: manifest %s, file %s" % (m.get("sha256"), sha))
        if m.get("bytes") != len(data):
            raise ValueError("byte count mismatch: manifest %s, file %d" % (m.get("bytes"), len(data)))
        if m.get("generated_at") != head["generated_at"]:
            raise ValueError("generated_at mismatch between manifest and header")
        c = m.get("counts") or {}
        for k in ("nodes", "edges", "places"):
            if k in c and c[k] != head[k[:-1] + "_count"]:
                raise ValueError("%s count mismatch: manifest %s, header %s" % (k, c[k], head[k[:-1] + "_count"]))
        if signer_hex:
            _verify_signature(m, signer_hex)
        head["manifest"] = m
    return head


def _verify_signature(m: dict, signer_hex: str):
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.exceptions import InvalidSignature
    sig = m.get("signature")
    if not sig:
        raise ValueError("manifest is not signed")
    if m.get("signer") != signer_hex:
        raise ValueError("manifest signed by a different key")
    raw = bytes.fromhex(signer_hex)
    pub = ec.EllipticCurvePublicNumbers(int.from_bytes(raw[:32], "big"), int.from_bytes(raw[32:], "big"),
                                        ec.SECP256R1()).public_key()
    try:
        pub.verify(bytes.fromhex(sig), bytes.fromhex(m["sha256"]), ec.ECDSA(hashes.SHA256()))
    except InvalidSignature:
        raise ValueError("manifest signature does not verify")
