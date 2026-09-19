package net.prok.proknet.core

/**
 * v0.9.27: **creating a group, as a state machine with two different
 * counters.**
 *
 * The v0.9.26 run gave the first genuinely clean creation attempt on the
 * OnePlus: accepted, fifteen silent seconds, no group. Then:
 *
 * ```
 * 12:20:13.617  nothing to remove (BUSY)
 * 12:20:13.618  creating attempt 2
 * 12:20:13.620  createGroup refused BUSY
 * 12:20:16.631  creating attempt 3
 * 12:20:16.636  createGroup failed after 3 attempts: BUSY
 * ```
 *
 * Attempts 2 and 3 never happened. Android was still settling the first
 * creation, answered BUSY twice, and the counter treated those as attempts.
 * So the run produced one real attempt, not three, and no decision about
 * the phone can be drawn from it.
 *
 * BUSY is not evidence. It is the framework asking for time. It never
 * consumes a logical attempt; it consumes a bounded number of RESET tries
 * instead. Only an acceptance, or an explicit non-busy refusal, counts.
 */
object P2pCreation {

    /** Real creation attempts, each one accepted or explicitly refused. */
    const val ATTEMPTS = 3
    /** Bounded framework recovery between attempts: this many tries, this far apart. */
    const val RESET_TRIES = 8
    const val RESET_BACKOFF_MS = 1_500L

    enum class Phase { IDLE, CREATING, FORMING, RESETTING, DONE, FAILED }

    /** Typed: no string matching can send one of these anywhere but GROUP_CREATE_FAIL. */
    enum class Fail { NONE, NEVER_FORMED, FRAMEWORK_BUSY, REFUSED, PERMISSION }

    data class State(
        val attempt: Int,          // attempts really started (accepted or refused), 0..ATTEMPTS
        val phase: Phase,
        val resetTries: Int,
        val fail: Fail,
        val detail: String,
    ) {
        /** The number a createGroup() call carries in the log: the attempt it would become. */
        val pending: Int get() = attempt + 1
        fun describe(): String = "creation " + phase + ", attempt " + attempt + "/" + ATTEMPTS +
            (if (phase == Phase.RESETTING) ", reset try " + resetTries + "/" + RESET_TRIES else "") +
            (if (fail != Fail.NONE) ", failed: " + fail else "")
    }

    val START = State(0, Phase.IDLE, 0, Fail.NONE, "")

    /** Is this Android reason the framework asking for time? */
    fun isBusy(reasonName: String): Boolean = reasonName.startsWith("BUSY", ignoreCase = true)

    /** createGroup() is about to be called. Nothing is committed until Android answers. */
    fun begin(s: State): State = when (s.phase) {
        Phase.IDLE, Phase.RESETTING -> s.copy(phase = Phase.CREATING, resetTries = 0)
        else -> s
    }

    /** Android accepted: THIS is a real attempt. Wait for the group. */
    fun accepted(s: State): State =
        if (s.phase == Phase.CREATING) s.copy(attempt = s.attempt + 1, phase = Phase.FORMING) else s

    fun formed(s: State): State = if (s.phase == Phase.FORMING || s.phase == Phase.CREATING) s.copy(phase = Phase.DONE) else s

    /**
     * Android answered BUSY to createGroup(). Not an attempt: the framework
     * is still settling. Back off and reset, a bounded number of times.
     */
    fun createBusy(s: State): State {
        if (s.phase != Phase.CREATING) return s
        val tries = s.resetTries + 1
        return if (tries > RESET_TRIES) s.copy(phase = Phase.FAILED, fail = Fail.FRAMEWORK_BUSY, resetTries = tries,
            detail = "createGroup answered BUSY " + tries + " times")
        else s.copy(phase = Phase.RESETTING, resetTries = tries)
    }

    /** An explicit, non-busy refusal. That IS an attempt: Android tried and said no. */
    fun createRefused(s: State, reason: String): State {
        if (s.phase != Phase.CREATING) return s
        val n = s.attempt + 1
        return if (n >= ATTEMPTS) s.copy(attempt = n, phase = Phase.FAILED, fail = Fail.REFUSED, detail = reason)
        else s.copy(attempt = n, phase = Phase.RESETTING, resetTries = 0, detail = reason)
    }

    fun permissionDenied(s: State, reason: String): State =
        s.copy(phase = Phase.FAILED, fail = Fail.PERMISSION, detail = reason)

    /** The formation window closed with no group. The attempt happened and produced nothing. */
    fun formationTimeout(s: State): State {
        if (s.phase != Phase.FORMING) return s
        return if (s.attempt >= ATTEMPTS) s.copy(phase = Phase.FAILED, fail = Fail.NEVER_FORMED)
        else s.copy(phase = Phase.RESETTING, resetTries = 0)
    }

    /** The reset asked Android to clean up and Android said BUSY: wait and try the reset again, bounded. */
    fun resetBusy(s: State): State {
        if (s.phase != Phase.RESETTING) return s
        val tries = s.resetTries + 1
        return if (tries >= RESET_TRIES) s.copy(phase = Phase.FAILED, fail = Fail.FRAMEWORK_BUSY, resetTries = tries,
            detail = "the framework stayed BUSY through " + tries + " reset tries")
        else s.copy(resetTries = tries)
    }

    /** The framework is clean: the next REAL attempt may begin. */
    fun resetClean(s: State): State = if (s.phase == Phase.RESETTING) s.copy(phase = Phase.CREATING) else s

    val failed: (State) -> Boolean = { it.phase == Phase.FAILED }

    // ---- reasons: every one is typed AND carries the same prefix, so nothing can file it elsewhere ----

    const val PREFIX = "GROUP_CREATE: "

    fun reasonText(f: Fail, detail: String = ""): String = PREFIX + when (f) {
        Fail.NONE -> "no failure"
        Fail.NEVER_FORMED -> "Android accepted createGroup " + ATTEMPTS + " times but no Wi-Fi Direct group formed"
        Fail.FRAMEWORK_BUSY -> "the Wi-Fi Direct framework stayed BUSY while resetting for another group attempt" +
            (if (detail.isNotEmpty()) " (" + detail + ")" else "")
        Fail.REFUSED -> "Android refused createGroup: " + detail.ifEmpty { "no reason given" }
        Fail.PERMISSION -> "permission refused while creating the group: " + detail
    }

    fun reasonText(s: State): String = reasonText(s.fail, s.detail)

    /** Is [reason] a group-creation failure, whatever its wording? */
    fun isCreationFailure(reason: String): Boolean = reason.startsWith(PREFIX)

    /** The framework-busy ending is the one that asks the user to wait, not to retry at once. */
    fun isFrameworkBusy(reason: String): Boolean = reason.startsWith(PREFIX) && reason.contains("stayed BUSY")
}
