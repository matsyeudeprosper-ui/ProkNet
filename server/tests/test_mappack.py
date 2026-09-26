"""The offline map pack: encoder, decoder, validator, router and the Brain's serving helper.

No network and no download: a tiny synthetic graph goes through the same encoder the
Brazzaville build uses. The test also WRITES `app/src/test/resources/synthetic.prokmap`
and `synthetic.expected.json`, the shared fixture the Kotlin reader test opens - the
same rule as `test_crosslang`: two languages implementing one byte format must meet on
one committed file, or each stays self-consistently wrong.
"""
import hashlib
import json
import os
import tempfile
import unittest

from brain import mappack
from tools import build_map_pack, prokmap

M = prokmap.MICRO

# a corner of Bacongo, in microdegrees; ~111 m per 1000 microdegrees of latitude here
LAT0, LON0 = -4_263_400, 15_242_900


def synthetic() -> prokmap.Graph:
    nodes = [
        (LAT0, LON0),                    # 0
        (LAT0, LON0 + 1000),             # 1  ~111 m east of 0
        (LAT0, LON0 + 2000),             # 2
        (LAT0 + 800, LON0 + 1500),       # 3  up the steps from 1
        (LAT0 + 1000, LON0 + 2000),      # 4  the destination
        (LAT0 + 50_000, LON0 + 50_000),  # 5  an island 7 km away, one dead-end edge
        (LAT0 + 50_000, LON0 + 50_500),  # 6
    ]
    names = ["", "Avenue de la Paix", "Rue Mbochi", "Escalier Saint-Pierre"]

    def length(a, b):
        return prokmap.haversine_m(nodes[a][0] / M, nodes[a][1] / M, nodes[b][0] / M, nodes[b][1] / M)

    C = prokmap.CLASSES
    edges = [
        (0, 1, length(0, 1), C["residential"], 1),
        (1, 2, length(1, 2), C["residential"], 1),
        (2, 4, length(2, 4), C["footway"], 2),
        (1, 3, length(1, 3), C["steps"], 3),        # the short way has stairs: +10 % time
        (3, 4, length(3, 4), C["footway"], 2),
        (5, 6, length(5, 6), C["path"], 0),
    ]
    places = [
        (prokmap.PLACE_KINDS["suburb"], LAT0 - 200, LON0 + 300, "Bacongo"),
        (prokmap.PLACE_KINDS["neighbourhood"], LAT0 + 1200, LON0 + 2100, "Poto-Poto"),
        (prokmap.PLACE_KINDS["school"], LAT0 + 400, LON0 + 900, "École Général Leclerc"),
        (prokmap.PLACE_KINDS["market"], LAT0 + 900, LON0 + 1900, "Marché Total"),
    ]
    return prokmap.Graph("Synthetic", 1_790_000_000_000, None, nodes, edges, names, places)


def repo_root():
    d = os.path.dirname(os.path.abspath(__file__))
    for _ in range(6):
        if os.path.isdir(os.path.join(d, "app")) and os.path.isdir(os.path.join(d, "server")):
            return d
        d = os.path.dirname(d)
    return None


class MapPackTest(unittest.TestCase):
    def setUp(self):
        self.g = synthetic()
        self.data = prokmap.encode(self.g)

    # ================= the bytes =================

    def test_round_trip_is_exact(self):
        g2 = prokmap.decode(self.data)
        self.assertEqual(g2.city, "Synthetic")
        self.assertEqual(g2.generated_at, 1_790_000_000_000)
        self.assertEqual(g2.nodes, self.g.nodes)
        self.assertEqual(g2.names, self.g.names)
        self.assertEqual(g2.places, self.g.places)
        # lengths are stored as whole metres
        self.assertEqual([(a, b, cls, n) for a, b, _l, cls, n in g2.edges],
                         [(a, b, cls, n) for a, b, _l, cls, n in self.g.edges])
        for (_a, _b, l1, _c, _n), (_a2, _b2, l2, _c2, _n2) in zip(self.g.edges, g2.edges):
            self.assertEqual(int(round(l1)), l2)
        # the bbox is computed from the nodes when not given
        self.assertEqual(g2.bbox, (LAT0, LON0, LAT0 + 50_000, LON0 + 50_500))
        # re-encoding the decoded graph gives the same bytes
        self.assertEqual(prokmap.encode(g2), self.data)

    def test_header_is_the_documented_layout(self):
        self.assertEqual(self.data[:4], b"PKMP")
        h = prokmap.decode_header(self.data)
        self.assertEqual(h["version"], 1)
        self.assertEqual((h["node_count"], h["edge_count"], h["name_count"], h["place_count"]), (7, 6, 4, 4))
        self.assertEqual(h["body_offset"], 56 + 1 + len("Synthetic"))
        self.assertEqual(len(self.data), h["body_offset"] + 7 * 8 + 6 * 13 + h["name_bytes"] + h["place_bytes"])

    def test_encoder_refuses_dangling_references(self):
        g = synthetic()
        g.edges.append((0, 99, 5, 4, 0))
        with self.assertRaises(prokmap.FormatError):
            prokmap.encode(g)
        g = synthetic()
        g.edges.append((0, 1, 5, 4, 42))
        with self.assertRaises(prokmap.FormatError):
            prokmap.encode(g)

    # ================= the validator =================

    def write_pack(self, d, data, city="synthetic"):
        p = os.path.join(d, city + ".prokmap")
        with open(p, "wb") as f:
            f.write(data)
        m = dict(city=city, version=1_790_000_000, generated_at=1_790_000_000_000, bytes=len(data),
                 sha256=hashlib.sha256(data).hexdigest(), attribution=build_map_pack.ATTRIBUTION,
                 counts=dict(nodes=7, edges=6, places=4))
        with open(os.path.join(d, city + ".manifest.json"), "w", encoding="utf-8") as f:
            json.dump(m, f)
        return p, m

    def test_validator_accepts_the_pack_and_catches_a_flipped_byte(self):
        with tempfile.TemporaryDirectory() as d:
            p, _ = self.write_pack(d, self.data)
            h = mappack.validate(p)
            self.assertEqual(h["node_count"], 7)
            self.assertEqual(h["manifest"]["attribution"], "© OpenStreetMap contributors, ODbL 1.0")

            # one bit in the node table: parses fine, sha256 disagrees
            bad = bytearray(self.data)
            bad[60] ^= 0x01
            with open(p, "wb") as f:
                f.write(bad)
            with self.assertRaises(ValueError) as cm:
                mappack.validate(p)
            self.assertIn("sha256", str(cm.exception))

            # the magic
            bad = bytearray(self.data)
            bad[0] ^= 0xFF
            with open(p, "wb") as f:
                f.write(bad)
            with self.assertRaises(ValueError) as cm:
                mappack.validate(p)
            self.assertIn("magic", str(cm.exception))

            # a truncated download
            with open(p, "wb") as f:
                f.write(self.data[:-3])
            with self.assertRaises(ValueError) as cm:
                mappack.validate(p)
            self.assertIn("size mismatch", str(cm.exception))

            # an edge pointing past the node table, with the header counts still consistent
            bad = bytearray(self.data)
            off = prokmap.decode_header(self.data)["body_offset"] + 7 * 8
            bad[off:off + 4] = (99).to_bytes(4, "little")
            with open(p, "wb") as f:
                f.write(bad)
            with self.assertRaises(ValueError) as cm:
                mappack.validate(p)
            self.assertIn("beyond the table", str(cm.exception))

    def test_signature_is_checked_when_a_signer_is_known(self):
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import ec
        with tempfile.TemporaryDirectory() as d:
            p, m = self.write_pack(d, self.data)
            key = ec.generate_private_key(ec.SECP256R1())
            key_path = os.path.join(d, "k.pem")
            with open(key_path, "wb") as f:
                f.write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                          serialization.NoEncryption()))
            build_map_pack.sign_manifest(m, key_path)
            mp = os.path.join(d, "synthetic.manifest.json")
            with open(mp, "w") as f:
                json.dump(m, f)
            mappack.validate(p, mp, m["signer"])
            other = ec.generate_private_key(ec.SECP256R1()).public_key().public_numbers()
            other_hex = (other.x.to_bytes(32, "big") + other.y.to_bytes(32, "big")).hex()
            with self.assertRaises(ValueError):
                mappack.validate(p, mp, other_hex)
            m["signature"] = "00" * 64
            with open(mp, "w") as f:
                json.dump(m, f)
            with self.assertRaises(ValueError):
                mappack.validate(p, mp, m["signer"])

    def test_brain_helper_serves_only_a_complete_pack(self):
        with tempfile.TemporaryDirectory() as d:
            packs = mappack.MapPacks(d)
            self.assertEqual(packs.cities(), [])
            self.assertIsNone(packs.manifest("synthetic"))
            p, m = self.write_pack(d, self.data)
            self.assertEqual(packs.cities(), ["synthetic"])
            self.assertEqual(packs.manifest("synthetic")["sha256"], m["sha256"])
            self.assertEqual(packs.open("synthetic"), (p, len(self.data)))
            # not a city name: never a path
            self.assertIsNone(packs.manifest("../brain"))
            self.assertIsNone(packs.open("Synthetic"))
            # a pack whose size disagrees with its manifest is a copy in progress
            with open(p, "ab") as f:
                f.write(b"x")
            self.assertIsNone(packs.open("synthetic"))

    # ================= the router =================

    def test_route_takes_the_short_way_and_charges_for_the_stairs(self):
        g = prokmap.decode(self.data)
        r = prokmap.route(g, LAT0 / M, LON0 / M, (LAT0 + 1000) / M, (LON0 + 2000) / M)
        self.assertIsNotNone(r)
        self.assertEqual(r["node_path"], [0, 1, 3, 4])
        edge = {(min(a, b), max(a, b)): (l, c) for a, b, l, c, _n in g.edges}
        expect = edge[(0, 1)][0] + edge[(1, 3)][0] + edge[(3, 4)][0]
        self.assertAlmostEqual(r["distance_m"], expect, places=6)     # endpoints ON nodes: no snap legs
        eta = (edge[(0, 1)][0] + edge[(3, 4)][0]) / 1.25 + edge[(1, 3)][0] / 1.25 * 1.1
        self.assertEqual(r["eta_s"], int(round(eta)))
        self.assertEqual(r["points"][0], (LAT0 / M, LON0 / M))
        self.assertEqual(len(r["points"]), 6)

    def test_route_snaps_and_adds_the_snap_legs(self):
        g = prokmap.decode(self.data)
        # 20 m south of node 0
        r = prokmap.route(g, (LAT0 - 180) / M, LON0 / M, (LAT0 + 1000) / M, (LON0 + 2000) / M)
        self.assertEqual(r["node_path"], [0, 1, 3, 4])
        snap = prokmap.haversine_m((LAT0 - 180) / M, LON0 / M, LAT0 / M, LON0 / M)
        self.assertTrue(19 < snap < 21)
        edge = {(min(a, b), max(a, b)): l for a, b, l, _c, _n in g.edges}
        self.assertAlmostEqual(r["distance_m"], edge[(0, 1)] + edge[(1, 3)] + edge[(3, 4)] + snap, places=6)

    def test_no_route_beyond_300_m_or_to_an_island(self):
        g = prokmap.decode(self.data)
        # 400 m south of everything
        self.assertIsNone(prokmap.route(g, (LAT0 - 3600) / M, LON0 / M, (LAT0 + 1000) / M, (LON0 + 2000) / M))
        self.assertIsNone(prokmap.route(g, LAT0 / M, LON0 / M, (LAT0 + 1000) / M, (LON0 + 5400) / M))
        # both ends near nodes, but no path between the island and the town
        self.assertIsNone(prokmap.route(g, LAT0 / M, LON0 / M, (LAT0 + 50_000) / M, (LON0 + 50_000) / M))

    # ================= the builder's own joins (no PBF needed) =================

    def test_builder_shares_the_junction_node_and_keeps_merged_lengths(self):
        # two ways crossing at OSM node 20; a third dead-end way with a bend
        pts = lambda *ids: [(i, (LAT0 + 100 * i) / M, (LON0 + 137 * i) / M) for i in ids]
        ways = [
            (prokmap.CLASSES["residential"], "Avenue A", pts(10, 20, 30)),
            (prokmap.CLASSES["footway"], "", pts(40, 20, 50)),
            (prokmap.CLASSES["track"], "", [(60, LAT0 / M, LON0 / M), (61, (LAT0 + 900) / M, LON0 / M),
                                            (62, (LAT0 + 900) / M, (LON0 + 900) / M)]),
        ]
        g, stats, dropped = build_map_pack.build_graph(ways, [], [], "T", 1, (-5, 15, -4, 16), 10 ** 9, (-5, 15, -4, 16), lambda s: None)
        self.assertEqual(stats["nodes"], 8)        # 10 20 30 40 50 60 61 62: node 20 once
        self.assertEqual(stats["edges"], 6)
        self.assertEqual(dropped, {})
        self.assertEqual(g.names, ["", "Avenue A"])
        full = sum(l for _a, _b, l, c, _n in g.edges if c == prokmap.CLASSES["track"])

        # over budget: stage 1 drops nothing here (the track is inside the core), stage 2
        # merges the bend away but keeps its walked length
        g2, stats2, dropped2 = build_map_pack.build_graph(ways, [], [], "T", 1, (-5, 15, -4, 16), 10, (-5, 15, -4, 16), lambda s: None)
        self.assertEqual(stats2["nodes"], 7)
        self.assertEqual(dropped2["shape_nodes_merged"], 1)
        merged = [l for _a, _b, l, c, _n in g2.edges if c == prokmap.CLASSES["track"]]
        self.assertEqual(len(merged), 1)
        self.assertEqual(merged[0], full)

    def test_walk_class_rules(self):
        wc = build_map_pack.walk_class
        self.assertEqual(wc({"highway": "residential"}), prokmap.CLASSES["residential"])
        self.assertEqual(wc({"highway": "primary_link"}), prokmap.CLASSES["primary"])
        self.assertIsNone(wc({"highway": "motorway"}))
        self.assertIsNone(wc({"highway": "trunk"}))
        self.assertIsNone(wc({"highway": "residential", "foot": "no"}))
        self.assertIsNone(wc({"highway": "service", "access": "private"}))
        self.assertEqual(wc({"highway": "service", "access": "private", "foot": "yes"}), prokmap.CLASSES["service"])
        self.assertIsNone(wc({"highway": "cycleway"}))
        self.assertEqual(wc({"highway": "cycleway", "foot": "yes"}), prokmap.CLASSES["cycleway"])
        self.assertIsNone(wc({"building": "yes"}))

    # ================= the shared fixture for the Kotlin reader =================

    def test_write_the_cross_language_fixture(self):
        root = repo_root()
        if root is None:
            self.skipTest("not inside the repository")
        res = os.path.join(root, "app", "src", "test", "resources")
        os.makedirs(res, exist_ok=True)
        with open(os.path.join(res, "synthetic.prokmap"), "wb") as f:
            f.write(self.data)
        g = prokmap.decode(self.data)
        a = (LAT0 - 180) / M, LON0 / M
        b = (LAT0 + 1000) / M, (LON0 + 2000) / M
        r = prokmap.route(g, a[0], a[1], b[0], b[1])
        expected = dict(
            sha256=hashlib.sha256(self.data).hexdigest(), bytes=len(self.data),
            city="Synthetic", generated_at=1_790_000_000_000, nodes=7, edges=6, names=4, places=4,
            route=dict(from_lat=a[0], from_lon=a[1], to_lat=b[0], to_lon=b[1],
                       node_path=r["node_path"], distance_m=r["distance_m"], eta_s=r["eta_s"]),
            no_route=dict(from_lat=(LAT0 - 3600) / M, from_lon=LON0 / M, to_lat=b[0], to_lon=b[1]),
            island=dict(from_lat=a[0], from_lon=a[1], to_lat=(LAT0 + 50_000) / M, to_lon=(LON0 + 50_000) / M),
            search=dict(query="ecole general", expect="École Général Leclerc"),
            neighbourhood=dict(lat=(LAT0 + 1100) / M, lon=(LON0 + 2000) / M, expect="Poto-Poto"),
        )
        with open(os.path.join(res, "synthetic.expected.json"), "w", encoding="utf-8") as f:
            json.dump(expected, f, indent=2, ensure_ascii=False)
            f.write("\n")


if __name__ == "__main__":
    unittest.main()
