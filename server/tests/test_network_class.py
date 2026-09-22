"""v0.17.4: a provider that cannot charge is unavailable, not generous.

The phone-side bug is in `ProviderPresence`: build 71 set `free_ready` from
`!mayOfferPaidSharing`, so a commercial seller whose Mobile Money number was missing was
published to the Brain as a FREE provider. This file pins the server half - what the
matcher must do with the honest flags, and what it must do when a provider's readiness
changes while somebody is already waiting.

It also covers a starvation risk this fix creates. Before v0.17.4 almost every provider
carried `free_ready=1` (because almost every provider is, at some moment, unable to
charge), so a FREE demand nearly always found somebody. Now that FREE is explicit, a FREE
demand in a zone with only commercial providers is genuinely unservable - and if it is the
oldest, `serve_zone` would hand it every heartbeat for ever and the COMMERCIAL demands
behind it would never be reconsidered.
"""
import sqlite3
import unittest

from brain import network


def plane():
    db = sqlite3.connect(":memory:")
    db.row_factory = sqlite3.Row
    return network.NetworkPlane(db)


NOW = 1_700_000_000_000


class ClassMatchingTest(unittest.TestCase):
    """Cases 2, 3, 4 and 5: who is a candidate for what."""

    def setUp(self):
        self.n = plane()
        self.zone = "z-cls:1"
        self.buyer = "b" * 32
        self.seller = "a" * 32

    def present(self, commercial=0, free=0, offer=network.COMMERCIAL, price=300):
        self.n.put_presence(self.seller, {
            "zone": self.zone, "upstreamAvailable": 1, "sharingEnabled": 1,
            "commercialReady": commercial, "freeReady": free, "currentLoad": 0,
            "maxBuyers": 1, "offerClass": offer, "priceHintInternal": price}, NOW)

    def ask(self, want, demand_id="dem-cls-0001"):
        self.n.put_demand(self.buyer, {
            "demandId": demand_id, "zone": self.zone, "budgetCentimes": 5_000,
            "requestedClass": want}, NOW)
        return self.n.serve(demand_id, NOW)

    # ---- case 2 ----------------------------------------------------------------------

    def test_a_free_buyer_is_not_given_a_commercial_seller_that_cannot_charge(self):
        # the exact phone state: commercial intent, payment not ready. Build 71 would
        # have sent free_ready=1 here and this buyer would have been matched.
        self.present(commercial=0, free=0, offer=network.COMMERCIAL)
        out = self.ask(network.FREE)
        self.assertNotEqual(network.ACTIVATION_SENT, out["status"])
        self.assertEqual(0, out.get("candidates"))
        self.assertEqual([], self.n.jobs_for_provider(self.seller, NOW))

    # ---- case 3 ----------------------------------------------------------------------

    def test_a_commercial_buyer_is_not_given_a_seller_that_cannot_charge(self):
        self.present(commercial=0, free=0, offer=network.COMMERCIAL)
        out = self.ask(network.COMMERCIAL)
        self.assertNotEqual(network.ACTIVATION_SENT, out["status"])
        self.assertEqual(0, out.get("candidates"))

    # ---- case 4 ----------------------------------------------------------------------

    def test_an_explicitly_free_provider_serves_a_free_buyer(self):
        self.present(commercial=0, free=1, offer=network.FREE, price=0)
        out = self.ask(network.FREE)
        self.assertEqual(network.ACTIVATION_SENT, out["status"])

    # ---- case 5 ----------------------------------------------------------------------

    def test_a_paid_ready_seller_serves_a_commercial_buyer(self):
        self.present(commercial=1, free=0, offer=network.COMMERCIAL)
        out = self.ask(network.COMMERCIAL)
        self.assertEqual(network.ACTIVATION_SENT, out["status"])

    def test_the_class_the_buyer_is_matched_on_is_the_class_it_asked_for(self):
        # a FREE buyer must never be quietly upgraded into a paying one
        self.present(commercial=1, free=0, offer=network.COMMERCIAL)
        p = self.n.presence(self.seller)
        self.assertFalse(p.ready_for(network.FREE))
        self.assertEqual(network.COMMERCIAL, p.effective_class(network.FREE))
        # and the matcher itself agrees, not only the row
        self.n.put_demand(self.buyer, {
            "demandId": "dem-cls-0009", "zone": self.zone, "budgetCentimes": 0,
            "requestedClass": network.FREE}, NOW)
        self.assertEqual([], self.n.candidates("dem-cls-0009", NOW))


class ReadinessBecomesTrueTest(unittest.TestCase):
    """The end-to-end case: OUKITEL willing, not sharing, payment not ready yet."""

    def setUp(self):
        self.n = plane()
        self.zone = "z-ready:1"
        self.buyer = "b" * 32
        self.seller = "a" * 32
        self.demand = "dem-ready-0001"

    def present(self, commercial):
        return self.n.put_presence(self.seller, {
            "zone": self.zone, "upstreamAvailable": 1, "sharingEnabled": 1,
            "commercialReady": commercial, "freeReady": 0, "currentLoad": 0,
            "maxBuyers": 1, "offerClass": network.COMMERCIAL,
            "priceHintInternal": 300}, NOW)

    def test_an_unready_seller_wakes_for_nobody_and_then_wakes_when_it_can_charge(self):
        # 1. the OUKITEL is willing, not sharing, commercial, payment NOT ready
        self.present(commercial=0)
        p = self.n.presence(self.seller)
        self.assertIsNotNone(p, "the presence still exists for coverage and diagnostics")
        self.assertTrue(int(p.sharing_enabled), "it is still discoverable")
        self.assertFalse(int(p.free_ready), "but it is NOT advertised as free")
        self.assertEqual(network.COMMERCIAL, p.offer_class, "nor relabelled")

        # 2. a buyer asking FREE does not wake it
        self.n.put_demand(self.buyer, {
            "demandId": self.demand, "zone": self.zone, "budgetCentimes": 0,
            "requestedClass": network.FREE}, NOW)
        self.assertNotEqual(network.ACTIVATION_SENT,
                            self.n.serve(self.demand, NOW)["status"])
        self.assertEqual([], self.n.jobs_for_provider(self.seller, NOW))

        # 3. and neither does a buyer asking COMMERCIAL
        other = "c" * 32
        self.n.put_demand(other, {
            "demandId": "dem-ready-0002", "zone": self.zone, "budgetCentimes": 5_000,
            "requestedClass": network.COMMERCIAL}, NOW)
        self.assertNotEqual(network.ACTIVATION_SENT,
                            self.n.serve("dem-ready-0002", NOW)["status"])
        self.assertEqual([], self.n.jobs_for_provider(self.seller, NOW))

        # 4. the seller finishes setting up Mobile Money. The next heartbeat carries
        #    commercialReady=1, and the waiting COMMERCIAL demand is reconsidered without
        #    the buyer having to ask again.
        out = self.present(commercial=1)
        self.assertIn("served", out, "the waiting demand must be picked up on the heartbeat")
        jobs = self.n.jobs_for_provider(self.seller, NOW)
        self.assertEqual(1, len(jobs))
        self.assertEqual("dem-ready-0002", jobs[0]["demand_id"],
                         "the COMMERCIAL demand, not the FREE one it still cannot serve")

    def test_the_free_demand_it_cannot_serve_does_not_block_the_one_it_can(self):
        # the starvation this fix would otherwise introduce. The FREE demand is OLDER, so
        # oldest-first would hand it over on every heartbeat and the commercial buyer
        # behind it would wait for ever.
        self.n.put_demand(self.buyer, {
            "demandId": "dem-free-0001", "zone": self.zone, "budgetCentimes": 0,
            "requestedClass": network.FREE}, NOW)
        self.n.put_demand("c" * 32, {
            "demandId": "dem-paid-0002", "zone": self.zone, "budgetCentimes": 5_000,
            "requestedClass": network.COMMERCIAL}, NOW + 1_000)

        oldest = self.n.oldest_waiting_demand(self.zone, NOW + 2_000)
        self.assertEqual("dem-free-0001", oldest["demand_id"], "the FREE one really is first")

        self.present(commercial=1)
        jobs = self.n.jobs_for_provider(self.seller, NOW + 2_000)
        self.assertEqual(1, len(jobs))
        self.assertEqual("dem-paid-0002", jobs[0]["demand_id"])
        # and the free demand is untouched, still waiting for somebody who can serve it
        self.assertIn(self.n.demand("dem-free-0001")["status"], network.DEMAND_LIVE)

    def test_fairness_is_unchanged_among_demands_it_can_serve(self):
        # oldest-first still, once the ones it cannot serve are out of the way
        self.n.put_demand(self.buyer, {
            "demandId": "dem-paid-0001", "zone": self.zone, "budgetCentimes": 5_000,
            "requestedClass": network.COMMERCIAL}, NOW)
        self.n.put_demand("c" * 32, {
            "demandId": "dem-paid-0002", "zone": self.zone, "budgetCentimes": 5_000,
            "requestedClass": network.COMMERCIAL}, NOW + 1_000)
        self.present(commercial=1)
        jobs = self.n.jobs_for_provider(self.seller, NOW + 2_000)
        self.assertEqual(1, len(jobs))
        self.assertEqual("dem-paid-0001", jobs[0]["demand_id"], "the one that waited longest")


if __name__ == "__main__":
    unittest.main()
