package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.16.2: a signature is for one endpoint, not for a body.
 *
 * v0.16.1 signed only `domain|ts|nonce|bodyHash`. That was enough while there was one
 * endpoint. It stopped being enough the moment several payment endpoints started accepting
 * similar JSON: a captured request to one of them could be replayed at another that happens
 * to read the same fields, and the signature would still be valid.
 *
 * So the method and the path are inside the signed bytes now. These tests pin the exact
 * line, because the server recomputes it independently in `signed_request.signing_line` —
 * a mismatch here is not a failing test on a phone, it is every payment request rejected.
 */
class SignedRequestBindingTest {

    private val kp = Crypto.generateKeyPair()
    private val now = 1_700_000_000_000L
    private val body = "{\"payment_id\":\"abc\"}".toByteArray(Charsets.UTF_8)

    private val signer = object : Signer {
        override val pubBytes: ByteArray = Crypto.publicBytes(kp.public)
        override val idBytes: ByteArray = Crypto.deriveId(Crypto.publicBytes(kp.public))
        override val displayName: String = "test"
        override fun sign(data: ByteArray): ByteArray = Crypto.sign(kp.private, data)
    }

    @Test fun the_bound_line_is_byte_for_byte_what_the_server_builds() {
        val hash = SignedApi.bodyHash(body)
        val line = String(SignedApi.signingLine(now, "nonce1234", hash, "post", "/v1/pay/receipt"), Charsets.UTF_8)
        assertEquals("ProkNet-api-1|$now|nonce1234|$hash|POST|/v1/pay/receipt", line)
    }

    @Test fun an_unbound_line_is_unchanged_so_older_phones_keep_working() {
        val hash = SignedApi.bodyHash(body)
        assertEquals("ProkNet-api-1|$now|nonce1234|$hash",
            String(SignedApi.signingLine(now, "nonce1234", hash), Charsets.UTF_8))
    }

    @Test fun a_signature_made_for_one_endpoint_does_not_verify_at_another() {
        val h = SignedApi.sign(body, signer, now, method = "POST", path = "/v1/pay/receipt")
        assertTrue(SignedApi.verify(h.asMap(), body, now, "POST", "/v1/pay/receipt"))
        assertFalse("the same body must not pass at a different endpoint",
            SignedApi.verify(h.asMap(), body, now, "POST", "/v1/pay/destination"))
        assertFalse("nor with a different method",
            SignedApi.verify(h.asMap(), body, now, "GET", "/v1/pay/receipt"))
        assertFalse("nor stripped of the binding",
            SignedApi.verify(h.asMap(), body, now))
    }

    @Test fun the_method_is_compared_without_case_so_both_sides_agree() {
        val h = SignedApi.sign(body, signer, now, method = "post", path = "/v1/pay/receipt")
        assertTrue(SignedApi.verify(h.asMap(), body, now, "POST", "/v1/pay/receipt"))
        assertTrue(SignedApi.verify(h.asMap(), body, now, "post", "/v1/pay/receipt"))
    }

    @Test fun the_body_is_still_covered() {
        val h = SignedApi.sign(body, signer, now, method = "POST", path = "/v1/pay/receipt")
        val tampered = "{\"payment_id\":\"abd\"}".toByteArray(Charsets.UTF_8)
        assertFalse(SignedApi.verify(h.asMap(), tampered, now, "POST", "/v1/pay/receipt"))
    }

    @Test fun the_skew_window_still_applies_to_a_bound_request() {
        val h = SignedApi.sign(body, signer, now, method = "POST", path = "/v1/pay/receipt")
        assertFalse(SignedApi.verify(h.asMap(), body, now + 10 * 60_000, "POST", "/v1/pay/receipt"))
        assertFalse(SignedApi.verify(h.asMap(), body, now - 10 * 60_000, "POST", "/v1/pay/receipt"))
    }

    @Test fun two_requests_never_share_a_nonce() {
        val a = SignedApi.sign(body, signer, now, method = "POST", path = "/v1/pay/receipt")
        val b = SignedApi.sign(body, signer, now, method = "POST", path = "/v1/pay/receipt")
        assertNotEquals(a.nonce, b.nonce)
        assertTrue(a.nonce.length in 8..64)
    }
}
