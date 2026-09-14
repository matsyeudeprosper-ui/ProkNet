package net.prok.proknet.core

/**
 * Wi-Fi Direct experiment, pure part (v0.9.7).
 *
 * Phone evidence that started this: a seller connected to a home router
 * (Freebox) cannot create a LocalOnlyHotspot, so no buyer can reach it;
 * the same seller with Wi-Fi off and mobile data on works. Selling home,
 * shop or public Wi-Fi therefore needs a local link that can exist WHILE
 * the seller stays joined to that router. Wi-Fi Direct is the candidate.
 *
 *   home router
 *        |  (seller stays connected: this is what must survive)
 *   seller phone
 *        |  Wi-Fi Direct group
 *   buyer phone
 *
 * Method A (LocalOnlyHotspot) stays exactly as it is. This is method B, and
 * it is behind the developer screens until real phones prove it.
 *
 * Only the decisions live here: which side listens, whether it is worth
 * trying, and what the outcome means. The Android calls are in
 * transport/P2pLink; the authenticated link, the tunnel and the accounting
 * on top are the unchanged ProkNet layers.
 */
object P2pPlan {

    /** The TCP port the group owner listens on. Different from the hotspot path, so both can exist. */
    const val PORT = 47742

    /** Android decides who owns the group; we must work either way. */
    enum class Role { NONE, GROUP_OWNER, CLIENT }

    fun role(groupFormed: Boolean, isGroupOwner: Boolean): Role = when {
        !groupFormed -> Role.NONE
        isGroupOwner -> Role.GROUP_OWNER
        else -> Role.CLIENT
    }

    /** The group owner listens, the client dials it. Null = nothing to dial (we listen). */
    fun socketTarget(role: Role, groupOwnerAddress: String?): String? = when (role) {
        Role.CLIENT -> groupOwnerAddress?.takeIf { it.isNotEmpty() && it != "0.0.0.0" }
        else -> null
    }

    /** The adopted link is hosted by whoever owns the group. */
    fun isHost(role: Role): Boolean = role == Role.GROUP_OWNER

    enum class Ready { OK, NO_HARDWARE, WIFI_OFF, P2P_DISABLED }

    fun ready(supported: Boolean, wifiEnabled: Boolean, p2pEnabled: Boolean): Ready = when {
        !supported -> Ready.NO_HARDWARE
        !wifiEnabled -> Ready.WIFI_OFF
        !p2pEnabled -> Ready.P2P_DISABLED
        else -> Ready.OK
    }

    fun readyText(r: Ready): String = when (r) {
        Ready.OK -> "ready"
        Ready.NO_HARDWARE -> "this phone has no Wi-Fi Direct"
        Ready.WIFI_OFF -> "Wi-Fi is off: Wi-Fi Direct needs it on"
        Ready.P2P_DISABLED -> "Android reports Wi-Fi Direct disabled"
    }

    /**
     * What the experiment proved. The seller staying on its home network is
     * half the result: a P2P link that kills the STA connection is a failure
     * even if bytes flow.
     */
    enum class Verdict { NOT_RUN, NO_GROUP, GROUP_BUT_STA_LOST, LINK_UP_STA_KEPT, LINK_FAILED }

    fun verdict(groupFormed: Boolean, staKept: Boolean, linkAuthenticated: Boolean): Verdict = when {
        !groupFormed -> Verdict.NO_GROUP
        !staKept -> Verdict.GROUP_BUT_STA_LOST
        !linkAuthenticated -> Verdict.LINK_FAILED
        else -> Verdict.LINK_UP_STA_KEPT
    }

    fun verdictText(v: Verdict): String = when (v) {
        Verdict.NOT_RUN -> "not run yet"
        Verdict.NO_GROUP -> "no Wi-Fi Direct group formed"
        Verdict.GROUP_BUT_STA_LOST -> "group formed BUT the phone left its Wi-Fi network: useless for selling home Wi-Fi"
        Verdict.LINK_FAILED -> "group formed, home Wi-Fi kept, but the ProkNet link did not authenticate"
        Verdict.LINK_UP_STA_KEPT -> "link up while the phone stayed on its Wi-Fi network"
    }

    /**
     * A Wi-Fi Direct interface must never be chosen as the seller's upstream:
     * it is a local link with no Internet behind it. Names look like
     * `p2p-wlan0-0` or `p2p0`.
     */
    fun isLocalLinkIface(name: String?): Boolean {
        val n = (name ?: "").lowercase()
        return n.startsWith("p2p") || n.startsWith("ap") || n.startsWith("swlan") || n.contains("softap")
    }

    // ---- deterministic lifecycle (v0.9.8) ---------------------------------------------------------

    /**
     * v0.9.7 left state behind: after STOP and a later BUY a phone still
     * showed `group ssid=DIRECT-...`, `p2p0=192.168.49.1` and a listening
     * server, because the cleanup was fired and forgotten while the local
     * state was wiped at once. Every role change now walks these steps IN
     * ORDER and only moves on when Android has answered.
     */
    enum class Step { CANCEL_CONNECT, STOP_DISCOVERY, CLOSE_SOCKETS, REMOVE_GROUP, DONE }

    val CLEANUP_ORDER = listOf(Step.CANCEL_CONNECT, Step.STOP_DISCOVERY, Step.CLOSE_SOCKETS, Step.REMOVE_GROUP, Step.DONE)

    fun nextStep(s: Step): Step {
        val i = CLEANUP_ORDER.indexOf(s)
        return if (i < 0 || i >= CLEANUP_ORDER.size - 1) Step.DONE else CLEANUP_ORDER[i + 1]
    }

    /** What the developer asked for. A cleanup always runs first, whatever the phone was doing. */
    enum class Want { NONE, SELL, BUY }

    enum class Stage { IDLE, CLEANING, DISCOVERING, CREATING_GROUP, GROUP_OWNER, CLIENT, FAILED }

    fun stageName(s: Stage): String = when (s) {
        Stage.IDLE -> "IDLE"; Stage.CLEANING -> "CLEANING"; Stage.DISCOVERING -> "DISCOVERING"
        Stage.CREATING_GROUP -> "CREATING GROUP"; Stage.GROUP_OWNER -> "GROUP OWNER"; Stage.CLIENT -> "CLIENT"; Stage.FAILED -> "FAILED"
    }

    /** Everything a user can see about the P2P side. After a cleanup it must be [clean]. */
    class View(
        val stage: Stage, val want: Want, val step: Step, val role: Role,
        val groupFormed: Boolean, val groupInfo: String, val socketInfo: String, val peers: Int,
    ) {
        val clean: Boolean get() = !groupFormed && role == Role.NONE && groupInfo.isEmpty() && socketInfo.isEmpty() && peers == 0
        fun describe(): String = stageName(stage) + " want=" + want + " step=" + step + " role=" + role +
            " group=" + groupInfo.ifEmpty { "none" } + " socket=" + socketInfo.ifEmpty { "none" } + " peers=" + peers
    }

    /**
     * The pure lifecycle the Android layer executes. It owns every visible
     * field, so nothing can be cleared early or left behind: the group only
     * disappears when Android confirms REMOVE_GROUP, the listening socket
     * only when it is really closed.
     */
    class Life {
        var stage = Stage.IDLE; private set
        var want = Want.NONE; private set
        var step = Step.DONE; private set
        var role = Role.NONE; private set
        var groupFormed = false; private set
        var groupInfo = ""; private set
        var socketInfo = ""; private set
        var peers = 0; private set

        fun view() = View(stage, want, step, role, groupFormed, groupInfo, socketInfo, peers)

        /** A role change: clean first, always, even when this phone believes it is idle. */
        fun start(w: Want): Step { want = w; stage = Stage.CLEANING; step = Step.CANCEL_CONNECT; return step }

        /** STOP is the same walk with nothing to start afterwards. */
        fun stop(): Step = start(Want.NONE)

        /** Android confirmed [s] (or timed out). Returns the next step; DONE means the cleanup is over. */
        fun done(s: Step): Step {
            if (stage != Stage.CLEANING || s != step || s == Step.DONE) return step
            when (s) {
                Step.STOP_DISCOVERY -> peers = 0
                Step.CLOSE_SOCKETS -> socketInfo = ""
                Step.REMOVE_GROUP -> { groupFormed = false; groupInfo = ""; role = Role.NONE }
                else -> {}
            }
            step = nextStep(s)
            if (step == Step.DONE) stage = when (want) {
                Want.SELL -> Stage.CREATING_GROUP
                Want.BUY -> Stage.DISCOVERING
                Want.NONE -> Stage.IDLE
            }
            return step
        }

        val cleaning: Boolean get() = stage == Stage.CLEANING

        fun onPeers(n: Int) { if (!cleaning) peers = n }

        fun onGroup(formed: Boolean, owner: Boolean, info: String) {
            if (cleaning) return                       // a broadcast during cleanup never resurrects the old group
            groupFormed = formed
            groupInfo = if (formed) info else ""
            role = role(formed, owner)
            if (formed) stage = if (owner) Stage.GROUP_OWNER else Stage.CLIENT
        }

        fun onSocket(info: String) { if (!cleaning) socketInfo = info }

        fun fail() { if (!cleaning) stage = Stage.FAILED }
    }

    // ---- who invites whom (v0.9.9) ----------------------------------------------------------------

    /**
     * Phone evidence from v0.9.8: the seller's group forms, it stays on the
     * Freebox, it sees the buyer in its peer list, but `clients` stays 0.
     * The buyer taps, `connect()` is accepted, the seller turns "invited",
     * and no group ever forms on the buyer.
     *
     * That is the wrong direction. A phone that already OWNS a group cannot
     * join another one, so the invitation must travel the other way: the
     * group owner invites the guest, and the guest only has to be
     * discoverable and wait.
     */
    enum class Join { OWNER_INVITES, GUEST_WAITS }

    fun joinRole(iOwnAGroup: Boolean): Join = if (iOwnAGroup) Join.OWNER_INVITES else Join.GUEST_WAITS

    /** The guest never waits in silence: it asks again, then tries itself, then gives up with a reason. */
    enum class GuestStep { WAIT, ASK_AGAIN, TRY_MYSELF, GIVE_UP }

    const val INVITE_ASK_AGAIN_MS = 12_000L
    const val INVITE_TRY_SELF_MS = 24_000L
    const val INVITE_GIVE_UP_MS = 45_000L

    fun guestStep(elapsedMs: Long, groupFormed: Boolean, ownerVisible: Boolean): GuestStep = when {
        groupFormed -> GuestStep.WAIT                                   // we are in, nothing to do
        elapsedMs >= INVITE_GIVE_UP_MS -> GuestStep.GIVE_UP
        elapsedMs >= INVITE_TRY_SELF_MS && ownerVisible -> GuestStep.TRY_MYSELF
        elapsedMs >= INVITE_ASK_AGAIN_MS -> GuestStep.ASK_AGAIN
        else -> GuestStep.WAIT
    }

    fun guestStepText(s: GuestStep): String = when (s) {
        GuestStep.WAIT -> "waiting for the provider to invite this phone"
        GuestStep.ASK_AGAIN -> "asking the provider again"
        GuestStep.TRY_MYSELF -> "the provider has not invited us: trying to join its group directly"
        GuestStep.GIVE_UP -> "the provider could not bring this phone into its Wi-Fi Direct group"
    }

    /** One discovered Wi-Fi Direct peer, as the owner sees it. */
    class PeerRef(val name: String, val address: String)

    /**
     * Android hides a phone's OWN Wi-Fi Direct MAC since Android 10, so a
     * buyer cannot tell the seller its address; it sends its device NAME
     * over the existing BLE channel and the seller matches it here.
     */
    fun matchPeer(peers: List<PeerRef>, wantedName: String): String? {
        val want = wantedName.trim()
        if (want.isEmpty()) return null
        peers.firstOrNull { it.name == want }?.let { return it.address }
        peers.firstOrNull { it.name.equals(want, ignoreCase = true) }?.let { return it.address }
        peers.firstOrNull { it.name.contains(want, ignoreCase = true) || want.contains(it.name, ignoreCase = true) }?.let { return it.address }
        return null
    }

    /** Which transport a developer test asked for. Method A stays the default everywhere else. */
    enum class Method { HOTSPOT, WIFI_DIRECT }

    fun methodName(m: Method) = when (m) { Method.HOTSPOT -> "LocalOnlyHotspot (A)"; Method.WIFI_DIRECT -> "Wi-Fi Direct (B, experimental)" }
}
