package net.prok.proknet.core

import android.content.Context
import java.security.SecureRandom

/**
 * Persistent ProkNet device identity.
 *
 * v0.1: a random 16-byte ID generated once and stored in SharedPreferences.
 * The first 4 bytes are the "short ID" that fits into a BLE scan response.
 * Later milestones will replace this with a real key pair; the API is kept
 * minimal so that swap is cheap.
 */
class Identity private constructor(val idBytes: ByteArray, var displayName: String) {

    val idHex: String get() = idBytes.toHex()
    val shortIdBytes: ByteArray get() = idBytes.copyOfRange(0, SHORT_ID_LEN)
    val shortIdHex: String get() = shortIdBytes.toHex()

    companion object {
        const val ID_LEN = 16
        const val SHORT_ID_LEN = 4
        private const val PREFS = "proknet_identity"
        private const val KEY_ID = "id_hex"
        private const val KEY_NAME = "display_name"

        fun load(context: Context): Identity {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var hex = prefs.getString(KEY_ID, null)
            if (hex == null || hex.length != ID_LEN * 2) {
                val bytes = ByteArray(ID_LEN)
                SecureRandom().nextBytes(bytes)
                hex = bytes.toHex()
                prefs.edit().putString(KEY_ID, hex).apply()
            }
            val bytes = hex.hexToBytes()
            val name = prefs.getString(KEY_NAME, null) ?: ("prok-" + hex.substring(0, 8))
            return Identity(bytes, name)
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
