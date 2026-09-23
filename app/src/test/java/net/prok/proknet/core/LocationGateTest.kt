package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.17.5: the defect hardware found on 2026-09-23.
 *
 * The pilot OUKITEL was healthy in every way that mattered - Brain reachable, signed
 * requests succeeding, notifications on, Internet validated, Bluetooth on, a working
 * local BLE session with the other phone - and the Network Brain saw nothing from it at
 * all. Its diagnostic said:
 *
 *     zone: z? (no location permission)
 *
 * Every Brain call is gated on the zone, and each one returns early and silently without
 * it. The provider was told nothing. The buyer was told "Aucun Internet disponible tout
 * de suite", which describes the wrong problem entirely.
 *
 * And the app only asked for the permission on the MAP tab, so a user who never opened
 * the map was never asked once.
 */
class LocationGateTest {

    // ================= the four states =================

    @Test fun a_phone_that_can_see_its_zone_is_not_nagged() {
        assertEquals(LocationGate.Need.NONE,
            LocationGate.need(hasPermission = true, locationServicesOn = true, canAskInApp = true))
        assertFalse(LocationGate.blocked(LocationGate.Need.NONE))
        assertEquals("", LocationGate.note(LocationGate.Need.NONE))
        assertEquals("", LocationGate.title(LocationGate.Need.NONE))
        assertEquals("", LocationGate.button(LocationGate.Need.NONE))
    }

    @Test fun the_normal_case_is_one_tap_yes_or_no() {
        // nothing granted yet and Android will still show its dialog
        val n = LocationGate.need(hasPermission = false, locationServicesOn = true, canAskInApp = true)
        assertEquals(LocationGate.Need.ASK_PERMISSION, n)
        assertTrue(LocationGate.blocked(n))
        assertEquals("Autoriser", LocationGate.button(n))
        // and nobody is told to go hunting
        assertFalse(LocationGate.message(n).contains("Paramètres"))
        assertFalse(LocationGate.message(n).contains("Réglages"))
    }

    @Test fun a_used_up_dialog_opens_the_right_screen_instead_of_giving_up() {
        val n = LocationGate.need(hasPermission = false, locationServicesOn = true, canAskInApp = false)
        assertEquals(LocationGate.Need.OPEN_SETTINGS, n)
        assertEquals("Ouvrir", LocationGate.button(n))
        assertTrue("it must say what to tap once there", LocationGate.message(n).contains("Position"))
    }

    @Test fun a_granted_permission_with_the_switch_off_is_its_own_case() {
        // asking for a permission the user already granted would be nonsense, and the fix
        // is a different screen
        val n = LocationGate.need(hasPermission = true, locationServicesOn = false, canAskInApp = true)
        assertEquals(LocationGate.Need.TURN_ON_LOCATION, n)
        assertNotEquals(LocationGate.Need.ASK_PERMISSION, n)
        assertEquals("Ouvrir", LocationGate.button(n))
        assertEquals("Activez la localisation", LocationGate.title(n))
    }

    @Test fun the_switch_being_off_wins_over_whether_we_may_still_ask() {
        // canAskInApp is irrelevant once the permission is granted
        for (canAsk in listOf(true, false))
            assertEquals(LocationGate.Need.TURN_ON_LOCATION,
                LocationGate.need(hasPermission = true, locationServicesOn = false, canAskInApp = canAsk))
    }

    // ================= it must never be silent =================

    @Test fun every_blocked_state_says_something_the_user_can_act_on() {
        for (n in LocationGate.Need.values()) {
            if (n == LocationGate.Need.NONE) continue
            assertTrue(n.name + " needs a title", LocationGate.title(n).isNotEmpty())
            assertTrue(n.name + " needs a message", LocationGate.message(n).length > 40)
            assertTrue(n.name + " needs a button", LocationGate.button(n).isNotEmpty())
            assertTrue(n.name + " needs a note", LocationGate.note(n).isNotEmpty())
            assertTrue(n.name + " needs a diagnostic line", LocationGate.diag(n).isNotEmpty())
        }
    }

    @Test fun the_note_explains_the_consequence_and_not_just_the_setting() {
        // "Zone inconnue" alone would tell somebody nothing. The point is that ProkNet
        // cannot see anybody - which is the thing they are actually experiencing.
        for (n in listOf(LocationGate.Need.ASK_PERMISSION, LocationGate.Need.OPEN_SETTINGS,
                LocationGate.Need.TURN_ON_LOCATION)) {
            val note = LocationGate.note(n)
            assertTrue(n.name + ": " + note,
                note.contains("ne voit personne") || note.contains("trouver Internet"))
        }
    }

    @Test fun the_privacy_promise_is_repeated_where_the_permission_is_asked_for() {
        // a permission granted without understanding is not consent, and this is the only
        // screen where most users will ever read what ProkNet does with a position
        val m = LocationGate.message(LocationGate.Need.ASK_PERMISSION)
        assertTrue(m.contains("500 m"))
        assertTrue(m.contains("Jamais votre position exacte"))
        assertTrue(m.contains("Jamais l'historique"))
    }

    // ================= the regression this file exists for =================

    @Test fun the_pilot_phone_state_of_2026_09_23_is_now_reported() {
        // exactly what the OUKITEL diagnostic showed: no permission, everything else fine.
        // Build 72 said nothing at all here, on any screen.
        val n = LocationGate.need(hasPermission = false, locationServicesOn = true, canAskInApp = true)
        assertTrue("build 72 was silent; build 73 must not be", LocationGate.blocked(n))
        assertTrue(LocationGate.note(n).isNotEmpty())

        // and the state it leads to once the user taps Autoriser
        assertEquals(LocationGate.Need.NONE,
            LocationGate.need(hasPermission = true, locationServicesOn = true, canAskInApp = false))
    }

    @Test fun a_zone_is_what_the_whole_brain_layer_waits_on() {
        // the reason this gate matters at all, pinned against the real gatekeeper: with
        // NO_ZONE there is no presence, so no heartbeat, no job poll and no matching.
        val e = ProviderActivation.eligibility(
            optIn = true, upstreamType = Tunnel.UP_WIFI, upstreamValidated = true,
            bulkSupported = true, bluetoothOn = true,
            alreadySharing = false, busy = false, sellPriceCentimesPerMb = 500)
        assertNull("no zone, no presence",
            ProviderPresence.of(CoverageModel.NO_ZONE, e, false, 0, true,
                ProviderPresence.Intent.COMMERCIAL))
        // and with a real zone the very same phone is available
        val p = ProviderPresence.of("z2431:337", e, false, 0, true,
            ProviderPresence.Intent.COMMERCIAL)
        assertNotNull(p)
        assertTrue(p!!.shouldPublish)
    }
}
