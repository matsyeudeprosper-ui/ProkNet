"""v0.19.0: the free Internet finder.

The rules under test: a sighting is never a place; a venue is published only with consent,
access rule, hours, one DEVICE check and a second independent check; the status words come
from one table on both sides and turn over at exactly 5 minutes, 24 hours and 7 days; two
independent failures or one operator closure demote; owner removal and safety reports hide
at once; scout rewards are 10 / 3 FCFA of promo credit with the §10.2 caps, once per venue
ever for the first find, once per 7 days for a refresh, expiring at 90 days, appealable
for 30.
"""
import os
import sqlite3
import unittest

from brain import places
from brain.places import (CAP_DAY, CAP_GLOBAL, CAP_MONTH, CLAIM, CLOSURE, CORRECTION, DEVICE, DRAFT, EARNED, EXPIRED, FAILURE,
                          FIRST, FREE_AFTER_SIGNIN, FREE_OPEN, HIDDEN, OLDER_CHECK, OPERATOR, PENDING, PENDING_REVIEW, PUBLISHED,
                          RECENTLY_VERIFIED, REFRESH, REJECTED, REMOVAL, REMOVED, REPORTED, SAFETY, STATUS_TEXT, STATUSES,
                          UNAVAILABLE, UNVERIFIED_STALE, VISITOR, WORKING_NOW, Places, PlacesError, status)

MIN = 60_000
H = 3_600_000
DAY = 24 * H
#: 2023-11-15 00:00:00 UTC (a Wednesday; 01:00 in Brazzaville). Day and month caps count
#: UTC calendar periods, so the tests start a day at its first millisecond.
T0 = 1_700_006_400_000
OP = "aa" * 16
OP2 = "ab" * 16
C1 = "c1" * 16
C2 = "c2" * 16
SCOUT = "5c" * 16
OWNER = "0e" * 16
STRANGER = "ee" * 16
ALWAYS = {d: ["00:00", "23:59"] for d in places.DAYS}
LAT, LON = -4.2634, 15.2429           # Bacongo, roughly
CITY = "Brazzaville"


def fresh(ledger=None, operators=(OP, OP2)):
    db = sqlite3.connect(":memory:", check_same_thread=False)
    ops = set(operators)
    return Places(db, lambda who: who in ops, ledger)


class Recorder:
    """The ledger stub: records what would have been posted, nothing else."""
    def __init__(self):
        self.posts = []
    def __call__(self, now, kind, debit, credit, amount, ref, memo):
        self.posts.append({"now": now, "kind": kind, "debit": debit, "credit": credit, "amount": amount, "ref": ref, "memo": memo})


def draft(P, now, name="Café du Port", radio="ab" * 8, lat=LAT, lon=LON, neighbourhood="Bacongo", consent=True, access=FREE_OPEN, hours=ALWAYS, owner=""):
    out = P.venue_submit(OP, CITY, name, "café", lat, lon, now, neighbourhood=neighbourhood, radio_hash=radio, access_rule=access, hours=hours)
    vid = out["venue_id"]
    if consent:
        P.record_consent(OP, vid, "consent-form-2026-09", now, owner_id=owner)
    return vid


def publish(P, vid, now, checkers=(C1, C2)):
    for i, c in enumerate(checkers):
        P.check(c, vid, True, DEVICE, now + i)
    return P.venue_publish(OP, vid, now + len(checkers))


def chk(checker, at, ok=True, kind=DEVICE):
    return {"checker_id": checker, "at": at, "ok": 1 if ok else 0, "kind": kind}


def venue_row(state=PUBLISHED):
    return {"state": state}


class StatusRuleTest(unittest.TestCase):
    """`status` is pure; every boundary is a test."""

    def test_the_texts_are_exactly_the_shared_fixture(self):
        path = os.path.join(os.path.dirname(__file__), "fixtures", "place_status.txt")
        with open(path, encoding="utf-8") as f:
            lines = [l.strip() for l in f if l.strip() and not l.startswith("#")]
        fixture = dict(l.split("|", 1) for l in lines)
        self.assertEqual(fixture, STATUS_TEXT)
        self.assertEqual(set(STATUSES), set(STATUS_TEXT))
        self.assertEqual("Fonctionne maintenant", STATUS_TEXT[WORKING_NOW])
        self.assertEqual("Ancien contrôle — confirmez avant de vous déplacer", STATUS_TEXT[OLDER_CHECK])

    def test_working_now_needs_a_probe_under_five_minutes_at_an_open_venue(self):
        s = status(venue_row(), [chk(C1, T0)], T0 + places.WORKING_NOW_MS - 1, True)
        self.assertEqual(WORKING_NOW, s["status"])
        self.assertEqual(places.WORKING_NOW_MS - 1, s["status_age_ms"])
        self.assertEqual(T0, s["last_ok_at"])
        # exactly five minutes: it has expired
        self.assertEqual(RECENTLY_VERIFIED, status(venue_row(), [chk(C1, T0)], T0 + places.WORKING_NOW_MS, True)["status"])
        # closed, or hours unknown: not "working now" however fresh
        self.assertEqual(RECENTLY_VERIFIED, status(venue_row(), [chk(C1, T0)], T0 + 1, False)["status"])
        self.assertEqual(RECENTLY_VERIFIED, status(venue_row(), [chk(C1, T0)], T0 + 1, None)["status"])
        # a failure after the success contradicts it
        self.assertEqual(RECENTLY_VERIFIED, status(venue_row(), [chk(C1, T0), chk(C2, T0 + 1, ok=False)], T0 + 2, True)["status"])
        # an operator probe counts; a visitor's word does not make it "working now"
        self.assertEqual(WORKING_NOW, status(venue_row(), [chk(OP, T0, kind=OPERATOR)], T0 + 1, True)["status"])
        self.assertEqual(UNVERIFIED_STALE, status(venue_row(), [chk(C1, T0, kind=VISITOR)], T0 + 1, True)["status"])

    def test_twenty_four_hours_turns_recently_verified_into_older_check(self):
        self.assertEqual(RECENTLY_VERIFIED, status(venue_row(), [chk(C1, T0)], T0 + places.RECENT_MS - 1, True)["status"])
        self.assertEqual(OLDER_CHECK, status(venue_row(), [chk(C1, T0)], T0 + places.RECENT_MS, True)["status"])

    def test_seven_days_turns_older_check_into_stale_and_no_check_is_stale(self):
        self.assertEqual(OLDER_CHECK, status(venue_row(), [chk(C1, T0)], T0 + places.STALE_MS - 1, True)["status"])
        s = status(venue_row(), [chk(C1, T0)], T0 + places.STALE_MS, True)
        self.assertEqual(UNVERIFIED_STALE, s["status"])
        none = status(venue_row(), [], T0, True)
        self.assertEqual(UNVERIFIED_STALE, none["status"])
        self.assertEqual(-1, none["status_age_ms"])
        self.assertEqual(0, none["last_ok_at"])
        self.assertEqual(0, none["confidence"])

    def test_two_independent_failures_within_a_day_demote_and_a_later_success_restores(self):
        base = [chk(C1, T0)]
        one = base + [chk(STRANGER, T0 + H, ok=False, kind=VISITOR)]
        self.assertEqual(RECENTLY_VERIFIED, status(venue_row(), one, T0 + 2 * H, True)["status"])
        same_again = one + [chk(STRANGER, T0 + 2 * H, ok=False, kind=VISITOR)]
        self.assertEqual(RECENTLY_VERIFIED, status(venue_row(), same_again, T0 + 3 * H, True)["status"], "the same person twice is one failure")
        two = one + [chk(C2, T0 + 2 * H, ok=False, kind=VISITOR)]
        self.assertEqual(UNAVAILABLE, status(venue_row(), two, T0 + 3 * H, True)["status"])
        # failures age out of the 24 h window
        self.assertEqual(OLDER_CHECK, status(venue_row(), two, T0 + H + places.FAILURE_WINDOW_MS, True)["status"])
        # a success after the failures clears them
        back = two + [chk(C1, T0 + 4 * H)]
        self.assertEqual(RECENTLY_VERIFIED, status(venue_row(), back, T0 + 5 * H, True)["status"])

    def test_one_operator_confirmed_closure_demotes_alone(self):
        rows = [chk(C1, T0), chk(OP, T0 + H, ok=False, kind=OPERATOR)]
        self.assertEqual(UNAVAILABLE, status(venue_row(), rows, T0 + 2 * H, True)["status"])
        # and a device failure alone does not
        self.assertEqual(RECENTLY_VERIFIED, status(venue_row(), [chk(C1, T0), chk(C2, T0 + H, ok=False)], T0 + 2 * H, True)["status"])

    def test_hidden_or_removed_is_unavailable_whatever_the_checks_say(self):
        for st in (HIDDEN, REMOVED):
            self.assertEqual(UNAVAILABLE, status(venue_row(st), [chk(C1, T0)], T0 + 1, True)["status"], st)

    def test_pending_review_or_an_open_report_is_reported_unless_unavailable(self):
        self.assertEqual(REPORTED, status(venue_row(PENDING_REVIEW), [chk(C1, T0)], T0 + 1, True)["status"])
        self.assertEqual(REPORTED, status(venue_row(), [chk(C1, T0)], T0 + 1, True, open_reports=1)["status"])
        rows = [chk(C1, T0), chk(OP, T0 + H, ok=False, kind=OPERATOR)]
        self.assertEqual(UNAVAILABLE, status(venue_row(PENDING_REVIEW), rows, T0 + 2 * H, True)["status"])

    def test_confidence_grows_with_independent_checkers_and_freshness(self):
        one = status(venue_row(), [chk(C1, T0)], T0 + 1, True)["confidence"]
        two = status(venue_row(), [chk(C1, T0), chk(C2, T0)], T0 + 1, True)["confidence"]
        visitor = status(venue_row(), [chk(C1, T0), chk(C2, T0), chk(STRANGER, T0, kind=VISITOR)], T0 + 1, True)["confidence"]
        old = status(venue_row(), [chk(C1, T0), chk(C2, T0)], T0 + 2 * DAY, True)["confidence"]
        self.assertTrue(0 < one < two <= visitor <= 100)
        self.assertLess(old, two)
        self.assertEqual(0, status(venue_row(), [chk(C1, T0)], T0 + 30 * DAY, True)["confidence"])


class HoursAndCellsTest(unittest.TestCase):

    def test_open_at_uses_local_time_and_handles_overnight_ranges(self):
        # T0 is Wednesday 01:00 local
        hours = {"wed": ["08:00", "20:00"]}
        h = places.parse_hours(hours)
        self.assertFalse(places.open_at(h, T0))
        self.assertFalse(places.open_at(h, T0 + 7 * H - 1 * MIN))   # 07:59
        self.assertTrue(places.open_at(h, T0 + 7 * H))              # 08:00
        self.assertTrue(places.open_at(h, T0 + 19 * H - 1 * MIN))   # 19:59
        self.assertFalse(places.open_at(h, T0 + 19 * H))            # 20:00
        night = places.parse_hours({"tue": ["20:00", "02:00"]})
        self.assertTrue(places.open_at(night, T0), "Tuesday night runs into Wednesday 01:00")
        self.assertFalse(places.open_at(night, T0 + H))
        self.assertIsNone(places.open_at({}, T0))
        self.assertEqual({}, places.parse_hours("not json"))
        self.assertEqual({}, places.parse_hours({"mon": ["8h", "20h"]}))

    def test_the_cell_string_is_the_phones_zone_id(self):
        self.assertEqual("z-853:3048", places.cell_of(LAT, LON))
        self.assertEqual("z0:0", places.cell_of(0.0, 0.0))
        self.assertLess(abs(places.distance_m(LAT, LON, LAT + 0.001, LON) - 111.2), 1.0)

    def test_hours_line_is_flat_for_the_phone(self):
        line = places.hours_line(places.parse_hours({"mon": ["08:00", "20:00"]}))
        self.assertEqual("mon=08:00-20:00,tue=,wed=,thu=,fri=,sat=,sun=", line)


class PublicationTest(unittest.TestCase):

    def test_everything_in_place_publishes_and_the_entrance_becomes_visible(self):
        P = fresh()
        vid = draft(P, T0)
        with self.assertRaises(PlacesError) as cm:
            P.venue(vid, T0)
        self.assertEqual(404, cm.exception.code, "a draft's entrance is nobody's business")
        out = publish(P, vid, T0 + 1)
        self.assertEqual(PUBLISHED, out["state"])
        card = P.venue(vid, T0 + 10)
        self.assertEqual(LAT, card["lat"]); self.assertEqual(LON, card["lon"])
        self.assertEqual("Gratuit ici", card["access_text"])
        self.assertEqual("direct", card["direct_or_relay"])
        self.assertTrue(card["open_now"])
        self.assertEqual(WORKING_NOW, card["status"])

    def test_each_precondition_is_refused_alone(self):
        for missing in ("consent", "access", "hours", "device", "second", "same_checker", "consent_not_operator"):
            P = fresh()
            vid = draft(P, T0, consent=missing != "consent", access="" if missing == "access" else FREE_OPEN, hours=None if missing == "hours" else ALWAYS)
            if missing == "consent_not_operator":
                P.db.execute("UPDATE place_venues SET consent_recorded_by=? WHERE id=?", (STRANGER, vid)); P.db.commit()
            if missing != "device":
                P.check(C1, vid, True, DEVICE, T0 + 1)
                if missing == "same_checker":
                    P.check(C1, vid, True, DEVICE, T0 + 2)
                elif missing != "second":
                    P.check(C2, vid, True, DEVICE, T0 + 2)
            with self.assertRaises(PlacesError) as cm:
                P.venue_publish(OP, vid, T0 + 3)
            self.assertEqual(409, cm.exception.code, missing)
            expect = {"consent": places.NO_CONSENT, "consent_not_operator": places.NO_CONSENT, "access": places.NO_ACCESS_RULE, "hours": places.NO_HOURS,
                      "device": places.NO_DEVICE_CHECK, "second": places.NO_SECOND_CHECK, "same_checker": places.NO_SECOND_CHECK}[missing]
            self.assertEqual(expect, cm.exception.reason, missing)
            self.assertEqual(DRAFT, P._venue(vid)["state"])
            self.assertEqual(1, len(P.db.execute("SELECT * FROM place_audit WHERE allowed=0").fetchall()), "a refusal is an audit row")

    def test_the_second_check_may_be_an_operators_but_a_visitor_report_is_not_a_check(self):
        P = fresh()
        vid = draft(P, T0)
        P.check(C1, vid, True, DEVICE, T0 + 1)
        P.check(STRANGER, vid, True, VISITOR, T0 + 2)
        self.assertEqual([places.NO_SECOND_CHECK], P.publication_blockers(vid))
        P.check(OP, vid, True, OPERATOR, T0 + 3)
        self.assertEqual([], P.publication_blockers(vid))
        self.assertEqual(PUBLISHED, P.venue_publish(OP, vid, T0 + 4)["state"])

    def test_only_an_operator_publishes_or_submits(self):
        P = fresh()
        with self.assertRaises(PlacesError) as cm:
            P.venue_submit(STRANGER, CITY, "x", "", LAT, LON, T0)
        self.assertEqual(403, cm.exception.code)
        self.assertEqual(places.NOT_OPERATOR, cm.exception.reason)
        vid = draft(P, T0)
        P.check(C1, vid, True, DEVICE, T0 + 1); P.check(C2, vid, True, DEVICE, T0 + 2)
        with self.assertRaises(PlacesError) as cm:
            P.venue_publish(C1, vid, T0 + 3)
        self.assertEqual(403, cm.exception.code)
        with self.assertRaises(PlacesError):
            P.check(C1, vid, True, OPERATOR, T0 + 3)

    def test_a_sighting_never_creates_or_publishes_a_venue(self):
        P = fresh()
        for i in range(100):
            out = P.sighting(SCOUT, CITY, "z-853:3048", "ab" * 8, "wifi", -50, True, T0 + i)
            self.assertTrue(out["ok"]); self.assertEqual("", out["matched_venue_id"])
        self.assertEqual(0, P.db.execute("SELECT COUNT(*) AS n FROM place_venues").fetchone()["n"])
        self.assertEqual(0, P.index(CITY, T0 + 200)["count"])
        vid = draft(P, T0 + 200)
        out = P.sighting(SCOUT, CITY, "z-853:3048", "ab" * 8, "wifi", -50, True, T0 + 201)
        self.assertEqual(vid, out["matched_venue_id"])
        self.assertEqual(DRAFT, P._venue(vid)["state"], "matched, still not published")
        # and a sighting must be a hash in a cell, never a name or a coordinate
        with self.assertRaises(PlacesError) as cm:
            P.sighting(SCOUT, CITY, "z-853:3048", "Café du Port WiFi", "wifi", -50, True, T0)
        self.assertEqual(places.BAD_RADIO_HASH, cm.exception.reason)
        with self.assertRaises(PlacesError) as cm:
            P.sighting(SCOUT, CITY, "-4.2634,15.2429", "ab" * 8, "wifi", -50, True, T0)
        self.assertEqual(places.BAD_CELL, cm.exception.reason)

    def test_the_signin_path_leaves_only_for_free_after_signin(self):
        P = fresh()
        vid = draft(P, T0, access=FREE_AFTER_SIGNIN)
        P.venue_update(OP, vid, T0, signin_path="captive.example/login")
        publish(P, vid, T0 + 1)
        self.assertEqual("captive.example/login", P.venue(vid, T0 + 5)["signin_path"])
        self.assertEqual("Gratuit après connexion", P.venue(vid, T0 + 5)["access_text"])
        P.venue_update(OP, vid, T0 + 6, access_rule=FREE_OPEN)
        self.assertEqual("", P.venue(vid, T0 + 7)["signin_path"])
        self.assertEqual(PENDING_REVIEW, P._venue(vid)["state"], "a material edit goes back through review")


class DedupTest(unittest.TestCase):

    def test_same_radio_hash_in_the_same_cell_is_the_same_spot(self):
        P = fresh()
        vid = draft(P, T0, radio="ab" * 8)
        with self.assertRaises(PlacesError) as cm:
            P.venue_submit(OP, CITY, "Another name", "bar", LAT + 0.0001, LON, T0 + 1, radio_hash="ab" * 8)
        self.assertEqual(places.DUPLICATE_SPOT, cm.exception.reason)
        self.assertEqual(409, cm.exception.code)
        self.assertIn(vid, str(cm.exception))

    def test_same_hash_in_another_cell_is_another_spot(self):
        P = fresh()
        draft(P, T0, radio="ab" * 8)
        out = P.venue_submit(OP, CITY, "Café du Port", "café", LAT + 0.02, LON, T0 + 1, radio_hash="ab" * 8)
        self.assertTrue(out["ok"])
        self.assertEqual("", out["dup_candidate_of"], "2 km away with the same name is not a name duplicate either")

    def test_same_name_within_100_m_is_flagged_for_the_operator_not_merged(self):
        P = fresh()
        vid = draft(P, T0, radio="ab" * 8)
        near = P.venue_submit(OP, CITY, "CAFE du port", "café", LAT + 0.0005, LON, T0 + 1, radio_hash="cd" * 8)   # ~55 m, accents/case differ
        self.assertTrue(near["ok"])
        self.assertEqual(vid, near["dup_candidate_of"])
        far = P.venue_submit(OP, CITY, "Café du Port", "café", LAT + 0.0015, LON, T0 + 2, radio_hash="ef" * 8)   # ~165 m
        self.assertEqual("", far["dup_candidate_of"])
        self.assertEqual(3, P.db.execute("SELECT COUNT(*) AS n FROM place_venues").fetchone()["n"])

    def test_a_sighting_matches_by_hash_and_cell_never_by_name(self):
        P = fresh()
        vid = draft(P, T0, radio="ab" * 8)
        self.assertEqual("", P.sighting(SCOUT, CITY, "z-853:3049", "ab" * 8, "wifi", -50, True, T0 + 1)["matched_venue_id"])
        self.assertEqual(vid, P.sighting(SCOUT, CITY, "z-853:3048", "ab" * 8, "wifi", -50, True, T0 + 2)["matched_venue_id"])


class FirstRewardTest(unittest.TestCase):

    def test_the_earliest_validated_finder_earns_once_after_publication(self):
        L = Recorder()
        P = fresh(L)
        A, B, C = "a1" * 16, "b1" * 16, "c1" * 16
        P.sighting(A, CITY, "z-853:3048", "ab" * 8, "wifi", -40, False, T0)          # earliest, but never validated
        P.sighting(B, CITY, "z-853:3048", "ab" * 8, "wifi", -50, True, T0 + 1)       # earliest validated
        P.sighting(C, CITY, "z-853:3048", "ab" * 8, "wifi", -45, True, T0 + 2)
        vid = draft(P, T0 + 10, radio="ab" * 8)
        self.assertEqual(PENDING, P.rewards_for(B, T0 + 10)["rewards"][0]["state"])
        self.assertEqual(0, P.rewards_for(B, T0 + 10)["earned_centimes"])
        self.assertEqual([], L.posts, "nothing is credited before publication")
        P.check(C1, vid, True, DEVICE, T0 + 11)
        with self.assertRaises(PlacesError):
            P.venue_publish(OP, vid, T0 + 12)
        self.assertEqual([], L.posts, "one check is not enough")
        P.check(C2, vid, True, DEVICE, T0 + 13)
        out = P.venue_publish(OP, vid, T0 + 14)
        r = out["first_reward"]
        self.assertEqual(EARNED, r["state"]); self.assertEqual(B, r["scout_id"]); self.assertEqual(1000, r["amount_centimes"])
        self.assertEqual(T0 + 14 + places.REWARD_EXPIRY_MS, r["expires_at"])
        self.assertEqual(1, len(L.posts))
        self.assertEqual(("fund:promo", "promo:" + B, 1000, r["id"]), (L.posts[0]["debit"], L.posts[0]["credit"], L.posts[0]["amount"], L.posts[0]["ref"]))
        self.assertEqual([], P.rewards_for(A, T0 + 14)["rewards"])
        self.assertEqual([], P.rewards_for(C, T0 + 14)["rewards"])
        # hide, re-publish: still one reward, ever
        P.venue_hide(OP, vid, T0 + 20)
        P.venue_publish(OP, vid, T0 + 21)
        self.assertEqual(1, len(L.posts))
        self.assertEqual(1, P.db.execute("SELECT COUNT(*) AS n FROM place_rewards WHERE venue_id=? AND kind=?", (vid, FIRST)).fetchone()["n"])

    def test_a_sighting_after_publication_earns_nothing_on_a_later_republish(self):
        L = Recorder(); P = fresh(L)
        vid = draft(P, T0, radio="ab" * 8)
        publish(P, vid, T0 + 1)
        self.assertEqual([], L.posts)
        P.sighting(SCOUT, CITY, "z-853:3048", "ab" * 8, "wifi", -50, True, T0 + 100)
        P.venue_hide(OP, vid, T0 + 200); P.venue_publish(OP, vid, T0 + 201)
        self.assertEqual([], L.posts)
        self.assertEqual([], P.rewards_for(SCOUT, T0 + 201)["rewards"])

    def test_the_owner_never_earns_the_find_of_their_own_place(self):
        L = Recorder(); P = fresh(L)
        P.sighting(OWNER, CITY, "z-853:3048", "ab" * 8, "wifi", -50, True, T0)
        vid = draft(P, T0 + 1, radio="ab" * 8, owner=OWNER)
        publish(P, vid, T0 + 2)
        self.assertEqual([], L.posts)
        rows = P.rewards_for(OWNER, T0 + 5)["rewards"]
        self.assertEqual([(REJECTED, places.OWNER_SELF)], [(r["state"], r["reason"]) for r in rows], "recorded, refused, with the reason")
        self.assertEqual(0, P.rewards_for(OWNER, T0 + 5)["earned_centimes"])

    def test_a_ledger_object_with_post_is_accepted_too(self):
        class FakeLedger:
            def __init__(self): self.calls = []
            def _post(self, now, kind, debit, credit, amount, ref="", memo="", actor=""):
                self.calls.append((kind, debit, credit, amount, ref, actor))
        fl = FakeLedger()
        P = fresh(fl)
        P.sighting(SCOUT, CITY, "z-853:3048", "ab" * 8, "wifi", -50, True, T0)
        vid = draft(P, T0 + 1, radio="ab" * 8)
        publish(P, vid, T0 + 2)
        self.assertEqual([("PROMO_FIRST", "fund:promo", "promo:" + SCOUT, 1000, fl.calls[0][4], "places")], fl.calls)


class RefreshRewardTest(unittest.TestCase):

    def setUp(self):
        self.L = Recorder()
        self.P = fresh(self.L)
        self.vid = draft(self.P, T0, radio="ab" * 8)
        publish(self.P, self.vid, T0 + 1)          # last success at T0 + 2
        self.t_ok = T0 + 2

    def test_the_first_device_check_after_seven_stale_days_earns_three_francs_once_per_window(self):
        X, Y = "11" * 16, "22" * 16
        # a day before the window: an older check, no reward
        out = self.P.check(X, self.vid, True, DEVICE, self.t_ok + places.STALE_MS - DAY)
        self.assertIsNone(out["refresh_reward"])
        last_ok = self.t_ok + places.STALE_MS - DAY
        self.assertEqual(UNVERIFIED_STALE, self.P.venue(self.vid, last_ok + places.STALE_MS)["status"])
        out = self.P.check(X, self.vid, True, DEVICE, last_ok + places.STALE_MS)
        r = out["refresh_reward"]
        self.assertEqual(EARNED, r["state"]); self.assertEqual(REFRESH, r["kind"]); self.assertEqual(300, r["amount_centimes"]); self.assertEqual(X, r["scout_id"])
        self.assertEqual(WORKING_NOW, out["status"], "a probe just now at an open venue")
        self.assertEqual(RECENTLY_VERIFIED, self.P.venue(self.vid, last_ok + places.STALE_MS + places.WORKING_NOW_MS)["status"])
        self.assertEqual(1, len(self.L.posts)); self.assertEqual(300, self.L.posts[0]["amount"])
        # the next scanner an hour later gets nothing: the place is fresh again
        self.assertIsNone(self.P.check(Y, self.vid, True, DEVICE, last_ok + places.STALE_MS + H)["refresh_reward"])
        # a confirmed failure inside the same 7-day window, then a success: no second refresh
        t = last_ok + places.STALE_MS + 2 * H
        self.P.check(C1, self.vid, False, VISITOR, t); self.P.check(C2, self.vid, False, VISITOR, t + 1)
        self.assertEqual(UNAVAILABLE, self.P.venue(self.vid, t + 2)["status"])
        self.assertIsNone(self.P.check(Y, self.vid, True, DEVICE, t + 3)["refresh_reward"], "once per venue per 7 days")
        self.assertEqual(1, len(self.L.posts))

    def test_a_confirmed_failure_after_the_window_earns_a_refresh(self):
        t = self.t_ok + places.REFRESH_WINDOW_MS + H
        self.P.check(C1, self.vid, False, VISITOR, t); self.P.check(C2, self.vid, False, VISITOR, t + 1)
        self.assertEqual(UNAVAILABLE, self.P.venue(self.vid, t + 2)["status"])
        r = self.P.check("33" * 16, self.vid, True, DEVICE, t + 3)["refresh_reward"]
        self.assertEqual(EARNED, r["state"]); self.assertEqual(300, r["amount_centimes"])

    def test_an_operator_check_refreshes_the_status_but_earns_nothing(self):
        t = self.t_ok + places.STALE_MS
        out = self.P.check(OP, self.vid, True, OPERATOR, t)
        self.assertIsNone(out["refresh_reward"])
        self.assertEqual(WORKING_NOW, out["status"])
        self.assertEqual([], self.L.posts)
        # and a visitor saying "it worked" is not a probe: status stays stale, no reward
        P2 = fresh(); v2 = draft(P2, T0, radio="cd" * 8); publish(P2, v2, T0 + 1)
        out = P2.check(STRANGER, v2, True, VISITOR, T0 + 2 + places.STALE_MS)
        self.assertIsNone(out["refresh_reward"]); self.assertEqual(UNVERIFIED_STALE, out["status"])


def find_and_publish(P, scout, i, t):
    """One scout finds spot i at t, two checkers confirm it, the operator publishes. Returns the first reward."""
    radio = "%016x" % (0x1000 + i)
    lat = LAT + i * 0.002
    P.sighting(scout, CITY, places.cell_of(lat, LON), radio, "wifi", -50, True, t)
    vid = P.venue_submit(OP, CITY, "Spot %d" % i, "shop", lat, LON, t + 1, radio_hash=radio, access_rule=FREE_OPEN, hours=ALWAYS)["venue_id"]
    P.record_consent(OP, vid, "doc", t + 1)
    P.check(C1, vid, True, DEVICE, t + 2); P.check(C2, vid, True, DEVICE, t + 3)
    return P.venue_publish(OP, vid, t + 4)["first_reward"]


class CapsTest(unittest.TestCase):

    def test_the_constants_are_the_launch_decisions(self):
        self.assertEqual(1000, places.FIRST_REWARD_CENTIMES)
        self.assertEqual(300, places.REFRESH_REWARD_CENTIMES)
        self.assertEqual(3000, places.SCOUT_DAY_CAP_CENTIMES)
        self.assertEqual(10_000, places.SCOUT_MONTH_CAP_CENTIMES)
        self.assertEqual(500_000, places.GLOBAL_MONTH_CAP_CENTIMES)
        self.assertEqual(90 * DAY, places.REWARD_EXPIRY_MS)
        self.assertEqual(30 * DAY, places.APPEAL_WINDOW_MS)
        self.assertEqual(7 * DAY, places.REFRESH_WINDOW_MS)

    def test_thirty_francs_a_day_per_scout(self):
        L = Recorder(); P = fresh(L)
        for i in range(3):
            self.assertTrue(P.offer_for(SCOUT, T0 + i * H)["available"], "the offer stands until the day is full")
            self.assertEqual(EARNED, find_and_publish(P, SCOUT, i, T0 + i * H)["state"])
        self.assertFalse(P.offer_for(SCOUT, T0 + 3 * H)["available"], "30 FCFA reached: no paid offer for the rest of the day")
        r = find_and_publish(P, SCOUT, 3, T0 + 3 * H)
        self.assertEqual(REJECTED, r["state"]); self.assertEqual(CAP_DAY, r["reason"])
        self.assertEqual(3, len(L.posts), "nothing posted for a rejected reward")
        offer = P.offer_for(SCOUT, T0 + 4 * H)
        self.assertFalse(offer["available"]); self.assertEqual(CAP_DAY, offer["reason"]); self.assertEqual("pas d'offre scout payée", offer["text"])
        # reports and sightings are still accepted, and carry the same honest offer
        s = P.sighting(SCOUT, CITY, "z-853:3048", "ff" * 8, "wifi", -50, True, T0 + 4 * H)
        self.assertTrue(s["ok"]); self.assertFalse(s["offer"]["available"])
        vid = P.db.execute("SELECT id FROM place_venues LIMIT 1").fetchone()["id"]
        rep = P.report(SCOUT, vid, FAILURE, "portail captif", T0 + 4 * H)
        self.assertTrue(rep["ok"]); self.assertEqual("pas d'offre scout payée", rep["offer"]["text"])
        # tomorrow the day cap is gone; the global offer never went away
        self.assertTrue(P.offer_for(SCOUT, T0 + DAY)["available"])
        self.assertTrue(P.offer_available(T0 + 4 * H))
        self.assertEqual(EARNED, find_and_publish(P, SCOUT, 4, T0 + DAY)["state"])

    def test_a_hundred_francs_a_month_per_scout(self):
        P = fresh()
        for d in range(10):
            self.assertEqual(EARNED, find_and_publish(P, SCOUT, d, T0 + d * DAY)["state"])
        self.assertEqual(10_000, P.rewards_for(SCOUT, T0 + 10 * DAY)["earned_centimes"])
        r = find_and_publish(P, SCOUT, 10, T0 + 10 * DAY)
        self.assertEqual(REJECTED, r["state"]); self.assertEqual(CAP_MONTH, r["reason"])
        offer = P.offer_for(SCOUT, T0 + 10 * DAY)
        self.assertFalse(offer["available"]); self.assertEqual(CAP_MONTH, offer["reason"])
        self.assertTrue(P.offer_for(STRANGER, T0 + 10 * DAY)["available"], "another scout is not capped")
        # December: the month cap resets (T0 + 16 days is 1 December)
        self.assertTrue(P.offer_for(SCOUT, T0 + 16 * DAY)["available"])
        self.assertEqual(EARNED, find_and_publish(P, SCOUT, 11, T0 + 16 * DAY)["state"])

    def test_five_thousand_francs_a_month_for_everybody(self):
        P = fresh()
        n = 0
        for s in range(50):
            scout = ("%02x" % (0x30 + s)) * 16
            for d in range(10):
                self.assertEqual(EARNED, find_and_publish(P, scout, n, T0 + d * DAY + s * MIN)["state"])
                n += 1
        self.assertEqual(500_000, P._issued("", T0 + 10 * DAY)[2])
        self.assertFalse(P.offer_available(T0 + 10 * DAY))
        newcomer = "77" * 16
        offer = P.offer_for(newcomer, T0 + 10 * DAY)
        self.assertFalse(offer["available"]); self.assertEqual(CAP_GLOBAL, offer["reason"]); self.assertEqual("pas d'offre scout payée", offer["text"])
        self.assertFalse(P.offer_for("", T0 + 10 * DAY)["available"], "the unsigned offer says so too")
        r = find_and_publish(P, newcomer, n, T0 + 10 * DAY)
        self.assertEqual(REJECTED, r["state"]); self.assertEqual(CAP_GLOBAL, r["reason"])
        self.assertTrue(P.offer_available(T0 + 16 * DAY), "next month")
        self.assertEqual(501, P.index(CITY, T0 + 10 * DAY)["count"], "every venue was published regardless of the reward")


class ExpiryAndAppealTest(unittest.TestCase):

    def test_earned_credit_expires_at_ninety_days_and_goes_back_to_the_fund(self):
        L = Recorder(); P = fresh(L)
        r = find_and_publish(P, SCOUT, 0, T0)
        earned_at = r["decided_at"]
        self.assertEqual({"rewards_expired": 0, "venues_stale": 0, "venues_unavailable": 0}, P.sweep(earned_at + DAY))
        n = P.sweep(earned_at + places.REWARD_EXPIRY_MS - 1)
        self.assertEqual(0, n["rewards_expired"])
        self.assertEqual(1, n["venues_stale"], "the venue nobody checked for 90 days is stale, the credit is not yet")
        self.assertEqual(EARNED, P.rewards_for(SCOUT)["rewards"][0]["state"])
        n = P.sweep(earned_at + places.REWARD_EXPIRY_MS)
        self.assertEqual(1, n["rewards_expired"])
        row = P.rewards_for(SCOUT)["rewards"][0]
        self.assertEqual(EXPIRED, row["state"])
        self.assertEqual(0, P.rewards_for(SCOUT)["earned_centimes"])
        self.assertEqual(2, len(L.posts))
        self.assertEqual(("promo:" + SCOUT, "fund:promo", 1000), (L.posts[1]["debit"], L.posts[1]["credit"], L.posts[1]["amount"]))

    def test_an_appeal_within_thirty_days_keeps_the_state_until_an_operator_decides(self):
        L = Recorder(); P = fresh(L)
        for i in range(3):
            find_and_publish(P, SCOUT, i, T0 + i)
        r = find_and_publish(P, SCOUT, 3, T0 + 10)
        self.assertEqual(REJECTED, r["state"])
        decided = r["decided_at"]
        with self.assertRaises(PlacesError) as cm:
            P.appeal(STRANGER, r["id"], "mine!", decided + 1)
        self.assertEqual(403, cm.exception.code)
        with self.assertRaises(PlacesError) as cm:
            P.appeal(SCOUT, P.rewards_for(SCOUT)["rewards"][-1]["id"], "already earned", decided + 1)
        self.assertEqual(places.WRONG_STATE, cm.exception.reason)
        out = P.appeal(SCOUT, r["id"], "c'était mon premier lieu du jour", decided + places.APPEAL_WINDOW_MS)
        self.assertEqual(REJECTED, out["reward"]["state"], "an appeal changes nothing by itself")
        self.assertEqual("c'était mon premier lieu du jour", out["reward"]["appeal"])
        with self.assertRaises(PlacesError) as cm:
            P.appeal(SCOUT, r["id"], "late", decided + places.APPEAL_WINDOW_MS + 1)
        self.assertEqual(places.APPEAL_WINDOW_CLOSED, cm.exception.reason)
        self.assertEqual(1, len(P.ops_queue(OP, decided + DAY)["appeals"]))
        # the operator may earn it - but the caps still apply on the day it is decided
        with self.assertRaises(PlacesError) as cm:
            P.decide_reward(OP, r["id"], True, decided + 1)
        self.assertEqual(CAP_DAY, cm.exception.reason)
        out = P.decide_reward(OP, r["id"], True, decided + DAY)
        self.assertEqual(EARNED, out["reward"]["state"])
        self.assertEqual(4, len(L.posts))
        with self.assertRaises(PlacesError):
            P.decide_reward(STRANGER, r["id"], False, decided + DAY)

    def test_an_operator_may_also_keep_it_rejected_with_a_reason(self):
        P = fresh()
        for i in range(4):
            r = find_and_publish(P, SCOUT, i, T0 + i)
        out = P.decide_reward(OP, r["id"], False, T0 + 100, reason="doublon")
        self.assertEqual(REJECTED, out["reward"]["state"]); self.assertEqual("doublon", out["reward"]["reason"])


class ClaimsAndReportsTest(unittest.TestCase):

    def setUp(self):
        self.P = fresh()
        self.vid = draft(self.P, T0)
        publish(self.P, self.vid, T0 + 1)

    def test_owner_removal_hides_immediately_and_a_strangers_request_waits(self):
        P, vid = self.P, self.vid
        c = P.claim(OWNER, vid, CLAIM, "c'est mon café", T0 + 10)
        self.assertEqual(PENDING, c["state"])
        self.assertEqual(PUBLISHED, P._venue(vid)["state"])
        # a removal from somebody who is not (yet) the owner waits for a person
        r = P.claim(STRANGER, vid, REMOVAL, "remove it", T0 + 11)
        self.assertEqual(PENDING, r["state"]); self.assertEqual(PUBLISHED, r["venue_state"])
        self.assertEqual(1, P.index(CITY, T0 + 12)["count"])
        P.review_claim(OP, c["claim_id"], True, T0 + 13)
        self.assertEqual(OWNER, P._venue(vid)["owner_id"])
        out = P.claim(OWNER, vid, REMOVAL, "je ferme", T0 + 14)
        self.assertEqual(places.APPLIED, out["state"]); self.assertEqual(HIDDEN, out["venue_state"])
        self.assertEqual(0, P.index(CITY, T0 + 15)["count"])
        with self.assertRaises(PlacesError):
            P.venue(vid, T0 + 15)
        self.assertEqual(UNAVAILABLE, P.venue(vid, T0 + 15, who=OP)["status"])
        # the stranger's pending removal, accepted later, hides too (already hidden here)
        self.assertEqual(HIDDEN, P.review_claim(OP, r["claim_id"], True, T0 + 16)["venue_state"])
        with self.assertRaises(PlacesError):
            P.claim(OWNER, vid, "DELETE", "", T0 + 17)

    def test_a_safety_report_hides_pending_review(self):
        P, vid = self.P, self.vid
        out = P.report(STRANGER, vid, SAFETY, "agression à l'entrée", T0 + 10)
        self.assertEqual(HIDDEN, out["venue_state"])
        self.assertEqual(0, P.index(CITY, T0 + 11)["count"])
        self.assertEqual(1, len(P.ops_queue(OP, T0 + 11)["reports"]))
        # dismissed: back where it was
        P.review_report(OP, out["report_id"], False, T0 + 12, note="rien constaté")
        self.assertEqual(PUBLISHED, P._venue(vid)["state"])
        self.assertEqual(1, P.index(CITY, T0 + 13)["count"])
        # confirmed: gone for good
        out = P.report(STRANGER, vid, SAFETY, "again", T0 + 14)
        P.review_report(OP, out["report_id"], True, T0 + 15)
        self.assertEqual(REMOVED, P._venue(vid)["state"])
        with self.assertRaises(PlacesError):
            P.venue_publish(OP, vid, T0 + 16)

    def test_other_reports_keep_the_venue_visible_as_reported(self):
        P, vid = self.P, self.vid
        out = P.report(STRANGER, vid, FAILURE, "pas d'Internet", T0 + 10)
        self.assertEqual(PUBLISHED, out["venue_state"])
        idx = P.index(CITY, T0 + 11)
        self.assertEqual(1, idx["count"])
        self.assertEqual(REPORTED, idx["venues"][0]["status"]); self.assertEqual("Signalé, à vérifier", idx["venues"][0]["status_text"])
        P.review_report(OP, out["report_id"], True, T0 + 12)
        self.assertEqual(UNAVAILABLE, P.venue(vid, T0 + 13)["status"], "a confirmed failure is an operator failure check")
        self.assertEqual(PUBLISHED, P._venue(vid)["state"])
        # a device success afterwards brings it back
        P.check(C1, vid, True, DEVICE, T0 + 14)
        self.assertEqual(WORKING_NOW, P.venue(vid, T0 + 15)["status"])
        out = P.report(STRANGER, vid, CLOSURE, "fermé définitivement", T0 + 16)
        self.assertEqual(REPORTED, P.venue(vid, T0 + 17)["status"])
        P.review_report(OP, out["report_id"], True, T0 + 18)
        self.assertEqual(HIDDEN, P._venue(vid)["state"])
        # dismissing a non-safety report changes nothing about the venue
        vid2 = draft(self.P, T0 + 20, radio="cd" * 8, name="Autre"); publish(self.P, vid2, T0 + 21)
        o2 = P.report(STRANGER, vid2, places.OTHER, "?", T0 + 30)
        P.review_report(OP, o2["report_id"], False, T0 + 31)
        self.assertEqual(WORKING_NOW, P.venue(vid2, T0 + 32)["status"])

    def test_an_accepted_correction_sends_the_venue_back_through_review(self):
        P, vid = self.P, self.vid
        c = P.claim(OWNER, vid, CORRECTION, "l'entrée est rue X", T0 + 10)
        self.assertEqual(PUBLISHED, P._venue(vid)["state"])
        self.assertEqual(PENDING_REVIEW, P.review_claim(OP, c["claim_id"], True, T0 + 11)["venue_state"])
        self.assertEqual(REPORTED, P.index(CITY, T0 + 12)["venues"][0]["status"])
        self.assertEqual(PUBLISHED, P.venue_publish(OP, vid, T0 + 13)["state"])
        with self.assertRaises(PlacesError):
            P.review_claim(OP, c["claim_id"], True, T0 + 14)


class IndexAndDispatchTest(unittest.TestCase):

    def setUp(self):
        self.P = fresh()
        self.v1 = draft(self.P, T0, name="Café du Port", radio="ab" * 8, neighbourhood="Bacongo")
        publish(self.P, self.v1, T0 + 1)
        self.v2 = draft(self.P, T0 + 10, name="Bibliothèque Poto-Poto", radio="cd" * 8, lat=LAT + 0.03, neighbourhood="Poto-Poto")
        publish(self.P, self.v2, T0 + 11)
        self.v3 = draft(self.P, T0 + 20, name="Brouillon", radio="ef" * 8, lat=LAT + 0.05, neighbourhood="Moungali")   # never published

    def test_the_index_has_count_last_update_neighbourhoods_and_ordered_cards(self):
        now = T0 + 100
        idx = self.P.index(CITY, now)
        for k in ("city", "generated_at", "count", "last_update", "neighbourhoods", "venues", "exhaustive"):
            self.assertIn(k, idx)
        self.assertEqual(2, idx["count"]); self.assertEqual(len(idx["venues"]), idx["count"])
        self.assertEqual(now, idx["generated_at"])
        self.assertFalse(idx["exhaustive"])
        self.assertEqual(["Bacongo", "Poto-Poto"], idx["neighbourhoods"], "the draft's neighbourhood is not listed")
        self.assertEqual(T0 + 20, idx["last_update"], "the newest change, the draft's row included (it changes the ETag, not the list)")
        card = idx["venues"][0]
        for k in ("id", "name", "neighbourhood", "lat", "lon", "access_rule", "access_text", "hours", "open_now", "status", "status_text",
                  "status_age_ms", "last_ok_at", "confidence", "direct_or_relay", "prok_deliverable", "signin_path", "kind", "updated_at"):
            self.assertIn(k, card)
        self.assertEqual(STATUS_TEXT[card["status"]], card["status_text"])
        self.assertTrue(card["hours"].startswith("mon=00:00-23:59,"))
        self.assertNotIn("state", card, "the public card carries no internal state")
        # after a while the older-checked venue sorts under the fresher one
        self.P.check(C1, self.v2, True, DEVICE, T0 + 2 * DAY)
        idx = self.P.index(CITY, T0 + 2 * DAY + 1)
        self.assertEqual([self.v2, self.v1], [c["id"] for c in idx["venues"]])
        self.assertEqual(T0 + 2 * DAY, idx["last_update"])
        self.assertEqual([WORKING_NOW, OLDER_CHECK], [c["status"] for c in idx["venues"]])

    def test_search_ignores_case_and_accents_and_looks_at_the_neighbourhood(self):
        self.assertEqual([self.v2], [c["id"] for c in self.P.search(CITY, "bibliotheque", T0 + 50)["venues"]])
        self.assertEqual([self.v2], [c["id"] for c in self.P.search(CITY, "POTO", T0 + 50)["venues"]])
        self.assertEqual(2, self.P.search(CITY, "", T0 + 50)["count"])
        self.assertEqual(0, self.P.search(CITY, "brouillon", T0 + 50)["count"], "a draft is not searchable")
        self.assertEqual(0, self.P.search("Pointe-Noire", "café", T0 + 50)["count"])

    def test_public_gets_work_unsigned_and_the_rest_do_not(self):
        P = self.P
        code, out = P.handle_get("", "/v1/places/index", {"city": CITY}, T0 + 50)
        self.assertEqual(200, code); self.assertEqual(2, out["count"])
        code, out = P.handle_get("", "/v1/places/index", {"city": [CITY], "if_newer": [str(out["last_update"])]}, T0 + 51)
        self.assertEqual(200, code); self.assertTrue(out["unchanged"])
        code, out = P.handle_get("", "/v1/places/index", {"city": CITY, "if_newer": str(T0 + 19)}, T0 + 52)
        self.assertEqual(200, code); self.assertNotIn("unchanged", out)
        code, out = P.handle_get("", "/v1/places/venue", {"id": self.v1}, T0 + 50)
        self.assertEqual(200, code); self.assertEqual("Café du Port", out["name"])
        code, out = P.handle_get("", "/v1/places/venue", {"id": self.v3}, T0 + 50)
        self.assertEqual(404, code); self.assertEqual(places.UNKNOWN_VENUE, out["reason"])
        code, out = P.handle_get(OP, "/v1/places/venue", {"id": self.v3}, T0 + 50)
        self.assertEqual(200, code); self.assertEqual(DRAFT, out["state"]); self.assertIn("blockers", out)
        code, out = P.handle_get("", "/v1/places/search", {"city": CITY, "q": "port"}, T0 + 50)
        self.assertEqual(200, code); self.assertEqual(1, out["count"])
        code, out = P.handle_get("", "/v1/places/offer", {}, T0 + 50)
        self.assertEqual(200, code); self.assertTrue(out["available"])
        code, out = P.handle_get("", "/v1/places/rewards", {}, T0 + 50)
        self.assertEqual(401, code); self.assertEqual(places.NOT_SIGNED, out["reason"])
        code, out = P.handle_get(SCOUT, "/v1/places/rewards", {}, T0 + 50)
        self.assertEqual(200, code); self.assertIn("note", out)
        code, out = P.handle_get(SCOUT, "/v1/places/ops/queue", {}, T0 + 50)
        self.assertEqual(403, code); self.assertEqual(places.NOT_OPERATOR, out["reason"])
        self.assertEqual((404, {"error": "not found"}), P.handle_get("", "/v1/places/nope", {}, T0))

    def test_posts_are_signed_and_dispatch_to_the_methods(self):
        P = self.P
        self.assertEqual((404, {"error": "not found"}), P.handle_post("", "/v1/places/nope", {}, T0))
        code, out = P.handle_post("", "/v1/places/sighting", {"cell": "z-853:3048", "radio_hash": "ab" * 8}, T0 + 50)
        self.assertEqual(401, code)
        code, out = P.handle_post(SCOUT, "/v1/places/sighting", {"cell": "z-853:3048", "radio_hash": "ab" * 8, "kind": "wifi", "signal": -50, "validated_internet": True}, T0 + 50)
        self.assertEqual(200, code, out); self.assertEqual(self.v1, out["matched_venue_id"]); self.assertIn("offer", out)
        code, out = P.handle_post(SCOUT, "/v1/places/check", {"venue_id": self.v1, "ok": False, "kind": OPERATOR}, T0 + 51)
        self.assertEqual(400, code)
        code, out = P.handle_post(SCOUT, "/v1/places/check", {"venue_id": self.v1, "ok": True, "kind": DEVICE}, T0 + 52)
        self.assertEqual(200, code); self.assertEqual(WORKING_NOW, out["status"]); self.assertEqual("Fonctionne maintenant", out["status_text"])
        code, out = P.handle_post(SCOUT, "/v1/places/report", {"venue_id": self.v1, "kind": CLOSURE, "text": "fermé"}, T0 + 53)
        self.assertEqual(200, code)
        code, out = P.handle_post(SCOUT, "/v1/places/ops/report", {"report_id": out["report_id"], "confirm": True}, T0 + 54)
        self.assertEqual(403, code); self.assertEqual(places.NOT_OPERATOR, out["reason"])
        code, out = P.handle_post(OP, "/v1/places/ops/venue", {"name": "Nouveau", "kind": "bar", "lat": LAT, "lon": LON + 0.01, "radio_hash": "12" * 8,
                                                              "access_rule": FREE_LIMITED_OR(), "hours": ALWAYS, "neighbourhood": "Bacongo"}, T0 + 55)
        self.assertEqual(200, code, out); vid = out["venue_id"]
        code, out = P.handle_post(OP, "/v1/places/ops/publish", {"venue_id": vid}, T0 + 56)
        self.assertEqual(409, code); self.assertEqual(places.NO_CONSENT, out["reason"]); self.assertIn("error", out)
        code, out = P.handle_post(OP, "/v1/places/ops/consent", {"venue_id": vid, "consent_doc": "form-7"}, T0 + 57)
        self.assertEqual(200, code)
        code, out = P.handle_post(C1, "/v1/places/check", {"venue_id": vid, "ok": True, "kind": DEVICE}, T0 + 58)
        code, out = P.handle_post(OP, "/v1/places/ops/check", {"venue_id": vid, "ok": True}, T0 + 59)
        self.assertEqual(200, code)
        code, out = P.handle_post(OP, "/v1/places/ops/publish", {"venue_id": vid}, T0 + 60)
        self.assertEqual(200, code); self.assertEqual(PUBLISHED, out["state"])
        code, out = P.handle_post(OP, "/v1/places/ops/sweep", {}, T0 + 61)
        self.assertEqual(200, code); self.assertIn("rewards_expired", out)
        # the other argument order some notes use is tolerated
        code, out = P.handle_post(OP, {"venue_id": vid, "reason": "test"}, "/v1/places/ops/hide", T0 + 62)
        self.assertEqual(200, code); self.assertEqual(HIDDEN, out["state"])


def FREE_LIMITED_OR():
    return places.FREE_LIMITED


if __name__ == "__main__":
    unittest.main()
