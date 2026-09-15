package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.20 symmetric admission, corrected in v0.9.21.
 *
 * Two real runs, two opposite visibilities:
 *
 * ```
 * CASE A (v0.9.11)  the buyer saw the seller; the OWNER's peer list showed the
 *                   buyer as 00:00:00:00:00:00 and could not identify it
 * CASE B (v0.9.19)  the seller saw "OnePlus Nord CE 2 Lite 5G" at
 *                   1e:4f:f2:19:36:ce; the buyer saw 0 peers, 0 attempts
 * ```
 *
 * And then v0.9.20 decided SELLER_INVITE correctly and never sent the
 * invitation, because it treated the provider's own empty group as proof that
 * admission was already over:
 *
 * ```
 * 17:56:05  admission: the customer ... cannot address me, and I can address
 *           "OnePlus Nord CE 2 Lite 5G" at 1e:4f:f2:19:36:ce -> SELLER_INVITE
 *           role GROUP_OWNER, group formed, clients 0
 * 17:56:05  INVITING ...   never printed
 * ```
 *
 * A group is a room. Admission is over when somebody is IN it.
 */
class P2pAdmissionTest {

    private val sellerName = "C1 Pro"
    private val sellerAddr = "72:cb:dd:b9:a1:da"
    private val buyerName = "OnePlus Nord CE 2 Lite 5G"
    private val buyerAddr = "1e:4f:f2:19:36:ce"

    private val printer = P2pPlan.PeerRef("DIRECT-FB-HP DeskJet 2700 series", "14:cb:19:f5:f9:fc")
    private val tv = P2pPlan.PeerRef("Hisense VIDAA TV", "d6:f9:21:c3:41:a8")

    /** The customer as the provider sees it: the exact name from BLE, at a real address. */
    private val buyerInSight = P2pAdmission.Sight(true, buyerAddr, buyerName)

    private fun owner(clients: Int) = P2pDataPlane.advance(
        P2pDataPlane.advance(P2pDataPlane.NONE, P2pEndpoint.Observed(P2pPlan.Role.GROUP_OWNER, "p2p-wlan0-2", "192.168.49.1", "173"), 0, true),
        P2pEndpoint.Observed(P2pPlan.Role.GROUP_OWNER, "p2p-wlan0-2", "192.168.49.1", "173"), clients, true)

    // ---- what each phone can see ---------------------------------------------------------------------

    @Test
    fun a_phone_sees_the_other_only_by_the_name_it_gave_over_ble() {
        val sees = P2pAdmission.look(listOf(printer, P2pPlan.PeerRef(sellerName, sellerAddr), tv), sellerName)
        assertTrue(sees.canSee)
        assertEquals(sellerAddr, sees.address)
        assertEquals(sellerName, sees.name)
        assertTrue(sees.describe().contains(sellerAddr))

        // CASE B: only a printer and a television are addressable
        val blind = P2pAdmission.look(listOf(printer, tv), sellerName)
        assertFalse("a printer is never the provider", blind.canSee)
        assertEquals("", blind.address)

        // CASE A: the owner's list anonymises the customer
        val anonymised = listOf(P2pPlan.PeerRef("", "00:00:00:00:00:00"), P2pPlan.PeerRef("", "02:00:00:00:00:00"))
        assertFalse(P2pAdmission.look(anonymised, buyerName).canSee)

        // and with no name to look for there is nothing to see
        assertFalse(P2pAdmission.look(listOf(P2pPlan.PeerRef(sellerName, sellerAddr)), "").canSee)
        assertFalse(P2pAdmission.look(emptyList(), sellerName).canSee)
    }

    // ---- the plan ------------------------------------------------------------------------------------

    @Test
    fun the_plan_is_decided_by_what_the_two_phones_can_see() {
        assertEquals(P2pAdmission.Plan.GUEST_CONNECT, P2pAdmission.plan(guestSeesOwner = true, ownerSeesGuest = false))
        assertEquals(P2pAdmission.Plan.OWNER_INVITE, P2pAdmission.plan(guestSeesOwner = false, ownerSeesGuest = true))
        // both see each other: the customer joins, because that is the path that has formed groups
        assertEquals(P2pAdmission.Plan.GUEST_CONNECT, P2pAdmission.plan(guestSeesOwner = true, ownerSeesGuest = true))
        assertEquals(P2pAdmission.Plan.WAIT, P2pAdmission.plan(guestSeesOwner = false, ownerSeesGuest = false))

        assertEquals(P2pAdmission.Owner.GUEST, P2pAdmission.owner(P2pAdmission.Plan.GUEST_CONNECT))
        assertEquals(P2pAdmission.Owner.OWNER, P2pAdmission.owner(P2pAdmission.Plan.OWNER_INVITE))
        assertEquals(P2pAdmission.Owner.NOBODY, P2pAdmission.owner(P2pAdmission.Plan.WAIT))
        for (p in P2pAdmission.Plan.values()) {
            assertTrue(P2pAdmission.planName(p).isNotEmpty())
            assertTrue(P2pAdmission.planText(p).isNotEmpty())
        }
    }

    // ---- THE v0.9.21 CORRECTION: a group is a room, membership is admission ---------------------------

    @Test
    fun a_provider_holding_an_EMPTY_group_must_invite_the_customer_it_can_see() {
        // exactly the state of the phone at 17:56:05
        val empty = owner(clients = 0)
        assertTrue("the provider owns a group", empty.endpointReady)
        assertFalse("and nobody has joined it", empty.hasMember)

        assertTrue("this is the state the invitation exists FOR",
            P2pAdmission.mayInvite(P2pAdmission.Plan.OWNER_INVITE, buyerInSight,
                ownsGroup = true, hasMember = empty.hasMember, invitedMsAgo = Long.MAX_VALUE))

        // and the plan is not released just because the provider owns a group
        assertTrue(P2pAdmission.keepOwner(P2pAdmission.Owner.OWNER, 1_000, failed = false, hasMember = empty.hasMember))
        assertEquals(P2pAdmission.Plan.OWNER_INVITE, P2pAdmission.heldPlan(
            P2pAdmission.Plan.OWNER_INVITE, P2pAdmission.Owner.OWNER, 1_000,
            failed = false, hasMember = empty.hasMember, fresh = P2pAdmission.Plan.WAIT))
    }

    @Test
    fun once_a_customer_has_joined_admission_is_over() {
        val live = owner(clients = 1)
        assertTrue(live.hasMember)
        assertFalse("no invitation once somebody is on the link",
            P2pAdmission.mayInvite(P2pAdmission.Plan.OWNER_INVITE, buyerInSight,
                ownsGroup = true, hasMember = live.hasMember, invitedMsAgo = Long.MAX_VALUE))
        assertFalse("and the attempt no longer owns admission",
            P2pAdmission.keepOwner(P2pAdmission.Owner.OWNER, 0, failed = false, hasMember = live.hasMember))
    }

    @Test
    fun a_provider_with_no_group_of_its_own_does_not_invite() {
        assertFalse(P2pAdmission.mayInvite(P2pAdmission.Plan.OWNER_INVITE, buyerInSight,
            ownsGroup = false, hasMember = false, invitedMsAgo = Long.MAX_VALUE))
    }

    @Test
    fun an_invitation_is_not_repeated_while_pending_and_may_be_retried_after_it() {
        assertTrue("the first one goes out",
            P2pAdmission.mayInvite(P2pAdmission.Plan.OWNER_INVITE, buyerInSight, true, false, Long.MAX_VALUE))
        assertFalse("not again three seconds later",
            P2pAdmission.mayInvite(P2pAdmission.Plan.OWNER_INVITE, buyerInSight, true, false, 3_000))
        assertTrue("but the window ends and nobody joined: try again",
            P2pAdmission.mayInvite(P2pAdmission.Plan.OWNER_INVITE, buyerInSight, true, false, P2pAdmission.ASSOCIATION_TIMEOUT_MS))
        // and after that window the plan may be taken again from scratch
        assertEquals(P2pAdmission.Plan.GUEST_CONNECT, P2pAdmission.heldPlan(
            P2pAdmission.Plan.OWNER_INVITE, P2pAdmission.Owner.OWNER,
            P2pAdmission.ASSOCIATION_TIMEOUT_MS, false, hasMember = false, fresh = P2pAdmission.Plan.GUEST_CONNECT))
    }

    // ---- never guess ---------------------------------------------------------------------------------

    @Test
    fun a_seller_that_only_sees_an_anonymous_peer_waits_and_never_invites() {
        val anonymised = listOf(P2pPlan.PeerRef("", "00:00:00:00:00:00"))
        val mine = P2pAdmission.look(anonymised, buyerName)
        assertFalse(mine.canSee)
        assertEquals(P2pAdmission.Plan.WAIT, P2pAdmission.plan(guestSeesOwner = false, ownerSeesGuest = mine.canSee))
        assertFalse("never invite a peer we cannot identify",
            P2pAdmission.mayInvite(P2pAdmission.Plan.OWNER_INVITE, mine, true, false, Long.MAX_VALUE))
    }

    @Test
    fun a_buyer_that_only_sees_a_printer_waits_and_never_connects_to_it() {
        val sight = P2pAdmission.look(listOf(printer), sellerName)
        assertFalse(sight.canSee)
        assertEquals(P2pAdmission.Plan.WAIT, P2pAdmission.plan(sight.canSee, ownerSeesGuest = false))
        assertEquals(P2pAdmission.BuyerStep.WAIT_DISCOVERY,
            P2pAdmission.buyerStep(P2pAdmission.Plan.WAIT, sight.canSee, reportedMsAgo = 0))
    }

    // ---- one attempt owns admission ------------------------------------------------------------------

    @Test
    fun a_pending_attempt_is_never_overtaken_by_a_new_decision() {
        // the provider decided SELLER_INVITE and invited. The customer then suddenly sees the provider.
        assertEquals("the invitation in flight keeps admission", P2pAdmission.Plan.OWNER_INVITE,
            P2pAdmission.heldPlan(P2pAdmission.Plan.OWNER_INVITE, P2pAdmission.Owner.OWNER,
                sinceMs = 5_000, failed = false, hasMember = false, fresh = P2pAdmission.Plan.GUEST_CONNECT))

        // a customer told to wait never connects, even when it can see the provider
        assertEquals(P2pAdmission.BuyerStep.WAIT_FOR_INVITE,
            P2pAdmission.buyerStep(P2pAdmission.Plan.OWNER_INVITE, canSee = true, reportedMsAgo = 0))

        // the same the other way: a join in flight is not replaced by an invitation
        assertEquals(P2pAdmission.Plan.GUEST_CONNECT, P2pAdmission.heldPlan(
            P2pAdmission.Plan.GUEST_CONNECT, P2pAdmission.Owner.GUEST, 5_000, false, false, P2pAdmission.Plan.OWNER_INVITE))
        assertFalse("the provider must not invite while the customer is joining",
            P2pAdmission.mayInvite(P2pAdmission.Plan.GUEST_CONNECT, buyerInSight, true, false, Long.MAX_VALUE))

        // an attempt that ran out of time, or failed, or produced a member, releases admission
        assertFalse(P2pAdmission.keepOwner(P2pAdmission.Owner.OWNER, P2pAdmission.ASSOCIATION_TIMEOUT_MS, false, false))
        assertFalse(P2pAdmission.keepOwner(P2pAdmission.Owner.OWNER, 1_000, failed = true, hasMember = false))
        assertFalse(P2pAdmission.keepOwner(P2pAdmission.Owner.OWNER, 1_000, failed = false, hasMember = true))
        assertFalse(P2pAdmission.keepOwner(P2pAdmission.Owner.NOBODY, 0, failed = false, hasMember = false))
        assertTrue(P2pAdmission.keepOwner(P2pAdmission.Owner.GUEST, 1_000, failed = false, hasMember = false))
    }

    // ---- the customer's own tick ---------------------------------------------------------------------

    @Test
    fun the_customer_reports_what_it_sees_and_then_obeys_the_plan() {
        // nothing decided yet: say what we can see, once per interval, not on every tick
        assertEquals(P2pAdmission.BuyerStep.REPORT_VISIBILITY,
            P2pAdmission.buyerStep(null, canSee = false, reportedMsAgo = P2pAdmission.VISIBILITY_EVERY_MS))
        assertEquals(P2pAdmission.BuyerStep.WAIT_DISCOVERY,
            P2pAdmission.buyerStep(null, canSee = false, reportedMsAgo = 1_000))
        // told to connect, and we can: connect
        assertEquals(P2pAdmission.BuyerStep.CONNECT,
            P2pAdmission.buyerStep(P2pAdmission.Plan.GUEST_CONNECT, canSee = true, reportedMsAgo = 0))
        // told to connect but we have lost sight: report again instead of dialling nothing
        assertEquals(P2pAdmission.BuyerStep.REPORT_VISIBILITY,
            P2pAdmission.buyerStep(P2pAdmission.Plan.GUEST_CONNECT, canSee = false, reportedMsAgo = P2pAdmission.VISIBILITY_EVERY_MS))
        // v0.9.21: waiting for an invitation still reports, because that report is what makes the
        // provider decide again if its invitation did not arrive
        assertEquals(P2pAdmission.BuyerStep.REPORT_VISIBILITY,
            P2pAdmission.buyerStep(P2pAdmission.Plan.OWNER_INVITE, canSee = false, reportedMsAgo = P2pAdmission.VISIBILITY_EVERY_MS))
        for (s in P2pAdmission.BuyerStep.values()) assertTrue(P2pAdmission.buyerStepText(s).isNotEmpty())
    }

    // ---- how a purchase ends -------------------------------------------------------------------------

    @Test
    fun the_ending_names_the_stage_that_actually_failed() {
        // the last run ended saying neither phone could address the other. That was false: the
        // provider could address the customer and its invitation never went out.
        assertEquals(P2pAdmission.INVITE_FAIL_REASON, P2pAdmission.failReason(P2pAdmission.Plan.OWNER_INVITE, 0))
        assertEquals(P2pAdmission.JOIN_FAIL_REASON, P2pAdmission.failReason(P2pAdmission.Plan.GUEST_CONNECT, 4))
        assertEquals(P2pAdmission.JOIN_FAIL_REASON, P2pAdmission.failReason(null, 2))
        assertEquals(P2pAdmission.BLIND_FAIL_REASON, P2pAdmission.failReason(P2pAdmission.Plan.WAIT, 0))
        assertEquals(P2pAdmission.BLIND_FAIL_REASON, P2pAdmission.failReason(null, 0))

        // and each one reaches the customer in French, with what to do next
        assertTrue(ProductState.lostHint(P2pAdmission.INVITE_FAIL_REASON).contains("invit"))
        assertTrue(ProductState.lostHint(P2pAdmission.JOIN_FAIL_REASON).contains("fournisseur"))
        assertTrue(ProductState.lostHint(P2pAdmission.BLIND_FAIL_REASON).contains("Wi-Fi Direct"))
    }

    // ---- after the customer leaves -------------------------------------------------------------------

    @Test
    fun when_the_customer_leaves_admission_resets_and_the_provider_is_findable_again() {
        val live = owner(clients = 1)
        assertTrue(live.hasMember)
        assertFalse(P2pPlan.discoveryWanted(P2pPlan.Want.SELL, live.hasMember))
        assertFalse(P2pAdmission.keepOwner(P2pAdmission.Owner.GUEST, 0, failed = false, hasMember = true))

        val alone = P2pDataPlane.advance(live, P2pEndpoint.Observed(P2pPlan.Role.GROUP_OWNER, "p2p-wlan0-2", "192.168.49.1", "173"), 0, true)
        assertFalse(alone.hasMember)
        assertTrue("the provider must be findable again", P2pPlan.discoveryWanted(P2pPlan.Want.SELL, alone.hasMember))
        // and a fresh decision may be taken for the next customer
        assertEquals(P2pAdmission.Plan.WAIT, P2pAdmission.heldPlan(
            P2pAdmission.Plan.GUEST_CONNECT, P2pAdmission.Owner.GUEST,
            P2pAdmission.ASSOCIATION_TIMEOUT_MS + 1, false, hasMember = false, fresh = P2pAdmission.Plan.WAIT))
        // an empty group is ready to invite the next one
        assertTrue(P2pAdmission.mayInvite(P2pAdmission.Plan.OWNER_INVITE, buyerInSight, true, alone.hasMember, Long.MAX_VALUE))
    }

    // ---- the wire ------------------------------------------------------------------------------------

    @Test
    fun the_visibility_and_the_plan_survive_the_wire() {
        val v = Wire.parseControl(Wire.p2pVisibility(true, buyerName)) as Wire.Control.P2pVisibility
        assertTrue(v.canSee)
        assertEquals(buyerName, v.deviceName)
        val blind = Wire.parseControl(Wire.p2pVisibility(false, "")) as Wire.Control.P2pVisibility
        assertFalse(blind.canSee)
        assertEquals("", blind.deviceName)

        val p = Wire.parseControl(Wire.p2pJoinPlan(Wire.JOIN_PLAN_OWNER_INVITE)) as Wire.Control.P2pJoinPlan
        assertEquals(Wire.JOIN_PLAN_OWNER_INVITE, p.plan)
        assertEquals("OWNER_INVITE", Wire.joinPlanName(p.plan))
        assertEquals("GUEST_CONNECT", Wire.joinPlanName(Wire.JOIN_PLAN_GUEST_CONNECT))
        assertEquals("WAIT", Wire.joinPlanName(Wire.JOIN_PLAN_WAIT))
    }

    // ---- v0.9.22: an accepted association owns the radio and its own clock ---------------------------

    @Test
    fun a_transient_formed_false_during_an_accepted_join_does_not_release_the_radio() {
        val t0 = 1_000_000L
        // 18:54:58.447 connect accepted
        assertTrue(P2pAdmission.associationPending(P2pAdmission.Owner.GUEST, t0, t0, hasMember = false, failed = false))
        // 18:54:58.472 Android says formed=false in the middle of its own join choreography
        assertTrue("that is not the end of the attempt",
            P2pAdmission.associationPending(P2pAdmission.Owner.GUEST, t0, t0 + 25, hasMember = false, failed = false))
        // the same for an accepted invitation
        assertTrue(P2pAdmission.associationPending(P2pAdmission.Owner.OWNER, t0, t0 + 25, hasMember = false, failed = false))

        // it ends on membership, on an explicit refusal, or on its own clock. Nothing else.
        assertFalse(P2pAdmission.associationPending(P2pAdmission.Owner.GUEST, t0, t0 + 100, hasMember = true, failed = false))
        assertFalse(P2pAdmission.associationPending(P2pAdmission.Owner.GUEST, t0, t0 + 100, hasMember = false, failed = true))
        assertFalse(P2pAdmission.associationPending(P2pAdmission.Owner.GUEST, t0, t0 + P2pAdmission.ASSOCIATION_TIMEOUT_MS, false, false))
        assertFalse("nothing in flight", P2pAdmission.associationPending(P2pAdmission.Owner.NOBODY, t0, t0, false, false))
        assertFalse("never accepted", P2pAdmission.associationPending(P2pAdmission.Owner.GUEST, 0L, t0, false, false))
    }

    @Test
    fun membership_turns_discovery_off_whatever_else_changed_in_the_same_observation() {
        // a first join changes BOTH generations at once: group 0 -> 1 and membership 0 -> 1
        val empty = owner(clients = 0)
        val live = owner(clients = 1)
        assertEquals(1, live.groupGeneration)
        assertEquals(1, live.membershipGeneration)
        assertTrue("both changed from nothing", live.groupGeneration != P2pDataPlane.NONE.groupGeneration &&
            live.membershipGeneration != P2pDataPlane.NONE.membershipGeneration)
        // whichever branch a log line lives in, THIS is the rule
        assertFalse(P2pPlan.discoveryWanted(P2pPlan.Want.SELL, live.hasMember))
        assertFalse(P2pPlan.discoveryWanted(P2pPlan.Want.BUY, live.hasMember))
        assertTrue(P2pPlan.discoveryWanted(P2pPlan.Want.SELL, empty.hasMember))
    }

    @Test
    fun a_newly_chosen_association_gets_a_full_fresh_clock() {
        val t0 = 5_000_000L
        // the purchase has been searching for thirty seconds and the plan is chosen only now
        val searched = 30_000L
        assertEquals(P2pAdmission.Ladder.SEARCH, P2pAdmission.ladder(associationStartedAt = 0L, now = t0, searchedMs = searched, hasMember = false))
        // the association is accepted NOW: the search time no longer decides anything
        assertEquals(P2pAdmission.Ladder.ASSOCIATING, P2pAdmission.ladder(t0, t0, searched, hasMember = false))
        assertEquals("a person is reading the Android popup",
            P2pAdmission.Ladder.ASSOCIATING, P2pAdmission.ladder(t0, t0 + 20_000, searched + 20_000, hasMember = false))
        assertEquals(P2pAdmission.Ladder.ASSOCIATING,
            P2pAdmission.ladder(t0, t0 + P2pAdmission.ASSOCIATION_TIMEOUT_MS - 1, 10 * searched, hasMember = false))
        // and only its own deadline ends it
        assertEquals(P2pAdmission.Ladder.GIVE_UP,
            P2pAdmission.ladder(t0, t0 + P2pAdmission.ASSOCIATION_TIMEOUT_MS, searched, hasMember = false))
        // with no association at all, the search clock still bounds the purchase
        assertEquals(P2pAdmission.Ladder.GIVE_UP, P2pAdmission.ladder(0L, t0, P2pAdmission.SEARCH_GIVE_UP_MS, hasMember = false))
        assertTrue("a person needs time to read a dialog and tap Connect", P2pAdmission.ASSOCIATION_TIMEOUT_MS >= 30_000L)
    }

    @Test
    fun the_run_that_failed_two_milliseconds_after_choosing_seller_invite_cannot_happen_again() {
        // 19:10:36.476 JOIN PLAN = SELLER_INVITE, after ~30 s of searching
        val t0 = 9_000_000L
        val searched = 34_000L
        val startedNow = t0
        // 19:10:36.478, two milliseconds later
        assertEquals(P2pAdmission.Ladder.ASSOCIATING, P2pAdmission.ladder(startedNow, t0 + 2, searched, hasMember = false))
        // and the provider is still allowed to be waiting for its guest at twenty seconds
        assertTrue(P2pAdmission.keepOwner(P2pAdmission.Owner.OWNER, 20_000, failed = false, hasMember = false))
        // an explicit Android refusal ends it at once, and a replan may follow
        assertFalse(P2pAdmission.keepOwner(P2pAdmission.Owner.OWNER, 2, failed = true, hasMember = false))
        assertEquals(P2pAdmission.Plan.GUEST_CONNECT, P2pAdmission.heldPlan(
            P2pAdmission.Plan.OWNER_INVITE, P2pAdmission.Owner.OWNER, 2,
            failed = true, hasMember = false, fresh = P2pAdmission.Plan.GUEST_CONNECT))
    }

    // ---- v0.9.23: membership ends the admission phase completely --------------------------------------

    @Test
    fun once_the_group_is_joined_the_admission_clock_can_never_fire_again() {
        val t0 = 7_000_000L
        // 19:38:15 the group formed. The association clock had been running since the invitation.
        assertEquals(P2pAdmission.Ladder.MEMBER_JOINED,
            P2pAdmission.ladder(t0, t0 + 16_000, 60_000, hasMember = true))
        // 19:38:55, forty seconds after the association started, v0.9.22 killed the session here
        assertEquals("the invitation completed: admission cannot fail any more",
            P2pAdmission.Ladder.MEMBER_JOINED,
            P2pAdmission.ladder(t0, t0 + P2pAdmission.ASSOCIATION_TIMEOUT_MS, 10 * 60_000, hasMember = true))
        // and with no member it still behaves as before
        assertEquals(P2pAdmission.Ladder.GIVE_UP,
            P2pAdmission.ladder(t0, t0 + P2pAdmission.ASSOCIATION_TIMEOUT_MS, 0, hasMember = false))
        assertEquals(P2pAdmission.Ladder.ASSOCIATING, P2pAdmission.ladder(t0, t0 + 1_000, 0, hasMember = false))
        assertEquals(P2pAdmission.Ladder.SEARCH, P2pAdmission.ladder(0L, t0, 1_000, hasMember = false))
    }

    @Test
    fun a_failure_is_filed_under_the_stage_it_happened_in() {
        assertEquals(P2pAdmission.FailStage.SEARCH, P2pAdmission.stageOf(P2pAdmission.BLIND_FAIL_REASON))
        assertEquals(P2pAdmission.FailStage.ASSOCIATION, P2pAdmission.stageOf(P2pAdmission.INVITE_FAIL_REASON))
        assertEquals(P2pAdmission.FailStage.ASSOCIATION, P2pAdmission.stageOf(P2pAdmission.JOIN_FAIL_REASON))
        // the v0.9.22 run died HERE, and was reported as an invitation failure
        assertEquals(P2pAdmission.FailStage.TRANSPORT, P2pAdmission.stageOf(P2pPlan.TRANSPORT_FAIL_REASON))
        assertEquals(P2pAdmission.FailStage.NONE, P2pAdmission.stageOf(""))
        assertEquals("TRANSPORT_FAIL", P2pAdmission.stageName(P2pAdmission.FailStage.TRANSPORT))
        assertEquals("ASSOCIATION_FAIL", P2pAdmission.stageName(P2pAdmission.FailStage.ASSOCIATION))
        // and the customer is told that the link, not the invitation, is what failed
        val fr = ProductState.lostHint(P2pPlan.TRANSPORT_FAIL_REASON)
        assertTrue(fr.contains("lien"))
        assertFalse("it was never an invitation failure", fr.contains("invit"))
    }

    // ---- v0.9.23: the reversed topology experiment ----------------------------------------------------

    @Test
    fun who_owns_the_group_is_not_who_sells_the_internet() {
        // production: the provider owns the group
        assertTrue(P2pPlan.ownsGroup(P2pPlan.Topology.SELLER_GROUP_OWNER, providing = true))
        assertFalse(P2pPlan.ownsGroup(P2pPlan.Topology.SELLER_GROUP_OWNER, providing = false))
        // the experiment: the customer owns it, and the provider still sells the Internet
        assertFalse(P2pPlan.ownsGroup(P2pPlan.Topology.BUYER_GROUP_OWNER, providing = true))
        assertTrue(P2pPlan.ownsGroup(P2pPlan.Topology.BUYER_GROUP_OWNER, providing = false))

        for (t in P2pPlan.Topology.values()) {
            assertTrue(P2pPlan.topologyName(t).isNotEmpty())
            assertTrue(P2pPlan.topologyText(t).isNotEmpty())
        }
        assertEquals("BUYER_GROUP_OWNER", P2pPlan.topologyName(P2pPlan.Topology.BUYER_GROUP_OWNER))
        assertEquals("BUYER_GROUP_OWNER", Wire.topologyName(Wire.TOPOLOGY_BUYER_GROUP_OWNER))
        val back = Wire.parseControl(Wire.p2pTopology(Wire.TOPOLOGY_BUYER_GROUP_OWNER)) as Wire.Control.P2pTopology
        assertEquals(Wire.TOPOLOGY_BUYER_GROUP_OWNER, back.topology)
        // a message with no body is the production topology, never the experiment
        assertEquals(Wire.TOPOLOGY_SELLER_GROUP_OWNER,
            (Wire.parseControl(byteArrayOf(Wire.OP_P2P_TOPOLOGY.toByte())) as Wire.Control.P2pTopology).topology)
    }

    @Test
    fun the_admission_plan_is_the_same_rule_whichever_phone_owns_the_group() {
        // the names are Wi-Fi Direct roles now, so the rule does not change when the group moves
        assertEquals(P2pAdmission.Plan.GUEST_CONNECT, P2pAdmission.plan(guestSeesOwner = true, ownerSeesGuest = false))
        assertEquals(P2pAdmission.Plan.OWNER_INVITE, P2pAdmission.plan(guestSeesOwner = false, ownerSeesGuest = true))
        assertEquals(P2pAdmission.Plan.GUEST_CONNECT, P2pAdmission.plan(guestSeesOwner = true, ownerSeesGuest = true))
        assertEquals(P2pAdmission.Plan.WAIT, P2pAdmission.plan(guestSeesOwner = false, ownerSeesGuest = false))
        assertEquals("GUEST_CONNECT", Wire.joinPlanName(Wire.JOIN_PLAN_GUEST_CONNECT))
        assertEquals("OWNER_INVITE", Wire.joinPlanName(Wire.JOIN_PLAN_OWNER_INVITE))
    }

    @Test
    fun the_last_test_record_survives_cleanup() {
        val r = P2pReport()
        assertFalse(r.ran)
        assertTrue(r.describe().contains("none since this phone started"))
        r.begin("BUYER_GROUP_OWNER", providing = true, now = 1_700_000_000_000L)
        r.role = "CLIENT"; r.groupChannel = "2.4 GHz ch 6 (2437 MHz)"; r.homeChannel = "Freebox 5 GHz ch 48"
        r.localIp = "192.168.49.124"; r.peerIp = "192.168.49.1"
        r.udpSent = 10; r.udpReceived = 0; r.udpRepliesReceived = 0
        r.verdict = "NO IP packet crossed"; r.failureStage = "TRANSPORT_FAIL"
        val text = r.describe()
        assertTrue(r.ran)
        assertTrue(text.contains("BUYER_GROUP_OWNER"))
        assertTrue(text.contains("2.4 GHz ch 6"))
        assertTrue(text.contains("192.168.49.124"))
        assertTrue(text.contains("TRANSPORT_FAIL"))
        assertTrue(text.contains("UDP sent: 10"))
        // a new test replaces it, and nothing else does
        r.begin("SELLER_GROUP_OWNER", providing = false)
        assertEquals("", r.verdict)
        assertEquals(0, r.udpSent)
    }
}
