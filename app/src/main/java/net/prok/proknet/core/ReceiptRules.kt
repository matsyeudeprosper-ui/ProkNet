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
     * The configuration signing key, pinned in the app. Key id `9410c707`.
     *
     * This is a **dedicated** key. It is not the Brain's transport identity and not any
     * user identity, so a compromised Brain can withhold or delay a configuration - which
     * phones survive, because the built-in rules keep working - but can never forge one.
     *
     * The private half exists only on the admin machine and is used only by
     * `brain.publish_rules`. It is not in the repository and must never be.
     *
     * Must equal `CONFIG_PUBLIC_KEY` in `brain/app.py`, byte for byte.
     */
    const val PINNED_CONFIG_KEY =
        "9d536299f0c879aa6025e37b37efff18d7262bf53b434f23dfce26181367cd64" +
        "5450473b16377d9c53d7049c5dd6de7e2de78b88f745cff23a0e9bdb9533aa0c"

    const val MAX_TERMS_PER_CATEGORY = 64
    const val MAX_TERM_LENGTH = 48
    const val MAX_CONFIG_BYTES = 16 * 1024

    /** The only categories a configuration may carry. Anything else is refused, not ignored. */
    val CATEGORIES = listOf("credit", "debit", "currency", "balance", "reject", "senders")

    /**
     * The order the canonical bytes use. **Alphabetical**, because the publisher builds
     * them with `json.dumps(..., sort_keys=True)`.
     *
     * v0.16.3 fixed this. It used to emit [CATEGORIES] in declaration order, which meant
     * the two sides hashed different bytes and every signed configuration the server
     * published would have been refused by every phone - a feature that looked finished
     * and could never have worked once. A fixture test now pins the two spellings
     * together.
     */
    private val CANONICAL_ORDER = CATEGORIES.sorted()

    /**
     * Characters a term may not contain. Terms are WORDS, out of an operator's message. A
     * quote or a backslash would have to be JSON-escaped, and the escaping is exactly
     * where two independent canonicalisations drift apart.
     */
    val FORBIDDEN_IN_TERM = listOf('"', '\\')

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
        for ((i, k) in CANONICAL_ORDER.withIndex()) {
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
                // and nothing that would need JSON escaping, so the canonical bytes are
                // the same string on both sides without either having to escape anything
                if (t.any { it in FORBIDDEN_IN_TERM }) return false
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

    /**
     * Restore the last configuration we trusted, so a restart does not fall back silently.
     *
     * The signature is checked AGAIN on the way out of storage. Trusting it because we
     * trusted it once would make the phone's own database a place to write parser rules,
     * and the database is the easiest part of a phone to reach.
     */
    fun restore(store: MessageStore) {
        restore(store.receiptRules(), store.receiptRulesSignature())
    }

    /**
     * The decision [restore] makes, with the storage taken out of it.
     *
     * `MessageStore` needs a `Context`, so the version above cannot run off a phone - and
     * this is the part worth testing: what happens when a stored configuration no longer
     * verifies. Returns whether anything was activated.
     */
    fun restore(stored: Config?, signature: String, key: String = PINNED_CONFIG_KEY): Boolean {
        val c = stored ?: return false
        if (!verify(c.version, c.validFrom, c.terms, signature, key)) return false
        active = c
        return true
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
