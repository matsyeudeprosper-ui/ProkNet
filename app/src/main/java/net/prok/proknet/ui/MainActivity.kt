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
import net.prok.proknet.core.Identity
import net.prok.proknet.core.MsgStatus
import net.prok.proknet.service.ProkNetService

/**
 * ProkNet Lab screen. Deliberately plain: one Activity, stock widgets,
 * everything visible. Functionality over design.
 *
 * Milestone 2B: the Activity is only a window onto the node. The node is
 * owned by the Application and driven by ProkNetService; closing this screen
 * changes nothing about discovery or delivery.
 */
class MainActivity : Activity(), ProkNetNode.Listener {
    private val tag = "UI"
    private lateinit var node: ProkNetNode

    private lateinit var txtIdentity: TextView
    private lateinit var txtService: TextView
    private lateinit var txtStatus: TextView
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
            txtSelected.text = "Selected: " + (selected?.describe() ?: "none")
            DiagLog.i(tag, "selected " + selected?.label + (if (selected?.inRange == false) " (not in range, messages will queue)" else ""))
        }
        messagesAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        listMessages.adapter = messagesAdapter

        findViewById<Button>(R.id.btnStart).setOnClickListener { startNode() }
        findViewById<Button>(R.id.btnStop).setOnClickListener { stopNode() }
        findViewById<Button>(R.id.btnRename).setOnClickListener { renameDialog() }
        findViewById<Button>(R.id.btnRetry).setOnClickListener {
            if (!node.isRunning) toast("Press Start first") else node.queue.retryAllNow()
        }
        findViewById<Button>(R.id.btnBattery).setOnClickListener { batterySettings() }
        findViewById<Button>(R.id.btnSend).setOnClickListener { sendMessage() }
        editMessage.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { sendMessage(); true } else false
        }
        findViewById<Button>(R.id.btnCopyLog).setOnClickListener { copyLog() }
        findViewById<Button>(R.id.btnShareLog).setOnClickListener { shareLog() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener { DiagLog.clear(); txtLog.text = "" }

        refreshIdentity()
        DiagLog.i(tag, "activity created; device: " + Build.MANUFACTURER + " " + Build.MODEL + " Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
    }

    override fun onStart() {
        super.onStart()
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
        DiagLog.i(tag, "activity hidden (background) - node continues in the service: " + node.statusLine())
        node.removeListener(this)
        DiagLog.removeListener(logListener)
        super.onStop()
    }

    // ---- start / stop / permissions ------------------------------------------------------------

    private fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT,
        ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun missingPermissions() = requiredPermissions().filter {
        checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

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
        if (Build.VERSION.SDK_INT < 31) {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val locOn = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            if (!locOn) DiagLog.w(tag, "Location services are OFF. On Android 8-11 BLE scanning finds nothing until Location is turned on.")
        }
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
            startNodeSkippingNotificationPrompt()
            return
        }
        if (denied.isEmpty()) { DiagLog.i(tag, "permissions granted"); startNode() }
        else DiagLog.e(tag, "permissions DENIED: " + denied.joinToString() + " - grant them in Settings > Apps > ProkNet Lab > Permissions")
    }

    private var skipNotifPrompt = false
    private fun startNodeSkippingNotificationPrompt() {
        skipNotifPrompt = true
        try {
            if (missingPermissions().isEmpty() && node.isBluetoothOn()) { ProkNetService.start(this); txtService.postDelayed({ refreshServiceLine() }, 1500) }
            else startNode()
        } finally { skipNotifPrompt = false }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2) {
            if (resultCode == RESULT_OK) { DiagLog.i(tag, "Bluetooth enabled"); startNode() }
            else DiagLog.w(tag, "user declined to enable Bluetooth")
        }
    }

    private fun batterySettings() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Already exempt from battery optimisation"); DiagLog.i(tag, "battery: already exempt"); return
        }
        DiagLog.i(tag, "battery: asking Android to exempt ProkNet from battery optimisation")
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:" + packageName)))
        } catch (e: Exception) {
            DiagLog.w(tag, "battery: dialog not available (" + e + "), opening the settings list instead")
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
        DiagLog.i(tag, "send \"" + text + "\" -> " + peer.label + (if (peer.inRange) "" else " (not in range: queued)"))
        node.sendText(peer, text)
        editMessage.setText("")
        if (!peer.inRange) toast("Queued: will deliver when " + peer.label + " is back in range")
    }

    private fun renameDialog() {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setText(node.identity.displayName)
        AlertDialog.Builder(this).setTitle("Display name (local only)").setView(input)
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
        return "ProkNet Lab v0.3 diagnostic\n" +
            "device: " + Build.MANUFACTURER + " " + Build.MODEL + " Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\n" +
            "id: " + node.identity.idHex + " name: " + node.identity.displayName + "\n" +
            "service: " + (if (ProkNetService.running) "RUNNING" else "stopped") + ", battery-exempt: " + pm.isIgnoringBatteryOptimizations(packageName) + "\n" +
            "status: " + node.statusLine() + "\n" +
            "peers: " + peers.joinToString("; ") { it.describe() } + "\n" +
            "messages stored: " + node.store.count() + ", pending: " + pending.size + "\n" +
            pending.joinToString("") { "  pending msg=" + it.msgId + " to " + it.peerName + " attempts=" + it.attempts + " last=" + it.lastError + "\n" } +
            "----- log -----\n" + DiagLog.text() + "\n"
    }

    // ---- node listener -----------------------------------------------------------------------

    override fun onPeers(peers: List<Peer>) {
        this.peers = peers
        peersAdapter.clear()
        peersAdapter.addAll(peers.map { it.describe() })
        peersAdapter.notifyDataSetChanged()
        val sel = selected
        if (sel != null) {
            val still = peers.firstOrNull { it.shortId == sel.shortId }
            if (still != null) { selected = still; txtSelected.text = "Selected: " + still.describe() }
        }
    }

    override fun onMessagesChanged() {
        val rows = node.store.recent(60).map { m ->
            val arrow = if (m.direction == "in") "<- " else "-> "
            val extra = when (m.status) {
                MsgStatus.PENDING -> if (m.attempts > 0) " try " + m.attempts else ""
                MsgStatus.FAILED, MsgStatus.EXPIRED -> " " + m.lastError.take(40)
                else -> ""
            }
            timeFmt.format(Date(m.timestamp)) + " " + arrow + m.peerName + " [" + m.status + extra + "]: " + m.text
        }
        messagesAdapter.clear()
        messagesAdapter.addAll(rows)
        messagesAdapter.notifyDataSetChanged()
    }

    override fun onStatus(status: String) {
        txtStatus.text = status
        refreshServiceLine()
    }

    private fun refreshServiceLine() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        txtService.text = "Service: " + (if (ProkNetService.running) "RUNNING (background OK)" else "STOPPED") +
            "   battery-exempt: " + (if (pm.isIgnoringBatteryOptimizations(packageName)) "yes" else "no")
    }

    private fun refreshIdentity() {
        txtIdentity.text = "ProkNet Lab v0.3  |  " + node.identity.displayName + "  |  id " + node.identity.shortIdHex
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
