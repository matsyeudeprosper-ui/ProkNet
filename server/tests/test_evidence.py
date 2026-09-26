"""v0.15.1: no debt enters the server unless the server can verify the evidence itself.

The rule under test: a phone cannot create an obligation by asserting one. It must
present the signed contract and the signed closing checkpoint, and the server re-derives
every figure the money depends on. Anything it cannot verify is refused rather than
recorded with a warning.
"""
import struct
import unittest

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec

from brain import evidence, settlement, signed_request


def keypair():
    return ec.generate_private_key(ec.SECP256R1())


def pub_hex(priv) -> str:
    n = priv.public_key().public_numbers()
    return (n.x.to_bytes(32, "big") + n.y.to_bytes(32, "big")).hex()


def sign(priv, data: bytes) -> str:
    return priv.sign(data, ec.ECDSA(hashes.SHA256())).hex()


def node_id(priv) -> str:
    import hashlib
    return hashlib.sha256(bytes.fromhex(pub_hex(priv))).hexdigest()[:32]


MB = 1_000_000   # v0.18.1: the phones' MB; evidence.MB is the same number now


def contract_bytes(session: bytes, buyer: bytes, seller: bytes, rate=300, budget=5_000, fee=5, ts=1_700_000_000_000):
    ceiling = (budget * MB) // rate if rate > 0 else 0
    max_mb = min(100_000, (ceiling + MB - 1) // MB)
    price_cfa = (rate + 99) // 100
    return (struct.pack(">B", 2) + session + buyer + seller +
            struct.pack(">IIIBq", price_cfa, 0, max_mb, fee, ts) +
            struct.pack(">IqqIBB", rate, budget, ceiling, 0, 1, 1))


def checkpoint_bytes(session: bytes, seq: int, up: int, down: int, cost: int, ts=1_700_000_000_000, final=True):
    return struct.pack(">8sIqqqqB", session, seq, up, down, cost, ts, 1 if final else 0)


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.now = 1_700_000_000_000
        self.ttl = 86_400_000
        self.buyer = keypair()
        self.seller = keypair()
        self.session = bytes([7]) * 8
        self.contract = contract_bytes(self.session, bytes.fromhex(node_id(self.buyer)), bytes.fromhex(node_id(self.seller)))
        c = evidence.Contract(self.contract)
        self.used = 3 * MB
        self.cost = c.cost_for(self.used)
        self.checkpoint = checkpoint_bytes(self.session, 1, self.used // 2, self.used - self.used // 2, self.cost)

    def ev(self, submitter=None, **over):
        submitter = submitter or self.buyer
        base = {
            "contract": self.contract.hex(),
            "buyer_contract_sig": sign(self.buyer, evidence.CONTRACT_DOMAIN + self.contract),
            "seller_contract_sig": sign(self.seller, evidence.CONTRACT_DOMAIN + self.contract),
            "checkpoint": self.checkpoint.hex(),
            "seller_checkpoint_sig": sign(self.seller, evidence.CHECKPOINT_DOMAIN + self.checkpoint),
            "buyer_checkpoint_sig": sign(self.buyer, evidence.CHECKPOINT_DOMAIN + self.checkpoint),
            "buyer_pub": pub_hex(self.buyer),
            "seller_pub": pub_hex(self.seller),
            "submitter_pub": pub_hex(submitter),
        }
        base.update(over)
        return base

    def refused(self, ev, contains):
        with self.assertRaises(evidence.EvidenceError) as cm:
            evidence.verify(ev, self.now, self.ttl)
        self.assertIn(contains, str(cm.exception))

    # ---- the happy path --------------------------------------------------------------

    def test_the_server_derives_the_money_itself(self):
        out = evidence.verify(self.ev(), self.now, self.ttl)
        self.assertEqual(self.cost, out["gross"])
        self.assertTrue(out["gross"] > 0)
        gross, fee, net = evidence.split(self.cost, 5)
        self.assertEqual(fee, out["prok_fee"])
        self.assertEqual(net, out["seller_net"])
        self.assertEqual(out["gross"], out["seller_net"] + out["prok_fee"])
        self.assertEqual("buyer", out["actor"])
        self.assertEqual(node_id(self.buyer), out["buyer_id"])
        # the id is derived here, not accepted
        self.assertEqual(32, len(out["settlement_id"]))
        # the seller submitting the same evidence derives exactly the same thing
        other = evidence.verify(self.ev(submitter=self.seller), self.now + 5_000, self.ttl)
        self.assertEqual(out["settlement_id"], other["settlement_id"])
        self.assertEqual(out["gross"], other["gross"])
        self.assertEqual("seller", other["actor"])

    def test_the_cost_formula_matches_the_phones(self):
        c = evidence.Contract(self.contract)
        self.assertEqual(2, c.version)
        self.assertTrue(c.budget_session)
        self.assertEqual(300, c.rate_centimes_per_mb)
        self.assertEqual(5_000, c.buyer_budget_centimes)
        # half up, then clamped at both ceilings, exactly as Contract.costFor does it
        self.assertEqual((3 * MB * 300 + MB // 2) // MB, c.cost_for(3 * MB))
        self.assertEqual(5_000, c.cost_for(1 << 40))
        self.assertEqual(0, c.cost_for(0))
        self.assertEqual(0, c.cost_for(-5))

    # ---- forged or broken evidence ---------------------------------------------------

    def test_an_invented_session_is_refused(self):
        # a phone asserting an amount with no evidence at all
        self.refused({"settlement_id": "0" * 32, "gross": 500_000}, "missing")

    def test_a_tampered_amount_is_refused(self):
        # the classic attack: keep the real signatures, claim a bigger number
        self.refused(self.ev(gross=500_000), "claimed amount does not match")
        self.refused(self.ev(settlement_id="f" * 32), "claimed settlement id does not match")

    def test_every_signature_is_checked(self):
        other = keypair()
        self.refused(self.ev(buyer_contract_sig=sign(other, evidence.CONTRACT_DOMAIN + self.contract)),
                     "buyer contract signature is invalid")
        self.refused(self.ev(seller_contract_sig=sign(other, evidence.CONTRACT_DOMAIN + self.contract)),
                     "seller contract signature is invalid")
        self.refused(self.ev(seller_checkpoint_sig=sign(other, evidence.CHECKPOINT_DOMAIN + self.checkpoint)),
                     "seller checkpoint signature is invalid")
        self.refused(self.ev(buyer_checkpoint_sig=sign(other, evidence.CHECKPOINT_DOMAIN + self.checkpoint)),
                     "buyer countersignature is invalid")

    def test_a_signature_over_different_bytes_does_not_transfer(self):
        # sign a cheaper checkpoint, then present an expensive one
        cheap = checkpoint_bytes(self.session, 1, 1000, 1000, evidence.Contract(self.contract).cost_for(2000))
        self.refused(self.ev(seller_checkpoint_sig=sign(self.seller, evidence.CHECKPOINT_DOMAIN + cheap)),
                     "seller checkpoint signature is invalid")

    def test_the_keys_must_be_the_parties_named_in_the_contract(self):
        other = keypair()
        self.refused(self.ev(buyer_pub=pub_hex(other)), "buyer key does not match")
        self.refused(self.ev(seller_pub=pub_hex(other)), "seller key does not match")

    def test_a_checkpoint_from_another_session_cannot_be_borrowed(self):
        foreign = checkpoint_bytes(bytes([9]) * 8, 1, self.used // 2, self.used - self.used // 2, self.cost)
        self.refused(self.ev(checkpoint=foreign.hex(),
                             seller_checkpoint_sig=sign(self.seller, evidence.CHECKPOINT_DOMAIN + foreign),
                             buyer_checkpoint_sig=sign(self.buyer, evidence.CHECKPOINT_DOMAIN + foreign)),
                     "belongs to a different session")

    def test_a_non_final_checkpoint_does_not_settle_anything(self):
        mid = checkpoint_bytes(self.session, 1, self.used // 2, self.used - self.used // 2, self.cost, final=False)
        self.refused(self.ev(checkpoint=mid.hex(),
                             seller_checkpoint_sig=sign(self.seller, evidence.CHECKPOINT_DOMAIN + mid),
                             buyer_checkpoint_sig=sign(self.buyer, evidence.CHECKPOINT_DOMAIN + mid)),
                     "not the closing one")

    def test_a_cost_that_the_terms_do_not_give_is_refused(self):
        lying = checkpoint_bytes(self.session, 1, self.used // 2, self.used - self.used // 2, self.cost * 3)
        self.refused(self.ev(checkpoint=lying.hex(),
                             seller_checkpoint_sig=sign(self.seller, evidence.CHECKPOINT_DOMAIN + lying),
                             buyer_checkpoint_sig=sign(self.buyer, evidence.CHECKPOINT_DOMAIN + lying)),
                     "does not match the agreed terms")

    def test_usage_past_the_signed_ceiling_is_refused(self):
        c = evidence.Contract(self.contract)
        over = c.max_billable_bytes * 3
        big = checkpoint_bytes(self.session, 1, over // 2, over - over // 2, c.cost_for(over))
        self.refused(self.ev(checkpoint=big.hex(),
                             seller_checkpoint_sig=sign(self.seller, evidence.CHECKPOINT_DOMAIN + big),
                             buyer_checkpoint_sig=sign(self.buyer, evidence.CHECKPOINT_DOMAIN + big)),
                     "exceeds the signed byte ceiling")

    def test_a_stranger_cannot_submit_somebody_elses_session(self):
        self.refused(self.ev(submitter=keypair()), "neither the buyer nor the seller")

    def test_free_and_legacy_sessions_create_no_debt(self):
        free = contract_bytes(self.session, bytes.fromhex(node_id(self.buyer)), bytes.fromhex(node_id(self.seller)), rate=0, budget=0)
        # a rate of 0 makes the ceiling 0 too, which the contract rules allow for free
        self.refused(self.ev(contract=free.hex(),
                             buyer_contract_sig=sign(self.buyer, evidence.CONTRACT_DOMAIN + free),
                             seller_contract_sig=sign(self.seller, evidence.CONTRACT_DOMAIN + free)),
                     "free session")
        v1 = struct.pack(">B", 1) + self.session + bytes.fromhex(node_id(self.buyer)) + \
            bytes.fromhex(node_id(self.seller)) + struct.pack(">IIIBq", 5, 0, 20, 5, 1_700_000_000_000)
        self.refused(self.ev(contract=v1.hex(),
                             buyer_contract_sig=sign(self.buyer, evidence.CONTRACT_DOMAIN + v1),
                             seller_contract_sig=sign(self.seller, evidence.CONTRACT_DOMAIN + v1)),
                     "not a v2 budget session")

    # ---- what the store then records --------------------------------------------------

    def test_the_stored_obligation_is_the_derived_one(self):
        s = settlement.Settlements(":memory:")
        out = s.record(evidence.verify(self.ev(), self.now, self.ttl), self.now)
        self.assertTrue(out["ok"])
        self.assertEqual(self.cost, out["gross"])
        self.assertFalse(out["agreed"])
        # the seller corroborates from the same signed bytes
        again = s.record(evidence.verify(self.ev(submitter=self.seller), self.now + 1_000, self.ttl), self.now + 1_000)
        self.assertTrue(again["agreed"])
        self.assertEqual(1, s.db.execute("SELECT COUNT(*) c FROM settlements").fetchone()["c"])


class SignedRequestTest(unittest.TestCase):
    def setUp(self):
        self.now = 1_700_000_000_000
        self.priv = keypair()
        self.nonces = signed_request.Nonces()
        self.body = b'{"hello":"world"}'

    METHOD = "POST"
    TARGET = "/v1/settlements"

    def headers(self, **over):
        digest = signed_request.body_hash(self.body)
        ts = over.pop("ts", self.now)
        nonce = over.pop("nonce", "nonce-0001")
        method = over.pop("method", self.METHOD)
        target = over.pop("target", self.TARGET)
        h = {
            "X-Prok-Identity": pub_hex(self.priv),
            "X-Prok-Timestamp": str(ts),
            "X-Prok-Nonce": nonce,
            "X-Prok-Signature": sign(self.priv, signed_request.signing_line(
                ts, nonce, digest, method, target)),
        }
        h.update(over)
        return h

    def check(self, h, body=None, now=None, **kw):
        return signed_request.verify(h, self.body if body is None else body, self.nonces,
                                     self.now if now is None else now,
                                     method=kw.pop("method", self.METHOD),
                                     path=kw.pop("path", self.TARGET), **kw)

    def test_a_properly_signed_request_is_accepted_once(self):
        self.assertEqual(node_id(self.priv), self.check(self.headers()))

    def test_a_replay_is_refused(self):
        h = self.headers()
        self.check(h)
        with self.assertRaises(signed_request.AuthError) as cm:
            self.check(h, now=self.now + 1_000)
        self.assertIn("already been used", str(cm.exception))

    def test_an_altered_body_breaks_the_signature(self):
        with self.assertRaises(signed_request.AuthError) as cm:
            self.check(self.headers(), body=b'{"hello":"tampered"}')
        self.assertIn("must cover", str(cm.exception))

    def test_an_old_or_future_timestamp_is_refused(self):
        for ts in (self.now - 10 * 60 * 1000, self.now + 10 * 60 * 1000):
            with self.assertRaises(signed_request.AuthError) as cm:
                self.check(self.headers(ts=ts))
            self.assertIn("window", str(cm.exception))

    def test_an_unsigned_request_is_refused(self):
        with self.assertRaises(signed_request.AuthError):
            self.check({})
        with self.assertRaises(signed_request.AuthError):
            self.check(self.headers(**{"X-Prok-Signature": ""}))

    # ---- v0.16.3: the target is inside the signature ------------------------------------

    def test_a_signature_for_one_target_does_not_verify_at_another(self):
        h = self.headers()
        with self.assertRaises(signed_request.AuthError):
            self.check(h, path="/v1/pay/receipt")
        with self.assertRaises(signed_request.AuthError):
            self.check(h, method="GET")

    def test_a_legacy_body_only_signature_is_refused_on_a_money_route(self):
        digest = signed_request.body_hash(self.body)
        legacy = {
            "X-Prok-Identity": pub_hex(self.priv),
            "X-Prok-Timestamp": str(self.now),
            "X-Prok-Nonce": "nonce-legacy-1",
            "X-Prok-Signature": sign(self.priv, signed_request.signing_line(
                self.now, "nonce-legacy-1", digest)),
        }
        with self.assertRaises(signed_request.AuthError) as cm:
            self.check(legacy)
        self.assertIn("must cover", str(cm.exception))
        # and it is still a valid signature - it is refused for WHAT it covers, not
        # because it is malformed
        self.assertEqual(node_id(self.priv),
                         signed_request.verify(legacy, self.body, self.nonces, self.now,
                                               require_bound=False))

    def test_asking_for_a_bound_check_without_a_target_is_a_programming_error(self):
        with self.assertRaises(ValueError):
            signed_request.verify(self.headers(), self.body, self.nonces, self.now)

    def test_the_canonical_target_sorts_the_query_and_decodes_nothing(self):
        c = signed_request.canonical_target
        self.assertEqual("/v1/pay/reply", c("/v1/pay/reply"))
        self.assertEqual("/v1/pay/reply", c("/v1/pay/reply?"))
        self.assertEqual("/v1/pay/destinations?seller=abc", c("/v1/pay/destinations?seller=abc"))
        self.assertEqual("/v1/p?a=1&b=2", c("/v1/p?b=2&a=1"))
        self.assertEqual("/v1/p?a=1&b=2", c("/v1/p?a=1&b=2"))
        # different resource, different signature
        self.assertNotEqual(c("/v1/pay/reply?payment=A"), c("/v1/pay/reply?payment=B"))
        # nothing is decoded: %2F must not quietly become /
        self.assertEqual("/v1/p?x=%2Fy", c("/v1/p?x=%2Fy"))

    def test_a_failed_signature_does_not_burn_the_nonce(self):
        # otherwise an attacker could lock out a legitimate request by guessing its nonce
        bad = self.headers()
        bad["X-Prok-Signature"] = sign(keypair(), signed_request.signing_line(
            self.now, "nonce-0001", signed_request.body_hash(self.body), self.METHOD, self.TARGET))
        with self.assertRaises(signed_request.AuthError):
            self.check(bad)
        self.assertEqual(node_id(self.priv), self.check(self.headers()))

    def test_nonces_expire_but_not_before_the_skew_window_closes(self):
        self.check(self.headers())
        self.assertEqual(1, len(self.nonces.seen))
        self.nonces.sweep(self.now + signed_request.MAX_SKEW_MS)
        self.assertEqual(1, len(self.nonces.seen), "a nonce must outlive the window its timestamp is valid in")
        self.nonces.sweep(self.now + signed_request.NONCE_TTL_MS + 1)
        self.assertEqual(0, len(self.nonces.seen))


if __name__ == "__main__":
    unittest.main()
