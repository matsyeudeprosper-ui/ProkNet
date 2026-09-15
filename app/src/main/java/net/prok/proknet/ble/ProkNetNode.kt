package net.prok.proknet.ble

import android.content.Context
import android.os.Handler
import android.os.Looper
import net.prok.proknet.core.BleHealth
import net.prok.proknet.core.Crypto
import net.prok.proknet.core.DeliveryResult
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Dir
import net.prok.proknet.core.Identity
import net.prok.proknet.core.MessageStore
import net.prok.proknet.core.MsgStatus
import net.prok.proknet.core.Packet
import net.prok.proknet.core.Routing
import net.prok.proknet.core.Signed
import net.prok.proknet.core.StoredMessage
import net.prok.proknet.core.Transfer
import net.prok.proknet.core.Wire
import net.prok.proknet.core.hexToBytes
import net.prok.proknet.core.toHex
import net.prok.proknet.core.ShareCheck
import net.prok.proknet.node.Gateway
import net.prok.proknet.node.HotspotProbe
import net.prok.proknet.node.RelayNode
import net.prok.proknet.node.TransferEngine
import net.prok.proknet.node.TunnelClient
import net.prok.proknet.core.Relay
import net.prok.proknet.core.Tunnel
import net.prok.proknet.core.Market
import net.prok.proknet.transport.BleTransport
import net.prok.proknet.transport.Frame
import net.prok.proknet.transport.Transport
import net.prok.proknet.transport.TransportListener
import net.prok.proknet.transport.P2pLink
import net.prok.proknet.transport.WifiTransport
import net.prok.proknet.core.P2pPlan

/**
 * One ProkNet node (v0.5) = cryptographic identity + store + transports
 * (BLE, Wi-Fi) + delivery/carry queue + transfer engine. Created once per
 * process by ProkNetApp, started/stopped by ProkNetService.
 *
 * Every message leaves this phone end-to-end encrypted for its destination
 * and signed by this identity. Relays see routing metadata only.
 */
class ProkNetNode(private val context: Context) : TransportListener {
    private val tag = "NODE"
    private val main = Handler(Looper.getMainLooper())

    val identity: Identity = Identity.load(context)
    val store = MessageStore(context)

    interface Listener {
        fun onPeers(peers: List<Peer>)
        fun onMessagesChanged()
        fun onStatus(status: String)
    }

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()
    fun addListener(l: Listener) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }
    private val listener = object : Listener {
        override fun onPeers(peers: List<Peer>) { listeners.forEach { it.onPeers(peers) } }
        override fun onMessagesChanged() { listeners.forEach { it.onMessagesChanged() } }
        override fun onStatus(status: String) { listeners.forEach { it.onStatus(status) } }
    }

    private fun identityRecord(): ByteArray = Wire.identityRecord(identity.idBytes, identity.pubBytes, identity.displayName)

    val ble = BleTransport(context, identity, ::identityRecord)
    val wifi = WifiTransport(context, identity, object : WifiTransport.ControlChannel {
        override fun sendControl(peerShort: String, body: ByteArray, cb: (Boolean) -> Unit) = this@ProkNetNode.sendControl(peerShort, body, cb)
        override fun knownPeerPub(peerShort: String): ByteArray? = store.peerKey(peerShort)?.pub
        override fun appVisible(): Boolean = net.prok.proknet.ProkNetApp.appVisible()
        override fun linkInUse(): Boolean = gateway.session != null || tunnel.session != null || relay.session != null
    })
    /** v0.9: the relay phone's second Wi-Fi link, client-only, towards its seller. Never carries packets or transfers. */
    val wifiUp = WifiTransport(context, identity, object : WifiTransport.ControlChannel {
        override fun sendControl(peerShort: String, body: ByteArray, cb: (Boolean) -> Unit) = this@ProkNetNode.sendControl(peerShort, body, cb)
        override fun knownPeerPub(peerShort: String): ByteArray? = store.peerKey(peerShort)?.pub
        override fun appVisible(): Boolean = net.prok.proknet.ProkNetApp.appVisible()
        override fun linkInUse(): Boolean = gateway.session != null || tunnel.session != null || relay.session != null
    }, Routing.TRANSPORT_WIFI_UP, mayHost = false)
    private val transports: List<Transport> get() = listOf(wifi, ble)

    val queue = DeliveryQueue(store, identity, object : DeliveryQueue.Hooks {
        override fun reachablePeers(): List<Routing.PeerView> = this@ProkNetNode.reachablePeers()
        override fun transportFor(peerShort: String): Transport? = this@ProkNetNode.transportFor(peerShort)
        override fun onChanged() { main.post { listener.onMessagesChanged(); pushStatus() } }
    }, context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)

    val engine = TransferEngine(context, store, identity, object : TransferEngine.Hooks {
        override fun transportFor(peerShort: String): Transport? = this@ProkNetNode.transportFor(peerShort)
        override fun bleReachable(peerShort: String) = ble.canReach(peerShort)
        override fun wifiUp(peerShort: String) = wifi.canReach(peerShort)
        override fun wifiIdle() = wifi.state.isIdle
        override fun requestWifi(peerShort: String) = wifi.requestLink(peerShort)
        override fun peerPub(peerShort: String): ByteArray? = store.peerKey(peerShort)?.pub
        override fun onChanged() { main.post { listener.onMessagesChanged(); pushStatus() } }
    })

    // ---- v0.7 marketplace settings (SharedPreferences) ----
    private val marketPrefs = context.getSharedPreferences("proknet_market", Context.MODE_PRIVATE)
    var sellPrice: Int get() = marketPrefs.getInt("price", 5); set(v) { marketPrefs.edit().putInt("price", v).apply() }
    var sellMinPrice: Int get() = marketPrefs.getInt("min", 0); set(v) { marketPrefs.edit().putInt("min", v).apply() }
    var sellMaxMb: Int get() = marketPrefs.getInt("max_mb", 0); set(v) { marketPrefs.edit().putInt("max_mb", v).apply() }
    var feePct: Int get() = marketPrefs.getInt("fee_pct", Market.DEFAULT_FEE_PCT); set(v) { marketPrefs.edit().putInt("fee_pct", v).apply() }
    var relayOn: Boolean get() = marketPrefs.getBoolean("relay", false); set(v) { marketPrefs.edit().putBoolean("relay", v).apply() }
    val sellOn: Boolean get() = gateway.providing

    // ---- v0.6/0.7: Internet through another phone, as a market ----
    val gateway: Gateway = Gateway(context, identity, object : Gateway.Hooks {
        override fun send(type: Int, streamId: Int, data: ByteArray): Boolean = sendFromGateway(type, streamId, data)
        override fun peerFullId(peerShort: String): String? = store.peerKey(peerShort)?.fullId
        override fun peerPub(peerShort: String): ByteArray? = store.peerKey(peerShort)?.pub
        override fun store(): MessageStore = this@ProkNetNode.store
        override fun terms(): IntArray = intArrayOf(sellPrice, sellMinPrice, sellMaxMb, feePct)
        override fun onChanged() { main.post { refreshAdvert(); pushStatus(); recheckSharingIfNetworkChanged() } }
    })
    val tunnel: TunnelClient = TunnelClient(identity, object : TunnelClient.Hooks {
        override fun send(type: Int, streamId: Int, data: ByteArray): Boolean = sendFromTunnel(type, streamId, data)
        override fun linkPeer(): String? = buyerFarEnd()
        override fun linkPeerFullId(): String? = buyerFarEnd()?.let { store.peerKey(it)?.fullId }
        override fun peerPub(peerShort: String): ByteArray? = store.peerKey(peerShort)?.pub
        override fun store(): MessageStore = this@ProkNetNode.store
        override fun feePct(): Int = this@ProkNetNode.feePct
        override fun onSessionUp() { main.post { vpnRequested?.invoke() } }
        override fun onAttemptFailed(reason: String) {
            // v0.9.2: the attempt is over. Clear it, or the next SELL is refused with "stop buying first"
            // and the VPN keeps capturing this phone's traffic with no tunnel behind it.
            main.post {
                if (buyerWanted != null || net.prok.proknet.vpn.ProkVpnService.running) {
                    DiagLog.i(tag, "buy attempt ended (" + reason + "): clearing the buyer state" + (if (net.prok.proknet.vpn.ProkVpnService.running) " and stopping the VPN" else ""))
                    lastBuyError = reason
                    buyerWanted = null; buyViaRelay = false; introAttempts = 0; introRefused = false
                    net.prok.proknet.vpn.ProkVpnService.stop(context)
                }
                pushStatus()
            }
        }
        override fun onChanged() { main.post { pushStatus() } }
    })
    /** Price the buyer saw in the scan when it pressed BUY (locks the proposal). */
    /**
     * v0.9.7 EXPERIMENT (developer screens only): a local link over Wi-Fi Direct, so a seller can
     * serve a customer while it stays connected to its home router. The socket it produces is adopted
     * by the normal Wi-Fi transport, so the signed handshake, the tunnel, the VPN and the accounting
     * above it are exactly the same code as method A.
     */
    val p2p = P2pLink(context, object : P2pLink.Hooks {
        override fun onSocket(socket: java.net.Socket, isHost: Boolean) {
            DiagLog.i(tag, "Wi-Fi Direct produced a socket (" + (if (isHost) "group owner" else "client") + "): adopting it as the ProkNet link")
            if (!wifi.adoptSocket(socket, isHost, "Wi-Fi Direct")) DiagLog.w(tag, "the Wi-Fi Direct socket could not be adopted")
        }
        override fun onChanged() { main.post { refreshAdvert(); pushStatus(); onP2pGroupChanged() } }
        override fun staDescription(): String = wifi.currentWifi()?.let { (it.ssid ?: "?") + " " + ShareCheck.describe(it.freqMhz) } ?: ""
        override fun staFrequency(): Int = wifi.currentWifi()?.freqMhz ?: 0
        override fun onDataPlane(plane: net.prok.proknet.core.P2pDataPlane.Plane) { main.post { onP2pDataPlane(plane) } }
    })

    /**
     * v0.9.12: a buyer asks whether our Wi-Fi Direct group is ready. We answer with the state and
     * our OWN P2P name, and the buyer joins by itself.
     *
     * The phone test showed why: this phone owns a real group and sees BLE perfectly, but in its own
     * Wi-Fi Direct peer list the buyer is `00:00:00:00:00:00 available`. Android anonymises it here,
     * so the owner cannot reliably identify, let alone invite, the right phone.
     */
    private fun onP2pRequest(peerShort: String, deviceName: String) {
        val status = P2pPlan.groupStatus(gateway.providing && p2pFallbackActive, p2p.groupFormed, p2p.role == P2pPlan.Role.GROUP_OWNER)
        val code = when (status) {
            P2pPlan.GroupStatus.READY -> Wire.P2P_READY
            P2pPlan.GroupStatus.REBUILDING -> Wire.P2P_REBUILDING
            P2pPlan.GroupStatus.NOT_AVAILABLE -> Wire.P2P_NOT_AVAILABLE
        }
        DiagLog.i(tag, "P2P REQUEST from prok-" + peerShort + " (it calls itself \"" + deviceName + "\") -> answering " +
            Wire.p2pStatusName(code) + (if (code == Wire.P2P_READY) ", my Wi-Fi Direct name is \"" + p2p.myDeviceName + "\", clients " + p2p.clientCount else ""))
        sendControl(peerShort, Wire.p2pStatus(code, p2p.myDeviceName)) {}
        if (status == P2pPlan.GroupStatus.REBUILDING) {
            DiagLog.w(tag, "a buyer is asking and my group is not up (" + p2p.phase + "): rebuilding it")
            p2pFallbackActive = false
            startP2pFallback("a buyer is asking and the group was gone")
        }
    }

    // ---- v0.9.14: the transport handshake, once the client is really a member ---------------------

    /** The customer that told us its P2P address, and the membership generation we armed for it. */
    @Volatile private var p2pMemberAddress = ""
    @Volatile private var p2pMemberPeer = ""
    /** Buyer side: the membership generation we have already announced, so we announce it once. */
    @Volatile private var p2pAnnounced = 0

    /**
     * v0.9.14: the data plane moved on this phone. GROUP_READY only ever
     * meant "you may join"; this is where "you are joined and the transport
     * is up" is decided, on both sides.
     */
    private fun onP2pDataPlane(plane: net.prok.proknet.core.P2pDataPlane.Plane) {
        DiagLog.i(tag, "DATA PLANE " + plane.generationText() + ": " + plane.describe() + " | usable " + plane.usable)
        if (!plane.usable) return
        // SELLER: the customer may have announced itself before Android reported the client count, or
        // the other way round. Whichever came second, the transport is armed and announced here.
        if (gateway.providing && plane.role == P2pPlan.Role.GROUP_OWNER && p2pMemberAddress.isNotEmpty()) {
            p2p.armTransport("the data plane became usable")
            val peer = p2pMemberPeer
            if (peer.isNotEmpty()) {
                DiagLog.i(tag, "announcing TRANSPORT_READY to prok-" + peer + ": " + plane.localAddress + ":" + P2pPlan.PORT +
                    " for membership generation " + plane.membershipGeneration)
                sendControl(peer, Wire.p2pTransport(plane.membershipGeneration, plane.localAddress, P2pPlan.PORT)) {}
            }
            p2p.dialPeer(p2pMemberAddress, P2pPlan.PORT, "the data plane became usable and I know where the customer is")
        }
        if (buyViaP2p && plane.role == P2pPlan.Role.CLIENT) {
            // tell the provider where we are: it cannot learn a client address from Android
            val peer = buyerWanted ?: return
            if (p2pAnnounced == plane.membershipGeneration) return
            p2pAnnounced = plane.membershipGeneration
            DiagLog.i(tag, "telling prok-" + peer + " that I am in its group at " + plane.localAddress + ":" + P2pPlan.PORT)
            sendControl(peer, Wire.p2pMember(plane.localAddress, P2pPlan.PORT)) { ok ->
                if (!ok) DiagLog.w(tag, "the membership message could not be delivered over BLE")
            }
        }
    }

    /**
     * Seller: the customer says it has joined our group, and where it is.
     * We arm the listener FOR THIS membership, tell it so, and dial it
     * ourselves as well: Android lets us bind an outgoing socket to the
     * Wi-Fi Direct network, and gives no way to bind a listening one, so the
     * provider must not depend on being dialled.
     */
    private fun onP2pMember(peerShort: String, c: Wire.Control.P2pMember) {
        if (!gateway.providing) { DiagLog.w(tag, "prok-" + peerShort + " announced a membership but this phone is not sharing"); return }
        p2pMemberAddress = c.address
        p2pMemberPeer = peerShort
        DiagLog.i(tag, "P2P MEMBER: prok-" + peerShort + " is in my group at " + c.address + ":" + c.port)
        p2p.armTransport("the customer announced its membership")
        val plane = p2p.plane
        if (!plane.usable) { DiagLog.w(tag, "the data plane is not usable yet (" + plane.describe() + "), not announcing the transport"); return }
        DiagLog.i(tag, "answering TRANSPORT_READY: " + plane.localAddress + ":" + P2pPlan.PORT + " for membership generation " + plane.membershipGeneration)
        sendControl(peerShort, Wire.p2pTransport(plane.membershipGeneration, plane.localAddress, P2pPlan.PORT)) {}
        p2p.dialPeer(c.address, c.port, "the customer announced its membership")
    }

    /**
     * Buyer: the provider says its listener is armed for OUR membership.
     * That is the moment to dial, instead of dialling blindly six times.
     */
    private fun onP2pTransport(peerShort: String, c: Wire.Control.P2pTransport) {
        if (!buyViaP2p || buyerWanted != peerShort) return
        DiagLog.i(tag, "TRANSPORT_READY from prok-" + peerShort + ": " + c.address + ":" + c.port +
            " for membership generation " + c.membership)
        val err = p2p.dialPeer(c.address, c.port, "the provider announced its transport")
        if (err != null) DiagLog.w(tag, "cannot dial the provider yet: " + err)
    }

    private fun invitePeerByName(peerShort: String, deviceName: String, attempt: Int) {
        val found = p2p.findPeer(deviceName)
        if (found != null) {
            val err = p2p.invite(found.address, found.name)
            if (err != null) sendControl(peerShort, Wire.wifiCancel(Wire.CANCEL_P2P, err)) {}
            return
        }
        if (attempt >= 4) {
            DiagLog.w(tag, "\"" + deviceName + "\" never appeared in my Wi-Fi Direct peer list (" + p2p.peers.size + " peers seen)")
            sendControl(peerShort, Wire.wifiCancel(Wire.CANCEL_P2P, "the provider cannot see this phone in its Wi-Fi Direct peer list")) {}
            return
        }
        DiagLog.i(tag, "\"" + deviceName + "\" not in the peer list yet (attempt " + attempt + "/4), waiting for discovery")
        main.postDelayed({ invitePeerByName(peerShort, deviceName, attempt + 1) }, 5_000)
    }

    // ---- buyer side of method B -------------------------------------------------------------------

    @Volatile private var buyViaP2p = false
    @Volatile private var p2pWaitStart = 0L
    @Volatile private var p2pReachableMs = 0L
    @Volatile private var p2pUnreachableMs = 0L
    @Volatile private var p2pPausedLogged = false
    @Volatile private var p2pLastAskAt = 0L

    // ---- v0.9.12: the buyer joins the seller group by itself ---------------------------------------
    @Volatile private var p2pStatus: P2pPlan.GroupStatus? = null
    @Volatile private var p2pSellerName = ""
    @Volatile private var p2pConnectAttempts = 0
    @Volatile private var p2pNextConnectAt = 0L
    /** v0.9.13: when the local group formed on THIS phone. The transport has a bounded time from there. */
    @Volatile private var p2pGroupFormedAt = 0L

    /** The seller answered. Its own P2P name is what lets us find it in OUR peer list. */
    private fun onP2pStatus(peerShort: String, c: Wire.Control.P2pStatus) {
        if (!buyViaP2p || buyerWanted != peerShort) return
        p2pStatus = when (c.code) {
            Wire.P2P_READY -> P2pPlan.GroupStatus.READY
            Wire.P2P_REBUILDING -> P2pPlan.GroupStatus.REBUILDING
            else -> P2pPlan.GroupStatus.NOT_AVAILABLE
        }
        if (c.deviceName.isNotEmpty()) p2pSellerName = c.deviceName
        DiagLog.i(tag, "provider prok-" + peerShort + " answered " + Wire.p2pStatusName(c.code) +
            (if (p2pSellerName.isNotEmpty()) ", its Wi-Fi Direct name is \"" + p2pSellerName + "\"" else ""))
        if (p2pStatus == P2pPlan.GroupStatus.READY) { p2pConnectAttempts = 0; p2pNextConnectAt = 0L }
        main.post { p2pWaitStep(peerShort) }
    }

    /** v0.9.11: the provider answered "no" to the admission request. Stop at once, with its reason. */
    private fun onP2pRefused(peerShort: String, c: Wire.Control.WifiCancel) {
        val detail = c.detail.ifEmpty { Wire.cancelReasonText(c.reason) }
        DiagLog.e(tag, "prok-" + peerShort + " refused the Wi-Fi Direct admission: " + detail)
        failBuy("provider refused the Wi-Fi Direct admission", Wire.cancelReasonText(c.reason) + " [" + detail + "]")
    }

    /**
     * v0.9.11: stop a purchase AND keep the reason. stopInternet() clears lastBuyError, so the old
     * code lost every message and the screen fell back to the offer list with nothing explained.
     */
    private fun failBuy(logReason: String, userError: String) {
        stopInternet(logReason)
        lastBuyError = userError
        pushStatus()
    }

    /** The buyer becomes discoverable and asks the seller to invite it; it never joins on its own first. */
    private fun startP2pBuy(peerShort: String): Boolean {
        buyViaP2p = true
        p2pWaitStart = System.currentTimeMillis()
        p2pReachableMs = 0L; p2pUnreachableMs = 0L; p2pPausedLogged = false; p2pLastAskAt = 0L
        p2pStatus = null; p2pSellerName = ""; p2pConnectAttempts = 0; p2pNextConnectAt = 0L; p2pGroupFormedAt = 0L
        p2pAnnounced = 0
        val err = p2p.startBuyer()
        if (err != null) { DiagLog.e(tag, "cannot start Wi-Fi Direct: " + err); lastBuyError = err; return false }
        DiagLog.i(tag, "BUY over Wi-Fi Direct from prok-" + peerShort + ": becoming discoverable and asking to be invited")
        main.postDelayed({ askForInvite(peerShort) }, 2500)     // let this phone learn its own P2P name first
        main.postDelayed({ p2pWaitStep(peerShort) }, 4000)
        return true
    }

    private fun askForInvite(peerShort: String) {
        if (!buyViaP2p || buyerWanted != peerShort) return
        // v0.9.10: never hammer a transport that is not there. The provider has to be in BLE range.
        if (transportFor(peerShort) == null) {
            if (!p2pPausedLogged) { p2pPausedLogged = true; DiagLog.w(tag, "prok-" + peerShort + " is not reachable over BLE: pausing the invitation requests until it is seen again") }
            return
        }
        p2pPausedLogged = false
        val now = System.currentTimeMillis()
        if (now - p2pLastAskAt < P2pPlan.ASK_EVERY_MS) return    // v0.9.11: one request per ladder step, not per tick
        p2pLastAskAt = now
        val name = p2p.myDeviceName
        if (name.isEmpty()) { DiagLog.w(tag, "this phone does not know its own Wi-Fi Direct name yet, retrying"); main.postDelayed({ askForInvite(peerShort) }, 2500); return }
        DiagLog.i(tag, "asking prok-" + peerShort + " whether its Wi-Fi Direct group is ready (I am \"" + name + "\")")
        sendControl(peerShort, Wire.p2pRequest(name)) { ok -> if (!ok) DiagLog.w(tag, "the request could not be delivered over BLE") }
    }

    /** No silent waiting: ask again, then try to join by ourselves, then give up with a reason. */
    private fun p2pWaitStep(peerShort: String) {
        if (!buyViaP2p || buyerWanted != peerShort || wifi.linkedPeer != null) return
        val reachable = transportFor(peerShort) != null
        if (reachable) p2pReachableMs += 4000 else p2pUnreachableMs += 4000
        // out of BLE range: the ladder pauses, it never hammers a transport that is not there (v0.9.10)
        if (!reachable) {
            if (p2pUnreachableMs >= P2pPlan.UNREACHABLE_GIVE_UP_MS) {
                DiagLog.e(tag, P2pPlan.guestStepText(P2pPlan.GuestStep.UNREACHABLE))
                failBuy("provider out of range", "the provider is no longer in range")
                return
            }
            if (!p2pPausedLogged) { p2pPausedLogged = true; DiagLog.w(tag, P2pPlan.guestStepText(P2pPlan.GuestStep.PAUSED)) }
            main.postDelayed({ p2pWaitStep(peerShort) }, 4000)
            return
        }
        p2pPausedLogged = false
        // v0.9.13: the group can be formed and the transport still never come up. That must END, with
        // a sentence the customer can read, instead of a spinner that never stops.
        val sinceGroup = if (p2pGroupFormedAt == 0L) 0L else System.currentTimeMillis() - p2pGroupFormedAt
        if (P2pPlan.transportStep(p2p.groupFormed, wifi.linkedPeer != null, sinceGroup) == P2pPlan.TransportStep.FAIL_NO_TRANSPORT) {
            DiagLog.e(tag, "the Wi-Fi Direct group is formed but no ProkNet transport came up in " + (sinceGroup / 1000) +
                "s | my data plane: " + p2p.plane.describe() + " | listener " + net.prok.proknet.core.P2pDataPlane.verdictText(p2p.listenerVerdict()))
            // the sentence the customer reads is built in French by ProductState.lostHint
            failBuy(P2pPlan.TRANSPORT_FAIL_REASON, P2pPlan.TRANSPORT_FAIL_REASON)
            return
        }
        val sellerAddr = P2pPlan.pickSellerPeer(p2p.realPeers(), p2pSellerName)
        val step = P2pPlan.joinStep(p2pStatus, sellerAddr != null, p2pConnectAttempts, p2pReachableMs, p2p.groupFormed)
        when (step) {
            P2pPlan.JoinStep.DONE -> {}
            P2pPlan.JoinStep.ASK_STATUS -> askForInvite(peerShort)
            P2pPlan.JoinStep.WAIT_REBUILD -> { if (p2pReachableMs % 12_000L < 4_000L) askForInvite(peerShort) }
            P2pPlan.JoinStep.WAIT_PEER -> {
                if (p2pReachableMs % 12_000L < 4_000L)
                    DiagLog.i(tag, P2pPlan.joinStepText(step) + " (" + p2p.peers.size + " seen, " + p2p.realPeers().size + " with a real address" +
                        (if (p2pSellerName.isNotEmpty()) ", looking for \"" + p2pSellerName + "\"" else "") + "): " +
                        (if (p2p.realPeers().isEmpty()) "none addressable" else p2p.realPeers().joinToString("; ") { it.name.ifEmpty { "?" } }))
            }
            P2pPlan.JoinStep.CONNECT, P2pPlan.JoinStep.RETRY_BUSY -> {
                val now = System.currentTimeMillis()
                if (now >= p2pNextConnectAt && sellerAddr != null) {
                    p2pConnectAttempts++
                    // v0.9.18: hold the next attempt off BEFORE asking, so an accepted join is never
                    // overtaken by its own successor three seconds later, which is what made Android
                    // answer BUSY to us and burn the whole ladder
                    p2pNextConnectAt = now + P2pPlan.JOIN_ACCEPTED_WAIT_MS
                    DiagLog.i(tag, P2pPlan.joinStepText(step) + ": attempt " + p2pConnectAttempts + "/" + P2pPlan.CONNECT_ATTEMPTS +
                        " to \"" + P2pPlan.peerName(p2p.realPeers(), sellerAddr) + "\" (" + sellerAddr + ")")
                    p2p.connectTo(sellerAddr) { ok, why ->
                        if (ok) DiagLog.i(tag, "join accepted: waiting up to " + (P2pPlan.JOIN_ACCEPTED_WAIT_MS / 1000) + "s for the group to form")
                        else {
                            val wait = P2pPlan.busyDelayMs(p2pConnectAttempts)
                            p2pNextConnectAt = System.currentTimeMillis() + wait
                            DiagLog.w(tag, "join refused (" + why + "), next attempt in " + (wait / 1000) + "s")
                        }
                    }
                }
            }
            P2pPlan.JoinStep.FAIL_NOT_AVAILABLE -> {
                DiagLog.e(tag, P2pPlan.joinStepText(step))
                failBuy("provider not sharing by Wi-Fi Direct", "the provider is not sharing by Wi-Fi Direct")
                return
            }
            P2pPlan.JoinStep.GIVE_UP -> {
                DiagLog.e(tag, P2pPlan.joinStepText(step) + " after " + p2pConnectAttempts + " attempts")
                failBuy("Wi-Fi Direct join failed", "could not join the provider Wi-Fi Direct group")
                return
            }
        }
        main.postDelayed({ p2pWaitStep(peerShort) }, 4000)
    }

    @Volatile private var p2pGroupWasFormed = false

    /** One readable line when the group appears or disappears, on either side. */
    private fun onP2pGroupChanged() {
        if (p2p.groupFormed == p2pGroupWasFormed) return
        p2pGroupWasFormed = p2p.groupFormed
        if (p2p.groupFormed) {
            p2pGroupFormedAt = System.currentTimeMillis()
            DiagLog.i(tag, "WI-FI DIRECT GROUP FORMED: role " + p2p.role + ", clients " + p2p.clientCount + ", " + p2p.groupInfo +
                " | " + p2p.plane.describe())
        } else {
            p2pGroupFormedAt = 0L
            DiagLog.w(tag, "Wi-Fi Direct group gone (" + p2p.phase + ")")
        }
    }

    /** Developer test: this phone becomes the Wi-Fi Direct group owner and waits for a buyer. */
    fun p2pSell(): String? {
        if (!isRunning) return "start the node first"
        val r = P2pPlan.ready(p2p.supported, wifi.wifiEnabled, p2p.p2pEnabled)
        if (r != P2pPlan.Ready.OK) return P2pPlan.readyText(r)
        return p2p.startSeller()
    }

    /** Developer test: look for a Wi-Fi Direct group to join. */
    fun p2pBuy(): String? {
        if (!isRunning) return "start the node first"
        val r = P2pPlan.ready(p2p.supported, wifi.wifiEnabled, p2p.p2pEnabled)
        if (r != P2pPlan.Ready.OK) return P2pPlan.readyText(r)
        return p2p.startBuyer()
    }

    fun p2pStop() { p2p.stop(); if (wifi.linkedPeer != null) wifi.disconnect("Wi-Fi Direct test stopped") }

    // ---- v0.9.6: can this phone serve a customer while it stays on its own Wi-Fi network? ----
    private val capPrefs = context.getSharedPreferences("proknet_share_cap", Context.MODE_PRIVATE)
    @Volatile var shareCheck: ShareCheck.Result = ShareCheck.Result.UNKNOWN
        private set
    @Volatile var shareNetworkKey: String = ""
        private set
    @Volatile var shareFreqMhz: Int = 0
        private set
    @Volatile var shareDetail: String = ""
        private set

    /** Null = never tested on this network. False ONLY when Android refused the hotspot here. */
    fun canShareWhileOnWifi(): Boolean? = ShareCheck.canShareWhileOnWifi(shareCheck)

    private fun readCap(key: String): ShareCheck.Result = when (capPrefs.getString("cap:" + key, null)) {
        "1" -> ShareCheck.Result.CAN_SHARE
        "0" -> ShareCheck.Result.CANNOT_SHARE
        else -> ShareCheck.Result.UNKNOWN
    }

    private fun writeCap(key: String, r: ShareCheck.Result, detail: String, freq: Int) {
        capPrefs.edit()
            .putString("cap:" + key, if (r == ShareCheck.Result.CAN_SHARE) "1" else "0")
            .putString("why:" + key, detail)
            .putInt("freq:" + key, freq)
            .putLong("at:" + key, System.currentTimeMillis())
            .apply()
    }

    /** v0.9.9: true when this phone serves customers through a Wi-Fi Direct group instead of a hotspot. */
    @Volatile var p2pFallbackActive = false
        private set

    /**
     * v0.9.9: the hotspot is refused on this Wi-Fi network, so try method B instead of telling the
     * seller to give up. Nothing above the link changes: the group produces a socket, the socket is
     * adopted, and the tunnel runs as usual.
     */
    private fun startP2pFallback(why: String) {
        if (p2pFallbackActive || !gateway.providing) return
        val ready = P2pPlan.ready(p2p.supported, wifi.wifiEnabled, p2p.p2pEnabled)
        if (ready != P2pPlan.Ready.OK) { DiagLog.w(tag, "no Wi-Fi Direct fallback: " + P2pPlan.readyText(ready)); return }
        DiagLog.i(tag, "SHARING BY WI-FI DIRECT (" + why + "): creating a group while staying on this Wi-Fi network")
        p2pFallbackActive = true
        p2p.startSeller()
        refreshAdvert()
        pushStatus()
    }

    /**
     * v0.9.6: run when SELL is switched on (and when the upstream network changes). A phone selling
     * mobile data needs no test. A phone selling its own Wi-Fi is tested ONCE per network: the hotspot
     * is started and closed immediately, before any customer can fail on it.
     */
    fun checkSharing(why: String, force: Boolean = false) {
        val type = Tunnel.upstreamType(gateway.upstream)
        val net = wifi.currentWifi()
        val key = ShareCheck.key(net?.ssid, net?.bssid)
        shareNetworkKey = key
        shareFreqMhz = net?.freqMhz ?: 0
        if (!ShareCheck.needed(type)) {
            shareCheck = ShareCheck.Result.NOT_NEEDED; shareDetail = ""
            DiagLog.i(tag, "SHARE CHECK: upstream is " + Tunnel.upstreamName(type) + ", a hotspot cannot clash with it")
            pushStatus(); return
        }
        val remembered = if (force) ShareCheck.Result.UNKNOWN else readCap(key)
        if (!ShareCheck.shouldProbe(type, remembered)) {
            shareCheck = remembered; shareDetail = capPrefs.getString("why:" + key, "") ?: ""
            DiagLog.i(tag, "SHARE CHECK " + remembered + " (remembered) for " + key + " on " + ShareCheck.describe(shareFreqMhz))
            if (remembered == ShareCheck.Result.CANNOT_SHARE) startP2pFallback("hotspot known to be refused on this network")
            pushStatus(); return
        }
        if (!wifi.state.isIdle || gateway.session != null) { DiagLog.i(tag, "share check postponed (" + why + "): a Wi-Fi link is in use"); return }
        DiagLog.i(tag, "SHARE CHECK (" + why + "): testing the hotspot while joined to " + (net?.ssid ?: "a Wi-Fi network") + " on " + ShareCheck.describe(shareFreqMhz))
        HotspotProbe.run(context, shareFreqMhz, key) { o ->
            main.post {
                shareCheck = ShareCheck.verdict(type, o.started)
                shareDetail = o.detail
                writeCap(key, shareCheck, o.detail, o.freqMhz)
                DiagLog.i(tag, "SHARE CHECK " + shareCheck + " on " + ShareCheck.describe(o.freqMhz) + " [" + key + "]: " + o.detail +
                    (if (shareCheck == ShareCheck.Result.CANNOT_SHARE) " - no hotspot on this network; trying Wi-Fi Direct instead" else ""))
                if (shareCheck == ShareCheck.Result.CANNOT_SHARE) startP2pFallback("hotspot refused on this network")
                pushStatus()
            }
        }
    }

    /** The Wi-Fi network changed under a seller: the answer may be different on the new one. */
    private fun recheckSharingIfNetworkChanged() {
        if (!gateway.providing) return
        val net = wifi.currentWifi()
        if (ShareCheck.key(net?.ssid, net?.bssid) != shareNetworkKey) checkSharing("upstream network changed")
    }

    // ---- v0.9 live relay ----
    val relay: RelayNode = RelayNode(identity, object : RelayNode.Hooks {
        override fun sendDown(type: Int, payload: ByteArray): Boolean = wifi.sendRaw(type, payload)
        override fun sendUp(type: Int, payload: ByteArray): Boolean = wifiUp.sendRaw(type, payload)
        override fun downPeer(): String? = wifi.linkedPeer
        override fun upPeer(): String? = wifiUp.linkedPeer
        override fun peerPub(peerShort: String): ByteArray? = store.peerKey(peerShort)?.pub
        override fun peerFullId(peerShort: String): String? = store.peerKey(peerShort)?.fullId
        override fun peerName(peerShort: String): String = this@ProkNetNode.peerName(peerShort)
        override fun myRecord(): ByteArray = identityRecord()
        override fun saveIdentity(rec: Wire.IdentityRecord) = onIdentity("relay-intro", rec.idHex, rec.pub, rec.name)
        override fun upstreamOffer(): Market.Offer? = wifiUp.linkedPeer?.let { up -> ble.visiblePeers().firstOrNull { it.shortId == up }?.offer() }
        override fun onTunnelFromRelay(originShort: String, frame: Tunnel.Frame) = routeTunnelFrame(originShort, frame, "sealed via relay")
        override fun onRelayPeerGone(farShort: String, reason: String) { gateway.onLinkClosed(farShort, reason); tunnel.onLinkClosed(farShort, reason) }
        override fun onProviderIntroduced(sellerShort: String, pricePerMb: Int, flags: Int) {
            main.post {
                val want = buyerWanted
                if (want != null && want == wifi.linkedPeer && tunnel.session == null && tunnel.contract == null) {
                    if (pricePerMb != buyPrice) DiagLog.w(tag, "relay's seller price " + pricePerMb + " differs from the advertised " + buyPrice + " CFA/MB: proposing the advertised price")
                    DiagLog.i(tag, "relay prok-" + want + " introduced seller prok-" + sellerShort + ": proposing the contract end to end")
                    tunnel.start(buyPrice)
                }
            }
        }
        override fun onIntroductionRefused(relayShort: String) {
            main.post { if (buyerWanted == relayShort) { introRefused = true; introStep(relayShort) } }
        }
        override fun onChanged() { main.post { refreshAdvert(); pushStatus() } }
    })

    /** Seller side: to the buyer on my link, or sealed for the buyer behind my relay. */
    private fun sendFromGateway(type: Int, streamId: Int, data: ByteArray): Boolean {
        val rb = relay.relayedBuyer
        val target = gateway.buyerShort ?: gateway.contract?.buyerShort ?: rb
        return if (rb != null && target == rb) sendSealed(rb, type, streamId, data) else wifi.sendTunnel(type, streamId, data)
    }

    /** Buyer side: to the seller on my link, or sealed for the seller behind my relay. */
    private fun sendFromTunnel(type: Int, streamId: Int, data: ByteArray): Boolean {
        val p = relay.providerShort
        return if (p != null) sendSealed(p, type, streamId, data) else wifi.sendTunnel(type, streamId, data)
    }

    /** The seller the buyer talks to: the one behind a relay if introduced, else the link peer. */
    private fun buyerFarEnd(): String? = relay.providerShort ?: wifi.linkedPeer

    /** Seal a tunnel frame for [farShort] and send it on MY link (to the relay). */
    private fun sendSealed(farShort: String, type: Int, streamId: Int, data: ByteArray): Boolean {
        val bytes = relay.seal(farShort, type, streamId, data) ?: return false
        return wifi.sendRaw(Wire.FRAME_RELAY, bytes)
    }

    /** Where an incoming tunnel frame goes (plain from the link, or opened from a relay envelope). */
    private fun routeTunnelFrame(peerShort: String, frame: Tunnel.Frame, how: String) {
        when (Tunnel.route(frame.type, gateway.providing)) {
            Tunnel.Side.GATEWAY -> gateway.onFrame(peerShort, frame)
            Tunnel.Side.CLIENT -> tunnel.onFrame(peerShort, frame)
            Tunnel.Side.MISDIRECTED -> DiagLog.w(tag, "tunnel frame " + Tunnel.typeName(frame.type) + " from prok-" + peerShort + " (" + how + ") ignored: wrong direction for my role (" + (if (gateway.providing) "seller" else "buyer") + ")")
        }
    }

    /** B: relay mode on/off. A relay neither sells nor buys on its own. */
    fun setRelayMode(on: Boolean): String? {
        if (on && (gateway.providing || tunnel.session != null || buyerWanted != null)) return "stop selling / buying first"
        relay.setRelayMode(on)
        return null
    }

    /** B: bring the upstream link to the seller [peer] up (we join its hotspot; our normal link stays free to host a buyer). */
    fun linkUpstream(peer: Peer): Boolean {
        if (!isRunning) return false
        if (!hasKey(peer.shortId)) { DiagLog.e(tag, "upstream link needs prok-" + peer.shortId + "'s key first"); if (peer.inRange) fetchKey(peer.shortId); return false }
        if (wifi.linkedPeer == peer.shortId) { DiagLog.w(tag, "prok-" + peer.shortId + " is already on my normal link; the upstream link must be a different phone"); return false }
        DiagLog.i(tag, "UPSTREAM LINK requested to prok-" + peer.shortId + " (client-only second Wi-Fi link)")
        return wifiUp.requestLink(peer.shortId)
    }

    fun dropUpstream() { wifiUp.disconnect("dropped by user") }

    @Volatile private var buyPrice = 0

    /** Offers visible right now, best first. */
    fun offers(): List<Market.Offer> = Market.rank(ble.visiblePeers().filter { it.inRange && it.hasId && it.offer().selling }.map { it.offer() })

    /** Advertise SELL/RELAY/upstream/price in the BLE scan response. */
    fun refreshAdvert() {
        val ro = relay.lastUpstreamOffer
        if (relay.upstreamReady && ro != null) {
            ble.setCapabilities(Market.flags(sell = true, relay = relayOn, validated = ro.validated, upstreamType = ro.upstreamType, viaRelay = true), ro.pricePerMb)
            return
        }
        val up = gateway.upstream
        val flags = Market.flags(sell = gateway.providing && up != null, relay = relayOn, validated = up?.validated == true,
            // v0.9.11: only claim the Wi-Fi Direct way in when the group is REALLY formed. The phone
            // test showed a seller advertising it while its group had not come up, so every buyer
            // started a doomed admission and was refused.
            upstreamType = Tunnel.upstreamType(up), p2p = p2pFallbackActive && p2p.groupFormed)
        ble.setCapabilities(flags, if (gateway.providing) sellPrice else 0)
    }

    /** SELL on/off with the given terms. */
    fun setSelling(on: Boolean, price: Int = sellPrice, minPrice: Int = sellMinPrice, maxMb: Int = sellMaxMb): String? {
        if (on) {
            if (!Market.validPrice(price) || !Market.validMinPrice(minPrice) || !Market.validMaxMb(maxMb)) return "invalid terms"
            if (tunnel.session != null || buyerWanted != null) return "stop buying first"
            if (relay.relayMode) return "relay mode is on"
            sellPrice = price; sellMinPrice = minPrice; sellMaxMb = maxMb
            gateway.start()
            main.postDelayed({ if (gateway.providing) checkSharing("sharing switched on") }, 1200)
        } else {
            gateway.stop()
            p2pMemberAddress = ""; p2pMemberPeer = ""
            if (p2pFallbackActive) { p2pFallbackActive = false; p2p.stop() }
        }
        refreshAdvert(); pushStatus()
        return null
    }

    fun setRelay(on: Boolean) { relayOn = on; DiagLog.i(tag, "RELAY " + (if (on) "ON: this phone advertises that it carries packets for others" else "OFF")); refreshAdvert(); pushStatus() }

    /** BUY from [peer] at the advertised price: brings the Wi-Fi link up if needed, then contract, session, VPN. */
    fun buy(peer: Peer): Boolean {
        if (!isRunning) return false
        if (gateway.providing) { DiagLog.w(tag, "cannot BUY while SELL is on: stop selling first"); return false }
        if (relay.relayMode) { DiagLog.w(tag, "cannot BUY in relay mode"); return false }
        val offer = peer.offer()
        if (!offer.selling) { DiagLog.w(tag, "prok-" + peer.shortId + " is not selling Internet right now"); return false }
        buyPrice = offer.pricePerMb
        buyerWanted = peer.shortId
        buyViaRelay = offer.viaRelay
        clearLastFailure()
        DiagLog.i(tag, "BUY from prok-" + peer.shortId + " at " + offer.pricePerMb + " CFA/MB (" + Tunnel.upstreamName(offer.upstreamType) + (if (offer.viaRelay) ", THROUGH A RELAY" else "") + ", signal " + Market.signalWord(offer.rssi) + ")")
        if (wifi.canReach(peer.shortId)) return if (offer.viaRelay) awaitIntroduction(peer.shortId) else tunnel.start(buyPrice)
        if (offer.p2p) return startP2pBuy(peer.shortId)    // v0.9.9: this seller is reachable through its Wi-Fi Direct group
        return requestWifi(peer)
    }

    // ---- ledger ----
    fun ledgerAction(entryId: String, action: String): String? {
        val e = store.ledgerEntry(entryId) ?: return "entry not found"
        val n = Market.transition(e, action, System.currentTimeMillis()) ?: return "not allowed in state " + e.status
        store.updateLedger(n)
        DiagLog.i(tag, "LEDGER " + action + ": " + n.describe())
        pushStatus(); return null
    }
    val me: String get() = "prok-" + identity.shortIdHex
    fun balance(): Long = Market.balance(store.ledger(), me)
    /** Set by the UI: called when the session is accepted and the VPN should be started (consent dialog first). */
    @Volatile var vpnRequested: (() -> Unit)? = null
    @Volatile var buyerWanted: String? = null
        private set
    @Volatile private var buyViaRelay = false

    @Volatile private var introAttempts = 0
    @Volatile private var introRefused = false

    /**
     * A: the link to the relay is up. v0.9.1: ASK to be introduced instead of
     * waiting for an unsolicited introduction the relay may have sent minutes
     * ago (that one-shot design is what failed the first 3-phone test). The
     * request is idempotent and repeated until the relay answers.
     */
    private fun awaitIntroduction(relayShort: String): Boolean {
        introAttempts = 0; introRefused = false
        introStep(relayShort)
        return true
    }

    private fun introStep(relayShort: String) {
        if (buyerWanted != relayShort || tunnel.contract != null || tunnel.session != null) return
        when (Relay.buyerStep(relay.providerShort != null, introRefused, introAttempts)) {
            Relay.BuyerStep.START_CONTRACT -> { DiagLog.i(tag, "relay prok-" + relayShort + " introduced its seller prok-" + relay.providerShort + ": proposing the contract"); tunnel.start(buyPrice) }
            Relay.BuyerStep.NO_SELLER -> { DiagLog.w(tag, "relay prok-" + relayShort + " has no seller available right now"); failBuy("relay has no seller", "the relay has no Internet seller right now") }
            Relay.BuyerStep.GIVE_UP -> { DiagLog.w(tag, "relay prok-" + relayShort + " did not answer " + introAttempts + " introduction requests"); failBuy("relay silent", "the relay did not answer the introduction request") }
            Relay.BuyerStep.ASK -> {
                introAttempts++
                val ok = relay.requestIntroduction()
                DiagLog.i(tag, "INTRO REQUEST " + introAttempts + "/" + Relay.INTRO_ATTEMPTS + " -> relay prok-" + relayShort + " (written " + ok + ")")
                main.postDelayed({ introStep(relayShort) }, Relay.INTRO_RETRY_MS)
            }
        }
    }
    @Volatile var lastInternetTest: String = ""
    /** v0.9.3: why the last purchase attempt ended, kept after the attempt is cleared so the screen can explain it. */
    @Volatile var lastBuyError: String = ""

    private val tunnelSink = object : net.prok.proknet.transport.WifiTransport.TunnelSink {
        // v0.7.1: routed by frame direction and role only (a CONTRACT_PROPOSE arrives before any buyer is known).
        override fun onTunnelFrame(peerShort: String, frame: Tunnel.Frame) = routeTunnelFrame(peerShort, frame, "plain on link")
        override fun onLinkClosed(peerShort: String, reason: String) {
            gateway.onLinkClosed(peerShort, reason); tunnel.onLinkClosed(peerShort, reason)
            onSessionTornDown("link with prok-" + peerShort + " closed: " + reason)
            // v0.9: the far end behind this relay is unreachable too
            relay.onLinkToRelayClosed()?.let { far -> gateway.onLinkClosed(far, "link to relay closed: " + reason); tunnel.onLinkClosed(far, "link to relay closed: " + reason) }
            relay.onLinksChanged()
        }
        override fun onRawFrame(peerShort: String, type: Int, payload: ByteArray) = relay.onRawFrame(Relay.Side.DOWN, peerShort, type, payload)
    }
    private val upSink = object : net.prok.proknet.transport.WifiTransport.TunnelSink {
        override fun onTunnelFrame(peerShort: String, frame: Tunnel.Frame) { DiagLog.w(tag, "plain tunnel frame " + Tunnel.typeName(frame.type) + " on the UPSTREAM link from prok-" + peerShort + " ignored") }
        override fun onLinkClosed(peerShort: String, reason: String) { relay.onLinksChanged() }
        override fun onRawFrame(peerShort: String, type: Int, payload: ByteArray) = relay.onRawFrame(Relay.Side.UP, peerShort, type, payload)
    }

    /** v0.6 compatibility: provider on/off = SELL with the stored terms. */
    fun setProviding(on: Boolean) { setSelling(on) }

    /** Diagnostic entry point kept from v0.6: same as BUY at the peer's advertised price (0 if it advertises none). */
    fun useInternet(peer: Peer): Boolean = if (peer.offer().selling) buy(peer) else {
        if (!isRunning || gateway.providing) false else { buyPrice = 0; buyerWanted = peer.shortId; if (wifi.canReach(peer.shortId)) tunnel.start(0) else requestWifi(peer) }
    }

    /**
     * v0.9.17: the phase of the transport THIS purchase uses.
     *
     * A buyer on the Wi-Fi Direct path must never be judged by the hotspot
     * transport's leftover state. That is what made the screen say
     * "Connexion perdue" the moment SE CONNECTER was pressed, with nothing
     * attempted: the hotspot transport still held `DOWN ... could not reach
     * the host (10.168.138.1)` from an earlier attempt.
     */
    fun buyPhase(): String =
        if (buyViaP2p) P2pPlan.buyPhase(p2p.stage, p2p.groupFormed, p2p.plane.usable, wifi.linkedPeer != null)
        else wifi.phase

    /** A new purchase starts from a clean screen: nothing from the last attempt may show. */
    private fun clearLastFailure() {
        lastBuyError = ""
        tunnel.clearError()
        if (wifi.linkedPeer == null) wifi.disconnect("a new purchase starts from a clean screen")
    }

    fun stopInternet(reason: String) {
        if (buyViaP2p) { buyViaP2p = false; p2p.stop(); onSessionTornDown("buyer stopped a Wi-Fi Direct attempt") }
        buyerWanted = null; buyViaRelay = false; introAttempts = 0; introRefused = false; lastBuyError = ""
        tunnel.stop(reason)
        net.prok.proknet.vpn.ProkVpnService.stop(context)
        pushStatus()
    }

    fun onVpnChanged() { main.post { pushStatus() } }

    fun internetTest(host: String, cb: (TunnelClient.TestResult) -> Unit) = tunnel.internetTest(host) { r -> lastInternetTest = r.text; main.post { pushStatus(); cb(r) } }

    @Volatile var isRunning = false
        private set

    // ---- v0.9.10: BLE self-healing ----------------------------------------------------------------
    @Volatile private var sessionEndedAt = 0L
    @Volatile var lastBleVerdict: BleHealth.Verdict = BleHealth.Verdict.NOT_RUNNING
        private set

    private fun bleState(): BleHealth.State {
        val now = System.currentTimeMillis()
        val lastSeen = store.knownPeers().maxOfOrNull { it.lastSeen } ?: 0L
        val msSince = if (lastSeen > 0) now - lastSeen else -1L
        val linkBusy = wifi.linkedPeer != null || !wifi.state.isIdle || wifiUp.linkedPeer != null || !wifiUp.state.isIdle ||
            p2p.groupFormed || p2p.phase == "CLEANING" || p2p.phase == "CREATING GROUP"
        return BleHealth.State(
            now = now, running = isRunning && ble.isRunning, bluetoothOn = ble.isBluetoothOn,
            advertising = ble.advertising, advertiseFailedAt = ble.advertiseFailedAt,
            scanning = ble.scanning, scanFailedAt = ble.scanFailedAt, lastScanResultAt = ble.lastScanResultAt,
            startedAt = ble.startedAt, gattTimeouts = ble.gattTimeouts,
            expectPeers = BleHealth.expectPeers(msSince, buyerWanted != null, gateway.providing),
            linkBusy = linkBusy, sessionEndedAt = sessionEndedAt,
            lastRecoveryAt = ble.lastRecoveryAt, recoveries = ble.recoveries,
        )
    }

    /** Runs while the service runs. Silent when everything is fine; never loops. */
    private val bleWatchdog = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val s = bleState()
            val v = BleHealth.verdict(s)
            if (v != lastBleVerdict && (v != BleHealth.Verdict.HEALTHY || lastBleVerdict != BleHealth.Verdict.NOT_RUNNING))
                DiagLog.i(tag, "BLE health: " + BleHealth.verdictText(v))
            lastBleVerdict = v
            if (BleHealth.action(v) == BleHealth.Action.RECOVER) {
                DiagLog.w(tag, "BLE health check: " + BleHealth.verdictText(v) + " (no scan result for " +
                    (if (s.lastScanResultAt > 0) ((s.now - s.lastScanResultAt) / 1000).toString() + "s" else "ever") +
                    ", gatt timeouts " + s.gattTimeouts + ", peers expected " + s.expectPeers + ")")
                if (ble.recoverRadio(BleHealth.verdictText(v))) {
                    refreshAdvert()          // SELL stays on: its offer must go back on the air
                    main.postDelayed({ listener.onPeers(peers()); pushStatus() }, 1500)
                }
            }
            main.postDelayed(this, 10_000)
        }
    }

    /**
     * v0.9.10: a Wi-Fi or Wi-Fi Direct session just ended. The BLE stack is the
     * prime suspect after one, so look sooner than the normal watchdog would.
     */
    fun onSessionTornDown(why: String) {
        sessionEndedAt = System.currentTimeMillis()
        DiagLog.i(tag, "BLE health check scheduled after a Wi-Fi session ended (" + why + ")")
        main.postDelayed({ if (isRunning) bleWatchdog.run() }, 3_000)
    }
    private var lastVisibleIds: Set<String> = emptySet()
    private val linkStates = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val keyFetchInFlight = HashSet<String>()

    fun isBluetoothOn(): Boolean = ble.isBluetoothOn

    fun start(): Boolean {
        if (isRunning) { DiagLog.w(tag, "start ignored: already running"); return true }
        if (!ble.isBluetoothOn) { DiagLog.e(tag, "Bluetooth is OFF - turn it on and press Start again"); pushStatus(); return false }
        DiagLog.i(tag, "starting node id=" + identity.idHex + " name=" + identity.displayName + " fingerprint=" + identity.fingerprint +
            (identity.legacyIdHex?.let { " (migrated from " + it.substring(0, 8) + ")" } ?: "") + ", known keys=" + store.peerKeyCount())
        val bleOk = ble.start(this)
        wifi.tunnelSink = tunnelSink
        wifi.start(this)
        wifiUp.tunnelSink = upSink
        wifiUp.start(this)
        main.postDelayed({ refreshAdvert() }, 1500)
        isRunning = true
        queue.start()
        engine.start()
        DiagLog.i(tag, "node started: ble=" + bleOk + " wifi=ready(idle)")
        main.postDelayed({ pushStatus() }, 1500)
        main.postDelayed(bleWatchdog, 15_000)
        listener.onPeers(peers())
        pushStatus()
        return bleOk
    }

    fun stop() {
        if (!isRunning) return
        tunnel.stop("node stopped")
        net.prok.proknet.vpn.ProkVpnService.stop(context)
        gateway.stop()
        engine.stop()
        queue.stop()
        relay.reset()
        wifiUp.stop()
        wifi.stop()
        ble.stop()
        isRunning = false
        main.removeCallbacks(bleWatchdog)
        lastVisibleIds = emptySet()
        DiagLog.i(tag, "node stopped")
        listener.onPeers(peers())
        pushStatus()
    }

    // ---- peers and keys ---------------------------------------------------------------------------

    /** Visible peers first (with RSSI), then known peers that are not in range. */
    fun peers(): List<Peer> {
        val vis = ble.visiblePeers()
        val visIds = vis.map { it.shortId }.toSet()
        val known = store.knownPeers()
            .filter { it.shortId !in visIds }
            .map { Peer(it.shortId, it.lastAddress, 0, it.lastSeen, inRange = false, fullId = it.fullId) }
        return vis + known
    }

    fun hasKey(shortId: String): Boolean = store.peerKey(shortId) != null
    fun peerName(shortId: String): String = store.peerKey(shortId)?.name?.takeIf { it.isNotEmpty() } ?: ("prok-" + shortId)

    private fun reachablePeers(): List<Routing.PeerView> {
        val out = ArrayList<Routing.PeerView>()
        for (p in ble.visiblePeers()) if (p.inRange && p.hasId) out.add(Routing.PeerView(p.shortId, p.rssi))
        val w = wifi.linkedPeer
        if (w != null && wifi.canReach(w) && out.none { it.shortId == w }) out.add(Routing.PeerView(w, 0))
        return out
    }

    private fun transportFor(peerShort: String): Transport? =
        when (Routing.chooseTransport(wifi.canReach(peerShort), ble.canReach(peerShort))) {
            Routing.TRANSPORT_WIFI -> wifi
            Routing.TRANSPORT_BLE -> ble
            else -> null
        }

    override fun onPeersChanged(peers: List<Peer>) {
        for (p in peers) if (p.hasId) store.rememberPeer(p.shortId, p.label, p.address, p.lastSeen, p.fullId)
        val ids = peers.map { it.shortId }.toSet()
        val appeared = peers.filter { it.shortId !in lastVisibleIds }
        lastVisibleIds = ids
        main.post {
            listener.onPeers(this.peers())
            if (relay.relayMode) relay.onLinksChanged()
            for (p in appeared) {
                queue.onPeerAppeared(p)
                engine.onPeerSeen(p.shortId)
                if (p.hasId && !hasKey(p.shortId)) fetchKey(p.shortId)
            }
            queue.onPeersChanged()
            pushStatus()
        }
    }

    /** Learn a peer's public key with one short BLE connection (once per peer per session, or until it works). */
    private fun fetchKey(shortId: String) {
        if (!keyFetchInFlight.add(shortId)) return
        DiagLog.i(tag, "KEY FETCH: reading identity record of prok-" + shortId + " over BLE")
        ble.fetchIdentity(shortId) { ok ->
            ble.noteGatt(ok)
            main.post {
                keyFetchInFlight.remove(shortId)
                if (!ok) DiagLog.w(tag, "KEY FETCH from prok-" + shortId + " failed; will retry when it reappears")
                pushStatus()
            }
        }
    }

    override fun onIdentity(transport: String, fullIdHex: String, pubBytes: ByteArray, name: String) {
        val short = fullIdHex.substring(0, 8)
        val had = store.peerKey(short)
        store.savePeerKey(short, fullIdHex, pubBytes, name)
        store.rememberPeer(short, "prok-" + short, "", System.currentTimeMillis(), fullIdHex)
        if (had == null || !had.pub.contentEquals(pubBytes))
            DiagLog.i(tag, "KEY LEARNED for prok-" + short + " via " + transport + ": fingerprint " + Crypto.fingerprint(pubBytes) + (if (name.isNotEmpty()) " name \"" + name + "\"" else "") +
                (if (had != null) " (KEY CHANGED - reinstall or new device?)" else ""))
        main.post { listener.onPeers(peers()); queue.onPeersChanged(); engine.onPeerSeen(short); pushStatus() }
    }

    override fun onLinkState(transport: String, state: String) {
        linkStates[transport] = state
        if (transport == Routing.TRANSPORT_WIFI) p2p.linkAuthenticated = wifi.linkedPeer != null
        main.post {
            pushStatus(); engine.onWifiChanged(); queue.onPeersChanged()
            // Buyer waiting for the link: start the session as soon as the authenticated link is up.
            val want = buyerWanted
            if (transport == Routing.TRANSPORT_WIFI && want != null && wifi.canReach(want) && tunnel.session == null && tunnel.contract == null && tunnel.state != "CONNECTING" && tunnel.state != "AGREEING") {
                if (buyViaRelay) awaitIntroduction(want)
                else { DiagLog.i(tag, "Wi-Fi link up with prok-" + want + ": proposing the contract"); tunnel.start(buyPrice) }
            }
            // v0.9.3: the link attempt died before any session existed. The purchase is over: say why and
            // clear it, so the screen explains the real reason and the next SELL is not refused.
            if (transport == Routing.TRANSPORT_WIFI && want != null && wifi.phase.startsWith("DOWN") && tunnel.session == null && tunnel.contract == null) {
                val why = wifi.state.lastError.ifEmpty { "the Wi-Fi link could not be set up" }
                DiagLog.w(tag, "buy attempt ended before any session: " + why)
                lastBuyError = why
                buyerWanted = null; buyViaRelay = false; introAttempts = 0; introRefused = false
            }
            relay.onLinksChanged()
        }
    }

    fun linkState(transport: String): String = linkStates[transport] ?: "?"

    /** v0.9.10: one line for the developer diagnostic. */
    fun bleHealthLine(): String = BleHealth.verdictText(BleHealth.verdict(bleState())) + " | " + ble.healthLine()

    // ---- sending -----------------------------------------------------------------------------------

    /** Encrypted text to [peer]: one packet if it fits (relayable), else a chunked transfer (direct only). */
    fun sendText(peer: Peer, text: String): Boolean {
        if (!isRunning) { DiagLog.e(tag, "cannot queue: node not running"); return false }
        if (!peer.hasId) { DiagLog.e(tag, "cannot address " + peer.label + ": no ProkNet ID received from it yet"); return false }
        val key = store.peerKey(peer.shortId)
        if (key == null) {
            DiagLog.e(tag, "cannot encrypt for " + peer.label + ": public key unknown. It is fetched automatically when the peer is in range" + (if (peer.inRange) " (fetching now)" else ""))
            if (peer.inRange) fetchKey(peer.shortId)
            return false
        }
        val dest = key.fullId.hexToBytes()
        val msgId = Packet.newMsgId()
        val ts = System.currentTimeMillis()
        val header = Packet(identity.idBytes, dest, msgId, ts, ByteArray(0), Packet.TYPE_ENVELOPE)
        val payload = try { Crypto.seal(key.pub, header.aad(), identity.buildSigned(header.aad(), Signed.KIND_TEXT, text.toByteArray(Charsets.UTF_8))) }
        catch (e: Exception) { DiagLog.e(tag, "encryption failed", e); return false }
        if (payload.size <= Packet.MAX_PAYLOAD_BYTES) {
            queue.enqueue(peer.shortId, key.fullId, peer.label, text, msgId, ts, payload)
            return true
        }
        DiagLog.i(tag, "text is " + text.length + " chars: too big for one packet, sending as a chunked transfer (direct only, no relay)")
        return engine.send(peer.shortId, key.fullId, peer.label, Transfer.BLOB_TEXT, "", text.toByteArray(Charsets.UTF_8))
    }

    fun sendFile(peer: Peer, name: String, data: ByteArray): Boolean {
        if (!isRunning) return false
        val key = store.peerKey(peer.shortId) ?: run { DiagLog.e(tag, "cannot encrypt for " + peer.label + ": public key unknown"); if (peer.inRange) fetchKey(peer.shortId); return false }
        return engine.send(peer.shortId, key.fullId, peer.label, Transfer.BLOB_FILE, name, data)
    }

    fun requestWifi(peer: Peer): Boolean {
        if (!isRunning) return false
        if (!hasKey(peer.shortId)) { DiagLog.e(tag, "Wi-Fi link needs prok-" + peer.shortId + "'s key first"); if (peer.inRange) fetchKey(peer.shortId); return false }
        return wifi.requestLink(peer.shortId)
    }

    /** Encrypted, signed control message (Wi-Fi negotiation), sent immediately over the best transport. Not stored. */
    private fun sendControl(peerShort: String, body: ByteArray, cb: (Boolean) -> Unit) {
        val key = store.peerKey(peerShort) ?: run { cb(false); return }
        val transport = transportFor(peerShort) ?: run { DiagLog.w(tag, "no transport to prok-" + peerShort + " for control message"); cb(false); return }
        val msgId = Packet.newMsgId(); val ts = System.currentTimeMillis()
        val dest = key.fullId.hexToBytes()
        val header = Packet(identity.idBytes, dest, msgId, ts, ByteArray(0), Packet.TYPE_ENVELOPE)
        val payload = try { Crypto.seal(key.pub, header.aad(), identity.buildSigned(header.aad(), Signed.KIND_CONTROL, body)) } catch (e: Exception) { cb(false); return }
        val pkt = Packet(identity.idBytes, dest, msgId, ts, payload, Packet.TYPE_ENVELOPE, 1, 0).stamped(identity.idBytes)
        DiagLog.i(tag, "CONTROL -> prok-" + peerShort + " over " + transport.name + " (" + body.size + " bytes, encrypted)")
        transport.send(peerShort, Frame(msgId, pkt.encode())) { res, detail ->
            val ok = res == DeliveryResult.DELIVERED || res == DeliveryResult.DUPLICATE
            if (!ok) DiagLog.w(tag, "CONTROL to prok-" + peerShort + " failed: " + res + " " + detail)
            if (transport.name == Routing.TRANSPORT_BLE) ble.noteGatt(ok)
            cb(ok)
        }
    }

    // ---- receiving --------------------------------------------------------------------------------

    /** A packet arrived over [transport]. Returns the receipt code. Called on a transport thread. */
    override fun onFrame(transport: String, fromShort: String?, bytes: ByteArray): Int {
        val pkt = Packet.decode(bytes) ?: return Routing.RECEIPT_REJECTED
        val known = if (pkt.isChunk) null else store.knows(pkt.msgIdHex, pkt.originShort)
        val decision = Routing.decideReceive(pkt, identity.idBytes, known != null)
        val via = if (pkt.hasLastHop) pkt.lastHopShort else (fromShort ?: "?")
        val viaIsOrigin = via == pkt.originShort
        when (decision) {
            Routing.Receive.CHUNK -> return engine.onChunk(pkt, transport)
            Routing.Receive.FINAL -> return receiveFinal(pkt, transport, via, viaIsOrigin)
            Routing.Receive.RELAY -> {
                val fresh = store.insert(
                    StoredMessage(
                        0, pkt.msgIdHex, Dir.CARRY, pkt.originShort, "prok-" + pkt.originShort, "", pkt.timestamp, MsgStatus.CARRYING,
                        destId = pkt.destIdHex, originId = pkt.originIdHex, via = via, ttl = pkt.ttl, hops = pkt.hops,
                        payload = pkt.payload, enc = if (pkt.isEncrypted) 1 else 0, transport = transport,
                    )
                )
                if (!fresh) return Routing.RECEIPT_DUPLICATE
                DiagLog.i(tag, "ACCEPTED FOR RELAY msg=" + pkt.msgIdHex + " from prok-" + pkt.originShort + " (handed by prok-" + via + ") for prok-" + pkt.destShort +
                    " (hops " + pkt.hops + "/" + pkt.ttl + ", " + (if (pkt.isEncrypted) "encrypted, opaque to me" else "PLAINTEXT legacy") + ") - CARRYING until I meet prok-" + pkt.destShort)
                main.post { listener.onMessagesChanged(); pushStatus("carrying for prok-" + pkt.destShort); queue.pump("relay accepted") }
                return Routing.RECEIPT_ACCEPTED_RELAY
            }
            Routing.Receive.DUPLICATE -> { DiagLog.w(tag, "already have msg=" + pkt.msgIdHex + " from prok-" + pkt.originShort + " (" + known + ") - receipt DUPLICATE"); return Routing.RECEIPT_DUPLICATE }
            Routing.Receive.REJECT_TTL -> { DiagLog.w(tag, "REFUSING custody of msg=" + pkt.msgIdHex + ": TTL exhausted (hops " + pkt.hops + "/" + pkt.ttl + ")"); return Routing.RECEIPT_REJECTED }
            Routing.Receive.REJECT_ALREADY_RELAYED -> { DiagLog.w(tag, "REFUSING custody of msg=" + pkt.msgIdHex + ": already relayed once"); return Routing.RECEIPT_REJECTED }
            Routing.Receive.REJECT_NOT_RELAYABLE -> { DiagLog.w(tag, "REFUSING chunk for prok-" + pkt.destShort + ": transfers are direct only"); return Routing.RECEIPT_REJECTED }
            Routing.Receive.REJECT_MALFORMED -> return Routing.RECEIPT_REJECTED
        }
    }

    private fun receiveFinal(pkt: Packet, transport: String, via: String, viaIsOrigin: Boolean): Int {
        var text: String
        var enc = 0; var verified = 0
        if (pkt.isEncrypted) {
            val opened = identity.open(pkt.aad(), pkt.payload)
            if (opened == null) { DiagLog.e(tag, "msg=" + pkt.msgIdHex + " from prok-" + pkt.originShort + ": cannot DECRYPT (not for my key, or tampered) - REJECTED"); return Routing.RECEIPT_REJECTED }
            val signed = Signed.parse(opened) ?: run { DiagLog.e(tag, "msg=" + pkt.msgIdHex + ": plaintext malformed - REJECTED"); return Routing.RECEIPT_REJECTED }
            val pub = store.peerKey(pkt.originShort)?.pub
            verified = if (pub == null) 0 else if (Signed.verify(pub, pkt.aad(), signed)) 1 else -1
            if (verified == -1) { DiagLog.e(tag, "msg=" + pkt.msgIdHex + ": signature INVALID for prok-" + pkt.originShort + " - REJECTED (impersonation attempt?)"); return Routing.RECEIPT_REJECTED }
            enc = 1
            if (signed.kind == Signed.KIND_CONTROL) {
                if (verified != 1) { DiagLog.w(tag, "control message from prok-" + pkt.originShort + " ignored: sender key unknown"); return Routing.RECEIPT_REJECTED }
                DiagLog.i(tag, "CONTROL from prok-" + pkt.originShort + " over " + transport + " (" + signed.body.size + " bytes, signature verified)")
                dispatchControl(pkt.originShort, signed.body)
                return Routing.RECEIPT_ACCEPTED
            }
            if (signed.kind != Signed.KIND_TEXT) { DiagLog.w(tag, "msg=" + pkt.msgIdHex + ": unknown kind " + signed.kind); return Routing.RECEIPT_REJECTED }
            text = String(signed.body, Charsets.UTF_8)
        } else {
            text = pkt.text
        }
        val fresh = store.insert(
            StoredMessage(
                0, pkt.msgIdHex, Dir.IN, pkt.originShort, peerName(pkt.originShort), text, pkt.timestamp, MsgStatus.RECEIVED,
                destId = identity.idHex, originId = pkt.originIdHex, via = if (viaIsOrigin) "" else via, ttl = pkt.ttl, hops = pkt.hops,
                enc = enc, verified = verified, transport = transport,
            )
        )
        if (!fresh) { DiagLog.w(tag, "duplicate message " + pkt.msgIdHex + " ignored (receipt DUPLICATE)"); return Routing.RECEIPT_DUPLICATE }
        DiagLog.i(tag, "FINAL RECEIVED msg=" + pkt.msgIdHex + " from prok-" + pkt.originShort +
            (if (viaIsOrigin) " directly" else " via relay prok-" + via + " after " + pkt.hops + " hop(s)") + " over " + transport +
            (if (enc == 1) ", E2E decrypted, signature " + (if (verified == 1) "VERIFIED" else "unverified (key unknown)") else ", PLAINTEXT (legacy v" + pkt.wireVersion + ")") + ": \"" + text.take(80) + "\"")
        main.post { listener.onMessagesChanged(); pushStatus("received from prok-" + pkt.originShort) }
        return Routing.RECEIPT_ACCEPTED
    }

    /** v0.9: two Wi-Fi instances share one control channel; an offer goes to the instance that asked for it. */
    private fun dispatchControl(peerShort: String, body: ByteArray) {
        val c = Wire.parseControl(body)
        val upWants = wifiUp.state.peer == peerShort && !wifiUp.state.isIdle
        when {
            c is Wire.Control.P2pRequest -> onP2pRequest(peerShort, c.deviceName)
            // v0.9.11: a refusal for the Wi-Fi Direct admission was being handed to the hotspot
            // transport, which is idle on this path and dropped it in silence. The phone test shows
            // the seller answering every single request and the buyer never reacting.
            c is Wire.Control.P2pStatus -> onP2pStatus(peerShort, c)
            c is Wire.Control.P2pMember -> onP2pMember(peerShort, c)
            c is Wire.Control.P2pTransport -> onP2pTransport(peerShort, c)
            c is Wire.Control.WifiCancel && buyViaP2p && buyerWanted == peerShort -> onP2pRefused(peerShort, c)
            c is Wire.Control.WifiRequest -> wifi.onControl(peerShort, body)
            upWants -> wifiUp.onControl(peerShort, body)
            else -> wifi.onControl(peerShort, body)
        }
    }

    // ---- status ----------------------------------------------------------------------------------------

    fun statusLine(extra: String? = null): String {
        val bt = if (ble.isBluetoothOn) "BT on" else "BT OFF"
        val q = "queue " + store.pendingCount() + " carry " + store.carryingCount() + (if (queue.inFlightMsg() != null) " (1 sending)" else "") +
            (engine.inFlight?.let { " xfer " + engine.inFlightProgress + "%" } ?: "")
        if (!isRunning) return bt + " | node stopped | " + q
        val sb = StringBuilder(bt)
        sb.append(" | ble: ").append(ble.linkState())
        sb.append(" | wifi: ").append(wifi.phase).append(wifi.linkedPeer?.let { " prok-" + it } ?: "")
        if (wifiUp.phase != "IDLE") sb.append(" | up: ").append(wifiUp.phase).append(wifiUp.linkedPeer?.let { " prok-" + it } ?: "")
        if (relay.relayMode || relay.providerShort != null || relay.relayedBuyer != null) sb.append(" | ").append(relay.stateLine())
        sb.append(" | keys ").append(store.peerKeyCount())
        if (gateway.providing) sb.append(" | SELL: ").append(gateway.state)
        if (gateway.providing && shareCheck == ShareCheck.Result.CANNOT_SHARE) sb.append(" | hotspot refused on this Wi-Fi (").append(ShareCheck.band(shareFreqMhz)).append(")")
        if (p2pFallbackActive) sb.append(" | sharing by Wi-Fi Direct: ").append(p2p.phase).append(" clients ").append(p2p.clientCount)
            .append(" | listener ").append(net.prok.proknet.core.P2pDataPlane.verdictText(p2p.listenerVerdict()))
        if (relayOn) sb.append(" | RELAY on")
        if (tunnel.state != "DISCONNECTED" || buyerWanted != null) sb.append(" | BUY: ").append(tunnel.state)
        sb.append(" | ").append(q)
        if (extra != null) sb.append(" | ").append(extra)
        return sb.toString()
    }

    private fun pushStatus(extra: String? = null) {
        val line = statusLine(extra)
        main.post { listener.onStatus(line) }
    }
}
