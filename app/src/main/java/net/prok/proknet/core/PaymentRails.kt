package net.prok.proknet.core

/**
 * v0.15.0: how an obligation gets paid.
 *
 * ProkNet never asks for a Mobile Money PIN, secret code, password, or an OTP meant only
 * for the operator. The user authorises a payment inside the operator's own flow — its
 * app, its USSD menu, its confirmation screen — and ProkNet receives only the result and
 * the reference it is legitimately allowed to see. Any design that needs the PIN is the
 * wrong design, not a feature to add later.
 *
 * The rails are adapters so the wallet core never knows which operator it is talking to.
 *
 * **Status in this build, stated plainly:**
 * - `MOCK` — real, developer mode only, simulates every outcome.
 * - `MANUAL_PILOT` — real, and honest: the buyer pays outside the app and gives the
 *   reference; the obligation stays PAYMENT_SEEN until a human verifies it. A reference
 *   somebody typed is never treated as proof.
 * - `MTN_MOMO`, `AIRTEL_MONEY` — interface and integration points only. There are no
 *   merchant credentials in this repository, so these rails refuse to run rather than
 *   pretend. See [Adapter] for exactly what production must fill in.
 */
object PaymentRails {

    /** What a rail can be asked to do. Deliberately small. */
    interface Adapter {
        val rail: Settlement.Rail

        /** True when this rail can actually be used on this phone right now. */
        fun available(): Boolean

        /** What a person calls it. */
        fun displayName(): String

        /**
         * Ask the rail to start a payment. Returns what to do next; it never returns
         * "paid", because starting a payment is not evidence of one.
         *
         * Production integration point for MTN and Airtel: a merchant collection request
         * (MTN MoMo "requesttopay", Airtel "push payment") against the seller's payment
         * destination, authorised by the user in the operator's own flow.
         */
        fun initiate(o: Settlement.Obligation, destination: Destination): Initiation

        /**
         * Ask the rail what really happened. This is the only thing that may produce
         * CONFIRMED.
         *
         * Production integration point: the operator's transaction-status endpoint, or a
         * signed webhook verified server-side. Never the buyer's word.
         */
        fun check(reference: String): Settlement.Status
    }

    /** Where a seller wants to be paid. Never broadcast, never in an advert or gossip. */
    class Destination(val rail: Settlement.Rail, val msisdn: String, val holderName: String = "") {
        val valid: Boolean get() = rail != Settlement.Rail.NONE && msisdn.length in 6..20 && msisdn.all { it.isDigit() || it == '+' }
        /** What may be shown on screen: enough to recognise, not enough to copy elsewhere. */
        fun masked(): String = if (msisdn.length < 5) "•••" else "•••••" + msisdn.takeLast(3)
    }

    /** What to do after asking a rail to start. */
    class Initiation(
        val ok: Boolean,
        val status: Settlement.Status,
        val reference: String,
        /** A human instruction when the rail needs the user to act somewhere else. */
        val instruction: String,
        /** A USSD string or operator deep link the user may be sent to, if any. */
        val dial: String = "",
    )

    // ---- mock: developer mode only -------------------------------------------------------------------

    /**
     * Simulates a rail so the whole settlement path can be exercised on the phones without
     * touching real money. Guarded by [devMode]: in consumer mode it reports unavailable,
     * so a mock payment can never be shown to a user as a real one.
     */
    class MockRail(private val devMode: () -> Boolean) : Adapter {
        override val rail = Settlement.Rail.MOCK
        override fun available() = devMode()
        override fun displayName() = "Paiement simulé (développeur)"

        /** Set by the developer screen to rehearse the unhappy paths. */
        @Volatile var nextOutcome: Settlement.Status = Settlement.Status.CONFIRMED
        @Volatile var failNext = false
        private val seen = HashMap<String, Settlement.Status>()

        override fun initiate(o: Settlement.Obligation, destination: Destination): Initiation {
            if (!devMode()) return Initiation(false, Settlement.Status.PENDING, "", "Le paiement simulé est réservé au mode développeur")
            val ref = "MOCK-" + o.settlementId.substring(0, 12)
            val outcome = if (failNext) Settlement.Status.FAILED else nextOutcome
            seen[ref] = outcome
            return Initiation(true, Settlement.Status.PAYMENT_INITIATED, ref, "Paiement simulé lancé")
        }

        override fun check(reference: String): Settlement.Status =
            if (!devMode()) Settlement.Status.PENDING else seen[reference] ?: Settlement.Status.PENDING
    }

    // ---- manual pilot: real, and honest about what it proves ------------------------------------------

    /**
     * The buyer pays the seller through MTN or Airtel outside the app and gives the
     * transaction reference. That reference is **not** verification: it moves the
     * obligation to PAYMENT_SEEN and no further. Only a person checking the operator
     * statement, or a later API, may confirm it.
     *
     * This exists because it is the honest version of "we have no merchant credentials
     * yet", and it is usable in a small pilot today.
     */
    class ManualPilotRail : Adapter {
        override val rail = Settlement.Rail.MANUAL_PILOT
        override fun available() = true
        override fun displayName() = "Paiement direct (MTN / Airtel)"

        override fun initiate(o: Settlement.Obligation, destination: Destination): Initiation {
            if (!destination.valid) return Initiation(false, Settlement.Status.PENDING, "", "Le fournisseur n'a pas encore indiqué où être payé")
            return Initiation(true, Settlement.Status.PAYMENT_INITIATED, "",
                "Envoyez " + Market.cfa(o.grossCentimes) + " à " + destination.masked() +
                    ", puis entrez la référence de la transaction.",
                dial = "")
        }

        /** A typed reference is a claim, never a confirmation. */
        override fun check(reference: String): Settlement.Status =
            if (reference.isBlank()) Settlement.Status.PENDING else Settlement.Status.PAYMENT_SEEN

        /** Is this plausibly an operator reference at all? Shape only; it proves nothing. */
        fun looksLikeReference(r: String): Boolean =
            r.trim().length in 6..40 && r.trim().all { it.isLetterOrDigit() || it == '-' || it == '.' }
    }

    // ---- operators: interface built, not connected ----------------------------------------------------

    /**
     * MTN Mobile Money and Airtel Money for Congo-Brazzaville.
     *
     * Built as far as it can honestly be built here. There are no merchant credentials,
     * no collection endpoint and no webhook secret in this repository, so this rail
     * reports itself unavailable and refuses to initiate. It does not simulate success.
     *
     * To put it into production, three things are needed and none of them are code
     * decisions we can make alone: a merchant/collection account with the operator, the
     * API credentials held server-side (never on the phone), and a webhook endpoint whose
     * signature the settlement service verifies.
     */
    class OperatorRail(override val rail: Settlement.Rail, private val name: String) : Adapter {
        override fun available() = false
        override fun displayName() = name

        override fun initiate(o: Settlement.Obligation, destination: Destination): Initiation =
            Initiation(false, Settlement.Status.PENDING, "",
                name + " n'est pas encore connecté. Utilisez le paiement direct.")

        override fun check(reference: String): Settlement.Status = Settlement.Status.PENDING
    }

    fun mtn() = OperatorRail(Settlement.Rail.MTN_MOMO, "MTN Mobile Money")
    fun airtel() = OperatorRail(Settlement.Rail.AIRTEL_MONEY, "Airtel Money")

    /**
     * The rails this phone offers, best first. An unavailable rail is still listed so the
     * user can see it is coming, but [Adapter.available] keeps it from being used.
     */
    fun all(devMode: () -> Boolean): List<Adapter> =
        listOf(ManualPilotRail(), mtn(), airtel(), MockRail(devMode))

    fun anyAvailable(rails: List<Adapter>): Boolean = rails.any { it.available() }
}
