package net.prok.proknet.core

import java.sql.Connection
import java.sql.DriverManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.16.4: upgrading a database that is already on somebody's phone.
 *
 * Every other test in this project builds its state from nothing, which is precisely why
 * none of them could see this. The bug only exists on a phone that has been carried
 * forward from an older build:
 *
 * - v0.16.0 (build 62) shipped `payment_receipts` with no `delivered` column and
 *   `destination_claims` keyed `PRIMARY KEY(seller_id, rail)`.
 * - v0.16.1 (build 63) changed both **without bumping the database version**, so no
 *   upgrade step ever ran.
 * - v0.16.2 and v0.16.3 bumped to 9, but only called `CREATE TABLE IF NOT EXISTS`, which
 *   does nothing at all to a table that already exists.
 *
 * So the schemas below are not invented: they are the exact `CREATE TABLE` text from
 * commits 6f22ec6 (build 62) and 867dae6 (build 63). Each fixture is a real SQLite
 * database, and what runs against it is [Migrations.toV10] - the same statements
 * `MessageStore.migrateV10` executes on a phone.
 *
 * What a failure here means: on a real upgraded phone, `receiptDelivered` queries a column
 * that does not exist, and saving a new destination REPLACES the old one instead of
 * keeping it, so the cooling period - which exists so a payment already on its way still
 * lands somewhere valid - silently stops working.
 */
class MigrationTest {

    // ---- the schemas that actually shipped ------------------------------------------------

    /** build 62. No `delivered`. */
    private val receiptsV62 =
        "CREATE TABLE payment_receipts(" +
            "payment_id TEXT PRIMARY KEY," +
            "seller_id TEXT NOT NULL," +
            "buyer_id TEXT NOT NULL," +
            "rail TEXT NOT NULL," +
            "destination_hash TEXT NOT NULL," +
            "expected INTEGER NOT NULL," +
            "observed INTEGER NOT NULL," +
            "observed_at INTEGER NOT NULL," +
            "source TEXT NOT NULL," +
            "source_package TEXT NOT NULL," +
            "evidence_hash TEXT NOT NULL," +
            "parser_version INTEGER NOT NULL," +
            "confidence TEXT NOT NULL," +
            "settlement_ids TEXT NOT NULL," +
            "reference TEXT NOT NULL DEFAULT ''," +
            "sig BLOB NOT NULL)"

    /** build 62. One row per (seller, rail): no history, so no cooling. */
    private val claimsV62 =
        "CREATE TABLE destination_claims(" +
            "seller_id TEXT NOT NULL," +
            "rail TEXT NOT NULL," +
            "msisdn TEXT NOT NULL," +
            "version INTEGER NOT NULL," +
            "created_at INTEGER NOT NULL," +
            "sig BLOB NOT NULL," +
            "PRIMARY KEY(seller_id, rail))"

    private val receiptsV63 = Migrations.CREATE_RECEIPTS_TEMPLATE.format("payment_receipts")
    private val claimsV63 = Migrations.CREATE_CLAIMS_TEMPLATE.format("destination_claims")

    // ---- plumbing ----------------------------------------------------------------------

    private fun db(): Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.columns(table: String): List<Migrations.Column> {
        val out = ArrayList<Migrations.Column>()
        createStatement().use { st ->
            st.executeQuery("PRAGMA table_info($table)").use { rs ->
                while (rs.next()) out.add(Migrations.Column(rs.getString("name"), rs.getInt("pk")))
            }
        }
        return out
    }

    /** name|type|notnull|default|pk for every column: everything item 19 asks about. */
    private fun Connection.shape(table: String): List<String> {
        val out = ArrayList<String>()
        createStatement().use { st ->
            st.executeQuery("PRAGMA table_info($table)").use { rs ->
                while (rs.next()) out.add(listOf(
                    rs.getString("name"), rs.getString("type"),
                    rs.getInt("notnull").toString(), rs.getString("dflt_value") ?: "-",
                    rs.getInt("pk").toString()).joinToString("|"))
            }
        }
        return out
    }

    private fun Connection.indexes(table: String): List<String> {
        val out = ArrayList<String>()
        createStatement().use { st ->
            st.executeQuery("SELECT name, sql FROM sqlite_master WHERE type='index'" +
                " AND tbl_name='$table' ORDER BY name").use { rs ->
                while (rs.next()) out.add(rs.getString("name") + "=" + (rs.getString("sql") ?: "auto"))
            }
        }
        return out
    }

    private fun Connection.schema(): Map<String, List<Migrations.Column>> {
        val out = HashMap<String, List<Migrations.Column>>()
        for (t in listOf("payment_receipts", "destination_claims"))
            columns(t).takeIf { it.isNotEmpty() }?.let { out[t] = it }
        return out
    }

    /** Exactly what a phone does: read the schema, plan, run the plan in one transaction. */
    private fun Connection.migrate(): Int {
        val plan = Migrations.toV10(schema())
        autoCommit = false
        try {
            for (sql in plan) exec(sql)
            commit()
        } catch (e: Exception) {
            rollback(); throw e
        } finally {
            autoCommit = true
        }
        return plan.size
    }

    private fun Connection.count(sql: String): Long =
        createStatement().use { st -> st.executeQuery(sql).use { it.next(); it.getLong(1) } }

    private fun Connection.insertReceipt(id: String, sig: ByteArray, withDelivered: Boolean) {
        val cols = "payment_id, seller_id, buyer_id, rail, destination_hash, expected, observed," +
            " observed_at, source, source_package, evidence_hash, parser_version, confidence," +
            " settlement_ids, reference, sig" + (if (withDelivered) ", delivered" else "")
        val marks = "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?" + (if (withDelivered) ",?" else "")
        prepareStatement("INSERT INTO payment_receipts($cols) VALUES($marks)").use { ps ->
            ps.setString(1, id); ps.setString(2, "seller"); ps.setString(3, "buyer")
            ps.setString(4, "MTN_MOMO"); ps.setString(5, "desthash")
            ps.setLong(6, 7300); ps.setLong(7, 7300); ps.setLong(8, 1_700_000_000_000)
            ps.setString(9, "DIRECT_SMS"); ps.setString(10, "com.android.mms")
            ps.setString(11, "evhash"); ps.setInt(12, 1); ps.setString(13, "DEVICE_SMS_VERIFIED")
            ps.setString(14, "s1"); ps.setString(15, "")
            ps.setBytes(16, sig)
            if (withDelivered) ps.setInt(17, 1)
            ps.execute()
        }
    }

    private fun Connection.insertClaim(rail: String, msisdn: String, version: Int, at: Long) {
        prepareStatement("INSERT OR REPLACE INTO destination_claims" +
            "(seller_id, rail, msisdn, version, created_at, sig) VALUES(?,?,?,?,?,?)").use { ps ->
            ps.setString(1, "seller"); ps.setString(2, rail); ps.setString(3, msisdn)
            ps.setInt(4, version); ps.setLong(5, at); ps.setBytes(6, byteArrayOf(version.toByte()))
            ps.execute()
        }
    }

    private fun build62(): Connection = db().also {
        it.exec(receiptsV62); it.exec(claimsV62)
    }

    private fun build63(): Connection = db().also {
        it.exec(receiptsV63); it.exec(claimsV63)
    }

    // ================= the bug, on a build-62 database =================

    @Test fun a_build_62_database_really_is_missing_the_delivered_column() {
        // if this ever stops being true the rest of the file proves nothing
        build62().use { c ->
            assertTrue("delivered" !in c.columns("payment_receipts").map { it.name })
            assertEquals(listOf("seller_id", "rail"),
                c.columns("destination_claims").filter { it.pk > 0 }.sortedBy { it.pk }.map { it.name })
        }
    }

    @Test fun upgrading_from_build_62_adds_delivered_and_keeps_every_receipt() {
        build62().use { c ->
            val sig = byteArrayOf(1, 2, 3, 4, 5)
            c.insertReceipt("p1", sig, withDelivered = false)
            c.insertReceipt("p2", byteArrayOf(9), withDelivered = false)

            assertTrue(c.migrate() > 0)

            assertTrue("delivered" in c.columns("payment_receipts").map { it.name })
            assertEquals("no payment evidence may be lost on an upgrade",
                2L, c.count("SELECT COUNT(*) FROM payment_receipts"))
            // an existing receipt starts undelivered, which is the safe direction: it will
            // be offered to the buyer again rather than silently treated as done
            assertEquals(0L, c.count("SELECT delivered FROM payment_receipts WHERE payment_id='p1'"))
            // and the signature is byte-for-byte what it was
            c.createStatement().use { st ->
                st.executeQuery("SELECT sig FROM payment_receipts WHERE payment_id='p1'").use { rs ->
                    rs.next()
                    assertArrayEquals("a receipt whose signature changed is no longer evidence",
                        sig, rs.getBytes(1))
                }
            }
        }
    }

    @Test fun upgrading_from_build_62_gives_destination_claims_a_history() {
        build62().use { c ->
            c.insertClaim("MTN_MOMO", "066111111", 1, 1_000)
            c.migrate()

            assertEquals(listOf("seller_id", "rail", "version"),
                c.columns("destination_claims").filter { it.pk > 0 }.sortedBy { it.pk }.map { it.name })
            assertEquals("the seller's current number must survive the upgrade",
                1L, c.count("SELECT COUNT(*) FROM destination_claims"))

            // the thing that was broken: a second claim on the same rail now KEEPS the first
            c.insertClaim("MTN_MOMO", "066999999", 2, 2_000)
            assertEquals(2L, c.count("SELECT COUNT(*) FROM destination_claims"))
        }
    }

    @Test fun before_the_migration_a_new_claim_destroys_the_old_one() {
        // the failure being fixed, stated once so the test above is not just describing
        // behaviour that was always fine
        build62().use { c ->
            c.insertClaim("MTN_MOMO", "066111111", 1, 1_000)
            c.insertClaim("MTN_MOMO", "066999999", 2, 2_000)
            assertEquals("build 62 keeps one row per (seller, rail), so cooling cannot work",
                1L, c.count("SELECT COUNT(*) FROM destination_claims"))
        }
    }

    @Test fun the_queries_the_app_runs_work_after_a_build_62_upgrade() {
        build62().use { c ->
            c.insertReceipt("p1", byteArrayOf(1), withDelivered = false)
            c.insertClaim("MTN_MOMO", "066111111", 1, 1_000)
            c.migrate()
            c.insertClaim("AIRTEL_MONEY", "055222222", 2, 2_000)

            // MessageStore.receiptDelivered
            assertEquals(0L, c.count(
                "SELECT COUNT(*) FROM payment_receipts WHERE payment_id='p1' AND delivered=1"))
            c.exec("UPDATE payment_receipts SET delivered=1 WHERE payment_id='p1'")
            assertEquals(1L, c.count(
                "SELECT COUNT(*) FROM payment_receipts WHERE payment_id='p1' AND delivered=1"))

            // MessageStore.previousDestinationClaim: the claim cooling falls back to
            c.createStatement().use { st ->
                st.executeQuery("SELECT msisdn FROM destination_claims WHERE seller_id='seller'" +
                    " AND version<2 ORDER BY version DESC LIMIT 1").use { rs ->
                    assertTrue("there must BE a previous claim, or there is no cooling", rs.next())
                    assertEquals("066111111", rs.getString(1))
                }
            }
        }
    }

    // ================= build 63 and 64 =================

    @Test fun a_build_63_database_needs_nothing(
    ) {
        build63().use { c ->
            c.insertReceipt("p1", byteArrayOf(1), withDelivered = true)
            c.insertClaim("MTN_MOMO", "066111111", 1, 1_000)
            assertEquals("build 63 already had the right shapes", 0, c.migrate())
            assertEquals(1L, c.count("SELECT COUNT(*) FROM payment_receipts"))
        }
    }

    @Test fun a_build_64_database_needs_nothing() {
        // build 64 is build 63's payment tables plus two new ones, which the migration
        // does not touch at all
        build63().use { c ->
            c.exec("CREATE TABLE pay_sync(k TEXT PRIMARY KEY, at INTEGER NOT NULL)")
            c.exec("CREATE TABLE receipt_rules(version INTEGER PRIMARY KEY," +
                " valid_from INTEGER NOT NULL, terms TEXT NOT NULL," +
                " signature TEXT NOT NULL, stored_at INTEGER NOT NULL)")
            assertEquals(0, c.migrate())
        }
    }

    // ================= running it twice =================

    @Test fun migrating_twice_changes_nothing_the_second_time() {
        build62().use { c ->
            c.insertReceipt("p1", byteArrayOf(1), withDelivered = false)
            c.insertClaim("MTN_MOMO", "066111111", 1, 1_000)
            assertTrue(c.migrate() > 0)
            val receipts = c.count("SELECT COUNT(*) FROM payment_receipts")
            val claims = c.count("SELECT COUNT(*) FROM destination_claims")

            assertEquals("a migrated database must plan nothing", 0, c.migrate())
            assertEquals(receipts, c.count("SELECT COUNT(*) FROM payment_receipts"))
            assertEquals(claims, c.count("SELECT COUNT(*) FROM destination_claims"))
            assertTrue(Migrations.isCurrent(c.schema()))
        }
    }

    // ================= an upgraded phone and a fresh install must match =================

    @Test fun an_upgraded_database_ends_up_identical_to_a_fresh_one() {
        build62().use { old ->
            old.insertReceipt("p1", byteArrayOf(1), withDelivered = false)
            old.insertClaim("MTN_MOMO", "066111111", 1, 1_000)
            old.migrate()
            build63().use { fresh ->
                for (t in listOf("payment_receipts", "destination_claims")) {
                    // names, types, NOT NULL, defaults, key ordinals AND order. A phone
                    // whose table is a different shape from every test database is the
                    // exact situation this file exists to end.
                    assertEquals("$t must be structurally identical after an upgrade",
                        fresh.shape(t), old.shape(t))
                    assertEquals("$t must have the same indexes", fresh.indexes(t), old.indexes(t))
                }
            }
        }
    }

    // ================= what the migration must not touch =================

    /** The other payment tables a build-62 phone is carrying while it upgrades. */
    private fun Connection.addUntouchedTables() {
        exec("CREATE TABLE receipt_rules(version INTEGER PRIMARY KEY," +
            " valid_from INTEGER NOT NULL, terms TEXT NOT NULL," +
            " signature TEXT NOT NULL, stored_at INTEGER NOT NULL)")
        exec("CREATE TABLE payment_expectations(payment_id TEXT PRIMARY KEY," +
            " buyer_id TEXT NOT NULL, seller_id TEXT NOT NULL, rail TEXT NOT NULL," +
            " destination_hash TEXT NOT NULL, amount INTEGER NOT NULL," +
            " created_at INTEGER NOT NULL, valid_from INTEGER NOT NULL," +
            " expires_at INTEGER NOT NULL, settlement_ids TEXT NOT NULL, state TEXT NOT NULL)")
        exec("CREATE TABLE settlements(settlement_id TEXT PRIMARY KEY, session_id TEXT NOT NULL," +
            " buyer_id TEXT NOT NULL, seller_id TEXT NOT NULL, checkpoint_hash TEXT NOT NULL," +
            " gross INTEGER NOT NULL, seller_net INTEGER NOT NULL, prok_fee INTEGER NOT NULL," +
            " created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, status TEXT NOT NULL," +
            " rail TEXT NOT NULL DEFAULT 'NONE', payment_ref TEXT NOT NULL DEFAULT ''," +
            " note TEXT NOT NULL DEFAULT '', synced_at INTEGER NOT NULL DEFAULT 0)")
        exec("INSERT INTO receipt_rules VALUES(2, 1758400000000," +
            " '{\"credit\":[\"fonds arrives\"]}', 'abcdef0123', 1758400000001)")
        exec("INSERT INTO payment_expectations VALUES('pay-1','buyer','seller','MTN_MOMO'," +
            " 'hash-v1', 7300, 1000, 1000, 1200000, 's1', 'ACTIVE')")
        exec("INSERT INTO settlements VALUES('s1','sess','buyer','seller','cp'," +
            " 7300, 6935, 365, 1000, 99999999, 'PENDING', 'NONE', '', '', 0)")
    }

    @Test fun a_signed_parser_configuration_survives_the_upgrade() {
        // losing this would silently push a phone back to the built-in wording, and the
        // only symptom would be payments quietly failing to clear
        build62().use { c ->
            c.addUntouchedTables()
            c.migrate()
            c.createStatement().use { st ->
                st.executeQuery("SELECT version, valid_from, terms, signature FROM receipt_rules" +
                    " ORDER BY version DESC LIMIT 1").use { rs ->
                    assertTrue("the trusted configuration must still be there", rs.next())
                    assertEquals(2, rs.getInt(1))
                    assertEquals(1758400000000L, rs.getLong(2))
                    assertEquals("{\"credit\":[\"fonds arrives\"]}", rs.getString(3))
                    assertEquals("a changed signature would fail ReceiptRules.restore and" +
                        " drop the phone back to built-in rules", "abcdef0123", rs.getString(4))
                }
            }
        }
    }

    @Test fun an_outstanding_payment_survives_the_upgrade() {
        // a phone can be upgraded mid-payment: the buyer owes, the expectation is live,
        // and the seller has already signed a receipt that has not reached the buyer
        build62().use { c ->
            c.addUntouchedTables()
            val sig = byteArrayOf(7, 7, 7, 7)
            c.insertReceipt("pay-1", sig, withDelivered = false)
            c.migrate()

            c.createStatement().use { st ->
                st.executeQuery("SELECT rail, destination_hash, amount, state, settlement_ids" +
                    " FROM payment_expectations WHERE payment_id='pay-1'").use { rs ->
                    assertTrue("the expectation must still be readable", rs.next())
                    assertEquals("MTN_MOMO", rs.getString(1))
                    assertEquals("hash-v1", rs.getString(2))
                    assertEquals(7300, rs.getLong(3))
                    assertEquals("ACTIVE", rs.getString(4))
                    assertEquals("s1", rs.getString(5))
                }
            }
            c.createStatement().use { st ->
                st.executeQuery("SELECT sig, expected, confidence FROM payment_receipts" +
                    " WHERE payment_id='pay-1'").use { rs ->
                    assertTrue("the receipt must still be readable", rs.next())
                    assertArrayEquals("a receipt whose signature changed is not evidence",
                        sig, rs.getBytes(1))
                    assertEquals(7300, rs.getLong(2))
                    assertEquals("DEVICE_SMS_VERIFIED", rs.getString(3))
                }
            }
            c.createStatement().use { st ->
                st.executeQuery("SELECT status, gross FROM settlements WHERE settlement_id='s1'").use { rs ->
                    assertTrue(rs.next())
                    assertEquals("no migration may change what somebody owes", "PENDING", rs.getString(1))
                    assertEquals(7300, rs.getLong(2))
                }
            }
        }
    }

    @Test fun the_migration_touches_only_the_two_tables_it_names() {
        build62().use { c ->
            c.addUntouchedTables()
            val before = listOf("receipt_rules", "payment_expectations", "settlements")
                .associateWith { c.shape(it) }
            c.migrate()
            for ((t, shape) in before)
                assertEquals("$t must be untouched", shape, c.shape(t))
        }
    }

    // ================= the planner's own rules =================

    @Test fun a_missing_signature_column_is_refused_rather_than_invented() {
        val broken = listOf("payment_id", "seller_id", "buyer_id").map { Migrations.Column(it) }
        try {
            Migrations.receiptStatements(broken)
            throw AssertionError("expected a refusal")
        } catch (e: Migrations.Unmigratable) {
            assertTrue("a fabricated signature would look like evidence that money arrived",
                e.message!!.contains("sig"))
        }
    }

    @Test fun a_table_that_is_not_there_yet_is_left_to_the_create_statements() {
        assertEquals(emptyList<String>(), Migrations.toV10(emptyMap()))
    }

    @Test fun two_rows_at_one_version_keep_the_later_one_instead_of_failing_the_upgrade() {
        // cannot happen through the app, but an upgrade that throws leaves a phone unable
        // to open its own database at all
        db().use { c ->
            c.exec("CREATE TABLE destination_claims(seller_id TEXT NOT NULL, rail TEXT NOT NULL," +
                " msisdn TEXT NOT NULL, version INTEGER NOT NULL, created_at INTEGER NOT NULL," +
                " sig BLOB NOT NULL)")
            c.insertClaim("MTN_MOMO", "066111111", 1, 1_000)
            c.insertClaim("MTN_MOMO", "066222222", 1, 2_000)
            c.migrate()
            assertEquals(1L, c.count("SELECT COUNT(*) FROM destination_claims"))
            c.createStatement().use { st ->
                st.executeQuery("SELECT msisdn FROM destination_claims").use { rs ->
                    rs.next()
                    assertEquals("066222222", rs.getString(1))
                }
            }
        }
    }

    @Test fun the_database_version_the_app_ships_is_ten() {
        assertEquals(10, Migrations.DB_VERSION)
    }
}
