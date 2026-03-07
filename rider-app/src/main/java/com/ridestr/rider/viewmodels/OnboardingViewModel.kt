package com.ridestr.rider.viewmodels

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.ridestr.common.nostr.keys.KeyManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel for the onboarding/key setup screen.
 */
class OnboardingViewModel(application: Application) : AndroidViewModel(application) {

    private val keyManager = KeyManager(application)
    private val syncPrefs = application.getSharedPreferences("ridestr_sync", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        // Check if user already has a key
        if (keyManager.hasKey()) {
            _uiState.value = OnboardingUiState(
                isLoggedIn = true,
                isProfileCompleted = keyManager.isProfileCompleted(),
                npub = keyManager.getNpub(),
                needsProfileSync = syncPrefs.getBoolean("needs_profile_sync", false)
            )
        }
    }

    /**
     * Generate a new random keypair.
     */
    fun generateNewKey() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        val success = keyManager.generateNewKey()
        if (success) {
            _uiState.value = OnboardingUiState(
                isLoggedIn = true,
                npub = keyManager.getNpub(),
                showBackupReminder = true
            )
        } else {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "Failed to generate keypair"
            )
        }
    }

    /**
     * Import an existing key from nsec or hex.
     */
    fun importKey(keyInput: String) {
        if (keyInput.isBlank()) {
            _uiState.value = _uiState.value.copy(error = "Please enter a key")
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        val success = keyManager.importKey(keyInput)
        if (success) {
            keyManager.markProfileCompleted()
            syncPrefs.edit().putBoolean("needs_profile_sync", true).apply()
            _uiState.value = OnboardingUiState(
                isLoggedIn = true,
                isProfileCompleted = true,
                npub = keyManager.getNpub(),
                needsProfileSync = true
            )
        } else {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "Invalid key format. Use nsec1... or hex format."
            )
        }
    }

    /**
     * Get nsec for backup display.
     */
    fun getNsecForBackup(): String? = keyManager.getNsec()

    /**
     * Dismiss the backup reminder.
     */
    fun dismissBackupReminder() {
        _uiState.value = _uiState.value.copy(showBackupReminder = false)
    }

    /**
     * Mark profile sync as completed (clears both persisted and in-memory).
     */
    fun markProfileSyncCompleted() {
        syncPrefs.edit().putBoolean("needs_profile_sync", false).apply()
        _uiState.value = _uiState.value.copy(needsProfileSync = false)
    }

    /**
     * Log out and clear the stored key.
     */
    fun logout() {
        syncPrefs.edit().putBoolean("needs_profile_sync", false).apply()
        keyManager.logout()
        _uiState.value = OnboardingUiState()
    }

    /**
     * Get the KeyManager instance for use elsewhere.
     */
    fun getKeyManager(): KeyManager = keyManager
}

/**
 * UI state for the onboarding screen.
 */
data class OnboardingUiState(
    val isLoggedIn: Boolean = false,
    val isProfileCompleted: Boolean = false,
    val isLoading: Boolean = false,
    val npub: String? = null,
    val error: String? = null,
    val showBackupReminder: Boolean = false,
    val needsProfileSync: Boolean = false
)
