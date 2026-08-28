// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.net.Uri
import helium314.keyboard.latin.database.TypingHistoryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles encrypted backup and restore of typing history.
 * Backups contain FULL timestamps with millisecond precision.
 */
class TypingHistoryBackupManager(private val context: Context) {
    
    private val dao: TypingHistoryDao = TypingHistoryDao.getInstance(context)
    
    companion object {
        private const val BACKUP_VERSION = 1
        private const val PBKDF2_ITERATIONS = 10000
        private const val KEY_LENGTH = 256
        private const val IV_LENGTH = 12
        private const val SALT_LENGTH = 16
        private const val GCM_TAG_LENGTH = 128
    }
    
    data class BackupStats(
        val totalSessions: Int,
        val totalEvents: Int,
        val dateRangeStart: Long,
        val dateRangeEnd: Long,
        val fileSize: Long
    )
    
    /**
     * Create encrypted backup of all history
     * @param password Backup password for encryption
     * @param outputStream Stream to write backup data to
     */
    suspend fun createBackup(
        password: String,
        outputStream: OutputStream
    ): Result<BackupStats> = withContext(Dispatchers.IO) {
        try {
            val sessions = dao.getAllSessions()
            val eventsBySession = sessions.associate { session ->
                session.sessionId to dao.getEventsBySession(session.sessionId)
            }
            
            // Build JSON with full data
            val json = buildBackupJson(sessions, eventsBySession)
            
            // Encrypt with password
            val encrypted = encrypt(json, password)
            
            // Write to stream
            outputStream.use { stream ->
                stream.write(encrypted)
                stream.flush()
            }
            
            val allEvents = eventsBySession.values.flatten()
            Result.success(
                BackupStats(
                    totalSessions = sessions.size,
                    totalEvents = allEvents.size,
                    dateRangeStart = allEvents.minOfOrNull { it.timestamp } ?: 0,
                    dateRangeEnd = allEvents.maxOfOrNull { it.timestamp } ?: 0,
                    fileSize = encrypted.size.toLong()
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Restore history from encrypted backup
     * @param password Backup password for decryption
     * @param encryptedData Encrypted backup bytes
     * @param mergeWithExisting true to merge, false to replace
     */
    suspend fun restoreBackup(
        password: String,
        encryptedData: ByteArray,
        mergeWithExisting: Boolean = true
    ): Result<RestoreStats> = withContext(Dispatchers.IO) {
        try {
            if (encryptedData.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Backup file is empty"))
            }

            // Decrypt
            val json = try {
                decrypt(encryptedData, password)
            } catch (e: javax.crypto.AEADBadTagException) {
                return@withContext Result.failure(IllegalArgumentException("Wrong password — could not decrypt backup"))
            } catch (e: IllegalArgumentException) {
                return@withContext Result.failure(e)
            } catch (e: Exception) {
                return@withContext Result.failure(IllegalArgumentException("Invalid backup file: ${e.message}"))
            }
            
            // Parse JSON
            val backupData = parseBackupJson(json)
            
            if (mergeWithExisting) {
                // Merge: add events with new session IDs
                backupData.forEach { session ->
                    val newSessionId = session.sessionId
                    // Insert events
                    session.events.forEach { event ->
                        dao.recordEvent(
                            timestamp = event.timestamp,
                            eventType = event.eventType,
                            textContent = event.textContent,
                            deletedText = event.deletedText,
                            charCount = event.charCount,
                            appPackage = event.appPackage,
                            appName = event.appName,
                            inputType = event.inputType,
                            sessionId = newSessionId,
                            isSentenceEnd = event.isSentenceEnd
                        )
                    }
                    dao.flushQueue()
                }
            } else {
                // Replace: delete existing, then restore
                dao.deleteAllHistory()
                backupData.forEach { session ->
                    session.events.forEach { event ->
                        dao.recordEvent(
                            timestamp = event.timestamp,
                            eventType = event.eventType,
                            textContent = event.textContent,
                            deletedText = event.deletedText,
                            charCount = event.charCount,
                            appPackage = event.appPackage,
                            appName = event.appName,
                            inputType = event.inputType,
                            sessionId = session.sessionId,
                            isSentenceEnd = event.isSentenceEnd
                        )
                    }
                    dao.flushQueue()
                }
            }
            
            Result.success(
                RestoreStats(
                    totalSessions = backupData.size,
                    totalEvents = backupData.sumOf { it.events.size }
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    data class RestoreStats(
        val totalSessions: Int,
        val totalEvents: Int
    )
    
    // ─── JSON building ───
    
    private fun buildBackupJson(
        sessions: List<TypingHistoryDao.TypingSession>,
        eventsBySession: Map<String, List<TypingHistoryDao.TypingEvent>>
    ): String {
        val root = JSONObject().apply {
            put("backup_version", BACKUP_VERSION)
            put("backup_date", System.currentTimeMillis())
            put("app", "HeliBoard")
            put("total_sessions", sessions.size)
            put("total_events", eventsBySession.values.sumOf { it.size })
        }
        
        val sessionsArray = JSONArray()
        sessions.forEach { session ->
            val sessionObj = JSONObject().apply {
                put("session_id", session.sessionId)
                put("app_package", session.appPackage)
                put("app_name", session.appName)
                put("start_time", session.startTime)
                put("end_time", session.endTime)
                put("session_trigger", session.sessionTrigger)
                put("total_typed", session.totalTyped)
                put("total_deletes", session.totalDeletes)
                put("total_passwords", session.totalPasswords)
                put("total_linebreaks", session.totalLinebreaks)
                put("has_passwords", session.hasPasswords)
                put("preview_text", session.previewText)
            }
            
            // Add events
            val eventsArray = JSONArray()
            eventsBySession[session.sessionId]?.forEach { event ->
                val eventObj = JSONObject().apply {
                    put("timestamp", event.timestamp)
                    put("event_type", event.eventType.name)
                    put("text_content", event.textContent)
                    put("deleted_text", event.deletedText)
                    put("char_count", event.charCount)
                    put("app_package", event.appPackage)
                    put("app_name", event.appName)
                    put("input_type", event.inputType)
                    put("is_sentence_end", event.isSentenceEnd)
                }
                eventsArray.put(eventObj)
            }
            sessionObj.put("events", eventsArray)
            
            sessionsArray.put(sessionObj)
        }
        
        root.put("sessions", sessionsArray)
        return root.toString()
    }
    
    /**
     * Parse backup JSON into restore data
     * @throws Exception if JSON format is invalid
     */
    private fun parseBackupJson(json: String): List<RestoreSession> {
        val root = JSONObject(json)
        val version = root.optInt("backup_version", 0)
        if (version != BACKUP_VERSION) {
            throw IllegalArgumentException("Unsupported backup version: $version")
        }
        
        val sessions = mutableListOf<RestoreSession>()
        val sessionsArray = root.optJSONArray("sessions") ?: JSONArray()
        
        for (i in 0 until sessionsArray.length()) {
            val sessionObj = sessionsArray.getJSONObject(i)
            val events = mutableListOf<TypingHistoryDao.TypingEvent>()
            
            val eventsArray = sessionObj.optJSONArray("events") ?: JSONArray()
            for (j in 0 until eventsArray.length()) {
                val eventObj = eventsArray.getJSONObject(j)
                val event = TypingHistoryDao.TypingEvent(
                    timestamp = eventObj.getLong("timestamp"),
                    eventType = TypingHistoryDao.EventType.valueOf(eventObj.getString("event_type")),
                    textContent = eventObj.optString("text_content", null),
                    deletedText = eventObj.optString("deleted_text", null),
                    charCount = eventObj.optInt("char_count", 0),
                    appPackage = eventObj.getString("app_package"),
                    appName = eventObj.optString("app_name", null),
                    inputType = eventObj.optInt("input_type", 0),
                    sessionId = sessionObj.getString("session_id"),
                    isSentenceEnd = eventObj.optBoolean("is_sentence_end", false)
                )
                events.add(event)
            }
            
            sessions.add(
                RestoreSession(
                    sessionId = sessionObj.getString("session_id"),
                    appPackage = sessionObj.getString("app_package"),
                    appName = sessionObj.optString("app_name", null),
                    events = events
                )
            )
        }
        
        return sessions
    }
    
    private data class RestoreSession(
        val sessionId: String,
        val appPackage: String,
        val appName: String?,
        val events: List<TypingHistoryDao.TypingEvent>
    )
    
    // ─── Encryption / Decryption ───
    
    /**
     * Encrypt JSON data with password using AES-GCM
     */
    private fun encrypt(data: String, password: String): ByteArray {
        val random = SecureRandom()
        
        // Generate salt and IV
        val salt = ByteArray(SALT_LENGTH)
        random.nextBytes(salt)
        val iv = ByteArray(IV_LENGTH)
        random.nextBytes(iv)
        
        // Derive key from password
        val key = deriveKey(password, salt)
        
        // Encrypt
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, spec)
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        
        // Format: [salt][iv][encrypted]
        val output = ByteArray(salt.size + iv.size + encrypted.size)
        System.arraycopy(salt, 0, output, 0, salt.size)
        System.arraycopy(iv, 0, output, salt.size, iv.size)
        System.arraycopy(encrypted, 0, output, salt.size + iv.size, encrypted.size)
        
        return output
    }
    
    /**
     * Decrypt backup data with password
     * @throws Exception if wrong password or corrupted data
     */
    private fun decrypt(data: ByteArray, password: String): String {
        if (data.isEmpty()) {
            throw IllegalArgumentException("Backup file is empty")
        }
        if (data.size < SALT_LENGTH + IV_LENGTH + GCM_TAG_LENGTH / 8) {
            throw IllegalArgumentException("Backup file is corrupted or not a valid backup")
        }
        
        // Extract salt and IV
        val salt = data.copyOfRange(0, SALT_LENGTH)
        val iv = data.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
        val encrypted = data.copyOfRange(SALT_LENGTH + IV_LENGTH, data.size)
        
        // Derive key
        val key = deriveKey(password, salt)
        
        // Decrypt
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        val decrypted = cipher.doFinal(encrypted) // Throws AEADBadTagException on wrong password
        
        return String(decrypted, Charsets.UTF_8)
    }
    
    /**
     * Derive AES key from password using PBKDF2
     */
    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }
    
    /**
     * Generate suggested backup file name with timestamp
     */
    fun getDefaultBackupFileName(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault())
        return "HeliBoard_History_${sdf.format(Date())}.json"
    }
}
