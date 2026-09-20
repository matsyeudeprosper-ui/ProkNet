package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.13.3: the control plane must survive what the phones actually did.
 *
 *  - two different buyer requests, the second swallowed by a global cooldown;
 *  - a dismissed notification losing the demand entirely;
 *  - Bluetooth off and on leaving "server ready, adv on" while every customer
 *    got "peer has no ProkNet service";
 *  - the same request re-forwarded on every onPeers() tick.
 */
class ActivationReliabilityTest {
    private val now = 1_700_000_000_000L

    private class TestSigner : Signer {
        private val kp = Crypto.generateKeyPair()
        override val pubBytes: ByteArray = Crypto.publicBytes(kp.public)
        override val idBytes: ByteArray = Crypto.deriveId(pubBytes)
        override val displayName: String = "test"
        val short: String get() = idBytes.copyOfRange(0, 4).toHex()
        override fun sign(data: ByteArray): ByteArray = Crypto.sign(kp.private, data)
    }

    private val buyerA = TestSigner()
    private val buyerB = TestSigner()
    private fun req(s: TestSigner = buyerA, id: String = "aaaaaaaaaaaaaaaa", at: Long = now, zone: String = "z1:2") =
        NetRequest.sign(NetRequest.oneTap(id, s.short, s.pubBytes.toHex(), at, zone), s)

    // ================= 1. the inbox: one truth behind the alert and the card =================

    @Test
    fun one_request_makes_one_opportunity_one_alert_and_one_card() {
        val r = req()
        var st = ProviderInbox.offer(ProviderInbox.State(), r, ProviderInbox.Source.LOCAL, now)
        assertEquals(1, ProviderInbox.active(st, now).size)
        val a = ProviderInbox.alert(st, now)!!
        assertEquals("Quelqu'un cherche Internet à proximité.", a.title)
        assertEquals(1, a.count)
        st = ProviderInbox.noted(st, a.requestIds, now)
        // the card says the same thing, from the same state
        assertEquals("1 personne cherche Internet", ProviderInbox.cardTitle(st, now))
        assertTrue(ProviderInbox.cardSub(st, now).startsWith("À proximité · maintenant"))

        // duplicates and re-offers of the same generation change nothing and never re-alert
        st = ProviderInbox.offer(st, r, ProviderInbox.Source.LOCAL, now + 1_000)
        st = ProviderInbox.offer(st, r, ProviderInbox.Source.LOCAL, now + 2_000)
        assertEquals(1, ProviderInbox.active(st, now + 2_000).size)
        assertNull("a duplicate must not alert again", ProviderInbox.alert(st, now + 2_000))
        assertEquals(1, st.notifications)
    }

    @Test
    fun a_new_request_from_another_buyer_alerts_immediately_after_the_first_one_ended() {
        // the exact phone failure: request A alerted at 17:57:35, request B refused at 17:58:28
        // as "rate-limited" although it was a different, real request.
        val a = req(buyerA, "aaaaaaaaaaaaaaaa")
        var st = ProviderInbox.offer(ProviderInbox.State(), a, ProviderInbox.Source.LOCAL, now)
        st = ProviderInbox.noted(st, ProviderInbox.alert(st, now)!!.requestIds, now)

        // A is fulfilled and leaves
        st = ProviderInbox.remove(st, a.id)
        assertTrue(ProviderInbox.active(st, now + 10_000).isEmpty())

        // B arrives ten seconds later: it alerts at once, no cooldown anywhere
        val b = req(buyerB, "bbbbbbbbbbbbbbbb", at = now + 10_000)
        st = ProviderInbox.offer(st, b, ProviderInbox.Source.LOCAL, now + 10_000)
        val alert = ProviderInbox.alert(st, now + 10_000)
        assertNotNull("a different request must never be suppressed by a clock", alert)
        assertEquals(listOf(b.id), alert!!.requestIds)
    }

    @Test
    fun several_requests_are_aggregated_into_one_alert_not_five() {
        var st = ProviderInbox.State()
        st = ProviderInbox.offer(st, req(buyerA, "aaaaaaaaaaaaaaaa"), ProviderInbox.Source.LOCAL, now)
        st = ProviderInbox.offer(st, req(buyerB, "bbbbbbbbbbbbbbbb"), ProviderInbox.Source.LOCAL, now + 100)
        st = ProviderInbox.offer(st, req(buyerB, "cccccccccccccccc"), ProviderInbox.Source.LOCAL, now + 200)
        val a = ProviderInbox.alert(st, now + 300)!!
        assertEquals("3 personnes cherchent Internet à proximité.", a.title)
        assertEquals(3, a.count)
        assertEquals(3, a.requestIds.size)
        assertEquals("3 personnes cherchent Internet", ProviderInbox.cardTitle(st, now + 300))
        // one alert covered all three: nothing is left pending
        st = ProviderInbox.noted(st, a.requestIds, now + 300)
        assertNull(ProviderInbox.alert(st, now + 400))
        // a fourth one arriving later alerts once, for the whole set
        st = ProviderInbox.offer(st, req(buyerA, "dddddddddddddddd", at = now + 1000), ProviderInbox.Source.LOCAL, now + 1000)
        val a2 = ProviderInbox.alert(st, now + 1000)!!
        assertEquals(4, a2.count)
        assertEquals("only the new one is newly alerted", listOf("dddddddddddddddd"), a2.requestIds)
    }

    @Test
    fun a_request_from_the_brain_never_claims_to_be_nearby() {
        var st = ProviderInbox.offer(ProviderInbox.State(), req(), ProviderInbox.Source.BRAIN, now)
        assertEquals("Une demande Internet existe dans votre zone.", ProviderInbox.alert(st, now)!!.title)
        assertEquals("Une demande Internet dans votre zone", ProviderInbox.cardTitle(st, now))
        assertTrue(ProviderInbox.cardSub(st, now).startsWith("Dans votre zone"))
        assertFalse(ProviderInbox.anyLocal(st, now))
        // one local request among brain ones makes the whole set "à proximité", which is true
        st = ProviderInbox.offer(st, req(buyerB, "bbbbbbbbbbbbbbbb"), ProviderInbox.Source.LOCAL, now)
        assertTrue(ProviderInbox.title(2, ProviderInbox.anyLocal(st, now)).contains("à proximité"))
    }

    @Test
    fun ending_a_request_any_way_removes_the_card_and_the_alert() {
        val r = req()
        val fresh = ProviderInbox.offer(ProviderInbox.State(), r, ProviderInbox.Source.LOCAL, now)

        // cancelled or fulfilled: gone
        assertTrue(ProviderInbox.active(ProviderInbox.remove(fresh, r.id), now).isEmpty())
        // expired: gone by itself, no sweep needed for the card
        assertTrue(ProviderInbox.active(fresh, r.expiresAt).isEmpty())
        assertEquals("", ProviderInbox.cardTitle(fresh, r.expiresAt))
        assertNull(ProviderInbox.alert(fresh, r.expiresAt))
        // and the sweep really drops it
        assertTrue(ProviderInbox.sweep(fresh, r.expiresAt + 1).items.isEmpty())
        // accepted: it stays in the inbox but stops asking for attention
        val accepted = ProviderInbox.accept(fresh, r.id, now)
        assertNull(ProviderInbox.alert(accepted, now))
        assertEquals("", ProviderInbox.otherDemandLine(accepted, now))
    }

    @Test
    fun the_inbox_survives_the_process_and_does_not_realert_what_it_already_alerted() {
        val r = req()
        var st = ProviderInbox.offer(ProviderInbox.State(), r, ProviderInbox.Source.LOCAL, now)
        st = ProviderInbox.noted(st, listOf(r.id), now)
        st = ProviderInbox.offer(st, req(buyerB, "bbbbbbbbbbbbbbbb"), ProviderInbox.Source.BRAIN, now + 5_000)

        val back = ProviderInbox.decode(ProviderInbox.encode(st))
        assertEquals(st.items, back.items)
        assertEquals(2, ProviderInbox.active(back, now + 6_000).size)
        // the card returns after a restart; the one already alerted does not alert again
        assertEquals("2 personnes cherchent Internet", ProviderInbox.cardTitle(back, now + 6_000))
        assertEquals(listOf("bbbbbbbbbbbbbbbb"), ProviderInbox.alert(back, now + 6_000)!!.requestIds)
        // a damaged line loses only itself
        assertEquals(2, ProviderInbox.decode(ProviderInbox.encode(st) + "O\tbroken\n").items.size)
    }

    @Test
    fun a_missing_notification_permission_never_hides_the_demand() {
        var st = ProviderInbox.offer(ProviderInbox.State(), req(), ProviderInbox.Source.LOCAL, now)
        st = ProviderInbox.suppressed(st, "permission denied")
        assertEquals("the opportunity is still there", 1, ProviderInbox.active(st, now).size)
        assertEquals("1 personne cherche Internet", ProviderInbox.cardTitle(st, now))
        assertEquals("permission denied", st.lastSuppressed)
        assertNotNull("and it is still pending, so a later alert can still fire", ProviderInbox.alert(st, now))
    }

    @Test
    fun tapping_an_old_notification_after_the_request_ended_cannot_start_sharing() {
        val r = req()
        val ended = ProviderInbox.remove(ProviderInbox.offer(ProviderInbox.State(), r, ProviderInbox.Source.LOCAL, now), r.id)
        assertNull(ended.items[r.id])
        // the acceptance path re-checks the request, and a dead one is refused in words
        val tomb = NetRequest.tombstone(r, NetRequest.State.CANCELLED, now + 1_000, buyerA)
        val e = ProviderActivation.eligibility(true, Tunnel.UP_WIFI, true, true, true, false, false, 500)
        assertEquals(ProviderActivation.Refusal.REQUEST_NOT_OPEN, ProviderActivation.refusal(e, tomb, now + 2_000))
        assertEquals("Cette demande n'est plus active.", ProviderInbox.refusalSentence(ProviderActivation.Refusal.REQUEST_NOT_OPEN))
        // and every refusal has a sentence a normal person can act on
        for (x in ProviderActivation.Refusal.values()) assertTrue(x.name, ProviderInbox.refusalSentence(x)!!.isNotEmpty())
        assertNull(ProviderInbox.refusalSentence(null))
        assertEquals("Votre Internet n'est plus disponible.", ProviderInbox.refusalSentence(ProviderActivation.Refusal.NO_INTERNET))
        assertEquals("Activez le Bluetooth pour partager.", ProviderInbox.refusalSentence(ProviderActivation.Refusal.BLUETOOTH_OFF))
    }

    @Test
    fun three_full_cycles_in_a_row_are_never_blocked_by_anything_left_behind() {
        // the hardware regression: request, accept, share, fulfil, stop, and again, three times.
        var st = ProviderInbox.State()
        val ids = listOf("1111111111111111", "2222222222222222", "3333333333333333")
        var t = now
        for ((i, id) in ids.withIndex()) {
            val r = req(if (i % 2 == 0) buyerA else buyerB, id, at = t)
            st = ProviderInbox.offer(st, r, ProviderInbox.Source.LOCAL, t)
            val alert = ProviderInbox.alert(st, t)
            assertNotNull("cycle " + (i + 1) + " must alert", alert)
            assertEquals(listOf(id), alert!!.requestIds)
            st = ProviderInbox.noted(st, alert.requestIds, t)
            st = ProviderInbox.accept(st, id, t)               // PARTAGER
            assertEquals("", ProviderInbox.otherDemandLine(st, t))
            st = ProviderInbox.remove(st, id)                  // FULFILLED tombstone, seller stops
            assertTrue("cycle " + (i + 1) + " must leave nothing behind", ProviderInbox.active(st, t).isEmpty())
            t += 20_000                                        // the next request comes 20 s later
        }
        assertEquals(3, st.notifications)
    }

    // ================= 2. the BLE generation machine =================

    @Test
    fun a_healthy_stack_is_one_whose_service_advert_and_scan_share_the_current_generation() {
        var s = BleLifecycle.State(bluetoothOn = true)
        assertFalse(BleLifecycle.serviceReady(s))
        assertFalse("never advertise before the service exists", BleLifecycle.mayAdvertise(s))
        s = BleLifecycle.serverOpened(s, now)
        assertEquals(BleLifecycle.Phase.SERVICE_PENDING, s.phase)
        assertFalse(BleLifecycle.mayAdvertise(s))
        s = BleLifecycle.serviceAdded(s, s.generation, true, now)
        assertTrue(BleLifecycle.serviceReady(s))
        assertTrue(BleLifecycle.mayAdvertise(s))
        s = BleLifecycle.advertisingStarted(s, s.generation, true)
        s = BleLifecycle.scanStarted(s, s.generation, true)
        assertTrue(BleLifecycle.controlPlaneHealthy(s))
        assertNull(BleLifecycle.unhealthyReason(s))
    }

    @Test
    fun bluetooth_off_then_on_invalidates_the_whole_stack_not_just_the_scanner() {
        // build a healthy stack
        var s = BleLifecycle.State(bluetoothOn = true)
        s = BleLifecycle.serviceAdded(BleLifecycle.serverOpened(s, now), 1, true, now)
        s = BleLifecycle.scanStarted(BleLifecycle.advertisingStarted(s, 1, true), 1, true)
        assertTrue(BleLifecycle.controlPlaneHealthy(s))
        val gen = s.generation

        // the adapter goes away: nothing Android owned survives, whatever our objects say
        s = BleLifecycle.bluetoothOff(s)
        assertFalse(BleLifecycle.serviceReady(s)); assertFalse(BleLifecycle.advertising(s)); assertFalse(BleLifecycle.scanning(s))
        assertEquals("Bluetooth is off", BleLifecycle.unhealthyReason(s))

        // it comes back: a NEW generation, so the old server/service/advert cannot be mistaken for live
        s = BleLifecycle.bluetoothOn(s, now + 1_000)
        assertEquals(gen + 1, s.generation)
        assertFalse("the v0.13.2 bug: this used to stay true and skip the rebuild", BleLifecycle.serviceReady(s))
        assertFalse(BleLifecycle.mayAdvertise(s))
        assertEquals("Bluetooth returned", s.lastRebuildWhy)

        // and the rebuild is ordered: server, service, THEN advertising
        s = BleLifecycle.serverOpened(s, now + 1_100)
        assertFalse(BleLifecycle.mayAdvertise(s))
        s = BleLifecycle.serviceAdded(s, s.generation, true, now + 1_200)
        assertTrue(BleLifecycle.mayAdvertise(s))
        s = BleLifecycle.scanStarted(BleLifecycle.advertisingStarted(s, s.generation, true), s.generation, true)
        assertTrue(BleLifecycle.controlPlaneHealthy(s))
    }

    @Test
    fun a_callback_from_the_old_generation_can_never_touch_the_new_stack() {
        var s = BleLifecycle.State(bluetoothOn = true)
        s = BleLifecycle.serviceAdded(BleLifecycle.serverOpened(s, now), 1, true, now)
        s = BleLifecycle.rebuild(s, now + 500, "wedged radio")
        assertEquals(2, s.generation)
        assertTrue(BleLifecycle.isStale(s, 1))
        assertFalse(BleLifecycle.isStale(s, 2))
        // the old server's onServiceAdded finally answers: ignored
        assertEquals(s, BleLifecycle.serviceAdded(s, 1, true, now + 600))
        // an old advertiser reporting success: ignored
        assertFalse(BleLifecycle.advertising(BleLifecycle.advertisingStarted(s, 1, true)))
        assertFalse(BleLifecycle.scanning(BleLifecycle.scanStarted(s, 1, true)))
        // the current generation is accepted normally
        assertTrue(BleLifecycle.serviceReady(BleLifecycle.serviceAdded(s, 2, true, now + 700)))
    }

    @Test
    fun a_service_that_cannot_be_added_is_retried_bounded_and_never_called_healthy() {
        var s = BleLifecycle.State(bluetoothOn = true)
        s = BleLifecycle.serviceAdded(BleLifecycle.serverOpened(s, now), 1, false, now)
        assertEquals(BleLifecycle.Phase.FAILED, s.phase)
        assertFalse(BleLifecycle.serviceReady(s)); assertFalse(BleLifecycle.mayAdvertise(s))
        assertTrue(BleLifecycle.unhealthyReason(s)!!.contains("could not be added"))
        // the backoff grows and the attempts are bounded
        assertFalse(BleLifecycle.mayTryService(s, now))
        assertTrue(BleLifecycle.mayTryService(s, now + BleLifecycle.SERVICE_RETRY_MS[1]))
        var many = s.copy(serviceAttempts = BleLifecycle.SERVICE_MAX_ATTEMPTS)
        assertFalse("it stops instead of looping forever", BleLifecycle.mayTryService(many, now + 3_600_000))
        // a server that never answers is retried too
        val pending = BleLifecycle.serverOpened(BleLifecycle.State(bluetoothOn = true), now)
        assertFalse(BleLifecycle.mayTryService(pending, now + 1_000))
        assertTrue(BleLifecycle.mayTryService(pending, now + 11_000))
    }

    @Test
    fun advertising_without_the_service_is_reported_as_unhealthy() {
        // the exact phone state: our flags said adv on, Android had dropped the service
        var s = BleLifecycle.State(bluetoothOn = true, generation = 4, advertisingGeneration = 4, scanGeneration = 4, phase = BleLifecycle.Phase.DOWN)
        assertTrue(BleLifecycle.advertising(s))
        assertFalse(BleLifecycle.serviceReady(s))
        assertFalse(BleLifecycle.controlPlaneHealthy(s))
        assertEquals("CONTROL PLANE UNHEALTHY: advertising without ProkNet GATT service", BleLifecycle.unhealthyReason(s))
        // the diagnostic names each piece and its generation
        val d = BleLifecycle.describe(s)
        assertTrue(d.contains("generation: 4")); assertTrue(d.contains("ProkNet service: MISSING"))
    }

    // ================= 3. no retry storms =================

    @Test
    fun the_same_request_is_not_resent_to_the_same_peer_on_every_peers_tick() {
        val st0 = ControlRetry.State()
        assertTrue(ControlRetry.mayStart(st0, "r1", 1, "aabbccdd", now, bleGeneration = 1))
        var st = ControlRetry.started(st0, "r1", 1, "aabbccdd", now, 1)
        // onPeers() fires again and again while the send is in flight: nothing new is started
        for (t in listOf(10L, 100L, 500L, 1_000L, 5_000L)) assertFalse(ControlRetry.mayStart(st, "r1", 1, "aabbccdd", now + t, 1))
        assertEquals(1, ControlRetry.inFlight(st))
        // delivered: never sent again
        st = ControlRetry.delivered(st, "r1", 1, "aabbccdd", now + 200)
        assertFalse(ControlRetry.mayStart(st, "r1", 1, "aabbccdd", now + 60_000, 1))
        assertEquals(0, ControlRetry.inFlight(st))
        // another peer and another generation are their own business
        assertTrue(ControlRetry.mayStart(st, "r1", 1, "eeff0011", now, 1))
        assertTrue(ControlRetry.mayStart(st, "r1", 2, "aabbccdd", now, 1))
    }

    @Test
    fun a_failed_send_backs_off_and_stops_instead_of_hammering() {
        var st = ControlRetry.started(ControlRetry.State(), "r1", 1, "aabbccdd", now, 1)
        st = ControlRetry.failed(st, "r1", 1, "aabbccdd", now, "link closed")
        assertFalse(ControlRetry.mayStart(st, "r1", 1, "aabbccdd", now + 500, 1))
        assertTrue(ControlRetry.mayStart(st, "r1", 1, "aabbccdd", now + 1_000, 1))
        // the delays grow
        var t = now
        for (i in 1..4) { st = ControlRetry.started(st, "r1", 1, "aabbccdd", t, 1); st = ControlRetry.failed(st, "r1", 1, "aabbccdd", t, "boom") }
        val a = st.attempts[ControlRetry.key("r1", 1, "aabbccdd")]!!
        assertTrue(a.attempts >= 4)
        assertTrue(a.nextAt - t >= ControlRetry.DELAYS_MS.last())
        // and it gives up rather than loop forever
        var many = st
        repeat(ControlRetry.MAX_ATTEMPTS) { many = ControlRetry.failed(ControlRetry.started(many, "r1", 1, "aabbccdd", t, 1), "r1", 1, "aabbccdd", t, "boom") }
        assertEquals(ControlRetry.Phase.PARKED, many.attempts[ControlRetry.key("r1", 1, "aabbccdd")]!!.phase)
    }

    @Test
    fun a_peer_whose_gatt_service_is_missing_is_parked_not_hammered() {
        // the OnePlus log, over and over: "peer has no ProkNet service (services=2)"
        assertTrue(ControlRetry.isServiceMissing("peer has no ProkNet service (services=2)"))
        assertTrue(ControlRetry.isServiceMissing("service discovery failed status 129"))
        assertFalse(ControlRetry.isServiceMissing("prok-aabbccdd not in BLE range"))

        var st = ControlRetry.State()
        var t = now
        repeat(ControlRetry.SERVICE_MISSING_PARK_AFTER) {
            st = ControlRetry.started(st, "r1", 1, "aabbccdd", t, 1)
            st = ControlRetry.failed(st, "r1", 1, "aabbccdd", t, "peer has no ProkNet service (services=2)")
            t += 1_000
        }
        // the peer gets real quiet time to repair itself, and other requests do not hammer it either
        assertFalse(ControlRetry.mayStart(st, "r1", 1, "aabbccdd", t, 1))
        assertFalse(ControlRetry.mayStart(st, "r2", 1, "aabbccdd", t, 1))
        assertTrue("another peer is unaffected", ControlRetry.mayStart(st, "r1", 1, "eeff0011", t, 1))
        val note = ControlRetry.peerNote(st.peers["aabbccdd"], t)
        assertTrue(note, note.contains("advertises ProkNet but its GATT service is missing"))

        // our own radio being rebuilt is a real reason to try again at once
        val after = ControlRetry.onBleGenerationChanged(st, 2)
        assertTrue(ControlRetry.mayStart(after, "r1", 1, "aabbccdd", t, 2))
        // so is the peer reappearing (a new BLE address, the same ProkNet identity)
        assertTrue(ControlRetry.mayStart(ControlRetry.onPeerReappeared(st, "aabbccdd"), "r1", 1, "aabbccdd", t, 1))
    }

    @Test
    fun retry_state_follows_the_prok_identity_and_a_delivery_clears_the_record() {
        // addresses rotate; nothing here is keyed by one
        var st = ControlRetry.failed(ControlRetry.started(ControlRetry.State(), "r1", 1, "aabbccdd", now, 1), "r1", 1, "aabbccdd", now, "peer has no ProkNet service")
        assertTrue(ControlRetry.key("r1", 1, "aabbccdd").endsWith("aabbccdd"))
        assertEquals(1, st.peers["aabbccdd"]!!.serviceMissing)
        // the same phone under a new address is the same short id, and a success wipes the slate
        st = ControlRetry.delivered(st, "r1", 1, "aabbccdd", now + 60_000)
        assertEquals(0, st.peers["aabbccdd"]!!.serviceMissing)
        assertEquals("", ControlRetry.peerNote(st.peers["aabbccdd"], now + 60_000))
        // finished requests are forgotten so the map cannot grow forever
        assertTrue("nothing live: nothing kept", ControlRetry.keepOnly(st, emptySet()).attempts.isEmpty())
        assertEquals("r1 still live: its record stays", 1, ControlRetry.keepOnly(st, setOf("r1")).attempts.size)
    }
}
