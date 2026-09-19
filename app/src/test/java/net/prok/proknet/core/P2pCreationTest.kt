package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.27: BUSY is not an attempt.
 *
 * The v0.9.26 run, the first genuinely clean creation on the OnePlus:
 *
 * ```
 * 12:19:58  createGroup attempt 1 accepted
 * 12:20:13  no group formed within 15s of createGroup attempt 1 being accepted
 * 12:20:13  nothing to remove (BUSY)
 * 12:20:13  creating attempt 2
 * 12:20:13  createGroup refused BUSY
 * 12:20:16  creating attempt 3
 * 12:20:16  createGroup failed after 3 attempts: BUSY
 * ```
 *
 * One real attempt, two framework BUSY answers counted as attempts, and a
 * failure filed under stage NONE with a screen that blamed the provider.
 */
class P2pCreationTest {

    private fun accepted(s: P2pCreation.State) = P2pCreation.accepted(P2pCreation.begin(s))

    @Test
    fun an_accepted_attempt_that_times_out_starts_a_reset_and_keeps_the_count_at_one() {
        val s1 = accepted(P2pCreation.START)
        assertEquals(1, s1.attempt)
        assertEquals(P2pCreation.Phase.FORMING, s1.phase)
        // 12:20:13 the formation window closes
        val reset = P2pCreation.formationTimeout(s1)
        assertEquals(P2pCreation.Phase.RESETTING, reset.phase)
        assertEquals("the attempt happened and is counted once", 1, reset.attempt)
        // 12:20:13 removeGroup answers BUSY: still attempt 1
        val busy = P2pCreation.resetBusy(reset)
        assertEquals(1, busy.attempt)
        assertEquals(1, busy.resetTries)
        assertEquals(P2pCreation.Phase.RESETTING, busy.phase)
    }

    @Test
    fun busy_answers_never_advance_the_attempt_number() {
        var s = P2pCreation.formationTimeout(accepted(P2pCreation.START))
        // the framework says BUSY several times in a row
        repeat(5) { s = P2pCreation.resetBusy(s) }
        assertEquals("five BUSY answers, still attempt 1", 1, s.attempt)
        assertEquals(5, s.resetTries)
        assertEquals(P2pCreation.Phase.RESETTING, s.phase)
        // createGroup itself answering BUSY is the same thing
        val creating = P2pCreation.begin(P2pCreation.START)
        val b = P2pCreation.createBusy(creating)
        assertEquals(0, b.attempt)
        assertEquals(P2pCreation.Phase.RESETTING, b.phase)
        assertTrue(P2pCreation.isBusy("BUSY (framework busy)"))
        assertFalse(P2pCreation.isBusy("ERROR (internal)"))
    }

    @Test
    fun only_a_clean_framework_starts_the_second_real_attempt() {
        var s = P2pCreation.formationTimeout(accepted(P2pCreation.START))
        s = P2pCreation.resetBusy(s)
        s = P2pCreation.resetBusy(s)
        // the framework becomes clean
        s = P2pCreation.resetClean(s)
        assertEquals(P2pCreation.Phase.CREATING, s.phase)
        assertEquals("nothing is committed until Android answers", 1, s.attempt)
        assertEquals(2, s.pending)
        // Android accepts: NOW it is attempt 2
        s = P2pCreation.accepted(s)
        assertEquals(2, s.attempt)
        assertEquals(P2pCreation.Phase.FORMING, s.phase)
    }

    @Test
    fun three_real_attempts_and_only_then_exhaustion() {
        var s = P2pCreation.START
        // attempt 1: accepted, never formed
        s = P2pCreation.formationTimeout(accepted(s))
        assertEquals(P2pCreation.Phase.RESETTING, s.phase)
        // attempt 2: explicitly refused (not busy), which counts
        s = P2pCreation.createRefused(P2pCreation.begin(P2pCreation.resetClean(s)), "ERROR (internal)")
        assertEquals(2, s.attempt)
        assertEquals(P2pCreation.Phase.RESETTING, s.phase)
        // attempt 3: accepted, never formed: exhausted, and it is NEVER_FORMED
        s = P2pCreation.formationTimeout(accepted(P2pCreation.resetClean(s)))
        assertEquals(3, s.attempt)
        assertEquals(P2pCreation.Phase.FAILED, s.phase)
        assertEquals(P2pCreation.Fail.NEVER_FORMED, s.fail)
        assertTrue(P2pCreation.reasonText(s).contains("no Wi-Fi Direct group formed"))
        // a group that forms ends the machine at DONE
        assertEquals(P2pCreation.Phase.DONE, P2pCreation.formed(accepted(P2pCreation.START)).phase)
    }

    @Test
    fun a_framework_that_never_leaves_busy_ends_in_a_bounded_specific_failure() {
        var s = P2pCreation.formationTimeout(accepted(P2pCreation.START))
        var steps = 0
        while (s.phase == P2pCreation.Phase.RESETTING && steps < 100) { s = P2pCreation.resetBusy(s); steps++ }
        assertTrue("it must end", steps < 100)
        assertEquals(P2pCreation.RESET_TRIES, steps)
        assertEquals(P2pCreation.Phase.FAILED, s.phase)
        assertEquals(P2pCreation.Fail.FRAMEWORK_BUSY, s.fail)
        assertEquals("the one real attempt is still the only one", 1, s.attempt)
        val reason = P2pCreation.reasonText(s)
        assertTrue(reason.contains("stayed BUSY"))
        assertTrue(P2pCreation.isFrameworkBusy(reason))
        // the bound is a few seconds, not a lifetime
        assertTrue(P2pCreation.RESET_TRIES * P2pCreation.RESET_BACKOFF_MS <= 20_000L)
    }

    @Test
    fun every_creation_failure_is_filed_under_group_create_never_none() {
        for (f in listOf(P2pCreation.Fail.NEVER_FORMED, P2pCreation.Fail.FRAMEWORK_BUSY, P2pCreation.Fail.REFUSED, P2pCreation.Fail.PERMISSION)) {
            val reason = P2pCreation.reasonText(f, "BUSY (framework busy)")
            assertTrue(P2pCreation.isCreationFailure(reason))
            assertEquals(f.toString(), P2pAdmission.FailStage.GROUP_CREATE, P2pAdmission.stageOf(reason))
        }
        // the exact v0.9.26 wording, which went to stage NONE
        assertEquals(P2pAdmission.FailStage.GROUP_CREATE, P2pAdmission.stageOf("createGroup failed after 3 attempts: BUSY (framework busy)"))
        assertEquals(P2pAdmission.FailStage.GROUP_CREATE, P2pAdmission.stageOf(P2pPlan.GROUP_CREATE_FAIL_REASON))
    }

    @Test
    fun a_busy_framework_on_this_phone_never_blames_the_provider() {
        // the screen in the v0.9.26 run said the provider was occupied with another phone. It was not.
        val busy = P2pCreation.reasonText(P2pCreation.Fail.FRAMEWORK_BUSY, "createGroup answered BUSY 9 times")
        val fr = ProductState.lostHint(busy)
        assertFalse(fr.contains("fournisseur"))
        assertTrue(fr.contains("occup") && fr.contains("Attendez"))
        val refused = P2pCreation.reasonText(P2pCreation.Fail.REFUSED, "ERROR (internal)")
        val fr2 = ProductState.lostHint(refused)
        assertFalse(fr2.contains("fournisseur"))
        assertTrue(fr2.contains("Wi-Fi Direct"))
        // the old raw wording too
        assertFalse(ProductState.lostHint("createGroup failed after 3 attempts: BUSY (framework busy)").contains("fournisseur"))
        // and a REAL provider-busy refusal still says exactly that
        assertTrue(ProductState.lostHint(Wire.cancelReasonText(Wire.CANCEL_BUSY)).contains("fournisseur"))
    }
}
