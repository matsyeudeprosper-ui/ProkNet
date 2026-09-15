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
 * Method B, experimental: a local link over **Wi-Fi Direct**, so a seller
 * can serve a customer WHILE it stays connected to its home router. Method A
 * (LocalOnlyHotspot, `WifiTransport`) is untouched and is still the only
 * path the consumer app uses.
 *
 * This class only builds the pipe: clean up, discover, form the group, and
 * hand a connected TCP socket to [WifiTransport.adoptSocket]. Everything
 * above it (signed handshake, framing, tunnel, VPN, contract, checkpoints)
 * is the existing ProkNet layer, unchanged.
 *
 * v0.9.8: the lifecycle is deterministic. A phone test showed a buyer still
 * holding `group ssid=DIRECT-...`, `p2p0=192.168.49.1` and a listening
 * server after STOP, because the old code fired removeGroup and forgot it
 * while wiping its own state at once. Now every role change walks
 * cancelConnect -> stopPeerDiscovery -> close sockets -> removeGroup, each
 * step waiting for Android to answer (or a 4 s watchdog), and only then
 * starts the new role. The visible state lives in the pure
 * [P2pPlan.Life], so nothing is cleared early or left behind.
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
    private val CREATE_ATTEMPTS = 3
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val life = P2pPlan.Life()

    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null
    private var server: ServerSocket? = null
    private var cleanupSeq = 0
    @Volatile private var discovering = false

    // ---- what the lab screen reads: one source of truth ------------------------------------------

    val phase: String get() = P2pPlan.stageName(life.stage)
    val role: P2pPlan.Role get() = life.role
    val groupFormed: Boolean get() = life.groupFormed
    val groupInfo: String get() = life.groupInfo
    val socketInfo: String get() = life.socketInfo
    fun view(): P2pPlan.View = life.view()

    @Volatile var lastError: String = ""
        private set
    @Volatile var ifaceInfo: String = ""
        private set
    @Volatile var staBefore: String = ""
        private set
    @Volatile var staAfter: String = ""
        private set
    @Volatile var linkAuthenticated = false
    @Volatile var p2pEnabled = true
        private set
    @Volatile var peers: List<Peer> = emptyList()
        private set
    /** v0.9.9: this phone's own Wi-Fi Direct name. Android hides its own MAC, the name is what the peer can match. */
    @Volatile var myDeviceName: String = ""
        private set
    /** How many phones actually joined the group. The v0.9.8 run was stuck at 0. */
    @Volatile var clientCount: Int = 0
        private set
    /** The last invitation this phone sent, and what Android answered. */
    @Volatile var lastInvite: String = ""
        private set
    /** v0.9.12: the last join THIS phone attempted, and what Android answered. */
    @Volatile var lastJoin: String = ""
        private set

    class Peer(val name: String, val address: String, val status: String, val isGroupOwner: Boolean) {
        fun describe(): String = name + "  " + address + "  " + status + (if (isGroupOwner) "  [group owner]" else "")
    }

    val supported: Boolean get() = context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    fun verdict(): P2pPlan.Verdict =
        if (life.stage == P2pPlan.Stage.IDLE && !life.groupFormed) P2pPlan.Verdict.NOT_RUN
        else P2pPlan.verdict(life.groupFormed, staAfter.isNotEmpty() && staAfter == staBefore, linkAuthenticated)

    private fun changed(why: String = "") {
        if (why.isNotEmpty()) DiagLog.i(tag, why + " -> " + life.view().describe())
        hooks.onChanged()
    }

    // ---- channel and broadcasts -------------------------------------------------------------------

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

    // ---- the deterministic cleanup ------------------------------------------------------------------

    /**
     * Walk every cleanup step in order, waiting for Android at each one.
     * [then] runs only once the last step is confirmed.
     */
    private fun cleanup(reason: String, then: () -> Unit) {
        val seq = ++cleanupSeq
        DiagLog.i(tag, "CLEANUP started (" + reason + ") from " + life.view().describe())
        changed()
        runStep(seq, life.step, then)
    }

    private fun runStep(seq: Int, step: P2pPlan.Step, then: () -> Unit) {
        if (seq != cleanupSeq) { DiagLog.i(tag, "cleanup " + seq + " abandoned: a newer one started"); return }
        if (step == P2pPlan.Step.DONE) { finishCleanup(then); return }
        var advanced = false
        val advance: (String) -> Unit = { how ->
            if (!advanced && seq == cleanupSeq) {
                advanced = true
                DiagLog.i(tag, "cleanup " + step + ": " + how)
                val next = life.done(step)
                changed()
                runStep(seq, next, then)
            }
        }
        main.postDelayed({ advance("no answer from Android within 4s, moving on") }, 4000)
        val m = manager; val c = channel
        if (m == null || c == null) { advance("no p2p channel (nothing to undo)"); return }
        try {
            when (step) {
                P2pPlan.Step.CANCEL_CONNECT -> m.cancelConnect(c, action(advance))
                P2pPlan.Step.STOP_DISCOVERY -> m.stopPeerDiscovery(c, action(advance))
                P2pPlan.Step.CLOSE_SOCKETS -> { val n = closeSockets(); advance(n) }
                P2pPlan.Step.REMOVE_GROUP -> m.removeGroup(c, action(advance))
                P2pPlan.Step.DONE -> advance("nothing to do")
            }
        } catch (e: SecurityException) { advance("permission: " + e.message) } catch (e: Exception) { advance("threw " + e.javaClass.simpleName) }
    }

    private fun action(advance: (String) -> Unit) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() { advance("ok") }
        override fun onFailure(reason: Int) { advance("nothing to undo / refused: " + reasonName(reason)) }
    }

    private fun closeSockets(): String {
        stopDiscovering()
        val had = server != null
        try { server?.close() } catch (_: Exception) {}
        server = null
        peers = emptyList()
        return if (had) "server socket closed" else "no server socket was open"
    }

    private fun finishCleanup(then: () -> Unit) {
        ifaceInfo = interfaces()
        val v = life.view()
        DiagLog.i(tag, "CLEANUP complete: " + v.describe() + " | interfaces now: " + ifaceInfo +
            (if (v.clean) "" else "  <-- STILL NOT CLEAN"))
        changed()
        then()
    }

    // ---- the three developer entry points ----------------------------------------------------------

    /** Seller: clean first, then become an autonomous group owner. The home Wi-Fi must survive it. */
    fun startSeller(): String? {
        if (!ensureChannel()) return lastError
        lastError = ""; linkAuthenticated = false
        staBefore = hooks.staDescription(); staAfter = staBefore
        DiagLog.i(tag, "SELL TEST requested while this phone is on " + (staBefore.ifEmpty { "no Wi-Fi network" }))
        life.start(P2pPlan.Want.SELL)
        cleanup("before SELL") { createGroup() }
        return null
    }

    /**
     * Buyer / guest: clean first, then become discoverable and WAIT. Since
     * v0.9.9 the guest does not call connect(): the owner invites it. The
     * node still has a deliberate fallback ladder if no invitation arrives.
     */
    fun startBuyer(): String? {
        if (!ensureChannel()) return lastError
        lastError = ""; linkAuthenticated = false
        staBefore = hooks.staDescription(); staAfter = staBefore
        DiagLog.i(tag, "BUY TEST requested while this phone is on " + (staBefore.ifEmpty { "no Wi-Fi network" }))
        life.start(P2pPlan.Want.BUY)
        cleanup("before BUY") { keepDiscovering("guest waiting to be invited") }
        return null
    }

    /** Stop everything and only say IDLE once Android has confirmed each step. */
    fun stop() {
        if (!ensureChannel() && manager == null) { life.stop(); while (life.step != P2pPlan.Step.DONE) life.done(life.step); changed("stop with no p2p"); return }
        life.stop()
        cleanup("STOP") {
            receiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) {} }; receiver = null
            changed("IDLE confirmed")
        }
    }

    /**
     * v0.9.11: the framework answers BUSY right after a cleanup often enough that one attempt is not
     * a design. A seller whose group never came up advertised the Wi-Fi Direct way in and refused
     * every buyer, which is exactly what the phone test showed.
     */
    private fun createGroup(attempt: Int = 1) {
        val m = manager; val c = channel
        if (m == null || c == null) { fail("no p2p channel"); return }
        DiagLog.i(tag, "creating a fresh Wi-Fi Direct group (attempt " + attempt + "/" + CREATE_ATTEMPTS + ")")
        try {
            m.createGroup(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { DiagLog.i(tag, "createGroup accepted, waiting for the group to form"); refreshAll() }
                override fun onFailure(reason: Int) {
                    if (attempt < CREATE_ATTEMPTS) {
                        DiagLog.w(tag, "createGroup refused (" + reasonName(reason) + "), retrying in 3s")
                        main.postDelayed({ if (life.stage == P2pPlan.Stage.CREATING_GROUP) createGroup(attempt + 1) }, 3_000)
                    } else fail("createGroup failed after " + attempt + " attempts: " + reasonName(reason))
                }
            })
        } catch (e: SecurityException) { fail("permission: " + e.message) }
    }

    private fun discover() {
        val m = manager; val c = channel
        if (m == null || c == null) { fail("no p2p channel"); return }
        DiagLog.i(tag, "starting peer discovery from a clean state")
        try {
            m.discoverPeers(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { DiagLog.i(tag, "discoverPeers accepted"); changed() }
                override fun onFailure(reason: Int) { fail("discoverPeers failed: " + reasonName(reason)) }
            })
        } catch (e: SecurityException) { fail("permission: " + e.message) }
    }

    /**
     * Buyer: join the group of [address] (the seller's REAL P2P address, taken from THIS phone's own
     * peer list). v0.9.12 makes this the main admission path, because the owner's peer list
     * anonymises the buyer (`00:00:00:00:00:00`) on the phones under test.
     *
     * [onResult] reports what Android answered, so the caller can back off on BUSY instead of
     * looping.
     */
    fun connectTo(address: String, onResult: ((Boolean, String) -> Unit)? = null): String? {
        if (P2pPlan.anonymous(address)) { val e = "refusing to join an anonymised peer (" + address + ")"; DiagLog.w(tag, e); onResult?.invoke(false, e); return e }
        val m = manager; val c = channel
        if (m == null || c == null) { if (!ensureChannel()) { onResult?.invoke(false, lastError); return lastError } }
        val cfg = WifiP2pConfig().apply {
            deviceAddress = address
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0        // we want to be the client of an existing group
        }
        lastJoin = "buyer connect() requested to " + address
        DiagLog.i(tag, lastJoin)
        changed()
        try {
            manager?.connect(channel, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    lastJoin = "connect accepted for " + address + ", waiting for the group"
                    DiagLog.i(tag, lastJoin); changed(); onResult?.invoke(true, "accepted")
                }
                override fun onFailure(reason: Int) {
                    lastJoin = "connect refused for " + address + ": " + reasonName(reason)
                    DiagLog.w(tag, lastJoin); changed(); onResult?.invoke(false, reasonName(reason))
                }
            })
        } catch (e: SecurityException) { lastError = "permission: " + e.message; fail(lastError); onResult?.invoke(false, lastError); return lastError }
        return null
    }

    /** Peers this phone can really address (the anonymised ones are useless). */
    fun realPeers(): List<P2pPlan.PeerRef> = peers.filter { !P2pPlan.anonymous(it.address) }.map { P2pPlan.PeerRef(it.name, it.address) }

    fun groupOwnerAddresses(): Set<String> = peers.filter { it.isGroupOwner && !P2pPlan.anonymous(it.address) }.map { it.address }.toSet()

    private fun fail(why: String) {
        lastError = why
        life.fail()
        DiagLog.e(tag, why)
        changed()
    }

    // ---- broadcasts ---------------------------------------------------------------------------------

    private fun onP2pBroadcast(i: Intent) {
        when (i.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                p2pEnabled = i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                DiagLog.i(tag, "Wi-Fi Direct " + (if (p2pEnabled) "enabled" else "DISABLED"))
                changed()
            }
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> requestPeers()
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> refreshAll()
            WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                val d = try { i.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE) } catch (e: Exception) { null }
                if (d != null) {
                    if (d.deviceName != null && d.deviceName != myDeviceName) DiagLog.i(tag, "this phone is \"" + d.deviceName + "\" on Wi-Fi Direct (its own MAC is hidden by Android)")
                    myDeviceName = d.deviceName ?: myDeviceName
                }
                changed()
            }
        }
    }

    private fun requestPeers() {
        if (life.cleaning) { DiagLog.i(tag, "peers broadcast ignored during cleanup"); return }
        try {
            manager?.requestPeers(channel) { list ->
                peers = list.deviceList.map { Peer(it.deviceName ?: "?", it.deviceAddress ?: "", statusName(it.status), it.isGroupOwner) }
                life.onPeers(peers.size)
                DiagLog.i(tag, "peers: " + (if (peers.isEmpty()) "none" else peers.joinToString("; ") { it.describe() }))
                changed()
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
        if (life.cleaning) { DiagLog.i(tag, "group info ignored during cleanup"); return }
        if (g == null) return
        val iface = try { g.`interface` } catch (e: Exception) { null }
        clientCount = g.clientList?.size ?: 0
        val info = "ssid=" + g.networkName + " owner=" + (g.owner?.deviceName ?: "?") + " clients=" + clientCount + " iface=" + (iface ?: "?")
        life.onGroup(true, g.isGroupOwner, info)
        ifaceInfo = interfaces()
        DiagLog.i(tag, "group: " + info + " | interfaces: " + ifaceInfo)
        changed()
    }

    private fun onConnectionInfo(info: WifiP2pInfo?) {
        staAfter = hooks.staDescription()
        if (life.cleaning) { DiagLog.i(tag, "connection info ignored during cleanup (formed=" + (info?.groupFormed == true) + ")"); return }
        val formed = info?.groupFormed == true
        val go = info?.groupOwnerAddress?.hostAddress
        life.onGroup(formed, info?.isGroupOwner == true, life.groupInfo.ifEmpty { "groupOwner=" + go })
        DiagLog.i(tag, "connection: formed=" + formed + " role=" + life.role + " groupOwner=" + go +
            " | my Wi-Fi network before=" + (staBefore.ifEmpty { "none" }) + " now=" + (staAfter.ifEmpty { "none" }))
        if (!formed) { changed(); return }
        if (staBefore.isNotEmpty() && staAfter != staBefore) DiagLog.e(tag, "the Wi-Fi Direct group KILLED this phone's Wi-Fi connection (" + staBefore + " -> " + (staAfter.ifEmpty { "none" }) + ")")
        when (life.role) {
            P2pPlan.Role.GROUP_OWNER -> { listen(); keepDiscovering("group owner waiting for a guest") }
            P2pPlan.Role.CLIENT -> {
                val target = P2pPlan.socketTarget(life.role, go)
                if (target == null) { fail("no group owner address"); return }
                dial(target)
            }
            P2pPlan.Role.NONE -> {}
        }
        changed()
    }

    /**
     * v0.9.9: discovery has to stay alive on BOTH sides. The owner needs the
     * guest in its peer list to invite it; the guest has to stay
     * discoverable to be invited. Android stops discovery on its own after
     * a couple of minutes, so it is re-issued while we are waiting.
     */
    private fun keepDiscovering(why: String) {
        if (discovering) return
        discovering = true
        DiagLog.i(tag, "keeping Wi-Fi Direct discovery alive: " + why)
        val r = object : Runnable {
            override fun run() {
                if (!discovering) return
                discover()
                main.postDelayed(this, 30_000)
            }
        }
        main.post(r)
    }

    private fun stopDiscovering() { discovering = false }

    /**
     * v0.9.9, the fix the phone run pointed at: the phone that OWNS the group
     * invites the guest. A phone that already owns a group cannot join
     * another one, which is why the buyer's own connect() was accepted and
     * then went nowhere.
     */
    fun invite(address: String, name: String): String? {
        val m = manager; val c = channel
        if (m == null || c == null) return "no p2p channel"
        if (life.role != P2pPlan.Role.GROUP_OWNER) DiagLog.w(tag, "inviting although this phone is not the group owner (role " + life.role + ")")
        val cfg = WifiP2pConfig().apply {
            deviceAddress = address
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 15      // we own the group and keep it
        }
        lastInvite = "inviting " + name + " (" + address + ")"
        DiagLog.i(tag, "INVITE: " + lastInvite)
        changed()
        return try {
            m.connect(c, cfg, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { lastInvite = "invitation to " + name + " accepted by Android, waiting for it to join"; DiagLog.i(tag, lastInvite); changed() }
                override fun onFailure(reason: Int) { lastInvite = "invitation to " + name + " REFUSED: " + reasonName(reason); DiagLog.e(tag, lastInvite); changed() }
            })
            null
        } catch (e: SecurityException) { lastInvite = "permission: " + e.message; DiagLog.e(tag, lastInvite); lastInvite }
    }

    /** Find a discovered peer by the name it sent us over BLE. */
    fun findPeer(name: String): Peer? {
        val addr = P2pPlan.matchPeer(peers.map { P2pPlan.PeerRef(it.name, it.address) }, name) ?: return null
        return peers.firstOrNull { it.address == addr }
    }

    // ---- sockets --------------------------------------------------------------------------------------

    private fun listen() {
        if (server != null) return
        try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(P2pPlan.PORT))
            server = ss
            life.onSocket("listening on :" + P2pPlan.PORT)
            DiagLog.i(tag, "group owner " + life.socketInfo)
            io.execute {
                while (!ss.isClosed) {
                    val s = try { ss.accept() } catch (e: Exception) { break }
                    val info = "accepted " + s.inetAddress?.hostAddress + ":" + s.port + " on " + s.localAddress?.hostAddress
                    DiagLog.i(tag, "TCP " + info)
                    main.post { life.onSocket(info); hooks.onSocket(s, true); changed() }
                }
                DiagLog.i(tag, "group owner accept loop ended")
            }
            changed()
        } catch (e: Exception) { fail("server socket: " + LinkIo.describe(e)) }
    }

    private fun dial(host: String) {
        io.execute {
            var last = ""
            for (attempt in 1..6) {
                if (life.cleaning) return@execute
                try {
                    val s = Socket()
                    s.connect(InetSocketAddress(host, P2pPlan.PORT), 5000)
                    val info = "connected " + s.localAddress?.hostAddress + " -> " + host + ":" + P2pPlan.PORT
                    DiagLog.i(tag, "TCP " + info)
                    main.post { life.onSocket(info); hooks.onSocket(s, false); changed() }
                    return@execute
                } catch (e: Exception) {
                    last = LinkIo.describe(e)
                    DiagLog.w(tag, "TCP attempt " + attempt + "/6 to " + host + " failed: " + last)
                    try { Thread.sleep(1500) } catch (_: InterruptedException) { return@execute }
                }
            }
            main.post { fail("could not reach the group owner: " + last) }
        }
    }

    // ---- diagnostics ------------------------------------------------------------------------------------

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
        val v = life.view()
        val sb = StringBuilder()
        sb.append("--- Wi-Fi Direct (method B, experimental) ---\n")
        sb.append("isP2pSupported (feature): ").append(supported).append("\n")
        sb.append("Wi-Fi Direct enabled: ").append(p2pEnabled).append("\n")
        sb.append("state: ").append(v.describe()).append("\n")
        sb.append("clean (nothing left from a previous role): ").append(v.clean).append("\n")
        sb.append("this phone on Wi-Fi Direct: \"").append(myDeviceName.ifEmpty { "?" }).append("\"\n")
        sb.append("clients joined the group: ").append(clientCount).append("\n")
        sb.append("last invitation: ").append(lastInvite.ifEmpty { "none" }).append("\n")
        sb.append("last join attempt: ").append(lastJoin.ifEmpty { "none" }).append("\n")
        sb.append("peers I can really address: ").append(if (realPeers().isEmpty()) "none" else realPeers().joinToString("; ") { it.name + " " + it.address })
            .append(" (anonymised: ").append(peers.count { P2pPlan.anonymous(it.address) }).append(")\n")
        sb.append("interfaces now: ").append(interfaces()).append("\n")
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
        WifiP2pManager.BUSY -> "BUSY (framework busy)"
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
