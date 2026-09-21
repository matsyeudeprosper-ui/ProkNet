package net.prok.proknet.core

import java.text.Normalizer

/**
 * v0.16.0: reading a Mobile Money "you have received" message without knowing what it says.
 *
 * The habit in Congo-Brazzaville already works and we are not changing it: the buyer takes
 * cash to any kiosk, the kiosk sends it to the seller's ordinary MTN or Airtel number, and
 * the seller's phone gets the operator's normal message. The kiosk installs nothing, scans
 * nothing and has never heard of ProkNet. What ProkNet adds is that the **seller's** phone
 * notices that message and matches it to a debt.
 *
 * That makes the parser the risky part, so it is deliberately not written as a template:
 *
 *     if (text.startsWith("Vous avez reçu"))   // <- never this
 *
 * MTN and Airtel change their wording, add a promotion line, switch language, or drop an
 * accent, and a template-matching payment system quietly stops clearing debts. Instead the
 * text is normalised and **scored**: receipt words pull one way, sending words pull the
 * other, and every number in the message competes to be the amount based on what surrounds
 * it.
 *
 * The bias is stated once and applied everywhere: **a false negative costs a retry, a
 * false positive gives away Internet for free.** When the evidence is not clear, the answer
 * is AMBIGUOUS and nothing is cleared.
 */
object ReceiptParser {

    /** Bumped whenever scoring changes, and recorded on every receipt for the audit trail. */
    const val PARSER_VERSION = 1

    /**
     * The words the parser knows, kept as data so they can later be replaced by a **signed**
     * configuration from the Brain when an operator changes its wording. An unsigned remote
     * configuration must never be allowed to alter financial matching.
     */
    class Rules(
        /** Concepts that mean money arrived. */
        val credit: List<String> = listOf(
            "recu", "receve", "recevoir", "recois", "recoit",
            "credite", "credit", "crediter",
            "received", "receive", "deposit", "deposited",
            "versement", "verse", "transfert recu", "money received",
            "avez recu", "vous recevez"),
        /** Concepts that mean money left. These outrank the ones above. */
        val debit: List<String> = listOf(
            "envoye", "envoi", "sent", "send",
            "debite", "debit", "withdrawal", "withdraw", "retrait", "retire",
            "paiement effectue", "payment sent", "transfert envoye", "achat"),
        /** Messages that are not payments at all. */
        val reject: List<String> = listOf(
            "otp", "code secret", "mot de passe", "password", "pin",
            "ne partagez", "do not share", "verification code", "code de verification",
            "promo", "publicite", "forfait", "bundle"),
        /** Words that mean the number after them is a running balance, not the payment. */
        val balance: List<String> = listOf(
            "solde", "balance", "nouveau solde", "new balance", "disponible"),
        /** Currency tokens, all normalised forms. */
        val currency: List<String> = listOf("fcfa", "cfa", "xaf", "f cfa", "francs cfa", "franc cfa", "f"),
    )

    val DEFAULT = Rules()

    // ---- what came out ------------------------------------------------------------------------------

    enum class Verdict {
        /** A credit, with one amount we are confident about. */
        CREDIT,

        /** Plainly a payment leaving, a balance notice, an OTP, or not money at all. */
        NOT_A_CREDIT,

        /** Possibly a credit, but the amount cannot be identified safely. Never clears a debt. */
        AMBIGUOUS,
    }

    class Parsed(
        val verdict: Verdict,
        /** Centimes, when [verdict] is CREDIT. */
        val amountCentimes: Long,
        /** 0..100. Below [MIN_CONFIDENCE] nothing is cleared. */
        val confidence: Int,
        /** Why, for the developer diagnostic. Never shown to a user. */
        val reason: String,
        /** Opportunistic only: never required for matching. */
        val reference: String = "",
        val parserVersion: Int = PARSER_VERSION,
    ) {
        val usable: Boolean get() = verdict == Verdict.CREDIT && confidence >= MIN_CONFIDENCE
    }

    const val MIN_CONFIDENCE = 60

    // ---- normalisation ------------------------------------------------------------------------------

    /**
     * Strip everything an operator might change without meaning to change anything:
     * accents, case, non-breaking and narrow spaces, repeated punctuation.
     */
    fun normalize(raw: String): String {
        val noAccents = Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
        return noAccents.lowercase()
            .replace(' ', ' ').replace(' ', ' ').replace(' ', ' ')
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex(" {2,}"), " ")
            .trim()
    }

    /**
     * Every number in the message that could be money, with where it was found.
     *
     * Handles "1 250", "1.250", "1,250" and "50.75" without guessing: a group of exactly
     * three digits after a separator is thousands, anything else after a separator is a
     * fraction.
     */
    class Amount(val centimes: Long, val at: Int, val text: String)

    private val NUMBER = Regex("\\d[\\d .,]*\\d|\\d")

    /**
     * Dates and clock times, so their digits never compete to be the payment. Replaced by
     * spaces rather than removed, so every other position stays exactly where it was and
     * the proximity scoring still means what it says.
     */
    private val DATE_OR_TIME = Regex("\\d{1,4}[/\\-]\\d{1,2}[/\\-]\\d{2,4}|\\d{1,2}[:h]\\d{2}(?::\\d{2})?")

    fun maskDatesAndTimes(t: String): String =
        DATE_OR_TIME.replace(t) { " ".repeat(it.value.length) }

    fun amounts(normalized: String): List<Amount> {
        val out = ArrayList<Amount>()
        for (m in NUMBER.findAll(maskDatesAndTimes(normalized))) {
            val raw = m.value.trim()
            val centimes = toCentimes(raw) ?: continue
            if (centimes <= 0) continue
            out.add(Amount(centimes, m.range.first, raw))
        }
        return out
    }

    private fun toCentimes(raw: String): Long? {
        val cleaned = raw.replace(" ", "")
        if (cleaned.isEmpty() || !cleaned[0].isDigit()) return null
        // a trailing separator plus exactly two digits is a fraction; three is a thousand group
        val frac = Regex("^([\\d.,]*\\d)[.,](\\d{2})$").find(cleaned)
        val whole: String
        var cents = 0L
        if (frac != null && !Regex("^[\\d]{1,3}([.,]\\d{3})+$").matches(cleaned)) {
            whole = frac.groupValues[1].replace(Regex("[.,]"), "")
            cents = frac.groupValues[2].toLong()
        } else {
            whole = cleaned.replace(Regex("[.,]"), "")
        }
        if (whole.isEmpty() || whole.length > 12) return null
        val units = whole.toLongOrNull() ?: return null
        return units * 100 + cents
    }

    // ---- the decision --------------------------------------------------------------------------------

    /** Every position at which any of [words] appears. */
    private fun hits(text: String, words: List<String>): List<Int> =
        words.flatMap { w ->
            val out = ArrayList<Int>()
            var i = text.indexOf(w)
            while (i >= 0) { out.add(i); i = text.indexOf(w, i + 1) }
            out
        }

    /**
     * @param expectedCentimes what we are waiting for, when anything is. A match with the
     *        expected amount is the strongest single signal there is, which is exactly why
     *        it may raise confidence but must never invent an amount that is not present.
     */
    fun parse(raw: String, expectedCentimes: Long = 0, rules: Rules = DEFAULT): Parsed {
        if (raw.isBlank()) return Parsed(Verdict.NOT_A_CREDIT, 0, 0, "empty")
        val t = normalize(raw)

        val rejected = rules.reject.firstOrNull { t.contains(it) }
        if (rejected != null) return Parsed(Verdict.NOT_A_CREDIT, 0, 0, "reject word: " + rejected)

        val creditAt = hits(t, rules.credit)
        val debitAt = hits(t, rules.debit)
        if (creditAt.isEmpty() && debitAt.isEmpty())
            return Parsed(Verdict.NOT_A_CREDIT, 0, 0, "no receipt or payment concept")
        // money leaving outranks money arriving: "paiement de 50 CFA effectue" is not a receipt
        if (debitAt.isNotEmpty() && creditAt.isEmpty())
            return Parsed(Verdict.NOT_A_CREDIT, 0, 0, "payment leaving")

        val currencyAt = hits(t, rules.currency)
        val balanceAt = hits(t, rules.balance)
        val candidates = amounts(t)
        if (candidates.isEmpty()) return Parsed(Verdict.AMBIGUOUS, 0, 0, "no amount found")

        // both concepts present: only trust it when a credit word is the nearer one to the
        // amount we would choose, otherwise it is too muddled to act on
        val scored = candidates.map { it to score(it, creditAt, debitAt, currencyAt, balanceAt, expectedCentimes, t) }
            .sortedByDescending { it.second }

        val (best, bestScore) = scored.first()
        if (bestScore <= 0) return Parsed(Verdict.AMBIGUOUS, 0, 0, "no amount scored positively")
        if (debitAt.isNotEmpty() && creditAt.isNotEmpty()) {
            val nearestCredit = creditAt.minOf { Math.abs(it - best.at) }
            val nearestDebit = debitAt.minOf { Math.abs(it - best.at) }
            if (nearestDebit < nearestCredit)
                return Parsed(Verdict.NOT_A_CREDIT, 0, 0, "the nearer concept is money leaving")
        }

        // a clear winner, or nothing. Two amounts that score alike are exactly the case
        // where a template parser would silently pick the wrong one.
        val runnerUp = scored.getOrNull(1)?.second ?: 0
        if (runnerUp > 0 && bestScore - runnerUp < MARGIN)
            return Parsed(Verdict.AMBIGUOUS, 0, confidence(bestScore), "two amounts are equally plausible")

        val conf = confidence(bestScore)
        if (conf < MIN_CONFIDENCE)
            return Parsed(Verdict.AMBIGUOUS, best.centimes, conf, "not confident enough")
        return Parsed(Verdict.CREDIT, best.centimes, conf, "scored " + bestScore, reference(t))
    }

    /** How far ahead the winner must be before we act on it. */
    const val MARGIN = 25

    private fun score(
        a: Amount, creditAt: List<Int>, debitAt: List<Int>, currencyAt: List<Int>,
        balanceAt: List<Int>, expected: Long, t: String,
    ): Int {
        var s = 0
        // the expected amount is the strongest signal we have, and it can only ever
        // promote a number that is genuinely in the message
        if (expected > 0 && a.centimes == expected) s += 55
        else if (expected > 0) s -= 20

        // sitting next to a word that means "arrived"
        val nearCredit = creditAt.minOfOrNull { Math.abs(it - a.at) } ?: Int.MAX_VALUE
        if (nearCredit < 40) s += 45 - (nearCredit / 2)

        // and next to a currency token
        val nearCurrency = currencyAt.minOfOrNull { Math.abs(it - a.at) } ?: Int.MAX_VALUE
        if (nearCurrency < 20) s += 35 - nearCurrency

        // both together is what a real receipt looks like
        if (nearCredit < 40 && nearCurrency < 20) s += 15

        // a number right after "solde"/"balance" is the running total, not the payment
        val nearBalance = balanceAt.minOfOrNull { a.at - it } ?: Int.MIN_VALUE
        if (nearBalance in 0..24) s -= 95

        val nearDebit = debitAt.minOfOrNull { Math.abs(it - a.at) } ?: Int.MAX_VALUE
        if (nearDebit < 24) s -= 60

        // nine digits or more is a phone number, an account or a transaction id. No CFA
        // payment between two people is a hundred million francs, so this is never money.
        if (a.text.replace(Regex("[^0-9]"), "").length >= 9) s -= 120
        return s
    }

    private fun confidence(score: Int): Int = Math.max(0, Math.min(100, score))

    /**
     * Opportunistic. If the operator happens to include a transaction id we keep it as
     * extra evidence, but **matching never requires one** — that is the whole point of the
     * design, because a buyer at a kiosk has no way to give us one.
     */
    fun reference(normalized: String): String {
        val m = Regex("(?:ref|reference|txn|transaction|id)[^a-z0-9]{0,3}([a-z0-9][a-z0-9.-]{5,30})")
            .find(normalized) ?: return ""
        return m.groupValues[1].uppercase()
    }
}
