package net.prok.proknet.core

/**
 * v0.10.2: the words on the Bluetooth test screen, decided by pure code.
 *
 * The person testing is not a developer. The screen says one thing at a
 * time in plain words, and the copied result starts with a short summary
 * that a reader can judge without the log. Every line here is a function of
 * a [Snapshot], so the wording is tested on the JVM and cannot drift from
 * the real state.
 */
object BtLabText {
    enum class Role { NONE, SELLER, BUYER }

    /** What the screen knows. `httpsOk == null` means the Internet test has not run yet. */
    data class Snapshot(
        val role: Role,
        val nodeRunning: Boolean,
        val bluetoothOn: Boolean,
        val upstream: String,               // "Wi-Fi", "mobile data", "other", "none"
        val upstreamValidated: Boolean,
        val sellerFound: Boolean,
        val phase: BulkPlan.Phase,
        val authenticated: Boolean,
        val probeStep: BulkPlan.ProbeStep,
        val buyerToSeller: BulkPlan.Direction?,
        val sellerToBuyer: BulkPlan.Direction?,
        val verdict: BulkPlan.Verdict,
        val contract: Boolean,
        val tunnelState: String,
        val vpn: Boolean,
        val dnsCount: Int,
        val httpsOk: Boolean?,
        val customerConnected: Boolean,
        val lastError: String,
    )

    private const val OK = "✅"
    private const val BAD = "❌"
    fun yes(b: Boolean) = if (b) "YES" else "NO"
    private fun mark(b: Boolean) = if (b) OK else BAD

    private fun bluetoothConnected(s: Snapshot) = s.phase == BulkPlan.Phase.UP || (s.authenticated && s.phase != BulkPlan.Phase.FAILED)
    private fun tunnelUp(s: Snapshot) = s.tunnelState == "TUNNEL UP" || s.tunnelState == "INTERNET OK" || s.tunnelState == "INTERNET LOST"
    private fun probeFailed(s: Snapshot) = s.verdict != BulkPlan.Verdict.NOT_RUN && !BulkPlan.probePassed(s.verdict)
    private fun probeRunning(s: Snapshot) = s.phase == BulkPlan.Phase.UP && s.verdict == BulkPlan.Verdict.NOT_RUN
    fun internetWorking(s: Snapshot) = tunnelUp(s) && s.vpn && s.httpsOk == true

    /** The stage the attempt died at, or NONE. */
    fun failureStage(s: Snapshot): String = when {
        probeFailed(s) -> "PROBE"
        s.phase == BulkPlan.Phase.FAILED && s.authenticated -> "TUNNEL"
        s.phase == BulkPlan.Phase.FAILED && s.lastError.contains("handshake", ignoreCase = true) -> "AUTH"
        s.phase == BulkPlan.Phase.FAILED -> "BLUETOOTH"
        s.lastError.isNotEmpty() && !bluetoothConnected(s) -> "BLUETOOTH"
        s.httpsOk == false -> "INTERNET"
        s.lastError.isNotEmpty() && !tunnelUp(s) -> if (s.contract) "TUNNEL" else "CONTRACT"
        s.lastError.isNotEmpty() -> "INTERNET"
        else -> "NONE"
    }

    /** One plain sentence, or null when nothing failed. */
    fun failureSentence(s: Snapshot): String? = when (failureStage(s)) {
        "NONE" -> null
        "PROBE" -> BulkPlan.failureSentence(s.verdict, s.buyerToSeller, s.sellerToBuyer)
        "BLUETOOTH" -> "Bluetooth connection failed."
        "AUTH" -> "The other phone could not be verified."
        "CONTRACT" -> "The seller did not accept the connection."
        "TUNNEL" -> "The Internet tunnel was lost."
        else -> "Internet test failed."
    }

    /** The two ready lines at the top of the seller screen. */
    fun sellerReadyLines(s: Snapshot): List<String> {
        val src = if (s.upstream == "none") "Internet source: none " + BAD
            else "Internet source: " + s.upstream + " " + mark(s.upstreamValidated)
        return listOf(src, "Bluetooth: " + (if (s.bluetoothOn) "Ready " + OK else "Off " + BAD))
    }

    /** The big status on the seller screen. */
    fun sellerStatus(s: Snapshot): String {
        failureSentence(s)?.let { return it }
        return when {
            s.role != Role.SELLER -> "Tap START SHARING"
            s.customerConnected -> "Sharing Internet " + OK
            BulkPlan.probePassed(s.verdict) -> "Bluetooth connection works both ways " + OK + "\nInternet sharing starting..."
            probeRunning(s) -> "Phone connected " + OK + "\nTesting connection..."
            s.phase == BulkPlan.Phase.LISTENING || s.phase == BulkPlan.Phase.CONNECTING || s.phase == BulkPlan.Phase.AUTH -> "Phone connecting..."
            else -> "Waiting for another phone..."
        }
    }

    /** The big status on the buyer screen. */
    fun buyerStatus(s: Snapshot): String {
        failureSentence(s)?.let { return it }
        return when {
            internetWorking(s) -> "INTERNET WORKING " + OK
            tunnelUp(s) && s.vpn -> "Testing Internet..."
            tunnelUp(s) -> "Starting Internet...\n(accept the VPN if the phone asks)"
            BulkPlan.probePassed(s.verdict) -> "Starting Internet..."
            probeRunning(s) -> "Checking both directions..."
            s.phase == BulkPlan.Phase.REQUESTED || s.phase == BulkPlan.Phase.OFFERED || s.phase == BulkPlan.Phase.CONNECTING || s.phase == BulkPlan.Phase.AUTH -> "Connecting..."
            s.role == Role.BUYER -> "Connecting..."
            !s.bluetoothOn -> "Bluetooth: Off " + BAD
            s.sellerFound -> "Seller found " + OK + "\nTap CONNECT"
            else -> "Looking for the seller..."
        }
    }

    /** The detail lines under a working connection. */
    fun buyerDetailLines(s: Snapshot): List<String> = listOf(
        "Bluetooth: " + (if (bluetoothConnected(s)) "connected" else "not connected"),
        "Seller Internet: " + (if (tunnelUp(s) && s.tunnelState != "INTERNET LOST") "available" else "not available"),
        "VPN: " + (if (s.vpn) "connected" else "not connected"),
        "DNS: " + (if (s.dnsCount > 0) "working" else "waiting for the first lookup"),
        "HTTPS: " + (when (s.httpsOk) { true -> "working"; false -> "failed"; null -> "not tested yet" }),
    )

    private fun directionLine(d: BulkPlan.Direction?): String =
        if (d == null) "not run" else d.result.name + " " + d.bytes + "/" + d.target + " B"

    /** The summary a reader can judge without the log. */
    fun summary(s: Snapshot): String {
        val sb = StringBuilder("ProkNet Bluetooth Test\n\n")
        sb.append("Phone role: ").append(s.role.name).append("\n")
        sb.append("Bluetooth connected: ").append(yes(bluetoothConnected(s))).append("\n")
        sb.append("Authentication: ").append(yes(s.authenticated)).append("\n")
        sb.append("Buyer -> seller: ").append(directionLine(s.buyerToSeller)).append("\n")
        sb.append("Seller -> buyer: ").append(directionLine(s.sellerToBuyer)).append("\n")
        if (s.role == Role.SELLER) {
            sb.append("Customer connected: ").append(yes(s.customerConnected)).append("\n")
        } else {
            sb.append("Contract: ").append(yes(s.contract)).append("\n")
            sb.append("VPN: ").append(yes(s.vpn)).append("\n")
            sb.append("DNS: ").append(yes(s.dnsCount > 0)).append("\n")
            sb.append("HTTPS: ").append(when (s.httpsOk) { true -> "YES"; false -> "NO"; null -> "not tested" }).append("\n")
            sb.append("Internet: ").append(yes(internetWorking(s))).append("\n")
        }
        val stage = failureStage(s)
        if (stage != "NONE") {
            sb.append("Failure stage: ").append(stage).append("\n")
            sb.append("Reason: ").append(failureSentence(s)).append("\n")
            if (s.lastError.isNotEmpty()) sb.append("Detail: ").append(s.lastError).append("\n")
        }
        return sb.toString()
    }
}
