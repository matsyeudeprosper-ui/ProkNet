package net.prok.proknet.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import net.prok.proknet.ble.BleAdvertiser
import net.prok.proknet.ble.BleScanner
import net.prok.proknet.ble.BleSender
import net.prok.proknet.ble.GattServerNode
import net.prok.proknet.ble.Peer
import net.prok.proknet.core.DeliveryResult
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Identity
import net.prok.proknet.core.Routing
import net.prok.proknet.core.Wire

/**
 * BLE as a ProkNet transport: discovery (advertise + scan), control-sized
 * frames over GATT writes with receipts, identity records over a GATT read.
 * Not bulk capable (a few hundred bytes per second), but always available
 * and needs no user dialog.
 */
class BleTransport(
    private val context: Context,
    private val identity: Identity,
    private val identityRecord: () -> ByteArray,
) : Transport {
    private val tag = "BLE"
    override val name = Routing.TRANSPORT_BLE
    override val bulkCapable = false

    private val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var server: GattServerNode? = null
    private var advertiser: BleAdvertiser? = null
    private var scanner: BleScanner? = null
    private var sender: BleSender? = null
    private var listener: TransportListener? = null
    @Volatile override var isRunning = false
        private set
    @Volatile private var visible: List<Peer> = emptyList()
    private val identityFetched = HashSet<String>()

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true
    val serverReady: Boolean get() = server?.isReady == true
    val advertising: Boolean get() = advertiser?.isAdvertising == true
    val scanning: Boolean get() = scanner?.isScanning == true
    override val bytesSent: Long get() = sender?.bytesSent ?: 0L
    override val bytesReceived: Long get() = server?.bytesReceived ?: 0L

    override fun start(listener: TransportListener): Boolean {
        if (isRunning) return true
        val a = adapter ?: run { DiagLog.e(tag, "no Bluetooth adapter"); return false }
        if (!a.isEnabled) { DiagLog.e(tag, "Bluetooth is OFF"); return false }
        this.listener = listener
        server = GattServerNode(context, identityRecord) { pkt -> listener.onFrame(name, null, pkt.encode()) }
        val srvOk = server!!.start()
        advertiser = BleAdvertiser(a, identity)
        val advOk = advertiser!!.start()
        scanner = BleScanner(a) { peers -> visible = peers; listener.onPeersChanged(peers); listener.onLinkState(name, linkState()) }
        val scanOk = scanner!!.start()
        sender = BleSender(context, a)
        isRunning = true
        identityFetched.clear()
        DiagLog.i(tag, "transport started: server=" + srvOk + " advertise=" + advOk + " scan=" + scanOk)
        listener.onLinkState(name, linkState())
        return srvOk && scanOk
    }

    override fun stop() {
        if (!isRunning) return
        scanner?.stop(); scanner = null
        advertiser?.stop(); advertiser = null
        server?.stop(); server = null
        sender = null
        visible = emptyList()
        isRunning = false
        listener?.onLinkState(name, linkState())
        DiagLog.i(tag, "transport stopped")
    }

    fun visiblePeers(): List<Peer> = visible

    private fun peer(short: String): Peer? = visible.firstOrNull { it.shortId == short && it.inRange && it.hasId }

    override fun canReach(peerShort: String): Boolean = isRunning && peer(peerShort) != null

    override fun linkState(): String =
        if (!isRunning) "off" else "server " + (if (serverReady) "ready" else "not ready") + ", adv " + (if (advertising) "on" else "OFF") +
            ", scan " + (if (scanning) "on" else "OFF") + ", peers " + visible.size

    override fun sendBatch(peerShort: String, frames: List<Frame>, onEach: (Int, DeliveryResult, String) -> Boolean, onDone: () -> Unit) {
        val s = sender; val p = peer(peerShort)
        if (s == null || p == null) {
            if (frames.isNotEmpty()) onEach(0, DeliveryResult.TRANSPORT_FAILED, "prok-" + peerShort + " not in BLE range")
            onDone(); return
        }
        // Learn the peer's key on the same connection if we do not have it yet (free ride).
        val wantIdentity = identityFetched.add(peerShort)
        s.sendBatch(p, frames, onEach, onDone, readIdentity = wantIdentity, onIdentity = { rec -> handleIdentity(rec, p) })
    }

    /** Read the peer's identity record (ID, public key, name) over a dedicated connection. */
    fun fetchIdentity(peerShort: String, cb: (Boolean) -> Unit) {
        val s = sender; val p = peer(peerShort)
        if (s == null || p == null) { cb(false); return }
        identityFetched.add(peerShort)
        s.readIdentity(p) { rec -> cb(handleIdentity(rec, p)) }
    }

    private fun handleIdentity(bytes: ByteArray?, p: Peer): Boolean {
        val rec = Wire.parseIdentityRecord(bytes)
        if (rec == null) {
            if (bytes != null) DiagLog.w(tag, "identity record from " + p.label + " rejected (malformed or ID does not match key)")
            identityFetched.remove(p.shortId) // try again next time
            return false
        }
        if (rec.shortId != p.shortId) { DiagLog.w(tag, "identity record ID " + rec.shortId + " does not match advertised " + p.shortId + " - ignored"); return false }
        listener?.onIdentity(name, rec.idHex, rec.pub, rec.name)
        return true
    }

    fun identityKnownThisSession(peerShort: String) = identityFetched.contains(peerShort)
}
