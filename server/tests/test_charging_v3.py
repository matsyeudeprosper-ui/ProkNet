"""v0.18.0: contract version 3 on the Brain - one charging rule, the relay in the evidence."""
import json
import os
import struct
import unittest

from brain import evidence, ledger
from tests.test_evidence import checkpoint_bytes, keypair, node_id, pub_hex, sign

MB = evidence.MB
T0 = 1_700_000_000_000


def contract_v3(session: bytes, buyer: bytes, seller: bytes, rate=300, budget=5_000, fee=5, ts=T0, relay: bytes = bytes(16)):
    ceiling = (budget * MB) // rate if rate > 0 else 0
    max_mb = min(100_000, (ceiling + MB - 1) // MB)
    price_cfa = (rate + 99) // 100
    return (struct.pack(">B", 3) + session + buyer + seller +
            struct.pack(">IIIBq", price_cfa, 0, max_mb, fee, ts) +
            struct.pack(">IqqIBB", rate, budget, ceiling, 0, 1, 1) + relay)


class ChargingFixtureTest(unittest.TestCase):

    def test_the_fixture_is_what_this_code_computes_now(self):
        path = os.path.join(os.path.dirname(__file__), "fixtures", "charging_v3.json")
        with open(path, encoding="utf-8") as f:
            data = json.load(f)
        self.assertEqual(MB, data["mb"])
        self.assertGreaterEqual(len(data["cases"]), 10)
        from tests.write_charging_fixture import contract
        for c in data["cases"]:
            got = contract(c["version"], c["rate"], c["budget"], c["ceiling"]).cost_for_usage(c["up"], c["down"])
            self.assertEqual(c["cost"], got, c["label"])

    def test_the_rule_in_words(self):
        from tests.write_charging_fixture import contract
        c3 = contract(3, 300, 5_000, 0)
        c2 = contract(2, 300, 5_000, 0)
        self.assertEqual(0, c3.cost_for_usage(2 * MB, 0), "nothing down, nothing owed")
        self.assertGreater(c3.cost_for_usage(2 * MB, 1), 0, "one byte down and everything counts")
        self.assertEqual(c3.cost_for(2 * MB + 1), c3.cost_for_usage(2 * MB, 1))
        self.assertGreater(c2.cost_for_usage(2 * MB, 0), 0, "v2 unchanged")


class EvidenceV3Test(unittest.TestCase):

    def setUp(self):
        self.buyer = keypair(); self.seller = keypair(); self.relay = keypair()
        self.session = bytes([9]) * 8
        self.b = bytes.fromhex(node_id(self.buyer)); self.s = bytes.fromhex(node_id(self.seller)); self.r = bytes.fromhex(node_id(self.relay))

    def body(self, contract: bytes, up: int, down: int, cost=None):
        c = evidence.Contract(contract)
        cp = checkpoint_bytes(self.session, 1, up, down, c.cost_for_usage(up, down) if cost is None else cost)
        return {"contract": contract.hex(),
                "buyer_contract_sig": sign(self.buyer, evidence.CONTRACT_DOMAIN + contract),
                "seller_contract_sig": sign(self.seller, evidence.CONTRACT_DOMAIN + contract),
                "checkpoint": cp.hex(),
                "seller_checkpoint_sig": sign(self.seller, evidence.CHECKPOINT_DOMAIN + cp),
                "buyer_checkpoint_sig": sign(self.buyer, evidence.CHECKPOINT_DOMAIN + cp),
                "buyer_pub": pub_hex(self.buyer), "seller_pub": pub_hex(self.seller), "submitter_pub": pub_hex(self.seller)}

    def test_a_direct_v3_session_verifies_and_names_no_relay(self):
        d = evidence.verify(self.body(contract_v3(self.session, self.b, self.s), MB, 2 * MB), T0, 86_400_000)
        self.assertEqual(3, d["contract_version"])
        self.assertEqual("", d["relay_id"])
        self.assertEqual(900, d["gross"])

    def test_a_relayed_v3_session_names_the_relay_and_the_ledger_pays_it(self):
        d = evidence.verify(self.body(contract_v3(self.session, self.b, self.s, relay=self.r), MB, 2 * MB), T0, 86_400_000)
        self.assertEqual(node_id(self.relay), d["relay_id"])
        import sqlite3
        L = ledger.Ledger(sqlite3.connect(":memory:"), treasury_ids=("t",), test_ids=(d["buyer_id"],))
        L.test_credit("t", d["buyer_id"], 100_000, T0)
        out = L.post_settlement(d, T0)
        self.assertEqual(d["gross"] * ledger.RELAY_SHARE_PCT // 100, out["relay"])
        self.assertEqual(out["relay"], L.balance("earned:" + node_id(self.relay)))
        self.assertEqual(d["seller_net"] - out["relay"], L.balance("earned:" + d["seller_id"]))
        self.assertEqual(0, L.net_of_all_accounts())

    def test_nothing_down_is_refused_as_owing_nothing(self):
        with self.assertRaises(evidence.EvidenceError) as cm:
            evidence.verify(self.body(contract_v3(self.session, self.b, self.s), 3 * MB, 0), T0, 86_400_000)
        self.assertIn("owes nothing", str(cm.exception))

    def test_a_checkpoint_priced_by_the_old_rule_is_refused(self):
        c = evidence.Contract(contract_v3(self.session, self.b, self.s))
        with self.assertRaises(evidence.EvidenceError) as cm:
            evidence.verify(self.body(contract_v3(self.session, self.b, self.s), 3 * MB, 0, cost=c.cost_for(3 * MB)), T0, 86_400_000)
        self.assertIn("does not match", str(cm.exception))

    def test_a_relay_that_is_a_party_is_not_a_contract(self):
        with self.assertRaises(evidence.EvidenceError):
            evidence.verify(self.body(contract_v3(self.session, self.b, self.s, relay=self.b), MB, MB), T0, 86_400_000)
        with self.assertRaises(evidence.EvidenceError):
            evidence.verify(self.body(contract_v3(self.session, self.b, self.s, relay=self.s), MB, MB), T0, 86_400_000)

    def test_a_v3_body_at_the_wrong_length_is_refused(self):
        raw = contract_v3(self.session, self.b, self.s)
        with self.assertRaises(evidence.EvidenceError):
            evidence.Contract(raw[:-1])
        with self.assertRaises(evidence.EvidenceError):
            evidence.Contract(bytes([2]) + raw[1:])


if __name__ == "__main__":
    unittest.main()
