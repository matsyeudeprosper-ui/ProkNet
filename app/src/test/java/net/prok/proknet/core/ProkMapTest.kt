package net.prok.proknet.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.19: the phone reads the pack the server wrote, and walks the same route.
 *
 * Two sources of bytes:
 *
 *  1. `src/test/resources/synthetic.prokmap` + `synthetic.expected.json`, written by
 *     `server/tests/test_mappack.py` through the real encoder, with the route the Python
 *     router found. The Kotlin reader must decode the same counts and find the same path,
 *     distance and ETA - the cross-language rule from v0.16.3: one committed fixture, or
 *     both sides stay self-consistently wrong.
 *  2. [TestPack], a forty-line Kotlin encoder, for the cases that need their own graph:
 *     ETA arithmetic on stairs, a stored length longer than the straight line, corruption.
 */
class ProkMapTest {

    // ================= a tiny encoder for graphs made in the test =================

    object TestPack {
        class Node(val lat: Int, val lon: Int)
        class Edge(val a: Int, val b: Int, val len: Int, val cls: Int, val name: Int = 0)
        class Place(val kind: Int, val lat: Int, val lon: Int, val name: String)

        fun encode(nodes: List<Node>, edges: List<Edge>, names: List<String> = listOf(""),
                   places: List<Place> = emptyList(), city: String = "Test", generatedAt: Long = 1L): ByteArray {
            val nameBytes = names.map { it.toByteArray(Charsets.UTF_8) }
            val placeBytes = places.map { it.name.toByteArray(Charsets.UTF_8) }
            val nameLen = nameBytes.sumOf { 2 + it.size }
            val placeLen = placeBytes.sumOf { 11 + it.size }
            val cityB = city.toByteArray(Charsets.UTF_8)
            val total = 56 + 1 + cityB.size + nodes.size * 8 + edges.size * 13 + nameLen + placeLen
            val b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
            b.put('P'.code.toByte()).put('K'.code.toByte()).put('M'.code.toByte()).put('P'.code.toByte())
            b.putShort(1).putShort(0).putLong(generatedAt)
            b.putInt(nodes.minOf { it.lat }).putInt(nodes.minOf { it.lon }).putInt(nodes.maxOf { it.lat }).putInt(nodes.maxOf { it.lon })
            b.putInt(nodes.size).putInt(edges.size).putInt(names.size).putInt(places.size).putInt(nameLen).putInt(placeLen)
            b.put(cityB.size.toByte()).put(cityB)
            for (n in nodes) b.putInt(n.lat).putInt(n.lon)
            for (e in edges) b.putInt(e.a).putInt(e.b).putShort(e.len.toShort()).put(e.cls.toByte()).putShort(e.name.toShort())
            for (n in nameBytes) b.putShort(n.size.toShort()).put(n)
            for ((i, p) in places.withIndex()) b.put(p.kind.toByte()).putInt(p.lat).putInt(p.lon).putShort(placeBytes[i].size.toShort()).put(placeBytes[i])
            return b.array()
        }
    }

    private val LAT0 = -4_263_400
    private val LON0 = 15_242_900

    private fun res(name: String): ByteArray =
        javaClass.getResourceAsStream("/$name")?.readBytes()
            ?: throw AssertionError("$name missing from src/test/resources - run server/tests/test_mappack.py to regenerate")

    private val expected: String by lazy { String(res("synthetic.expected.json"), Charsets.UTF_8) }

    /** A number under [key], possibly inside the object [section]. */
    private fun num(key: String, section: String = ""): Double {
        val text = if (section.isEmpty()) expected else {
            val at = expected.indexOf("\"$section\"")
            val end = expected.indexOf('}', at)
            expected.substring(at, end)
        }
        val m = Regex("\"" + key + "\"\\s*:\\s*(-?[0-9.]+)").find(text) ?: throw AssertionError("no $key in $section")
        return m.groupValues[1].toDouble()
    }

    private fun str(key: String, section: String): String {
        val at = expected.indexOf("\"$section\"")
        val end = expected.indexOf('}', at)
        val m = Regex("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").find(expected.substring(at, end)) ?: throw AssertionError("no $key")
        return m.groupValues[1]
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    // ================= the shared fixture =================

    @Test fun the_python_fixture_parses_with_the_counts_python_wrote() {
        val data = res("synthetic.prokmap")
        assertEquals(Regex("\"sha256\"\\s*:\\s*\"([0-9a-f]{64})\"").find(expected)!!.groupValues[1], sha256(data))
        assertEquals(num("bytes").toInt(), data.size)
        val m = ProkMap.parse(data)
        assertEquals("Synthetic", m.city)
        assertEquals(1_790_000_000_000L, m.generatedAt)
        assertEquals(num("nodes").toInt(), m.nodeCount)
        assertEquals(num("edges").toInt(), m.edgeCount)
        assertEquals(num("names").toInt(), m.nameCount)
        assertEquals(num("places").toInt(), m.places.size)
        assertEquals(LAT0, m.nodeLatMicro(0)); assertEquals(LON0, m.nodeLonMicro(0))
        assertEquals("Avenue de la Paix", m.edgeName(0))
        assertEquals(ProkMap.CLASS_STEPS, m.edgeClass(3))
        assertEquals("", m.edgeName(5))
    }

    @Test fun the_route_is_the_one_python_found() {
        val m = ProkMap.parse(res("synthetic.prokmap"))
        val r = m.route(num("from_lat", "route"), num("from_lon", "route"), num("to_lat", "route"), num("to_lon", "route"))
        assertNotNull(r); r!!
        assertEquals(num("distance_m", "route"), r.distanceMeters, 0.01)
        assertEquals(num("eta_s", "route").toInt(), r.etaSeconds)
        // origin, the four nodes 0 1 3 4, destination
        assertEquals(6, r.points.size)
        assertEquals(num("from_lat", "route"), r.points[0][0], 1e-9)
        for ((k, node) in listOf(0, 1, 3, 4).withIndex()) {
            assertEquals(m.nodeLat(node), r.points[k + 1][0], 1e-9)
            assertEquals(m.nodeLon(node), r.points[k + 1][1], 1e-9)
        }
        assertEquals(num("to_lon", "route"), r.points[5][1], 1e-9)
        // the stairs cost their tenth: the plain time would be shorter
        assertTrue(r.etaSeconds > (r.distanceMeters / ProkMap.WALK_M_PER_S).toInt())
    }

    @Test fun no_route_beyond_300_m_and_none_to_an_island() {
        val m = ProkMap.parse(res("synthetic.prokmap"))
        assertNull(m.route(num("from_lat", "no_route"), num("from_lon", "no_route"), num("to_lat", "no_route"), num("to_lon", "no_route")))
        assertNull(m.route(num("from_lat", "island"), num("from_lon", "island"), num("to_lat", "island"), num("to_lon", "island")))
        // the island's own two nodes do route between themselves
        assertNotNull(m.route(m.nodeLat(5), m.nodeLon(5), m.nodeLat(6), m.nodeLon(6)))
        // exactly the boundary: 300 m snaps, 301 m does not
        assertTrue(m.nearestNode(m.nodeLat(0) - 299.0 / 111_320, m.nodeLon(0)) >= 0)
        assertEquals(-1, m.nearestNode(m.nodeLat(0) - 301.5 / 111_320, m.nodeLon(0)))
    }

    @Test fun search_ignores_accents_and_case() {
        val m = ProkMap.parse(res("synthetic.prokmap"))
        assertEquals(str("expect", "search"), m.search(str("query", "search")).first().name)
        assertEquals("École Général Leclerc", m.search("ECOLE").first().name)
        assertEquals("Marché Total", m.search("marche").first().name)
        assertEquals("Poto-Poto", m.search("poto poto").first().name)
        assertEquals("Poto-Poto", m.search("Poto-Poto").first().name)
        assertEquals(listOf("École Général Leclerc"), m.search("leclerc ecole").map { it.name })
        assertTrue(m.search("").isEmpty())
        assertTrue(m.search("zzz").isEmpty())
        assertEquals("école", m.search("ecole").first().kindName)
    }

    @Test fun neighbourhood_is_the_nearest_named_suburb_or_quarter() {
        val m = ProkMap.parse(res("synthetic.prokmap"))
        assertEquals(str("expect", "neighbourhood"), m.neighbourhoodOf(num("lat", "neighbourhood"), num("lon", "neighbourhood"))!!.name)
        assertEquals("Bacongo", m.neighbourhoodOf(m.nodeLat(0), m.nodeLon(0))!!.name)
        // the school is closer than any neighbourhood here, and is not one
        assertEquals("Bacongo", m.neighbourhoodOf(LAT0 / 1e6 + 0.0004, LON0 / 1e6 + 0.0009)!!.name)
        // the island is 7 km from every named place
        assertNull(m.neighbourhoodOf(m.nodeLat(5), m.nodeLon(5)))
    }

    // ================= graphs made here =================

    /** Two nodes 100 m apart on the equator-ish latitude of Brazzaville: 1000 microdeg of latitude ≈ 111 m. */
    private fun twoNodes(len: Int, cls: Int): ProkMap = ProkMap.parse(TestPack.encode(
        listOf(TestPack.Node(LAT0, LON0), TestPack.Node(LAT0 + 900, LON0)),
        listOf(TestPack.Edge(0, 1, len, cls))))

    @Test fun eta_is_four_and_a_half_km_per_hour_plus_a_tenth_on_stairs_and_track() {
        val flat = twoNodes(100, ProkMap.CLASS_RESIDENTIAL).route(LAT0 / 1e6, LON0 / 1e6, (LAT0 + 900) / 1e6, LON0 / 1e6)!!
        assertEquals(100.0, flat.distanceMeters, 1e-9)
        assertEquals(80, flat.etaSeconds)                                   // 100 / 1.25
        val stairs = twoNodes(100, ProkMap.CLASS_STEPS).route(LAT0 / 1e6, LON0 / 1e6, (LAT0 + 900) / 1e6, LON0 / 1e6)!!
        assertEquals(88, stairs.etaSeconds)                                 // 80 * 1.1
        val track = twoNodes(100, ProkMap.CLASS_TRACK).route(LAT0 / 1e6, LON0 / 1e6, (LAT0 + 900) / 1e6, LON0 / 1e6)!!
        assertEquals(88, track.etaSeconds)
        val path = twoNodes(100, ProkMap.CLASS_PATH).route(LAT0 / 1e6, LON0 / 1e6, (LAT0 + 900) / 1e6, LON0 / 1e6)!!
        assertEquals(80, path.etaSeconds)                                   // a path is not a track
        assertEquals(80.0, ProkMap.legSeconds(100.0, ProkMap.CLASS_FOOTWAY), 1e-9)
        assertEquals(88.0, ProkMap.legSeconds(100.0, ProkMap.CLASS_STEPS), 1e-9)
    }

    @Test fun snap_legs_count_in_the_distance_and_the_time_at_the_plain_pace() {
        val m = twoNodes(100, ProkMap.CLASS_STEPS)
        // start 20 m south of node 0 (about 180 microdegrees)
        val r = m.route((LAT0 - 180) / 1e6, LON0 / 1e6, (LAT0 + 900) / 1e6, LON0 / 1e6)!!
        val snap = ProkMap.haversineM((LAT0 - 180) / 1e6, LON0 / 1e6, LAT0 / 1e6, LON0 / 1e6)
        assertTrue(snap > 19.5 && snap < 20.5)
        assertEquals(100.0 + snap, r.distanceMeters, 1e-6)
        assertEquals(Math.round(88.0 + snap / 1.25).toInt(), r.etaSeconds)
    }

    @Test fun the_router_walks_the_stored_length_not_the_straight_line() {
        // A-C direct, but a winding 300 m street; A-B-C is two straight 100 m streets
        val m = ProkMap.parse(TestPack.encode(
            listOf(TestPack.Node(LAT0, LON0), TestPack.Node(LAT0 + 900, LON0), TestPack.Node(LAT0 + 1800, LON0)),
            listOf(TestPack.Edge(0, 2, 300, ProkMap.CLASS_RESIDENTIAL),
                   TestPack.Edge(0, 1, 100, ProkMap.CLASS_RESIDENTIAL),
                   TestPack.Edge(1, 2, 100, ProkMap.CLASS_RESIDENTIAL))))
        val r = m.route(LAT0 / 1e6, LON0 / 1e6, (LAT0 + 1800) / 1e6, LON0 / 1e6)!!
        assertEquals(200.0, r.distanceMeters, 1e-9)
        assertEquals(5, r.points.size)          // origin, A, B, C, destination
        // and the other way round, the same
        val back = m.route((LAT0 + 1800) / 1e6, LON0 / 1e6, LAT0 / 1e6, LON0 / 1e6)!!
        assertEquals(200.0, back.distanceMeters, 1e-9)
        assertEquals(m.nodeLat(1), back.points[2][0], 1e-9)
    }

    @Test fun the_reader_refuses_a_damaged_pack() {
        val good = res("synthetic.prokmap")
        fun refused(mutate: (ByteArray) -> ByteArray, why: String) {
            try { ProkMap.parse(mutate(good.copyOf())); throw AssertionError("accepted: $why") }
            catch (e: IllegalArgumentException) { /* expected */ }
        }
        refused({ it[0] = 'X'.code.toByte(); it }, "magic")
        refused({ it[4] = 2; it }, "version")
        refused({ it.copyOf(it.size - 3) }, "truncated")
        refused({ it + byteArrayOf(0) }, "trailing byte")
        refused({ val b = ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN); b.putInt(56 + 1 + "Synthetic".length + 7 * 8, 99); it }, "edge to a missing node")
        // the good one still parses after all that copying
        assertEquals(7, ProkMap.parse(good).nodeCount)
    }

    @Test fun fold_strips_what_a_person_would_not_type() {
        assertEquals("ecole general leclerc", ProkMap.fold("École  Général-Leclerc"))
        assertEquals("marche total", ProkMap.fold("Marché Total"))
        assertEquals("ngoma tsetse", ProkMap.fold("Ngoma-Tsétsé"))
        assertEquals("l eglise", ProkMap.fold("L'Église"))
    }
}
