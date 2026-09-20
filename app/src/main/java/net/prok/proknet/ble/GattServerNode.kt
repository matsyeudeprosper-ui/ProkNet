package net.prok.proknet.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.io.ByteArrayOutputStream
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Packet

/**
 * The BLE receiving side: a GATT server exposing the ProkNet service.
 *
 *   IDENTITY (read)  the identity record from [identityRecord]: [2][id 16][pub 64][name]
 *   INBOX (write)    one encoded Packet per write; long writes reassembled
 *   RECEIPT (read)   [ver][status][msgId 8] for the last packet THIS central wrote
 *
 * The receipt is written BEFORE the write response, so the central's read can never
 * see a stale value. Receipts are keyed by the central's transport address only as
 * connection bookkeeping; routing never uses that address.
 */
class GattServerNode(
    private val context: Context,
    private val identityRecord: () -> ByteArray,
    /** v0.13.3: the BLE generation this server belongs to; a late callback from an old one is ignored. */
    val generation: Int,
    private val onServiceAdded: (generation: Int, ok: Boolean) -> Unit,
    private val onPacket: (Packet) -> Int,
) {
    private val tag = "GATT-S"
    private var server: BluetoothGattServer? = null
    private val prepared = HashMap<String, ByteArrayOutputStream>()
    private val receipts = HashMap<String, ByteArray>()
    @Volatile var isReady = false
        private set
    @Volatile var bytesReceived = 0L
        private set

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            val ok = status == BluetoothGatt.GATT_SUCCESS
            isReady = ok
            DiagLog.i(tag, "service added status=" + status + " ready=" + ok + " (generation " + generation + ")")
            this@GattServerNode.onServiceAdded(generation, ok)
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val s = if (newState == BluetoothProfile.STATE_CONNECTED) "CONNECTED" else if (newState == BluetoothProfile.STATE_DISCONNECTED) "DISCONNECTED" else "state " + newState
            DiagLog.i(tag, "central " + device.address + " " + s + " (status " + status + ")")
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                synchronized(prepared) { prepared.remove(device.address) }
                synchronized(receipts) { receipts.remove(device.address) }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            DiagLog.i(tag, "mtu with " + device.address + " = " + mtu)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            val full: ByteArray = when (characteristic.uuid) {
                BleConstants.CHAR_IDENTITY_UUID -> {
                    if (offset == 0) DiagLog.i(tag, "identity record read by " + device.address)
                    try { identityRecord() } catch (e: Exception) { ByteArray(0) }
                }
                BleConstants.CHAR_RECEIPT_UUID -> {
                    val r = synchronized(receipts) { receipts[device.address] }
                    if (r == null) {
                        DiagLog.w(tag, "RECEIPT read by " + device.address + " but no packet was received from it on this connection")
                        receiptBytes(BleConstants.RECEIPT_REJECTED, ByteArray(8))
                    } else r
                }
                else -> { respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null); return }
            }
            val slice = if (offset >= full.size) ByteArray(0) else full.copyOfRange(offset, full.size)
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray?,
        ) {
            if (characteristic.uuid != BleConstants.CHAR_INBOX_UUID) {
                if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_WRITE_NOT_PERMITTED, 0, null)
                return
            }
            val bytes = value ?: ByteArray(0)
            if (preparedWrite) {
                synchronized(prepared) {
                    val buf = prepared.getOrPut(device.address) { ByteArrayOutputStream() }
                    if (buf.size() != offset) DiagLog.w(tag, "prepared write offset " + offset + " but buffered " + buf.size())
                    buf.write(bytes)
                }
                if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, bytes)
            } else {
                handle(bytes, device.address)
                if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val buf = synchronized(prepared) { prepared.remove(device.address) }
            if (execute && buf != null) handle(buf.toByteArray(), device.address)
            else DiagLog.w(tag, "execute write cancelled/empty from " + device.address)
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }

    private fun respond(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
        try { server?.sendResponse(device, requestId, status, offset, value) } catch (e: Exception) { DiagLog.e(tag, "sendResponse", e) }
    }

    private fun handle(bytes: ByteArray, address: String) {
        bytesReceived += bytes.size
        val pkt = Packet.decode(bytes)
        if (pkt == null) {
            DiagLog.w(tag, "invalid packet from " + address + " (" + bytes.size + " bytes) - REJECTED")
            synchronized(receipts) { receipts[address] = receiptBytes(BleConstants.RECEIPT_REJECTED, ByteArray(8)) }
            return
        }
        // Never let a bad packet or a store error kill the Bluetooth binder thread: answer REJECTED instead.
        val status = try { onPacket(pkt) } catch (e: Exception) {
            DiagLog.e(tag, "packet handler crashed for msg=" + pkt.msgIdHex + " - answering REJECTED", e)
            BleConstants.RECEIPT_REJECTED
        }
        synchronized(receipts) { receipts[address] = receiptBytes(status, pkt.msgId) }
        DiagLog.i(tag, "PACKET v" + pkt.wireVersion + " type=" + pkt.type + " origin=" + pkt.originShort + " dest=" + pkt.destShort +
            " lastHop=" + (if (pkt.hasLastHop) pkt.lastHopShort else "?") + " msg=" + pkt.msgIdHex + " hops=" + pkt.hops + "/" + pkt.ttl +
            " len=" + pkt.payload.size + " -> receipt " + statusName(status))
    }

    private fun receiptBytes(status: Int, msgId: ByteArray): ByteArray {
        val r = ByteArray(2 + 8)
        r[0] = BleConstants.RECEIPT_VERSION.toByte()
        r[1] = status.toByte()
        System.arraycopy(msgId, 0, r, 2, minOf(8, msgId.size))
        return r
    }

    private fun statusName(s: Int) = when (s) {
        BleConstants.RECEIPT_ACCEPTED -> "ACCEPTED"
        BleConstants.RECEIPT_DUPLICATE -> "DUPLICATE"
        BleConstants.RECEIPT_ACCEPTED_RELAY -> "ACCEPTED_RELAY"
        BleConstants.RECEIPT_REJECTED -> "REJECTED"
        else -> "status " + s
    }

    fun start(): Boolean {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return try {
            val srv = mgr.openGattServer(context, callback)
            if (srv == null) { DiagLog.e(tag, "openGattServer returned null"); return false }
            server = srv
            val service = BluetoothGattService(BleConstants.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(BluetoothGattCharacteristic(BleConstants.CHAR_IDENTITY_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
            service.addCharacteristic(BluetoothGattCharacteristic(BleConstants.CHAR_INBOX_UUID, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE))
            service.addCharacteristic(BluetoothGattCharacteristic(BleConstants.CHAR_RECEIPT_UUID, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
            val ok = srv.addService(service)
            DiagLog.i(tag, "gatt server opened (identity, inbox, receipt), addService=" + ok)
            ok
        } catch (e: SecurityException) {
            DiagLog.e(tag, "missing BLUETOOTH_CONNECT permission", e); false
        } catch (e: Exception) {
            DiagLog.e(tag, "gatt server start failed", e); false
        }
    }

    fun stop() {
        try { server?.clearServices(); server?.close() } catch (e: Exception) { DiagLog.w(tag, "close: " + e) }
        server = null
        isReady = false
        synchronized(receipts) { receipts.clear() }
        DiagLog.i(tag, "gatt server closed")
    }
}
