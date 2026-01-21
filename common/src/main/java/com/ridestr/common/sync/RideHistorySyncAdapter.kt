package com.ridestr.common.sync

import android.util.Log
import com.ridestr.common.data.RideHistoryRepository
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.RideshareEventKinds
import com.ridestr.common.nostr.relay.RelayManager
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import kotlinx.coroutines.delay

/**
 * Adapter that wraps existing RideHistoryRepository to implement SyncableProfileData.
 *
 * Syncs ride history (order=1) after wallet because:
 * - Ride history may reference payment info
 * - Less critical than wallet funds
 *
 * Uses event kind 30174 (parameterized replaceable).
 * d-tag: "rideshare-history"
 */
class RideHistorySyncAdapter(
    private val rideHistoryRepo: RideHistoryRepository,
    private val nostrService: NostrService
) : SyncableProfileData {

    companion object {
        private const val TAG = "RideHistorySyncAdapter"
    }

    override val kind: Int = RideshareEventKinds.RIDE_HISTORY_BACKUP  // 30174
    override val dTag: String = "rideshare-history"
    override val syncOrder: Int = 1  // After wallet
    override val displayName: String = "Ride History"

    override suspend fun fetchFromNostr(
        signer: NostrSigner,
        relayManager: RelayManager
    ): SyncResult {
        return try {
            Log.d(TAG, "Fetching ride history from Nostr...")

            // Use existing sync method which fetches and replaces local
            val success = rideHistoryRepo.syncFromNostr(nostrService)

            if (success) {
                val rideCount = rideHistoryRepo.rides.value.size
                Log.d(TAG, "Restored $rideCount rides from Nostr")
                SyncResult.Success(rideCount, SyncMetadata.RideHistory(rideCount))
            } else {
                Log.d(TAG, "No ride history found on Nostr")
                SyncResult.NoData("No ride history backup found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching ride history from Nostr", e)
            SyncResult.Error("Failed to restore ride history: ${e.message}", e)
        }
    }

    override suspend fun publishToNostr(
        signer: NostrSigner,
        relayManager: RelayManager
    ): String? {
        return try {
            Log.d(TAG, "Backing up ride history to Nostr...")
            // Call NostrService directly to get the event ID
            val rides = rideHistoryRepo.rides.value
            val stats = rideHistoryRepo.stats.value
            val eventId = nostrService.publishRideHistoryBackup(rides, stats)
            if (eventId != null) {
                Log.d(TAG, "Ride history backed up as event $eventId")
            } else {
                Log.w(TAG, "Failed to backup ride history")
            }
            eventId
        } catch (e: Exception) {
            Log.e(TAG, "Error backing up ride history", e)
            null
        }
    }

    override fun hasLocalData(): Boolean {
        return rideHistoryRepo.hasRides()
    }

    override fun clearLocalData() {
        rideHistoryRepo.clearAllHistory()
        Log.d(TAG, "Cleared ride history")
    }

    override suspend fun hasNostrBackup(
        pubKeyHex: String,
        relayManager: RelayManager
    ): Boolean {
        return try {
            val history = nostrService.fetchRideHistory()
            history != null && history.rides.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for ride history backup", e)
            false
        }
    }
}
