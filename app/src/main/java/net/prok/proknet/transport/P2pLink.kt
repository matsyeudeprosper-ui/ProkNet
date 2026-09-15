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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.LinkIo
import net.prok.proknet.core.P2pAdmission
import net.prok.proknet.core.P2pDataPlane
import net.prok.proknet.core.P2pEndpoint
import net.prok.proknet.core.P2pPlan
import net.prok.proknet.core.ShareCheck

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
        /** v0.9.19: the frequency of this phone's own Wi-Fi, 0 when it is on none. */
        fun staFrequency(): Int = 0
        /** v0.9.14: the data plane changed (a group, a membership, an endpoint). */
        fun onDataPlane(plane: P2pDataPlane.Plane) {}
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

    // ---- v0.9.14: the data plane a socket belongs to ------------------------------------------------

    private val binding = P2pSocketBinding(context)
    /** v0.9.16: the Wi-Fi radio must not sleep while a Wi-Fi Direct link is live. */
    private val radio = RadioLock(context)

    /** Group generation, membership generation and the endpoint: what a socket belongs to. */
    @Volatile var plane: P2pDataPlane.Plane = P2pDataPlane.NONE
        private set
    /** The server socket that exists, and the exact plane generation it was built for. */
    @Volatile var listener: P2pDataPlane.Listener? = null
        private set
    /** Only the loop holding the current token may hand a socket up. */
    @Volatile private var acceptToken = 0
    /** The interface name Android gave the group, so we bind to THAT p2p interface. */
    @Volatile private var groupIface: String = ""
    /** The operating frequency of the group, when Android tells us (API 29+). */
    @Volatile var groupFrequency: Int = 0
        private set
    /** The group owner address, for a client that has to dial it. */
    @Volatile private var goAddress: String = ""
    private var netHandle: P2pSocketBinding.Handle? = null
    @Volatile private var probe: DatagramSocket? = null
    @Volatile private var probeEchoed = 0
    /** What the last link probe proved. The one thing six timed out SYNs cannot say. */
    @Volatile var linkProof: P2pPlan.LinkProof = P2pPlan.LinkProof.NOT_RUN
        private set
    private var watching = false
    private var watchTicks = 0
    private var dialSeq = 0
    // ---- v0.9.22: one accepted association at a time, with its own clock ---------------------------

    @Volatile private var associationOwner = P2pAdmission.Owner.NOBODY
    @Volatile private var associationAt = 0L
    @Volatile private var associationFailed = false

    /**
     * An accepted `connect()` or `invite()` is in flight on this phone. While
     * this is true the radio belongs to that attempt: no peer discovery, and
     * a transient `groupFormed = false` from Android changes nothing.
     */
    val associationPending: Boolean
        get() = P2pAdmission.associationPending(associationOwner, associationAt, System.currentTimeMillis(), plane.hasMember, associationFailed)

    /** When the association in flight was accepted, for the log and the node's clock. */
    val associationStartedAt: Long get() = associationAt

    private fun beginAssociation(owner: P2pAdmission.Owner, why: String) {
        associationOwner = owner
        associationAt = System.currentTimeMillis()
        associationFailed = false
        stopDiscovery("an association is in flight (" + why + ")")
        DiagLog.i(tag, "ASSOCIATION started, owner " + owner + ", clock starts NOW: " + why +
            " (up to " + (P2pAdmission.ASSOCIATION_TIMEOUT_MS / 1000) + "s, a person may have to tap Connect)")
        changed()
    }

    private fun endAssociation(why: String, failed: Boolean) {
        if (associationOwner == P2pAdmission.Owner.NOBODY && associationAt == 0L) return
        associationFailed = failed
        associationOwner = P2pAdmission.Owner.NOBODY
        associationAt = 0L
        DiagLog.i(tag, "ASSOCIATION ended (" + why + ")")
        changed()
    }

    /** v0.9.19: a named group needs a passphrase; it is per run and never leaves this phone except to its own customer. */
    private val passphrase: String = "prok" + java.util.UUID.randomUUID().toString().replace("-", "").take(12)
    /** One dial ladder per target per data plane generation. */
    private val dialled = HashSet<String>()
    private val ENDPOINT_WATCH_TICKS = 6
    private val ENDPOINT_WATCH_MS = 2_000L

    /** What the data plane check says about the listener right now. */
    fun listenerVerdict(): P2pDataPlane.Verdict = P2pDataPlane.validate(plane, listener)

    /** This phone's own P2P address, "" until Android has given the interface one. */
    val localAddress: String get() = plane.localAddress

    /** The address of the group owner, for a client. */
    val groupOwnerAddress: String get() = goAddress

    /** This phone is the provider on this link: the ProkNet host, whichever side dialled. */
    private val selling: Boolean get() = life.want == P2pPlan.Want.SELL

    // ---- what the lab screen reads: one source of truth ------------------------------------------

    val phase: String get() = P2pPlan.stageName(life.stage)
    val stage: P2pPlan.Stage get() = life.stage
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
        val had = server != null || listener != null
        dropPlane("cleanup")
        peers = emptyList()
        return if (had) "listener closed and the data plane forgotten" else "no listener was open"
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
    /**
     * v0.9.19: ask for a 2.4 GHz group when this phone's own Wi-Fi is on
     * 5 GHz.
     *
     * The measurement says the data path is one way: everything the customer
     * sends arrives, and nothing the provider sends comes back, unicast and
     * broadcast alike, with the Wi-Fi radio lock held. The provider's group
     * follows its home Wi-Fi onto 5 GHz channel 48, so one radio is serving
     * both on one channel. A group on the other band makes the phone use
     * real dual band concurrency instead, which is a different path through
     * the driver.
     *
     * It is a request, not an assumption: if Android refuses the band, the
     * plain group is created instead and the log says which one happened.
     * `GROUP CHANNEL:` then reports what was actually granted.
     */
    private fun createGroup(attempt: Int = 1, withBand: Boolean = true) {
        val m = manager; val c = channel
        if (m == null || c == null) { fail("no p2p channel"); return }
        val sta = try { hooks.staFrequency() } catch (e: Exception) { 0 }
        val band = P2pPlan.groupBand(sta)
        val useBand = withBand && band == 2 && Build.VERSION.SDK_INT >= 29
        DiagLog.i(tag, "creating a fresh Wi-Fi Direct group (attempt " + attempt + "/" + CREATE_ATTEMPTS + "): " +
            (if (useBand) P2pPlan.groupBandText(band, sta) else P2pPlan.groupBandText(0, sta)))
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() { DiagLog.i(tag, "createGroup accepted (" + (if (useBand) "2.4 GHz requested" else "default band") + "), waiting for the group to form"); refreshAll() }
            override fun onFailure(reason: Int) {
                if (useBand) {
                    DiagLog.w(tag, "the 2.4 GHz group was refused (" + reasonName(reason) + "): creating a default group instead")
                    main.post { if (life.stage == P2pPlan.Stage.CREATING_GROUP) createGroup(attempt, withBand = false) }
                } else if (attempt < CREATE_ATTEMPTS) {
                    DiagLog.w(tag, "createGroup refused (" + reasonName(reason) + "), retrying in 3s")
                    main.postDelayed({ if (life.stage == P2pPlan.Stage.CREATING_GROUP) createGroup(attempt + 1) }, 3_000)
                } else fail("createGroup failed after " + attempt + " attempts: " + reasonName(reason))
            }
        }
        try {
            if (useBand) {
                val cfg = WifiP2pConfig.Builder()
                    .setNetworkName(P2pPlan.GROUP_NAME)
                    .setPassphrase(passphrase)
                    .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                    .build()
                m.createGroup(c, cfg, listener)
            } else m.createGroup(c, listener)
        } catch (e: SecurityException) { fail("permission: " + e.message) }
        catch (e: Exception) {
            DiagLog.w(tag, "the band request could not be built (" + e + "): creating a default group instead")
            if (useBand) main.post { if (life.stage == P2pPlan.Stage.CREATING_GROUP) createGroup(attempt, withBand = false) }
            else fail("createGroup: " + LinkIo.describe(e))
        }
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
                    DiagLog.i(tag, lastJoin)
                    beginAssociation(P2pAdmission.Owner.BUYER, "this phone is joining the provider group")
                    onResult?.invoke(true, "accepted")
                }
                override fun onFailure(reason: Int) {
                    lastJoin = "connect refused for " + address + ": " + reasonName(reason)
                    DiagLog.w(tag, lastJoin)
                    endAssociation("Android refused the join: " + reasonName(reason), failed = true)
                    onResult?.invoke(false, reasonName(reason))
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
        if (iface != null && iface != groupIface) groupIface = iface
        // v0.9.16: WHICH channel the group runs on, next to the channel this phone's home Wi-Fi uses
        val freq = if (Build.VERSION.SDK_INT >= 29) (try { g.frequency } catch (e: Exception) { 0 }) else 0
        if (freq > 0 && freq != groupFrequency) {
            groupFrequency = freq
            DiagLog.i(tag, "GROUP CHANNEL: " + ShareCheck.describe(freq) + " | this phone's Wi-Fi: " + hooks.staDescription().ifEmpty { "none" })
        }
        val before = clientCount
        clientCount = g.clientList?.size ?: 0
        val info = "ssid=" + g.networkName + " owner=" + (g.owner?.deviceName ?: "?") + " clients=" + clientCount + " iface=" + (iface ?: "?")
        life.onGroup(true, g.isGroupOwner, info)
        ifaceInfo = interfaces()
        DiagLog.i(tag, "group: " + info + " | interfaces: " + ifaceInfo)
        if (before != clientCount) onClientCountChanged(before, clientCount)
        // v0.9.19: the customer left, so this phone has to be findable again. Without this the seller
        // stopped discovering when its first customer joined and was never seen again: the phone run
        // showed the buyer reporting "0 seen, none addressable" for forty minutes afterwards.
        if (!plane.hasMember && life.want != P2pPlan.Want.NONE) keepDiscovering("no customer on this link: this phone must be findable")
        changed()
    }

    /**
     * v0.9.13: the moment that used to be lost. The owner created its
     * listener when the group formed, and the buyer joined a minute and a
     * half later. Nothing re-checked that the listener still belonged to the
     * endpoint the buyer is dialling. So on 0 -> 1 the listener is
     * VALIDATED, and replaced only if the check says it is not ours: this is
     * endpoint validation, not "restart because maybe".
     */
    private fun onClientCountChanged(before: Int, now: Int) {
        DiagLog.i(tag, "CLIENT COUNT " + before + " -> " + now)
        if (life.role != P2pPlan.Role.GROUP_OWNER) return
        observePlane("the client count changed")
        if (before == 0 && now >= 1) armTransport("a client joined the group")
        if (before > 0 && now == 0) keepDiscovering("the customer left the group")
    }

    private fun onConnectionInfo(info: WifiP2pInfo?) {
        staAfter = hooks.staDescription()
        if (life.cleaning) { DiagLog.i(tag, "connection info ignored during cleanup (formed=" + (info?.groupFormed == true) + ")"); return }
        val formed = info?.groupFormed == true
        val go = info?.groupOwnerAddress?.hostAddress
        life.onGroup(formed, info?.isGroupOwner == true, life.groupInfo.ifEmpty { "groupOwner=" + go })
        DiagLog.i(tag, "connection: formed=" + formed + " role=" + life.role + " groupOwner=" + go +
            " | my Wi-Fi network before=" + (staBefore.ifEmpty { "none" }) + " now=" + (staAfter.ifEmpty { "none" }))
        if (!formed) {
            // v0.9.22: Android reports groupFormed=false in the middle of its own join choreography.
            // While an accepted association is in flight that is not the end of anything.
            if (associationPending) {
                DiagLog.i(tag, "connection says formed=false while an association is in flight: holding the radio for it")
                changed(); return
            }
            radio.release("the group is gone")
            if (life.want != P2pPlan.Want.NONE) keepDiscovering("the group is gone, this phone can look for peers again")
            changed(); return
        }
        // v0.9.16: a sleeping radio is where the owner downlink was being lost
        radio.acquire("a Wi-Fi Direct group exists on this phone")
        if (staBefore.isNotEmpty() && staAfter != staBefore) DiagLog.e(tag, "the Wi-Fi Direct group KILLED this phone's Wi-Fi connection (" + staBefore + " -> " + (staAfter.ifEmpty { "none" }) + ")")
        goAddress = go ?: goAddress
        observePlane("connection info")
        when (life.role) {
            P2pPlan.Role.GROUP_OWNER -> keepDiscovering("group owner waiting for a guest")
            P2pPlan.Role.CLIENT -> {
                val target = P2pPlan.socketTarget(life.role, go)
                if (target == null) { fail("no group owner address"); return }
                // a client IS a member the moment the group forms: arm its own listener, then let the
                // node dial when the provider says its transport is ready (or dial by ourselves after
                // a bounded wait, which also covers the developer lab where there is no BLE channel)
                armTransport("this phone joined a group")
                main.postDelayed({ selfDial("the provider did not announce its transport") }, P2pDataPlane.READY_WAIT_MS)
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
        // v0.9.22: an accepted connect() or invite() owns the radio until it forms membership, is
        // refused, or runs out of time. Android's own mid-join `formed=false` is none of those.
        if (associationPending) {
            DiagLog.i(tag, "not starting discovery: an association is in flight (" + why + ")")
            return
        }
        if (!P2pPlan.discoveryWanted(life.want, plane.hasMember)) {
            DiagLog.i(tag, "not starting discovery: " + (if (plane.hasMember) "this link already has a peer on it" else "nothing to admit") + " (" + why + ")")
            return
        }
        discovering = true
        DiagLog.i(tag, "keeping Wi-Fi Direct discovery alive: " + why)
        val r = object : Runnable {
            override fun run() {
                if (!discovering) return
                // v0.9.15: the radio must stay on the group channel once a group exists
                if (!P2pPlan.discoveryWanted(life.want, plane.hasMember)) { stopDiscovery("somebody has joined: the radio belongs to the data plane now"); return }
                discover()
                main.postDelayed(this, 30_000)
            }
        }
        main.post(r)
    }

    /**
     * v0.9.15: stop the framework find, not just our own loop.
     *
     * Peer discovery takes a single-radio phone OFF the group channel, and
     * Android keeps a find running for about two minutes. In the v0.9.14 run
     * both phones were in the group, both listeners were armed, both dialled
     * with a correctly bound socket, every SYN in both directions timed out,
     * and both logged `discoverPeers accepted` every thirty seconds
     * throughout. Discovery belongs to admission; the data phase has none.
     */
    private fun stopDiscovery(why: String) {
        val wasOn = discovering
        discovering = false
        val m = manager; val c = channel
        if (m == null || c == null) return
        if (!wasOn) return
        DiagLog.i(tag, "DISCOVERY off: " + why)
        try {
            m.stopPeerDiscovery(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { DiagLog.i(tag, "discovery stopped, the radio can stay on the group channel") }
                override fun onFailure(reason: Int) { DiagLog.w(tag, "stopPeerDiscovery refused: " + reasonName(reason)) }
            })
        } catch (e: SecurityException) { DiagLog.w(tag, "stopPeerDiscovery: " + e.message) }
    }

    private fun stopDiscovering() { discovering = false }

    /**
     * v0.9.20: admission is symmetric, so discovery has to come back on its
     * own whenever an attempt did not produce a group. It is idempotent: it
     * does nothing while discovery is already running, and refuses while this
     * link has a peer on it.
     */
    fun resumeDiscovery(why: String) { keepDiscovering(why) }

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
                override fun onSuccess() {
                    lastInvite = "invitation to " + name + " accepted by Android, waiting for it to join"
                    DiagLog.i(tag, lastInvite)
                    beginAssociation(P2pAdmission.Owner.SELLER, "this phone invited " + name)
                }
                override fun onFailure(reason: Int) {
                    lastInvite = "invitation to " + name + " REFUSED: " + reasonName(reason)
                    DiagLog.e(tag, lastInvite)
                    endAssociation("Android refused the invitation: " + reasonName(reason), failed = true)
                }
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

    /**
     * Read the endpoint from Android and advance the data plane. A new group
     * generation for a real endpoint change, a new MEMBERSHIP generation the
     * moment this phone gains a live peer, and the same plane when nothing
     * moved, so nothing is rebuilt for no reason.
     */
    private fun observePlane(why: String) {
        if (life.cleaning) return
        val h = if (life.groupFormed) binding.resolve(groupIface.ifEmpty { null }) else null
        val o = binding.observe(life.role, h)
        val before = plane
        val now = P2pDataPlane.advance(before, o, clientCount, life.groupFormed)
        // v0.9.22: a post-condition, not a branch. On a first join the group generation AND the
        // membership generation both change in the same observation, and in v0.9.21 the group branch
        // won and the membership branch, which held this line, was skipped. Discovery then ran for
        // sixteen seconds INTO the data-path window.
        if (now.hasMember) {
            stopDiscovery("somebody is on this link: the radio stays on the group channel")
            endAssociation("membership formed", failed = false)
        }
        if (now == before) { if (netHandle == null) netHandle = h; return }
        plane = now
        netHandle = h
        when {
            !life.groupFormed -> { DiagLog.i(tag, "DATA PLANE torn down (" + why + ")"); closeListener("the group is gone"); dialled.clear() }
            now.groupGeneration != before.groupGeneration -> {
                DiagLog.i(tag, "DATA PLANE generation " + now.generationText() + " created (" + P2pDataPlane.changeReason(before, o) + ", seen on " + why + ")")
                DiagLog.i(tag, "   " + now.describe())
                closeListener("the endpoint changed")
                dialled.clear()
            }
            now.membershipGeneration != before.membershipGeneration -> {
                DiagLog.i(tag, "DATA PLANE generation " + now.generationText() + " created (client membership established, seen on " + why + ")")
                DiagLog.i(tag, "   " + now.describe())
                val old = listener
                if (old != null) DiagLog.i(tag, "   old listener: group generation " + old.plane.groupGeneration +
                    ", membership generation " + old.plane.membershipGeneration + " -> stale for live client membership")
            }
            else -> {
                DiagLog.i(tag, "DATA PLANE " + now.generationText() + ": clients " + before.clientCount + " -> " + now.clientCount + " (" + why + ")")
                if (before.hasMember && !now.hasMember) keepDiscovering("the link has no peer on it any more")
            }
        }
        hooks.onDataPlane(now)
        changed()
    }

    private fun dropPlane(why: String) {
        endAssociation(why, failed = true)
        radio.release(why)
        closeListener(why)
        if (plane != P2pDataPlane.NONE) DiagLog.i(tag, "DATA PLANE dropped: " + why)
        plane = plane.copy(role = P2pPlan.Role.NONE, interfaceName = "", localAddress = "", networkIdentity = "", clientCount = 0)
        netHandle = null
        groupIface = ""
        goAddress = ""
        dialled.clear()
        dialSeq++
        watching = false
    }

    // ---- the listener ---------------------------------------------------------------------------------

    /**
     * Make sure a listener exists **for this live membership**. The v0.9.13
     * phone run is the whole reason this is not "if (server != null)": that
     * listener existed, matched on every field, and accepted nothing,
     * because it was built while the group was still empty.
     */
    fun armTransport(why: String) {
        observePlane("arming the transport (" + why + ")")
        val p = plane
        if (!p.endpointReady) { watchEndpoint("the P2P endpoint is not readable yet (" + why + ")"); return }
        if (!p.hasMember) { DiagLog.i(tag, "not arming a listener yet: " + P2pDataPlane.verdictText(P2pDataPlane.Verdict.NO_MEMBER) + " (" + why + ")"); return }
        val v = listenerVerdict()
        if (P2pDataPlane.ok(v)) { DiagLog.i(tag, "listener check says " + P2pDataPlane.verdictText(v) + " (" + why + ")"); return }
        DiagLog.i(tag, "LISTENER rebuilding for live membership: " + P2pDataPlane.verdictText(v) + " (" + why + ")")
        openListener(p, why)
    }

    private fun openListener(p: P2pDataPlane.Plane, why: String) {
        closeListener("replacing it for generation " + p.generationText())
        val token = ++acceptToken
        val h = netHandle
        val handle = if (h != null && h.localAddress == p.localAddress) h else P2pSocketBinding.Handle(null, p.networkIdentity, p.interfaceName, p.localAddress)
        DiagLog.i(tag, P2pEndpoint.listenLine("creating", p.localAddress, P2pPlan.PORT, p, p.binding) + " | because " + why)
        try {
            val ss = binding.listenOn(handle, P2pPlan.PORT)
            server = ss
            val actual = ss.inetAddress?.hostAddress ?: ""
            listener = P2pDataPlane.Listener(p, actual, ss.localPort, true, p.binding)
            DiagLog.i(tag, P2pEndpoint.listenLine("actual", actual, ss.localPort, p, p.binding) +
                " | on the P2P local address=" + (actual == p.localAddress))
            if (actual != p.localAddress) DiagLog.e(tag, "the listener did NOT bind to the P2P local address, it is on " + actual)
            life.onSocket("listening on " + actual + ":" + ss.localPort + " (generation " + p.generationText() + ")")
            acceptLoop(ss, p, token)
            openProbe(p, handle)
            watching = false
            changed()
        } catch (ex: Exception) {
            server = null
            listener = null
            lastError = "listener on " + p.localAddress + ":" + P2pPlan.PORT + ": " + LinkIo.describe(ex)
            DiagLog.e(tag, "LISTENER could not bind: " + lastError)
            watchEndpoint("the listener could not bind to " + p.localAddress)
        }
    }

    // ---- the link probe: measure the link instead of guessing ------------------------------------------

    /**
     * A UDP echo on the P2P endpoint. It answers the one question six timed
     * out SYNs cannot: does ANY IP packet cross this Wi-Fi Direct link?
     */
    private fun openProbe(p: P2pDataPlane.Plane, handle: P2pSocketBinding.Handle) {
        closeProbe()
        try {
            val ds = binding.datagramOn(handle, P2pPlan.PROBE_PORT)
            try { ds.broadcast = true } catch (_: Exception) {}
            probe = ds
            probeEchoed = 0
            val bcast = P2pPlan.broadcastOf(p.localAddress)
            DiagLog.i(tag, "LINK PROBE listening on " + p.localAddress + ":" + P2pPlan.PROBE_PORT + ", broadcast " + bcast)
            io.execute {
                val buf = ByteArray(64)
                while (!ds.isClosed) {
                    val pk = DatagramPacket(buf, buf.size)
                    try { ds.receive(pk) } catch (e: Exception) { break }
                    val body = String(pk.data, 0, pk.length, Charsets.US_ASCII)
                    if (body.startsWith("prok-r")) continue            // an answer, not a probe
                    probeEchoed++
                    DiagLog.i(tag, "LINK PROBE: a packet DID cross, " + body + " from " + (pk.address?.hostAddress ?: "?") + ", answering it twice")
                    // answer BOTH ways, so the other side can tell unicast from broadcast
                    val u = ("prok-r" + P2pPlan.PROBE_UNICAST + body).toByteArray(Charsets.US_ASCII)
                    val b = ("prok-r" + P2pPlan.PROBE_BROADCAST + body).toByteArray(Charsets.US_ASCII)
                    try { ds.send(DatagramPacket(u, u.size, pk.address, pk.port)) }
                    catch (e: Exception) { DiagLog.w(tag, "LINK PROBE unicast answer failed: " + e) }
                    if (bcast.isNotEmpty()) {
                        try { ds.send(DatagramPacket(b, b.size, InetAddress.getByName(bcast), pk.port)) }
                        catch (e: Exception) { DiagLog.w(tag, "LINK PROBE broadcast answer failed: " + e) }
                    }
                }
                DiagLog.i(tag, "LINK PROBE responder ended")
            }
        } catch (e: Exception) { DiagLog.w(tag, "LINK PROBE could not listen: " + LinkIo.describe(e)) }
    }

    private fun closeProbe() {
        try { probe?.close() } catch (_: Exception) {}
        probe = null
    }

    /**
     * Send a few probes to [host], unicast and broadcast, and say plainly
     * what came back. Diagnostic only, never a gate.
     *
     * v0.9.16 separates the two because the v0.9.15 run proved the link is
     * ONE WAY: everything the client sent reached the owner, and nothing the
     * owner sent reached the client. If broadcast crosses where unicast does
     * not, the two phones cannot address each other directly, which is a
     * different fault from a radio that drops everything.
     */
    private fun probeLink(host: String) {
        io.execute {
            val h = binding.resolve(groupIface.ifEmpty { null })
            if (h == null) { DiagLog.w(tag, "LINK PROBE: no P2P endpoint to probe from"); return@execute }
            val bcast = P2pPlan.broadcastOf(h.localAddress)
            var sent = 0
            var uni = 0
            var bro = 0
            var best = -1L
            val ds = try {
                val d = DatagramSocket(null)
                d.reuseAddress = true
                h.network?.let { try { it.bindSocket(d) } catch (_: Exception) {} }
                d.bind(InetSocketAddress(InetAddress.getByName(h.localAddress), 0))
                d.broadcast = true
                d.soTimeout = P2pPlan.PROBE_TIMEOUT_MS
                d
            } catch (e: Exception) { DiagLog.w(tag, "LINK PROBE could not open a socket: " + LinkIo.describe(e)); return@execute }
            for (i in 1..P2pPlan.PROBE_COUNT) {
                if (life.cleaning) break
                val tag1 = P2pPlan.PROBE_UNICAST + i
                val tag2 = P2pPlan.PROBE_BROADCAST + i
                val t0 = System.currentTimeMillis()
                try {
                    ds.send(DatagramPacket(tag1.toByteArray(Charsets.US_ASCII), tag1.length, InetAddress.getByName(host), P2pPlan.PROBE_PORT))
                    sent++
                } catch (e: Exception) { DiagLog.w(tag, "LINK PROBE unicast send failed: " + LinkIo.describe(e)) }
                if (bcast.isNotEmpty()) {
                    try {
                        ds.send(DatagramPacket(tag2.toByteArray(Charsets.US_ASCII), tag2.length, InetAddress.getByName(bcast), P2pPlan.PROBE_PORT))
                        sent++
                    } catch (e: Exception) { DiagLog.w(tag, "LINK PROBE broadcast send failed: " + LinkIo.describe(e)) }
                }
                var answered = false
                while (true) {
                    try {
                        val back = DatagramPacket(ByteArray(64), 64)
                        ds.receive(back)
                        val body = String(back.data, 0, back.length, Charsets.US_ASCII)
                        if (!body.startsWith("prok-r")) continue          // our own probe coming back on broadcast
                        val ms = System.currentTimeMillis() - t0
                        if (best < 0 || ms < best) best = ms
                        if (body.startsWith("prok-r" + P2pPlan.PROBE_UNICAST)) uni++ else bro++
                        answered = true
                        DiagLog.i(tag, "LINK PROBE " + i + "/" + P2pPlan.PROBE_COUNT + " to " + host + ": REPLY " + body + " in " + ms + " ms")
                        break
                    } catch (e: Exception) { break }
                }
                if (answered && uni > 0) break
                try { Thread.sleep(P2pPlan.PROBE_GAP_MS) } catch (_: InterruptedException) { break }
            }
            try { ds.close() } catch (_: Exception) {}
            val proof = P2pPlan.linkProof(sent, uni, bro, probeEchoed)
            linkProof = proof
            DiagLog.i(tag, "LINK PROBE verdict: " + P2pPlan.linkProofText(proof) +
                " (sent " + sent + ", unicast replies " + uni + ", broadcast replies " + bro +
                ", probes answered by us " + probeEchoed + (if (best >= 0) ", best " + best + " ms" else "") + ")")
            main.post { changed() }
        }
    }

    private fun closeListener(why: String) {
        val had = server != null || listener != null
        acceptToken++                   // anything still accepting is stale from this instant
        closeProbe()
        try { server?.close() } catch (_: Exception) {}
        server = null
        listener = null
        if (had) DiagLog.i(tag, "LISTENER closed: " + why)
    }

    /**
     * The accept loop carries the plane it was born with. A loop from an
     * older group OR from the empty-group phase that comes back with a
     * connection has it refused and closed.
     */
    private fun acceptLoop(ss: ServerSocket, born: P2pDataPlane.Plane, token: Int) {
        DiagLog.i(tag, "LISTENER accept loop started for generation " + born.generationText() + " (token " + token + ") on " +
            (ss.inetAddress?.hostAddress ?: "?") + ":" + ss.localPort)
        io.execute {
            while (!ss.isClosed) {
                val s = try { ss.accept() } catch (e: Exception) { break }
                val from = (s.inetAddress?.hostAddress ?: "?") + ":" + s.port
                if (token != acceptToken) {
                    DiagLog.w(tag, "a STALE accept loop (generation " + born.generationText() + ", token " + token + " of " + acceptToken + ") refused a connection from " + from)
                    try { s.close() } catch (_: Exception) {}
                    continue
                }
                DiagLog.i(tag, "TCP accepted " + from + " on " + (s.localAddress?.hostAddress ?: "?") +
                    ", membership generation " + born.membershipGeneration)
                main.post {
                    if (token != acceptToken || !P2pDataPlane.acceptAllowed(born, plane)) {
                        DiagLog.w(tag, "dropping a socket from generation " + born.generationText() + ": the data plane is now " + plane.generationText())
                        try { s.close() } catch (_: Exception) {}
                    } else {
                        life.onSocket("accepted " + from + " (generation " + born.generationText() + ")")
                        hooks.onSocket(s, selling)
                        changed()
                    }
                }
            }
            DiagLog.i(tag, "LISTENER accept loop for generation " + born.generationText() + " ended")
            main.post {
                if (token == acceptToken) {
                    listener = listener?.copy(accepting = false)
                    DiagLog.w(tag, "the listener of the current generation stopped accepting")
                    armTransport("the accept loop of the current generation ended")
                }
            }
        }
    }

    /**
     * The address of a fresh p2p interface can appear a moment after the
     * group does. This watches the endpoint until it is readable, a bounded
     * number of times, and stops the moment the listener is valid. It opens
     * no socket by itself and retries nothing blindly.
     */
    private fun watchEndpoint(why: String) {
        if (watching) return
        watching = true
        watchTicks = 0
        DiagLog.i(tag, "watching for the P2P endpoint: " + why)
        val r = object : Runnable {
            override fun run() {
                if (!watching) return
                if (life.cleaning || !life.groupFormed) { watching = false; return }
                watchTicks++
                observePlane("endpoint watch " + watchTicks + "/" + ENDPOINT_WATCH_TICKS)
                val p = plane
                if (p.endpointReady && p.hasMember && !P2pDataPlane.ok(listenerVerdict())) {
                    watching = false
                    openListener(p, "the endpoint became readable")
                    return
                }
                if (P2pDataPlane.ok(listenerVerdict()) || (p.endpointReady && !p.hasMember)) { watching = false; return }
                if (watchTicks >= ENDPOINT_WATCH_TICKS) {
                    watching = false
                    DiagLog.e(tag, "the P2P endpoint never became readable: " + plane.describe() + " | interfaces: " + interfaces())
                    return
                }
                main.postDelayed(this, ENDPOINT_WATCH_MS)
            }
        }
        main.postDelayed(r, ENDPOINT_WATCH_MS)
    }

    // ---- dialling -------------------------------------------------------------------------------------

    /**
     * Open the local transport socket to [address]:[port].
     *
     * **Both phones may dial**, and that is deliberate. Android can bind an
     * OUTGOING socket to the Wi-Fi Direct network and offers no way at all to
     * bind a LISTENING one, so the provider, which is the phone that also
     * holds a home Wi-Fi network, must not depend on being dialled. One
     * ladder per target per data plane generation; the first authenticated
     * socket wins and the state machine closes the loser.
     */
    fun dialPeer(address: String, port: Int, why: String): String? {
        val p = plane
        if (!p.usable) {
            val e = "the data plane is not usable yet (" + P2pDataPlane.verdictText(P2pDataPlane.validate(p, listener)) + ")"
            DiagLog.w(tag, "not dialling " + address + ": " + e)
            return e
        }
        if (P2pPlan.anonymous(address) || address.isEmpty()) { DiagLog.w(tag, "not dialling a peer with no usable address"); return "no peer address" }
        val key = p.generationText() + "|" + address + ":" + port
        if (!dialled.add(key)) return null
        DiagLog.i(tag, "TRANSPORT dial to " + address + ":" + port + " for generation " + p.generationText() + " because " + why)
        probeLink(address)
        dial(address, port, p)
        return null
    }

    /** The bounded self-drive: a client that heard nothing dials the owner itself. */
    private fun selfDial(why: String) {
        if (life.cleaning || life.role != P2pPlan.Role.CLIENT) return
        if (linkAuthenticated) return
        val go = goAddress
        if (go.isEmpty()) { DiagLog.w(tag, "no group owner address to dial"); return }
        observePlane("self dial")
        val p = plane
        if (P2pDataPlane.dialStep(p, go, peerReady = false, waitedMs = P2pDataPlane.READY_WAIT_MS,
                alreadyDialled = dialled.contains(p.generationText() + "|" + go + ":" + P2pPlan.PORT)) == P2pDataPlane.DialRole.DIAL)
            dialPeer(go, P2pPlan.PORT, why)
    }

    private fun dial(host: String, port: Int, born: P2pDataPlane.Plane) {
        val seq = ++dialSeq
        io.execute {
            var last = ""
            for (attempt in 1..P2pPlan.DIAL_ATTEMPTS) {
                if (life.cleaning || seq != dialSeq) { DiagLog.i(tag, "dial abandoned: the lifecycle moved on"); return@execute }
                val nowPlane = plane
                if (born.groupGeneration != nowPlane.groupGeneration || born.membershipGeneration != nowPlane.membershipGeneration) {
                    DiagLog.i(tag, "dial abandoned: the data plane moved from " + born.generationText() + " to " + nowPlane.generationText())
                    return@execute
                }
                val h = binding.resolve(groupIface.ifEmpty { null })
                val s = Socket()
                val b = binding.bindOut(s, h)
                DiagLog.i(tag, P2pEndpoint.dialLine(attempt, P2pPlan.DIAL_ATTEMPTS, h?.localAddress ?: "", host, port,
                    h?.interfaceName ?: "", h?.identity ?: "", b))
                if (!P2pEndpoint.usable(b)) {
                    try { s.close() } catch (_: Exception) {}
                    DiagLog.e(tag, P2pEndpoint.NO_BINDING_ERROR)
                    main.post { if (!selling) fail(P2pEndpoint.NO_BINDING_ERROR) else lastError = P2pEndpoint.NO_BINDING_ERROR }
                    return@execute
                }
                try {
                    s.connect(InetSocketAddress(host, port), P2pPlan.DIAL_TIMEOUT_MS)
                    val info = "connected " + (s.localAddress?.hostAddress ?: "?") + " -> " + host + ":" + port +
                        " (binding " + P2pEndpoint.bindingText(b, h?.localAddress ?: "") + ", generation " + born.generationText() + ")"
                    DiagLog.i(tag, "TCP " + info)
                    main.post {
                        if (seq != dialSeq) { try { s.close() } catch (_: Exception) {} }
                        else { life.onSocket(info); hooks.onSocket(s, selling); changed() }
                    }
                    return@execute
                } catch (e: Exception) {
                    last = LinkIo.describe(e)
                    try { s.close() } catch (_: Exception) {}
                    DiagLog.w(tag, "TCP attempt " + attempt + "/" + P2pPlan.DIAL_ATTEMPTS + " to " + host + " failed: " + last)
                    try { Thread.sleep(P2pPlan.DIAL_GAP_MS) } catch (_: InterruptedException) { return@execute }
                }
            }
            val why = "could not reach " + host + ":" + port + " over Wi-Fi Direct: " + last
            main.post {
                if (seq != dialSeq) return@post
                DiagLog.e(tag, why)
                if (!selling) fail(why) else lastError = why
            }
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
        sb.append("data plane: ").append(plane.describe()).append("\n")
        sb.append("data plane usable: ").append(plane.usable).append("\n")
        sb.append("listener: ").append(listener?.describe() ?: "none").append("\n")
        sb.append("association in flight: ").append(if (associationPending) associationOwner.toString() +
            " for " + ((System.currentTimeMillis() - associationAt) / 1000) + "s" else "none").append("\n")
        sb.append("link probe: ").append(P2pPlan.linkProofText(linkProof)).append("\n")
        sb.append("group channel: ").append(if (groupFrequency > 0) ShareCheck.describe(groupFrequency) else "unknown")
            .append(" | this phone's Wi-Fi: ").append(hooks.staDescription().ifEmpty { "none" }).append("\n")
        sb.append("radio lock: ").append(radio.state).append("\n")
        sb.append("listener belongs to this live membership: ").append(P2pDataPlane.verdictText(listenerVerdict())).append("\n")
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
