package net.prok.proknet.node

import java.util.concurrent.ConcurrentHashMap
import net.prok.proknet.core.Crypto
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Identity
import net.prok.proknet.core.Market
import net.prok.proknet.core.Relay
import net.prok.proknet.core.Tunnel
import net.prok.proknet.core.Wire
import net.prok.proknet.core.hexToBytes
import net.prok.proknet.core.toHex

/**
 * Live relay glue (v0.9), the Android-side state for the three roles of the
 * 3-phone experiment. The framing and crypto are in core/Relay (pure).
 *
 *   B (relay):  relayMode on. DOWN link = its normal Wi-Fi link (it hosts A);
 *               UP link = its second, client-only link to the seller C.
 *               When both are up it introduces each side to the other and
 *               forwards FRAME_RELAY frames between them, counting sizes only.
 *   A (buyer):  learns the seller behind its link peer from the introduction,
 *               then talks to that seller through sealed frames.
 *   C (seller): learns the buyer behind its link peer, then receives that
 *               buyer's sealed frames as if the buyer were on the link.
 */
class RelayNode(private val identity: Identity, private val hooks: Hooks) {
    interface Hooks {
        fun sendDown(type: Int, payload: ByteArray): Boolean
        fun sendUp(type: Int, payload: ByteArray): Boolean
        fun downPeer(): String?
        fun upPeer(): String?
        fun peerPub(peerShort: String): ByteArray?
        fun peerFullId(peerShort: String): String?
        fun peerName(peerShort: String): String
        fun saveIdentity(rec: Wire.IdentityRecord)
        /** The seller's BLE advert as the relay last saw it (price, flags). */
        fun upstreamOffer(): Market.Offer?
        /** A or C: a tunnel frame that came sealed from [originShort] through the relay. */
        fun onTunnelFromRelay(originShort: String, frame: Tunnel.Frame)
        /** A or C: the far end behind the relay is gone. */
        fun onRelayPeerGone(farShort: String, reason: String)
        /** A: the relay introduced its seller; the buyer may start the contract now. */
        fun onProviderIntroduced(sellerShort: String, pricePerMb: Int, flags: Int)
        fun onChanged()
    }

    private val tag = "RELAY"

    /** B: forward between my two links. */
    @Volatile var relayMode = false
        private set
    @Volatile var session: Relay.Session? = null
        private set
    val history = ArrayList<Relay.Session>()
    @Volatile var lastUpstreamOffer: Market.Offer? = null
        private set
    @Volatile var lastEvent = ""

    /** A: the seller behind my link peer. */
    @Volatile var providerShort: String? = null
        private set
    @Volatile var providerPrice = 0
        private set
    @Volatile var providerFlags = 0
        private set
    /** C: the buyer behind my link peer. */
    @Volatile var relayedBuyer: String? = null
        private set

    private val keys = ConcurrentHashMap<String, ByteArray>()

    // ---- B: relay mode -----------------------------------------------------------------------------

    fun setRelayMode(on: Boolean) {
        if (relayMode == on) return
        relayMode = on
        if (!on) endSession("relay mode off", notify = true)
        DiagLog.i(tag, "RELAY MODE " + (if (on) "ON: this phone forwards between its two Wi-Fi links (no Internet of its own is used)" else "OFF"))
        onLinksChanged()
    }

    /** The relay's seller is reachable and selling: what to advertise to buyers. */
    val upstreamReady: Boolean get() = relayMode && hooks.upPeer() != null && (lastUpstreamOffer?.selling == true)

    /** Called by the node whenever either link changes or a peer's advert changes. */
    fun onLinksChanged() {
        val up = hooks.upPeer()
        if (up != null) hooks.upstreamOffer()?.let { if (it.sellerShort == up) lastUpstreamOffer = it }
        if (!relayMode) return
        val down = hooks.downPeer()
        val s = session
        if (s == null && up != null && down != null) startSession(up, down)
        else if (s != null && (up != s.upPeer || down != s.downPeer)) endSession(if (up != s.upPeer) "upstream link to prok-" + s.upPeer + " closed" else "downstream link to prok-" + s.downPeer + " closed", notify = true)
        hooks.onChanged()
    }

    private fun startSession(up: String, down: String) {
        val sellerKey = hooks.peerPub(up); val sellerFull = hooks.peerFullId(up)
        val buyerKey = hooks.peerPub(down); val buyerFull = hooks.peerFullId(down)
        if (sellerKey == null || sellerFull == null || buyerKey == null || buyerFull == null) { lastEvent = "cannot introduce: a key is missing"; DiagLog.w(tag, lastEvent); return }
        val offer = lastUpstreamOffer
        val s = Relay.Session(Crypto.randomBytes(4).toHex(), up, down, System.currentTimeMillis())
        session = s
        val sellerRec = Wire.identityRecord(sellerFull.hexToBytes(), sellerKey, hooks.peerName(up))
        val buyerRec = Wire.identityRecord(buyerFull.hexToBytes(), buyerKey, hooks.peerName(down))
        val okDown = hooks.sendDown(Wire.FRAME_RELAY_INFO, Relay.info(Relay.ROLE_UPSTREAM_SELLER, offer?.pricePerMb ?: 0, offer?.flags ?: 0, sellerRec))
        val okUp = hooks.sendUp(Wire.FRAME_RELAY_INFO, Relay.info(Relay.ROLE_DOWNSTREAM_BUYER, 0, 0, buyerRec))
        lastEvent = "session " + s.id + " introduced both sides (down " + okDown + ", up " + okUp + ")"
        DiagLog.i(tag, "RELAY SESSION " + s.id + " START: buyer prok-" + down + " <-> me <-> seller prok-" + up + " at " + (offer?.pricePerMb ?: 0) + " CFA/MB; introductions sent (to buyer " + okDown + ", to seller " + okUp + ")")
    }

    private fun endSession(reason: String, notify: Boolean) {
        val s = session ?: return
        s.end(reason)
        session = null
        history.add(0, s); if (history.size > 20) history.removeAt(history.size - 1)
        if (notify) {
            val gone = Relay.info(Relay.ROLE_PEER_GONE, 0, 0, null)
            if (hooks.downPeer() == s.downPeer) hooks.sendDown(Wire.FRAME_RELAY_INFO, gone)
            if (hooks.upPeer() == s.upPeer) hooks.sendUp(Wire.FRAME_RELAY_INFO, gone)
        }
        lastEvent = "session " + s.id + " ended: " + reason
        DiagLog.i(tag, "RELAY SESSION END: " + s.summary())
    }

    // ---- frames on either link ------------------------------------------------------------------------

    /** A FRAME_RELAY / FRAME_RELAY_INFO payload arrived on [from] from the authenticated [peerShort]. Read thread. */
    fun onRawFrame(from: Relay.Side, peerShort: String, type: Int, payload: ByteArray) {
        if (relayMode) {
            val s = session
            if (type != Wire.FRAME_RELAY) { DiagLog.w(tag, "relay frame type " + type + " from prok-" + peerShort + " ignored: a relay only forwards sealed frames"); return }
            if (s == null || (from == Relay.Side.DOWN && peerShort != s.downPeer) || (from == Relay.Side.UP && peerShort != s.upPeer)) { DiagLog.w(tag, "sealed frame from prok-" + peerShort + " dropped: no relay session with it"); return }
            val to = Relay.forwardTo(from)
            val ok = if (to == Relay.Side.UP) hooks.sendUp(Wire.FRAME_RELAY, payload) else hooks.sendDown(Wire.FRAME_RELAY, payload)
            if (ok) s.count(to, payload.size) else DiagLog.w(tag, "forward to " + to + " failed (" + payload.size + " bytes)")
            return
        }
        when (type) {
            Wire.FRAME_RELAY_INFO -> onInfo(peerShort, payload)
            Wire.FRAME_RELAY -> onSealed(peerShort, payload)
        }
    }

    private fun onInfo(peerShort: String, payload: ByteArray) {
        val i = Relay.parseInfo(payload) ?: run { DiagLog.w(tag, "malformed RELAY_INFO from prok-" + peerShort); return }
        when (i.role) {
            Relay.ROLE_UPSTREAM_SELLER -> {
                val rec = i.record!!
                hooks.saveIdentity(rec)
                providerShort = rec.shortId; providerPrice = i.pricePerMb; providerFlags = i.flags
                keys.remove(rec.shortId)
                DiagLog.i(tag, "INTRODUCED by relay prok-" + peerShort + ": seller prok-" + rec.shortId + " \"" + rec.name + "\" at " + i.pricePerMb + " CFA/MB (" + Tunnel.upstreamName(Market.upstreamOf(i.flags)) + "); my frames to it will be sealed end to end")
                hooks.onProviderIntroduced(rec.shortId, i.pricePerMb, i.flags)
            }
            Relay.ROLE_DOWNSTREAM_BUYER -> {
                val rec = i.record!!
                hooks.saveIdentity(rec)
                relayedBuyer = rec.shortId
                keys.remove(rec.shortId)
                DiagLog.i(tag, "INTRODUCED by relay prok-" + peerShort + ": buyer prok-" + rec.shortId + " \"" + rec.name + "\" behind it; its sealed frames will be treated as its own")
                hooks.onChanged()
            }
            Relay.ROLE_PEER_GONE -> {
                val far = relayedBuyer ?: providerShort ?: return
                DiagLog.i(tag, "relay prok-" + peerShort + " reports prok-" + far + " gone")
                clearFar()
                hooks.onRelayPeerGone(far, "relay reports the other side gone")
            }
        }
    }

    private fun onSealed(peerShort: String, payload: ByteArray) {
        val origin = Relay.peek(payload)?.originShort ?: run { DiagLog.w(tag, "malformed sealed frame from prok-" + peerShort); return }
        if (origin != relayedBuyer && origin != providerShort) { DiagLog.w(tag, "sealed frame from prok-" + origin + " via prok-" + peerShort + " dropped: not introduced"); return }
        val key = keyFor(origin) ?: run { DiagLog.w(tag, "no key for prok-" + origin); return }
        val bytes = Relay.open(key, payload) ?: run { DiagLog.w(tag, "sealed frame from prok-" + origin + " does NOT open (wrong key or tampered) - dropped"); return }
        val f = Tunnel.decode(bytes) ?: run { DiagLog.w(tag, "sealed frame from prok-" + origin + " carries a malformed tunnel frame"); return }
        hooks.onTunnelFromRelay(origin, f)
    }

    /** A or C: seal one tunnel frame for the far end. Null if the far end is unknown. */
    fun seal(farShort: String, type: Int, streamId: Int, data: ByteArray): ByteArray? {
        val key = keyFor(farShort) ?: return null
        return try { Relay.seal(key, identity.shortIdHex, Tunnel.encode(type, streamId, data)) } catch (e: Exception) { DiagLog.e(tag, "seal: " + e); null }
    }

    private fun keyFor(peerShort: String): ByteArray? = keys[peerShort] ?: run {
        val pub = hooks.peerPub(peerShort) ?: return null
        val full = hooks.peerFullId(peerShort) ?: return null
        val k = identity.agree(pub, Relay.keyInfo(identity.idBytes, full.hexToBytes()))
        keys[peerShort] = k; k
    }

    /** A or C: my link to the relay closed. Returns the far end that is now unreachable, if any. */
    fun onLinkToRelayClosed(): String? {
        val far = relayedBuyer ?: providerShort ?: return null
        clearFar(); return far
    }

    private fun clearFar() { relayedBuyer = null; providerShort = null; providerPrice = 0; providerFlags = 0; hooks.onChanged() }

    fun reset() { endSession("node stopped", notify = false); relayMode = false; clearFar(); keys.clear() }

    fun stateLine(): String = when {
        relayMode -> "RELAY MODE on" + (session?.let { " | session " + it.id + " prok-" + it.downPeer + " -> me -> prok-" + it.upPeer + ": to seller " + it.bytesToUp + " B, to buyer " + it.bytesToDown + " B, " + (it.durationMs / 1000) + " s" } ?: " | waiting for both links (down " + (hooks.downPeer() ?: "-") + ", up " + (hooks.upPeer() ?: "-") + ")") +
            (lastUpstreamOffer?.let { " | seller advert " + it.pricePerMb + " CFA/MB" } ?: " | no seller advert seen")
        providerShort != null -> "buying through relay prok-" + (hooks.downPeer() ?: "?") + " from seller prok-" + providerShort + " at " + providerPrice + " CFA/MB"
        relayedBuyer != null -> "selling through relay prok-" + (hooks.downPeer() ?: "?") + " to buyer prok-" + relayedBuyer
        else -> "no relay role"
    }
}
