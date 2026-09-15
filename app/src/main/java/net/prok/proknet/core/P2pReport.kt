package net.prok.proknet.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v0.9.23: **the last Wi-Fi Direct test, kept after cleanup.**
 *
 * The v0.9.22 run produced the first clean answer on the 2.4 GHz group, and
 * the seller's own probe counters for that attempt were already gone from its
 * log by the time the diagnostic was copied. Evidence that survives only in a
 * rolling log window is evidence that gets lost.
 *
 * So each phone keeps one record of its last attempt, written as it happens
 * and NOT cleared by cleanup. It is replaced only when the next test starts.
 */
class P2pReport {

    @Volatile var startedAt: Long = 0L
    @Volatile var topology: String = ""
    @Volatile var role: String = ""
    @Volatile var providing: Boolean = false
    @Volatile var groupChannel: String = ""
    @Volatile var homeChannel: String = ""
    @Volatile var peer: String = ""
    @Volatile var association: String = ""
    @Volatile var membership: String = ""
    @Volatile var discoveryStopped: String = ""
    @Volatile var localIp: String = ""
    @Volatile var peerIp: String = ""
    @Volatile var udpSent: Int = 0
    @Volatile var udpReceived: Int = 0
    @Volatile var udpRepliesSent: Int = 0
    @Volatile var udpRepliesReceived: Int = 0
    @Volatile var tcpAccepted: String = ""
    @Volatile var tcpConnected: String = ""
    @Volatile var verdict: String = ""
    @Volatile var failureStage: String = ""

    val ran: Boolean get() = startedAt > 0L

    /** A new attempt replaces the record. Nothing else ever clears it. */
    fun begin(topology: String, providing: Boolean, now: Long = System.currentTimeMillis()) {
        startedAt = now
        this.topology = topology
        this.providing = providing
        role = ""; groupChannel = ""; homeChannel = ""; peer = ""; association = ""; membership = ""
        discoveryStopped = ""; localIp = ""; peerIp = ""
        udpSent = 0; udpReceived = 0; udpRepliesSent = 0; udpRepliesReceived = 0
        tcpAccepted = ""; tcpConnected = ""; verdict = ""; failureStage = ""
    }

    fun time(): String =
        if (startedAt == 0L) "never" else SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(startedAt))

    private fun or(v: String): String = v.ifEmpty { "-" }

    fun describe(): String {
        if (!ran) return "last p2p test: none since this phone started"
        val sb = StringBuilder()
        sb.append("--- LAST P2P TEST RESULT (kept after cleanup) ---\n")
        sb.append("time: ").append(time()).append("\n")
        sb.append("topology: ").append(or(topology)).append(" | this phone provides the Internet: ").append(providing).append("\n")
        sb.append("role: ").append(or(role)).append("\n")
        sb.append("group channel: ").append(or(groupChannel)).append("\n")
        sb.append("home Wi-Fi channel: ").append(or(homeChannel)).append("\n")
        sb.append("peer: ").append(or(peer)).append("\n")
        sb.append("association: ").append(or(association)).append("\n")
        sb.append("membership: ").append(or(membership)).append("\n")
        sb.append("discovery stopped: ").append(or(discoveryStopped)).append("\n")
        sb.append("local IP: ").append(or(localIp)).append("\n")
        sb.append("peer IP: ").append(or(peerIp)).append("\n")
        sb.append("UDP sent: ").append(udpSent).append(" | UDP received: ").append(udpReceived)
            .append(" | UDP replies sent: ").append(udpRepliesSent)
            .append(" | UDP replies received: ").append(udpRepliesReceived).append("\n")
        sb.append("TCP accepted: ").append(or(tcpAccepted)).append("\n")
        sb.append("TCP connected: ").append(or(tcpConnected)).append("\n")
        sb.append("verdict: ").append(or(verdict)).append("\n")
        sb.append("failure stage: ").append(or(failureStage)).append("\n")
        return sb.toString()
    }
}
