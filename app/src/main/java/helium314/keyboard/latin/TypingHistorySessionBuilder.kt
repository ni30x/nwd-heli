// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import helium314.keyboard.latin.database.TypingHistoryDao

/**
 * Builds and manages typing sessions from raw events.
 * Groups events into sessions based on app changes, keyboard state, field clearing, and input type changes.
 */
class TypingHistorySessionBuilder {
    
    companion object {
        // Session boundary triggers
        enum class SessionTrigger {
            KEYBOARD_OPEN,
            APP_CHANGE,
            FIELD_CLEAR,
            INPUT_TYPE_CHANGE
        }
    }
    
    /**
     * Analyze events and determine if a new session should start
     */
    fun shouldStartNewSession(
        currentEvent: TypingHistoryDao.TypingEvent,
        lastEvent: TypingHistoryDao.TypingEvent?,
        keyboardState: KeyboardState
    ): Boolean {
        if (lastEvent == null) return true
        
        // Different app = new session
        if (currentEvent.appPackage != lastEvent.appPackage) return true
        
        // Keyboard was closed and reopened = new session
        if (keyboardState.wasClosedAndReopened) return true
        
        // Input field was cleared = new session
        if (keyboardState.fieldWasCleared) return true
        
        // Input type changed (normal ↔ password) = new session
        if (currentEvent.inputType != lastEvent.inputType) return true
        
        return false
    }
    
    /**
     * Determine the session trigger based on context
     */
    fun determineTrigger(
        currentEvent: TypingHistoryDao.TypingEvent,
        lastEvent: TypingHistoryDao.TypingEvent?,
        keyboardState: KeyboardState
    ): SessionTrigger? {
        if (lastEvent == null) return SessionTrigger.KEYBOARD_OPEN
        
        if (currentEvent.appPackage != lastEvent.appPackage) {
            return SessionTrigger.APP_CHANGE
        }
        
        if (keyboardState.wasClosedAndReopened) {
            return SessionTrigger.KEYBOARD_OPEN
        }
        
        if (keyboardState.fieldWasCleared) {
            return SessionTrigger.FIELD_CLEAR
        }
        
        if (currentEvent.inputType != lastEvent.inputType) {
            return SessionTrigger.INPUT_TYPE_CHANGE
        }
        
        return null
    }
    
    /**
     * Build session summary from a list of events
     */
    fun buildSessionSummary(events: List<TypingHistoryDao.TypingEvent>): SessionSummary {
        if (events.isEmpty()) {
            return SessionSummary(
                totalTyped = 0,
                totalDeletes = 0,
                totalPasswords = 0,
                totalLinebreaks = 0,
                hasPasswords = false,
                previewText = ""
            )
        }
        
        val totalTyped = events.count { it.eventType == TypingHistoryDao.EventType.TYPED }
        val totalDeletes = events.count { it.eventType == TypingHistoryDao.EventType.DELETE }
        val totalPasswords = events.count { it.eventType == TypingHistoryDao.EventType.PASSWORD }
        val totalLinebreaks = events.count { it.eventType == TypingHistoryDao.EventType.LINE_BREAK }
        
        val hasPasswords = totalPasswords > 0
        
        // Build preview from typed text
        val previewText = events
            .filter { it.eventType == TypingHistoryDao.EventType.TYPED }
            .mapNotNull { it.textContent }
            .joinToString("")
            .take(100)
        
        return SessionSummary(
            totalTyped = totalTyped,
            totalDeletes = totalDeletes,
            totalPasswords = totalPasswords,
            totalLinebreaks = totalLinebreaks,
            hasPasswords = hasPasswords,
            previewText = previewText
        )
    }
    
    /**
     * Session summary data class
     */
    data class SessionSummary(
        val totalTyped: Int,
        val totalDeletes: Int,
        val totalPasswords: Int,
        val totalLinebreaks: Int,
        val hasPasswords: Boolean,
        val previewText: String
    )
    
    /**
     * Keyboard state tracker
     */
    data class KeyboardState(
        val wasClosedAndReopened: Boolean = false,
        val fieldWasCleared: Boolean = false,
        val lastKeyboardCloseTime: Long = 0,
        val lastFieldClearTime: Long = 0
    )
}

