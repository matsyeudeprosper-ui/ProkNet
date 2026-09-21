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

    /**
     * v0.16.0's signed bytes. `createdAt` is **not** in them.
     *
     * Kept only so claims already on phones and in Brain databases keep verifying. No
     * build after 66 ever creates one.
     */
    const val DOMAIN = "ProkNet-destination-claim-1"

    /**
     * v0.16.5: the same claim with its `createdAt` inside the signature.
     *
     * v0.16.4 made that timestamp decide when a new destination becomes active, on both
     * the phone and the Brain - while it was still unsigned. Anything carrying the claim
     * could have moved the cooling window: ten minutes earlier and a buyer is sent to a
     * number the seller is not watching yet; ten minutes later and the seller keeps being
     * paid on a number it has abandoned. A field that decides where money goes has to be
     * a signed fact.
     */
    const val DOMAIN_V2 = "ProkNet-destination-claim-2"

    /** Wire prefixes. The prefix says which bytes were signed, so it cannot be guessed. */
    const val WIRE_V1 = "dest1"
    const val WIRE_V2 = "dest2"

    /** Signature formats, as small integers. 0 means "did not verify at all". */
    const val FORMAT_LEGACY = 1
    const val FORMAT_SIGNED_TIME = 2

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

        /**
         * v0.16.0's signed bytes, without `createdAt`. Legacy: verified, never produced.
         */
        fun signData(): ByteArray =
            (DOMAIN + "|" + sellerId + "|" + rail.name + "|" + normalized + "|" + version).toByteArray(Charsets.UTF_8)

        /**
         * What a seller signs from v0.16.5 on:
         *
         *     ProkNet-destination-claim-2|<sellerId>|<RAIL>|<normalised msisdn>|<version>|<createdAt>
         *
         * `Destination.sign_data_v2` on the server builds the same string, and a fixture
         * pins the two together byte for byte.
         */
        fun signDataV2(): ByteArray =
            (DOMAIN_V2 + "|" + sellerId + "|" + rail.name + "|" + normalized + "|" + version +
                "|" + createdAt).toByteArray(Charsets.UTF_8)

        /** Safe to show: enough to read at a kiosk, not enough to publish. */
        fun masked(): String = PaymentExpectation.maskedNumber(msisdn)
    }

    /** What a seller signs now. Always the v2 bytes. */
    fun sign(c: Claim, signer: Signer): ByteArray = signer.sign(c.signDataV2())

    /**
     * Verify [c] in exactly one format.
     *
     * Strict on purpose: a claim that arrived as `dest2` must verify as v2, so a carrier
     * cannot relabel a legacy claim and have its unsigned timestamp treated as signed.
     */
    fun verify(c: Claim, sellerPub: ByteArray, sig: ByteArray,
               format: Int = FORMAT_SIGNED_TIME): Boolean {
        if (!c.valid) return false
        if (Crypto.deriveId(sellerPub).toHex() != c.sellerId) return false
        val data = when (format) {
            FORMAT_SIGNED_TIME -> c.signDataV2()
            FORMAT_LEGACY -> c.signData()
            else -> return false
        }
        return Crypto.verify(sellerPub, data, sig)
    }

    /**
     * Which format a stored claim was signed in, or 0 if neither verifies.
     *
     * The database keeps the fields and the signature, not the wire line, so this is how a
     * claim is re-encoded for the wire without another schema migration: try the format we
     * produce now, fall back to the one we used to.
     */
    fun formatOf(c: Claim, sellerPub: ByteArray, sig: ByteArray): Int = when {
        verify(c, sellerPub, sig, FORMAT_SIGNED_TIME) -> FORMAT_SIGNED_TIME
        verify(c, sellerPub, sig, FORMAT_LEGACY) -> FORMAT_LEGACY
        else -> 0
    }

    /**
     * v0.16.5: is this claim's cooling timestamp a signed fact?
     *
     * False for a legacy claim. Its timestamp is still used - refusing would break every
     * phone that has one, and would make a seller re-enter a number they never changed -
     * but nothing may describe it as authenticated, and the moment that seller changes
     * anything the replacement is v2.
     */
    fun timeIsSigned(format: Int): Boolean = format == FORMAT_SIGNED_TIME

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

    /**
     * v0.16.4: which of a seller's claims a payment should use at [now].
     *
     * The newest one, unless it is still cooling, in which case the one before it - so
     * there is never a moment when neither works and a transfer already on its way still
     * lands somewhere valid. A rail change is not special: a seller has one place it is
     * paid, and the operator is simply part of what changed.
     *
     * The window is measured from the claim's own [Claim.createdAt], which is inside the
     * bytes the seller signed. `PayBox.active_destination` on the server measures it from
     * the same field, and `server/tests/fixtures/crosslang.json` holds the cases both must
     * answer identically. It used to use the moment the server received the claim, which
     * the phone cannot see: a phone offline for an hour would have moved to its new number
     * while the Brain still sent buyers to the old one.
     *
     * Pure, and takes the claims rather than a store, so the fixture runs the same
     * function the phone runs.
     */
    fun active(claims: List<Claim>, now: Long): Claim? {
        if (claims.isEmpty()) return null
        val ordered = claims.sortedByDescending { it.version }
        val newest = ordered[0]
        val previous = ordered.getOrNull(1) ?: return newest
        return if (usable(newest, previous, now)) newest else previous
    }

    /**
     * v0.16.4: the destination hashes a seller must still accept a payment to.
     *
     * Two, during a change: the one that is active now, and the one that was active when
     * the buyer asked. A buyer given the old number just before the cooling window closed
     * has already walked to a kiosk with it, and the facts inside a signed expectation -
     * rail, destination, amount - cannot be rewritten afterwards. Refusing it because the
     * seller has since moved on would make the seller reject a payment it had itself asked
     * for, minutes earlier.
     *
     * Usually one hash, because usually nothing has changed.
     */
    fun acceptableHashes(claims: List<Claim>, now: Long, askedAt: Long): Set<String> =
        listOfNotNull(active(claims, now), active(claims, askedAt))
            .map { it.hash() }.toSet()

    fun coolingLine(): String = "Nouveau numéro enregistré. Il sera utilisé dans quelques minutes."

    // ---- the wire ----------------------------------------------------------------------------------

    /**
     * One line, so it can travel over the tunnel, the brain, or carried by another phone.
     *
     * The prefix names the signature format. The line already carried `createdAt`; from
     * v0.16.5 that field is also inside the signature, so altering it in transit breaks
     * the claim instead of silently moving the cooling window.
     */
    fun encode(c: Claim, sig: ByteArray, format: Int = FORMAT_SIGNED_TIME): String =
        listOf(if (format == FORMAT_LEGACY) WIRE_V1 else WIRE_V2,
            c.sellerId, c.rail.name, c.normalized, c.version.toString(),
            c.createdAt.toString(), sig.toHex()).joinToString("|")

    class Decoded(val claim: Claim, val sig: ByteArray, val format: Int = FORMAT_SIGNED_TIME) {
        /** True only when the timestamp cooling depends on was actually signed. */
        val timeIsSigned: Boolean get() = timeIsSigned(format)
    }

    fun decode(line: String): Decoded? {
        val p = line.split("|")
        if (p.size != 7) return null
        val format = when (p[0]) {
            WIRE_V2 -> FORMAT_SIGNED_TIME
            WIRE_V1 -> FORMAT_LEGACY
            else -> return null
        }
        return try {
            Decoded(Claim(p[1], Settlement.Rail.valueOf(p[2]), p[3], p[4].toInt(), p[5].toLong()),
                p[6].hexToBytes(), format)
        } catch (e: Exception) { null }
    }
}
