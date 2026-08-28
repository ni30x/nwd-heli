// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.core.database.getIntOrNull
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import helium314.keyboard.latin.utils.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue

class TypingHistoryDao private constructor(private val context: Context) {
    
    private val db: Database = Database.getInstance(context)
    private val writeQueue = ConcurrentLinkedQueue<TypingEvent>()
    private val scope = CoroutineScope(Dispatchers.IO)
    
    // Event types
    enum class EventType {
        TYPED,
        PASSWORD,
        DELETE,
        LINE_BREAK
    }
    
    // Data classes
    data class TypingEvent(
        val id: Long = 0,
        val timestamp: Long,
        val eventType: EventType,
        val textContent: String?,
        val deletedText: String?,
        val charCount: Int = 0,
        val appPackage: String,
        val appName: String?,
        val inputType: Int,
        val sessionId: String,
        val isSentenceEnd: Boolean = false
    )
    
    data class TypingSession(
        val sessionId: String,
        val appPackage: String,
        val appName: String? = null,
        val startTime: Long,
        val endTime: Long,
        val sessionTrigger: String?,
        val totalTyped: Int = 0,
        val totalDeletes: Int = 0,
        val totalPasswords: Int = 0,
        val totalLinebreaks: Int = 0,
        val hasPasswords: Boolean = false,
        val previewText: String?
    )
    
    // Record a typing event (queued for batch insert)
    fun recordEvent(
        timestamp: Long,
        eventType: EventType,
        textContent: String?,
        deletedText: String?,
        charCount: Int,
        appPackage: String,
        appName: String?,
        inputType: Int,
        sessionId: String,
        isSentenceEnd: Boolean = false
    ) {
        val event = TypingEvent(
            timestamp = timestamp,
            eventType = eventType,
            textContent = textContent,
            deletedText = deletedText,
            charCount = charCount,
            appPackage = appPackage,
            appName = appName,
            inputType = inputType,
            sessionId = sessionId,
            isSentenceEnd = isSentenceEnd
        )
        
        writeQueue.add(event)
        
        // Flush queue if it reaches threshold
        if (writeQueue.size >= BATCH_SIZE) {
            flushQueue()
        }
    }
    
    // Flush queued events to database (async, on IO dispatcher)
    fun flushQueue() {
        if (writeQueue.isEmpty()) return

        scope.launch {
            flushQueueSync()
        }
    }

    /**
     * Drain the write queue and insert all pending events into the DB
     * on the calling thread. Call this before reading session stats
     * to guarantee all events are persisted.
     */
    fun flushQueueSync() {
        if (writeQueue.isEmpty()) return

        val events = mutableListOf<TypingEvent>()
        while (writeQueue.isNotEmpty()) {
            writeQueue.poll()?.let { events.add(it) }
        }

        if (events.isEmpty()) return

        try {
            db.writableDatabase.beginTransaction()
            try {
                events.forEach { event ->
                    insertEvent(event)
                }
                db.writableDatabase.setTransactionSuccessful()
            } finally {
                db.writableDatabase.endTransaction()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error flushing typing history events (sync)", e)
        }
    }
    
    // Insert event directly (internal use)
    private fun insertEvent(event: TypingEvent): Long {
        val cv = ContentValues().apply {
            put(COL_TIMESTAMP, event.timestamp)
            put(COL_EVENT_TYPE, event.eventType.name)
            put(COL_TEXT_CONTENT, event.textContent)
            put(COL_DELETED_TEXT, event.deletedText)
            put(COL_CHAR_COUNT, event.charCount)
            put(COL_APP_PACKAGE, event.appPackage)
            put(COL_APP_NAME, event.appName)
            put(COL_INPUT_TYPE, event.inputType)
            put(COL_SESSION_ID, event.sessionId)
            put(COL_IS_SENTENCE_END, if (event.isSentenceEnd) 1 else 0)
            put(COL_CREATED_AT, System.currentTimeMillis())
        }
        return db.writableDatabase.insert(TABLE_EVENTS, null, cv)
    }
    
    // Get events by session ID
    fun getEventsBySession(sessionId: String): List<TypingEvent> {
        val events = mutableListOf<TypingEvent>()
        
        db.readableDatabase.query(
            TABLE_EVENTS,
            null,
            "$COL_SESSION_ID = ?",
            arrayOf(sessionId),
            null,
            null,
            "$COL_TIMESTAMP ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                events.add(cursorToEvent(cursor))
            }
        }
        
        return events
    }
    
    // Get events by time range
    fun getEventsByTimeRange(startTime: Long, endTime: Long): List<TypingEvent> {
        val events = mutableListOf<TypingEvent>()
        
        db.readableDatabase.query(
            TABLE_EVENTS,
            null,
            "$COL_TIMESTAMP >= ? AND $COL_TIMESTAMP <= ?",
            arrayOf(startTime.toString(), endTime.toString()),
            null,
            null,
            "$COL_TIMESTAMP DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                events.add(cursorToEvent(cursor))
            }
        }
        
        return events
    }
    
    // Get events by app package
    fun getEventsByApp(appPackage: String): List<TypingEvent> {
        val events = mutableListOf<TypingEvent>()
        
        db.readableDatabase.query(
            TABLE_EVENTS,
            null,
            "$COL_APP_PACKAGE = ?",
            arrayOf(appPackage),
            null,
            null,
            "$COL_TIMESTAMP DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                events.add(cursorToEvent(cursor))
            }
        }
        
        return events
    }
    
    // Insert or update session
    fun upsertSession(session: TypingSession) {
        val cv = ContentValues().apply {
            put(COL_SESSION_ID, session.sessionId)
            put(COL_APP_PACKAGE, session.appPackage)
            put(COL_SESSION_APP_NAME, session.appName)
            put(COL_SESSION_START_TIME, session.startTime)
            put(COL_SESSION_END_TIME, session.endTime)
            put(COL_SESSION_TRIGGER, session.sessionTrigger)
            put(COL_TOTAL_TYPED, session.totalTyped)
            put(COL_TOTAL_DELETES, session.totalDeletes)
            put(COL_TOTAL_PASSWORDS, session.totalPasswords)
            put(COL_TOTAL_LINEBREAKS, session.totalLinebreaks)
            put(COL_HAS_PASSWORDS, if (session.hasPasswords) 1 else 0)
            put(COL_PREVIEW_TEXT, session.previewText)
        }
        
        db.writableDatabase.insertWithOnConflict(
            TABLE_SESSIONS,
            null,
            cv,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }
    
    // Get session by ID
    fun getSession(sessionId: String): TypingSession? {
        db.readableDatabase.query(
            TABLE_SESSIONS,
            null,
            "$COL_SESSION_ID = ?",
            arrayOf(sessionId),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursorToSession(cursor)
            }
        }
        return null
    }
    
    // Get all sessions ordered by time
    fun getAllSessions(): List<TypingSession> {
        val sessions = mutableListOf<TypingSession>()
        
        db.readableDatabase.query(
            TABLE_SESSIONS,
            null,
            null,
            null,
            null,
            null,
            "$COL_SESSION_START_TIME DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                sessions.add(cursorToSession(cursor))
            }
        }
        
        return sessions
    }

    /**
     * Get sessions whose start time falls within a date range (startOfDay to endOfDay).
     * Used by the calendar view to filter sessions for a specific date.
     */
    fun getSessionsByDateRange(startOfDay: Long, endOfDay: Long): List<TypingSession> {
        val sessions = mutableListOf<TypingSession>()

        db.readableDatabase.query(
            TABLE_SESSIONS,
            null,
            "$COL_SESSION_START_TIME >= ? AND $COL_SESSION_START_TIME <= ?",
            arrayOf(startOfDay.toString(), endOfDay.toString()),
            null,
            null,
            "$COL_SESSION_START_TIME DESC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                sessions.add(cursorToSession(cursor))
            }
        }

        return sessions
    }

    /**
     * Get all distinct dates (as day-start timestamps) that have sessions.
     * Used by the calendar to highlight days with history.
     */
    fun getDatesWithSessions(): Set<Long> {
        val dates = mutableSetOf<Long>()

        db.readableDatabase.query(
            true, // distinct
            TABLE_SESSIONS,
            arrayOf(COL_SESSION_START_TIME),
            null,
            null,
            null,
            null,
            null,
            null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val ts = cursor.getLongOrNull(0) ?: continue
                // Round down to start of day (strip hours/minutes/seconds)
                val dayStart = (ts / 86400000) * 86400000
                dates.add(dayStart)
            }
        }

        return dates
    }

    // Delete events by session
    fun deleteSessionEvents(sessionId: String): Int {
        return db.writableDatabase.delete(
            TABLE_EVENTS,
            "$COL_SESSION_ID = ?",
            arrayOf(sessionId)
        )
    }
    
    // Delete session
    fun deleteSession(sessionId: String): Int {
        deleteSessionEvents(sessionId)
        return db.writableDatabase.delete(
            TABLE_SESSIONS,
            "$COL_SESSION_ID = ?",
            arrayOf(sessionId)
        )
    }
    
    // Delete all history
    fun deleteAllHistory() {
        db.writableDatabase.delete(TABLE_EVENTS, null, null)
        db.writableDatabase.delete(TABLE_SESSIONS, null, null)
    }
    
    // Delete by time range
    fun deleteByTimeRange(startTime: Long, endTime: Long): Int {
        val deletedEvents = db.writableDatabase.delete(
            TABLE_EVENTS,
            "$COL_TIMESTAMP >= ? AND $COL_TIMESTAMP <= ?",
            arrayOf(startTime.toString(), endTime.toString())
        )
        
        // Clean up orphaned sessions
        db.writableDatabase.execSQL(
            "DELETE FROM $TABLE_SESSIONS WHERE $COL_SESSION_ID NOT IN (SELECT DISTINCT $COL_SESSION_ID FROM $TABLE_EVENTS)"
        )
        
        return deletedEvents
    }
    
    // Delete by app
    fun deleteByApp(appPackage: String): Int {
        val deletedEvents = db.writableDatabase.delete(
            TABLE_EVENTS,
            "$COL_APP_PACKAGE = ?",
            arrayOf(appPackage)
        )
        
        db.writableDatabase.delete(
            TABLE_SESSIONS,
            "$COL_APP_PACKAGE = ?",
            arrayOf(appPackage)
        )
        
        return deletedEvents
    }
    
    // Get total event count
    fun getTotalEventCount(): Int {
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_EVENTS", null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getInt(0)
            }
        }
        return 0
    }
    
    // Helper: cursor to event
    private fun cursorToEvent(cursor: Cursor): TypingEvent {
        return TypingEvent(
            id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID)),
            timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)),
            eventType = EventType.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(COL_EVENT_TYPE))),
            textContent = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(COL_TEXT_CONTENT)),
            deletedText = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(COL_DELETED_TEXT)),
            charCount = cursor.getIntOrNull(cursor.getColumnIndexOrThrow(COL_CHAR_COUNT)) ?: 0,
            appPackage = cursor.getString(cursor.getColumnIndexOrThrow(COL_APP_PACKAGE)),
            appName = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(COL_APP_NAME)),
            inputType = cursor.getInt(cursor.getColumnIndexOrThrow(COL_INPUT_TYPE)),
            sessionId = cursor.getString(cursor.getColumnIndexOrThrow(COL_SESSION_ID)),
            isSentenceEnd = cursor.getInt(cursor.getColumnIndexOrThrow(COL_IS_SENTENCE_END)) == 1
        )
    }
    
    // Helper: cursor to session
    private fun cursorToSession(cursor: Cursor): TypingSession {
        return TypingSession(
            sessionId = cursor.getString(cursor.getColumnIndexOrThrow(COL_SESSION_ID)),
            appPackage = cursor.getString(cursor.getColumnIndexOrThrow(COL_APP_PACKAGE)),
            appName = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(COL_SESSION_APP_NAME)),
            startTime = cursor.getLong(cursor.getColumnIndexOrThrow(COL_SESSION_START_TIME)),
            endTime = cursor.getLong(cursor.getColumnIndexOrThrow(COL_SESSION_END_TIME)),
            sessionTrigger = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(COL_SESSION_TRIGGER)),
            totalTyped = cursor.getIntOrNull(cursor.getColumnIndexOrThrow(COL_TOTAL_TYPED)) ?: 0,
            totalDeletes = cursor.getIntOrNull(cursor.getColumnIndexOrThrow(COL_TOTAL_DELETES)) ?: 0,
            totalPasswords = cursor.getIntOrNull(cursor.getColumnIndexOrThrow(COL_TOTAL_PASSWORDS)) ?: 0,
            totalLinebreaks = cursor.getIntOrNull(cursor.getColumnIndexOrThrow(COL_TOTAL_LINEBREAKS)) ?: 0,
            hasPasswords = cursor.getInt(cursor.getColumnIndexOrThrow(COL_HAS_PASSWORDS)) == 1,
            previewText = cursor.getStringOrNull(cursor.getColumnIndexOrThrow(COL_PREVIEW_TEXT))
        )
    }
    
    companion object {
        private val TAG = TypingHistoryDao::class.java.simpleName
        private const val BATCH_SIZE = 50
        
        // Table names
        const val TABLE_EVENTS = "TYPING_HISTORY_EVENTS"
        const val TABLE_SESSIONS = "TYPING_HISTORY_SESSIONS"
        
        // Events table columns
        private const val COL_ID = "ID"
        private const val COL_TIMESTAMP = "TIMESTAMP"
        private const val COL_EVENT_TYPE = "EVENT_TYPE"
        private const val COL_TEXT_CONTENT = "TEXT_CONTENT"
        private const val COL_DELETED_TEXT = "DELETED_TEXT"
        private const val COL_CHAR_COUNT = "CHAR_COUNT"
        private const val COL_APP_PACKAGE = "APP_PACKAGE"
        private const val COL_APP_NAME = "APP_NAME"
        private const val COL_INPUT_TYPE = "INPUT_TYPE"
        private const val COL_SESSION_ID = "SESSION_ID"
        private const val COL_IS_SENTENCE_END = "IS_SENTENCE_END"
        private const val COL_CREATED_AT = "CREATED_AT"
        
        // Sessions table columns
        private const val COL_SESSION_APP_NAME = "APP_NAME"
        private const val COL_SESSION_START_TIME = "START_TIME"
        private const val COL_SESSION_END_TIME = "END_TIME"
        private const val COL_SESSION_TRIGGER = "SESSION_TRIGGER"
        private const val COL_TOTAL_TYPED = "TOTAL_TYPED"
        private const val COL_TOTAL_DELETES = "TOTAL_DELETES"
        private const val COL_TOTAL_PASSWORDS = "TOTAL_PASSWORDS"
        private const val COL_TOTAL_LINEBREAKS = "TOTAL_LINEBREAKS"
        private const val COL_HAS_PASSWORDS = "HAS_PASSWORDS"
        private const val COL_PREVIEW_TEXT = "PREVIEW_TEXT"
        
        // SQL: Create events table
        const val CREATE_EVENTS_TABLE = """
            CREATE TABLE IF NOT EXISTS $TABLE_EVENTS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_EVENT_TYPE TEXT NOT NULL,
                $COL_TEXT_CONTENT TEXT,
                $COL_DELETED_TEXT TEXT,
                $COL_CHAR_COUNT INTEGER DEFAULT 0,
                $COL_APP_PACKAGE TEXT NOT NULL,
                $COL_APP_NAME TEXT,
                $COL_INPUT_TYPE INTEGER,
                $COL_SESSION_ID TEXT NOT NULL,
                $COL_IS_SENTENCE_END INTEGER DEFAULT 0,
                $COL_CREATED_AT INTEGER NOT NULL
            )
        """
        
        // SQL: Create sessions table
        const val CREATE_SESSIONS_TABLE = """
            CREATE TABLE IF NOT EXISTS $TABLE_SESSIONS (
                $COL_SESSION_ID TEXT PRIMARY KEY,
                $COL_APP_PACKAGE TEXT NOT NULL,
                $COL_SESSION_APP_NAME TEXT,
                $COL_SESSION_START_TIME INTEGER NOT NULL,
                $COL_SESSION_END_TIME INTEGER NOT NULL,
                $COL_SESSION_TRIGGER TEXT,
                $COL_TOTAL_TYPED INTEGER DEFAULT 0,
                $COL_TOTAL_DELETES INTEGER DEFAULT 0,
                $COL_TOTAL_PASSWORDS INTEGER DEFAULT 0,
                $COL_TOTAL_LINEBREAKS INTEGER DEFAULT 0,
                $COL_HAS_PASSWORDS INTEGER DEFAULT 0,
                $COL_PREVIEW_TEXT TEXT
            )
        """
        
        // SQL: Create indices for performance
        const val CREATE_TIMESTAMP_INDEX = 
            "CREATE INDEX IF NOT EXISTS idx_events_timestamp ON $TABLE_EVENTS($COL_TIMESTAMP)"
        
        const val CREATE_SESSION_INDEX = 
            "CREATE INDEX IF NOT EXISTS idx_events_session ON $TABLE_EVENTS($COL_SESSION_ID)"
        
        const val CREATE_APP_INDEX = 
            "CREATE INDEX IF NOT EXISTS idx_events_app ON $TABLE_EVENTS($COL_APP_PACKAGE)"
        
        private var instance: TypingHistoryDao? = null
        
        fun getInstance(context: Context): TypingHistoryDao {
            if (instance == null) {
                instance = TypingHistoryDao(context)
            }
            return instance!!
        }
    }
}
