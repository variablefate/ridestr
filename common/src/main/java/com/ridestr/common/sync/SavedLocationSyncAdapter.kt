package com.ridestr.common.sync

import android.util.Log
import com.ridestr.common.data.SavedLocation
import com.ridestr.common.data.SavedLocationRepository
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.RideshareEventKinds
import com.ridestr.common.nostr.events.SavedLocationBackupData
import com.ridestr.common.nostr.events.SavedLocationBackupEvent
import com.ridestr.common.nostr.relay.RelayManager
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner

/**
 * Adapter that syncs saved locations to/from Nostr for the rider app.
 *
 * Syncs saved locations (order=3) after wallet, ride history, and vehicles because:
 * - Saved locations are convenience data, not critical
 * - User can function without them on first login
 *
 * Uses event kind 30176 (parameterized replaceable).
 * d-tag: "rideshare-locations"
 */
class SavedLocationSyncAdapter(
    private val savedLocationRepository: SavedLocationRepository,
    private val nostrService: NostrService
) : SyncableProfileData {

    companion object {
        private const val TAG = "SavedLocationSyncAdapter"
    }

    override val kind: Int = RideshareEventKinds.SAVED_LOCATIONS_BACKUP  // 30176
    override val dTag: String = SavedLocationBackupEvent.D_TAG  // "rideshare-locations"
    override val syncOrder: Int = 3  // After wallet, history, and vehicles
    override val displayName: String = "Saved Locations"

    override suspend fun fetchFromNostr(
        signer: NostrSigner,
        relayManager: RelayManager
    ): SyncResult {
        return try {
            Log.d(TAG, "Fetching saved locations from Nostr...")

            val backupData = nostrService.fetchSavedLocationBackup()

            if (backupData != null && backupData.locations.isNotEmpty()) {
                // Replace local locations with backup
                restoreLocations(backupData.locations)
                Log.d(TAG, "Restored ${backupData.locations.size} saved locations from Nostr")
                SyncResult.Success(backupData.locations.size)
            } else {
                Log.d(TAG, "No saved location backup found on Nostr")
                SyncResult.NoData("No saved location backup found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching saved locations from Nostr", e)
            SyncResult.Error("Failed to restore saved locations: ${e.message}", e)
        }
    }

    override suspend fun publishToNostr(
        signer: NostrSigner,
        relayManager: RelayManager
    ): String? {
        return try {
            Log.d(TAG, "Backing up saved locations to Nostr...")

            val locations = savedLocationRepository.savedLocations.value
            if (locations.isEmpty()) {
                Log.d(TAG, "No saved locations to backup")
                return null
            }

            val eventId = nostrService.backupSavedLocations(locations)
            if (eventId != null) {
                Log.d(TAG, "Saved locations backed up as event $eventId")
            } else {
                Log.w(TAG, "Failed to backup saved locations")
            }
            eventId
        } catch (e: Exception) {
            Log.e(TAG, "Error backing up saved locations", e)
            null
        }
    }

    override fun hasLocalData(): Boolean {
        return savedLocationRepository.hasLocations()
    }

    override fun clearLocalData() {
        savedLocationRepository.clearAll()
        Log.d(TAG, "Cleared saved locations")
    }

    override suspend fun hasNostrBackup(
        pubKeyHex: String,
        relayManager: RelayManager
    ): Boolean {
        return try {
            val backup = nostrService.fetchSavedLocationBackup()
            backup != null && backup.locations.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for saved location backup", e)
            false
        }
    }

    /**
     * Restore locations from backup, replacing local data.
     */
    private fun restoreLocations(locations: List<SavedLocation>) {
        savedLocationRepository.restoreFromBackup(locations)
    }
}
