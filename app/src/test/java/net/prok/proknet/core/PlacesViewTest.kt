package net.prok.proknet.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.19.0: what a free-Internet card may say. The status words are the server's fixture;
 * a stale venue leaves the default list; a distance is a walking distance only with a
 * route and there is never an ETA without one; without a position nothing is said about
 * distance or direction.
 */
class PlacesViewTest {

    private fun fixture(name: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val f = File(dir, "server/tests/fixtures/" + name)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        throw AssertionError("server/tests/fixtures/" + name + " not found from " + File(".").absolutePath)
    }

    private val T0 = 1_700_006_400_000L      // 2023-11-15 00:00 UTC = Wednesday 01:00 in Brazzaville
    private val H = 3_600_000L
    private val DAY = 24 * H

    // the shape places.index() produces: json.dumps with \u escapes, flat cards, hours as a line
    private val index = """{"city": "Brazzaville", "generated_at": 1700006400500, "count": 3, "last_update": 1700006400020, "exhaustive": false,
        "note": "Liste non exhaustive", "neighbourhoods": ["Bacongo", "Poto-Poto"], "venues": [
        {"id": "v1", "city": "Brazzaville", "neighbourhood": "Bacongo", "name": "Café du Port", "kind": "café", "lat": -4.2634, "lon": 15.2429,
         "access_rule": "FREE_OPEN", "access_text": "Gratuit ici", "signin_path": "", "hours": "mon=00:00-23:59,tue=00:00-23:59,wed=00:00-23:59,thu=00:00-23:59,fri=00:00-23:59,sat=00:00-23:59,sun=",
         "open_now": true, "status": "WORKING_NOW", "status_text": "Fonctionne maintenant", "status_age_ms": 120000, "last_ok_at": 1700006400380, "confidence": 75,
         "direct_or_relay": "direct", "prok_deliverable": false, "updated_at": 1700006400020},
        {"id": "v2", "city": "Brazzaville", "neighbourhood": "Poto-Poto", "name": "Bibliothèque \"Centrale\"", "kind": "bibliothèque", "lat": -4.2334, "lon": 15.2429,
         "access_rule": "FREE_AFTER_SIGNIN", "access_text": "Gratuit après connexion", "signin_path": "portail.example", "hours": "wed=08:00-20:00",
         "open_now": false, "status": "OLDER_CHECK", "status_text": "Ancien contrôle", "status_age_ms": 172800000, "last_ok_at": 1699833600000, "confidence": 30,
         "direct_or_relay": "direct", "prok_deliverable": true, "updated_at": 1700006400010},
        {"id": "v3", "city": "Brazzaville", "neighbourhood": "Bacongo", "name": "Kiosque", "kind": "boutique", "lat": -4.2640, "lon": 15.2440,
         "access_rule": "CUSTOMERS_ONLY", "access_text": "Clients uniquement", "signin_path": "", "hours": "",
         "open_now": false, "status": "UNVERIFIED_STALE", "status_text": "Non vérifié", "status_age_ms": -1, "last_ok_at": 0, "confidence": 0,
         "direct_or_relay": "direct", "prok_deliverable": false, "updated_at": 1700006400000}]}"""

    private fun cards() = PlacesView.parseIndex(index)!!.venues
    private val here = PlacesView.Pos(-4.2634, 15.2429)       // standing at the café's entrance

    // ================= the shared table =================

    @Test fun the_status_texts_are_exactly_the_fixture_the_server_wrote() {
        val lines = fixture("place_status.txt").readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val expected = lines.associate { val p = it.split("|", limit = 2); p[0] to p[1] }
        assertEquals(expected, PlacesView.STATUS_TEXT)
        assertEquals("Fonctionne maintenant", PlacesView.statusText("WORKING_NOW"))
        // an unknown state is shown as the cautious one, never as anything greener
        assertEquals(PlacesView.UNKNOWN_STATUS_TEXT, PlacesView.statusText("GREEN_DOT_BY_MAGIC"))
        assertEquals(PlacesView.STATUS_TEXT["REPORTED"], PlacesView.UNKNOWN_STATUS_TEXT)
        assertEquals("Gratuit après connexion", PlacesView.accessText("FREE_AFTER_SIGNIN"))
    }

    // ================= parsing =================

    @Test fun the_index_is_read_with_its_count_last_update_neighbourhoods_and_escaped_names() {
        val idx = PlacesView.parseIndex(index)!!
        assertEquals("Brazzaville", idx.city)
        assertEquals(3, idx.count); assertEquals(3, idx.venues.size)
        assertEquals(1700006400020L, idx.lastUpdate)
        assertEquals(listOf("Bacongo", "Poto-Poto"), idx.neighbourhoods)
        assertFalse(idx.exhaustive)
        val c = idx.venues[0]
        assertEquals("Café du Port", c.name)
        assertEquals("Bibliothèque \"Centrale\"", idx.venues[1].name)
        assertEquals(-4.2634, c.lat, 1e-9); assertEquals(15.2429, c.lon, 1e-9)
        assertEquals("WORKING_NOW", c.status); assertEquals(120_000L, c.statusAgeMs); assertEquals(75, c.confidence)
        assertEquals(-1L, idx.venues[2].statusAgeMs)
        assertTrue(idx.venues[1].prokDeliverable); assertFalse(c.prokDeliverable)
        assertEquals("Fonctionne maintenant · il y a 2 min", c.freshnessLine())
        assertEquals("Non vérifié depuis plus de 7 jours", idx.venues[2].freshnessLine())
        assertEquals(intArrayOf(0, 1439).toList(), c.hours["mon"]!!.toList())
        assertNull(c.hours["sun"])
        assertTrue(idx.venues[2].hours.isEmpty())
        assertNull(PlacesView.parseIndex(""))
        assertNull(PlacesView.parseIndex("{\"error\": \"not found\"}"))
    }

    @Test fun hours_are_local_time_and_the_line_says_open_or_closed_or_unknown() {
        val v2 = cards()[1]                                          // Wednesday 08:00-20:00
        assertEquals(false, v2.openNow(T0))                          // 01:00
        assertEquals(true, v2.openNow(T0 + 7 * H))                   // 08:00
        assertEquals(false, v2.openNow(T0 + 19 * H))                 // 20:00
        assertTrue(v2.hoursLine(T0 + 7 * H).startsWith("Ouvert maintenant · mer 08:00-20:00"))
        assertTrue(v2.hoursLine(T0).startsWith("Fermé maintenant"))
        assertEquals("Horaires à confirmer", cards()[2].hoursLine(T0))
        assertEquals("lun-sam 00:00-23:59", PlacesView.hoursSummary(cards()[0].hours))
        val night = PlacesView.parseHours("tue=20:00-02:00")
        assertEquals("Tuesday night runs into Wednesday 01:00", true, PlacesView.openAt(night, T0))
    }

    // ================= filters and the default list =================

    @Test fun the_default_list_drops_stale_venues_but_search_and_filters_keep_them() {
        val all = cards()
        val recommended = PlacesView.list(all, emptySet(), "", "", null, T0)
        assertEquals(listOf("v1", "v2"), recommended.map { it.id })
        assertEquals(listOf("v3"), PlacesView.list(all, emptySet(), "", "kiosque", null, T0).map { it.id })
        assertEquals(listOf("v1", "v3"), PlacesView.list(all, emptySet(), "Bacongo", "", null, T0, includeStale = true).map { it.id })
        assertEquals(listOf("v1"), PlacesView.list(all, emptySet(), "Bacongo", "", null, T0).map { it.id })
        // accents and case do not matter
        assertEquals(listOf("v2"), PlacesView.list(all, emptySet(), "", "BIBLIOTHEQUE", null, T0).map { it.id })
    }

    @Test fun each_filter_means_what_its_label_says() {
        val all = cards()
        fun ids(f: PlacesView.Filter, at: Long = T0, pos: PlacesView.Pos? = here) = PlacesView.list(all, setOf(f), "", "", pos, at).map { it.id }
        assertEquals(listOf("v1", "v2"), ids(PlacesView.Filter.FREE))
        assertEquals(listOf("v1"), ids(PlacesView.Filter.WORKING_NOW))
        assertEquals(listOf("v1"), ids(PlacesView.Filter.OPEN_NOW))
        assertEquals(listOf("v1", "v2"), ids(PlacesView.Filter.OPEN_NOW, at = T0 + 7 * H))
        assertEquals(listOf("v1"), ids(PlacesView.Filter.NO_SIGNIN))
        assertEquals(listOf("v2"), ids(PlacesView.Filter.PROK_DELIVERABLE))
        // walking distance: the kiosk 130 m away is in, the library 3.3 km away is out
        assertEquals(listOf("v1", "v3"), ids(PlacesView.Filter.WALKING))
        // and without a position the walking filter is not offered, and does not filter
        assertFalse(PlacesView.filterAvailable(PlacesView.Filter.WALKING, false))
        assertTrue(PlacesView.filterAvailable(PlacesView.Filter.FREE, false))
        assertEquals(listOf("v1", "v2", "v3"), ids(PlacesView.Filter.WALKING, pos = null))
    }

    @Test fun the_list_orders_by_status_then_distance_then_name() {
        val all = cards()
        val far = PlacesView.Pos(-4.2334, 15.2429)                   // at the library
        val l = PlacesView.list(all, emptySet(), "", "", far, T0, includeStale = true)
        assertEquals("status rank first: working, older, stale", listOf("v1", "v2", "v3"), l.map { it.id })
        val two = listOf(all[0].copyStatus("OLDER_CHECK"), all[1])
        assertEquals("same rank: the nearer one first", listOf("v2", "v1"), PlacesView.list(two, emptySet(), "", "", far, T0).map { it.id })
    }

    private fun PlacesView.Card.copyStatus(s: String) = PlacesView.Card(id, name, neighbourhood, kind, lat, lon, accessRule, signinPath, hours, s, statusAgeMs, lastOkAt, confidence, prokDeliverable, directOrRelay)

    // ================= distance words =================

    @Test fun without_a_route_the_distance_is_approximate_and_there_is_never_an_eta() {
        val v3 = cards()[2]
        val line = PlacesView.distanceLine(v3, here, null)
        assertTrue(line, line.startsWith("≈ "))
        assertTrue(line, line.endsWith(" à vol d'oiseau"))
        assertFalse(line, line.contains("min"))
        assertFalse(line, line.contains("à pied"))
        assertEquals("≈ 140 m à vol d'oiseau", line)
        val g = PlacesView.guidanceLine(v3, here, null)
        assertTrue(g, g.contains("à vol d'oiseau") && g.contains("itinéraire indisponible") && !g.contains("min"))
        assertTrue(g, g.contains("direction "))
        assertEquals(null, StraightLineRouter.route(here.lat, here.lon, v3.lat, v3.lon))
    }

    @Test fun with_a_route_the_distance_is_a_walking_distance_with_an_eta() {
        val v2 = cards()[1]
        val route = WalkRoute(4_100.0, 3_280, listOf(doubleArrayOf(here.lat, here.lon), doubleArrayOf(v2.lat, v2.lon)))
        assertEquals("4,1 km à pied · 55 min", PlacesView.distanceLine(v2, here, route))
        assertEquals("Reste 4,1 km à pied · 55 min", PlacesView.guidanceLine(v2, here, route))
        assertEquals("1 h 05", PlacesView.formatEta(3_900))
    }

    @Test fun without_a_position_nothing_is_said_about_distance_or_direction() {
        val v1 = cards()[0]
        assertEquals("", PlacesView.distanceLine(v1, null, null))
        assertEquals("", PlacesView.directionLine(v1, null))
        assertEquals("Position inconnue : impossible de guider.", PlacesView.guidanceLine(v1, null, null))
        assertTrue(PlacesView.NO_LOCATION_NOTE.contains("sans distance ni direction"))
        // a card whose entrance is not published says nothing either, even with a position
        val noEntrance = v1.let { PlacesView.Card(it.id, it.name, it.neighbourhood, it.kind, 0.0, 0.0, it.accessRule, it.signinPath, it.hours, it.status, it.statusAgeMs, it.lastOkAt, it.confidence, it.prokDeliverable, it.directOrRelay) }
        assertEquals("", PlacesView.distanceLine(noEntrance, here, null))
        assertEquals("", PlacesView.directionLine(noEntrance, here))
    }

    @Test fun arrival_is_thirty_metres_and_bearings_read_as_compass_points() {
        val v1 = cards()[0]
        assertEquals("Vous êtes arrivé. Vérification de la connexion…", PlacesView.guidanceLine(v1, here, null))
        assertEquals("N", PlacesView.compass(PlacesView.bearingDeg(0.0, 0.0, 1.0, 0.0)))
        assertEquals("E", PlacesView.compass(PlacesView.bearingDeg(0.0, 0.0, 0.0, 1.0)))
        assertEquals("SO", PlacesView.compass(225.0))
        assertTrue(Math.abs(PlacesView.distanceM(-4.2634, 15.2429, -4.2624, 15.2429) - 111.2) < 1.0)
        assertEquals("350 m", PlacesView.formatDistance(347.0))
        assertEquals("1,2 km", PlacesView.formatDistance(1_240.0))
    }

    // ================= freshness and cache words =================

    @Test fun ages_and_the_refreshed_line_are_in_french_and_honest_about_an_empty_cache() {
        assertEquals("à l'instant", PlacesView.ageText(30_000))
        assertEquals("il y a 5 min", PlacesView.ageText(5 * 60_000))
        assertEquals("il y a 3 h", PlacesView.ageText(3 * H + 5))
        assertEquals("il y a 2 j", PlacesView.ageText(2 * DAY + H))
        assertEquals("mis à jour il y a 3 h", PlacesView.refreshedText(T0, T0 + 3 * H))
        assertTrue(PlacesView.refreshedText(0, T0).startsWith("jamais mis à jour"))
        assertEquals("Internet gratuit à Brazzaville · 3 lieux connus", PlacesView.headerLine("Brazzaville", 3))
        assertEquals("Internet gratuit à Brazzaville · 1 lieu connu", PlacesView.headerLine("Brazzaville", 1))
        assertEquals("Internet gratuit à Brazzaville · 0 lieux connus", PlacesView.headerLine("Brazzaville", 0))
    }

    @Test fun the_scout_offer_says_no_paid_offer_exactly_when_the_server_does() {
        val on = PlacesView.parseOffer("""{"available": true, "reason": "", "text": "Offre scout : 10 CFA", "first_centimes": 1000}""")!!
        assertTrue(on.available); assertEquals("Offre scout : 10 CFA", on.line)
        val off = PlacesView.parseOffer("""{"available": false, "reason": "cap_global", "text": "pas d'offre scout payée"}""")!!
        assertFalse(off.available); assertEquals("pas d'offre scout payée", off.line)
        assertEquals(PlacesView.NO_OFFER_TEXT, off.line)
        assertNull(PlacesView.parseOffer("{}"))
    }

    @Test fun the_flat_reader_survives_braces_inside_strings_and_floats() {
        val t = """{"venues": [{"id": "a", "name": "Chez {Marie}", "lat": -4.5, "lon": 15.0, "status": "REPORTED", "hours": ""}, {"id": "b", "name": "x]y", "lat": 0, "lon": 0}]}"""
        val cs = PlacesView.objects(t, "venues")
        assertEquals(2, cs.size)
        assertEquals("Chez {Marie}", PlacesView.str(cs[0], "name"))
        assertEquals(-4.5, PlacesView.dbl(cs[0], "lat"), 1e-9)
        assertEquals("REPORTED", PlacesView.parseCard(cs[0])!!.status)
        assertFalse(PlacesView.parseCard(cs[1])!!.hasEntrance)
        assertNotNull(PlacesView.parseCard(cs[1]))
    }
}
