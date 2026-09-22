package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.17.2: a Brain job stands on its own.
 *
 * The bug this file exists for. v0.17.1's provider path did:
 *
 *     val r = state.requests[j.demandId]
 *     if (r != null) inbox = ProviderInbox.offer(...)
 *
 * so a Brain activation only produced anything if the buyer's `NetRequest` had already
 * arrived through the legacy gossip path. Two phones on opposite sides of a
 * neighbourhood have no reason to have exchanged anything - which is the entire scenario
 * the Network Brain was built for. The activation arrived, the request store was empty,
 * and the provider got no opportunity, no notification and no card.
 *
 * `the_provider_has_never_seen_this_buyer_before` below is the test that must fail on
 * v0.17.1 and pass on v0.17.2.
 */
class BrainJobInboxTest {

    private val now = 1_700_000_000_000L

    private class Party {
        private val kp = Crypto.generateKeyPair()
        val pub: ByteArray = Crypto.publicBytes(kp.public)
        val shortId: String = Crypto.deriveId(pub).toHex().take(8)
        val signer = object : Signer {
            override val pubBytes: ByteArray = pub
            override val idBytes: ByteArray = Crypto.deriveId(pub)
            override val displayName: String = "test"
            override fun sign(data: ByteArray): ByteArray = Crypto.sign(kp.private, data)
        }
    }

    private val buyer = Party()

    /** The buyer's own signed request, exactly as `originate` builds it. */
    private fun signedRequest(id: String = "aa".repeat(8), zone: String = "z1:1",
                              at: Long = now): NetRequest.Request =
        NetRequest.sign(NetRequest.oneTap(id, buyer.shortId, buyer.pub.toHex(), at, zone),
            buyer.signer)

    // ================= item 34: no legacy request anywhere =================

    @Test fun the_provider_has_never_seen_this_buyer_before() {
        val r = signedRequest()
        // the provider's request store is EMPTY. This is the whole point.
        val store = RequestGossip.State()
        assertNull("the fixture must have no prior request", store.requests[r.id])

        // the job arrives carrying the buyer's own signed line, and it verifies
        val line = NetRequest.encodeLine(r)
        val carried = NetRequest.decodeLine(line)
        assertNotNull(carried)
        assertTrue("the provider checks the BUYER's signature, not the Brain's word",
            NetRequest.verify(carried!!))

        // and an opportunity exists without the gossip path ever running
        val inbox = ProviderInbox.offerFromBrain(
            ProviderInbox.State(), carried, "act-1", now)
        val o = inbox.items[r.id]
        assertNotNull("v0.17.1 produced nothing here", o)
        assertEquals(ProviderInbox.Source.BRAIN, o!!.source)
        assertEquals("act-1", o.brainActivationId)
        assertTrue("and it is eligible to raise a notification",
            ProviderInbox.pending(inbox, now).isNotEmpty())
    }

    @Test fun a_tampered_request_produces_no_opportunity() {
        val r = signedRequest()
        val fields = NetRequest.encodeLine(r).split("\t").toMutableList()
        // each of these is inside the signature
        for ((index, value) in listOf(0 to "bb".repeat(8), 6 to "z9:9",
                5 to (r.expiresAt + 600_000).toString())) {
            val bad = ArrayList(fields)
            bad[index] = value
            val decoded = NetRequest.decodeLine(bad.joinToString("\t"))
            assertTrue("field " + index + " survived editing",
                decoded == null || !NetRequest.verify(decoded))
        }
    }

    @Test fun a_request_signed_by_somebody_else_does_not_verify_as_this_buyer() {
        val other = Party()
        val r = signedRequest()
        val fields = NetRequest.encodeLine(r).split("\t").toMutableList()
        fields[2] = other.pub.toHex()                 // swap the public key
        val decoded = NetRequest.decodeLine(fields.joinToString("\t"))
        assertTrue(decoded == null || !NetRequest.verify(decoded))
    }

    @Test fun an_expired_request_is_not_worth_offering() {
        val old = signedRequest(at = now - 2 * NetRequest.NOW_TTL_MS)
        assertTrue(NetRequest.verify(old))
        assertTrue("it verifies, and it is still too old to act on", old.expired(now))
    }

    // ================= items 7-10: the activation is remembered and acknowledged =================

    @Test fun a_brain_opportunity_keeps_the_activation_that_created_it() {
        val inbox = ProviderInbox.offerFromBrain(
            ProviderInbox.State(), signedRequest(), "act-42", now)
        assertEquals("act-42", inbox.items["aa".repeat(8)]!!.brainActivationId)
    }

    @Test fun a_local_opportunity_has_no_activation() {
        val inbox = ProviderInbox.offer(
            ProviderInbox.State(), signedRequest(), ProviderInbox.Source.LOCAL, now)
        assertEquals("", inbox.items["aa".repeat(8)]!!.brainActivationId)
        assertTrue(!inbox.items["aa".repeat(8)]!!.needsBrainAck())
    }

    @Test fun accepting_marks_it_as_needing_the_brain_to_be_told() {
        var inbox = ProviderInbox.offerFromBrain(
            ProviderInbox.State(), signedRequest(), "act-42", now)
        inbox = ProviderInbox.accept(inbox, "aa".repeat(8), now)
        val o = inbox.items["aa".repeat(8)]!!
        assertTrue(o.accepted)
        assertTrue("this is what drives the retry", o.needsBrainAck())
    }

    @Test fun the_job_coming_back_offered_does_not_forget_the_tap() {
        // the acceptance was persisted and the network call failed. The Brain still says
        // OFFERED, so the job is re-offered - and must not reset what the provider did.
        var inbox = ProviderInbox.offerFromBrain(
            ProviderInbox.State(), signedRequest(), "act-42", now)
        inbox = ProviderInbox.accept(inbox, "aa".repeat(8), now)
        inbox = ProviderInbox.offerFromBrain(inbox, signedRequest(), "act-42", now + 30_000)
        val o = inbox.items["aa".repeat(8)]!!
        assertTrue("re-offering must not undo PARTAGER", o.accepted)
        assertTrue(o.needsBrainAck())
        assertEquals("act-42", o.brainActivationId)
    }

    // ================= item 33: it survives a restart =================

    @Test fun an_accepted_brain_opportunity_survives_being_written_and_read_back() {
        var inbox = ProviderInbox.offerFromBrain(
            ProviderInbox.State(), signedRequest(), "act-42", now)
        inbox = ProviderInbox.accept(inbox, "aa".repeat(8), now)

        val back = ProviderInbox.decode(ProviderInbox.encode(inbox))
        val o = back.items["aa".repeat(8)]
        assertNotNull("the provider tapped PARTAGER; a restart must not lose it", o)
        assertTrue(o!!.accepted)
        assertEquals("act-42", o.brainActivationId)
        assertTrue("so the retry still knows what to send", o.needsBrainAck())
    }

    @Test fun an_inbox_written_by_build_69_still_loads() {
        // nine fields, no activation id. An old install must not lose its inbox.
        val old = "V\t1\nO\t" + listOf("aa".repeat(8), buyer.shortId, "BRAIN", "z1:1",
            now.toString(), (now + 600_000).toString(), "0", "0", "true").joinToString("\t") + "\n"
        val back = ProviderInbox.decode(old)
        val o = back.items["aa".repeat(8)]
        assertNotNull("an upgrade must not drop opportunities", o)
        assertTrue(o!!.accepted)
        assertEquals("", o.brainActivationId)
        assertTrue("with no activation id there is nothing to retry, and that is honest",
            !o.needsBrainAck())
    }

    @Test fun one_bad_line_does_not_lose_the_rest() {
        var inbox = ProviderInbox.offerFromBrain(
            ProviderInbox.State(), signedRequest(), "act-42", now)
        val text = ProviderInbox.encode(inbox) + "O\trubbish\n"
        assertEquals(1, ProviderInbox.decode(text).items.size)
    }
}
