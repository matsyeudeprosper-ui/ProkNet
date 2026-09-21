package net.prok.proknet.core

/**
 * v0.16.1: the four payment messages that actually have to cross between two phones.
 *
 * v0.16.0 built the models and left them on whichever phone created them, so nothing in
 * the flow could complete. This is the wire, and the whole flow is only four messages:
 *
 * ```
 * seller  --DESTINATION_CLAIM-->  buyer     where to send the cash
 * buyer   --PAYMENT_EXPECTATION-> seller    what to watch for, and for how long
 * seller  --EXPECTATION_REPLY-->  buyer     accepted, or busy with the same amount
 * seller  --PAYMENT_RECEIPT----->  buyer    the operator's money arrived; debt cleared
 * ```
 *
 * Every one is versioned, carries its own id, and is **signed by the party whose claim it
 * is**: the seller says where the seller is paid, the buyer says what the buyer will pay,
 * and the seller says what the seller observed. The receiver verifies before it persists
 * or acts, never after.
 *
 * The encoding is a single line of `|`-separated fields so the same bytes can travel over
 * the authenticated control channel today, through the Brain when the phones are apart,
 * and be carried by a third phone later, without a second protocol.
 */
object PayWire {

    const val VERSION = 1

    const val T_DESTINATION_CLAIM = "pay1.dest"
    const val T_EXPECTATION = "pay1.exp"
    const val T_EXPECTATION_REPLY = "pay1.expreply"
    const val T_RECEIPT = "pay1.receipt"
    const val T_EXPECTATION_END = "pay1.expend"

    /** Domain separators. Distinct per message, so a signature can never be reused across types. */
    const val D_EXPECTATION = "ProkNet-payment-expectation-1"
    const val D_EXPECTATION_END = "ProkNet-payment-expectation-end-1"

    private const val SEP = "|"

    /** `|` and newlines would break the framing, so they can never appear in a field. */
    private fun safe(s: String): String = s.replace("|", "/").replace("\n", " ").replace("\r", " ")

    fun typeOf(line: String): String = line.substringBefore(SEP)

    // ---- 1. where the seller is paid ------------------------------------------------------------------

    /** Reuses [DestinationClaim]'s own signed encoding; this only adds the envelope. */
    fun destinationClaim(c: DestinationClaim.Claim, sig: ByteArray): String =
        T_DESTINATION_CLAIM + SEP + DestinationClaim.encode(c, sig)

    fun parseDestinationClaim(line: String): DestinationClaim.Decoded? {
        if (typeOf(line) != T_DESTINATION_CLAIM) return null
        return DestinationClaim.decode(line.substringAfter(SEP))
    }

    // ---- 2. what the buyer is about to pay --------------------------------------------------------------

    /**
     * Signed by the **buyer**. Without this the seller would be watching for amounts any
     * phone nearby asked it to watch for, which is exactly the door this closes.
     */
    fun expectationSignData(e: PaymentExpectation.Expectation): ByteArray =
        (D_EXPECTATION + SEP + VERSION + SEP + e.paymentId + SEP + e.buyerId + SEP + e.sellerId +
            SEP + e.rail.name + SEP + e.destinationHash + SEP + e.amountCentimes +
            SEP + e.createdAt + SEP + e.validFrom + SEP + e.expiresAt +
            SEP + e.includedSettlementIds.sorted().joinToString(",")).toByteArray(Charsets.UTF_8)

    fun expectation(e: PaymentExpectation.Expectation, sig: ByteArray): String = listOf(
        T_EXPECTATION, VERSION.toString(), e.paymentId, e.buyerId, e.sellerId, e.rail.name,
        e.destinationHash, e.amountCentimes.toString(), e.createdAt.toString(),
        e.validFrom.toString(), e.expiresAt.toString(),
        e.includedSettlementIds.joinToString(","), sig.toHex()).joinToString(SEP) { safe(it) }

    class SignedExpectation(val expectation: PaymentExpectation.Expectation, val sig: ByteArray)

    fun parseExpectation(line: String): SignedExpectation? {
        val p = line.split(SEP)
        if (p.size != 13 || p[0] != T_EXPECTATION || p[1] != VERSION.toString()) return null
        return try {
            SignedExpectation(PaymentExpectation.Expectation(
                paymentId = p[2], buyerId = p[3], sellerId = p[4],
                rail = Settlement.Rail.valueOf(p[5]), destinationHash = p[6],
                amountCentimes = p[7].toLong(), createdAt = p[8].toLong(),
                validFrom = p[9].toLong(), expiresAt = p[10].toLong(),
                includedSettlementIds = p[11].split(",").filter { it.isNotEmpty() }),
                p[12].hexToBytes())
        } catch (e: Exception) { null }
    }

    /**
     * Verify an expectation the way the seller must: the signer is the buyer it names, and
     * the buyer is not the seller.
     */
    fun verifyExpectation(s: SignedExpectation, buyerPub: ByteArray): Boolean {
        val e = s.expectation
        if (Crypto.deriveId(buyerPub).toHex() != e.buyerId) return false
        if (e.buyerId == e.sellerId) return false
        return Crypto.verify(buyerPub, expectationSignData(e), s.sig)
    }

    // ---- 3. the seller's answer ---------------------------------------------------------------------------

    /**
     * Why the seller is or is not watching. `BUSY_SAME_AMOUNT` is the one that preserves
     * the kiosk habit: two buyers owing the same amount take turns rather than being
     * charged different amounts to tell them apart.
     */
    enum class Reply { ACCEPTED, BUSY_SAME_AMOUNT, UNKNOWN_DESTINATION, NOT_FOR_ME, BAD_SIGNATURE, BAD_AMOUNT, EXPIRED, NOT_READY }

    fun expectationReply(paymentId: String, r: Reply): String =
        listOf(T_EXPECTATION_REPLY, VERSION.toString(), paymentId, r.name).joinToString(SEP) { safe(it) }

    class ParsedReply(val paymentId: String, val reply: Reply)

    fun parseExpectationReply(line: String): ParsedReply? {
        val p = line.split(SEP)
        if (p.size != 4 || p[0] != T_EXPECTATION_REPLY || p[1] != VERSION.toString()) return null
        return try { ParsedReply(p[2], Reply.valueOf(p[3])) } catch (e: Exception) { null }
    }

    /** Only this answer means the seller is actually watching. */
    fun ready(r: Reply): Boolean = r == Reply.ACCEPTED

    fun replyLine(r: Reply): String = when (r) {
        Reply.ACCEPTED -> "La vérification automatique est prête."
        Reply.BUSY_SAME_AMOUNT -> "Un paiement du même montant est déjà en cours. Réessayez dans quelques minutes."
        Reply.UNKNOWN_DESTINATION -> "Le fournisseur n'a pas encore indiqué où recevoir son paiement."
        Reply.NOT_READY -> "Le fournisseur ne peut pas encore vérifier les paiements automatiquement."
        Reply.BAD_AMOUNT -> "Le montant ne correspond pas aux sessions à régler."
        Reply.EXPIRED -> "Cette demande de paiement a expiré."
        else -> "Paiement impossible pour le moment."
    }

    // ---- 4. the money arrived ------------------------------------------------------------------------------

    /** Signed by the **seller**, who observed it. Reuses [DeviceReceipt.Receipt.signData]. */
    fun receipt(r: DeviceReceipt.Receipt, sig: ByteArray): String = listOf(
        T_RECEIPT, VERSION.toString(), r.paymentId, r.sellerId, r.buyerId, r.rail.name,
        r.destinationHash, r.expectedCentimes.toString(), r.observedCentimes.toString(),
        r.observedAt.toString(), r.source.name, r.sourcePackage, r.messageEvidenceHash,
        r.parserVersion.toString(), r.confidence.name,
        r.matchedSettlementIds.joinToString(","), r.reference, sig.toHex())
        .joinToString(SEP) { safe(it) }

    class SignedReceipt(val receipt: DeviceReceipt.Receipt, val sig: ByteArray)

    fun parseReceipt(line: String): SignedReceipt? {
        val p = line.split(SEP)
        if (p.size != 18 || p[0] != T_RECEIPT || p[1] != VERSION.toString()) return null
        return try {
            SignedReceipt(DeviceReceipt.Receipt(
                paymentId = p[2], sellerId = p[3], buyerId = p[4],
                rail = Settlement.Rail.valueOf(p[5]), destinationHash = p[6],
                expectedCentimes = p[7].toLong(), observedCentimes = p[8].toLong(),
                observedAt = p[9].toLong(), source = DeviceReceipt.Source.valueOf(p[10]),
                sourcePackage = p[11], messageEvidenceHash = p[12],
                parserVersion = p[13].toInt(), confidence = DeviceReceipt.Confidence.valueOf(p[14]),
                matchedSettlementIds = p[15].split(",").filter { it.isNotEmpty() },
                reference = p[16]), p[17].hexToBytes())
        } catch (e: Exception) { null }
    }

    // ---- 5. this window is over ----------------------------------------------------------------------------

    /**
     * Sent when a payment matched, the buyer cancelled, or the window closed. Without it
     * the seller would hold the same-amount lock for the full twenty minutes after a
     * payment has already succeeded, and the next buyer owing that amount would wait for
     * nothing.
     */
    fun expectationEndSignData(paymentId: String, buyerId: String, reason: String): ByteArray =
        (D_EXPECTATION_END + SEP + VERSION + SEP + paymentId + SEP + buyerId + SEP + reason).toByteArray(Charsets.UTF_8)

    fun expectationEnd(paymentId: String, buyerId: String, reason: String, sig: ByteArray): String =
        listOf(T_EXPECTATION_END, VERSION.toString(), paymentId, buyerId, reason, sig.toHex())
            .joinToString(SEP) { safe(it) }

    class ParsedEnd(val paymentId: String, val buyerId: String, val reason: String, val sig: ByteArray)

    fun parseExpectationEnd(line: String): ParsedEnd? {
        val p = line.split(SEP)
        if (p.size != 6 || p[0] != T_EXPECTATION_END || p[1] != VERSION.toString()) return null
        return try { ParsedEnd(p[2], p[3], p[4], p[5].hexToBytes()) } catch (e: Exception) { null }
    }

    fun verifyEnd(e: ParsedEnd, buyerPub: ByteArray): Boolean =
        Crypto.deriveId(buyerPub).toHex() == e.buyerId &&
            Crypto.verify(buyerPub, expectationEndSignData(e.paymentId, e.buyerId, e.reason), e.sig)

    // ---- the seller's decision -------------------------------------------------------------------------------

    /**
     * Everything the seller checks before it will watch for an amount, in one place so the
     * tests run the same decision the phone runs.
     *
     * @param myId            this phone's identity
     * @param myDestHash      the destination hash that is ACTIVE right now, which during a
     *                        cooling period is still the old number
     * @param outstanding     what each named settlement still owes, from the seller's own records
     * @param liveSameAmount  a live accepted expectation for this amount already exists
     */
    fun sellerDecision(
        s: SignedExpectation, buyerPub: ByteArray?, myId: String, myDestHash: String,
        outstanding: Map<String, Long>, liveSameAmount: Boolean, ready: Boolean, now: Long,
    ): Reply {
        val e = s.expectation
        if (e.sellerId != myId) return Reply.NOT_FOR_ME
        if (buyerPub == null || !verifyExpectation(s, buyerPub)) return Reply.BAD_SIGNATURE
        if (!ready) return Reply.NOT_READY
        if (myDestHash.isEmpty()) return Reply.UNKNOWN_DESTINATION
        if (e.destinationHash != myDestHash) return Reply.UNKNOWN_DESTINATION
        if (now >= e.expiresAt) return Reply.EXPIRED
        if (e.expiresAt - e.createdAt > 2 * PaymentExpectation.DEFAULT_WINDOW_MS) return Reply.EXPIRED
        if (e.includedSettlementIds.isEmpty() || e.amountCentimes <= 0) return Reply.BAD_AMOUNT
        // the amount must be exactly what those sessions still owe, derived from the
        // seller's own records rather than taken from the buyer's word.
        //
        // Every named session must be one we know about AND still owe something on. A
        // settled or unknown session in the list is refused rather than ignored: padding
        // the list is how a receipt would later be able to reach a debt the payment never
        // covered.
        val amounts = e.includedSettlementIds.map { outstanding[it] ?: -1L }
        if (amounts.any { it <= 0L }) return Reply.BAD_AMOUNT
        if (amounts.sum() != e.amountCentimes) return Reply.BAD_AMOUNT
        if (liveSameAmount) return Reply.BUSY_SAME_AMOUNT
        return Reply.ACCEPTED
    }

    /**
     * Everything the buyer checks before a receipt is allowed to clear anything.
     *
     * The rule that matters most: a receipt may only clear the settlements its own signed
     * expectation named. Without that, one receipt could be shifted onto a different debt
     * later.
     */
    fun buyerAcceptsReceipt(
        r: SignedReceipt, sellerPub: ByteArray?, myId: String,
        expectation: PaymentExpectation.Expectation?,
    ): Boolean {
        val x = r.receipt
        if (x.buyerId != myId) return false
        if (sellerPub == null) return false
        if (Crypto.deriveId(sellerPub).toHex() != x.sellerId) return false
        if (!DeviceReceipt.verify(x, sellerPub, r.sig)) return false
        if (!DeviceReceipt.clearsDebt(x.confidence)) return false
        val e = expectation ?: return false
        if (e.paymentId != x.paymentId) return false
        if (e.buyerId != x.buyerId || e.sellerId != x.sellerId) return false
        if (e.amountCentimes != x.expectedCentimes) return false
        if (e.destinationHash != x.destinationHash) return false
        // no settlement may appear that the buyer did not put in the expectation
        return x.matchedSettlementIds.isNotEmpty() &&
            e.includedSettlementIds.containsAll(x.matchedSettlementIds)
    }
}
