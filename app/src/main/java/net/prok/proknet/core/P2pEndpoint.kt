package net.prok.proknet.core

/**
 * v0.9.13: **what a Wi-Fi Direct socket belongs to.**
 *
 * The v0.9.12 phone run proved the topology. The seller kept its home Wi-Fi
 * (`wlan0=192.168.1.13`) AND owned the group (`p2p-wlan0-25=192.168.49.1`,
 * role GROUP_OWNER, clients 1); the buyer joined as CLIENT with
 * `p2p0=192.168.49.124`. And still nothing connected:
 *
 * ```
 * seller 09:08:43  group owner listening on :47742
 * buyer  09:10:1x  192.168.49.124 -> 192.168.49.1:47742   x6, every one timed out
 * seller           TCP accepted: never logged
 * ```
 *
 * The listener was opened when the group FORMED, on `0.0.0.0`, and was then
 * believed forever because the object was not null. A server socket object
 * is not proof that a usable server exists. So this file gives the socket an
 * identity:
 *
 * - an [Endpoint] is the P2P endpoint a socket can belong to: a generation,
 *   the role, the interface, the local address and the Android network,
 * - a [Listener] records which endpoint generation a server socket was
 *   actually built for, and what it actually bound to,
 * - [validate] answers one question with a reason: is this listener still
 *   the listener of THIS endpoint,
 * - [adopt] gives a NEW generation only when something material changed, so
 *   an unchanged endpoint is never rebuilt.
 *
 * Everything here is pure, so the lifecycle is tested on the JVM and the
 * Android class ([net.prok.proknet.transport.P2pSocketBinding]) only has to
 * report what it sees.
 */
object P2pEndpoint {

    /** What Android says about the local Wi-Fi Direct endpoint right now. */
    data class Observed(
        val role: P2pPlan.Role,
        val interfaceName: String,
        val localAddress: String,
        val networkIdentity: String,
    )

    /** An endpoint with an identity. [generation] changes only when the endpoint really changed. */
    data class Endpoint(
        val generation: Int,
        val role: P2pPlan.Role,
        val interfaceName: String,
        val localAddress: String,
        val networkIdentity: String,
    ) {
        /** A socket can only be bound to an endpoint that has a role, an interface and an address. */
        val usable: Boolean
            get() = role != P2pPlan.Role.NONE && interfaceName.isNotEmpty() && localAddress.isNotEmpty()

        fun describe(): String = "generation " + generation + ", role " + role +
            ", interface " + interfaceName.ifEmpty { "?" } +
            ", local " + localAddress.ifEmpty { "?" } +
            ", network " + networkIdentity.ifEmpty { "none" }
    }

    /** A server socket that exists, and the endpoint generation it was built for. */
    data class Listener(
        val generation: Int,
        val interfaceName: String,
        val boundAddress: String,
        val port: Int,
        val networkIdentity: String,
        val accepting: Boolean,
    ) {
        fun describe(): String = boundAddress.ifEmpty { "?" } + ":" + port +
            " (generation " + generation + ", interface " + interfaceName.ifEmpty { "?" } +
            ", network " + networkIdentity.ifEmpty { "none" } +
            ", accepting " + accepting + ")"
    }

    /** Has anything that a socket depends on changed? */
    fun changed(current: Endpoint?, o: Observed): Boolean {
        val c = current ?: return true
        return c.role != o.role || c.interfaceName != o.interfaceName ||
            c.localAddress != o.localAddress || c.networkIdentity != o.networkIdentity
    }

    /** Why it changed, in words, for the log. */
    fun changeReason(current: Endpoint?, o: Observed): String {
        val c = current ?: return "first endpoint"
        val parts = ArrayList<String>()
        if (c.role != o.role) parts.add("role " + c.role + " -> " + o.role)
        if (c.interfaceName != o.interfaceName) parts.add("interface " + c.interfaceName.ifEmpty { "none" } + " -> " + o.interfaceName.ifEmpty { "none" })
        if (c.localAddress != o.localAddress) parts.add("local address " + c.localAddress.ifEmpty { "none" } + " -> " + o.localAddress.ifEmpty { "none" })
        if (c.networkIdentity != o.networkIdentity) parts.add("android network " + c.networkIdentity.ifEmpty { "none" } + " -> " + o.networkIdentity.ifEmpty { "none" })
        return if (parts.isEmpty()) "nothing changed" else parts.joinToString(", ")
    }

    /**
     * The endpoint to use now. The SAME object (and generation) comes back
     * when nothing material changed, so an unchanged endpoint never rebuilds
     * anything; a real change always gets a new generation, which makes
     * every socket of the previous one stale by construction.
     */
    fun adopt(current: Endpoint?, o: Observed): Endpoint =
        if (current != null && !changed(current, o)) current
        else Endpoint((current?.generation ?: 0) + 1, o.role, o.interfaceName, o.localAddress, o.networkIdentity)

    enum class Verdict { NO_ENDPOINT, NO_LISTENER, STALE_GENERATION, WRONG_INTERFACE, WRONG_ADDRESS, WRONG_NETWORK, NOT_ACCEPTING, VALID }

    /** Is [listener] still the listener of [endpoint]? Never "probably". */
    fun validate(endpoint: Endpoint?, listener: Listener?): Verdict = when {
        endpoint == null || !endpoint.usable -> Verdict.NO_ENDPOINT
        listener == null -> Verdict.NO_LISTENER
        listener.generation != endpoint.generation -> Verdict.STALE_GENERATION
        listener.interfaceName != endpoint.interfaceName -> Verdict.WRONG_INTERFACE
        listener.boundAddress != endpoint.localAddress -> Verdict.WRONG_ADDRESS
        listener.networkIdentity != endpoint.networkIdentity -> Verdict.WRONG_NETWORK
        !listener.accepting -> Verdict.NOT_ACCEPTING
        else -> Verdict.VALID
    }

    fun ok(v: Verdict): Boolean = v == Verdict.VALID

    fun verdictText(v: Verdict): String = when (v) {
        Verdict.NO_ENDPOINT -> "there is no readable P2P endpoint yet"
        Verdict.NO_LISTENER -> "no listener exists for this endpoint"
        Verdict.STALE_GENERATION -> "the listener belongs to an older group lifecycle"
        Verdict.WRONG_INTERFACE -> "the listener is on another interface"
        Verdict.WRONG_ADDRESS -> "the listener is not bound to the P2P local address"
        Verdict.WRONG_NETWORK -> "the listener belongs to another Android network"
        Verdict.NOT_ACCEPTING -> "the listener is not accepting any more"
        Verdict.VALID -> "valid"
    }

    /**
     * May a socket accepted by the accept loop of [loopGeneration] be used?
     * An old loop that returns after the group was rebuilt must never push a
     * connection into the new lifecycle.
     */
    fun acceptAllowed(loopGeneration: Int, endpoint: Endpoint?): Boolean =
        endpoint != null && endpoint.usable && loopGeneration == endpoint.generation

    /** One line the two phones can be compared on. */
    fun dialLine(attempt: Int, of: Int, source: String, destination: String, port: Int, iface: String, network: String, boundToP2p: Boolean): String =
        "DIAL " + attempt + "/" + of + ": " + source.ifEmpty { "?" } + " -> " + destination + ":" + port +
            " | p2p interface " + iface.ifEmpty { "?" } +
            " | android network " + network.ifEmpty { "none" } +
            " | socket bound to P2P network=" + boundToP2p

    fun listenLine(stage: String, endpoint: Endpoint, actual: String, port: Int): String =
        "LISTENER " + stage + ": " + actual.ifEmpty { "?" } + ":" + port + " | " + endpoint.describe()
}
