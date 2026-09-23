package net.prok.proknet.core

/**
 * v0.17.6: keeping a coarse zone while the app is closed, and only while it is needed.
 *
 * THE DEFECT THIS FILE EXISTS FOR, the twin of the one v0.17.5 fixed. Location updates
 * were requested in the ACTIVITY's `onForeground` and cancelled in `onBackground`, and a
 * fix is only usable for [MAX_AGE_MS]. So a provider who had granted everything, opted in
 * to be woken, and left the phone in a pocket overnight had no zone by morning - and a
 * phone with no zone publishes no presence, which is precisely the idle provider that
 * v0.17.3 exists to make discoverable. The product promise was "leave it closed and we
 * will wake you", and the implementation was "only while you are looking at it".
 *
 * There was a second trap underneath it. Updates asked for a minimum movement of 300 m,
 * so a phone sitting still in a house received **no updates at all** and went stale even
 * while the app was open. The idle provider is by definition the phone that is not
 * moving, so the one case that mattered was the one case that could not work.
 *
 * ProkNet already runs a foreground service with a permanent notification - that is how
 * it carries anything at all. A foreground service declaring the `location` type may
 * receive updates while the app is closed **on the ordinary coarse permission**, with no
 * ACCESS_BACKGROUND_LOCATION and therefore no trip through system settings. So the fix is
 * to drive the updates from the service rather than from the screen.
 *
 * What this object decides is the other half: ProkNet asks for a position only while the
 * user is actually taking part. Somebody who is neither offering Internet nor looking for
 * it has no zone tracked at all, because we have no business knowing where they are.
 */
object ZoneWatch {

    /** A fix older than this is not trusted: the phone may have moved while it slept. */
    const val MAX_AGE_MS = 30 * 60_000L

    /** At most one update per five minutes. A coarse cell does not need more. */
    const val MIN_TIME_MS = 5 * 60_000L

    /**
     * Zero, deliberately.
     *
     * The old value was 300 m, which sounds thrifty and means "a phone that has not moved
     * never refreshes its timestamp". The fix then expires under a phone sitting exactly
     * where it is useful. Time is the only interval that keeps a stationary provider
     * alive, and on the network provider one coarse fix every five minutes is cheap.
     */
    const val MIN_DISTANCE_M = 0f

    /** Why ProkNet is holding a position, or why it is not. */
    enum class Why {
        /** Nobody is offering and nobody is asking. Track nothing. */
        NOT_NEEDED,

        /** Opted in to be woken when somebody nearby needs Internet. */
        WILLING_TO_SHARE,

        /** Actually sharing right now. */
        SHARING,

        /** Looking for Internet: the demand itself carries the zone. */
        LOOKING,
    }

    fun why(optedInToShare: Boolean, sharing: Boolean, looking: Boolean): Why = when {
        sharing -> Why.SHARING
        looking -> Why.LOOKING
        optedInToShare -> Why.WILLING_TO_SHARE
        else -> Why.NOT_NEEDED
    }

    /**
     * Should the service hold location updates open?
     *
     * Note what is NOT here: whether the app is on screen. That was the bug.
     */
    fun needed(optedInToShare: Boolean, sharing: Boolean, looking: Boolean): Boolean =
        why(optedInToShare, sharing, looking) != Why.NOT_NEEDED

    /** Is a fix taken at [lastFixAt] still good enough to publish a zone from? */
    fun fresh(lastFixAt: Long, now: Long): Boolean =
        lastFixAt > 0 && now - lastFixAt <= MAX_AGE_MS

    /**
     * How long a stationary phone may stay discoverable on one fix, in whole minutes.
     * Used by the diagnostic so a stale zone is visibly a stale zone.
     */
    fun minutesLeft(lastFixAt: Long, now: Long): Long =
        if (lastFixAt <= 0) 0 else maxOf(0L, (MAX_AGE_MS - (now - lastFixAt)) / 60_000L)

    fun diag(w: Why): String = when (w) {
        Why.NOT_NEEDED -> "not tracking (neither sharing nor looking)"
        Why.WILLING_TO_SHARE -> "tracking: opted in to be woken"
        Why.SHARING -> "tracking: sharing now"
        Why.LOOKING -> "tracking: looking for Internet"
    }
}
