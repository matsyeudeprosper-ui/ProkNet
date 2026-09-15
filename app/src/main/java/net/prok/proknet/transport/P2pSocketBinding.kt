package net.prok.proknet.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.P2pEndpoint
import net.prok.proknet.core.P2pPlan

/**
 * v0.9.13: the ONE place that turns "Wi-Fi Direct is up" into a socket that
 * really lives on the Wi-Fi Direct link.
 *
 * It answers two questions and nothing else:
 * - which Android [Network] and which local address is the P2P endpoint,
 * - bind this socket to it.
 *
 * **It is used for the local ProkNet transport socket only.** The provider's
 * Internet traffic keeps going out of the real upstream network, chosen by
 * [net.prok.proknet.node.Gateway] exactly as before: LOCAL LINK = Wi-Fi
 * Direct, UPSTREAM = the home Wi-Fi. Nothing in this class touches the
 * process-wide network, so the seller's Freebox connection cannot be
 * affected by it.
 */
class P2pSocketBinding(private val context: Context) {

    private val tag = "P2P"

    /** The P2P endpoint as the operating system sees it. [network] is null when Android exposes none. */
    class Handle(val network: Network?, val identity: String, val interfaceName: String, val localAddress: String) {
        fun describe(): String = interfaceName.ifEmpty { "?" } + " " + localAddress.ifEmpty { "?" } +
            " (network " + identity.ifEmpty { "none" } + ")"
    }

    /**
     * Find the live Wi-Fi Direct endpoint. [preferred] is the interface name
     * Android reported for the group, when we have it; a different p2p
     * interface is accepted only if the preferred one is not there, because
     * the group interface is renamed on every group (`p2p-wlan0-25`).
     */
    fun resolve(preferred: String?): Handle? {
        val fromNetwork = fromConnectivity(preferred)
        if (fromNetwork != null && fromNetwork.localAddress.isNotEmpty()) return fromNetwork
        val fromIface = fromInterfaces(preferred)
        // keep the Android network handle when we have one, even if the address came from the interface
        if (fromNetwork != null && fromIface != null)
            return Handle(fromNetwork.network, fromNetwork.identity, fromNetwork.interfaceName, fromIface.localAddress)
        return fromNetwork ?: fromIface
    }

    private fun cm(): ConnectivityManager? = try {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    } catch (e: Exception) { null }

    private fun fromConnectivity(preferred: String?): Handle? {
        val c = cm() ?: return null
        var fallback: Handle? = null
        try {
            for (n in c.allNetworks) {
                val lp = c.getLinkProperties(n) ?: continue
                val iface = lp.interfaceName ?: continue
                if (!P2pPlan.isP2pIface(iface)) continue
                val v4 = lp.linkAddresses.mapNotNull { it.address }.filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress ?: ""
                val h = Handle(n, n.toString(), iface, v4)
                if (!preferred.isNullOrEmpty() && iface == preferred) return h
                if (fallback == null) fallback = h
            }
        } catch (e: Exception) { DiagLog.w(tag, "reading the P2P network: " + e) }
        return fallback
    }

    private fun fromInterfaces(preferred: String?): Handle? {
        var fallback: Handle? = null
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                if (!P2pPlan.isP2pIface(nif.name)) continue
                val v4 = nif.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull()?.hostAddress ?: continue
                val h = Handle(null, "", nif.name, v4)
                if (!preferred.isNullOrEmpty() && nif.name == preferred) return h
                if (fallback == null) fallback = h
            }
        } catch (e: Exception) { DiagLog.w(tag, "reading the P2P interface: " + e) }
        return fallback
    }

    fun observe(role: P2pPlan.Role, h: Handle?): P2pEndpoint.Observed =
        P2pEndpoint.Observed(role, h?.interfaceName ?: "", h?.localAddress ?: "", h?.identity ?: "")

    /**
     * Bind an outgoing socket to the P2P network BEFORE it connects, so the
     * dial cannot leave through the phone's other network. Returns whether
     * the socket really belongs to the P2P network now.
     */
    fun bind(socket: Socket, h: Handle?): Boolean {
        val n = h?.network ?: return false
        return try { n.bindSocket(socket); true } catch (e: Exception) { DiagLog.w(tag, "bindSocket: " + e); false }
    }

    /**
     * Open a server socket ON the P2P local address. Not `0.0.0.0`: the
     * listener has to belong to this endpoint, and be seen to.
     */
    fun listenOn(h: Handle, port: Int): ServerSocket {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(java.net.InetSocketAddress(InetAddress.getByName(h.localAddress), port))
        return ss
    }
}
