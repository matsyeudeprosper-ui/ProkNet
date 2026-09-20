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


if __name__ == "__main__":
    unittest.main()
