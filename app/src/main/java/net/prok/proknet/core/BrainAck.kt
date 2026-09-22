package net.prok.proknet.core

/**
 * v0.17.3: whether a provider's acceptance still has to reach the Brain, decided in one
 * pure place so the phone and the test run the same function.
 *
 * THE BUG THIS FILE EXISTS FOR. The provider taps PARTAGER, the acceptance is written to
 * disk, and the request to the Brain dies - no signal, a timeout, a process that went
 * away. The Brain still shows OFFERED, and the buyer waits on a provider who has in fact
 * already agreed.
 *
 * Build 70 had a retry for exactly this and could not reach it. `pollJobs` returned early
 * whenever the list of activation ids was unchanged - and the stuck case is BY DEFINITION
 * the same activation coming back in the same state, so the one situation the retry
 * existed for was the one situation it never ran in. A provider could stay stuck for ever
 * while every poll succeeded.
 *
 * Two rules, and the second one matters as much as the first:
 *
 *  - the SERVER's job state is the authority. OFFERED means it does not have our answer,
 *    whatever this phone believes; anything further on means it does.
 *  - a failure to deliver is not a refusal. Only a reason the Brain states may throw the
 *    provider's tap away - see [BrainAnswer].
 */
object BrainAck {

    /** The server's word for an activation it has offered and not yet been answered. */
    const val OFFERED = "OFFERED"

    /** What one job the Brain just showed us means for the local opportunity. */
    enum class Step {
        /** Nothing to do: not ours, not accepted here, or already settled both sides. */
        NOTHING,

        /** The Brain has our acceptance. Remember that and stop trying. */
        MARK_ACKED,

        /** The Brain does not have it. Send it again - idempotently, and without a tap. */
        RESEND,
    }

    /**
     * @param o the local opportunity for this job's demand, or null if we hold none.
     * @param activationId the activation the job names.
     * @param jobState the server's state for it.
     */
    fun step(o: ProviderInbox.Opportunity?, activationId: String, jobState: String): Step = when {
        o == null -> Step.NOTHING
        !o.accepted -> Step.NOTHING                       // nobody pressed the button here
        o.brainActivationId.isEmpty() -> Step.NOTHING     // a local opportunity, no activation
        o.brainActivationId != activationId -> Step.NOTHING  // a different job for the same buyer
        // still OFFERED: the server does not have our answer, whatever we last believed.
        // Note this deliberately ignores brainAcked - a stale true must not silence the
        // one signal that says the acceptance was lost.
        jobState == OFFERED -> Step.RESEND
        // ACCEPTED, LOCAL_LINK_SEEN, anything past OFFERED: it has it.
        o.brainAcked -> Step.NOTHING
        else -> Step.MARK_ACKED
    }

    /** What the Brain's reply to a resend means for the inbox. */
    enum class Outcome {
        /** Confirmed. Persist that so later polls stop retrying. */
        ACKED,

        /** Nothing was decided. Keep the card, the acceptance and the activation id. */
        KEEP_AND_RETRY,

        /** The activation is genuinely over: the stale card may go. */
        DROP_CARD,
    }

    /**
     * @param sessionLive true while a seller session is actually carrying somebody.
     *
     * A dead activation drops a CARD, never a SESSION. Somebody is using the Internet
     * right now, and control-plane bookkeeping is not a reason to cut them off.
     */
    fun outcome(result: BrainAnswer.Result, sessionLive: Boolean): Outcome = when (result) {
        BrainAnswer.Result.ACCEPTED -> Outcome.ACKED
        BrainAnswer.Result.RETRYABLE_FAILURE -> Outcome.KEEP_AND_RETRY
        BrainAnswer.Result.TERMINAL_REJECT ->
            if (sessionLive) Outcome.KEEP_AND_RETRY else Outcome.DROP_CARD
    }

    /**
     * Has the job list actually moved since the last poll?
     *
     * Build 70 compared activation ids alone, which had two consequences. The UI missed a
     * job whose STATE changed under the same id - OFFERED becoming ACCEPTED is the same
     * id and a different fact - and, worse, the early return it guarded also skipped the
     * acknowledgement retry, whose whole case is an unchanged list.
     *
     * Reconciliation no longer lives behind this at all; this now decides only whether
     * the UI needs redrawing, and it counts a state change as movement.
     *
     * @param jobs (activationId, state) for each job, in the order the Brain sent them.
     */
    fun movementKey(jobs: List<Pair<String, String>>): List<String> =
        jobs.map { it.first + ":" + it.second }

    /**
     * Does this poll have anything to reconcile at all? Cheap, so the common case - a
     * provider with nothing accepted - costs one pass over a short list and no work.
     */
    fun anyWork(inbox: ProviderInbox.State, jobs: List<Triple<String, String, String>>): Boolean =
        jobs.any { (activationId, demandId, state) ->
            step(inbox.items[demandId], activationId, state) != Step.NOTHING
        }
}
