"""v0.16.2: the Brain carrying signed payment objects between phones that never meet.

The case these cover: a buyer uses somebody's Internet, walks away, and only decides to
pay an hour later from the other side of town. Somebody has to hold the signed objects in
between, and that somebody must not be able to abuse the position.

The property under test throughout: **the server is a carrier and a validator, never an
author.** It stores what phones signed, refuses what does not verify, and can never move
money, redirect a payment or invent a debt.
"""
import unittest

from brain import paybox, ruleconfig, settlement, signed_request
from tests.test_evidence import keypair, node_id, pub_hex, sign

MB = 1_000_000   # v0.18.1: the phones' MB; evidence.MB is the same number now
COOLING = 10 * 60 * 1000


def dest_line(seller, msisdn="066123456", version=1, created=1_700_000_000_000,
              rail="MTN_MOMO", fmt=2, sign_created=None):
    """A signed destination claim.

    `fmt=2` is what every build from 67 on produces: created_at is inside the signature.
    `fmt=1` is v0.16.0's, kept so the tests can prove a claim already on somebody's phone
    still verifies. `sign_created` signs a DIFFERENT timestamp from the one on the wire,
    which is the tampering v2 exists to stop.
    """
    number = paybox.normalize_msisdn(msisdn)
    if fmt == 2:
        data = "ProkNet-destination-claim-2|%s|%s|%s|%d|%d" % (
            node_id(seller), rail, number, version,
            created if sign_created is None else sign_created)
        prefix = "dest2"
    else:
        data = "ProkNet-destination-claim-1|%s|%s|%s|%d" % (
            node_id(seller), rail, number, version)
        prefix = "dest1"
    sig = sign(seller, data.encode("utf-8"))
    return "pay1.dest|%s|%s|%s|%s|%d|%d|%s" % (
        prefix, node_id(seller), rail, number, version, created, sig)


def exp_line(buyer, seller, amount=1_000, ids=("s1",), payment_id="p1",
             created=1_700_000_000_000, window=20 * 60 * 1000, dest=None, rail="MTN_MOMO"):
    dest = dest or paybox.destination_hash(rail, "066123456")
    expires = created + window
    data = "ProkNet-payment-expectation-1|1|%s|%s|%s|%s|%s|%d|%d|%d|%d|%s" % (
        payment_id, node_id(buyer), node_id(seller), rail, dest, amount,
        created, created, expires, ",".join(sorted(ids)))
    sig = sign(buyer, data.encode("utf-8"))
    return "pay1.exp|1|%s|%s|%s|%s|%s|%d|%d|%d|%d|%s|%s" % (
        payment_id, node_id(buyer), node_id(seller), rail, dest, amount,
        created, created, expires, ",".join(ids), sig)


def receipt_line(seller, buyer, amount=1_000, ids=("s1",), payment_id="p1",
                 observed=1_700_000_000_060, dest=None, rail="MTN_MOMO",
                 confidence="DEVICE_SMS_VERIFIED", evidence="hash1"):
    dest = dest or paybox.destination_hash(rail, "066123456")
    data = "ProkNet-device-receipt-1|%s|%s|%s|%s|%s|%d|%d|%d|%s|%s|%d|%s|%s" % (
        payment_id, node_id(seller), node_id(buyer), rail, dest, amount, amount, observed,
        "DIRECT_SMS", evidence, 1, confidence, ",".join(sorted(ids)))
    sig = sign(seller, data.encode("utf-8"))
    return "pay1.receipt|1|%s|%s|%s|%s|%s|%d|%d|%d|%s|%s|%s|%d|%s|%s|%s|%s" % (
        payment_id, node_id(seller), node_id(buyer), rail, dest, amount, amount, observed,
        "DIRECT_SMS", "com.android.mms", evidence, 1, confidence, ",".join(ids), "", sig)


class PayBoxTest(unittest.TestCase):
    def setUp(self):
        self.box = paybox.PayBox(":memory:")
        self.now = 1_700_000_000_000
        self.buyer = keypair()
        self.buyer2 = keypair()
        self.seller = keypair()
        self.stranger = keypair()

    def put_dest(self, **kw):
        return self.box.put_destination(dest_line(self.seller, **kw), pub_hex(self.seller),
                                        node_id(self.seller), self.now)

    # ---- destinations ---------------------------------------------------------------

    def test_a_seller_publishes_where_it_is_paid_and_a_buyer_can_read_it_back(self):
        out = self.put_dest()
        self.assertTrue(out["ok"])
        d = self.box.destination_for_buyer(node_id(self.seller), "MTN_MOMO", self.now, COOLING)
        self.assertIsNotNone(d)
        self.assertEqual(1, d["version"])
        # what comes back is the phone's own signed line, so the buyer verifies the SELLER
        self.assertTrue(d["line"].startswith("pay1.dest|"))
        paybox.Destination(d["line"]).verify(pub_hex(self.seller))

    def test_only_the_seller_may_publish_its_own_destination(self):
        # signed correctly, but submitted by somebody else
        with self.assertRaises(paybox.PayError):
            self.box.put_destination(dest_line(self.seller), pub_hex(self.seller),
                                     node_id(self.stranger), self.now)
        # or signed by somebody else entirely
        line = dest_line(self.stranger)
        with self.assertRaises(paybox.PayError):
            self.box.put_destination(line, pub_hex(self.seller), node_id(self.seller), self.now)

    def test_the_server_cannot_silently_rewrite_a_number(self):
        self.put_dest()
        d = self.box.destination_for_buyer(node_id(self.seller), "MTN_MOMO", self.now, COOLING)
        # a hostile server swaps the number in the stored line
        tampered = d["line"].replace("066123456", "066999999")
        with self.assertRaises(paybox.PayError):
            paybox.Destination(tampered).verify(pub_hex(self.seller))

    def test_a_replayed_older_version_is_refused_and_a_repeat_is_idempotent(self):
        self.put_dest(version=1)
        self.put_dest(version=2, msisdn="066999999")
        # re-posting the older claim we already hold is an honest retry, not an attack:
        # it is ignored, and what matters is that it does NOT become active again
        self.put_dest(version=1)
        active = self.box.destination_for_buyer(node_id(self.seller), "MTN_MOMO",
                                                self.now + COOLING + 1, COOLING)
        self.assertEqual(2, active["version"], "an old claim must never become active again")
        # an unseen lower version is a replay from somewhere else and is refused outright
        with self.assertRaises(paybox.PayError):
            self.box.put_destination(dest_line(self.seller, msisdn="066777777", version=1),
                                     pub_hex(self.seller), node_id(self.seller), self.now)
        again = self.put_dest(version=2, msisdn="066999999")
        self.assertTrue(again["duplicate"])
        # and a DIFFERENT claim at the same version is refused rather than overwriting
        with self.assertRaises(paybox.PayError):
            self.put_dest(version=2, msisdn="066000000")

    def test_during_cooling_the_old_number_stays_active(self):
        self.put_dest(version=1)
        box2 = self.box
        box2.put_destination(dest_line(self.seller, msisdn="066999999", version=2),
                             pub_hex(self.seller), node_id(self.seller), self.now + 1000)
        during = box2.destination_for_buyer(node_id(self.seller), "MTN_MOMO", self.now + 2000, COOLING)
        self.assertEqual(1, during["version"], "a payment in flight must still land somewhere valid")
        after = box2.destination_for_buyer(node_id(self.seller), "MTN_MOMO",
                                           self.now + 1000 + COOLING, COOLING)
        self.assertEqual(2, after["version"])

    # ---- expectations ---------------------------------------------------------------

    def put_exp(self, buyer=None, **kw):
        buyer = buyer or self.buyer
        return self.box.put_expectation(exp_line(buyer, self.seller, **kw), pub_hex(buyer),
                                        node_id(buyer), self.now, COOLING)

    def test_a_buyer_leaves_an_expectation_for_a_seller_that_is_not_there(self):
        self.put_dest()
        out = self.put_exp()
        self.assertTrue(out["ok"])
        pending = self.box.pending_expectations(node_id(self.seller), self.now)
        self.assertEqual(1, len(pending))
        # the seller gets the buyer's own signed line and checks it itself
        paybox.Expectation(pending[0]["line"]).verify(pub_hex(self.buyer))

    def test_an_expectation_must_name_the_destination_the_seller_actually_uses(self):
        self.put_dest()
        with self.assertRaises(paybox.PayError) as cm:
            self.put_exp(dest=paybox.destination_hash("MTN_MOMO", "066000000"))
        self.assertIn("not using", str(cm.exception))
        # and a seller with no destination cannot receive one at all
        fresh = paybox.PayBox(":memory:")
        with self.assertRaises(paybox.PayError):
            fresh.put_expectation(exp_line(self.buyer, self.seller), pub_hex(self.buyer),
                                  node_id(self.buyer), self.now, COOLING)

    def test_forged_expired_and_absurd_expectations_are_refused(self):
        self.put_dest()
        # signed by somebody who is not the buyer it names
        forged = exp_line(self.stranger, self.seller)
        with self.assertRaises(paybox.PayError):
            self.box.put_expectation(forged, pub_hex(self.buyer), node_id(self.buyer), self.now, COOLING)
        # already expired
        with self.assertRaises(paybox.PayError):
            self.put_exp(created=self.now - 60 * 60 * 1000)
        # asking the seller to watch for a week
        with self.assertRaises(paybox.PayError):
            self.put_exp(window=7 * 24 * 3600 * 1000)

    def test_the_amount_must_match_what_the_server_knows_is_owed(self):
        self.put_dest()
        with self.assertRaises(paybox.PayError) as cm:
            self.box.put_expectation(exp_line(self.buyer, self.seller, amount=9_900),
                                     pub_hex(self.buyer), node_id(self.buyer), self.now, COOLING,
                                     {"s1": 1_000})
        self.assertIn("does not match", str(cm.exception))
        # where the server knows nothing, it carries the object and lets the seller decide
        self.assertTrue(self.box.put_expectation(
            exp_line(self.buyer, self.seller, amount=1_000), pub_hex(self.buyer),
            node_id(self.buyer), self.now, COOLING, None)["ok"])

    def test_the_seller_remains_the_authority_on_same_amount_windows(self):
        self.put_dest()
        self.put_exp(payment_id="pA")
        self.put_exp(buyer=self.buyer2, payment_id="pB")
        # the Brain queues BOTH; it does not decide between them
        self.assertEqual(2, len(self.box.pending_expectations(node_id(self.seller), self.now)))
        # the seller answers, and only its own answers are accepted
        self.box.put_reply("pA", "ACCEPTED", node_id(self.seller), self.now)
        self.box.put_reply("pB", "BUSY_SAME_AMOUNT", node_id(self.seller), self.now)
        with self.assertRaises(paybox.PayError):
            self.box.put_reply("pA", "ACCEPTED", node_id(self.buyer), self.now)
        self.assertEqual("ACCEPTED", self.box.reply_for("pA", node_id(self.buyer))["reply"])
        self.assertEqual("BUSY_SAME_AMOUNT", self.box.reply_for("pB", node_id(self.buyer2))["reply"])
        # a stranger cannot read somebody else's payment state
        with self.assertRaises(paybox.PayError):
            self.box.reply_for("pA", node_id(self.stranger))
        # answered expectations leave the seller's inbox
        self.assertEqual(0, len(self.box.pending_expectations(node_id(self.seller), self.now)))

    # ---- receipts --------------------------------------------------------------------

    def put_receipt(self, **kw):
        return self.box.put_receipt(receipt_line(self.seller, self.buyer, **kw),
                                    pub_hex(self.seller), node_id(self.seller), self.now)

    def test_the_seller_leaves_a_receipt_and_the_buyer_collects_it_later(self):
        self.put_dest()
        self.put_exp()
        out = self.put_receipt()
        self.assertTrue(out["ok"])
        pending = self.box.pending_receipts(node_id(self.buyer))
        self.assertEqual(1, len(pending))
        # the buyer verifies the SELLER's signature, not the server's word
        paybox.Receipt(pending[0]["line"]).verify(pub_hex(self.seller))
        # and the window is closed, so the seller may take the next buyer at that amount
        self.assertTrue(self.box.reply_for("p1", node_id(self.buyer))["ended"])

    def test_a_buyer_cannot_forge_a_receipt_for_its_own_debt(self):
        self.put_dest()
        self.put_exp()
        forged = receipt_line(self.buyer, self.buyer)      # signed by the buyer
        with self.assertRaises(paybox.PayError):
            self.box.put_receipt(forged, pub_hex(self.seller), node_id(self.seller), self.now)
        # nor may somebody else submit the seller's receipt
        with self.assertRaises(paybox.PayError):
            self.box.put_receipt(receipt_line(self.seller, self.buyer), pub_hex(self.seller),
                                 node_id(self.stranger), self.now)

    def test_a_receipt_must_match_the_expectation_it_claims(self):
        self.put_dest()
        self.put_exp(amount=1_000, ids=("s1",))
        for kw in ({"amount": 5_000}, {"dest": paybox.destination_hash("MTN_MOMO", "066000000")},
                   {"ids": ("s1", "s2")}):
            with self.assertRaises(paybox.PayError):
                self.put_receipt(**kw)

    def test_the_server_may_never_store_an_operator_attestation(self):
        self.put_dest()
        self.put_exp()
        with self.assertRaises(paybox.PayError) as cm:
            self.put_receipt(confidence="OPERATOR_VERIFIED")
        self.assertIn("confidence", str(cm.exception))

    def test_receipt_delivery_is_idempotent_and_survives_a_long_absence(self):
        self.put_dest()
        self.put_exp()
        self.put_receipt()
        again = self.put_receipt()
        self.assertTrue(again["duplicate"])
        # downloaded twenty times, still one receipt
        for _ in range(20):
            self.assertEqual(1, len(self.box.pending_receipts(node_id(self.buyer))))
        # a buyer away for a week still finds it: sweeping does not touch unacknowledged money
        self.box.sweep(self.now + 7 * 24 * 3600 * 1000)
        self.assertEqual(1, len(self.box.pending_receipts(node_id(self.buyer))))
        # once acknowledged it stops being offered, and only the buyer may acknowledge
        with self.assertRaises(paybox.PayError):
            self.box.ack_receipt("p1", node_id(self.stranger))
        self.box.ack_receipt("p1", node_id(self.buyer))
        self.assertEqual(0, len(self.box.pending_receipts(node_id(self.buyer))))

    # ---- device risk -------------------------------------------------------------------

    def test_a_reinstall_on_the_same_phone_inherits_the_old_debt(self):
        debts = {node_id(self.buyer): 1_000}
        def unresolved(i):
            return debts.get(i, 0)

        self.box.note_device("device-P", node_id(self.buyer), self.now)
        r = self.box.device_risk("device-P", node_id(self.buyer), unresolved)
        self.assertEqual(1_000, r["unresolvedCentimes"])
        self.assertTrue(r["hasUnresolvedDebt"])

        # the app is reinstalled and a brand new identity appears on the same phone
        self.box.note_device("device-P", node_id(self.buyer2), self.now + 1000)
        r2 = self.box.device_risk("device-P", node_id(self.buyer2), unresolved)
        self.assertTrue(r2["hasUnresolvedDebt"], "the debt follows the phone, not the identity")
        self.assertEqual(1_000, r2["unresolvedCentimes"])
        self.assertEqual(1, r2["priorIdentityCount"])

        # a different phone inherits nothing
        self.box.note_device("device-Q", node_id(self.buyer2), self.now + 2000)
        self.assertFalse(self.box.device_risk("device-Q", node_id(self.buyer2), unresolved)["hasUnresolvedDebt"])

        # and once the old identity pays, the device is clean again. Not punished for ever.
        debts.clear()
        self.assertFalse(self.box.device_risk("device-P", node_id(self.buyer2), unresolved)["hasUnresolvedDebt"])

    def test_no_hardware_identifier_is_needed_or_stored(self):
        # whatever the phone sends is an opaque hash; the server never asks for more
        self.box.note_device("a" * 64, node_id(self.buyer), self.now)
        rows = self.box.db.execute("SELECT * FROM device_identities").fetchall()
        self.assertEqual(1, len(rows))
        self.assertEqual("a" * 64, rows[0]["pseudonym"])
        # an absurd value is ignored rather than stored
        self.box.note_device("x" * 200, node_id(self.buyer), self.now)
        self.assertEqual(1, len(self.box.db.execute("SELECT * FROM device_identities").fetchall()))





class DestinationV2Test(unittest.TestCase):
    """v0.16.5: the timestamp that decides when a destination goes live is signed.

    v0.16.4 made `created_at` the field that decides when a new destination becomes
    active, on the phone and here - while it was still outside the signature. Anything
    carrying a claim could have moved the cooling window: ten minutes earlier and a buyer
    is sent to a number the seller is not watching yet; ten minutes later and the seller
    keeps being paid on a number it has abandoned.
    """

    def setUp(self):
        self.box = paybox.PayBox(":memory:")
        self.now = 1_700_000_000_000
        self.seller = keypair()
        self.buyer = keypair()

    def put(self, **kw):
        return self.box.put_destination(dest_line(self.seller, **kw), pub_hex(self.seller),
                                        node_id(self.seller), kw.pop("at", self.now))

    # ---- the new format ------------------------------------------------------------

    def test_a_v2_claim_signs_its_timestamp(self):
        d = paybox.Destination(dest_line(self.seller))
        self.assertTrue(d.time_is_signed)
        self.assertIn(b"ProkNet-destination-claim-2", d.sign_data())
        self.assertIn(str(d.created_at).encode(), d.sign_data())
        d.verify(pub_hex(self.seller))

    def test_moving_the_timestamp_forward_breaks_the_claim(self):
        # the seller signed 09:59 and the wire says 10:09: the cooling window has been
        # pushed ten minutes later, and the seller keeps being paid on an old number
        line = dest_line(self.seller, created=self.now + 600_000, sign_created=self.now)
        with self.assertRaises(paybox.PayError):
            paybox.Destination(line).verify(pub_hex(self.seller))

    def test_moving_the_timestamp_backward_breaks_the_claim(self):
        # the other direction: the new number goes live early, before the seller is
        # watching it
        line = dest_line(self.seller, created=self.now - 600_000, sign_created=self.now)
        with self.assertRaises(paybox.PayError):
            paybox.Destination(line).verify(pub_hex(self.seller))

    def test_the_server_refuses_a_tampered_claim_outright(self):
        line = dest_line(self.seller, created=self.now + 600_000, sign_created=self.now)
        with self.assertRaises(paybox.PayError):
            self.box.put_destination(line, pub_hex(self.seller), node_id(self.seller), self.now)
        self.assertEqual([], self.box.destinations_for_buyer(node_id(self.seller), self.now, COOLING))

    # ---- the old format, read-only ----------------------------------------------------

    def test_a_legacy_claim_still_verifies_and_is_still_usable(self):
        # a seller who has not changed their number since build 62 must not be asked to
        # re-enter it
        d = paybox.Destination(dest_line(self.seller, fmt=1))
        self.assertFalse(d.time_is_signed)
        self.assertIn(b"ProkNet-destination-claim-1", d.sign_data())
        d.verify(pub_hex(self.seller))
        self.assertTrue(self.put(fmt=1)["ok"])
        got = self.box.destinations_for_buyer(node_id(self.seller), self.now, COOLING)
        self.assertEqual(1, len(got))

    def test_a_legacy_claim_relabelled_as_v2_does_not_verify(self):
        # otherwise a carrier could have an unsigned timestamp treated as authenticated
        legacy = dest_line(self.seller, fmt=1)
        relabelled = legacy.replace("|dest1|", "|dest2|", 1)
        with self.assertRaises(paybox.PayError):
            paybox.Destination(relabelled).verify(pub_hex(self.seller))

    def test_a_v2_claim_relabelled_as_legacy_does_not_verify(self):
        v2 = dest_line(self.seller)
        with self.assertRaises(paybox.PayError):
            paybox.Destination(v2.replace("|dest2|", "|dest1|", 1)).verify(pub_hex(self.seller))

    def test_an_unknown_wire_prefix_is_refused(self):
        with self.assertRaises(paybox.PayError):
            paybox.Destination(dest_line(self.seller).replace("|dest2|", "|dest9|", 1))

    # ---- the transition ------------------------------------------------------------------

    def test_the_version_keeps_counting_across_the_format_change(self):
        # a legacy claim at version 3 is followed by a v2 claim at version 4, not 1. The
        # signature format and the claim version are different things.
        self.assertTrue(self.put(fmt=1, version=3, msisdn="066111111")["ok"])
        out = self.put(fmt=2, version=4, msisdn="055222222", rail="AIRTEL_MONEY",
                       created=self.now + 60_000)
        self.assertTrue(out["ok"])
        self.assertEqual(4, out["version"])
        after = self.box.destinations_for_buyer(
            node_id(self.seller), self.now + 60_000 + COOLING, COOLING)
        self.assertEqual(4, after[0]["version"])

    def test_a_legacy_claim_cannot_overwrite_a_newer_v2_one(self):
        self.assertTrue(self.put(fmt=2, version=2, msisdn="055222222", rail="AIRTEL_MONEY")["ok"])
        with self.assertRaises(paybox.PayError):
            self.put(fmt=1, version=1, msisdn="066111111")
        with self.assertRaises(paybox.PayError):
            self.put(fmt=1, version=2, msisdn="066111111")
        got = self.box.destinations_for_buyer(node_id(self.seller), self.now, COOLING)
        self.assertEqual("AIRTEL_MONEY", got[0]["rail"])

    def test_a_mixed_history_still_cools_correctly(self):
        t1 = self.now + 60_000
        self.assertTrue(self.put(fmt=1, version=1, msisdn="066111111")["ok"])
        self.assertTrue(self.put(fmt=2, version=2, msisdn="055222222",
                                 rail="AIRTEL_MONEY", created=t1)["ok"])
        during = self.box.destinations_for_buyer(node_id(self.seller), t1 + 1_000, COOLING)
        self.assertEqual(1, during[0]["version"], "inside cooling, the legacy claim")
        after = self.box.destinations_for_buyer(node_id(self.seller), t1 + COOLING, COOLING)
        self.assertEqual(2, after[0]["version"])


class ExpectationTimingTest(unittest.TestCase):
    """v0.16.5: an expectation is judged by the destination the buyer was GIVEN.

        09:59  the buyer creates a signed expectation; MTN is still active
        10:00  the cooling window closes and Airtel becomes active
        10:01  the buyer finally gets online and uploads it

    Checked against 10:01, the expectation named MTN while Airtel was active and the Brain
    refused a payment the seller itself had asked for a minute earlier. Arrival time
    depends on when a phone found signal; it is the one thing here that is not a signed
    fact, so it decides nothing except whether the expectation has expired.
    """

    def setUp(self):
        self.box = paybox.PayBox(":memory:")
        self.t0 = 1_700_000_000_000
        self.t1 = self.t0 + 60_000            # the seller changes destination
        self.boundary = self.t1 + COOLING     # when Airtel becomes active
        self.seller = keypair()
        self.buyer = keypair()
        self.mtn = paybox.destination_hash("MTN_MOMO", "066111111")
        self.airtel = paybox.destination_hash("AIRTEL_MONEY", "055222222")
        self.box.put_destination(dest_line(self.seller, msisdn="066111111", version=1,
                                           created=self.t0),
                                 pub_hex(self.seller), node_id(self.seller), self.t0)
        self.box.put_destination(dest_line(self.seller, msisdn="055222222", version=2,
                                           rail="AIRTEL_MONEY", created=self.t1),
                                 pub_hex(self.seller), node_id(self.seller), self.t1)

    def upload(self, created, dest, rail, at, payment_id="p1"):
        line = exp_line(self.buyer, self.seller, created=created, dest=dest, rail=rail,
                        payment_id=payment_id)
        return self.box.put_expectation(line, pub_hex(self.buyer), node_id(self.buyer),
                                        at, COOLING, {"s1": 1_000})

    # ---- the exact boundary --------------------------------------------------------

    def test_a_created_one_millisecond_before_the_boundary_is_accepted_after_it(self):
        out = self.upload(self.boundary - 1, self.mtn, "MTN_MOMO", self.boundary + 1_000)
        self.assertTrue(out["ok"], "the seller told this buyer MTN; refusing it is refusing "
                                   "a payment the seller asked for")

    def test_b_created_exactly_at_the_boundary_must_use_the_new_destination(self):
        with self.assertRaises(paybox.PayError):
            self.upload(self.boundary, self.mtn, "MTN_MOMO", self.boundary + 1_000)
        out = self.upload(self.boundary, self.airtel, "AIRTEL_MONEY", self.boundary + 1_000,
                          payment_id="p2")
        self.assertTrue(out["ok"])

    def test_c_created_after_the_boundary_must_use_the_new_destination(self):
        with self.assertRaises(paybox.PayError):
            self.upload(self.boundary + 1, self.mtn, "MTN_MOMO", self.boundary + 1_000)
        out = self.upload(self.boundary + 1, self.airtel, "AIRTEL_MONEY",
                          self.boundary + 1_000, payment_id="p3")
        self.assertTrue(out["ok"])

    # ---- a slow upload --------------------------------------------------------------

    def test_an_expectation_uploaded_fifteen_minutes_late_is_still_accepted(self):
        created = self.t1 + 1_000                      # well inside cooling: MTN
        late = created + 15 * 60 * 1000                # long after the switch
        out = self.upload(created, self.mtn, "MTN_MOMO", late)
        self.assertTrue(out["ok"])

    def test_but_an_expectation_that_has_actually_expired_is_refused(self):
        created = self.t1 + 1_000
        with self.assertRaises(paybox.PayError) as cm:
            self.upload(created, self.mtn, "MTN_MOMO", created + 21 * 60 * 1000)
        self.assertIn("expired", str(cm.exception))

    # ---- what the buyer cannot do ------------------------------------------------------

    def test_a_buyer_cannot_name_a_destination_that_was_never_active(self):
        stranger = paybox.destination_hash("MTN_MOMO", "066999999")
        with self.assertRaises(paybox.PayError):
            self.upload(self.boundary + 1, stranger, "MTN_MOMO", self.boundary + 1_000)

    def test_a_buyer_cannot_backdate_its_expectation_to_reach_an_old_destination(self):
        # created_at is inside the buyer's own signature, so altering it after signing
        # breaks the expectation rather than reopening an old destination
        # honestly created AFTER the switch, so Airtel is required
        line = exp_line(self.buyer, self.seller, created=self.boundary + 1,
                        dest=self.mtn, rail="MTN_MOMO")
        parts = line.split("|")
        self.assertEqual(str(self.boundary + 1), parts[8], "index 8 is createdAt")
        parts[8] = str(self.boundary - 1)              # backdated to reach MTN again
        with self.assertRaises(paybox.PayError):
            self.box.put_expectation("|".join(parts), pub_hex(self.buyer),
                                     node_id(self.buyer), self.boundary + 1_000, COOLING,
                                     {"s1": 1_000})

    def test_the_rail_must_match_the_destination_it_names(self):
        with self.assertRaises(paybox.PayError):
            self.upload(self.t0 + 1_000, self.mtn, "AIRTEL_MONEY", self.t0 + 2_000)


class RailsTest(unittest.TestCase):
    """v0.16.3: a buyer must not have to guess which operator a seller uses.

    The Android Brain fetch asked for MTN_MOMO and nothing else, so an Airtel seller was
    silently unreachable: the buyer owed money and the honest answer came back "this
    provider has not said where to be paid". Nobody could have told from the screen that
    the question had been asked wrongly.

    The product gives a seller ONE place it is paid. Which operator that is can change,
    and the rail is simply part of what changed - which is why versions here count per
    seller, exactly as `DestinationClaim.nextVersion` does on the phone. A separate
    counter per rail would have left a seller who moved to Airtel with a stale MTN claim
    that still looked current, and buyers would have kept paying an abandoned number.
    """

    def setUp(self):
        self.box = paybox.PayBox(":memory:")
        self.now = 1_700_000_000_000
        self.seller = keypair()
        self.buyer = keypair()

    def publish(self, rail, msisdn="066123456", version=1, at=None):
        line = dest_line(self.seller, msisdn=msisdn, version=version, rail=rail)
        return self.box.put_destination(line, pub_hex(self.seller), node_id(self.seller),
                                        at if at is not None else self.now)

    def offered(self, at=None):
        return self.box.destinations_for_buyer(
            node_id(self.seller), self.now if at is None else at, COOLING)

    def test_an_mtn_seller_is_reachable(self):
        self.publish("MTN_MOMO")
        got = self.offered()
        self.assertEqual(1, len(got))
        self.assertEqual("MTN_MOMO", got[0]["rail"])

    def test_an_airtel_seller_is_reachable(self):
        self.publish("AIRTEL_MONEY", msisdn="055987654")
        got = self.offered()
        self.assertEqual(1, len(got), "an Airtel-only seller used to be invisible")
        self.assertEqual("AIRTEL_MONEY", got[0]["rail"])
        # and it is the seller's own signed claim, verifiable by a buyer that never met it
        paybox.Destination(got[0]["line"]).verify(got[0]["seller_pub"])

    def test_a_seller_with_no_claim_offers_nothing(self):
        self.assertEqual([], self.offered())

    def test_the_rail_comes_from_what_the_seller_signed_not_from_a_list_in_the_code(self):
        # the day an operator we have never heard of appears, it must simply work
        self.publish("SOME_NEW_RAIL")
        self.assertEqual("SOME_NEW_RAIL", self.offered()[0]["rail"])

    # ---- changing operator ----------------------------------------------------------

    def test_a_seller_moving_from_mtn_to_airtel_keeps_receiving_on_mtn_while_it_cools(self):
        self.publish("MTN_MOMO", msisdn="066111111", version=1)
        later = self.now + 60_000
        self.publish("AIRTEL_MONEY", msisdn="055222222", version=2, at=later)

        during = self.offered(later + 1_000)[0]
        self.assertEqual("MTN_MOMO", during["rail"],
                         "a transfer already on its way must still land somewhere valid")
        self.assertEqual(paybox.normalize_msisdn("066111111"),
                         paybox.Destination(during["line"]).msisdn)

        after = self.offered(later + COOLING + 1)[0]
        self.assertEqual("AIRTEL_MONEY", after["rail"])
        self.assertEqual(paybox.normalize_msisdn("055222222"),
                         paybox.Destination(after["line"]).msisdn)

    def test_the_abandoned_rail_does_not_stay_offered(self):
        self.publish("MTN_MOMO", msisdn="066111111", version=1)
        later = self.now + 60_000
        self.publish("AIRTEL_MONEY", msisdn="055222222", version=2, at=later)
        after = self.offered(later + COOLING + 1)
        self.assertEqual(1, len(after), "one seller, one place it is paid")
        self.assertEqual("AIRTEL_MONEY", after[0]["rail"])

    def test_a_stale_claim_on_the_old_rail_cannot_be_republished_over_the_new_one(self):
        self.publish("MTN_MOMO", msisdn="066111111", version=1)
        self.publish("AIRTEL_MONEY", msisdn="055222222", version=2, at=self.now + 60_000)
        with self.assertRaises(paybox.PayError):
            # the same version on another rail is a DIFFERENT claim, not an honest retry
            self.publish("MTN_MOMO", msisdn="066999999", version=2, at=self.now + 70_000)
        # re-posting the IDENTICAL old claim is an honest retry from a phone that never
        # heard the first answer, so it is accepted - and it still does not come back
        out = self.publish("MTN_MOMO", msisdn="066111111", version=1, at=self.now + 70_000)
        self.assertTrue(out["duplicate"])
        self.assertEqual("AIRTEL_MONEY", self.offered(self.now + 10 * COOLING)[0]["rail"],
                         "an old claim must never become current again")

    def test_republishing_the_same_claim_is_still_an_honest_retry(self):
        self.publish("AIRTEL_MONEY", msisdn="055222222", version=1)
        out = self.publish("AIRTEL_MONEY", msisdn="055222222", version=1, at=self.now + 5_000)
        self.assertTrue(out["duplicate"], "ECDSA is randomised; the same claim is the same claim")

    # ---- naming a rail explicitly -------------------------------------------------------

    def test_naming_the_wrong_rail_returns_nothing_rather_than_another_number(self):
        self.publish("AIRTEL_MONEY", msisdn="055222222")
        self.assertIsNone(self.box.destination_for_buyer(
            node_id(self.seller), "MTN_MOMO", self.now, COOLING),
            "money sent over MTN to an Airtel number does not arrive")
        self.assertIsNotNone(self.box.destination_for_buyer(
            node_id(self.seller), "AIRTEL_MONEY", self.now, COOLING))


class SignerKeyTravelsTest(unittest.TestCase):
    """v0.16.2: the signing key rides along with every carried object.

    Without it the Brain path is fail-closed and useless. A buyer who has never met a
    seller has no way to check that seller's signature, so it refuses everything and the
    debt can never be paid.

    Sending the key is safe because a node id IS the first bytes of the hash of the key.
    The phone re-derives the id before it believes anything, so a Brain that substituted
    its own key would simply be offering a key for a different identity. These tests fix
    that the key comes back at all; the phone side proves it checks it.
    """

    def setUp(self):
        self.box = paybox.PayBox(":memory:")
        self.now = 1_700_000_000_000
        self.buyer = keypair()
        self.seller = keypair()

    def test_a_destination_comes_back_with_the_sellers_key(self):
        self.box.put_destination(dest_line(self.seller), pub_hex(self.seller),
                                 node_id(self.seller), self.now)
        d = self.box.destination_for_buyer(node_id(self.seller), "MTN_MOMO", self.now, COOLING)
        self.assertEqual(pub_hex(self.seller), d["seller_pub"])
        # and it is the key the line actually verifies against
        paybox.Destination(d["line"]).verify(d["seller_pub"])
        # the id in the claim is the hash of that key, which is what makes it safe to send
        self.assertEqual(node_id(self.seller), paybox.protocol.node_id(d["seller_pub"]))

    def test_an_expectation_comes_back_with_the_buyers_key(self):
        self.box.put_destination(dest_line(self.seller), pub_hex(self.seller),
                                 node_id(self.seller), self.now)
        self.box.put_expectation(exp_line(self.buyer, self.seller), pub_hex(self.buyer),
                                 node_id(self.buyer), self.now, COOLING,
                                 {"s1": 1_000})
        rows = self.box.pending_expectations(node_id(self.seller), self.now)
        self.assertEqual(1, len(rows))
        self.assertEqual(pub_hex(self.buyer), rows[0]["buyer_pub"])
        paybox.Expectation(rows[0]["line"]).verify(rows[0]["buyer_pub"])

    def test_a_receipt_comes_back_with_the_sellers_key(self):
        self.box.put_destination(dest_line(self.seller), pub_hex(self.seller),
                                 node_id(self.seller), self.now)
        self.box.put_expectation(exp_line(self.buyer, self.seller), pub_hex(self.buyer),
                                 node_id(self.buyer), self.now, COOLING, {"s1": 1_000})
        self.box.put_reply("p1", "ACCEPTED", node_id(self.seller), self.now)
        self.box.put_receipt(receipt_line(self.seller, self.buyer), pub_hex(self.seller),
                             node_id(self.seller), self.now + 60_000)
        rows = self.box.pending_receipts(node_id(self.buyer))
        self.assertEqual(1, len(rows))
        self.assertEqual(pub_hex(self.seller), rows[0]["seller_pub"])
        paybox.Receipt(rows[0]["line"]).verify(rows[0]["seller_pub"])

    def test_the_whole_loop_runs_without_the_two_phones_ever_meeting(self):
        # seller publishes where it is paid
        self.box.put_destination(dest_line(self.seller), pub_hex(self.seller),
                                 node_id(self.seller), self.now)
        # buyer, far away, reads it and leaves what it intends to pay
        d = self.box.destination_for_buyer(node_id(self.seller), "MTN_MOMO", self.now, COOLING)
        self.assertIsNotNone(d)
        self.box.put_expectation(exp_line(self.buyer, self.seller), pub_hex(self.buyer),
                                 node_id(self.buyer), self.now, COOLING, {"s1": 1_000})
        # seller collects it, answers, and later leaves the receipt its own phone observed
        inbox = self.box.pending_expectations(node_id(self.seller), self.now)
        self.assertEqual(1, len(inbox))
        self.box.put_reply("p1", "ACCEPTED", node_id(self.seller), self.now)
        self.assertEqual("ACCEPTED",
                         self.box.reply_for("p1", node_id(self.buyer))["reply"])
        self.box.put_receipt(receipt_line(self.seller, self.buyer), pub_hex(self.seller),
                             node_id(self.seller), self.now + 60_000)
        # buyer collects the receipt and acknowledges it; nothing stays pending
        got = self.box.pending_receipts(node_id(self.buyer))
        self.assertEqual(1, len(got))
        self.box.ack_receipt("p1", node_id(self.buyer))
        self.assertEqual([], self.box.pending_receipts(node_id(self.buyer)))
        # the server authored none of it: every object still verifies against its phone
        paybox.Receipt(got[0]["line"]).verify(pub_hex(self.seller))


class RuleConfigTest(unittest.TestCase):
    def setUp(self):
        self.key = keypair()
        self.box = paybox.PayBox(":memory:")
        self.store = ruleconfig.RuleStore(self.box.db, pub_hex(self.key))
        self.now = 1_700_000_000_000
        self.terms = {"credit": ["recu", "credite"], "debit": ["envoye"],
                      "currency": ["fcfa", "xaf"], "balance": ["solde"],
                      "reject": ["otp"], "senders": ["MTN"]}

    def publish(self, version=1, terms=None, key=None, valid_from=0):
        terms = self.terms if terms is None else terms
        sig = ruleconfig.sign(version, valid_from, terms, (key or self.key))
        return self.store.publish(version, valid_from, terms, sig, self.now)

    def test_a_properly_signed_config_is_accepted_and_read_back(self):
        self.assertTrue(self.publish(1)["ok"])
        c = self.store.current()
        self.assertEqual(1, c["version"])
        self.assertEqual(["recu", "credite"], c["terms"]["credit"])
        # and the phone can verify it against the pinned key without trusting the server
        self.assertTrue(ruleconfig.verify(c["version"], c["validFrom"], c["terms"],
                                          c["signature"], pub_hex(self.key)))

    def test_a_config_signed_by_the_wrong_key_is_refused(self):
        with self.assertRaises(ruleconfig.ConfigError):
            self.publish(1, key=keypair())

    def test_a_tampered_config_no_longer_verifies(self):
        self.publish(1)
        c = self.store.current()
        c["terms"]["credit"].append("evil")
        self.assertFalse(ruleconfig.verify(c["version"], c["validFrom"], c["terms"],
                                           c["signature"], pub_hex(self.key)))

    def test_an_older_or_equal_version_is_refused(self):
        self.publish(2)
        for v in (1, 2):
            with self.assertRaises(ruleconfig.ConfigError):
                self.publish(v)
        self.assertEqual(2, self.store.current()["version"])

    def test_a_compromised_brain_cannot_send_an_enormous_or_hostile_rule_set(self):
        # too many terms
        with self.assertRaises(ruleconfig.ConfigError):
            self.publish(3, terms={"credit": ["t%d" % i for i in range(1000)]})
        # a term long enough to matter
        with self.assertRaises(ruleconfig.ConfigError):
            self.publish(3, terms={"credit": ["x" * 500]})
        # control characters
        with self.assertRaises(ruleconfig.ConfigError):
            self.publish(3, terms={"credit": ["bad\x00term"]})
        # and the shape itself is checked
        with self.assertRaises(ruleconfig.ConfigError):
            self.publish(3, terms={"credit": "not-a-list"})

    def test_a_server_that_publishes_no_rules_is_a_normal_deployment(self):
        none = ruleconfig.RuleStore(paybox.PayBox(":memory:").db, "")
        self.assertIsNone(none.current())
        with self.assertRaises(ruleconfig.ConfigError):
            none.publish(1, 0, self.terms, "00", self.now)


class DurableNonceTest(unittest.TestCase):
    """v0.16.2: replay protection that a restart does not undo."""

    def setUp(self):
        self.box = paybox.PayBox(":memory:")
        self.now = 1_700_000_000_000

    def test_a_nonce_survives_a_restart(self):
        n1 = signed_request.Nonces(self.box.db)
        self.assertTrue(n1.use("prokA", "nonce-1", self.now))
        self.assertFalse(n1.use("prokA", "nonce-1", self.now))
        # the process dies and comes back on the same database
        n2 = signed_request.Nonces(self.box.db)
        self.assertFalse(n2.use("prokA", "nonce-1", self.now),
                         "restarting must not make an old request replayable again")

    def test_nonces_are_per_identity_and_expire(self):
        n = signed_request.Nonces(self.box.db)
        self.assertTrue(n.use("prokA", "same", self.now))
        self.assertTrue(n.use("prokB", "same", self.now), "two identities may use the same nonce")
        n.sweep(self.now + signed_request.NONCE_TTL_MS + 1)
        self.assertTrue(n.use("prokA", "same", self.now + signed_request.NONCE_TTL_MS + 2))


class SigningBindingTest(unittest.TestCase):
    """v0.16.2: a signature may not be moved to another endpoint."""

    def test_method_and_path_are_covered(self):
        a = signed_request.signing_line(1, "n", "h", "POST", "/v1/pay/receipt")
        b = signed_request.signing_line(1, "n", "h", "POST", "/v1/pay/destination")
        c = signed_request.signing_line(1, "n", "h", "GET", "/v1/pay/receipt")
        self.assertNotEqual(a, b)
        self.assertNotEqual(a, c)
        # and the older unbound shape is still distinct, so old phones keep working
        self.assertNotEqual(a, signed_request.signing_line(1, "n", "h"))


if __name__ == "__main__":
    unittest.main()
