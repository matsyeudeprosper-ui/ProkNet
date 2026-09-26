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
        if (queue.isNullOrEmpty()) qb.addView(TextView(this).apply { textSize = 13f; text = "rien à relire" })
        else for (l in queue) qb.addView(listingRow(l, now))
        val rb = findViewById<LinearLayout>(R.id.mmReports); rb.removeAllViews()
        val rs = reports?.let { LedgerView.objects(it, "reports") }.orEmpty()
        if (rs.isEmpty()) rb.addView(TextView(this).apply { textSize = 13f; text = "aucun signalement" })
        else for (r in rs) rb.addView(reportRow(r))
        val ib = findViewById<LinearLayout>(R.id.mmInvoices); ib.removeAllViews()
        if (invoices == null) ib.addView(TextView(this).apply { textSize = 13f; text = "Trésorerie : " + tErr.ifEmpty { "identité non trésorerie" } })
        else if (invoices.isEmpty()) ib.addView(TextView(this).apply { textSize = 13f; text = "aucune facture ouverte" })
        else for (i in invoices) ib.addView(invoiceRow(i, now))
    }

    private fun listingRow(l: MarketView.Listing, now: Long): LinearLayout {
        val box = column()
        box.addView(TextView(this).apply {
            textSize = 15f
            text = l.rowTitle + " · " + l.priceText + " · " + MarketView.categoryName(l.category) + " · " + MarketView.conditionName(l.condition) +
                "\n" + l.description.take(300) + (if (l.description.length > 300) "…" else "") +
                "\nVendeur prok-" + l.sellerId.take(8) + (if (l.sellerVerified) " (téléphone vérifié)" else " (non vérifié)") + " · " + l.sellerPublished + " publiée(s)" +
                " · " + l.neighbourhood + " · photos " + l.photoUrls.size + " · état " + l.stateText +
                (if (l.reviewNote.isNotEmpty()) "\nNote : " + l.reviewNote else "") + (if (l.paidBy.isNotEmpty()) "\nPayé par : " + l.paidBy else "")
        })
        val b = row()
        b.addView(Button(this).apply { text = "Publier"; setOnClickListener { noteDialog("Publier « " + l.title + " »") { n -> run { market.review(l.id, true, n) } } } })
        b.addView(Button(this).apply { text = "Refuser"; setOnClickListener { noteDialog("Motif du refus (visible par le vendeur)") { n -> run { market.review(l.id, false, n) } } } })
        b.addView(Button(this).apply { text = "Bloquer vendeur"; setOnClickListener { confirm("Bloquer prok-" + l.sellerId.take(8) + " ? Ses annonces ne seront plus publiées.") { run { market.blockSeller(l.sellerId, true) } } } })
        box.addView(b); return box
    }

    private fun reportRow(r: String): LinearLayout {
        val id = BrainPayload.field(r, "id")
        val box = column()
        box.addView(TextView(this).apply {
            textSize = 15f
            text = BrainPayload.field(r, "kind") + (BrainPayload.field(r, "listing_id").takeIf { it.isNotEmpty() }?.let { " · annonce " + it.take(8) } ?: "") +
                (BrainPayload.field(r, "user_id").takeIf { it.isNotEmpty() }?.let { " · personne prok-" + it.take(8) } ?: "") +
                " · par prok-" + BrainPayload.field(r, "reporter").take(8) + "\n" + BrainPayload.field(r, "text")
        })
        val b = row()
        b.addView(Button(this).apply { text = "Rétablir"; setOnClickListener { run { market.resolveReport(id, "restore") } } })
        b.addView(Button(this).apply { text = "Retirer l'annonce"; setOnClickListener { confirm("Retirer définitivement l'annonce signalée ?") { run { market.resolveReport(id, "reject") } } } })
        b.addView(Button(this).apply { text = "Écarter"; setOnClickListener { run { market.resolveReport(id, "dismiss") } } })
        box.addView(b); return box
    }

    private fun invoiceRow(i: MarketView.Invoice, now: Long): LinearLayout {
        val box = column()
        box.addView(TextView(this).apply {
            textSize = 15f
            text = "Réf. " + i.reference + " · " + MarketView.fcfa(i.amountCentimes) + " · " + i.service + (if (i.rail.isNotEmpty() && i.rail != "ANY") " · " + i.rail else "") +
                " · " + i.text + " · expire dans " + i.hoursLeft(now) + " h"
        })
        val b = row()
        b.addView(Button(this).apply { text = "Confirmer paiement"; setOnClickListener { confirmDialog(i) } })
        b.addView(Button(this).apply { text = "Rembourser"; setOnClickListener { noteDialog("Preuve du remboursement manuel (référence opérateur)") { n -> run { market.treasuryRefund(i.id, n) } } } })
        box.addView(b); return box
    }

    private fun confirmDialog(i: MarketView.Invoice) {
        val txn = EditText(this).apply { hint = "Identifiant de transaction opérateur (obligatoire, unique)" }
        val amount = EditText(this).apply { hint = "Montant vu (CFA)"; setText((i.amountCentimes / 100).toString()) }
        val rail = EditText(this).apply { hint = "MTN ou AIRTEL"; setText(if (i.rail == "ANY") "" else i.rail) }
        val evidence = EditText(this).apply { hint = "Preuve (ligne du relevé, capture)" }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(txn); addView(amount); addView(rail); addView(evidence) }
        AlertDialog.Builder(this).setTitle("Confirmer la réf. " + i.reference)
            .setMessage("Action MANUELLE : vous avez vu ce transfert dans l'application MoMo / Airtel Money. Le Brain refuse si le montant diffère ou si l'identifiant a déjà payé quelque chose.")
            .setView(col)
            .setPositiveButton("Confirmer") { _, _ ->
                val cfa = amount.text.toString().filter { it.isDigit() }.toLongOrNull() ?: 0L
                run { market.treasuryConfirm(i.id, txn.text.toString().trim(), cfa * 100, "", rail.text.toString().trim().uppercase(), evidence.text.toString().trim()) }
            }.setNegativeButton("Annuler", null).show()
    }

    private fun matchDialog() {
        val msg = EditText(this).apply { hint = "Collez le SMS / la notification de l'opérateur"; minLines = 3 }
        val txn = EditText(this).apply { hint = "Identifiant de transaction (si absent du message)" }
        val rail = EditText(this).apply { hint = "MTN ou AIRTEL" }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(msg); addView(txn); addView(rail) }
        AlertDialog.Builder(this).setTitle("Rapprocher un message")
            .setMessage("Le Brain cherche une référence et un montant exact. Sans les deux, la ligne va en revue ; rien n'est attribué sur un simple numéro.")
            .setView(col)
            .setPositiveButton("Analyser") { _, _ ->
                run { market.treasuryMatch(msg.text.toString(), 0, txn.text.toString().trim(), rail.text.toString().trim().uppercase()) }
            }.setNegativeButton("Annuler", null).show()
    }

    private fun noteDialog(title: String, then: (String) -> Unit) {
        val e = EditText(this).apply { hint = "Note (journalisée)" }
        AlertDialog.Builder(this).setTitle(title).setView(e).setPositiveButton("Confirmer") { _, _ -> then(e.text.toString().trim()) }.setNegativeButton("Annuler", null).show()
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

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 12, 0, 12) }
    private fun row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
    private fun text(id: Int, s: String) { findViewById<TextView>(id).text = s }
}
