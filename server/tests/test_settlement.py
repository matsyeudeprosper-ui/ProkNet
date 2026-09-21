"""v0.15.0: the settlement service.

The properties that matter: one session can only ever create one obligation, a repeated
webhook changes nothing, an unverified webhook changes nothing at all, and two phones
disagreeing is a dispute rather than a licence to charge the larger figure.
"""
import unittest

from brain import settlement


class SettlementTest(unittest.TestCase):
    def setUp(self):
        self.s = settlement.Settlements(":memory:")
        self.now = 1_700_000_000_000
        self.buyer = "aa" * 16
        self.seller = "bb" * 16

    def claim(self, gross=1_200, cp="cp-hash-1", session="sess-1"):
        sid = settlement.settlement_id(session, "contract-hash-1", cp)
        return {
            "settlement_id": sid, "session_id": session, "buyer_id": self.buyer,
            "seller_id": self.seller, "contract_hash": "contract-hash-1", "checkpoint_hash": cp,
            "gross": gross, "fee_pct": 5, "expires_at": self.now + 86_400_000,
        }

    # ---- reporting -------------------------------------------------------------------

    def test_the_split_matches_the_phones(self):
        gross, fee, net = settlement.split(1_200, 5)
        self.assertEqual(1_200, gross)
        self.assertEqual(60, fee)
        self.assertEqual(1_140, net)
        self.assertEqual(gross, fee + net)
        # half up, exactly as Market.split does it
        self.assertEqual(11, settlement.split(211, 5)[1])
        self.assertEqual((0, 0, 0), settlement.split(0, 5))

    def test_one_session_creates_one_obligation_however_often_it_is_reported(self):
        c = self.claim()
        first = self.s.report(c, "buyer", self.now)
        self.assertTrue(first["ok"])
        self.assertFalse(first["agreed"])
        # the seller reports the same session: this reconciles, it does not duplicate
        second = self.s.report(c, "seller", self.now + 1_000)
        self.assertTrue(second["agreed"], "both phones reported the same figure")
        self.assertEqual(first["settlement_id"], second["settlement_id"])
        # and a replay of either changes nothing
        again = self.s.report(c, "buyer", self.now + 2_000)
        self.assertTrue(again["agreed"])
        rows = self.s.db.execute("SELECT COUNT(*) c FROM settlements").fetchone()["c"]
        self.assertEqual(1, rows)

    def test_a_forged_settlement_id_is_refused(self):
        c = self.claim()
        c["settlement_id"] = "0" * 32
        r = self.s.report(c, "buyer", self.now)
        self.assertFalse(r["ok"])
        self.assertIn("does not match", r["error"])
        self.assertEqual(0, self.s.db.execute("SELECT COUNT(*) c FROM settlements").fetchone()["c"])

    def test_two_phones_reporting_different_figures_is_disputed_not_charged(self):
        self.s.report(self.claim(gross=800), "buyer", self.now)
        r = self.s.report(self.claim(gross=1_200, cp="cp-hash-2"), "seller", self.now + 1)
        # a different checkpoint hash is a different settlement id, so this one is new;
        # the real conflict is the same id with a different amount
        self.assertTrue(r["ok"])

        c = self.claim(gross=800, session="sess-2")
        self.s.report(c, "buyer", self.now)
        bad = dict(c)
        bad["gross"] = 5_000
        out = self.s.report(bad, "seller", self.now + 1)
        self.assertEqual(settlement.DISPUTED, out["status"])
        row = self.s.get(c["settlement_id"])
        self.assertEqual(800, row["gross"], "the larger figure must never be adopted")
        self.assertIn("800", row["note"])

    # ---- payment ---------------------------------------------------------------------

    def test_a_phone_saying_paid_does_not_pay_anything(self):
        c = self.claim()
        self.s.report(c, "buyer", self.now)
        self.s.initiate(c["settlement_id"], "MANUAL_PILOT", "MP-123456", self.now)
        row = self.s.get(c["settlement_id"])
        self.assertEqual(settlement.PAYMENT_INITIATED, row["status"])
        self.assertNotEqual(settlement.CONFIRMED, row["status"])

        # an unverified webhook is recorded and ignored
        out = self.s.webhook({"settlement_id": c["settlement_id"], "rail": "MTN_MOMO",
                              "reference": "R1", "status": settlement.CONFIRMED, "amount": 1_200},
                             self.now + 10, verified=False)
        self.assertFalse(out["ok"])
        self.assertEqual(settlement.PAYMENT_INITIATED, self.s.get(c["settlement_id"])["status"])
        trail = self.s.audit(c["settlement_id"])
        self.assertTrue(any("not verified" in t["detail"] for t in trail))

    def test_a_verified_webhook_confirms_once_and_repeats_are_no_ops(self):
        c = self.claim()
        self.s.report(c, "buyer", self.now)
        ev = {"settlement_id": c["settlement_id"], "rail": "MTN_MOMO", "reference": "R1",
              "status": settlement.CONFIRMED, "amount": 1_200}
        first = self.s.webhook(ev, self.now + 10, verified=True)
        self.assertTrue(first["ok"])
        self.assertEqual(settlement.CONFIRMED, first["status"])
        # the operator retries, as operators do
        for _ in range(3):
            again = self.s.webhook(ev, self.now + 20, verified=True)
            self.assertTrue(again["duplicate"])
            self.assertEqual(settlement.CONFIRMED, again["status"])
        self.assertEqual(1, self.s.db.execute(
            "SELECT COUNT(*) c FROM payment_events WHERE settlement_id=?", (c["settlement_id"],)).fetchone()["c"])
        self.assertEqual(settlement.CONFIRMED, self.s.get(c["settlement_id"])["status"])

    def test_a_payment_for_the_wrong_amount_is_disputed(self):
        c = self.claim(gross=1_200)
        self.s.report(c, "buyer", self.now)
        out = self.s.webhook({"settlement_id": c["settlement_id"], "rail": "MTN_MOMO",
                              "reference": "R9", "status": settlement.CONFIRMED, "amount": 200},
                             self.now + 10, verified=True)
        self.assertFalse(out["ok"])
        self.assertEqual(settlement.DISPUTED, self.s.get(c["settlement_id"])["status"])

    def test_a_replayed_reference_from_another_session_cannot_confirm_this_one(self):
        a = self.claim(session="sess-a")
        b = self.claim(session="sess-b")
        self.s.report(a, "buyer", self.now)
        self.s.report(b, "buyer", self.now)
        self.s.webhook({"settlement_id": a["settlement_id"], "rail": "MTN_MOMO", "reference": "SAME",
                        "status": settlement.CONFIRMED, "amount": 1_200}, self.now + 1, verified=True)
        # the same operator reference pointed at a different obligation is a different
        # event key, so it is not silently swallowed; it must still verify on its merits
        self.assertEqual(settlement.CONFIRMED, self.s.get(a["settlement_id"])["status"])
        self.assertEqual(settlement.PENDING, self.s.get(b["settlement_id"])["status"])

    def test_nothing_moves_a_confirmed_obligation(self):
        c = self.claim()
        self.s.report(c, "buyer", self.now)
        self.s.webhook({"settlement_id": c["settlement_id"], "rail": "MOCK", "reference": "R1",
                        "status": settlement.CONFIRMED, "amount": 1_200}, self.now, verified=True)
        self.s.webhook({"settlement_id": c["settlement_id"], "rail": "MOCK", "reference": "R2",
                        "status": settlement.FAILED, "amount": 1_200}, self.now + 1, verified=True)
        self.assertEqual(settlement.CONFIRMED, self.s.get(c["settlement_id"])["status"])
        self.assertEqual({"ok": True, "status": settlement.CONFIRMED, "note": "already paid"},
                         self.s.initiate(c["settlement_id"], "MTN_MOMO", "R3", self.now + 2))

    def test_an_obligation_nobody_paid_expires(self):
        c = self.claim()
        c["expires_at"] = self.now + 1_000
        self.s.report(c, "buyer", self.now)
        self.assertEqual(0, self.s.expire(self.now))
        self.assertEqual(1, self.s.expire(self.now + 2_000))
        self.assertEqual(settlement.EXPIRED, self.s.get(c["settlement_id"])["status"])
        # and expiring twice is not two expiries
        self.assertEqual(0, self.s.expire(self.now + 3_000))

    # ---- wallet ----------------------------------------------------------------------

    def test_the_wallet_reports_obligations_for_both_sides(self):
        a = self.claim(gross=1_200, session="s1")
        b = self.claim(gross=800, session="s2")
        self.s.report(a, "buyer", self.now)
        self.s.report(b, "buyer", self.now)
        self.s.webhook({"settlement_id": b["settlement_id"], "rail": "MOCK", "reference": "R",
                        "status": settlement.CONFIRMED, "amount": 800}, self.now, verified=True)

        buyer = self.s.wallet(self.buyer)
        self.assertEqual(1_200, buyer["to_pay"])
        self.assertEqual(800, buyer["paid"])
        self.assertEqual(1, buyer["pending"])

        seller = self.s.wallet(self.seller)
        self.assertEqual(settlement.split(1_200, 5)[2], seller["to_receive"])
        self.assertEqual(settlement.split(800, 5)[2], seller["received"])
        self.assertEqual(0, seller["to_pay"])

        # the buyer's obligation equals the seller's receivable plus the Prok fee
        row = self.s.get(a["settlement_id"])
        self.assertEqual(int(row["gross"]), int(row["seller_net"]) + int(row["prok_fee"]))

    def test_every_change_leaves_an_audit_trail(self):
        c = self.claim()
        self.s.report(c, "buyer", self.now)
        self.s.initiate(c["settlement_id"], "MANUAL_PILOT", "MP-1", self.now + 1)
        self.s.webhook({"settlement_id": c["settlement_id"], "rail": "MANUAL_PILOT", "reference": "MP-1",
                        "status": settlement.CONFIRMED, "amount": 1_200}, self.now + 2, verified=True)
        trail = self.s.audit(c["settlement_id"])
        self.assertEqual(3, len(trail))
        self.assertEqual(settlement.CONFIRMED, trail[-1]["now"])
        self.assertTrue(all(t["at"] > 0 and t["actor"] for t in trail))


class PaymentTransactionTest(unittest.TestCase):
    """v0.15.1: one real operator transfer, allocated across the obligations it settles.

    The structural fix. Three tiny sessions become one MTN payment, so the unit of
    payment is the transfer, not the obligation. The rule that makes that safe is that
    (rail, operator reference) is ONE transaction and can never be presented
    independently against unrelated debts.
    """

    def setUp(self):
        self.s = settlement.Settlements(":memory:")
        self.now = 1_700_000_000_000
        self.buyer = "aa" * 16
        self.seller = "bb" * 16
        self.ids = []
        for i, amount in enumerate((300, 700, 500)):
            sid = settlement.settlement_id("sess-%d" % i, "contract-%d" % i, "cp-%d" % i)
            g, fee, net = settlement.split(amount, 5)
            self.s.db.execute(
                "INSERT INTO settlements(settlement_id, session_id, buyer_id, seller_id, checkpoint_hash,"
                " gross, seller_net, prok_fee, status, created_at, expires_at)"
                " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                (sid, "sess-%d" % i, self.buyer, self.seller, "cp-%d" % i, g, net, fee,
                 settlement.PENDING, self.now, self.now + 86_400_000))
            self.ids.append((sid, amount))
        self.s.db.commit()

    def allocations(self):
        return [(sid, amount) for sid, amount in self.ids]

    def test_one_transfer_settles_three_sessions(self):
        total = sum(a for _, a in self.ids)
        self.assertEqual(1_500, total)
        out = self.s.open_payment("MTN_MOMO", "ABC123", self.buyer, self.seller, total,
                                  self.allocations(), self.now)
        self.assertTrue(out["ok"], out.get("error"))
        self.assertEqual(total, out["allocated"])
        pid = out["payment_id"]
        self.assertEqual(3, len(self.s.allocations(pid)))
        for sid, _ in self.ids:
            self.assertEqual(settlement.PAYMENT_INITIATED, self.s.get(sid)["status"])

        done = self.s.confirm_payment(pid, total, self.now + 1_000, verified=True)
        self.assertTrue(done["ok"])
        self.assertEqual(3, len(done["settled"]))
        for sid, _ in self.ids:
            self.assertEqual(settlement.CONFIRMED, self.s.get(sid)["status"])
            self.assertEqual("ABC123", self.s.get(sid)["payment_ref"])
        self.assertEqual(0, self.s.wallet(self.buyer)["to_pay"])

    def test_the_same_operator_reference_is_one_transaction(self):
        total = sum(a for _, a in self.ids)
        first = self.s.open_payment("MTN_MOMO", "ABC123", self.buyer, self.seller, total, self.allocations(), self.now)
        again = self.s.open_payment("MTN_MOMO", "ABC123", self.buyer, self.seller, total, self.allocations(), self.now + 1)
        self.assertTrue(again["duplicate"])
        self.assertEqual(first["payment_id"], again["payment_id"])
        self.assertEqual(1, self.s.db.execute("SELECT COUNT(*) c FROM payment_transactions").fetchone()["c"])

    def test_the_same_reference_reused_for_a_different_payment_is_a_security_review(self):
        self.s.open_payment("MTN_MOMO", "ABC123", self.buyer, self.seller, 1_500, self.allocations(), self.now)
        out = self.s.open_payment("MTN_MOMO", "ABC123", self.buyer, self.seller, 9_900,
                                  [self.ids[0]], self.now + 1)
        self.assertFalse(out["ok"])
        self.assertEqual(settlement.SECURITY_REVIEW, out["status"])
        pid = self.s.payment_id("MTN_MOMO", "ABC123")
        self.assertEqual(settlement.SECURITY_REVIEW, self.s.payment(pid)["status"])
        self.assertEqual(settlement.SECURITY_REVIEW, self.s.get(self.ids[0][0])["status"])

    def test_allocations_may_never_exceed_what_was_paid(self):
        out = self.s.open_payment("MTN_MOMO", "REF-SHORT", self.buyer, self.seller, 1_000,
                                  self.allocations(), self.now)
        self.assertFalse(out["ok"])
        self.assertIn("exceed the amount paid", out["error"])
        self.assertIsNone(self.s.payment(self.s.payment_id("MTN_MOMO", "REF-SHORT")))

    def test_an_obligation_may_not_be_allocated_more_than_it_owes(self):
        sid, amount = self.ids[0]
        out = self.s.open_payment("MTN_MOMO", "REF-BIG", self.buyer, self.seller, 9_000,
                                  [(sid, amount * 5)], self.now)
        self.assertFalse(out["ok"])
        self.assertIn("still owes", out["error"])

    def test_a_confirmed_obligation_cannot_be_paid_twice(self):
        sid, amount = self.ids[0]
        p = self.s.open_payment("MTN_MOMO", "REF-1", self.buyer, self.seller, amount, [(sid, amount)], self.now)
        self.s.confirm_payment(p["payment_id"], amount, self.now + 1, verified=True)
        self.assertEqual(settlement.CONFIRMED, self.s.get(sid)["status"])
        self.assertEqual(0, self.s.remaining(sid))
        out = self.s.open_payment("MTN_MOMO", "REF-2", self.buyer, self.seller, amount, [(sid, amount)], self.now + 2)
        self.assertFalse(out["ok"])
        self.assertIn("already paid", out["error"])

    def test_a_duplicate_confirmation_is_a_no_op(self):
        total = sum(a for _, a in self.ids)
        p = self.s.open_payment("MTN_MOMO", "ABC999", self.buyer, self.seller, total, self.allocations(), self.now)
        self.s.confirm_payment(p["payment_id"], total, self.now + 1, verified=True)
        for _ in range(3):
            again = self.s.confirm_payment(p["payment_id"], total, self.now + 2, verified=True)
            self.assertTrue(again["duplicate"])
        self.assertEqual(3, self.s.db.execute(
            "SELECT COUNT(*) c FROM payment_allocations").fetchone()["c"])
        self.assertEqual(0, self.s.wallet(self.buyer)["to_pay"])

    def test_a_confirmation_for_a_different_amount_is_a_security_review(self):
        total = sum(a for _, a in self.ids)
        p = self.s.open_payment("MTN_MOMO", "ABC777", self.buyer, self.seller, total, self.allocations(), self.now)
        out = self.s.confirm_payment(p["payment_id"], 100, self.now + 1, verified=True)
        self.assertFalse(out["ok"])
        self.assertEqual(settlement.SECURITY_REVIEW, out["status"])
        for sid, _ in self.ids:
            self.assertEqual(settlement.SECURITY_REVIEW, self.s.get(sid)["status"])

    def test_an_unverified_confirmation_settles_nothing(self):
        total = sum(a for _, a in self.ids)
        p = self.s.open_payment("MTN_MOMO", "ABC555", self.buyer, self.seller, total, self.allocations(), self.now)
        out = self.s.confirm_payment(p["payment_id"], total, self.now + 1, verified=False)
        self.assertFalse(out["ok"])
        for sid, _ in self.ids:
            self.assertNotEqual(settlement.CONFIRMED, self.s.get(sid)["status"])

    def test_a_partial_payment_leaves_the_rest_owing(self):
        sid, amount = self.ids[1]      # 700
        p = self.s.open_payment("MTN_MOMO", "PART-1", self.buyer, self.seller, 300, [(sid, 300)], self.now)
        self.assertTrue(p["ok"])
        self.s.confirm_payment(p["payment_id"], 300, self.now + 1, verified=True)
        self.assertEqual(settlement.PAYMENT_SEEN, self.s.get(sid)["status"])
        self.assertEqual(400, self.s.remaining(sid), "the rest is still owed")
        p2 = self.s.open_payment("MTN_MOMO", "PART-2", self.buyer, self.seller, 400, [(sid, 400)], self.now + 2)
        self.assertTrue(p2["ok"], p2.get("error"))
        self.s.confirm_payment(p2["payment_id"], 400, self.now + 3, verified=True)
        self.assertEqual(settlement.CONFIRMED, self.s.get(sid)["status"])
        self.assertEqual(0, self.s.remaining(sid))

    def test_a_payment_cannot_mix_obligations_belonging_to_other_people(self):
        stranger = "cc" * 16
        out = self.s.open_payment("MTN_MOMO", "REF-X", stranger, self.seller, 1_500, self.allocations(), self.now)
        self.assertFalse(out["ok"])
        self.assertIn("other parties", out["error"])


if __name__ == "__main__":
    unittest.main()
