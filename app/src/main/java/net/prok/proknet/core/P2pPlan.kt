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
     * v0.9.23: **who owns the Wi-Fi Direct group.**
     *
     * Production is SELLER_GROUP_OWNER: the provider creates the group and
     * the customer joins it. Every clean measurement so far says that
     * topology cannot carry IP on these two phones while the provider stays
     * on its home Wi-Fi, on the default band AND forced to 2.4 GHz.
     *
     * BUYER_GROUP_OWNER is the controlled experiment: the customer owns the
     * group and the provider joins it as a client while keeping its Freebox
     * connection. Who owns the group does not change who sells the Internet.
     */
    enum class Topology { SELLER_GROUP_OWNER, BUYER_GROUP_OWNER }

    fun topologyName(t: Topology): String = when (t) {
        Topology.SELLER_GROUP_OWNER -> "SELLER_GROUP_OWNER"
        Topology.BUYER_GROUP_OWNER -> "BUYER_GROUP_OWNER"
    }

    fun topologyText(t: Topology): String = when (t) {
        Topology.SELLER_GROUP_OWNER -> "the provider owns the Wi-Fi Direct group and the customer joins it"
        Topology.BUYER_GROUP_OWNER -> "the customer owns the Wi-Fi Direct group and the provider joins it, keeping its home Wi-Fi"
    }

    /** Does THIS phone own the group, given the topology and whether it sells? */
    fun ownsGroup(t: Topology, providing: Boolean): Boolean =
        if (t == Topology.SELLER_GROUP_OWNER) providing else !providing

    // ---- v0.9.25: creating a group is its own stage, with its own clock ---------------------------------

    /**
     * After `createGroup()` is ACCEPTED, Android is allowed to report
     * `groupFormed = false` while the group is still being created. The
     * v0.9.24 run read that as "the group is gone", started peer discovery
     * against Android's own creation, got `discoverPeers failed: BUSY` in a
     * loop, and the group never formed.
     */
    const val GROUP_FORMATION_TIMEOUT_MS = 15_000L
    const val GROUP_CREATE_FAIL_REASON = "Android accepted createGroup three times but no Wi-Fi Direct group formed"

    enum class Creation { HOLD, GONE }

    /** What a `formed=false` connection change means right now. */
    fun onFormedFalse(stage: Stage, createAccepted: Boolean): Creation =
        if (stage == Stage.CREATING_GROUP && createAccepted) Creation.HOLD else Creation.GONE

    enum class Formation { FORMED, RETRY, FAIL, IGNORE }

    /** The formation clock ran out after an accepted `createGroup()`. */
    fun onFormationTimeout(stage: Stage, formed: Boolean, attempt: Int, maxAttempts: Int): Formation = when {
        formed -> Formation.FORMED
        stage != Stage.CREATING_GROUP -> Formation.IGNORE
        attempt < maxAttempts -> Formation.RETRY
        else -> Formation.FAIL
    }

    /**
     * v0.9.25: for a customer that owns the group, the admission search only
     * starts once that group exists. Creation time and search time are two
     * different stages, and the group owner has to exist before anybody can
     * find it.
     */
    fun searchedMs(topology: Topology, providing: Boolean, groupFormedAt: Long, purchaseStartedMs: Long, now: Long): Long =
        if (ownsGroup(topology, providing)) (if (groupFormedAt > 0L) now - groupFormedAt else 0L) else now - purchaseStartedMs

    /** The provider-group readiness ladder belongs to SELLER_GROUP_OWNER only. */
    fun asksProviderGroup(topology: Topology, providing: Boolean): Boolean = !ownsGroup(topology, providing)

    // ---- v0.9.19: which band to ask for ------------------------------------------------------------

    /** 0 = let Android choose, 2 = ask for 2.4 GHz, 5 = ask for 5 GHz. */
    fun groupBand(staFreqMhz: Int): Int = if (staFreqMhz >= 5_000) 2 else 0

    fun groupBandText(band: Int, staFreqMhz: Int): String = when (band) {
        2 -> "asking for a 2.4 GHz group: this phone's own Wi-Fi is on " + ShareCheck.describe(staFreqMhz) +
            ", so a 5 GHz group would share one channel with it"
        5 -> "asking for a 5 GHz group"
        else -> "letting Android choose the group band"
    }

    /**
     * The link probe: a UDP echo between the two P2P addresses, so a dead
     * link is MEASURED instead of guessed at. It proves whether any IP packet
     * crosses the Wi-Fi Direct link, in which direction, and how fast.
     */
    /** v0.9.19: a Wi-Fi Direct network name must start with DIRECT-. */
    const val GROUP_NAME = "DIRECT-prok"

    const val PROBE_PORT = 47743
    const val PROBE_COUNT = 5
    const val PROBE_GAP_MS = 400L
    const val PROBE_TIMEOUT_MS = 1_200

    /** v0.9.16: the probe tells unicast and broadcast apart, because the answer is different. */
    const val PROBE_UNICAST = "U"
    const val PROBE_BROADCAST = "B"

    enum class LinkProof { NOT_RUN, NO_PACKET_CROSSED, ONE_WAY, BROADCAST_ONLY, ALIVE }

    /**
     * [unicastReplies] and [broadcastReplies] are answers that came BACK to
     * this phone; [echoedHere] is how many of the other side's probes WE
     * answered. The v0.9.15 run produced two different verdicts on the two
     * phones, which is exactly how a one way link looks from each end.
     */
    fun linkProof(sent: Int, unicastReplies: Int, broadcastReplies: Int, echoedHere: Int): LinkProof = when {
        sent == 0 -> LinkProof.NOT_RUN
        unicastReplies > 0 -> LinkProof.ALIVE
        broadcastReplies > 0 -> LinkProof.BROADCAST_ONLY
        echoedHere > 0 -> LinkProof.ONE_WAY
        else -> LinkProof.NO_PACKET_CROSSED
    }

    fun linkProofText(p: LinkProof): String = when (p) {
        LinkProof.NOT_RUN -> "the link was never probed"
        LinkProof.NO_PACKET_CROSSED -> "NO IP packet crossed the Wi-Fi Direct link in either direction"
        LinkProof.ONE_WAY -> "packets arrive here but our answers do not get back"
        LinkProof.BROADCAST_ONLY -> "only BROADCAST crosses: the two phones cannot address each other directly"
        LinkProof.ALIVE -> "the link carries IP packets both ways"
    }

    /** The broadcast address of a /24, which is what a Wi-Fi Direct group always is. */
    fun broadcastOf(localAddress: String): String {
        val i = localAddress.lastIndexOf('.')
        return if (i <= 0) "" else localAddress.substring(0, i) + ".255"
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

    /**
     * v0.9.17: the phase word the CONSUMER screen must read during a Wi-Fi
     * Direct purchase.
     *
     * The phone run showed "Connexion perdue" the instant the user pressed
     * SE CONNECTER, before anything had been tried. The screen was reading
     * the hotspot transport, which was still sitting in `DOWN ... could not
     * reach the host (10.168.138.1)` from an attempt minutes earlier, and
     * `ProductState.buyer` turns any phase starting with DOWN into LOST.
     * A purchase must be judged by the transport it actually uses.
     */
    fun buyPhase(stage: Stage, groupFormed: Boolean, planeUsable: Boolean, linked: Boolean): String = when {
        linked -> "AUTH"
        planeUsable -> "TCP"
        groupFormed -> "JOINING the provider group"
        stage == Stage.FAILED -> "DOWN (the direct link failed)"
        else -> "FINDING"
    }

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
    /**
     * The provider's address in THIS phone's peer list, or null.
     *
     * v0.9.18: **only the provider that named itself over BLE.** There used
     * to be a fallback to "any peer that owns a group", for a provider too
     * old to send its name. The phone run showed what that costs: the
     * provider dropped out of the peer list for a few seconds and the
     * fallback dialled `DIRECT-FB-HP DeskJet 2700 series`, twice, burning
     * the ladder on a printer.
     *
     * A provider that is not in the list means WAIT. It is the same rule as
     * refusing to infer a buyer from `00:00:00:00:00:00`: this network does
     * not guess who it is talking to.
     */
    fun pickSellerPeer(peers: List<PeerRef>, sellerName: String): String? =
        matchPeer(peers.filter { !anonymous(it.address) }, sellerName)

    /** The name to show for an address, so a wrong target is obvious in the log. */
    fun peerName(peers: List<PeerRef>, address: String): String =
        peers.firstOrNull { it.address == address }?.name?.ifEmpty { address } ?: address

    /** What the buyer does next, once it knows the seller's answer. */
    enum class JoinStep { ASK_STATUS, WAIT_PEER, CONNECT, RETRY_BUSY, WAIT_REBUILD, FAIL_NOT_AVAILABLE, GIVE_UP, DONE }

    const val CONNECT_ATTEMPTS = 4

    /**
     * v0.9.18: how long an ACCEPTED `connect()` is given to produce a group
     * before another one is issued.
     *
     * The phone run re-dialled three seconds after Android had accepted the
     * first join, which made the framework answer BUSY to its own successor
     * and burned the whole ladder. Only a REFUSED connect uses the busy
     * backoff; an accepted one is simply waited for.
     */
    const val JOIN_ACCEPTED_WAIT_MS = 15_000L
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
