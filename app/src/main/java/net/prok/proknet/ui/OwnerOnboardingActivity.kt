package net.prok.proknet.ui

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import net.prok.proknet.ProkNetApp
import net.prok.proknet.R
import net.prok.proknet.core.OwnerQuoteView
import net.prok.proknet.node.JobsSync

/**
 * v0.19.0: "Partager mon Wi-Fi" (contract §4, owner onboarding). The owner says what the
 * network IS - controlled, validated, permitted by the upstream terms, free / earn /
 * sponsored - and ProkNet answers with a signed quote: customer price, owner share,
 * relay share, Prok share, examples, expiry. The owner accepts or declines that quote.
 * There is no field for a tariff anywhere on this screen.
 */
class OwnerOnboardingActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val node by lazy { ProkNetApp.node(this) }
    private val jobs by lazy { JobsSync(node.identity) { node.brainUrlProvider?.invoke() ?: "" } }
    private var quote: OwnerQuoteView.Quote? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_owner_onboarding)
        Prok.header(this, "Partager mon Wi-Fi", "ProkNet propose, vous acceptez ou refusez")
        findViewById<CheckBox>(R.id.ooAttest).text = OwnerQuoteView.ATTESTATION_SENTENCE
        findViewById<TextView>(R.id.ooFree).text = OwnerQuoteView.choiceWord(OwnerQuoteView.Choice.FREE)
        findViewById<TextView>(R.id.ooEarn).text = OwnerQuoteView.choiceWord(OwnerQuoteView.Choice.EARN)
        findViewById<TextView>(R.id.ooSponsored).text = OwnerQuoteView.choiceWord(OwnerQuoteView.Choice.SPONSORED)
        findViewById<Button>(R.id.btnOoQuote).setOnClickListener { requestQuote() }
        findViewById<Button>(R.id.btnOoAccept).setOnClickListener { decide(true) }
        findViewById<Button>(R.id.btnOoDecline).setOnClickListener { decide(false) }
    }

    override fun onResume() { super.onResume(); render() }

    private fun choice(): OwnerQuoteView.Choice? = when (findViewById<RadioGroup>(R.id.ooChoice).checkedRadioButtonId) {
        R.id.ooFree -> OwnerQuoteView.Choice.FREE
        R.id.ooEarn -> OwnerQuoteView.Choice.EARN
        R.id.ooSponsored -> OwnerQuoteView.Choice.SPONSORED
        else -> null
    }

    private fun checks(): OwnerQuoteView.Checks {
        val up = node.gateway.upstream
        return OwnerQuoteView.Checks(
            controlsNetwork = node.gateway.providing || up != null,
            upstreamDescription = node.gateway.upstreamDescription(),
            validated = up?.validated == true,
            attested = findViewById<CheckBox>(R.id.ooAttest).isChecked,
            choice = choice())
    }

    private fun render() {
        val f = node.flags
        val paidOpen = f.enabled("customer_paid_delivery"); val sponsoredOpen = f.enabled("sponsored_delivery"); val freeOpen = f.enabled("direct_free")
        text(R.id.ooFlag, "Gratuit direct : " + (if (freeOpen) "ouvert" else f.offSentence("direct_free")) +
            "\nLivraison payante : " + (if (paidOpen) "ouverte pour votre compte" else f.offSentence("customer_paid_delivery")) +
            "\nSponsorisé : " + (if (sponsoredOpen) "ouvert" else f.offSentence("sponsored_delivery")))
        text(R.id.ooChecks, checks().checksLines())
    }

    private fun requestQuote() {
        val c = checks()
        if (!c.canRequestQuote) { toast(c.blockingReason()); return }
        val choice = c.choice!!
        io.execute {
            val (out, q) = jobs.requestQuote(OwnerQuoteView.sourceKindFor(choice), relayPresent = false,
                sponsorCampaignId = "", allowanceBytes = 100L * 1_000_000L)
            runOnUiThread {
                if (q == null) { toast(out.message); return@runOnUiThread }
                quote = q
                val now = System.currentTimeMillis()
                text(R.id.ooQuote, listOf(q.headline(), q.examplesLine(), q.splitLine(), q.movementLine(), q.expiryLine(now), q.verdictLine(), q.nobodyLine())
                    .filter { it.isNotEmpty() }.joinToString("\n\n"))
                findViewById<Button>(R.id.btnOoAccept).isEnabled = !q.free && !q.expired(now)
                findViewById<Button>(R.id.btnOoDecline).isEnabled = !q.free && !q.expired(now)
                if (q.free) toast("Partage gratuit : rien à accepter, rien à gagner")
            }
        }
    }

    private fun decide(accept: Boolean) {
        val q = quote ?: return
        io.execute {
            val out = jobs.decide(OwnerQuoteView.decide(q, accept))
            runOnUiThread {
                toast(out.message)
                text(R.id.ooFooter, if (accept && out.ok) "Offre acceptée : elle s'applique aux prochaines sessions. Un changement de tarif exigera une nouvelle offre et votre accord." else if (!accept && out.ok) "Offre refusée. Rien ne change." else out.message)
                findViewById<Button>(R.id.btnOoAccept).isEnabled = false; findViewById<Button>(R.id.btnOoDecline).isEnabled = false
            }
        }
    }

    private fun text(id: Int, s: String) { findViewById<TextView>(id).text = s }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
