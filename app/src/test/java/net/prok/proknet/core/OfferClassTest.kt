package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.17.4: being unable to charge is not an offer to give anything away.
 *
 * THE BUG THIS FILE EXISTS FOR. Build 71's presence did:
 *
 *     commercialReady = willing && mayOfferPaidSharing
 *     freeReady       = willing && !mayOfferPaidSharing
 *     offerClass      = if (mayOfferPaidSharing) COMMERCIAL else FREE
 *
 * `mayOfferPaidSharing` is the v0.16 paid-seller safety check: a valid payment
 * destination, payment verification readiness, the seller not blocked. When it is false
 * the honest statement is "this seller cannot take money right now". Build 71 wrote down
 * "this seller is giving their Internet away".
 *
 * So a seller who had opted in to earn money, had working Internet, and had simply not
 * finished setting up their Mobile Money number was advertised to the entire zone as a
 * FREE provider. That breaks the locked product rule - FREE must be explicit - and it
 * creates a mismatch the rest of the system cannot absorb: the Brain matches a buyer who
 * asked for free help, the provider taps PARTAGER, and `Pricing` - which has never
 * looked at `mayOfferPaidSharing` and never will - quotes a paid rate for that session.
 * The buyer was promised one contract and offered another.
 *
 * The rule now: intent and readiness are separate. Intent says what is on offer and
 * comes from the SOURCE, through the same [Pricing.isFree] test the pricing engine uses
 * to decide the buyer pays nothing. Readiness says whether that offer can be completed.
 * An unready commercial seller is simply unavailable - both flags false - and is matched
 * for nothing at all, which is correct. It is never relabelled.
 */
class OfferClassTest {

    private val zone = "z1:1"

    /** A normal commercial seller: mobile data the seller paid for. */
    private val paidSource = Pricing.Source(Pricing.SourceKind.MOBILE_DATA, 100)

    /** Home Wi-Fi with no declared cost. Still not a gift - nobody offered it. */
    private val homeWifi = Pricing.Source(Pricing.SourceKind.AUTHORIZED_HOME_WIFI, 0)

    /** The two things that genuinely mean "free". */
    private val freePublic = Pricing.Source(Pricing.SourceKind.FREE_PUBLIC)
    private val declaredFree = Pricing.Source(Pricing.SourceKind.AUTHORIZED_HOME_WIFI, 0, free = true)

    private fun willingProvider() = ProviderActivation.eligibility(
        optIn = true, upstreamType = Tunnel.UP_WIFI, upstreamValidated = true,
        bulkSupported = true, bluetoothOn = true,
        alreadySharing = false, busy = false, sellPriceCentimesPerMb = 500)

    private fun presence(paidReady: Boolean, source: Pricing.Source) =
        ProviderPresence.of(zone, willingProvider(), currentlySharing = false,
            activeSessions = 0, mayOfferPaidSharing = paidReady,
            intent = ProviderPresence.intentOf(source))!!

    // ================= case 1: unready must not become free =================

    @Test fun a_seller_that_cannot_charge_is_unavailable_and_not_a_gift() {
        val p = presence(paidReady = false, source = paidSource)
        assertTrue("it is still willing and still visible", p.willing)
        assertFalse("but it cannot complete a paid session", p.commercialReady)
        assertFalse("and it never volunteered anything", p.freeReady)
        assertNotEquals("and its class is not rewritten", ProviderPresence.FREE, p.offerClass)
        assertEquals(ProviderPresence.COMMERCIAL, p.offerClass)

        // build 71's own expression, recomputed beside the new one, so a later
        // simplification back to it fails here rather than in Congo
        val mayOfferPaidSharing = false
        val build71FreeReady = p.willing && !mayOfferPaidSharing
        val build71OfferClass = if (mayOfferPaidSharing) ProviderPresence.COMMERCIAL else ProviderPresence.FREE
        assertTrue("build 71 called this phone FREE", build71FreeReady)
        assertEquals(ProviderPresence.FREE, build71OfferClass)
        assertFalse("build 72 does not", p.freeReady)
        assertNotEquals(build71OfferClass, p.offerClass)
    }

    @Test fun no_ordinary_source_is_ever_read_as_free() {
        // the conservative production answer: nothing a phone can be today is a gift
        for (s in listOf(paidSource, homeWifi,
                Pricing.Source(Pricing.SourceKind.UNKNOWN),
                Pricing.Source(Pricing.SourceKind.AUTHORIZED_SHOP_WIFI, 0))) {
            assertEquals(s.kind.name, ProviderPresence.Intent.COMMERCIAL, ProviderPresence.intentOf(s))
            assertFalse(s.kind.name, presence(paidReady = false, source = s).freeReady)
            assertFalse(s.kind.name, presence(paidReady = true, source = s).freeReady)
        }
    }

    // ================= cases 4 and 5: the honest classes =================

    @Test fun only_an_explicitly_free_source_offers_free() {
        for (s in listOf(freePublic, declaredFree)) {
            val p = presence(paidReady = false, source = s)
            assertTrue(s.kind.name, p.freeReady)
            assertFalse(s.kind.name, p.commercialReady)
            assertEquals(s.kind.name, ProviderPresence.FREE, p.offerClass)
            // and the hint matches what Pricing would really quote for that source
            assertEquals(0, p.priceHintInternal)
            assertEquals(0, Pricing.autoRate(s, Pricing.SellerPolicy.BALANCED))
        }
    }

    @Test fun a_paid_ready_commercial_seller_is_commercial_and_only_commercial() {
        val p = presence(paidReady = true, source = paidSource)
        assertTrue(p.commercialReady)
        assertFalse(p.freeReady)
        assertEquals(ProviderPresence.COMMERCIAL, p.offerClass)
        assertEquals("the price hint is the seller's real rate", 500, p.priceHintInternal)
    }

    @Test fun an_explicitly_free_provider_stays_free_even_when_it_could_charge() {
        // "FREE must still be explicit, never inferred from payment failure" - and the
        // converse: being ABLE to charge does not withdraw an offer already made.
        val p = presence(paidReady = true, source = freePublic)
        assertTrue(p.freeReady)
        assertFalse("the explicit offer wins", p.commercialReady)
        assertEquals(ProviderPresence.FREE, p.offerClass)
        assertEquals(0, p.priceHintInternal)
    }

    // ================= the class always matches the real contract =================

    @Test fun the_advertised_class_always_matches_what_pricing_would_charge() {
        // the definition of done, as one assertion over every combination
        for (s in listOf(paidSource, homeWifi, freePublic, declaredFree))
            for (paidReady in listOf(true, false)) {
                val p = presence(paidReady, s)
                val sessionWouldBeFree = Pricing.autoRate(s, Pricing.SellerPolicy.BALANCED) == 0 &&
                    Pricing.isFree(s)
                val brainSaysFree = p.offerClass == ProviderPresence.FREE
                assertEquals(s.kind.name + " paidReady=" + paidReady,
                    sessionWouldBeFree, brainSaysFree)
                assertEquals(s.kind.name + " paidReady=" + paidReady,
                    sessionWouldBeFree, p.freeReady)
            }
    }

    @Test fun a_free_source_really_does_charge_nothing_in_the_engine() {
        // the other half of the pairing: the presence claim is only worth something if
        // the engine agrees, so assert the engine directly rather than trusting the flag
        val q = Pricing.quote(5_000L, freePublic, Pricing.SellerPolicy.BALANCED)
        assertTrue(q.admissible)
        assertTrue("a free source charges nothing", q.free)
        assertEquals(0, q.rateCentimesPerMb)
    }

    @Test fun intent_is_the_pricing_engines_own_test_and_not_a_second_opinion() {
        for (s in listOf(paidSource, homeWifi, freePublic, declaredFree,
                Pricing.Source(Pricing.SourceKind.SPONSORED)))
            assertEquals(s.kind.name + " free=" + s.free,
                Pricing.isFree(s), ProviderPresence.intentOf(s) == ProviderPresence.Intent.FREE)
    }

    // ================= willing is untouched =================

    @Test fun v0173_willingness_is_unchanged_by_any_of_this() {
        // payment readiness and offer class must not leak back into discoverability: an
        // idle opted-in provider stays visible whatever it can or cannot charge.
        for (paidReady in listOf(true, false))
            for (s in listOf(paidSource, freePublic)) {
                val p = presence(paidReady, s)
                assertTrue(p.willing)
                assertTrue(p.shouldPublish)
                assertTrue(p.availableForActivation)
                assertEquals(0, p.currentLoad)
                assertEquals(1, p.maxBuyers)
            }
    }
}
