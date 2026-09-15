package net.prok.proknet.core

/**
 * v0.9.13/v0.9.14: **what a Wi-Fi Direct socket is bound to**, in words that
 * can be tested on the JVM and printed in a log.
 *
 * This file holds the observation and the binding vocabulary only. The
 * lifecycle itself (generations, membership, which listener is live) is in
 * [P2pDataPlane], and the Android side that produces these observations is
 * [net.prok.proknet.transport.P2pSocketBinding].
 */
object P2pEndpoint {

    /** What Android says about the local Wi-Fi Direct endpoint right now. */
    data class Observed(
        val role: P2pPlan.Role,
        val interfaceName: String,
        val localAddress: String,
        val networkIdentity: String,
    ) {
        /** Android exposes a [android.net.Network] for this endpoint. The OnePlus buyer does not. */
        val hasNetwork: Boolean get() = networkIdentity.isNotEmpty()

        fun describe(): String = "role " + role +
            ", interface " + interfaceName.ifEmpty { "?" } +
            ", local " + localAddress.ifEmpty { "?" } +
            ", network " + networkIdentity.ifEmpty { "none" }
    }

    /**
     * How a socket was tied to the Wi-Fi Direct link.
     *
     * The phone run of v0.9.13 is the reason this exists: the buyer logged
     * `android network none` and then dialled with an unbound socket, which
     * is a guess about routing, not a decision.
     */
    enum class Binding { ANDROID_NETWORK, LOCAL_ADDRESS, NONE }

    /**
     * The preferred order, and it is an order, not a fallback chain that may
     * end anywhere: the Android network when there is one, the P2P local
     * address when there is not, and nothing at all is a transport error.
     */
    fun bindingFor(hasNetwork: Boolean, localAddress: String): Binding = when {
        hasNetwork -> Binding.ANDROID_NETWORK
        localAddress.isNotEmpty() -> Binding.LOCAL_ADDRESS
        else -> Binding.NONE
    }

    fun bindingText(b: Binding, localAddress: String = ""): String = when (b) {
        Binding.ANDROID_NETWORK -> "ANDROID_NETWORK"
        Binding.LOCAL_ADDRESS -> "LOCAL_ADDRESS " + localAddress.ifEmpty { "?" }
        Binding.NONE -> "NONE"
    }

    /** A socket may never be created when we cannot tie it to the P2P link. */
    fun usable(b: Binding): Boolean = b != Binding.NONE

    const val NO_BINDING_ERROR = "no Wi-Fi Direct network and no P2P local address: refusing to open a socket that could leave by another route"

    // ---- the lines both phones are compared on --------------------------------------------------

    fun dialLine(attempt: Int, of: Int, source: String, destination: String, port: Int, iface: String, network: String, binding: Binding): String =
        "DIAL " + attempt + "/" + of + ": " + source.ifEmpty { "?" } + " -> " + destination + ":" + port +
            " | p2p interface " + iface.ifEmpty { "?" } +
            " | android network " + network.ifEmpty { "none" } +
            " | binding " + bindingText(binding, source)

    fun listenLine(stage: String, actual: String, port: Int, plane: P2pDataPlane.Plane, binding: Binding): String =
        "LISTENER " + stage + ": " + actual.ifEmpty { "?" } + ":" + port +
            " | " + plane.describe() + " | binding " + bindingText(binding, actual)
}
