package net.prok.proknet.core

/**
 * v0.18.0: may this paid session be admitted, given what the Brain said about the buyer's
 * credit?
 *
 * The seller is the phone with Internet, so before it agrees a PAID contract it asks the
 * Brain to reserve the buyer's credit (a hold). This object decides what the answer means;
 * `Gateway` only asks and obeys. Pure, so the four outcomes are tested without a socket:
 *
 *  - a FREE contract needs no hold at all;
 *  - the Brain GRANTED one: admit, and remember the hold so the session keeps it alive;
 *  - the Brain REFUSED (no credit, a hold already open, too many sessions): reject, and tell
 *    the buyer why in one sentence - the Brain answered, so its answer stands;
 *  - the Brain was UNREACHABLE or is not configured: admit on the pre-v0.18 rule (the local
 *    Trust cap the buyer already passed). A network hiccup must not turn every paid
 *    session into a refusal; the exceptional-path gate that closes this is a later step.
 */
object HoldGate {

    enum class Kind { GRANTED, REFUSED, UNREACHABLE, NOT_CONFIGURED }

    class Answer(val kind: Kind, val holdId: String = "", val reason: String = "", val message: String = "") {
        companion object {
            fun granted(holdId: String) = Answer(Kind.GRANTED, holdId = holdId)
            fun refused(reason: String, message: String) = Answer(Kind.REFUSED, reason = reason, message = message)
            val unreachable = Answer(Kind.UNREACHABLE)
            val notConfigured = Answer(Kind.NOT_CONFIGURED)
        }
    }

    class Decision(val admit: Boolean, val holdId: String, val rejectMessage: String, val note: String)

    /** Is a hold needed for this contract at all? */
    fun needsHold(c: Market.Contract): Boolean = c.budgetSession && c.rateCentimesPerMb > 0

    fun decide(paid: Boolean, a: Answer): Decision {
        if (!paid) return Decision(true, "", "", "free session, no hold")
        return when (a.kind) {
            Kind.GRANTED -> Decision(true, a.holdId, "", "hold " + a.holdId.take(8))
            Kind.REFUSED -> Decision(false, "", refusalSentence(a.reason, a.message), "hold refused: " + a.reason)
            Kind.UNREACHABLE -> Decision(true, "", "", "Brain unreachable: admitted on the local trust rule")
            Kind.NOT_CONFIGURED -> Decision(true, "", "", "no Brain configured: admitted on the local trust rule")
        }
    }

    /** What the buyer's screen says. Never a server code, always a sentence. */
    fun refusalSentence(reason: String, message: String): String = when (reason) {
        "insufficient_credit" -> "Crédit Internet insuffisant - rechargez d'abord."
        "hold_exists" -> "Une autre session est déjà en cours sur votre crédit."
        "too_many_holds" -> "Trop de sessions cette heure - réessayez plus tard."
        else -> if (message.isNotBlank()) message else "Le réseau Prok a refusé cette session."
    }
}
