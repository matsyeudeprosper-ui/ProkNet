package net.prok.proknet.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import net.prok.proknet.ProkNetApp
import net.prok.proknet.R
import net.prok.proknet.core.Market
import net.prok.proknet.core.RelayOffersView
import net.prok.proknet.node.JobsSync

/**
 * v0.19.0: the relay's offers (contract §4 "Relay job"). Three kinds, each quoted BEFORE
 * acceptance with amount, time, requirements and the daily cap: carry now (paid from the
 * signed settlement), stay available (a verified 30-minute block, paid on completion), move
 * to help (a fixed amount reserved first, earned on arrival + readiness). Ordinary online
 * time earns nothing and the screen says so. While a block runs, the screen answers the
 * Brain's reachability challenges on a timer; the earnings pipeline shows pending -> verified
 * -> earned -> withdrawal requested -> paid, and a pending job is never cash.
 */
class RelayOffersActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val node by lazy { ProkNetApp.node(this) }
    private val jobs by lazy { JobsSync(node.identity) { node.brainUrlProvider?.invoke() ?: "" } }
    private var probing: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_relay_offers)
        findViewById<Button>(R.id.btnRoRefresh).setOnClickListener { load() }
        findViewById<Button>(R.id.btnRoSettings).setOnClickListener { settingsDialog() }
        findViewById<Button>(R.id.btnRoStop).setOnClickListener { stopAvailability() }
    }

    override fun onResume() { super.onResume(); load() }
    override fun onPause() { super.onPause(); probing?.let { main.removeCallbacks(it) }; probing = null }

    private fun load() {
        io.execute {
            val mine = jobs.refresh()
            val wallet = node.ledgerSync.view
            runOnUiThread { render(mine, wallet) }
        }
    }

    private fun render(mine: RelayOffersView.Mine?, wallet: net.prok.proknet.core.LedgerView.View?) {
        val f = node.flags
        text(R.id.roFlag, if (f.enabled("relay_payout")) "Gains relais : ouverts pour votre compte." else f.offSentence("relay_payout"))
        if (mine == null) { text(R.id.roSettings, "Brain injoignable : " + jobs.lastError); return }
        text(R.id.roSettings, mine.settings.describe())
        val box = findViewById<LinearLayout>(R.id.roOffers); box.removeAllViews()
        show(R.id.roEmpty, mine.offers.isEmpty())
        for (o in mine.offers) box.addView(offerView(o))
        text(R.id.roPipeline, RelayOffersView.pipelineText(mine.current?.pipelineStage ?: -1) + "\n" + mine.earningsLines(wallet))
        text(R.id.roCap, mine.capLine)
        val running = mine.current
        probing?.let { main.removeCallbacks(it) }; probing = null
        if (running != null && running.blockRunning) {
            val r = object : Runnable { override fun run() { io.execute { jobs.answerChallenge(running.id) }; main.postDelayed(this, 60_000) } }
            probing = r; main.postDelayed(r, 5_000)
        }
    }

    private fun offerView(o: RelayOffersView.Offer): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 14, 0, 14) }
        val terms = when (o.kind) {
            "STAY" -> Market.cfa(o.amountCentimes) + " par bloc vérifié de " + o.blockMinutes + " min, max " + o.blocksPerDay + " blocs/jour · " + o.probesRequired + " sondes réussies requises"
            "MOVE" -> Market.cfa(o.amountCentimes) + " fixes à l'arrivée + gains sur les données livrées · zone de rendez-vous " + o.rendezvousCell
            else -> "part relais de l'offre signée, payée après règlement de la session"
        }
        box.addView(TextView(this).apply {
            textSize = 15f
            text = RelayOffersView.kindWord(o.kind) + " · " + o.zone + "\n" + terms + "\n" + o.text +
                (if (o.blockRunning) " · sondes ok " + o.probesOk + " / échecs " + o.probesFailed else "") +
                (if (o.failReason.isNotEmpty()) " · " + o.failReason else "") +
                "\nBatterie ≥ " + o.batteryFloorPct + " % · fenêtre " + RelayOffersView.Settings.hhmm(o.windowStartMin) + "–" + RelayOffersView.Settings.hhmm(o.windowEndMin)
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (o.canAccept) buttons.addView(Button(this).apply { text = "Accepter"; setOnClickListener { run { jobs.accept(o.id) } } })
        if (o.canDecline) buttons.addView(Button(this).apply { text = "Refuser"; setOnClickListener { run { jobs.decline(o.id) } } })
        if (o.kind == "STAY" && o.state == "ACCEPTED") buttons.addView(Button(this).apply { text = "Démarrer le bloc"; setOnClickListener { run { jobs.startBlock(o.id) } } })
        if (o.kind == "MOVE" && o.state == "ACCEPTED") buttons.addView(Button(this).apply { text = "Je pars"; setOnClickListener { run { jobs.moveDeparted(o.id) } } })
        if (o.kind == "MOVE" && o.state == "IN_PROGRESS" && o.arrivedAt == 0L) buttons.addView(Button(this).apply { text = "Arrivé"; setOnClickListener { run { jobs.moveArrived(o.id) } } })
        if (o.kind == "MOVE" && o.arrivedAt > 0L && o.readyAt == 0L) buttons.addView(Button(this).apply { text = "Prêt"; setOnClickListener { run { jobs.moveReady(o.id) } } })
        if (o.open && o.kind != "CARRY") buttons.addView(Button(this).apply { text = "Annuler"; setOnClickListener { run { jobs.cancel(o.id) } } })
        box.addView(buttons)
        return box
    }

    private fun settingsDialog() {
        val cur = jobs.mine?.settings ?: RelayOffersView.Settings.DEFAULT
        val min = EditText(this).apply { hint = "Payout minimum acceptable (CFA)"; setText((cur.minPayoutCentimes / 100).toString()) }
        val bat = EditText(this).apply { hint = "Batterie minimum (%)"; setText(cur.batteryFloorPct.toString()) }
        val from = EditText(this).apply { hint = "Disponible de (HH:MM)"; setText(RelayOffersView.Settings.hhmm(cur.windowStartMin)) }
        val to = EditText(this).apply { hint = "à (HH:MM)"; setText(RelayOffersView.Settings.hhmm(cur.windowEndMin)) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(min); addView(bat); addView(from); addView(to) }
        AlertDialog.Builder(this).setTitle("Mes réglages").setMessage("Vous ne fixez pas de tarif : ProkNet propose, vous acceptez ou refusez. Ces réglages filtrent les offres qu'on vous montre.").setView(col)
            .setPositiveButton("Enregistrer") { _, _ ->
                val s = RelayOffersView.Settings((min.text.toString().filter { it.isDigit() }.toLongOrNull() ?: 0L) * 100,
                    bat.text.toString().filter { it.isDigit() }.toIntOrNull()?.coerceIn(0, 100) ?: 20,
                    RelayOffersView.Settings.parseHhmm(from.text.toString()) ?: 0, RelayOffersView.Settings.parseHhmm(to.text.toString()) ?: 1440, true)
                run { jobs.saveSettings(s) }
            }.setNegativeButton("Annuler", null).show()
    }

    private fun stopAvailability() {
        val cur = jobs.mine?.settings ?: RelayOffersView.Settings.DEFAULT
        run { jobs.saveSettings(RelayOffersView.Settings(cur.minPayoutCentimes, cur.batteryFloorPct, cur.windowStartMin, cur.windowEndMin, false)) }
    }

    private fun run(block: () -> JobsSync.Outcome) {
        io.execute {
            val out = try { block() } catch (e: Exception) { JobsSync.Outcome(false, e.message ?: "échec") }
            runOnUiThread { toast(out.message); load() }
        }
    }

    private fun text(id: Int, s: String) { findViewById<TextView>(id).text = s }
    private fun show(id: Int, on: Boolean) { findViewById<android.view.View>(id).visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
