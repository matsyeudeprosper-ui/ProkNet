package net.prok.proknet.core

import java.util.Locale

/**
 * The one place where engine states become user words (v0.8), in FRENCH
 * since v0.9.2 (first market). Pure, tested. Screens never look at Wi-Fi
 * phases, tunnel states or protocol names; they ask this object. The engine
 * strings it reads (wifiPhase, tunnel state, lastError) stay English: they
 * are protocol values, not words for a user.
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
        if (lastError.isNotEmpty()) return Buyer.LOST          // v0.9: the session failed while the link stayed up (e.g. the relay lost its seller)
        if (wifiUp) return Buyer.SECURING
        return when {
            wifiPhase.startsWith("DOWN") -> Buyer.LOST
            wifiPhase.startsWith("JOINING") || wifiPhase == "TCP" || wifiPhase == "AUTH" -> Buyer.CONNECTING
            else -> Buyer.FINDING
        }
    }

    fun buyerTitle(b: Buyer): String = when (b) {
        Buyer.IDLE -> "Non connecté"
        Buyer.FINDING -> "Recherche d'un fournisseur…"
        Buyer.CONNECTING -> "Connexion…"
        Buyer.SECURING -> "Sécurisation de la connexion…"
        Buyer.STARTING -> "Démarrage d'Internet…"
        Buyer.ONLINE -> "Vous êtes en ligne"
        Buyer.LOST -> "Connexion perdue"
    }

    /** What the user must do next during setup, if anything. */
    fun buyerHint(b: Buyer, wifiPhase: String, vpnConsentPending: Boolean, lastError: String = ""): String = when {
        b == Buyer.CONNECTING && wifiPhase.contains("CONNECT", ignoreCase = true) -> "Android va demander de rejoindre un réseau : appuyez sur CONNECTER"
        b == Buyer.STARTING && vpnConsentPending -> "Android va demander d'autoriser la connexion : appuyez sur OK"
        b == Buyer.FINDING -> "Gardez les deux téléphones proches"
        b == Buyer.LOST -> lostHint(lastError)
        else -> ""
    }

    /**
     * v0.9.1: moving closer only helps when the RADIO failed. A negotiation
     * that failed while the link was perfectly up (no seller behind the relay,
     * no answer to the introduction, contract refused) must not tell the user
     * to walk.
     */
    fun lostHint(lastError: String): String = when {
        lastError.isEmpty() -> "Réessayez"
        // v0.9.21: each admission ending says which stage failed, and each gets its own sentence
        any(lastError, "invitation did not complete") ->
            "Le fournisseur vous a invit\u00e9 mais la connexion directe n'a pas abouti. Rapprochez les t\u00e9l\u00e9phones et r\u00e9essayez."
        any(lastError, "join did not complete") ->
            "La connexion au r\u00e9seau du fournisseur n'a pas abouti. Rapprochez les t\u00e9l\u00e9phones et r\u00e9essayez."
        any(lastError, "neither phone could address") ->
            "Les deux t\u00e9l\u00e9phones ne se voient pas en Wi-Fi Direct. Rapprochez-les et r\u00e9essayez."
        // v0.9.13: the local Wi-Fi Direct link was built and the provider never answered on it
        any(lastError, "no transport answer", "local link formed") ->
            "Connexion locale créée, mais le fournisseur ne répond pas."
        // v0.9.3: the three ways the setup really fails, each with what to check
        any(lastError, "did not answer", "did not introduce", "no contract answer", "no SESSION_OK", "did not join") ->
            "Le fournisseur n'a pas répondu. Sur son téléphone : Wi-Fi et localisation activés, application ouverte."
        any(lastError, "already serving", "busy") ->
            "Le fournisseur est d\u00e9j\u00e0 occup\u00e9 avec un autre t\u00e9l\u00e9phone. R\u00e9essayez dans un moment."
        // v0.9.5: the provider now sends its own Android error, so we can name the ONE thing to change
        any(lastError, "location services are off") ->
            "Le fournisseur doit activer la localisation sur son téléphone, puis réessayer."
        any(lastError, "incompatible mode", "tethering") ->
            "Le fournisseur doit désactiver son point d'accès Android (partage de connexion), puis réessayer."
        any(lastError, "no channel") ->
            "Le fournisseur doit se déconnecter du Wi-Fi et utiliser ses données mobiles : son téléphone ne peut pas partager le canal Wi-Fi."
        any(lastError, "permission") ->
            "Le fournisseur doit autoriser « Appareils à proximité » et la localisation pour Prok, puis réessayer."
        any(lastError, "hotspot", "cannot host", "could not start") ->
            "Le fournisseur n'a pas pu créer le point d'accès. Qu'il active le Wi-Fi et la localisation, puis réessayez."
        any(lastError, "not joined", "dialog", "unavailable") ->
            "Le réseau n'a pas été rejoint. Réessayez et appuyez sur CONNECTER dans la fenêtre Android."
        RADIO_WORDS.any { lastError.contains(it, ignoreCase = true) } -> "Rapprochez-vous du fournisseur et réessayez"
        else -> "Impossible d'établir la connexion. Réessayez."
    }

    private fun any(text: String, vararg words: String) = words.any { text.contains(it, ignoreCase = true) }

    private val RADIO_WORDS = listOf("link closed", "not in range", "out of range", "network lost", "network unavailable", "hotspot", "wi-fi is off", "could not reach", "signal")

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
        Seller.OFF -> "Vous ne partagez pas"
        Seller.NO_INTERNET -> "En attente de votre Internet"
        Seller.AVAILABLE -> "Vous partagez votre Internet"
        Seller.SERVING -> "Quelqu'un utilise votre Internet"
        Seller.LOST -> "Votre Internet est coupé"
    }

    fun sellerHint(s: Seller): String = when (s) {
        Seller.NO_INTERNET -> "Activez les données mobiles ou connectez-vous au Wi-Fi"
        Seller.AVAILABLE -> "Disponible pour les personnes à proximité"
        Seller.LOST -> "Les clients sont en pause jusqu'au retour"
        else -> ""
    }

    // ---- words for numbers ---------------------------------------------------------------------

    fun signalWord(rssi: Int): String = when { rssi >= -60 -> "Bon signal"; rssi >= -75 -> "Signal correct"; rssi >= -90 -> "Signal faible"; else -> "Signal très faible" }

    fun upstreamWord(type: Int): String = when (type) { Tunnel.UP_CELLULAR -> "Données mobiles"; Tunnel.UP_WIFI -> "Wi-Fi"; Tunnel.UP_OTHER -> "Internet"; else -> "Pas d'Internet" }

    /** French formatting everywhere: comma decimal separator, o / Ko / Mo / Go. Locale-independent (never the phone's). */
    private val FR = Locale.FRANCE

    /** Whole CFA for headlines: 5735 centimes -> "57 CFA"; under 1 CFA shows one decimal so small use is visible. */
    fun cfaShort(centimes: Long): String {
        val whole = (centimes + 50) / 100
        return if (centimes in 1..99) String.format(FR, "%.1f CFA", centimes / 100.0) else whole.toString() + " CFA"
    }

    fun cfaExact(centimes: Long): String = String.format(FR, "%.2f CFA", centimes / 100.0)

    fun data(bytes: Long): String = when {
        bytes < 1_000 -> bytes.toString() + " o"
        bytes < 1_000_000 -> (bytes / 1_000).toString() + " Ko"
        bytes < 100_000_000 -> String.format(FR, "%.1f Mo", bytes / 1_000_000.0)
        bytes < 1_000_000_000 -> (bytes / 1_000_000).toString() + " Mo"
        else -> String.format(FR, "%.2f Go", bytes / 1_000_000_000.0)
    }

    fun duration(ms: Long): String {
        val s = ms / 1000
        return when { s < 60 -> s.toString() + " s"; s < 3600 -> (s / 60).toString() + " min"; else -> (s / 3600).toString() + " h " + ((s % 3600) / 60) + " min" }
    }

    fun priceLine(pricePerMb: Int): String = pricePerMb.toString() + " CFA / Mo"
    fun minimumLine(minCfa: Int): String = "Minimum : " + minCfa + " CFA"
    fun limitLine(maxMb: Int): String = if (maxMb == 0) "Limite : illimitée" else "Limite : " + maxMb + " Mo"

    /** v0.9: coverage status in user words. GREEN / YELLOW / RED never appear on screen. */
    fun coverageWord(z: Coverage.ZoneStatus): String = when (z) {
        Coverage.ZoneStatus.GREEN -> "Internet disponible"
        Coverage.ZoneStatus.YELLOW -> "Internet peut être organisé"
        Coverage.ZoneStatus.RED -> "Aucune connexion disponible pour l'instant"
    }

    /** What the phone can say today from its own view: direct offers = available, relayed offers only = can be arranged. */
    fun coverageNow(directOffers: Int, relayedOffers: Int): Coverage.ZoneStatus = when {
        directOffers > 0 -> Coverage.ZoneStatus.GREEN
        relayedOffers > 0 -> Coverage.ZoneStatus.YELLOW
        else -> Coverage.ZoneStatus.RED
    }

    /** Payment status words for the activity list. */
    fun paymentWord(status: String, iAmPayer: Boolean): String = when (status) {
        Market.ST_SETTLED -> "Payé"
        Market.ST_DISPUTED -> "Contesté"
        Market.ST_CANCELLED -> "Annulé"
        else -> if (iAmPayer) "À payer" else "À recevoir"
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
