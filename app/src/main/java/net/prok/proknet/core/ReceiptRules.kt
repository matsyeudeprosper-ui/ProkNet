package net.prok.proknet.core

/**
 * v0.16.2: receipt-parser rules that can be updated as signed data.
 *
 * MTN or Airtel will reword a message one day. The parser is already scored rather than
 * templated, so a rewording degrades to "not confident" rather than to a wrong answer, but
 * its dictionaries still ship in the APK. This lets them be replaced without a release.
 *
 * Three rules make that safe enough to do at all, and all three are enforced here rather
 * than trusted to the server:
 *
 * 1. **A pinned, dedicated key.** Not the Brain's transport identity. A compromised Brain
 *    may withhold or delay a config; it cannot forge one.
 * 2. **Data only.** Word lists. No regex from the server, no expressions, no scripts. A
 *    remotely supplied rule that could execute would be a code path into the part of the
 *    system that decides whether money arrived.
 * 3. **Hard bounds**, so a compromised Brain cannot send something enormous or hostile.
 *
 * If anything is wrong we keep the rules we already trust. Detection never waits for a
 * config and never depends on one: with no Brain at all, the built-in rules work for ever.
 */
object ReceiptRules {

    const val DOMAIN = "ProkNet-receipt-rules-1"

    /**
     * The configuration signing key, pinned in the app.
     *
     * Empty in this build, which means **no remote configuration is accepted at all** and
     * the built-in rules are the only rules. That is the honest default: publishing a key
     * here without a corresponding key ceremony would look like a security control while
     * being none.
     */
    const val PINNED_CONFIG_KEY = ""

    const val MAX_TERMS_PER_CATEGORY = 64
    const val MAX_TERM_LENGTH = 48
    const val MAX_CONFIG_BYTES = 16 * 1024

    /** The only categories a configuration may carry. Anything else is refused, not ignored. */
    val CATEGORIES = listOf("credit", "debit", "currency", "balance", "reject", "senders")

    class Config(val version: Int, val validFrom: Long, val terms: Map<String, List<String>>) {
        /** The rules the parser should use. Categories absent from the config keep their defaults. */
        fun toRules(base: ReceiptParser.Rules = ReceiptParser.DEFAULT): ReceiptParser.Rules =
            ReceiptParser.Rules(
                credit = terms["credit"]?.takeIf { it.isNotEmpty() } ?: base.credit,
                debit = terms["debit"]?.takeIf { it.isNotEmpty() } ?: base.debit,
                reject = terms["reject"]?.takeIf { it.isNotEmpty() } ?: base.reject,
                balance = terms["balance"]?.takeIf { it.isNotEmpty() } ?: base.balance,
                currency = terms["currency"]?.takeIf { it.isNotEmpty() } ?: base.currency)
    }

    /**
     * Exactly the bytes the publisher signed. Sorted keys, no incidental whitespace, so
     * both sides compute the same bytes from the same content without either having to
     * re-serialise what the other sent.
     */
    fun canonical(version: Int, validFrom: Long, terms: Map<String, List<String>>): ByteArray {
        val sb = StringBuilder(DOMAIN).append("|{\"terms\":{")
        for ((i, k) in CATEGORIES.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append('"').append(k).append("\":[")
            sb.append((terms[k] ?: emptyList()).joinToString(",") { "\"" + it + "\"" })
            sb.append(']')
        }
        sb.append("},\"validFrom\":").append(validFrom).append(",\"version\":").append(version).append('}')
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** Shape and bounds. A config that fails any of these is discarded whole, never trimmed. */
    fun acceptable(version: Int, validFrom: Long, terms: Map<String, List<String>>): Boolean {
        if (version < 1 || validFrom < 0) return false
        for ((k, v) in terms) {
            if (k !in CATEGORIES) return false
            if (v.size > MAX_TERMS_PER_CATEGORY) return false
            for (t in v) {
                if (t.isEmpty() || t.length > MAX_TERM_LENGTH) return false
                // no control characters, and nothing that could be read as an expression
                if (t.any { it.code < 0x20 || it.code == 0x7F }) return false
            }
        }
        return canonical(version, validFrom, terms).size <= MAX_CONFIG_BYTES
    }

    /**
     * @param key the pinned signing key. Defaults to the one in the app; tests pass their
     *        own so that the acceptance path they exercise is the production one, not a
     *        parallel copy that could drift away from it.
     */
    fun verify(version: Int, validFrom: Long, terms: Map<String, List<String>>, signature: String,
               key: String = PINNED_CONFIG_KEY): Boolean {
        if (key.isEmpty()) return false
        if (!acceptable(version, validFrom, terms)) return false
        return try {
            Crypto.verify(key.hexToBytes(), canonical(version, validFrom, terms), signature.hexToBytes())
        } catch (e: Exception) { false }
    }

    // ---- what the phone actually uses ----------------------------------------------------------

    @Volatile private var active: Config? = null

    /** The rules in force. Built-in unless a signed configuration has been accepted. */
    fun current(): ReceiptParser.Rules = active?.toRules() ?: ReceiptParser.DEFAULT

    fun activeVersion(): Int = active?.version ?: 0

    /** Restore the last configuration we trusted, so a restart does not fall back silently. */
    fun restore(store: MessageStore) {
        val c = store.receiptRules() ?: return
        if (verify(c.version, c.validFrom, c.terms, store.receiptRulesSignature())) active = c
    }

    /**
     * Read a server response and return the configuration only if **everything** checks
     * out. Pure: it decides, it does not install. Returns null for every ordinary reason
     * — no key pinned, nothing published, not newer than what we run, out of bounds, a bad
     * signature — and the caller then simply keeps the rules already in force.
     */
    fun accept(responseJson: String, key: String = PINNED_CONFIG_KEY, minVersion: Int = activeVersion()): Pair<Config, String>? {
        if (key.isEmpty()) return null
        val version = Regex("\"version\"\\s*:\\s*(\\d+)").find(responseJson)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (version <= minVersion) return null
        val validFrom = Regex("\"validFrom\"\\s*:\\s*(\\d+)").find(responseJson)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val signature = Regex("\"signature\"\\s*:\\s*\"([0-9a-fA-F]*)\"").find(responseJson)?.groupValues?.get(1) ?: return null
        val terms = HashMap<String, List<String>>()
        for (k in CATEGORIES) {
            val block = Regex("\"" + k + "\"\\s*:\\s*\\[([^\\]]*)\\]").find(responseJson)?.groupValues?.get(1) ?: ""
            terms[k] = Regex("\"([^\"]*)\"").findAll(block).map { it.groupValues[1] }.toList()
        }
        if (!verify(version, validFrom, terms, signature, key)) return null
        return Config(version, validFrom, terms) to signature
    }

    /** Apply a server response, if [accept] approves it, and remember it across restarts. */
    fun acceptRemote(responseJson: String, store: MessageStore, now: Long): Boolean {
        val (c, sig) = accept(responseJson) ?: return false
        active = c
        store.saveReceiptRules(c, sig, now)
        return true
    }

    /** Test seam: forget any accepted configuration, so one test cannot bleed into the next. */
    fun resetForTest() { active = null }
}
