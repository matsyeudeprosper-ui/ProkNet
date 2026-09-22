package net.prok.proknet.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.17.3: the two runtime blockers that could still have failed the real two-phone test.
 *
 * Every test in this file describes something build 70 could NOT do, with the reason in
 * the name. Both faults had the same shape: each piece was correct on its own, and the
 * join between them made a state the product depends on unreachable.
 *
 *  1. PRESENCE REQUIRED `sellOn`. The Brain could only see a phone that was already
 *     sharing, so an idle opted-in provider was invisible and could never be offered a
 *     buyer - while a phone with sharing already on would refuse the job it was offered
 *     with ALREADY_SHARING. Neither state completes the loop.
 *
 *  2. THE ACK RETRY COULD NOT RUN. `pollJobs` returned early when the activation ids had
 *     not changed, and the stuck case is by definition the same activation coming back
 *     unchanged - so the retry existed and was unreachable, and `answer` returning a
 *     Boolean meant a dead network looked exactly like a refusal.
 */
class BrainAckTest {

    private val now = 1_700_000_000_000L
    private val zone = "z1:1"

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

    private fun request(id: String = "aa".repeat(8)): NetRequest.Request =
        NetRequest.sign(NetRequest.oneTap(id, buyer.shortId, buyer.pub.toHex(), now, zone), buyer.signer)

    /** The exact OUKITEL state in TESTING 75: opted in, Internet, Bluetooth, NOT sharing. */
    private fun idleProvider(optIn: Boolean = true, validated: Boolean = true,
                             bluetooth: Boolean = true, busy: Boolean = false,
                             sharing: Boolean = false) =
        ProviderActivation.eligibility(
            optIn = optIn, upstreamType = Tunnel.UP_WIFI, upstreamValidated = validated,
            bulkSupported = true, bluetoothOn = bluetooth,
            alreadySharing = sharing, busy = busy, sellPriceCentimesPerMb = 500)

    // ===================== item 21: the idle provider is visible =====================

    @Test fun an_idle_opted_in_provider_is_offered_to_the_brain() {
        // notifyOptIn = true, sellOn = FALSE, upstream validated, Bluetooth on, not busy
        val p = ProviderPresence.of(zone, idleProvider(), currentlySharing = false,
            activeSessions = 0, mayOfferPaidSharing = true)
        assertNotNull("an idle willing provider must produce a presence", p)
        p!!
        // build 70 sent sharingEnabled = optIn && sellOn, so this was false and the
        // heartbeat published nothing at all. No presence, no activation, ever.
        assertTrue("willing", p.willing)
        assertTrue("the Brain may ask this phone", p.availableForActivation)
        assertTrue("the heartbeat must go out", p.shouldPublish)
        assertFalse("and it is honestly not sharing yet", p.currentlySharing)
        assertEquals(0, p.currentLoad)
        assertEquals(1, p.maxBuyers)
        assertEquals(1, ProviderPresence.MAX_BUYERS)
        assertTrue("it has a path it could offer", p.upstreamAvailable)
        assertEquals("VALIDATED", p.upstreamClass)
    }

    @Test fun readiness_describes_ability_and_not_the_gateway() {
        // item 4: "if the user accepts this request, can this phone serve it?"
        val paid = ProviderPresence.of(zone, idleProvider(), false, 0, mayOfferPaidSharing = true)!!
        assertTrue("a paid-ready seller is commercially ready before it starts", paid.commercialReady)
        assertFalse(paid.freeReady)
        assertEquals(ProviderPresence.COMMERCIAL, paid.offerClass)

        val free = ProviderPresence.of(zone, idleProvider(), false, 0, mayOfferPaidSharing = false)!!
        assertFalse("v0.16 payment safety is untouched", free.commercialReady)
        assertTrue(free.freeReady)
        assertEquals(ProviderPresence.FREE, free.offerClass)
        // and neither of them is sharing yet
        assertFalse(paid.currentlySharing); assertFalse(free.currentlySharing)
    }

    // ===================== item 7: opt-in is still required =====================

    @Test fun a_phone_that_never_opted_in_is_never_a_provider() {
        val p = ProviderPresence.of(zone, idleProvider(optIn = false), false, 0, true)!!
        assertFalse("no silent sharing, paid or free", p.willing)
        assertFalse(p.availableForActivation)
        assertFalse("and the presence is withdrawn rather than left stale", p.shouldPublish)
        assertFalse(p.commercialReady); assertFalse(p.freeReady)
    }

    @Test fun willingness_needs_real_internet_and_a_real_path() {
        // unvalidated Internet is not Internet
        assertFalse(ProviderPresence.of(zone, idleProvider(validated = false), false, 0, true)!!.willing)
        // home Wi-Fi with Bluetooth off has no local path to offer at all
        val noPath = ProviderPresence.of(zone, idleProvider(bluetooth = false), false, 0, true)!!
        assertFalse(noPath.willing)
        assertFalse(noPath.upstreamAvailable)
        // and without a zone there is no presence to publish
        assertNull(ProviderPresence.of("", idleProvider(), false, 0, true))
        assertNull(ProviderPresence.of(CoverageModel.NO_ZONE, idleProvider(), false, 0, true))
    }

    @Test fun build_70s_own_expression_would_have_published_nothing_for_this_phone() {
        // the fix, pinned against the exact code it replaced. Build 70 built the presence
        // as `sharingEnabled = e.optIn && node.sellOn` and the heartbeat published only
        // when that was true, so this phone - opted in, with Internet, not sharing - was
        // invisible and could never be offered a buyer.
        val e = idleProvider()
        val sellOn = false
        val build70SharingEnabled = e.optIn && sellOn
        assertFalse("build 70 published nothing here", build70SharingEnabled)

        val p = ProviderPresence.of(zone, e, currentlySharing = sellOn,
            activeSessions = 0, mayOfferPaidSharing = true)!!
        assertTrue("and build 71 publishes", p.shouldPublish)
        assertTrue("and says it may be asked", p.availableForActivation)
        // the two answers genuinely differ - this test would be worthless otherwise
        assertTrue(build70SharingEnabled != p.availableForActivation)
    }

    @Test fun build_70s_change_test_could_not_see_a_job_changing_state() {
        // the second blocker, pinned the same way. Build 70 compared activation ids
        // alone, and the early return it guarded also skipped the acknowledgement retry -
        // whose entire case is a list that has not changed.
        val offered = listOf("act-1" to "OFFERED")
        val accepted = listOf("act-1" to "ACCEPTED")
        val build70Key = { l: List<Pair<String, String>> -> l.map { it.first } }
        assertEquals("build 70 saw no movement", build70Key(offered), build70Key(accepted))
        assertTrue("build 71 does", BrainAck.movementKey(offered) != BrainAck.movementKey(accepted))
        // and a genuinely unchanged list is still quiet, so the UI is not redrawn for nothing
        assertEquals(BrainAck.movementKey(offered), BrainAck.movementKey(listOf("act-1" to "OFFERED")))
    }

    // ===================== item 22: full means full, not invisible =====================

    @Test fun a_provider_already_serving_somebody_stays_visible_but_gets_no_second_buyer() {
        val p = ProviderPresence.of(zone, idleProvider(sharing = true, busy = true),
            currentlySharing = true, activeSessions = 1, mayOfferPaidSharing = true)!!
        // it is still willing, and still publishes - a busy provider is a real provider
        // and its zone is genuinely covered
        assertTrue(p.willing)
        assertTrue("presence keeps going while it serves", p.shouldPublish)
        assertTrue(p.currentlySharing)
        // but the Brain must not be told it may send another buyer
        assertEquals(1, p.currentLoad)
        assertEquals(1, p.maxBuyers)
        assertFalse("no second buyer", p.availableForActivation)
    }

    @Test fun capacity_is_reported_and_never_invented() {
        // the server clamps these anyway; the phone must not send nonsense in the first place
        val neg = ProviderPresence.of(zone, idleProvider(), false, -4, true)!!
        assertEquals(0, neg.currentLoad)
        val silly = ProviderPresence.of(zone, idleProvider(), false, 0, true, maxBuyers = 0)!!
        assertEquals(1, silly.maxBuyers)
    }

    @Test fun willing_is_one_definition_shared_with_the_old_availability_model() {
        // v0.17.3 did not add a parallel notion of "can share": `availability` is now
        // expressed in terms of the same function, so the two can never drift apart.
        for (busy in listOf(false, true)) {
            val e = idleProvider(busy = busy)
            val a = ProviderActivation.availability(e, zone, Tunnel.UP_WIFI)
            assertEquals(ProviderActivation.willing(e) && !busy, a.potential)
        }
    }

    // ===================== items 9/10/19: the retry can actually run =====================

    private fun accepted(activationId: String = "act-1", acked: Boolean = false): ProviderInbox.State {
        val r = request()
        var st = ProviderInbox.offerFromBrain(ProviderInbox.State(), r, activationId, now)
        st = ProviderInbox.accept(st, r.id, now)
        if (acked) st = ProviderInbox.brainAcked(st, r.id, true)
        return st
    }

    @Test fun an_unchanged_offered_job_still_triggers_the_retry() {
        // THE build-70 failure. The provider tapped PARTAGER, the accept died on the way,
        // and the next poll returned the SAME activation in the SAME state - which is the
        // only shape this case ever has. Build 70 compared ids, saw no change and
        // returned before the retry, so the tap could stay stuck for ever.
        val st = accepted()
        val o = st.items[request().id]!!
        assertTrue("the phone knows it owes the Brain an answer", o.needsBrainAck())
        assertEquals(BrainAck.Step.RESEND, BrainAck.step(o, "act-1", "OFFERED"))
        // and again on the next identical poll, and the one after that
        assertEquals(BrainAck.Step.RESEND, BrainAck.step(o, "act-1", "OFFERED"))
    }

    @Test fun an_accepted_job_settles_the_acknowledgement_without_sending_anything() {
        // item 18: the server already has it. Stop, and remember that.
        val st = accepted()
        val id = request().id
        assertEquals(BrainAck.Step.MARK_ACKED, BrainAck.step(st.items[id], "act-1", "ACCEPTED"))
        val after = ProviderInbox.brainAcked(st, id, true)
        assertTrue(after.items[id]!!.brainAcked)
        assertFalse("nothing more is owed", after.items[id]!!.needsBrainAck())
        assertEquals(BrainAck.Step.NOTHING, BrainAck.step(after.items[id], "act-1", "ACCEPTED"))
        assertEquals(BrainAck.Step.NOTHING, BrainAck.step(after.items[id], "act-1", "LOCAL_LINK_SEEN"))
    }

    @Test fun the_server_state_wins_over_what_the_phone_believed() {
        // a stale local "acknowledged" must not silence the one signal that says it was lost
        val st = accepted(acked = true)
        assertEquals(BrainAck.Step.RESEND, BrainAck.step(st.items[request().id], "act-1", "OFFERED"))
    }

    @Test fun somebody_elses_job_and_untapped_offers_are_left_alone() {
        val st = accepted()
        val id = request().id
        // a different activation for the same buyer is not the one we accepted
        assertEquals(BrainAck.Step.NOTHING, BrainAck.step(st.items[id], "act-2", "OFFERED"))
        // an offer nobody has tapped owes nothing
        val untapped = ProviderInbox.offerFromBrain(ProviderInbox.State(), request(), "act-9", now)
        assertEquals(BrainAck.Step.NOTHING, BrainAck.step(untapped.items[id], "act-9", "OFFERED"))
        // and a purely local opportunity has no activation behind it
        val local = ProviderInbox.accept(ProviderInbox.offer(ProviderInbox.State(), request(),
            ProviderInbox.Source.LOCAL, now), id, now)
        assertEquals(BrainAck.Step.NOTHING, BrainAck.step(local.items[id], "act-1", "OFFERED"))
        assertEquals(BrainAck.Step.NOTHING, BrainAck.step(null, "act-1", "OFFERED"))
    }

    // ===================== items 12-15/26: retryable is not a refusal =====================

    @Test fun a_dead_network_is_never_read_as_a_refusal() {
        // item 26. Build 70 removed the opportunity whenever answer() returned false,
        // and false was also what a timeout looked like.
        assertEquals(BrainAnswer.Result.RETRYABLE_FAILURE, BrainAnswer.unreachable())
        assertEquals(BrainAnswer.Result.RETRYABLE_FAILURE, BrainAnswer.classify(0, ""))
        assertEquals(BrainAnswer.Result.RETRYABLE_FAILURE, BrainAnswer.classify(500, "{\"error\":\"boom\"}"))
        assertEquals(BrainAnswer.Result.RETRYABLE_FAILURE, BrainAnswer.classify(503, ""))
        assertEquals(BrainAnswer.Result.RETRYABLE_FAILURE, BrainAnswer.classify(408, ""))
        // the Brain's own rate limit is "not now", not "never"
        assertEquals(BrainAnswer.Result.RETRYABLE_FAILURE,
            BrainAnswer.classify(400, "{\"error\":\"too many answers\"}"))
        assertEquals(BrainAnswer.Result.RETRYABLE_FAILURE, BrainAnswer.classify(429, "{\"error\":\"too many requests\"}"))
        // and each of those keeps everything exactly where it is
        for (c in listOf(0, 408, 429, 500, 503))
            assertEquals(BrainAck.Outcome.KEEP_AND_RETRY,
                BrainAck.outcome(BrainAnswer.classify(c, ""), sessionLive = false))
    }

    @Test fun a_reason_the_brain_states_is_final() {
        for (reason in BrainAnswer.TERMINAL_REASONS)
            assertEquals(reason, BrainAnswer.Result.TERMINAL_REJECT,
                BrainAnswer.classify(400, "{\"error\":\"x\",\"reason\":\"" + reason + "\"}"))
        assertEquals(BrainAck.Outcome.DROP_CARD,
            BrainAck.outcome(BrainAnswer.Result.TERMINAL_REJECT, sessionLive = false))
    }

    @Test fun an_unrecognised_refusal_is_treated_as_temporary() {
        // conservative on purpose: a newer Brain inventing a reason must not make this
        // phone throw away a provider's tap
        assertEquals(BrainAnswer.Result.RETRYABLE_FAILURE,
            BrainAnswer.classify(400, "{\"error\":\"x\",\"reason\":\"SOMETHING_NEW\"}"))
        assertEquals(BrainAnswer.Result.RETRYABLE_FAILURE, BrainAnswer.classify(404, "{\"error\":\"not found\"}"))
    }

    @Test fun a_dead_activation_drops_a_card_and_never_a_session() {
        // item 15. Somebody is using the Internet right now; bookkeeping does not cut
        // them off.
        assertEquals(BrainAck.Outcome.KEEP_AND_RETRY,
            BrainAck.outcome(BrainAnswer.Result.TERMINAL_REJECT, sessionLive = true))
        assertEquals(BrainAck.Outcome.DROP_CARD,
            BrainAck.outcome(BrainAnswer.Result.TERMINAL_REJECT, sessionLive = false))
    }

    @Test fun a_success_is_a_success_even_when_the_brain_already_had_it() {
        // item 16: the idempotent repeat. The server answers 200 with duplicate:1.
        assertEquals(BrainAnswer.Result.ACCEPTED,
            BrainAnswer.classify(200, "{\"ok\":1,\"duplicate\":1,\"state\":\"ACCEPTED\"}"))
        assertEquals(BrainAck.Outcome.ACKED, BrainAck.outcome(BrainAnswer.Result.ACCEPTED, false))
    }

    // ===================== item 24: the whole first-ACK-fails sequence =====================

    @Test fun the_first_acknowledgement_fails_and_the_second_one_lands() {
        val r = request()
        // the job arrives and the provider taps PARTAGER
        var st = ProviderInbox.offerFromBrain(ProviderInbox.State(), r, "act-1", now)
        st = ProviderInbox.accept(st, r.id, now)
        assertTrue(st.items[r.id]!!.accepted)

        // the accept request dies on the way: nothing may change
        var out = BrainAck.outcome(BrainAnswer.unreachable(), sessionLive = true)
        assertEquals(BrainAck.Outcome.KEEP_AND_RETRY, out)
        assertNotNull("the card stays", st.items[r.id])
        assertTrue("the tap stays", st.items[r.id]!!.accepted)
        assertEquals("the activation id stays", "act-1", st.items[r.id]!!.brainActivationId)
        assertFalse(st.items[r.id]!!.brainAcked)

        // the next poll returns the SAME job in the SAME state - build 70's blind spot
        assertEquals(BrainAck.Step.RESEND, BrainAck.step(st.items[r.id], "act-1", "OFFERED"))
        assertTrue(BrainAck.anyWork(st, listOf(Triple("act-1", r.id, "OFFERED"))))

        // and this time the Brain takes it
        out = BrainAck.outcome(BrainAnswer.classify(200, "{\"ok\":1,\"state\":\"ACCEPTED\"}"), true)
        assertEquals(BrainAck.Outcome.ACKED, out)
        st = ProviderInbox.brainAcked(st, r.id, true)
        assertFalse("and nothing is owed any more", st.items[r.id]!!.needsBrainAck())
        assertFalse(BrainAck.anyWork(st, listOf(Triple("act-1", r.id, "ACCEPTED"))))
    }

    // ===================== items 11/25: restart recovery =====================

    @Test fun the_acceptance_survives_a_restart_and_is_sent_again_without_a_second_tap() {
        val r = request()
        var st = ProviderInbox.offerFromBrain(ProviderInbox.State(), r, "act-1", now)
        st = ProviderInbox.accept(st, r.id, now)

        // the process dies here. Everything the retry needs must be on disk.
        val onDisk = ProviderInbox.encode(st)
        val reborn = ProviderInbox.decode(onDisk)
        val o = reborn.items[r.id]
        assertNotNull("an accepted opportunity must survive", o)
        assertTrue(o!!.accepted)
        assertEquals("act-1", o.brainActivationId)
        assertFalse(o.brainAcked)
        assertTrue(o.needsBrainAck())

        // the server still says OFFERED, so the very next poll sends it again
        assertEquals(BrainAck.Step.RESEND, BrainAck.step(o, "act-1", "OFFERED"))
        val after = ProviderInbox.brainAcked(reborn, r.id, true)
        assertTrue(ProviderInbox.decode(ProviderInbox.encode(after)).items[r.id]!!.brainAcked)
    }

    @Test fun a_re_offer_of_the_same_activation_keeps_both_the_tap_and_the_acknowledgement() {
        val r = request()
        var st = ProviderInbox.offerFromBrain(ProviderInbox.State(), r, "act-1", now)
        st = ProviderInbox.brainAcked(ProviderInbox.accept(st, r.id, now), r.id, true)
        // the same job comes round again on the next poll
        val again = ProviderInbox.offerFromBrain(st, r, "act-1", now + 1_000)
        assertTrue("re-offering must not undo the tap", again.items[r.id]!!.accepted)
        assertTrue("nor forget that the Brain has it", again.items[r.id]!!.brainAcked)
        // but a genuinely NEW activation has certainly not been acknowledged
        val fresh = ProviderInbox.offerFromBrain(st, r, "act-2", now + 2_000)
        assertEquals("act-2", fresh.items[r.id]!!.brainActivationId)
        assertFalse("a new activation is not acknowledged", fresh.items[r.id]!!.brainAcked)
        assertTrue(fresh.items[r.id]!!.needsBrainAck())
    }

    // ===================== items 20/35: nothing is lost =====================

    @Test fun a_job_missing_from_one_poll_never_deletes_an_accepted_opportunity() {
        // item 20: absence is not a server verdict. One failed or empty GET must not
        // throw away a tap; only a stated reason, or the request's own expiry, may.
        val r = request()
        var st = ProviderInbox.accept(
            ProviderInbox.offerFromBrain(ProviderInbox.State(), r, "act-1", now), r.id, now)
        assertFalse("an empty poll has nothing to reconcile", BrainAck.anyWork(st, emptyList()))
        assertNotNull("and the opportunity is still here", st.items[r.id])
        assertTrue(st.items[r.id]!!.accepted)
        // it leaves only when the buyer's own request runs out
        st = ProviderInbox.sweep(st, r.expiresAt + 1)
        assertNull(st.items[r.id])
    }

    @Test fun inbox_files_from_build_69_and_build_70_still_load() {
        // item 35. An upgrade that loses the inbox drops requests a provider has already
        // agreed to serve, which is a broken promise to a buyer who is waiting.
        val r = request()
        val nine = listOf(r.id, r.originShort, "BRAIN", zone, now, r.expiresAt, 0, 0, true)
            .joinToString("\t")
        val ten = nine + "\tact-1"
        val eleven = ten + "\ttrue"

        val b69 = ProviderInbox.decode("V\t1\nO\t" + nine + "\n").items[r.id]!!
        assertTrue(b69.accepted)
        assertEquals("", b69.brainActivationId)
        assertFalse("build 69 knew no activation, so nothing is acknowledged", b69.brainAcked)

        val b70 = ProviderInbox.decode("V\t1\nO\t" + ten + "\n").items[r.id]!!
        assertEquals("act-1", b70.brainActivationId)
        assertFalse("a missing eleventh field costs one idempotent retry, never the tap", b70.brainAcked)
        assertTrue(b70.needsBrainAck())

        val b71 = ProviderInbox.decode("V\t1\nO\t" + eleven + "\n").items[r.id]!!
        assertEquals("act-1", b71.brainActivationId)
        assertTrue(b71.brainAcked)
        assertFalse(b71.needsBrainAck())

        // and what build 71 writes is what build 71 reads
        val st = ProviderInbox.brainAcked(ProviderInbox.accept(
            ProviderInbox.offerFromBrain(ProviderInbox.State(), r, "act-1", now), r.id, now), r.id, true)
        val round = ProviderInbox.decode(ProviderInbox.encode(st)).items[r.id]!!
        assertEquals(st.items[r.id], round)
    }

    // ===================== the shared reason list =====================

    @Test fun the_terminal_reasons_are_the_ones_the_brain_actually_sends() {
        // v0.16.3 shipped a Kotlin/Python mismatch that neither green suite could see,
        // because two self-consistent implementations never meet. This list is one file,
        // read by this test and by server/tests/test_network_ack.py.
        var dir: File? = File(".").absoluteFile
        var f: File? = null
        while (dir != null && f == null) {
            val c = File(dir, "server/tests/fixtures/brain_answer_reasons.txt")
            if (c.isFile) f = c
            dir = dir.parentFile
        }
        assertNotNull("brain_answer_reasons.txt not found from " + File(".").absolutePath, f)
        val fromFile = f!!.readLines(Charsets.UTF_8)
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toSet()
        assertEquals(fromFile, BrainAnswer.TERMINAL_REASONS)
        assertTrue("the list must not be empty, or nothing would ever be final", fromFile.isNotEmpty())
    }
}
