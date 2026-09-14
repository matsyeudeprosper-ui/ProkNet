package net.prok.proknet.core

import java.nio.ByteBuffer

/**
 * Connectivity marketplace, pure part (v0.7): offers, ranking, pricing,
 * session contracts, signed usage checkpoints and the internal ledger.
 * No Android, no networking, no floating point for money. Unit-tested.
 *
 * Units: prices are CFA per MB (1 MB = 1_000_000 bytes); costs and ledger
 * amounts are in CENTIMES (1/100 CFA), rounded half up.
 */
object Market {
    const val PRICING_VERSION = 1
    const val MB = 1_000_000L
    const val MAX_PRICE_PER_MB = 100_000      // CFA; anything above is malformed
    const val MAX_MIN_PRICE = 1_000_000       // CFA
    const val MAX_MB_PER_SESSION = 100_000    // 100 GB cap; 0 = unlimited
    const val MAX_FEE_PCT = 50
    const val DEFAULT_FEE_PCT = 5
    const val MAX_BILLABLE_BYTES = 1L shl 40  // 1 TB: beyond this a checkpoint is malformed
    const val CHECKPOINT_INTERVAL_MS = 30_000L
    const val CHECKPOINT_INTERVAL_BYTES = 1L * MB
    /** How far the seller's claimed bytes may exceed the buyer's own count before the buyer disputes. */
    const val TOLERANCE_BYTES = 64 * 1024L
    const val TOLERANCE_PERMILLE = 100L       // 10 %

    // ---- capability / offer flags (BLE scan response) --------------------------------------------

    const val FLAG_SELL = 1        // Internet for sale (seller mode on, upstream present)
    const val FLAG_RELAY = 2       // willing to carry/forward for others
    const val FLAG_VALIDATED = 4   // upstream validated by Android
    const val FLAG_VIA_RELAY = 8   // v0.9: this phone relays a seller behind it (price = that seller's price)
    const val UPSTREAM_SHIFT = 4   // bits 4-5: Tunnel.UP_* (0 none, 1 cellular, 2 wifi, 3 other)

    fun flags(sell: Boolean, relay: Boolean, validated: Boolean, upstreamType: Int, viaRelay: Boolean = false): Int =
        (if (sell) FLAG_SELL else 0) or (if (relay) FLAG_RELAY else 0) or (if (validated) FLAG_VALIDATED else 0) or (if (viaRelay) FLAG_VIA_RELAY else 0) or ((upstreamType and 3) shl UPSTREAM_SHIFT)

    fun upstreamOf(flags: Int): Int = (flags shr UPSTREAM_SHIFT) and 3

    /** What a buyer sees in the scan before any connection. */
    class Offer(val sellerShort: String, val pricePerMb: Int, val flags: Int, val rssi: Int, val lastSeen: Long) {
        val selling get() = flags and FLAG_SELL != 0
        val relaying get() = flags and FLAG_RELAY != 0
        val validated get() = flags and FLAG_VALIDATED != 0
        val viaRelay get() = flags and FLAG_VIA_RELAY != 0
        val upstreamType get() = upstreamOf(flags)
        fun describe(): String = "prok-" + sellerShort + "  " + Tunnel.upstreamName(upstreamType) + (if (viaRelay) " via relay" else "") + "  " + pricePerMb + " CFA/MB  signal " + signalWord(rssi) + "  " +
            (if (selling) (if (validated) "available" else "available (unverified)") else "not selling")
    }

    fun signalWord(rssi: Int): String = when { rssi >= -60 -> "good"; rssi >= -75 -> "ok"; rssi >= -90 -> "weak"; else -> "poor" }

    /**
     * Deterministic ranking: unavailable offers sort last; otherwise
     * validated Internet + cheaper price + stronger signal win, in that order of
     * weight (a 15 CFA/MB difference outweighs any signal difference). Ties break on seller ID.
     * score = validated*100 + priceScore(0..60, 3 points per CFA) + signalScore(0..40)
     */
    fun score(o: Offer): Int {
        if (!o.selling) return -1
        val validated = if (o.validated) 100 else 0
        val price = 60 - o.pricePerMb.coerceIn(0, 20) * 3 // 0 CFA -> 60, 20+ CFA -> 0
        val signal = ((o.rssi.coerceIn(-100, -40) + 100) * 40) / 60 // -100 -> 0, -40 -> 40
        val relayed = if (o.viaRelay) 10 else 0                        // v0.9: one more hop, same price -> direct first
        return validated + price + signal - relayed
    }

    fun rank(offers: List<Offer>): List<Offer> = offers.sortedWith(compareByDescending<Offer> { score(it) }.thenBy { it.pricePerMb }.thenBy { it.sellerShort })

    // ---- pricing ------------------------------------------------------------------------------------

    fun validPrice(pricePerMb: Int) = pricePerMb in 0..MAX_PRICE_PER_MB
    fun validMinPrice(min: Int) = min in 0..MAX_MIN_PRICE
    fun validMaxMb(maxMb: Int) = maxMb in 0..MAX_MB_PER_SESSION
    fun validFee(pct: Int) = pct in 0..MAX_FEE_PCT

    /** Cost in centimes for [bytes] at [pricePerMb] CFA/MB, rounded half up. Exact integer math. */
    fun costCentimes(bytes: Long, pricePerMb: Int): Long {
        require(bytes in 0..MAX_BILLABLE_BYTES) { "bytes out of range" }
        require(validPrice(pricePerMb)) { "price out of range" }
        // bytes * price * 100 / MB, half up. Max: 2^40 * 1e5 * 100 = 1.1e17 < 9.2e18
        val num = bytes * pricePerMb.toLong() * 100L
        return (num + MB / 2) / MB
    }

    /** Session cost: usage cost, but never below the agreed minimum. */
    fun sessionCost(bytes: Long, pricePerMb: Int, minPriceCfa: Int): Long = maxOf(costCentimes(bytes, pricePerMb), minPriceCfa.toLong() * 100L)

    class Split(val gross: Long, val fee: Long, val sellerNet: Long)

    /** Prok fee as a percentage of the gross, half up; seller gets the rest. Sums exactly. */
    fun split(grossCentimes: Long, feePct: Int): Split {
        require(grossCentimes >= 0 && validFee(feePct))
        val fee = (grossCentimes * feePct + 50) / 100
        return Split(grossCentimes, fee, grossCentimes - fee)
    }

    fun cfa(centimes: Long): String {
        val neg = centimes < 0; val c = Math.abs(centimes)
        val s = (c / 100).toString() + "." + ((c % 100).toString().padStart(2, '0')) + " CFA"
        return if (neg) "-" + s else s
    }

    fun mb(bytes: Long): String = String.format("%.2f MB", bytes / MB.toDouble())

    // ---- session contract -------------------------------------------------------------------------

    /**
     * Terms both phones sign before any Internet flows. Immutable for the session.
     * Encoded: [ver 1][sessionId 8][buyerId 16][sellerId 16][price u32][minPrice u32][maxMb u32][feePct u8][startTs u64]
     */
    class Contract(
        val sessionId: ByteArray, val buyerId: ByteArray, val sellerId: ByteArray,
        val pricePerMb: Int, val minPriceCfa: Int, val maxMb: Int, val feePct: Int, val startTs: Long,
        val version: Int = PRICING_VERSION,
    ) {
        val sessionHex get() = sessionId.toHex()
        val buyerShort get() = buyerId.toHex().substring(0, 8)
        val sellerShort get() = sellerId.toHex().substring(0, 8)
        val maxBytes: Long get() = if (maxMb == 0) Long.MAX_VALUE else maxMb.toLong() * MB

        fun encode(): ByteArray = ByteBuffer.allocate(1 + 8 + 16 + 16 + 4 + 4 + 4 + 1 + 8)
            .put(version.toByte()).put(sessionId).put(buyerId).put(sellerId).putInt(pricePerMb).putInt(minPriceCfa).putInt(maxMb).put(feePct.toByte()).putLong(startTs).array()

        fun hash(): ByteArray = Crypto.sha256(encode())
        fun valid(): Boolean = sessionId.size == 8 && buyerId.size == 16 && sellerId.size == 16 && validPrice(pricePerMb) && validMinPrice(minPriceCfa) &&
            validMaxMb(maxMb) && validFee(feePct) && startTs > 0 && version == PRICING_VERSION && !buyerId.contentEquals(sellerId)

        fun sameTermsAs(o: Contract) = encode().contentEquals(o.encode())

        companion object {
            const val LEN = 62
            fun decode(b: ByteArray?): Contract? {
                if (b == null || b.size != LEN) return null
                return try {
                    val bb = ByteBuffer.wrap(b)
                    val ver = bb.get().toInt() and 0xFF
                    val sid = ByteArray(8).also { bb.get(it) }; val buyer = ByteArray(16).also { bb.get(it) }; val seller = ByteArray(16).also { bb.get(it) }
                    val c = Contract(sid, buyer, seller, bb.int, bb.int, bb.int, bb.get().toInt() and 0xFF, bb.long, ver)
                    if (c.valid()) c else null
                } catch (e: Exception) { null }
            }
        }
    }

    /** What a signature over a contract commits to (domain-separated). */
    fun contractSignData(c: Contract): ByteArray = "ProkNet-contract-1".toByteArray(Charsets.UTF_8) + c.encode()

    /** Seller-side check of a proposal: the terms must be exactly what the seller currently offers, and the parties must be the link parties. */
    fun acceptableProposal(c: Contract, myId: ByteArray, linkPeerId: ByteArray, myPrice: Int, myMin: Int, myMaxMb: Int, myFee: Int, nowMs: Long, usedSessionIds: Set<String>): String? {
        if (!c.valid()) return "malformed contract"
        if (!c.sellerId.contentEquals(myId)) return "seller id is not me"
        if (!c.buyerId.contentEquals(linkPeerId)) return "buyer id is not the authenticated link peer"
        if (c.pricePerMb != myPrice || c.minPriceCfa != myMin || c.maxMb != myMaxMb || c.feePct != myFee) return "terms differ from my offer"
        if (Math.abs(nowMs - c.startTs) > 10 * 60_000L) return "start time too far from now"
        if (usedSessionIds.contains(c.sessionHex)) return "session id already used"
        return null
    }

    // ---- signed usage checkpoints ---------------------------------------------------------------

    /**
     * Cumulative usage statement issued by the seller and countersigned by the buyer.
     * Encoded: [sessionId 8][seq u32][bytesUp u64][bytesDown u64][costCentimes u64][ts u64][final u8]
     */
    class Checkpoint(val sessionId: ByteArray, val seq: Int, val bytesUp: Long, val bytesDown: Long, val costCentimes: Long, val ts: Long, val final: Boolean) {
        val billable: Long get() = bytesUp + bytesDown
        fun encode(): ByteArray = ByteBuffer.allocate(8 + 4 + 8 + 8 + 8 + 8 + 1)
            .put(sessionId).putInt(seq).putLong(bytesUp).putLong(bytesDown).putLong(costCentimes).putLong(ts).put(if (final) 1 else 0).array()
        fun valid(): Boolean = sessionId.size == 8 && seq >= 1 && bytesUp in 0..MAX_BILLABLE_BYTES && bytesDown in 0..MAX_BILLABLE_BYTES && costCentimes >= 0 && ts > 0
        companion object {
            const val LEN = 45
            fun decode(b: ByteArray?): Checkpoint? {
                if (b == null || b.size != LEN) return null
                return try {
                    val bb = ByteBuffer.wrap(b)
                    val sid = ByteArray(8).also { bb.get(it) }
                    val c = Checkpoint(sid, bb.int, bb.long, bb.long, bb.long, bb.long, bb.get().toInt() != 0)
                    if (c.valid()) c else null
                } catch (e: Exception) { null }
            }
        }
    }

    fun checkpointSignData(c: Checkpoint): ByteArray = "ProkNet-usage-1".toByteArray(Charsets.UTF_8) + c.encode()

    /** Seller builds the next checkpoint from its own counters under the contract terms. */
    fun nextCheckpoint(contract: Contract, prevSeq: Int, bytesUp: Long, bytesDown: Long, ts: Long, final: Boolean): Checkpoint =
        Checkpoint(contract.sessionId, prevSeq + 1, bytesUp, bytesDown, sessionCost(bytesUp + bytesDown, contract.pricePerMb, contract.minPriceCfa), ts, final)

    /**
     * Buyer-side validation of a seller checkpoint against the contract, the last
     * accepted checkpoint and the buyer's OWN counters. Null = accept; else the reason.
     * Duplicates (same seq) and out-of-order (lower seq) are rejected; the byte count may
     * never shrink; the cost must be exactly what the terms give for those bytes; the
     * seller may not claim more than the buyer counted plus a tolerance.
     */
    fun validateCheckpoint(c: Checkpoint, contract: Contract, last: Checkpoint?, myUp: Long, myDown: Long): String? {
        if (!c.valid()) return "malformed checkpoint"
        if (!c.sessionId.contentEquals(contract.sessionId)) return "wrong session"
        if (last != null && c.seq <= last.seq) return if (c.seq == last.seq) "duplicate seq " + c.seq else "out-of-order seq " + c.seq + " <= " + last.seq
        if (last != null && (c.bytesUp < last.bytesUp || c.bytesDown < last.bytesDown)) return "usage decreased"
        if (last != null && last.final) return "session already finalised"
        if (c.costCentimes != sessionCost(c.billable, contract.pricePerMb, contract.minPriceCfa)) return "cost does not match the agreed terms"
        val claimed = c.billable; val mine = myUp + myDown
        val allowance = TOLERANCE_BYTES + mine * TOLERANCE_PERMILLE / 1000
        if (claimed > mine + allowance) return "seller claims " + claimed + " bytes, I counted " + mine + " (+" + allowance + " allowed)"
        if (claimed > contract.maxBytes) return "over the agreed maximum"
        return null
    }

    /** Final settlement from the last mutually signed checkpoint under the agreed terms. */
    fun finalCost(contract: Contract, lastSigned: Checkpoint?): Long =
        if (lastSigned == null) sessionCost(0, contract.pricePerMb, contract.minPriceCfa) else sessionCost(lastSigned.billable, contract.pricePerMb, contract.minPriceCfa)

    // ---- ledger ----------------------------------------------------------------------------------------

    const val PROK_ID = "prok-network"
    const val ST_PENDING = "pending"
    const val ST_SETTLED = "settled"
    const val ST_DISPUTED = "disputed"
    const val ST_CANCELLED = "cancelled"

    class Entry(val id: String, val sessionHex: String, val payer: String, val recipient: String, val amountCentimes: Long, val reason: String, val ts: Long, val status: String,
                val paidAt: Long = 0, val receivedAt: Long = 0) {
        fun describe(): String = cfa(amountCentimes) + "  " + payer + " -> " + recipient + "  " + reason + "  [" + status + "]"
    }

    /** Immutable entry id: hash of the content, so the same obligation cannot be booked twice. */
    fun entryId(sessionHex: String, payer: String, recipient: String, amount: Long, reason: String): String =
        Crypto.sha256((sessionHex + "|" + payer + "|" + recipient + "|" + amount + "|" + reason).toByteArray(Charsets.UTF_8)).toHex().substring(0, 24)

    /** The obligations a finished session creates: buyer owes seller the gross; seller owes Prok the fee. */
    fun sessionEntries(contract: Contract, finalCentimes: Long, ts: Long): List<Entry> {
        val s = split(finalCentimes, contract.feePct)
        val buyer = "prok-" + contract.buyerShort; val seller = "prok-" + contract.sellerShort
        val out = ArrayList<Entry>()
        if (s.gross > 0) out.add(Entry(entryId(contract.sessionHex, buyer, seller, s.gross, "internet session"), contract.sessionHex, buyer, seller, s.gross, "internet session", ts, ST_PENDING))
        if (s.fee > 0) out.add(Entry(entryId(contract.sessionHex, seller, PROK_ID, s.fee, "network fee " + contract.feePct + "%"), contract.sessionHex, seller, PROK_ID, s.fee, "network fee " + contract.feePct + "%", ts, ST_PENDING))
        return out
    }

    /** Settlement state machine: payer marks paid, recipient marks received; both -> settled; either may dispute; pending may be cancelled. */
    fun transition(e: Entry, action: String, now: Long): Entry? = when (action) {
        "paid" -> if (e.status == ST_PENDING) Entry(e.id, e.sessionHex, e.payer, e.recipient, e.amountCentimes, e.reason, e.ts, if (e.receivedAt > 0) ST_SETTLED else ST_PENDING, now, e.receivedAt) else null
        "received" -> if (e.status == ST_PENDING) Entry(e.id, e.sessionHex, e.payer, e.recipient, e.amountCentimes, e.reason, e.ts, if (e.paidAt > 0) ST_SETTLED else ST_PENDING, e.paidAt, now) else null
        "dispute" -> if (e.status == ST_PENDING) Entry(e.id, e.sessionHex, e.payer, e.recipient, e.amountCentimes, e.reason, e.ts, ST_DISPUTED, e.paidAt, e.receivedAt) else null
        "cancel" -> if (e.status == ST_PENDING && e.paidAt == 0L && e.receivedAt == 0L) Entry(e.id, e.sessionHex, e.payer, e.recipient, e.amountCentimes, e.reason, e.ts, ST_CANCELLED) else null
        else -> null
    }

    /** Net position of [who] over pending entries: positive = others owe them. */
    fun balance(entries: List<Entry>, who: String): Long =
        entries.filter { it.status == ST_PENDING }.sumOf { (if (it.recipient == who) it.amountCentimes else 0L) - (if (it.payer == who) it.amountCentimes else 0L) }
}
