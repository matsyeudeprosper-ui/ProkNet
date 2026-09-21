"""v0.16.3: a money operation commits completely or not at all.

Before this, the helpers committed as they went. A payment touching three obligations
committed three times on the way through, so a failure in the middle left a real
transaction row beside obligations that had not moved - money half-settled, with nothing
in the audit trail to say which half was true.

These tests do not hope a failure never happens. They **inject** one, in the middle, and
then check that the database looks exactly as it did before the call. The injection point
is `_audit`, because every state change writes one, which makes it the cheapest place to
stand in the way of a half-finished operation.
"""
import sqlite3
import unittest

from brain import settlement


class Boom(Exception):
    """The failure we inject. Not an error the code knows how to handle."""


class Snapshot:
    """Everything a money operation could have changed, as plain comparable data."""

    TABLES = ("settlements", "settlement_audit", "payment_transactions",
              "payment_allocations", "payment_events")

    def __init__(self, db):
        self.rows = {}
        for t in self.TABLES:
            try:
                self.rows[t] = [tuple(r) for r in db.execute("SELECT * FROM %s" % t)]
            except sqlite3.OperationalError:
                self.rows[t] = []

    def __eq__(self, other):
        return self.rows == other.rows

    def diff(self, other):
        return {t: (len(self.rows[t]), len(other.rows[t]))
                for t in self.TABLES if self.rows[t] != other.rows[t]}


class AtomicityTest(unittest.TestCase):
    def setUp(self):
        self.s = settlement.Settlements(":memory:")
        self.now = 1_700_000_000_000
        self.buyer = "aa" * 16
        self.seller = "bb" * 16
        self.a = self.obligation("sess-a", "cp-a", 1_000)
        self.b = self.obligation("sess-b", "cp-b", 2_000)

    def obligation(self, session, cp, gross):
        sid = settlement.settlement_id(session, "contract-hash", cp)
        self.s.report({
            "settlement_id": sid, "session_id": session, "buyer_id": self.buyer,
            "seller_id": self.seller, "contract_hash": "contract-hash", "checkpoint_hash": cp,
            "gross": gross, "fee_pct": 5, "expires_at": self.now + 86_400_000,
        }, "buyer", self.now)
        return sid

    def fail_after(self, n):
        """Let `n` audit lines through, then throw. Restores itself on the way out."""
        real = self.s._audit
        state = {"n": 0}

        def wrapped(*a, **kw):
            if state["n"] >= n:
                raise Boom("injected failure")
            state["n"] += 1
            return real(*a, **kw)

        self.s._audit = wrapped
        return lambda: setattr(self.s, "_audit", real)

    # ---- opening a payment ----------------------------------------------------------

    def test_a_payment_that_fails_halfway_leaves_nothing_behind(self):
        before = Snapshot(self.s.db)
        restore = self.fail_after(1)          # first obligation moves, then we throw
        try:
            with self.assertRaises(Boom):
                self.s.open_payment("MTN_MOMO", "REF-1", self.buyer, self.seller, 3_000,
                                    [(self.a, 1_000), (self.b, 2_000)], self.now)
        finally:
            restore()

        after = Snapshot(self.s.db)
        self.assertEqual(before, after, "rolled back everything: %s" % before.diff(after))
        # said explicitly, because this is the whole point
        self.assertIsNone(self.s.payment(self.s.payment_id("MTN_MOMO", "REF-1")),
                          "no transaction row")
        self.assertEqual([], self.s.allocations(self.s.payment_id("MTN_MOMO", "REF-1")),
                         "no allocation row")
        self.assertEqual(settlement.PENDING, self.s.get(self.a)["status"],
                         "the obligation that DID move must be back where it was")
        self.assertEqual(settlement.PENDING, self.s.get(self.b)["status"])

    def test_the_same_reference_still_works_after_a_rolled_back_attempt(self):
        restore = self.fail_after(0)
        try:
            with self.assertRaises(Boom):
                self.s.open_payment("MTN_MOMO", "REF-2", self.buyer, self.seller, 3_000,
                                    [(self.a, 1_000), (self.b, 2_000)], self.now)
        finally:
            restore()
        # nothing was written, so the reference is not burned and the buyer can retry
        out = self.s.open_payment("MTN_MOMO", "REF-2", self.buyer, self.seller, 3_000,
                                  [(self.a, 1_000), (self.b, 2_000)], self.now)
        self.assertTrue(out["ok"])
        self.assertFalse(out.get("duplicate"), "the failed attempt must not count as one")
        self.assertEqual(3_000, out["allocated"])

    # ---- confirming a payment ---------------------------------------------------------

    def test_a_confirmation_that_fails_halfway_settles_nobody(self):
        pid = self.s.open_payment("MTN_MOMO", "REF-3", self.buyer, self.seller, 3_000,
                                  [(self.a, 1_000), (self.b, 2_000)], self.now)["payment_id"]
        before = Snapshot(self.s.db)
        restore = self.fail_after(1)          # one obligation settles, then we throw
        try:
            with self.assertRaises(Boom):
                self.s.confirm_payment(pid, 3_000, self.now + 1_000, True)
        finally:
            restore()

        after = Snapshot(self.s.db)
        self.assertEqual(before, after, "rolled back everything: %s" % before.diff(after))
        self.assertEqual(settlement.PAYMENT_INITIATED, self.s.payment(pid)["status"],
                         "the payment must remain in its previous state")
        for sid in (self.a, self.b):
            self.assertEqual(settlement.PAYMENT_INITIATED, self.s.get(sid)["status"],
                             "both obligations must remain in their previous state")

    def test_a_confirmation_that_succeeds_settles_everybody(self):
        # the positive control: without it the test above could pass on broken code
        pid = self.s.open_payment("MTN_MOMO", "REF-4", self.buyer, self.seller, 3_000,
                                  [(self.a, 1_000), (self.b, 2_000)], self.now)["payment_id"]
        out = self.s.confirm_payment(pid, 3_000, self.now + 1_000, True)
        self.assertTrue(out["ok"])
        self.assertEqual({self.a, self.b}, set(out["settled"]))
        self.assertEqual(settlement.CONFIRMED, self.s.payment(pid)["status"])
        for sid in (self.a, self.b):
            self.assertEqual(settlement.CONFIRMED, self.s.get(sid)["status"])

    def test_a_retry_after_a_rolled_back_confirmation_still_works(self):
        pid = self.s.open_payment("MTN_MOMO", "REF-5", self.buyer, self.seller, 3_000,
                                  [(self.a, 1_000), (self.b, 2_000)], self.now)["payment_id"]
        restore = self.fail_after(1)
        try:
            with self.assertRaises(Boom):
                self.s.confirm_payment(pid, 3_000, self.now + 1_000, True)
        finally:
            restore()
        out = self.s.confirm_payment(pid, 3_000, self.now + 2_000, True)
        self.assertTrue(out["ok"])
        self.assertEqual({self.a, self.b}, set(out["settled"]))

    # ---- flagging for review ------------------------------------------------------------

    def test_flagging_a_payment_for_review_is_all_or_nothing(self):
        pid = self.s.open_payment("MTN_MOMO", "REF-6", self.buyer, self.seller, 3_000,
                                  [(self.a, 1_000), (self.b, 2_000)], self.now)["payment_id"]
        before = Snapshot(self.s.db)
        restore = self.fail_after(1)
        try:
            with self.assertRaises(Boom):
                self.s._flag_payment(pid, self.now + 1_000, "injected")
        finally:
            restore()
        after = Snapshot(self.s.db)
        self.assertEqual(before, after, "rolled back everything: %s" % before.diff(after))
        self.assertNotEqual(settlement.SECURITY_REVIEW, self.s.payment(pid)["status"],
                            "a payment must not sit under review while its obligations look payable")

    def test_flagging_that_succeeds_moves_the_payment_and_its_obligations_together(self):
        pid = self.s.open_payment("MTN_MOMO", "REF-7", self.buyer, self.seller, 3_000,
                                  [(self.a, 1_000), (self.b, 2_000)], self.now)["payment_id"]
        self.s._flag_payment(pid, self.now + 1_000, "amount does not match")
        self.assertEqual(settlement.SECURITY_REVIEW, self.s.payment(pid)["status"])
        for sid in (self.a, self.b):
            self.assertEqual(settlement.SECURITY_REVIEW, self.s.get(sid)["status"])

    # ---- reporting and expiry -------------------------------------------------------------

    def test_an_obligation_and_the_audit_line_explaining_it_arrive_together(self):
        before = Snapshot(self.s.db)
        restore = self.fail_after(0)
        try:
            with self.assertRaises(Boom):
                self.obligation("sess-c", "cp-c", 500)
        finally:
            restore()
        after = Snapshot(self.s.db)
        self.assertEqual(before, after,
                         "an obligation without its audit line is a debt nobody can explain: %s"
                         % before.diff(after))

    def test_expiring_a_batch_is_one_change(self):
        old = self.s.db.execute("SELECT COUNT(*) c FROM settlements WHERE status=?",
                                (settlement.EXPIRED,)).fetchone()["c"]
        self.assertEqual(0, old)
        before = Snapshot(self.s.db)
        restore = self.fail_after(1)
        try:
            with self.assertRaises(Boom):
                self.s.expire(self.now + 90 * 24 * 3600 * 1000)
        finally:
            restore()
        after = Snapshot(self.s.db)
        self.assertEqual(before, after, "rolled back everything: %s" % before.diff(after))
        # positive control
        self.assertEqual(2, self.s.expire(self.now + 90 * 24 * 3600 * 1000))

    # ---- the webhook path -------------------------------------------------------------------

    def test_a_webhook_event_and_its_consequence_arrive_together(self):
        before = Snapshot(self.s.db)
        restore = self.fail_after(0)
        try:
            with self.assertRaises(Boom):
                self.s.webhook({"settlement_id": self.a, "rail": "MTN_MOMO", "reference": "R",
                                "status": settlement.CONFIRMED, "amount": 1_000}, self.now, True)
        finally:
            restore()
        after = Snapshot(self.s.db)
        self.assertEqual(before, after,
                         "an event row without its effect reads as already handled: %s"
                         % before.diff(after))
        # positive control: the same event now lands and settles
        out = self.s.webhook({"settlement_id": self.a, "rail": "MTN_MOMO", "reference": "R",
                              "status": settlement.CONFIRMED, "amount": 1_000}, self.now, True)
        self.assertTrue(out["ok"])
        self.assertEqual(settlement.CONFIRMED, self.s.get(self.a)["status"])

    # ---- the helper itself -------------------------------------------------------------------

    def test_set_does_not_commit_on_its_own(self):
        """The property the rest of this file depends on, stated once."""
        row = self.s.get(self.a)
        self.s._set(self.a, settlement.EXPIRED, self.now, "server", row["status"], "test")
        self.s.db.rollback()
        self.assertEqual(settlement.PENDING, self.s.get(self.a)["status"],
                         "_set must leave the transaction to its caller")
