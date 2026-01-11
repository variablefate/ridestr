package com.ridestr.common.nostr.keys

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for Nostr private keys using Android's EncryptedSharedPreferences.
 * The private key (nsec) is stored encrypted at rest.
 */
class SecureKeyStorage(context: Context) {

    companion object {
        private const val TAG = "SecureKeyStorage"
        private const val PREFS_FILE = "ridestr_secure_keys"
        private const val KEY_PRIVATE_KEY = "nostr_private_key"
        private const val KEY_PROFILE_COMPLETED = "profile_completed"
        private const val KEY_USER_MODE = "user_mode"
    }

    /**
     * User mode - either RIDER or DRIVER.
     */
    enum class UserMode {
        RIDER,
        DRIVER
    }

    private val prefs: SharedPreferences

    init {
        prefs = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create encrypted prefs, falling back to regular prefs", e)
            // Fallback for emulators or devices without hardware-backed keystore
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    /**
     * Save the private key (hex format) securely.
     */
    fun savePrivateKey(privateKeyHex: String) {
        prefs.edit().putString(KEY_PRIVATE_KEY, privateKeyHex).apply()
        Log.d(TAG, "Private key saved")
    }

    /**
     * Retrieve the stored private key (hex format).
     * @return The private key hex string, or null if not stored
     */
    fun getPrivateKey(): String? {
        return prefs.getString(KEY_PRIVATE_KEY, null)
    }

    /**
     * Check if a private key is stored.
     */
    fun hasKey(): Boolean {
        return prefs.contains(KEY_PRIVATE_KEY) && !prefs.getString(KEY_PRIVATE_KEY, null).isNullOrEmpty()
    }

    /**
     * Clear the stored private key.
     */
    fun clearKey() {
        prefs.edit().remove(KEY_PRIVATE_KEY).apply()
        Log.d(TAG, "Private key cleared")
    }

    /**
     * Mark profile setup as completed.
     */
    fun saveProfileCompleted() {
        prefs.edit().putBoolean(KEY_PROFILE_COMPLETED, true).apply()
        Log.d(TAG, "Profile marked as completed")
    }

    /**
     * Check if profile setup has been completed.
     */
    fun isProfileCompleted(): Boolean {
        return prefs.getBoolean(KEY_PROFILE_COMPLETED, false)
    }

    /**
     * Clear profile completion status (for logout).
     */
    fun clearProfileStatus() {
        prefs.edit().remove(KEY_PROFILE_COMPLETED).apply()
        Log.d(TAG, "Profile status cleared")
    }

    /**
     * Save the user mode (RIDER or DRIVER).
     */
    fun saveUserMode(mode: UserMode) {
        prefs.edit().putString(KEY_USER_MODE, mode.name).apply()
        Log.d(TAG, "User mode saved: $mode")
    }

    /**
     * Get the current user mode. Defaults to RIDER.
     */
    fun getUserMode(): UserMode {
        val modeName = prefs.getString(KEY_USER_MODE, UserMode.RIDER.name)
        return try {
            UserMode.valueOf(modeName ?: UserMode.RIDER.name)
        } catch (e: Exception) {
            UserMode.RIDER
        }
    }
}
