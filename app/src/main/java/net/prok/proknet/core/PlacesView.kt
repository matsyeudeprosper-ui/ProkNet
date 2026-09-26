package net.prok.proknet.core

import java.util.Locale

/**
 * v0.19.0: the free Internet finder, as the phone reads and shows it.
 *
 * The product rules this file holds, so a test can hold them too:
 *  - the status words are ONE table shared with the server
 *    (server/tests/fixtures/place_status.txt); a state this build does not know is shown
 *    as "Signalé, à vérifier", never as anything greener;
 *  - a venue nobody has checked for 7 days (UNVERIFIED_STALE) leaves the default
 *    recommended list; it is still there when the person searches or filters;
 *  - a distance is a WALKING distance only when a [WalkRouter] returned a route; with no
 *    route the card says "≈ 1,2 km à vol d'oiseau" and never an ETA; with no position it
 *    says nothing about distance and never a direction;
 *  - the cached index is honest about its age: "mis à jour il y a …".
 *
 * Pure: no android.* imports, no org.json (the android.jar stubs return null in JVM tests).
 * The index JSON is read with a small depth-aware scanner because a card is a flat object
 * inside an array and the server escapes non-ASCII as \uXXXX.
 */
object PlacesView {

    /** State -> what the card says. Must equal places.STATUS_TEXT on the server. */
    val STATUS_TEXT: Map<String, String> = linkedMapOf(
        "WORKING_NOW" to "Fonctionne maintenant",
        "RECENTLY_VERIFIED" to "Vérifié récemment",
        "OLDER_CHECK" to "Ancien contrôle — confirmez avant de vous déplacer",
        "UNVERIFIED_STALE" to "Non vérifié depuis plus de 7 jours",
        "REPORTED" to "Signalé, à vérifier",
        "UNAVAILABLE" to "Indisponible",
    )
    const val UNKNOWN_STATUS_TEXT = "Signalé, à vérifier"
    val STATUS_RANK: Map<String, Int> = mapOf("WORKING_NOW" to 0, "RECENTLY_VERIFIED" to 1, "OLDER_CHECK" to 2, "REPORTED" to 3, "UNAVAILABLE" to 4, "UNVERIFIED_STALE" to 5)
    const val STALE = "UNVERIFIED_STALE"

    /** Access rule -> the card's words. Same as places.ACCESS_TEXT. */
    val ACCESS_TEXT: Map<String, String> = linkedMapOf(
        "FREE_OPEN" to "Gratuit ici",
        "FREE_AFTER_SIGNIN" to "Gratuit après connexion",
        "FREE_LIMITED" to "Gratuit avec limite",
        "CUSTOMERS_ONLY" to "Clients uniquement",
    )
    val FREE_RULES = setOf("FREE_OPEN", "FREE_AFTER_SIGNIN", "FREE_LIMITED")
    val NO_SIGNIN_RULES = setOf("FREE_OPEN", "FREE_LIMITED")

    /** "Walking distance": at most this far in a straight line. */
    const val WALKING_M = 1500.0
    /** Arrival, for the guidance screen's re-check. */
    const val ARRIVED_M = 30.0
    /** Brazzaville is UTC+1 all year; hours are local, the clock is UTC ms. Same as the server. */
    const val LOCAL_UTC_OFFSET_MS = 3_600_000L
    val DAYS = listOf("mon", "tue", "wed", "thu", "fri", "sat", "sun")
    const val DAY_FR = "lun,mar,mer,jeu,ven,sam,dim"

    const val NO_OFFER_TEXT = "pas d'offre scout payée"
    const val NO_LOCATION_NOTE = "Sans position : recherche par quartier, sans distance ni direction."

    fun statusText(state: String): String = STATUS_TEXT[state] ?: UNKNOWN_STATUS_TEXT
    fun accessText(rule: String): String = ACCESS_TEXT[rule] ?: "Conditions inconnues"

    class Pos(val lat: Double, val lon: Double)

    class Card(
        val id: String, val name: String, val neighbourhood: String, val kind: String,
        val lat: Double, val lon: Double, val accessRule: String, val signinPath: String,
        /** day -> [openMinute, closeMinute], absent = closed; empty map = hours unknown */
        val hours: Map<String, IntArray>, val status: String, val statusAgeMs: Long, val lastOkAt: Long,
        val confidence: Int, val prokDeliverable: Boolean, val directOrRelay: String,
    ) {
        val statusText: String get() = statusText(status)
        val accessText: String get() = accessText(accessRule)
        val free: Boolean get() = accessRule in FREE_RULES
        val noSignin: Boolean get() = accessRule in NO_SIGNIN_RULES
        val hasEntrance: Boolean get() = lat != 0.0 || lon != 0.0
        val stale: Boolean get() = status == STALE
        fun openNow(now: Long): Boolean? = openAt(hours, now)

        /** The status line with its age: "Vérifié récemment · il y a 3 h". */
        fun freshnessLine(): String = when (status) {
            "WORKING_NOW", "RECENTLY_VERIFIED", "OLDER_CHECK" -> statusText + (if (statusAgeMs >= 0) " · " + ageText(statusAgeMs) else "")
            else -> statusText
        }

        /** "Ouvert maintenant · lun-ven 08:00-20:00" or "Fermé maintenant" or "Horaires à confirmer". */
        fun hoursLine(now: Long): String {
            val open = openNow(now) ?: return "Horaires à confirmer"
            return (if (open) "Ouvert maintenant" else "Fermé maintenant") + " · " + hoursSummary(hours)
        }
    }

    class Index(
        val city: String, val generatedAt: Long, val count: Int, val lastUpdate: Long,
        val neighbourhoods: List<String>, val venues: List<Card>, val exhaustive: Boolean,
    )

    class Offer(val available: Boolean, val reason: String, val text: String) {
        /** What the contribution screen says before the person sends anything. */
        val line: String get() = if (available) text else NO_OFFER_TEXT
    }

    // ---- hours -----------------------------------------------------------------------------

    /** "mon=08:00-20:00,tue=,..." -> the map. Anything unreadable is "unknown". */
    fun parseHours(line: String): Map<String, IntArray> {
        if (line.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, IntArray>()
        for (part in line.split(',')) {
            val eq = part.indexOf('='); if (eq < 0) continue
            val day = part.substring(0, eq).trim(); if (day !in DAYS) continue
            val rng = part.substring(eq + 1).trim(); if (rng.isEmpty()) continue
            val m = Regex("([0-2][0-9]):([0-5][0-9])-([0-2][0-9]):([0-5][0-9])").matchEntire(rng) ?: continue
            val g = m.groupValues
            out[day] = intArrayOf(g[1].toInt() * 60 + g[2].toInt(), g[3].toInt() * 60 + g[4].toInt())
        }
        return out
    }

    /** Open at [now] in local time; null when hours are unknown. Mirrors places.open_at. */
    fun openAt(hours: Map<String, IntArray>, now: Long): Boolean? {
        if (hours.isEmpty()) return null
        val local = (now + LOCAL_UTC_OFFSET_MS) / 60_000L
        val dayIdx = (((local / 1440L) + 3L) % 7L).toInt()      // 1970-01-01 was a Thursday
        val minute = (local % 1440L).toInt()
        hours[DAYS[dayIdx]]?.let { r ->
            val (o, c) = r[0] to r[1]
            if (if (o < c) minute in o until c else (minute >= o || minute < c)) return true
        }
        hours[DAYS[(dayIdx + 6) % 7]]?.let { r -> if (r[1] < r[0] && minute < r[1]) return true }
        return false
    }

    fun hoursSummary(hours: Map<String, IntArray>): String {
        if (hours.isEmpty()) return "horaires à confirmer"
        val fr = DAY_FR.split(',')
        val parts = ArrayList<String>()
        var i = 0
        while (i < 7) {
            val r = hours[DAYS[i]]
            if (r == null) { i++; continue }
            var j = i
            while (j + 1 < 7 && hours[DAYS[j + 1]]?.contentEquals(r) == true) j++
            parts.add((if (j > i) fr[i] + "-" + fr[j] else fr[i]) + " " + hm(r[0]) + "-" + hm(r[1]))
            i = j + 1
        }
        return parts.joinToString(", ")
    }

    private fun hm(m: Int): String = String.format(Locale.ROOT, "%02d:%02d", m / 60, m % 60)

    // ---- parsing ----------------------------------------------------------------------------

    fun parseIndex(text: String): Index? {
        if (text.isBlank() || !text.contains("\"venues\"")) return null
        return Index(
            city = str(text, "city"), generatedAt = num(text, "generated_at"), count = num(text, "count").toInt(),
            lastUpdate = num(text, "last_update"), neighbourhoods = strings(text, "neighbourhoods"),
            venues = objects(text, "venues").mapNotNull { parseCard(it) }, exhaustive = bool(text, "exhaustive"),
        )
    }

    fun parseCard(o: String): Card? {
        val id = str(o, "id")
        if (id.isEmpty()) return null
        return Card(
            id = id, name = str(o, "name"), neighbourhood = str(o, "neighbourhood"), kind = str(o, "kind"),
            lat = dbl(o, "lat"), lon = dbl(o, "lon"), accessRule = str(o, "access_rule"), signinPath = str(o, "signin_path"),
            hours = parseHours(str(o, "hours")), status = str(o, "status"), statusAgeMs = num(o, "status_age_ms", -1L),
            lastOkAt = num(o, "last_ok_at"), confidence = num(o, "confidence").toInt(), prokDeliverable = bool(o, "prok_deliverable"),
            directOrRelay = str(o, "direct_or_relay").ifEmpty { "direct" },
        )
    }

    fun parseOffer(text: String): Offer? {
        if (text.isBlank() || !text.contains("\"available\"")) return null
        return Offer(bool(text, "available"), str(text, "reason"), str(text, "text"))
    }

    /** The string under [key], JSON escapes (\", \\, \n, \uXXXX) undone; "" when absent or not a string. */
    fun str(text: String, key: String): String {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(text) ?: return ""
        return unescape(m.groupValues[1])
    }

    fun num(text: String, key: String, default: Long = 0L): Long {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(-?[0-9]+)(?![0-9.eE])").find(text) ?: return default
        return m.groupValues[1].toLongOrNull() ?: default
    }

    fun dbl(text: String, key: String): Double {
        val m = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?(?:[eE][-+]?[0-9]+)?)").find(text) ?: return 0.0
        return m.groupValues[1].toDoubleOrNull() ?: 0.0
    }

    fun bool(text: String, key: String): Boolean =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*true").containsMatchIn(text)

    /** Every top-level `{...}` inside the array under [key]; braces inside strings are skipped. */
    fun objects(text: String, key: String): List<String> {
        val at = text.indexOf("\"" + key + "\"")
        if (at < 0) return emptyList()
        val open = text.indexOf('[', at)
        if (open < 0) return emptyList()
        val out = ArrayList<String>()
        var depth = 0; var start = -1; var inStr = false; var esc = false
        var i = open + 1
        while (i < text.length) {
            val ch = text[i]
            if (inStr) {
                if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false
            } else when (ch) {
                '"' -> inStr = true
                '{' -> { if (depth == 0) start = i; depth++ }
                '}' -> { depth--; if (depth == 0 && start >= 0) { out.add(text.substring(start, i + 1)); start = -1 } }
                ']' -> if (depth == 0) return out
            }
            i++
        }
        return out
    }

    /** The strings inside the array under [key]. */
    fun strings(text: String, key: String): List<String> {
        val at = text.indexOf("\"" + key + "\"")
        if (at < 0) return emptyList()
        val open = text.indexOf('[', at); if (open < 0) return emptyList()
        val close = text.indexOf(']', open); if (close < 0) return emptyList()
        return Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(text.substring(open + 1, close)).map { unescape(it.groupValues[1]) }.toList()
    }

    fun unescape(s: String): String {
        if (!s.contains('\\')) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '\\' || i + 1 >= s.length) { sb.append(c); i++; continue }
            when (val n = s[i + 1]) {
                'u' -> if (i + 5 < s.length) { sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 6 } else { sb.append(n); i += 2 }
                'n' -> { sb.append('\n'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                else -> { sb.append(n); i += 2 }
            }
        }
        return sb.toString()
    }

    // ---- filters and order -------------------------------------------------------------------

    enum class Filter { FREE, WORKING_NOW, OPEN_NOW, WALKING, NO_SIGNIN, PROK_DELIVERABLE }

    fun filterLabel(f: Filter): String = when (f) {
        Filter.FREE -> "Gratuit"
        Filter.WORKING_NOW -> "Fonctionne maintenant"
        Filter.OPEN_NOW -> "Ouvert maintenant"
        Filter.WALKING -> "À pied (≤ 1,5 km)"
        Filter.NO_SIGNIN -> "Sans inscription"
        Filter.PROK_DELIVERABLE -> "Livrable par Prok"
    }

    /** A filter that needs a position is not offered without one. */
    fun filterAvailable(f: Filter, hasLocation: Boolean): Boolean = f != Filter.WALKING || hasLocation

    /**
     * The default recommended list: filtered, then stale venues dropped, then by status
     * rank, then by distance when there is one, then by name. With `searching` or an
     * explicit filter set, nothing is dropped - the person asked.
     */
    fun list(cards: List<Card>, filters: Set<Filter>, neighbourhood: String, query: String, here: Pos?, now: Long,
             includeStale: Boolean = false): List<Card> {
        val q = normalise(query)
        var out = cards.asSequence()
        if (neighbourhood.isNotEmpty()) out = out.filter { it.neighbourhood == neighbourhood }
        if (q.isNotEmpty()) out = out.filter { normalise(it.name + " " + it.neighbourhood + " " + it.kind).contains(q) }
        for (f in filters) out = when (f) {
            Filter.FREE -> out.filter { it.free }
            Filter.WORKING_NOW -> out.filter { it.status == "WORKING_NOW" }
            Filter.OPEN_NOW -> out.filter { it.openNow(now) == true }
            Filter.WALKING -> if (here == null) out else out.filter { it.hasEntrance && distanceM(here.lat, here.lon, it.lat, it.lon) <= WALKING_M }
            Filter.NO_SIGNIN -> out.filter { it.noSignin }
            Filter.PROK_DELIVERABLE -> out.filter { it.prokDeliverable }
        }
        val keepStale = includeStale || q.isNotEmpty() || filters.isNotEmpty()
        if (!keepStale) out = out.filter { !it.stale }
        return out.sortedWith(compareBy<Card> { STATUS_RANK[it.status] ?: 3 }
            .thenBy { if (here != null && it.hasEntrance) distanceM(here.lat, here.lon, it.lat, it.lon) else Double.MAX_VALUE }
            .thenBy { it.name }).toList()
    }

    fun normalise(s: String): String {
        val n = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return n.lowercase(Locale.ROOT).trim().replace(Regex("\\s+"), " ")
    }

    // ---- distance, bearing, words ----------------------------------------------------------

    /** Haversine, metres: a straight line, never called a walking distance. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1); val dl = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
        return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(a)))
    }

    /** Initial bearing 0..360, clockwise from north. */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2); val dl = Math.toRadians(lon2 - lon1)
        val y = Math.sin(dl) * Math.cos(p2)
        val x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl)
        return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
    }

    fun compass(bearing: Double): String {
        val names = listOf("N", "NE", "E", "SE", "S", "SO", "O", "NO")
        return names[((bearing + 22.5) / 45.0).toInt() % 8]
    }

    fun formatDistance(m: Double): String =
        if (m < 950) Math.round(m / 10.0).times(10).toString() + " m" else String.format(Locale.FRENCH, "%.1f km", m / 1000.0)

    fun formatEta(seconds: Long): String {
        val min = Math.max(1L, (seconds + 30) / 60)
        return if (min < 60) "$min min" else (min / 60).toString() + " h " + String.format(Locale.ROOT, "%02d", min % 60)
    }

    /**
     * The distance line of a card. Real route: "1,2 km à pied · 15 min". No route:
     * "≈ 1,2 km à vol d'oiseau", and NO ETA, because a straight line is not a route. No
     * position: "" - nothing is said, and nothing about direction either.
     */
    fun distanceLine(card: Card, here: Pos?, route: WalkRoute?): String {
        if (here == null || !card.hasEntrance) return ""
        if (route != null) return formatDistance(route.distanceMeters) + " à pied · " + formatEta(route.etaSeconds.toLong())
        return "≈ " + formatDistance(distanceM(here.lat, here.lon, card.lat, card.lon)) + " à vol d'oiseau"
    }

    /** Direction is offered only with a position; a straight-line bearing is labelled so. */
    fun directionLine(card: Card, here: Pos?): String {
        if (here == null || !card.hasEntrance) return ""
        return "direction " + compass(bearingDeg(here.lat, here.lon, card.lat, card.lon)) + " (à vol d'oiseau)"
    }

    /** The guidance screen's line while walking: remaining distance, ETA only with a route. */
    fun guidanceLine(card: Card, here: Pos?, route: WalkRoute?): String {
        if (here == null) return "Position inconnue : impossible de guider."
        if (!card.hasEntrance) return "Entrée non publiée : pas de guidage."
        val straight = distanceM(here.lat, here.lon, card.lat, card.lon)
        if (straight <= ARRIVED_M) return "Vous êtes arrivé. Vérification de la connexion…"
        return if (route != null) "Reste " + formatDistance(route.distanceMeters) + " à pied · " + formatEta(route.etaSeconds.toLong())
        else "Reste ≈ " + formatDistance(straight) + " à vol d'oiseau · itinéraire indisponible · " + directionLine(card, here)
    }

    fun ageText(ageMs: Long): String {
        val m = Math.max(0L, ageMs) / 60_000L
        return when {
            m < 1 -> "à l'instant"
            m < 60 -> "il y a $m min"
            m < 48 * 60 -> "il y a " + (m / 60) + " h"
            else -> "il y a " + (m / 1440) + " j"
        }
    }

    /** "mis à jour il y a 3 h", or the honest line when the cache has never been filled. */
    fun refreshedText(refreshedAt: Long, now: Long): String =
        if (refreshedAt <= 0) "jamais mis à jour · connectez-vous une fois pour charger la liste" else "mis à jour " + ageText(now - refreshedAt)

    fun headerLine(city: String, count: Int): String =
        "Internet gratuit à " + city + " · " + count + " lieu" + (if (count == 1) "" else "x") + " connu" + (if (count == 1) "" else "s")

    const val NOT_EXHAUSTIVE = "Liste non exhaustive : seuls les lieux vérifiés et consentants figurent ici."

    /** "Signaler": the three answers and what each sends. */
    enum class Report { WORKED, FAILED, CLOSED }
    fun reportLabel(r: Report): String = when (r) { Report.WORKED -> "Ça a marché"; Report.FAILED -> "Ça n'a pas marché"; Report.CLOSED -> "Fermé" }
}
