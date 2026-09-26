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
 *  - the Brain was UNREACHABLE or is not configured: REFUSE, with a sentence that says so.
 *    Product rule (Mike, 2026-09-26): a paid session requires a CONFIRMED hold. There is
 *    no "admit on the old trust rule" any more - that was a way to spend credit nobody
 *    had reserved. Free sessions never come here and keep working without any Brain.
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
            Kind.UNREACHABLE -> Decision(false, "", UNREACHABLE_SENTENCE, "hold refused: Brain unreachable")
            Kind.NOT_CONFIGURED -> Decision(false, "", NOT_CONFIGURED_SENTENCE, "hold refused: no Brain configured")
        }
    }

    /** What the buyer reads when the credit could not be checked. Names the consequence and the way out. */
    const val UNREACHABLE_SENTENCE = "Le réseau Prok est injoignable : votre crédit ne peut pas être vérifié. Réessayez plus tard, ou utilisez une connexion gratuite."
    const val NOT_CONFIGURED_SENTENCE = "Ce fournisseur n'est pas relié au réseau Prok : seule une connexion gratuite est possible ici."

    /** What the buyer's screen says. Never a server code, always a sentence. */
    fun refusalSentence(reason: String, message: String): String = when (reason) {
        "insufficient_credit" -> "Crédit Internet insuffisant - rechargez d'abord."
        "hold_exists" -> "Une autre session est déjà en cours sur votre crédit."
        "too_many_holds" -> "Trop de sessions cette heure - réessayez plus tard."
        else -> if (message.isNotBlank()) message else "Le réseau Prok a refusé cette session."
    }
}
