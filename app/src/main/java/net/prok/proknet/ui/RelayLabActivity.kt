package net.prok.proknet.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import net.prok.proknet.ProkNetApp
import net.prok.proknet.R
import net.prok.proknet.ble.Peer
import net.prok.proknet.ble.ProkNetNode
import net.prok.proknet.core.Coverage
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.ShareCheck
import net.prok.proknet.node.RelayProbe

/**
 * v0.9 Relay Lab (developer screen): capability probe, the 3-phone live
 * relay experiment (relay mode, upstream link) and Wi-Fi source discovery
 * with local trust classification. Nothing here reaches the consumer tabs.
 */
class RelayLabActivity : Activity(), ProkNetNode.Listener {
    private val tag = "RELAYLAB"
    private lateinit var node: ProkNetNode
    private val main = Handler(Looper.getMainLooper())
    private var peers: List<Peer> = emptyList()
    private var selected: Peer? = null
    private lateinit var peersAdapter: ArrayAdapter<String>
    private lateinit var sourcesAdapter: ArrayAdapter<String>
    private var scan: List<ScanResult> = emptyList()
    private val prefs by lazy { getSharedPreferences("proknet_sources", Context.MODE_PRIVATE) }
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val ticker = object : Runnable { override fun run() { refreshState(); main.postDelayed(this, 2000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_relay_lab)
        node = ProkNetApp.node(this)
        findViewById<Button>(R.id.btnRelayMode).setOnClickListener { toggleRelayMode() }
        findViewById<Button>(R.id.btnLinkUp).setOnClickListener { linkUpstream() }
        findViewById<Button>(R.id.btnDropUp).setOnClickListener { node.dropUpstream(); toast("Upstream link dropped") }
        findViewById<Button>(R.id.btnProbe).setOnClickListener { refreshProbe(); refreshState() }
        findViewById<Button>(R.id.btnShareCheck).setOnClickListener {
            if (!node.isRunning) { toast("Start the node first"); return@setOnClickListener }
            node.checkSharing("manual test", force = true)
            toast("Testing the hotspot on this Wi-Fi network...")
            main.postDelayed({ refreshState() }, 3000)
        }
        findViewById<Button>(R.id.btnCopyRelayDiag).setOnClickListener { copyRelayDiag() }
        findViewById<Button>(R.id.btnScanWifi).setOnClickListener { scanWifi() }
        peersAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        findViewById<ListView>(R.id.listRelayPeers).let { l ->
            l.adapter = peersAdapter
            l.setOnItemClickListener { _, _, pos, _ -> selected = peers.getOrNull(pos); findViewById<TextView>(R.id.txtRelaySelected).text = "Selected: " + (selected?.let { describe(it) } ?: "none") }
        }
        sourcesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        findViewById<ListView>(R.id.listSources).let { l -> l.adapter = sourcesAdapter; l.setOnItemClickListener { _, _, pos, _ -> scan.getOrNull(pos)?.let { classify(it) } } }
        refreshProbe()
    }

    override fun onStart() {
        super.onStart()
        ProkNetApp.visibleActivities++
        node.addListener(this)
        onPeers(node.peers()); refreshState()
        main.post(ticker)
    }

    override fun onStop() {
        ProkNetApp.visibleActivities = maxOf(0, ProkNetApp.visibleActivities - 1)
        main.removeCallbacks(ticker)
        node.removeListener(this)
        super.onStop()
    }

    // ---- relay experiment -------------------------------------------------------------------------

    private fun toggleRelayMode() {
        if (!node.isRunning) { toast("Start the node first (developer screen)"); return }
        val on = !node.relay.relayMode
        val err = node.setRelayMode(on)
        if (err != null) toast("Cannot: " + err) else toast(if (on) "RELAY MODE ON: now link upstream to the seller" else "Relay mode off")
        refreshState()
    }

    private fun linkUpstream() {
        if (!node.isRunning) { toast("Start the node first"); return }
        val p = selected ?: run { toast("Select the seller phone (C) in the list first"); return }
        if (!node.relay.relayMode) toast("Tip: turn RELAY MODE on so buyers see the offer")
        if (!node.linkUpstream(p)) toast("Cannot link (see log)") else toast("Upstream link requested to " + p.label + ": approve the Wi-Fi dialog")
        refreshState()
    }

    private fun refreshState() {
        val r = node.relay
        val t = "node " + (if (node.isRunning) "running" else "STOPPED") + " | " + r.stateLine() + "\n" +
            "share while on Wi-Fi: " + node.shareCheck + " on " + ShareCheck.describe(node.shareFreqMhz) + " [" + node.shareNetworkKey + "]" + (if (node.shareDetail.isNotEmpty()) " - " + node.shareDetail else "") + "\n" +
            "DOWN (normal): " + node.wifi.phase + (node.wifi.linkedPeer?.let { " prok-" + it } ?: "") + " | relay tx " + node.wifi.relayBytesSent + " rx " + node.wifi.relayBytesReceived + "\n" +
            "UP: " + node.wifiUp.phase + (node.wifiUp.linkedPeer?.let { " prok-" + it } ?: "") + " | relay tx " + node.wifiUp.relayBytesSent + " rx " + node.wifiUp.relayBytesReceived + "\n" +
            (r.session?.let { "session " + it.id + ": to seller " + it.bytesToUp + " B / " + it.framesToUp + " frames, to buyer " + it.bytesToDown + " B / " + it.framesToDown + " frames, " + (it.durationMs / 1000) + " s\n" } ?: "") +
            (if (r.lastEvent.isNotEmpty()) "last: " + r.lastEvent + "\n" else "") +
            "seller side: " + node.gateway.state + " | buyer side: " + node.tunnel.state + (node.tunnel.providerShort?.let { " (provider prok-" + it + ")" } ?: "")
        findViewById<TextView>(R.id.txtRelayState).text = t
        findViewById<Button>(R.id.btnRelayMode).text = if (r.relayMode) "RELAY MODE on" else "RELAY MODE"
    }

    private fun refreshProbe() {
        val sb = StringBuilder()
        for ((k, v) in RelayProbe.capabilities(this)) sb.append(k).append(": ").append(v).append("\n")
        sb.append("networks:\n")
        for (l in RelayProbe.networks(this)) sb.append("  ").append(l).append("\n")
        sb.append("ifaces: ").append(RelayProbe.interfaces().joinToString(" ")).append("\n")
        sb.append("down link: ").append(node.wifi.linkDescription()).append("\n")
        sb.append("up link: ").append(node.wifiUp.linkDescription())
        findViewById<TextView>(R.id.txtProbe).text = sb.toString()
    }

    private fun copyRelayDiag() {
        val text = RelayProbe.text(this, node) + "--- wifi sources ---\n" + sourceLines().joinToString("\n") + "\n"
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Prok relay diag", text))
        DiagLog.i(tag, "relay diag copied (" + text.length + " chars)")
        toast("Relay diagnostic copied")
    }

    // ---- Internet source discovery (Part C foundation) ------------------------------------------

    @Suppress("DEPRECATION")
    private fun scanWifi() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            val started = wm.startScan()
            DiagLog.i(tag, "wifi scan requested (" + (if (started) "accepted" else "throttled by Android, showing cached results") + ")")
            findViewById<TextView>(R.id.txtSourcesNote).text = if (started) "Scanning…" else "Android throttles scans (4 per 2 min): showing the last results"
        } catch (e: Exception) { DiagLog.w(tag, "startScan: " + e) }
        main.postDelayed({ readScan() }, 3500)
    }

    private fun readScan() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        try {
            scan = wm.scanResults.sortedByDescending { it.level }
            sourcesAdapter.clear(); sourcesAdapter.addAll(sourceLines())
            findViewById<TextView>(R.id.txtSourcesNote).text = scan.size.toString() + " networks at " + timeFmt.format(Date()) + (if (scan.isEmpty()) " (Location must be ON for scan results)" else "")
            DiagLog.i(tag, "wifi scan: " + scan.size + " networks; open: " + scan.count { Coverage.securityOf(it.capabilities).startsWith("open") })
        } catch (e: SecurityException) {
            findViewById<TextView>(R.id.txtSourcesNote).text = "Scan results need the Location permission"
            DiagLog.w(tag, "scanResults: " + e)
        }
    }

    private fun sourceLines(): List<String> = scan.map { r ->
        val ssid = if (r.SSID.isNullOrEmpty()) "(hidden)" else r.SSID
        val cls = prefs.getString(r.BSSID, null)?.let { Coverage.Trust.valueOf(it) } ?: Coverage.Trust.UNKNOWN
        ssid + "  " + r.BSSID + "  " + r.level + " dBm  " + Coverage.securityOf(r.capabilities) + "  [" + Coverage.trustWord(cls) + "]" + (if (Coverage.redistributable(cls)) " shareable" else "")
    }

    private fun classify(r: ScanResult) {
        val options = Coverage.Trust.values()
        AlertDialog.Builder(this).setTitle((if (r.SSID.isNullOrEmpty()) "(hidden)" else r.SSID) + "\n" + r.BSSID)
            .setItems(options.map { Coverage.trustWord(it) }.toTypedArray()) { _, i ->
                prefs.edit().putString(r.BSSID, options[i].name).apply()
                DiagLog.i(tag, "source " + r.BSSID + " (" + r.SSID + ") classified " + options[i].name + (if (Coverage.redistributable(options[i])) " (shareable later)" else " (never shared)"))
                sourcesAdapter.clear(); sourcesAdapter.addAll(sourceLines())
            }.setNegativeButton("Cancel", null).show()
    }

    // ---- node listener ----------------------------------------------------------------------------

    private fun describe(p: Peer): String {
        val o = p.offer()
        return p.label + (if (node.hasKey(p.shortId)) " [key]" else " [no key]") + (if (o.selling) " [SELL " + o.pricePerMb + (if (o.viaRelay) " via relay" else "") + "]" else "") +
            (if (p.inRange) " rssi " + p.rssi else " not in range") + (if (node.wifi.linkedPeer == p.shortId) " [DOWN link]" else "") + (if (node.wifiUp.linkedPeer == p.shortId) " [UP link]" else "")
    }

    override fun onPeers(peers: List<Peer>) { this.peers = peers; peersAdapter.clear(); peersAdapter.addAll(peers.map { describe(it) }) }
    override fun onMessagesChanged() {}
    override fun onStatus(status: String) { refreshState() }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
