package net.prok.proknet.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Identity
import net.prok.proknet.core.MessageStore
import net.prok.proknet.core.Packet
import net.prok.proknet.core.StoredMessage

/**
 * One ProkNet node = identity + GATT server (receive) + advertiser (be found)
 * + scanner (find others) + sender (deliver). This is the only object the UI talks to.
 */
class ProkNetNode(private val context: Context) {
    private val tag = "NODE"
    private val main = Handler(Looper.getMainLooper())

    val identity: Identity = Identity.load(context)
    val store = MessageStore(context)

    interface Listener {
        fun onPeers(peers: List<Peer>)
        fun onMessagesChanged()
        fun onStatus(status: String)
    }
    var listener: Listener? = null

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var server: GattServerNode? = null
    private var advertiser: BleAdvertiser? = null
    private var scanner: BleScanner? = null
    private var sender: BleSender? = null
    @Volatile var isRunning = false
        private set
    private var peerCount = 0

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    fun start(): Boolean {
        if (isRunning) { DiagLog.w(tag, "start ignored: already running"); return true }
        val a = adapter
        if (a == null) { DiagLog.e(tag, "no Bluetooth adapter on this phone"); pushStatus(); return false }
        if (!a.isEnabled) { DiagLog.e(tag, "Bluetooth is OFF - turn it on and press Start again"); pushStatus(); return false }
        DiagLog.i(tag, "starting node id=" + identity.idHex + " name=" + identity.displayName)

        server = GattServerNode(context, identity) { pkt, addr -> onPacketReceived(pkt, addr) }
        val srvOk = server!!.start()
        advertiser = BleAdvertiser(a, identity)
        val advOk = advertiser!!.start()
        scanner = BleScanner(a) { peers ->
            peerCount = peers.size
            listener?.onPeers(peers)
            pushStatus()
        }
        val scanOk = scanner!!.start()
        sender = BleSender(context, a)
        isRunning = true
        DiagLog.i(tag, "node started: server=" + srvOk + " advertise=" + advOk + " scan=" + scanOk)
        main.postDelayed({ pushStatus() }, 1500)
        pushStatus()
        return srvOk && scanOk
    }

    fun stop() {
        if (!isRunning) return
        scanner?.stop(); scanner = null
        advertiser?.stop(); advertiser = null
        server?.stop(); server = null
        sender = null
        isRunning = false
        peerCount = 0
        DiagLog.i(tag, "node stopped")
        pushStatus()
    }

    fun peers(): List<Peer> = scanner?.snapshot() ?: emptyList()

    fun sendText(peer: Peer, text: String) {
        val s = sender
        if (s == null || !isRunning) { DiagLog.e(tag, "cannot send: node not running"); return }
        val pkt = Packet.text(identity, text)
        val bytes = pkt.encode()
        store.insert(
            StoredMessage(0, pkt.msgIdHex, "out", peer.shortId, peer.label, text, pkt.timestamp, "sending")
        )
        listener?.onMessagesChanged()
        s.send(peer, bytes) { ok, detail ->
            store.updateStatus(pkt.msgIdHex, if (ok) "sent" else "failed")
            listener?.onMessagesChanged()
            pushStatus(if (ok) "last send OK" else "last send FAILED: " + detail)
        }
    }

    private fun onPacketReceived(pkt: Packet, address: String) {
        val senderShort = pkt.senderIdHex.substring(0, Identity.SHORT_ID_LEN * 2)
        val fresh = store.insert(
            StoredMessage(0, pkt.msgIdHex, "in", senderShort, "prok-" + senderShort, pkt.text, pkt.timestamp, "received")
        )
        if (!fresh) DiagLog.w(tag, "duplicate message " + pkt.msgIdHex + " ignored")
        main.post { listener?.onMessagesChanged(); pushStatus("received from prok-" + senderShort) }
    }

    fun statusLine(extra: String? = null): String {
        val bt = if (adapter == null) "no BT" else if (adapter.isEnabled) "BT on" else "BT OFF"
        if (!isRunning) return bt + " | node stopped"
        val sb = StringBuilder(bt)
        sb.append(" | server ").append(if (server?.isReady == true) "ready" else "not ready")
        sb.append(" | adv ").append(if (advertiser?.isAdvertising == true) "on" else "OFF")
        sb.append(" | scan ").append(if (scanner?.isScanning == true) "on" else "OFF")
        sb.append(" | peers ").append(peerCount)
        if (extra != null) sb.append(" | ").append(extra)
        return sb.toString()
    }

    private fun pushStatus(extra: String? = null) {
        val line = statusLine(extra)
        main.post { listener?.onStatus(line) }
    }
}
