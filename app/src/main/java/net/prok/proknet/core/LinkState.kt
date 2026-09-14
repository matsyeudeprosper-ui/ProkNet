package net.prok.proknet.core

/**
 * Wi-Fi link state machine, pure (v0.5). The Android layer feeds events in
 * and executes the actions it returns; the JVM tests drive it directly.
 *
 * Roles: the phone that wants the link is the INITIATOR (it will join the
 * other's hotspot); the other is the HOST (it starts a local-only hotspot).
 *
 *   IDLE --request--> REQUESTING (BLE: WIFI_REQUEST sent)
 *   REQUESTING --offer--> JOINING (join SSID) --network--> HANDSHAKE --ok--> UP
 *   IDLE --requestReceived--> HOSTING (start hotspot) --hotspotUp--> OFFERING (BLE: WIFI_OFFER sent) --client connected--> HANDSHAKE --ok--> UP
 *   any --lost/closed/failed--> DOWN (with retry budget) --retry--> IDLE
 */
class LinkState {
    enum class State { IDLE, REQUESTING, HOSTING, OFFERING, JOINING, HANDSHAKE, UP, DOWN }
    enum class Role { NONE, INITIATOR, HOST }
    enum class Action { NONE, SEND_REQUEST, START_HOTSPOT, SEND_OFFER, JOIN_NETWORK, OPEN_SOCKET, TEARDOWN }

    var state = State.IDLE; private set
    var role = Role.NONE; private set
    var peer: String? = null; private set
    var lastError = ""; private set
    var failures = 0; private set
    var lastChange = 0L; private set

    val isUp get() = state == State.UP
    val isIdle get() = state == State.IDLE || state == State.DOWN
    val isBusy get() = !isIdle && !isUp

    private fun go(s: State, now: Long): LinkState { state = s; lastChange = now; return this }

    /** Initiator wants a link to [peerShort]. Returns the action to perform. */
    fun request(peerShort: String, now: Long): Action {
        if (state == State.UP && peer == peerShort) return Action.NONE
        if (isBusy) return Action.NONE
        role = Role.INITIATOR; peer = peerShort; lastError = ""
        go(State.REQUESTING, now)
        return Action.SEND_REQUEST
    }

    /**
     * v0.9.1: we believe we are linked to [peerShort], yet it is asking for a
     * new link. Its side is gone (app restart, link torn down without us
     * noticing), so the old link is stale and must be dropped before hosting
     * again. Otherwise the peer keeps timing out in REQUESTING for ever.
     */
    fun staleLinkRequest(peerShort: String): Boolean = state == State.UP && peer == peerShort

    /** A WIFI_REQUEST arrived from [peerShort]. Tie-break when both requested: the lower ID hosts. */
    fun requestReceived(peerShort: String, myShort: String, now: Long): Action {
        if (state == State.UP) return Action.NONE
        if (state == State.REQUESTING && peer == peerShort && myShort > peerShort) return Action.NONE // they will host
        if (isBusy && !(state == State.REQUESTING && peer == peerShort)) return Action.NONE
        role = Role.HOST; peer = peerShort; lastError = ""
        go(State.HOSTING, now)
        return Action.START_HOTSPOT
    }

    fun hotspotUp(now: Long): Action {
        if (state != State.HOSTING) return Action.NONE
        go(State.OFFERING, now)
        return Action.SEND_OFFER
    }

    fun offerReceived(peerShort: String, now: Long): Action {
        if (state != State.REQUESTING || peer != peerShort) return Action.NONE
        go(State.JOINING, now)
        return Action.JOIN_NETWORK
    }

    fun networkAvailable(now: Long): Action {
        if (state != State.JOINING) return Action.NONE
        go(State.HANDSHAKE, now)
        return Action.OPEN_SOCKET
    }

    fun clientConnected(now: Long): Action {
        if (state != State.OFFERING) return Action.NONE
        go(State.HANDSHAKE, now)
        return Action.NONE
    }

    fun handshakeOk(peerShort: String, now: Long): Action {
        if (state != State.HANDSHAKE) return Action.NONE
        peer = peerShort; failures = 0
        go(State.UP, now)
        return Action.NONE
    }

    /** Anything that ends the attempt or the link. */
    fun fail(reason: String, now: Long): Action {
        if (state == State.IDLE || state == State.DOWN) return Action.NONE
        lastError = reason
        if (state != State.UP) failures++
        go(State.DOWN, now)
        return Action.TEARDOWN
    }

    /** Timeout for the in-progress step: the Android layer calls this on a timer. */
    fun tick(now: Long, stepTimeoutMs: Long): Action {
        if (!isBusy) return Action.NONE
        return if (now - lastChange > stepTimeoutMs) fail("timeout in " + state, now) else Action.NONE
    }

    /**
     * v0.9.3: each step gets its own patience instead of one 120 s wait for
     * everything. Only the two steps that wait for a HUMAN (the Android
     * "connect to this device?" dialog, on either side) keep the long one; a
     * provider that never answers is reported in a minute, not two.
     */
    fun stepTimeoutMs(s: State = state): Long = when (s) {
        State.REQUESTING -> 60_000L    // our BLE request went out; the host must start a hotspot and answer
        State.HOSTING -> 45_000L       // our own hotspot must come up
        State.OFFERING -> 120_000L     // the client is looking at the Android dialog
        State.JOINING -> 120_000L      // we are looking at the Android dialog
        State.HANDSHAKE -> 30_000L     // sockets only, no human
        else -> 120_000L
    }

    fun tick(now: Long): Action = tick(now, stepTimeoutMs())

    /** Can a new attempt start now? Exponential wait after failures, capped. */
    fun retryDelayMs(): Long = if (failures == 0) 0 else minOf(60_000L, 5_000L shl minOf(failures - 1, 4))

    fun canRetry(now: Long): Boolean = (state == State.DOWN && now - lastChange >= retryDelayMs()) || state == State.IDLE

    fun reset(now: Long) { role = Role.NONE; peer = null; go(State.IDLE, now) }

    fun describe(): String = state.name + (if (role != Role.NONE) " (" + role.name.lowercase() + (peer?.let { " with prok-" + it } ?: "") + ")" else "") +
        (if (lastError.isNotEmpty() && state == State.DOWN) " - " + lastError else "")
}
