package net.prok.proknet.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
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
        fill(R.id.prVenues, venues, "aucun lieu en attente") { o -> venueRow(o) }
        fill(R.id.prClaims, claims, "aucune demande") { o -> claimRow(o) }
        fill(R.id.prReports, reports, "aucun signalement") { o -> reportRow(o) }
        fill(R.id.prAppeals, appeals, "aucune contestation") { o -> appealRow(o) }
    }

    private fun fill(id: Int, items: List<String>, empty: String, row: (String) -> LinearLayout) {
        val box = findViewById<LinearLayout>(id); box.removeAllViews()
        if (items.isEmpty()) { box.addView(TextView(this).apply { textSize = 13f; text = empty }); return }
        for (o in items) box.addView(row(o))
    }

    private fun venueRow(o: String): LinearLayout {
        val id = BrainPayload.field(o, "id"); val state = BrainPayload.field(o, "state")
        val blockers = Regex("\"blockers\"\\s*:\\s*\\[([^\\]]*)\\]").find(o)?.groupValues?.get(1)?.replace("\"", "")?.trim().orEmpty()
        val box = column()
        box.addView(TextView(this).apply {
            textSize = 15f
            text = BrainPayload.field(o, "name").ifEmpty { "(sans nom)" } + " · " + BrainPayload.field(o, "neighbourhood") + " · " + BrainPayload.field(o, "kind") +
                "\nÉtat " + state + " · " + PlacesView.accessText(BrainPayload.field(o, "access_rule")) +
                "\nEntrée " + (if (LedgerView.num(o, "lat") != 0L || o.contains("\"lat\": -") || o.contains("\"lat\":-")) "renseignée" else "MANQUANTE") +
                " · consentement " + (if (BrainPayload.field(o, "consent_doc").isNotEmpty()) "enregistré" else "aucun") +
                " · soumis par prok-" + BrainPayload.field(o, "submitted_by").take(8) +
                (if (blockers.isNotEmpty()) "\nBloque la publication : " + blockers else "") +
                "\nStatut : " + PlacesView.statusText(BrainPayload.field(o, "status")) + " · rapports ouverts " + LedgerView.num(o, "open_reports")
        })
        val b = row()
        b.addView(Button(this).apply { text = "Fonctionne"; setOnClickListener { noteDialog("Vérifié sur place : fonctionne") { n -> run { places.opsCheck(id, true, n) } } } })
        b.addView(Button(this).apply { text = "Indispo"; setOnClickListener { noteDialog("Vérifié sur place : indisponible") { n -> run { places.opsCheck(id, false, n) } } } })
        if (state != "PUBLISHED") b.addView(Button(this).apply { text = "Publier"; setOnClickListener { run { places.opsPublish(id) } } })
        if (state != "HIDDEN") b.addView(Button(this).apply { text = "Masquer"; setOnClickListener { noteDialog("Motif du masquage") { n -> run { places.opsHide(id, n) } } } })
        b.addView(Button(this).apply { text = "Retirer"; setOnClickListener { noteDialog("Motif du retrait (définitif)") { n -> run { places.opsRemove(id, n) } } } })
        box.addView(b); return box
    }

    private fun claimRow(o: String): LinearLayout {
        val id = BrainPayload.field(o, "id")
        val box = column()
        box.addView(TextView(this).apply {
            textSize = 15f
            text = BrainPayload.field(o, "kind") + " · lieu " + BrainPayload.field(o, "venue_id").take(8) + " · par prok-" + BrainPayload.field(o, "claimant_id").take(8) +
                "\n" + BrainPayload.field(o, "text") + "\nUne demande n'est jamais acceptée sur un simple numéro : exigez un justificatif hors de l'application."
        })
        val b = row()
        b.addView(Button(this).apply { text = "Accepter"; setOnClickListener { noteDialog("Justificatif vu (document, appel, visite)") { n -> run { places.opsClaim(id, true, n) } } } })
        b.addView(Button(this).apply { text = "Refuser"; setOnClickListener { noteDialog("Motif du refus") { n -> run { places.opsClaim(id, false, n) } } } })
        box.addView(b); return box
    }

    private fun reportRow(o: String): LinearLayout {
        val id = BrainPayload.field(o, "id")
        val box = column()
        box.addView(TextView(this).apply {
            textSize = 15f
            text = BrainPayload.field(o, "kind") + " · lieu " + BrainPayload.field(o, "venue_id").take(8) + " · par prok-" + BrainPayload.field(o, "reporter_id").take(8) +
                "\n" + BrainPayload.field(o, "text")
        })
        val b = row()
        b.addView(Button(this).apply { text = "Confirmer"; setOnClickListener { noteDialog("Confirmé : le lieu passe indisponible/masqué") { n -> run { places.opsReport(id, true, n) } } } })
        b.addView(Button(this).apply { text = "Écarter"; setOnClickListener { noteDialog("Motif") { n -> run { places.opsReport(id, false, n) } } } })
        box.addView(b); return box
    }

    private fun appealRow(o: String): LinearLayout {
        val id = BrainPayload.field(o, "id")
        val box = column()
        box.addView(TextView(this).apply {
            textSize = 15f
            text = "Récompense " + BrainPayload.field(o, "kind") + " " + Market.cfa(LedgerView.num(o, "amount_centimes")) + " · scout prok-" + BrainPayload.field(o, "scout_id").take(8) +
                "\nRefusée : " + BrainPayload.field(o, "reason") + "\nContestation : " + BrainPayload.field(o, "appeal")
        })
        val b = row()
        b.addView(Button(this).apply { text = "Accorder"; setOnClickListener { noteDialog("Motif") { n -> run { places.opsReward(id, true, n) } } } })
        b.addView(Button(this).apply { text = "Maintenir le refus"; setOnClickListener { noteDialog("Motif") { n -> run { places.opsReward(id, false, n) } } } })
        box.addView(b); return box
    }

    private fun noteDialog(title: String, then: (String) -> Unit) {
        val e = EditText(this).apply { hint = "Note (journalisée)" }
        AlertDialog.Builder(this).setTitle(title).setView(e).setPositiveButton("Confirmer") { _, _ -> then(e.text.toString().trim()) }.setNegativeButton("Annuler", null).show()
    }

    private fun run(block: () -> PlacesSync.Outcome) {
        io.execute {
            val out = try { block() } catch (e: Exception) { PlacesSync.Outcome(false, e.message ?: "échec") }
            runOnUiThread { Toast.makeText(this, out.message, Toast.LENGTH_SHORT).show(); load() }
        }
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 12, 0, 12) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun text(id: Int, s: String) { findViewById<TextView>(id).text = s }
}
