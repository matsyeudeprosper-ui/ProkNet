package net.prok.proknet.ble

import android.content.Context
import android.os.Handler
import android.os.Looper
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
import net.prok.proknet.transport.WifiTransport

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
                    (if (shareCheck == ShareCheck.Result.CANNOT_SHARE) " - this phone cannot RESELL this network; the network itself stays on the map for another phone" else ""))
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
        val flags = Market.flags(sell = gateway.providing && up != null, relay = relayOn, validated = up?.validated == true, upstreamType = Tunnel.upstreamType(up))
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
        } else gateway.stop()
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
        lastBuyError = ""
        DiagLog.i(tag, "BUY from prok-" + peer.shortId + " at " + offer.pricePerMb + " CFA/MB (" + Tunnel.upstreamName(offer.upstreamType) + (if (offer.viaRelay) ", THROUGH A RELAY" else "") + ", signal " + Market.signalWord(offer.rssi) + ")")
        if (wifi.canReach(peer.shortId)) return if (offer.viaRelay) awaitIntroduction(peer.shortId) else tunnel.start(buyPrice)
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
            Relay.BuyerStep.NO_SELLER -> { DiagLog.w(tag, "relay prok-" + relayShort + " has no seller available right now"); stopInternet("the relay has no Internet seller right now") }
            Relay.BuyerStep.GIVE_UP -> { DiagLog.w(tag, "relay prok-" + relayShort + " did not answer " + introAttempts + " introduction requests"); stopInternet("the relay did not answer the introduction request") }
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

    fun stopInternet(reason: String) {
        buyerWanted = null; buyViaRelay = false; introAttempts = 0; introRefused = false; lastBuyError = ""
        tunnel.stop(reason)
        net.prok.proknet.vpn.ProkVpnService.stop(context)
        pushStatus()
    }

    fun onVpnChanged() { main.post { pushStatus() } }

    fun internetTest(host: String, cb: (TunnelClient.TestResult) -> Unit) = tunnel.internetTest(host) { r -> lastInternetTest = r.text; main.post { pushStatus(); cb(r) } }

    @Volatile var isRunning = false
        private set
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
        if (gateway.providing && shareCheck == ShareCheck.Result.CANNOT_SHARE) sb.append(" | CANNOT resell this Wi-Fi (").append(ShareCheck.band(shareFreqMhz)).append(")")
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
