package net.prok.proknet.core

/**
 * v0.16.4: bringing a database that already exists on somebody's phone up to date.
 *
 * This is the one part of the project that can only be wrong on an **upgraded** phone and
 * is always right on a fresh install, so every test that creates a database from scratch
 * passes while the bug is present. That is exactly what happened:
 *
 * - v0.16.0 (build 62) shipped `payment_receipts` with no `delivered` column and
 *   `destination_claims` keyed `PRIMARY KEY(seller_id, rail)`.
 * - v0.16.1 (build 63) changed both shapes **without bumping the database version**.
 * - v0.16.2 and v0.16.3 bumped to 9, but their upgrade path only calls
 *   `CREATE TABLE IF NOT EXISTS`, which does nothing to a table that already exists.
 *
 * So a phone that has been running since build 62 still has the build-62 shapes today.
 * `receiptDelivered` would query a column that is not there, and `destination_claims`
 * would keep only one row per (seller, rail) - so saving a new claim REPLACES the old one
 * and the cooling period, which exists so a payment already on its way still lands
 * somewhere valid, silently stops working.
 *
 * The decisions live here, as a pure function over the schema SQLite reports, because
 * `MessageStore` needs a `Context` and cannot run off a phone. The statements this
 * returns are the statements production executes.
 */
object Migrations {

    /** The database version v0.16.4 ships. */
    const val DB_VERSION = 10

    /** One column, as `PRAGMA table_info` describes it. [pk] is 0 for a non-key column. */
    class Column(val name: String, val pk: Int = 0)

    /** Columns whose absence means the table is not one we recognise. Never fabricated. */
    private val RECEIPT_EVIDENCE = listOf("payment_id", "seller_id", "buyer_id", "sig")

    /** name to the literal used when a column is missing from an older table. */
    private val RECEIPT_COLUMNS = listOf(
        "payment_id" to null, "seller_id" to null, "buyer_id" to null,
        "rail" to "''", "destination_hash" to "''",
        "expected" to "0", "observed" to "0", "observed_at" to "0",
        "source" to "''", "source_package" to "''", "evidence_hash" to "''",
        "parser_version" to "0", "confidence" to "''", "settlement_ids" to "''",
        "reference" to "''", "delivered" to "0", "sig" to null)

    const val CREATE_RECEIPTS_TEMPLATE =
        "CREATE TABLE %s(" +
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
            "delivered INTEGER NOT NULL DEFAULT 0," +
            "sig BLOB NOT NULL)"

    const val CREATE_CLAIMS_TEMPLATE =
        "CREATE TABLE %s(" +
            "seller_id TEXT NOT NULL," +
            "rail TEXT NOT NULL," +
            "msisdn TEXT NOT NULL," +
            "version INTEGER NOT NULL," +
            "created_at INTEGER NOT NULL," +
            "sig BLOB NOT NULL," +
            "PRIMARY KEY(seller_id, rail, version))"

    /** The key `destination_claims` must have, so a seller keeps a history of numbers. */
    private val CLAIM_KEY = listOf("seller_id", "rail", "version")

    private val CLAIM_COLUMNS = listOf("seller_id", "rail", "msisdn", "version", "created_at", "sig")

    class Unmigratable(message: String) : Exception(message)

    /**
     * Every statement needed to bring [schema] to version 10, in order.
     *
     * Empty when there is nothing to do, which is the normal case on a fresh install and
     * on a phone that has already been migrated - so running this twice is safe.
     *
     * @param schema table name to its columns, as `PRAGMA table_info` reports them. A
     *        table that is absent is left to `CREATE TABLE IF NOT EXISTS`.
     */
    fun toV10(schema: Map<String, List<Column>>): List<String> {
        val out = ArrayList<String>()
        schema["payment_receipts"]?.let { out += receiptStatements(it) }
        schema["destination_claims"]?.let { out += claimStatements(it) }
        return out
    }

    // ---- payment_receipts ----------------------------------------------------------------

    fun receiptStatements(existing: List<Column>): List<String> {
        val have = existing.map { it.name }
        val want = RECEIPT_COLUMNS.map { it.first }
        val missing = want.filter { it !in have }
        if (missing.isEmpty()) return emptyList()

        val lost = RECEIPT_EVIDENCE.filter { it !in have }
        if (lost.isNotEmpty()) {
            // Refuse rather than write an empty signature. A receipt with a fabricated
            // signature is worse than no receipt: it looks like evidence that money
            // arrived and verifies against nobody.
            throw Unmigratable("payment_receipts is missing " + lost.joinToString(", "))
        }

        // Rebuild rather than ALTER, even for the single missing `delivered` column that
        // is the only shape that ever shipped. `ALTER TABLE ADD COLUMN` appends, so an
        // upgraded phone would end up with `..., sig, delivered` while a fresh install has
        // `..., delivered, sig`. The queries are all by name, so nothing breaks today -
        // but a phone whose table is a different shape from every test database is the
        // exact situation this whole file exists to end.
        //
        // Columns the old table does not have are filled with a literal. Never for
        // evidence, which was refused above.
        val select = RECEIPT_COLUMNS.joinToString(", ") { (name, fill) ->
            if (name in have) name else (fill ?: throw Unmigratable("cannot fill " + name))
        }
        return listOf(
            CREATE_RECEIPTS_TEMPLATE.format("payment_receipts_v10"),
            "INSERT INTO payment_receipts_v10(" + want.joinToString(", ") + ") " +
                "SELECT " + select + " FROM payment_receipts",
            "DROP TABLE payment_receipts",
            "ALTER TABLE payment_receipts_v10 RENAME TO payment_receipts")
    }

    // ---- destination_claims ----------------------------------------------------------------

    fun claimStatements(existing: List<Column>): List<String> {
        val key = existing.filter { it.pk > 0 }.sortedBy { it.pk }.map { it.name }
        if (key == CLAIM_KEY) return emptyList()

        val have = existing.map { it.name }
        val lost = CLAIM_COLUMNS.filter { it !in have }
        if (lost.isNotEmpty())
            throw Unmigratable("destination_claims is missing " + lost.joinToString(", "))

        val cols = CLAIM_COLUMNS.joinToString(", ")
        return listOf(
            CREATE_CLAIMS_TEMPLATE.format("destination_claims_v10"),
            // GROUP BY the new key, so a database that somehow holds two rows for one
            // version keeps the later one rather than failing the whole upgrade. SQLite
            // takes the bare columns from the row that produced MAX(created_at).
            "INSERT INTO destination_claims_v10(" + cols + ") " +
                "SELECT seller_id, rail, msisdn, version, MAX(created_at), sig " +
                "FROM destination_claims GROUP BY seller_id, rail, version",
            "DROP TABLE destination_claims",
            "ALTER TABLE destination_claims_v10 RENAME TO destination_claims")
    }

    /** True when [schema] already has everything version 10 expects. */
    fun isCurrent(schema: Map<String, List<Column>>): Boolean = toV10(schema).isEmpty()
}
