package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.19: the map pack download rules from the launch contract (10.2), readable back.
 *
 * Show the real size before downloading; Wi-Fi by default, cellular only on request;
 * "last refreshed" in words; never start what the storage cannot hold.
 */
class MapPackStateTest {

    private val now = 1_790_500_000_000L
    private val MB = 1024L * 1024

    /** The shape `tools.build_map_pack` writes, nested objects included. */
    private val manifestJson = """{
      "city": "brazzaville", "city_name": "Brazzaville", "format_version": 1,
      "version": 1790444973, "generated_at": 1790444973000, "generated_at_iso": "2026-09-26T17:49:33Z",
      "source": {"file": "congo-brazzaville-latest.osm.pbf", "bytes": 32620511, "last_modified": "Fri, 25 Sep 2026 22:28:26 GMT"},
      "bytes": 3027460, "sha256": "268e9dfdc1ead4e723ba1828e158720c5648c9adda10fc9e6c555b69f8f611ef",
      "attribution": "© OpenStreetMap contributors, ODbL 1.0",
      "counts": {"nodes": 133784, "edges": 149202, "names": 478, "places": 281},
      "dropped": {"names_beyond_u16": 0}
    }"""

    private fun remote(version: Long = 1790444973L, bytes: Long = 3_027_460L, sha: String = "a".repeat(64)) =
        MapPackState.Manifest("brazzaville", "Brazzaville", version, version * 1000, bytes, sha, "© OpenStreetMap contributors, ODbL 1.0")

    private fun installed(version: Long = 1790444973L, sha: String = "a".repeat(64), refreshedAt: Long = now - 3 * 3_600_000L) =
        MapPackState.Installed(version, version * 1000, 3_027_460L, sha, refreshedAt)

    // ================= the manifest =================

    @Test fun manifest_is_read_from_the_top_level_even_when_source_comes_first() {
        val m = MapPackState.parseManifest(manifestJson)
        assertNotNull(m); m!!
        assertEquals("brazzaville", m.city)
        assertEquals("Brazzaville", m.cityName)
        assertEquals(1790444973L, m.version)
        assertEquals(1790444973000L, m.generatedAt)
        assertEquals(3_027_460L, m.bytes)                  // NOT the 32 MB of the source extract
        assertEquals("268e9dfdc1ead4e723ba1828e158720c5648c9adda10fc9e6c555b69f8f611ef", m.sha256)
        assertEquals("© OpenStreetMap contributors, ODbL 1.0", m.attribution)
        // a manifest where the nested object is written BEFORE the top-level bytes
        val reordered = """{"city":"x","source":{"bytes":999,"version":1},"version":7,"bytes":5,"sha256":"${"b".repeat(64)}"}"""
        val r = MapPackState.parseManifest(reordered)!!
        assertEquals(5L, r.bytes); assertEquals(7L, r.version)
    }

    @Test fun a_manifest_without_a_size_or_a_hash_is_no_manifest() {
        assertNull(MapPackState.parseManifest("""{"city":"brazzaville","version":3,"sha256":"${"a".repeat(64)}"}"""))
        assertNull(MapPackState.parseManifest("""{"city":"brazzaville","version":3,"bytes":100,"sha256":"short"}"""))
        assertNull(MapPackState.parseManifest("""{"version":3,"bytes":100,"sha256":"${"a".repeat(64)}"}"""))
        assertNull(MapPackState.parseManifest("not json"))
    }

    @Test fun top_level_only_keeps_strings_with_braces_intact() {
        val top = MapPackState.topLevelOnly("""{"a":"{x}","n":{"b":2,"c":[1,2]},"b":1,"d":[{"b":3}]}""")
        assertEquals("{x}", BrainPayload.field(top, "a"))
        assertEquals("1", BrainPayload.field(top, "b"))
        assertFalse(top.contains("2"))
        assertFalse(top.contains("3"))
        assertEquals("", BrainPayload.field(top, "c"))
    }

    // ================= the decision =================

    @Test fun version_comparison() {
        assertEquals(MapPackState.Decision.NOTHING, MapPackState.compare(null, null))
        assertEquals(MapPackState.Decision.DOWNLOAD, MapPackState.compare(null, remote()))
        assertEquals(MapPackState.Decision.CURRENT, MapPackState.compare(installed(), null))
        assertEquals(MapPackState.Decision.CURRENT, MapPackState.compare(installed(), remote()))
        assertEquals(MapPackState.Decision.UPDATE, MapPackState.compare(installed(), remote(version = 1790444974L, sha = "c".repeat(64))))
        // an older server copy is never an "update"
        assertEquals(MapPackState.Decision.CURRENT, MapPackState.compare(installed(), remote(version = 1790000000L, sha = "c".repeat(64))))
        // a bumped version with identical bytes is not worth 3 MB
        assertEquals(MapPackState.Decision.CURRENT, MapPackState.compare(installed(), remote(version = 1790444999L)))
    }

    @Test fun wifi_by_default_cellular_only_when_asked() {
        assertTrue(MapPackState.mayDownload(onWifi = true, cellularRequested = false))
        assertFalse(MapPackState.mayDownload(onWifi = false, cellularRequested = false))
        assertTrue(MapPackState.mayDownload(onWifi = false, cellularRequested = true))
    }

    @Test fun storage_needs_the_pack_plus_the_margin() {
        assertEquals(3_027_460L + 20 * MB, MapPackState.storageNeeded(3_027_460L))
        assertTrue(MapPackState.storageOk(3_027_460L, 30 * MB))
        assertFalse(MapPackState.storageOk(3_027_460L, 22 * MB))
    }

    // ================= the words =================

    @Test fun size_is_the_real_number_in_french() {
        assertEquals("3,0 Mo", MapPackState.sizeText(3_027_460L))
        assertEquals("12,3 Mo", MapPackState.sizeText(12_345_678L))
        assertEquals("49,9 Mo", MapPackState.sizeText(49_949_999L))
        assertEquals("353 o", MapPackState.sizeText(353L))
        assertEquals("2 Ko", MapPackState.sizeText(1_500L))
        assertEquals("1,0 Mo", MapPackState.sizeText(1_000_000L))
    }

    @Test fun last_refreshed_in_words() {
        assertEquals("Carte jamais téléchargée", MapPackState.lastRefreshedText(0L, now))
        assertEquals("Carte mise à jour à l'instant", MapPackState.lastRefreshedText(now - 10_000L, now))
        assertEquals("Carte mise à jour il y a 5 min", MapPackState.lastRefreshedText(now - 5 * 60_000L, now))
        assertEquals("Carte mise à jour il y a 3 h", MapPackState.lastRefreshedText(now - 3 * 3_600_000L, now))
        assertEquals("Carte mise à jour hier", MapPackState.lastRefreshedText(now - 30 * 3_600_000L, now))
        assertEquals("Carte mise à jour il y a 12 jours", MapPackState.lastRefreshedText(now - 12 * 24 * 3_600_000L, now))
    }

    @Test fun the_plan_shows_the_size_before_any_download() {
        val p = MapPackState.plan(null, remote(), onWifi = true, cellularRequested = false, freeBytes = 500 * MB, now = now)
        assertEquals(MapPackState.Decision.DOWNLOAD, p.decision)
        assertTrue(p.allowedNow)
        assertEquals("Télécharger la carte de Brazzaville (3,0 Mo)", p.action)
        assertTrue(p.text, p.text.startsWith("3,0 Mo sur Wi-Fi"))
        assertTrue(p.text, p.text.contains("jamais téléchargée"))
    }

    @Test fun off_wifi_the_button_says_cellular_and_waits_for_the_person() {
        val p = MapPackState.plan(null, remote(), onWifi = false, cellularRequested = false, freeBytes = 500 * MB, now = now)
        assertFalse(p.allowedNow)
        assertTrue(p.action, p.action.contains("données mobiles"))
        assertTrue(p.text, p.text.contains("appuyez pour télécharger sur données mobiles"))
        val asked = MapPackState.plan(null, remote(), onWifi = false, cellularRequested = true, freeBytes = 500 * MB, now = now)
        assertTrue(asked.allowedNow)
        assertTrue(asked.text, asked.text.contains("à votre demande"))
    }

    @Test fun an_update_is_worded_as_one_and_shows_when_the_old_one_was_fetched() {
        val p = MapPackState.plan(installed(), remote(version = 1790444974L, sha = "c".repeat(64)), onWifi = true, cellularRequested = false, freeBytes = 500 * MB, now = now)
        assertEquals(MapPackState.Decision.UPDATE, p.decision)
        assertEquals("Mettre à jour la carte de Brazzaville (3,0 Mo)", p.action)
        assertTrue(p.text, p.text.contains("il y a 3 h"))
    }

    @Test fun no_space_means_no_button_and_an_honest_line() {
        val p = MapPackState.plan(null, remote(), onWifi = true, cellularRequested = false, freeBytes = 10 * MB, now = now)
        assertFalse(p.allowedNow); assertFalse(p.storageOk)
        assertEquals("", p.action)
        assertTrue(p.text, p.text.startsWith("Pas assez d'espace"))
        assertTrue(p.text, p.text.contains("10,5 Mo"))    // what is free, the real number
    }

    @Test fun current_says_when_and_flags_an_old_pack() {
        val fresh = MapPackState.plan(installed(), remote(), onWifi = false, cellularRequested = false, freeBytes = 0, now = now)
        assertEquals(MapPackState.Decision.CURRENT, fresh.decision)
        assertEquals("", fresh.action)
        assertEquals("Carte mise à jour il y a 3 h", fresh.text)
        val old = MapPackState.plan(installed(refreshedAt = now - 90L * 24 * 3_600_000L), null, onWifi = false, cellularRequested = false, freeBytes = 0, now = now)
        assertTrue(old.text, old.text.contains("ancienne"))
        val none = MapPackState.plan(null, null, onWifi = true, cellularRequested = false, freeBytes = 0, now = now)
        assertEquals(MapPackState.Decision.NOTHING, none.decision)
        assertTrue(none.text.contains("liste des lieux reste utilisable"))
    }
}
