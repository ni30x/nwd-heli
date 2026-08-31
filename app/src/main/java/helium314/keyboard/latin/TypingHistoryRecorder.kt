// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.content.SharedPreferences
import android.view.inputmethod.EditorInfo
import helium314.keyboard.latin.database.TypingHistoryDao
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.InputTypeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Handles recording typing events to the database.
 * Called from input logic hooks (commitText, delete, etc.)
 */
class TypingHistoryRecorder private constructor(private val context: Context) {
    
    private val dao: TypingHistoryDao = TypingHistoryDao.getInstance(context)
    private val prefs: SharedPreferences = context.getSharedPreferences("heliboard_preferences", 0)
    private val scope = CoroutineScope(Dispatchers.IO)
    
    private var mCurrentSessionId: String? = null
    private var mCurrentAppPackage: String? = null
    private var mCurrentAppName: String? = null
    private var mCurrentInputType: Int = 0
    private var mCurrentSessionTrigger: String = "KEYBOARD_OPEN"
    private var mFieldWasCleared: Boolean = false
    private var mKeyboardWasClosedAndReopened: Boolean = false
    private var mHasActivityInSession: Boolean = false
    private var mLastFieldClearTime: Long = 0
    private var mLastFinishInputTime: Long = 0

    companion object {
        private var instance: TypingHistoryRecorder? = null

        @JvmStatic
        fun getInstance(context: Context): TypingHistoryRecorder {
            if (instance == null) {
                instance = TypingHistoryRecorder(context.applicationContext)
            }
            return instance!!
        }

        // Cooldown to prevent double-fire from performEditorAction + onUpdateSelection
        private const val FIELD_CLEAR_COOLDOWN_MS = 2000L

        // Minimum time keyboard must be closed before treating reopen as a new session.
        // Brief hides (suggestions popup, clipboard overlay) should NOT split sessions.
        private const val KEYBOARD_CLOSE_THRESHOLD_MS = 3000L
    }
    
    /**
     * Called when keyboard input view starts
     */
    fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        if (!isFeatureEnabled()) return
        
        val newAppPackage = info.packageName
        val newInputType = info.inputType
        
        // Only treat keyboard reopen as a new session if keyboard was closed for > threshold.
        // Brief hides (suggestions, clipboard popup) should NOT split the current session.
        val realKeyboardReopen = mKeyboardWasClosedAndReopened &&
            (System.currentTimeMillis() - mLastFinishInputTime > KEYBOARD_CLOSE_THRESHOLD_MS)

        val trigger = when {
            mCurrentSessionId == null -> "KEYBOARD_OPEN"
            newAppPackage != mCurrentAppPackage -> "APP_CHANGE"
            newInputType != mCurrentInputType -> "INPUT_TYPE_CHANGE"
            mFieldWasCleared -> "FIELD_CLEAR"
            realKeyboardReopen -> "KEYBOARD_OPEN"
            else -> null
        }
        
        if (trigger != null) {
            // End current session if exists (only saves if there's real content)
            mCurrentSessionId?.let { endSession() }

            // Prepare new session — do NOT write a row yet.
            // The session row is created in endSession() only if real content exists.
            mCurrentSessionId = UUID.randomUUID().toString()
            mCurrentAppPackage = newAppPackage
            // Use placeholder immediately; resolve real name off-thread.
            // Session row is written in endSession() which can safely wait.
            mCurrentAppName = newAppPackage
            mCurrentInputType = newInputType
            mCurrentSessionTrigger = trigger
            mFieldWasCleared = false
            mKeyboardWasClosedAndReopened = false
            mHasActivityInSession = false

            // Resolve app name off the input thread
            scope.launch {
                val resolved = getAppName(newAppPackage)
                // Only update if we're still on the same package (defensive)
                if (mCurrentAppPackage == newAppPackage) {
                    mCurrentAppName = resolved
                }
            }
        }
    }
    
    /**
     * Called when keyboard input view finishes
     */
    fun onFinishInputView() {
        if (!isFeatureEnabled()) return

        mLastFinishInputTime = System.currentTimeMillis()
        mKeyboardWasClosedAndReopened = true
        mCurrentSessionId?.let { endSession() }
    }
    
    /**
     * Called when text is committed (from commitText)
     */
    fun onTextCommitted(
        text: String,
        packageName: String?,
        inputType: Int
    ) {
        if (!isFeatureEnabled()) return
        if (text.isEmpty()) return
        
        val now = System.currentTimeMillis()
        val sessionId = ensureSession(packageName, inputType)
        
        // Record the typed event
        val isPassword = InputTypeUtils.isPasswordInputType(inputType)
        val eventType = if (isPassword && readRecordPasswords()) {
            TypingHistoryDao.EventType.PASSWORD
        } else {
            TypingHistoryDao.EventType.TYPED
        }

        // Use cached app name — never call getAppName() on the input thread
        val appName = mCurrentAppName
        
        // Check for newlines to record as separate line break events
        if (text.contains('\n')) {
            val parts = text.split('\n')
            parts.forEachIndexed { index, part ->
                if (part.isNotEmpty()) {
                    dao.recordEvent(
                        timestamp = now,
                        eventType = eventType,
                        textContent = part,
                        deletedText = null,
                        charCount = 0,
                        appPackage = packageName ?: "",
                        appName = appName,
                        inputType = inputType,
                        sessionId = sessionId,
                        isSentenceEnd = false
                    )
                }
                // Record line break if not the last split part
                if (index < parts.size - 1) {
                    dao.recordEvent(
                        timestamp = now,
                        eventType = TypingHistoryDao.EventType.LINE_BREAK,
                        textContent = null,
                        deletedText = null,
                        charCount = 0,
                        appPackage = packageName ?: "",
                        appName = appName,
                        inputType = inputType,
                        sessionId = sessionId
                    )
                }
            }
        } else {
            // Record as single typed event
            dao.recordEvent(
                timestamp = now,
                eventType = eventType,
                textContent = text,
                deletedText = null,
                charCount = 0,
                appPackage = packageName ?: "",
                appName = appName,
                inputType = inputType,
                sessionId = sessionId,
                isSentenceEnd = false
            )
        }
        
        mFieldWasCleared = false
        mHasActivityInSession = true
    }

    /**
     * Called when delete/backspace occurs (from deleteTextBeforeCursor)
     */
    fun onDeleteEvent(
        deletedText: String?,
        charCount: Int,
        packageName: String?,
        inputType: Int
    ) {
        if (!isFeatureEnabled()) return
        if (charCount <= 0) return
        
        val now = System.currentTimeMillis()
        val sessionId = ensureSession(packageName, inputType)
        
        // Use cached app name — never call getAppName() on the input thread
        val appName = mCurrentAppName

        dao.recordEvent(
            timestamp = now,
            eventType = TypingHistoryDao.EventType.DELETE,
            textContent = null,
            deletedText = deletedText,
            charCount = charCount,
            appPackage = packageName ?: "",
            appName = appName,
            inputType = inputType,
            sessionId = sessionId
        )

        mHasActivityInSession = true
    }
    
    /**
     * Called when input field is cleared or message is sent.
     * Has cooldown guard to prevent double-fire from performEditorAction + onUpdateSelection.
     */
    fun onFieldCleared(packageName: String?, inputType: Int) {
        if (!isFeatureEnabled()) return

        val now = System.currentTimeMillis()

        // Skip if nothing was typed in this session (cursor just moved, not a real clear)
        if (!mHasActivityInSession) return

        // Skip if another field clear just happened (cooldown guard)
        if ((now - mLastFieldClearTime) < FIELD_CLEAR_COOLDOWN_MS) return

        mLastFieldClearTime = now
        mFieldWasCleared = true
        mCurrentSessionId?.let { endSession() }

        // Prepare next session — do NOT write a row yet.
        mCurrentSessionId = UUID.randomUUID().toString()
        mCurrentAppPackage = packageName
        // Use placeholder immediately; resolve real name off-thread
        mCurrentAppName = packageName
        mCurrentInputType = inputType
        mCurrentSessionTrigger = "FIELD_CLEAR"
        mHasActivityInSession = false

        // Resolve app name off the input thread
        val pkg = packageName
        if (pkg != null) {
            scope.launch {
                val resolved = getAppName(pkg)
                if (mCurrentAppPackage == pkg) {
                    mCurrentAppName = resolved
                }
            }
        }
    }
    
    /**
     * Ensure we have a valid session id, but do NOT write a session row yet.
     * The session row is created in endSession() only if there is real content.
     */
    private fun ensureSession(packageName: String?, inputType: Int): String {
        if (mCurrentSessionId == null) {
            mCurrentSessionId = UUID.randomUUID().toString()
            mCurrentAppPackage = packageName
            // Use placeholder immediately; resolve real name off-thread
            mCurrentAppName = packageName
            mCurrentInputType = inputType
            mCurrentSessionTrigger = "KEYBOARD_OPEN"
            mHasActivityInSession = false

            // Resolve app name off the input thread
            val pkg = packageName
            if (pkg != null) {
                scope.launch {
                    val resolved = getAppName(pkg)
                    if (mCurrentAppPackage == pkg) {
                        mCurrentAppName = resolved
                    }
                }
            }
        } else if (packageName != null && packageName != mCurrentAppPackage) {
            // Defensive: package changed mid-session without triggering a new session.
            // Re-resolve the app name off-thread but do NOT call getAppName() on input thread.
            mCurrentAppPackage = packageName
            mCurrentAppName = packageName // placeholder
            scope.launch {
                val resolved = getAppName(packageName)
                if (mCurrentAppPackage == packageName) {
                    mCurrentAppName = resolved
                }
            }
        }

        return requireNotNull(mCurrentSessionId) { "Session ID should have been initialized" }
    }
    
    /**
     * End the current session
     */
    private fun endSession() {
        mCurrentSessionId?.let { sessionId ->
            // CRITICAL: flush the write queue synchronously so all events are in the DB
            // before we read stats. Without this, sessions with < 50 events appear empty
            // and get wrongly deleted.
            dao.flushQueueSync()

            // Get session stats from events (now guaranteed to be in DB)
            val events = dao.getEventsBySession(sessionId)
            val totalTyped = events.count { it.eventType == TypingHistoryDao.EventType.TYPED }
            val totalDeletes = events.count { it.eventType == TypingHistoryDao.EventType.DELETE }
            val totalPasswords = events.count { it.eventType == TypingHistoryDao.EventType.PASSWORD }
            val totalLinebreaks = events.count { it.eventType == TypingHistoryDao.EventType.LINE_BREAK }
            val hasPasswords = totalPasswords > 0

            // Build preview text from typed events
            val previewText = events
                .filter { it.eventType == TypingHistoryDao.EventType.TYPED }
                .mapNotNull { it.textContent }
                .joinToString("")
                .take(100)

            // Discard sessions that are truly empty or contain only whitespace
            // with no other meaningful activity.
            // Keep sessions that have deletes, passwords, or line breaks — even if
            // previewText (built from TYPED events only) is blank.
            val hasContent = totalTyped > 0 || totalDeletes > 0 || totalPasswords > 0
            val hasNonTypedActivity = totalDeletes > 0 || totalPasswords > 0 || totalLinebreaks > 0
            val hasMeaningfulTypedContent = previewText.isNotBlank()
            if (!hasContent && !hasNonTypedActivity) {
                // Zero events of any kind — discard
                dao.deleteSession(sessionId)
                mCurrentSessionId = null
                return
            }
            if (!hasMeaningfulTypedContent && !hasNonTypedActivity) {
                // Only typed whitespace, no deletes/passwords/linebreaks — discard
                dao.deleteSession(sessionId)
                mCurrentSessionId = null
                return
            }

            // Strip millisecond precision — round to second boundary
            val rawStart = events.minOfOrNull { it.timestamp } ?: System.currentTimeMillis()
            val rawEnd = events.maxOfOrNull { it.timestamp } ?: System.currentTimeMillis()
            val startTime = (rawStart / 1000) * 1000
            val endTime = (rawEnd / 1000) * 1000

            val session = TypingHistoryDao.TypingSession(
                sessionId = sessionId,
                appPackage = mCurrentAppPackage ?: "",
                appName = mCurrentAppName,
                startTime = startTime,
                endTime = endTime,
                sessionTrigger = mCurrentSessionTrigger,
                totalTyped = totalTyped,
                totalDeletes = totalDeletes,
                totalPasswords = totalPasswords,
                totalLinebreaks = totalLinebreaks,
                hasPasswords = hasPasswords,
                previewText = previewText
            )

            dao.upsertSession(session)
        }
    }
    
    /**
     * Check if typing history feature is enabled
     */
    private fun isFeatureEnabled(): Boolean {
        return Settings.readTypingHistoryEnabled(prefs)
    }

    /**
     * Check if any text was typed or deleted in the current session.
     * Used by LatinIME to avoid false field-clear detection on cursor moves.
     */
    fun hasActivityInSession(): Boolean {
        return mHasActivityInSession
    }
    
    /**
     * Called from LatinIME.onDestroy() to flush any pending data before process death.
     * Ends the current session (if any) and unconditionally flushes the write queue.
     */
    fun onImeDestroyed() {
        mCurrentSessionId?.let { endSession() }
        // Safety net: flush anything still in the write queue
        dao.flushQueueSync()
    }

    /**
     * Check if password recording is enabled
     */
    private fun readRecordPasswords(): Boolean {
        return Settings.readTypingHistoryRecordPasswords(prefs)
    }
    
    /**
     * Get app name from package name
     */
    private fun getAppName(packageName: String?): String? {
        if (packageName == null) return null
        return try {
            val packageManager = context.packageManager
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: Exception) {
            packageName
        }
    }
}

