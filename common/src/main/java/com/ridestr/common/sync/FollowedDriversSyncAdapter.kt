package com.ridestr.common.sync

import android.util.Log
import com.ridestr.common.data.FollowedDriversRepository
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.RideshareEventKinds
import com.ridestr.common.nostr.events.FollowedDriversEvent
import com.ridestr.common.nostr.relay.RelayManager
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner

/**
 * Sync adapter for rider's followed drivers list (RoadFlare feature).
 *
 * Syncs Kind 30011 events containing:
 * - List of followed drivers with their pubkeys and names
 * - RoadFlare decryption keys for each driver
 *
 * Sync order = 3 (after wallet, profile, history) because:
 * - RoadFlare is an optional feature
 * - Doesn't affect core ride functionality
 *
 * Uses d-tag: "roadflare-drivers"
 * Content is NIP-44 encrypted to self.
 */
class FollowedDriversSyncAdapter(
    private val repository: FollowedDriversRepository,
    private val nostrService: NostrService
) : SyncableProfileData {

    companion object {
        private const val TAG = "FollowedDriversSync"
    }

    override val kind: Int = RideshareEventKinds.ROADFLARE_FOLLOWED_DRIVERS  // 30011
    override val dTag: String = FollowedDriversEvent.D_TAG  // "roadflare-drivers"
    override val syncOrder: Int = 3  // After wallet, profile, history
    override val displayName: String = "Favorite Drivers"

    override suspend fun fetchFromNostr(
        signer: NostrSigner,
        relayManager: RelayManager
    ): SyncResult {
        return try {
            Log.d(TAG, "Fetching followed drivers from Nostr...")

            val data = nostrService.fetchFollowedDrivers()

            if (data != null && data.drivers.isNotEmpty()) {
                // Replace local data with Nostr data
                repository.replaceAll(data.drivers)
                val count = data.drivers.size
                Log.d(TAG, "Restored $count followed drivers from Nostr")
                SyncResult.Success(count, SyncMetadata.FollowedDrivers(count))
            } else {
                Log.d(TAG, "No followed drivers found on Nostr")
                SyncResult.NoData("No favorite drivers backup found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching followed drivers from Nostr", e)
            SyncResult.Error("Failed to restore favorite drivers: ${e.message}", e)
        }
    }

    override suspend fun publishToNostr(
        signer: NostrSigner,
        relayManager: RelayManager
    ): String? {
        return try {
            val drivers = repository.getAll()

            // Publish even when empty - replaces old event and removes p-tags
            Log.d(TAG, "Backing up ${drivers.size} followed drivers to Nostr...")
            val eventId = nostrService.publishFollowedDrivers(drivers)

            if (eventId != null) {
                Log.d(TAG, "Followed drivers backed up as event $eventId (${drivers.size} drivers)")
            } else {
                Log.w(TAG, "Failed to backup followed drivers")
            }
            eventId
        } catch (e: Exception) {
            Log.e(TAG, "Error backing up followed drivers", e)
            null
        }
    }

    override fun hasLocalData(): Boolean {
        return repository.hasDrivers()
    }

    override fun clearLocalData() {
        repository.clearAll()
        Log.d(TAG, "Cleared followed drivers")
    }

    override suspend fun hasNostrBackup(
        pubKeyHex: String,
        relayManager: RelayManager
    ): Boolean {
        return try {
            val data = nostrService.fetchFollowedDrivers()
            data != null && data.drivers.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for followed drivers backup", e)
            false
        }
    }
}
