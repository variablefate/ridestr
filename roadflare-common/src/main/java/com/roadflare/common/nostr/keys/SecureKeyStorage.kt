package com.roadflare.common.nostr.keys

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
        private const val PREFS_FILE = "roadflare_secure_keys"
        private const val KEY_PRIVATE_KEY = "nostr_private_key"
        private const val KEY_PROFILE_COMPLETED = "profile_completed"
        private const val KEY_USER_MODE = "user_mode"
    }

    enum class UserMode {
        RIDER,
        DRIVER
    }

    private val prefs: SharedPreferences
    private var isUsingUnencryptedFallback = false

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
            isUsingUnencryptedFallback = true
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    fun isUsingFallback(): Boolean = isUsingUnencryptedFallback

    fun savePrivateKey(privateKeyHex: String) {
        prefs.edit().putString(KEY_PRIVATE_KEY, privateKeyHex).apply()
        Log.d(TAG, "Private key saved")
    }

    fun getPrivateKey(): String? {
        return prefs.getString(KEY_PRIVATE_KEY, null)
    }

    fun hasKey(): Boolean {
        return prefs.contains(KEY_PRIVATE_KEY) && !prefs.getString(KEY_PRIVATE_KEY, null).isNullOrEmpty()
    }

    fun clearKey() {
        prefs.edit().remove(KEY_PRIVATE_KEY).apply()
        Log.d(TAG, "Private key cleared")
    }

    fun saveProfileCompleted() {
        prefs.edit().putBoolean(KEY_PROFILE_COMPLETED, true).apply()
    }

    fun isProfileCompleted(): Boolean {
        return prefs.getBoolean(KEY_PROFILE_COMPLETED, false)
    }

    fun clearProfileStatus() {
        prefs.edit().remove(KEY_PROFILE_COMPLETED).apply()
    }

    fun saveUserMode(mode: UserMode) {
        prefs.edit().putString(KEY_USER_MODE, mode.name).apply()
    }

    fun getUserMode(): UserMode {
        val modeName = prefs.getString(KEY_USER_MODE, UserMode.RIDER.name)
        return try {
            UserMode.valueOf(modeName ?: UserMode.RIDER.name)
        } catch (e: Exception) {
            UserMode.RIDER
        }
    }
}
