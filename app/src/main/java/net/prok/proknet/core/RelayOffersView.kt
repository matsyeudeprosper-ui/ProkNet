package net.prok.proknet.core

/**
 * v0.19.0: what the Brain's job engine says about this phone's relay offers, read into
 * something a screen can show - and the words the screen is allowed to use.
 *
 * The product rule (contract §4): a pending job is never spendable cash. The text for each
 * state comes from ONE table the server also holds (server/tests/fixtures/relay_offer_states.txt);
 * "Vérifié — gagné" is said only when the Brain verified and settled the block or the move.
 * A state this build does not know reads as "Non vérifié — rien gagné", never as earned.
 *
 * Before the volunteer accepts, the screen shows the amount, the time, the requirements and
 * the cap ([Offer.termsLine]) - the contract's "state the offered amount, time, requirements
 * and cap before the volunteer accepts". The earnings pipeline is the contract's five words.
 *
 * Pure: `RelayOffersActivity` gathers and draws, this decides. Parsing uses [BrainPayload]
 * and [LedgerView]'s small readers, never org.json, so it runs in the JVM tests as on the phone.
 */
object RelayOffersView {

    /** State -> what the screen says. Must equal jobs.OFFER_TEXT on the server. */
    val OFFER_TEXT: Map<String, String> = linkedMapOf(
        "OFFERED" to "Proposé",
        "ACCEPTED" to "Accepté — en attente",
        "IN_PROGRESS" to "En cours",
        "COMPLETED" to "Vérifié — gagné",
        "DECLINED" to "Refusé",
        "CANCELLED" to "Annulé",
        "EXPIRED" to "Expiré",
        "FAILED" to "Non vérifié — rien gagné",
    )
    const val UNKNOWN_TEXT = "Non vérifié — rien gagné"
    val OPEN_STATES = setOf("OFFERED", "ACCEPTED", "IN_PROGRESS")

    /** The contract's pipeline, in order. Must equal jobs.PIPELINE on the server. */
    val PIPELINE = listOf("Contrat en attente", "Vérifié", "Gagné", "Retrait demandé", "Payé")

    fun statusText(state: String): String = OFFER_TEXT[state] ?: UNKNOWN_TEXT

    fun kindWord(kind: String): String = when (kind) {
        "CARRY" -> "Relayer maintenant"
        "STAY" -> "Rester disponible"
        "MOVE" -> "Se déplacer pour aider"
        else -> kind
    }

    class Offer(
        val id: String, val kind: String, val zone: String, val amountCentimes: Long, val extraCentimes: Long, val state: String,
        val createdAt: Long, val acceptedAt: Long, val expiresAt: Long, val endedAt: Long, val earnedCentimes: Long,
        val batteryFloorPct: Int, val minPayoutCentimes: Long, val windowStartMin: Int, val windowEndMin: Int,
        val blockMinutes: Int, val blocksPerDay: Int, val probesRequired: Int, val probesMaxFailed: Int,
        val blockStartedAt: Long = 0, val blockElapsedMs: Long = 0, val probesOk: Int = 0, val probesFailed: Int = 0,
        val blockState: String = "", val failReason: String = "", val rendezvousCell: String = "",
        val departedAt: Long = 0, val arrivedAt: Long = 0, val readyAt: Long = 0, val confirmedAt: Long = 0,
        val compensationCentimes: Long = 0, val pipelineStage: Int = -1,
    ) {
        val text: String get() = statusText(state)
        val open: Boolean get() = state in OPEN_STATES
        val canAccept: Boolean get() = state == "OFFERED"
        val canDecline: Boolean get() = state == "OFFERED" || state == "ACCEPTED"
        val canStartBlock: Boolean get() = kind == "STAY" && state == "ACCEPTED"
        val blockRunning: Boolean get() = kind == "STAY" && state == "IN_PROGRESS"
        val canDepart: Boolean get() = kind == "MOVE" && state == "ACCEPTED"
        val canArrive: Boolean get() = kind == "MOVE" && state == "IN_PROGRESS" && arrivedAt == 0L
        val canSayReady: Boolean get() = kind == "MOVE" && state == "IN_PROGRESS" && arrivedAt > 0L && readyAt == 0L
        val canCancel: Boolean get() = kind == "MOVE" && state == "IN_PROGRESS"
        val title: String get() = kindWord(kind) + (if (zone.isNotEmpty()) " · " + zone else "")

        /** Amount, time, requirements and cap - shown BEFORE acceptance, exactly as the Brain will judge them. */
        fun termsLine(): String = when (kind) {
            "STAY" -> Market.cfa(amountCentimes) + " par bloc de " + blockMinutes + " min · " + probesRequired +
                " sondes réussies réparties sur le bloc, " + probesMaxFailed + " échec toléré · max " + blocksPerDay + " blocs/jour" +
                " · 2 échecs de suite ou 10 min sans réponse = bloc perdu, rien gagné" + requirementsSuffix()
            "MOVE" -> Market.cfa(amountCentimes) + " fixes à l'arrivée confirmée par le client" +
                (if (extraCentimes > 0) " + " + Market.cfa(extraCentimes) + " de données livrées" else "") +
                (if (rendezvousCell.isNotEmpty()) " · rendez-vous zone " + rendezvousCell else "") +
                " · annulation avant départ : 0 · annulation par le client après départ : compensation partielle" + requirementsSuffix()
            "CARRY" -> Market.cfa(amountCentimes) + " pour la session complète · gagné seulement sur les octets vérifiés au règlement signé" + requirementsSuffix()
            else -> Market.cfa(amountCentimes)
        }

        private fun requirementsSuffix(): String =
            (if (batteryFloorPct > 0) " · batterie ≥ " + batteryFloorPct + " %" else "") +
                (if (minPayoutCentimes > 0) " · votre minimum " + Market.cfa(minPayoutCentimes) else "")

        fun timeLine(now: Long): String {
            if (!open) return ""
            val left = expiresAt - now
            return if (left <= 0) "Expiré" else "Expire dans " + ((left + 59_999) / 60_000) + " min"
        }

        /** The running block, as the Brain counts it; a failed one says why. */
        fun blockProgress(now: Long): String = when {
            kind != "STAY" -> ""
            state == "IN_PROGRESS" -> {
                val elapsed = if (blockStartedAt > 0) (now - blockStartedAt).coerceAtLeast(0) else blockElapsedMs
                "Bloc : " + (elapsed / 60_000).coerceAtMost(blockMinutes.toLong()) + " / " + blockMinutes + " min · sondes " +
                    probesOk + " réussie" + (if (probesOk == 1) "" else "s") + ", " + probesFailed + " échouée" + (if (probesFailed == 1) "" else "s")
            }
            state == "FAILED" -> "Non vérifié" + (if (failReason.isNotEmpty()) " : " + failReason else "")
            state == "COMPLETED" -> "Bloc vérifié : " + Market.cfa(earnedCentimes) + " gagnés"
            else -> ""
        }

        /** The move, step by step, so the relay knows which step earns. */
        fun moveProgress(): String = when {
            kind != "MOVE" -> ""
            state == "COMPLETED" -> "Arrivée et disponibilité confirmées par le client : " + Market.cfa(earnedCentimes) + " gagnés"
            state == "CANCELLED" -> "Annulé" + (if (compensationCentimes > 0) " après départ : compensation " + Market.cfa(compensationCentimes) else " : rien gagné")
            readyAt > 0 -> "Prêt déclaré — en attente de la confirmation du client (c'est elle qui fait gagner)"
            arrivedAt > 0 -> "Arrivé — déclarez-vous prêt"
            departedAt > 0 -> "En route"
            state == "ACCEPTED" -> "Accepté — partez quand vous êtes prêt"
            else -> ""
        }

        /** The pipeline with this offer's stage marked; withdrawal stages are the wallet's. */
        fun pipelineLine(): String = pipelineText(pipelineStage)
    }

    /** "[Contrat en attente] → Vérifié → Gagné → Retrait demandé → Payé" with the current stage in brackets. */
    fun pipelineText(stage: Int): String =
        PIPELINE.mapIndexed { i, s -> if (i == stage) "[" + s + "]" else s }.joinToString(" → ")

    /** What the volunteer controls: minimum payout, battery floor, availability window, stop. Never a tariff. */
    class Settings(val minPayoutCentimes: Long, val batteryFloorPct: Int, val windowStartMin: Int, val windowEndMin: Int, val available: Boolean) {
        fun describe(): String =
            (if (available) "Disponible " else "Indisponible (arrêt demandé) ") + hhmm(windowStartMin) + "–" + hhmm(windowEndMin) +
                " · payout minimum " + Market.cfa(minPayoutCentimes) + " · batterie ≥ " + batteryFloorPct + " %"
        companion object {
            val DEFAULT = Settings(0, 20, 0, 1440, true)
            fun hhmm(min: Int): String = (min / 60).toString().padStart(2, '0') + ":" + (min % 60).toString().padStart(2, '0')
            /** "08:30" -> 510; rubbish -> null. */
            fun parseHhmm(s: String): Int? {
                val p = s.trim().split(':', 'h', 'H')
                if (p.isEmpty() || p[0].isBlank()) return null
                val h = p[0].trim().toIntOrNull() ?: return null
                val m = if (p.size > 1 && p[1].isNotBlank()) (p[1].trim().toIntOrNull() ?: return null) else 0
                if (h !in 0..24 || m !in 0..59) return null
                return (h * 60 + m).coerceAtMost(1440)
            }
        }
    }

    class Pipeline(val stages: List<String>, val pendingCentimes: Long, val earnedCentimes: Long)

    class Mine(val offers: List<Offer>, val settings: Settings, val blocksToday: Int, val blocksPerDay: Int, val pipeline: Pipeline, val fetchedAt: Long) {
        val current: Offer? get() = offers.firstOrNull { it.blockRunning } ?: offers.firstOrNull { it.kind == "MOVE" && it.state == "IN_PROGRESS" }
        val capLine: String get() = "Blocs aujourd'hui : " + blocksToday + " / " + blocksPerDay

        /**
         * The earnings pipeline with the wallet's numbers joined in: what is pending (never cash),
         * what was verified and earned here, what the wallet says is withdrawable, requested, paid.
         */
        fun earningsLines(wallet: LedgerView.View?): String {
            val lines = ArrayList<String>()
            lines += PIPELINE[0] + " : " + Market.cfa(pipeline.pendingCentimes) + " (pas encore de l'argent)"
            lines += PIPELINE[1] + " → " + PIPELINE[2] + " : " + Market.cfa(pipeline.earnedCentimes)
            if (wallet != null) {
                lines += "Retirable : " + Market.cfa(wallet.withdrawableCentimes)
                val w = wallet.withdrawal
                if (w != null && w.open) lines += PIPELINE[3] + " : " + Market.cfa(w.amountCentimes) + " · " + w.text
                if (w != null && w.state == "PAID") lines += PIPELINE[4] + " : " + Market.cfa(w.amountCentimes)
            }
            return lines.joinToString("\n")
        }
    }

    // ---- parsing -------------------------------------------------------------------------

    fun parse(text: String, now: Long): Mine? {
        if (text.isBlank() || !text.contains("\"offers\"")) return null
        val offers = LedgerView.objects(text, "offers").mapNotNull { parseOffer(it) }
        val settings = LedgerView.obj(text, "settings")?.let { parseSettings(it) } ?: Settings.DEFAULT
        val p = LedgerView.obj(text, "pipeline")
        val pipeline = Pipeline(PIPELINE, p?.let { LedgerView.num(it, "pending_centimes") } ?: 0L, p?.let { LedgerView.num(it, "earned_centimes") } ?: 0L)
        return Mine(offers, settings, LedgerView.num(text, "blocks_today").toInt(),
            LedgerView.num(text, "blocks_per_day").toInt().let { if (it == 0) 4 else it }, pipeline, now)
    }

    fun parseOffer(s: String): Offer? {
        val id = BrainPayload.field(s, "id")
        if (id.isEmpty()) return null
        val n = { k: String -> LedgerView.num(s, k) }
        return Offer(
            id = id, kind = BrainPayload.field(s, "kind"), zone = BrainPayload.field(s, "zone"), amountCentimes = n("amount"),
            extraCentimes = n("extra"), state = BrainPayload.field(s, "state"), createdAt = n("created_at"), acceptedAt = n("accepted_at"),
            expiresAt = n("expires_at"), endedAt = n("ended_at"), earnedCentimes = n("earned"), batteryFloorPct = n("battery_floor_pct").toInt(),
            minPayoutCentimes = n("min_payout_centimes"), windowStartMin = n("window_start_min").toInt(), windowEndMin = n("window_end_min").toInt(),
            blockMinutes = n("block_minutes").toInt().let { if (it == 0) 30 else it }, blocksPerDay = n("blocks_per_day").toInt().let { if (it == 0) 4 else it },
            probesRequired = n("probes_required").toInt().let { if (it == 0) 3 else it }, probesMaxFailed = n("probes_max_failed").toInt(),
            blockStartedAt = n("block_started_at"), blockElapsedMs = n("block_elapsed_ms"), probesOk = n("probes_ok").toInt(),
            probesFailed = n("probes_failed").toInt(), blockState = BrainPayload.field(s, "block_state"), failReason = BrainPayload.field(s, "fail_reason"),
            rendezvousCell = BrainPayload.field(s, "rendezvous_cell"), departedAt = n("departed_at"), arrivedAt = n("arrived_at"),
            readyAt = n("ready_at"), confirmedAt = n("confirmed_at"), compensationCentimes = n("compensation"),
            pipelineStage = BrainPayload.field(s, "pipeline_stage").toIntOrNull() ?: -1,
        )
    }

    fun parseSettings(s: String): Settings = Settings(
        minPayoutCentimes = LedgerView.num(s, "min_payout_centimes"),
        batteryFloorPct = LedgerView.num(s, "battery_floor_pct").toInt(),
        windowStartMin = LedgerView.num(s, "window_start_min").toInt(),
        windowEndMin = LedgerView.num(s, "window_end_min").toInt().let { if (it == 0 && !s.contains("\"window_end_min\"")) 1440 else it },
        available = BrainPayload.field(s, "available") != "false",
    )
}
