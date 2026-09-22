package net.prok.proknet.core

/**
 * v0.17.0: one model for "can I get Internet right now", and what to say about it.
 *
 * The reason this exists rather than a handful of booleans. Before v0.17 the answer came
 * from local discovery alone, and one flag could carry it. Now it comes from three places
 * at once - a usable local source, a locally visible ProkNet provider, and a demand the
 * Brain is working on - and the screen has to show one honest sentence about all three.
 * `brainSearching`, `providerFound` and `localConnecting` spread through the UI would
 * disagree with each other within a week.
 *
 * So: the local path and the Brain both feed [State], and every screen reads it.
 *
 * The honesty rule the wording enforces. The Brain can say a provider exists in this
 * neighbourhood and that it agreed to help. It cannot say Bluetooth reached, because only
 * the transport knows that. So nothing here says "Internet disponible" until the transport
 * itself reports a usable path - a provider accepting is "se prépare", not "disponible".
 * A buyer told there is Internet who then cannot load a page has been lied to, and will
 * not press the button again.
 */
object NetworkAccess {

    /**
     * Where a buyer stands. Ordered roughly by progress, but the order is not a promise:
     * SEARCHING may follow PROVIDER_FOUND when a provider declines.
     */
    enum class State {
        /** Nothing asked for, nothing running. */
        IDLE,

        /** A usable source is already here. The fastest path, and it needs no server. */
        LOCAL_AVAILABLE,

        /** Nobody local; the Brain is looking. */
        SEARCHING,

        /** The Brain found somebody worth asking and has asked them. */
        PROVIDER_FOUND,

        /** They said yes. They are not necessarily reachable yet. */
        PROVIDER_ACCEPTED,

        /** The two phones are trying to reach each other over Bluetooth. */
        CONNECTING,

        /** The transport says there is a usable path. Only the transport may say this. */
        CONNECTED,

        /** Nobody could help, or the link would not come up. */
        FAILED,
    }

    /** Zone colour, as a map or a Home line would show it. Reuses the existing enum. */
    fun zoneStatus(freshProviders: Int, recentSuccess: Boolean): Coverage.ZoneStatus = when {
        freshProviders > 0 && recentSuccess -> Coverage.ZoneStatus.GREEN
        freshProviders > 0 -> Coverage.ZoneStatus.YELLOW
        else -> Coverage.ZoneStatus.RED
    }

    /**
     * v0.17.1: how long a zone answer from the Brain may be believed.
     *
     * Shorter than the server's own presence window on purpose. The phone is reading a
     * cached answer over a connection that may have died minutes ago, so its confidence
     * has to decay faster than the thing it describes. A zone that was green yesterday is
     * not green now.
     */
    const val BRAIN_ZONE_FRESH_MS = 5 * 60_000L

    /**
     * v0.17.1: one colour for a zone, from everything we know about it.
     *
     * The precedence matters more than the code. **Local observation outranks the Brain**,
     * because a source this phone can actually see is stronger evidence than a server's
     * opinion about the neighbourhood - and because the Brain's answer is a cached hint
     * that ages, while a usable source in front of you does not.
     *
     * 1. a local source that works right now -> GREEN, whatever the Brain thinks;
     * 2. a fresh Brain GREEN -> GREEN;
     * 3. anything anyone still considers a candidate -> YELLOW;
     * 4. nothing useful and nothing fresh -> RED.
     *
     * A stale Brain answer is discarded rather than downgraded one step, because there is
     * no honest middle ground between "somebody was there five minutes ago" and "somebody
     * is there" - and leaving yesterday's provider showing green is the specific thing
     * this is written to prevent.
     */
    fun mergeZone(
        local: Coverage.ZoneStatus,
        brain: Coverage.ZoneStatus,
        brainAgeMs: Long,
        localDirectUsable: Boolean = false,
    ): Coverage.ZoneStatus {
        val brainFresh = brainAgeMs in 0 until BRAIN_ZONE_FRESH_MS
        val b = if (brainFresh) brain else Coverage.ZoneStatus.RED
        return when {
            localDirectUsable -> Coverage.ZoneStatus.GREEN
            local == Coverage.ZoneStatus.GREEN -> Coverage.ZoneStatus.GREEN
            b == Coverage.ZoneStatus.GREEN -> Coverage.ZoneStatus.GREEN
            local == Coverage.ZoneStatus.YELLOW || b == Coverage.ZoneStatus.YELLOW ->
                Coverage.ZoneStatus.YELLOW
            else -> Coverage.ZoneStatus.RED
        }
    }

    /**
     * Whether a Brain zone answer is still worth showing at all.
     *
     * Exposed so a screen can decide to say nothing rather than say something stale.
     */
    fun brainZoneFresh(brainAgeMs: Long): Boolean =
        brainAgeMs in 0 until BRAIN_ZONE_FRESH_MS

    /**
     * Everything a screen needs, and nothing it does not.
     *
     * No provider id, because no screen should show one - a buyer is told somebody is
     * preparing, not which phone. Diagnostics get ids; people do not.
     */
    class Snapshot(
        val state: State,
        /** Set only while a Brain demand is live, so a restart can pick it up again. */
        val demandId: String = "",
        /** How long the buyer has been waiting, for the "still looking" wording. */
        val waitingMs: Long = 0,
        /** What this zone looks like, for Home and the map. */
        val zone: Coverage.ZoneStatus = Coverage.ZoneStatus.RED,
        /** True when the Brain is unreachable; the local path is unaffected. */
        val brainOffline: Boolean = false,
        val note: String = "",
    ) {
        val live: Boolean get() = state != State.IDLE && state != State.FAILED
        /** Whether ProkNet is waiting on somebody else rather than on itself. */
        val waitingOnNetwork: Boolean get() =
            state == State.SEARCHING || state == State.PROVIDER_FOUND ||
                state == State.PROVIDER_ACCEPTED
    }

    /** After this long with nobody found, the wording softens rather than repeating. */
    const val LONG_SEARCH_MS = 45_000L

    /**
     * The one line Home shows under "Internet autour de vous".
     *
     * Short, ordinary French. Nothing here is a protocol word, and nothing promises
     * Internet before the transport has reported a path.
     */
    fun title(s: Snapshot): String = when (s.state) {
        State.IDLE -> when (s.zone) {
            Coverage.ZoneStatus.GREEN -> "Internet disponible maintenant"
            Coverage.ZoneStatus.YELLOW -> "ProkNet peut chercher un fournisseur"
            Coverage.ZoneStatus.RED -> "Pas de fournisseur connu actuellement"
        }
        State.LOCAL_AVAILABLE -> "Internet disponible maintenant"
        State.SEARCHING ->
            if (s.waitingMs >= LONG_SEARCH_MS) "Recherche toujours en cours…"
            else "Recherche d'Internet…"
        State.PROVIDER_FOUND -> "Un fournisseur est disponible"
        State.PROVIDER_ACCEPTED -> "Un fournisseur se prépare"
        State.CONNECTING -> "Connexion en cours…"
        State.CONNECTED -> "Connecté"
        State.FAILED -> "Personne ne peut partager pour le moment"
    }

    /** A second line, only when it adds something. Empty means show nothing. */
    fun hint(s: Snapshot): String = when {
        s.state == State.SEARCHING && s.brainOffline ->
            "ProkNet continue de chercher autour de vous."
        s.state == State.SEARCHING && s.waitingMs >= LONG_SEARCH_MS ->
            "Cela peut prendre quelques minutes."
        s.state == State.PROVIDER_ACCEPTED -> "Gardez le téléphone à proximité."
        s.state == State.FAILED -> "Réessayez dans un moment."
        s.state == State.IDLE && s.zone == Coverage.ZoneStatus.YELLOW ->
            "Appuyez pour chercher."
        else -> ""
    }

    /** What the map says for a zone. Operational hints, never guarantees. */
    fun zoneLabel(z: Coverage.ZoneStatus): String = when (z) {
        Coverage.ZoneStatus.GREEN -> "Internet disponible maintenant"
        Coverage.ZoneStatus.YELLOW -> "ProkNet peut chercher un fournisseur"
        Coverage.ZoneStatus.RED -> "Pas de fournisseur connu actuellement"
    }

    /**
     * What the provider's Gagner hero says when the Brain has asked it for help.
     * Reuses the v0.13.3 wording pattern rather than inventing a second voice.
     */
    fun providerTitle(offered: Boolean, accepted: Boolean): String = when {
        accepted -> "PRÊT À PARTAGER"
        offered -> "DEMANDE PROCHE"
        else -> ""
    }

    fun providerText(offered: Boolean, accepted: Boolean): String = when {
        accepted -> "ProkNet cherche l'appareil à connecter."
        offered -> "Quelqu'un cherche Internet près de vous."
        else -> ""
    }

    /** The notification a provider sees. One sentence, one action. */
    const val NOTIFICATION_TITLE = "Quelqu'un près de vous cherche Internet"
    const val NOTIFICATION_ACTION = "PARTAGER"

    // ---- what the local side reports, and how it maps in -------------------------------

    /**
     * The transport's own milestones. Only these may move a demand towards CONNECTED,
     * which is why they are named after things the radio can actually observe.
     */
    enum class LinkEvent {
        /** An authenticated ProkNet peer is in range. Not yet carrying anything. */
        PEER_SEEN,

        /** A session is up and Internet is flowing. The strongest thing we can say. */
        INTERNET_UP,

        /** It did not come up, or it dropped before it worked. */
        LINK_FAILED,
    }

    /** What the Brain should be told, or empty when this event is not worth reporting. */
    fun reportFor(e: LinkEvent): String = when (e) {
        LinkEvent.PEER_SEEN -> "LOCAL_LINK_SEEN"
        LinkEvent.INTERNET_UP -> "CONNECTED"
        LinkEvent.LINK_FAILED -> "FAILED"
    }

    /**
     * Fold a local event into the model.
     *
     * Note what cannot happen: nothing here produces CONNECTED except [LinkEvent.INTERNET_UP].
     * A provider accepting, a zone turning green and a peer appearing all stop short.
     */
    fun onLink(s: Snapshot, e: LinkEvent, now: Long): Snapshot = when (e) {
        LinkEvent.PEER_SEEN ->
            if (s.state == State.CONNECTED) s
            else Snapshot(State.CONNECTING, s.demandId, s.waitingMs, s.zone, s.brainOffline)
        LinkEvent.INTERNET_UP ->
            Snapshot(State.CONNECTED, s.demandId, s.waitingMs, s.zone, s.brainOffline)
        LinkEvent.LINK_FAILED ->
            // back to looking: another provider may still work, and the demand is alive
            if (s.demandId.isEmpty())
                Snapshot(State.FAILED, "", s.waitingMs, s.zone, s.brainOffline, "link failed")
            else Snapshot(State.SEARCHING, s.demandId, s.waitingMs, s.zone, s.brainOffline)
    }

    /**
     * Fold the Brain's view of a demand in.
     *
     * The server's own status words, mapped once here so no screen ever parses them.
     * An unknown word leaves the model alone rather than guessing - a newer Brain must
     * not be able to put an older phone into a state it has no wording for.
     */
    fun onDemandStatus(s: Snapshot, status: String, demandId: String, now: Long,
                       createdAt: Long = 0): Snapshot {
        val waited = if (createdAt > 0) now - createdAt else s.waitingMs
        val next = when (status) {
            "CREATED", "SEARCHING" -> State.SEARCHING
            "PROVIDER_FOUND", "ACTIVATION_SENT" -> State.PROVIDER_FOUND
            "PROVIDER_ACCEPTED" -> State.PROVIDER_ACCEPTED
            "WAITING_FOR_LOCAL_LINK" -> State.CONNECTING
            // deliberately NOT State.CONNECTED. The Brain is told about a connection by a
            // phone; it is not evidence that THIS phone has one. The transport says so.
            "CONNECTED" -> if (s.state == State.CONNECTED) State.CONNECTED else State.CONNECTING
            "EXPIRED", "FAILED" -> State.FAILED
            "CANCELLED" -> State.IDLE
            else -> return s
        }
        val id = if (next == State.IDLE || next == State.FAILED) "" else demandId
        return Snapshot(next, id, waited, s.zone, s.brainOffline)
    }

    /**
     * v0.17.1: the whole Home decision, as one pure function.
     *
     * `MainActivity` needs a `Context` and cannot run off a phone, so the decision lives
     * here and the screen only draws the result. A test therefore exercises the same
     * function the screen runs, rather than a parallel copy of the reasoning - which is
     * the only way "PROVIDER_ACCEPTED shows *Un fournisseur se prépare*" can actually be
     * verified rather than asserted about a string constant.
     *
     * The order of the branches IS the honesty rule:
     *
     * 1. the transport says Internet works -> CONNECTED. Nothing else can produce this.
     * 2. the transport is bringing a link up -> CONNECTING.
     * 3. a Brain demand is live -> whatever the Brain says it is.
     * 4. searching locally -> SEARCHING.
     * 5. the last attempt failed -> FAILED.
     * 6. otherwise -> IDLE, described by the zone.
     *
     * @param internetUp the transport's own verdict, and the only route to CONNECTED.
     * @param linkComingUp a peer is being connected to right now.
     * @param demandStatus the Brain's word for our demand, empty when there is none.
     */
    fun homeState(
        internetUp: Boolean,
        linkComingUp: Boolean,
        searchingLocally: Boolean,
        lastAttemptFailed: Boolean,
        demandId: String,
        demandStatus: String,
        zone: Coverage.ZoneStatus,
        brainOffline: Boolean,
        now: Long,
        requestStartedAt: Long = 0,
    ): Snapshot {
        val base = idle(zone)
        val s = when {
            internetUp -> onLink(base, LinkEvent.INTERNET_UP, now)
            linkComingUp -> onLink(base, LinkEvent.PEER_SEEN, now)
            demandId.isNotEmpty() && demandStatus.isNotEmpty() -> onDemandStatus(
                searching(demandId, now, zone, brainOffline), demandStatus, demandId, now,
                requestStartedAt)
            demandId.isNotEmpty() -> searching(demandId, now, zone, brainOffline)
            searchingLocally -> searching("", now, zone, brainOffline)
            lastAttemptFailed -> Snapshot(State.FAILED, "", 0, zone, brainOffline)
            else -> base
        }
        return withBrainOffline(withZone(s, zone), brainOffline)
    }

    /** A local source is usable right now, so nothing else matters. */
    fun localAvailable(s: Snapshot): Snapshot =
        Snapshot(State.LOCAL_AVAILABLE, "", 0, s.zone, s.brainOffline)

    fun idle(zone: Coverage.ZoneStatus = Coverage.ZoneStatus.RED): Snapshot =
        Snapshot(State.IDLE, "", 0, zone)

    fun searching(demandId: String, now: Long, zone: Coverage.ZoneStatus,
                  brainOffline: Boolean = false): Snapshot =
        Snapshot(State.SEARCHING, demandId, 0, zone, brainOffline)

    fun withZone(s: Snapshot, z: Coverage.ZoneStatus): Snapshot =
        Snapshot(s.state, s.demandId, s.waitingMs, z, s.brainOffline, s.note)

    fun withBrainOffline(s: Snapshot, off: Boolean): Snapshot =
        Snapshot(s.state, s.demandId, s.waitingMs, s.zone, off, s.note)

    // ---- what Activité shows ---------------------------------------------------------

    /**
     * Ordinary sentences for the history, one per meaningful transition.
     *
     * No protocol in here. "HTTP 409", "L2CAP state=7" and "nonce rejected" belong in
     * diagnostics, where somebody who wants them can find them.
     */
    fun eventLine(kind: String): String = when (kind) {
        "demand.created" -> "Recherche Internet"
        "demand.provider_found", "demand.activation_sent" -> "Fournisseur trouvé"
        "demand.provider_accepted" -> "Partage accepté"
        "demand.connected" -> "Connexion réussie"
        "demand.cancelled" -> "Demande annulée"
        "demand.expired" -> "Demande expirée"
        "demand.failed" -> "Connexion impossible"
        else -> ""
    }

    /**
     * One line per logical transition, however many times the Brain repeats itself.
     *
     * A retried sync sends the same status again; a buyer polling every few seconds sends
     * it many times. Keyed on the demand and the transition, so the history stays readable.
     */
    fun dedupKey(demandId: String, kind: String): String = demandId + "|" + kind
}
