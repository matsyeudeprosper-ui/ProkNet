package net.prok.proknet.core

/**
 * v0.16.0: where a candidate payment message came from, and what we may conclude from it.
 *
 * Capture and interpretation are kept apart on purpose. Android decides *how* a message
 * reaches us — a notification, or the telephony SMS API — and none of the rest of ProkNet
 * should care which. What it must care about is how much that source is worth.
 */
object DeviceReceipt {

    /**
     * How the message reached the phone. The order is the order of trust.
     *
     * Neither of these is an operator attestation. MTN has not signed anything; we watched
     * a message arrive on the seller's own phone. That is honest evidence for a pilot and
     * it is not the same thing, which is why the names never say "operator".
     */
    enum class Source {
        /**
         * Read through the telephony SMS API. Stronger: the sender address comes from the
         * network rather than from whatever an app chose to display.
         */
        DIRECT_SMS,

        /**
         * Read from a notification posted by the phone's **default SMS application**. The
         * practical source for most builds, because the SMS permissions are restricted on
         * Play. Weaker, because a notification is a rendering, not the message.
         */
        DEFAULT_SMS_NOTIFICATION,
    }

    /** What we are willing to claim. Never collapse these into one word. */
    enum class Confidence {
        /** Seen through telephony SMS on the seller's device. */
        DEVICE_SMS_VERIFIED,

        /** Seen in the default SMS app's notification on the seller's device. */
        DEVICE_NOTIFICATION_VERIFIED,

        /**
         * MTN or Airtel confirmed it through their API. **Future only.** Nothing in this
         * build may ever produce this value, and a test enforces that.
         */
        OPERATOR_VERIFIED,
    }

    fun confidenceFor(s: Source): Confidence = when (s) {
        Source.DIRECT_SMS -> Confidence.DEVICE_SMS_VERIFIED
        Source.DEFAULT_SMS_NOTIFICATION -> Confidence.DEVICE_NOTIFICATION_VERIFIED
    }

    /** Both device levels clear a debt in the pilot. The operator level does not exist yet. */
    fun clearsDebt(c: Confidence): Boolean =
        c == Confidence.DEVICE_SMS_VERIFIED || c == Confidence.DEVICE_NOTIFICATION_VERIFIED

    /** What a developer diagnostic may print. Never shown to a user. */
    fun word(c: Confidence): String = when (c) {
        Confidence.DEVICE_SMS_VERIFIED -> "vérifié sur l'appareil (SMS)"
        Confidence.DEVICE_NOTIFICATION_VERIFIED -> "vérifié sur l'appareil (notification)"
        Confidence.OPERATOR_VERIFIED -> "vérifié par l'opérateur"
    }

    // ---- what a source hands over --------------------------------------------------------------------

    /**
     * One message that might be a payment. The raw text is carried only as far as the
     * parser and the hash; it is never stored, never uploaded and never leaves the phone.
     */
    class Candidate(
        val source: Source,
        /** The app that posted it, for the notification source. Recorded on every receipt. */
        val sourcePackage: String,
        /** The operator short code or sender, when the source knows it. */
        val sender: String,
        val text: String,
        val receivedAt: Long,
    ) {
        /** Identifies the message without keeping it. Used to reject the same one twice. */
        fun evidenceHash(): String =
            Crypto.sha256(("ProkNet-receipt-1|" + sender + "|" + text.trim()).toByteArray(Charsets.UTF_8)).toHex()
    }

    /**
     * Is this candidate allowed to be payment evidence at all?
     *
     * The attack this blocks is simple and obvious: any app can post a notification saying
     * "Vous avez reçu 50 CFA". So a notification counts only when the app that posted it is
     * the phone's **default SMS application**, which a hostile app cannot become silently.
     *
     * Explicitly refused, and none of these is an oversight:
     * a screenshot, clipboard text, text the buyer supplied, a sentence somebody typed.
     * Evidence is observed by the seller's phone or it is not evidence.
     */
    fun eligible(c: Candidate, defaultSmsPackage: String): Boolean = when (c.source) {
        Source.DIRECT_SMS -> c.text.isNotBlank()
        Source.DEFAULT_SMS_NOTIFICATION ->
            c.text.isNotBlank() && defaultSmsPackage.isNotEmpty() && c.sourcePackage == defaultSmsPackage
    }

    fun refusalReason(c: Candidate, defaultSmsPackage: String): String = when {
        c.text.isBlank() -> "the notification had no readable content"
        c.source == Source.DEFAULT_SMS_NOTIFICATION && defaultSmsPackage.isEmpty() ->
            "the default SMS application is unknown"
        c.source == Source.DEFAULT_SMS_NOTIFICATION && c.sourcePackage != defaultSmsPackage ->
            "posted by " + c.sourcePackage + ", not the default SMS application"
        else -> ""
    }

    // ---- the signed receipt ----------------------------------------------------------------------------

    /**
     * What the seller's phone signs when it observes a payment.
     *
     * Signed by the **seller**, because the seller is the one who observed it. The buyer
     * cannot produce this object, which is the entire point: the party who benefits from a
     * debt disappearing is not the party who attests that it was paid.
     */
    class Receipt(
        val paymentId: String,
        val sellerId: String,
        val buyerId: String,
        val rail: Settlement.Rail,
        val destinationHash: String,
        val expectedCentimes: Long,
        val observedCentimes: Long,
        val observedAt: Long,
        val source: Source,
        val sourcePackage: String,
        /** sha256 of the message. The message itself is never kept. */
        val messageEvidenceHash: String,
        val parserVersion: Int,
        val confidence: Confidence,
        val matchedSettlementIds: List<String>,
        /** Opportunistic; empty is normal and fine. */
        val reference: String = "",
    ) {
        val valid: Boolean
            get() = paymentId.isNotEmpty() && sellerId.isNotEmpty() && buyerId.isNotEmpty() &&
                sellerId != buyerId && destinationHash.isNotEmpty() &&
                expectedCentimes > 0 && observedCentimes == expectedCentimes &&
                observedAt > 0 && messageEvidenceHash.isNotEmpty() && matchedSettlementIds.isNotEmpty()

        /** The bytes the seller signs. Everything that matters is inside. */
        fun signData(): ByteArray = ("ProkNet-device-receipt-1|" + paymentId + "|" + sellerId + "|" + buyerId +
            "|" + rail.name + "|" + destinationHash + "|" + expectedCentimes + "|" + observedCentimes +
            "|" + observedAt + "|" + source.name + "|" + messageEvidenceHash + "|" + parserVersion +
            "|" + confidence.name + "|" + matchedSettlementIds.sorted().joinToString(",")
            ).toByteArray(Charsets.UTF_8)
    }

    /** Build the receipt for a match. Returns null when the match was not one we may act on. */
    fun receiptFor(
        e: PaymentExpectation.Expectation, c: Candidate, parsed: ReceiptParser.Parsed, observedAt: Long,
    ): Receipt? {
        if (!parsed.usable) return null
        if (parsed.amountCentimes != e.amountCentimes) return null
        val conf = confidenceFor(c.source)
        if (!clearsDebt(conf)) return null
        return Receipt(
            paymentId = e.paymentId, sellerId = e.sellerId, buyerId = e.buyerId, rail = e.rail,
            destinationHash = e.destinationHash, expectedCentimes = e.amountCentimes,
            observedCentimes = parsed.amountCentimes, observedAt = observedAt,
            source = c.source, sourcePackage = c.sourcePackage,
            messageEvidenceHash = c.evidenceHash(), parserVersion = parsed.parserVersion,
            confidence = conf, matchedSettlementIds = e.includedSettlementIds, reference = parsed.reference)
    }

    /** The seller's signature over [Receipt.signData]. */
    fun verify(r: Receipt, sellerPub: ByteArray, sig: ByteArray): Boolean =
        r.valid && Crypto.verify(sellerPub, r.signData(), sig)
}
