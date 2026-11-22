package com.echo.caller

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

enum class EchoMessageDirection { SENT, RECEIVED }

data class EchoMessage(
    val id: String,
    val peerFingerprint: String,
    val direction: EchoMessageDirection,
    val body: String,
    val createdAt: Long,
    val deliveryState: String
)

/**
 * Local-only message history. Message IDs are unique, and inserts are
 * idempotent so a retransmitted message cannot create a duplicate row.
 */
class EchoMessageStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "echo_messages_v1.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE messages (
                id TEXT PRIMARY KEY NOT NULL,
                peer_fingerprint TEXT NOT NULL,
                direction TEXT NOT NULL,
                body TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                delivery_state TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX messages_peer_time ON messages(peer_fingerprint, created_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Versioned migrations will be added here before changing the schema.
    }

    fun add(message: EchoMessage): Boolean {
        require(message.id.isNotBlank())
        require(message.peerFingerprint.isNotBlank())
        require(message.body.isNotBlank() && message.body.length <= MAX_MESSAGE_CHARS)
        require(message.createdAt > 0)
        val values = ContentValues().apply {
            put("id", message.id)
            put("peer_fingerprint", message.peerFingerprint)
            put("direction", message.direction.name)
            put("body", message.body)
            put("created_at", message.createdAt)
            put("delivery_state", message.deliveryState)
        }
        return writableDatabase.insertWithOnConflict(
            "messages", null, values, SQLiteDatabase.CONFLICT_IGNORE
        ) != -1L
    }

    fun conversation(peerFingerprint: String, limit: Int = 200): List<EchoMessage> {
        require(peerFingerprint.isNotBlank())
        val safeLimit = limit.coerceIn(1, 500)
        val result = mutableListOf<EchoMessage>()
        readableDatabase.query(
            "messages",
            arrayOf("id", "peer_fingerprint", "direction", "body", "created_at", "delivery_state"),
            "peer_fingerprint = ?",
            arrayOf(peerFingerprint),
            null, null, "created_at ASC",
            safeLimit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    EchoMessage(
                        id = cursor.getString(0),
                        peerFingerprint = cursor.getString(1),
                        direction = EchoMessageDirection.valueOf(cursor.getString(2)),
                        body = cursor.getString(3),
                        createdAt = cursor.getLong(4),
                        deliveryState = cursor.getString(5)
                    )
                )
            }
        }
        return result
    }

    fun allMessages(limit: Int = 300): List<EchoMessage> {
        val safeLimit = limit.coerceIn(1, 1000)
        val result = mutableListOf<EchoMessage>()
        readableDatabase.query(
            "messages",
            arrayOf("id", "peer_fingerprint", "direction", "body", "created_at", "delivery_state"),
            null, null, null, null, "created_at DESC",
            safeLimit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(EchoMessage(
                    cursor.getString(0), cursor.getString(1),
                    EchoMessageDirection.valueOf(cursor.getString(2)),
                    cursor.getString(3), cursor.getLong(4), cursor.getString(5)
                ))
            }
        }
        return result
    }

    fun newMessageId(): String = UUID.randomUUID().toString()

    companion object {
        const val MAX_MESSAGE_CHARS = 4000
    }
}
