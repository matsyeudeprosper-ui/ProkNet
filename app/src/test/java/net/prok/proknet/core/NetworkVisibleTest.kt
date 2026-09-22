package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.17.1: the part of v0.17 a person can actually see.
 *
 * v0.17.0 built the model and drew none of it, so build 68 would have looked to Mike like
 * nothing had changed. These tests run the same functions the screens run - `homeState`
 * for the Home card, `mergeZone` for the map, `NetworkHistory` for Activité - so "the
 * Home line says *Un fournisseur se prépare*" is verified rather than asserted about a
 * string constant sitting in a file.
 */
class NetworkVisibleTest {

    private val now = 1_700_000_000_000L
    private val green = Coverage.ZoneStatus.GREEN
    private val yellow = Coverage.ZoneStatus.YELLOW
    private val red = Coverage.ZoneStatus.RED

    private fun home(
        internetUp: Boolean = false,
        linkComingUp: Boolean = false,
        searchingLocally: Boolean = false,
        lastAttemptFailed: Boolean = false,
        demandId: String = "",
        demandStatus: String = "",
        zone: Coverage.ZoneStatus = yellow,
        brainOffline: Boolean = false,
        startedAt: Long = 0,
        localUsable: Boolean = false,
    ) = NetworkAccess.homeState(internetUp, linkComingUp, searchingLocally,
        lastAttemptFailed, demandId, demandStatus, zone, brainOffline, now, startedAt,
        localUsable)

    /** What the Home card would print, through the same two calls the screen makes. */
    private fun line(s: NetworkAccess.Snapshot) = NetworkAccess.title(s)

    // ================= item 35: the Home card =================

    @Test fun searching_shows_recherche_dinternet() {
        assertEquals("Recherche d'Internet…",
            line(home(demandId = "dem-1", demandStatus = "SEARCHING")))
    }

    @Test fun a_provider_found_shows_un_fournisseur_est_disponible() {
        assertEquals("Un fournisseur est disponible",
            line(home(demandId = "dem-1", demandStatus = "PROVIDER_FOUND")))
        assertEquals("Un fournisseur est disponible",
            line(home(demandId = "dem-1", demandStatus = "ACTIVATION_SENT")))
    }

    @Test fun provider_accepted_shows_un_fournisseur_se_prepare() {
        assertEquals("Un fournisseur se prépare",
            line(home(demandId = "dem-1", demandStatus = "PROVIDER_ACCEPTED")))
    }

    @Test fun waiting_for_the_link_shows_connexion_en_cours() {
        assertEquals("Connexion en cours…",
            line(home(demandId = "dem-1", demandStatus = "WAITING_FOR_LOCAL_LINK")))
    }

    @Test fun only_the_transport_turns_the_card_to_connected() {
        assertEquals("Connecté", line(home(internetUp = true)))
        // the Brain saying CONNECTED is somebody else's news, not ours
        assertNotEquals("Connecté", line(home(demandId = "dem-1", demandStatus = "CONNECTED")))
        assertEquals("Connexion en cours…",
            line(home(demandId = "dem-1", demandStatus = "CONNECTED")))
    }

    @Test fun a_failed_or_expired_request_says_nobody_can_share() {
        for (status in listOf("FAILED", "EXPIRED"))
            assertEquals("Personne ne peut partager pour le moment",
                line(home(demandId = "dem-1", demandStatus = status)))
        assertEquals("Personne ne peut partager pour le moment",
            line(home(lastAttemptFailed = true)))
    }

    @Test fun a_cancelled_request_goes_back_to_describing_the_area() {
        val s = home(demandId = "dem-1", demandStatus = "CANCELLED", zone = yellow)
        assertEquals(NetworkAccess.State.IDLE, s.state)
        assertEquals("ProkNet peut chercher un fournisseur", line(s))
    }

    @Test fun the_transport_outranks_everything_the_brain_says() {
        // item 3 and item 22: local truth wins, whatever coordination thinks
        for (status in listOf("SEARCHING", "PROVIDER_FOUND", "PROVIDER_ACCEPTED", "FAILED")) {
            val s = home(internetUp = true, demandId = "dem-1", demandStatus = status)
            assertEquals("the transport said Internet works; " + status + " cannot override it",
                NetworkAccess.State.CONNECTED, s.state)
        }
    }

    @Test fun a_link_coming_up_outranks_a_searching_demand() {
        val s = home(linkComingUp = true, demandId = "dem-1", demandStatus = "SEARCHING")
        assertEquals(NetworkAccess.State.CONNECTING, s.state)
    }

    @Test fun an_idle_phone_describes_its_neighbourhood() {
        assertEquals("Un fournisseur est actif dans votre zone", line(home(zone = green)))
        assertEquals("ProkNet peut chercher un fournisseur", line(home(zone = yellow)))
        assertEquals("Pas de fournisseur connu actuellement", line(home(zone = red)))
    }

    @Test fun a_long_wait_softens_the_wording_rather_than_repeating_it() {
        val s = home(demandId = "dem-1", demandStatus = "SEARCHING",
            startedAt = now - NetworkAccess.LONG_SEARCH_MS - 1)
        assertEquals("Recherche toujours en cours…", line(s))
        assertEquals("Cela peut prendre quelques minutes.", NetworkAccess.hint(s))
    }

    @Test fun the_brain_being_away_is_said_plainly_and_changes_nothing_local() {
        val s = home(demandId = "dem-1", demandStatus = "SEARCHING", brainOffline = true)
        assertEquals("ProkNet continue de chercher autour de vous.", NetworkAccess.hint(s))
        // and a working local session is unaffected by the Brain being gone
        assertEquals(NetworkAccess.State.CONNECTED,
            home(internetUp = true, brainOffline = true).state)
    }

    @Test fun every_home_line_is_short_and_carries_no_protocol_word() {
        val statuses = listOf("", "CREATED", "SEARCHING", "PROVIDER_FOUND", "ACTIVATION_SENT",
            "PROVIDER_ACCEPTED", "WAITING_FOR_LOCAL_LINK", "CONNECTED", "EXPIRED",
            "CANCELLED", "FAILED")
        for (st in statuses) {
            for (zone in Coverage.ZoneStatus.values()) {
                val s = home(demandId = if (st.isEmpty()) "" else "dem-1",
                    demandStatus = st, zone = zone)
                val t = line(s)
                assertTrue("empty line for " + st, t.isNotEmpty())
                assertTrue("too long: " + t, t.length <= 42)
                for (word in listOf("ACTIVATION", "demand", "HTTP", "L2CAP", "nonce",
                        "PROVIDER_", "null", "MB"))
                    assertTrue("'" + word + "' reached the screen: " + t, !t.contains(word))
            }
        }
    }

    // ================= items 6-10: the map =================

    @Test fun a_usable_local_source_is_green_whatever_the_brain_thinks() {
        assertEquals(green, NetworkAccess.mergeZone(red, red, 0, localDirectUsable = true))
    }

    @Test fun local_green_outranks_a_stale_brain() {
        assertEquals(green, NetworkAccess.mergeZone(green, red, Long.MAX_VALUE))
    }

    @Test fun a_fresh_brain_green_is_green() {
        assertEquals(green, NetworkAccess.mergeZone(red, green, 30_000))
    }

    @Test fun yesterdays_provider_does_not_keep_a_zone_green() {
        // the specific thing item 9 is written to prevent
        assertEquals(red, NetworkAccess.mergeZone(red, green,
            NetworkAccess.BRAIN_ZONE_FRESH_MS + 1))
        assertEquals(red, NetworkAccess.mergeZone(red, yellow,
            NetworkAccess.BRAIN_ZONE_FRESH_MS + 1))
    }

    @Test fun the_freshness_boundary_is_exact() {
        assertEquals(green, NetworkAccess.mergeZone(red, green,
            NetworkAccess.BRAIN_ZONE_FRESH_MS - 1))
        assertEquals(red, NetworkAccess.mergeZone(red, green,
            NetworkAccess.BRAIN_ZONE_FRESH_MS))
        assertTrue(NetworkAccess.brainZoneFresh(0))
        assertTrue(!NetworkAccess.brainZoneFresh(Long.MAX_VALUE))
        assertTrue("a negative age is a clock going backwards, not freshness",
            !NetworkAccess.brainZoneFresh(-1))
    }

    @Test fun either_side_calling_it_a_candidate_makes_it_yellow() {
        assertEquals(yellow, NetworkAccess.mergeZone(yellow, red, 0))
        assertEquals(yellow, NetworkAccess.mergeZone(red, yellow, 0))
    }

    @Test fun knowing_nothing_is_red_and_not_invented() {
        // item 10: no demo cells. Nothing known means nothing claimed.
        assertEquals(red, NetworkAccess.mergeZone(red, red, 0))
        assertEquals(red, NetworkAccess.mergeZone(red, red, Long.MAX_VALUE))
    }

    @Test fun the_map_labels_never_promise_anything() {
        assertEquals("Fournisseur actif dans cette zone", NetworkAccess.zoneLabel(green))
        assertEquals("ProkNet peut chercher un fournisseur", NetworkAccess.zoneLabel(yellow))
        assertEquals("Pas de fournisseur connu actuellement", NetworkAccess.zoneLabel(red))
        for (z in Coverage.ZoneStatus.values()) {
            val l = NetworkAccess.zoneLabel(z)
            assertTrue(!l.contains("garanti") && !l.contains("sûr"))
        }
    }

    // ================= items 15-18: a zone colour is not a connection =================

    /**
     * The bug this replaced: `IDLE + GREEN` said "Internet disponible maintenant", and
     * GREEN can come entirely from the Brain - which knows only that somebody was sharing
     * somewhere in a coarse cell. Not that this phone can reach them, not that Bluetooth
     * carries that far, not that they still have capacity.
     */
    @Test fun a_brain_green_zone_never_claims_working_internet() {
        val s = home(zone = green)                       // no local source, nothing up
        assertEquals("Un fournisseur est actif dans votre zone", line(s))
        assertTrue("a colour must not promise a connection",
            !line(s).contains("disponible maintenant"))
        assertTrue(line(s) != "Connecté")
    }

    @Test fun a_real_local_source_may_promise_internet() {
        val s = home(zone = red, localUsable = true)
        assertEquals(NetworkAccess.State.LOCAL_AVAILABLE, s.state)
        assertEquals("Internet disponible maintenant", line(s))
    }

    @Test fun the_transport_being_up_says_connected() {
        assertEquals("Connecté", line(home(internetUp = true)))
    }

    @Test fun the_brain_saying_connected_while_the_transport_has_not_never_says_connected() {
        val s = home(demandId = "dem-1", demandStatus = "CONNECTED", zone = green)
        assertEquals("Connexion en cours…", line(s))
        assertTrue(line(s) != "Connecté")
    }

    @Test fun a_green_zone_with_a_live_search_still_says_it_is_searching() {
        // the colour must not overtake a request that is actually in progress
        assertEquals("Recherche d'Internet…",
            line(home(demandId = "dem-1", demandStatus = "SEARCHING", zone = green)))
    }

    @Test fun a_local_source_does_not_hijack_a_request_already_under_way() {
        val s = home(demandId = "dem-1", demandStatus = "PROVIDER_ACCEPTED",
            zone = green, localUsable = true)
        assertEquals("Un fournisseur se prépare", line(s))
    }

    @Test fun no_line_anywhere_promises_internet_from_a_colour_alone() {
        for (zone in Coverage.ZoneStatus.values()) {
            val s = home(zone = zone)                    // localUsable = false throughout
            assertTrue(zone.toString() + " said: " + line(s),
                !line(s).contains("disponible maintenant"))
            assertTrue(!NetworkAccess.zoneLabel(zone).contains("disponible maintenant"))
        }
    }

    // ================= items 19-20, 36: coverage has its own clock =================

    @Test fun a_coverage_answer_ages_out_on_its_own_timestamp() {
        // GREEN fetched at T0, and nothing fetched since - however busy the rest of the
        // control plane has been
        assertEquals(green, NetworkAccess.mergeZone(red, green, 4 * 60_000))
        assertEquals("six minutes later it is no longer evidence",
            red, NetworkAccess.mergeZone(red, green, 6 * 60_000))
    }

    @Test fun a_never_fetched_coverage_answer_is_not_fresh() {
        assertEquals(red, NetworkAccess.mergeZone(red, green, Long.MAX_VALUE))
        assertTrue(!NetworkAccess.brainZoneFresh(Long.MAX_VALUE))
    }

    // ================= items 11-13: Activité =================

    @Test fun a_transition_becomes_one_readable_line() {
        var st = NetworkHistory.State()
        st = NetworkHistory.onStatus(st, "dem-1", "CREATED", now)
        assertEquals(1, st.visible.size)
        assertEquals("Recherche Internet", st.visible[0].line)
    }

    @Test fun polling_the_same_status_does_not_fill_the_screen() {
        // the exact failure item 12 names
        var st = NetworkHistory.State()
        for (i in 0 until 20) st = NetworkHistory.onStatus(st, "dem-1", "SEARCHING", now + i * 1_000)
        assertEquals(1, st.visible.size)
    }

    @Test fun nothing_is_written_when_nothing_changed() {
        val st = NetworkHistory.onStatus(NetworkHistory.State(), "dem-1", "CREATED", now)
        val again = NetworkHistory.onStatus(st, "dem-1", "CREATED", now + 5_000)
        assertTrue("an unchanged poll must not repaint anything", st === again)
    }

    @Test fun the_whole_journey_reads_like_a_person_wrote_it() {
        var st = NetworkHistory.State()
        // real transitions arrive seconds apart, and newest is shown first
        var at = now
        for (s in listOf("CREATED", "SEARCHING", "PROVIDER_FOUND", "ACTIVATION_SENT",
                "PROVIDER_ACCEPTED", "CONNECTED")) {
            at += 5_000
            st = NetworkHistory.onStatus(st, "dem-1", s, at)
        }
        assertEquals(listOf("Connexion réussie", "Partage accepté", "Fournisseur trouvé",
            "Recherche Internet"), st.visible.map { it.line })
    }

    @Test fun two_requests_keep_their_own_history() {
        var st = NetworkHistory.State()
        st = NetworkHistory.onStatus(st, "dem-1", "CREATED", now)
        st = NetworkHistory.onStatus(st, "dem-2", "CREATED", now + 1_000)
        assertEquals(2, st.visible.size)
    }

    @Test fun a_technical_event_is_never_written_at_all() {
        var st = NetworkHistory.State()
        for (kind in listOf("activation.offered", "presence.expired", "http.401", "nonce"))
            st = NetworkHistory.note(st, "dem-1", kind, now)
        assertEquals("diagnostics must not reach Activité", 0, st.rows.size)
    }

    @Test fun nothing_in_the_history_carries_a_protocol_word() {
        var st = NetworkHistory.State()
        for (s in listOf("CREATED", "PROVIDER_FOUND", "PROVIDER_ACCEPTED", "CONNECTED",
                "CANCELLED", "EXPIRED", "FAILED"))
            st = NetworkHistory.onStatus(st, "d-" + s, s, now)
        for (r in st.visible)
            for (word in listOf("HTTP", "nonce", "L2CAP", "activation", "state", "_"))
                assertTrue("'" + word + "' in: " + r.line, !r.line.contains(word))
    }

    @Test fun the_history_survives_being_written_and_read_back() {
        var st = NetworkHistory.State()
        st = NetworkHistory.onStatus(st, "dem-1", "CREATED", now)
        st = NetworkHistory.onStatus(st, "dem-1", "PROVIDER_ACCEPTED", now + 1_000)
        st = NetworkHistory.onStatus(st, "dem-1", "CONNECTED", now + 2_000)
        val back = NetworkHistory.decode(NetworkHistory.encode(st))
        assertEquals(st.visible.map { it.line }, back.visible.map { it.line })
        assertEquals(st.visible.map { it.at }, back.visible.map { it.at })
        // and re-noting what is already there still changes nothing
        assertTrue(NetworkHistory.onStatus(back, "dem-1", "CONNECTED", now + 9_000) === back)
    }

    @Test fun a_truncated_file_does_not_stop_the_app_opening() {
        val good = NetworkHistory.encode(
            NetworkHistory.onStatus(NetworkHistory.State(), "dem-1", "CREATED", now))
        for (broken in listOf(good + "\nrubbish", "\n\n", "not\ta\tthing", good.dropLast(3)))
            NetworkHistory.decode(broken)          // must not throw
        assertEquals(1, NetworkHistory.decode(good + "\nrubbish").visible.size)
    }

    @Test fun the_history_does_not_grow_for_ever() {
        var st = NetworkHistory.State()
        for (i in 0 until NetworkHistory.MAX_ROWS + 25)
            st = NetworkHistory.onStatus(st, "dem-" + i, "CREATED", now + i * 1_000L)
        assertTrue(st.rows.size <= NetworkHistory.MAX_ROWS)
        assertEquals("the newest are the ones kept",
            now + (NetworkHistory.MAX_ROWS + 24) * 1_000L, st.visible.first().at)
    }

    @Test fun an_unknown_status_writes_no_line() {
        val st = NetworkHistory.onStatus(NetworkHistory.State(), "dem-1", "TELEPORTING", now)
        assertEquals(0, st.rows.size)
    }
}
