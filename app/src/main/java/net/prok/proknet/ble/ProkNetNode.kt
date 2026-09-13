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
import net.prok.proknet.node.TransferEngine
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
    })
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
        wifi.start(this)
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
        engine.stop()
        queue.stop()
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
        main.post { pushStatus(); engine.onWifiChanged(); queue.onPeersChanged() }
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
                wifi.onControl(pkt.originShort, signed.body)
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

    // ---- status ----------------------------------------------------------------------------------------

    fun statusLine(extra: String? = null): String {
        val bt = if (ble.isBluetoothOn) "BT on" else "BT OFF"
        val q = "queue " + store.pendingCount() + " carry " + store.carryingCount() + (if (queue.inFlightMsg() != null) " (1 sending)" else "") +
            (engine.inFlight?.let { " xfer " + engine.inFlightProgress + "%" } ?: "")
        if (!isRunning) return bt + " | node stopped | " + q
        val sb = StringBuilder(bt)
        sb.append(" | ble: ").append(ble.linkState())
        sb.append(" | wifi: ").append(wifi.state.state.name.lowercase()).append(wifi.linkedPeer?.let { " prok-" + it } ?: "")
        sb.append(" | keys ").append(store.peerKeyCount())
        sb.append(" | ").append(q)
        if (extra != null) sb.append(" | ").append(extra)
        return sb.toString()
    }

    private fun pushStatus(extra: String? = null) {
        val line = statusLine(extra)
        main.post { listener.onStatus(line) }
    }
}
