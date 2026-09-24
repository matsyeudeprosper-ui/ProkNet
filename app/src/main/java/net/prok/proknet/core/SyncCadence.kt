package net.prok.proknet.core

/**
 * v0.17.10: how often this phone talks to the Brain, decided by what it is doing.
 *
 * THE DEFECT THIS FILE EXISTS FOR, found on hardware 2026-09-24 and the most consequential
 * of the whole series. `NetworkBrainSync` already declared the right numbers, with the
 * right comments:
 *
 *     const val POLL_MS = 5_000L        // How often a waiting buyer asks what happened
 *     const val IDLE_POLL_MS = 30_000L  // How often a willing provider checks for work
 *
 * **Neither was used anywhere.** The scheduler that would have honoured them was never
 * written, so everything ran on the service's one fixed sweep of fifteen minutes. The
 * intent was documented and the code that meant it did not exist.
 *
 * Two consequences, both fatal to the two-phone test:
 *
 *  - The server expires a presence after **120 seconds**. Heartbeating every fifteen
 *    minutes makes a provider visible for two minutes in every fifteen - about 13% of the
 *    time - so a buyer asking at any given moment almost certainly finds nobody. Every
 *    time the Brain was inspected the presence was stale, and that was read as the app
 *    being closed, or the zone expiring, or the service type. Those were all real and all
 *    fixed; this one was underneath them and would have kept the network broken alone.
 *
 *  - A buyer waiting on "Recherche d'Internet…" learns that a provider accepted on the
 *    next sweep. Up to a quarter of an hour of staring at a screen that already had its
 *    answer.
 *
 * So the cadence follows the state, and the numbers that were already written down are
 * finally the numbers that run. A phone doing nothing still sleeps for fifteen minutes:
 * this spends battery only while somebody is actually waiting or actually offering.
 */
object SyncCadence {

    /** A buyer is waiting on an answer. Seconds matter: they are watching the screen. */
    const val BUYER_WAITING_MS = 5_000L

    /**
     * A willing provider, idle or serving.
     *
     * Four times inside the server's 120 s presence window, so three heartbeats can be
     * lost to a bad moment of signal before the phone falls out of the network.
     */
    const val PROVIDER_MS = 30_000L

    /** Neither offering nor asking. Nothing needs to be fresh. */
    const val QUIET_MS = 15 * 60_000L

    /** The server's window, repeated here so the test can prove we stay inside it. */
    const val PRESENCE_TTL_MS = 120_000L

    enum class Why { BUYER_WAITING, PROVIDER, QUIET }

    fun why(buyerWaiting: Boolean, providerWilling: Boolean, sharing: Boolean): Why = when {
        buyerWaiting -> Why.BUYER_WAITING
        sharing || providerWilling -> Why.PROVIDER
        else -> Why.QUIET
    }

    fun delayFor(w: Why): Long = when (w) {
        Why.BUYER_WAITING -> BUYER_WAITING_MS
        Why.PROVIDER -> PROVIDER_MS
        Why.QUIET -> QUIET_MS
    }

    fun nextDelayMs(buyerWaiting: Boolean, providerWilling: Boolean, sharing: Boolean): Long =
        delayFor(why(buyerWaiting, providerWilling, sharing))

    /**
     * Does this cadence keep a presence alive?
     *
     * The property the defect broke. Any state in which this phone publishes a presence
     * must talk to the Brain comfortably inside the server's window - not once per window,
     * which leaves no room for a single lost request.
     */
    fun keepsPresenceAlive(w: Why): Boolean =
        w == Why.QUIET || delayFor(w) * 2 <= PRESENCE_TTL_MS

    fun diag(w: Why): String = when (w) {
        Why.BUYER_WAITING -> "every " + BUYER_WAITING_MS / 1000 + " s (somebody is waiting)"
        Why.PROVIDER -> "every " + PROVIDER_MS / 1000 + " s (offering to share)"
        Why.QUIET -> "every " + QUIET_MS / 60_000 + " min (neither offering nor asking)"
    }
}
