package net.prok.proknet.core

/**
 * v0.9.14: **client membership is part of the transport lifecycle.**
 *
 * v0.9.13 gave the socket an endpoint identity, and the phone run showed
 * that an endpoint is not enough:
 *
 * ```
 * seller  p2p-wlan0-26 = 192.168.49.1   GROUP_OWNER   network 158
 * seller  listener 192.168.49.1:47742   generation 1  accepting true
 * seller  CLIENT COUNT 0 -> 1           listener check says valid
 * buyer   p2p0 = 192.168.49.124         android network none
 * buyer   DIAL 1..6 -> 192.168.49.1:47742   all six timed out
 * seller  TCP accepted                  never
 * ```
 *
 * Every field matched and nothing was reachable. So "valid" was measuring
 * the wrong thing: the listener was built while the group was EMPTY, and it
 * was carried forward into a live membership purely because its address,
 * interface and network metadata still looked the same.
 *
 * The usable lifecycle is therefore:
 *
 * ```
 * group formed -> endpoint exists -> client membership established
 *              -> a data plane generation becomes usable
 *              -> listener and dial belong to THAT generation
 * ```
 *
 * A [Plane] carries both counters. `groupGeneration` changes when the group
 * or its endpoint changes; `membershipGeneration` changes when this phone
 * gains a live peer (an owner whose client count reached one, or a client
 * that joined). A listener from group 4 / membership 0 is NOT the listener
 * of group 4 / membership 1, and [validate] says so by name.
 */
object P2pDataPlane {

    data class Plane(
        val groupGeneration: Int,
        val membershipGeneration: Int,
        val role: P2pPlan.Role,
        val interfaceName: String,
        val localAddress: String,
        val networkIdentity: String,
        val clientCount: Int,
    ) {
        /** The endpoint is readable: there is a role, an interface and an address to bind to. */
        val endpointReady: Boolean
            get() = role != P2pPlan.Role.NONE && interfaceName.isNotEmpty() && localAddress.isNotEmpty()

        /** There is a peer on the other side of this link right now. */
        val hasMember: Boolean get() = member(role, clientCount)

        /** A socket may be created and is expected to be reachable. */
        val usable: Boolean get() = endpointReady && hasMember && membershipGeneration > 0

        val binding: P2pEndpoint.Binding get() = P2pEndpoint.bindingFor(networkIdentity.isNotEmpty(), localAddress)

        fun describe(): String = "group generation " + groupGeneration +
            ", membership generation " + membershipGeneration +
            ", role " + role +
            ", interface " + interfaceName.ifEmpty { "?" } +
            ", local " + localAddress.ifEmpty { "?" } +
            ", network " + networkIdentity.ifEmpty { "none" } +
            ", clients " + clientCount

        /** Short form for a log line that already says everything else. */
        fun generationText(): String = groupGeneration.toString() + "." + membershipGeneration
    }

    val NONE = Plane(0, 0, P2pPlan.Role.NONE, "", "", "", 0)

    /**
     * Membership as the data plane means it: an owner has a member when a
     * client actually joined, a client is itself the member of the group it
     * joined.
     */
    fun member(role: P2pPlan.Role, clientCount: Int): Boolean = when (role) {
        P2pPlan.Role.GROUP_OWNER -> clientCount >= 1
        P2pPlan.Role.CLIENT -> true
        P2pPlan.Role.NONE -> false
    }

    /** Has the endpoint itself changed, so that every socket of it is worthless? */
    fun endpointChanged(current: Plane, o: P2pEndpoint.Observed): Boolean =
        current.role != o.role || current.interfaceName != o.interfaceName ||
            current.localAddress != o.localAddress || current.networkIdentity != o.networkIdentity

    fun changeReason(current: Plane, o: P2pEndpoint.Observed): String {
        val parts = ArrayList<String>()
        if (current.role != o.role) parts.add("role " + current.role + " -> " + o.role)
        if (current.interfaceName != o.interfaceName) parts.add("interface " + current.interfaceName.ifEmpty { "none" } + " -> " + o.interfaceName.ifEmpty { "none" })
        if (current.localAddress != o.localAddress) parts.add("local address " + current.localAddress.ifEmpty { "none" } + " -> " + o.localAddress.ifEmpty { "none" })
        if (current.networkIdentity != o.networkIdentity) parts.add("android network " + current.networkIdentity.ifEmpty { "none" } + " -> " + o.networkIdentity.ifEmpty { "none" })
        return if (parts.isEmpty()) "nothing changed" else parts.joinToString(", ")
    }

    /**
     * The plane after an observation.
     *
     * - the group is gone: the endpoint is forgotten, the group counter is
     *   kept so the next group gets a new number,
     * - the endpoint changed: a new group generation, and membership starts
     *   again from zero because a fresh group has no members yet,
     * - a member appeared where there was none: a new membership generation.
     *   **This is the transition that the phone run proved matters.**
     * - nothing changed: the same plane, so nothing is rebuilt.
     */
    fun advance(current: Plane, o: P2pEndpoint.Observed, clientCount: Int, groupFormed: Boolean): Plane {
        if (!groupFormed) return current.copy(role = P2pPlan.Role.NONE, interfaceName = "", localAddress = "", networkIdentity = "", clientCount = 0)
        val changed = endpointChanged(current, o)
        val nowMember = member(o.role, clientCount)
        if (changed) {
            return Plane(current.groupGeneration + 1, if (nowMember) 1 else 0, o.role, o.interfaceName, o.localAddress, o.networkIdentity, clientCount)
        }
        val wasMember = current.hasMember
        val membership = when {
            nowMember && !wasMember -> current.membershipGeneration + 1
            else -> current.membershipGeneration
        }
        if (membership == current.membershipGeneration && clientCount == current.clientCount) return current
        return current.copy(membershipGeneration = membership, clientCount = clientCount)
    }

    /** A server socket, and the exact plane generation it was built for. */
    data class Listener(
        val plane: Plane,
        val boundAddress: String,
        val port: Int,
        val accepting: Boolean,
        val binding: P2pEndpoint.Binding,
    ) {
        fun describe(): String = boundAddress.ifEmpty { "?" } + ":" + port +
            " (group generation " + plane.groupGeneration +
            ", membership generation " + plane.membershipGeneration +
            ", interface " + plane.interfaceName.ifEmpty { "?" } +
            ", network " + plane.networkIdentity.ifEmpty { "none" } +
            ", binding " + P2pEndpoint.bindingText(binding, boundAddress) +
            ", accepting " + accepting + ")"
    }

    enum class Verdict {
        NO_ENDPOINT, NO_MEMBER, NO_LISTENER, STALE_GROUP, STALE_MEMBERSHIP,
        WRONG_INTERFACE, WRONG_ADDRESS, WRONG_NETWORK, NOT_ACCEPTING, VALID
    }

    /**
     * Is [listener] the listener of THIS live data plane? `STALE_MEMBERSHIP`
     * is the verdict v0.9.13 could not produce: same group, same address,
     * same network, but built before the client existed.
     */
    fun validate(plane: Plane, listener: Listener?): Verdict = when {
        !plane.endpointReady -> Verdict.NO_ENDPOINT
        !plane.hasMember -> Verdict.NO_MEMBER
        listener == null -> Verdict.NO_LISTENER
        listener.plane.groupGeneration != plane.groupGeneration -> Verdict.STALE_GROUP
        listener.plane.membershipGeneration != plane.membershipGeneration -> Verdict.STALE_MEMBERSHIP
        listener.plane.interfaceName != plane.interfaceName -> Verdict.WRONG_INTERFACE
        listener.boundAddress != plane.localAddress -> Verdict.WRONG_ADDRESS
        listener.plane.networkIdentity != plane.networkIdentity -> Verdict.WRONG_NETWORK
        !listener.accepting -> Verdict.NOT_ACCEPTING
        else -> Verdict.VALID
    }

    fun ok(v: Verdict): Boolean = v == Verdict.VALID

    fun verdictText(v: Verdict): String = when (v) {
        Verdict.NO_ENDPOINT -> "there is no readable P2P endpoint yet"
        Verdict.NO_MEMBER -> "nobody has joined this link yet"
        Verdict.NO_LISTENER -> "no listener exists for this data plane"
        Verdict.STALE_GROUP -> "the listener belongs to an older group"
        Verdict.STALE_MEMBERSHIP -> "the listener was built before this client membership existed"
        Verdict.WRONG_INTERFACE -> "the listener is on another interface"
        Verdict.WRONG_ADDRESS -> "the listener is not bound to the P2P local address"
        Verdict.WRONG_NETWORK -> "the listener belongs to another Android network"
        Verdict.NOT_ACCEPTING -> "the listener is not accepting any more"
        Verdict.VALID -> "valid for this live membership"
    }

    /**
     * May a socket produced by a loop born in [born] be used under [now]?
     * Both counters must match, so a listener from the empty-group phase can
     * never feed a live membership, and an older group can never feed a
     * newer one.
     */
    fun acceptAllowed(born: Plane, now: Plane): Boolean =
        now.usable && born.groupGeneration == now.groupGeneration && born.membershipGeneration == now.membershipGeneration

    /**
     * Who dials whom. Both phones may open a socket, because only one of
     * them can bind it properly: a provider that is ALSO on its home Wi-Fi
     * can bind an OUTGOING socket to the Wi-Fi Direct network, and Android
     * offers no way to bind a listening socket to a network at all.
     */
    enum class DialRole { WAIT, DIAL }

    /**
     * A phone dials when the data plane is usable, it knows where the peer
     * is, the peer says it is ready (or the bounded wait for that answer is
     * over) and it has not already dialled for this generation.
     */
    fun dialStep(plane: Plane, peerAddress: String, peerReady: Boolean, waitedMs: Long, alreadyDialled: Boolean): DialRole = when {
        !plane.usable -> DialRole.WAIT
        peerAddress.isEmpty() -> DialRole.WAIT
        alreadyDialled -> DialRole.WAIT
        peerReady -> DialRole.DIAL
        waitedMs >= READY_WAIT_MS -> DialRole.DIAL
        else -> DialRole.WAIT
    }

    /** How long a phone waits for the other side to say its listener is armed, before dialling anyway. */
    const val READY_WAIT_MS = 8_000L
}
