"""Build the offline map pack for one city from OpenStreetMap DATA.

    python -m tools.build_map_pack --city brazzaville --out C:\\ProkNetBrain\\maps
    python -m tools.build_map_pack --city brazzaville --out ... --dry-run
    python -m tools.build_map_pack --city brazzaville --out ... --limit-bbox -4.30,15.20,-4.20,15.30

Input: the Geofabrik extract for the Republic of the Congo (ODbL). It is downloaded
once to `<out>/source/` and never committed. The OpenStreetMap Foundation's tile
servers are NOT touched: this reads the data and draws nothing.

Output (in `<out>/`, outside the repository):

    <city>.prokmap           the binary pack (tools.prokmap format)
    <city>.manifest.json     city, version, generated_at, bytes, sha256, attribution,
                             source, bbox, counts, and what was dropped to fit

Version is the build's unix time in seconds, so a rebuild is always newer.
Optional `--sign-key file.pem` (P-256, kept outside the repository like the receipt-rule
key) adds `signature` and `signer` to the manifest; the pack bytes are unchanged.
"""
import argparse
import collections
import datetime
import hashlib
import json
import math
import os
import sys
import time
import urllib.request

try:
    from tools import prokmap
except ImportError:                              # run as a script from server/tools
    sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    from tools import prokmap

ATTRIBUTION = "© OpenStreetMap contributors, ODbL 1.0"

CITIES = {
    "brazzaville": dict(
        name="Brazzaville",
        # verified against the data on 2026-09-26: the contract's -4.35..-4.15 / 15.15..15.35
        # misses the northern expansion (Kintélé, the airport side) and the eastern edge,
        # ~2,700 walkable ways. Widened; the original box stays the "core" that is never
        # thinned when the pack has to be simplified to fit.
        bbox=(-4.40, 15.10, -3.95, 15.45),
        core=(-4.35, 15.15, -4.15, 15.35),
        extract_url="https://download.geofabrik.de/africa/congo-brazzaville-latest.osm.pbf",
        extract_file="congo-brazzaville-latest.osm.pbf",
    ),
}

WALKABLE = set(prokmap.CLASSES)
# motorway / trunk (and their links) are never walkable here; links of the rest inherit
LINK_OF = {"primary_link": "primary", "secondary_link": "secondary", "tertiary_link": "tertiary"}
LANDMARK_AMENITIES = {"school", "hospital", "market", "place_of_worship"}
PLACE_KINDS = {"city", "town", "village", "suburb", "neighbourhood", "quarter"}
# marketplaces are usually tagged amenity=marketplace; the contract's word is "market"
AMENITY_ALIAS = {"marketplace": "market"}

DEFAULT_BUDGET = 8 * 1024 * 1024
MAX_NAMES = 65535


def in_box(lat, lon, box):
    return box[0] <= lat <= box[2] and box[1] <= lon <= box[3]


def walk_class(tags):
    """The u8 class for a way, or None when it must not be walked."""
    hw = tags.get("highway")
    if hw is None:
        return None
    hw = LINK_OF.get(hw, hw)
    if hw not in WALKABLE:
        return None
    if tags.get("foot") == "no":
        return None
    if tags.get("access") in ("private", "no") and tags.get("foot") not in ("yes", "designated", "permissive"):
        return None
    if hw == "cycleway" and tags.get("foot") not in ("yes", "designated", "permissive"):
        return None
    if tags.get("area") == "yes":
        return None
    return prokmap.CLASSES[hw]


class Extract:
    """One pass over the PBF: walkable ways with their node positions, and the places."""

    def __init__(self, bbox):
        import osmium
        self.bbox = bbox
        # ways: list of (class_u8, name, [(osm_node_id, lat, lon), ...]) already clipped
        self.ways = []
        self.places = []          # (kind_name, name, lat, lon)
        self.landmarks = []       # (kind_name, name, lat, lon)
        self.skipped_no_location = 0
        outer = self

        class H(osmium.SimpleHandler):
            def node(self, n):
                t = n.tags
                if "name" not in t:
                    return
                place = t.get("place")
                if place in PLACE_KINDS:
                    if in_box(n.location.lat, n.location.lon, outer.bbox):
                        outer.places.append((place, t["name"], n.location.lat, n.location.lon))
                    return
                am = AMENITY_ALIAS.get(t.get("amenity"), t.get("amenity"))
                if am in LANDMARK_AMENITIES and in_box(n.location.lat, n.location.lon, outer.bbox):
                    outer.landmarks.append((am, t["name"], n.location.lat, n.location.lon))

            def way(self, w):
                t = w.tags
                cls = walk_class(t)
                if cls is not None:
                    outer._way(w, cls, t.get("name", ""))
                    return
                # a named landmark drawn as a building outline: one coarse point
                if "name" in t:
                    am = AMENITY_ALIAS.get(t.get("amenity"), t.get("amenity"))
                    place = t.get("place")
                    kind = am if am in LANDMARK_AMENITIES else (place if place in PLACE_KINDS else None)
                    if kind is None:
                        return
                    pts = []
                    try:
                        for nd in w.nodes:
                            if nd.location.valid():
                                pts.append((nd.location.lat, nd.location.lon))
                    except osmium.InvalidLocationError:
                        return
                    if not pts:
                        return
                    lat = sum(p[0] for p in pts) / len(pts)
                    lon = sum(p[1] for p in pts) / len(pts)
                    if in_box(lat, lon, outer.bbox):
                        (outer.places if kind in PLACE_KINDS else outer.landmarks).append((kind, t["name"], lat, lon))

        self.handler = H()

    def _way(self, w, cls, name):
        import osmium
        run = []
        try:
            for nd in w.nodes:
                if not nd.location.valid():
                    self.skipped_no_location += 1
                    if len(run) >= 2:
                        self.ways.append((cls, name, run))
                    run = []
                    continue
                lat, lon = nd.location.lat, nd.location.lon
                if in_box(lat, lon, self.bbox):
                    run.append((nd.ref, lat, lon))
                else:
                    # clipped at the box: the way continues outside, we keep what is inside
                    if len(run) >= 2:
                        self.ways.append((cls, name, run))
                    run = []
        except osmium.InvalidLocationError:
            self.skipped_no_location += 1
        if len(run) >= 2:
            self.ways.append((cls, name, run))

    def run(self, path):
        self.handler.apply_file(path, locations=True, idx="flex_mem")


def build_graph(ways, places, landmarks, city_name, generated_at, bbox, budget, core, log):
    """Ways -> deduplicated nodes, one edge per consecutive node pair, name and place tables.
    Simplifies in two steps only if the encoded size is over `budget`, and says what it did."""
    dropped = {}
    stage = 0
    while True:
        g, stats = _assemble(ways, places, landmarks, city_name, generated_at, bbox)
        size = len(prokmap.encode(g))
        stats["bytes"] = size
        if size <= budget or stage >= 2:
            if size > budget:
                log("WARNING: still %d bytes over the %d byte budget after simplification" % (size - budget, budget))
            return g, stats, dropped
        stage += 1
        if stage == 1:
            # 1: service and track ways outside the core box are the least useful for walking
            before = len(ways)
            keep = []
            for cls, name, pts in ways:
                if cls in (prokmap.CLASSES["service"], prokmap.CLASSES["track"]) and \
                        not any(in_box(la, lo, core) for _, la, lo in pts):
                    continue
                keep.append((cls, name, pts))
            ways = keep
            dropped["service_track_ways_outside_core"] = before - len(keep)
            log("over budget (%d > %d): dropped %d service/track ways outside the core box" % (size, budget, before - len(keep)))
        elif stage == 2:
            # 2: merge degree-2 chains: the shape between two junctions becomes a straight line
            n_before = stats["nodes"]
            ways = _merge_chains(ways)
            g2, s2 = _assemble(ways, places, landmarks, city_name, generated_at, bbox)
            dropped["shape_nodes_merged"] = n_before - s2["nodes"]
            dropped["geometry"] = "degree-2 chains merged; streets are drawn as straight lines between junctions"
            log("over budget (%d > %d): merged degree-2 chains, %d shape nodes removed" % (size, budget, n_before - s2["nodes"]))


def _assemble(ways, places, landmarks, city_name, generated_at, bbox):
    node_index = {}
    nodes = []
    name_count = collections.Counter()
    for _cls, name, pts in ways:
        if name:
            name_count[name] += 1
    names = [""] + [n for n, _ in name_count.most_common(MAX_NAMES - 1)]
    name_index = {n: i for i, n in enumerate(names)}
    names_dropped = max(0, len(name_count) - (MAX_NAMES - 1))

    edges = []
    seen = set()
    for cls, name, pts in ways:
        ni = name_index.get(name, 0)
        prev = None
        for p in pts:
            ref, lat, lon = p[0], p[1], p[2]
            idx = node_index.get(ref)
            if idx is None:
                idx = len(nodes)
                node_index[ref] = idx
                nodes.append((int(round(lat * prokmap.MICRO)), int(round(lon * prokmap.MICRO))))
            if prev is not None and prev != idx:
                key = (min(prev, idx), max(prev, idx))
                if key not in seen:
                    seen.add(key)
                    if len(p) > 3:
                        length = p[3]       # a merged chain: the real walked length
                    else:
                        a, b = nodes[prev], nodes[idx]
                        length = prokmap.haversine_m(a[0] / prokmap.MICRO, a[1] / prokmap.MICRO,
                                                     b[0] / prokmap.MICRO, b[1] / prokmap.MICRO)
                    edges.append((prev, idx, max(1, int(round(length))), cls, ni))
            prev = idx

    # places: de-duplicated by (kind, normalised name) within ~150 m, cities/towns first
    plist = []
    seen_p = []
    for kind, name, lat, lon in sorted(places + landmarks, key=lambda p: prokmap.PLACE_KINDS[p[0]]):
        key = (kind, name.strip().lower())
        dup = False
        for k2, la2, lo2 in seen_p:
            if k2 == key and prokmap.haversine_m(lat, lon, la2, lo2) < 150:
                dup = True
                break
        if dup:
            continue
        seen_p.append((key, lat, lon))
        plist.append((prokmap.PLACE_KINDS[kind], int(round(lat * prokmap.MICRO)), int(round(lon * prokmap.MICRO)), name.strip()))

    box = tuple(int(round(v * prokmap.MICRO)) for v in bbox)
    g = prokmap.Graph(city_name, generated_at, box, nodes, edges, names, plist)
    stats = dict(ways=len(ways), nodes=len(nodes), edges=len(edges), names=len(names),
                 places=len(plist), names_dropped=names_dropped,
                 classes={prokmap.CLASS_NAMES.get(c, str(c)): n for c, n in
                          sorted(collections.Counter(e[3] for e in edges).items())},
                 place_kinds={prokmap.PLACE_KIND_NAMES.get(k, str(k)): n for k, n in
                              sorted(collections.Counter(p[0] for p in plist).items())})
    return g, stats


def _merge_chains(ways):
    """Keep only junction nodes and way ends; the length of a merged run is the sum of the
    original pieces (the router keeps walking the real distance, the drawing loses the bends)."""
    use = collections.Counter()
    for _cls, _name, pts in ways:
        for ref, _la, _lo in pts:
            use[ref] += 1
    out = []
    for cls, name, pts in ways:
        kept = [pts[0][:3]]
        acc = 0.0
        for i in range(1, len(pts)):
            acc += prokmap.haversine_m(pts[i - 1][1], pts[i - 1][2], pts[i][1], pts[i][2])
            if i == len(pts) - 1 or use[pts[i][0]] > 1:
                kept.append((pts[i][0], pts[i][1], pts[i][2], acc))
                acc = 0.0
        out.append((cls, name, kept))
    return out


def download(url, dest, log):
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    log("downloading " + url)
    tmp = dest + ".part"
    req = urllib.request.Request(url, headers={"User-Agent": "ProkNet-map-pack-builder/1 (+proknet)"})
    with urllib.request.urlopen(req, timeout=60) as r, open(tmp, "wb") as f:
        last_modified = r.headers.get("Last-Modified", "")
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
    os.replace(tmp, dest)
    if last_modified:
        with open(dest + ".last-modified", "w") as f:
            f.write(last_modified)
    log("saved %d bytes to %s" % (os.path.getsize(dest), dest))


def source_info(path, url):
    import osmium
    info = dict(file=os.path.basename(path), url=url, bytes=os.path.getsize(path),
                file_mtime=datetime.datetime.utcfromtimestamp(os.path.getmtime(path)).strftime("%Y-%m-%dT%H:%M:%SZ"))
    lm = path + ".last-modified"
    if os.path.exists(lm):
        with open(lm) as f:
            info["last_modified"] = f.read().strip()
    try:
        r = osmium.io.Reader(path)
        ts = r.header().get("osmosis_replication_timestamp")
        r.close()
        if ts:
            info["osm_data_timestamp"] = ts
    except Exception as e:                      # a missing header is not a build failure
        info["header_error"] = str(e)
    return info


def sign_manifest(manifest, key_path):
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    with open(key_path, "rb") as f:
        priv = serialization.load_pem_private_key(f.read(), password=None)
    sig = priv.sign(bytes.fromhex(manifest["sha256"]), ec.ECDSA(hashes.SHA256()))
    n = priv.public_key().public_numbers()
    manifest["signature"] = sig.hex()
    manifest["signer"] = (n.x.to_bytes(32, "big") + n.y.to_bytes(32, "big")).hex()


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--city", default="brazzaville", choices=sorted(CITIES))
    ap.add_argument("--out", required=True, help="output directory, e.g. C:\\ProkNetBrain\\maps")
    ap.add_argument("--source", default="", help="path to the .osm.pbf (default: <out>/source/<extract>)")
    ap.add_argument("--no-download", action="store_true", help="fail instead of downloading a missing extract")
    ap.add_argument("--limit-bbox", default="", help="min_lat,min_lon,max_lat,max_lon overriding the city box")
    ap.add_argument("--budget", type=int, default=DEFAULT_BUDGET, help="bytes above which the graph is simplified")
    ap.add_argument("--dry-run", action="store_true", help="report counts and size, write nothing")
    ap.add_argument("--sign-key", default="", help="PEM P-256 private key to sign the manifest (optional)")
    args = ap.parse_args(argv)

    t0 = time.time()
    log = lambda s: print("[%6.1fs] %s" % (time.time() - t0, s), flush=True)
    city = CITIES[args.city]
    bbox = city["bbox"]
    if args.limit_bbox:
        bbox = tuple(float(v) for v in args.limit_bbox.split(","))
        if len(bbox) != 4 or bbox[0] >= bbox[2] or bbox[1] >= bbox[3]:
            ap.error("--limit-bbox must be min_lat,min_lon,max_lat,max_lon")
    core = city["core"]
    src = args.source or os.path.join(args.out, "source", city["extract_file"])
    if not os.path.exists(src):
        if args.no_download:
            ap.error("extract not found: " + src)
        download(city["extract_url"], src, log)
    else:
        log("using existing extract " + src)

    log("reading %s, bbox %s" % (os.path.basename(src), bbox))
    ex = Extract(bbox)
    ex.run(src)
    log("walkable way pieces %d, places %d, landmarks %d, nodes without location %d" %
        (len(ex.ways), len(ex.places), len(ex.landmarks), ex.skipped_no_location))

    generated_at = int(time.time() * 1000)
    g, stats, dropped = build_graph(ex.ways, ex.places, ex.landmarks, city["name"], generated_at,
                                    bbox, args.budget, core, log)
    data = prokmap.encode(g)
    sha = hashlib.sha256(data).hexdigest()
    log("graph: %(nodes)d nodes, %(edges)d edges, %(names)d names, %(places)d places -> %(bytes)d bytes" % stats)
    log("classes: " + json.dumps(stats["classes"]))
    log("places: " + json.dumps(stats["place_kinds"], ensure_ascii=False))
    log("pack %.2f MB (%.1f%% of the 50 MB ceiling), sha256 %s" % (len(data) / 1e6, len(data) / 5e5, sha))

    manifest = dict(
        city=args.city, city_name=city["name"], format_version=prokmap.FORMAT_VERSION,
        version=generated_at // 1000, generated_at=generated_at,
        generated_at_iso=datetime.datetime.utcfromtimestamp(generated_at / 1000).strftime("%Y-%m-%dT%H:%M:%SZ"),
        bytes=len(data), sha256=sha, attribution=ATTRIBUTION,
        license_url="https://www.openstreetmap.org/copyright",
        source=source_info(src, city["extract_url"]),
        bbox=dict(min_lat=bbox[0], min_lon=bbox[1], max_lat=bbox[2], max_lon=bbox[3]),
        counts=dict(nodes=stats["nodes"], edges=stats["edges"], names=stats["names"], places=stats["places"]),
        classes=stats["classes"], place_kinds=stats["place_kinds"],
        dropped=dict(dropped, names_beyond_u16=stats["names_dropped"]),
        build_seconds=round(time.time() - t0, 1),
    )
    if args.dry_run:
        log("dry run: nothing written")
        print(json.dumps(manifest, indent=2, ensure_ascii=False))
        return 0

    os.makedirs(args.out, exist_ok=True)
    pack_path = os.path.join(args.out, args.city + ".prokmap")
    man_path = os.path.join(args.out, args.city + ".manifest.json")
    tmp = pack_path + ".part"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, pack_path)
    if args.sign_key:
        sign_manifest(manifest, args.sign_key)
    with open(man_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
        f.write("\n")

    # read it back the way the Brain will, before calling it done
    from brain import mappack
    v = mappack.validate(pack_path, man_path)
    log("validated: %s" % json.dumps(dict(nodes=v["node_count"], edges=v["edge_count"], places=v["place_count"])))
    log("wrote %s and %s in %.1f s" % (pack_path, man_path, time.time() - t0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
