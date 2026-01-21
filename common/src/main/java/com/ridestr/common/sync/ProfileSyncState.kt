package com.ridestr.common.sync

/**
 * Observable state for profile sync operations.
 * Exposed via ProfileSyncManager.syncState for UI observation.
 */
sealed class ProfileSyncState {
    /**
     * No sync operation in progress.
     */
    object Idle : ProfileSyncState()

    /**
     * Checking if there's any Ridestr data to restore.
     */
    object Checking : ProfileSyncState()

    /**
     * Connecting to Nostr relays.
     */
    object Connecting : ProfileSyncState()

    /**
     * No Ridestr data found on relays.
     * User can continue with fresh profile.
     */
    object NoDataFound : ProfileSyncState()

    /**
     * Fetching data from Nostr (restore flow).
     * @param dataType Name of the data type being synced (e.g., "Wallet", "Ride History")
     * @param progress Optional progress indicator (0.0 to 1.0)
     */
    data class Syncing(
        val dataType: String,
        val progress: Float? = null
    ) : ProfileSyncState()

    /**
     * Publishing data to Nostr (backup flow).
     * @param dataType Name of the data type being backed up
     */
    data class Backing(val dataType: String) : ProfileSyncState()

    /**
     * Sync completed successfully.
     * @param itemsRestored Total number of items restored across all data types
     * @param restoredData Breakdown of what was restored
     */
    data class Complete(
        val itemsRestored: Int,
        val restoredData: RestoredProfileData = RestoredProfileData()
    ) : ProfileSyncState()

    /**
     * Error during sync operation.
     * @param message Human-readable error description
     * @param retryable Whether the operation can be retried
     */
    data class Error(
        val message: String,
        val retryable: Boolean = true
    ) : ProfileSyncState()
}

/**
 * Detailed breakdown of what profile data was restored.
 * Used to show user what was found during sync.
 */
data class RestoredProfileData(
    val walletBalance: Long? = null,      // Sats restored from NIP-60
    val vehicleCount: Int = 0,             // Vehicles restored (driver)
    val savedLocationCount: Int = 0,       // Saved locations restored (rider)
    val rideHistoryCount: Int = 0,         // Ride history entries restored
    val settingsRestored: Boolean = false  // Whether settings were restored
)
