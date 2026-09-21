package net.prok.proknet.core

/**
 * v0.15.3: the signed proof that a session owed what it owed.
 *
 * The server stopped believing amounts in v0.15.1: it re-derives the money from the
 * signed contract and the signed closing checkpoint. But nothing on the phone actually
 * built and sent that package, so the verifier had nothing to verify. This is the missing
 * half.
 *
 * What goes up is **evidence, not results**: the exact bytes both phones signed, plus the
 * four signatures over them and the three public keys. The claimed settlement id and
 * amount travel too, but only as a cross-check the server may reject us on; it computes
 * the real ones itself.
 *
 * Nothing here needs a live session. Everything is read back from the database, so a
 * settlement survives a restart, a flat battery and a week offline.
 */
object Evidence {

    /** Matches `evidence.REQUIRED` on the server, field for field. */
    class Package(
        val settlementId: String,
        val sessionHex: String,
        val contract: ByteArray,
        val buyerContractSig: ByteArray,
        val sellerContractSig: ByteArray,
        val checkpoint: ByteArray,
        val sellerCheckpointSig: ByteArray,
        val buyerCheckpointSig: ByteArray,
        val buyerPub: ByteArray,
        val sellerPub: ByteArray,
        val submitterPub: ByteArray,
        /** Cross-check only. The server recomputes it and refuses us if we disagree. */
        val claimedGross: Long,
    ) {
        /**
         * The exact UTF-8 bytes that are signed and sent. Built once: the signature covers
         * these bytes, so they must never be re-serialised afterwards.
         */
        fun body(): ByteArray = json().toByteArray(Charsets.UTF_8)

        fun json(): String = StringBuilder().apply {
            append('{')
            field("contract", contract.toHex()); append(',')
            field("buyer_contract_sig", buyerContractSig.toHex()); append(',')
            field("seller_contract_sig", sellerContractSig.toHex()); append(',')
            field("checkpoint", checkpoint.toHex()); append(',')
            field("seller_checkpoint_sig", sellerCheckpointSig.toHex()); append(',')
            field("buyer_checkpoint_sig", buyerCheckpointSig.toHex()); append(',')
            field("buyer_pub", buyerPub.toHex()); append(',')
            field("seller_pub", sellerPub.toHex()); append(',')
            field("submitter_pub", submitterPub.toHex()); append(',')
            field("settlement_id", settlementId); append(',')
            append("\"gross\":").append(claimedGross)
            append('}')
        }.toString()

        private fun StringBuilder.field(k: String, v: String) {
            append('"').append(k).append("\":\"").append(v).append('"')
        }
    }

    /** Why a session cannot be reported. Each is a normal outcome, not an error. */
    enum class Missing { NONE, NO_SESSION, NO_CONTRACT, NOT_COMMERCIAL, NO_FINAL_CHECKPOINT, NO_SIGNATURES, NO_KEYS }

    class Result(val pkg: Package?, val missing: Missing) {
        val ok: Boolean get() = pkg != null
    }

    /**
     * Assemble the package from what was stored during the session.
     *
     * @param myPub        this phone's public key; it is the submitter
     * @param peerPub      the other phone's public key, learned during the handshake
     * @param iAmSeller    which side this phone was on
     *
     * Returns [Missing] rather than throwing, because most reasons are ordinary: a free
     * session owes nothing, and a session whose closing checkpoint was never countersigned
     * has nothing anybody may be billed for.
     */
    fun build(
        contractBytes: ByteArray?,
        contractBuyerSig: ByteArray?,
        contractSellerSig: ByteArray?,
        checkpointBytes: ByteArray?,
        checkpointSellerSig: ByteArray?,
        checkpointBuyerSig: ByteArray?,
        myPub: ByteArray?,
        peerPub: ByteArray?,
        iAmSeller: Boolean,
    ): Result {
        if (contractBytes == null) return Result(null, Missing.NO_SESSION)
        val c = Market.Contract.decode(contractBytes) ?: return Result(null, Missing.NO_CONTRACT)
        // v0.15 real money covers commercial v2 budget sessions only
        if (!c.budgetSession || c.rateCentimesPerMb <= 0) return Result(null, Missing.NOT_COMMERCIAL)
        if (contractBuyerSig == null || contractSellerSig == null) return Result(null, Missing.NO_SIGNATURES)
        if (checkpointBytes == null) return Result(null, Missing.NO_FINAL_CHECKPOINT)
        val cp = Market.Checkpoint.decode(checkpointBytes) ?: return Result(null, Missing.NO_FINAL_CHECKPOINT)
        if (!cp.final) return Result(null, Missing.NO_FINAL_CHECKPOINT)
        if (checkpointSellerSig == null || checkpointBuyerSig == null) return Result(null, Missing.NO_SIGNATURES)
        if (myPub == null || peerPub == null) return Result(null, Missing.NO_KEYS)

        val buyerPub = if (iAmSeller) peerPub else myPub
        val sellerPub = if (iAmSeller) myPub else peerPub
        val o = Settlement.fromSession(c, cp, System.currentTimeMillis()) ?: return Result(null, Missing.NOT_COMMERCIAL)
        return Result(Package(
            settlementId = o.settlementId,
            sessionHex = c.sessionHex,
            contract = contractBytes,
            buyerContractSig = contractBuyerSig,
            sellerContractSig = contractSellerSig,
            checkpoint = checkpointBytes,
            sellerCheckpointSig = checkpointSellerSig,
            buyerCheckpointSig = checkpointBuyerSig,
            buyerPub = buyerPub,
            sellerPub = sellerPub,
            submitterPub = myPub,
            claimedGross = o.grossCentimes), Missing.NONE)
    }

    // ---- how far a settlement has got with the server ---------------------------------------------

    enum class Sync {
        /** Derived locally, not sent yet. */
        PENDING,

        /** Sent and verified by the server. */
        REPORTED,

        /** The server verified it and the two phones disagree. A person decides. */
        DISPUTED,

        /** The server refused the evidence. It will not be retried; something is wrong. */
        REFUSED,

        /** There is no evidence to send, and there never will be. Free sessions live here. */
        NOT_APPLICABLE,
    }

    /** True while the queue should keep trying. */
    fun retryable(s: Sync): Boolean = s == Sync.PENDING

    /**
     * v0.15.3: what the technical details may say. The consumer cards say nothing about
     * servers, and a settlement that has not reached one is not "wrong".
     */
    fun word(s: Sync): String = when (s) {
        Sync.PENDING -> "En attente"
        Sync.REPORTED -> "Vérifié"
        Sync.DISPUTED -> "Contesté"
        Sync.REFUSED -> "Refusé"
        Sync.NOT_APPLICABLE -> "—"
    }

    // ---- bounded retry -----------------------------------------------------------------------------

    const val FIRST_BACKOFF_MS = 30_000L
    const val MAX_BACKOFF_MS = 6L * 3600 * 1000
    /** After this many failures we stop retrying and leave it for a person to look at. */
    const val MAX_ATTEMPTS = 12

    /**
     * Doubling, bounded, from the number of attempts already made. No tight loop, and a
     * phone that has been offline for a day does not hammer the server when it returns.
     */
    fun backoffMs(attempts: Int): Long {
        if (attempts <= 0) return 0
        var ms = FIRST_BACKOFF_MS
        for (i in 1 until attempts) {
            ms *= 2
            if (ms >= MAX_BACKOFF_MS) return MAX_BACKOFF_MS
        }
        return minOf(ms, MAX_BACKOFF_MS)
    }

    fun dueAt(attempts: Int, lastAttempt: Long): Long = lastAttempt + backoffMs(attempts)

    fun mayTry(attempts: Int, lastAttempt: Long, now: Long): Boolean =
        attempts < MAX_ATTEMPTS && now >= dueAt(attempts, lastAttempt)

    /**
     * What the server said. A duplicate is a success: the settlement is deterministic, so
     * "already reported" means exactly what we wanted.
     */
    fun interpret(httpCode: Int, responseBody: String): Sync = when {
        httpCode in 200..299 && responseBody.contains("\"status\": \"DISPUTED\"") -> Sync.DISPUTED
        httpCode in 200..299 && responseBody.contains("\"status\":\"DISPUTED\"") -> Sync.DISPUTED
        httpCode in 200..299 -> Sync.REPORTED
        // the evidence itself is wrong; retrying the same bytes cannot help
        httpCode == 400 || httpCode == 403 -> Sync.REFUSED
        else -> Sync.PENDING          // 401 clock skew, 5xx, timeouts: try again later
    }
}
