package net.prok.proknet.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Message directions. */
object Dir {
    const val IN = "in"        // addressed to me, stored as received
    const val OUT = "out"      // written by me
    const val CARRY = "carry"  // someone else's packet I hold for its destination
}

/** Message states. */
object MsgStatus {
    // out
    const val PENDING = "pending"         // stored, waiting for the destination or a relay
    const val SENDING = "sending"         // one attempt in flight
    const val DELIVERED = "delivered"     // destination confirmed it stored the message (final)
    const val HANDED_OFF = "handed_off"   // a relay took custody; NOT final delivery
    const val FAILED = "failed"           // rejected, or too many attempts
    const val EXPIRED = "expired"         // waited longer than the TTL
    // carry
    const val CARRYING = "carrying"       // holding for the destination
    const val FORWARDING = "forwarding"   // attempt to the destination in flight
    const val FORWARDED = "forwarded"     // destination confirmed (final, from the relay's view)
    // in
    const val RECEIVED = "received"
    // transfers (v0.5)
    const val RECEIVING = "receiving"
}

class StoredMessage(
    val rowId: Long,
    val msgId: String,
    val direction: String,
    val peerId: String,      // in: origin short | out: destination short | carry: origin short
    val peerName: String,
    val text: String,        // plaintext (in/out); "" for carried packets (opaque)
    val timestamp: Long,
    val status: String,
    val attempts: Int = 0,
    val nextAttempt: Long = 0,
    val lastError: String = "",
    val deliveredAt: Long = 0,
    val destId: String = "",     // full destination ID hex (out, carry, in)
    val originId: String = "",   // full origin ID hex (carry, in)
    val via: String = "",        // in: relay short ID it came through | out: relay that took custody
    val ttl: Int = 0,
    val hops: Int = 0,
    val payload: ByteArray? = null, // v0.5: the on-air payload (ciphertext) for out and carry rows
    val enc: Int = 0,               // 1 = end-to-end encrypted
    val verified: Int = 0,          // in: 1 = origin signature verified, -1 = failed, 0 = unknown key
    val transport: String = "",     // "ble" / "wifi" of the last attempt or receipt
) {
    val destShort: String get() = if (destId.length >= 8) destId.substring(0, 8) else peerId
}

/** A peer we have seen at least once. */
class KnownPeer(val shortId: String, val label: String, val lastSeen: Long, val lastAddress: String, val fullId: String?)

/** A peer's public identity record (v0.5). */
class PeerKey(val shortId: String, val fullId: String, val pub: ByteArray, val name: String, val learnedAt: Long)

/** A large payload transfer (v0.5). */
class StoredTransfer(
    val tid: String,
    val direction: String,     // out / in
    val peerId: String,        // out: destination short | in: origin short
    val peerName: String,
    val kind: Int,             // Transfer.BLOB_TEXT / BLOB_FILE
    val name: String,
    val dataSize: Int,
    val blobLen: Int,
    val chunkCount: Int,
    val nextChunk: Int,        // out: next chunk index to send
    val receivedMask: ByteArray?, // in: one byte per chunk
    val status: String,
    val transport: String,
    val error: String,
    val timestamp: Long,
    val attempts: Int,
    val nextAttempt: Long,
    val sha256: String,
    val path: String,          // out: blob file; in: reassembly file / final data file
    val destId: String,
    val originId: String,
) {
    val progressPercent: Int get() = if (chunkCount == 0) 0 else when (direction) {
        Dir.OUT -> nextChunk * 100 / chunkCount
        else -> (receivedMask?.count { it.toInt() != 0 } ?: 0) * 100 / chunkCount
    }
}

/** Local SQLite store: messages, delivery/carry queue, known peers, peer keys, transfers. Plain SQLiteOpenHelper. */
/** A session's agreed terms plus what happened (v0.7). */
class StoredSession(
    val sessionHex: String, val role: String, val contract: ByteArray, val buyerSig: ByteArray?, val sellerSig: ByteArray?,
    val status: String, val startTs: Long, val endTs: Long, val bytesUp: Long, val bytesDown: Long, val lastSeq: Int,
    val lastCheckpoint: ByteArray?, val finalCentimes: Long, val disconnectReason: String, val peerShort: String,
)

class MessageStore(context: Context) : SQLiteOpenHelper(context, "proknet.db", null, 8) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE messages(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "msg_id TEXT NOT NULL," +
                "direction TEXT NOT NULL," +
                "peer_id TEXT NOT NULL," +
                "peer_name TEXT NOT NULL," +
                "text TEXT NOT NULL," +
                "ts INTEGER NOT NULL," +
                "status TEXT NOT NULL," +
                "attempts INTEGER NOT NULL DEFAULT 0," +
                "next_attempt INTEGER NOT NULL DEFAULT 0," +
                "last_error TEXT NOT NULL DEFAULT ''," +
                "delivered_at INTEGER NOT NULL DEFAULT 0," +
                "dest_id TEXT NOT NULL DEFAULT ''," +
                "origin_id TEXT NOT NULL DEFAULT ''," +
                "via TEXT NOT NULL DEFAULT ''," +
                "ttl INTEGER NOT NULL DEFAULT 0," +
                "hops INTEGER NOT NULL DEFAULT 0," +
                "payload BLOB," +
                "enc INTEGER NOT NULL DEFAULT 0," +
                "verified INTEGER NOT NULL DEFAULT 0," +
                "transport TEXT NOT NULL DEFAULT '')"
        )
        createIndexV3(db)
        createPeers(db)
        createV4(db)
        createV5(db)
    }

    private fun createV5(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS sessions(" +
                "session_id TEXT PRIMARY KEY," +
                "role TEXT NOT NULL," +
                "contract BLOB NOT NULL," +
                "buyer_sig BLOB," +
                "seller_sig BLOB," +
                "status TEXT NOT NULL," +
                "start_ts INTEGER NOT NULL," +
                "end_ts INTEGER NOT NULL DEFAULT 0," +
                "bytes_up INTEGER NOT NULL DEFAULT 0," +
                "bytes_down INTEGER NOT NULL DEFAULT 0," +
                "last_seq INTEGER NOT NULL DEFAULT 0," +
                "last_checkpoint BLOB," +
                "final_centimes INTEGER NOT NULL DEFAULT 0," +
                "disconnect_reason TEXT NOT NULL DEFAULT ''," +
                "peer_short TEXT NOT NULL DEFAULT '')"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS checkpoints(" +
                "session_id TEXT NOT NULL," +
                "seq INTEGER NOT NULL," +
                "body BLOB NOT NULL," +
                "seller_sig BLOB," +
                "buyer_sig BLOB," +
                "ts INTEGER NOT NULL," +
                "PRIMARY KEY(session_id, seq))"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS ledger(" +
                "id TEXT PRIMARY KEY," +
                "session_id TEXT NOT NULL," +
                "payer TEXT NOT NULL," +
                "recipient TEXT NOT NULL," +
                "amount INTEGER NOT NULL," +
                "reason TEXT NOT NULL," +
                "ts INTEGER NOT NULL," +
                "status TEXT NOT NULL," +
                "paid_at INTEGER NOT NULL DEFAULT 0," +
                "received_at INTEGER NOT NULL DEFAULT 0)"
        )
    }

    private fun createIndexV3(db: SQLiteDatabase) {
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_msg3 ON messages(msg_id, direction, peer_id)")
    }

    private fun createPeers(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS peers(" +
                "short_id TEXT PRIMARY KEY," +
                "label TEXT NOT NULL," +
                "last_seen INTEGER NOT NULL," +
                "last_address TEXT NOT NULL," +
                "full_id TEXT NOT NULL DEFAULT '')"
        )
    }

    private fun createV4(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS peer_keys(" +
                "short_id TEXT PRIMARY KEY," +
                "full_id TEXT NOT NULL," +
                "pub BLOB NOT NULL," +
                "name TEXT NOT NULL DEFAULT ''," +
                "learned_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS transfers(" +
                "tid TEXT PRIMARY KEY," +
                "direction TEXT NOT NULL," +
                "peer_id TEXT NOT NULL," +
                "peer_name TEXT NOT NULL," +
                "kind INTEGER NOT NULL," +
                "name TEXT NOT NULL," +
                "data_size INTEGER NOT NULL," +
                "blob_len INTEGER NOT NULL," +
                "chunk_count INTEGER NOT NULL," +
                "next_chunk INTEGER NOT NULL DEFAULT 0," +
                "received_mask BLOB," +
                "status TEXT NOT NULL," +
                "transport TEXT NOT NULL DEFAULT ''," +
                "error TEXT NOT NULL DEFAULT ''," +
                "ts INTEGER NOT NULL," +
                "attempts INTEGER NOT NULL DEFAULT 0," +
                "next_attempt INTEGER NOT NULL DEFAULT 0," +
                "sha256 TEXT NOT NULL DEFAULT ''," +
                "path TEXT NOT NULL DEFAULT ''," +
                "dest_id TEXT NOT NULL DEFAULT ''," +
                "origin_id TEXT NOT NULL DEFAULT ''," +
                "delivered_at INTEGER NOT NULL DEFAULT 0)"
        )
        createSettlements(db)
    }

    /**
     * v0.15.0: what a finished session owes. The primary key is the settlement id, which
     * is a hash of the signed session facts, so re-deriving the same obligation after a
     * restart, or receiving it twice from a sync, can never create a second row and can
     * never ask anybody to pay twice.
     */
    private fun createSettlements(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS settlements(" +
                "settlement_id TEXT PRIMARY KEY," +
                "session_id TEXT NOT NULL," +
                "buyer_id TEXT NOT NULL," +
                "seller_id TEXT NOT NULL," +
                "checkpoint_hash TEXT NOT NULL," +
                "gross INTEGER NOT NULL," +
                "seller_net INTEGER NOT NULL," +
                "prok_fee INTEGER NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "expires_at INTEGER NOT NULL," +
                "status TEXT NOT NULL," +
                "rail TEXT NOT NULL DEFAULT 'NONE'," +
                "payment_ref TEXT NOT NULL DEFAULT ''," +
                "note TEXT NOT NULL DEFAULT ''," +
                "synced_at INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_settlements_status ON settlements(status)")
        // v0.15.3: how far each obligation has got with the server. The evidence itself
        // is already durable in `sessions` and `checkpoints`, so this holds only the
        // queue state: a settlement survives a restart, a flat battery and a week
        // offline, and is submitted whenever the server is next reachable.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS settlement_sync(" +
                "settlement_id TEXT PRIMARY KEY," +
                "session_id TEXT NOT NULL," +
                "state TEXT NOT NULL," +
                "attempts INTEGER NOT NULL DEFAULT 0," +
                "last_attempt INTEGER NOT NULL DEFAULT 0," +
                "last_error TEXT NOT NULL DEFAULT ''," +
                "reported_at INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_state ON settlement_sync(state)")
        // v0.16.0: automatic cash-to-Mobile-Money verification.
        //
        // What is deliberately NOT here: no message bodies, no sender names, no inbox.
        // A receipt keeps the sha256 of the one message that matched and nothing else,
        // so the seller's private messages never become ProkNet's data.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS payment_expectations(" +
                "payment_id TEXT PRIMARY KEY," +
                "buyer_id TEXT NOT NULL," +
                "seller_id TEXT NOT NULL," +
                "rail TEXT NOT NULL," +
                "destination_hash TEXT NOT NULL," +
                "amount INTEGER NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "valid_from INTEGER NOT NULL," +
                "expires_at INTEGER NOT NULL," +
                "settlement_ids TEXT NOT NULL," +
                "state TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS payment_receipts(" +
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
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS destination_claims(" +
                "seller_id TEXT NOT NULL," +
                "rail TEXT NOT NULL," +
                "msisdn TEXT NOT NULL," +
                "version INTEGER NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "sig BLOB NOT NULL," +
                "PRIMARY KEY(seller_id, rail, version))"
        )
        // v0.15.0: where a seller wants to be paid. Local only: never advertised, never
        // gossiped, and only revealed to a buyer that owes a real settled obligation.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS payment_destinations(" +
                "peer_id TEXT PRIMARY KEY," +
                "rail TEXT NOT NULL," +
                "msisdn TEXT NOT NULL," +
                "holder TEXT NOT NULL DEFAULT ''," +
                "updated_at INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN next_attempt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN last_error TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN delivered_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE messages SET status='delivered' WHERE status='sent'")
            db.execSQL("UPDATE messages SET status='pending' WHERE status='sending' OR status='failed'")
            createPeers(db)
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN dest_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN origin_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN via TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN ttl INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN hops INTEGER NOT NULL DEFAULT 0")
            db.execSQL("DROP INDEX IF EXISTS idx_msg")
            createIndexV3(db)
            db.execSQL("UPDATE messages SET dest_id = peer_id || '000000000000000000000000', ttl = 3 WHERE direction='out' AND dest_id=''")
            createPeers(db)
            try { db.execSQL("ALTER TABLE peers ADD COLUMN full_id TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE messages ADD COLUMN payload BLOB")
            db.execSQL("ALTER TABLE messages ADD COLUMN enc INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN verified INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN transport TEXT NOT NULL DEFAULT ''")
            createV4(db)
            // v0.5 sends only encrypted packets; plaintext rows still waiting cannot be sent as they are
            // (no key material stored with them). They are marked failed with an explanation, not lost.
            db.execSQL("UPDATE messages SET status='failed', last_error='v0.5: pending plaintext row cannot be encrypted after the fact; resend' WHERE direction='out' AND status IN ('pending','sending')")
            db.execSQL("UPDATE messages SET status='expired', last_error='v0.5: carried plaintext packet dropped on upgrade' WHERE direction='carry' AND status IN ('carrying','forwarding')")
        }
        if (oldVersion < 5) createV5(db)
        if (oldVersion < 6) createSettlements(db)
        if (oldVersion < 7) createSettlements(db)
        if (oldVersion < 8) createSettlements(db)
    }

    // ---- sessions / checkpoints / ledger (v0.7) ---------------------------------------------------

    fun insertSession(s: StoredSession): Boolean {
        val cv = ContentValues().apply {
            put("session_id", s.sessionHex); put("role", s.role); put("contract", s.contract)
            if (s.buyerSig != null) put("buyer_sig", s.buyerSig); if (s.sellerSig != null) put("seller_sig", s.sellerSig)
            put("status", s.status); put("start_ts", s.startTs); put("end_ts", s.endTs); put("bytes_up", s.bytesUp); put("bytes_down", s.bytesDown)
            put("last_seq", s.lastSeq); if (s.lastCheckpoint != null) put("last_checkpoint", s.lastCheckpoint)
            put("final_centimes", s.finalCentimes); put("disconnect_reason", s.disconnectReason); put("peer_short", s.peerShort)
        }
        return writableDatabase.insertWithOnConflict("sessions", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun updateSession(sessionHex: String, status: String? = null, endTs: Long? = null, bytesUp: Long? = null, bytesDown: Long? = null, lastSeq: Int? = null,
                      lastCheckpoint: ByteArray? = null, finalCentimes: Long? = null, reason: String? = null, buyerSig: ByteArray? = null, sellerSig: ByteArray? = null) {
        val cv = ContentValues()
        if (status != null) cv.put("status", status); if (endTs != null) cv.put("end_ts", endTs)
        if (bytesUp != null) cv.put("bytes_up", bytesUp); if (bytesDown != null) cv.put("bytes_down", bytesDown)
        if (lastSeq != null) cv.put("last_seq", lastSeq); if (lastCheckpoint != null) cv.put("last_checkpoint", lastCheckpoint)
        if (finalCentimes != null) cv.put("final_centimes", finalCentimes); if (reason != null) cv.put("disconnect_reason", reason)
        if (buyerSig != null) cv.put("buyer_sig", buyerSig); if (sellerSig != null) cv.put("seller_sig", sellerSig)
        if (cv.size() > 0) writableDatabase.update("sessions", cv, "session_id=?", arrayOf(sessionHex))
    }

    fun session(sessionHex: String): StoredSession? {
        readableDatabase.rawQuery("SELECT * FROM sessions WHERE session_id=?", arrayOf(sessionHex)).use { c -> return if (c.moveToFirst()) sessionRow(c) else null }
    }

    fun sessions(limit: Int = 50): List<StoredSession> {
        val out = ArrayList<StoredSession>()
        readableDatabase.rawQuery("SELECT * FROM sessions ORDER BY start_ts DESC LIMIT " + limit, null).use { c -> while (c.moveToNext()) out.add(sessionRow(c)) }
        return out
    }

    fun sessionIds(): Set<String> {
        val out = HashSet<String>()
        readableDatabase.rawQuery("SELECT session_id FROM sessions", null).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    private fun sessionRow(c: Cursor) = StoredSession(
        c.getString(c.getColumnIndexOrThrow("session_id")), c.getString(c.getColumnIndexOrThrow("role")), c.getBlob(c.getColumnIndexOrThrow("contract")),
        c.getColumnIndex("buyer_sig").let { if (it >= 0 && !c.isNull(it)) c.getBlob(it) else null },
        c.getColumnIndex("seller_sig").let { if (it >= 0 && !c.isNull(it)) c.getBlob(it) else null },
        c.getString(c.getColumnIndexOrThrow("status")), c.getLong(c.getColumnIndexOrThrow("start_ts")), c.getLong(c.getColumnIndexOrThrow("end_ts")),
        c.getLong(c.getColumnIndexOrThrow("bytes_up")), c.getLong(c.getColumnIndexOrThrow("bytes_down")), c.getInt(c.getColumnIndexOrThrow("last_seq")),
        c.getColumnIndex("last_checkpoint").let { if (it >= 0 && !c.isNull(it)) c.getBlob(it) else null },
        c.getLong(c.getColumnIndexOrThrow("final_centimes")), c.getString(c.getColumnIndexOrThrow("disconnect_reason")), c.getString(c.getColumnIndexOrThrow("peer_short")),
    )

    /** Returns false when (session, seq) already exists: duplicates are never stored twice. */
    fun insertCheckpoint(sessionHex: String, seq: Int, body: ByteArray, sellerSig: ByteArray?, buyerSig: ByteArray?, ts: Long): Boolean {
        val cv = ContentValues().apply {
            put("session_id", sessionHex); put("seq", seq); put("body", body); put("ts", ts)
            if (sellerSig != null) put("seller_sig", sellerSig); if (buyerSig != null) put("buyer_sig", buyerSig)
        }
        return writableDatabase.insertWithOnConflict("checkpoints", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun setCheckpointBuyerSig(sessionHex: String, seq: Int, buyerSig: ByteArray) {
        val cv = ContentValues().apply { put("buyer_sig", buyerSig) }
        writableDatabase.update("checkpoints", cv, "session_id=? AND seq=?", arrayOf(sessionHex, seq.toString()))
    }

    fun checkpointCount(sessionHex: String): Long = DatabaseUtils.queryNumEntries(readableDatabase, "checkpoints", "session_id=?", arrayOf(sessionHex))

    // ---- v0.15.0: settlement obligations --------------------------------------------------------

    /**
     * Insert or update one obligation. Inserting the same settlement id twice is a no-op
     * on the amounts: the money a session owes is fixed by what was signed, and nothing
     * arriving later may change it.
     */
    fun saveSettlement(o: Settlement.Obligation, syncedAt: Long = 0) {
        val cv = ContentValues().apply {
            put("settlement_id", o.settlementId); put("session_id", o.sessionHex)
            put("buyer_id", o.buyerId); put("seller_id", o.sellerId); put("checkpoint_hash", o.finalCheckpointHash)
            put("gross", o.grossCentimes); put("seller_net", o.sellerNetCentimes); put("prok_fee", o.prokFeeCentimes)
            put("created_at", o.createdAt); put("expires_at", o.expiresAt)
            put("status", o.status.name); put("rail", o.rail.name); put("payment_ref", o.paymentReference); put("note", o.note)
            put("synced_at", syncedAt)
        }
        val db = writableDatabase
        if (db.insertWithOnConflict("settlements", null, cv, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
            // already known: only the payment state may move, never the amounts
            val upd = ContentValues().apply {
                put("status", o.status.name); put("rail", o.rail.name)
                put("payment_ref", o.paymentReference); put("note", o.note); put("synced_at", syncedAt)
            }
            db.update("settlements", upd, "settlement_id=?", arrayOf(o.settlementId))
        }
    }

    /** True when this obligation was new. Used to prove one session books one obligation. */
    fun insertSettlementIfNew(o: Settlement.Obligation): Boolean {
        val cv = ContentValues().apply {
            put("settlement_id", o.settlementId); put("session_id", o.sessionHex)
            put("buyer_id", o.buyerId); put("seller_id", o.sellerId); put("checkpoint_hash", o.finalCheckpointHash)
            put("gross", o.grossCentimes); put("seller_net", o.sellerNetCentimes); put("prok_fee", o.prokFeeCentimes)
            put("created_at", o.createdAt); put("expires_at", o.expiresAt)
            put("status", o.status.name); put("rail", o.rail.name); put("payment_ref", o.paymentReference); put("note", o.note)
        }
        return writableDatabase.insertWithOnConflict("settlements", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun settlement(id: String): Settlement.Obligation? =
        settlements("settlement_id=?", arrayOf(id), 1).firstOrNull()

    fun settlements(limit: Int = 200): List<Settlement.Obligation> = settlements(null, null, limit)

    private fun settlements(where: String?, args: Array<String>?, limit: Int): List<Settlement.Obligation> {
        val out = ArrayList<Settlement.Obligation>()
        val c = readableDatabase.query("settlements", null, where, args, null, null, "created_at DESC", limit.toString())
        c.use {
            while (it.moveToNext()) {
                out.add(Settlement.Obligation(
                    it.getString(it.getColumnIndexOrThrow("settlement_id")),
                    it.getString(it.getColumnIndexOrThrow("session_id")),
                    it.getString(it.getColumnIndexOrThrow("buyer_id")),
                    it.getString(it.getColumnIndexOrThrow("seller_id")),
                    it.getString(it.getColumnIndexOrThrow("checkpoint_hash")),
                    it.getLong(it.getColumnIndexOrThrow("gross")),
                    it.getLong(it.getColumnIndexOrThrow("seller_net")),
                    it.getLong(it.getColumnIndexOrThrow("prok_fee")),
                    it.getLong(it.getColumnIndexOrThrow("created_at")),
                    it.getLong(it.getColumnIndexOrThrow("expires_at")),
                    runCatching { Settlement.Status.valueOf(it.getString(it.getColumnIndexOrThrow("status"))) }.getOrDefault(Settlement.Status.PENDING),
                    runCatching { Settlement.Rail.valueOf(it.getString(it.getColumnIndexOrThrow("rail"))) }.getOrDefault(Settlement.Rail.NONE),
                    it.getString(it.getColumnIndexOrThrow("payment_ref")),
                    it.getString(it.getColumnIndexOrThrow("note"))))
            }
        }
        return out
    }

    /** Obligations that still need something to happen, oldest first, for the sync. */
    fun unsettled(limit: Int = 100): List<Settlement.Obligation> =
        settlements(limit).filter { Settlement.isOutstanding(it.status) }.sortedBy { it.createdAt }

    fun confirmedSettlementCount(myId: String): Int =
        settlements(2_000).count { it.buyerId == myId && it.status == Settlement.Status.CONFIRMED }

    // ---- v0.15.0: where a seller wants to be paid ------------------------------------------------

    fun savePaymentDestination(peerId: String, d: PaymentRails.Destination, now: Long) {
        val cv = ContentValues().apply {
            put("peer_id", peerId); put("rail", d.rail.name); put("msisdn", d.msisdn); put("holder", d.holderName); put("updated_at", now)
        }
        writableDatabase.insertWithOnConflict("payment_destinations", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun paymentDestination(peerId: String): PaymentRails.Destination? {
        val c = readableDatabase.query("payment_destinations", null, "peer_id=?", arrayOf(peerId), null, null, null, "1")
        c.use {
            if (!it.moveToNext()) return null
            val rail = runCatching { Settlement.Rail.valueOf(it.getString(it.getColumnIndexOrThrow("rail"))) }.getOrDefault(Settlement.Rail.NONE)
            return PaymentRails.Destination(rail, it.getString(it.getColumnIndexOrThrow("msisdn")), it.getString(it.getColumnIndexOrThrow("holder")))
        }
    }

    // ---- v0.15.3: the evidence, read back long after the session ---------------------------------

    /**
     * The closing checkpoint for a session, with both signatures. Everything the server's
     * verifier needs was already being stored during the session; this reads it back.
     */
    class StoredCheckpoint(val seq: Int, val body: ByteArray, val sellerSig: ByteArray?, val buyerSig: ByteArray?)

    fun finalCheckpoint(sessionHex: String): StoredCheckpoint? {
        val c = readableDatabase.query("checkpoints", null, "session_id=?", arrayOf(sessionHex),
            null, null, "seq DESC", "1")
        c.use {
            if (!it.moveToNext()) return null
            return StoredCheckpoint(
                it.getInt(it.getColumnIndexOrThrow("seq")),
                it.getBlob(it.getColumnIndexOrThrow("body")),
                it.getBlobOrNull(it, "seller_sig"),
                it.getBlobOrNull(it, "buyer_sig"))
        }
    }

    private fun android.database.Cursor.getBlobOrNull(c: android.database.Cursor, name: String): ByteArray? {
        val i = c.getColumnIndexOrThrow(name)
        return if (c.isNull(i)) null else c.getBlob(i)
    }

    // ---- v0.15.3: the settlement sync queue ------------------------------------------------------

    class SyncRow(val settlementId: String, val sessionHex: String, val state: Evidence.Sync,
                  val attempts: Int, val lastAttempt: Long, val lastError: String)

    /** Queue an obligation for submission. Idempotent: an existing row is left alone. */
    fun enqueueSync(settlementId: String, sessionHex: String) {
        val cv = ContentValues().apply {
            put("settlement_id", settlementId); put("session_id", sessionHex)
            put("state", Evidence.Sync.PENDING.name)
        }
        writableDatabase.insertWithOnConflict("settlement_sync", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun syncRow(settlementId: String): SyncRow? {
        val c = readableDatabase.query("settlement_sync", null, "settlement_id=?", arrayOf(settlementId), null, null, null, "1")
        c.use {
            if (!it.moveToNext()) return null
            return SyncRow(
                it.getString(it.getColumnIndexOrThrow("settlement_id")),
                it.getString(it.getColumnIndexOrThrow("session_id")),
                runCatching { Evidence.Sync.valueOf(it.getString(it.getColumnIndexOrThrow("state"))) }.getOrDefault(Evidence.Sync.PENDING),
                it.getInt(it.getColumnIndexOrThrow("attempts")),
                it.getLong(it.getColumnIndexOrThrow("last_attempt")),
                it.getString(it.getColumnIndexOrThrow("last_error")))
        }
    }

    /** Everything still worth trying, oldest first. */
    fun pendingSync(): List<SyncRow> {
        val out = ArrayList<SyncRow>()
        val c = readableDatabase.query("settlement_sync", null, "state=?", arrayOf(Evidence.Sync.PENDING.name),
            null, null, "last_attempt ASC", "50")
        c.use {
            while (it.moveToNext()) out.add(SyncRow(
                it.getString(it.getColumnIndexOrThrow("settlement_id")),
                it.getString(it.getColumnIndexOrThrow("session_id")),
                Evidence.Sync.PENDING,
                it.getInt(it.getColumnIndexOrThrow("attempts")),
                it.getLong(it.getColumnIndexOrThrow("last_attempt")),
                it.getString(it.getColumnIndexOrThrow("last_error"))))
        }
        return out
    }

    fun markSync(settlementId: String, state: Evidence.Sync, attempts: Int, at: Long, error: String = "") {
        val cv = ContentValues().apply {
            put("state", state.name); put("attempts", attempts); put("last_attempt", at); put("last_error", error)
            if (state == Evidence.Sync.REPORTED) put("reported_at", at)
        }
        writableDatabase.update("settlement_sync", cv, "settlement_id=?", arrayOf(settlementId))
    }

    fun syncCounts(): Triple<Int, Int, Int> {
        fun n(state: Evidence.Sync) = DatabaseUtils.longForQuery(readableDatabase,
            "SELECT COUNT(*) FROM settlement_sync WHERE state=?", arrayOf(state.name)).toInt()
        return Triple(n(Evidence.Sync.PENDING), n(Evidence.Sync.REPORTED), n(Evidence.Sync.DISPUTED))
    }

    // ---- v0.16.0: payment expectations, receipts and destinations --------------------------------

    fun saveExpectation(e: PaymentExpectation.Expectation) {
        val cv = ContentValues().apply {
            put("payment_id", e.paymentId); put("buyer_id", e.buyerId); put("seller_id", e.sellerId)
            put("rail", e.rail.name); put("destination_hash", e.destinationHash); put("amount", e.amountCentimes)
            put("created_at", e.createdAt); put("valid_from", e.validFrom); put("expires_at", e.expiresAt)
            put("settlement_ids", e.includedSettlementIds.joinToString(",")); put("state", e.state.name)
        }
        writableDatabase.insertWithOnConflict("payment_expectations", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun expectations(limit: Int = 50): List<PaymentExpectation.Expectation> {
        val out = ArrayList<PaymentExpectation.Expectation>()
        val c = readableDatabase.query("payment_expectations", null, null, null, null, null, "created_at DESC", limit.toString())
        c.use {
            while (it.moveToNext()) out.add(PaymentExpectation.Expectation(
                it.getString(it.getColumnIndexOrThrow("payment_id")),
                it.getString(it.getColumnIndexOrThrow("buyer_id")),
                it.getString(it.getColumnIndexOrThrow("seller_id")),
                runCatching { Settlement.Rail.valueOf(it.getString(it.getColumnIndexOrThrow("rail"))) }.getOrDefault(Settlement.Rail.NONE),
                it.getString(it.getColumnIndexOrThrow("destination_hash")),
                it.getLong(it.getColumnIndexOrThrow("amount")),
                it.getLong(it.getColumnIndexOrThrow("created_at")),
                it.getLong(it.getColumnIndexOrThrow("valid_from")),
                it.getLong(it.getColumnIndexOrThrow("expires_at")),
                it.getString(it.getColumnIndexOrThrow("settlement_ids")).split(",").filter { s -> s.isNotEmpty() },
                runCatching { PaymentExpectation.State.valueOf(it.getString(it.getColumnIndexOrThrow("state"))) }.getOrDefault(PaymentExpectation.State.WAITING)))
        }
        return out
    }

    fun saveReceipt(r: DeviceReceipt.Receipt, sig: ByteArray) {
        val cv = ContentValues().apply {
            put("payment_id", r.paymentId); put("seller_id", r.sellerId); put("buyer_id", r.buyerId)
            put("rail", r.rail.name); put("destination_hash", r.destinationHash)
            put("expected", r.expectedCentimes); put("observed", r.observedCentimes); put("observed_at", r.observedAt)
            put("source", r.source.name); put("source_package", r.sourcePackage)
            put("evidence_hash", r.messageEvidenceHash); put("parser_version", r.parserVersion)
            put("confidence", r.confidence.name); put("settlement_ids", r.matchedSettlementIds.joinToString(","))
            put("reference", r.reference); put("sig", sig)
        }
        writableDatabase.insertWithOnConflict("payment_receipts", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun hasReceipt(paymentId: String): Boolean = DatabaseUtils.longForQuery(readableDatabase,
        "SELECT COUNT(*) FROM payment_receipts WHERE payment_id=?", arrayOf(paymentId)) > 0

    /**
     * v0.16.1: payments this identity made AS A BUYER. This is the only counter that may
     * raise a credit limit: selling Internet is not evidence that you pay your debts.
     */
    fun buyerReceiptCount(myId: String): Int = DatabaseUtils.longForQuery(readableDatabase,
        "SELECT COUNT(*) FROM payment_receipts WHERE buyer_id=?", arrayOf(myId)).toInt()

    /** Kept separate, for seller reputation later. Never feeds buyer credit. */
    fun sellerReceiptCount(myId: String): Int = DatabaseUtils.longForQuery(readableDatabase,
        "SELECT COUNT(*) FROM payment_receipts WHERE seller_id=?", arrayOf(myId)).toInt()

    /** Receipts this phone signed as the seller, with their signatures, for redelivery. */
    fun receiptsFrom(sellerId: String, limit: Int = 50): List<Pair<DeviceReceipt.Receipt, ByteArray>> {
        val out = ArrayList<Pair<DeviceReceipt.Receipt, ByteArray>>()
        val c = readableDatabase.query("payment_receipts", null, "seller_id=?", arrayOf(sellerId),
            null, null, "observed_at DESC", limit.toString())
        c.use {
            while (it.moveToNext()) out.add(DeviceReceipt.Receipt(
                it.getString(it.getColumnIndexOrThrow("payment_id")),
                it.getString(it.getColumnIndexOrThrow("seller_id")),
                it.getString(it.getColumnIndexOrThrow("buyer_id")),
                runCatching { Settlement.Rail.valueOf(it.getString(it.getColumnIndexOrThrow("rail"))) }.getOrDefault(Settlement.Rail.NONE),
                it.getString(it.getColumnIndexOrThrow("destination_hash")),
                it.getLong(it.getColumnIndexOrThrow("expected")),
                it.getLong(it.getColumnIndexOrThrow("observed")),
                it.getLong(it.getColumnIndexOrThrow("observed_at")),
                runCatching { DeviceReceipt.Source.valueOf(it.getString(it.getColumnIndexOrThrow("source"))) }.getOrDefault(DeviceReceipt.Source.DEFAULT_SMS_NOTIFICATION),
                it.getString(it.getColumnIndexOrThrow("source_package")),
                it.getString(it.getColumnIndexOrThrow("evidence_hash")),
                it.getInt(it.getColumnIndexOrThrow("parser_version")),
                runCatching { DeviceReceipt.Confidence.valueOf(it.getString(it.getColumnIndexOrThrow("confidence"))) }.getOrDefault(DeviceReceipt.Confidence.DEVICE_NOTIFICATION_VERIFIED),
                it.getString(it.getColumnIndexOrThrow("settlement_ids")).split(",").filter { s -> s.isNotEmpty() },
                it.getString(it.getColumnIndexOrThrow("reference"))) to it.getBlob(it.getColumnIndexOrThrow("sig")))
        }
        return out
    }

    fun receiptDelivered(paymentId: String): Boolean = DatabaseUtils.longForQuery(readableDatabase,
        "SELECT COUNT(*) FROM payment_receipts WHERE payment_id=? AND delivered=1", arrayOf(paymentId)) > 0

    fun markReceiptDelivered(paymentId: String) {
        val cv = ContentValues().apply { put("delivered", 1) }
        writableDatabase.update("payment_receipts", cv, "payment_id=?", arrayOf(paymentId))
    }

    /** The signature over our own destination claim, so it can be handed to a buyer. */
    fun destinationSig(sellerId: String): ByteArray? {
        val c = readableDatabase.query("destination_claims", arrayOf("sig"), "seller_id=?", arrayOf(sellerId),
            null, null, "version DESC", "1")
        c.use { return if (it.moveToNext()) it.getBlob(0) else null }
    }

    /** The claim before [version], which stays active during a cooling period. */
    fun previousDestinationClaim(sellerId: String, version: Int): DestinationClaim.Claim? {
        val c = readableDatabase.query("destination_claims", null, "seller_id=? AND version<?",
            arrayOf(sellerId, version.toString()), null, null, "version DESC", "1")
        c.use {
            if (!it.moveToNext()) return null
            return DestinationClaim.Claim(
                it.getString(it.getColumnIndexOrThrow("seller_id")),
                runCatching { Settlement.Rail.valueOf(it.getString(it.getColumnIndexOrThrow("rail"))) }.getOrDefault(Settlement.Rail.NONE),
                it.getString(it.getColumnIndexOrThrow("msisdn")),
                it.getInt(it.getColumnIndexOrThrow("version")),
                it.getLong(it.getColumnIndexOrThrow("created_at")))
        }
    }

    fun saveDestinationClaim(c: DestinationClaim.Claim, sig: ByteArray) {
        val cv = ContentValues().apply {
            put("seller_id", c.sellerId); put("rail", c.rail.name); put("msisdn", c.normalized)
            put("version", c.version); put("created_at", c.createdAt); put("sig", sig)
        }
        writableDatabase.insertWithOnConflict("destination_claims", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun destinationClaim(sellerId: String): DestinationClaim.Claim? {
        val c = readableDatabase.query("destination_claims", null, "seller_id=?", arrayOf(sellerId),
            null, null, "version DESC", "1")
        c.use {
            if (!it.moveToNext()) return null
            return DestinationClaim.Claim(
                it.getString(it.getColumnIndexOrThrow("seller_id")),
                runCatching { Settlement.Rail.valueOf(it.getString(it.getColumnIndexOrThrow("rail"))) }.getOrDefault(Settlement.Rail.NONE),
                it.getString(it.getColumnIndexOrThrow("msisdn")),
                it.getInt(it.getColumnIndexOrThrow("version")),
                it.getLong(it.getColumnIndexOrThrow("created_at")))
        }
    }

    fun insertLedger(e: Market.Entry): Boolean {
        val cv = ContentValues().apply {
            put("id", e.id); put("session_id", e.sessionHex); put("payer", e.payer); put("recipient", e.recipient); put("amount", e.amountCentimes)
            put("reason", e.reason); put("ts", e.ts); put("status", e.status); put("paid_at", e.paidAt); put("received_at", e.receivedAt)
        }
        return writableDatabase.insertWithOnConflict("ledger", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun updateLedger(e: Market.Entry) {
        val cv = ContentValues().apply { put("status", e.status); put("paid_at", e.paidAt); put("received_at", e.receivedAt) }
        writableDatabase.update("ledger", cv, "id=?", arrayOf(e.id))
    }

    fun ledger(limit: Int = 200): List<Market.Entry> {
        val out = ArrayList<Market.Entry>()
        readableDatabase.rawQuery("SELECT id,session_id,payer,recipient,amount,reason,ts,status,paid_at,received_at FROM ledger ORDER BY ts DESC LIMIT " + limit, null).use { c ->
            while (c.moveToNext()) out.add(Market.Entry(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getString(5), c.getLong(6), c.getString(7), c.getLong(8), c.getLong(9)))
        }
        return out
    }

    fun ledgerEntry(id: String): Market.Entry? = ledger(1000).firstOrNull { it.id == id }

    /** Relay activity already happening in the network: packets this phone carried and delivered for others. */
    fun relayStats(): Pair<Long, Long> {
        val n = DatabaseUtils.queryNumEntries(readableDatabase, "messages", "direction='carry' AND status='forwarded'")
        var bytes = 0L
        readableDatabase.rawQuery("SELECT COALESCE(SUM(LENGTH(payload)),0) FROM messages WHERE direction='carry' AND status='forwarded'", null).use { c -> if (c.moveToFirst()) bytes = c.getLong(0) }
        return n to bytes
    }

    // ---- messages ------------------------------------------------------------------------------

    /** Returns false if this (msgId, direction, peerId) already exists (duplicate). */
    fun insert(m: StoredMessage): Boolean {
        val cv = ContentValues().apply {
            put("msg_id", m.msgId)
            put("direction", m.direction)
            put("peer_id", m.peerId)
            put("peer_name", m.peerName)
            put("text", m.text)
            put("ts", m.timestamp)
            put("status", m.status)
            put("attempts", m.attempts)
            put("next_attempt", m.nextAttempt)
            put("last_error", m.lastError)
            put("delivered_at", m.deliveredAt)
            put("dest_id", m.destId)
            put("origin_id", m.originId)
            put("via", m.via)
            put("ttl", m.ttl)
            put("hops", m.hops)
            if (m.payload != null) put("payload", m.payload)
            put("enc", m.enc)
            put("verified", m.verified)
            put("transport", m.transport)
        }
        return writableDatabase.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    /** Does any row for this message (from this origin) exist, in any direction? Used for duplicate detection. */
    fun knows(msgId: String, originShort: String): String? {
        readableDatabase.rawQuery(
            "SELECT direction, status FROM messages WHERE msg_id=? AND " +
                "((direction IN ('in','carry') AND peer_id=?) OR (direction='out' AND substr(origin_id,1,8)=?)) LIMIT 1",
            arrayOf(msgId, originShort, originShort)
        ).use { c -> return if (c.moveToFirst()) c.getString(0) + "/" + c.getString(1) else null }
    }

    fun setStatus(
        msgId: String, status: String, error: String = "", nextAttempt: Long = 0,
        bumpAttempts: Boolean = false, direction: String = Dir.OUT, via: String? = null, transport: String? = null,
    ) {
        val sql = "UPDATE messages SET status=?, last_error=?, next_attempt=?" +
            (if (bumpAttempts) ", attempts=attempts+1" else "") +
            (if (status == MsgStatus.DELIVERED || status == MsgStatus.FORWARDED) ", delivered_at=" + System.currentTimeMillis() else "") +
            (if (via != null) ", via='" + via.replace("'", "") + "'" else "") +
            (if (transport != null) ", transport='" + transport.replace("'", "") + "'" else "") +
            " WHERE msg_id=? AND direction=?"
        writableDatabase.execSQL(sql, arrayOf(status, error, nextAttempt, msgId, direction))
    }

    fun pending(): List<StoredMessage> =
        query("SELECT * FROM messages WHERE direction='out' AND status='pending' ORDER BY ts ASC, id ASC", emptyArray())

    fun carrying(): List<StoredMessage> =
        query("SELECT * FROM messages WHERE direction='carry' AND status='carrying' ORDER BY ts ASC, id ASC", emptyArray())

    fun pendingCount(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "messages", "direction='out' AND status='pending'")
    fun carryingCount(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "messages", "direction='carry' AND status='carrying'")

    fun recoverInterrupted(): Int {
        val db = writableDatabase
        db.execSQL("UPDATE messages SET status='pending', next_attempt=0 WHERE direction='out' AND status='sending'")
        val a = DatabaseUtils.longForQuery(db, "SELECT changes()", null).toInt()
        db.execSQL("UPDATE messages SET status='carrying', next_attempt=0 WHERE direction='carry' AND status='forwarding'")
        val b = DatabaseUtils.longForQuery(db, "SELECT changes()", null).toInt()
        db.execSQL("UPDATE transfers SET status='pending', next_attempt=0 WHERE direction='out' AND status='sending'")
        val c = DatabaseUtils.longForQuery(db, "SELECT changes()", null).toInt()
        return a + b + c
    }

    fun expireOlderThan(cutoffTs: Long): List<StoredMessage> {
        val victims = query(
            "SELECT * FROM messages WHERE ((direction='out' AND status='pending') OR (direction='carry' AND status='carrying')) AND ts<?",
            arrayOf(cutoffTs.toString())
        )
        if (victims.isNotEmpty()) {
            writableDatabase.execSQL(
                "UPDATE messages SET status='expired', last_error='ttl exceeded' WHERE ((direction='out' AND status='pending') OR (direction='carry' AND status='carrying')) AND ts<?",
                arrayOf(cutoffTs)
            )
        }
        return victims
    }

    fun retryAllNow() {
        writableDatabase.execSQL("UPDATE messages SET next_attempt=0 WHERE (direction='out' AND status='pending') OR (direction='carry' AND status='carrying')")
        writableDatabase.execSQL("UPDATE transfers SET next_attempt=0 WHERE direction='out' AND status='pending'")
    }

    fun retryNowFor(peerShort: String): Int {
        val db = writableDatabase
        db.execSQL(
            "UPDATE messages SET next_attempt=0 WHERE ((direction='out' AND status='pending') OR (direction='carry' AND status='carrying')) AND substr(dest_id,1,8)=?",
            arrayOf(peerShort)
        )
        db.execSQL("UPDATE transfers SET next_attempt=0 WHERE direction='out' AND status='pending' AND peer_id=?", arrayOf(peerShort))
        return DatabaseUtils.queryNumEntries(
            db, "messages",
            "((direction='out' AND status='pending') OR (direction='carry' AND status='carrying')) AND substr(dest_id,1,8)=?", arrayOf(peerShort)
        ).toInt()
    }

    fun recent(limit: Int = 100): List<StoredMessage> =
        query("SELECT * FROM messages ORDER BY ts DESC, id DESC LIMIT " + limit, emptyArray()).reversed()

    fun count(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "messages")

    private fun query(sql: String, args: Array<String>): List<StoredMessage> {
        val out = ArrayList<StoredMessage>()
        readableDatabase.rawQuery(sql, args).use { c -> while (c.moveToNext()) out.add(row(c)) }
        return out
    }

    private fun row(c: Cursor) = StoredMessage(
        rowId = c.getLong(c.getColumnIndexOrThrow("id")),
        msgId = c.getString(c.getColumnIndexOrThrow("msg_id")),
        direction = c.getString(c.getColumnIndexOrThrow("direction")),
        peerId = c.getString(c.getColumnIndexOrThrow("peer_id")),
        peerName = c.getString(c.getColumnIndexOrThrow("peer_name")),
        text = c.getString(c.getColumnIndexOrThrow("text")),
        timestamp = c.getLong(c.getColumnIndexOrThrow("ts")),
        status = c.getString(c.getColumnIndexOrThrow("status")),
        attempts = c.getInt(c.getColumnIndexOrThrow("attempts")),
        nextAttempt = c.getLong(c.getColumnIndexOrThrow("next_attempt")),
        lastError = c.getString(c.getColumnIndexOrThrow("last_error")),
        deliveredAt = c.getLong(c.getColumnIndexOrThrow("delivered_at")),
        destId = c.getString(c.getColumnIndexOrThrow("dest_id")),
        originId = c.getString(c.getColumnIndexOrThrow("origin_id")),
        via = c.getString(c.getColumnIndexOrThrow("via")),
        ttl = c.getInt(c.getColumnIndexOrThrow("ttl")),
        hops = c.getInt(c.getColumnIndexOrThrow("hops")),
        payload = c.getColumnIndex("payload").let { if (it >= 0 && !c.isNull(it)) c.getBlob(it) else null },
        enc = c.getInt(c.getColumnIndexOrThrow("enc")),
        verified = c.getInt(c.getColumnIndexOrThrow("verified")),
        transport = c.getString(c.getColumnIndexOrThrow("transport")),
    )

    // ---- known peers ---------------------------------------------------------------------------

    fun rememberPeer(shortId: String, label: String, address: String, seenAt: Long, fullId: String?) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("short_id", shortId); put("label", label); put("last_seen", seenAt); put("last_address", address)
        }
        if (fullId != null) cv.put("full_id", fullId)
        if (db.update("peers", cv, "short_id=?", arrayOf(shortId)) == 0) {
            if (fullId == null) cv.put("full_id", "")
            db.insertWithOnConflict("peers", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun knownPeers(): List<KnownPeer> {
        val out = ArrayList<KnownPeer>()
        readableDatabase.rawQuery("SELECT short_id,label,last_seen,last_address,full_id FROM peers ORDER BY last_seen DESC", null).use { c ->
            while (c.moveToNext()) {
                val full = c.getString(4)
                out.add(KnownPeer(c.getString(0), c.getString(1), c.getLong(2), c.getString(3), if (full.isNullOrEmpty()) null else full))
            }
        }
        return out
    }

    // ---- peer keys (v0.5) ----------------------------------------------------------------------

    fun savePeerKey(shortId: String, fullId: String, pub: ByteArray, name: String) {
        val cv = ContentValues().apply {
            put("short_id", shortId); put("full_id", fullId); put("pub", pub); put("name", name); put("learned_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("peer_keys", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun peerKey(shortId: String): PeerKey? {
        readableDatabase.rawQuery("SELECT short_id,full_id,pub,name,learned_at FROM peer_keys WHERE short_id=?", arrayOf(shortId)).use { c ->
            return if (c.moveToFirst()) PeerKey(c.getString(0), c.getString(1), c.getBlob(2), c.getString(3), c.getLong(4)) else null
        }
    }

    fun peerKeyCount(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "peer_keys")

    // ---- transfers (v0.5) ----------------------------------------------------------------------

    fun insertTransfer(t: StoredTransfer): Boolean {
        val cv = ContentValues().apply {
            put("tid", t.tid); put("direction", t.direction); put("peer_id", t.peerId); put("peer_name", t.peerName)
            put("kind", t.kind); put("name", t.name); put("data_size", t.dataSize); put("blob_len", t.blobLen)
            put("chunk_count", t.chunkCount); put("next_chunk", t.nextChunk)
            if (t.receivedMask != null) put("received_mask", t.receivedMask)
            put("status", t.status); put("transport", t.transport); put("error", t.error); put("ts", t.timestamp)
            put("attempts", t.attempts); put("next_attempt", t.nextAttempt); put("sha256", t.sha256); put("path", t.path)
            put("dest_id", t.destId); put("origin_id", t.originId)
        }
        return writableDatabase.insertWithOnConflict("transfers", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun transfer(tid: String): StoredTransfer? {
        readableDatabase.rawQuery("SELECT * FROM transfers WHERE tid=?", arrayOf(tid)).use { c ->
            return if (c.moveToFirst()) transferRow(c) else null
        }
    }

    fun transfers(limit: Int = 50): List<StoredTransfer> {
        val out = ArrayList<StoredTransfer>()
        readableDatabase.rawQuery("SELECT * FROM transfers ORDER BY ts DESC LIMIT " + limit, null).use { c -> while (c.moveToNext()) out.add(transferRow(c)) }
        return out
    }

    fun pendingTransfers(): List<StoredTransfer> {
        val out = ArrayList<StoredTransfer>()
        readableDatabase.rawQuery("SELECT * FROM transfers WHERE direction='out' AND status='pending' ORDER BY ts ASC", null).use { c -> while (c.moveToNext()) out.add(transferRow(c)) }
        return out
    }

    fun updateTransfer(
        tid: String, status: String? = null, nextChunk: Int? = null, receivedMask: ByteArray? = null, error: String? = null,
        transport: String? = null, nextAttempt: Long? = null, bumpAttempts: Boolean = false, path: String? = null, sha256: String? = null,
    ) {
        val cv = ContentValues()
        if (status != null) { cv.put("status", status); if (status == MsgStatus.DELIVERED || status == MsgStatus.RECEIVED) cv.put("delivered_at", System.currentTimeMillis()) }
        if (nextChunk != null) cv.put("next_chunk", nextChunk)
        if (receivedMask != null) cv.put("received_mask", receivedMask)
        if (error != null) cv.put("error", error)
        if (transport != null) cv.put("transport", transport)
        if (nextAttempt != null) cv.put("next_attempt", nextAttempt)
        if (path != null) cv.put("path", path)
        if (sha256 != null) cv.put("sha256", sha256)
        if (cv.size() > 0) writableDatabase.update("transfers", cv, "tid=?", arrayOf(tid))
        if (bumpAttempts) writableDatabase.execSQL("UPDATE transfers SET attempts=attempts+1 WHERE tid=?", arrayOf(tid))
    }

    /** Incoming transfers learn their kind/name/size only after decryption. */
    fun updateTransferMeta(tid: String, kind: Int, name: String, dataSize: Int) {
        val cv = ContentValues().apply { put("kind", kind); put("name", name); put("data_size", dataSize) }
        writableDatabase.update("transfers", cv, "tid=?", arrayOf(tid))
    }

    private fun transferRow(c: Cursor) = StoredTransfer(
        tid = c.getString(c.getColumnIndexOrThrow("tid")),
        direction = c.getString(c.getColumnIndexOrThrow("direction")),
        peerId = c.getString(c.getColumnIndexOrThrow("peer_id")),
        peerName = c.getString(c.getColumnIndexOrThrow("peer_name")),
        kind = c.getInt(c.getColumnIndexOrThrow("kind")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        dataSize = c.getInt(c.getColumnIndexOrThrow("data_size")),
        blobLen = c.getInt(c.getColumnIndexOrThrow("blob_len")),
        chunkCount = c.getInt(c.getColumnIndexOrThrow("chunk_count")),
        nextChunk = c.getInt(c.getColumnIndexOrThrow("next_chunk")),
        receivedMask = c.getColumnIndex("received_mask").let { if (it >= 0 && !c.isNull(it)) c.getBlob(it) else null },
        status = c.getString(c.getColumnIndexOrThrow("status")),
        transport = c.getString(c.getColumnIndexOrThrow("transport")),
        error = c.getString(c.getColumnIndexOrThrow("error")),
        timestamp = c.getLong(c.getColumnIndexOrThrow("ts")),
        attempts = c.getInt(c.getColumnIndexOrThrow("attempts")),
        nextAttempt = c.getLong(c.getColumnIndexOrThrow("next_attempt")),
        sha256 = c.getString(c.getColumnIndexOrThrow("sha256")),
        path = c.getString(c.getColumnIndexOrThrow("path")),
        destId = c.getString(c.getColumnIndexOrThrow("dest_id")),
        originId = c.getString(c.getColumnIndexOrThrow("origin_id")),
    )
}
