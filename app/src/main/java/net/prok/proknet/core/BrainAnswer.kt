package net.prok.proknet.core

/**
 * v0.17.3: "the Brain did not take my acceptance" is three different facts.
 *
 * THE BUG THIS FILE EXISTS FOR. `NetworkBrainSync.answer` returned a Boolean, and `false`
 * meant all of:
 *
 *  - the server refused, definitively;
 *  - the server was not reachable;
 *  - the request timed out;
 *  - the server had an internal fault.
 *
 * The retry path then treated `false` as a refusal and REMOVED the provider's accepted
 * opportunity. So a provider who tapped PARTAGER in a place with bad signal could lose the
 * card - and with it the acknowledgement that the buyer is waiting for - because the
 * network was down for a moment. A dropped packet is not a decision.
 *
 * Conservative by construction: only a reason the Brain states explicitly is terminal.
 * Anything unrecognised is retryable, because the cost of retrying a settled activation is
 * one idempotent request, and the cost of discarding a live one is a buyer waiting for
 * ever on a provider who already agreed.
 */
object BrainAnswer {

    enum class Result {
        /** The Brain has it. Includes a repeat of an acceptance it already recorded. */
        ACCEPTED,

        /** Nothing was decided. Keep everything, try again later. */
        RETRYABLE_FAILURE,

        /** The Brain says this activation is over. Only then may the stale card go. */
        TERMINAL_REJECT,
    }

    /**
     * The `reason` slugs the Brain sends for an activation that can never be accepted.
     *
     * Machine-readable on purpose. v0.16.3 shipped a Kotlin/Python canonical-form
     * mismatch that no green suite could see, so the phone does not match on English
     * prose the server is free to reword: `server/brain/network.py` sets exactly these
     * slugs, and `server/tests/fixtures/brain_answer_reasons.txt` is the shared list both sides
     * test against.
     */
    val TERMINAL_REASONS: Set<String> = setOf(
        "ACTIVATION_UNKNOWN",
        "ACTIVATION_NOT_YOURS",
        "ACTIVATION_SETTLED",
        "ACTIVATION_EXPIRED")

    /**
     * @param code the HTTP status, or 0 when no reply arrived at all.
     * @param body the reply, which for a refusal carries `"reason"`.
     */
    fun classify(code: Int, body: String): Result = when {
        code in 200..299 -> Result.ACCEPTED
        // a 5xx is the Brain's fault, not this activation's; a 429 is "not now"
        code >= 500 || code == 0 || code == 408 || code == 429 -> Result.RETRYABLE_FAILURE
        BrainPayload.field(body, "reason") in TERMINAL_REASONS -> Result.TERMINAL_REJECT
        // an unrecognised 4xx: assume the network or a version skew, never a verdict
        else -> Result.RETRYABLE_FAILURE
    }

    /** The request threw before any server answered. Always retryable. */
    fun unreachable(): Result = Result.RETRYABLE_FAILURE

    fun describe(r: Result): String = when (r) {
        Result.ACCEPTED -> "the network knows this phone accepted"
        Result.RETRYABLE_FAILURE -> "acceptance not delivered yet; it will be retried"
        Result.TERMINAL_REJECT -> "the network says this request is over"
    }
}
