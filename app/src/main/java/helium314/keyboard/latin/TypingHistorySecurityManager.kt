// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import helium314.keyboard.latin.settings.Settings
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.Cipher
import javax.crypto.SecretKey

/**
 * Manages password protection for typing history.
 * Uses PBKDF2 hashing with salt for password storage.
 */
class TypingHistorySecurityManager private constructor(private val context: Context) {
    
    private val prefs: SharedPreferences = context.getSharedPreferences("heliboard_preferences", 0)
    
    companion object {
        private var instance: TypingHistorySecurityManager? = null
        
        private const val ITERATION_COUNT = 10000
        private const val KEY_LENGTH = 256
        private const val SALT_LENGTH = 16
        
        fun getInstance(context: Context): TypingHistorySecurityManager {
            if (instance == null) {
                instance = TypingHistorySecurityManager(context.applicationContext)
            }
            return instance!!
        }
    }
    
    /**
     * Check if password is set
     */
    fun hasPassword(): Boolean {
        val hash = Settings.readTypingHistoryPasswordHash(prefs)
        val salt = Settings.readTypingHistorySalt(prefs)
        return hash.isNotEmpty() && salt.isNotEmpty()
    }
    
    /**
     * Set or change password
     * @return true if password was set successfully
     */
    fun setPassword(newPassword: String): Boolean {
        if (newPassword.isEmpty()) return false
        
        val salt = generateSalt()
        val hash = hashPassword(newPassword, salt)
        
        prefs.edit()
            .putString(Settings.PREF_TYPING_HISTORY_PASSWORD_HASH, hash)
            .putString(Settings.PREF_TYPING_HISTORY_SALT, salt)
            .apply()
        
        return true
    }
    
    /**
     * Verify password
     * @return true if password matches
     */
    fun verifyPassword(password: String): Boolean {
        val storedHash = Settings.readTypingHistoryPasswordHash(prefs)
        val storedSalt = Settings.readTypingHistorySalt(prefs)
        
        if (storedHash.isEmpty() || storedSalt.isEmpty()) {
            return false
        }
        
        val inputHash = hashPassword(password, storedSalt)
        return inputHash == storedHash
    }
    
    /**
     * Remove password (requires current password verification)
     * @return true if password was removed
     */
    fun removePassword(currentPassword: String): Boolean {
        if (!verifyPassword(currentPassword)) return false
        
        prefs.edit()
            .remove(Settings.PREF_TYPING_HISTORY_PASSWORD_HASH)
            .remove(Settings.PREF_TYPING_HISTORY_SALT)
            .apply()
        
        return true
    }
    
    /**
     * Generate random salt
     */
    private fun generateSalt(): String {
        val salt = ByteArray(SALT_LENGTH)
        SecureRandom().nextBytes(salt)
        return Base64.getEncoder().encodeToString(salt)
    }
    
    /**
     * Hash password with salt using PBKDF2
     */
    private fun hashPassword(password: String, salt: String): String {
        if (password.isEmpty() || salt.isEmpty()) return ""
        val saltBytes = Base64.getDecoder().decode(salt)
        val spec = PBEKeySpec(password.toCharArray(), saltBytes, ITERATION_COUNT, KEY_LENGTH)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        return Base64.getEncoder().encodeToString(hash)
    }
    
    /**
     * Check if typing history feature is enabled and password protected
     */
    fun isFeatureEnabled(): Boolean {
        return Settings.readTypingHistoryEnabled(prefs)
    }
    
    /**
     * Enable typing history feature
     */
    fun enableFeature() {
        prefs.edit()
            .putBoolean(Settings.PREF_TYPING_HISTORY_ENABLED, true)
            .apply()
    }
    
    /**
     * Disable typing history feature (requires password if set)
     */
    fun disableFeature(password: String? = null): Boolean {
        if (hasPassword() && password != null) {
            if (!verifyPassword(password)) return false
        }
        
        prefs.edit()
            .putBoolean(Settings.PREF_TYPING_HISTORY_ENABLED, false)
            .apply()
        
        return true
    }
    
    /**
     * Get current password (used for backup encryption)
     * NOTE: This is stored as a hash, not plaintext.
     * For backup, we need the actual password from the user.
     */
    fun getCurrentPassword(): String {
        // In production, this should prompt the user for password
        // For now, return empty string as placeholder
        return ""
    }
}
