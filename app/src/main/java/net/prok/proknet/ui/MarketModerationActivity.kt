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
import net.prok.proknet.core.MarketView
import net.prok.proknet.node.MarketSync

/**
 * v0.19.0: Prok Market moderation (contract §5) and the market half of the treasury.
 * Operator: the paid listings awaiting a human eye (approve publishes; reject never leaves
 * the seller charged) and the open reports (restore / reject / dismiss). Treasury: the OPEN
 * invoices with their unique references, and "paste a message" - the Brain matches a
 * reference and an exact amount or sends the line to review; the screen never decides.
 */
class MarketModerationActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val node by lazy { ProkNetApp.node(this) }
    private val market by lazy { MarketSync(node.identity) { node.brainUrlProvider?.invoke() ?: "" } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_market_moderation)
        Prok.header(this, "Modération", "Prok Market · relecture, signalements, factures")
        findViewById<Button>(R.id.btnMmRefresh).setOnClickListener { load() }
        findViewById<Button>(R.id.btnMmMatch).setOnClickListener { matchDialog() }
    }

    override fun onResume() { super.onResume(); load() }

    private fun load() {
        io.execute {
            val queue = market.operatorQueue(); val qErr = market.lastError
            val reports = market.operatorReports()
            val invoices = market.treasuryInvoices(); val tErr = market.lastError
            runOnUiThread { render(queue, qErr, reports, invoices, tErr) }
        }
    }

    private fun render(queue: List<MarketView.Listing>?, qErr: String, reports: String?, invoices: List<MarketView.Invoice>?, tErr: String) {
        val now = System.currentTimeMillis()
        text(R.id.mmSummary, if (queue == null) "File de relecture injoignable ou refusée : " + qErr.ifEmpty { "identité non opérateur" }
            else queue.size.toString() + " annonce(s) à relire · " + (reports?.let { LedgerView.objects(it, "reports").size } ?: 0) + " signalement(s)")
        val qb = findViewById<LinearLayout>(R.id.mmQueue); qb.removeAllViews()
        if (queue.isNullOrEmpty()) qb.addView(Prok.empty(this, "Rien à relire."))
        else for (l in queue) qb.addView(listingRow(l))
        val rb = findViewById<LinearLayout>(R.id.mmReports); rb.removeAllViews()
        val rs = reports?.let { LedgerView.objects(it, "reports") }.orEmpty()
        if (rs.isEmpty()) rb.addView(Prok.empty(this, "Aucun signalement."))
        else for (r in rs) rb.addView(reportRow(r))
        val ib = findViewById<LinearLayout>(R.id.mmInvoices); ib.removeAllViews()
        if (invoices == null) ib.addView(Prok.empty(this, "Trésorerie : " + tErr.ifEmpty { "identité non trésorerie" }))
        else if (invoices.isEmpty()) ib.addView(Prok.empty(this, "Aucune facture ouverte."))
        else for (i in invoices) ib.addView(invoiceRow(i, now))
    }

    private fun listingRow(l: MarketView.Listing): View {
        val c = this
        val card = Prok.card(c)
        card.addView(Prok.titleRow(c, l.rowTitle, l.stateText, Prok.Tone.WARN))
        card.addView(Prok.stat(c, l.priceText))
        card.addView(Prok.muted(c, MarketView.categoryName(l.category) + " · " + MarketView.conditionName(l.condition) + " · " + l.neighbourhood + " · " + l.photoUrls.size + " photo(s)", 2))
        card.addView(Prok.body(c, l.description.take(300) + (if (l.description.length > 300) "…" else ""), 10))
        card.addView(Prok.muted(c, "Vendeur prok-" + l.sellerId.take(8) + (if (l.sellerVerified) " · téléphone vérifié" else " · non vérifié") + " · " + l.sellerPublished + " publiée(s)" +
            (if (l.paidBy.isNotEmpty()) " · payé par " + l.paidBy else "") + (if (l.reviewNote.isNotEmpty()) "\nNote : " + l.reviewNote else ""), 8))
        card.addView(Prok.actions(c,
            Prok.primary(c, "Publier") { noteDialog("Publier « " + l.title + " »") { n -> run { market.review(l.id, true, n) } } },
            Prok.secondary(c, "Refuser") { noteDialog("Motif du refus (visible par le vendeur)") { n -> run { market.review(l.id, false, n) } } },
            Prok.ghost(c, "Bloquer le vendeur", danger = true) { confirm("Bloquer prok-" + l.sellerId.take(8) + " ? Ses annonces ne seront plus publiées.") { run { market.blockSeller(l.sellerId, true) } } }))
        return card
    }

    private fun reportRow(r: String): View {
        val c = this
        val id = BrainPayload.field(r, "id")
        val target = BrainPayload.field(r, "listing_id").takeIf { it.isNotEmpty() }?.let { "annonce " + it.take(8) }
            ?: BrainPayload.field(r, "user_id").takeIf { it.isNotEmpty() }?.let { "personne prok-" + it.take(8) } ?: ""
        val card = Prok.card(c)
        card.addView(Prok.titleRow(c, "Signalement · " + BrainPayload.field(r, "kind"), target, Prok.Tone.WARN))
        card.addView(Prok.body(c, BrainPayload.field(r, "text").ifEmpty { "(sans texte)" }))
        card.addView(Prok.muted(c, "Par prok-" + BrainPayload.field(r, "reporter").take(8)))
        card.addView(Prok.actions(c,
            Prok.primary(c, "Rétablir") { run { market.resolveReport(id, "restore") } },
            Prok.secondary(c, "Retirer l'annonce") { confirm("Retirer définitivement l'annonce signalée ?") { run { market.resolveReport(id, "reject") } } },
            Prok.ghost(c, "Écarter") { run { market.resolveReport(id, "dismiss") } }))
        return card
    }

    private fun invoiceRow(i: MarketView.Invoice, now: Long): View {
        val c = this
        val card = Prok.card(c)
        card.addView(Prok.titleRow(c, "Réf. " + i.reference, i.text, Prok.Tone.BRAND))
        card.addView(Prok.stat(c, MarketView.fcfa(i.amountCentimes)))
        card.addView(Prok.muted(c, i.service + (if (i.rail.isNotEmpty() && i.rail != "ANY") " · " + i.rail else "") + " · expire dans " + i.hoursLeft(now) + " h", 2))
        card.addView(Prok.actions(c,
            Prok.primary(c, "Confirmer le paiement") { confirmDialog(i) },
            Prok.ghost(c, "Rembourser") { noteDialog("Preuve du remboursement manuel (référence opérateur)") { n -> run { market.treasuryRefund(i.id, n) } } }))
        return card
    }

    private fun form(vararg fields: View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; val p = Prok.dp(context, 20); setPadding(p, Prok.dp(context, 8), p, 0)
        for (f in fields) addView(f, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Prok.dp(context, 8) })
    }

    private fun confirmDialog(i: MarketView.Invoice) {
        val txn = EditText(this).apply { hint = "Identifiant de transaction opérateur (unique)" }
        val amount = EditText(this).apply { hint = "Montant vu (CFA)"; setText((i.amountCentimes / 100).toString()) }
        val rail = EditText(this).apply { hint = "MTN ou AIRTEL"; setText(if (i.rail == "ANY") "" else i.rail) }
        val evidence = EditText(this).apply { hint = "Preuve (ligne du relevé, capture)" }
        AlertDialog.Builder(this).setTitle("Confirmer la réf. " + i.reference)
            .setMessage("Action MANUELLE : vous avez vu ce transfert dans l'application MoMo / Airtel Money. Le Brain refuse si le montant diffère ou si l'identifiant a déjà payé quelque chose.")
            .setView(form(txn, amount, rail, evidence))
            .setPositiveButton("Confirmer") { _, _ ->
                val cfa = amount.text.toString().filter { it.isDigit() }.toLongOrNull() ?: 0L
                run { market.treasuryConfirm(i.id, txn.text.toString().trim(), cfa * 100, "", rail.text.toString().trim().uppercase(), evidence.text.toString().trim()) }
            }.setNegativeButton("Annuler", null).show()
    }

    private fun matchDialog() {
        val msg = EditText(this).apply { hint = "Collez le SMS / la notification de l'opérateur"; minLines = 3 }
        val txn = EditText(this).apply { hint = "Identifiant de transaction (si absent du message)" }
        val rail = EditText(this).apply { hint = "MTN ou AIRTEL" }
        AlertDialog.Builder(this).setTitle("Rapprocher un message")
            .setMessage("Le Brain cherche une référence et un montant exact. Sans les deux, la ligne va en revue ; rien n'est attribué sur un simple numéro.")
            .setView(form(msg, txn, rail))
            .setPositiveButton("Analyser") { _, _ ->
                run { market.treasuryMatch(msg.text.toString(), 0, txn.text.toString().trim(), rail.text.toString().trim().uppercase()) }
            }.setNegativeButton("Annuler", null).show()
    }

    private fun noteDialog(title: String, then: (String) -> Unit) {
        val e = EditText(this).apply { hint = "Note (journalisée)" }
        AlertDialog.Builder(this).setTitle(title).setView(form(e)).setPositiveButton("Confirmer") { _, _ -> then(e.text.toString().trim()) }.setNegativeButton("Annuler", null).show()
    }

    private fun confirm(message: String, then: () -> Unit) {
        AlertDialog.Builder(this).setMessage(message).setPositiveButton("Oui") { _, _ -> then() }.setNegativeButton("Non", null).show()
    }

    private fun run(block: () -> MarketSync.Outcome) {
        io.execute {
            val out = try { block() } catch (e: Exception) { MarketSync.Outcome(false, e.message ?: "échec") }
            runOnUiThread {
                val extra = if (out.ok && out.text.contains("\"confirmed\"")) (if (out.text.contains("\"confirmed\": null") || out.text.contains("\"confirmed\":null")) " — aucune facture payée, ligne en revue" else " — facture payée") else ""
                Toast.makeText(this, out.message + extra, Toast.LENGTH_LONG).show(); load()
            }
        }
    }

    private fun text(id: Int, s: String) { findViewById<TextView>(id).text = s }
}
