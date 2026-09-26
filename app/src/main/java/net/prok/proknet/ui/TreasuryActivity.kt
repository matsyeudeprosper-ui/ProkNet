package net.prok.proknet.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import net.prok.proknet.ProkNetApp
import net.prok.proknet.R
import net.prok.proknet.core.LedgerView
import net.prok.proknet.core.Market
import net.prok.proknet.core.Msisdn
import net.prok.proknet.node.TreasuryWatch

/**
 * v0.18.0: the treasurer's queue.
 *
 * What this screen is honest about, in its first line: N withdrawals waiting means N
 * Mobile Money transfers a person has to send by hand. Nothing here sends money. The
 * buttons record what the treasurer did, in the order the ledger allows: Approuver,
 * then Marquer envoyé (once - a second tap is refused by the Brain), then either the
 * operator's own "vous avez envoyé" message turns the row Payé or the treasurer confirms
 * it with the operator's reference.
 *
 * Reachable only when the Brain has said this identity is a treasury identity; every
 * request is signed, and the Brain refuses anybody else with a 403 and an audit row.
 */
class TreasuryActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val node by lazy { ProkNetApp.node(this) }
    private val ledger get() = node.ledgerSync

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_treasury)
        findViewById<android.view.View>(R.id.hdBack).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnTrRefresh).setOnClickListener { load() }
        findViewById<Button>(R.id.btnTrBalance).setOnClickListener { balanceDialog() }
        findViewById<Button>(R.id.btnTrTestCredit).setOnClickListener { testCreditDialog() }
    }

    override fun onResume() { super.onResume(); load() }

    private fun load() {
        val v = ledger.view
        if (v == null || !v.treasury) {
            text(R.id.trSentence, "Cet appareil n'est pas une trésorerie Prok.")
            text(R.id.trSummary, "Le Brain n'a pas reconnu cette identité comme trésorerie.")
            return
        }
        io.execute {
            val q = ledger.queue()
            val review = ledger.reviewList()
            val rec = ledger.reconcile()
            runOnUiThread { render(q, review, rec) }
        }
    }

    private fun render(q: LedgerView.Queue?, review: List<Map<String, String>>, rec: String?) {
        if (q == null) { text(R.id.trSentence, "Brain injoignable"); text(R.id.trSummary, ledger.lastError); return }
        val s = q.summary
        text(R.id.trSentence, LedgerView.manualSendsLine(s.manualSendsPending))
        val recLine = if (rec == null) "Rapprochement : indisponible" else LedgerView.reconcileLines(rec)
        text(R.id.trSummary,
            "Envoyés, non confirmés : " + s.sentUnconfirmed + " · À vérifier : " + s.needsAttention +
            "\nRecharges non attribuées : " + s.topupsUnassigned +
            "\nFloat MTN " + Market.cfa(s.floatMtn) + " · Airtel " + Market.cfa(s.floatAirtel) + " · dettes " + Market.cfa(s.liabilities) +
            (if (s.shortfall > 0) " · MANQUE " + Market.cfa(s.shortfall) else "") +
            "\n" + recLine +
            "\nPaiements clients : " + (if (s.paymentsLive) "ACTIFS" else "désactivés (pilote)"))
        findViewById<Button>(R.id.btnTrTestCredit).setOnLongClickListener { moveIdentityDialog(); true }
        val rows = findViewById<LinearLayout>(R.id.trRows)
        rows.removeAllViews()
        show(R.id.trEmpty, q.rows.isEmpty())
        val now = System.currentTimeMillis()
        for (r in q.rows) rows.addView(rowView(r, now))
        val rv = findViewById<LinearLayout>(R.id.trReview)
        rv.removeAllViews()
        val pending = TreasuryWatch.pendingReview()
        show(R.id.trReviewEmpty, review.isEmpty() && pending.isEmpty())
        for (p in pending) rv.addView(localReviewView(p))
        for (item in review) rv.addView(reviewView(item))
        text(R.id.trFooter, ledger.describe())
    }

    private fun rowView(r: LedgerView.QueueRow, now: Long): LinearLayout {
        val tone = when (r.state) { "PAID" -> Prok.Tone.OK; "SENT" -> Prok.Tone.BRAND; "NEEDS_ATTENTION" -> Prok.Tone.DANGER; "APPROVED" -> Prok.Tone.WARN; else -> Prok.Tone.MUTED }
        val box = Prok.card(this)
        box.addView(Prok.titleRow(this, (if (r.kind == "REFUND") "Remboursement " else "") + Market.cfa(r.amountCentimes), LedgerView.statusText(r.state), tone))
        box.addView(Prok.body(this, railName(r.rail) + " · " + Msisdn.pretty(r.msisdn).ifEmpty { r.msisdn }, 4))
        box.addView(Prok.muted(this, r.ageLine(now) + " · prok-" + r.payeeId.take(8) + (if (r.amber) "\nÀ vérifier dans l'historique MoMo" else "") + (if (r.memo.isNotEmpty()) "\n" + r.memo else "")))
        val buttons = ArrayList<android.view.View>()
        for ((i, a) in r.actions.withIndex()) buttons += (if (i == 0) Prok.primary(this, LedgerView.actionLabel(a)) { act(r, a) } else Prok.secondary(this, LedgerView.actionLabel(a)) { act(r, a) })
        buttons += Prok.ghost(this, "Copier le n°") { copy(r.msisdn) }
        box.addView(Prok.actions(this, *buttons.toTypedArray()))
        return box
    }

    private fun act(r: LedgerView.QueueRow, action: String) {
        when (action) {
            "sent" -> confirm("Marquer envoyé", "Vous avez envoyé " + Market.cfa(r.amountCentimes) + " au " + Msisdn.pretty(r.msisdn) + " depuis l'application " + railName(r.rail) + " ?\n\nUne fois marqué, ce retrait ne sera plus proposé à l'envoi.") {
                run { ledger.treasuryAction(r.id, "sent") }
            }
            "paid" -> ask("Confirmer payé", "Référence de l'opérateur (dans votre historique " + railName(r.rail) + ")") { ref ->
                run { ledger.treasuryAction(r.id, "paid", evidence = ref) }
            }
            "deny" -> ask("Refuser", "Motif (visible par le bénéficiaire)") { memo -> run { ledger.treasuryAction(r.id, "deny", memo = memo) } }
            "unsent" -> ask("Non envoyé", "Pourquoi (pour le journal)") { memo -> run { ledger.treasuryAction(r.id, "unsent", memo = memo) } }
            else -> run { ledger.treasuryAction(r.id, action) }
        }
    }

    private fun reviewView(item: Map<String, String>): LinearLayout {
        val amount = item["amount"]?.toLongOrNull() ?: 0L
        val claimed = item["customer_id"].orEmpty()
        val box = Prok.card(this)
        box.addView(Prok.titleRow(this, "Recharge " + Market.cfa(amount), item["state"].orEmpty(), if (claimed.isNotEmpty()) Prok.Tone.WARN else Prok.Tone.MUTED))
        box.addView(Prok.muted(this, railName(item["rail"].orEmpty()) +
            (if (claimed.isNotEmpty()) " · réclamée par prok-" + claimed.take(8) + " · réf. " + item["claim_ref"] else " · non attribuée") +
            (if (item["stale"] == "true") " · plus de 7 jours" else "")))
        if (claimed.isNotEmpty()) box.addView(Prok.actions(this,
            Prok.primary(this, "Créditer") { run { ledger.review(item["topup_id"].orEmpty(), true, "") } },
            Prok.secondary(this, "Rejeter") { ask("Rejeter", "Motif") { m -> run { ledger.review(item["topup_id"].orEmpty(), false, m) } } }))
        return box
    }

    /** A message this phone could not read confidently: the text is here, on Prok's phone, and nowhere else. */
    private fun localReviewView(p: TreasuryWatch.Pending): LinearLayout {
        val box = Prok.card(this)
        box.addView(Prok.titleRow(this, "Message illisible", p.reason, Prok.Tone.WARN))
        box.addView(Prok.body(this, p.text.take(240)))
        box.addView(Prok.actions(this,
            Prok.primary(this, "Reçu de…") { manualObserve(p, credit = true) },
            Prok.secondary(this, "Envoyé à…") { manualObserve(p, credit = false) },
            Prok.ghost(this, "Ignorer") { TreasuryWatch.dismiss(p.smsHash); load() }))
        return box
    }

    private fun manualObserve(p: TreasuryWatch.Pending, credit: Boolean) {
        val number = EditText(this).apply { hint = "Numéro (9 chiffres)"; inputType = android.text.InputType.TYPE_CLASS_PHONE }
        val amount = EditText(this).apply { hint = "Montant en CFA"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val rail = EditText(this).apply { hint = "MTN ou AIRTEL"; setText(p.railGuess) }
                AlertDialog.Builder(this).setTitle(if (credit) "Argent reçu" else "Argent envoyé").setMessage(p.text.take(200)).setView(form(rail, number, amount))
            .setPositiveButton("Enregistrer") { _, _ ->
                val cfa = amount.text.toString().filter { it.isDigit() }.toLongOrNull() ?: 0L
                val h = Msisdn.hash(number.text.toString())
                val r = rail.text.toString().trim().uppercase()
                if (cfa <= 0 || h.isEmpty() || (r != "MTN" && r != "AIRTEL")) { toast("Numéro, montant et opérateur requis"); return@setPositiveButton }
                run {
                    val out = if (credit) ledger.observeCredit(r, h, cfa * 100, p.smsHash, "treasurer") else ledger.observeDebit(r, h, cfa * 100, p.smsHash)
                    if (out.ok) TreasuryWatch.dismiss(p.smsHash)
                    out
                }
            }.setNegativeButton("Annuler", null).show()
    }

    private fun balanceDialog() {
        val mtn = EditText(this).apply { hint = "Solde MTN MoMo (CFA), tel qu'affiché par *xxx#"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        val airtel = EditText(this).apply { hint = "Solde Airtel Money (CFA)"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
                AlertDialog.Builder(this).setTitle("Solde du jour").setMessage("Consultez le solde dans chaque application ou par USSD, puis tapez-le ici. Le Brain compare avec ce que le registre attend.").setView(form(mtn, airtel))
            .setPositiveButton("Enregistrer") { _, _ ->
                val m = mtn.text.toString().filter { it.isDigit() }.toLongOrNull()
                val a = airtel.text.toString().filter { it.isDigit() }.toLongOrNull()
                io.execute {
                    val msgs = ArrayList<String>()
                    if (m != null) msgs += "MTN: " + ledger.balanceCheck("MTN", m * 100).message
                    if (a != null) msgs += "Airtel: " + ledger.balanceCheck("AIRTEL", a * 100).message
                    runOnUiThread { toast(msgs.joinToString(" · ").ifEmpty { "Rien saisi" }); load() }
                }
            }.setNegativeButton("Annuler", null).show()
    }

    /** Device or key recovery: the old identity's credit and earnings move to the new one, never duplicated. */
    private fun moveIdentityDialog() {
        val from = EditText(this).apply { hint = "Ancienne identité (id complet)" }
        val to = EditText(this).apply { hint = "Nouvelle identité (id complet)" }
        val memo = EditText(this).apply { hint = "Comment la personne a prouvé que c'est elle" }
                AlertDialog.Builder(this).setTitle("Transférer une identité").setMessage("Téléphone réinstallé ou perdu : les soldes de l'ancienne identité passent à la nouvelle. Refusé tant que l'ancienne a une session ou un retrait en cours. Tout est journalisé.").setView(form(from, to, memo))
            .setPositiveButton("Transférer") { _, _ -> run { ledger.moveIdentity(from.text.toString().trim(), to.text.toString().trim(), memo.text.toString().trim()) } }
            .setNegativeButton("Annuler", null).show()
    }

    private fun testCreditDialog() {
        val target = EditText(this).apply { hint = "Identité de test (id complet, 32 caractères hex)" }
        val amount = EditText(this).apply { hint = "Montant en CFA"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
                AlertDialog.Builder(this).setTitle("Crédit test (pilote)").setMessage("Uniquement pour une identité listée comme test sur le Brain. Ce n'est pas de l'argent : c'est un crédit pour exercer les sessions et les retraits.").setView(form(target, amount))
            .setPositiveButton("Poster") { _, _ ->
                val cfa = amount.text.toString().filter { it.isDigit() }.toLongOrNull() ?: 0L
                run { ledger.testCredit(target.text.toString().trim(), cfa * 100, "treasury screen") }
            }.setNegativeButton("Annuler", null).show()
    }

    // ---- small helpers ---------------------------------------------------------------------------

    /** Dialog fields with the screen's own margins. */
    private fun form(vararg fields: android.view.View): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; val p = Prok.dp(context, 20); setPadding(p, Prok.dp(context, 8), p, 0)
        for (f in fields) addView(f, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = Prok.dp(context, 8) })
    }

    private fun run(block: () -> net.prok.proknet.node.LedgerSync.Outcome) {
        io.execute {
            val out = try { block() } catch (e: Exception) { net.prok.proknet.node.LedgerSync.Outcome(false, e.message ?: "échec") }
            runOnUiThread { toast(out.message); load() }
        }
    }

    private fun confirm(title: String, message: String, onYes: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setPositiveButton("Oui") { _, _ -> onYes() }.setNegativeButton("Non", null).show()
    }

    private fun ask(title: String, hint: String, onText: (String) -> Unit) {
        val input = EditText(this).apply { this.hint = hint }
        AlertDialog.Builder(this).setTitle(title).setView(form(input))
            .setPositiveButton("OK") { _, _ -> onText(input.text.toString().trim()) }.setNegativeButton("Annuler", null).show()
    }

    private fun copy(number: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("numéro", number))
        toast("Numéro copié")
    }

    private fun railName(rail: String): String = when (rail) { "MTN" -> "MTN MoMo"; "AIRTEL" -> "Airtel Money"; else -> rail }
    private fun text(id: Int, s: String) { findViewById<TextView>(id).text = s }
    private fun show(id: Int, on: Boolean) { findViewById<android.view.View>(id).visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
