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

    /** An accepted association owns admission until the group forms or this runs out. */
    const val ATTEMPT_OWN_MS = 20_000L

    /** How often a phone re-reports what it can see while nothing is decided. */
    const val VISIBILITY_EVERY_MS = 8_000L

    /** Is the attempt in flight still the one that owns admission? */
    fun keepOwner(current: Owner, sinceMs: Long, failed: Boolean, groupFormed: Boolean): Boolean =
        current != Owner.NOBODY && !groupFormed && !failed && sinceMs < ATTEMPT_OWN_MS

    /**
     * The plan to act on now. A pending attempt is never overtaken by a
     * newer decision, so the two sides cannot both start associating.
     */
    fun heldPlan(current: Plan?, currentOwner: Owner, sinceMs: Long, failed: Boolean, groupFormed: Boolean, fresh: Plan): Plan =
        if (current != null && keepOwner(currentOwner, sinceMs, failed, groupFormed)) current else fresh

    /** What the customer does on this tick. */
    enum class BuyerStep { REPORT_VISIBILITY, CONNECT, WAIT_FOR_INVITE, WAIT_DISCOVERY }

    fun buyerStep(plan: Plan?, canSee: Boolean, reportedMsAgo: Long): BuyerStep = when {
        plan == Plan.SELLER_INVITE -> BuyerStep.WAIT_FOR_INVITE
        plan == Plan.BUYER_CONNECT && canSee -> BuyerStep.CONNECT
        reportedMsAgo >= VISIBILITY_EVERY_MS -> BuyerStep.REPORT_VISIBILITY
        else -> BuyerStep.WAIT_DISCOVERY
    }

    fun buyerStepText(s: BuyerStep): String = when (s) {
        BuyerStep.REPORT_VISIBILITY -> "telling the provider what this phone can see"
        BuyerStep.CONNECT -> "joining the provider group"
        BuyerStep.WAIT_FOR_INVITE -> "waiting for the provider to invite this phone"
        BuyerStep.WAIT_DISCOVERY -> "looking for the provider in this phone's Wi-Fi Direct list"
    }

    /**
     * May the provider send an invitation now? Only when it owns admission,
     * it can really address the customer, and it is not repeating an
     * invitation that is still pending.
     */
    fun mayInvite(plan: Plan, sellerSight: Sight, invitedMsAgo: Long, groupFormed: Boolean): Boolean =
        plan == Plan.SELLER_INVITE && sellerSight.canSee && !groupFormed && invitedMsAgo >= ATTEMPT_OWN_MS

    /** The sentence a purchase ends with when neither phone could address the other. */
    const val BLIND_FAIL_REASON = "neither phone could address the other over Wi-Fi Direct"
}
