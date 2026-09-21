package net.prok.proknet.core

/**
 * v0.15.3: the phone half of `brain/signed_request.py`.
 *
 * Every settlement submission proves who sent it, when, and that the body has not been
 * touched. HTTPS says the bytes arrived unaltered; it says nothing about which identity
 * sent them or whether this is the third replay of an hour-old request.
 *
 * The signature covers exactly:
 *
 *     ProkNet-api-1|<timestamp ms>|<nonce>|<sha256 of the body bytes, hex>
 *
 * **The body bytes signed must be the body bytes sent.** Re-serialising JSON after
 * signing is the classic way to break this: a different key order, a different space, and
 * the hash no longer matches. So the caller builds the bytes once and passes the same
 * array to both.
 */
object SignedApi {

    const val DOMAIN = "ProkNet-api-1"

    const val HEADER_IDENTITY = "X-Prok-Identity"
    const val HEADER_TIMESTAMP = "X-Prok-Timestamp"
    const val HEADER_NONCE = "X-Prok-Nonce"
    const val HEADER_SIGNATURE = "X-Prok-Signature"

    /** The server allows five minutes of clock skew; stay well inside it. */
    const val MAX_SKEW_MS = 5 * 60 * 1000L

    fun bodyHash(body: ByteArray): String = Crypto.sha256(body).toHex()

    /**
     * The request target as both sides must spell it before signing.
     *
     * The query is part of it. `/v1/pay/reply?payment=A` and `?payment=B` ask about two
     * different people's money, so they must not share a signature.
     *
     * The rule is deliberately dull, because a clever rule is one the two languages will
     * eventually disagree about:
     *
     * - keep the path exactly as sent, percent-encoding and all;
     * - drop an empty query entirely;
     * - otherwise sort the raw `k=v` pieces and rejoin them with `&`.
     *
     * Nothing is decoded. Decoding is where two implementations drift: `%2F` and `/`
     * would canonicalise the same on one side and not the other.
     *
     * Mirrors `signed_request.canonical_target`, and a fixture test pins the two together.
     */
    fun canonicalTarget(requestTarget: String): String {
        val q = requestTarget.indexOf('?')
        if (q < 0) return requestTarget
        val path = requestTarget.substring(0, q)
        val parts = requestTarget.substring(q + 1).split("&").filter { it.isNotEmpty() }.sorted()
        return if (parts.isEmpty()) path else path + "?" + parts.joinToString("&")
    }

    /**
     * Byte-for-byte what `signed_request.signing_line` builds on the server:
     *
     *     ProkNet-api-1|<ts>|<nonce>|<sha256 of the body>|<METHOD>|<canonical target>
     *
     * The METHOD and the target are inside the signature. Without them a signature made
     * for one endpoint could be replayed against another that accepts the same body.
     *
     * The short form, without them, is v0.16.1's. Every money route on the server refuses
     * it now; it survives only so the tests can prove that.
     */
    fun signingLine(ts: Long, nonce: String, bodyHash: String, method: String = "", path: String = ""): ByteArray =
        if (method.isNotEmpty() || path.isNotEmpty())
            (DOMAIN + "|" + ts + "|" + nonce + "|" + bodyHash + "|" + method.uppercase() + "|" +
                canonicalTarget(path)).toByteArray(Charsets.UTF_8)
        else (DOMAIN + "|" + ts + "|" + nonce + "|" + bodyHash).toByteArray(Charsets.UTF_8)

    /**
     * A nonce the server will accept: 8 to 64 characters. Random, so a retry of the same
     * evidence is a new request rather than a replay of the old one.
     */
    fun newNonce(): String = Crypto.randomBytes(16).toHex()

    class Headers(val identity: String, val timestamp: String, val nonce: String, val signature: String) {
        fun asMap(): Map<String, String> = mapOf(
            HEADER_IDENTITY to identity,
            HEADER_TIMESTAMP to timestamp,
            HEADER_NONCE to nonce,
            HEADER_SIGNATURE to signature)
    }

    /**
     * Sign [body] as [signer].
     *
     * @param body the exact bytes that will be written to the connection. Not a string
     *        that will be encoded again later.
     */
    fun sign(body: ByteArray, signer: Signer, now: Long, nonce: String = newNonce(),
             method: String = "", path: String = ""): Headers {
        val hash = bodyHash(body)
        val sig = signer.sign(signingLine(now, nonce, hash, method, path))
        return Headers(signer.pubBytes.toHex(), now.toString(), nonce, sig.toHex())
    }

    /**
     * Verify a signed request the way the server does. Kept here so the two halves can be
     * tested against each other on this side of the wire, rather than discovering a
     * mismatch on the phones.
     */
    fun verify(headers: Map<String, String>, body: ByteArray, now: Long,
               method: String = "", path: String = ""): Boolean {
        val pub = headers[HEADER_IDENTITY] ?: return false
        val ts = headers[HEADER_TIMESTAMP]?.toLongOrNull() ?: return false
        val nonce = headers[HEADER_NONCE] ?: return false
        val sig = headers[HEADER_SIGNATURE] ?: return false
        if (nonce.length !in 8..64) return false
        if (Math.abs(now - ts) > MAX_SKEW_MS) return false
        return try {
            Crypto.verify(pub.hexToBytes(), signingLine(ts, nonce, bodyHash(body), method, path), sig.hexToBytes())
        } catch (e: Exception) { false }
    }
}
