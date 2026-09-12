package net.prok.proknet.core

import android.content.ContentValues
import android.content.Context
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class StoredMessage(
    val rowId: Long,
    val msgId: String,
    val direction: String,   // "in" or "out"
    val peerId: String,      // sender (in) or recipient (out) ID hex
    val peerName: String,
    val text: String,
    val timestamp: Long,
    val status: String,      // out: sending | sent | failed ; in: received
)

/** Local SQLite store for sent and received messages. Plain SQLiteOpenHelper: no Room, no kapt. */
class MessageStore(context: Context) : SQLiteOpenHelper(context, "proknet.db", null, 1) {

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
                "status TEXT NOT NULL)"
        )
        db.execSQL("CREATE UNIQUE INDEX idx_msg ON messages(msg_id, direction)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS messages")
        onCreate(db)
    }

    /** Returns false if this msgId/direction already exists (duplicate delivery). */
    fun insert(m: StoredMessage): Boolean {
        val cv = ContentValues().apply {
            put("msg_id", m.msgId)
            put("direction", m.direction)
            put("peer_id", m.peerId)
            put("peer_name", m.peerName)
            put("text", m.text)
            put("ts", m.timestamp)
            put("status", m.status)
        }
        return writableDatabase.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun updateStatus(msgId: String, status: String) {
        val cv = ContentValues().apply { put("status", status) }
        writableDatabase.update("messages", cv, "msg_id=? AND direction='out'", arrayOf(msgId))
    }

    fun recent(limit: Int = 100): List<StoredMessage> {
        val out = ArrayList<StoredMessage>()
        readableDatabase.rawQuery(
            "SELECT id,msg_id,direction,peer_id,peer_name,text,ts,status FROM messages ORDER BY ts DESC, id DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    StoredMessage(
                        c.getLong(0), c.getString(1), c.getString(2), c.getString(3),
                        c.getString(4), c.getString(5), c.getLong(6), c.getString(7)
                    )
                )
            }
        }
        return out.reversed()
    }

    fun count(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "messages")
}
