package net.prok.proknet.core

/**
 * v0.16.2: reading what the Brain hands back, and deciding whose key signed it.
 *
 * This lives apart from [net.prok.proknet.node.PaymentSync] on purpose. That class needs a
 * socket, so it cannot be exercised off a phone — and the two decisions it makes before
 * believing anything are exactly the ones worth testing: which bytes are the other phone's
 * signed object, and which public key is allowed to verify it.
 *
 * Nothing here trusts the server. It only takes the envelope apart. The signature inside is
 * what decides, and it is checked by the same code the local Bluetooth path uses.
 */
object BrainPayload {

    /** Crude but sufficient for the server's small, known responses. */
    fun field(text: String, key: String): String {
        val m = Regex("\"" + key + "\"\\s*:\\s*(?:\"([^\"]*)\"|(-?[0-9]+)|(true|false))").find(text) ?: return ""
        return m.groupValues.drop(1).firstOrNull { it.isNotEmpty() } ?: ""
    }

    /**
     * The objects under [key], each as its string fields. Handles an array of objects and a
     * single object, which is what every response the phone reads looks like. Anything else
     * — null, a number, nothing at all — is no objects rather than an error, because a Brain
     * that answers strangely must leave the phone working on what it already knows.
     */
    fun objects(text: String, key: String): List<Map<String, String>> {
        val at = text.indexOf("\"" + key + "\"")
        if (at < 0) return emptyList()
        val colon = text.indexOf(':', at)
        if (colon < 0) return emptyList()
        var i = colon + 1
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length) return emptyList()
        val body = when (text[i]) {
            '[' -> { val e = text.indexOf(']', i); if (e < 0) return emptyList(); text.substring(i + 1, e) }
            '{' -> { val e = text.indexOf('}', i); if (e < 0) return emptyList(); text.substring(i, e + 1) }
            else -> return emptyList()
        }
        return Regex("\\{[^{}]*\\}").findAll(body).map { m ->
            Regex("\"([A-Za-z_]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(m.value)
                .associate { it.groupValues[1] to unescape(it.groupValues[2]) }
        }.toList()
    }

    fun escape(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"")

    fun unescape(s: String): String = s.replace("\\\"", "\"").replace("\\\\", "\\")

    /**
     * The public key that goes with [idHex], or null.
     *
     * A node id **is** the first bytes of the hash of its public key, so a key that derives
     * to the id in the message is the right key by construction. That is why a key may be
     * taken from the server at all: there is no directory to trust and no name to look up.
     * A Brain that substituted its own key would be offering a key for a different identity,
     * and this returns null rather than that key.
     *
     * @param offered the key the server sent, hex, possibly absent or rubbish.
     * @param known what this phone already holds for that identity, if it has met it.
     */
    fun pubFor(idHex: String, offered: String?, known: ByteArray?): ByteArray? {
        if (!offered.isNullOrEmpty()) {
            try {
                val bytes = offered.hexToBytes()
                if (Crypto.deriveId(bytes).toHex() == idHex) return bytes
            } catch (e: Exception) {
                // unreadable hex is simply not a key
            }
        }
        return known
    }
}
