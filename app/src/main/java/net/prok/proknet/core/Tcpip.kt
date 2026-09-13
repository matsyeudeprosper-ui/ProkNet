package net.prok.proknet.core

import java.nio.ByteBuffer

/**
 * Minimal IPv4 / TCP / UDP packet parsing and building for the VPN path (v0.6).
 * Pure Kotlin, no Android, unit-tested. Only what a user-space TCP endpoint
 * needs: headers, checksums, and segment construction.
 */
object Tcpip {
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17
    const val TCP_FIN = 0x01
    const val TCP_SYN = 0x02
    const val TCP_RST = 0x04
    const val TCP_PSH = 0x08
    const val TCP_ACK = 0x10

    class Ip4(
        val srcIp: Int, val dstIp: Int, val protocol: Int, val headerLen: Int, val totalLen: Int, val id: Int,
        val payload: ByteArray, val ttl: Int,
    )

    class Tcp(
        val srcPort: Int, val dstPort: Int, val seq: Long, val ack: Long, val flags: Int, val window: Int,
        val headerLen: Int, val payload: ByteArray, val mss: Int,
    ) {
        val syn get() = flags and TCP_SYN != 0
        val fin get() = flags and TCP_FIN != 0
        val rst get() = flags and TCP_RST != 0
        val isAck get() = flags and TCP_ACK != 0
    }

    class Udp(val srcPort: Int, val dstPort: Int, val payload: ByteArray)

    fun ipToInt(dotted: String): Int {
        val p = dotted.split('.')
        require(p.size == 4)
        return (p[0].toInt() shl 24) or (p[1].toInt() shl 16) or (p[2].toInt() shl 8) or p[3].toInt()
    }

    fun ipToString(ip: Int): String = ((ip ushr 24) and 0xFF).toString() + "." + ((ip ushr 16) and 0xFF) + "." + ((ip ushr 8) and 0xFF) + "." + (ip and 0xFF)

    /** Returns null for anything that is not a well-formed IPv4 packet. Never throws. */
    fun parseIp4(b: ByteArray, len: Int = b.size): Ip4? {
        if (len < 20 || b.size < len) return null
        val ver = (b[0].toInt() ushr 4) and 0xF
        if (ver != 4) return null
        val ihl = (b[0].toInt() and 0xF) * 4
        if (ihl < 20 || ihl > len) return null
        val total = ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        if (total < ihl || total > len) return null
        val proto = b[9].toInt() and 0xFF
        val src = readInt(b, 12); val dst = readInt(b, 16)
        val id = ((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF)
        val flagsFrag = ((b[6].toInt() and 0xFF) shl 8) or (b[7].toInt() and 0xFF)
        if (flagsFrag and 0x1FFF != 0) return null // fragments unsupported
        return Ip4(src, dst, proto, ihl, total, id, b.copyOfRange(ihl, total), b[8].toInt() and 0xFF)
    }

    fun parseTcp(p: ByteArray): Tcp? {
        if (p.size < 20) return null
        val doff = ((p[12].toInt() ushr 4) and 0xF) * 4
        if (doff < 20 || doff > p.size) return null
        var mss = 0
        var i = 20
        while (i < doff) {
            val kind = p[i].toInt() and 0xFF
            if (kind == 0) break
            if (kind == 1) { i++; continue }
            if (i + 1 >= doff) break
            val l = p[i + 1].toInt() and 0xFF
            if (l < 2 || i + l > doff) break
            if (kind == 2 && l == 4) mss = ((p[i + 2].toInt() and 0xFF) shl 8) or (p[i + 3].toInt() and 0xFF)
            i += l
        }
        return Tcp(
            readShort(p, 0), readShort(p, 2), readInt(p, 4).toLong() and 0xFFFFFFFFL, readInt(p, 8).toLong() and 0xFFFFFFFFL,
            p[13].toInt() and 0xFF, readShort(p, 14), doff, p.copyOfRange(doff, p.size), mss,
        )
    }

    fun parseUdp(p: ByteArray): Udp? {
        if (p.size < 8) return null
        val len = readShort(p, 4)
        if (len < 8 || len > p.size) return null
        return Udp(readShort(p, 0), readShort(p, 2), p.copyOfRange(8, len))
    }

    /** Build an IPv4 packet with a TCP segment. [seq]/[ack] are 32-bit unsigned. */
    fun buildTcp(srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int, seq: Long, ack: Long, flags: Int, window: Int, payload: ByteArray, mssOption: Int = 0, ipId: Int = 0): ByteArray {
        val opts = if (mssOption > 0) 4 else 0
        val tcpLen = 20 + opts + payload.size
        val total = 20 + tcpLen
        val b = ByteArray(total)
        writeIpHeader(b, srcIp, dstIp, PROTO_TCP, total, ipId)
        val t = 20
        writeShort(b, t, srcPort); writeShort(b, t + 2, dstPort)
        writeInt(b, t + 4, seq.toInt()); writeInt(b, t + 8, ack.toInt())
        b[t + 12] = (((20 + opts) / 4) shl 4).toByte()
        b[t + 13] = flags.toByte()
        writeShort(b, t + 14, window.coerceIn(0, 65535))
        if (mssOption > 0) { b[t + 20] = 2; b[t + 21] = 4; writeShort(b, t + 22, mssOption) }
        System.arraycopy(payload, 0, b, t + 20 + opts, payload.size)
        val cs = transportChecksum(b, srcIp, dstIp, PROTO_TCP, t, tcpLen)
        writeShort(b, t + 16, cs)
        return b
    }

    /** Build an IPv4 packet with a UDP datagram. */
    fun buildUdp(srcIp: Int, dstIp: Int, srcPort: Int, dstPort: Int, payload: ByteArray, ipId: Int = 0): ByteArray {
        val udpLen = 8 + payload.size
        val total = 20 + udpLen
        val b = ByteArray(total)
        writeIpHeader(b, srcIp, dstIp, PROTO_UDP, total, ipId)
        writeShort(b, 20, srcPort); writeShort(b, 22, dstPort); writeShort(b, 24, udpLen)
        System.arraycopy(payload, 0, b, 28, payload.size)
        val cs = transportChecksum(b, srcIp, dstIp, PROTO_UDP, 20, udpLen)
        writeShort(b, 26, if (cs == 0) 0xFFFF else cs)
        return b
    }

    private fun writeIpHeader(b: ByteArray, srcIp: Int, dstIp: Int, proto: Int, total: Int, id: Int) {
        b[0] = 0x45; b[1] = 0
        writeShort(b, 2, total); writeShort(b, 4, id and 0xFFFF)
        b[6] = 0x40; b[7] = 0 // don't fragment
        b[8] = 64; b[9] = proto.toByte()
        writeInt(b, 12, srcIp); writeInt(b, 16, dstIp)
        writeShort(b, 10, 0)
        writeShort(b, 10, checksum(b, 0, 20, 0))
    }

    /** True if the IPv4 header checksum of a parsed packet is valid. */
    fun ipChecksumOk(b: ByteArray): Boolean {
        val ihl = (b[0].toInt() and 0xF) * 4
        return checksum(b, 0, ihl, 0) == 0
    }

    /** True if the TCP/UDP checksum of the packet is valid (UDP zero checksum accepted). */
    fun transportChecksumOk(b: ByteArray, ip: Ip4): Boolean {
        val len = ip.totalLen - ip.headerLen
        if (ip.protocol == PROTO_UDP && readShort(b, ip.headerLen + 6) == 0) return true
        return transportChecksum(b, ip.srcIp, ip.dstIp, ip.protocol, ip.headerLen, len, verify = true) == 0
    }

    /** RFC 1071 one's complement sum over [len] bytes at [off], seeded with [initial] (already folded 16-bit partial). */
    fun checksum(b: ByteArray, off: Int, len: Int, initial: Long): Int {
        var sum = initial
        var i = off
        val end = off + len
        while (i + 1 < end) { sum += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF); i += 2 }
        if (i < end) sum += (b[i].toInt() and 0xFF) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xFFFF) + (sum ushr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun transportChecksum(b: ByteArray, srcIp: Int, dstIp: Int, proto: Int, off: Int, len: Int, verify: Boolean = false): Int {
        var pseudo = 0L
        pseudo += (srcIp ushr 16) and 0xFFFF; pseudo += srcIp and 0xFFFF
        pseudo += (dstIp ushr 16) and 0xFFFF; pseudo += dstIp and 0xFFFF
        pseudo += proto; pseudo += len
        if (!verify) { b[off + (if (proto == PROTO_TCP) 16 else 6)] = 0; b[off + (if (proto == PROTO_TCP) 17 else 7)] = 0 }
        return checksum(b, off, len, pseudo)
    }

    fun readShort(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    fun readInt(b: ByteArray, i: Int): Int = ByteBuffer.wrap(b, i, 4).int
    fun writeShort(b: ByteArray, i: Int, v: Int) { b[i] = (v ushr 8).toByte(); b[i + 1] = v.toByte() }
    fun writeInt(b: ByteArray, i: Int, v: Int) { b[i] = (v ushr 24).toByte(); b[i + 1] = (v ushr 16).toByte(); b[i + 2] = (v ushr 8).toByte(); b[i + 3] = v.toByte() }
}
