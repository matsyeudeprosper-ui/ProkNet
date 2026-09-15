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
    /**
     * v0.9.13: strictly the Wi-Fi Direct interface. [isLocalLinkIface] is
     * wider on purpose (it also covers the method A hotspot), but a socket
     * that must belong to the Wi-Fi Direct endpoint may only be bound to a
     * `p2p...` interface.
     */
    fun isP2pIface(name: String?): Boolean = name != null && name.startsWith("p2p")

    // ---- v0.9.15: discovery belongs to admission, never to the data phase ---------------------------

    /**
     * Wi-Fi Direct peer discovery makes a single-radio phone LEAVE the group
     * channel to scan the social channels, and Android keeps a find running
     * for about two minutes once it is accepted.
     *
     * The v0.9.14 phone run is what forced this rule. Both phones were in the
     * group, both listeners were armed for the live membership, both dialled
     * with a correctly bound socket, and every SYN in BOTH directions timed
     * out while each phone logged `discoverPeers accepted` every thirty
     * seconds throughout. A socket cannot reach a radio that is off channel.
     *
     * The rule is tied to MEMBERSHIP, not to the group: a seller with an
     * empty group still has to be found by a buyer, and that admission path
     * is proven, so it is left exactly as it is. The moment somebody has
     * joined, admission is over and the radio belongs to the data plane.
     */
    fun discoveryWanted(want: Want, hasLiveMember: Boolean): Boolean = want != Want.NONE && !hasLiveMember

    /**
     * The link probe: a UDP echo between the two P2P addresses, so a dead
     * link is MEASURED instead of guessed at. It proves whether any IP packet
     * crosses the Wi-Fi Direct link, in which direction, and how fast.
     */
    const val PROBE_PORT = 47743
    const val PROBE_COUNT = 5
    const val PROBE_GAP_MS = 400L
    const val PROBE_TIMEOUT_MS = 1_200

    enum class LinkProof { NOT_RUN, NO_PACKET_CROSSED, ONE_WAY, ALIVE }

    fun linkProof(sent: Int, replies: Int, echoedHere: Int): LinkProof = when {
        sent == 0 -> LinkProof.NOT_RUN
        replies > 0 -> LinkProof.ALIVE
        echoedHere > 0 -> LinkProof.ONE_WAY
        else -> LinkProof.NO_PACKET_CROSSED
    }

    fun linkProofText(p: LinkProof): String = when (p) {
        LinkProof.NOT_RUN -> "the link was never probed"
        LinkProof.NO_PACKET_CROSSED -> "NO IP packet crossed the Wi-Fi Direct link in either direction"
        LinkProof.ONE_WAY -> "packets arrive here but our answers do not get back"
        LinkProof.ALIVE -> "the link carries IP packets both ways"
    }

    // ---- v0.9.13: the buyer dial window and the bounded failure -------------------------------------

    const val DIAL_ATTEMPTS = 6
    const val DIAL_TIMEOUT_MS = 4_000
    const val DIAL_GAP_MS = 1_500L

    /**
     * After the local group is formed, the whole transport has this long to
     * come up: the six dial attempts plus a margin for the owner to notice
     * the client and revalidate its listener. Then the purchase fails with a
     * sentence a customer can read, instead of spinning forever.
     */
    const val TRANSPORT_GIVE_UP_MS = 45_000L

    /** What the buyer says to itself when it gives up on the transport. */
    const val TRANSPORT_FAIL_REASON = "local link formed but no transport answer from the provider"

    enum class TransportStep { WAIT, FAIL_NO_TRANSPORT, DONE }

    /**
     * [msSinceGroup] counts from the moment the Wi-Fi Direct group formed on
     * this phone, not from the start of the purchase: the group is the point
     * from which a socket is supposed to be possible.
     */
    fun transportStep(groupFormed: Boolean, linkUp: Boolean, msSinceGroup: Long): TransportStep = when {
        linkUp -> TransportStep.DONE
        !groupFormed -> TransportStep.WAIT
        msSinceGroup >= TRANSPORT_GIVE_UP_MS -> TransportStep.FAIL_NO_TRANSPORT
        else -> TransportStep.WAIT
    }

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

    /**
     * v0.9.11: what a seller does with an admission request. It must never advertise the Wi-Fi
     * Direct way in without a group, and when one is asked for anyway the honest answers are
     * "rebuild it" (we do sell this way) or "no" (we do not).
     */
    enum class Admission { INVITE, REBUILD_GROUP, REFUSE }

    fun admission(sharingByP2p: Boolean, groupFormed: Boolean, isOwner: Boolean): Admission = when {
        groupFormed && isOwner -> Admission.INVITE
        sharingByP2p -> Admission.REBUILD_GROUP
        else -> Admission.REFUSE
    }

    /** The advert may only claim this way in while the group really exists. */
    fun advertiseP2p(sharingByP2p: Boolean, groupFormed: Boolean): Boolean = sharingByP2p && groupFormed

    /** The guest never waits in silence: it asks again, then tries itself, then gives up with a reason. */
    enum class GuestStep { WAIT, ASK_AGAIN, TRY_MYSELF, GIVE_UP, PAUSED, UNREACHABLE }

    /** v0.9.11: one request per ladder step. The phone test logged six in twenty seconds. */
    const val ASK_EVERY_MS = 10_000L
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
        GuestStep.PAUSED -> "the provider is not in range: waiting for it to be discovered again"
        GuestStep.UNREACHABLE -> "the provider never came back in range"
    }

    /** v0.9.10: a phone whose Bluetooth went deaf must not keep hammering a transport that is gone. */
    const val UNREACHABLE_GIVE_UP_MS = 90_000L

    /**
     * The ladder only advances while the control path (BLE) is really there.
     * Time spent out of range does not count towards the 45 s admission
     * timeout; it counts towards a separate, clear reachability failure.
     */
    fun guestTick(controlAvailable: Boolean, reachableMs: Long, unreachableMs: Long, groupFormed: Boolean, ownerVisible: Boolean): GuestStep = when {
        groupFormed -> GuestStep.WAIT
        !controlAvailable && unreachableMs >= UNREACHABLE_GIVE_UP_MS -> GuestStep.UNREACHABLE
        !controlAvailable -> GuestStep.PAUSED
        else -> guestStep(reachableMs, groupFormed, ownerVisible)
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

    // ---- the buyer joins by itself (v0.9.12) ------------------------------------------------------

    /**
     * Phone evidence: the seller owns a real group (GROUP OWNER, clients 0) and BLE sees the buyer
     * perfectly (prok-0f7d57b3, rssi -38), but in the OWNER's Wi-Fi Direct peer list the buyer is
     * `00:00:00:00:00:00 available`. Android anonymises it there, so owner-side name matching
     * cannot be the main path.
     *
     * The buyer, however, sees the seller correctly WITH its real P2P address. So the buyer asks
     * over BLE whether the group is ready, and then joins by itself.
     */
    enum class GroupStatus { READY, REBUILDING, NOT_AVAILABLE }

    fun groupStatus(sharingByP2p: Boolean, groupFormed: Boolean, isOwner: Boolean): GroupStatus = when {
        groupFormed && isOwner -> GroupStatus.READY
        sharingByP2p -> GroupStatus.REBUILDING
        else -> GroupStatus.NOT_AVAILABLE
    }

    /** Android hides a peer behind one of these when it will not name it. Never try to join one. */
    fun anonymous(address: String?): Boolean {
        val a = (address ?: "").trim().lowercase()
        return a.isEmpty() || a == "00:00:00:00:00:00" || a == "02:00:00:00:00:00" || a == "ff:ff:ff:ff:ff:ff"
    }

    /**
     * The buyer picks the seller out of ITS OWN peer list: by the name the seller sent over BLE,
     * else by the fact that it owns a group. Anonymised entries are never candidates.
     */
    fun pickSellerPeer(peers: List<PeerRef>, sellerName: String, groupOwners: Set<String> = emptySet()): String? {
        val real = peers.filter { !anonymous(it.address) }
        if (real.isEmpty()) return null
        matchPeer(real, sellerName)?.let { return it }
        real.firstOrNull { it.address in groupOwners }?.let { return it.address }
        return null
    }

    /** What the buyer does next, once it knows the seller's answer. */
    enum class JoinStep { ASK_STATUS, WAIT_PEER, CONNECT, RETRY_BUSY, WAIT_REBUILD, FAIL_NOT_AVAILABLE, GIVE_UP, DONE }

    const val CONNECT_ATTEMPTS = 4
    const val JOIN_GIVE_UP_MS = 60_000L

    /** Conservative, state aware: 3 s, 6 s, 12 s, 24 s. Never a rapid loop. */
    fun busyDelayMs(attempt: Int): Long {
        var ms = 3_000L
        repeat(minOf(maxOf(attempt - 1, 0), 3)) { ms *= 2 }
        return ms
    }

    fun joinStep(status: GroupStatus?, sellerPeerFound: Boolean, connectAttempts: Int, elapsedMs: Long, groupFormed: Boolean): JoinStep = when {
        groupFormed -> JoinStep.DONE
        status == GroupStatus.NOT_AVAILABLE -> JoinStep.FAIL_NOT_AVAILABLE
        elapsedMs >= JOIN_GIVE_UP_MS -> JoinStep.GIVE_UP
        status == null -> JoinStep.ASK_STATUS
        status == GroupStatus.REBUILDING -> JoinStep.WAIT_REBUILD
        !sellerPeerFound -> JoinStep.WAIT_PEER
        connectAttempts == 0 -> JoinStep.CONNECT
        connectAttempts < CONNECT_ATTEMPTS -> JoinStep.RETRY_BUSY
        else -> JoinStep.GIVE_UP
    }

    fun joinStepText(s: JoinStep): String = when (s) {
        JoinStep.ASK_STATUS -> "asking the provider whether its group is ready"
        JoinStep.WAIT_PEER -> "the provider group is ready: looking for it in this phone's Wi-Fi Direct list"
        JoinStep.CONNECT -> "joining the provider group"
        JoinStep.RETRY_BUSY -> "Android was busy: trying to join again"
        JoinStep.WAIT_REBUILD -> "the provider is rebuilding its group"
        JoinStep.FAIL_NOT_AVAILABLE -> "the provider is not sharing by Wi-Fi Direct"
        JoinStep.GIVE_UP -> "could not join the provider Wi-Fi Direct group"
        JoinStep.DONE -> "in the group"
    }

    /** Which transport a developer test asked for. Method A stays the default everywhere else. */
    enum class Method { HOTSPOT, WIFI_DIRECT }

    fun methodName(m: Method) = when (m) { Method.HOTSPOT -> "LocalOnlyHotspot (A)"; Method.WIFI_DIRECT -> "Wi-Fi Direct (B, experimental)" }
}
