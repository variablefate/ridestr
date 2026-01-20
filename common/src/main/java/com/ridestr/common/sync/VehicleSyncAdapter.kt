package com.ridestr.common.sync

import android.util.Log
import com.ridestr.common.data.Vehicle
import com.ridestr.common.data.VehicleRepository
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.RideshareEventKinds
import com.ridestr.common.nostr.events.VehicleBackupData
import com.ridestr.common.nostr.events.VehicleBackupEvent
import com.ridestr.common.nostr.relay.RelayManager
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner

/**
 * Adapter that syncs vehicles to/from Nostr for the driver app.
 *
 * Syncs vehicles (order=2) after wallet and ride history because:
 * - Vehicles are profile data, not payment-critical
 * - Less time-sensitive than wallet restoration
 *
 * Uses event kind 30175 (parameterized replaceable).
 * d-tag: "rideshare-vehicles"
 *
 * @deprecated Use [ProfileSyncAdapter] instead. Vehicles are now part of unified profile backup (Kind 30177).
 */
@Deprecated("Use ProfileSyncAdapter instead", ReplaceWith("ProfileSyncAdapter(vehicleRepository, null, settingsManager, nostrService)"))
class VehicleSyncAdapter(
    private val vehicleRepository: VehicleRepository,
    private val nostrService: NostrService
) : SyncableProfileData {

    companion object {
        private const val TAG = "VehicleSyncAdapter"
    }

    override val kind: Int = RideshareEventKinds.VEHICLE_BACKUP  // 30175
    override val dTag: String = VehicleBackupEvent.D_TAG  // "rideshare-vehicles"
    override val syncOrder: Int = 2  // After wallet and history
    override val displayName: String = "Vehicles"

    override suspend fun fetchFromNostr(
        signer: NostrSigner,
        relayManager: RelayManager
    ): SyncResult {
        return try {
            Log.d(TAG, "Fetching vehicles from Nostr...")

            val backupData = nostrService.fetchVehicleBackup()

            if (backupData != null && backupData.vehicles.isNotEmpty()) {
                // Replace local vehicles with backup
                restoreVehicles(backupData.vehicles)
                Log.d(TAG, "Restored ${backupData.vehicles.size} vehicles from Nostr")
                SyncResult.Success(backupData.vehicles.size)
            } else {
                Log.d(TAG, "No vehicle backup found on Nostr")
                SyncResult.NoData("No vehicle backup found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching vehicles from Nostr", e)
            SyncResult.Error("Failed to restore vehicles: ${e.message}", e)
        }
    }

    override suspend fun publishToNostr(
        signer: NostrSigner,
        relayManager: RelayManager
    ): String? {
        return try {
            Log.d(TAG, "Backing up vehicles to Nostr...")

            val vehicles = vehicleRepository.vehicles.value
            if (vehicles.isEmpty()) {
                Log.d(TAG, "No vehicles to backup")
                return null
            }

            val eventId = nostrService.backupVehicles(vehicles)
            if (eventId != null) {
                Log.d(TAG, "Vehicles backed up as event $eventId")
            } else {
                Log.w(TAG, "Failed to backup vehicles")
            }
            eventId
        } catch (e: Exception) {
            Log.e(TAG, "Error backing up vehicles", e)
            null
        }
    }

    override fun hasLocalData(): Boolean {
        return vehicleRepository.hasVehicles()
    }

    override fun clearLocalData() {
        vehicleRepository.clearAll()
        Log.d(TAG, "Cleared vehicles")
    }

    override suspend fun hasNostrBackup(
        pubKeyHex: String,
        relayManager: RelayManager
    ): Boolean {
        return try {
            val backup = nostrService.fetchVehicleBackup()
            backup != null && backup.vehicles.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for vehicle backup", e)
            false
        }
    }

    /**
     * Restore vehicles from backup, replacing local data.
     */
    private fun restoreVehicles(vehicles: List<Vehicle>) {
        // Clear existing vehicles
        vehicleRepository.clearAll()

        // Add each vehicle from backup
        vehicles.forEach { vehicle ->
            vehicleRepository.addVehicle(vehicle)
        }
    }
}
