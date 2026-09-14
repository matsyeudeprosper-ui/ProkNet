package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.6. A seller phone was refused its hotspot while it was joined to a
 * Wi-Fi router, and worked as soon as it shared mobile data instead. These
 * are the rules that decide when to test, what the answer means, and how a
 * failure is recorded so the band can be blamed later.
 */
class ShareCheckTest {

    @Test
    fun only_a_wifi_upstream_has_to_be_tested() {
        assertTrue(ShareCheck.needed(Tunnel.UP_WIFI))
        assertFalse(ShareCheck.needed(Tunnel.UP_CELLULAR))
        assertFalse(ShareCheck.needed(Tunnel.UP_NONE))
        assertFalse(ShareCheck.needed(Tunnel.UP_OTHER))
        // mobile data: no test, and the phone is allowed to share
        assertEquals(ShareCheck.Result.NOT_NEEDED, ShareCheck.verdict(Tunnel.UP_CELLULAR, hotspotStarted = false))
        assertEquals(true, ShareCheck.canShareWhileOnWifi(ShareCheck.verdict(Tunnel.UP_CELLULAR, false)))
    }

    @Test
    fun the_test_runs_once_per_network_and_only_a_real_failure_is_a_refusal() {
        assertTrue(ShareCheck.shouldProbe(Tunnel.UP_WIFI, ShareCheck.Result.UNKNOWN))
        assertFalse(ShareCheck.shouldProbe(Tunnel.UP_WIFI, ShareCheck.Result.CAN_SHARE))
        assertFalse(ShareCheck.shouldProbe(Tunnel.UP_WIFI, ShareCheck.Result.CANNOT_SHARE))
        assertFalse(ShareCheck.shouldProbe(Tunnel.UP_CELLULAR, ShareCheck.Result.UNKNOWN))

        assertEquals(ShareCheck.Result.CAN_SHARE, ShareCheck.verdict(Tunnel.UP_WIFI, hotspotStarted = true))
        assertEquals(ShareCheck.Result.CANNOT_SHARE, ShareCheck.verdict(Tunnel.UP_WIFI, hotspotStarted = false))

        // unknown is NOT a refusal: only a tested failure removes the phone from Wi-Fi reselling
        assertNull(ShareCheck.canShareWhileOnWifi(ShareCheck.Result.UNKNOWN))
        assertEquals(true, ShareCheck.canShareWhileOnWifi(ShareCheck.Result.CAN_SHARE))
        assertEquals(false, ShareCheck.canShareWhileOnWifi(ShareCheck.Result.CANNOT_SHARE))
    }

    @Test
    fun the_answer_is_remembered_per_network_not_per_phone() {
        val a = ShareCheck.key("Maison", "a4:b1:c2:00:11:22")
        val b = ShareCheck.key("Boutique", "a4:b1:c2:00:11:33")
        assertFalse(a == b)
        // the BSSID wins, so two routers with the same name stay separate
        assertEquals(a, ShareCheck.key("autre nom", "A4:B1:C2:00:11:22"))
        // no BSSID (Android redacts it without permission): fall back to the name
        assertEquals("ssid:Maison", ShareCheck.key("Maison", null))
        assertEquals("ssid:Maison", ShareCheck.key("Maison", "02:00:00:00:00:00"))
        assertEquals("unknown", ShareCheck.key(null, null))
        assertEquals("unknown", ShareCheck.key("<unknown ssid>", ""))
    }

    @Test
    fun the_band_and_channel_are_recorded_so_failures_can_be_correlated() {
        assertEquals("2.4 GHz", ShareCheck.band(2412)); assertEquals(1, ShareCheck.channel(2412))
        assertEquals("2.4 GHz", ShareCheck.band(2437)); assertEquals(6, ShareCheck.channel(2437))
        assertEquals(14, ShareCheck.channel(2484))
        assertEquals("5 GHz", ShareCheck.band(5180)); assertEquals(36, ShareCheck.channel(5180))
        assertEquals("5 GHz DFS", ShareCheck.band(5300)); assertEquals(60, ShareCheck.channel(5300))
        assertTrue(ShareCheck.isDfs(5500)); assertFalse(ShareCheck.isDfs(5180)); assertFalse(ShareCheck.isDfs(2412))
        assertEquals("5 GHz", ShareCheck.band(5825))
        assertEquals("6 GHz", ShareCheck.band(6135))
        assertEquals("unknown", ShareCheck.band(0)); assertEquals(0, ShareCheck.channel(0))
        assertEquals("unknown band", ShareCheck.describe(0))
        assertTrue(ShareCheck.describe(5300).contains("5 GHz DFS ch 60"))
    }
}
