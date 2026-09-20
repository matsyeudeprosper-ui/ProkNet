package net.prok.proknet.core

/**
 * v0.13.3: bounded, serialised delivery of control traffic, pure.
 *
 * The OnePlus log after the Bluetooth toggle: FORWARD, CONTROL, "peer has no
 * ProkNet service", retry, FORWARD again, over and over, because `onPeers()`
 * fires constantly and every fire re-queued the same request to the same
 * peer. That is a radio and battery storm, and it gave the other phone no
 * quiet moment to repair itself.
 *
 * One attempt per (request generation, peer identity) at a time, with a
 * bounded backoff, and a peer whose GATT service is missing is parked rather
 * than hammered. Everything is keyed by the signed ProkNet short id, never
 * by a BLE address, because addresses rotate.
 */
object ControlRetry {

    enum class Phase { IDLE, SENDING, BACKOFF, DELIVERED, PARKED }

    val DELAYS_MS = listOf(1_000L, 3_000L, 10_000L, 30_000L)
    /** A peer that advertises ProkNet but has no GATT service gets time to rebuild itself. */
    const val SERVICE_MISSING_BACKOFF_MS = 45_000L
    const val SERVICE_MISSING_PARK_AFTER = 3
    const val MAX_ATTEMPTS = 6

    data class Attempt(
        val phase: Phase = Phase.IDLE,
        val attempts: Int = 0,
        val nextAt: Long = 0,
        val lastError: String = "",
        /** The BLE generation this attempt belongs to; a new stack retries at once. */
        val bleGeneration: Int = 0,
        val startedAt: Long = 0,
    )

    /** How a peer's control plane looks from here. */
    data class PeerHealth(val serviceMissing: Int = 0, val lastFailureAt: Long = 0, val parkedUntil: Long = 0) {
        fun parked(now: Long): Boolean = now < parkedUntil
    }

    data class State(
        /** "requestId:generation:peerShort" -> attempt. */
        val attempts: Map<String, Attempt> = emptyMap(),
        val peers: Map<String, PeerHealth> = emptyMap(),
        val sends: Int = 0,
        val storms: Int = 0,
    )

    fun key(requestId: String, generation: Int, peerShort: String) = requestId + ":" + generation + ":" + peerShort

    /** A send may start only when nothing is in flight for this exact thing and the backoff has passed. */
    fun mayStart(st: State, requestId: String, generation: Int, peerShort: String, now: Long, bleGeneration: Int): Boolean {
        if (st.peers[peerShort]?.parked(now) == true) return false
        val a = st.attempts[key(requestId, generation, peerShort)] ?: return true
        // a rebuilt BLE stack is a real reason to try again at once
        if (a.bleGeneration != bleGeneration && a.phase != Phase.DELIVERED) return true
        return when (a.phase) {
            Phase.IDLE -> true
            Phase.SENDING -> now - a.startedAt > 30_000L      // a send that never called back
            Phase.BACKOFF -> now >= a.nextAt && a.attempts < MAX_ATTEMPTS
            Phase.PARKED -> now >= a.nextAt
            Phase.DELIVERED -> false
        }
    }

    fun started(st: State, requestId: String, generation: Int, peerShort: String, now: Long, bleGeneration: Int): State {
        val k = key(requestId, generation, peerShort)
        val a = st.attempts[k] ?: Attempt()
        return st.copy(attempts = st.attempts + (k to a.copy(phase = Phase.SENDING, startedAt = now, bleGeneration = bleGeneration)), sends = st.sends + 1)
    }

    fun delivered(st: State, requestId: String, generation: Int, peerShort: String, now: Long): State {
        val k = key(requestId, generation, peerShort)
        val a = st.attempts[k] ?: Attempt()
        return st.copy(
            attempts = st.attempts + (k to a.copy(phase = Phase.DELIVERED, lastError = "")),
            peers = st.peers + (peerShort to PeerHealth()))          // a delivery clears the peer's record
    }

    fun failed(st: State, requestId: String, generation: Int, peerShort: String, now: Long, error: String): State {
        val k = key(requestId, generation, peerShort)
        val a = st.attempts[k] ?: Attempt()
        val n = a.attempts + 1
        val missing = isServiceMissing(error)
        val health = st.peers[peerShort] ?: PeerHealth()
        val nextHealth = if (!missing) health.copy(lastFailureAt = now) else {
            val count = health.serviceMissing + 1
            PeerHealth(count, now, if (count >= SERVICE_MISSING_PARK_AFTER) now + SERVICE_MISSING_BACKOFF_MS * 2 else now + SERVICE_MISSING_BACKOFF_MS)
        }
        val delay = if (missing) SERVICE_MISSING_BACKOFF_MS else DELAYS_MS[minOf(n - 1, DELAYS_MS.size - 1)]
        val phase = if (missing || n >= MAX_ATTEMPTS) Phase.PARKED else Phase.BACKOFF
        return st.copy(attempts = st.attempts + (k to a.copy(phase = phase, attempts = n, nextAt = now + delay, lastError = error)),
            peers = st.peers + (peerShort to nextHealth))
    }

    /** The other phone told us its ProkNet service is not there. */
    fun isServiceMissing(error: String): Boolean =
        error.contains("no ProkNet service", ignoreCase = true) || error.contains("service discovery failed", ignoreCase = true)

    /** What the diagnostic says about a peer that keeps failing that way. */
    fun peerNote(h: PeerHealth?, now: Long): String = when {
        h == null || h.serviceMissing == 0 -> ""
        h.parked(now) -> "peer advertises ProkNet but its GATT service is missing (" + h.serviceMissing + "x, waiting " + ((h.parkedUntil - now) / 1000) + "s)"
        else -> "peer advertises ProkNet but its GATT service is missing (" + h.serviceMissing + "x)"
    }

    /** Our own radio was rebuilt: every backoff is meaningless now, try again. */
    fun onBleGenerationChanged(st: State, generation: Int): State =
        st.copy(attempts = st.attempts.mapValues { (_, a) -> if (a.phase == Phase.DELIVERED) a else a.copy(phase = Phase.IDLE, nextAt = 0, attempts = 0) },
            peers = emptyMap())

    /** A peer we had parked is visible again under a new address: give it one clean chance. */
    fun onPeerReappeared(st: State, peerShort: String): State =
        st.copy(peers = st.peers - peerShort,
            attempts = st.attempts.mapValues { (k, a) -> if (k.endsWith(":" + peerShort) && a.phase == Phase.PARKED) a.copy(phase = Phase.IDLE, nextAt = 0, attempts = 0) else a })

    /** Keep only what still belongs to a live request, so the map cannot grow forever. */
    fun keepOnly(st: State, liveRequestIds: Set<String>): State =
        st.copy(attempts = st.attempts.filterKeys { it.substringBefore(':') in liveRequestIds })

    fun inFlight(st: State): Int = st.attempts.values.count { it.phase == Phase.SENDING }

    fun describe(st: State, now: Long): String {
        val parked = st.peers.filterValues { it.parked(now) }
        return "sends " + st.sends + " | in flight " + inFlight(st) + " | tracked " + st.attempts.size +
            (if (parked.isEmpty()) "" else " | parked: " + parked.keys.joinToString(", ") { "prok-" + it })
    }
}
