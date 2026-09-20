package net.prok.proknet.core

import java.nio.ByteBuffer

/**
 * v0.13: the Internet request as a signed network object. The local
 * lifecycle on the phone stays [InternetRequest]; when it needs the network
 * (no direct source), it becomes one of these: signed by its origin, with a
 * generation, an expiry, a hop budget, and a state that can be a tombstone
 * (CANCELLED / FULFILLED) so a carried copy can never resurrect it.
 *
 * Pure. The wire form is compact because it travels inside an encrypted BLE
 * control envelope.
 */
object NetRequest {

    enum class State {
        CREATED, SEARCHING_LOCAL, DIRECT_SOURCE_FOUND, NETWORK_REQUESTED, CARRIED, UPLOADED, SUPPLY_POSSIBLE,
        PROVIDER_ACTIVATING, CONNECTING, ONLINE, FAILED, EXPIRED, CANCELLED, FULFILLED,
    }

    const val WIRE_VERSION = 1
    const val MAX_HOPS = 6
    const val NOW_TTL_MS = 30 * 60_000L
    const val FLEXIBLE = 0
    const val PRICE_AUTOMATIC = -1
    const val URGENCY_NOW = 0
    const val URGENCY_SOON = 1
    const val URGENCY_FLEXIBLE = 2

    /** The signed object. Hex strings for keys and signatures so it is a plain data class. */
    data class Request(
        val id: String,                 // 16 hex
        val originShort: String,        // 8 hex, the origin's ProkNet short id
        val originPubHex: String,       // 128 hex, so anyone can verify
        val createdAt: Long,
        val updatedAt: Long,
        val expiresAt: Long,
        val zone: String,
        val desiredMb: Int,
        val desiredMinutes: Int,
        val ceilingCentimesPerMb: Int,
        val urgency: Int,
        val state: State,
        val generation: Int,
        val hops: Int,
        val signatureHex: String,
    ) {
        val tombstone: Boolean get() = isTombstone(state)
        val open: Boolean get() = !tombstone && state != State.EXPIRED && state != State.FAILED && state != State.ONLINE
        val ceiling: Int? get() = if (ceilingCentimesPerMb == PRICE_AUTOMATIC) null else ceilingCentimesPerMb
        fun expired(now: Long): Boolean = now >= expiresAt
    }

    fun isTombstone(s: State): Boolean = s == State.CANCELLED || s == State.FULFILLED

    /** The one-tap request: flexible, automatic price, NOW, from the origin's zone. Unsigned until [sign]. */
    fun oneTap(id: String, originShort: String, originPubHex: String, now: Long, zone: String, ttlMs: Long = NOW_TTL_MS): Request =
        Request(id, originShort, originPubHex, now, now, now + ttlMs, zone, FLEXIBLE, FLEXIBLE, PRICE_AUTOMATIC, URGENCY_NOW,
            State.NETWORK_REQUESTED, 1, 0, "")

    /** The bytes the origin signs: everything that must not change, plus the generation and whether it is a tombstone. */
    fun signedBytes(r: Request): ByteArray {
        val zone = r.zone.toByteArray(Charsets.UTF_8)
        val b = ByteBuffer.allocate(1 + 8 + 4 + 8 + 8 + 8 + 4 + 4 + 4 + 1 + 4 + 1 + 2 + zone.size)
        b.put(WIRE_VERSION.toByte()).put(r.id.hexToBytes()).put(r.originShort.hexToBytes())
            .putLong(r.createdAt).putLong(r.updatedAt).putLong(r.expiresAt)
            .putInt(r.desiredMb).putInt(r.desiredMinutes).putInt(r.ceilingCentimesPerMb).put(r.urgency.toByte())
            .putInt(r.generation).put((if (r.tombstone) 1 else 0).toByte()).putShort(zone.size.toShort()).put(zone)
        return b.array()
    }

    fun sign(r: Request, signer: Signer): Request = r.copy(signatureHex = signer.sign(signedBytes(r)).toHex())

    fun verify(r: Request): Boolean {
        if (r.signatureHex.isEmpty() || r.originPubHex.length != 128) return false
        val pub = try { r.originPubHex.hexToBytes() } catch (_: Exception) { return false }
        if (Crypto.deriveId(pub).copyOfRange(0, 4).toHex() != r.originShort.lowercase()) return false
        return Crypto.verify(pub, signedBytes(r), try { r.signatureHex.hexToBytes() } catch (_: Exception) { return false })
    }

    /** A new generation with a terminal state, signed by the origin. Carried copies of older generations die on contact. */
    fun tombstone(r: Request, state: State, now: Long, signer: Signer): Request {
        require(isTombstone(state))
        return sign(r.copy(state = state, generation = r.generation + 1, updatedAt = now, hops = 0), signer)
    }

    /** A copy to hand to the next peer: one more hop, the signature untouched (hops are not signed). */
    fun forwarded(r: Request): Request = r.copy(hops = r.hops + 1)

    // ---- wire ------------------------------------------------------------------------------------------------

    fun encode(r: Request): ByteArray {
        val zone = r.zone.toByteArray(Charsets.UTF_8)
        val sig = r.signatureHex.hexToBytes()
        val b = ByteBuffer.allocate(1 + 8 + 4 + 64 + 8 + 8 + 8 + 4 + 4 + 4 + 1 + 1 + 4 + 1 + 2 + zone.size + 1 + sig.size)
        b.put(WIRE_VERSION.toByte()).put(r.id.hexToBytes()).put(r.originShort.hexToBytes()).put(r.originPubHex.hexToBytes())
            .putLong(r.createdAt).putLong(r.updatedAt).putLong(r.expiresAt)
            .putInt(r.desiredMb).putInt(r.desiredMinutes).putInt(r.ceilingCentimesPerMb).put(r.urgency.toByte())
            .put(r.state.ordinal.toByte()).putInt(r.generation).put(r.hops.toByte())
            .putShort(zone.size.toShort()).put(zone).put(sig.size.toByte()).put(sig)
        return b.array()
    }

    fun decode(bytes: ByteArray): Request? = try {
        val b = ByteBuffer.wrap(bytes)
        if (b.get().toInt() != WIRE_VERSION) null else {
            val id = ByteArray(8).also { b.get(it) }.toHex()
            val origin = ByteArray(4).also { b.get(it) }.toHex()
            val pub = ByteArray(64).also { b.get(it) }.toHex()
            val created = b.long; val updated = b.long; val expires = b.long
            val mb = b.int; val minutes = b.int; val ceiling = b.int; val urgency = b.get().toInt()
            val state = State.values()[b.get().toInt().coerceIn(0, State.values().size - 1)]
            val gen = b.int; val hops = b.get().toInt() and 0xFF
            val zl = b.short.toInt(); val zone = String(ByteArray(zl).also { b.get(it) }, Charsets.UTF_8)
            val sl = b.get().toInt() and 0xFF; val sig = ByteArray(sl).also { b.get(it) }.toHex()
            Request(id, origin, pub, created, updated, expires, zone, mb, minutes, ceiling, urgency, state, gen, hops, sig)
        }
    } catch (_: Exception) { null }

    // ---- persistence line ------------------------------------------------------------------------------------

    fun encodeLine(r: Request): String = listOf(r.id, r.originShort, r.originPubHex, r.createdAt, r.updatedAt, r.expiresAt, r.zone.replace("\t", " "),
        r.desiredMb, r.desiredMinutes, r.ceilingCentimesPerMb, r.urgency, r.state.name, r.generation, r.hops, r.signatureHex).joinToString("\t")

    fun decodeLine(line: String): Request? = try {
        val f = line.split('\t')
        if (f.size < 15) null else Request(f[0], f[1], f[2], f[3].toLong(), f[4].toLong(), f[5].toLong(), f[6], f[7].toInt(), f[8].toInt(), f[9].toInt(), f[10].toInt(),
            State.valueOf(f[11]), f[12].toInt(), f[13].toInt(), f[14])
    } catch (_: Exception) { null }

    // ---- words ----------------------------------------------------------------------------------------------------

    /** The sphere's word while the network is asked. */
    fun sphereWord(s: State): String = when (s) {
        State.NETWORK_REQUESTED, State.CARRIED, State.UPLOADED, State.SUPPLY_POSSIBLE, State.PROVIDER_ACTIVATING -> "DEMANDE"
        State.CONNECTING, State.DIRECT_SOURCE_FOUND -> "CONNEXION"
        State.ONLINE, State.FULFILLED -> "CONNECTÉ"
        State.FAILED, State.EXPIRED -> "RÉESSAYER"
        else -> "RECHERCHE"
    }

    /** The small line under the sphere: truthful, never promising a provider that has not accepted. */
    fun hint(s: State, ageMs: Long, providerAccepted: Boolean): String = when {
        providerAccepted -> "Internet trouvé. Connexion…"
        s == State.NETWORK_REQUESTED && ageMs < 60_000 -> "Demande envoyée aux téléphones ProkNet autour de vous."
        s == State.NETWORK_REQUESTED || s == State.CARRIED || s == State.UPLOADED || s == State.SUPPLY_POSSIBLE -> "ProkNet continue de chercher Internet autour de vous."
        s == State.PROVIDER_ACTIVATING -> "Un téléphone à proximité prépare le partage…"
        s == State.EXPIRED -> "Personne n'a pu partager Internet pour l'instant."
        else -> ""
    }
}
