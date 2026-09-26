"""The `.prokmap` format: one small binary file holding a pedestrian graph and a
place index for one city, built from OpenStreetMap DATA (never tiles).

This module is the reference encoder/decoder. `tools.build_map_pack` writes the
real Brazzaville pack through it; `tests.test_mappack` pushes a synthetic graph
through it; `app/.../core/ProkMap.kt` is the Android reader of the same bytes and
`app/src/test/resources/synthetic.prokmap` (written by `tests.test_mappack`) is the
shared cross-language fixture, so a change here breaks a test instead of a phone.

Layout, every integer little-endian:

    header
        4   magic          "PKMP"
        u16 version        FORMAT_VERSION (1)
        u16 flags          reserved, 0
        u64 generated_at   unix ms
        i32 x4 bbox        min_lat, min_lon, max_lat, max_lon  (microdegrees)
        u32 node_count
        u32 edge_count
        u32 name_count     (entry 0 is always the empty name)
        u32 place_count
        u32 name_bytes     byte length of the name table
        u32 place_bytes    byte length of the place table
        u8  city_len, city UTF-8
    node table   node_count x (i32 lat, i32 lon)        microdegrees      8 B/node
    edge table   edge_count x (u32 a, u32 b, u16 length_m, u8 class, u16 name)  13 B/edge
    name table   name_count x (u16 len, UTF-8)
    place table  place_count x (u8 kind, i32 lat, i32 lon, u16 len, UTF-8)

Edges are undirected (people walk both ways). Lengths are whole metres, never 0.
Classes and place kinds are the small tables below; unknown values are tolerated
by readers (drawn thin, never routed differently).
"""
import heapq
import math
import struct

MAGIC = b"PKMP"
FORMAT_VERSION = 1
MICRO = 1_000_000

HEADER = struct.Struct("<4sHHQiiiiIIIIII")   # 56 bytes, then u8 city_len + city
NODE = struct.Struct("<ii")
EDGE = struct.Struct("<IIHBH")
PLACE_HEAD = struct.Struct("<BiiH")

# highway class -> u8. The renderer widens the low numbers; the router slows the last two.
CLASSES = {
    "primary": 1, "secondary": 2, "tertiary": 3, "residential": 4, "unclassified": 5,
    "living_street": 6, "pedestrian": 7, "service": 8, "footway": 9, "cycleway": 10,
    "path": 11, "track": 12, "steps": 13,
}
CLASS_NAMES = {v: k for k, v in CLASSES.items()}
SLOW_CLASSES = {CLASSES["steps"], CLASSES["track"]}   # +10 % walking time

PLACE_KINDS = {
    "city": 1, "town": 2, "village": 3, "suburb": 4, "neighbourhood": 5, "quarter": 6,
    "school": 16, "hospital": 17, "market": 18, "place_of_worship": 19,
}
PLACE_KIND_NAMES = {v: k for k, v in PLACE_KINDS.items()}
NEIGHBOURHOOD_KINDS = {PLACE_KINDS["suburb"], PLACE_KINDS["neighbourhood"], PLACE_KINDS["quarter"]}

WALK_M_PER_S = 4.5 * 1000 / 3600.0     # 1.25 m/s
SNAP_MAX_M = 300.0                     # farther than this from any node: no route, no ETA


class FormatError(ValueError):
    pass


def haversine_m(lat1, lon1, lat2, lon2) -> float:
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = p2 - p1
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(a)))


class Graph:
    """The in-memory form on both sides of the encoder.

    nodes: list of (lat_micro, lon_micro)
    edges: list of (a, b, length_m, class_u8, name_index)
    names: list of str, names[0] == ""
    places: list of (kind_u8, lat_micro, lon_micro, name)
    """

    def __init__(self, city="", generated_at=0, bbox=None, nodes=None, edges=None, names=None, places=None):
        self.city = city
        self.generated_at = int(generated_at)
        self.bbox = tuple(bbox) if bbox else None
        self.nodes = list(nodes or [])
        self.edges = list(edges or [])
        self.names = list(names or [""])
        self.places = list(places or [])
        if not self.names or self.names[0] != "":
            self.names.insert(0, "")

    def computed_bbox(self):
        if self.bbox:
            return self.bbox
        if not self.nodes:
            return (0, 0, 0, 0)
        return (min(n[0] for n in self.nodes), min(n[1] for n in self.nodes),
                max(n[0] for n in self.nodes), max(n[1] for n in self.nodes))


def encode(g: Graph) -> bytes:
    if len(g.names) > 65535:
        raise FormatError("too many names for a u16 index: %d" % len(g.names))
    if len(g.nodes) > 0xFFFFFFFF:
        raise FormatError("too many nodes")
    city = g.city.encode("utf-8")
    if len(city) > 255:
        raise FormatError("city name too long")

    nodes = bytearray()
    for lat, lon in g.nodes:
        nodes += NODE.pack(int(lat), int(lon))

    edges = bytearray()
    n = len(g.nodes)
    for a, b, length_m, cls, name in g.edges:
        if not (0 <= a < n and 0 <= b < n):
            raise FormatError("edge references a missing node")
        if not (0 <= name < len(g.names)):
            raise FormatError("edge references a missing name")
        length = max(1, min(65535, int(round(length_m))))
        edges += EDGE.pack(a, b, length, int(cls) & 0xFF, name)

    names = bytearray()
    for s in g.names:
        raw = s.encode("utf-8")
        if len(raw) > 65535:
            raise FormatError("name too long")
        names += struct.pack("<H", len(raw)) + raw

    places = bytearray()
    for kind, lat, lon, name in g.places:
        raw = name.encode("utf-8")
        places += PLACE_HEAD.pack(int(kind) & 0xFF, int(lat), int(lon), len(raw)) + raw

    bbox = g.computed_bbox()
    head = HEADER.pack(MAGIC, FORMAT_VERSION, 0, int(g.generated_at),
                       bbox[0], bbox[1], bbox[2], bbox[3],
                       len(g.nodes), len(g.edges), len(g.names), len(g.places),
                       len(names), len(places))
    return bytes(head + bytes([len(city)]) + city + nodes + edges + names + places)


def decode_header(data: bytes) -> dict:
    if len(data) < HEADER.size + 1:
        raise FormatError("file too short for a header")
    (magic, version, flags, generated_at, min_lat, min_lon, max_lat, max_lon,
     node_count, edge_count, name_count, place_count, name_bytes, place_bytes) = HEADER.unpack_from(data, 0)
    if magic != MAGIC:
        raise FormatError("bad magic %r" % magic)
    if version != FORMAT_VERSION:
        raise FormatError("unsupported format version %d" % version)
    city_len = data[HEADER.size]
    off = HEADER.size + 1
    if len(data) < off + city_len:
        raise FormatError("file too short for the city name")
    city = data[off:off + city_len].decode("utf-8")
    off += city_len
    expected = off + node_count * NODE.size + edge_count * EDGE.size + name_bytes + place_bytes
    if expected != len(data):
        raise FormatError("size mismatch: header implies %d bytes, file has %d" % (expected, len(data)))
    return dict(version=version, flags=flags, generated_at=generated_at,
                bbox=(min_lat, min_lon, max_lat, max_lon),
                node_count=node_count, edge_count=edge_count, name_count=name_count,
                place_count=place_count, name_bytes=name_bytes, place_bytes=place_bytes,
                city=city, body_offset=off)


def decode(data: bytes) -> Graph:
    h = decode_header(data)
    off = h["body_offset"]
    nodes = []
    for _ in range(h["node_count"]):
        nodes.append(NODE.unpack_from(data, off))
        off += NODE.size
    edges = []
    n = len(nodes)
    for _ in range(h["edge_count"]):
        a, b, length, cls, name = EDGE.unpack_from(data, off)
        off += EDGE.size
        if a >= n or b >= n:
            raise FormatError("edge references node beyond the table")
        if name >= h["name_count"]:
            raise FormatError("edge references name beyond the table")
        edges.append((a, b, length, cls, name))
    names = []
    end = off + h["name_bytes"]
    for _ in range(h["name_count"]):
        if off + 2 > end:
            raise FormatError("name table truncated")
        (ln,) = struct.unpack_from("<H", data, off)
        off += 2
        if off + ln > end:
            raise FormatError("name table truncated")
        names.append(data[off:off + ln].decode("utf-8"))
        off += ln
    if off != end:
        raise FormatError("name table has trailing bytes")
    places = []
    end = off + h["place_bytes"]
    for _ in range(h["place_count"]):
        if off + PLACE_HEAD.size > end:
            raise FormatError("place table truncated")
        kind, lat, lon, ln = PLACE_HEAD.unpack_from(data, off)
        off += PLACE_HEAD.size
        if off + ln > end:
            raise FormatError("place table truncated")
        places.append((kind, lat, lon, data[off:off + ln].decode("utf-8")))
        off += ln
    if off != end:
        raise FormatError("place table has trailing bytes")
    if not names or names[0] != "":
        raise FormatError("name 0 must be the empty name")
    return Graph(h["city"], h["generated_at"], h["bbox"], nodes, edges, names, places)


# ---- routing (reference implementation; the phone's is ProkMap.kt) ------------------------

def _adjacency(g: Graph):
    adj = [[] for _ in g.nodes]
    for a, b, length, cls, _name in g.edges:
        adj[a].append((b, length, cls))
        adj[b].append((a, length, cls))
    return adj


def nearest_node(g: Graph, lat: float, lon: float):
    """(index, distance_m) of the closest node, brute force. Fine for a reference."""
    best, best_d = -1, float("inf")
    for i, (nlat, nlon) in enumerate(g.nodes):
        d = haversine_m(lat, lon, nlat / MICRO, nlon / MICRO)
        if d < best_d:
            best, best_d = i, d
    return best, best_d


def eta_seconds(edges_walked, snap_extra_m=0.0) -> int:
    """4.5 km/h, +10 % on steps and unpaved track; snap legs at the plain speed."""
    t = snap_extra_m / WALK_M_PER_S
    for length, cls in edges_walked:
        t += length / WALK_M_PER_S * (1.1 if cls in SLOW_CLASSES else 1.0)
    return int(round(t))


def route(g: Graph, from_lat, from_lon, to_lat, to_lon, snap_max_m=SNAP_MAX_M):
    """A* on the graph. Returns None when either end is farther than snap_max_m from any
    node; otherwise dict(distance_m, eta_s, node_path, points)."""
    s, ds = nearest_node(g, from_lat, from_lon)
    t, dt = nearest_node(g, to_lat, to_lon)
    if s < 0 or ds > snap_max_m or dt > snap_max_m:
        return None
    adj = _adjacency(g)
    tlat, tlon = g.nodes[t][0] / MICRO, g.nodes[t][1] / MICRO

    def h(i):
        return haversine_m(g.nodes[i][0] / MICRO, g.nodes[i][1] / MICRO, tlat, tlon)

    dist = {s: 0}
    prev = {}
    prev_edge = {}
    pq = [(h(s), s)]
    closed = set()
    while pq:
        _, u = heapq.heappop(pq)
        if u in closed:
            continue
        if u == t:
            break
        closed.add(u)
        for v, length, cls in adj[u]:
            nd = dist[u] + length
            if nd < dist.get(v, float("inf")):
                dist[v] = nd
                prev[v] = u
                prev_edge[v] = (length, cls)
                heapq.heappush(pq, (nd + h(v), v))
    if t not in dist:
        return None
    path = [t]
    walked = []
    while path[-1] != s:
        walked.append(prev_edge[path[-1]])
        path.append(prev[path[-1]])
    path.reverse()
    walked.reverse()
    points = [(from_lat, from_lon)] + [(g.nodes[i][0] / MICRO, g.nodes[i][1] / MICRO) for i in path] + [(to_lat, to_lon)]
    return dict(distance_m=dist[t] + ds + dt, eta_s=eta_seconds(walked, ds + dt),
                node_path=path, points=points)
