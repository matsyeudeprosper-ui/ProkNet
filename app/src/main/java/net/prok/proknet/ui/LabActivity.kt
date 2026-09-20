package net.prok.proknet.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import net.prok.proknet.ProkNetApp
import net.prok.proknet.R
import net.prok.proknet.ble.Peer
import net.prok.proknet.ble.ProkNetNode
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Dir
import net.prok.proknet.core.Identity
import net.prok.proknet.core.MsgStatus
import net.prok.proknet.core.Routing
import net.prok.proknet.core.Transfer
import net.prok.proknet.service.ProkNetService
import net.prok.proknet.core.Tunnel
import net.prok.proknet.core.Market
import net.prok.proknet.vpn.ProkVpnService

/**
 * Prok DEVELOPER screen (the v0.1-v0.7 lab UI, kept whole): every engineering tool,
 * everything visible. The Activity is only a window onto the node, which is
 * owned by the Application and driven by ProkNetService.
 */
class LabActivity : Activity(), ProkNetNode.Listener {
    private val tag = "UI"
    private lateinit var node: ProkNetNode

    private lateinit var txtIdentity: TextView
    private lateinit var txtService: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtDiag: TextView
    private lateinit var txtWifiBanner: TextView
    private lateinit var txtInternet: TextView
    private lateinit var btnProvide: Button
    private lateinit var btnUseInternet: Button
    private lateinit var txtSelected: TextView
    private lateinit var txtLog: TextView
    private lateinit var scrollLog: ScrollView
    private lateinit var listPeers: ListView
    private lateinit var listMessages: ListView
    private lateinit var editMessage: EditText

    private lateinit var peersAdapter: ArrayAdapter<String>
    private lateinit var messagesAdapter: ArrayAdapter<String>
    private var peers: List<Peer> = emptyList()
    private var selected: Peer? = null
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val versionName: String by lazy {
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" }
    }
    private val versionCode: Long by lazy {
        try { val pi = packageManager.getPackageInfo(packageName, 0); if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong() } catch (e: Exception) { 0L }
    }

    private val logListener: (String) -> Unit = { line ->
        txtLog.append(line + "\n")
        if (txtLog.text.length > 60_000) txtLog.text = DiagLog.text() + "\n"
        scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lab)
        node = ProkNetApp.node(this)

        txtIdentity = findViewById(R.id.txtIdentity)
        txtService = findViewById(R.id.txtService)
        txtStatus = findViewById(R.id.txtStatus)
        txtDiag = findViewById(R.id.txtDiag)
        txtWifiBanner = findViewById(R.id.txtWifiBanner)
        txtInternet = findViewById(R.id.txtInternet)
        btnProvide = findViewById(R.id.btnProvide)
        btnUseInternet = findViewById(R.id.btnUseInternet)
        btnProvide.setOnClickListener { toggleProvide() }
        btnUseInternet.setOnClickListener { toggleUseInternet() }
        findViewById<Button>(R.id.btnNetTest).setOnClickListener { netTest() }
        findViewById<Button>(R.id.btnBuy).setOnClickListener { buy() }
        findViewById<Button>(R.id.btnSell).setOnClickListener { sell() }
        findViewById<Button>(R.id.btnRelay).setOnClickListener { node.setRelay(!node.relayOn); toast("RELAY " + (if (node.relayOn) "on" else "off")); refreshInternetLine() }
        findViewById<Button>(R.id.btnLedger).setOnClickListener { ledgerDialog() }
        findViewById<Button>(R.id.btnHistory).setOnClickListener { historyDialog() }
        findViewById<Button>(R.id.btnRelayLab).setOnClickListener { startActivity(Intent(this, RelayLabActivity::class.java)) }
        findViewById<Button>(R.id.btnBulkLab).setOnClickListener { startActivity(Intent(this, BulkLabActivity::class.java)) }
        findViewById<Button>(R.id.btnCopyLogTop).setOnClickListener { copyLog() }
        findViewById<Button>(R.id.btnCopyDiag).setOnClickListener { copyDiag() }
        // v0.15: simulated payments. Developer screen only, off by default, and a long
        // press is required so it cannot be switched on by accident during a demo.
        findViewById<Button>(R.id.btnCopyNetwork).setOnLongClickListener {
            node.mockPaymentsEnabled = !node.mockPaymentsEnabled
            DiagLog.w(tag, "MOCK PAYMENTS " + (if (node.mockPaymentsEnabled) "ENABLED (developer only, not real money)" else "disabled"))
            toast(if (node.mockPaymentsEnabled) "Mock payments ON (developer only)" else "Mock payments OFF")
            true
        }
        findViewById<Button>(R.id.btnCopyNetwork).setOnClickListener {
            // v0.13: requests, carrying, provider state, the brain sync, the gossip counters
            val net = ProkNetApp.network(this)
            val text = "Prok NETWORK DIAG " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "\n" +
                "me: prok-" + node.identity.shortIdHex + "\n" + net.diag() + ProkNetApp.coverage(this).diag() + "--- last 60 log lines ---\n" + DiagLog.text().lines().takeLast(60).joinToString("\n") + "\n"
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ProkNet network", text))
            toast("Network diagnostic copied (" + text.length + " chars)")
        }
        findViewById<Button>(R.id.btnBrainUrl).setOnClickListener {
            val net = ProkNetApp.network(this)
            val input = android.widget.EditText(this).apply { setText(net.brainUrl); hint = "https://brain.example.org (empty = off)" }
            AlertDialog.Builder(this).setTitle("Network Brain URL").setMessage("HTTPS base URL of the ProkNet Network Brain. Leave empty to run local/direct only.").setView(input)
                .setPositiveButton("Save") { _, _ -> net.brainUrl = input.text.toString(); toast(if (net.configured) "Brain: " + net.brainUrl else "Brain off"); net.syncNow("url set") }
                .setNeutralButton("Sync now") { _, _ -> net.syncNow("manual"); toast("Sync requested") }
                .setNegativeButton("Cancel", null).show()
        }
        findViewById<Button>(R.id.btnCopyCoverage).setOnClickListener {
            // v0.12: sources, observations, cells, the last GET INTERNET decision and why
            val text = "Prok COVERAGE DIAG " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "\n" +
                "me: prok-" + node.identity.shortIdHex + "\n" + ProkNetApp.coverage(this).diag()
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ProkNet coverage", text))
            toast("Coverage diagnostic copied (" + text.length + " chars)")
        }
        node.vpnRequested = { startVpnWithConsent() }
        txtSelected = findViewById(R.id.txtSelected)
        txtLog = findViewById(R.id.txtLog)
        scrollLog = findViewById(R.id.scrollLog)
        listPeers = findViewById(R.id.listPeers)
        listMessages = findViewById(R.id.listMessages)
        editMessage = findViewById(R.id.editMessage)

        peersAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        listPeers.adapter = peersAdapter
        listPeers.setOnItemClickListener { _, _, pos, _ ->
            selected = peers.getOrNull(pos)
            txtSelected.text = "Selected: " + (selected?.let { describe(it) } ?: "none")
            DiagLog.i(tag, "selected " + selected?.label + (if (selected?.inRange == false) " (not in range, messages will queue)" else ""))
        }
        messagesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        listMessages.adapter = messagesAdapter

        findViewById<Button>(R.id.btnStart).setOnClickListener { startNode() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopNode() }
        findViewById<Button>(R.id.btnRename).setOnClickListener { renameDialog() }
        findViewById<Button>(R.id.btnRetry).setOnClickListener {
            if (!node.isRunning) toast("Press Start first") else { node.queue.retryAllNow(); node.engine.pump("manual") }
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { batterySettings() }
        findViewById<Button>(R.id.btnWifi).setOnClickListener { wifiLink() }
        findViewById<Button>(R.id.btnBigTest).setOnClickListener { bigTest() }
        findViewById<Button>(R.id.btnSendFile).setOnClickListener { pickFile() }
        findViewById<Button>(R.id.btnSend).setOnClickListener { sendMessage() }
        editMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
        }
        findViewById<Button>(R.id.btnCopyLog).setOnClickListener { copyLog() }
        findViewById<Button>(R.id.btnShareLog).setOnClickListener { shareLog() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener { DiagLog.clear(); txtLog.text = "" }

        refreshIdentity()
        DiagLog.i(tag, "activity created; app " + versionName + " (" + versionCode + "); device: " + Build.MANUFACTURER + " " + Build.MODEL + " Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
    }

    override fun onStart() {
        super.onStart()
        ProkNetApp.visibleActivities++
        txtLog.text = DiagLog.text() + "\n"
        scrollLog.post { scrollLog.fullScroll(ScrollView.FOCUS_DOWN) }
        DiagLog.addListener(logListener)
        node.addListener(this)
        DiagLog.i(tag, "activity visible (foreground); service " + (if (ProkNetService.running) "RUNNING" else "stopped") + ", node " + (if (node.isRunning) "running" else "stopped"))
        onPeers(node.peers())
        onMessagesChanged()
        onStatus(node.statusLine())
        refreshServiceLine()
    }

    override fun onStop() {
        ProkNetApp.visibleActivities = maxOf(0, ProkNetApp.visibleActivities - 1)
        DiagLog.i(tag, "activity hidden (background) - node continues in the service: " + node.statusLine())
        node.removeListener(this)
        DiagLog.removeListener(logListener)
        super.onStop()
    }

    // ---- start / stop / permissions ------------------------------------------------------------

    private fun requiredPermissions(): Array<String> {
        val p = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) { p += Manifest.permission.BLUETOOTH_SCAN; p += Manifest.permission.BLUETOOTH_ADVERTISE; p += Manifest.permission.BLUETOOTH_CONNECT }
        else p += Manifest.permission.ACCESS_FINE_LOCATION
        // Wi-Fi hotspot / join: Nearby devices on 13+, fine location below.
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.NEARBY_WIFI_DEVICES
        else if (Manifest.permission.ACCESS_FINE_LOCATION !in p) p += Manifest.permission.ACCESS_FINE_LOCATION
        // Android 12+ ignores a FINE location request that does not also ask for COARSE.
        if (Manifest.permission.ACCESS_FINE_LOCATION in p) p += Manifest.permission.ACCESS_COARSE_LOCATION
        return p.toTypedArray()
    }

    private fun missingPermissions() = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun notificationPermissionMissing(): Boolean =
        Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun startNode() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            DiagLog.i(tag, "requesting permissions: " + missing.joinToString())
            requestPermissions(missing.toTypedArray(), 1)
            return
        }
        if (notificationPermissionMissing()) {
            DiagLog.i(tag, "requesting POST_NOTIFICATIONS (needed to show the persistent notification)")
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
            return
        }
        if (!node.isBluetoothOn()) {
            DiagLog.w(tag, "Bluetooth is off, asking user to enable it")
            try { startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 2) } catch (e: Exception) { DiagLog.e(tag, "enable BT", e) }
            return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val locOn = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        if (!locOn) DiagLog.w(tag, "Location services are OFF. BLE scanning (Android 8-11) and the Wi-Fi hotspot (most versions) need Location ON.")
        DiagLog.i(tag, "Start pressed -> starting foreground service")
        ProkNetService.start(this)
        txtService.postDelayed({ refreshServiceLine() }, 1500)
    }

    private fun stopNode() {
        DiagLog.i(tag, "Stop pressed -> stopping service and node")
        ProkNetService.stop(this)
        txtService.postDelayed({ refreshServiceLine() }, 1500)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val denied = permissions.filterIndexed { i, _ -> grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED }
        if (requestCode == 3) {
            if (denied.isNotEmpty()) DiagLog.w(tag, "notification permission denied: the service still runs but its notification stays hidden")
            if (missingPermissions().isEmpty() && node.isBluetoothOn()) { ProkNetService.start(this); txtService.postDelayed({ refreshServiceLine() }, 1500) } else startNode()
            return
        }
        val bluetoothDenied = denied.filter { it.contains("BLUETOOTH") || (Build.VERSION.SDK_INT < 31 && it.contains("LOCATION")) }
        if (bluetoothDenied.isEmpty()) {
            if (denied.isNotEmpty()) DiagLog.w(tag, "Wi-Fi permission denied (" + denied.joinToString() + "): BLE works, Wi-Fi links will not")
            DiagLog.i(tag, "permissions granted"); startNode()
        } else DiagLog.e(tag, "permissions DENIED: " + denied.joinToString() + " - grant them in Settings > Apps > ProkNet Lab > Permissions")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2) {
            if (resultCode == RESULT_OK) { DiagLog.i(tag, "Bluetooth enabled"); startNode() }
            else DiagLog.w(tag, "user declined to enable Bluetooth")
        }
        if (requestCode == 4 && resultCode == RESULT_OK && data?.data != null) onFilePicked(data.data!!)
        if (requestCode == 6) onActivityResultVpn(resultCode)
    }

    private fun batterySettings() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) { toast("Already exempt from battery optimisation"); return }
        DiagLog.i(tag, "battery: asking Android to exempt ProkNet from battery optimisation")
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:" + packageName)))
        } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { toast("Not available on this phone") }
        }
    }

    // ---- v0.7 marketplace ------------------------------------------------------------------------

    private fun buy() {
        if (!node.isRunning) { toast("Press Start first"); return }
        if (node.tunnel.session != null || node.tunnel.contract != null || node.buyerWanted != null) {
            DiagLog.i(tag, "Stop buying pressed"); node.stopInternet("stopped by buyer"); refreshInternetLine(); return
        }
        val offers = node.offers()
        val sel = selected
        val peer = if (sel != null && sel.offer().selling) sel else null
        if (peer == null) {
            if (offers.isEmpty()) { toast("No Internet offers nearby (sellers show [SELL price])"); return }
            val labels = offers.map { it.describe() }.toTypedArray()
            AlertDialog.Builder(this).setTitle("Buy Internet from").setItems(labels) { _, i ->
                val p = peers.firstOrNull { it.shortId == offers[i].sellerShort } ?: return@setItems
                selected = p; txtSelected.text = "Selected: " + describe(p)
                startBuy(p)
            }.setNegativeButton("Cancel", null).show()
            return
        }
        startBuy(peer)
    }

    private fun startBuy(peer: Peer) {
        val o = peer.offer()
        if (!node.hasKey(peer.shortId)) { toast(peer.label + "'s key not known yet, wait a few seconds"); return }
        DiagLog.i(tag, "BUY pressed: prok-" + peer.shortId + " " + o.pricePerMb + " CFA/MB")
        if (!node.buy(peer)) toast("Cannot buy (see log)") else toast("Buying from prok-" + peer.shortId + " at " + o.pricePerMb + " CFA/MB: approve the Wi-Fi and VPN prompts")
        refreshInternetLine()
    }

    private fun sell() {
        if (!node.isRunning) { toast("Press Start first"); return }
        if (node.sellOn) { node.setSelling(false); toast("SELL off"); refreshInternetLine(); return }
        val layout = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        val price = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "price per MB (CFA)"; setText(node.sellPrice.toString()) }
        val min = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "minimum session price (CFA, 0 = none)"; setText(node.sellMinPrice.toString()) }
        val max = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "max MB per session (0 = unlimited)"; setText(node.sellMaxMb.toString()) }
        layout.addView(TextView(this).apply { text = "Price per MB (CFA)" }); layout.addView(price)
        layout.addView(TextView(this).apply { text = "Minimum session price (CFA)" }); layout.addView(min)
        layout.addView(TextView(this).apply { text = "Max MB per session (0 = unlimited)" }); layout.addView(max)
        layout.addView(TextView(this).apply { text = "Prok network fee: " + node.feePct + "% of what the buyer pays"; textSize = 12f })
        AlertDialog.Builder(this).setTitle("SELL Internet").setView(layout).setPositiveButton("Start selling") { _, _ ->
            val err = node.setSelling(true, price.text.toString().toIntOrNull() ?: -1, min.text.toString().toIntOrNull() ?: -1, max.text.toString().toIntOrNull() ?: -1)
            if (err != null) toast("Cannot sell: " + err)
            else if (!node.gateway.upstreamReady) toast("SELL on, but this phone has no Internet yet (turn mobile data on)")
            else toast("SELLING at " + node.sellPrice + " CFA/MB via " + node.gateway.upstreamDescription())
            refreshInternetLine()
        }.setNegativeButton("Cancel", null).show()
    }

    private fun ledgerDialog() {
        val entries = node.store.ledger(100)
        val bal = Market.balance(entries, node.me)
        if (entries.isEmpty()) { AlertDialog.Builder(this).setTitle("Ledger").setMessage("No entries yet. Finish a paid session first.").setPositiveButton("OK", null).show(); return }
        val labels = entries.map { e -> (if (e.payer == node.me) "I owe " else if (e.recipient == node.me) "owed to me " else "") + e.describe() + "  " + timeFmt.format(Date(e.ts)) }.toTypedArray()
        AlertDialog.Builder(this).setTitle("Ledger  (my balance: " + Market.cfa(bal) + ")").setItems(labels) { _, i ->
            val e = entries[i]
            val actions = ArrayList<String>()
            if (e.status == Market.ST_PENDING) {
                if (e.payer == node.me && e.paidAt == 0L) actions.add("paid")
                if (e.recipient == node.me && e.receivedAt == 0L) actions.add("received")
                actions.add("dispute"); if (e.paidAt == 0L && e.receivedAt == 0L) actions.add("cancel")
            }
            if (actions.isEmpty()) { toast("No action possible in state " + e.status); return@setItems }
            AlertDialog.Builder(this).setTitle(e.describe()).setItems(actions.map { "MARK AS " + it.uppercase() }.toTypedArray()) { _, j ->
                val err = node.ledgerAction(e.id, actions[j]); toast(err ?: "Marked " + actions[j]); refreshInternetLine()
            }.show()
        }.setPositiveButton("Close", null).show()
    }

    private fun historyDialog() {
        val sessions = node.store.sessions(30)
        if (sessions.isEmpty()) { AlertDialog.Builder(this).setTitle("History").setMessage("No sessions yet.").setPositiveButton("OK", null).show(); return }
        val lines = sessions.map { s ->
            val c = Market.Contract.decode(s.contract)
            val cp = Market.Checkpoint.decode(s.lastCheckpoint)
            val dur = if (s.endTs > 0) (s.endTs - s.startTs) / 1000 else 0
            (if (s.role == "buyer") "BOUGHT from " else "SOLD to ") + "prok-" + s.peerShort + "  " + java.text.SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(s.startTs)) +
                "\n  " + Market.mb(cp?.billable ?: 0) + " signed (" + Market.mb(s.bytesUp + s.bytesDown) + " counted), " + dur + " s, " + (c?.pricePerMb ?: 0) + " CFA/MB -> " +
                Market.cfa(s.finalCentimes) + "  [" + s.status + (if (s.disconnectReason.isNotEmpty()) ": " + s.disconnectReason else "") + "]"
        }
        AlertDialog.Builder(this).setTitle("Session history").setMessage(lines.joinToString("\n\n")).setPositiveButton("OK", null).show()
    }

    // ---- v0.6 Internet roles ------------------------------------------------------------------

    private fun toggleProvide() {
        if (!node.isRunning) { toast("Press Start first"); return }
        val on = !node.gateway.providing
        DiagLog.i(tag, (if (on) "Provide Internet ON" else "Provide Internet OFF") + " pressed")
        node.setProviding(on)
        if (on && !node.gateway.upstreamReady) toast("Provider enabled, but this phone has no Internet right now (turn mobile data on)")
        else if (on) toast("PROVIDER READY via " + node.gateway.upstreamDescription())
        refreshInternetLine()
    }

    private fun toggleUseInternet() {
        if (!node.isRunning) { toast("Press Start first"); return }
        if (node.tunnel.session != null || node.buyerWanted != null) {
            DiagLog.i(tag, "Stop Internet pressed")
            node.stopInternet("stopped by user"); refreshInternetLine(); return
        }
        val peer = selected ?: run { toast("Select the provider phone first"); return }
        if (!node.hasKey(peer.shortId)) { toast(peer.label + "'s key not known yet"); return }
        if (!peer.inRange && !node.wifi.canReach(peer.shortId)) { toast("Provider must be in BLE range"); return }
        DiagLog.i(tag, "Use Internet pressed -> prok-" + peer.shortId + (if (peer.providesInternet) " (advertises Internet)" else " (does NOT advertise Internet; trying anyway)"))
        if (!node.useInternet(peer)) toast("Cannot start (see log)") else toast("Connecting to prok-" + peer.shortId + " for Internet...")
        refreshInternetLine()
    }

    /** Called by the node when the provider accepted the session: ask Android for VPN consent, then start the VPN. */
    private fun startVpnWithConsent() {
        try {
            val intent = android.net.VpnService.prepare(this)
            if (intent != null) {
                DiagLog.i(tag, "VPN consent needed: Android shows 'connection request' - tap OK")
                toast("Android asks to allow the VPN: tap OK")
                startActivityForResult(intent, 6)
            } else onActivityResultVpn(RESULT_OK)
        } catch (e: Exception) { DiagLog.e(tag, "VPN prepare", e) }
    }

    private fun onActivityResultVpn(resultCode: Int) {
        if (resultCode == RESULT_OK) { DiagLog.i(tag, "VPN consent granted, starting VPN service"); ProkVpnService.start(this) }
        else { DiagLog.w(tag, "VPN consent DENIED: apps cannot use the tunnel; the in-app Net test still works"); toast("VPN not allowed: only the in-app test will work") }
        refreshInternetLine()
    }

    private fun netTest() {
        if (node.tunnel.session == null) { toast("No Internet session (press Use Internet first)"); return }
        toast("Testing DNS + HTTPS through the provider...")
        node.internetTest("example.com") { r -> runOnUiThread { AlertDialog.Builder(this).setTitle(if (r.ok) "Internet test OK" else "Internet test FAILED").setMessage(r.text).setPositiveButton("OK", null).show(); refreshInternetLine() } }
    }

    private fun refreshInternetLine() {
        val g = node.gateway; val t = node.tunnel
        val text = when {
            g.providing -> "SELL: " + g.state + " | " + node.sellPrice + " CFA/MB" + (if (node.sellMinPrice > 0) ", min " + node.sellMinPrice else "") + (if (node.sellMaxMb > 0) ", max " + node.sellMaxMb + " MB" else "") +
                " | upstream " + g.upstreamDescription() + " | sold " + Market.mb(g.totalSoldBytes) + ", earned " + Market.cfa(g.totalEarnedCentimes) +
                (g.session?.let { s -> "\nbuyer prok-" + s.peerShort + ": " + Market.mb(s.bytesUp + s.bytesDown) + " (up " + s.bytesUp + " B, down " + s.bytesDown + " B), " + (s.durationMs / 1000) + " s, running " + Market.cfa(g.runningCost()) +
                    ", agreed " + Market.cfa(g.agreedCost()) + " (checkpoint #" + (g.lastSigned?.seq ?: 0) + ")" } ?: "")
            t.session != null || t.contract != null || node.buyerWanted != null -> "BUY: " + t.state + (t.providerShort?.let { " from prok-" + it } ?: "") + " | VPN " + (if (ProkVpnService.running) "UP" else "off") +
                (t.contract?.let { c -> " | " + c.pricePerMb + " CFA/MB agreed" } ?: "") +
                (t.session?.let { s -> "\n" + Market.mb(s.bytesUp + s.bytesDown) + " used, " + (s.durationMs / 1000) + " s, running " + Market.cfa(t.runningCost()) + ", agreed " + Market.cfa(t.agreedCost()) +
                    " (checkpoint #" + (t.lastAccepted?.seq ?: 0) + "), flows " + t.activeFlows() + ", dns " + t.dnsCount } ?: "") +
                (if (t.disputed.isNotEmpty()) "\nDISPUTE: " + t.disputed else "") + (if (t.lastError.isNotEmpty()) "\nlast error: " + t.lastError else "")
            else -> "Market: " + node.offers().size + " Internet offer(s) nearby | balance " + Market.cfa(node.balance()) + (if (node.relayOn) " | RELAY on" else "") + " | spent " + Market.cfa(t.totalSpentCentimes)
        }
        txtInternet.text = text
        btnProvide.text = if (g.providing) "dev: stop" else "dev: provide"
        btnUseInternet.text = if (t.session != null || node.buyerWanted != null) "dev: stop" else "dev: use"
        findViewById<Button>(R.id.btnBuy).text = if (t.session != null || t.contract != null || node.buyerWanted != null) "STOP BUY" else "BUY"
        findViewById<Button>(R.id.btnSell).text = if (g.providing) "STOP SELL" else "SELL"
        findViewById<Button>(R.id.btnRelay).text = if (node.relayOn) "RELAY on" else "RELAY"
    }

    // ---- actions -----------------------------------------------------------------------------

    private fun sendMessage() {
        val peer = selected
        val text = editMessage.text.toString().trim()
        if (peer == null) { toast("Select a device first"); return }
        if (text.isEmpty()) { toast("Type a message"); return }
        if (!node.isRunning) { toast("Press Start first"); return }
        DiagLog.i(tag, "send " + text.length + " chars -> " + peer.label + (if (peer.inRange) "" else " (not in range: queued)"))
        if (!node.sendText(peer, text)) { toast("Cannot send: " + (if (!node.hasKey(peer.shortId)) peer.label + "'s key not known yet (bring it in range once)" else "see log")); return }
        editMessage.setText("")
        if (!peer.inRange) toast("Queued: will deliver when " + peer.label + " is back in range")
    }

    private fun wifiLink() {
        val peer = selected ?: run { toast("Select a peer first"); return }
        if (!node.isRunning) { toast("Press Start first"); return }
        if (!peer.inRange) { toast("Peer must be in BLE range to negotiate Wi-Fi"); return }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        if (!wm.isWifiEnabled) {
            DiagLog.w(tag, "Wi-Fi is off: opening the Wi-Fi panel (turn it on, then press Wi-Fi link again)")
            toast("Turn Wi-Fi on first (both phones)")
            try {
                if (Build.VERSION.SDK_INT >= 29) startActivity(Intent(Settings.Panel.ACTION_WIFI))
                else @Suppress("DEPRECATION") wm.setWifiEnabled(true)
            } catch (e: Exception) { DiagLog.w(tag, "wifi panel: " + e) }
            return
        }
        DiagLog.i(tag, "Wi-Fi link requested with " + peer.label)
        if (!node.requestWifi(peer)) toast("Cannot start Wi-Fi link now (see log)")
        else toast("Negotiating Wi-Fi with " + peer.label + ": answer the connect dialog if it appears")
    }

    private fun bigTest() {
        val peer = selected ?: run { toast("Select a peer first"); return }
        if (!node.isRunning) { toast("Press Start first"); return }
        val sizes = arrayOf("2 KB text (BLE, ~6 chunks)", "20 KB text (BLE slow / Wi-Fi fast)", "200 KB binary (asks for Wi-Fi first)", "1 MB binary (asks for Wi-Fi first)")
        AlertDialog.Builder(this).setTitle("Big test payload").setItems(sizes) { _, which ->
            val ok = when (which) {
                0 -> node.sendText(peer, ("ProkNet big test 2KB " + "0123456789 ").repeat(90))
                1 -> node.sendText(peer, ("ProkNet 20KB test line " + Date() + "\n").repeat(500))
                2 -> node.sendFile(peer, "test-200k.bin", ByteArray(200 * 1024) { (it * 31 + 7).toByte() })
                else -> node.sendFile(peer, "test-1m.bin", ByteArray(1024 * 1024) { (it * 131 + 3).toByte() })
            }
            if (!ok) toast("Cannot send: key of " + peer.label + " unknown, or node stopped") else toast("Transfer queued")
        }.show()
    }

    private fun pickFile() {
        if (selected == null) { toast("Select a peer first"); return }
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), 4)
        } catch (e: Exception) { toast("No file picker on this phone") }
    }

    private fun onFilePicked(uri: Uri) {
        val peer = selected ?: return
        var name = "file"
        try { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name } } catch (_: Exception) {}
        val bytes = try { contentResolver.openInputStream(uri)?.use { it.readBytes() } } catch (e: Exception) { null }
        if (bytes == null) { toast("Cannot read file"); return }
        if (bytes.size > Transfer.MAX_BLOB_BYTES - 4096) { toast("File too big for v0.5 (max ~2 MB)"); return }
        DiagLog.i(tag, "file picked: " + name + " (" + bytes.size + " bytes) -> " + peer.label)
        if (!node.sendFile(peer, name, bytes)) toast("Cannot send: key of " + peer.label + " unknown") else toast("File transfer queued (" + bytes.size / 1024 + " KB)")
    }

    private fun renameDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setText(node.identity.displayName)
        AlertDialog.Builder(this).setTitle("Display name (sent to peers with your key)").setView(input)
            .setPositiveButton("Save") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) { Identity.saveName(applicationContext, node.identity, n); refreshIdentity() }
            }.setNegativeButton("Cancel", null).show()
    }

    private fun copyLog() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ProkNet log", diagnosticText()))
        toast("Log copied to clipboard")
    }

    /** Short diagnostic: everything above the log plus the last 120 log lines. Fits a chat message. */
    private fun copyDiag() {
        val full = diagnosticText()
        val head = full.substringBefore("----- log -----")
        val tail = DiagLog.text().lines().takeLast(120).joinToString("\n")
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ProkNet diagnostic", head + "----- last 120 log lines -----\n" + tail + "\n"))
        toast("Diagnostic copied (short)")
    }

    private fun shareLog() {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "ProkNet Lab log " + node.identity.displayName)
            .putExtra(Intent.EXTRA_TEXT, diagnosticText())
        try { startActivity(Intent.createChooser(i, "Share ProkNet log")) } catch (e: Exception) { toast("No app to share with") }
    }

    private fun diagnosticText(): String {
        val pending = node.store.pending()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return "ProkNet Lab " + versionName + " (build " + versionCode + ") diagnostic\n" +
            "device: " + Build.MANUFACTURER + " " + Build.MODEL + " Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n" +
            "id: " + node.identity.idHex + " name: " + node.identity.displayName + "\n" +
            "fingerprint: " + node.identity.fingerprint + (node.identity.legacyIdHex?.let { " (legacy id " + it.substring(0, 8) + ")" } ?: "") + "\n" +
            "service: " + (if (ProkNetService.running) "RUNNING" else "stopped") + ", battery-exempt: " + pm.isIgnoringBatteryOptimizations(packageName) + "\n" +
            "status: " + node.statusLine() + "\n" +
            "ble health: " + node.bleHealthLine() + "\n" +
            "share check: " + node.shareCheck + " on " + net.prok.proknet.core.ShareCheck.describe(node.shareFreqMhz) + " [" + node.shareNetworkKey + "]" +
            (if (node.shareDetail.isNotEmpty()) " - " + node.shareDetail else "") + "\n" +
            (if (node.wifi.lastHotspotError.isNotEmpty()) "my hotspot error: " + node.wifi.lastHotspotError + "\n" else "") +
            (if (node.wifi.peerCancelDetail.isNotEmpty()) "provider refused, ITS error: " + node.wifi.peerCancelDetail + "\n" else "") +
            "ble: " + node.linkState(Routing.TRANSPORT_BLE) + " | sent " + node.ble.bytesSent + " B, recv " + node.ble.bytesReceived + " B\n" +
            "wifi: " + node.linkState(Routing.TRANSPORT_WIFI) + " | sent " + node.wifi.bytesSent + " B, recv " + node.wifi.bytesReceived + " B\n" +
            node.bulk.diag() + ProkNetApp.coverage(this).diag() +
            "peers: " + peers.joinToString("; ") { describe(it) } + "\n" +
            "keys known: " + node.store.peerKeyCount() + "\n" +
            "market: SELL " + (if (node.sellOn) "on " + node.sellPrice + " CFA/MB min " + node.sellMinPrice + " max " + node.sellMaxMb + " MB" else "off") + ", RELAY " + node.relayOn + ", fee " + node.feePct + "%, offers nearby " + node.offers().joinToString("; ") { it.describe() } + "\n" +
            "selected offer: " + (selected?.offer()?.describe() ?: "none") + "\n" +
            "buyer contract: " + (node.tunnel.contract?.let { c -> c.sessionHex.substring(0, 8) + " " + c.pricePerMb + " CFA/MB min " + c.minPriceCfa + " max " + c.maxMb + " fee " + c.feePct + "%" } ?: "none") +
            " | seller contract: " + (node.gateway.contract?.let { c -> c.sessionHex.substring(0, 8) + " " + c.pricePerMb + " CFA/MB" } ?: "none") + "\n" +
            "billable/cost: buyer " + (node.tunnel.session?.let { Market.mb(it.bytesUp + it.bytesDown) } ?: "-") + " running " + Market.cfa(node.tunnel.runningCost()) + " agreed " + Market.cfa(node.tunnel.agreedCost()) + " cp#" + (node.tunnel.lastAccepted?.seq ?: 0) +
            " | seller " + (node.gateway.session?.let { Market.mb(it.bytesUp + it.bytesDown) } ?: "-") + " running " + Market.cfa(node.gateway.runningCost()) + " agreed " + Market.cfa(node.gateway.agreedCost()) + " cp#" + (node.gateway.lastSigned?.seq ?: 0) + "\n" +
            "ledger: balance " + Market.cfa(node.balance()) + ", entries " + node.store.ledger(5).joinToString("; ") { it.describe() } + "\n" +
            "relay activity: " + node.store.relayStats().let { it.first.toString() + " packets forwarded, " + it.second + " B" } + "\n" +
            "live relay: " + node.relay.stateLine() + " | up link " + node.wifiUp.linkState() + "\n" +
            "provider: " + node.gateway.state + ", upstream " + node.gateway.upstreamDescription() + ", streams " + node.gateway.activeStreams() + " [" + node.gateway.streamSummary() + "]\n" +
            "buyer: " + node.tunnel.state + ", vpn " + ProkVpnService.running + ", flows " + node.tunnel.activeFlows() + " [" + node.tunnel.flowSummary() + "], dns " + node.tunnel.dnsCount + ", last error: " + node.tunnel.lastError + "\n" +
            (node.tunnel.session ?: node.gateway.session)?.let { "session: " + it.summary() + "\n" }.orEmpty() +
            (node.tunnel.history + node.gateway.history).take(5).joinToString("") { "  past session: " + it.summary() + "\n" } +
            "tunnel bytes on link: sent " + node.wifi.tunnelBytesSent + " recv " + node.wifi.tunnelBytesReceived + "\n" +
            (if (node.lastInternetTest.isNotEmpty()) "last net test: " + node.lastInternetTest.replace("\n", " | ") + "\n" else "") +
            "messages stored: " + node.store.count() + ", pending: " + pending.size + ", carrying: " + node.store.carryingCount() + "\n" +
            pending.joinToString("") { "  pending msg=" + it.msgId + " to " + it.peerName + " attempts=" + it.attempts + " last=" + it.lastError + "\n" } +
            node.store.carrying().joinToString("") { "  carrying msg=" + it.msgId + " from " + it.peerName + " for prok-" + it.destShort + " hops=" + it.hops + "/" + it.ttl + " enc=" + it.enc + "\n" } +
            node.store.transfers(20).joinToString("") { "  transfer " + it.tid + " " + it.direction + " " + it.peerName + " " + it.status + " " + it.progressPercent + "% " + it.dataSize + "B via " + it.transport + " " + it.error + "\n" } +
            "----- log -----\n" + DiagLog.text() + "\n"
    }

    // ---- node listener -----------------------------------------------------------------------

    private fun describe(p: Peer): String {
        val o = p.offer()
        val key = (if (node.hasKey(p.shortId)) "[key] " else "[no key] ") + (if (o.selling) "[SELL " + o.pricePerMb + " CFA/MB " + Tunnel.upstreamName(o.upstreamType) + (if (o.validated) "" else " unverified") + "] " else "") + (if (o.relaying) "[RELAY] " else "")
        val name = node.peerName(p.shortId).let { if (it != p.label) " \"" + it + "\"" else "" }
        val wifi = if (node.wifi.linkedPeer == p.shortId) " [WIFI UP]" else ""
        return key + p.describe() + name + wifi
    }

    override fun onPeers(peers: List<Peer>) {
        this.peers = peers
        peersAdapter.clear()
        peersAdapter.addAll(peers.map { describe(it) })
        peersAdapter.notifyDataSetChanged()
        val sel = selected
        if (sel != null) {
            val still = peers.firstOrNull { it.shortId == sel.shortId }
            if (still != null) { selected = still; txtSelected.text = "Selected: " + describe(still) }
        }
        refreshDiag()
    }

    override fun onMessagesChanged() {
        val rows = ArrayList<Pair<Long, String>>()
        for (m in node.store.recent(60)) {
            val extra = when (m.status) {
                MsgStatus.PENDING, MsgStatus.CARRYING -> if (m.attempts > 0) " try " + m.attempts else ""
                MsgStatus.HANDED_OFF -> " via prok-" + m.via + ", not final"
                MsgStatus.FORWARDED -> " to prok-" + m.destShort
                MsgStatus.FAILED, MsgStatus.EXPIRED -> " " + m.lastError.take(40)
                MsgStatus.RECEIVED -> (if (m.via.isNotEmpty()) " via prok-" + m.via + ", " + m.hops + " hop" else "") + (if (m.verified == 1) ", signed" else if (m.enc == 1) ", unverified" else "")
                else -> ""
            }
            val lock = if (m.enc == 1) "e2e " else if (m.direction != Dir.CARRY) "PLAIN " else ""
            val head = when (m.direction) {
                Dir.IN -> "<- " + m.peerName
                Dir.CARRY -> "~ carrying " + m.peerName + " -> prok-" + m.destShort
                else -> "-> " + m.peerName
            }
            val tr = if (m.transport.isNotEmpty()) " " + m.transport else ""
            rows.add(m.timestamp to (timeFmt.format(Date(m.timestamp)) + " " + head + " [" + lock + m.status + extra + tr + "]: " + (if (m.direction == Dir.CARRY) "(opaque)" else m.text.take(200))))
        }
        for (t in node.store.transfers(20)) {
            val head = if (t.direction == Dir.IN) "<= " + t.peerName else "=> " + t.peerName
            val what = (if (t.kind == Transfer.BLOB_TEXT) "text" else "file " + t.name) + " " + t.dataSize + " B"
            val prog = if (t.status == MsgStatus.SENDING || t.status == MsgStatus.RECEIVING || t.status == MsgStatus.PENDING) " " + t.progressPercent + "%" else ""
            val body = node.engine.receivedText(t, 120)?.let { ": " + it.replace("\n", " ") } ?: ""
            rows.add(t.timestamp to (timeFmt.format(Date(t.timestamp)) + " " + head + " [xfer e2e " + t.status + prog + (if (t.transport.isNotEmpty()) " " + t.transport else "") + "] " + what + (if (t.error.isNotEmpty()) " (" + t.error.take(40) + ")" else "") + body))
        }
        rows.sortBy { it.first }
        messagesAdapter.clear()
        messagesAdapter.addAll(rows.map { it.second })
        messagesAdapter.notifyDataSetChanged()
        refreshDiag()
    }

    override fun onStatus(status: String) {
        txtStatus.text = status
        refreshServiceLine()
        refreshDiag()
        refreshInternetLine()
    }

    private fun refreshDiag() {
        val phase = node.wifi.phase
        when {
            node.wifi.approvalNeeded -> {
                txtWifiBanner.visibility = android.view.View.VISIBLE
                txtWifiBanner.setBackgroundColor(0xFFFFE082.toInt())
                txtWifiBanner.text = "WI-FI: " + phase + "\nAndroid will ask to connect to a network: tap CONNECT. Keep this screen open."
            }
            phase == "WIFI UP" -> {
                txtWifiBanner.visibility = android.view.View.VISIBLE
                txtWifiBanner.setBackgroundColor(0xFFB9F6CA.toInt())
                txtWifiBanner.text = "WI-FI UP with prok-" + (node.wifi.linkedPeer ?: "?") + " - big transfers go over Wi-Fi now"
            }
            phase == "IDLE" || phase.startsWith("DOWN") -> txtWifiBanner.visibility = android.view.View.GONE
            else -> {
                txtWifiBanner.visibility = android.view.View.VISIBLE
                txtWifiBanner.setBackgroundColor(0xFFE3F2FD.toInt())
                txtWifiBanner.text = "WI-FI: " + phase + "   (REQUESTING > OFFERED > JOINING > TCP > AUTH > WIFI UP)"
            }
        }
        val xfer = node.engine.inFlight?.let { "transfer " + it.substring(0, 6) + " " + node.engine.inFlightProgress + "%" } ?: "no transfer"
        val active = node.wifi.linkedPeer?.let { "wifi (prok-" + it + ")" } ?: "ble"
        txtDiag.text = "fp " + node.identity.fingerprint + "\n" +
            "ble: " + node.linkState(Routing.TRANSPORT_BLE) + "  tx " + node.ble.bytesSent + " rx " + node.ble.bytesReceived + "\n" +
            "wifi: " + node.linkState(Routing.TRANSPORT_WIFI) + "  tx " + node.wifi.bytesSent + " rx " + node.wifi.bytesReceived + "\n" +
            "active transport: " + active + " | " + xfer + " | e2e: on (P-256 + AES-GCM) | keys " + node.store.peerKeyCount()
    }

    private fun refreshServiceLine() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        txtService.text = "Service: " + (if (ProkNetService.running) "RUNNING (background OK)" else "STOPPED") +
            "   battery-exempt: " + (if (pm.isIgnoringBatteryOptimizations(packageName)) "yes" else "no")
    }

    private fun refreshIdentity() {
        txtIdentity.text = "ProkNet Lab v" + versionName + "  |  " + node.identity.displayName + "  |  id " + node.identity.shortIdHex
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
