package net.prok.proknet.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.16.3: the two halves of the system agree on the exact bytes they sign.
 *
 * Everything else in the project is two independent implementations of the same rule, one
 * in Kotlin and one in Python, tested separately and passing separately. That is precisely
 * how a cross-language mismatch survives: both sides are self-consistent and neither test
 * ever meets the other's bytes.
 *
 * It had already happened. `ReceiptRules.canonical` emitted its categories in declaration
 * order while `ruleconfig.canonical` sorted them, so every configuration the server signed
 * would have been refused by every phone - a finished-looking feature that could not have
 * worked once in the field, and that no amount of unit testing on either side would have
 * caught.
 *
 * So the fixture is written by Python, committed, and read here. A change to either
 * canonicalisation breaks this test instead of breaking the pilot.
 *
 * The keys in the fixture are test keys. They are in the repository deliberately and are
 * not the pilot's configuration key.
 */
class CrossLanguageFixtureTest {

    private val fixture: Map<String, String> by lazy { load() }

    /** Walk up from wherever Gradle started us until the fixture appears. */
    private fun fixtureFile(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val f = File(dir, "server/tests/fixtures/crosslang.json")
            if (f.isFile) return f
            dir = dir.parentFile
        }
        throw AssertionError("server/tests/fixtures/crosslang.json not found from " +
            File(".").absolutePath + " - regenerate it before running the tests")
    }

    /**
     * A deliberately small reader for a file we generate ourselves. It flattens
     * `"section.key"` so the tests read plainly, and it keeps the `terms` object as raw
     * text, which is all these tests need.
     */
    private fun load(): Map<String, String> {
        val text = fixtureFile().readText(Charsets.UTF_8)
        val out = HashMap<String, String>()
        for (section in listOf("request", "rules")) {
            val at = text.indexOf("\"" + section + "\"")
            require(at >= 0) { "fixture has no $section section" }
            val open = text.indexOf('{', at)
            var depth = 0
            var end = open
            while (end < text.length) {
                if (text[end] == '{') depth++
                if (text[end] == '}') { depth--; if (depth == 0) break }
                end++
            }
            val body = text.substring(open, end + 1)
            for (m in Regex("\"([A-Za-z0-9_]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(body))
                out[section + "." + m.groupValues[1]] = unescape(m.groupValues[2])
            for (m in Regex("\"([A-Za-z0-9_]+)\"\\s*:\\s*(-?[0-9]+)").findAll(body))
                out[section + "." + m.groupValues[1]] = m.groupValues[2]
        }
        return out
    }

    private fun unescape(s: String) = s
        .replace("\\\"", "\"").replace("\\n", "\n").replace("\\/", "/").replace("\\\\", "\\")

    private fun get(k: String): String = fixture[k] ?: throw AssertionError("fixture missing $k")

    // ================= the signed request =================

    @Test fun the_canonical_request_target_is_the_one_python_computed() {
        assertEquals(get("request.canonical_target"),
            SignedApi.canonicalTarget(get("request.request_target")))
    }

    @Test fun the_signing_line_is_byte_for_byte_the_one_python_built() {
        val ts = get("request.ts").toLong()
        val body = get("request.body").hexToBytes()
        assertEquals("the body must hash the same on both sides",
            get("request.body_hash"), SignedApi.bodyHash(body))
        val line = SignedApi.signingLine(ts, get("request.nonce"), SignedApi.bodyHash(body),
            get("request.method"), get("request.request_target"))
        assertEquals(get("request.signing_line"), String(line, Charsets.UTF_8))
    }

    @Test fun a_signature_python_made_verifies_here() {
        val ts = get("request.ts").toLong()
        val body = get("request.body").hexToBytes()
        val line = SignedApi.signingLine(ts, get("request.nonce"), SignedApi.bodyHash(body),
            get("request.method"), get("request.request_target"))
        assertTrue("the server signs, the phone must accept",
            Crypto.verify(get("request.public").hexToBytes(), line,
                get("request.python_signature").hexToBytes()))
    }

    @Test fun the_whole_signed_request_verifies_through_the_production_path() {
        val body = get("request.body").hexToBytes()
        val headers = mapOf(
            SignedApi.HEADER_IDENTITY to get("request.public"),
            SignedApi.HEADER_TIMESTAMP to get("request.ts"),
            SignedApi.HEADER_NONCE to get("request.nonce"),
            SignedApi.HEADER_SIGNATURE to get("request.python_signature"))
        assertTrue(SignedApi.verify(headers, body, get("request.ts").toLong(),
            get("request.method"), get("request.request_target")))
        // and the same headers must not pass at another endpoint
        assertTrue(!SignedApi.verify(headers, body, get("request.ts").toLong(),
            get("request.method"), "/v1/pay/receipt"))
    }

    @Test fun a_signature_this_phone_makes_verifies_against_the_fixture_key() {
        // the inverse direction, locally: the captured half is checked by the Python suite
        val priv = Crypto.privateKeyFrom(get("request.private_pkcs8").hexToBytes())
        val body = get("request.body").hexToBytes()
        val line = SignedApi.signingLine(get("request.ts").toLong(), get("request.nonce"),
            SignedApi.bodyHash(body), get("request.method"), get("request.request_target"))
        val sig = Crypto.sign(priv, line)
        assertTrue(Crypto.verify(get("request.public").hexToBytes(), line, sig))
        // printed so it can be captured into the fixture for the Python half to verify
        println("KOTLIN_REQUEST_SIGNATURE=" + sig.toHex())
    }

    // ================= the signed rule configuration =================

    @Test fun the_canonical_configuration_is_byte_for_byte_pythons() {
        val terms = mapOf(
            "credit" to listOf("fonds arrives", "vous avez recu"),
            "currency" to listOf("XAF", "FCFA"))
        val canon = ReceiptRules.canonical(get("rules.version").toInt(),
            get("rules.validFrom").toLong(), terms)
        assertEquals("a mismatch here is a configuration no phone could ever accept",
            get("rules.canonical"), String(canon, Charsets.UTF_8))
    }

    @Test fun a_configuration_python_signed_is_accepted_here() {
        val got = ReceiptRules.accept(get("rules.server_response"), get("rules.public"), minVersion = 0)
        assertNotNull("the publisher signs, the phone must accept", got)
        assertEquals(get("rules.version").toInt(), got!!.first.version)
        assertEquals(listOf("fonds arrives", "vous avez recu"), got.first.terms["credit"])
    }

    @Test fun a_configuration_this_phone_signs_verifies_against_the_fixture_key() {
        val priv = Crypto.privateKeyFrom(get("rules.private_pkcs8").hexToBytes())
        val terms = mapOf(
            "credit" to listOf("fonds arrives", "vous avez recu"),
            "currency" to listOf("XAF", "FCFA"))
        val canon = ReceiptRules.canonical(get("rules.version").toInt(),
            get("rules.validFrom").toLong(), terms)
        val sig = Crypto.sign(priv, canon)
        assertTrue(ReceiptRules.verify(get("rules.version").toInt(), get("rules.validFrom").toLong(),
            terms, sig.toHex(), get("rules.public")))
        println("KOTLIN_RULES_SIGNATURE=" + sig.toHex())
    }

    // ================= the pinned key is the deployed one =================

    @Test fun the_app_pins_a_real_configuration_key() {
        assertEquals("a pinned key is 64 raw bytes as hex", 128, ReceiptRules.PINNED_CONFIG_KEY.length)
        assertTrue(ReceiptRules.PINNED_CONFIG_KEY.all { it in "0123456789abcdef" })
        assertTrue("the pinned key must not be a test key",
            ReceiptRules.PINNED_CONFIG_KEY != get("rules.public"))
        // and it must be usable: a key that cannot be parsed would fail closed for ever
        assertEquals(64, ReceiptRules.PINNED_CONFIG_KEY.hexToBytes().size)
        Crypto.publicKeyFrom(ReceiptRules.PINNED_CONFIG_KEY.hexToBytes())
    }
}
