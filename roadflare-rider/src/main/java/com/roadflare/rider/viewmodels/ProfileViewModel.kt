package com.roadflare.rider.viewmodels

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadflare.common.nostr.NostrService
import com.roadflare.common.nostr.events.UserProfile
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

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

/**
 * ViewModel for profile setup and editing.
 *
 * Port from ridestr ProfileViewModel with:
 * - Package rename com.ridestr -> com.roadflare
 * - AndroidViewModel -> ViewModel + Hilt injection
 * - NostrService injected via constructor instead of manual instantiation
 * - Relay connect/disconnect managed externally (Application-level)
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val nostrService: NostrService
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProfileUiState())
    val uiState: StateFlow<ProfileUiState> = _uiState.asStateFlow()

    private var profileSubscriptionId: String? = null

    init {
        _uiState.value = _uiState.value.copy(
            npub = nostrService.keyManager.getNpub()
        )

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

    fun updateName(name: String) {
        _uiState.value = _uiState.value.copy(name = name)
    }

    fun updateDisplayName(displayName: String) {
        _uiState.value = _uiState.value.copy(displayName = displayName)
    }

    fun updateAbout(about: String) {
        _uiState.value = _uiState.value.copy(about = about)
    }

    fun updatePicture(picture: String) {
        _uiState.value = _uiState.value.copy(picture = picture)
    }

    /**
     * Get the NostrSigner for Blossom upload/delete operations.
     */
    fun getSigner(): NostrSigner? = nostrService.getSigner()

    /**
     * Whether profile picture upload is currently possible (signer available).
     */
    fun canUpload(): Boolean = getSigner() != null

    /**
     * Returns an upload handler that accepts a content Uri and returns the uploaded URL,
     * or null if upload is not possible.
     * TODO: Implement Blossom upload when BlossomService is available.
     */
    fun getUploadHandler(): (suspend (Uri) -> String?)? {
        if (getSigner() == null) return null
        return { _: Uri -> null } // Stub - Blossom upload not yet implemented
    }

    fun updateLightningAddress(lud16: String) {
        _uiState.value = _uiState.value.copy(lightningAddress = lud16)
    }

    fun saveProfile(onComplete: () -> Unit) {
        val state = _uiState.value

        if (state.displayName.isBlank()) {
            _uiState.value = state.copy(error = "Please enter a name")
            return
        }

        _uiState.value = state.copy(isSaving = true, error = null)

        viewModelScope.launch {
            val profile = UserProfile(
                name = state.name.takeIf { it.isNotBlank() },
                displayName = state.displayName.takeIf { it.isNotBlank() },
                about = state.about.takeIf { it.isNotBlank() },
                picture = state.picture.takeIf { it.isNotBlank() },
                lud16 = state.lightningAddress.takeIf { it.isNotBlank() },
                banner = state.existingProfile?.banner,
                website = state.existingProfile?.website,
                nip05 = state.existingProfile?.nip05,
                lud06 = state.existingProfile?.lud06
            )

            val eventId = nostrService.publishProfile(profile)

            if (eventId != null) {
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

    fun skip(onComplete: () -> Unit) {
        nostrService.keyManager.markProfileCompleted()
        onComplete()
    }

    override fun onCleared() {
        super.onCleared()
        profileSubscriptionId?.let { nostrService.closeSubscription(it) }
        nostrService.disconnect()
    }
}
