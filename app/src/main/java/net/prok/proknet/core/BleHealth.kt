package net.prok.proknet.core

/**
 * Is the BLE radio really working? (v0.9.10, pure.)
 *
 * Phone evidence: after a 70 minute Wi-Fi Direct Internet session ended
 * ("connection closed: end of stream"), both phones sat side by side and
 * saw nobody. The known seller expired 25 s later, every control message
 * said "no transport", two GATT reconnects timed out after 20 s each, and
 * the diagnostic still claimed `server ready, adv on, scan on`.
 *
 * So the requested state is not the truth. This object judges the REAL
 * state from what the callbacks actually reported, and decides when a
 * controlled restart of the scanner and the advertiser is justified. It
 * never restarts the node, the GATT server, the queue or an active link,
 * and it backs off instead of looping.
 */
object BleHealth {

    /** No scan result at all for this long, while peers are expected, means the scanner is wedged. */
    const val SCAN_STALE_MS = 40_000L
    /** Right after a Wi-Fi / Wi-Fi Direct session the stack is the prime suspect: look sooner. */
    const val SCAN_STALE_AFTER_SESSION_MS = 15_000L
    /** Advertising asked for but never confirmed by the callback. */
    const val ADV_STALE_MS = 15_000L
    /** How long a phone we have seen keeps us expecting to see somebody. */
    const val PEER_MEMORY_MS = 15 * 60_000L
    /** Two GATT timeouts in a row with no fresh scan results is the wedged pattern from the report. */
    const val GATT_TIMEOUTS_WEDGED = 2
    const val COOLDOWN_MS = 60_000L
    const val COOLDOWN_MAX_MS = 300_000L
    /** A fresh start needs a moment before anything can be called stale. */
    const val GRACE_MS = 12_000L

    class State(
        val now: Long,
        val running: Boolean,
        val bluetoothOn: Boolean,
        /** Advertising confirmed by onStartSuccess. */
        val advertising: Boolean,
        val advertiseFailedAt: Long,
        /** startScan was accepted and no failure has been reported since. */
        val scanning: Boolean,
        val scanFailedAt: Long,
        /** When the last scan result of any kind arrived, 0 = never. */
        val lastScanResultAt: Long,
        /** When the radio was started or last recovered. */
        val startedAt: Long,
        val gattTimeouts: Int,
        /** Somebody is expected to be around: a peer was seen recently, or we are buying or selling. */
        val expectPeers: Boolean,
        /** An authenticated link or a Wi-Fi Direct setup is in progress: never touch the radio. */
        val linkBusy: Boolean,
        /** A Wi-Fi / P2P session ended at this time, 0 = not recently. */
        val sessionEndedAt: Long,
        val lastRecoveryAt: Long,
        val recoveries: Int,
    )

    enum class Verdict { HEALTHY, NOT_RUNNING, BLUETOOTH_OFF, BUSY, COOLING_DOWN, ADVERTISING_STALE, SCAN_STALE, BOTH_STALE, GATT_WEDGED }

    enum class Action { NONE, RECOVER }

    /** Peers are expected when we are trading, or when somebody was around recently. */
    fun expectPeers(msSinceAnyPeerSeen: Long, buying: Boolean, selling: Boolean): Boolean =
        buying || selling || (msSinceAnyPeerSeen in 0..PEER_MEMORY_MS)

    /** The backoff: one minute, then double, capped at five. */
    fun cooldownMs(recoveries: Int): Long {
        if (recoveries <= 0) return 0
        var ms = COOLDOWN_MS
        repeat(minOf(recoveries - 1, 5)) { ms *= 2 }
        return minOf(ms, COOLDOWN_MAX_MS)
    }

    private fun scanStaleMs(s: State): Long =
        if (s.sessionEndedAt > 0 && s.now - s.sessionEndedAt < 2 * 60_000L) SCAN_STALE_AFTER_SESSION_MS else SCAN_STALE_MS

    private fun since(now: Long, at: Long): Long = if (at <= 0) Long.MAX_VALUE else now - at

    fun verdict(s: State): Verdict {
        if (!s.running) return Verdict.NOT_RUNNING
        if (!s.bluetoothOn) return Verdict.BLUETOOTH_OFF
        // never interrupt a working link or a group being formed
        if (s.linkBusy) return Verdict.BUSY
        if (s.recoveries > 0 && s.now - s.lastRecoveryAt < cooldownMs(s.recoveries)) return Verdict.COOLING_DOWN
        // let a fresh start settle before judging it
        val settling = s.now - maxOf(s.startedAt, s.lastRecoveryAt) < GRACE_MS
        if (settling) return Verdict.HEALTHY

        val advStale = !s.advertising && (since(s.now, s.advertiseFailedAt) < SCAN_STALE_MS || s.now - s.startedAt > ADV_STALE_MS)
        val silent = since(s.now, s.lastScanResultAt) > scanStaleMs(s) && s.now - s.startedAt > scanStaleMs(s)
        val scanStale = s.expectPeers && (!s.scanning || since(s.now, s.scanFailedAt) < SCAN_STALE_MS || silent)

        if (advStale && scanStale) return Verdict.BOTH_STALE
        if (scanStale) return Verdict.SCAN_STALE
        if (advStale) return Verdict.ADVERTISING_STALE
        // the reported pattern: reconnects to a known peer time out while nothing is being discovered
        if (s.gattTimeouts >= GATT_TIMEOUTS_WEDGED && s.expectPeers && silent) return Verdict.GATT_WEDGED
        return Verdict.HEALTHY
    }

    fun action(v: Verdict): Action = when (v) {
        Verdict.ADVERTISING_STALE, Verdict.SCAN_STALE, Verdict.BOTH_STALE, Verdict.GATT_WEDGED -> Action.RECOVER
        else -> Action.NONE
    }

    fun verdictText(v: Verdict): String = when (v) {
        Verdict.HEALTHY -> "healthy"
        Verdict.NOT_RUNNING -> "node not running"
        Verdict.BLUETOOTH_OFF -> "Bluetooth is off"
        Verdict.BUSY -> "a link is in use: not touching the radio"
        Verdict.COOLING_DOWN -> "waiting after the last recovery"
        Verdict.ADVERTISING_STALE -> "advertising stale"
        Verdict.SCAN_STALE -> "scan stale"
        Verdict.BOTH_STALE -> "scan and advertising stale"
        Verdict.GATT_WEDGED -> "connections time out and nothing is being discovered"
    }
}
