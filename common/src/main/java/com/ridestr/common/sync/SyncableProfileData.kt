package com.ridestr.common.sync

import com.ridestr.common.nostr.relay.RelayManager
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner

/**
 * Interface for profile data that can be synced to/from Nostr relays.
 *
 * Each implementation handles its own event format and NIP-44 encryption.
 * All data is encrypted to self (user's own pubkey) for privacy.
 *
 * Implementations:
 * - Nip60WalletSyncAdapter (order=0, kind=7375/17375)
 * - RideHistorySyncAdapter (order=1, kind=30174)
 * - VehicleSyncAdapter (order=2, kind=30175)
 * - SavedLocationSyncAdapter (order=3, kind=30176)
 */
interface SyncableProfileData {
    /**
     * Nostr event kind for this data type.
     * Use established NIPs where applicable (e.g., 7375 for wallet proofs per NIP-60).
     */
    val kind: Int

    /**
     * d-tag for parameterized replaceable events (kind 30000-39999).
     * Only the latest event per d-tag is kept by relays.
     * For non-replaceable events, return empty string.
     */
    val dTag: String

    /**
     * Sync order determines the sequence during restore.
     * Lower values are synced first.
     *
     * Recommended ordering:
     * - 0: Wallet (needed for payments)
     * - 1: Ride History (may reference payments)
     * - 2: Vehicles (driver profile data)
     * - 3: Saved Locations (rider convenience)
     */
    val syncOrder: Int

    /**
     * Human-readable name for logging and UI feedback.
     */
    val displayName: String

    /**
     * Fetch data from Nostr and merge/replace local state.
     *
     * Called during:
     * - First login with imported key (restore from backup)
     * - Manual refresh triggered by user
     *
     * @param signer User's signer for NIP-44 decryption
     * @param relayManager Connected relay manager
     * @return SyncResult indicating success, no data, or error
     */
    suspend fun fetchFromNostr(
        signer: NostrSigner,
        relayManager: RelayManager
    ): SyncResult

    /**
     * Publish current local state to Nostr.
     *
     * Called when:
     * - User modifies data locally (auto-backup)
     * - Manual backup triggered by user
     *
     * @param signer User's signer for NIP-44 encryption and signing
     * @param relayManager Connected relay manager
     * @return Event ID if successful, null on failure
     */
    suspend fun publishToNostr(
        signer: NostrSigner,
        relayManager: RelayManager
    ): String?

    /**
     * Check if there is local data worth backing up.
     */
    fun hasLocalData(): Boolean

    /**
     * Clear all local data (called during logout).
     */
    fun clearLocalData()

    /**
     * Check if a backup exists on Nostr without fetching full data.
     * Used during onboarding to detect existing wallet/profile.
     *
     * @param pubKeyHex User's public key in hex
     * @param relayManager Connected relay manager
     * @return true if backup exists on relays
     */
    suspend fun hasNostrBackup(
        pubKeyHex: String,
        relayManager: RelayManager
    ): Boolean = false  // Default implementation, override if needed
}

/**
 * Result of a sync operation.
 */
sealed class SyncResult {
    /**
     * Successfully synced data.
     * @param itemCount Number of items synced (proofs, rides, vehicles, etc.)
     * @param metadata Optional metadata about what was synced (for UI display)
     */
    data class Success(
        val itemCount: Int,
        val metadata: SyncMetadata? = null
    ) : SyncResult()

    /**
     * No data found on relays (not an error - user may be new or data deleted).
     * @param reason Human-readable explanation
     */
    data class NoData(val reason: String) : SyncResult()

    /**
     * Error during sync operation.
     * @param message Human-readable error description
     * @param exception Optional underlying exception for logging
     */
    data class Error(
        val message: String,
        val exception: Exception? = null
    ) : SyncResult()
}

/**
 * Metadata about synced data, used for UI display.
 */
sealed class SyncMetadata {
    /** Wallet sync result with balance */
    data class Wallet(val balanceSats: Long) : SyncMetadata()

    /** Vehicle sync result */
    data class Vehicles(val count: Int) : SyncMetadata()

    /** Saved locations sync result */
    data class SavedLocations(val count: Int) : SyncMetadata()

    /** Ride history sync result */
    data class RideHistory(val count: Int) : SyncMetadata()

    /** Profile sync result (may include vehicles, locations, settings) */
    data class Profile(
        val vehicleCount: Int = 0,
        val savedLocationCount: Int = 0,
        val settingsRestored: Boolean = false
    ) : SyncMetadata()
}
