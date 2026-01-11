package com.ridestr.app.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ridestr.app.nostr.NostrService
import com.ridestr.app.nostr.events.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for profile setup and editing.
 */
class ProfileViewModel(application: Application) : AndroidViewModel(application) {

    private val nostrService = NostrService(application)

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private var profileSubscriptionId: String? = null

    init {
        // Load initial npub
        _uiState.value = _uiState.value.copy(
            npub = nostrService.keyManager.getNpub()
        )

        // Connect to relays and fetch existing profile
        nostrService.connect()
        subscribeToOwnProfile()
    }

    private fun subscribeToOwnProfile() {
        profileSubscriptionId = nostrService.subscribeToOwnProfile { profile ->
            _uiState.value = _uiState.value.copy(
                name = profile.name ?: _uiState.value.name,
                displayName = profile.displayName ?: _uiState.value.displayName,
                about = profile.about ?: _uiState.value.about,
                picture = profile.picture ?: _uiState.value.picture,
                lightningAddress = profile.lud16 ?: _uiState.value.lightningAddress,
                existingProfile = profile
            )
        }
    }

    /**
     * Update the name field.
     */
    fun updateName(name: String) {
        _uiState.value = _uiState.value.copy(name = name)
    }

    /**
     * Update the display name field.
     */
    fun updateDisplayName(displayName: String) {
        _uiState.value = _uiState.value.copy(displayName = displayName)
    }

    /**
     * Update the about field.
     */
    fun updateAbout(about: String) {
        _uiState.value = _uiState.value.copy(about = about)
    }

    /**
     * Update the picture URL field.
     */
    fun updatePicture(picture: String) {
        _uiState.value = _uiState.value.copy(picture = picture)
    }

    /**
     * Update the lightning address field.
     */
    fun updateLightningAddress(lud16: String) {
        _uiState.value = _uiState.value.copy(lightningAddress = lud16)
    }

    /**
     * Save the profile to Nostr.
     */
    fun saveProfile(onComplete: () -> Unit) {
        val state = _uiState.value

        _uiState.value = state.copy(isSaving = true, error = null)

        viewModelScope.launch {
            val profile = UserProfile(
                name = state.name.takeIf { it.isNotBlank() },
                displayName = state.displayName.takeIf { it.isNotBlank() },
                about = state.about.takeIf { it.isNotBlank() },
                picture = state.picture.takeIf { it.isNotBlank() },
                lud16 = state.lightningAddress.takeIf { it.isNotBlank() },
                // Preserve existing fields we're not editing
                banner = state.existingProfile?.banner,
                website = state.existingProfile?.website,
                nip05 = state.existingProfile?.nip05,
                lud06 = state.existingProfile?.lud06
            )

            val eventId = nostrService.publishProfile(profile)

            if (eventId != null) {
                // Mark profile as completed
                nostrService.keyManager.markProfileCompleted()
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    saveSuccess = true
                )
                onComplete()
            } else {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = "Failed to save profile. Please try again."
                )
            }
        }
    }

    /**
     * Skip profile setup.
     */
    fun skip(onComplete: () -> Unit) {
        // Mark profile as completed even if skipped
        nostrService.keyManager.markProfileCompleted()
        onComplete()
    }

    override fun onCleared() {
        super.onCleared()
        profileSubscriptionId?.let { nostrService.closeSubscription(it) }
        nostrService.disconnect()
    }
}

/**
 * UI state for profile setup.
 */
data class ProfileUiState(
    val npub: String? = null,
    val name: String = "",
    val displayName: String = "",
    val about: String = "",
    val picture: String = "",
    val lightningAddress: String = "",
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    val error: String? = null,
    val existingProfile: UserProfile? = null
)
