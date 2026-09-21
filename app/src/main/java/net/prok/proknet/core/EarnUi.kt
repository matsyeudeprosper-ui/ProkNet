package net.prok.proknet.core

/**
 * v0.15.2: what the Gagner screen shows.
 *
 * The old screen stacked seven cards of equal weight: a source line, a price policy with
 * three chips, a bundle form with two number fields, a fee note, an earnings total, two
 * switches and a relay panel — all before the button that actually does something. Somebody
 * who has never used an app before reads that as a form, not an offer.
 *
 * The screen asks one question: **do you want to share your Internet right now?** So it
 * shows one state, one number and one button, and everything an expert might want is
 * behind a single "Réglages" row that a first-time user never has to open.
 *
 * Pure, so every state is tested rather than judged from a screenshot.
 */
object EarnUi {

    /** What the phone can do about sharing at this moment. */
    enum class Stage {
        /** No usable Internet to share yet. Nothing to offer, and we say why. */
        NO_SOURCE,

        /** Ready to start. This is the normal resting state. */
        READY,

        /** Sharing, nobody connected yet. */
        WAITING,

        /** Sharing, somebody is using it right now. */
        SERVING,
    }

    class Hero(
        val stage: Stage,
        /** The one big line. */
        val title: String,
        /** One sentence under it. Never two. */
        val subtitle: String,
        /** The button, or empty when there is nothing to press. */
        val button: String,
        /** True when the button ends something rather than starts it. */
        val destructive: Boolean,
        /** A quiet line under the button: the estimate, or what is happening. */
        val footnote: String,
    )

    fun stage(sellerOn: Boolean, hasSource: Boolean, hasCustomer: Boolean): Stage = when {
        !sellerOn && !hasSource -> Stage.NO_SOURCE
        !sellerOn -> Stage.READY
        hasCustomer -> Stage.SERVING
        else -> Stage.WAITING
    }

    /**
     * @param sourceWord what this phone is sharing, in the seller's words ("Wi-Fi Freebox")
     * @param estimate   "Environ 3 CFA par personne", or empty when we cannot say
     * @param customer   who is connected, when somebody is
     * @param blocker    why sharing is impossible, when it is
     */
    fun hero(stage: Stage, sourceWord: String, estimate: String, customer: String, blocker: String): Hero = when (stage) {
        Stage.NO_SOURCE -> Hero(stage,
            "Pas encore d'Internet à partager",
            blocker.ifEmpty { "Connectez ce téléphone à un Wi-Fi ou activez vos données mobiles." },
            "", false, "")

        Stage.READY -> Hero(stage,
            "Partagez votre Internet",
            "Gagnez de l'argent en partageant votre connexion avec les téléphones autour de vous.",
            "Commencer", false,
            listOf(sourceWord, estimate).filter { it.isNotEmpty() }.joinToString(" · "))

        Stage.WAITING -> Hero(stage,
            "Vous partagez",
            "Vous serez payé dès que quelqu'un se connecte.",
            "Arrêter le partage", true, sourceWord)

        Stage.SERVING -> Hero(stage,
            "Quelqu'un utilise votre Internet",
            customer.ifEmpty { "Une personne est connectée." },
            "Arrêter le partage", true, sourceWord)
    }

    /** True while the screen should show the live figures. */
    fun showsLiveStats(stage: Stage): Boolean = stage == Stage.WAITING || stage == Stage.SERVING

    // ---- the live figures -----------------------------------------------------------------------

    class Stat(val label: String, val value: String)

    /**
     * Three figures, inside the hero rather than in three competing cards. "Clients" was
     * an accounting word; a person counts people.
     */
    fun liveStats(people: Int, sharedWord: String, earnedWord: String): List<Stat> = listOf(
        Stat(if (people <= 1) "Personne connectée" else "Personnes connectées", people.toString()),
        Stat("Partagé", sharedWord),
        Stat("Gagné", earnedWord))

    // ---- earnings -------------------------------------------------------------------------------

    class Earnings(
        val today: String,
        val todayLabel: String,
        /** "À recevoir · 37 CFA", or empty. */
        val receivable: String,
        /** "12 sessions partagées", or empty on the first day. */
        val history: String,
        val link: String,
    )

    fun earnings(todayCentimes: Long, receivableCentimes: Long, sessionsShared: Int): Earnings = Earnings(
        today = Market.cfa(todayCentimes),
        todayLabel = "Gagné aujourd'hui",
        receivable = if (receivableCentimes > 0) "À recevoir · " + Market.cfa(receivableCentimes) else "",
        history = when {
            sessionsShared <= 0 -> ""
            sessionsShared == 1 -> "1 partage au total"
            else -> sessionsShared.toString() + " partages au total"
        },
        link = "Voir le Wallet")

    // ---- settings, folded away --------------------------------------------------------------------

    /**
     * One line summarising everything behind the Réglages row, so a curious user can see
     * the state without opening it and a first-time user can ignore it entirely.
     */
    fun settingsSummary(policy: Pricing.SellerPolicy, notify: Boolean, coverage: Boolean, relay: Boolean): String {
        val bits = ArrayList<String>()
        bits.add(policyWord(policy))
        if (notify) bits.add("Alertes activées")
        if (coverage) bits.add("Carte partagée")
        if (relay) bits.add("Relais activé")
        return bits.joinToString(" · ")
    }

    fun policyWord(p: Pricing.SellerPolicy): String = when (p) {
        Pricing.SellerPolicy.CHEAPER -> "Moins cher"
        Pricing.SellerPolicy.BALANCED -> "Équilibré"
        Pricing.SellerPolicy.EARN_MORE -> "Gagner plus"
    }

    /** What each choice actually means, in one short line, so the words are not a riddle. */
    fun policyHint(p: Pricing.SellerPolicy): String = when (p) {
        Pricing.SellerPolicy.CHEAPER -> "Prix bas, plus de personnes se connectent."
        Pricing.SellerPolicy.BALANCED -> "Un bon équilibre entre le prix et vos gains."
        Pricing.SellerPolicy.EARN_MORE -> "Prix plus élevé, vous gagnez plus par personne."
    }

    const val SETTINGS_TITLE = "Réglages du partage"
    const val SETTINGS_OPEN = "Ouvrir"
    const val SETTINGS_CLOSE = "Fermer"

    // ---- demand ------------------------------------------------------------------------------------

    /** The alert stays first, because somebody nearby is waiting on an answer. */
    fun showDemand(waiting: Int, sellerOn: Boolean): Boolean = waiting > 0 && !sellerOn
}
