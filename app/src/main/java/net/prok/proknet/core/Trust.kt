package net.prok.proknet.core

/**
 * v0.16.0: how much unpaid Internet a stranger gets, and what happens when they reinstall.
 *
 * This is designed on the assumption that **some people will simply not pay**, and that
 * designing around goodwill would be naive. The defence is not moral, it is economic: make
 * the most anybody can take small enough that taking it is not worth the trouble.
 *
 * A brand new identity gets about one short session. If they do not pay, they get nothing
 * more until they do. Free Internet and sponsored Internet are never affected, because
 * neither costs the seller anything to give.
 *
 * Trust then grows only through payments that the **seller's phone observed by itself**.
 * A button saying "I paid" does not exist and must never exist: it would hand the decision
 * to exactly the person who benefits from the wrong answer.
 */
object Trust {

    /** Pilot values. Business policy, deliberately not protocol constants. */
    class Policy(
        val newLimitCentimes: Long = 1_000,          // 10 CFA: about one short session
        val provenLimitCentimes: Long = 2_500,       // 25 CFA after 3 verified payments
        val establishedLimitCentimes: Long = 5_000,  // 50 CFA after 10
        val strongLimitCentimes: Long = 10_000,      // 100 CFA with a strong history
        val provenAfter: Int = 3,
        val establishedAfter: Int = 10,
        val strongAfter: Int = 25,
    )

    val DEFAULT = Policy()

    enum class Tier { NEW, PROVEN, ESTABLISHED, STRONG, BLOCKED }

    /**
     * @param verifiedPayments payments cleared by observed evidence. Nothing else counts.
     * @param deviceHasUnresolvedDebt true when this device previously owed money under a
     *        different identity that is still unpaid.
     */
    fun tier(verifiedPayments: Int, deviceHasUnresolvedDebt: Boolean, policy: Policy = DEFAULT): Tier = when {
        deviceHasUnresolvedDebt -> Tier.BLOCKED
        verifiedPayments >= policy.strongAfter -> Tier.STRONG
        verifiedPayments >= policy.establishedAfter -> Tier.ESTABLISHED
        verifiedPayments >= policy.provenAfter -> Tier.PROVEN
        else -> Tier.NEW
    }

    fun limitCentimes(t: Tier, policy: Policy = DEFAULT): Long = when (t) {
        Tier.BLOCKED -> 0
        Tier.NEW -> policy.newLimitCentimes
        Tier.PROVEN -> policy.provenLimitCentimes
        Tier.ESTABLISHED -> policy.establishedLimitCentimes
        Tier.STRONG -> policy.strongLimitCentimes
    }

    class Verdict(val allowed: Boolean, val limitCentimes: Long, val owedCentimes: Long, val reason: String) {
        val mustSettleCentimes: Long get() = if (allowed) 0 else owedCentimes
    }

    /**
     * May this phone start another **paid** session?
     *
     * Asked before any Bluetooth channel, handshake or probe, so a blocked buyer is told on
     * the home screen rather than after a connection has been built and paid for.
     */
    fun admitPaidSession(
        owedCentimes: Long, verifiedPayments: Int, deviceHasUnresolvedDebt: Boolean, policy: Policy = DEFAULT,
    ): Verdict {
        val t = tier(verifiedPayments, deviceHasUnresolvedDebt, policy)
        if (t == Tier.BLOCKED) return Verdict(false, 0, owedCentimes,
            "une dette non réglée existe déjà sur cet appareil")
        val limit = limitCentimes(t, policy)
        // the rule that makes deliberate theft boring: owe anything at the limit and the
        // next paid session simply does not happen
        if (owedCentimes >= limit) return Verdict(false, limit, owedCentimes, "limite de crédit atteinte")
        return Verdict(true, limit, owedCentimes, "dans la limite de crédit")
    }

    fun settleSentence(owedCentimes: Long): String = "Réglez " + Market.cfa(owedCentimes) + " pour continuer"

    /** What a seller may honestly be told about a buyer they have not met. */
    fun buyerRiskLine(t: Tier, limit: Long): String = when (t) {
        Tier.BLOCKED -> "Cet acheteur a une dette non réglée."
        Tier.NEW -> "Nouvel acheteur · crédit limité à " + Market.cfa(limit)
        else -> "Acheteur connu · crédit jusqu'à " + Market.cfa(limit)
    }

    // ---- reinstall ---------------------------------------------------------------------------------

    /**
     * v0.16.0: a pseudonym for this phone, so that owing 10 CFA and reinstalling does not
     * hand somebody a fresh 10 CFA.
     *
     * **This is anti-abuse, not identity.** It is deliberately weak in a specific way: no
     * hardware serial, no IMEI, no advertising id, nothing that identifies a person or
     * follows them to another app. What goes in is an app-scoped value Android is willing
     * to give us, and it is hashed with a domain separator before it is stored or sent, so
     * what leaves the phone cannot be reversed or correlated with anything outside ProkNet.
     *
     * A factory reset defeats it. Clearing app data may defeat it. Somebody determined
     * enough will get through, and that is exactly why the exposure on the other side of
     * it is one short session. We would rather be bypassable than collect invasive
     * identifiers to pretend otherwise.
     */
    const val DEVICE_DOMAIN = "ProkNet-device-v1"

    fun devicePseudonym(appScopedId: String): String {
        if (appScopedId.isBlank()) return ""
        return Crypto.sha256((DEVICE_DOMAIN + "|" + appScopedId).toByteArray(Charsets.UTF_8))
            .toHex().substring(0, 32)
    }

    /**
     * The server's view: this device, the identities it has used, and whether any of them
     * walked away from money.
     */
    class DeviceHistory(val pseudonym: String, val identities: List<String>, val unresolvedCentimes: Long) {
        val hasUnresolvedDebt: Boolean get() = unresolvedCentimes > 0
    }

    /**
     * A new identity on a device that already owes money starts with no credit at all.
     * Not banned: free and sponsored Internet still work, and paying the old debt restores
     * everything.
     */
    fun creditForNewIdentity(history: DeviceHistory?, policy: Policy = DEFAULT): Long =
        if (history != null && history.hasUnresolvedDebt) 0 else policy.newLimitCentimes

    // ---- what the seller needs before taking paid work ------------------------------------------------

    enum class SellerReadiness { READY, NO_DESTINATION, NO_DETECTION, NOTHING }

    /**
     * A seller cannot be paid automatically without somewhere to be paid and some way to
     * notice. Saying otherwise would be promising money we cannot deliver, so paid sharing
     * is gated and free sharing is not.
     */
    fun sellerReadiness(hasDestination: Boolean, hasDetection: Boolean): SellerReadiness = when {
        hasDestination && hasDetection -> SellerReadiness.READY
        hasDestination -> SellerReadiness.NO_DETECTION
        hasDetection -> SellerReadiness.NO_DESTINATION
        else -> SellerReadiness.NOTHING
    }

    fun sellerReadinessLine(r: SellerReadiness): String = when (r) {
        SellerReadiness.READY -> "Vérification automatique · Activée ✓"
        SellerReadiness.NO_DETECTION -> "Activez la vérification automatique pour recevoir des paiements."
        SellerReadiness.NO_DESTINATION -> "Ajoutez votre numéro Mobile Money pour recevoir des paiements."
        SellerReadiness.NOTHING -> "Configurez un moyen de réception pour être payé automatiquement."
    }

    /** Free and sponsored sharing never depend on any of this. */
    fun mayShareForFree(): Boolean = true
    fun mayShareForMoney(r: SellerReadiness): Boolean = r == SellerReadiness.READY
}
