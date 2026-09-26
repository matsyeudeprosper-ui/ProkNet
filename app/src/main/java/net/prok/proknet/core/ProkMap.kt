package net.prok.proknet.core

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.Normalizer
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * v0.19: the offline map pack, read on the phone.
 *
 * The bytes are the `.prokmap` format written by `server/tools/prokmap.py` from
 * OpenStreetMap DATA (© OpenStreetMap contributors, ODbL 1.0) - a pedestrian graph and a
 * place index for one city, a few megabytes, no tiles. This class is the reader, the
 * nearest-node index, the A* walking router, the place search and "which neighbourhood
 * am I in". Pure Kotlin: nothing here needs Android, so the whole thing runs in the unit
 * tests against `app/src/test/resources/synthetic.prokmap`, which the Python test writes.
 *
 * Memory: everything is a primitive array. A city of 200 k nodes and 220 k edges is about
 * 5 MB on the heap plus the adjacency, and the router's working arrays are allocated once.
 *
 * Routing honesty (launch contract 10.2): an endpoint more than [SNAP_MAX_M] from any node
 * of the graph gives NO route, so the caller shows "distance approximative" and never a time.
 */
class ProkMap private constructor(
    val city: String,
    val formatVersion: Int,
    val generatedAt: Long,
    val minLatMicro: Int, val minLonMicro: Int, val maxLatMicro: Int, val maxLonMicro: Int,
    private val lat: IntArray,
    private val lon: IntArray,
    private val edgeA: IntArray,
    private val edgeB: IntArray,
    private val edgeLen: ShortArray,
    private val edgeCls: ByteArray,
    private val edgeName: ShortArray,
    private val names: Array<String>,
    val places: List<Place>,
) : WalkRouter {

    class Place(val name: String, val kind: Int, val lat: Double, val lon: Double) {
        val kindName: String get() = KIND_NAMES[kind] ?: "?"
        val isNeighbourhood: Boolean get() = kind == KIND_SUBURB || kind == KIND_NEIGHBOURHOOD || kind == KIND_QUARTER
        internal val key: String = fold(name)
    }

    companion object {
        const val FORMAT_VERSION = 1
        const val MICRO = 1_000_000.0
        const val HEADER_BYTES = 56
        const val NODE_BYTES = 8
        const val EDGE_BYTES = 13

        /** Farther than this from any node: no route, no ETA. */
        const val SNAP_MAX_M = 300.0
        /** 4.5 km/h. */
        const val WALK_M_PER_S = 1.25
        /** Steps and unpaved track take a tenth longer. */
        const val SLOW_FACTOR = 1.1
        /** How far a "which neighbourhood" answer may reach before it is no answer. */
        const val NEIGHBOURHOOD_MAX_M = 3000.0

        // highway classes, as the builder numbers them
        const val CLASS_PRIMARY = 1
        const val CLASS_SECONDARY = 2
        const val CLASS_TERTIARY = 3
        const val CLASS_RESIDENTIAL = 4
        const val CLASS_UNCLASSIFIED = 5
        const val CLASS_LIVING_STREET = 6
        const val CLASS_PEDESTRIAN = 7
        const val CLASS_SERVICE = 8
        const val CLASS_FOOTWAY = 9
        const val CLASS_CYCLEWAY = 10
        const val CLASS_PATH = 11
        const val CLASS_TRACK = 12
        const val CLASS_STEPS = 13

        const val KIND_CITY = 1
        const val KIND_TOWN = 2
        const val KIND_VILLAGE = 3
        const val KIND_SUBURB = 4
        const val KIND_NEIGHBOURHOOD = 5
        const val KIND_QUARTER = 6
        const val KIND_SCHOOL = 16
        const val KIND_HOSPITAL = 17
        const val KIND_MARKET = 18
        const val KIND_PLACE_OF_WORSHIP = 19

        val KIND_NAMES: Map<Int, String> = mapOf(
            KIND_CITY to "ville", KIND_TOWN to "ville", KIND_VILLAGE to "village",
            KIND_SUBURB to "arrondissement", KIND_NEIGHBOURHOOD to "quartier", KIND_QUARTER to "quartier",
            KIND_SCHOOL to "école", KIND_HOSPITAL to "hôpital", KIND_MARKET to "marché",
            KIND_PLACE_OF_WORSHIP to "lieu de culte")

        fun isSlow(cls: Int): Boolean = cls == CLASS_STEPS || cls == CLASS_TRACK

        /** Cell edge of the nearest-node grid, in microdegrees (~550 m). */
        private const val CELL_MICRO = 5_000

        fun load(file: File): ProkMap = parse(file.readBytes())

        /** Parse, checking every count and every reference; throws [IllegalArgumentException] on a bad file. */
        fun parse(data: ByteArray): ProkMap {
            require(data.size >= HEADER_BYTES + 1) { "file too short" }
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            require(b.get() == 'P'.code.toByte() && b.get() == 'K'.code.toByte() &&
                b.get() == 'M'.code.toByte() && b.get() == 'P'.code.toByte()) { "not a prokmap file" }
            val version = b.short.toInt() and 0xFFFF
            require(version == FORMAT_VERSION) { "unsupported format version $version" }
            b.short   // flags
            val generatedAt = b.long
            val minLat = b.int; val minLon = b.int; val maxLat = b.int; val maxLon = b.int
            val nodeCount = b.int; val edgeCount = b.int; val nameCount = b.int; val placeCount = b.int
            val nameBytes = b.int; val placeBytes = b.int
            require(nodeCount >= 0 && edgeCount >= 0 && nameCount >= 1 && placeCount >= 0 && nameBytes >= 0 && placeBytes >= 0) { "bad counts" }
            val cityLen = b.get().toInt() and 0xFF
            require(data.size >= HEADER_BYTES + 1 + cityLen) { "file too short" }
            val city = String(data, HEADER_BYTES + 1, cityLen, Charsets.UTF_8)
            b.position(HEADER_BYTES + 1 + cityLen)
            val expected = (HEADER_BYTES + 1 + cityLen).toLong() + nodeCount.toLong() * NODE_BYTES +
                edgeCount.toLong() * EDGE_BYTES + nameBytes + placeBytes
            require(expected == data.size.toLong()) { "size mismatch: header implies $expected bytes, file has ${data.size}" }

            val lat = IntArray(nodeCount); val lon = IntArray(nodeCount)
            for (i in 0 until nodeCount) { lat[i] = b.int; lon[i] = b.int }
            val ea = IntArray(edgeCount); val eb = IntArray(edgeCount)
            val el = ShortArray(edgeCount); val ec = ByteArray(edgeCount); val en = ShortArray(edgeCount)
            for (i in 0 until edgeCount) {
                val a = b.int; val c = b.int
                require(a in 0 until nodeCount && c in 0 until nodeCount) { "edge $i references a missing node" }
                ea[i] = a; eb[i] = c
                el[i] = b.short; ec[i] = b.get()
                val n = b.short
                require((n.toInt() and 0xFFFF) < nameCount) { "edge $i references a missing name" }
                en[i] = n
            }
            val nameEnd = b.position() + nameBytes
            val names = Array(nameCount) { "" }
            for (i in 0 until nameCount) {
                require(b.position() + 2 <= nameEnd) { "name table truncated" }
                val ln = b.short.toInt() and 0xFFFF
                require(b.position() + ln <= nameEnd) { "name table truncated" }
                names[i] = String(data, b.position(), ln, Charsets.UTF_8)
                b.position(b.position() + ln)
            }
            require(b.position() == nameEnd) { "name table has trailing bytes" }
            require(names[0].isEmpty()) { "name 0 must be empty" }
            val placeEnd = b.position() + placeBytes
            val places = ArrayList<Place>(placeCount)
            for (i in 0 until placeCount) {
                require(b.position() + 11 <= placeEnd) { "place table truncated" }
                val kind = b.get().toInt() and 0xFF
                val pla = b.int; val plo = b.int
                val ln = b.short.toInt() and 0xFFFF
                require(b.position() + ln <= placeEnd) { "place table truncated" }
                val name = String(data, b.position(), ln, Charsets.UTF_8)
                b.position(b.position() + ln)
                places.add(Place(name, kind, pla / MICRO, plo / MICRO))
            }
            require(b.position() == placeEnd) { "place table has trailing bytes" }
            return ProkMap(city, version, generatedAt, minLat, minLon, maxLat, maxLon,
                lat, lon, ea, eb, el, ec, en, names, places)
        }

        fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6_371_000.0
            val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
            val dp = p2 - p1
            val dl = Math.toRadians(lon2 - lon1)
            val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * r * asin(min(1.0, sqrt(a)))
        }

        /** Lower-case, accents stripped, so "ecole" finds "École". */
        fun fold(s: String): String {
            val n = Normalizer.normalize(s, Normalizer.Form.NFD)
            val sb = StringBuilder(n.length)
            for (ch in n) {
                val t = Character.getType(ch)
                if (t == Character.NON_SPACING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt()) continue
                sb.append(if (ch == '-' || ch == '\'' || ch == '’') ' ' else Character.toLowerCase(ch))
            }
            return sb.toString().trim().replace(Regex("\\s+"), " ")
        }

        /** Walking time for a leg of [meters] on a way of class [cls]. */
        fun legSeconds(meters: Double, cls: Int): Double =
            meters / WALK_M_PER_S * (if (isSlow(cls)) SLOW_FACTOR else 1.0)
    }

    // ---- what the renderer reads ----------------------------------------------------------

    val nodeCount: Int get() = lat.size
    val edgeCount: Int get() = edgeA.size
    fun nodeLatMicro(i: Int): Int = lat[i]
    fun nodeLonMicro(i: Int): Int = lon[i]
    fun nodeLat(i: Int): Double = lat[i] / MICRO
    fun nodeLon(i: Int): Double = lon[i] / MICRO
    fun edgeFrom(i: Int): Int = edgeA[i]
    fun edgeTo(i: Int): Int = edgeB[i]
    fun edgeClass(i: Int): Int = edgeCls[i].toInt() and 0xFF
    fun edgeLengthM(i: Int): Int = edgeLen[i].toInt() and 0xFFFF
    fun edgeName(i: Int): String = names[edgeName[i].toInt() and 0xFFFF]
    val nameCount: Int get() = names.size

    // ---- adjacency (CSR), built once ----------------------------------------------------

    private val adjStart = IntArray(lat.size + 1)
    private val adjNode = IntArray(edgeA.size * 2)
    private val adjEdge = IntArray(edgeA.size * 2)

    // ---- nearest-node grid, built once --------------------------------------------------------

    private val cols = ((maxLonMicro - minLonMicro) / CELL_MICRO + 1).coerceAtLeast(1)
    private val rows = ((maxLatMicro - minLatMicro) / CELL_MICRO + 1).coerceAtLeast(1)
    private val cellStart = IntArray(cols * rows + 1)
    private val cellNode = IntArray(lat.size)

    init {
        for (i in edgeA.indices) { adjStart[edgeA[i] + 1]++; adjStart[edgeB[i] + 1]++ }
        for (i in 1..lat.size) adjStart[i] += adjStart[i - 1]
        val fill = adjStart.copyOf()
        for (i in edgeA.indices) {
            val a = edgeA[i]; val b = edgeB[i]
            adjNode[fill[a]] = b; adjEdge[fill[a]] = i; fill[a]++
            adjNode[fill[b]] = a; adjEdge[fill[b]] = i; fill[b]++
        }
        for (i in lat.indices) cellStart[cellOf(lat[i], lon[i]) + 1]++
        for (c in 1..cols * rows) cellStart[c] += cellStart[c - 1]
        val cfill = cellStart.copyOf()
        for (i in lat.indices) { val c = cellOf(lat[i], lon[i]); cellNode[cfill[c]] = i; cfill[c]++ }
    }

    private fun cellRow(latMicro: Int): Int = ((latMicro - minLatMicro) / CELL_MICRO).coerceIn(0, rows - 1)
    private fun cellCol(lonMicro: Int): Int = ((lonMicro - minLonMicro) / CELL_MICRO).coerceIn(0, cols - 1)
    private fun cellOf(latMicro: Int, lonMicro: Int): Int = cellRow(latMicro) * cols + cellCol(lonMicro)

    /** Index of the closest node within [maxMeters], or -1. */
    fun nearestNode(latD: Double, lonD: Double, maxMeters: Double = SNAP_MAX_M): Int {
        if (lat.isEmpty()) return -1
        val la = (latD * MICRO).roundToInt(); val lo = (lonD * MICRO).roundToInt()
        // outside the box by more than the reach: nothing can be close enough
        val reachMicro = (maxMeters / 0.111 * 1.05).toInt() + 1
        if (la < minLatMicro - reachMicro || la > maxLatMicro + reachMicro ||
            lo < minLonMicro - reachMicro || lo > maxLonMicro + reachMicro) return -1
        val r0 = cellRow(la); val c0 = cellCol(lo)
        val rings = ceil(reachMicro.toDouble() / CELL_MICRO).toInt() + 1
        var best = -1; var bestD = maxMeters
        for (ring in 0..rings) {
            for (r in r0 - ring..r0 + ring) {
                if (r < 0 || r >= rows) continue
                for (c in c0 - ring..c0 + ring) {
                    if (c < 0 || c >= cols) continue
                    if (ring > 0 && abs(r - r0) != ring && abs(c - c0) != ring) continue   // interior, done already
                    val cell = r * cols + c
                    for (k in cellStart[cell] until cellStart[cell + 1]) {
                        val i = cellNode[k]
                        val d = haversineM(latD, lonD, lat[i] / MICRO, lon[i] / MICRO)
                        if (d <= bestD) { bestD = d; best = i }
                    }
                }
            }
            // every node in a farther ring is at least (ring * cell - a cell) away
            if (best >= 0 && (ring * CELL_MICRO - CELL_MICRO) * 0.111 > bestD) break
        }
        return best
    }

    fun distanceToNode(i: Int, latD: Double, lonD: Double): Double = haversineM(latD, lonD, lat[i] / MICRO, lon[i] / MICRO)

    // ---- A* ---------------------------------------------------------------------------------------

    // working arrays, allocated on the first route and reused; `stamp` says which query
    // wrote a slot, so nothing is cleared between queries
    private var stamp = 0
    private var seen: IntArray? = null
    private var gScore: IntArray? = null
    private var prevNode: IntArray? = null
    private var prevEdge: IntArray? = null
    private var closed: IntArray? = null

    private class Open(val f: Double, val node: Int)

    override fun route(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): WalkRoute? {
        val s = nearestNode(fromLat, fromLon)
        val t = nearestNode(toLat, toLon)
        if (s < 0 || t < 0) return null
        val snapIn = distanceToNode(s, fromLat, fromLon)
        val snapOut = distanceToNode(t, toLat, toLon)
        val path = synchronized(this) { astar(s, t) } ?: return null
        var meters = 0.0
        var seconds = (snapIn + snapOut) / WALK_M_PER_S
        for (e in path.second) {
            val l = edgeLengthM(e).toDouble()
            meters += l
            seconds += legSeconds(l, edgeClass(e))
        }
        val points = ArrayList<DoubleArray>(path.first.size + 2)
        points.add(doubleArrayOf(fromLat, fromLon))
        for (n in path.first) points.add(doubleArrayOf(lat[n] / MICRO, lon[n] / MICRO))
        points.add(doubleArrayOf(toLat, toLon))
        return WalkRoute(meters + snapIn + snapOut, seconds.roundToInt(), points)
    }

    /** (node path, edge path) from s to t, or null when they are not connected. */
    private fun astar(s: Int, t: Int): Pair<IntArray, IntArray>? {
        val n = lat.size
        val seen = this.seen ?: IntArray(n).also { this.seen = it }
        val g = this.gScore ?: IntArray(n).also { this.gScore = it }
        val prev = this.prevNode ?: IntArray(n).also { this.prevNode = it }
        val prevE = this.prevEdge ?: IntArray(n).also { this.prevEdge = it }
        val closed = this.closed ?: IntArray(n).also { this.closed = it }
        stamp++
        if (stamp == Int.MAX_VALUE) { seen.fill(0); closed.fill(0); stamp = 1 }
        val tLat = lat[t] / MICRO; val tLon = lon[t] / MICRO
        fun h(i: Int) = haversineM(lat[i] / MICRO, lon[i] / MICRO, tLat, tLon)
        val open = PriorityQueue<Open>(64) { a, b -> a.f.compareTo(b.f) }
        seen[s] = stamp; g[s] = 0; prev[s] = -1; prevE[s] = -1
        open.add(Open(h(s), s))
        var found = false
        while (open.isNotEmpty()) {
            val u = open.poll().node
            if (closed[u] == stamp) continue
            if (u == t) { found = true; break }
            closed[u] = stamp
            for (k in adjStart[u] until adjStart[u + 1]) {
                val v = adjNode[k]
                if (closed[v] == stamp) continue
                val e = adjEdge[k]
                val nd = g[u] + edgeLengthM(e)
                if (seen[v] != stamp || nd < g[v]) {
                    seen[v] = stamp; g[v] = nd; prev[v] = u; prevE[v] = e
                    open.add(Open(nd + h(v), v))
                }
            }
        }
        if (!found) return null
        var count = 0
        var i = t
        while (i != -1) { count++; i = prev[i] }
        val nodes = IntArray(count); val edges = IntArray(count - 1)
        i = t
        var k = count - 1
        while (i != -1) {
            nodes[k] = i
            if (k > 0) edges[k - 1] = prevE[i]
            i = prev[i]; k--
        }
        return nodes to edges
    }

    // ---- places -------------------------------------------------------------------------------------

    /** Accent-insensitive search over place names: prefix matches first, then shorter names. */
    fun search(text: String, limit: Int = 20): List<Place> {
        val q = fold(text)
        if (q.isEmpty()) return emptyList()
        val prefix = ArrayList<Place>(); val inside = ArrayList<Place>()
        for (p in places) {
            when {
                p.key.startsWith(q) -> prefix.add(p)
                p.key.contains(q) -> inside.add(p)
                // every word of the query somewhere in the name: "leclerc ecole" still finds it
                q.split(' ').all { p.key.contains(it) } -> inside.add(p)
            }
        }
        prefix.sortBy { it.name.length }
        inside.sortBy { it.name.length }
        return (prefix + inside).take(limit)
    }

    /** The nearest named suburb / neighbourhood / quarter within [maxMeters], or null. */
    fun neighbourhoodOf(latD: Double, lonD: Double, maxMeters: Double = NEIGHBOURHOOD_MAX_M): Place? {
        var best: Place? = null; var bestD = maxMeters
        for (p in places) {
            if (!p.isNeighbourhood) continue
            val d = haversineM(latD, lonD, p.lat, p.lon)
            if (d <= bestD) { bestD = d; best = p }
        }
        return best
    }

    fun describe(): String = "  map pack: " + city + ", " + nodeCount + " nodes, " + edgeCount + " edges, " +
        places.size + " places, built " + generatedAt
}
