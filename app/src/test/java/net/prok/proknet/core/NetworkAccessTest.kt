package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.17.0: what the buyer is told, and what nothing is allowed to claim.
 *
 * The whole milestone turns on one distinction. The Brain can say a provider exists in
 * this neighbourhood and that it agreed to help. It cannot say Bluetooth reached - only
 * the transport knows that. A buyer told "Internet disponible" who then cannot load a page
 * has been lied to, and will not press the button again.
 *
 * So these tests are mostly about the gap between those two facts staying visible.
 */
class NetworkAccessTest {

    private val now = 1_700_000_000_000L
    private val green = Coverage.ZoneStatus.GREEN
    private val yellow = Coverage.ZoneStatus.YELLOW
    private val red = Coverage.ZoneStatus.RED

    // ================= nothing promises Internet too early =================

    @Test fun only_the_transport_can_produce_connected() {
        // every state the Brain can put us in, and none of them is CONNECTED
        var s = NetworkAccess.idle(green)
        for (status in listOf("CREATED", "SEARCHING", "PROVIDER_FOUND", "ACTIVATION_SENT",
                "PROVIDER_ACCEPTED", "WAITING_FOR_LOCAL_LINK")) {
            s = NetworkAccess.onDemandStatus(s, status, "dem-1", now)
            assertTrue("the Brain said " + status + " and we claimed to be connected",
                s.state != NetworkAccess.State.CONNECTED)
        }
        // and then the radio says so
        s = NetworkAccess.onLink(s, NetworkAccess.LinkEvent.INTERNET_UP, now)
        assertEquals(NetworkAccess.State.CONNECTED, s.state)
    }

    @Test fun the_brain_reporting_connected_is_not_this_phone_being_connected() {
        // somebody told the Brain a connection happened. That is not evidence about us.
        val s = NetworkAccess.onDemandStatus(
            NetworkAccess.searching("dem-1", now, yellow), "CONNECTED", "dem-1", now)
        assertEquals(NetworkAccess.State.CONNECTING, s.state)
        assertTrue(NetworkAccess.title(s) != "Connecté")
    }

    @Test fun a_provider_accepting_says_prepares_not_available() {
        val s = NetworkAccess.onDemandStatus(
            NetworkAccess.searching("dem-1", now, yellow), "PROVIDER_ACCEPTED", "dem-1", now)
        assertEquals("Un fournisseur se prépare", NetworkAccess.title(s))
        assertTrue("accepting is not arriving",
            !NetworkAccess.title(s).contains("disponible"))
    }

    @Test fun no_waiting_state_ever_says_internet_is_available() {
        for (st in listOf(NetworkAccess.State.SEARCHING, NetworkAccess.State.PROVIDER_FOUND,
                NetworkAccess.State.PROVIDER_ACCEPTED, NetworkAccess.State.CONNECTING)) {
            val line = NetworkAccess.title(NetworkAccess.Snapshot(st, "dem-1", 0, green))
            assertTrue(st.toString() + " said: " + line, !line.contains("disponible maintenant"))
        }
    }

    @Test fun a_peer_appearing_is_not_a_connection() {
        val s = NetworkAccess.onLink(
            NetworkAccess.searching("dem-1", now, yellow),
            NetworkAccess.LinkEvent.PEER_SEEN, now)
        assertEquals(NetworkAccess.State.CONNECTING, s.state)
        assertEquals("Connexion en cours…", NetworkAccess.title(s))
    }

    @Test fun a_peer_appearing_does_not_undo_a_working_connection() {
        val up = NetworkAccess.onLink(NetworkAccess.idle(green),
            NetworkAccess.LinkEvent.INTERNET_UP, now)
        val after = NetworkAccess.onLink(up, NetworkAccess.LinkEvent.PEER_SEEN, now)
        assertEquals(NetworkAccess.State.CONNECTED, after.state)
    }

    // ================= the local path comes first =================

    @Test fun a_usable_local_source_needs_no_server_and_says_so_plainly() {
        val s = NetworkAccess.localAvailable(NetworkAccess.idle(red))
        assertEquals(NetworkAccess.State.LOCAL_AVAILABLE, s.state)
        assertEquals("Internet disponible maintenant", NetworkAccess.title(s))
        assertEquals("no demand is needed when somebody is already here", "", s.demandId)
    }

    @Test fun the_brain_being_away_changes_nothing_about_the_local_path() {
        val local = NetworkAccess.withBrainOffline(
            NetworkAccess.localAvailable(NetworkAccess.idle(red)), true)
        assertEquals(NetworkAccess.State.LOCAL_AVAILABLE, local.state)
        assertEquals("Internet disponible maintenant", NetworkAccess.title(local))
    }

    @Test fun a_search_with_no_brain_still_says_something_honest() {
        val s = NetworkAccess.withBrainOffline(
            NetworkAccess.searching("dem-1", now, red), true)
        assertEquals("ProkNet continue de chercher autour de vous.", NetworkAccess.hint(s))
    }

    // ================= failure does not strand the buyer =================

    @Test fun a_failed_link_keeps_looking_while_the_request_is_alive() {
        val s = NetworkAccess.onLink(
            NetworkAccess.searching("dem-1", now, yellow),
            NetworkAccess.LinkEvent.LINK_FAILED, now)
        assertEquals("another provider may still work",
            NetworkAccess.State.SEARCHING, s.state)
        assertEquals("dem-1", s.demandId)
    }

    @Test fun a_failed_link_with_no_request_is_simply_a_failure() {
        val s = NetworkAccess.onLink(NetworkAccess.idle(red),
            NetworkAccess.LinkEvent.LINK_FAILED, now)
        assertEquals(NetworkAccess.State.FAILED, s.state)
        assertEquals("Personne ne peut partager pour le moment", NetworkAccess.title(s))
        assertEquals("Réessayez dans un moment.", NetworkAccess.hint(s))
    }

    @Test fun an_expired_request_clears_itself_so_nothing_resurrects_it() {
        for (status in listOf("EXPIRED", "FAILED")) {
            val s = NetworkAccess.onDemandStatus(
                NetworkAccess.searching("dem-1", now, yellow), status, "dem-1", now)
            assertEquals(NetworkAccess.State.FAILED, s.state)
            assertEquals("a dead request must not come back on its own", "", s.demandId)
        }
    }

    @Test fun a_cancelled_request_returns_to_idle() {
        val s = NetworkAccess.onDemandStatus(
            NetworkAccess.searching("dem-1", now, yellow), "CANCELLED", "dem-1", now)
        assertEquals(NetworkAccess.State.IDLE, s.state)
        assertEquals("", s.demandId)
    }

    @Test fun an_unknown_status_leaves_the_model_alone() {
        // a newer Brain must not be able to put an older phone into a state it has no
        // wording for
        val before = NetworkAccess.searching("dem-1", now, yellow)
        val after = NetworkAccess.onDemandStatus(before, "TELEPORTING", "dem-1", now)
        assertEquals(before.state, after.state)
        assertEquals(before.demandId, after.demandId)
    }

    // ================= the wording itself =================

    @Test fun a_long_search_softens_instead_of_repeating() {
        val short = NetworkAccess.Snapshot(NetworkAccess.State.SEARCHING, "dem-1", 5_000, yellow)
        val long = NetworkAccess.Snapshot(NetworkAccess.State.SEARCHING, "dem-1",
            NetworkAccess.LONG_SEARCH_MS, yellow)
        assertEquals("Recherche d'Internet…", NetworkAccess.title(short))
        assertEquals("Recherche toujours en cours…", NetworkAccess.title(long))
        assertEquals("Cela peut prendre quelques minutes.", NetworkAccess.hint(long))
    }

    @Test fun every_state_has_a_short_line_in_ordinary_french() {
        for (st in NetworkAccess.State.values()) {
            val line = NetworkAccess.title(NetworkAccess.Snapshot(st, "", 0, yellow))
            assertTrue(st.toString() + " has no line", line.isNotEmpty())
            assertTrue(st.toString() + " is too long: " + line, line.length <= 42)
            assertTrue(st.toString() + " is two sentences: " + line,
                line.trimEnd('.', '…').count { it == '.' } == 0)
        }
    }

    @Test fun nothing_the_user_sees_carries_a_protocol_word() {
        val lines = ArrayList<String>()
        for (st in NetworkAccess.State.values()) {
            val s = NetworkAccess.Snapshot(st, "dem-1", 0, yellow)
            lines.add(NetworkAccess.title(s)); lines.add(NetworkAccess.hint(s))
        }
        for (z in Coverage.ZoneStatus.values()) lines.add(NetworkAccess.zoneLabel(z))
        lines.add(NetworkAccess.NOTIFICATION_TITLE)
        for (kind in listOf("demand.created", "demand.provider_accepted", "demand.connected"))
            lines.add(NetworkAccess.eventLine(kind))
        for (line in lines) {
            for (word in listOf("HTTP", "L2CAP", "nonce", "GATT", "ACTIVATION_SENT",
                    "demandId", "activation", "BLE", "MB", "CFA/", "null")) {
                assertTrue("a user-visible line said '" + word + "': " + line,
                    !line.contains(word))
            }
        }
    }

    @Test fun no_screen_is_ever_given_a_provider_identity() {
        val s = NetworkAccess.Snapshot(NetworkAccess.State.PROVIDER_ACCEPTED, "dem-1", 0, green)
        // the model simply has no field for one, which is the point
        assertTrue(NetworkAccess.title(s).none { it.isDigit() })
        assertEquals("Un fournisseur se prépare", NetworkAccess.title(s))
    }

    // ================= the zone colour =================

    @Test fun green_needs_both_a_provider_and_a_recent_success() {
        assertEquals(green, NetworkAccess.zoneStatus(2, true))
        assertEquals("a candidate alone is not a promise",
            yellow, NetworkAccess.zoneStatus(2, false))
        assertEquals(red, NetworkAccess.zoneStatus(0, true))
        assertEquals(red, NetworkAccess.zoneStatus(0, false))
    }

    @Test fun the_zone_labels_are_hints_not_guarantees() {
        // v0.17.2: GREEN says somebody is ACTIVE here, never that Internet is waiting
        assertEquals("Fournisseur actif dans cette zone", NetworkAccess.zoneLabel(green))
        assertEquals("ProkNet peut chercher un fournisseur", NetworkAccess.zoneLabel(yellow))
        assertEquals("Pas de fournisseur connu actuellement", NetworkAccess.zoneLabel(red))
        assertTrue("nothing may say guaranteed",
            Coverage.ZoneStatus.values().none { NetworkAccess.zoneLabel(it).contains("garanti") })
    }

    @Test fun an_idle_phone_describes_its_neighbourhood_rather_than_nothing() {
        assertEquals("Un fournisseur est actif dans votre zone",
            NetworkAccess.title(NetworkAccess.idle(green)))
        assertEquals("ProkNet peut chercher un fournisseur",
            NetworkAccess.title(NetworkAccess.idle(yellow)))
        assertEquals("Appuyez pour chercher.", NetworkAccess.hint(NetworkAccess.idle(yellow)))
        assertEquals("Appuyez pour vous connecter.", NetworkAccess.hint(NetworkAccess.idle(green)))
        assertEquals("Pas de fournisseur connu actuellement",
            NetworkAccess.title(NetworkAccess.idle(red)))
    }

    // ================= the provider side =================

    @Test fun the_provider_hero_matches_the_v0_13_3_voice() {
        assertEquals("DEMANDE PROCHE", NetworkAccess.providerTitle(true, false))
        assertEquals("Quelqu'un cherche Internet près de vous.",
            NetworkAccess.providerText(true, false))
        assertEquals("PRÊT À PARTAGER", NetworkAccess.providerTitle(true, true))
        assertEquals("ProkNet cherche l'appareil à connecter.",
            NetworkAccess.providerText(true, true))
        assertEquals("", NetworkAccess.providerTitle(false, false))
    }

    @Test fun the_notification_is_one_sentence_and_one_action() {
        assertEquals("Quelqu'un près de vous cherche Internet", NetworkAccess.NOTIFICATION_TITLE)
        assertEquals("PARTAGER", NetworkAccess.NOTIFICATION_ACTION)
    }

    // ================= what the transport reports =================

    @Test fun each_local_event_maps_to_one_server_word() {
        assertEquals("LOCAL_LINK_SEEN", NetworkAccess.reportFor(NetworkAccess.LinkEvent.PEER_SEEN))
        assertEquals("CONNECTED", NetworkAccess.reportFor(NetworkAccess.LinkEvent.INTERNET_UP))
        assertEquals("FAILED", NetworkAccess.reportFor(NetworkAccess.LinkEvent.LINK_FAILED))
    }

    // ================= Activité =================

    @Test fun the_history_reads_like_a_person_wrote_it() {
        assertEquals("Recherche Internet", NetworkAccess.eventLine("demand.created"))
        assertEquals("Fournisseur trouvé", NetworkAccess.eventLine("demand.provider_found"))
        assertEquals("Partage accepté", NetworkAccess.eventLine("demand.provider_accepted"))
        assertEquals("Connexion réussie", NetworkAccess.eventLine("demand.connected"))
        assertEquals("Demande annulée", NetworkAccess.eventLine("demand.cancelled"))
        assertEquals("Demande expirée", NetworkAccess.eventLine("demand.expired"))
        assertEquals("Connexion impossible", NetworkAccess.eventLine("demand.failed"))
    }

    @Test fun a_technical_event_produces_no_history_line_at_all() {
        for (kind in listOf("activation.offered", "presence.expired", "http.409", ""))
            assertEquals("diagnostics must not leak into the history",
                "", NetworkAccess.eventLine(kind))
    }

    @Test fun one_transition_is_one_line_however_often_it_is_reported() {
        val a = NetworkAccess.dedupKey("dem-1", "demand.connected")
        val b = NetworkAccess.dedupKey("dem-1", "demand.connected")
        val c = NetworkAccess.dedupKey("dem-2", "demand.connected")
        val d = NetworkAccess.dedupKey("dem-1", "demand.cancelled")
        assertEquals(a, b)
        assertTrue(a != c && a != d)
    }

    // ================= the flags this replaces =================

    @Test fun waiting_on_the_network_is_one_question_with_one_answer() {
        // the reason this model exists: three booleans in three screens would disagree
        assertTrue(NetworkAccess.Snapshot(NetworkAccess.State.SEARCHING, "d", 0, red).waitingOnNetwork)
        assertTrue(NetworkAccess.Snapshot(NetworkAccess.State.PROVIDER_FOUND, "d", 0, red).waitingOnNetwork)
        assertTrue(NetworkAccess.Snapshot(NetworkAccess.State.PROVIDER_ACCEPTED, "d", 0, red).waitingOnNetwork)
        assertTrue(!NetworkAccess.Snapshot(NetworkAccess.State.CONNECTING, "d", 0, red).waitingOnNetwork)
        assertTrue(!NetworkAccess.Snapshot(NetworkAccess.State.CONNECTED, "d", 0, red).waitingOnNetwork)
        assertTrue(!NetworkAccess.idle(red).live)
        assertTrue(NetworkAccess.searching("d", now, red).live)
    }
}
