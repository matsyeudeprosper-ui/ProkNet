package net.prok.proknet.core

/**
 * v0.9.20: **admission is symmetric.**
 *
 * Two real phone runs, two opposite failures:
 *
 * ```
 * CASE A (v0.9.11)  the buyer saw the seller; the owner's peer list showed
 *                   the buyer as 00:00:00:00:00:00 and could not identify it
 * CASE B (v0.9.19)  the seller saw "OnePlus Nord CE 2 Lite 5G" at
 *                   1e:4f:f2:19:36:ce; the buyer saw 0 peers and never
 *                   attempted a join
 * ```
 *
 * A design that assumes one particular side can address the other fails half
 * the time on real Android. So the two phones exchange **what each can see**
 * over the BLE control channel, and the provider turns the two facts into one
 * plan that both sides obey:
 *
 * ```
 * buyer sees seller                  -> BUYER_CONNECT   (the proven path, preferred)
 * buyer blind, seller sees buyer     -> SELLER_INVITE
 * neither sees the other             -> WAIT, both keep looking, bounded
 * ```
 *
 * Two rules hold it together:
 *
 * - **Never guess.** A phone "sees" the other only when a peer's NAME matches
 *   the identity exchanged over BLE and its address is real. No group owner
 *   fallback, no anonymous address, no lone available peer.
 * - **One attempt owns admission.** Once a side has been told to act, the
 *   plan is held for [ATTEMPT_OWN_MS] so the other side can never start a
 *   competing association while a join is pending.
 */
object P2pAdmission {

    /** What one phone can see of the other in ITS OWN Wi-Fi Direct peer list. */
    data class Sight(val canSee: Boolean, val address: String, val name: String) {
        fun describe(): String =
            if (canSee) "can address \"" + name + "\" at " + address else "cannot address the other phone"
    }

    val BLIND = Sight(false, "", "")

    /**
     * Look for [wantedName], the name the other phone gave over BLE, and
     * nothing else. An unnamed peer, an anonymised address or somebody else
     * entirely is not the phone we are talking to.
     */
    fun look(peers: List<P2pPlan.PeerRef>, wantedName: String): Sight {
        if (wantedName.isBlank()) return BLIND
        val addr = P2pPlan.pickSellerPeer(peers, wantedName) ?: return BLIND
        return Sight(true, addr, P2pPlan.peerName(peers, addr))
    }

    /**
     * v0.9.23: the two sides are named by their WI-FI DIRECT role, not by who
     * sells, because v0.9.23 can run the group either way round.
     *
     * GUEST_CONNECT: the phone that does not own the group joins it.
     * OWNER_INVITE:  the phone that owns the group invites the other in.
     */
    enum class Plan { GUEST_CONNECT, OWNER_INVITE, WAIT }

    /**
     * The plan, from the two visibilities and nothing else.
     *
     * When BOTH can see each other, BUYER_CONNECT wins: it is the path that
     * has actually formed groups on these phones.
     */
    fun plan(guestSeesOwner: Boolean, ownerSeesGuest: Boolean): Plan = when {
        guestSeesOwner -> Plan.GUEST_CONNECT
        ownerSeesGuest -> Plan.OWNER_INVITE
        else -> Plan.WAIT
    }

    fun planName(p: Plan): String = when (p) {
        Plan.GUEST_CONNECT -> "GUEST_CONNECT"
        Plan.OWNER_INVITE -> "OWNER_INVITE"
        Plan.WAIT -> "WAIT"
    }

    fun planText(p: Plan): String = when (p) {
        Plan.GUEST_CONNECT -> "the guest can address the group owner, so the guest joins"
        Plan.OWNER_INVITE -> "only the group owner can address the guest, so the owner invites"
        Plan.WAIT -> "neither phone can address the other yet: both keep looking"
    }

    /** Which side holds the single admission attempt in flight. */
    enum class Owner { NOBODY, GUEST, OWNER }

    fun owner(p: Plan): Owner = when (p) {
        Plan.GUEST_CONNECT -> Owner.GUEST
        Plan.OWNER_INVITE -> Owner.OWNER
        Plan.WAIT -> Owner.NOBODY
    }

    /**
     * v0.9.22: **one clock, measured from the moment an association was
     * actually accepted.**
     *
     * Not from the start of the purchase. The phone run chose SELLER_INVITE
     * after thirty seconds of searching and failed two milliseconds later,
     * because the deadline it was judged against had already expired before
     * the attempt existed:
     *
     * ```
     * 19:10:36.476  JOIN PLAN = SELLER_INVITE
     * 19:10:36.478  the provider could see this phone, but the invitation did not complete
     * ```
     *
     * Android had put a confirmation dialog on both phones. The user was
     * reading it while ProkNet tore the attempt down. So the window has to be
     * long enough for a person to read a popup and tap Connect.
     */
    const val ASSOCIATION_TIMEOUT_MS = 40_000L

    /** Looking for each other, before any association has been accepted. */
    const val SEARCH_GIVE_UP_MS = 60_000L

    /** How often a phone re-reports what it can see while nothing is decided. */
    const val VISIBILITY_EVERY_MS = 8_000L

    /**
     * **Admission is over when somebody has JOINED, not when a group
     * exists.**
     *
     * v0.9.21, and it is the whole fix. A provider creates and owns its
     * Wi-Fi Direct group before any customer arrives, so `groupFormed` is
     * true from the moment it starts sharing. v0.9.20 used that as proof
     * that admission was complete, and the invitation it had just decided on
     * was therefore never sent:
     *
     * ```
     * 17:56:05  admission: the customer "OnePlus Nord CE 2 Lite 5G" cannot address me,
     *           and I can address it at 1e:4f:f2:19:36:ce -> SELLER_INVITE
     *           (role GROUP_OWNER, group formed, clients 0)
     * 17:56:05  INVITING ...   never printed
     * ```
     *
     * The truth is membership: `P2pDataPlane.Plane.hasMember`, which is a
     * client count above zero for an owner and "I joined" for a client.
     */
    fun keepOwner(current: Owner, sinceMs: Long, failed: Boolean, hasMember: Boolean): Boolean =
        current != Owner.NOBODY && !hasMember && !failed && sinceMs < ASSOCIATION_TIMEOUT_MS

    /**
     * v0.9.22: **is an accepted association still in flight on this phone?**
     *
     * This is the one truth that keeps peer discovery off. Android emits a
     * `groupFormed = false` connection change in the middle of its own join
     * choreography, and v0.9.21 read that as "the attempt is dead, start
     * looking again":
     *
     * ```
     * 18:54:58.447  connect accepted
     * 18:54:58.447  DISCOVERY off
     * 18:54:58.470  stopPeerDiscovery refused: BUSY
     * 18:54:58.472  connection formed=false
     * 18:54:58.474  starting peer discovery again      <- wrong
     * ```
     *
     * Scanning then ran through the whole data-path window. An attempt ends
     * when membership forms, when Android refuses it, or when its own clock
     * runs out. A transient callback is none of those.
     */
    fun associationPending(owner: Owner, startedAt: Long, now: Long, hasMember: Boolean, failed: Boolean): Boolean =
        owner != Owner.NOBODY && startedAt > 0L && !hasMember && !failed && (now - startedAt) < ASSOCIATION_TIMEOUT_MS

    /** Where a purchase is in the admission phase. */
    enum class Ladder { SEARCH, ASSOCIATING, MEMBER_JOINED, GIVE_UP }

    /**
     * Choosing a plan is not starting an attempt, and **membership ends the
     * admission phase completely.**
     *
     * v0.9.23. The v0.9.22 run joined the group at 19:38:15 and the ladder
     * kept counting the association clock it had started before that:
     *
     * ```
     * 19:38:15  formed=true role=CLIENT, DISCOVERY off, GROUP CHANNEL 2.4 GHz ch 6
     * 19:38:31  association in flight for 16s
     * 19:38:55  the provider could see this phone, but the invitation did not complete
     * ```
     *
     * The invitation had completed perfectly. What failed was the IP
     * transport, forty seconds later and one phase further on. Once
     * `hasMember` is true this returns MEMBER_JOINED forever, the admission
     * clock is cleared, and only the transport deadline can end the session.
     */
    fun ladder(associationStartedAt: Long, now: Long, searchedMs: Long, hasMember: Boolean): Ladder = when {
        hasMember -> Ladder.MEMBER_JOINED
        associationStartedAt > 0L ->
            if (now - associationStartedAt >= ASSOCIATION_TIMEOUT_MS) Ladder.GIVE_UP else Ladder.ASSOCIATING
        searchedMs >= SEARCH_GIVE_UP_MS -> Ladder.GIVE_UP
        else -> Ladder.SEARCH
    }

    /**
     * The plan to act on now. A pending attempt is never overtaken by a
     * newer decision, so the two sides cannot both start associating.
     */
    fun heldPlan(current: Plan?, currentOwner: Owner, sinceMs: Long, failed: Boolean, hasMember: Boolean, fresh: Plan): Plan =
        if (current != null && keepOwner(currentOwner, sinceMs, failed, hasMember)) current else fresh

    /** What the customer does on this tick. */
    enum class BuyerStep { REPORT_VISIBILITY, CONNECT, WAIT_FOR_INVITE, WAIT_DISCOVERY }

    /**
     * v0.9.21: a customer waiting for an invitation keeps reporting what it
     * can see. That report is what drives the provider's decision, so
     * stopping it would leave a failed invitation with nothing to retry it.
     * The plan is still held by the provider, so re-reporting cannot start a
     * competing attempt.
     */
    fun buyerStep(plan: Plan?, canSee: Boolean, reportedMsAgo: Long): BuyerStep = when {
        plan == Plan.GUEST_CONNECT && canSee -> BuyerStep.CONNECT
        reportedMsAgo >= VISIBILITY_EVERY_MS -> BuyerStep.REPORT_VISIBILITY
        plan == Plan.OWNER_INVITE -> BuyerStep.WAIT_FOR_INVITE
        else -> BuyerStep.WAIT_DISCOVERY
    }

    fun buyerStepText(s: BuyerStep): String = when (s) {
        BuyerStep.REPORT_VISIBILITY -> "telling the provider what this phone can see"
        BuyerStep.CONNECT -> "joining the provider group"
        BuyerStep.WAIT_FOR_INVITE -> "waiting for the provider to invite this phone"
        BuyerStep.WAIT_DISCOVERY -> "looking for the provider in this phone's Wi-Fi Direct list"
    }

    /**
     * May the provider send an invitation now?
     *
     * The state this is FOR is a provider holding an empty group:
     *
     * ```
     * role GROUP_OWNER, group formed, clients 0,
     * this phone can address the exact customer, plan SELLER_INVITE
     * ```
     *
     * It stops being allowed once a customer is really on the link, or while
     * an invitation is still pending.
     */
    fun mayInvite(plan: Plan, sellerSight: Sight, ownsGroup: Boolean, hasMember: Boolean, invitedMsAgo: Long): Boolean =
        plan == Plan.OWNER_INVITE && sellerSight.canSee && ownsGroup && !hasMember && invitedMsAgo >= ASSOCIATION_TIMEOUT_MS

    // ---- v0.9.24: which transport a purchase starts on --------------------------------------------------

    enum class BuyPath { LINK_UP, RELAY_INTRO, WIFI_DIRECT, HOTSPOT }

    /**
     * The v0.9.23 run never tested the reversed topology because of this
     * decision. A provider in BUYER_GROUP_OWNER mode drops its own group on
     * purpose, so it stops advertising `p2p` in its offer, and the customer
     * then fell back to the hotspot request. A customer that OWNS the group
     * must not wait for the provider to advertise one.
     */
    fun buyPath(topology: P2pPlan.Topology, offerP2p: Boolean, linkUp: Boolean, viaRelay: Boolean): BuyPath = when {
        linkUp -> if (viaRelay) BuyPath.RELAY_INTRO else BuyPath.LINK_UP
        topology == P2pPlan.Topology.BUYER_GROUP_OWNER -> BuyPath.WIFI_DIRECT
        offerP2p -> BuyPath.WIFI_DIRECT
        else -> BuyPath.HOTSPOT
    }

    /**
     * Provider side of the reversed topology: the one customer whose group
     * this phone is joining. The v0.9.23 run left it alive after the customer
     * had cancelled, and the provider kept reporting what it could see, every
     * few seconds, for minutes. It is cleared as one unit, on cancel, on stop,
     * and when a different customer arrives.
     */
    class GuestSession {
        @Volatile var peer: String = ""
        @Volatile var plan: Plan? = null
        @Volatile var visibilityAt: Long = 0L
        @Volatile var connectAt: Long = 0L
        val active: Boolean get() = peer.isNotEmpty()
        fun begin(peer: String) { clear(); this.peer = peer }
        fun clear() { peer = ""; plan = null; visibilityAt = 0L; connectAt = 0L }
        /** May the guest ladder run a tick for [peer]? */
        fun ticks(peer: String, providing: Boolean, linked: Boolean, hasMember: Boolean): Boolean =
            active && this.peer == peer && providing && !linked && !hasMember
    }

    /** Group owner side: the plan in force, who holds it, and the invitation clock. Reset as one unit. */
    class OwnerDecision {
        @Volatile var plan: Plan? = null
        @Volatile var owner: Owner = Owner.NOBODY
        @Volatile var at: Long = 0L
        @Volatile var invitedAt: Long = 0L
        @Volatile var failed: Boolean = false
        val clean: Boolean get() = plan == null && owner == Owner.NOBODY && at == 0L && invitedAt == 0L && !failed
        fun reset() { plan = null; owner = Owner.NOBODY; at = 0L; invitedAt = 0L; failed = false }
    }

    // ---- how a purchase ends, truthfully -------------------------------------------------------------

    /**
     * v0.9.23: the stage a session died in. Each one means something
     * different to the customer and to whoever reads the log.
     */
    enum class FailStage { NONE, GROUP_CREATE, SEARCH, ASSOCIATION, TRANSPORT, TUNNEL, INTERNET }

    /** Neither phone could ever address the other. */
    const val BLIND_FAIL_REASON = "neither phone could address the other over Wi-Fi Direct"
    /** The group owner could see the guest, and its invitation did not complete. */
    const val INVITE_FAIL_REASON = "the group owner could see this phone, but the Wi-Fi Direct invitation did not complete"
    /** The guest could see the owner, and its join did not complete. */
    const val JOIN_FAIL_REASON = "the guest could see the group owner, but the Wi-Fi Direct join did not complete"

    /**
     * Report the stage that actually failed. A planned attempt that did not
     * complete is never collapsed back into "neither could address the
     * other", and an attempt that DID complete is never blamed at all: once
     * membership exists the admission phase is over and cannot fail.
     */
    fun failReason(plan: Plan?, connectAttempts: Int): String = when {
        plan == Plan.OWNER_INVITE -> INVITE_FAIL_REASON
        plan == Plan.GUEST_CONNECT || connectAttempts > 0 -> JOIN_FAIL_REASON
        else -> BLIND_FAIL_REASON
    }

    /** Which stage a failure reason belongs to, for the log and the saved test record. */
    fun stageOf(reason: String): FailStage = when {
        reason.isEmpty() -> FailStage.NONE
        reason == P2pPlan.GROUP_CREATE_FAIL_REASON || reason.contains("no Wi-Fi Direct group formed") -> FailStage.GROUP_CREATE
        reason == BLIND_FAIL_REASON -> FailStage.SEARCH
        reason == INVITE_FAIL_REASON || reason == JOIN_FAIL_REASON -> FailStage.ASSOCIATION
        reason.contains("transport", ignoreCase = true) || reason.contains("local link", ignoreCase = true) -> FailStage.TRANSPORT
        reason.contains("tunnel", ignoreCase = true) -> FailStage.TUNNEL
        reason.contains("internet", ignoreCase = true) -> FailStage.INTERNET
        else -> FailStage.NONE
    }

    fun stageName(s: FailStage): String = when (s) {
        FailStage.NONE -> "NONE"; FailStage.GROUP_CREATE -> "GROUP_CREATE_FAIL"
        FailStage.SEARCH -> "SEARCH_FAIL"; FailStage.ASSOCIATION -> "ASSOCIATION_FAIL"
        FailStage.TRANSPORT -> "TRANSPORT_FAIL"; FailStage.TUNNEL -> "TUNNEL_FAIL"; FailStage.INTERNET -> "INTERNET_FAIL"
    }
}
