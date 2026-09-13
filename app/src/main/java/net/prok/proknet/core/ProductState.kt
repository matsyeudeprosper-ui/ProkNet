package net.prok.proknet.core

/**
 * The one place where engine states become user words (v0.8). Pure, tested.
 * Screens never look at Wi-Fi phases, tunnel states or protocol names; they
 * ask this object.
 */
object ProductState {

    // ---- buyer ("Get Internet") -------------------------------------------------------------------

    enum class Buyer { IDLE, FINDING, CONNECTING, SECURING, STARTING, ONLINE, LOST }

    /**
     * @param wanted     the user pressed CONNECT and has not stopped
     * @param wifiPhase  WifiTransport.phase (REQUESTING, OFFERED, JOINING..., TCP, AUTH, WIFI UP, DOWN..., IDLE)
     * @param wifiUp     link authenticated with the chosen provider
     * @param tunnel     TunnelClient.state (DISCONNECTED, AGREEING, CONNECTING, TUNNEL UP, INTERNET OK, INTERNET LOST)
     * @param vpnUp      the VPN service holds the tunnel interface
     * @param lastError  TunnelClient.lastError ("" if none)
     */
    fun buyer(wanted: Boolean, wifiPhase: String, wifiUp: Boolean, tunnel: String, vpnUp: Boolean, lastError: String): Buyer {
        if (tunnel == "INTERNET LOST") return Buyer.LOST
        if (tunnel == "INTERNET OK") return Buyer.ONLINE
        if (tunnel == "TUNNEL UP") return if (vpnUp) Buyer.ONLINE else Buyer.STARTING
        if (tunnel == "CONNECTING") return Buyer.STARTING
        if (tunnel == "AGREEING") return Buyer.SECURING
        if (!wanted) return if (lastError.isNotEmpty()) Buyer.LOST else Buyer.IDLE
        // wanted, tunnel not started yet: the Wi-Fi link is being built
        if (wifiUp) return Buyer.SECURING
        return when {
            wifiPhase.startsWith("DOWN") -> Buyer.LOST
            wifiPhase.startsWith("JOINING") || wifiPhase == "TCP" || wifiPhase == "AUTH" -> Buyer.CONNECTING
            else -> Buyer.FINDING
        }
    }

    fun buyerTitle(b: Buyer): String = when (b) {
        Buyer.IDLE -> "Not connected"
        Buyer.FINDING -> "Finding provider…"
        Buyer.CONNECTING -> "Connecting…"
        Buyer.SECURING -> "Securing connection…"
        Buyer.STARTING -> "Starting Internet…"
        Buyer.ONLINE -> "You're online"
        Buyer.LOST -> "Connection lost"
    }

    /** What the user must do next during setup, if anything. */
    fun buyerHint(b: Buyer, wifiPhase: String, vpnConsentPending: Boolean): String = when {
        b == Buyer.CONNECTING && wifiPhase.contains("CONNECT", ignoreCase = true) -> "Android will ask to join a network: tap CONNECT"
        b == Buyer.STARTING && vpnConsentPending -> "Android will ask to allow the connection: tap OK"
        b == Buyer.FINDING -> "Keep both phones close together"
        b == Buyer.LOST -> "Move closer to the provider and try again"
        else -> ""
    }

    val Buyer.busy: Boolean get() = this == Buyer.FINDING || this == Buyer.CONNECTING || this == Buyer.SECURING || this == Buyer.STARTING
    val Buyer.active: Boolean get() = busy || this == Buyer.ONLINE

    // ---- seller ("Share Internet") ---------------------------------------------------------------

    enum class Seller { OFF, NO_INTERNET, AVAILABLE, SERVING, LOST }

    /** @param gateway Gateway.state (SELL OFF, NO UPSTREAM, PROVIDER READY, CONTRACT AGREED, TUNNEL UP (selling), INTERNET LOST) */
    fun seller(providing: Boolean, gateway: String): Seller = when {
        !providing -> Seller.OFF
        gateway == "INTERNET LOST" -> Seller.LOST
        gateway.startsWith("TUNNEL UP") || gateway == "CONTRACT AGREED" -> Seller.SERVING
        gateway == "PROVIDER READY" -> Seller.AVAILABLE
        else -> Seller.NO_INTERNET
    }

    fun sellerTitle(s: Seller): String = when (s) {
        Seller.OFF -> "Not sharing"
        Seller.NO_INTERNET -> "Waiting for your Internet"
        Seller.AVAILABLE -> "You're sharing Internet"
        Seller.SERVING -> "Someone is using your Internet"
        Seller.LOST -> "Your Internet is down"
    }

    fun sellerHint(s: Seller): String = when (s) {
        Seller.NO_INTERNET -> "Turn on mobile data or connect to Wi-Fi"
        Seller.AVAILABLE -> "Available to people nearby"
        Seller.LOST -> "Customers are paused until it comes back"
        else -> ""
    }

    // ---- words for numbers ---------------------------------------------------------------------

    fun signalWord(rssi: Int): String = when { rssi >= -60 -> "Good signal"; rssi >= -75 -> "OK signal"; rssi >= -90 -> "Weak signal"; else -> "Poor signal" }

    fun upstreamWord(type: Int): String = when (type) { Tunnel.UP_CELLULAR -> "Mobile data"; Tunnel.UP_WIFI -> "Wi-Fi"; Tunnel.UP_OTHER -> "Internet"; else -> "No Internet" }

    /** Whole CFA for headlines: 5735 centimes -> "57 CFA"; under 1 CFA shows one decimal so small use is visible. */
    fun cfaShort(centimes: Long): String {
        val whole = (centimes + 50) / 100
        return if (centimes in 1..99) String.format("%.1f CFA", centimes / 100.0) else whole.toString() + " CFA"
    }

    fun cfaExact(centimes: Long): String = Market.cfa(centimes)

    fun data(bytes: Long): String = when {
        bytes < 1_000 -> bytes.toString() + " B"
        bytes < 1_000_000 -> (bytes / 1_000).toString() + " KB"
        bytes < 100_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
        bytes < 1_000_000_000 -> (bytes / 1_000_000).toString() + " MB"
        else -> String.format("%.2f GB", bytes / 1_000_000_000.0)
    }

    fun duration(ms: Long): String {
        val s = ms / 1000
        return when { s < 60 -> s.toString() + " s"; s < 3600 -> (s / 60).toString() + " min"; else -> (s / 3600).toString() + " h " + ((s % 3600) / 60) + " min" }
    }

    fun priceLine(pricePerMb: Int): String = pricePerMb.toString() + " CFA / MB"
    fun minimumLine(minCfa: Int): String = if (minCfa == 0) "Minimum: 0 CFA" else "Minimum: " + minCfa + " CFA"
    fun limitLine(maxMb: Int): String = if (maxMb == 0) "Limit: Unlimited" else "Limit: " + maxMb + " MB"

    /** Payment status words for the activity list. */
    fun paymentWord(status: String, iAmPayer: Boolean): String = when (status) {
        Market.ST_SETTLED -> "Paid"
        Market.ST_DISPUTED -> "Disputed"
        Market.ST_CANCELLED -> "Cancelled"
        else -> if (iAmPayer) "To pay" else "To receive"
    }

    /** Wallet summary over pending entries: what I must pay, what I should receive, fees I owe Prok. */
    class Wallet(val toPay: Long, val toReceive: Long, val prokFees: Long)
    fun wallet(entries: List<Market.Entry>, me: String): Wallet {
        var pay = 0L; var recv = 0L; var fees = 0L
        for (e in entries) if (e.status == Market.ST_PENDING) {
            if (e.payer == me && e.recipient == Market.PROK_ID) fees += e.amountCentimes
            else if (e.payer == me) pay += e.amountCentimes
            else if (e.recipient == me) recv += e.amountCentimes
        }
        return Wallet(pay, recv, fees)
    }
}
