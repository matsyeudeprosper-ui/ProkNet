package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.20, symmetric admission.
 *
 * Two real runs, two opposite failures:
 *
 * ```
 * CASE A (v0.9.11)  the buyer saw the seller; the OWNER's peer list showed the
 *                   buyer as 00:00:00:00:00:00 and could not identify it
 * CASE B (v0.9.19)  the seller saw "OnePlus Nord CE 2 Lite 5G" at
 *                   1e:4f:f2:19:36:ce; the buyer saw 0 peers, 0 attempts
 * ```
 *
 * So neither side may be assumed to be the one that can address the other.
 */
class P2pAdmissionTest {

    private val sellerName = "C1 Pro"
    private val sellerAddr = "72:cb:dd:b9:a1:da"
    private val buyerName = "OnePlus Nord CE 2 Lite 5G"
    private val buyerAddr = "1e:4f:f2:19:36:ce"

    private val printer = P2pPlan.PeerRef("DIRECT-FB-HP DeskJet 2700 series", "14:cb:19:f5:f9:fc")
    private val tv = P2pPlan.PeerRef("Hisense VIDAA TV", "d6:f9:21:c3:41:a8")

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
        // the buyer sees the seller, the seller cannot identify the buyer: CASE A
        assertEquals(P2pAdmission.Plan.BUYER_CONNECT, P2pAdmission.plan(buyerSeesSeller = true, sellerSeesBuyer = false))
        // the buyer is blind, the seller sees the buyer: CASE B
        assertEquals(P2pAdmission.Plan.SELLER_INVITE, P2pAdmission.plan(buyerSeesSeller = false, sellerSeesBuyer = true))
        // both see each other: the customer joins, because that is the path that has formed groups
        assertEquals(P2pAdmission.Plan.BUYER_CONNECT, P2pAdmission.plan(buyerSeesSeller = true, sellerSeesBuyer = true))
        // neither: nobody acts, both keep looking
        assertEquals(P2pAdmission.Plan.WAIT, P2pAdmission.plan(buyerSeesSeller = false, sellerSeesBuyer = false))

        assertEquals(P2pAdmission.Owner.BUYER, P2pAdmission.owner(P2pAdmission.Plan.BUYER_CONNECT))
        assertEquals(P2pAdmission.Owner.SELLER, P2pAdmission.owner(P2pAdmission.Plan.SELLER_INVITE))
        assertEquals(P2pAdmission.Owner.NOBODY, P2pAdmission.owner(P2pAdmission.Plan.WAIT))
        for (p in P2pAdmission.Plan.values()) {
            assertTrue(P2pAdmission.planName(p).isNotEmpty())
            assertTrue(P2pAdmission.planText(p).isNotEmpty())
        }
    }

    @Test
    fun a_seller_that_only_sees_an_anonymous_peer_waits_and_never_invites() {
        val anonymised = listOf(P2pPlan.PeerRef("", "00:00:00:00:00:00"))
        val mine = P2pAdmission.look(anonymised, buyerName)
        assertFalse(mine.canSee)
        assertEquals(P2pAdmission.Plan.WAIT, P2pAdmission.plan(buyerSeesSeller = false, sellerSeesBuyer = mine.canSee))
        assertFalse("never invite a peer we cannot identify",
            P2pAdmission.mayInvite(P2pAdmission.Plan.SELLER_INVITE, mine, invitedMsAgo = Long.MAX_VALUE, groupFormed = false))
    }

    @Test
    fun a_buyer_that_only_sees_a_printer_waits_and_never_connects_to_it() {
        val sight = P2pAdmission.look(listOf(printer), sellerName)
        assertFalse(sight.canSee)
        assertEquals(P2pAdmission.Plan.WAIT, P2pAdmission.plan(sight.canSee, sellerSeesBuyer = false))
        // and with that plan the customer never dials anything
        assertEquals(P2pAdmission.BuyerStep.WAIT_DISCOVERY,
            P2pAdmission.buyerStep(P2pAdmission.Plan.WAIT, sight.canSee, reportedMsAgo = 0))
    }

    // ---- one attempt owns admission ------------------------------------------------------------------

    @Test
    fun a_pending_attempt_is_never_overtaken_by_a_new_decision() {
        // the provider decided SELLER_INVITE and invited. The customer then suddenly sees the provider.
        val held = P2pAdmission.heldPlan(
            current = P2pAdmission.Plan.SELLER_INVITE, currentOwner = P2pAdmission.Owner.SELLER,
            sinceMs = 5_000, failed = false, groupFormed = false, fresh = P2pAdmission.Plan.BUYER_CONNECT)
        assertEquals("the invitation in flight keeps admission", P2pAdmission.Plan.SELLER_INVITE, held)

        // the customer, told to wait for an invitation, does not connect even though it can see
        assertEquals(P2pAdmission.BuyerStep.WAIT_FOR_INVITE,
            P2pAdmission.buyerStep(P2pAdmission.Plan.SELLER_INVITE, canSee = true, reportedMsAgo = 60_000))

        // the same the other way: a buyer connect in flight is not replaced by an invitation
        assertEquals(P2pAdmission.Plan.BUYER_CONNECT, P2pAdmission.heldPlan(
            P2pAdmission.Plan.BUYER_CONNECT, P2pAdmission.Owner.BUYER, 5_000, false, false, P2pAdmission.Plan.SELLER_INVITE))
        assertFalse("the provider must not invite while the customer is joining",
            P2pAdmission.mayInvite(P2pAdmission.Plan.BUYER_CONNECT,
                P2pAdmission.Sight(true, buyerAddr, buyerName), Long.MAX_VALUE, groupFormed = false))

        // an attempt that ran out of time releases admission
        assertEquals(P2pAdmission.Plan.BUYER_CONNECT, P2pAdmission.heldPlan(
            P2pAdmission.Plan.SELLER_INVITE, P2pAdmission.Owner.SELLER,
            P2pAdmission.ATTEMPT_OWN_MS, false, false, P2pAdmission.Plan.BUYER_CONNECT))
        // so does one that failed, and so does a group that formed
        assertFalse(P2pAdmission.keepOwner(P2pAdmission.Owner.SELLER, 1_000, failed = true, groupFormed = false))
        assertFalse(P2pAdmission.keepOwner(P2pAdmission.Owner.SELLER, 1_000, failed = false, groupFormed = true))
        assertFalse(P2pAdmission.keepOwner(P2pAdmission.Owner.NOBODY, 0, failed = false, groupFormed = false))
        assertTrue(P2pAdmission.keepOwner(P2pAdmission.Owner.BUYER, 1_000, failed = false, groupFormed = false))
    }

    @Test
    fun an_invitation_is_not_repeated_while_it_is_still_pending() {
        val mine = P2pAdmission.Sight(true, buyerAddr, buyerName)
        assertTrue(P2pAdmission.mayInvite(P2pAdmission.Plan.SELLER_INVITE, mine, Long.MAX_VALUE, groupFormed = false))
        assertFalse("not again three seconds later",
            P2pAdmission.mayInvite(P2pAdmission.Plan.SELLER_INVITE, mine, 3_000, groupFormed = false))
        assertTrue("but again once the window is over",
            P2pAdmission.mayInvite(P2pAdmission.Plan.SELLER_INVITE, mine, P2pAdmission.ATTEMPT_OWN_MS, groupFormed = false))
        assertFalse("never once the group exists",
            P2pAdmission.mayInvite(P2pAdmission.Plan.SELLER_INVITE, mine, Long.MAX_VALUE, groupFormed = true))
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
            P2pAdmission.buyerStep(P2pAdmission.Plan.BUYER_CONNECT, canSee = true, reportedMsAgo = 0))
        // told to connect but we have lost sight: report again instead of dialling nothing
        assertEquals(P2pAdmission.BuyerStep.REPORT_VISIBILITY,
            P2pAdmission.buyerStep(P2pAdmission.Plan.BUYER_CONNECT, canSee = false, reportedMsAgo = P2pAdmission.VISIBILITY_EVERY_MS))
        for (s in P2pAdmission.BuyerStep.values()) assertTrue(P2pAdmission.buyerStepText(s).isNotEmpty())
        assertTrue(P2pAdmission.BLIND_FAIL_REASON.isNotEmpty())
    }

    // ---- after the customer leaves -------------------------------------------------------------------

    @Test
    fun when_the_customer_leaves_admission_resets_and_the_provider_is_findable_again() {
        // with a customer on the link there is no admission and no discovery
        val live = P2pDataPlane.advance(
            P2pDataPlane.advance(P2pDataPlane.NONE, P2pEndpoint.Observed(P2pPlan.Role.GROUP_OWNER, "p2p-wlan0-2", "192.168.49.1", "173"), 0, true),
            P2pEndpoint.Observed(P2pPlan.Role.GROUP_OWNER, "p2p-wlan0-2", "192.168.49.1", "173"), 1, true)
        assertTrue(live.hasMember)
        assertFalse(P2pPlan.discoveryWanted(P2pPlan.Want.SELL, live.hasMember))
        assertFalse(P2pAdmission.keepOwner(P2pAdmission.Owner.BUYER, 0, failed = false, groupFormed = true))

        // the customer leaves: discovery returns and a fresh decision may be taken
        val alone = P2pDataPlane.advance(live, P2pEndpoint.Observed(P2pPlan.Role.GROUP_OWNER, "p2p-wlan0-2", "192.168.49.1", "173"), 0, true)
        assertFalse(alone.hasMember)
        assertTrue(P2pPlan.discoveryWanted(P2pPlan.Want.SELL, alone.hasMember))
        assertEquals(P2pAdmission.Plan.WAIT, P2pAdmission.heldPlan(
            P2pAdmission.Plan.BUYER_CONNECT, P2pAdmission.Owner.BUYER,
            P2pAdmission.ATTEMPT_OWN_MS + 1, false, false, P2pAdmission.Plan.WAIT))
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

        val p = Wire.parseControl(Wire.p2pJoinPlan(Wire.JOIN_PLAN_SELLER_INVITE)) as Wire.Control.P2pJoinPlan
        assertEquals(Wire.JOIN_PLAN_SELLER_INVITE, p.plan)
        assertEquals("SELLER_INVITE", Wire.joinPlanName(p.plan))
        assertEquals("BUYER_CONNECT", Wire.joinPlanName(Wire.JOIN_PLAN_BUYER_CONNECT))
        assertEquals("WAIT", Wire.joinPlanName(Wire.JOIN_PLAN_WAIT))
    }
}
