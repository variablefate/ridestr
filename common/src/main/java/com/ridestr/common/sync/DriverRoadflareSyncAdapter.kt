package com.ridestr.common.sync

import android.util.Log
import com.ridestr.common.data.DriverRoadflareRepository
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.RideshareEventKinds
import com.ridestr.common.nostr.events.DriverRoadflareStateEvent
import com.ridestr.common.nostr.relay.RelayManager
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner

/**
 * Sync adapter for driver's RoadFlare state (RoadFlare feature).
 *
 * Syncs Kind 30012 events containing:
 * - RoadFlare keypair (for encrypting location broadcasts)
 * - Followers list (who has received the decryption key)
 * - Muted riders list (excluded from broadcasts)
 * - DND (Do Not Disturb) status
 *
 * This is CRITICAL for cross-device sync:
 * - When driver imports key on new device, this restores the RoadFlare keypair
 * - Without this, driver would need to rotate key and re-share with all followers
 *
 * Sync order = 3 (after wallet, profile, history) because:
 * - RoadFlare is an optional feature
 * - Doesn't affect core ride functionality
 *
 * Uses d-tag: "roadflare-state"
 * Content is NIP-44 encrypted to self.
 */
class DriverRoadflareSyncAdapter(
    private val repository: DriverRoadflareRepository,
    private val nostrService: NostrService
) : SyncableProfileData {

    companion object {
        private const val TAG = "DriverRoadflareSync"
    }

    override val kind: Int = RideshareEventKinds.ROADFLARE_DRIVER_STATE  // 30012
    override val dTag: String = DriverRoadflareStateEvent.D_TAG  // "roadflare-state"
    override val syncOrder: Int = 3  // After wallet, profile, history
    override val displayName: String = "RoadFlare Settings"

    override suspend fun fetchFromNostr(
        signer: NostrSigner,
        relayManager: RelayManager
    ): SyncResult {
        return try {
            Log.d(TAG, "Fetching driver RoadFlare state from Nostr...")

            val state = nostrService.fetchDriverRoadflareState()

            if (state != null) {
                // Replace local state with Nostr data
                repository.restoreFromBackup(state)

                val hasKey = state.roadflareKey != null
                val followerCount = state.followers.size
                val dndActive = state.dndActive

                Log.d(TAG, "Restored RoadFlare state: key=${hasKey}, followers=$followerCount, DND=$dndActive")
                SyncResult.Success(
                    followerCount,
                    SyncMetadata.DriverRoadflare(hasKey, followerCount, dndActive)
                )
            } else {
                Log.d(TAG, "No RoadFlare state found on Nostr")
                SyncResult.NoData("No RoadFlare settings backup found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching RoadFlare state from Nostr", e)
            SyncResult.Error("Failed to restore RoadFlare settings: ${e.message}", e)
        }
    }

    override suspend fun publishToNostr(
        signer: NostrSigner,
        relayManager: RelayManager
    ): String? {
        return try {
            val state = repository.state.value
            if (state == null) {
                Log.d(TAG, "No RoadFlare state to backup")
                return null
            }

            // Only backup if there's meaningful data (has key or followers)
            if (state.roadflareKey == null && state.followers.isEmpty()) {
                Log.d(TAG, "RoadFlare state is empty, skipping backup")
                return null
            }

            Log.d(TAG, "Backing up RoadFlare state: key v${state.roadflareKey?.version}, ${state.followers.size} followers...")
            val eventId = nostrService.publishDriverRoadflareState(signer, state)

            if (eventId != null) {
                Log.d(TAG, "RoadFlare state backed up as event $eventId")
            } else {
                Log.w(TAG, "Failed to backup RoadFlare state")
            }
            eventId
        } catch (e: Exception) {
            Log.e(TAG, "Error backing up RoadFlare state", e)
            null
        }
    }

    override fun hasLocalData(): Boolean {
        return repository.hasActiveSetup()
    }

    override fun clearLocalData() {
        repository.clearAll()
        Log.d(TAG, "Cleared RoadFlare state")
    }

    override suspend fun hasNostrBackup(
        pubKeyHex: String,
        relayManager: RelayManager
    ): Boolean {
        return try {
            val state = nostrService.fetchDriverRoadflareState()
            state != null && (state.roadflareKey != null || state.followers.isNotEmpty())
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for RoadFlare state backup", e)
            false
        }
    }
}
