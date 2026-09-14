package net.prok.proknet.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.LinkIo
import net.prok.proknet.core.P2pPlan

/**
 * Method B, experimental (v0.9.7): a local link over **Wi-Fi Direct**, so a
 * seller can serve a customer WHILE it stays connected to its home router.
 * Method A (LocalOnlyHotspot, `WifiTransport`) is untouched and still the
 * only path the consumer app uses.
 *
 * This class only builds the pipe: discover, form the group, and hand a
 * connected TCP socket to [WifiTransport.adoptSocket]. Everything above it
 * (signed handshake, framing, tunnel, VPN, contract, checkpoints) is the
 * existing ProkNet layer, unchanged.
 *
 * Android decides who owns the group, so both roles are handled: the group
 * owner listens on [P2pPlan.PORT], the client dials the group owner.
 */
class P2pLink(private val context: Context, private val hooks: Hooks) {
    interface Hooks {
        /** A socket is connected to a ProkNet peer: adopt it as the authenticated link. */
        fun onSocket(socket: Socket, isHost: Boolean)
        /** Something changed worth showing in the lab screen. */
        fun onChanged()
        /** The Wi-Fi network this phone is on right now, "" when none: the point of the whole experiment. */
        fun staDescription(): String
    }

    private val tag = "P2P"
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var server: ServerSocket? = null

    @Volatile var phase: String = "IDLE"
        private set
    @Volatile var role: P2pPlan.Role = P2pPlan.Role.NONE
        private set
    @Volatile var lastError: String = ""
        private set
    @Volatile var groupInfo: String = ""
        private set
    @Volatile var ifaceInfo: String = ""
        private set
    @Volatile var staBefore: String = ""
        private set
    @Volatile var staAfter: String = ""
        private set
    @Volatile var linkAuthenticated = false
    @Volatile var groupFormed = false
        private set
    @Volatile var socketInfo: String = ""
        private set
    @Volatile var p2pEnabled = true
        private set
    /** Peers seen by the buyer, for the developer list. */
    @Volatile var peers: List<Peer> = emptyList()
        private set

    class Peer(val name: String, val address: String, val status: String, val isGroupOwner: Boolean) {
        fun describe(): String = name + "  " + address + "  " + status + (if (isGroupOwner) "  [group owner]" else "")
    }

    val supported: Boolean get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    fun verdict(): P2pPlan.Verdict =
        if (phase == "IDLE") P2pPlan.Verdict.NOT_RUN
        else P2pPlan.verdict(groupFormed, staAfter.isNotEmpty() && staAfter == staBefore, linkAuthenticated)

    private fun setPhase(p: String, why: String = "") {
        phase = p
        DiagLog.i(tag, "PHASE " + p + (if (why.isNotEmpty()) " - " + why else ""))
        hooks.onChanged()
    }

    // ---- lifecycle ---------------------------------------------------------------------------------

    private fun ensureChannel(): Boolean {
        if (channel != null) return true
        if (!supported) { lastError = "this phone has no Wi-Fi Direct"; return false }
        return try {
            val m = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
            val c = m.initialize(context, Looper.getMainLooper(), WifiP2pManager.ChannelListener {
                DiagLog.w(tag, "p2p channel disconnected"); channel = null
            })
            manager = m; channel = c
            registerReceiver()
            true
        } catch (e: Exception) { lastError = "initialize: " + LinkIo.describe(e); false }
    }

    private fun registerReceiver() {
        if (receiver != null) return
        val f = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { if (i != null) onP2pBroadcast(i) }
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(r, f)
            receiver = r
        } catch (e: Exception) { DiagLog.w(tag, "registerReceiver: " + e) }
    }

    fun stop() {
        try { manager?.removeGroup(channel, null) } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}; server = null
        receiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) {} }; receiver = null
        try { manager?.stopPeerDiscovery(channel, null) } catch (_: Exception) {}
        groupFormed = false; role = P2pPlan.Role.NONE; peers = emptyList()
        setPhase("IDLE", "stopped by user")
    }

    // ---- the two developer entry points ----------------------------------------------------------

    /** Seller: become an autonomous group owner and wait for the buyer. The home Wi-Fi must survive this. */
    fun startSeller(): String? {
        if (!ensureChannel()) return lastError
        staBefore = hooks.staDescription(); staAfter = staBefore
        linkAuthenticated = false
        DiagLog.i(tag, "SELL TEST: creating a Wi-Fi Direct group while this phone is on " + (staBefore.ifEmpty { "no Wi-Fi network" }))
        setPhase("CREATING GROUP")
        try {
            manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { createGroup() }
                override fun onFailure(reason: Int) { createGroup() }   // nothing to remove: normal
            })
        } catch (e: SecurityException) { lastError = "permission: " + e.message; setPhase("FAILED", lastError); return lastError }
        return null
    }

    private fun createGroup() {
        try {
            manager?.createGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { DiagLog.i(tag, "createGroup accepted, waiting for the group to form"); refreshAll() }
                override fun onFailure(reason: Int) {
                    lastError = "createGroup failed: " + reasonName(reason)
                    setPhase("FAILED", lastError)
                }
            })
        } catch (e: SecurityException) { lastError = "permission: " + e.message; setPhase("FAILED", lastError) }
    }

    /** Buyer: look for the seller's group. */
    fun startBuyer(): String? {
        if (!ensureChannel()) return lastError
        staBefore = hooks.staDescription(); staAfter = staBefore
        linkAuthenticated = false
        setPhase("DISCOVERING")
        try {
            manager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { DiagLog.i(tag, "discoverPeers accepted") }
                override fun onFailure(reason: Int) { lastError = "discoverPeers failed: " + reasonName(reason); setPhase("FAILED", lastError) }
            })
        } catch (e: SecurityException) { lastError = "permission: " + e.message; setPhase("FAILED", lastError); return lastError }
        return null
    }

    /** Buyer: join the group of [address] (its P2P MAC from the peer list). */
    fun connectTo(address: String): String? {
        if (!ensureChannel()) return lastError
        val cfg = WifiP2pConfig().apply {
            deviceAddress = address
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0        // we would rather be the client; Android may still decide otherwise
        }
        setPhase("CONNECTING", "joining " + address)
        try {
            manager?.connect(channel, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { DiagLog.i(tag, "connect accepted for " + address) }
                override fun onFailure(reason: Int) { lastError = "connect failed: " + reasonName(reason); setPhase("FAILED", lastError) }
            })
        } catch (e: SecurityException) { lastError = "permission: " + e.message; setPhase("FAILED", lastError); return lastError }
        return null
    }

    // ---- broadcasts --------------------------------------------------------------------------------

    private fun onP2pBroadcast(i: Intent) {
        when (i.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                p2pEnabled = i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                DiagLog.i(tag, "Wi-Fi Direct " + (if (p2pEnabled) "enabled" else "DISABLED"))
                hooks.onChanged()
            }
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> refreshAll()
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> hooks.onChanged()
        }
    }

    private fun requestPeers() {
        try {
            manager?.requestPeers(channel) { list ->
                peers = list.deviceList.map { Peer(it.deviceName ?: "?", it.deviceAddress ?: "", statusName(it.status), it.isGroupOwner) }
                DiagLog.i(tag, "peers: " + (if (peers.isEmpty()) "none" else peers.joinToString("; ") { it.describe() }))
                hooks.onChanged()
            }
        } catch (e: SecurityException) { DiagLog.w(tag, "requestPeers: " + e.message) }
    }

    private fun refreshAll() {
        try {
            manager?.requestConnectionInfo(channel) { info -> onConnectionInfo(info) }
            manager?.requestGroupInfo(channel) { g -> onGroupInfo(g) }
        } catch (e: SecurityException) { DiagLog.w(tag, "requestConnectionInfo: " + e.message) }
    }

    private fun onGroupInfo(g: WifiP2pGroup?) {
        if (g == null) { groupInfo = "" ; return }
        val iface = try { g.`interface` } catch (e: Exception) { null }
        groupInfo = "ssid=" + g.networkName + " owner=" + (g.owner?.deviceName ?: "?") + " clients=" + (g.clientList?.size ?: 0) + " iface=" + (iface ?: "?")
        ifaceInfo = interfaces()
        DiagLog.i(tag, "group: " + groupInfo + " | interfaces: " + ifaceInfo)
        hooks.onChanged()
    }

    private fun onConnectionInfo(info: WifiP2pInfo?) {
        staAfter = hooks.staDescription()
        groupFormed = info?.groupFormed == true
        role = P2pPlan.role(groupFormed, info?.isGroupOwner == true)
        val go = info?.groupOwnerAddress?.hostAddress
        DiagLog.i(tag, "connection: formed=" + groupFormed + " role=" + role + " groupOwner=" + go +
            " | my Wi-Fi network before=" + (staBefore.ifEmpty { "none" }) + " now=" + (staAfter.ifEmpty { "none" }))
        if (!groupFormed) { setPhase(if (phase == "FAILED") "FAILED" else "WAITING", "no group yet"); return }
        if (staBefore.isNotEmpty() && staAfter != staBefore) DiagLog.e(tag, "the Wi-Fi Direct group KILLED this phone's Wi-Fi connection (" + staBefore + " -> " + (staAfter.ifEmpty { "none" }) + ")")
        when (role) {
            P2pPlan.Role.GROUP_OWNER -> { setPhase("GROUP OWNER", "listening on " + P2pPlan.PORT); listen() }
            P2pPlan.Role.CLIENT -> {
                val target = P2pPlan.socketTarget(role, go)
                if (target == null) { setPhase("FAILED", "no group owner address"); return }
                setPhase("CLIENT", "dialling " + target + ":" + P2pPlan.PORT); dial(target)
            }
            P2pPlan.Role.NONE -> {}
        }
    }

    // ---- sockets -----------------------------------------------------------------------------------

    private fun listen() {
        if (server != null) return
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(P2pPlan.PORT))
            server = ss
            socketInfo = "listening on :" + P2pPlan.PORT
            io.execute {
                while (!ss.isClosed) {
                    val s = try { ss.accept() } catch (e: Exception) { break }
                    socketInfo = "accepted " + s.inetAddress?.hostAddress + ":" + s.port + " on " + s.localAddress?.hostAddress
                    DiagLog.i(tag, "TCP " + socketInfo)
                    main.post { hooks.onSocket(s, true) }
                }
            }
            DiagLog.i(tag, "group owner socket " + socketInfo)
        } catch (e: Exception) { lastError = "server socket: " + LinkIo.describe(e); setPhase("FAILED", lastError) }
    }

    private fun dial(host: String) {
        io.execute {
            var last = ""
            for (attempt in 1..6) {
                try {
                    val s = Socket()
                    s.connect(InetSocketAddress(host, P2pPlan.PORT), 5000)
                    socketInfo = "connected " + s.localAddress?.hostAddress + " -> " + host + ":" + P2pPlan.PORT
                    DiagLog.i(tag, "TCP " + socketInfo)
                    main.post { hooks.onSocket(s, false) }
                    return@execute
                } catch (e: Exception) {
                    last = LinkIo.describe(e)
                    DiagLog.w(tag, "TCP attempt " + attempt + "/6 to " + host + " failed: " + last)
                    try { Thread.sleep(1500) } catch (_: InterruptedException) { return@execute }
                }
            }
            lastError = "could not reach the group owner: " + last
            main.post { setPhase("FAILED", lastError) }
        }
    }

    // ---- diagnostics -------------------------------------------------------------------------------

    private fun interfaces(): String {
        val out = StringBuilder()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                val v4 = nif.inetAddresses.toList().filterIsInstance<java.net.Inet4Address>().map { it.hostAddress }
                if (v4.isNotEmpty()) out.append(nif.name).append("=").append(v4.joinToString(",")).append(" ")
            }
        } catch (e: Exception) { out.append("interfaces: ").append(e) }
        return out.toString().trim()
    }

    fun diag(): String {
        val sb = StringBuilder()
        sb.append("--- Wi-Fi Direct (method B, experimental) ---\n")
        sb.append("isP2pSupported (feature): ").append(supported).append("\n")
        sb.append("Wi-Fi Direct enabled: ").append(p2pEnabled).append("\n")
        sb.append("phase: ").append(phase).append("  role: ").append(role).append("\n")
        sb.append("group: ").append(groupInfo.ifEmpty { "none" }).append("\n")
        sb.append("socket: ").append(socketInfo.ifEmpty { "none" }).append("\n")
        sb.append("interfaces now: ").append(ifaceInfo.ifEmpty { interfaces() }).append("\n")
        sb.append("my Wi-Fi network BEFORE p2p: ").append(staBefore.ifEmpty { "none" }).append("\n")
        sb.append("my Wi-Fi network NOW: ").append(hooks.staDescription().ifEmpty { "none" }).append("\n")
        sb.append("ProkNet link authenticated over p2p: ").append(linkAuthenticated).append("\n")
        sb.append("peers: ").append(if (peers.isEmpty()) "none" else peers.joinToString("; ") { it.describe() }).append("\n")
        sb.append("verdict: ").append(P2pPlan.verdictText(verdict())).append("\n")
        if (lastError.isNotEmpty()) sb.append("last error: ").append(lastError).append("\n")
        return sb.toString()
    }

    private fun reasonName(r: Int) = when (r) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        WifiP2pManager.ERROR -> "ERROR (internal)"
        WifiP2pManager.BUSY -> "BUSY (framework busy, try again)"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
        else -> "reason " + r
    }

    private fun statusName(s: Int) = when (s) {
        WifiP2pDevice.AVAILABLE -> "available"
        WifiP2pDevice.INVITED -> "invited"
        WifiP2pDevice.CONNECTED -> "connected"
        WifiP2pDevice.FAILED -> "failed"
        WifiP2pDevice.UNAVAILABLE -> "unavailable"
        else -> "status " + s
    }
}
