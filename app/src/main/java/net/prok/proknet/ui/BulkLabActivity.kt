package net.prok.proknet.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import net.prok.proknet.ProkNetApp
import net.prok.proknet.R
import net.prok.proknet.ble.Peer
import net.prok.proknet.ble.ProkNetNode
import net.prok.proknet.core.BtLabText
import net.prok.proknet.core.BulkPlan
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Tunnel
import net.prok.proknet.node.RelayProbe
import net.prok.proknet.service.ProkNetService
import net.prok.proknet.vpn.ProkVpnService

/**
 * v0.10.2 Bluetooth test screen. One button per phone: the seller taps
 * START SHARING, the buyer taps CONNECT, and the signed handshake, the
 * sequential probe, the contract, the tunnel, the VPN and the Internet test
 * follow by themselves. The words come from [BtLabText]; the developer
 * detail stays underneath.
 */
class BulkLabActivity : Activity(), ProkNetNode.Listener {
    private val tag = "BTLAB"
    private lateinit var node: ProkNetNode
    private val main = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable { override fun run() { refresh(); main.postDelayed(this, 1000) } }
    private var pendingAfterStart: (() -> Unit)? = null
    private var startPolls = 0
    private val pollStarted = object : Runnable { override fun run() { onPollStarted() } }
    /** The automatic Internet test: null = not run for this session, else its result. */
    private var httpsOk: Boolean? = null
    private var httpsTesting = false
    private var httpsText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bulk_lab)
        node = ProkNetApp.node(this)
        findViewById<Button>(R.id.btnBulkSell).setOnClickListener { whenNodeRunning { startSharing() } }
        findViewById<Button>(R.id.btnBulkBuy).setOnClickListener { whenNodeRunning { connect() } }
        findViewById<Button>(R.id.btnBulkStop).setOnClickListener {
            node.stopInternet("stopped from the Bluetooth test screen")
            if (node.sellOn) node.setSelling(false)
            node.preferBluetooth = false
            httpsOk = null; httpsText = ""
            toast("Stopped")
            refresh()
        }
        findViewById<Button>(R.id.btnBulkCopyResult).setOnClickListener { copy(BtLabText.summary(snapshot()) + "\nDetailed diagnostic:\n" + diagText(), "Test result copied") }
        findViewById<Button>(R.id.btnBulkDiag).setOnClickListener { copy(diagText(), "Diagnostic copied") }
    }

    override fun onStart() {
        super.onStart()
        ProkNetApp.visibleActivities++
        node.addListener(this)
        node.vpnRequested = { startVpnWithConsent() }
        main.post(ticker)
    }

    override fun onStop() {
        ProkNetApp.visibleActivities = maxOf(0, ProkNetApp.visibleActivities - 1)
        main.removeCallbacks(ticker)
        node.removeListener(this)
        super.onStop()
    }

    // ---- the two buttons ------------------------------------------------------------------------------------

    private fun startSharing() {
        val err = node.setSelling(true)
        if (err != null) toast(err) else DiagLog.i(tag, "BT test: START SHARING")
        refresh()
    }

    private fun connect() {
        if (node.gateway.providing) { toast("This phone is the seller here: tap STOP first"); return }
        val offer = node.offers().firstOrNull { it.bulkBt } ?: run { toast("No seller found yet. Is the other phone sharing?"); return }
        val peer = node.peers().firstOrNull { it.shortId == offer.sellerShort } ?: run { toast("Seller not reachable yet, try again"); return }
        node.preferBluetooth = true
        httpsOk = null; httpsText = ""
        DiagLog.i(tag, "BT test: CONNECT to prok-" + peer.shortId + " at " + offer.pricePerMb + " CFA/MB")
        if (!node.buy(peer)) toast("Cannot start (see the details below)")
        refresh()
    }

    /** The user should not have to visit the developer screen first: start the node here if needed. */
    private fun whenNodeRunning(action: () -> Unit) {
        if (node.isRunning) { action(); return }
        val missing = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) { pendingAfterStart = action; requestPermissions(missing.toTypedArray(), 1); return }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingAfterStart = action; requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3); return
        }
        if (!node.isBluetoothOn()) {
            pendingAfterStart = action
            try { startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 2) } catch (e: Exception) { DiagLog.e(tag, "enable BT", e) }
            return
        }
        DiagLog.i(tag, "BT test: starting the node")
        ProkNetService.start(this)
        pendingAfterStart = action; startPolls = 0
        main.postDelayed(pollStarted, 500)
    }

    private fun onPollStarted() {
        if (node.isRunning) { val a = pendingAfterStart; pendingAfterStart = null; a?.invoke(); return }
        if (++startPolls < 20) main.postDelayed(pollStarted, 500) else { pendingAfterStart = null; toast("The node did not start (see the details below)") }
    }

    private fun requiredPermissions(): List<String> {
        val p = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) { p += Manifest.permission.BLUETOOTH_SCAN; p += Manifest.permission.BLUETOOTH_ADVERTISE; p += Manifest.permission.BLUETOOTH_CONNECT }
        else p += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.NEARBY_WIFI_DEVICES
        else if (Manifest.permission.ACCESS_FINE_LOCATION !in p) p += Manifest.permission.ACCESS_FINE_LOCATION
        if (Manifest.permission.ACCESS_FINE_LOCATION in p) p += Manifest.permission.ACCESS_COARSE_LOCATION
        return p
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val a = pendingAfterStart ?: return
        pendingAfterStart = null
        if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) whenNodeRunning(a) else toast("Permission refused: the test cannot run")
    }

    // ---- the automatic steps ---------------------------------------------------------------------------------------

    private fun startVpnWithConsent() {
        try {
            val intent = android.net.VpnService.prepare(this)
            if (intent != null) { DiagLog.i(tag, "VPN consent needed"); startActivityForResult(intent, 6) } else onVpn(RESULT_OK)
        } catch (e: Exception) { DiagLog.e(tag, "VPN prepare", e) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 6) onVpn(resultCode)
        if (requestCode == 2) { val a = pendingAfterStart; pendingAfterStart = null; if (a != null) whenNodeRunning(a) }
    }

    private fun onVpn(resultCode: Int) {
        if (resultCode == RESULT_OK) { DiagLog.i(tag, "VPN consent granted"); ProkVpnService.start(this) }
        else toast("VPN refused: the phone cannot use the Internet, only the in-app test")
        refresh()
    }

    /** Once the tunnel is up, test the Internet by ourselves, once per session. */
    private fun autoInternetTest() {
        if (node.tunnel.session == null) { if (httpsOk != null) { httpsOk = null; httpsText = "" }; httpsTesting = false; return }
        if (httpsOk != null || httpsTesting) return
        httpsTesting = true
        DiagLog.i(tag, "BT test: tunnel up, testing HTTPS through the seller")
        node.internetTest("example.com") { r -> runOnUiThread { httpsTesting = false; httpsOk = r.ok; httpsText = r.text; refresh() } }
    }

    // ---- the words -----------------------------------------------------------------------------------------------------

    private fun snapshot(): BtLabText.Snapshot {
        val role = if (node.gateway.providing) BtLabText.Role.SELLER else if (node.buyerWanted != null || node.tunnel.session != null) BtLabText.Role.BUYER else BtLabText.Role.NONE
        val up = node.gateway.upstream
        val b = node.bulk
        return BtLabText.Snapshot(
            role = role, nodeRunning = node.isRunning, bluetoothOn = b.isBluetoothOn,
            upstream = if (up == null) "none" else Tunnel.upstreamName(Tunnel.upstreamType(up)),
            upstreamValidated = up?.validated == true,
            sellerFound = node.offers().any { it.bulkBt },
            phase = b.state.phase, authenticated = b.state.authenticated,
            probeStep = b.probeStep, buyerToSeller = b.probeBuyerToSeller, sellerToBuyer = b.probeSellerToBuyer, verdict = b.lastVerdict,
            contract = node.tunnel.contract != null, tunnelState = node.tunnel.state, vpn = ProkVpnService.running, dnsCount = node.tunnel.dnsCount,
            httpsOk = httpsOk, customerConnected = node.gateway.session != null,
            lastError = node.tunnel.lastError.ifEmpty { node.lastBuyError }.ifEmpty { if (b.state.phase == BulkPlan.Phase.FAILED) b.lastError else "" })
    }

    private fun refresh() {
        if (isFinishing) return
        autoInternetTest()
        val s = snapshot()
        val seller = s.role == BtLabText.Role.SELLER
        val buyer = s.role == BtLabText.Role.BUYER
        findViewById<TextView>(R.id.txtBulkRole).text = when (s.role) { BtLabText.Role.SELLER -> "SELLER"; BtLabText.Role.BUYER -> "BUYER"; else -> "ProkNet Bluetooth" }
        findViewById<TextView>(R.id.txtBulkReady).text =
            if (buyer) (if (s.sellerFound || s.authenticated) "Seller found ✅" else "") else BtLabText.sellerReadyLines(s).joinToString("\n")
        findViewById<Button>(R.id.btnBulkSell).isEnabled = !seller && !buyer
        findViewById<Button>(R.id.btnBulkBuy).isEnabled = !seller && !buyer && s.sellerFound
        findViewById<TextView>(R.id.txtBulkHeadline).text = if (seller) BtLabText.sellerStatus(s) else BtLabText.buyerStatus(s)
        findViewById<TextView>(R.id.txtBulkLines).text =
            if (buyer && (s.authenticated || s.vpn)) BtLabText.buyerDetailLines(s).joinToString("\n")
            else if (!seller && !buyer) (if (s.sellerFound) "Seller found ✅  tap CONNECT to be the buyer\nor tap START SHARING to be the seller" else "Tap START SHARING to be the seller.\nThe buyer sees CONNECT once a seller is found.")
            else ""
        findViewById<TextView>(R.id.txtBulkState).text = detailText(12)
    }

    private fun detailText(logLines: Int): String {
        val sb = StringBuilder()
        sb.append("me: prok-").append(node.identity.shortIdHex).append(" | node ").append(if (node.isRunning) "RUNNING" else "STOPPED").append("\n")
        sb.append(node.bulk.diag())
        sb.append("offers: ").append(node.offers().joinToString("; ") { it.describe() + (if (it.bulkBt) " [BT]" else "") }.ifEmpty { "none" }).append("\n")
        sb.append("seller: ").append(node.gateway.state).append(" upstream ").append(node.gateway.upstreamDescription())
            .append(node.gateway.session?.let { " | customer prok-" + it.peerShort + " up " + it.bytesUp + " B down " + it.bytesDown + " B" } ?: "").append("\n")
        sb.append("buyer: ").append(node.tunnel.state).append(" vpn ").append(ProkVpnService.running)
            .append(" flows ").append(node.tunnel.activeFlows()).append(" dns ").append(node.tunnel.dnsCount)
            .append(node.tunnel.session?.let { " | up " + it.bytesUp + " B down " + it.bytesDown + " B" } ?: "").append("\n")
        if (httpsText.isNotEmpty()) sb.append("https test: ").append(httpsText.replace("\n", " | ")).append("\n")
        if (node.lastBuyError.isNotEmpty()) sb.append("last buy error: ").append(node.lastBuyError).append("\n")
        sb.append("--- last ").append(logLines).append(" log lines ---\n").append(DiagLog.text().lines().takeLast(logLines).joinToString("\n"))
        return sb.toString()
    }

    private fun diagText(): String =
        "Prok BLUETOOTH DIAG " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "\n" +
            "me: prok-" + node.identity.shortIdHex + " \"" + node.identity.displayName + "\"\n" +
            "status: " + node.statusLine() + "\n" +
            "--- networks ---\n" + RelayProbe.networks(this).joinToString("\n") + "\n" +
            "interfaces: " + RelayProbe.interfaces().joinToString(" ") + "\n" +
            detailText(150) + "\n"

    private fun copy(text: String, msg: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("ProkNet Bluetooth test", text))
        DiagLog.i(tag, msg + " (" + text.length + " chars)")
        toast(msg)
    }

    override fun onPeers(peers: List<Peer>) {}
    override fun onMessagesChanged() {}
    override fun onStatus(status: String) { refresh() }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
