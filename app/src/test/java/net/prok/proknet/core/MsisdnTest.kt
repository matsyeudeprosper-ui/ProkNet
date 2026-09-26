package net.prok.proknet.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * v0.18.0: one number, one hash, on every side - the fixture was written by the Python
 * side, so a normalisation that drifts here fails here and not in Congo.
 */
class MsisdnTest {

    private fun fixture(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val f = File(dir, "server/tests/fixtures/msisdn_hash.txt")
            if (f.exists()) return f
            dir = dir.parentFile
        }
        throw AssertionError("server/tests/fixtures/msisdn_hash.txt not found from " + File(".").absolutePath)
    }

    @Test fun every_fixture_line_hashes_the_same_here_as_in_python() {
        val rows = fixture().readLines(Charsets.UTF_8).filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split("|") }
        assert(rows.size >= 8)
        for (r in rows) {
            assertEquals(r[0], r[1], Msisdn.digits(r[0]))
            assertEquals(r[0], r[2], Msisdn.hash(r[0]))
        }
    }

    @Test fun spellings_of_one_number_are_one_hash_and_a_guess_is_not_a_number() {
        assertEquals(Msisdn.hash("066123456"), Msisdn.hash("+242 06 612 34 56"))
        assertEquals(Msisdn.hash("066123456"), Msisdn.hash("00242066123456"))
        assertNotEquals(Msisdn.hash("066123456"), Msisdn.hash("055987654"))
        assertEquals("", Msisdn.hash("66123456"))       // eight digits: no guessing the missing one
        assertEquals("", Msisdn.hash("0661234567"))
        assertEquals("", Msisdn.hash("abc"))
        assertEquals("", Msisdn.hash(null))
        assertEquals("06 61 23 45 6", Msisdn.pretty("066123456"))
    }
}
