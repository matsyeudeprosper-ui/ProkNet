"""v0.17.1: the parts of the loop v0.17.0 left unfinished.

Each class here corresponds to something a buyer or a provider would actually have
noticed on a phone, not to an internal refactor.
"""
import sqlite3
import unittest

from brain import network


def plane():
    db = sqlite3.connect(":memory:")
    db.row_factory = sqlite3.Row
    return network.NetworkPlane(db)


BUYER = "b" * 32
BUYER2 = "e" * 32
SELLER = "a" * 32
SELLER2 = "c" * 32
ZONE = "z1:1"
FAR = "z9:9"
T0 = 1_700_000_000_000


def presence(zone=ZONE, **kw):
    body = {"zone": zone, "upstreamAvailable": 1, "sharingEnabled": 1,
            "commercialReady": 1, "currentLoad": 0, "maxBuyers": 1,
            "offerClass": network.COMMERCIAL, "priceHintInternal": 300}
    body.update(kw)
    return body


def demand(demand_id="dem-00000001", zone=ZONE, **kw):
    body = {"demandId": demand_id, "zone": zone, "budgetCentimes": 5_000,
            "requestedClass": network.COMMERCIAL}
    body.update(kw)
    return body


class AcceptedWaitWindowTest(unittest.TestCase):
    """Items 16-20. v0.17.0 gave an accepted provider the 75-second window it had to
    ANSWER in, so it vanished a minute later while the two people were still walking
    towards each other - and the buyer's screen gave up for no reason they could see."""

    def setUp(self):
        self.n = plane()
        self.n.put_presence(SELLER, presence(), T0)
        self.n.put_demand(BUYER, demand(), T0)
        self.aid = self.n.serve("dem-00000001", T0)["activationId"]

    def test_an_unanswered_offer_still_dies_quickly(self):
        # the buyer is standing there; a silent provider must not hold the request
        self.n.sweep(T0 + network.OFFER_TTL_MS + 1)
        self.assertEqual(network.ACT_EXPIRED, self.n.activation(self.aid)["state"])

    def test_accepting_buys_a_real_window(self):
        self.n.answer(self.aid, SELLER, True, T0 + 1_000)
        a = self.n.activation(self.aid)
        self.assertEqual(T0 + 1_000, int(a["accepted_at"]))
        self.assertGreater(int(a["link_deadline"]), T0 + network.OFFER_TTL_MS,
                           "the offer window is not the walking window")

    def test_an_accepted_provider_survives_the_old_seventy_five_seconds(self):
        self.n.answer(self.aid, SELLER, True, T0 + 1_000)
        later = T0 + network.OFFER_TTL_MS + 60_000
        self.n.sweep(later)
        self.assertEqual(network.ACCEPTED, self.n.activation(self.aid)["state"],
                         "this is exactly what v0.17.0 got wrong")
        self.assertEqual(network.PROVIDER_ACCEPTED, self.n.demand("dem-00000001")["status"])
        self.assertEqual(1, len(self.n.jobs_for_provider(SELLER, later)),
                         "and the provider can still see the job it agreed to")

    def test_the_window_never_outlives_the_request_it_serves(self):
        # item 18: min(demand expiry, acceptedAt + wait)
        self.n.answer(self.aid, SELLER, True, T0 + 1_000)
        a = self.n.activation(self.aid)
        d = self.n.demand("dem-00000001")
        self.assertLessEqual(int(a["link_deadline"]), int(d["expires_at"]))

    def test_the_window_does_end(self):
        self.n.answer(self.aid, SELLER, True, T0 + 1_000)
        self.n.sweep(T0 + network.LINK_WAIT_TTL_MS + 5_000)
        self.assertEqual(network.ACT_EXPIRED, self.n.activation(self.aid)["state"])

    def test_link_seen_keeps_the_longer_window_too(self):
        self.n.answer(self.aid, SELLER, True, T0 + 1_000)
        self.n.report(self.aid, SELLER, network.LOCAL_LINK_SEEN, T0 + 2_000)
        self.n.sweep(T0 + network.OFFER_TTL_MS + 60_000)
        self.assertEqual(network.LOCAL_LINK_SEEN, self.n.activation(self.aid)["state"])

    def test_answering_after_the_long_window_is_refused(self):
        self.n.answer(self.aid, SELLER, True, T0 + 1_000)
        with self.assertRaises(network.NetworkError):
            self.n.answer(self.aid, SELLER, False,
                          T0 + network.LINK_WAIT_TTL_MS + 60_000)


class FallbackAfterAcceptedTimeoutTest(unittest.TestCase):
    """Item 19-20. A provider that agreed and then never turned up must not leave the
    buyer looking at "un fournisseur se prÃ©pare" for ever."""

    def setUp(self):
        self.n = plane()
        self.n.put_presence(SELLER, presence(priceHintInternal=200), T0)
        self.n.put_presence(SELLER2, presence(priceHintInternal=400), T0)
        self.n.put_demand(BUYER, demand(), T0)
        self.aid = self.n.serve("dem-00000001", T0)["activationId"]
        self.n.answer(self.aid, SELLER, True, T0 + 1_000)

    def test_the_search_resumes_when_the_phones_never_meet(self):
        self.n.sweep(T0 + network.LINK_WAIT_TTL_MS + 5_000)
        self.assertEqual(network.SEARCHING, self.n.demand("dem-00000001")["status"])

    def test_and_the_next_provider_is_tried(self):
        # this is why DEMAND_TTL is longer than LINK_WAIT: with both at ten minutes an
        # accepted provider swallowed the whole request and this could never happen
        after = T0 + network.LINK_WAIT_TTL_MS + 5_000
        self.n.sweep(after)
        self.assertEqual(network.SEARCHING, self.n.demand("dem-00000001")["status"])
        # ten minutes have passed, so both providers have heartbeated many times
        # since. A presence from T0 is long stale, and matching a stale provider is
        # the one thing this whole module exists to avoid.
        self.n.put_presence(SELLER, presence(priceHintInternal=200), after)
        self.n.put_presence(SELLER2, presence(priceHintInternal=400), after)
        out = self.n.serve("dem-00000001", after + 1_000)
        self.assertEqual(network.ACTIVATION_SENT, out["status"])
        self.assertEqual(SELLER2, self.n.activation(out["activationId"])["provider_id"],
                         "the one who agreed and did not turn up is not asked again")

    def test_a_request_with_no_time_left_simply_ends(self):
        # and when there is no room for another attempt, the honest answer is that the
        # request ran out - not an endless "un fournisseur se prepare"
        self.n.sweep(T0 + network.DEMAND_TTL_MS + 1_000)
        self.assertEqual(network.EXPIRED, self.n.demand("dem-00000001")["status"])


class LateProviderTest(unittest.TestCase):
    """Item 26. v0.17.0 only matched when a demand was created, so a buyer who asked
    before anybody was sharing stayed SEARCHING even once a provider woke up beside
    them. This is the other half of the loop."""

    def setUp(self):
        self.n = plane()

    def test_a_provider_that_appears_later_is_given_the_waiting_work(self):
        self.n.put_demand(BUYER, demand(), T0)
        self.assertEqual(network.SEARCHING, self.n.serve("dem-00000001", T0)["status"])

        out = self.n.put_presence(SELLER, presence(), T0 + 30_000)
        self.assertIn("served", out, "the heartbeat should have picked the work up")
        self.assertEqual(network.ACTIVATION_SENT, self.n.demand("dem-00000001")["status"])

    def test_a_provider_appearing_in_another_zone_serves_nobody(self):
        self.n.put_demand(BUYER, demand(), T0)
        out = self.n.put_presence(SELLER, presence(zone=FAR), T0 + 30_000)
        self.assertNotIn("served", out)
        self.assertEqual(network.CREATED, self.n.demand("dem-00000001")["status"],
                         "nothing touched it, so it has not even started searching")

    def test_capacity_coming_back_picks_up_waiting_work(self):
        # item 29: the seller finishes a session and becomes useful again
        self.n.put_presence(SELLER, presence(currentLoad=1, maxBuyers=1), T0)
        self.n.put_demand(BUYER, demand(), T0)
        self.assertEqual(network.SEARCHING, self.n.serve("dem-00000001", T0)["status"])
        out = self.n.put_presence(SELLER, presence(currentLoad=0), T0 + 60_000)
        self.assertIn("served", out)

    def test_a_full_provider_is_given_nothing(self):
        self.n.put_demand(BUYER, demand(), T0)
        out = self.n.put_presence(SELLER, presence(currentLoad=1, maxBuyers=1), T0 + 1_000)
        self.assertNotIn("served", out)

    def test_a_heartbeat_with_nothing_waiting_is_still_just_a_heartbeat(self):
        out = self.n.put_presence(SELLER, presence(), T0)
        self.assertTrue(out["ok"])
        self.assertNotIn("served", out)


class FairnessTest(unittest.TestCase):
    """Item 27. Somebody who has been waiting eight minutes must not watch newer
    arrivals get served ahead of them."""

    def test_the_oldest_waiting_request_is_served_first(self):
        n = plane()
        n.put_demand(BUYER, demand("dem-first-0001"), T0)
        n.serve("dem-first-0001", T0)
        n.put_demand(BUYER2, demand("dem-second-001"), T0 + 60_000)
        n.serve("dem-second-001", T0 + 60_000)

        out = n.put_presence(SELLER, presence(), T0 + 120_000)
        self.assertIn("served", out)
        a = n.activation(out["served"])
        self.assertEqual("dem-first-0001", a["demand_id"])
        self.assertEqual(network.SEARCHING, n.demand("dem-second-001")["status"])

    def test_and_the_next_provider_serves_the_next_one(self):
        n = plane()
        n.put_demand(BUYER, demand("dem-first-0001"), T0)
        n.put_demand(BUYER2, demand("dem-second-001"), T0 + 60_000)
        n.put_presence(SELLER, presence(), T0 + 120_000)
        n.put_presence(SELLER2, presence(), T0 + 121_000)
        self.assertEqual(network.ACTIVATION_SENT, n.demand("dem-first-0001")["status"])
        self.assertEqual(network.ACTIVATION_SENT, n.demand("dem-second-001")["status"])

    def test_a_demand_out_of_attempts_is_skipped_not_retried_for_ever(self):
        n = plane()
        n.put_demand(BUYER, demand(), T0)
        n.db.execute("UPDATE network_demand SET attempts=? WHERE demand_id=?",
                     (network.MAX_ACTIVATION_ATTEMPTS, "dem-00000001"))
        n.db.commit()
        self.assertIsNone(n.oldest_waiting_demand(ZONE, T0 + 1_000))


class ZoneMovementTest(unittest.TestCase):
    """Items 24-25. People walk around while they are waiting."""

    def setUp(self):
        self.n = plane()

    def test_a_buyer_that_walks_takes_its_request_with_it(self):
        # the policy, stated: the SAME demand follows the buyer. Recreating it would
        # reset their place in the queue and burn the cooldown.
        self.n.put_presence(SELLER, presence(zone=FAR), T0)
        self.n.put_demand(BUYER, demand(zone=ZONE), T0)
        self.assertEqual(network.SEARCHING, self.n.serve("dem-00000001", T0)["status"])

        out = self.n.move_demand("dem-00000001", BUYER, FAR, T0 + 30_000)
        self.assertEqual(FAR, out["zone"])
        self.assertEqual("dem-00000001", self.n.demand("dem-00000001")["demand_id"],
                         "same request, same age, same attempt count")
        self.assertEqual(network.ACTIVATION_SENT, self.n.demand("dem-00000001")["status"])

    def test_moving_closes_an_offer_left_behind(self):
        self.n.put_presence(SELLER, presence(zone=ZONE), T0)
        self.n.put_demand(BUYER, demand(zone=ZONE), T0)
        aid = self.n.serve("dem-00000001", T0)["activationId"]
        self.n.move_demand("dem-00000001", BUYER, FAR, T0 + 30_000)
        self.assertEqual(network.ACT_EXPIRED, self.n.activation(aid)["state"],
                         "a provider in the old area can no longer help")
        self.assertEqual([], self.n.jobs_for_provider(SELLER, T0 + 31_000))

    def test_only_the_buyer_may_move_its_own_request(self):
        self.n.put_demand(BUYER, demand(), T0)
        with self.assertRaises(network.NetworkError):
            self.n.move_demand("dem-00000001", BUYER2, FAR, T0 + 1_000)

    def test_moving_to_the_same_zone_changes_nothing(self):
        self.n.put_demand(BUYER, demand(), T0)
        out = self.n.move_demand("dem-00000001", BUYER, ZONE, T0 + 1_000)
        self.assertTrue(out.get("duplicate"))

    def test_a_provider_that_walks_away_loses_an_unanswered_offer(self):
        self.n.put_presence(SELLER, presence(zone=ZONE), T0)
        self.n.put_demand(BUYER, demand(zone=ZONE), T0)
        aid = self.n.serve("dem-00000001", T0)["activationId"]
        self.n.put_presence(SELLER, presence(zone=FAR), T0 + 30_000)
        self.assertEqual(network.ACT_EXPIRED, self.n.activation(aid)["state"])
        self.assertEqual(network.SEARCHING, self.n.demand("dem-00000001")["status"],
                         "the buyer's search resumes rather than waiting on somebody gone")

    def test_a_provider_that_walks_away_after_accepting_keeps_the_job(self):
        # they may well be walking TOWARDS the buyer, which is what the window is for
        self.n.put_presence(SELLER, presence(zone=ZONE), T0)
        self.n.put_demand(BUYER, demand(zone=ZONE), T0)
        aid = self.n.serve("dem-00000001", T0)["activationId"]
        self.n.answer(aid, SELLER, True, T0 + 10_000)
        self.n.put_presence(SELLER, presence(zone=FAR), T0 + 30_000)
        self.assertEqual(network.ACCEPTED, self.n.activation(aid)["state"])


class SchemaThreeTest(unittest.TestCase):
    """Item 45. Additive, numbered, and not smuggled into schema 2."""

    def test_a_schema_two_database_gains_the_columns_and_keeps_its_rows(self):
        import os
        import tempfile
        from brain import db as braindb

        path = os.path.join(tempfile.mkdtemp(), "brain.db")
        con = sqlite3.connect(path)
        con.row_factory = sqlite3.Row
        # exactly what v0.17.0 left behind: migrations 1 and 2, and nothing else
        con.executescript(braindb.MIGRATIONS[0])
        con.executescript(braindb.MIGRATIONS[1])
        con.execute("INSERT INTO schema_version(version) VALUES (1)")
        con.execute("INSERT INTO schema_version(version) VALUES (2)")
        con.execute(
            "INSERT INTO network_activation(activation_id, demand_id, buyer_id, provider_id,"
            " zone, created_at, expires_at, updated_at, state) VALUES(?,?,?,?,?,?,?,?,?)",
            ("act-old-1", "dem-old-1", BUYER, SELLER, ZONE, T0, T0 + 75_000, T0,
             network.ACCEPTED))
        con.commit()
        cols_before = {r[1] for r in con.execute("PRAGMA table_info(network_activation)")}
        self.assertNotIn("link_deadline", cols_before, "the fixture must be a v0.17.0 shape")
        con.close()

        b = braindb.Brain(path)
        self.assertEqual(6, b.schema_version())
        cols = {r[1] for r in b.db.execute("PRAGMA table_info(network_activation)")}
        self.assertIn("accepted_at", cols)
        self.assertIn("link_deadline", cols)
        row = b.db.execute("SELECT * FROM network_activation WHERE activation_id=?",
                           ("act-old-1",)).fetchone()
        self.assertIsNotNone(row, "no activation may be lost by the upgrade")
        self.assertEqual(network.ACCEPTED, row["state"])
        self.assertEqual(0, int(row["link_deadline"]),
                         "an old accepted row falls back to its offer deadline")
        b.db.close()

    def test_an_upgraded_activation_table_matches_a_fresh_one(self):
        import os
        import tempfile
        from brain import db as braindb

        old = os.path.join(tempfile.mkdtemp(), "old.db")
        con = sqlite3.connect(old)
        con.executescript(braindb.MIGRATIONS[0])
        con.executescript(braindb.MIGRATIONS[1])
        con.execute("INSERT INTO schema_version(version) VALUES (1)")
        con.execute("INSERT INTO schema_version(version) VALUES (2)")
        con.commit(); con.close()
        up = braindb.Brain(old)

        fresh = braindb.Brain(os.path.join(tempfile.mkdtemp(), "fresh.db"))
        shape = lambda b: [(r[1], r[2], r[3], r[4], r[5]) for r in
                           b.db.execute("PRAGMA table_info(network_activation)")]
        self.assertEqual(shape(fresh), shape(up),
                         "an upgraded Brain and a fresh one must be the same shape")
        up.db.close(); fresh.db.close()


class BrainRestartTest(unittest.TestCase):
    """Item 47. A restart must not lose who is waiting, nor refresh what is stale."""

    def test_an_accepted_window_survives_a_restart(self):
        import os
        import tempfile
        path = os.path.join(tempfile.mkdtemp(), "brain.db")

        db = sqlite3.connect(path); db.row_factory = sqlite3.Row
        n = network.NetworkPlane(db)
        n.put_presence(SELLER, presence(), T0)
        n.put_demand(BUYER, demand(), T0)
        aid = n.serve("dem-00000001", T0)["activationId"]
        n.answer(aid, SELLER, True, T0 + 1_000)
        db.commit(); db.close()

        db2 = sqlite3.connect(path); db2.row_factory = sqlite3.Row
        n2 = network.NetworkPlane(db2)
        a = n2.activation(aid)
        self.assertEqual(network.ACCEPTED, a["state"])
        self.assertEqual(T0 + 1_000, int(a["accepted_at"]))
        # still inside its window after the restart
        self.assertEqual(1, len(n2.jobs_for_provider(SELLER, T0 + 5 * 60_000)))
        # and presence is still judged by its own clock
        self.assertEqual([], n2.fresh_in_zone(ZONE, T0 + network.PRESENCE_TTL_MS + 1))


if __name__ == "__main__":
    unittest.main()
