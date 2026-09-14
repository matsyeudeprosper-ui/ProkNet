package net.prok.proknet.core

import java.nio.ByteBuffer

/**
 * Small pure encodings shared by the transports (v0.5). No Android imports;
 * unit-tested on the JVM.
 */
object Wire {
    // ---- identity record (BLE IDENTITY characteristic, Wi-Fi HELLO) -----------------------

    const val IDENTITY_VERSION = 2

    class IdentityRecord(val id: ByteArray, val pub: ByteArray, val name: String) {
        val idHex get() = id.toHex()
        val shortId get() = idHex.substring(0, Packet.SHORT_ID_LEN * 2)
    }

    /** [2][id 16][pub 64][nameLen u8][name utf8] */
    fun identityRecord(id: ByteArray, pub: ByteArray, name: String): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8).let { if (it.size > 64) it.copyOf(64) else it }
        return ByteBuffer.allocate(1 + Packet.ID_LEN + Crypto.PUB_LEN + 1 + n.size)
            .put(IDENTITY_VERSION.toByte()).put(id).put(pub).put(n.size.toByte()).put(n).array()
    }

    /** Null unless well-formed AND the ID really is derived from the public key (self-authenticating). */
    fun parseIdentityRecord(bytes: ByteArray?): IdentityRecord? {
        if (bytes == null || bytes.size < 1 + Packet.ID_LEN + Crypto.PUB_LEN + 1) return null
        if ((bytes[0].toInt() and 0xFF) != IDENTITY_VERSION) return null
        return try {
            val b = ByteBuffer.wrap(bytes); b.get()
            val id = ByteArray(Packet.ID_LEN).also { b.get(it) }
            val pub = ByteArray(Crypto.PUB_LEN).also { b.get(it) }
            val n = b.get().toInt() and 0xFF
            if (b.remaining() < n) return null
            val name = String(ByteArray(n).also { b.get(it) }, Charsets.UTF_8)
            if (!Crypto.deriveId(pub).contentEquals(id)) return null
            Crypto.publicKeyFrom(pub) // must be a valid curve point
            IdentityRecord(id, pub, name)
        } catch (e: Exception) { null }
    }

    // ---- Wi-Fi control messages (body of a KIND_CONTROL signed plaintext) -------------------

    const val OP_WIFI_REQUEST = 1
    const val OP_WIFI_OFFER = 2
    const val OP_WIFI_CANCEL = 3
    /** v0.9.9: "invite me into your Wi-Fi Direct group", carrying this phone's P2P device name. */
    const val OP_P2P_REQUEST = 4

    /** Hotspot security as reported by the host (v0.5.1). */
    const val SEC_UNKNOWN = 0
    const val SEC_WPA2 = 1
    const val SEC_WPA3 = 2
    const val SEC_TRANSITION = 3
    const val SEC_OPEN = 4

    /** v0.9.4: why a host gave up, so the waiting phone can say something useful instead of timing out. */
    const val CANCEL_GENERIC = 0
    const val CANCEL_NO_HOTSPOT = 1   // could not create the local-only hotspot (Wi-Fi / Location off, tethering...)
    const val CANCEL_BUSY = 2         // already linked to someone else and serving them
    const val CANCEL_P2P = 3          // v0.9.9: could not bring the other phone into the Wi-Fi Direct group

    fun cancelName(reason: Int) = when (reason) {
        CANCEL_NO_HOTSPOT -> "cannot create the hotspot"; CANCEL_BUSY -> "busy with another phone"
        CANCEL_P2P -> "cannot invite into the Wi-Fi Direct group"; else -> "cancelled"
    }

    /** The wording the UI layer classifies (English, like every other engine string). */
    fun cancelReasonText(reason: Int) = when (reason) {
        CANCEL_NO_HOTSPOT -> "the provider could not start its Wi-Fi hotspot"
        CANCEL_BUSY -> "the provider is already serving another phone"
        CANCEL_P2P -> "the provider could not invite this phone into its Wi-Fi Direct group"
        else -> "the provider stopped the connection attempt"
    }

    sealed class Control {
        class WifiRequest(val port: Int) : Control()
        class WifiOffer(val ssid: String, val pass: String, val port: Int, val ips: List<String>, val security: Int = SEC_UNKNOWN, val hidden: Boolean = false) : Control()
        /** v0.9.9: buyer -> seller, "invite me into your group"; [deviceName] is this phone's P2P name. */
        class P2pRequest(val deviceName: String) : Control()
        /**
         * [reason] is CANCEL_*; a v0.5..v0.9.3 peer sends no byte at all, which reads as CANCEL_GENERIC.
         * v0.9.5: [detail] is the host's own error text, so the waiting phone can show and log the REAL
         * cause (which Android error the hotspot returned) without anybody opening the other phone.
         */
        class WifiCancel(val reason: Int = CANCEL_GENERIC, val detail: String = "") : Control()
    }

    /**
     * Which specifier(s) to try for a hotspot of the given security, in order.
     * A WPA2 specifier matches WPA2 and transition-mode networks; a WPA3
     * specifier is needed for SAE-only hotspots. Unknown: try both.
     */
    fun joinAttempts(security: Int): List<Int> = when (security) {
        SEC_WPA2 -> listOf(SEC_WPA2)
        SEC_WPA3 -> listOf(SEC_WPA3)
        SEC_OPEN -> listOf(SEC_OPEN)
        SEC_TRANSITION -> listOf(SEC_WPA2, SEC_WPA3)
        else -> listOf(SEC_WPA2, SEC_WPA3)
    }

    fun wifiRequest(port: Int): ByteArray = ByteBuffer.allocate(3).put(OP_WIFI_REQUEST.toByte()).putShort(port.toShort()).array()
    const val CANCEL_DETAIL_MAX = 120

    fun p2pRequest(deviceName: String): ByteArray {
        val n = deviceName.take(64).toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(1 + n.size).put(OP_P2P_REQUEST.toByte()).put(n).array()
    }

    fun wifiCancel(reason: Int = CANCEL_GENERIC, detail: String = ""): ByteArray {
        val d = detail.take(CANCEL_DETAIL_MAX).toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(2 + d.size).put(OP_WIFI_CANCEL.toByte()).put(reason.toByte()).put(d).array()
    }

    fun wifiOffer(ssid: String, pass: String, port: Int, ips: List<String>, security: Int = SEC_UNKNOWN, hidden: Boolean = false): ByteArray {
        val s = ssid.toByteArray(Charsets.UTF_8); val p = pass.toByteArray(Charsets.UTF_8)
        require(s.size <= 255 && p.size <= 255 && ips.size <= 255)
        val ipBytes = ips.map { it.toByteArray(Charsets.UTF_8) }
        val b = ByteBuffer.allocate(1 + 1 + s.size + 1 + p.size + 2 + 1 + ipBytes.sumOf { 1 + it.size } + 2)
        b.put(OP_WIFI_OFFER.toByte()).put(s.size.toByte()).put(s).put(p.size.toByte()).put(p).putShort(port.toShort()).put(ips.size.toByte())
        for (ip in ipBytes) { b.put(ip.size.toByte()); b.put(ip) }
        b.put(security.toByte()).put(if (hidden) 1 else 0)
        return b.array()
    }

    fun parseControl(body: ByteArray?): Control? {
        if (body == null || body.isEmpty()) return null
        return try {
            val b = ByteBuffer.wrap(body)
            when (b.get().toInt() and 0xFF) {
                OP_WIFI_REQUEST -> Control.WifiRequest(b.short.toInt() and 0xFFFF)
                OP_P2P_REQUEST -> Control.P2pRequest(if (b.remaining() > 0) String(ByteArray(b.remaining()).also { b.get(it) }, Charsets.UTF_8) else "")
                OP_WIFI_CANCEL -> {
                    val r = if (b.remaining() >= 1) b.get().toInt() and 0xFF else CANCEL_GENERIC
                    val d = if (b.remaining() > 0) String(ByteArray(b.remaining()).also { b.get(it) }, Charsets.UTF_8) else ""
                    Control.WifiCancel(r, d)
                }
                OP_WIFI_OFFER -> {
                    val sn = b.get().toInt() and 0xFF; val ssid = String(ByteArray(sn).also { b.get(it) }, Charsets.UTF_8)
                    val pn = b.get().toInt() and 0xFF; val pass = String(ByteArray(pn).also { b.get(it) }, Charsets.UTF_8)
                    val port = b.short.toInt() and 0xFFFF
                    val n = b.get().toInt() and 0xFF
                    val ips = ArrayList<String>()
                    repeat(n) { val l = b.get().toInt() and 0xFF; ips.add(String(ByteArray(l).also { b.get(it) }, Charsets.UTF_8)) }
                    // v0.5.1 trailing fields; absent in v0.5.0 offers
                    val sec = if (b.remaining() >= 1) b.get().toInt() and 0xFF else SEC_UNKNOWN
                    val hidden = if (b.remaining() >= 1) b.get().toInt() != 0 else false
                    if (ssid.isEmpty() || port == 0) null else Control.WifiOffer(ssid, pass, port, ips, sec, hidden)
                }
                else -> null
            }
        } catch (e: Exception) { null }
    }

    // ---- Wi-Fi TCP framing and handshake ---------------------------------------------------

    const val FRAME_PACKET = 1
    const val FRAME_RECEIPT = 2
    const val FRAME_HELLO = 3
    const val FRAME_AUTH = 4
    const val FRAME_TUNNEL = 5     // v0.6: [tunnel type][stream id][data], see core/Tunnel.kt
    const val FRAME_RELAY = 6      // v0.9: end-to-end sealed tunnel frame forwarded by a relay, see core/Relay.kt
    const val FRAME_RELAY_INFO = 7 // v0.9: relay introductions (who is behind me), see core/Relay.kt
    const val MAX_FRAME = 1 + 5 + 16 * 1024 + 64
    const val NONCE_LEN = 16

    /** [type 1][payload] (the u32 length prefix is written by the socket layer). */
    fun frame(type: Int, payload: ByteArray): ByteArray = ByteBuffer.allocate(1 + payload.size).put(type.toByte()).put(payload).array()

    fun receiptPayload(status: Int, msgId: ByteArray): ByteArray = ByteBuffer.allocate(1 + Packet.MSG_ID_LEN).put(status.toByte()).put(msgId, 0, Packet.MSG_ID_LEN).array()

    class Receipt(val status: Int, val msgId: ByteArray)
    fun parseReceipt(p: ByteArray?): Receipt? =
        if (p == null || p.size < 1 + Packet.MSG_ID_LEN) null else Receipt(p[0].toInt() and 0xFF, p.copyOfRange(1, 1 + Packet.MSG_ID_LEN))

    /** HELLO = identity record + nonce: [record][nonce 16]. The record's own length is implied by its name length. */
    fun hello(id: ByteArray, pub: ByteArray, name: String, nonce: ByteArray): ByteArray {
        require(nonce.size == NONCE_LEN)
        return identityRecord(id, pub, name) + nonce
    }

    class Hello(val record: IdentityRecord, val nonce: ByteArray)
    fun parseHello(p: ByteArray?): Hello? {
        if (p == null || p.size < NONCE_LEN + 1 + Packet.ID_LEN + Crypto.PUB_LEN + 1) return null
        val rec = parseIdentityRecord(p.copyOfRange(0, p.size - NONCE_LEN)) ?: return null
        return Hello(rec, p.copyOfRange(p.size - NONCE_LEN, p.size))
    }

    /** What each side signs to prove it holds the key behind its ID, bound to both nonces and both IDs. */
    fun authData(myId: ByteArray, peerId: ByteArray, myNonce: ByteArray, peerNonce: ByteArray): ByteArray =
        "ProkNet-wifi-1".toByteArray(Charsets.UTF_8) + myId + peerId + myNonce + peerNonce
}
