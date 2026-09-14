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
 * Live relay glue (v0.9, handshake reworked in v0.9.1). Every decision is in
 * the pure [Relay] object; this class only executes them and keeps the
 * Android-side state.
 *
 *   B (relay):  relayMode on. DOWN link = its normal Wi-Fi link (it hosts A);
 *               UP link = its second, client-only link to the seller C. It
 *               answers every INTRO_REQUEST from its buyer, re-sends both
 *               introductions each time (idempotent), and forwards sealed
 *               FRAME_RELAY frames between the two links, counting sizes only.
 *   A (buyer):  asks its relay to introduce the seller, stores the
 *               self-certifying record, ACKs it, then talks to that seller
 *               through sealed frames.
 *   C (seller): is told which buyer is behind the relay, ACKs it, and treats
 *               that buyer's sealed frames as if it were on the link.
 *
 * On A and C "down" simply means "my one link to the relay".
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
        /** This phone's own signed identity record, for an INTRO_REQUEST. */
        fun myRecord(): ByteArray
        fun saveIdentity(rec: Wire.IdentityRecord)
        /** The seller's BLE advert as the relay last saw it (price, flags). */
        fun upstreamOffer(): Market.Offer?
        /** A or C: a tunnel frame that came sealed from [originShort] through the relay. */
        fun onTunnelFromRelay(originShort: String, frame: Tunnel.Frame)
        /** A or C: the far end behind the relay is gone. */
        fun onRelayPeerGone(farShort: String, reason: String)
        /** A: the relay introduced its seller; the buyer may start the contract now. */
        fun onProviderIntroduced(sellerShort: String, pricePerMb: Int, flags: Int)
        /** A: the relay answered that it has no seller available. */
        fun onIntroductionRefused(relayShort: String)
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
        DiagLog.i(tag, "RELAY MODE " + (if (on) "ON: this phone forwards between its two Wi-Fi links (no Internet of its own is used)" else "OFF"))
        onLinksChanged()
    }

    /** The relay's seller is reachable and selling: what to advertise to buyers. */
    val upstreamReady: Boolean get() = relayMode && hooks.upPeer() != null && (lastUpstreamOffer?.selling == true)

    /** Called by the node whenever either link changes or a peer's advert changes. */
    fun onLinksChanged() {
        val up = hooks.upPeer()
        if (up != null) hooks.upstreamOffer()?.let { if (it.sellerShort == up) lastUpstreamOffer = it }
        val s = session
        val down = hooks.downPeer()
        when (Relay.onLinks(relayMode, s?.upPeer, s?.downPeer, up, down)) {
            Relay.LinkChange.START -> startSession(up!!, down!!)
            Relay.LinkChange.RESTART -> { endSession("links changed", notify = true); startSession(up!!, down!!) }
            Relay.LinkChange.END -> endSession(if (up != s?.upPeer) "upstream link closed" else "downstream link closed", notify = true)
            Relay.LinkChange.KEEP, Relay.LinkChange.IDLE -> {}
        }
        hooks.onChanged()
    }

    private fun startSession(up: String, down: String) {
        val s = Relay.Session(Crypto.randomBytes(4).toHex(), up, down, System.currentTimeMillis())
        session = s
        DiagLog.i(tag, "RELAY SESSION " + s.id + " START: buyer prok-" + down + " <-> me <-> seller prok-" + up +
            " at " + (lastUpstreamOffer?.pricePerMb ?: 0) + " CFA/MB. Waiting for the buyer to ask (it may also be introduced now).")
        introduce(s, "session start")   // unsolicited first try; the buyer asks again whenever it is ready
    }

    /**
     * (Re)send both introductions. Idempotent and safe to repeat: each side
     * simply overwrites what it knows and ACKs. Returns false if a key is
     * missing (then the buyer's next request tries again).
     */
    private fun introduce(s: Relay.Session, why: String): Boolean {
        val sellerKey = hooks.peerPub(s.upPeer); val sellerFull = hooks.peerFullId(s.upPeer)
        val buyerKey = hooks.peerPub(s.downPeer); val buyerFull = hooks.peerFullId(s.downPeer)
        if (sellerKey == null || sellerFull == null || buyerKey == null || buyerFull == null) {
            lastEvent = "cannot introduce (" + why + "): a public key is missing"
            DiagLog.w(tag, lastEvent); hooks.onChanged(); return false
        }
        val offer = lastUpstreamOffer
        val sellerRec = Wire.identityRecord(sellerFull.hexToBytes(), sellerKey, hooks.peerName(s.upPeer))
        val buyerRec = Wire.identityRecord(buyerFull.hexToBytes(), buyerKey, hooks.peerName(s.downPeer))
        val okDown = hooks.sendDown(Wire.FRAME_RELAY_INFO, Relay.info(Relay.ROLE_UPSTREAM_SELLER, offer?.pricePerMb ?: 0, offer?.flags ?: 0, sellerRec))
        val okUp = hooks.sendUp(Wire.FRAME_RELAY_INFO, Relay.info(Relay.ROLE_DOWNSTREAM_BUYER, 0, 0, buyerRec))
        if (okDown) s.introductionsDown++
        if (okUp) s.introductionsUp++
        lastEvent = "introduced both sides (" + why + "): to buyer " + okDown + " #" + s.introductionsDown + ", to seller " + okUp + " #" + s.introductionsUp
        DiagLog.i(tag, "INTRODUCE (" + why + ") session " + s.id + ": seller prok-" + s.upPeer + " -> buyer prok-" + s.downPeer + " (written " + okDown + "), " +
            "buyer prok-" + s.downPeer + " -> seller prok-" + s.upPeer + " (written " + okUp + "); waiting for their ACKs")
        hooks.onChanged()
        return okDown && okUp
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

    // ---- A: ask to be introduced --------------------------------------------------------------------

    /** A: "introduce your seller to me". Safe to repeat; the relay answers every time. */
    fun requestIntroduction(): Boolean {
        val ok = hooks.sendDown(Wire.FRAME_RELAY_INFO, Relay.info(Relay.ROLE_INTRO_REQUEST, 0, 0, hooks.myRecord()))
        if (!ok) DiagLog.w(tag, "INTRO_REQUEST could not be written to the relay link")
        return ok
    }

    // ---- frames on either link ------------------------------------------------------------------------

    /** A FRAME_RELAY / FRAME_RELAY_INFO payload arrived on [from] from the authenticated [peerShort]. Read thread. */
    fun onRawFrame(from: Relay.Side, peerShort: String, type: Int, payload: ByteArray) {
        if (relayMode) {
            val s = session
            if (type == Wire.FRAME_RELAY_INFO) { onRelayInfoAtRelay(from, peerShort, payload); return }
            val to = Relay.forward(from, peerShort, s?.downPeer, s?.upPeer)
            if (to == null || s == null) { DiagLog.w(tag, "sealed frame from prok-" + peerShort + " dropped: no relay session with it"); return }
            val ok = if (to == Relay.Side.UP) hooks.sendUp(Wire.FRAME_RELAY, payload) else hooks.sendDown(Wire.FRAME_RELAY, payload)
            if (ok) s.count(to, payload.size) else DiagLog.w(tag, "forward to " + to + " failed (" + payload.size + " bytes)")
            return
        }
        when (type) {
            Wire.FRAME_RELAY_INFO -> onInfo(peerShort, payload)
            Wire.FRAME_RELAY -> onSealed(peerShort, payload)
        }
    }

    /** B: requests and acknowledgements from the two sides. */
    private fun onRelayInfoAtRelay(from: Relay.Side, peerShort: String, payload: ByteArray) {
        val i = Relay.parseInfo(payload) ?: run { DiagLog.w(tag, "malformed RELAY_INFO from prok-" + peerShort); return }
        when (i.role) {
            Relay.ROLE_INTRO_REQUEST -> {
                val answer = Relay.onIntroRequest(relayMode, hooks.downPeer(), hooks.upPeer(), lastUpstreamOffer?.selling == true, peerShort)
                DiagLog.i(tag, "INTRO_REQUEST from prok-" + peerShort + " -> " + answer)
                when (answer) {
                    Relay.Answer.INTRODUCE -> {
                        i.record?.let { hooks.saveIdentity(it) }      // the asker's own record, in case my copy is stale
                        onLinksChanged()                               // make sure a session exists for these two links
                        session?.let { introduce(it, "buyer asked") }
                    }
                    Relay.Answer.NO_UPSTREAM -> {
                        lastEvent = "told prok-" + peerShort + " that I have no seller right now"
                        hooks.sendDown(Wire.FRAME_RELAY_INFO, Relay.info(Relay.ROLE_NO_UPSTREAM, 0, 0, null))
                        hooks.onChanged()
                    }
                    Relay.Answer.NOT_MY_BUYER, Relay.Answer.NOT_A_RELAY -> DiagLog.w(tag, "INTRO_REQUEST from prok-" + peerShort + " ignored: " + answer)
                }
            }
            Relay.ROLE_INTRO_ACK -> {
                val s = session ?: return
                val who = i.record?.shortId
                if (from == Relay.Side.DOWN && peerShort == s.downPeer && who == s.upPeer) { s.ackedDown = true; DiagLog.i(tag, "buyer prok-" + peerShort + " confirmed the seller introduction") }
                else if (from == Relay.Side.UP && peerShort == s.upPeer && who == s.downPeer) { s.ackedUp = true; DiagLog.i(tag, "seller prok-" + peerShort + " confirmed the buyer introduction") }
                else DiagLog.w(tag, "INTRO_ACK from prok-" + peerShort + " for prok-" + who + " does not match the session")
                hooks.onChanged()
            }
            else -> DiagLog.w(tag, "relay frame " + Relay.roleName(i.role) + " from prok-" + peerShort + " ignored: a relay only answers requests and forwards sealed frames")
        }
    }

    /** A and C: introductions from my relay. */
    private fun onInfo(peerShort: String, payload: ByteArray) {
        val i = Relay.parseInfo(payload) ?: run { DiagLog.w(tag, "malformed RELAY_INFO from prok-" + peerShort); return }
        when (i.role) {
            Relay.ROLE_UPSTREAM_SELLER -> {
                val rec = i.record!!
                hooks.saveIdentity(rec)
                val repeat = providerShort == rec.shortId
                providerShort = rec.shortId; providerPrice = i.pricePerMb; providerFlags = i.flags
                keys.remove(rec.shortId)
                DiagLog.i(tag, "INTRODUCED by relay prok-" + peerShort + ": seller prok-" + rec.shortId + " \"" + rec.name + "\" at " + i.pricePerMb + " CFA/MB (" +
                    Tunnel.upstreamName(Market.upstreamOf(i.flags)) + ")" + (if (repeat) " [repeat]" else "") + "; my frames to it will be sealed end to end")
                hooks.sendDown(Wire.FRAME_RELAY_INFO, Relay.info(Relay.ROLE_INTRO_ACK, 0, 0, Wire.identityRecord(rec.id, rec.pub, rec.name)))
                hooks.onProviderIntroduced(rec.shortId, i.pricePerMb, i.flags)
            }
            Relay.ROLE_DOWNSTREAM_BUYER -> {
                val rec = i.record!!
                hooks.saveIdentity(rec)
                val repeat = relayedBuyer == rec.shortId
                relayedBuyer = rec.shortId
                keys.remove(rec.shortId)
                DiagLog.i(tag, "INTRODUCED by relay prok-" + peerShort + ": buyer prok-" + rec.shortId + " \"" + rec.name + "\" behind it" + (if (repeat) " [repeat]" else "") +
                    "; its sealed frames will be treated as its own")
                hooks.sendDown(Wire.FRAME_RELAY_INFO, Relay.info(Relay.ROLE_INTRO_ACK, 0, 0, Wire.identityRecord(rec.id, rec.pub, rec.name)))
                hooks.onChanged()
            }
            Relay.ROLE_NO_UPSTREAM -> {
                DiagLog.w(tag, "relay prok-" + peerShort + " answered: no seller available right now")
                hooks.onIntroductionRefused(peerShort)
            }
            Relay.ROLE_PEER_GONE -> {
                val far = relayedBuyer ?: providerShort ?: return
                DiagLog.i(tag, "relay prok-" + peerShort + " reports prok-" + far + " gone")
                clearFar()
                hooks.onRelayPeerGone(far, "the relay lost the other side")
            }
            else -> DiagLog.w(tag, "relay frame " + Relay.roleName(i.role) + " from prok-" + peerShort + " ignored: I am not a relay")
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
        relayMode -> "RELAY MODE on" + (session?.let { " | session " + it.id + " prok-" + it.downPeer + " -> me -> prok-" + it.upPeer + ": to seller " + it.bytesToUp + " B, to buyer " + it.bytesToDown + " B, " +
            "intro " + it.introductionsDown + "/" + it.introductionsUp + " ack " + (if (it.ackedDown) "buyer" else "-") + "/" + (if (it.ackedUp) "seller" else "-") + ", " + (it.durationMs / 1000) + " s" }
            ?: " | waiting for both links (down " + (hooks.downPeer() ?: "-") + ", up " + (hooks.upPeer() ?: "-") + ")") +
            (lastUpstreamOffer?.let { " | seller advert " + it.pricePerMb + " CFA/MB" } ?: " | no seller advert seen")
        providerShort != null -> "buying through relay prok-" + (hooks.downPeer() ?: "?") + " from seller prok-" + providerShort + " at " + providerPrice + " CFA/MB"
        relayedBuyer != null -> "selling through relay prok-" + (hooks.downPeer() ?: "?") + " to buyer prok-" + relayedBuyer
        else -> "no relay role"
    }
}
