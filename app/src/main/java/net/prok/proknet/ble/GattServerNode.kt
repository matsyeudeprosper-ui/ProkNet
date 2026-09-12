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
import net.prok.proknet.core.Identity
import net.prok.proknet.core.Packet

/**
 * The receiving side: a GATT server exposing the ProkNet service.
 *
 * Peers write one encoded Packet into the INBOX characteristic. Android splits
 * writes larger than MTU-3 into "prepared writes" followed by an execute; both
 * paths are handled and reassembled here.
 */
class GattServerNode(
    private val context: Context,
    private val identity: Identity,
    private val onPacket: (Packet, String) -> Unit,
) {
    private val tag = "GATT-S"
    private var server: BluetoothGattServer? = null
    private val prepared = HashMap<String, ByteArrayOutputStream>()
    @Volatile var isReady = false
        private set

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            isReady = status == BluetoothGatt.GATT_SUCCESS
            DiagLog.i(tag, "service added status=" + status + " ready=" + isReady)
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            val s = if (newState == BluetoothProfile.STATE_CONNECTED) "CONNECTED" else if (newState == BluetoothProfile.STATE_DISCONNECTED) "DISCONNECTED" else "state " + newState
            DiagLog.i(tag, "central " + device.address + " " + s + " (status " + status + ")")
            if (newState == BluetoothProfile.STATE_DISCONNECTED) synchronized(prepared) { prepared.remove(device.address) }
        }

        override fun onMtuChanged(device: BluetoothDevice, mtu: Int) {
            DiagLog.i(tag, "mtu with " + device.address + " = " + mtu)
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic,
        ) {
            if (characteristic.uuid != BleConstants.CHAR_IDENTITY_UUID) {
                respond(device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, 0, null); return
            }
            val full = (identity.idHex + "|" + identity.displayName).toByteArray(Charsets.UTF_8)
            val slice = if (offset >= full.size) ByteArray(0) else full.copyOfRange(offset, full.size)
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            DiagLog.i(tag, "identity read by " + device.address + " offset=" + offset)
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
                DiagLog.i(tag, "prepared write chunk from " + device.address + " offset=" + offset + " len=" + bytes.size)
                if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, bytes)
            } else {
                DiagLog.i(tag, "write from " + device.address + " len=" + bytes.size)
                if (responseNeeded) respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                handle(bytes, device.address)
            }
        }

        override fun onExecuteWrite(device: BluetoothDevice, requestId: Int, execute: Boolean) {
            val buf = synchronized(prepared) { prepared.remove(device.address) }
            respond(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            if (execute && buf != null) {
                val bytes = buf.toByteArray()
                DiagLog.i(tag, "execute write from " + device.address + " total=" + bytes.size)
                handle(bytes, device.address)
            } else {
                DiagLog.w(tag, "execute write cancelled/empty from " + device.address)
            }
        }
    }

    private fun respond(device: BluetoothDevice, requestId: Int, status: Int, offset: Int, value: ByteArray?) {
        try { server?.sendResponse(device, requestId, status, offset, value) } catch (e: Exception) { DiagLog.e(tag, "sendResponse", e) }
    }

    private fun handle(bytes: ByteArray, address: String) {
        val pkt = Packet.decode(bytes)
        if (pkt == null) {
            DiagLog.w(tag, "invalid packet from " + address + " (" + bytes.size + " bytes) - ignored"); return
        }
        DiagLog.i(tag, "PACKET from " + pkt.senderIdHex.substring(0, 8) + " msg=" + pkt.msgIdHex + " text=\"" + pkt.text + "\"")
        onPacket(pkt, address)
    }

    fun start(): Boolean {
        val mgr = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        return try {
            val srv = mgr.openGattServer(context, callback)
            if (srv == null) { DiagLog.e(tag, "openGattServer returned null"); return false }
            server = srv
            val service = BluetoothGattService(BleConstants.SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(
                BluetoothGattCharacteristic(
                    BleConstants.CHAR_IDENTITY_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_READ,
                )
            )
            service.addCharacteristic(
                BluetoothGattCharacteristic(
                    BleConstants.CHAR_INBOX_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE,
                )
            )
            val ok = srv.addService(service)
            DiagLog.i(tag, "gatt server opened, addService=" + ok)
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
        DiagLog.i(tag, "gatt server closed")
    }
}
