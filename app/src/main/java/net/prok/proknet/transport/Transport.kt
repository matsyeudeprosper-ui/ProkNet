package net.prok.proknet.transport

import net.prok.proknet.ble.Peer
import net.prok.proknet.core.DeliveryResult

/** One frame to deliver: the encoded packet plus the message ID the receipt must echo. */
class Frame(val msgId: ByteArray, val bytes: ByteArray)

/**
 * What the node gets back from a transport. All methods may be called on any
 * thread; [onFrame] must return the receipt code synchronously.
 */
interface TransportListener {
    /** BLE only: the current scan picture changed. */
    fun onPeersChanged(peers: List<Peer>)
    /** A packet arrived. [fromShort] is the authenticated link peer for Wi-Fi, null for BLE. Returns the receipt code. */
    fun onFrame(transport: String, fromShort: String?, bytes: ByteArray): Int
    /** A peer's identity record was learned (BLE IDENTITY read or Wi-Fi handshake). */
    fun onIdentity(transport: String, fullIdHex: String, pubBytes: ByteArray, name: String)
    /** Human-readable link state for diagnostics. */
    fun onLinkState(transport: String, state: String)
}

/**
 * A way to move frames to a peer. BLE and Wi-Fi implement it; routing and
 * message logic never touch Bluetooth or sockets directly.
 */
interface Transport {
    val name: String
    fun start(listener: TransportListener): Boolean
    fun stop()
    val isRunning: Boolean
    /** Can a frame be sent to this peer right now over this transport? */
    fun canReach(peerShort: String): Boolean
    /** Sustained bulk transfer possible (Wi-Fi), or control-sized only (BLE)? */
    val bulkCapable: Boolean
    fun linkState(): String
    val bytesSent: Long
    val bytesReceived: Long

    /**
     * Send frames one after another on one link/connection. [onEach] gets the
     * receipt result of each frame and returns true to continue with the next;
     * [onDone] is called once at the end (after a stop, a failure or the last frame).
     */
    fun sendBatch(peerShort: String, frames: List<Frame>, onEach: (Int, DeliveryResult, String) -> Boolean, onDone: () -> Unit)

    fun send(peerShort: String, frame: Frame, cb: (DeliveryResult, String) -> Unit) =
        sendBatch(peerShort, listOf(frame), { _, r, d -> cb(r, d); false }, {})
}
