package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.17.11: the pilot Brain as a default, and the line that must not move.
 *
 * Clearing an app's storage wipes the Brain address, so a phone that has just been reset
 * is silently off the network until somebody types a URL from memory into a diagnostic
 * screen. That happened four times in two days of testing, and nobody in Congo would ever
 * do it.
 */
class BrainEndpointTest {

    // ================= the default =================

    @Test fun a_fresh_phone_is_on_the_network_without_anybody_typing_anything() {
        assertTrue(BrainEndpoint.configured(BrainEndpoint.PILOT))
        assertEquals(BrainEndpoint.PILOT, BrainEndpoint.normalise(BrainEndpoint.PILOT))
    }

    @Test fun the_pilot_address_is_https_and_stays_https() {
        // the standing rule was never to hard-code a temporary INSECURE public address to
        // paper over missing TLS. This is the opposite, and this test holds that line.
        assertTrue(BrainEndpoint.PILOT.startsWith("https://"))
        assertTrue(BrainEndpoint.secure(BrainEndpoint.PILOT))
        assertNull(BrainEndpoint.refusal(BrainEndpoint.PILOT))
        assertFalse("no trailing slash, or every signed path would differ",
            BrainEndpoint.PILOT.endsWith("/"))
    }

    // ================= off is a real choice =================

    @Test fun an_empty_address_means_off_and_must_survive() {
        // TESTING 75g and 76 switch the Brain off and check the local flow is untouched.
        // A default that helpfully replaced an empty address would make that untestable.
        assertFalse(BrainEndpoint.configured(BrainEndpoint.OFF))
        assertFalse(BrainEndpoint.configured("   "))
        assertEquals("", BrainEndpoint.normalise("   "))
        assertTrue("off is not insecure", BrainEndpoint.secure(BrainEndpoint.OFF))
        assertNull(BrainEndpoint.refusal(BrainEndpoint.OFF))
    }

    // ================= normalising =================

    @Test fun one_stray_slash_would_break_every_signature() {
        // the signed request covers the canonical target, so the base must not carry a
        // trailing slash into it
        assertEquals("https://x.org", BrainEndpoint.normalise("https://x.org/"))
        assertEquals("https://x.org", BrainEndpoint.normalise("  https://x.org//  "))
        assertEquals("https://x.org/base", BrainEndpoint.normalise("https://x.org/base/"))
    }

    // ================= what may be spoken to =================

    @Test fun a_public_plain_http_address_is_refused() {
        assertFalse(BrainEndpoint.secure("http://proknet.duckdns.org"))
        assertFalse(BrainEndpoint.secure("http://203.0.113.9:8080"))
        val why = BrainEndpoint.refusal("http://proknet.duckdns.org")
        assertNotNull(why)
        assertTrue(why!!.contains("https"))
    }

    @Test fun a_brain_on_your_own_machine_or_network_is_allowed() {
        // somebody testing against a Brain on their own laptop must not be blocked
        for (u in listOf("http://localhost:8081", "http://127.0.0.1:8081",
                "http://192.168.1.13:8081", "http://10.0.0.5:8081", "http://172.16.4.2:8081"))
            assertTrue(u, BrainEndpoint.secure(u))
        // and 172.32 is NOT private
        assertFalse(BrainEndpoint.secure("http://172.32.0.1:8081"))
    }

    @Test fun anything_that_is_not_an_address_we_speak_is_refused() {
        for (u in listOf("ftp://x.org", "proknet.duckdns.org", "ws://x.org", "javascript:x")) {
            assertFalse(u, BrainEndpoint.secure(u))
            assertNotNull(u, BrainEndpoint.refusal(u))
        }
    }

    @Test fun every_refusal_tells_the_user_what_to_do() {
        for (u in listOf("http://x.org", "ftp://x.org", "nonsense")) {
            val why = BrainEndpoint.refusal(u)
            assertNotNull(u, why)
            assertTrue(u + ": " + why, why!!.length > 20)
        }
    }
}
