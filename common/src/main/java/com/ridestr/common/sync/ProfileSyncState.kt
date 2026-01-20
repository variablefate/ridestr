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
     * Connecting to Nostr relays.
     */
    object Connecting : ProfileSyncState()

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
     */
    data class Complete(val itemsRestored: Int) : ProfileSyncState()

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
