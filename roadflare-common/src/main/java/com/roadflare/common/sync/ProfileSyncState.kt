package com.roadflare.common.sync

/**
 * State machine for profile data sync during onboarding.
 */
sealed interface ProfileSyncState {
    data object Idle : ProfileSyncState
    data object Checking : ProfileSyncState
    data object Connecting : ProfileSyncState
    data class Syncing(val dataType: String, val progress: Float?) : ProfileSyncState
    data object NoDataFound : ProfileSyncState
    data class Complete(val restoredData: RestoredProfileData) : ProfileSyncState
    data class Error(val message: String, val retryable: Boolean = true) : ProfileSyncState
    data class Backing(val dataType: String) : ProfileSyncState
}

/**
 * Summary of what was restored during profile sync.
 */
data class RestoredProfileData(
    val vehicleCount: Int = 0,
    val savedLocationCount: Int = 0,
    val rideHistoryCount: Int = 0,
    val settingsRestored: Boolean = false
)
