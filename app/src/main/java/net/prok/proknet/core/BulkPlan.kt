package net.prok.proknet.core

/**
 * v0.10.0: **Bluetooth bulk Internet, the pure part.**
 *
 * Wi-Fi Direct is archived with its evidence: the OUKITEL forms a group and
 * keeps its Freebox but its IP path is one way; the OnePlus never forms one.
 * So the local link between the two phones moves to Bluetooth, which already
 * carries the control channel and never asks Android for a hotspot or a
 * group.
 *
 * BLE GATT stays what it is, control and store-carry-forward. The bulk
 * bytes go over a Bluetooth L2CAP connection-oriented channel: the provider
 * listens and gets a dynamic PSM, tells the customer over the encrypted BLE
 * control channel, the customer connects to that exact peer, and the
 * existing signed ProkNet handshake runs on the socket. Nothing above the
 * link changes.
 *
 * This file is the negotiation and the link lifecycle, testable on the JVM:
 * who may do what, in which state, with which session, for how long.
 */
object BulkPlan {

    /** The technology carried in a BULK_OFFER. */
    const val TECH_L2CAP = 1

    /**
     * v0.10.1: **how a provider serves a customer's local link.**
     *
     * v0.10.0 claimed the home-Wi-Fi provider stays on its Wi-Fi and serves
     * over Bluetooth, and then `setSelling` still ran the hotspot capability
     * probe and, on a refusal, created a Wi-Fi Direct group. So the runtime
     * did not obey the architecture. This is the one rule that decides it,
     * and Wi-Fi Direct is not one of its answers: it is archived, chosen only
     * by explicit developer controls, never here.
     *
     * A provider on mobile data keeps the proven LocalOnlyHotspot. A provider
     * on home Wi-Fi with Bluetooth serves over Bluetooth and never touches the
     * Wi-Fi radio. A provider on home Wi-Fi without Bluetooth has no automatic
     * consumer path: NONE, reported honestly, not a silent Wi-Fi Direct group.
     */
    enum class SellerAccessPath { BLUETOOTH_BULK, HOTSPOT, NONE }

    fun sellerAccessPath(upstreamIsWifi: Boolean, bulkSupported: Boolean, bluetoothOn: Boolean): SellerAccessPath = when {
        !upstreamIsWifi -> SellerAccessPath.HOTSPOT
        bulkSupported && bluetoothOn -> SellerAccessPath.BLUETOOTH_BULK
        else -> SellerAccessPath.NONE
    }

    /** Does this path need the hotspot capability probe? Only the hotspot one does. */
    fun needsHotspotProbe(p: SellerAccessPath): Boolean = p == SellerAccessPath.HOTSPOT

    fun accessPathText(p: SellerAccessPath): String = when (p) {
        SellerAccessPath.BLUETOOTH_BULK -> "serving over Bluetooth; staying on the home Wi-Fi, the Wi-Fi radio is not touched"
        SellerAccessPath.HOTSPOT -> "serving over a Wi-Fi hotspot (mobile-data upstream)"
        SellerAccessPath.NONE -> "no automatic local link: home Wi-Fi upstream and Bluetooth is off or unsupported"
    }

    enum class Phase { IDLE, REQUESTED, LISTENING, OFFERED, CONNECTING, AUTH, UP, FAILED }
    enum class Side { NONE, HOST, CLIENT }

    /** Every stage is bounded. */
    const val OFFER_TIMEOUT_MS = 20_000L        // customer waits for BULK_OFFER after BULK_REQUEST
    const val ACCEPT_TIMEOUT_MS = 30_000L       // provider waits for the customer to connect to its PSM
    const val CONNECT_TIMEOUT_MS = 20_000L      // customer's socket connect
    const val AUTH_TIMEOUT_MS = 15_000L         // the signed handshake
    const val PROBE_TIMEOUT_MS = 40_000L        // both 1 MB directions

    data class State(
        val phase: Phase,
        val side: Side,
        val session: Int,
        val peer: String,
        val psm: Int,
        val authenticated: Boolean,
        val error: String,
    ) {
        val active: Boolean get() = phase != Phase.IDLE && phase != Phase.FAILED
        fun describe(): String = phase.toString() + (if (side != Side.NONE) " as " + side else "") +
            (if (session != 0) " session " + sessionHex(session) else "") +
            (if (peer.isNotEmpty()) " with prok-" + peer else "") +
            (if (psm != 0) " psm " + psm else "") +
            (if (authenticated) " authenticated" else "") +
            (if (error.isNotEmpty()) " | " + error else "")
    }

    val IDLE = State(Phase.IDLE, Side.NONE, 0, "", 0, false, "")

    fun sessionHex(s: Int): String = Integer.toHexString(s and 0x7fffffff)

    // ---- the customer ----------------------------------------------------------------------------------

    /** BUY pressed: one fresh session token per purchase. */
    fun request(s: State, session: Int, peer: String): State =
        if (s.active) s else State(Phase.REQUESTED, Side.CLIENT, session, peer, 0, false, "")

    /**
     * A BULK_OFFER arrived. It is only ours if the session AND the peer
     * match; anything else is a stale or foreign offer and changes nothing.
     */
    fun offerReceived(s: State, session: Int, peer: String, tech: Int, psm: Int): State = when {
        s.phase != Phase.REQUESTED -> s
        s.session != session || s.peer != peer -> s
        tech != TECH_L2CAP -> s.copy(phase = Phase.FAILED, error = "unsupported bulk technology " + tech)
        psm <= 0 -> s.copy(phase = Phase.FAILED, error = "offer carried no PSM")
        else -> s.copy(phase = Phase.CONNECTING, psm = psm)
    }

    fun isOurOffer(s: State, session: Int, peer: String): Boolean =
        s.phase == Phase.REQUESTED && s.session == session && s.peer == peer

    // ---- the provider ------------------------------------------------------------------------------------

    /** A BULK_REQUEST arrived and the listener is open with this PSM. */
    fun listening(s: State, session: Int, peer: String, psm: Int): State =
        if (s.active && s.phase != Phase.LISTENING) s
        else State(Phase.LISTENING, Side.HOST, session, peer, psm, false, "")

    // ---- both ---------------------------------------------------------------------------------------------

    /** A socket exists. Nothing is trusted yet. */
    fun socketConnected(s: State): State = when (s.phase) {
        Phase.CONNECTING, Phase.LISTENING -> s.copy(phase = Phase.AUTH)
        else -> s
    }

    /** The signed handshake verified [peer]. It must be the peer we negotiated with. */
    fun authenticated(s: State, peer: String): State = when {
        s.phase != Phase.AUTH -> s
        s.peer.isNotEmpty() && s.peer != peer -> s.copy(phase = Phase.FAILED, error = "authenticated prok-" + peer + " but negotiated with prok-" + s.peer)
        else -> s.copy(phase = Phase.UP, peer = peer, authenticated = true)
    }

    /** May tunnel bytes flow? Only on an authenticated, up link, to that peer. */
    fun mayCarry(s: State, peer: String): Boolean = s.phase == Phase.UP && s.authenticated && s.peer == peer

    fun failed(s: State, why: String): State = if (s.phase == Phase.IDLE) s else s.copy(phase = Phase.FAILED, error = why, authenticated = false)

    fun reset(s: State): State = IDLE

    /** How long the current phase may last. */
    fun timeoutMs(p: Phase): Long = when (p) {
        Phase.REQUESTED -> OFFER_TIMEOUT_MS
        Phase.LISTENING -> ACCEPT_TIMEOUT_MS
        Phase.CONNECTING -> CONNECT_TIMEOUT_MS
        Phase.AUTH -> AUTH_TIMEOUT_MS
        else -> 0L
    }

    /** A timer fired for [phase] and session [session]: does it still apply? */
    fun timerApplies(s: State, phase: Phase, session: Int): Boolean = s.phase == phase && s.session == session

    fun timeoutReason(p: Phase): String = when (p) {
        Phase.REQUESTED -> "the provider did not offer a Bluetooth bulk channel"
        Phase.LISTENING -> "the customer never connected to the Bluetooth channel"
        Phase.CONNECTING -> "the Bluetooth channel could not be connected"
        Phase.AUTH -> "the signed handshake did not complete over Bluetooth"
        else -> "timeout"
    }

    /** Which phase word the consumer screen reads. */
    fun buyPhase(s: State): String = when (s.phase) {
        Phase.IDLE -> "FINDING"
        Phase.REQUESTED, Phase.LISTENING, Phase.OFFERED -> "FINDING"
        Phase.CONNECTING -> "JOINING the Bluetooth channel"
        Phase.AUTH -> "AUTH"
        Phase.UP -> "AUTH"
        Phase.FAILED -> "DOWN (" + s.error + ")"
    }

    // ---- the bulk probe: real bytes both ways before anything is believed ----------------------------------

    const val PROBE_BYTES = 1_048_576
    const val PROBE_CHUNK = 8_192

    data class Direction(val bytes: Long, val ms: Long, val ok: Boolean) {
        fun kbps(): Long = if (ms <= 0) 0 else bytes * 1000 / 1024 / ms
        fun describe(): String = if (!ok) "FAILED after " + bytes + " B" else "%,d B OK, %d KB/s".format(bytes, kbps())
    }

    enum class Verdict { NOT_RUN, BIDIRECTIONAL, ONLY_A_TO_B, ONLY_B_TO_A, NEITHER }

    fun verdict(aToB: Direction?, bToA: Direction?): Verdict = when {
        aToB == null && bToA == null -> Verdict.NOT_RUN
        aToB?.ok == true && bToA?.ok == true -> Verdict.BIDIRECTIONAL
        aToB?.ok == true -> Verdict.ONLY_A_TO_B
        bToA?.ok == true -> Verdict.ONLY_B_TO_A
        else -> Verdict.NEITHER
    }

    fun verdictText(v: Verdict): String = when (v) {
        Verdict.NOT_RUN -> "not run"
        Verdict.BIDIRECTIONAL -> "BIDIRECTIONAL"
        Verdict.ONLY_A_TO_B -> "only buyer -> seller carried bytes"
        Verdict.ONLY_B_TO_A -> "only seller -> buyer carried bytes"
        Verdict.NEITHER -> "NEITHER direction carried the payload"
    }

    /** The Internet stack may start only on a link that carried the payload both ways. */
    fun probePassed(v: Verdict): Boolean = v == Verdict.BIDIRECTIONAL

    const val PROBE_FAIL_REASON = "the Bluetooth bulk link did not carry bytes in both directions"
}
