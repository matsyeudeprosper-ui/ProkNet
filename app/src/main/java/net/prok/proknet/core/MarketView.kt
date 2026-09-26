package net.prok.proknet.core

/**
 * v0.19.0: Prok Market as the phone shows it - the price table, the words for every
 * invoice and listing state, the payment instruction, the safety guide, and the readers
 * that turn the Brain's answers into rows a screen can draw.
 *
 * The product rule: every amount and every word here equals the server's. The price table
 * and the state texts come from ONE file both halves read
 * (server/tests/fixtures/market_prices.txt); MarketViewTest holds this object to it. A
 * state this build does not know is shown cautiously ("En vérification"), never as "Payé"
 * or "Publiée".
 *
 * Pure: no android.*, no org.json. The Brain writes JSON with `\uXXXX` escapes and nests
 * arrays inside listing objects, so this has its own small depth-aware reader rather than
 * [LedgerView.objects] (which stops at the first `]`).
 */
object MarketView {

    class Price(val centimes: Long, val durationDays: Int, val extras: Map<String, String>) {
        fun extra(key: String): Int = extras[key]?.toIntOrNull() ?: 0
    }

    /** KEY -> price. Must equal DEFAULT_PRICES in server/brain/market.py and the fixture. */
    val PRICES: Map<String, Price> = linkedMapOf(
        "POST" to Price(10_000L, 30, mapOf("photos" to "5")),
        "BOOST" to Price(20_000L, 7, mapOf("zones" to "1")),
        "PACKAGE5" to Price(40_000L, 90, mapOf("slots" to "5")),
        "STOREFRONT" to Price(200_000L, 30, mapOf("catalog" to "1", "slots" to "30")),
        "VOUCHER" to Price(0L, 30, mapOf("face_value" to "10000", "limit" to "100")),
    )

    /** Invoice state -> what the seller's screen says. Must equal market.INVOICE_TEXT. */
    val INVOICE_TEXT: Map<String, String> = linkedMapOf(
        "OPEN" to "En attente de confirmation",
        "PAID" to "Payé",
        "EXPIRED" to "Expiré",
        "CANCELLED" to "Annulé",
        "REFUND_REQUESTED" to "Remboursement en cours",
        "REFUNDED" to "Remboursé",
    )

    /** Listing state -> what the seller's screen says. Must equal market.LISTING_TEXT. */
    val LISTING_TEXT: Map<String, String> = linkedMapOf(
        "DRAFT" to "Brouillon",
        "AWAITING_PAYMENT" to "En attente de paiement",
        "AWAITING_REVIEW" to "En attente de modération",
        "PUBLISHED" to "Publiée",
        "REJECTED" to "Refusée",
        "EXPIRED" to "Expirée",
        "WITHDRAWN" to "Retirée",
        "HIDDEN" to "Masquée",
    )
    const val UNKNOWN_TEXT = "En vérification"
    const val SPONSORED_LABEL = "Sponsorisé"
    const val MAX_PHOTOS = 5
    const val MAX_PHOTO_BYTES = 300 * 1024
    const val MAX_MESSAGE = 500

    val CATEGORIES: Map<String, String> = linkedMapOf(
        "PHONES" to "Téléphones", "ELECTRONICS" to "Électronique", "HOME" to "Maison", "FASHION" to "Mode",
        "VEHICLES" to "Véhicules", "PROPERTY" to "Immobilier", "SERVICES" to "Services", "KIDS" to "Enfants",
        "BEAUTY" to "Beauté", "FOOD" to "Alimentation", "OTHER" to "Autre",
    )
    val CONDITIONS: Map<String, String> = linkedMapOf(
        "NEW" to "Neuf", "LIKE_NEW" to "Comme neuf", "GOOD" to "Bon état", "USED" to "Usagé", "FOR_PARTS" to "Pour pièces",
    )

    fun invoiceText(state: String): String = INVOICE_TEXT[state] ?: UNKNOWN_TEXT
    fun listingText(state: String): String = LISTING_TEXT[state] ?: UNKNOWN_TEXT
    fun categoryName(key: String): String = CATEGORIES[key] ?: key
    fun conditionName(key: String): String = CONDITIONS[key] ?: key

    /** Whole francs, the way a price is spoken: "2 500 F". Centimes are never shown on a listing. */
    fun fcfa(centimes: Long): String {
        val f = centimes / 100
        val s = f.toString()
        val sb = StringBuilder()
        var count = 0
        for (i in s.length - 1 downTo 0) {
            sb.append(s[i]); count++
            if (count % 3 == 0 && i > 0 && s[i - 1] != '-') sb.append(' ')
        }
        return sb.reverse().toString() + " F"
    }

    /** "Publier 30 jours - 100 F", the line a seller reads before choosing. */
    fun offerLine(key: String): String {
        val p = PRICES[key] ?: return key
        return when (key) {
            "POST" -> "Publier cette annonce " + p.durationDays + " jours (jusqu'à " + p.extra("photos") + " photos) - " + fcfa(p.centimes)
            "BOOST" -> "Mettre en avant " + p.durationDays + " jours dans un quartier - " + fcfa(p.centimes)
            "PACKAGE5" -> p.extra("slots").toString() + " publications à utiliser sous " + p.durationDays + " jours - " + fcfa(p.centimes)
            "STOREFRONT" -> "Vitrine professionnelle " + p.durationDays + " jours, " + p.extra("slots") + " annonces et catalogue - " + fcfa(p.centimes)
            "VOUCHER" -> "Première annonce offerte (" + p.durationDays + " jours) - 0 F"
            else -> key
        }
    }

    /**
     * The sentence the seller reads on the payment screen. Without a number it is
     * WORD FOR WORD the server's `Market.pay_instruction`; with one it names it, because
     * the Brain tells the phone the treasury number only when payments are live.
     */
    fun payInstruction(amountCentimes: Long, reference: String, rail: String = "ANY", payTo: String = ""): String {
        val cfa = amountCentimes / 100
        val via = when (rail) { "MTN" -> " MTN MoMo"; "AIRTEL" -> " Airtel Money"; else -> "" }
        val number = if (payTo.isEmpty()) "au numéro Prok" + via else "au numéro " + payTo + via
        return "Payer " + cfa + " F : envoyez exactement " + cfa + " F " + number + " avec la référence " + reference + " dans le motif."
    }

    /** The in-person guide, shown on every listing and before the first message. */
    val SAFETY_GUIDE: List<String> = listOf(
        "Rencontrez-vous dans un lieu public et fréquenté, de jour.",
        "Vérifiez l'article avant de payer : allumez-le, testez-le, comparez avec les photos.",
        "Payez directement au vendeur, en main propre ou par Mobile Money, seulement une fois l'article en main.",
        "Prok ne garde jamais l'argent et ne garantit pas la vente : ne payez jamais « à Prok » ni d'avance.",
        "Ne communiquez ni pièce d'identité ni adresse exacte ; utilisez la messagerie Prok.",
        "Un doute ? Signalez l'annonce ou bloquez la personne depuis l'annonce.",
    )
    fun safetyGuideText(): String = SAFETY_GUIDE.joinToString("\n") { "• " + it }

    // ---- what the Brain answers ------------------------------------------------------------------

    class Listing(
        val id: String, val sellerId: String, val title: String, val description: String, val category: String,
        val priceCentimes: Long, val condition: String, val city: String, val neighbourhood: String,
        val pickupOptions: String, val photoUrls: List<String>, val state: String, val createdAt: Long,
        val publishedAt: Long, val expiresAt: Long, val boosted: Boolean, val boostZone: String,
        val distanceKm: String = "", val reviewNote: String = "", val paidBy: String = "",
        val sellerPublished: Int = 0, val sellerSince: Long = 0L, val sellerVerified: Boolean = false, val sellerStorefront: Boolean = false,
    ) {
        val stateText: String get() = listingText(state)
        val priceText: String get() = fcfa(priceCentimes)
        /** The first line of a result row; a boosted one says so, always. */
        val rowTitle: String get() = (if (boosted) SPONSORED_LABEL + " · " else "") + title
        val live: Boolean get() = state == "PUBLISHED"
        val canEdit: Boolean get() = state in setOf("DRAFT", "AWAITING_PAYMENT", "AWAITING_REVIEW", "PUBLISHED")
        val canWithdraw: Boolean get() = state in setOf("DRAFT", "AWAITING_PAYMENT", "AWAITING_REVIEW", "PUBLISHED", "HIDDEN")
        val canRenew: Boolean get() = state == "PUBLISHED" || state == "EXPIRED"
        val canBoost: Boolean get() = state == "PUBLISHED" && !boosted
        val needsPayment: Boolean get() = state == "AWAITING_PAYMENT"
        fun daysLeft(now: Long): Long = if (expiresAt <= 0) 0 else ((expiresAt - now) / 86_400_000L).coerceAtLeast(0)
    }

    class Invoice(
        val id: String, val reference: String, val service: String, val listingId: String, val amountCentimes: Long,
        val rail: String, val zone: String, val state: String, val createdAt: Long, val expiresAt: Long, val instruction: String,
    ) {
        val text: String get() = invoiceText(state)
        val open: Boolean get() = state == "OPEN"
        val paid: Boolean get() = state == "PAID"
        fun hoursLeft(now: Long): Long = ((expiresAt - now) / 3_600_000L).coerceAtLeast(0)
    }

    class Package(val id: String, val kind: String, val slotsTotal: Int, val slotsUsed: Int, val slotsFree: Int, val validUntil: Long, val catalog: Boolean) {
        val line: String get() = (if (kind == "STOREFRONT") "Vitrine" else "Forfait") + " : " + slotsFree + " publication" + (if (slotsFree == 1) "" else "s") + " restante" + (if (slotsFree == 1) "" else "s") + " sur " + slotsTotal
    }

    class SellerStatus(
        val registered: Boolean, val verified: Boolean, val trusted: Boolean, val adultAttested: Boolean, val hasPhone: Boolean,
        val voucherAvailable: Boolean, val packages: List<Package>, val slotsFree: Int, val storefront: Boolean, val publishedCount: Int,
    )

    class Message(val id: Long, val fromId: String, val toId: String, val body: String, val offerCentimes: Long, val at: Long, val read: Boolean) {
        fun line(me: String): String = (if (fromId == me) "Moi : " else "") + (if (offerCentimes > 0) "Offre " + fcfa(offerCentimes) + " - " else "") + body
    }

    class Thread(val listingId: String, val other: String, val blocked: Boolean, val messages: List<Message>)

    class InboxRow(val listingId: String, val title: String, val other: String, val lastAt: Long, val unread: Int, val lastBody: String)

    class SearchPage(val items: List<Listing>, val total: Int)

    // ---- parsing -----------------------------------------------------------------------------

    fun parseListing(text: String): Listing? {
        val id = str(text, "id")
        if (id.isEmpty() || !text.contains("\"title\"")) return null
        val seller = obj(text, "seller")
        return Listing(
            id = id, sellerId = str(text, "seller_id"), title = str(text, "title"), description = str(text, "description"),
            category = str(text, "category"), priceCentimes = num(text, "price_centimes"), condition = str(text, "condition"),
            city = str(text, "city"), neighbourhood = str(text, "neighbourhood"), pickupOptions = str(text, "pickup_options"),
            photoUrls = strings(text, "photo_urls"), state = str(text, "state"), createdAt = num(text, "created_at"),
            publishedAt = num(text, "published_at"), expiresAt = num(text, "expires_at"), boosted = bool(text, "boosted"),
            boostZone = str(text, "boost_zone"), distanceKm = rawNumber(text, "distance_km"),
            reviewNote = str(text, "review_note"), paidBy = str(text, "paid_by"),
            sellerPublished = if (seller != null) num(seller, "published_count").toInt() else 0,
            sellerSince = if (seller != null) num(seller, "member_since") else 0L,
            sellerVerified = seller != null && bool(seller, "verified"),
            sellerStorefront = seller != null && bool(seller, "storefront"),
        )
    }

    fun parseSearch(text: String): SearchPage? {
        if (!text.contains("\"items\"")) return null
        val items = objects(text, "items").mapNotNull { parseListing(it) }
        return SearchPage(items, num(text, "total").toInt())
    }

    fun parseListings(text: String): List<Listing> = objects(text, "listings").mapNotNull { parseListing(it) }

    fun parseInvoice(text: String): Invoice? {
        val id = str(text, "id")
        val ref = str(text, "reference")
        if (id.isEmpty() || ref.isEmpty()) return null
        return Invoice(
            id = id, reference = ref, service = str(text, "service"), listingId = str(text, "listing_id"),
            amountCentimes = num(text, "amount"), rail = str(text, "rail"), zone = str(text, "zone"), state = str(text, "state"),
            createdAt = num(text, "created_at"), expiresAt = num(text, "expires_at"), instruction = str(text, "instruction"),
        )
    }

    fun parseInvoices(text: String): List<Invoice> = objects(text, "invoices").mapNotNull { parseInvoice(it) }

    /** The invoice inside a submit / pay / renew / boost / package answer. */
    fun invoiceOf(answer: String): Invoice? = obj(answer, "invoice")?.let { parseInvoice(it) }
    fun listingOf(answer: String): Listing? = obj(answer, "listing")?.let { parseListing(it) }

    fun parseSellerStatus(text: String): SellerStatus? {
        if (!text.contains("\"registered\"")) return null
        val packages = objects(text, "packages").map { p ->
            Package(str(p, "id"), str(p, "kind"), num(p, "slots_total").toInt(), num(p, "slots_used").toInt(), num(p, "slots_free").toInt(),
                num(p, "valid_until"), bool(p, "catalog"))
        }
        return SellerStatus(
            registered = bool(text, "registered"), verified = bool(text, "verified"), trusted = bool(text, "trusted"),
            adultAttested = bool(text, "adult_attested"), hasPhone = bool(text, "has_phone"), voucherAvailable = bool(text, "voucher_available"),
            packages = packages, slotsFree = num(text, "slots_free").toInt(), storefront = bool(text, "storefront"),
            publishedCount = num(text, "published_count").toInt(),
        )
    }

    fun parseThread(text: String): Thread? {
        if (!text.contains("\"messages\"")) return null
        val msgs = objects(text, "messages").mapNotNull { m ->
            val id = num(m, "id")
            if (id <= 0) null else Message(id, str(m, "from_id"), str(m, "to_id"), str(m, "body"), num(m, "offer_centimes"), num(m, "at"), bool(m, "read"))
        }
        return Thread(str(text, "listing_id"), str(text, "other"), bool(text, "blocked"), msgs)
    }

    fun parseInbox(text: String): List<InboxRow> = objects(text, "threads").map { t ->
        InboxRow(str(t, "listing_id"), str(t, "title"), str(t, "other"), num(t, "last_at"), num(t, "unread").toInt(), str(t, "last_body"))
    }

    // ---- the small reader ----------------------------------------------------------------------

    /** A string field, escapes decoded (`\"`, `\\`, `\n`, `\uXXXX`). Empty when absent or not a string. */
    fun str(text: String, key: String): String {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(text) ?: return ""
        return unescape(m.groupValues[1])
    }

    fun num(text: String, key: String): Long = BrainPayload.field(text, key).toLongOrNull() ?: 0L

    fun bool(text: String, key: String): Boolean = BrainPayload.field(text, key) == "true"

    private fun rawNumber(text: String, key: String): String {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?)").find(text) ?: return ""
        return m.groupValues[1]
    }

    fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    'u' -> {
                        val hex = if (i + 6 <= s.length) s.substring(i + 2, i + 6) else ""
                        val code = hex.toIntOrNull(16)
                        if (code != null) { sb.append(code.toChar()); i += 6 } else { sb.append(n); i += 2 }
                    }
                    else -> { sb.append(n); i += 2 }
                }
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    /** The `{...}` under [key], however deep, or null when absent or `null`. */
    fun obj(text: String, key: String): String? {
        val at = keyAt(text, key) ?: return null
        var i = at
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != '{') return null
        val end = matching(text, i, '{', '}') ?: return null
        return text.substring(i, end + 1)
    }

    /** Every top-level `{...}` inside the array under [key] - nested arrays and objects are kept whole. */
    fun objects(text: String, key: String): List<String> {
        val at = keyAt(text, key) ?: return emptyList()
        var i = at
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != '[') return emptyList()
        val end = matching(text, i, '[', ']') ?: return emptyList()
        val out = ArrayList<String>()
        var j = i + 1
        while (j < end) {
            if (text[j] == '{') {
                val e = matching(text, j, '{', '}') ?: break
                out.add(text.substring(j, e + 1)); j = e + 1
            } else j++
        }
        return out
    }

    /** Every string in the flat array under [key]. */
    fun strings(text: String, key: String): List<String> {
        val at = keyAt(text, key) ?: return emptyList()
        var i = at
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != '[') return emptyList()
        val end = matching(text, i, '[', ']') ?: return emptyList()
        return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(text.substring(i + 1, end)).map { unescape(it.groupValues[1]) }.toList()
    }

    /** Index just after the colon of the first top-level-ish occurrence of `"key":`. */
    private fun keyAt(text: String, key: String): Int? {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:").find(text) ?: return null
        return m.range.last + 1
    }

    /** Index of the bracket closing the one at [open], skipping strings. */
    private fun matching(text: String, open: Int, o: Char, c: Char): Int? {
        var depth = 0
        var i = open
        var inString = false
        while (i < text.length) {
            val ch = text[i]
            if (inString) {
                if (ch == '\\') i++ else if (ch == '"') inString = false
            } else when (ch) {
                '"' -> inString = true
                o -> depth++
                c -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return null
    }

    /** What the seller sees while an invoice waits, and after: one line, never a promise. */
    fun invoiceLine(inv: Invoice, now: Long): String = when (inv.state) {
        "OPEN" -> inv.text + " · " + fcfa(inv.amountCentimes) + " · réf. " + inv.reference + " · expire dans " + inv.hoursLeft(now) + " h"
        "PAID" -> inv.text + " · " + fcfa(inv.amountCentimes) + " · réf. " + inv.reference
        else -> inv.text + " · réf. " + inv.reference
    }
}
