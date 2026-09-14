package net.prok.proknet.node

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface
import net.prok.proknet.ble.ProkNetNode
import net.prok.proknet.core.DiagLog

/**
 * Relay Lab capability probe (v0.9). Reports what THIS phone says it can do
 * (Android APIs) and what it is doing right now (networks, interfaces, both
 * ProkNet links, relay counters). Facts only; the 3-phone test decides.
 */
object RelayProbe {

    fun capabilities(context: Context): List<Pair<String, String>> {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val pm = context.packageManager
        val out = ArrayList<Pair<String, String>>()
        out += "device" to (Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE + ")")
        out += "android" to (Build.VERSION.RELEASE + " / API " + Build.VERSION.SDK_INT + " / patch " + Build.VERSION.SECURITY_PATCH)
        out += "wifi enabled" to wm.isWifiEnabled.toString()
        out += "STA+AP concurrency (hotspot while joined)" to (if (Build.VERSION.SDK_INT >= 30) safe { wm.isStaApConcurrencySupported.toString() } else "unknown: API < 30")
        out += "STA concurrency for local-only connections" to (if (Build.VERSION.SDK_INT >= 31) safe { wm.isStaConcurrencyForLocalOnlyConnectionsSupported.toString() } else "unknown: API < 31")
        out += "STA concurrency for multi-Internet" to (if (Build.VERSION.SDK_INT >= 33) safe { wm.isStaConcurrencyForMultiInternetSupported.toString() } else "unknown: API < 33")
        out += "bridged AP concurrency" to (if (Build.VERSION.SDK_INT >= 31) safe { wm.isBridgedApConcurrencySupported.toString() } else "unknown: API < 31")
        out += "Wi-Fi Direct (P2P) feature" to pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT).toString()
        out += "P2P supported (WifiManager)" to safe { wm.isP2pSupported.toString() }
        out += "Wi-Fi Aware feature" to pm.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE).toString()
        out += "5 GHz" to safe { wm.is5GHzBandSupported.toString() }
        out += "6 GHz" to (if (Build.VERSION.SDK_INT >= 30) safe { wm.is6GHzBandSupported.toString() } else "unknown")
        out += "WPA3 SAE" to (if (Build.VERSION.SDK_INT >= 29) safe { wm.isWpa3SaeSupported.toString() } else "unknown")
        return out
    }

    private fun safe(f: () -> String): String = try { f() } catch (e: Throwable) { "error: " + e.javaClass.simpleName }

    fun networks(context: Context): List<String> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val out = ArrayList<String>()
        try {
            val active = cm.activeNetwork
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                val lp = cm.getLinkProperties(n)
                val t = ArrayList<String>()
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) t += "WIFI"
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) t += "CELL"
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) t += "VPN"
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) t += "BT"
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) t += "ETH"
                out += n.toString() + " " + t.joinToString("+") + " iface=" + (lp?.interfaceName ?: "?") +
                    (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) " INTERNET" else " local-only") +
                    (if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) " validated" else "") +
                    (if (n == active) " DEFAULT" else "") +
                    " addrs=" + (lp?.linkAddresses?.joinToString(",") { it.toString() } ?: "-") +
                    " dns=" + (lp?.dnsServers?.joinToString(",") { it.hostAddress ?: "?" } ?: "-")
            }
        } catch (e: Exception) { out += "networks: " + e }
        return out
    }

    fun interfaces(): List<String> {
        val out = ArrayList<String>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                val v4 = nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress }
                if (v4.isNotEmpty()) out += nif.name + "=" + v4.joinToString(",")
            }
        } catch (e: Exception) { out += "interfaces: " + e }
        return out
    }

    /** The whole relay diagnostic: capabilities, live networks, both links, relay counters, last log lines. */
    fun text(context: Context, node: ProkNetNode, logLines: Int = 80): String {
        val sb = StringBuilder()
        sb.append("Prok RELAY DIAG ").append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())).append("\n")
        sb.append("me: prok-").append(node.identity.shortIdHex).append(" \"").append(node.identity.displayName).append("\"\n")
        sb.append("--- capabilities (what Android says) ---\n")
        for ((k, v) in capabilities(context)) sb.append(k).append(": ").append(v).append("\n")
        sb.append("--- networks now (ConnectivityManager) ---\n")
        for (l in networks(context)) sb.append(l).append("\n")
        sb.append("interfaces with IPv4: ").append(interfaces().joinToString(" ")).append("\n")
        sb.append("--- ProkNet links ---\n")
        sb.append("DOWNSTREAM / normal link: ").append(node.wifi.linkState()).append("\n  ").append(node.wifi.linkDescription())
            .append(if (node.wifi.hotspotSsid.isNotEmpty()) "\n  hotspot ssid " + node.wifi.hotspotSsid else "").append(if (node.wifi.lastHotspotError.isNotEmpty()) "\n  last hotspot error: " + node.wifi.lastHotspotError else "")
            .append("\n  relay bytes sent ").append(node.wifi.relayBytesSent).append(" recv ").append(node.wifi.relayBytesReceived).append("\n")
        sb.append("UPSTREAM link: ").append(node.wifiUp.linkState()).append("\n  ").append(node.wifiUp.linkDescription())
            .append("\n  relay bytes sent ").append(node.wifiUp.relayBytesSent).append(" recv ").append(node.wifiUp.relayBytesReceived).append("\n")
        sb.append("--- relay ---\n")
        sb.append(node.relay.stateLine()).append("\n")
        node.relay.session?.let { sb.append("current: ").append(it.summary()).append("\n") }
        for (s in node.relay.history.take(5)) sb.append("past: ").append(s.summary()).append("\n")
        if (node.relay.lastEvent.isNotEmpty()) sb.append("last event: ").append(node.relay.lastEvent).append("\n")
        sb.append("seller side: ").append(node.gateway.state).append(" upstream ").append(node.gateway.upstreamDescription())
            .append(node.gateway.session?.let { " | session with prok-" + it.peerShort + " up " + it.bytesUp + " B down " + it.bytesDown + " B" } ?: "").append("\n")
        sb.append("buyer side: ").append(node.tunnel.state).append(node.tunnel.providerShort?.let { " provider prok-" + it } ?: "").append(" vpn ").append(net.prok.proknet.vpn.ProkVpnService.running)
            .append(node.tunnel.session?.let { " | up " + it.bytesUp + " B down " + it.bytesDown + " B" } ?: "").append("\n")
        sb.append("--- last ").append(logLines).append(" log lines ---\n")
        sb.append(DiagLog.text().lines().takeLast(logLines).joinToString("\n")).append("\n")
        return sb.toString()
    }
}
