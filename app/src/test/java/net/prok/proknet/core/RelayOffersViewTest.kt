package net.prok.proknet.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.19.0: the relay's offers screen against the SHARED fixture the server writes
 * (server/tests/fixtures/relay_offer_states.txt). The state words come from the Brain;
 * the phone shows exactly the fixture's text - and "Vérifié — gagné" only for COMPLETED.
 */
class RelayOffersViewTest {

    private fun fixture(name: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val f = File(dir, "server/tests/fixtures/" + name)
            if (f.isFile) return f
            dir = dir.parentFile
        }
        throw AssertionError("server/tests/fixtures/" + name + " not found from " + File(".").absolutePath)
    }

    @Test
    fun everyServerStateReadsExactlyTheFixtureText() {
        val lines = fixture("relay_offer_states.txt").readLines(Charsets.UTF_8).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        assertTrue(lines.size >= 8)
        for (line in lines) {
            val (state, text) = line.split('|', limit = 2)
            assertEquals(state, text, RelayOffersView.statusText(state))
        }
        // the one sentence that means money was verified is said for COMPLETED and nothing else
        val earned = lines.first { it.startsWith("COMPLETED|") }.substringAfter('|')
        for (line in lines) {
            val (state, text) = line.split('|', limit = 2)
            if (state != "COMPLETED") assertFalse(state, text == earned)
        }
        assertEquals(RelayOffersView.UNKNOWN_TEXT, RelayOffersView.statusText("SOMETHING_NEW"))
    }

    private val offered = """{"id":"o1","kind":"STAY","zone":"Poto-Poto","amount":2000,"extra":0,"state":"OFFERED","created_at":1000,"accepted_at":0,
        "expires_at":900000,"ended_at":0,"earned":0,"battery_floor_pct":20,"min_payout_centimes":0,"window_start_min":480,"window_end_min":1320,
        "block_minutes":30,"blocks_per_day":4,"probes_required":3,"probes_max_failed":1,"pipeline_stage":0,"rendezvous_cell":""}"""
    private val running = """{"id":"o2","kind":"STAY","zone":"Bacongo","amount":2000,"extra":0,"state":"IN_PROGRESS","created_at":1000,"accepted_at":2000,
        "expires_at":900000,"ended_at":0,"earned":0,"battery_floor_pct":20,"min_payout_centimes":0,"window_start_min":0,"window_end_min":1440,
        "block_minutes":30,"blocks_per_day":4,"probes_required":3,"probes_max_failed":1,"pipeline_stage":0,"block_started_at":5000,"block_state":"RUNNING",
        "probes_ok":2,"probes_failed":0,"block_elapsed_ms":600000,"fail_reason":""}"""
    private val move = """{"id":"o3","kind":"MOVE","zone":"Moungali","amount":10000,"extra":0,"state":"IN_PROGRESS","created_at":1000,"accepted_at":2000,
        "expires_at":900000,"ended_at":0,"earned":0,"battery_floor_pct":30,"min_payout_centimes":0,"window_start_min":0,"window_end_min":1440,
        "block_minutes":30,"blocks_per_day":4,"probes_required":3,"probes_max_failed":1,"pipeline_stage":0,"rendezvous_cell":"c-4.27-15.28",
        "departed_at":3000,"arrived_at":0,"ready_at":0,"confirmed_at":0,"compensation":0}"""
    private val done = """{"id":"o4","kind":"STAY","zone":"Bacongo","amount":2000,"extra":0,"state":"COMPLETED","created_at":1000,"accepted_at":2000,
        "expires_at":900000,"ended_at":8000,"earned":2000,"battery_floor_pct":20,"min_payout_centimes":0,"window_start_min":0,"window_end_min":1440,
        "block_minutes":30,"blocks_per_day":4,"probes_required":3,"probes_max_failed":1,"pipeline_stage":2}"""

    private fun mine(vararg offers: String, blocksToday: Int = 1): String =
        """{"offers":[""" + offers.joinToString(",") + """],"settings":{"min_payout_centimes":500,"battery_floor_pct":25,"window_start_min":480,"window_end_min":1320,"available":true},
           "blocks_today":$blocksToday,"blocks_per_day":4,"pipeline":{"pending_centimes":2000,"earned_centimes":6000}}"""

    @Test
    fun parsesOffersSettingsCapAndPipeline() {
        val m = RelayOffersView.parse(mine(offered, running, done), 10_000)!!
        assertEquals(3, m.offers.size)
        assertEquals(500L, m.settings.minPayoutCentimes); assertEquals(25, m.settings.batteryFloorPct)
        assertEquals("08:00", RelayOffersView.Settings.hhmm(m.settings.windowStartMin)); assertEquals("22:00", RelayOffersView.Settings.hhmm(m.settings.windowEndMin))
        assertEquals("Blocs aujourd'hui : 1 / 4", m.capLine)
        assertEquals(2000L, m.pipeline.pendingCentimes); assertEquals(6000L, m.pipeline.earnedCentimes)
        assertEquals("o2", m.current!!.id)
        assertNull(RelayOffersView.parse("", 0)); assertNull(RelayOffersView.parse("{\"error\":\"nope\"}", 0))
    }

    @Test
    fun theButtonsFollowTheStateMachine() {
        val o = RelayOffersView.parseOffer(offered)!!
        assertTrue(o.canAccept); assertTrue(o.canDecline); assertFalse(o.canStartBlock); assertFalse(o.blockRunning); assertTrue(o.open)
        val r = RelayOffersView.parseOffer(running)!!
        assertFalse(r.canAccept); assertFalse(r.canDecline); assertTrue(r.blockRunning); assertEquals(2, r.probesOk); assertEquals("RUNNING", r.blockState)
        val mv = RelayOffersView.parseOffer(move)!!
        assertFalse(mv.canDepart); assertTrue(mv.canArrive); assertFalse(mv.canSayReady); assertTrue(mv.canCancel); assertEquals("c-4.27-15.28", mv.rendezvousCell)
        val d = RelayOffersView.parseOffer(done)!!
        assertFalse(d.open); assertEquals(2000L, d.earnedCentimes); assertEquals("Vérifié — gagné", d.text); assertEquals(2, d.pipelineStage)
        assertNull(RelayOffersView.parseOffer("{\"kind\":\"STAY\"}"))
    }

    @Test
    fun aPendingJobIsNeverCash() {
        // the pipeline sentence for a job the Brain has not verified must not say "earned"
        val pending = RelayOffersView.pipelineText(0)
        val earned = RelayOffersView.pipelineText(2)
        assertTrue(pending, pending.contains("Contrat en attente"))
        assertFalse(pending, pending.contains("Payé") && !pending.contains("→") && pending.startsWith("Payé"))
        assertTrue(earned, earned.contains("Gagné"))
        assertEquals(RelayOffersView.PIPELINE.size, 5)
        assertNotNull(RelayOffersView.pipelineText(-1))
    }

    @Test
    fun settingsParseAndRoundTrip() {
        assertEquals(1440, RelayOffersView.Settings.DEFAULT.windowEndMin)
        assertEquals(0, RelayOffersView.Settings.parseHhmm("00:00")); assertEquals(1320, RelayOffersView.Settings.parseHhmm("22h00"))
        assertEquals(510, RelayOffersView.Settings.parseHhmm("8:30")); assertNull(RelayOffersView.Settings.parseHhmm("abc"))
        val s = RelayOffersView.parseSettings("""{"min_payout_centimes":0,"battery_floor_pct":15,"window_start_min":0,"available":false}""")
        assertEquals(1440, s.windowEndMin); assertFalse(s.available)
        assertTrue(s.describe().isNotEmpty())
    }
}
