"""v0.17.0: the live control plane.

The property under test throughout: **the Brain coordinates and never carries.** It can
say who is worth asking and what happened; it can never move money, never route traffic,
and never assert that a Bluetooth path works.

Two rules that most of this file exists to protect:

- A zone is a hint. PROVIDER_FOUND may never become CONNECTED, and nothing may skip
  straight from one to the other, because only the phones know whether the radio reached.
- A status report is advisory. It teaches the matcher; it touches no signed accounting.
"""
import unittest

from brain import network


class Clock:
    def __init__(self, t=1_700_000_000_000):
        self.t = t

    def tick(self, ms):
        self.t += ms
        return self.t


def plane():
    import sqlite3
    db = sqlite3.connect(":memory:")
    db.row_factory = sqlite3.Row
    return network.NetworkPlane(db)


BUYER = "b" * 32
SELLER = "a" * 32
OTHER = "c" * 32
ZONE = "z1234:5678"


def presence(zone=ZONE, **kw):
    body = {"zone": zone, "upstreamAvailable": 1, "upstreamClass": "HOME_WIFI",
            "sharingEnabled": 1, "commercialReady": 1, "freeReady": 0,
            "sponsoredReady": 0, "currentLoad": 0, "maxBuyers": 2,
            "offerClass": network.COMMERCIAL, "priceHintInternal": 300}
    body.update(kw)
    return body


def demand(demand_id="dem-0000000001", zone=ZONE, **kw):
    body = {"demandId": demand_id, "zone": zone, "budgetCentimes": 5_000,
            "requestedClass": network.COMMERCIAL, "connectivityNeed": "BROWSE"}
    body.update(kw)
    return body


class PresenceTest(unittest.TestCase):
    def setUp(self):
        self.n = plane()
        self.c = Clock()

    def test_a_heartbeat_is_idempotent(self):
        first = self.n.put_presence(SELLER, presence(), self.c.t)
        again = self.n.put_presence(SELLER, presence(), self.c.tick(1_000))
        self.assertTrue(first["ok"] and again["ok"])
        self.assertEqual(1, len(self.n.fresh_in_zone(ZONE, self.c.t)),
                         "a retried heartbeat is one provider, not two")

    def test_a_provider_that_stops_sending_disappears(self):
        self.n.put_presence(SELLER, presence(), self.c.t)
        self.assertEqual(1, len(self.n.fresh_in_zone(ZONE, self.c.t)))
        self.c.tick(network.PRESENCE_TTL_MS + 1)
        self.assertEqual([], self.n.fresh_in_zone(ZONE, self.c.t),
                         "matching somebody who has gone wastes the buyer's walk")

    def test_a_provider_can_withdraw_at_once(self):
        self.n.put_presence(SELLER, presence(), self.c.t)
        self.n.drop_presence(SELLER, self.c.tick(1_000))
        self.assertEqual([], self.n.fresh_in_zone(ZONE, self.c.t))

    def test_the_ttl_a_phone_asks_for_is_capped(self):
        out = self.n.put_presence(SELLER, presence(ttlMs=10 * 3600 * 1000), self.c.t)
        self.assertEqual(network.PRESENCE_TTL_MS, out["ttlMs"],
                         "a phone cannot promise to be awake for ten hours")

    def test_impossible_capacity_is_clamped(self):
        self.n.put_presence(SELLER, presence(currentLoad=-5, maxBuyers=0), self.c.t)
        p = self.n.presence(SELLER)
        self.assertEqual(0, int(p.current_load))
        self.assertEqual(1, int(p.max_buyers))

    def test_a_presence_needs_a_zone_and_a_known_class(self):
        with self.assertRaises(network.NetworkError):
            self.n.put_presence(SELLER, presence(zone=""), self.c.t)
        with self.assertRaises(network.NetworkError):
            self.n.put_presence(SELLER, presence(offerClass="GIFT"), self.c.t)

    def test_presence_holds_nothing_private(self):
        self.n.put_presence(SELLER, presence(), self.c.t)
        cols = [r[1] for r in self.n.db.execute("PRAGMA table_info(network_presence)")]
        for forbidden in ("msisdn", "lat", "lon", "latitude", "longitude", "number", "address"):
            self.assertNotIn(forbidden, cols,
                             "a presence row must never hold a number or a position")


class DemandTest(unittest.TestCase):
    def setUp(self):
        self.n = plane()
        self.c = Clock()

    def test_a_demand_is_idempotent_on_its_id(self):
        first = self.n.put_demand(BUYER, demand(), self.c.t)
        again = self.n.put_demand(BUYER, demand(), self.c.tick(500))
        self.assertTrue(again.get("duplicate"), "the phone resends this the moment it has signal")
        self.assertEqual(first["demandId"], again["demandId"])
        self.assertEqual(1, int(self.n.db.execute(
            "SELECT COUNT(*) FROM network_demand").fetchone()[0]))

    def test_one_live_demand_per_buyer(self):
        self.n.put_demand(BUYER, demand("dem-0000000001"), self.c.t)
        out = self.n.put_demand(BUYER, demand("dem-0000000002"), self.c.tick(500))
        self.assertTrue(out.get("duplicate"))
        self.assertEqual("dem-0000000001", out["demandId"],
                         "a second ask is the same person asking twice")

    def test_a_demand_belonging_to_somebody_else_is_refused(self):
        self.n.put_demand(BUYER, demand(), self.c.t)
        with self.assertRaises(network.NetworkError):
            self.n.put_demand(OTHER, demand(), self.c.tick(500))

    def test_cancelling_starts_a_cooldown(self):
        self.n.put_demand(BUYER, demand(), self.c.t)
        self.n.cancel_demand("dem-0000000001", BUYER, self.c.tick(1_000))
        with self.assertRaises(network.NetworkError):
            self.n.put_demand(BUYER, demand("dem-0000000009"), self.c.tick(1_000))
        # and it lifts
        self.c.tick(network.DEMAND_COOLDOWN_MS)
        self.assertTrue(self.n.put_demand(BUYER, demand("dem-0000000009"), self.c.t)["ok"])

    def test_only_the_buyer_may_cancel(self):
        self.n.put_demand(BUYER, demand(), self.c.t)
        with self.assertRaises(network.NetworkError):
            self.n.cancel_demand("dem-0000000001", OTHER, self.c.tick(500))

    def test_cancelling_twice_is_not_an_error(self):
        self.n.put_demand(BUYER, demand(), self.c.t)
        self.n.cancel_demand("dem-0000000001", BUYER, self.c.tick(500))
        out = self.n.cancel_demand("dem-0000000001", BUYER, self.c.tick(500))
        self.assertTrue(out.get("duplicate"))

    def test_a_demand_expires_on_its_own(self):
        self.n.put_demand(BUYER, demand(), self.c.t)
        self.c.tick(network.DEMAND_TTL_MS + 1)
        self.n.sweep(self.c.t)
        self.assertEqual(network.EXPIRED, self.n.demand("dem-0000000001")["status"])

    def test_a_status_may_only_move_along_a_path_that_exists(self):
        self.n.put_demand(BUYER, demand(), self.c.t)
        d = "dem-0000000001"
        self.assertTrue(not self.n.set_demand_status(d, network.CONNECTED, self.c.t, "no"),
                        "nothing may jump straight to connected")
        self.assertEqual(network.CREATED, self.n.demand(d)["status"])
        self.assertTrue(self.n.set_demand_status(d, network.SEARCHING, self.c.t))
        self.assertTrue(not self.n.set_demand_status(d, network.CONNECTED, self.c.t, "no"))


class MatchingTest(unittest.TestCase):
    def setUp(self):
        self.n = plane()
        self.c = Clock()

    def add(self, who, **kw):
        self.n.put_presence(who, presence(**kw), self.c.t)

    def test_a_demand_with_no_provider_is_searching_not_failed(self):
        self.n.put_demand(BUYER, demand(), self.c.t)
        out = self.n.serve("dem-0000000001", self.c.t)
        self.assertEqual(network.SEARCHING, out["status"])
        self.assertEqual(0, out["candidates"])

    def test_the_whole_loop_from_demand_to_connected(self):
        self.add(SELLER)
        self.n.put_demand(BUYER, demand(), self.c.t)
        served = self.n.serve("dem-0000000001", self.c.t)
        self.assertEqual(network.ACTIVATION_SENT, served["status"])
        aid = served["activationId"]

        self.assertEqual(network.ACCEPTED,
                         self.n.answer(aid, SELLER, True, self.c.tick(2_000))["state"])
        self.assertEqual(network.PROVIDER_ACCEPTED, self.n.demand("dem-0000000001")["status"])

        # the phones then meet, and the transport says so
        self.n.report(aid, SELLER, network.LOCAL_LINK_SEEN, self.c.tick(3_000))
        self.assertEqual(network.WAITING_FOR_LOCAL_LINK,
                         self.n.demand("dem-0000000001")["status"])
        out = self.n.report(aid, BUYER, network.ACT_CONNECTED, self.c.tick(2_000))
        self.assertEqual(network.CONNECTED, self.n.demand("dem-0000000001")["status"])
        self.assertEqual(network.ACT_CONNECTED, out["state"])

    def test_free_beats_cheap_commercial(self):
        self.add(SELLER, freeReady=1, commercialReady=0, priceHintInternal=-1)
        self.add(OTHER, priceHintInternal=1)
        self.n.put_demand(BUYER, demand(), self.c.t)
        best = self.n.candidates("dem-0000000001", self.c.t)[0]
        self.assertEqual(SELLER, best[0].provider_id)
        self.assertEqual(network.FREE, best[1])

    def test_the_cheapest_commercial_offer_wins_among_equals(self):
        self.add(SELLER, priceHintInternal=500)
        self.add(OTHER, priceHintInternal=200)
        self.n.put_demand(BUYER, demand(), self.c.t)
        self.assertEqual(OTHER, self.n.candidates("dem-0000000001", self.c.t)[0][0].provider_id)

    def test_a_buyer_asking_for_free_is_never_silently_charged(self):
        self.add(SELLER, priceHintInternal=200)     # commercial only
        self.n.put_demand(BUYER, demand(requestedClass=network.FREE), self.c.t)
        self.assertEqual([], self.n.candidates("dem-0000000001", self.c.t))

    def test_a_provider_at_capacity_is_not_offered_work(self):
        self.add(SELLER, currentLoad=2, maxBuyers=2)
        self.n.put_demand(BUYER, demand(), self.c.t)
        self.assertEqual([], self.n.candidates("dem-0000000001", self.c.t))

    def test_an_unpriced_commercial_offer_is_not_a_candidate(self):
        # it cannot be compared with anything, so it cannot be chosen honestly
        self.add(SELLER, priceHintInternal=-1)
        self.n.put_demand(BUYER, demand(), self.c.t)
        self.assertEqual([], self.n.candidates("dem-0000000001", self.c.t))

    def test_a_phone_is_never_matched_to_itself(self):
        self.add(BUYER)
        self.n.put_demand(BUYER, demand(), self.c.t)
        self.assertEqual([], self.n.candidates("dem-0000000001", self.c.t))

    def test_a_provider_in_another_zone_is_not_a_candidate(self):
        self.add(SELLER, zone="z9999:9999")
        self.n.put_demand(BUYER, demand(), self.c.t)
        self.assertEqual([], self.n.candidates("dem-0000000001", self.c.t))

    def test_selection_is_deterministic(self):
        # same state, same answer, every time: a pilot has to be able to explain why a
        # particular phone rang
        for who in (SELLER, OTHER, "d" * 32):
            self.add(who, priceHintInternal=300)
        self.n.put_demand(BUYER, demand(), self.c.t)
        first = [c[0].provider_id for c in self.n.candidates("dem-0000000001", self.c.t)]
        for _ in range(5):
            self.assertEqual(first, [c[0].provider_id
                                     for c in self.n.candidates("dem-0000000001", self.c.t)])
        self.assertEqual(sorted(first), first, "the last tie-break is the provider id")

    def test_a_more_reliable_provider_is_preferred_at_the_same_price(self):
        self.add(SELLER, priceHintInternal=300)
        self.add(OTHER, priceHintInternal=300)
        # OTHER has a record of actually working
        for _ in range(3):
            self.n._bump(OTHER, "connected", self.c.t, success=True)
        self.n._bump(SELLER, "failed", self.c.t)
        self.n.db.commit()
        self.n.put_demand(BUYER, demand(), self.c.t)
        self.assertEqual(OTHER, self.n.candidates("dem-0000000001", self.c.t)[0][0].provider_id)

    def test_a_new_provider_is_not_punished_for_being_new(self):
        self.assertEqual(0.5, self.n.score("someone-unseen"),
                         "a network that only suggests providers it has used cannot grow")


class ActivationTest(unittest.TestCase):
    def setUp(self):
        self.n = plane()
        self.c = Clock()
        self.n.put_presence(SELLER, presence(), self.c.t)
        self.n.put_demand(BUYER, demand(), self.c.t)
        self.aid = self.n.serve("dem-0000000001", self.c.t)["activationId"]

    def test_only_the_provider_it_was_offered_to_may_answer(self):
        with self.assertRaises(network.NetworkError):
            self.n.answer(self.aid, OTHER, True, self.c.tick(1_000))

    def test_accepting_twice_is_not_an_error_and_counts_once(self):
        self.n.answer(self.aid, SELLER, True, self.c.tick(1_000))
        out = self.n.answer(self.aid, SELLER, True, self.c.tick(1_000))
        self.assertTrue(out.get("duplicate"))
        self.assertEqual(1, int(self.n.reliability(SELLER)["accepted"]))

    def test_an_unanswered_offer_expires_and_the_search_resumes(self):
        self.c.tick(network.ACTIVATION_TTL_MS + 1)
        self.n.sweep(self.c.t)
        self.assertEqual(network.ACT_EXPIRED, self.n.activation(self.aid)["state"])
        self.assertEqual(network.SEARCHING, self.n.demand("dem-0000000001")["status"],
                         "the buyer must not be left waiting on somebody who went quiet")

    def test_a_decline_frees_the_demand_for_the_next_candidate(self):
        self.n.put_presence(OTHER, presence(priceHintInternal=400), self.c.t)
        self.n.answer(self.aid, SELLER, False, self.c.tick(1_000))
        self.assertEqual(network.DECLINED, self.n.activation(self.aid)["state"])
        nxt = self.n.serve("dem-0000000001", self.c.t)
        self.assertEqual(network.ACTIVATION_SENT, nxt["status"])
        self.assertEqual(OTHER, self.n.activation(nxt["activationId"])["provider_id"],
                         "the one who said no is not asked again for this request")

    def test_only_one_activation_is_live_at_a_time(self):
        with self.assertRaises(network.NetworkError):
            self.n.offer("dem-0000000001", OTHER, self.c.tick(1_000))

    def test_a_demand_gives_up_after_a_bounded_number_of_attempts(self):
        # twenty phones buzzing for one small request is how a network makes itself
        # unwelcome
        self.n.answer(self.aid, SELLER, False, self.c.tick(1_000))
        for i in range(network.MAX_ACTIVATION_ATTEMPTS):
            who = chr(ord("e") + i) * 32
            self.n.put_presence(who, presence(priceHintInternal=300 + i), self.c.t)
            out = self.n.serve("dem-0000000001", self.c.t)
            if out.get("status") == network.FAILED:
                break
            self.n.answer(out["activationId"], who, False, self.c.tick(1_000))
        self.assertEqual(network.FAILED, self.n.demand("dem-0000000001")["status"])
        self.assertLessEqual(int(self.n.demand("dem-0000000001")["attempts"]),
                             network.MAX_ACTIVATION_ATTEMPTS)

    def test_cancelling_a_demand_closes_the_offer_the_provider_can_see(self):
        self.n.cancel_demand("dem-0000000001", BUYER, self.c.tick(1_000))
        self.assertEqual([], self.n.jobs_for_provider(SELLER, self.c.t),
                         "a provider must not go on seeing a request nobody is waiting on")

    def test_an_answer_after_the_window_closed_is_refused(self):
        self.c.tick(network.ACTIVATION_TTL_MS + 1)
        with self.assertRaises(network.NetworkError):
            self.n.answer(self.aid, SELLER, True, self.c.t)
        self.assertEqual(network.ACT_EXPIRED, self.n.activation(self.aid)["state"])


class ReportTest(unittest.TestCase):
    def setUp(self):
        self.n = plane()
        self.c = Clock()
        self.n.put_presence(SELLER, presence(), self.c.t)
        self.n.put_demand(BUYER, demand(), self.c.t)
        self.aid = self.n.serve("dem-0000000001", self.c.t)["activationId"]
        self.n.answer(self.aid, SELLER, True, self.c.tick(1_000))

    def test_a_stranger_cannot_report_on_somebody_elses_activation(self):
        with self.assertRaises(network.NetworkError):
            self.n.report(self.aid, OTHER, network.ACT_CONNECTED, self.c.tick(1_000))

    def test_a_provider_cannot_invent_a_result_for_another_demand(self):
        self.n.put_demand(OTHER, demand("dem-0000000002"), self.c.t)
        with self.assertRaises(network.NetworkError):
            self.n.report("act-dem-0000000002-1", SELLER, network.ACT_CONNECTED, self.c.t)

    def test_both_sides_agreeing_is_recorded_as_stronger(self):
        self.n.report(self.aid, SELLER, network.ACT_CONNECTED, self.c.tick(1_000))
        out = self.n.report(self.aid, BUYER, network.ACT_CONNECTED, self.c.tick(1_000))
        self.assertTrue(out["corroborated"])

    def test_one_side_alone_is_still_accepted_but_not_corroborated(self):
        out = self.n.report(self.aid, BUYER, network.ACT_CONNECTED, self.c.tick(1_000))
        self.assertEqual(network.ACT_CONNECTED, out["state"])
        self.assertFalse(out["corroborated"])

    def test_a_failure_puts_the_demand_back_to_searching(self):
        self.n.report(self.aid, BUYER, network.ACT_FAILED, self.c.tick(1_000))
        self.assertEqual(network.SEARCHING, self.n.demand("dem-0000000001")["status"])
        self.assertEqual(1, int(self.n.reliability(SELLER)["failed"]))

    def test_an_unknown_result_is_refused(self):
        with self.assertRaises(network.NetworkError):
            self.n.report(self.aid, BUYER, "GREAT", self.c.tick(1_000))

    def test_reporting_changes_no_money(self):
        """The line this whole module must not cross."""
        before = [r[0] for r in self.n.db.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")]
        self.n.report(self.aid, BUYER, network.ACT_CONNECTED, self.c.tick(1_000))
        # the control plane writes only to its own tables
        touched = set(r["kind"].split(".")[0] for r in self.n.events(20))
        self.assertTrue(touched <= {"demand", "activation"}, touched)
        self.assertEqual(before, [r[0] for r in self.n.db.execute(
            "SELECT name FROM sqlite_master WHERE type='table'")])


class ZoneTest(unittest.TestCase):
    def setUp(self):
        self.n = plane()
        self.c = Clock()

    def test_no_provider_is_red(self):
        self.assertEqual(network.RED, self.n.zone_status(ZONE, self.c.t)["status"])

    def test_a_fresh_candidate_with_no_history_is_yellow(self):
        self.n.put_presence(SELLER, presence(), self.c.t)
        self.assertEqual(network.YELLOW, self.n.zone_status(ZONE, self.c.t)["status"],
                         "somebody could probably be woken; nobody is promising Internet")

    def test_a_fresh_provider_and_a_recent_success_is_green(self):
        self.n.put_presence(SELLER, presence(), self.c.t)
        self.n.put_demand(BUYER, demand(), self.c.t)
        aid = self.n.serve("dem-0000000001", self.c.t)["activationId"]
        self.n.answer(aid, SELLER, True, self.c.tick(1_000))
        self.n.report(aid, BUYER, network.ACT_CONNECTED, self.c.tick(1_000))
        self.assertEqual(network.GREEN, self.n.zone_status(ZONE, self.c.t)["status"])

    def test_green_does_not_survive_the_provider_leaving(self):
        # a zone cannot stay green because somebody was there yesterday
        self.n.put_presence(SELLER, presence(), self.c.t)
        self.n.put_demand(BUYER, demand(), self.c.t)
        aid = self.n.serve("dem-0000000001", self.c.t)["activationId"]
        self.n.answer(aid, SELLER, True, self.c.tick(1_000))
        self.n.report(aid, BUYER, network.ACT_CONNECTED, self.c.tick(1_000))
        self.assertEqual(network.GREEN, self.n.zone_status(ZONE, self.c.t)["status"])
        self.c.tick(network.PRESENCE_TTL_MS + 1)
        self.assertEqual(network.RED, self.n.zone_status(ZONE, self.c.t)["status"])

    def test_green_does_not_survive_the_success_ageing_out(self):
        self.n.put_presence(SELLER, presence(), self.c.t)
        self.n.put_demand(BUYER, demand(), self.c.t)
        aid = self.n.serve("dem-0000000001", self.c.t)["activationId"]
        self.n.answer(aid, SELLER, True, self.c.tick(1_000))
        self.n.report(aid, BUYER, network.ACT_CONNECTED, self.c.tick(1_000))
        # a day later, with the provider still heartbeating
        self.c.tick(25 * 3600 * 1000)
        self.n.put_presence(SELLER, presence(), self.c.t)
        self.assertEqual(network.YELLOW, self.n.zone_status(ZONE, self.c.t)["status"])


class PersistenceTest(unittest.TestCase):
    """Item 60: a Brain restart must not lose who is waiting for whom, and must not
    pretend stale presence is fresh."""

    def test_state_survives_a_reopen_and_ttl_still_applies(self):
        import os
        import sqlite3
        import tempfile
        path = os.path.join(tempfile.mkdtemp(), "brain.db")
        c = Clock()

        db = sqlite3.connect(path)
        db.row_factory = sqlite3.Row
        n = network.NetworkPlane(db)
        n.put_presence(SELLER, presence(), c.t)
        n.put_demand(BUYER, demand(), c.t)
        aid = n.serve("dem-0000000001", c.t)["activationId"]
        n.answer(aid, SELLER, True, c.tick(1_000))
        db.commit()
        db.close()

        # the process dies and comes back
        db2 = sqlite3.connect(path)
        db2.row_factory = sqlite3.Row
        n2 = network.NetworkPlane(db2)
        self.assertEqual(network.PROVIDER_ACCEPTED, n2.demand("dem-0000000001")["status"])
        self.assertEqual(network.ACCEPTED, n2.activation(aid)["state"])
        self.assertEqual(1, int(n2.reliability(SELLER)["accepted"]))

        # and a presence from before the restart is judged by its own TTL, not refreshed
        c.tick(network.PRESENCE_TTL_MS + 1)
        self.assertEqual([], n2.fresh_in_zone(ZONE, c.t),
                         "old timestamps must not be treated as fresh after a restart")


class SweepTest(unittest.TestCase):
    def test_sweeping_is_deterministic_and_reports_what_it_did(self):
        n = plane()
        c = Clock()
        n.put_presence(SELLER, presence(), c.t)
        n.put_demand(BUYER, demand(), c.t)
        n.serve("dem-0000000001", c.t)
        c.tick(network.DEMAND_TTL_MS + 1)
        out = n.sweep(c.t)
        self.assertEqual(1, out.get("activations"))
        self.assertEqual(1, out.get("demands"))
        self.assertEqual({}, n.sweep(c.t), "a second sweep has nothing left to do")

    def test_old_events_are_forgotten_but_recent_ones_are_not(self):
        n = plane()
        c = Clock()
        n.put_demand(BUYER, demand(), c.t)
        n.db.commit()
        old_ids = [e["id"] for e in n.events(5)]
        self.assertTrue(old_ids)
        c.tick(network.EVENT_KEEP_MS + 1)
        n.sweep(c.t)
        left = n.events(5)
        for e in left:
            self.assertNotIn(e["id"], old_ids, "the old audit line must be gone")
            self.assertGreaterEqual(int(e["at"]), c.t - 1_000,
                                    "what remains is what the sweep itself wrote")


if __name__ == "__main__":
    unittest.main()
