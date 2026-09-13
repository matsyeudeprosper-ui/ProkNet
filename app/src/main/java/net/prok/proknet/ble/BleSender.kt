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
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.toHex

/**
 * The sending side: connect to a peer's GATT server, negotiate MTU, discover
 * the ProkNet service, write one packet, wait for the write acknowledgement,
 * then (milestone 2A) read the RECEIPT characteristic to learn whether the
 * peer really stored the packet. One operation at a time; each operation gets
 * one automatic transport retry inside the same call.
 *
 * All state changes run on the main thread (BLE callbacks arrive on binder threads).
 */
class BleSender(private val context: Context, private val adapter: BluetoothAdapter) {
    private val tag = "GATT-C"
    private val main = Handler(Looper.getMainLooper())

    private class Op(val peer: Peer, val msgId: ByteArray, val bytes: ByteArray, val cb: (DeliveryResult, String) -> Unit) {
        var gatt: BluetoothGatt? = null
        var phase = "idle"
        var attempt = 0
        var mtu = 23
        var done = false
        var timeout: Runnable? = null
        var mtuFallback: Runnable? = null
    }

    private val queue = ArrayDeque<Op>()
    private var current: Op? = null
    val isBusy: Boolean get() = current != null || queue.isNotEmpty()

    fun send(peer: Peer, msgId: ByteArray, bytes: ByteArray, cb: (DeliveryResult, String) -> Unit) {
        main.post {
            queue.addLast(Op(peer, msgId, bytes, cb))
            DiagLog.i(tag, "queued send to " + peer.label + " msg=" + msgId.toHex() + " (" + bytes.size + " bytes)")
            pump()
        }
    }

    private fun pump() {
        if (current != null) return
        val op = queue.pollFirst() ?: return
        current = op
        attempt(op)
    }

    private fun attempt(op: Op) {
        op.attempt++
        op.phase = "connecting"
        DiagLog.i(tag, "attempt " + op.attempt + ": connecting to " + op.peer.label + " @ " + op.peer.address)
        val device: BluetoothDevice = try {
            adapter.getRemoteDevice(op.peer.address)
        } catch (e: Exception) {
            finish(op, DeliveryResult.TRANSPORT_FAILED, "bad address " + op.peer.address); return
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
                DiagLog.i(tag, "mtu=" + mtu + " status=" + status + (if (op.bytes.size > mtu - 3) " (packet larger than MTU-3, stack will use long write)" else ""))
                discover(op)
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            main.post {
                val op = current ?: return@post
                if (op.gatt !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { fail(op, "service discovery failed status " + status); return@post }
                val service = gatt.getService(BleConstants.SERVICE_UUID)
                val ch = service?.getCharacteristic(BleConstants.CHAR_INBOX_UUID)
                if (ch == null) {
                    fail(op, "peer has no ProkNet inbox characteristic (services=" + gatt.services.size + ")"); return@post
                }
                if (service.getCharacteristic(BleConstants.CHAR_RECEIPT_UUID) == null) {
                    DiagLog.w(tag, "peer has no RECEIPT characteristic (old app version?) - delivery cannot be confirmed")
                }
                op.phase = "writing"
                DiagLog.i(tag, "ProkNet service found, writing " + op.bytes.size + " bytes")
                val ok: Boolean = try {
                    if (Build.VERSION.SDK_INT >= 33) {
                        gatt.writeCharacteristic(ch, op.bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
                    } else {
                        ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        @Suppress("DEPRECATION")
                        ch.value = op.bytes
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(ch)
                    }
                } catch (e: Exception) { DiagLog.e(tag, "write threw", e); false }
                if (!ok) fail(op, "writeCharacteristic not accepted by stack")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            main.post {
                val op = current ?: return@post
                if (op.gatt !== gatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) { fail(op, "write rejected status " + status + statusHint(status)); return@post }
                DiagLog.i(tag, "write ACKED by " + op.peer.label + ", reading RECEIPT")
                val rc = gatt.getService(BleConstants.SERVICE_UUID)?.getCharacteristic(BleConstants.CHAR_RECEIPT_UUID)
                if (rc == null) {
                    finish(op, DeliveryResult.NO_RECEIPT, "write acked but peer has no RECEIPT characteristic"); return@post
                }
                op.phase = "receipt"
                val ok = try { gatt.readCharacteristic(rc) } catch (e: Exception) { false }
                if (!ok) fail(op, "readCharacteristic(RECEIPT) not accepted")
            }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            // Pre-API-33 callback. On 33+ the 4-arg overload below fires instead.
            if (Build.VERSION.SDK_INT < 33) onReceipt(gatt, characteristic.value, status)
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            onReceipt(gatt, value, status)
        }

        private fun onReceipt(gatt: BluetoothGatt, value: ByteArray?, status: Int) {
            main.post {
                val op = current ?: return@post
                if (op.gatt !== gatt || op.phase != "receipt") return@post
                if (status != BluetoothGatt.GATT_SUCCESS || value == null) {
                    finish(op, DeliveryResult.NO_RECEIPT, "receipt read failed status " + status); return@post
                }
                if (value.size < 10 || (value[0].toInt() and 0xFF) != BleConstants.RECEIPT_VERSION) {
                    finish(op, DeliveryResult.NO_RECEIPT, "receipt malformed (" + value.size + " bytes)"); return@post
                }
                val rStatus = value[1].toInt() and 0xFF
                val rMsg = value.copyOfRange(2, 10)
                if (!rMsg.contentEquals(op.msgId)) {
                    finish(op, DeliveryResult.NO_RECEIPT, "receipt is for msg " + rMsg.toHex() + ", expected " + op.msgId.toHex()); return@post
                }
                when (rStatus) {
                    BleConstants.RECEIPT_ACCEPTED -> finish(op, DeliveryResult.DELIVERED, "RECEIPT accepted (mtu " + op.mtu + ", attempt " + op.attempt + ")")
                    BleConstants.RECEIPT_DUPLICATE -> finish(op, DeliveryResult.DUPLICATE, "RECEIPT duplicate: peer already had it")
                    else -> finish(op, DeliveryResult.REJECTED, "RECEIPT rejected by peer")
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

    /** Transport-level failure: retry once inside this op, then report TRANSPORT_FAILED. */
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
            finish(op, DeliveryResult.TRANSPORT_FAILED, reason)
        }
    }

    private fun finish(op: Op, result: DeliveryResult, detail: String) {
        if (op.done) return
        op.done = true
        op.timeout?.let { main.removeCallbacks(it) }
        op.mtuFallback?.let { main.removeCallbacks(it) }
        closeGatt(op)
        DiagLog.i(tag, "SEND " + result + " to " + op.peer.label + " msg=" + op.msgId.toHex() + ": " + detail)
        current = null
        op.cb(result, detail)
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
