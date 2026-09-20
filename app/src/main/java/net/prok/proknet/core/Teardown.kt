package net.prok.proknet.core

/**
 * v0.14.2: how a session stops, as a machine with no Android in it.
 *
 * On the phones, v0.14.1 stopped a session by sending the final control message and
 * closing the Bluetooth link in the next statement. The message could then never be
 * acknowledged, so the transport reported a failure that our own shutdown had caused,
 * and the seller's final checkpoint had nowhere to arrive. A session stopped before the
 * 30 s periodic checkpoint settled at zero.
 *
 * The rule this encodes: **never destroy the transport underneath a message the
 * protocol still expects an answer to.** Stopping is two steps. First say so and wait,
 * briefly and with a bound, for the last figure both sides sign. Only then close
 * anything.
 *
 * `TunnelClient` (buyer) and `Gateway` (seller) both drive this. Neither can be built in
 * a JVM test, so the decisions live here where the tests can run the real ones.
 */
object Teardown {

    enum class Phase {
        /** A session is live and carrying traffic. */
        RUNNING,

        /** Stop was asked for. The last signed figure is outstanding. No new app traffic. */
        SETTLING,

        /** Nothing is owed. Everything may be closed. */
        IDLE,
    }

    /**
     * v0.15.0: who ended it. The role decides which frame goes out first; it must never
     * decide what the session costs. A buyer stop and a seller stop over the same signed
     * usage settle at exactly the same figure, and a test pins that.
     */
    enum class Cause {
        NONE,

        /** This phone's user pressed Stop. */
        LOCAL_STOP,

        /** The other phone ended the session. */
        PEER_STOP,

        /** The link went away before the closing figure was signed. */
        LINK_LOST,

        /** The grace window expired. */
        TIMEOUT,
    }

    /** What became of the final signed usage figure. */
    enum class Final {
        /** No session, so nothing to settle. */
        NONE,

        /** Asked for, not yet countersigned. */
        WAITING,

        /** Both sides signed the closing figure. This is the one that bills. */
        PASS,

        /** The peer did not answer inside the window. Settle on the last signed figure. */
        TIMEOUT,

        /** The peer or the link was already gone. Settle on the last signed figure. */
        UNAVAILABLE,
    }

    /**
     * @param token bumped on every transition out of SETTLING, so a timer armed by one
     *        stop can never end the session that came after it.
     */
    class State(
        val phase: Phase = Phase.IDLE,
        val final: Final = Final.NONE,
        val token: Int = 0,
        val reason: String = "",
        val cause: Cause = Cause.NONE,
    ) {
        val settling: Boolean get() = phase == Phase.SETTLING
        /** True once nothing is owed and the caller may close the transport. */
        val mayClose: Boolean get() = phase == Phase.IDLE

        fun describe(): String = phase.toString() + " | final checkpoint " + word(final) +
            (if (reason.isEmpty()) "" else " | " + reason)
    }

    fun word(f: Final): String = when (f) {
        Final.NONE -> "-"
        Final.WAITING -> "waiting"
        Final.PASS -> "PASS"
        Final.TIMEOUT -> "timeout"
        Final.UNAVAILABLE -> "unavailable"
    }

    /** A session is up and carrying traffic. */
    fun running(s: State = State()): State = State(Phase.RUNNING, Final.NONE, s.token, "", Cause.NONE)

    /**
     * Stop was asked for.
     *
     * Pressing Stop twice, or the UI and the system lifecycle both calling cleanup, must
     * produce one settlement and one close, so a stop already under way is left alone.
     *
     * @param canReachPeer false when the link is already gone: there is nobody to sign
     *        the closing figure with, so settle on what is already signed and finish.
     */
    fun begin(s: State, reason: String, canReachPeer: Boolean, cause: Cause = Cause.LOCAL_STOP): State = when {
        s.phase == Phase.IDLE -> State(Phase.IDLE, Final.NONE, s.token + 1, reason, cause)
        s.phase == Phase.SETTLING -> s                       // already stopping: one stop only
        !canReachPeer -> State(Phase.IDLE, Final.UNAVAILABLE, s.token + 1, reason, cause)
        else -> State(Phase.SETTLING, Final.WAITING, s.token + 1, reason, cause)
    }

    /** The closing figure came back signed by both sides. This is the good ending. */
    fun onFinalSigned(s: State): State =
        if (s.phase != Phase.SETTLING) s
        else State(Phase.IDLE, Final.PASS, s.token + 1, s.reason, s.cause)

    /**
     * The graceful window expired. Only the timer belonging to THIS stop may end it: an
     * old timer firing after a new session started must change nothing.
     */
    fun onTimeout(s: State, token: Int): State =
        if (s.phase != Phase.SETTLING || s.token != token) s
        else State(Phase.IDLE, Final.TIMEOUT, s.token + 1, s.reason, Cause.TIMEOUT)

    /**
     * The link went away. During a stop that is the expected end of it, not a fault; the
     * caller must not record an error the user never caused.
     */
    fun onLinkGone(s: State, reason: String): State = when (s.phase) {
        Phase.SETTLING -> State(Phase.IDLE, Final.UNAVAILABLE, s.token + 1, s.reason, Cause.LINK_LOST)
        Phase.RUNNING -> State(Phase.IDLE, Final.UNAVAILABLE, s.token + 1, reason, Cause.LINK_LOST)
        Phase.IDLE -> s
    }

    /** True when a callback carrying [token] still belongs to the live session. */
    fun accepts(s: State, token: Int): Boolean = s.token == token && s.phase != Phase.IDLE

    /**
     * Is this stop an ordinary one the user asked for, or did something actually break?
     * An intentional close must never leave an error behind on the screen.
     */
    fun isClean(s: State): Boolean = s.final != Final.NONE || s.phase == Phase.IDLE

    /**
     * What a session owes when it ends. [lastSigned] is the closing figure if both sides
     * signed one, otherwise the last periodic one.
     *
     * Deliberately NOT special-cased for a short session: a buyer that stops after five
     * seconds pays for five seconds of traffic, because the seller issues the closing
     * checkpoint on SESSION_END rather than only every thirty seconds. Settling a short
     * session at zero would make stopping early a way to browse for nothing.
     */
    fun settlement(contract: Market.Contract, lastSigned: Market.Checkpoint?): Long =
        Market.finalCost(contract, lastSigned)
}
