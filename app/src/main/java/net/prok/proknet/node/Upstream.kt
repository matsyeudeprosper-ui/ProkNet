package net.prok.proknet.node

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.P2pPlan
import net.prok.proknet.core.Tunnel

/**
 * v0.13.2: what Internet this phone has RIGHT NOW, independent of SELL.
 *
 * The provider-activation bug: eligibility read `gateway.upstream`, which is
 * null until `gateway.start()`, which only runs after the user taps PARTAGER.
 * So a phone on a validated Freebox with sharing off reported NO_INTERNET,
 * never got the notification, and the user could never tap PARTAGER.
 *
 * "Could this phone become a seller right now?" and "is the seller gateway
 * running?" are different questions. This object answers the first, from the
 * same evidence [Gateway] uses for the second — the scan lives here now and
 * the gateway calls it, so there is one network truth, not two.
 */
object Upstream {
    private const val tag = "UPSTREAM"

    /** Every network Android reports, as [Tunnel.NetView]s. ProkNet's own local links are marked, never chosen. */
    fun networks(context: Context): List<Pair<Network, Tunnel.NetView>> {
        val out = ArrayList<Pair<Network, Tunnel.NetView>>()
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                val lp = cm.getLinkProperties(n)
                val iface = lp?.interfaceName ?: ""
                val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                // v0.9.7: p2p interfaces join the list of local-only links that can never be an upstream
                val isProk = wifi && (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || P2pPlan.isLocalLinkIface(iface))
                out.add(n to Tunnel.NetView(
                    n.toString() + "/" + iface, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR), wifi, isProk,
                ))
            }
        } catch (e: Exception) { DiagLog.w(tag, "networks: " + e) }
        return out
    }

    /** The Internet this phone would sell if it started now. Null when it has none. */
    fun now(context: Context): Tunnel.NetView? = Tunnel.chooseUpstream(networks(context).map { it.second })

    /** "Wi-Fi, validated" / "mobile data, not validated" / "none", for the diagnostic. */
    fun describe(n: Tunnel.NetView?): String =
        n?.let { Tunnel.upstreamName(Tunnel.upstreamType(it)).uppercase() + (if (it.validated) " validated" else " NOT validated") } ?: "NONE"
}
