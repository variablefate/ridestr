package com.roadflare.rider.viewmodels

import androidx.lifecycle.ViewModel
import com.roadflare.common.nostr.keys.KeyManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * UI state for the onboarding screen.
 */
data class OnboardingUiState(
    val isLoggedIn: Boolean = false,
    val isProfileCompleted: Boolean = false,
    val isLoading: Boolean = false,
    val npub: String? = null,
    val error: String? = null,
    val showBackupReminder: Boolean = false
)

/**
 * ViewModel for the onboarding / key setup screen.
 *
 * Port from ridestr OnboardingViewModel with:
 * - Package rename com.ridestr -> com.roadflare
 * - AndroidViewModel -> ViewModel + Hilt injection
 * - KeyManager injected via constructor instead of manual instantiation
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val keyManager: KeyManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    init {
        // Check if user already has a key
        if (keyManager.hasKey()) {
            _uiState.value = OnboardingUiState(
                isLoggedIn = true,
                isProfileCompleted = keyManager.isProfileCompleted(),
                npub = keyManager.getNpub()
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
            _uiState.value = OnboardingUiState(
                isLoggedIn = true,
                isProfileCompleted = true,
                npub = keyManager.getNpub()
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
     * Log out and clear the stored key.
     */
    fun logout() {
        keyManager.logout()
        _uiState.value = OnboardingUiState()
    }

    /**
     * Re-read keyManager state and update uiState.
     * Needed because keyManager is SharedPreferences-based (not reactive),
     * so the ViewModel must explicitly re-read after profile/onboarding changes.
     */
    fun refreshState() {
        _uiState.value = _uiState.value.copy(
            isLoggedIn = keyManager.hasKey(),
            isProfileCompleted = keyManager.isProfileCompleted(),
            isLoading = false
        )
    }

    /**
     * Get the KeyManager instance for use elsewhere.
     */
    fun getKeyManager(): KeyManager = keyManager
}
