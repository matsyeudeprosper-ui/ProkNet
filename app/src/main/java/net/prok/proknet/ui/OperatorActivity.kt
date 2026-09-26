package net.prok.proknet.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
import net.prok.proknet.core.FlagsView
import net.prok.proknet.core.LedgerView

/**
 * v0.19.0: the operator console. Reachable from the Lab screen only when the Brain has said
 * this identity is an operator; every action is a signed request the Brain audits and may
 * refuse. Switches are dated decisions with a reason; restricted functions need a
 * recorded external decision first, and the console says which one is missing. The work
 * queues (venue verification, market moderation, invoice confirmation, treasury) open
 * their own screens.
 */
class OperatorActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val node by lazy { ProkNetApp.node(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_operator)
        findViewById<android.view.View>(R.id.hdBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnOpRefresh).setOnClickListener { load() }
        findViewById<Button>(R.id.btnOpDecision).setOnClickListener { decisionDialog() }
        findViewById<Button>(R.id.btnOpCohort).setOnClickListener { cohortDialog() }
    }

    override fun onResume() { super.onResume(); load() }

    private fun load() {
        io.execute {
            node.flagsSync.run()
            val console = node.flagsSync.console()
            runOnUiThread { render(node.flags, console) }
        }
    }

    private fun render(s: FlagsView.Status, console: String?) {
        text(R.id.opTitle, "Console opérateur — " + node.city)
        if (!s.operator) {
            text(R.id.opNote, "Le Brain ne reconnaît pas cette identité comme opérateur (PROK_OPERATOR_IDS)."); return
        }
        val box = findViewById<LinearLayout>(R.id.opFunctions); box.removeAllViews()
        for (name in FlagsView.FUNCTIONS) {
            val f = s.functions[name]
            val on = f?.switchOn == true
            val enabled = f?.enabled == true
            val card = Prok.card(this)
            card.addView(Prok.titleRow(this, FlagsView.label(name), if (enabled) "ouverte" else if (on) "interrupteur on, bloquée" else "fermée", if (enabled) Prok.Tone.OK else if (on) Prok.Tone.WARN else Prok.Tone.MUTED))
            card.addView(Prok.muted(this, s.why(name)))
            card.addView(Prok.actions(this, if (on) Prok.secondary(this, "Fermer") { flagDialog(name, false) } else Prok.primary(this, "Ouvrir") { flagDialog(name, true) }))
            box.addView(card)
        }
        val decisions = if (console == null) "(console injoignable)" else LedgerView.objects(console, "decisions").joinToString("\n\n") { d ->
            BrainPayload.field(d, "kind") + " · " + BrainPayload.field(d, "summary") + "\n" + BrainPayload.field(d, "document") + " · " +
                BrainPayload.field(d, "accountable") + (if (BrainPayload.field(d, "revoked_at") != "0") " · RÉVOQUÉE" else "")
        }.ifEmpty { "Aucune décision enregistrée." }
        text(R.id.opDecisions, decisions)
        val queues = findViewById<LinearLayout>(R.id.opQueues); queues.removeAllViews()
        queues.addView(Prok.link(this, "Trésorerie", "Retraits, recharges, rapprochement", R.drawable.ic_wallet) { startActivity(Intent(this@OperatorActivity, TreasuryActivity::class.java)) })
        for ((label, sub, icon, cls) in QUEUE_SCREENS) {
            val c = try { Class.forName(cls) } catch (e: Exception) { null }
            if (c != null) queues.addView(Prok.link(this, label, sub, icon) { startActivity(Intent(this@OperatorActivity, c)) })
        }
        text(R.id.opFooter, "Identité : prok-" + node.identity.shortIdHex + " · dernier état " + (if (s.fetchedAt > 0) "reçu" else "jamais reçu"))
    }

    private fun form(vararg fields: android.view.View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; val p = Prok.dp(context, 20); setPadding(p, Prok.dp(context, 8), p, 0)
        for (f in fields) addView(f, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Prok.dp(context, 8) })
    }

    private fun flagDialog(function: String, enable: Boolean) {
        val reason = EditText(this).apply { hint = "Motif (obligatoire) — c'est une décision datée" }
        AlertDialog.Builder(this).setTitle((if (enable) "Ouvrir " else "Fermer ") + FlagsView.label(function))
            .setMessage(if (enable) "Une fonction restreinte n'ouvre qu'avec les décisions externes enregistrées. Les obligations déjà acceptées continuent quoi qu'il arrive." else "Fermer arrête les NOUVELLES sessions/annonces ; les obligations acceptées sont honorées.")
            .setView(form(reason))
            .setPositiveButton("Confirmer") { _, _ -> run { node.flagsSync.setFlag(function, enable, reason.text.toString().trim()) } }
            .setNegativeButton("Annuler", null).show()
    }

    private fun cohortDialog() {
        val fn = EditText(this).apply { hint = "fonction (ex. market_paid_publish)" }
        val id = EditText(this).apply { hint = "identité complète (32 hex)" }
        AlertDialog.Builder(this).setTitle("Cohorte").setView(form(fn, id))
            .setPositiveButton("Ajouter") { _, _ -> run { node.flagsSync.cohort(fn.text.toString().trim(), id.text.toString().trim(), true) } }
            .setNeutralButton("Retirer") { _, _ -> run { node.flagsSync.cohort(fn.text.toString().trim(), id.text.toString().trim(), false) } }
            .setNegativeButton("Annuler", null).show()
    }

    private fun decisionDialog() {
        val kind = EditText(this).apply { hint = "kind: payment_classification | upstream_permission | merchant_collection_terms | data_protection | arpce" }
        val summary = EditText(this).apply { hint = "Résumé de la décision" }
        val document = EditText(this).apply { hint = "Document (nom/référence, version)" }
        val who = EditText(this).apply { hint = "Personne responsable" }
        AlertDialog.Builder(this).setTitle("Enregistrer une décision externe")
            .setMessage("Le fait externe qu'aucun ingénieur ne peut fabriquer : ce qui a été décidé, le document, la personne, la date (aujourd'hui).").setView(form(kind, summary, document, who))
            .setPositiveButton("Enregistrer") { _, _ ->
                run { node.flagsSync.decision(kind.text.toString().trim(), summary.text.toString().trim(), document.text.toString().trim(), who.text.toString().trim(), System.currentTimeMillis()) }
            }.setNegativeButton("Annuler", null).show()
    }

    private fun run(block: () -> net.prok.proknet.node.LedgerSync.Outcome) {
        io.execute {
            val out = try { block() } catch (e: Exception) { net.prok.proknet.node.LedgerSync.Outcome(false, e.message ?: "échec") }
            runOnUiThread { Toast.makeText(this, out.message, Toast.LENGTH_SHORT).show(); load() }
        }
    }

    private fun text(id: Int, s: String) { findViewById<TextView>(id).text = s }

    companion object {
        /** Work-queue screens other modules provide; shown only when the class exists in this build. */
        data class Queue(val label: String, val sub: String, val icon: Int, val cls: String)
        val QUEUE_SCREENS = listOf(
            Queue("Vérification des lieux", "Brouillons, demandes, signalements, contestations", R.drawable.ic_place, "net.prok.proknet.ui.PlacesReviewActivity"),
            Queue("Modération Prok Market", "Relecture, signalements, factures ouvertes", R.drawable.ic_bag, "net.prok.proknet.ui.MarketModerationActivity"),
            Queue("Offres relais", "Ce que ce téléphone voit comme relais", R.drawable.ic_bolt, "net.prok.proknet.ui.RelayOffersActivity"),
        )
    }
}
