package net.prok.proknet.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import net.prok.proknet.ProkNetApp
import net.prok.proknet.R
import net.prok.proknet.core.BrainPayload
import net.prok.proknet.core.LedgerView
import net.prok.proknet.core.Market
import net.prok.proknet.core.PlacesView
import net.prok.proknet.node.PlacesSync

/**
 * v0.19.0: the operator's queue for the free-Internet map (contract §3 moderation).
 * Four lists the Brain assembles for an operator identity only: venues not yet public
 * (draft / pending / hidden), owner claims, open reports, rejected-reward appeals. Every
 * button is one signed POST; the Brain decides, audits and answers with its own sentence.
 * A venue reaches the public map only through "Publier", and the Brain refuses that when
 * the entrance, the access rule or a private place's consent is missing.
 */
class PlacesReviewActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val node by lazy { ProkNetApp.node(this) }
    private val places by lazy { PlacesSync(node.identity, { node.brainUrlProvider?.invoke() ?: "" }, filesDir, node.city) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_places_review)
        Prok.header(this, "Vérification des lieux", "Rien n'est public sans une vérification et un accord")
        findViewById<Button>(R.id.btnPrRefresh).setOnClickListener { load() }
    }

    override fun onResume() { super.onResume(); load() }

    private fun load() {
        io.execute {
            val q = places.opsQueue()
            runOnUiThread { render(q) }
        }
    }

    private fun render(q: String?) {
        if (q == null) {
            text(R.id.prSummary, "File injoignable ou refusée : " + places.lastError.ifEmpty { "le Brain ne reconnaît pas cette identité comme opérateur" })
            for (id in listOf(R.id.prVenues, R.id.prClaims, R.id.prReports, R.id.prAppeals)) findViewById<LinearLayout>(id).removeAllViews()
            return
        }
        val venues = LedgerView.objects(q, "venues"); val claims = LedgerView.objects(q, "claims")
        val reports = LedgerView.objects(q, "reports"); val appeals = LedgerView.objects(q, "appeals")
        text(R.id.prSummary, venues.size.toString() + " lieu(x) · " + claims.size + " demande(s) · " + reports.size + " signalement(s) · " + appeals.size + " contestation(s)" +
            "\nRécompenses scout ce mois : " + Market.cfa(LedgerView.num(q, "issued_this_month_centimes")) +
            " · offre scout " + (if (BrainPayload.field(q, "offer_available") == "true") "disponible" else "fermée (plafond ou interrupteur)"))
        fill(R.id.prVenues, venues, "Aucun lieu en attente") { o -> venueRow(o) }
        fill(R.id.prClaims, claims, "Aucune demande") { o -> claimRow(o) }
        fill(R.id.prReports, reports, "Aucun signalement") { o -> reportRow(o) }
        fill(R.id.prAppeals, appeals, "Aucune contestation") { o -> appealRow(o) }
    }

    private fun fill(id: Int, items: List<String>, empty: String, row: (String) -> View) {
        val box = findViewById<LinearLayout>(id); box.removeAllViews()
        if (items.isEmpty()) { box.addView(Prok.empty(this, empty)); return }
        for (o in items) box.addView(row(o))
    }

    private fun stateTone(state: String): Prok.Tone = when (state) { "PUBLISHED" -> Prok.Tone.OK; "HIDDEN" -> Prok.Tone.DANGER; "PENDING_REVIEW" -> Prok.Tone.WARN; else -> Prok.Tone.MUTED }

    private fun venueRow(o: String): View {
        val c = this
        val id = BrainPayload.field(o, "id"); val state = BrainPayload.field(o, "state")
        val blockers = Regex("\"blockers\"\\s*:\\s*\\[([^\\]]*)\\]").find(o)?.groupValues?.get(1)?.replace("\"", "")?.trim().orEmpty()
        val hasEntrance = LedgerView.num(o, "lat") != 0L || o.contains("\"lat\": -") || o.contains("\"lat\":-")
        val card = Prok.card(c)
        card.addView(Prok.titleRow(c, BrainPayload.field(o, "name").ifEmpty { "(sans nom)" }, state, stateTone(state)))
        card.addView(Prok.muted(c, BrainPayload.field(o, "neighbourhood") + " · " + BrainPayload.field(o, "kind") + " · " + PlacesView.accessText(BrainPayload.field(o, "access_rule")), 2))
        card.addView(Prok.pills(c,
            (if (hasEntrance) "Entrée renseignée" else "Entrée manquante") to (if (hasEntrance) Prok.Tone.OK else Prok.Tone.WARN),
            (if (BrainPayload.field(o, "consent_doc").isNotEmpty()) "Consentement enregistré" else "Sans consentement") to (if (BrainPayload.field(o, "consent_doc").isNotEmpty()) Prok.Tone.OK else Prok.Tone.MUTED),
            PlacesView.statusText(BrainPayload.field(o, "status")) to Prok.Tone.MUTED))
        card.addView(Prok.muted(c, "Soumis par prok-" + BrainPayload.field(o, "submitted_by").take(8) + " · rapports ouverts " + LedgerView.num(o, "open_reports") +
            (if (blockers.isNotEmpty()) "\nBloque la publication : " + blockers else ""), 8))
        val buttons = ArrayList<View>()
        buttons += Prok.primary(c, "Fonctionne") { noteDialog("Vérifié sur place : fonctionne") { n -> run { places.opsCheck(id, true, n) } } }
        buttons += Prok.secondary(c, "Indisponible") { noteDialog("Vérifié sur place : indisponible") { n -> run { places.opsCheck(id, false, n) } } }
        if (state != "PUBLISHED") buttons += Prok.secondary(c, "Publier") { run { places.opsPublish(id) } }
        if (state != "HIDDEN") buttons += Prok.ghost(c, "Masquer") { noteDialog("Motif du masquage") { n -> run { places.opsHide(id, n) } } }
        buttons += Prok.ghost(c, "Retirer", danger = true) { noteDialog("Motif du retrait (définitif)") { n -> run { places.opsRemove(id, n) } } }
        card.addView(Prok.actions(c, *buttons.toTypedArray()))
        return card
    }

    private fun claimRow(o: String): View {
        val c = this
        val id = BrainPayload.field(o, "id")
        val card = Prok.card(c)
        card.addView(Prok.titleRow(c, "Demande · " + BrainPayload.field(o, "kind"), "lieu " + BrainPayload.field(o, "venue_id").take(8), Prok.Tone.MUTED))
        card.addView(Prok.body(c, BrainPayload.field(o, "text")))
        card.addView(Prok.muted(c, "Par prok-" + BrainPayload.field(o, "claimant_id").take(8) + " · une demande n'est jamais acceptée sur un simple numéro : exigez un justificatif hors de l'application."))
        card.addView(Prok.actions(c,
            Prok.primary(c, "Accepter") { noteDialog("Justificatif vu (document, appel, visite)") { n -> run { places.opsClaim(id, true, n) } } },
            Prok.secondary(c, "Refuser") { noteDialog("Motif du refus") { n -> run { places.opsClaim(id, false, n) } } }))
        return card
    }

    private fun reportRow(o: String): View {
        val c = this
        val id = BrainPayload.field(o, "id")
        val card = Prok.card(c)
        card.addView(Prok.titleRow(c, "Signalement · " + BrainPayload.field(o, "kind"), "lieu " + BrainPayload.field(o, "venue_id").take(8), Prok.Tone.WARN))
        card.addView(Prok.body(c, BrainPayload.field(o, "text").ifEmpty { "(sans texte)" }))
        card.addView(Prok.muted(c, "Par prok-" + BrainPayload.field(o, "reporter_id").take(8)))
        card.addView(Prok.actions(c,
            Prok.primary(c, "Confirmer") { noteDialog("Confirmé : le lieu passe indisponible ou masqué") { n -> run { places.opsReport(id, true, n) } } },
            Prok.secondary(c, "Écarter") { noteDialog("Motif") { n -> run { places.opsReport(id, false, n) } } }))
        return card
    }

    private fun appealRow(o: String): View {
        val c = this
        val id = BrainPayload.field(o, "id")
        val card = Prok.card(c)
        card.addView(Prok.titleRow(c, "Récompense " + BrainPayload.field(o, "kind") + " · " + Market.cfa(LedgerView.num(o, "amount_centimes")), "refusée", Prok.Tone.DANGER))
        card.addView(Prok.muted(c, "Scout prok-" + BrainPayload.field(o, "scout_id").take(8) + " · motif du refus : " + BrainPayload.field(o, "reason")))
        card.addView(Prok.body(c, "Contestation : " + BrainPayload.field(o, "appeal")))
        card.addView(Prok.actions(c,
            Prok.primary(c, "Accorder") { noteDialog("Motif") { n -> run { places.opsReward(id, true, n) } } },
            Prok.secondary(c, "Maintenir le refus") { noteDialog("Motif") { n -> run { places.opsReward(id, false, n) } } }))
        return card
    }

    private fun noteDialog(title: String, then: (String) -> Unit) {
        val e = EditText(this).apply { hint = "Note (journalisée)" }
        val col = LinearLayout(this).apply { val p = Prok.dp(context, 20); setPadding(p, Prok.dp(context, 8), p, 0); addView(e) }
        AlertDialog.Builder(this).setTitle(title).setView(col).setPositiveButton("Confirmer") { _, _ -> then(e.text.toString().trim()) }.setNegativeButton("Annuler", null).show()
    }

    private fun run(block: () -> PlacesSync.Outcome) {
        io.execute {
            val out = try { block() } catch (e: Exception) { PlacesSync.Outcome(false, e.message ?: "échec") }
            runOnUiThread { Toast.makeText(this, out.message, Toast.LENGTH_SHORT).show(); load() }
        }
    }

    private fun text(id: Int, s: String) { findViewById<TextView>(id).text = s }
}
