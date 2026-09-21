package net.prok.proknet.core

/**
 * v0.16.0: where a seller says it wants to be paid, signed by the seller.
 *
 * v0.15.3 added a `payment_destinations` table on the server and a check against it, but
 * nothing in the app ever established the first entry, so the check could never bite. This
 * is the missing piece, and it matters more than it looks: the destination is the one field
 * where getting it wrong sends somebody's cash to a stranger.
 *
 * The rule: **only the seller may say where the seller is paid.** The buyer receives the
 * number so it can be read aloud at a kiosk, and can never propose or alter it. Changing an
 * established destination needs a new claim signed by the same identity, at a higher
 * version, so a replayed old claim cannot quietly move the money back.
 */
object DestinationClaim {

    const val DOMAIN = "ProkNet-destination-claim-1"

    class Claim(
        val sellerId: String,
        val rail: Settlement.Rail,
        /** Normalised, so the same number written three ways is one destination. */
        val msisdn: String,
        /** Increases on every change. An older version may never replace a newer one. */
        val version: Int,
        val createdAt: Long,
    ) {
        val valid: Boolean
            get() = sellerId.isNotEmpty() && version >= 1 && createdAt > 0 &&
                rail != Settlement.Rail.NONE &&
                PaymentExpectation.normalizeMsisdn(msisdn).length in 6..15

        val normalized: String get() = PaymentExpectation.normalizeMsisdn(msisdn)

        fun hash(): String = PaymentExpectation.destinationHash(rail, msisdn)

        /** What the seller signs. The version is inside, so a downgrade is not signable. */
        fun signData(): ByteArray =
            (DOMAIN + "|" + sellerId + "|" + rail.name + "|" + normalized + "|" + version).toByteArray(Charsets.UTF_8)

        /** Safe to show: enough to read at a kiosk, not enough to publish. */
        fun masked(): String = PaymentExpectation.maskedNumber(msisdn)
    }

    fun verify(c: Claim, sellerPub: ByteArray, sig: ByteArray): Boolean =
        c.valid && Crypto.deriveId(sellerPub).toHex() == c.sellerId && Crypto.verify(sellerPub, c.signData(), sig)

    /**
     * May [next] replace [current]?
     *
     * Same seller, same rail, strictly higher version. Everything else is refused, which
     * covers the replay of an old claim and any attempt by another identity to redirect a
     * seller's money.
     */
    /**
     * v0.16.3: a seller may change operator.
     *
     * This used to refuse any claim on a different rail from the one already held, which
     * meant a seller who moved from MTN to Airtel could not say so - their own phone
     * rejected the claim it had just made, and buyers kept being sent to an abandoned
     * number. A seller has ONE place it is paid, and the operator is part of what can
     * change; the version is what decides, and the signature is what proves the seller
     * asked for it.
     */
    fun mayReplace(current: Claim?, next: Claim): Boolean = when {
        !next.valid -> false
        current == null -> true
        current.sellerId != next.sellerId -> false
        else -> next.version > current.version
    }

    /** The next version a seller should use when changing its number. */
    fun nextVersion(current: Claim?): Int = (current?.version ?: 0) + 1

    /**
     * v0.16.0: a changed destination is worth a moment's pause once real money is moving,
     * so a stolen phone cannot redirect payments instantly. For the pilot this is a
     * statement of intent with a short delay rather than a full cooling-off process.
     */
    const val CHANGE_COOLING_MS = 10 * 60 * 1000L

    fun usableFrom(c: Claim, previous: Claim?): Long =
        if (previous == null) c.createdAt else c.createdAt + CHANGE_COOLING_MS

    fun usable(c: Claim, previous: Claim?, now: Long): Boolean = now >= usableFrom(c, previous)

    fun coolingLine(): String = "Nouveau numéro enregistré. Il sera utilisé dans quelques minutes."

    // ---- the wire ----------------------------------------------------------------------------------

    /** One line, so it can travel over the tunnel, the brain, or carried by another phone. */
    fun encode(c: Claim, sig: ByteArray): String =
        listOf("dest1", c.sellerId, c.rail.name, c.normalized, c.version.toString(),
            c.createdAt.toString(), sig.toHex()).joinToString("|")

    class Decoded(val claim: Claim, val sig: ByteArray)

    fun decode(line: String): Decoded? {
        val p = line.split("|")
        if (p.size != 7 || p[0] != "dest1") return null
        return try {
            Decoded(Claim(p[1], Settlement.Rail.valueOf(p[2]), p[3], p[4].toInt(), p[5].toLong()), p[6].hexToBytes())
        } catch (e: Exception) { null }
    }
}
