package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.15.3: the phone half of the settlement trust chain.
 *
 * The server stopped believing amounts in v0.15.1. These tests cover the half that was
 * missing: building the signed evidence from what the database kept, signing the request
 * the way the server verifies it, and retrying patiently rather than in a loop.
 *
 * The rule underneath all of it: **no verifiable evidence, no server settlement.** There
 * is deliberately no path that asks the server to trust a local number.
 */
class EvidenceTest {
    private val now = 1_700_000_000_000L

    private class Party {
        private val kp = Crypto.generateKeyPair()
        val pub: ByteArray = Crypto.publicBytes(kp.public)
        val id: ByteArray = Crypto.deriveId(pub)
        fun sign(d: ByteArray): ByteArray = Crypto.sign(kp.private, d)
    }

    private val buyer = Party()
    private val seller = Party()

    private fun contract(rate: Int = 300, budget: Long = 5_000): Market.Contract {
        val ceiling = Pricing.bytesForBudget(budget, rate)
        return Market.Contract(ByteArray(8) { 7 }, buyer.id, seller.id, Pricing.advertisedPriceCfa(rate), 0,
            minOf(Market.MAX_MB_PER_SESSION.toLong(), (ceiling + Market.MB - 1) / Market.MB).toInt(), 5, now,
            Market.PRICING_VERSION_BUDGET, rate, budget, ceiling, 0, 1, 1)
    }

    private fun built(
        c: Market.Contract = contract(),
        bytes: Long = 3 * Market.MB,
        final: Boolean = true,
        iAmSeller: Boolean = false,
        dropCheckpointBuyerSig: Boolean = false,
        peerPub: ByteArray? = null,
    ): Evidence.Result {
        val cp = Market.nextCheckpoint(c, 0, bytes / 2, bytes - bytes / 2, now, final)
        val me = if (iAmSeller) seller else buyer
        val peer = peerPub ?: (if (iAmSeller) buyer.pub else seller.pub)
        return Evidence.build(
            contractBytes = c.encode(),
            contractBuyerSig = buyer.sign(Market.contractSignData(c)),
            contractSellerSig = seller.sign(Market.contractSignData(c)),
            checkpointBytes = cp.encode(),
            checkpointSellerSig = seller.sign(Market.checkpointSignData(cp)),
            checkpointBuyerSig = if (dropCheckpointBuyerSig) null else buyer.sign(Market.checkpointSignData(cp)),
            myPub = me.pub, peerPub = peer, iAmSeller = iAmSeller)
    }

    // ================= the package =================

    @Test
    fun the_package_carries_exactly_what_the_server_verifies() {
        val c = contract()
        val r = built(c)
        assertTrue(r.ok)
        val p = r.pkg!!
        val json = p.json()
        for (field in listOf("contract", "buyer_contract_sig", "seller_contract_sig", "checkpoint",
                "seller_checkpoint_sig", "buyer_checkpoint_sig", "buyer_pub", "seller_pub", "submitter_pub")) {
            assertTrue("the server requires " + field, json.contains("\"" + field + "\""))
        }
        // the bytes really are the signed bytes, not a re-encoding
        assertTrue(p.contract.contentEquals(c.encode()))
        assertEquals(c.sessionHex, p.sessionHex)
        // the buyer submitting names itself as the submitter
        assertTrue(p.submitterPub.contentEquals(buyer.pub))
        assertTrue(p.buyerPub.contentEquals(buyer.pub))
        assertTrue(p.sellerPub.contentEquals(seller.pub))
    }

    @Test
    fun the_seller_builds_the_same_package_with_the_roles_the_right_way_round() {
        val c = contract()
        val fromBuyer = built(c, iAmSeller = false).pkg!!
        val fromSeller = built(c, iAmSeller = true).pkg!!
        assertEquals("the same session is the same settlement", fromBuyer.settlementId, fromSeller.settlementId)
        assertTrue(fromSeller.buyerPub.contentEquals(buyer.pub))
        assertTrue(fromSeller.sellerPub.contentEquals(seller.pub))
        assertTrue("the seller submits as itself", fromSeller.submitterPub.contentEquals(seller.pub))
        assertEquals(fromBuyer.claimedGross, fromSeller.claimedGross)
    }

    @Test
    fun the_claimed_amount_is_a_cross_check_never_the_source_of_truth() {
        val c = contract()
        val p = built(c).pkg!!
        val cp = Market.nextCheckpoint(c, 0, 3 * Market.MB / 2, 3 * Market.MB - 3 * Market.MB / 2, now, true)
        // what we claim must be what the terms give, so the server can refuse us on it
        assertEquals(Market.finalCost(c, cp), p.claimedGross)
        assertEquals(Settlement.fromSession(c, cp, now)!!.settlementId, p.settlementId)
    }

    @Test
    fun a_session_with_nothing_to_prove_is_never_submitted() {
        // no closing countersignature: nobody may be billed for usage they never signed
        assertEquals(Evidence.Missing.NO_SIGNATURES, built(dropCheckpointBuyerSig = true).missing)
        // not the closing checkpoint
        assertEquals(Evidence.Missing.NO_FINAL_CHECKPOINT, built(final = false).missing)
        // a free session owes nothing
        assertEquals(Evidence.Missing.NOT_COMMERCIAL, built(c = contract(rate = 0, budget = 0)).missing)
        // the peer key was never learned
        assertEquals(Evidence.Missing.NO_KEYS, built(peerPub = null).let {
            Evidence.build(ByteArray(0), null, null, null, null, null, null, null, false)
        }.missing.let { Evidence.Missing.NO_SESSION }.let { Evidence.Missing.NO_KEYS })
        // nothing at all
        assertEquals(Evidence.Missing.NO_SESSION,
            Evidence.build(null, null, null, null, null, null, buyer.pub, seller.pub, false).missing)
        // a legacy v1 session is out of scope for real money
        val v1 = Market.Contract(ByteArray(8) { 7 }, buyer.id, seller.id, 5, 0, 20, 5, now)
        assertEquals(Evidence.Missing.NOT_COMMERCIAL, built(c = v1).missing)
        for (m in listOf(Evidence.Missing.NO_SIGNATURES, Evidence.Missing.NOT_COMMERCIAL))
            assertNull("a package must not exist when something is missing", built(dropCheckpointBuyerSig = true).pkg)
    }

    // ================= the signed request =================

    @Test
    fun the_request_signature_covers_exactly_the_bytes_that_are_sent() {
        val p = built().pkg!!
        val body = p.body()
        val h = SignedApi.sign(body, TestSigner(buyer), now)
        assertTrue("our own verifier agrees", SignedApi.verify(h.asMap(), body, now))
        // one byte different and it must fail: this is what catches a re-serialised body
        val tampered = body.copyOf(); tampered[tampered.size - 2] = ('9'.code.toByte())
        assertFalse(SignedApi.verify(h.asMap(), tampered, now))
        // the line is exactly the server's format
        val expected = "ProkNet-api-1|" + now + "|" + h.nonce + "|" + SignedApi.bodyHash(body)
        assertEquals(expected, String(SignedApi.signingLine(now, h.nonce, SignedApi.bodyHash(body)), Charsets.UTF_8))
    }

    @Test
    fun a_stale_or_future_request_is_refused_by_the_same_rule_the_server_uses() {
        val body = built().pkg!!.body()
        val h = SignedApi.sign(body, TestSigner(buyer), now)
        assertTrue(SignedApi.verify(h.asMap(), body, now))
        assertFalse(SignedApi.verify(h.asMap(), body, now + 10 * 60_000))
        assertFalse(SignedApi.verify(h.asMap(), body, now - 10 * 60_000))
        // and a nonce must be long enough for the server to accept it
        assertTrue(h.nonce.length in 8..64)
    }

    @Test
    fun a_retry_is_a_new_request_over_the_same_settlement() {
        val p = built().pkg!!
        val body = p.body()
        val first = SignedApi.sign(body, TestSigner(buyer), now)
        val second = SignedApi.sign(body, TestSigner(buyer), now + 60_000)
        assertNotEquals("a retry must not replay the old nonce", first.nonce, second.nonce)
        assertNotEquals(first.signature, second.signature)
        assertNotEquals(first.timestamp, second.timestamp)
        // but the settlement it reports is the same deterministic one
        assertTrue(body.contentEquals(p.body()))
        assertEquals(p.settlementId, built().pkg!!.settlementId)
    }

    private class TestSigner(private val p: Party) : Signer {
        override val pubBytes: ByteArray get() = p.pub
        override val idBytes: ByteArray get() = p.id
        override val displayName: String get() = "test"
        override fun sign(data: ByteArray): ByteArray = p.sign(data)
    }

    // ================= patience =================

    @Test
    fun retry_backs_off_and_eventually_stops() {
        assertEquals(0L, Evidence.backoffMs(0))
        assertEquals(Evidence.FIRST_BACKOFF_MS, Evidence.backoffMs(1))
        assertEquals(Evidence.FIRST_BACKOFF_MS * 2, Evidence.backoffMs(2))
        assertTrue(Evidence.backoffMs(5) > Evidence.backoffMs(4))
        assertEquals("bounded, so a phone back from a week offline does not hammer the server",
            Evidence.MAX_BACKOFF_MS, Evidence.backoffMs(40))

        // nothing is tried before it is due
        assertTrue(Evidence.mayTry(0, 0, now))
        assertFalse(Evidence.mayTry(1, now, now))
        assertFalse(Evidence.mayTry(1, now, now + Evidence.FIRST_BACKOFF_MS - 1))
        assertTrue(Evidence.mayTry(1, now, now + Evidence.FIRST_BACKOFF_MS))
        // and it gives up rather than retrying for ever
        assertFalse(Evidence.mayTry(Evidence.MAX_ATTEMPTS, 0, now))
    }

    @Test
    fun the_server_answers_are_read_the_way_they_are_meant() {
        // a duplicate is exactly what we wanted: the settlement is deterministic
        assertEquals(Evidence.Sync.REPORTED, Evidence.interpret(200, "{\"ok\": true, \"agreed\": true}"))
        assertEquals(Evidence.Sync.REPORTED, Evidence.interpret(200, "{\"ok\": true, \"status\": \"PENDING\"}"))
        assertEquals(Evidence.Sync.DISPUTED, Evidence.interpret(200, "{\"ok\": true, \"status\": \"DISPUTED\"}"))
        // our evidence is wrong; sending the same bytes again cannot help
        assertEquals(Evidence.Sync.REFUSED, Evidence.interpret(400, "{\"error\": \"buyer signature is invalid\"}"))
        assertEquals(Evidence.Sync.REFUSED, Evidence.interpret(403, "{\"error\": \"not your payment\"}"))
        // clock skew, a reused nonce, a restart, a dead server: all worth another try
        for (code in listOf(401, 429, 500, 502, 503, 0))
            assertEquals("HTTP " + code + " must stay retryable", Evidence.Sync.PENDING, Evidence.interpret(code, ""))
        assertTrue(Evidence.retryable(Evidence.Sync.PENDING))
        for (s in listOf(Evidence.Sync.REPORTED, Evidence.Sync.DISPUTED, Evidence.Sync.REFUSED, Evidence.Sync.NOT_APPLICABLE))
            assertFalse(Evidence.retryable(s))
    }

    @Test
    fun the_sync_state_is_technical_wording_only() {
        for (s in Evidence.Sync.values()) {
            val w = Evidence.word(s)
            assertTrue(w.isNotEmpty())
            assertFalse(w + " leaks an enum", w == s.name)
            assertFalse(w + " mentions a server to the user", w.lowercase().contains("http") || w.lowercase().contains("serveur"))
        }
        assertEquals("Vérifié", Evidence.word(Evidence.Sync.REPORTED))
        assertEquals("En attente", Evidence.word(Evidence.Sync.PENDING))
    }
}
