package net.prok.proknet.core

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * ProkNet v0.5 cryptography. Pure JCA, available on Android API 26+ and on a
 * plain JVM (so it is unit-tested on the VPS).
 *
 *  Identity     P-256 (secp256r1) key pair. ProkNet ID = SHA-256(public key)[0..16].
 *  Signatures   ECDSA with SHA-256 (DER encoded, 70-72 bytes).
 *  Envelope     ECIES-style: ephemeral P-256 ECDH with the destination's static
 *               key -> HKDF-SHA256 -> AES-256-GCM. Layout:
 *                 [ephemeral public key 64][nonce 12][ciphertext + 16-byte tag]
 *               The routing header that must never change is passed as GCM
 *               associated data, so a relay that alters it breaks the tag.
 *  Plaintext    sign-then-encrypt: [sigLen 1][ECDSA sig][kind 1][body]. The
 *               signature covers aad || kind || body, so the destination knows
 *               the ORIGIN wrote exactly this, and a relay cannot see the
 *               signature or the body.
 */
object Crypto {
    const val PUB_LEN = 64
    const val NONCE_LEN = 12
    const val TAG_LEN = 16
    const val ID_LEN = 16
    /** Bytes an envelope adds on top of the plaintext. */
    const val ENVELOPE_OVERHEAD = PUB_LEN + NONCE_LEN + TAG_LEN
    private const val CURVE = "secp256r1"
    private val HKDF_SALT = "ProkNet-v5".toByteArray(Charsets.UTF_8)
    private val random = SecureRandom()

    private val curveParams: ECParameterSpec by lazy {
        val ap = AlgorithmParameters.getInstance("EC")
        ap.init(ECGenParameterSpec(CURVE))
        ap.getParameterSpec(ECParameterSpec::class.java)
    }

    // ---- keys -----------------------------------------------------------------------------------

    fun generateKeyPair(): KeyPair {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec(CURVE), random)
        return g.generateKeyPair()
    }

    /** Raw uncompressed point X||Y, 64 bytes. */
    fun publicBytes(pub: PublicKey): ByteArray {
        val w = (pub as ECPublicKey).w
        val out = ByteArray(PUB_LEN)
        val x = w.affineX.toByteArray(); val y = w.affineY.toByteArray()
        copyFixed(x, out, 0); copyFixed(y, out, 32)
        return out
    }

    private fun copyFixed(src: ByteArray, dst: ByteArray, off: Int) {
        // BigInteger.toByteArray may carry a leading sign byte or be short.
        var s = src
        if (s.size > 32) s = s.copyOfRange(s.size - 32, s.size)
        System.arraycopy(s, 0, dst, off + 32 - s.size, s.size)
    }

    // P-256 field prime and curve constant b (a = -3), for the on-curve check below.
    private val P256_P = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
    private val P256_B = BigInteger("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B", 16)

    /** Rebuild a public key from X||Y. Throws on a point that is not on P-256 (invalid-curve defence). */
    fun publicKeyFrom(bytes: ByteArray): PublicKey {
        require(bytes.size == PUB_LEN) { "public key must be " + PUB_LEN + " bytes" }
        val x = BigInteger(1, bytes.copyOfRange(0, 32))
        val y = BigInteger(1, bytes.copyOfRange(32, 64))
        require(x < P256_P && y < P256_P) { "coordinate out of range" }
        val lhs = y.multiply(y).mod(P256_P)
        val rhs = x.multiply(x).multiply(x).subtract(x.multiply(BigInteger.valueOf(3))).add(P256_B).mod(P256_P)
        require(lhs == rhs) { "point is not on the curve" }
        val spec = ECPublicKeySpec(ECPoint(x, y), curveParams)
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }

    fun privateBytes(priv: PrivateKey): ByteArray = priv.encoded // PKCS#8
    fun privateKeyFrom(pkcs8: ByteArray): PrivateKey = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(pkcs8))

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    /** ProkNet identity bound to the key: first 16 bytes of SHA-256(X||Y). */
    fun deriveId(pubBytes: ByteArray): ByteArray = sha256(pubBytes).copyOfRange(0, ID_LEN)

    /** Human-checkable fingerprint of a public key: 8 groups of 4 hex chars of SHA-256(X||Y). */
    fun fingerprint(pubBytes: ByteArray): String {
        val h = sha256(pubBytes).toHex().substring(0, 32)
        return h.chunked(4).joinToString(" ")
    }

    // ---- signatures -----------------------------------------------------------------------------

    fun sign(priv: PrivateKey, data: ByteArray): ByteArray {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(priv, random); s.update(data)
        return s.sign()
    }

    fun verify(pubBytes: ByteArray, data: ByteArray, sig: ByteArray): Boolean = try {
        val s = Signature.getInstance("SHA256withECDSA")
        s.initVerify(publicKeyFrom(pubBytes)); s.update(data)
        s.verify(sig)
    } catch (e: Exception) { false }

    // ---- envelope -------------------------------------------------------------------------------

    private fun hkdf(secret: ByteArray, info: ByteArray, len: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(HKDF_SALT, "HmacSHA256"))
        val prk = mac.doFinal(secret)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArray(len)
        var t = ByteArray(0); var pos = 0; var i = 1
        while (pos < len) {
            mac.update(t); mac.update(info); mac.update(i.toByte())
            t = mac.doFinal()
            val n = minOf(t.size, len - pos)
            System.arraycopy(t, 0, out, pos, n); pos += n; i++
        }
        return out
    }

    private fun ecdh(priv: PrivateKey, pub: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(priv); ka.doPhase(pub, true)
        return ka.generateSecret()
    }

    /** Encrypt [plaintext] for the holder of [destPubBytes]; [aad] must be identical at open(). */
    fun seal(destPubBytes: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val eph = generateKeyPair()
        val ephPub = publicBytes(eph.public)
        val shared = ecdh(eph.private, publicKeyFrom(destPubBytes))
        val key = hkdf(shared, ephPub + destPubBytes + aad, 32)
        val nonce = ByteArray(NONCE_LEN).also { random.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, nonce))
        c.updateAAD(aad)
        val ct = c.doFinal(plaintext)
        return ByteBuffer.allocate(PUB_LEN + NONCE_LEN + ct.size).put(ephPub).put(nonce).put(ct).array()
    }

    /** Decrypt with my private key. Returns null on any failure (wrong key, tampering, wrong aad). Never throws. */
    fun open(myPriv: PrivateKey, myPubBytes: ByteArray, aad: ByteArray, envelope: ByteArray?): ByteArray? {
        if (envelope == null || envelope.size < ENVELOPE_OVERHEAD) return null
        return try {
            val ephPub = envelope.copyOfRange(0, PUB_LEN)
            val nonce = envelope.copyOfRange(PUB_LEN, PUB_LEN + NONCE_LEN)
            val ct = envelope.copyOfRange(PUB_LEN + NONCE_LEN, envelope.size)
            val shared = ecdh(myPriv, publicKeyFrom(ephPub))
            val key = hkdf(shared, ephPub + myPubBytes + aad, 32)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, nonce))
            c.updateAAD(aad)
            c.doFinal(ct)
        } catch (e: Exception) { null }
    }

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    // ---- v0.9 relay: static-static agreement + raw AES-GCM ---------------------------------------

    /** 32-byte key from ECDH(my private, peer public) and [info]; the peer derives the same with the roles swapped. */
    fun agree(myPriv: PrivateKey, peerPubBytes: ByteArray, info: ByteArray): ByteArray = hkdf(ecdh(myPriv, publicKeyFrom(peerPubBytes)), info, 32)

    fun gcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, nonce))
        c.updateAAD(aad)
        return c.doFinal(plaintext)
    }

    /** Null on any failure (wrong key, tampering, wrong aad). Never throws. */
    fun gcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_LEN * 8, nonce))
        c.updateAAD(aad)
        c.doFinal(ciphertext)
    } catch (e: Exception) { null }
}

/**
 * The signed plaintext inside an envelope: [sigLen 1][sig][kind 1][body].
 * The signature covers aad || kind || body.
 */
object Signed {
    const val KIND_TEXT = 1
    const val KIND_CONTROL = 2
    const val KIND_BLOB = 3   // large payload: [type 1][nameLen u16][name][data]

    class Parsed(val sig: ByteArray, val kind: Int, val body: ByteArray)

    fun build(originPriv: PrivateKey, aad: ByteArray, kind: Int, body: ByteArray): ByteArray {
        val sig = Crypto.sign(originPriv, aad + byteArrayOf(kind.toByte()) + body)
        require(sig.size in 1..255)
        return ByteBuffer.allocate(1 + sig.size + 1 + body.size).put(sig.size.toByte()).put(sig).put(kind.toByte()).put(body).array()
    }

    fun parse(bytes: ByteArray?): Parsed? {
        if (bytes == null || bytes.size < 3) return null
        val n = bytes[0].toInt() and 0xFF
        if (n == 0 || bytes.size < 1 + n + 1) return null
        val sig = bytes.copyOfRange(1, 1 + n)
        val kind = bytes[1 + n].toInt() and 0xFF
        return Parsed(sig, kind, bytes.copyOfRange(2 + n, bytes.size))
    }

    /** True if [originPubBytes] signed exactly this (aad, kind, body). */
    fun verify(originPubBytes: ByteArray, aad: ByteArray, p: Parsed): Boolean =
        Crypto.verify(originPubBytes, aad + byteArrayOf(p.kind.toByte()) + p.body, p.sig)
}
