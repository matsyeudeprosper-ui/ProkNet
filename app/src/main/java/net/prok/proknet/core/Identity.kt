package net.prok.proknet.core

import android.content.Context
import android.util.Base64
import java.security.PrivateKey

/**
 * Persistent cryptographic ProkNet device identity (v0.5).
 *
 * One P-256 key pair per installation, generated once and kept in the app's
 * private SharedPreferences (MODE_PRIVATE: unreadable by other apps; never
 * exported, logged or shown). The ProkNet ID is derived from the public key
 * (SHA-256 of X||Y, first 16 bytes), so an ID is a claim only its key holder
 * can back. The first 4 bytes are the "short ID" used in scan responses.
 *
 * Migration from v0.1-v0.4 (random ID, no key): a key pair is generated, the
 * ID changes to the derived one, the old ID is kept as [legacyIdHex] for the
 * record. Messages already stored are untouched.
 *
 * The private key never leaves this class: signing and opening envelopes are
 * done through [sign] and [open].
 */
class Identity private constructor(
    private val priv: PrivateKey,
    override val pubBytes: ByteArray,
    override var displayName: String,
    val legacyIdHex: String?,
    val createdAt: Long,
) : Signer {
    override val idBytes: ByteArray = Crypto.deriveId(pubBytes)
    val idHex: String get() = idBytes.toHex()
    val shortIdBytes: ByteArray get() = idBytes.copyOfRange(0, SHORT_ID_LEN)
    val shortIdHex: String get() = shortIdBytes.toHex()
    val fingerprint: String get() = Crypto.fingerprint(pubBytes)

    override fun sign(data: ByteArray): ByteArray = Crypto.sign(priv, data)
    fun open(aad: ByteArray, envelope: ByteArray?): ByteArray? = Crypto.open(priv, pubBytes, aad, envelope)
    fun buildSigned(aad: ByteArray, kind: Int, body: ByteArray): ByteArray = Signed.build(priv, aad, kind, body)

    companion object {
        const val ID_LEN = 16
        const val SHORT_ID_LEN = 4
        private const val PREFS = "proknet_identity"
        private const val KEY_ID = "id_hex"           // v0.1-v0.4 random ID (kept as legacy)
        private const val KEY_NAME = "display_name"
        private const val KEY_PRIV = "ec_priv_pkcs8_b64"
        private const val KEY_PUB = "ec_pub_xy_b64"
        private const val KEY_LEGACY = "legacy_id_hex"
        private const val KEY_CREATED = "key_created_at"

        fun load(context: Context): Identity {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var privB64 = prefs.getString(KEY_PRIV, null)
            var pubB64 = prefs.getString(KEY_PUB, null)
            var legacy = prefs.getString(KEY_LEGACY, null)
            var created = prefs.getLong(KEY_CREATED, 0L)
            var priv: PrivateKey? = null
            var pub: ByteArray? = null
            if (privB64 != null && pubB64 != null) {
                try {
                    priv = Crypto.privateKeyFrom(Base64.decode(privB64, Base64.NO_WRAP))
                    pub = Base64.decode(pubB64, Base64.NO_WRAP)
                    if (pub.size != Crypto.PUB_LEN) { priv = null; pub = null }
                } catch (e: Exception) { priv = null; pub = null }
            }
            if (priv == null || pub == null) {
                // First run on v0.5, or an unreadable key: generate. Remember the old random ID if there was one.
                val oldId = prefs.getString(KEY_ID, null)
                if (oldId != null && oldId.length == ID_LEN * 2 && legacy == null) legacy = oldId
                val kp = Crypto.generateKeyPair()
                priv = kp.private
                pub = Crypto.publicBytes(kp.public)
                created = System.currentTimeMillis()
                privB64 = Base64.encodeToString(Crypto.privateBytes(priv), Base64.NO_WRAP)
                pubB64 = Base64.encodeToString(pub, Base64.NO_WRAP)
                prefs.edit()
                    .putString(KEY_PRIV, privB64).putString(KEY_PUB, pubB64)
                    .putLong(KEY_CREATED, created)
                    .apply { if (legacy != null) putString(KEY_LEGACY, legacy) }
                    .apply()
                DiagLog.i("IDENTITY", "generated new P-256 identity key" + (if (legacy != null) " (migrated from random ID " + legacy.substring(0, 8) + ")" else ""))
            }
            val privKey: PrivateKey = priv ?: throw IllegalStateException("no private key")
            val pubBytes: ByteArray = pub ?: throw IllegalStateException("no public key")
            val derivedHex = Crypto.deriveId(pubBytes).toHex()
            val name = prefs.getString(KEY_NAME, null) ?: ("prok-" + derivedHex.substring(0, 8))
            // Keep id_hex in sync so any old reader sees the current ID.
            if (prefs.getString(KEY_ID, null) != derivedHex) prefs.edit().putString(KEY_ID, derivedHex).apply()
            return Identity(privKey, pubBytes, name, legacy, created)
        }

        fun saveName(context: Context, identity: Identity, name: String) {
            identity.displayName = name
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_NAME, name).apply()
        }
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "hex string must have even length" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}
