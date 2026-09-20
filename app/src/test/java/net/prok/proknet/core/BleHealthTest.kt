package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.10. After a 70 minute Wi-Fi Direct session ended, two phones sitting
 * side by side saw nobody: the known peer expired after 25 s, every control
 * message said "no transport", two GATT reconnects timed out, and the
 * diagnostic still claimed "server ready, adv on, scan on". These are the
 * rules that tell a wedged radio from a quiet one, and that stop the cure
 * from being worse than the disease.
 */
class BleHealthTest {
    private val t0 = 1_700_000_000_000L

    private fun state(
        now: Long = t0 + 60_000,
        running: Boolean = true,
        bluetoothOn: Boolean = true,
        advertising: Boolean = true,
        advertiseFailedAt: Long = 0,
        scanning: Boolean = true,
        scanFailedAt: Long = 0,
        lastScanResultAt: Long = t0 + 59_000,
        startedAt: Long = t0,
        gattTimeouts: Int = 0,
        expectPeers: Boolean = true,
        linkBusy: Boolean = false,
        sessionEndedAt: Long = 0,
        lastRecoveryAt: Long = 0,
        recoveries: Int = 0,
        bluetoothReturnedAt: Long = 0,
    ) = BleHealth.State(now, running, bluetoothOn, advertising, advertiseFailedAt, scanning, scanFailedAt,
        lastScanResultAt, startedAt, gattTimeouts, expectPeers, linkBusy, sessionEndedAt, lastRecoveryAt, recoveries, bluetoothReturnedAt)

    @Test
    fun a_working_radio_is_left_alone() {
        val s = state()
        assertEquals(BleHealth.Verdict.HEALTHY, BleHealth.verdict(s))
        assertEquals(BleHealth.Action.NONE, BleHealth.action(BleHealth.verdict(s)))
        // a phone alone in a field sees nothing for hours and that is NOT a fault
        val alone = state(now = t0 + 3_600_000, lastScanResultAt = 0, expectPeers = false)
        assertEquals(BleHealth.Verdict.HEALTHY, BleHealth.verdict(alone))
        // and a freshly started radio is given time to settle
        val fresh = state(now = t0 + 5_000, lastScanResultAt = 0, advertising = false)
        assertEquals(BleHealth.Verdict.HEALTHY, BleHealth.verdict(fresh))
    }

    @Test
    fun the_session_that_ended_is_the_prime_suspect() {
        // exactly the report: the Wi-Fi Direct session ended, nothing has been discovered since,
        // and a peer we know is expected to be right there
        val after = state(now = t0 + 100_000, sessionEndedAt = t0 + 80_000, lastScanResultAt = t0 + 80_000, startedAt = t0)
        assertEquals(BleHealth.Verdict.SCAN_STALE, BleHealth.verdict(after))
        assertEquals(BleHealth.Action.RECOVER, BleHealth.action(BleHealth.verdict(after)))
        // without a session behind us the same silence waits longer before being called a fault
        val quiet = state(now = t0 + 100_000, lastScanResultAt = t0 + 80_000)
        assertEquals(BleHealth.Verdict.HEALTHY, BleHealth.verdict(quiet))
        assertEquals(BleHealth.Verdict.SCAN_STALE, BleHealth.verdict(quiet.let { state(now = t0 + 130_000, lastScanResultAt = t0 + 80_000) }))
    }

    @Test
    fun a_peer_that_expired_still_counts_as_expected_company() {
        // the seller expired after 25 s of silence but we saw it a minute ago: we should see it
        assertTrue(BleHealth.expectPeers(msSinceAnyPeerSeen = 60_000, buying = false, selling = false))
        assertTrue(BleHealth.expectPeers(BleHealth.PEER_MEMORY_MS - 1, false, false))
        assertFalse(BleHealth.expectPeers(BleHealth.PEER_MEMORY_MS + 1, false, false))
        // and trading always means company is expected, even on a phone that never saw anybody
        assertTrue(BleHealth.expectPeers(-1, buying = true, selling = false))
        assertTrue(BleHealth.expectPeers(-1, buying = false, selling = true))
        assertFalse(BleHealth.expectPeers(-1, buying = false, selling = false))
    }

    @Test
    fun two_gatt_timeouts_with_nothing_being_discovered_is_the_wedged_pattern() {
        val wedged = state(now = t0 + 90_000, lastScanResultAt = t0 + 20_000, gattTimeouts = 2)
        assertEquals(BleHealth.Verdict.SCAN_STALE, BleHealth.verdict(wedged))   // silence alone already calls it
        // one timeout while results keep arriving is just a peer that walked away
        val walkedAway = state(now = t0 + 90_000, lastScanResultAt = t0 + 89_000, gattTimeouts = 1)
        assertEquals(BleHealth.Verdict.HEALTHY, BleHealth.verdict(walkedAway))
        val twoButAlive = state(now = t0 + 90_000, lastScanResultAt = t0 + 89_000, gattTimeouts = 2)
        assertEquals("results are flowing, so the radio is not wedged", BleHealth.Verdict.HEALTHY, BleHealth.verdict(twoButAlive))
        assertTrue(BleHealth.GATT_TIMEOUTS_WEDGED == 2)
    }

    @Test
    fun advertising_that_never_confirmed_is_a_fault_even_when_scanning_works() {
        val s = state(now = t0 + 60_000, advertising = false, lastScanResultAt = t0 + 59_000)
        assertEquals(BleHealth.Verdict.ADVERTISING_STALE, BleHealth.verdict(s))
        assertEquals(BleHealth.Action.RECOVER, BleHealth.action(BleHealth.verdict(s)))
        // both broken at once is reported as both
        val both = state(now = t0 + 120_000, advertising = false, scanning = false)
        assertEquals(BleHealth.Verdict.BOTH_STALE, BleHealth.verdict(both))
    }

    @Test
    fun a_live_link_is_never_interrupted() {
        // an authenticated Wi-Fi or Wi-Fi Direct link, or a group being formed: hands off
        val busy = state(now = t0 + 300_000, lastScanResultAt = 0, advertising = false, scanning = false, linkBusy = true)
        assertEquals(BleHealth.Verdict.BUSY, BleHealth.verdict(busy))
        assertEquals(BleHealth.Action.NONE, BleHealth.action(BleHealth.verdict(busy)))
        // and nothing is attempted when the radio is off or the node is stopped
        assertEquals(BleHealth.Verdict.BLUETOOTH_OFF, BleHealth.verdict(state(bluetoothOn = false, advertising = false)))
        assertEquals(BleHealth.Verdict.NOT_RUNNING, BleHealth.verdict(state(running = false, advertising = false)))
        assertEquals(BleHealth.Action.NONE, BleHealth.action(BleHealth.Verdict.BLUETOOTH_OFF))
    }

    @Test
    fun repeated_failures_back_off_instead_of_looping() {
        val justRecovered = state(now = t0 + 200_000, lastRecoveryAt = t0 + 190_000, recoveries = 1, advertising = false, scanning = false)
        assertEquals(BleHealth.Verdict.COOLING_DOWN, BleHealth.verdict(justRecovered))
        assertEquals(BleHealth.Action.NONE, BleHealth.action(BleHealth.verdict(justRecovered)))

        // after the cooldown the same fault is acted on again
        val later = state(now = t0 + 400_000, lastRecoveryAt = t0 + 190_000, recoveries = 1, advertising = false, scanning = false,
            startedAt = t0 + 190_000, lastScanResultAt = 0)
        assertEquals(BleHealth.Verdict.BOTH_STALE, BleHealth.verdict(later))

        // and the wait grows with each attempt, capped
        assertEquals(0L, BleHealth.cooldownMs(0))
        assertEquals(BleHealth.COOLDOWN_MS, BleHealth.cooldownMs(1))
        assertEquals(BleHealth.COOLDOWN_MS * 2, BleHealth.cooldownMs(2))
        assertEquals(BleHealth.COOLDOWN_MS * 4, BleHealth.cooldownMs(3))
        assertEquals(BleHealth.COOLDOWN_MAX_MS, BleHealth.cooldownMs(20))
        for (v in BleHealth.Verdict.values()) assertTrue(BleHealth.verdictText(v).isNotEmpty())
    }

    // ---- v0.13.1: the phone that went quiet after Bluetooth came back --------------------------------

    @Test
    fun bluetooth_off_then_on_recovers_the_radio_exactly_once() {
        // the OnePlus log: "Bluetooth is off" at 15:30, "healthy" at 15:58, adv on, scan on, peers 0,
        // the last scan result thousands of seconds old, and nothing ever restarted. The stale flags
        // survive the adapter restart, and the last peer was 55 minutes old so nobody was "expected".
        val off = state(bluetoothOn = false)
        assertEquals(BleHealth.Verdict.BLUETOOTH_OFF, BleHealth.verdict(off))
        assertEquals(BleHealth.Action.NONE, BleHealth.action(BleHealth.verdict(off)))

        val returned = t0 + 100_000
        val back = state(now = returned + BleHealth.GRACE_MS, bluetoothReturnedAt = returned, expectPeers = false,
            lastScanResultAt = t0 - 55 * 60_000, startedAt = t0 - 60 * 60_000)
        assertEquals(BleHealth.Verdict.BLUETOOTH_RETURNED, BleHealth.verdict(back))
        assertEquals(BleHealth.Action.RECOVER, BleHealth.action(BleHealth.verdict(back)))

        // exactly once: after the recovery the same return is not a reason any more
        val after = state(now = returned + BleHealth.GRACE_MS + 1000, bluetoothReturnedAt = returned,
            lastRecoveryAt = returned + BleHealth.GRACE_MS, recoveries = 1, expectPeers = false, lastScanResultAt = returned)
        assertEquals(BleHealth.Action.NONE, BleHealth.action(BleHealth.verdict(after)))
        // and well past the backoff it is simply healthy again
        assertEquals(BleHealth.Verdict.HEALTHY, BleHealth.verdict(state(now = returned + 10 * 60_000, bluetoothReturnedAt = returned,
            lastRecoveryAt = returned + BleHealth.GRACE_MS, recoveries = 1, expectPeers = false, lastScanResultAt = returned + 9 * 60_000)))

        // and it is given a moment to settle before being judged
        assertEquals(BleHealth.Verdict.HEALTHY, BleHealth.verdict(state(now = returned + 1000, bluetoothReturnedAt = returned, expectPeers = false)))
        // never during a link, and never when the node is stopped
        assertEquals(BleHealth.Verdict.BUSY, BleHealth.verdict(back.let { state(now = it.now, bluetoothReturnedAt = returned, linkBusy = true, expectPeers = false) }))
        assertEquals(BleHealth.Verdict.NOT_RUNNING, BleHealth.verdict(state(now = returned + 60_000, bluetoothReturnedAt = returned, running = false)))
    }

    @Test
    fun a_scanner_that_has_heard_nothing_for_ten_minutes_restarts_even_with_nobody_expected() {
        val quiet = t0 + 3 * BleHealth.SILENT_MAX_MS
        // nobody expected (the last peer is an hour old), scanning claimed on, and total silence
        val s = state(now = quiet, expectPeers = false, lastScanResultAt = t0, startedAt = t0)
        assertEquals(BleHealth.Verdict.SCAN_SILENT, BleHealth.verdict(s))
        assertEquals(BleHealth.Action.RECOVER, BleHealth.action(BleHealth.verdict(s)))
        // a phone that never heard anybody is alone in a field, not wedged: left alone forever
        assertEquals(BleHealth.Verdict.HEALTHY, BleHealth.verdict(state(now = quiet, expectPeers = false, lastScanResultAt = 0, startedAt = t0)))
        // a result a minute ago is not silence
        assertEquals(BleHealth.Verdict.HEALTHY, BleHealth.verdict(state(now = quiet, expectPeers = false, lastScanResultAt = quiet - 60_000, startedAt = t0)))
        // a young node is not judged on this
        assertEquals(BleHealth.Verdict.HEALTHY, BleHealth.verdict(state(now = t0 + BleHealth.SILENT_MAX_MS / 2, expectPeers = false, lastScanResultAt = 0, startedAt = t0)))
        // the backoff still applies, so it cannot loop
        assertEquals(BleHealth.Verdict.COOLING_DOWN, BleHealth.verdict(state(now = quiet, expectPeers = false, lastScanResultAt = t0, startedAt = t0,
            lastRecoveryAt = quiet - 30_000, recoveries = 3)))
        // and an active link is still untouchable
        assertEquals(BleHealth.Verdict.BUSY, BleHealth.verdict(state(now = quiet, expectPeers = false, lastScanResultAt = t0, startedAt = t0, linkBusy = true)))
    }
}
