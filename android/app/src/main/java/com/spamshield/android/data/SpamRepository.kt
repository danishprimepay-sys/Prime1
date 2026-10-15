package com.spamshield.android.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.spamshield.android.data.SpamDatabase.Companion.T_CALL_LOG
import com.spamshield.android.data.SpamDatabase.Companion.T_COMMUNITY
import com.spamshield.android.data.SpamDatabase.Companion.T_LIST
import com.spamshield.android.data.SpamDatabase.Companion.T_QUARANTINE
import com.spamshield.core.Action
import com.spamshield.core.Engine
import com.spamshield.core.EngineContext
import com.spamshield.core.Event
import com.spamshield.core.Numbers
import com.spamshield.core.Patterns
import com.spamshield.core.Verdict

/**
 * Single facade over the engine, the app database and settings. All methods do blocking I/O
 * and must be called off the main thread (except [settings] accessors).
 */
class SpamRepository private constructor(context: Context) {
    private val db = SpamDatabase(context)
    val settings = SettingsStore(context)
    val engine: Engine by lazy { Engine.loadBundled() }

    // --- Evaluation ---------------------------------------------------------------------------

    fun evaluate(event: Event): Verdict = engine.evaluate(event, contextFor(event.number))

    /** Builds the engine context; the community map holds only the entry for [number], if any. */
    fun contextFor(number: String?): EngineContext {
        val settings = settings.engineSettings()
        val normalized = Numbers.normalize(number, settings.countryCode)
        val community = normalized?.let { n -> communityScore(n)?.let { mapOf(n to it) } }.orEmpty()
        return EngineContext(
            blocklist = patterns(ListKind.BLOCK),
            allowlist = patterns(ListKind.ALLOW),
            community = community,
            settings = settings,
        )
    }

    fun normalize(number: String?): String? = Numbers.normalize(number, settings.countryCode)

    // --- Block / allow lists ------------------------------------------------------------------

    fun entries(kind: ListKind): List<ListEntry> =
        db.readableDatabase.query(T_LIST, null, "kind = ?", arrayOf(kind.key), null, null, "added_at DESC").use { c ->
            c.map { ListEntry(it.long("_id"), kind, it.string("pattern")!!, it.long("added_at")) }
        }

    private fun patterns(kind: ListKind): List<String> = entries(kind).map { it.pattern }

    /** Adds a pattern; returns false when it can never match (see SPEC section 2). */
    fun addEntry(kind: ListKind, pattern: String): Boolean {
        val trimmed = pattern.trim()
        if (Patterns.prepare(trimmed, settings.countryCode) == null) return false
        val values = ContentValues().apply {
            put("kind", kind.key)
            put("pattern", trimmed)
            put("added_at", System.currentTimeMillis())
        }
        db.writableDatabase.insertWithOnConflict(T_LIST, null, values, SQLiteDatabase.CONFLICT_IGNORE)
        return true
    }

    fun removeEntry(id: Long) {
        db.writableDatabase.delete(T_LIST, "_id = ?", arrayOf(id.toString()))
    }

    // --- Community cache ----------------------------------------------------------------------

    fun communityScore(normalizedNumber: String): Double? =
        db.readableDatabase.query(T_COMMUNITY, arrayOf("score"), "number = ?", arrayOf(normalizedNumber), null, null, null)
            .use { c -> if (c.moveToFirst()) c.getDouble(0) else null }

    fun communityCount(): Long =
        android.database.DatabaseUtils.queryNumEntries(db.readableDatabase, T_COMMUNITY)

    /** Atomically replaces the whole community cache. */
    fun replaceCommunity(entries: List<Pair<String, Double>>) {
        val w = db.writableDatabase
        w.beginTransaction()
        try {
            w.delete(T_COMMUNITY, null, null)
            val stmt = w.compileStatement("INSERT OR REPLACE INTO $T_COMMUNITY (number, score) VALUES (?, ?)")
            for ((number, score) in entries) {
                stmt.bindString(1, number)
                stmt.bindDouble(2, score)
                stmt.executeInsert()
                stmt.clearBindings()
            }
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    // --- Call log -----------------------------------------------------------------------------

    fun logCall(number: String?, verdict: Verdict) {
        val values = ContentValues().apply {
            put("number", number)
            put("at", System.currentTimeMillis())
            put("action", verdict.action.name)
            put("score", verdict.score)
            put("reasons", verdict.reasons.joinToString(REASON_SEPARATOR))
        }
        db.writableDatabase.insert(T_CALL_LOG, null, values)
    }

    fun callLog(): List<CallLogEntry> =
        db.readableDatabase.query(T_CALL_LOG, null, null, null, null, null, "at DESC", "500").use { c ->
            c.map {
                CallLogEntry(
                    id = it.long("_id"),
                    number = it.string("number"),
                    at = it.long("at"),
                    action = Action.valueOf(it.string("action")!!),
                    score = it.double("score"),
                    reasons = it.reasons(),
                )
            }
        }

    fun deleteCallLogEntry(id: Long) {
        db.writableDatabase.delete(T_CALL_LOG, "_id = ?", arrayOf(id.toString()))
    }

    fun clearCallLog() {
        db.writableDatabase.delete(T_CALL_LOG, null, null)
    }

    // --- SMS quarantine -----------------------------------------------------------------------

    fun quarantine(sms: IncomingSms, verdict: Verdict): Long {
        val values = ContentValues().apply {
            put("address", sms.address)
            put("body", sms.body)
            put("sent_at", sms.sentAt)
            put("received_at", sms.receivedAt)
            put("sub_id", sms.subscriptionId)
            put("score", verdict.score)
            put("reasons", verdict.reasons.joinToString(REASON_SEPARATOR))
        }
        return db.writableDatabase.insert(T_QUARANTINE, null, values)
    }

    fun quarantined(): List<QuarantinedSms> =
        db.readableDatabase.query(T_QUARANTINE, null, null, null, null, null, "received_at DESC").use { c ->
            c.map {
                QuarantinedSms(
                    id = it.long("_id"),
                    sms = IncomingSms(
                        address = it.string("address"),
                        body = it.string("body").orEmpty(),
                        sentAt = it.long("sent_at"),
                        receivedAt = it.long("received_at"),
                        subscriptionId = it.long("sub_id").toInt(),
                    ),
                    score = it.double("score"),
                    reasons = it.reasons(),
                )
            }
        }

    fun quarantineCount(): Long = android.database.DatabaseUtils.queryNumEntries(db.readableDatabase, T_QUARANTINE)

    fun deleteQuarantined(id: Long) {
        db.writableDatabase.delete(T_QUARANTINE, "_id = ?", arrayOf(id.toString()))
    }

    companion object {
        private const val REASON_SEPARATOR = ","

        @Volatile
        private var instance: SpamRepository? = null

        fun get(context: Context): SpamRepository =
            instance ?: synchronized(this) {
                instance ?: SpamRepository(context.applicationContext).also { instance = it }
            }

        private inline fun <T> Cursor.map(transform: (Cursor) -> T): List<T> {
            val out = ArrayList<T>(count)
            while (moveToNext()) out += transform(this)
            return out
        }

        private fun Cursor.long(column: String) = getLong(getColumnIndexOrThrow(column))
        private fun Cursor.double(column: String) = getDouble(getColumnIndexOrThrow(column))
        private fun Cursor.string(column: String): String? = getString(getColumnIndexOrThrow(column))
        private fun Cursor.reasons() = string("reasons").orEmpty().split(REASON_SEPARATOR).filter { it.isNotEmpty() }
    }
}
