package net.prok.proknet.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import net.prok.proknet.core.DiagLog
import net.prok.proknet.node.RelayProbe
import net.prok.proknet.vpn.ProkVpnService

/**
 * v0.10.0 Bluetooth Bulk Internet Lab. One button per side, the real
 * purchase path underneath: SELL is the normal SELL, BUY OVER BLUETOOTH is
 * the normal BUY with Bluetooth preferred, and everything from the signed
 * handshake to the VPN is the production stack.
 */
class BulkLabActivity : Activity(), ProkNetNode.Listener {
    private val tag = "BTLAB"
    private lateinit var node: ProkNetNode
    private val main = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable { override fun run() { refresh(); main.postDelayed(this, 2000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bulk_lab)
        node = ProkNetApp.node(this)
        findViewById<Button>(R.id.btnBulkSell).setOnClickListener {
            if (!node.isRunning) { toast("Start the node first (Developer screen)"); return@setOnClickListener }
            val err = node.setSelling(true)
            toast(err ?: "Sharing Internet: this phone will serve over Bluetooth when asked")
            refresh()
        }
        findViewById<Button>(R.id.btnBulkBuy).setOnClickListener { buyOverBluetooth() }
        findViewById<Button>(R.id.btnBulkStop).setOnClickListener {
            node.stopInternet("stopped from the Bluetooth lab")
            if (node.sellOn) node.setSelling(false)
            node.preferBluetooth = false
            toast("Stopped")
            refresh()
        }
        findViewById<Button>(R.id.btnBulkNetTest).setOnClickListener { netTest() }
        findViewById<Button>(R.id.btnBulkDiag).setOnClickListener { copyDiag() }
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

    private fun buyOverBluetooth() {
        if (!node.isRunning) { toast("Start the node first (Developer screen)"); return }
        if (node.gateway.providing) { toast("This phone is the seller here: stop selling first"); return }
        val offer = node.offers().firstOrNull() ?: run { toast("No provider offer visible over BLE yet"); return }
        val peer = node.peers().firstOrNull { it.shortId == offer.sellerShort } ?: run { toast("Provider not in the peer list"); return }
        if (!offer.bulkBt) { toast("prok-" + peer.shortId + " does not advertise Bluetooth bulk (older build, or its Bluetooth is off)"); return }
        node.preferBluetooth = true
        DiagLog.i(tag, "BT lab: BUY OVER BLUETOOTH from prok-" + peer.shortId + " at " + offer.pricePerMb + " CFA/MB")
        if (!node.buy(peer)) toast("Cannot start the purchase (see log)") else toast("Asking prok-" + peer.shortId + " for a Bluetooth channel...")
        refresh()
    }

    private fun startVpnWithConsent() {
        try {
            val intent = android.net.VpnService.prepare(this)
            if (intent != null) { DiagLog.i(tag, "VPN consent needed"); startActivityForResult(intent, 6) } else onVpn(RESULT_OK)
        } catch (e: Exception) { DiagLog.e(tag, "VPN prepare", e) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 6) onVpn(resultCode)
    }

    private fun onVpn(resultCode: Int) {
        if (resultCode == RESULT_OK) { DiagLog.i(tag, "VPN consent granted"); ProkVpnService.start(this) }
        else toast("VPN refused: only the in-app net test will work")
        refresh()
    }

    private fun netTest() {
        if (node.tunnel.session == null) { toast("No session yet"); return }
        toast("Testing DNS + HTTPS through the seller over Bluetooth...")
        node.internetTest("example.com") { r -> runOnUiThread { AlertDialog.Builder(this).setTitle(if (r.ok) "Internet OK" else "Internet FAILED").setMessage(r.text).setPositiveButton("OK", null).show(); refresh() } }
    }

    private fun refresh() {
        if (isFinishing) return
        val sb = StringBuilder()
        sb.append("me: prok-").append(node.identity.shortIdHex).append(" | node ").append(if (node.isRunning) "RUNNING" else "STOPPED")
            .append(" | Bluetooth ").append(if (node.bulk.isBluetoothOn) "on" else "OFF").append("\n")
        sb.append("role: ").append(if (node.gateway.providing) "SELLER" else if (node.buyerWanted != null) "BUYER (prok-" + node.buyerWanted + ")" else "-").append("\n")
        sb.append(node.bulk.diag())
        sb.append("provider offers: ").append(node.offers().joinToString("; ") { it.describe() + (if (it.bulkBt) " [BT]" else "") }.ifEmpty { "none" }).append("\n")
        sb.append("seller: ").append(node.gateway.state).append(" upstream ").append(node.gateway.upstreamDescription())
            .append(node.gateway.session?.let { " | customer prok-" + it.peerShort + " up " + it.bytesUp + " B down " + it.bytesDown + " B" } ?: "").append("\n")
        sb.append("buyer: ").append(node.tunnel.state).append(" vpn ").append(ProkVpnService.running)
            .append(" flows ").append(node.tunnel.activeFlows()).append(" dns ").append(node.tunnel.dnsCount)
            .append(node.tunnel.session?.let { " | up " + it.bytesUp + " B down " + it.bytesDown + " B" } ?: "").append("\n")
        if (node.lastBuyError.isNotEmpty()) sb.append("last buy error: ").append(node.lastBuyError).append("\n")
        sb.append("\n--- last 25 log lines ---\n").append(DiagLog.text().lines().takeLast(25).joinToString("\n"))
        findViewById<TextView>(R.id.txtBulkState).text = sb.toString()
    }

    private fun copyDiag() {
        val text = "Prok BLUETOOTH BULK DIAG " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "\n" +
            "me: prok-" + node.identity.shortIdHex + " \"" + node.identity.displayName + "\"\n" +
            "status: " + node.statusLine() + "\n" +
            node.bulk.diag() +
            "buyer: " + node.tunnel.state + " vpn " + ProkVpnService.running + " flows " + node.tunnel.activeFlows() + " dns " + node.tunnel.dnsCount +
            (node.tunnel.session?.let { " | up " + it.bytesUp + " B down " + it.bytesDown + " B" } ?: "") + "\n" +
            "seller: " + node.gateway.state + " upstream " + node.gateway.upstreamDescription() +
            (node.gateway.session?.let { " | customer prok-" + it.peerShort + " up " + it.bytesUp + " B down " + it.bytesDown + " B" } ?: "") + "\n" +
            "--- networks ---\n" + RelayProbe.networks(this).joinToString("\n") + "\n" +
            "interfaces: " + RelayProbe.interfaces().joinToString(" ") + "\n" +
            "--- last 120 log lines ---\n" + DiagLog.text().lines().takeLast(120).joinToString("\n") + "\n"
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Prok bt diag", text))
        DiagLog.i(tag, "bluetooth bulk diag copied (" + text.length + " chars)")
        toast("Bluetooth diagnostic copied")
    }

    override fun onPeers(peers: List<Peer>) {}
    override fun onMessagesChanged() {}
    override fun onStatus(status: String) { refresh() }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
