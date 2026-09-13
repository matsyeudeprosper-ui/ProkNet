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

/**
 * ProkNet Lab screen. Deliberately plain: one Activity, stock widgets,
 * everything visible. The Activity is only a window onto the node, which is
 * owned by the Application and driven by ProkNetService.
 */
class MainActivity : Activity(), ProkNetNode.Listener {
    private val tag = "UI"
    private lateinit var node: ProkNetNode

    private lateinit var txtIdentity: TextView
    private lateinit var txtService: TextView
    private lateinit var txtStatus: TextView
    private lateinit var txtDiag: TextView
    private lateinit var txtWifiBanner: TextView
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
        setContentView(R.layout.activity_main)
        node = ProkNetApp.node(this)

        txtIdentity = findViewById(R.id.txtIdentity)
        txtService = findViewById(R.id.txtService)
        txtStatus = findViewById(R.id.txtStatus)
        txtDiag = findViewById(R.id.txtDiag)
        txtWifiBanner = findViewById(R.id.txtWifiBanner)
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
            "ble: " + node.linkState(Routing.TRANSPORT_BLE) + " | sent " + node.ble.bytesSent + " B, recv " + node.ble.bytesReceived + " B\n" +
            "wifi: " + node.linkState(Routing.TRANSPORT_WIFI) + " | sent " + node.wifi.bytesSent + " B, recv " + node.wifi.bytesReceived + " B\n" +
            "peers: " + peers.joinToString("; ") { describe(it) } + "\n" +
            "keys known: " + node.store.peerKeyCount() + "\n" +
            "messages stored: " + node.store.count() + ", pending: " + pending.size + ", carrying: " + node.store.carryingCount() + "\n" +
            pending.joinToString("") { "  pending msg=" + it.msgId + " to " + it.peerName + " attempts=" + it.attempts + " last=" + it.lastError + "\n" } +
            node.store.carrying().joinToString("") { "  carrying msg=" + it.msgId + " from " + it.peerName + " for prok-" + it.destShort + " hops=" + it.hops + "/" + it.ttl + " enc=" + it.enc + "\n" } +
            node.store.transfers(20).joinToString("") { "  transfer " + it.tid + " " + it.direction + " " + it.peerName + " " + it.status + " " + it.progressPercent + "% " + it.dataSize + "B via " + it.transport + " " + it.error + "\n" } +
            "----- log -----\n" + DiagLog.text() + "\n"
    }

    // ---- node listener -----------------------------------------------------------------------

    private fun describe(p: Peer): String {
        val key = if (node.hasKey(p.shortId)) "[key] " else "[no key] "
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
