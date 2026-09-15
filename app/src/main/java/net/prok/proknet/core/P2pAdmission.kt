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

    enum class Plan { BUYER_CONNECT, SELLER_INVITE, WAIT }

    /**
     * The plan, from the two visibilities and nothing else.
     *
     * When BOTH can see each other, BUYER_CONNECT wins: it is the path that
     * has actually formed groups on these phones.
     */
    fun plan(buyerSeesSeller: Boolean, sellerSeesBuyer: Boolean): Plan = when {
        buyerSeesSeller -> Plan.BUYER_CONNECT
        sellerSeesBuyer -> Plan.SELLER_INVITE
        else -> Plan.WAIT
    }

    fun planName(p: Plan): String = when (p) {
        Plan.BUYER_CONNECT -> "BUYER_CONNECT"
        Plan.SELLER_INVITE -> "SELLER_INVITE"
        Plan.WAIT -> "WAIT"
    }

    fun planText(p: Plan): String = when (p) {
        Plan.BUYER_CONNECT -> "the customer can address the provider, so the customer joins"
        Plan.SELLER_INVITE -> "only the provider can address the customer, so the provider invites"
        Plan.WAIT -> "neither phone can address the other yet: both keep looking"
    }

    /** Who owns the single admission attempt in flight. */
    enum class Owner { NOBODY, BUYER, SELLER }

    fun owner(p: Plan): Owner = when (p) {
        Plan.BUYER_CONNECT -> Owner.BUYER
        Plan.SELLER_INVITE -> Owner.SELLER
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

    /** Where a purchase is: still looking, associating, or out of time. */
    enum class Ladder { SEARCH, ASSOCIATING, GIVE_UP }

    /**
     * Choosing a plan is not starting an attempt. Only an ACCEPTED
     * association starts the association clock, and from that moment the
     * search clock no longer decides anything.
     */
    fun ladder(associationStartedAt: Long, now: Long, searchedMs: Long): Ladder = when {
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
        plan == Plan.BUYER_CONNECT && canSee -> BuyerStep.CONNECT
        reportedMsAgo >= VISIBILITY_EVERY_MS -> BuyerStep.REPORT_VISIBILITY
        plan == Plan.SELLER_INVITE -> BuyerStep.WAIT_FOR_INVITE
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
        plan == Plan.SELLER_INVITE && sellerSight.canSee && ownsGroup && !hasMember && invitedMsAgo >= ASSOCIATION_TIMEOUT_MS

    // ---- how a purchase ends, truthfully -------------------------------------------------------------

    /** Neither phone could ever address the other. */
    const val BLIND_FAIL_REASON = "neither phone could address the other over Wi-Fi Direct"
    /** The provider could see the customer, and its invitation did not complete. */
    const val INVITE_FAIL_REASON = "the provider could see this phone, but the Wi-Fi Direct invitation did not complete"
    /** The customer could see the provider, and its join did not complete. */
    const val JOIN_FAIL_REASON = "the customer could see the provider, but the Wi-Fi Direct join did not complete"

    /**
     * v0.9.21: report the stage that actually failed. A planned attempt that
     * did not complete must never be collapsed back into "neither could
     * address the other", which was simply false in the last run.
     */
    fun failReason(plan: Plan?, connectAttempts: Int): String = when {
        plan == Plan.SELLER_INVITE -> INVITE_FAIL_REASON
        plan == Plan.BUYER_CONNECT || connectAttempts > 0 -> JOIN_FAIL_REASON
        else -> BLIND_FAIL_REASON
    }
}
