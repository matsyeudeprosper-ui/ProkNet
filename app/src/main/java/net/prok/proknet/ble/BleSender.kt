package net.prok.proknet.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import net.prok.proknet.core.DeliveryResult
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Routing
import net.prok.proknet.core.toHex
import net.prok.proknet.transport.Frame

/**
 * BLE central side: connect to a peer's GATT server, negotiate MTU, discover
 * the ProkNet service, optionally read IDENTITY, then write frames one after
 * another on the same connection, reading the RECEIPT after each write.
 * One operation at a time; one automatic reconnect per operation, resuming
 * at the frame that was in flight.
 *
 * All state changes run on the main thread (BLE callbacks arrive on binder threads).
 */
class BleSender(private val context: Context, private val adapter: BluetoothAdapter) {
    private val tag = "GATT-C"
    private val main = Handler(Looper.getMainLooper())
    @Volatile var bytesSent = 0L
        private set

    private class Op(
        val peer: Peer,
        val frames: List<Frame>,
        val readIdentity: Boolean,
        val onIdentity: ((ByteArray?) -> Unit)?,
        val onEach: (Int, DeliveryResult, String) -> Boolean,
        val onDone: () -> Unit,
    ) {
        var gatt: BluetoothGatt? = null
        var phase = "idle"
        var attempt = 0
        var mtu = 23
        var index = 0
        var done = false
        var timeout: Runnable? = null
        var mtuFallback: Runnable? = null
        var identityDone = false
    }

    private val queue = ArrayDeque<Op>()
    private var current: Op? = null
    val isBusy: Boolean get() = current != null || queue.isNotEmpty()

    fun sendBatch(peer: Peer, frames: List<Frame>, onEach: (Int, DeliveryResult, String) -> Boolean, onDone: () -> Unit, readIdentity: Boolean = false, onIdentity: ((ByteArray?) -> Unit)? = null) {
        main.post {
            queue.addLast(Op(peer, frames, readIdentity, onIdentity, onEach, onDone))
            DiagLog.i(tag, "queued " + frames.size + " frame(s) to " + peer.label + (if (readIdentity) " (+identity read)" else "") + ", ops waiting=" + queue.size)
            pump()
        }
    }

    fun readIdentity(peer: Peer, cb: (ByteArray?) -> Unit) = sendBatch(peer, emptyList(), { _, _, _ -> false }, {}, readIdentity = true, onIdentity = cb)

    private fun pump() {
        if (current != null) return
        val op = queue.pollFirst() ?: return
        current = op
        attempt(op)
    }

    private fun attempt(op: Op) {
        op.attempt++
        op.phase = "connecting"
        DiagLog.i(tag, "attempt " + op.attempt + ": connecting to " + op.peer.label + " @ " + op.peer.address + (if (op.index > 0) " (resume at frame " + op.index + ")" else ""))
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(op.peer.address)
        } catch (e: Exception) {
            finishTransport(op, "bad address " + op.peer.address); return
        }
        op.timeout = Runnable { fail(op, "timeout in phase '" + op.phase + "' after " + (BleConstants.SEND_TIMEOUT_MS / 1000) + "s") }
        main.postDelayed(op.timeout!!, BleConstants.SEND_TIMEOUT_MS)
        try {
            op.gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            if (op.gatt == null) fail(op, "connectGatt returned null")
        } catch (e: SecurityException) {
            fail(op, "missing BLUETOOTH_CONNECT permission: " + e.message)
        } catch (e: Exception) {
            fail(op, "connectGatt threw " + e)
        }
    }

    private fun armTimeout(op: Op) {
        op.timeout?.let { main.removeCallbacks(it) }
        op.timeout = Runnable { fail(op, "timeout in phase '" + op.phase + "'") }
        main.postDelayed(op.timeout!!, BleConstants.SEND_TIMEOUT_MS)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            main.post {
                val op = current ?: return@post
                if (op.gatt !== gatt) return@post
                if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                    DiagLog.i(tag, "connected to " + op.peer.label + ", requesting MTU " + BleConstants.REQUEST_MTU)
                    op.phase = "mtu"
                    val ok = try { gatt.requestMtu(BleConstants.REQUEST_MTU) } catch (e: Exception) { false }
                    if (!ok) { DiagLog.w(tag, "requestMtu not accepted, discovering services anyway"); discover(op) }
                    else {
                        op.mtuFallback = Runnable {
                            if (op.phase == "mtu") { DiagLog.w(tag, "no MTU callback after 3s, discovering services anyway"); discover(op) }
                        }
                        main.postDelayed(op.mtuFallback!!, BleConstants.MTU_FALLBACK_MS)
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (!op.done) fail(op, "disconnected in phase '" + op.phase + "' (status " + status + statusHint(status) + ")")
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            main.post {
                val op = current ?: return@post
                if (op.gatt !== gatt || op.phase != "mtu") return@post
                op.mtu = mtu
                DiagLog.i(tag, "mtu=" + mtu + " status=" + status)
                discover(op)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            main.post {
                val op = current ?: return@post
                if (op.gatt !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { fail(op, "service discovery failed status " + status); return@post }
                val service = gatt.getService(BleConstants.SERVICE_UUID)
                if (service == null) { fail(op, "peer has no ProkNet service (services=" + gatt.services.size + ")"); return@post }
                if (service.getCharacteristic(BleConstants.CHAR_RECEIPT_UUID) == null) DiagLog.w(tag, "peer has no RECEIPT characteristic (old app version?)")
                if (op.readIdentity && !op.identityDone) readIdentityChar(op) else writeNext(op)
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            main.post {
                val op = current ?: return@post
                if (op.gatt !== gatt || op.phase != "writing") return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { fail(op, "write rejected status " + status + statusHint(status)); return@post }
                bytesSent += op.frames[op.index].bytes.size
                val rc = gatt.getService(BleConstants.SERVICE_UUID)?.getCharacteristic(BleConstants.CHAR_RECEIPT_UUID)
                if (rc == null) { frameResult(op, DeliveryResult.NO_RECEIPT, "write acked but peer has no RECEIPT characteristic"); return@post }
                op.phase = "receipt"
                armTimeout(op)
                val ok = try { gatt.readCharacteristic(rc) } catch (e: Exception) { false }
                if (!ok) fail(op, "readCharacteristic(RECEIPT) not accepted")
            }
        }

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) onRead(gatt, characteristic, characteristic.value, status)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            onRead(gatt, characteristic, value, status)
        }

        private fun onRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray?, status: Int) {
            main.post {
                val op = current ?: return@post
                if (op.gatt !== gatt) return@post
                when (characteristic.uuid) {
                    BleConstants.CHAR_IDENTITY_UUID -> {
                        if (op.phase != "identity") return@post
                        op.identityDone = true
                        if (status != BluetoothGatt.GATT_SUCCESS || value == null) {
                            DiagLog.w(tag, "identity read failed status " + status); op.onIdentity?.invoke(null)
                        } else {
                            DiagLog.i(tag, "identity record read from " + op.peer.label + " (" + value.size + " bytes)")
                            try { op.onIdentity?.invoke(value) } catch (e: Exception) { DiagLog.e(tag, "identity handler", e) }
                        }
                        writeNext(op)
                    }
                    BleConstants.CHAR_RECEIPT_UUID -> {
                        if (op.phase != "receipt") return@post
                        if (status != BluetoothGatt.GATT_SUCCESS || value == null) { frameResult(op, DeliveryResult.NO_RECEIPT, "receipt read failed status " + status); return@post }
                        if (value.size < 10 || (value[0].toInt() and 0xFF) != BleConstants.RECEIPT_VERSION) { frameResult(op, DeliveryResult.NO_RECEIPT, "receipt malformed (" + value.size + " bytes)"); return@post }
                        val rStatus = value[1].toInt() and 0xFF
                        val rMsg = value.copyOfRange(2, 10)
                        val expected = op.frames[op.index].msgId
                        if (!rMsg.contentEquals(expected)) { frameResult(op, DeliveryResult.NO_RECEIPT, "receipt is for msg " + rMsg.toHex() + ", expected " + expected.toHex()); return@post }
                        val res = Routing.resultFor(rStatus)
                        val why = when (res) {
                            DeliveryResult.DELIVERED -> "RECEIPT accepted: destination stored it (mtu " + op.mtu + ")"
                            DeliveryResult.DUPLICATE -> "RECEIPT duplicate: peer already had it"
                            DeliveryResult.RELAYED -> "RECEIPT accepted_relay: " + op.peer.label + " took custody (NOT final delivery)"
                            else -> "RECEIPT rejected by peer (status " + rStatus + ")"
                        }
                        frameResult(op, res, why)
                    }
                    else -> {}
                }
            }
        }
    }

    private fun discover(op: Op) {
        op.mtuFallback?.let { main.removeCallbacks(it) }
        op.phase = "discovering"
        val ok = try { op.gatt?.discoverServices() ?: false } catch (e: Exception) { false }
        if (!ok) fail(op, "discoverServices not accepted")
    }

    private fun readIdentityChar(op: Op) {
        val ch = op.gatt?.getService(BleConstants.SERVICE_UUID)?.getCharacteristic(BleConstants.CHAR_IDENTITY_UUID)
        if (ch == null) { DiagLog.w(tag, "peer has no IDENTITY characteristic"); op.identityDone = true; op.onIdentity?.invoke(null); writeNext(op); return }
        op.phase = "identity"
        armTimeout(op)
        val ok = try { op.gatt!!.readCharacteristic(ch) } catch (e: Exception) { false }
        if (!ok) fail(op, "readCharacteristic(IDENTITY) not accepted")
    }

    private fun writeNext(op: Op) {
        if (op.index >= op.frames.size) { finish(op); return }
        val gatt = op.gatt ?: return
        val ch = gatt.getService(BleConstants.SERVICE_UUID)?.getCharacteristic(BleConstants.CHAR_INBOX_UUID)
        if (ch == null) { fail(op, "peer has no ProkNet inbox characteristic"); return }
        val frame = op.frames[op.index]
        op.phase = "writing"
        armTimeout(op)
        DiagLog.i(tag, "writing frame " + (op.index + 1) + "/" + op.frames.size + " (" + frame.bytes.size + " bytes) msg=" + frame.msgId.toHex() +
            (if (frame.bytes.size > op.mtu - 3) " via long write" else ""))
        val ok: Boolean = try {
            if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(ch, frame.bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                ch.value = frame.bytes
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(ch)
            }
        } catch (e: Exception) { DiagLog.e(tag, "write threw", e); false }
        if (!ok) fail(op, "writeCharacteristic not accepted by stack")
    }

    /** Receipt (or receipt failure) for the current frame; decides whether to continue. */
    private fun frameResult(op: Op, result: DeliveryResult, detail: String) {
        val i = op.index
        DiagLog.i(tag, "frame " + (i + 1) + "/" + op.frames.size + " -> " + result + " (" + detail + ")")
        val cont = try { op.onEach(i, result, detail) } catch (e: Exception) { DiagLog.e(tag, "onEach", e); false }
        op.index = i + 1
        if (!cont || op.index >= op.frames.size) finish(op) else writeNext(op)
    }

    /** Transport-level failure: reconnect once per op, resuming at the current frame; then give up. */
    private fun fail(op: Op, reason: String) {
        if (op.done) return
        DiagLog.w(tag, "attempt " + op.attempt + " failed: " + reason)
        closeGatt(op)
        op.timeout?.let { main.removeCallbacks(it) }
        op.mtuFallback?.let { main.removeCallbacks(it) }
        if (op.attempt < 2) {
            DiagLog.i(tag, "retrying in 1.5s")
            main.postDelayed({ if (!op.done) attempt(op) }, 1500)
        } else {
            finishTransport(op, reason)
        }
    }

    private fun finishTransport(op: Op, reason: String) {
        if (op.done) return
        if (op.readIdentity && !op.identityDone) { op.identityDone = true; try { op.onIdentity?.invoke(null) } catch (_: Exception) {} }
        if (op.index < op.frames.size) {
            try { op.onEach(op.index, DeliveryResult.TRANSPORT_FAILED, reason) } catch (e: Exception) { DiagLog.e(tag, "onEach", e) }
        }
        finish(op)
    }

    private fun finish(op: Op) {
        if (op.done) return
        op.done = true
        op.timeout?.let { main.removeCallbacks(it) }
        op.mtuFallback?.let { main.removeCallbacks(it) }
        closeGatt(op)
        DiagLog.i(tag, "op to " + op.peer.label + " finished (" + op.index + "/" + op.frames.size + " frames)")
        current = null
        try { op.onDone() } catch (e: Exception) { DiagLog.e(tag, "onDone", e) }
        pump()
    }

    private fun closeGatt(op: Op) {
        val g = op.gatt ?: return
        op.gatt = null
        try { g.disconnect() } catch (_: Exception) {}
        try { g.close() } catch (_: Exception) {}
    }

    private fun statusHint(status: Int) = when (status) {
        133 -> " GATT_ERROR/133: generic stack error, usually just retry"
        8 -> " connection timeout"
        19 -> " remote closed connection"
        22 -> " local host terminated"
        62 -> " failed to establish connection"
        else -> ""
    }
}
