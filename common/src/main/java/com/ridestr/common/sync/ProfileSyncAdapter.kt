package com.ridestr.common.sync

import android.util.Log
import com.ridestr.common.data.SavedLocation
import com.ridestr.common.data.SavedLocationRepository
import com.ridestr.common.data.Vehicle
import com.ridestr.common.data.VehicleRepository
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.ProfileBackupEvent
import com.ridestr.common.nostr.events.RideshareEventKinds
import com.ridestr.common.nostr.relay.RelayManager
import com.ridestr.common.settings.SettingsManager
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner

/**
 * Adapter that syncs unified profile data to/from Nostr.
 * Consolidates vehicles, saved locations, and settings into a single backup event.
 *
 * Syncs profile (order=1) after wallet because:
 * - Settings may affect app behavior
 * - Vehicles/locations are needed for rider/driver flow
 *
 * Uses event kind 30177 (parameterized replaceable).
 * d-tag: "rideshare-profile"
 *
 * Migration: On fetch, if no 30177 event found, falls back to separate
 * 30175 (vehicles) and 30176 (locations) events for backward compatibility.
 */
class ProfileSyncAdapter(
    private val vehicleRepository: VehicleRepository?,
    private val savedLocationRepository: SavedLocationRepository?,
    private val settingsManager: SettingsManager,
    private val nostrService: NostrService
) : SyncableProfileData {

    companion object {
        private const val TAG = "ProfileSyncAdapter"
    }

    override val kind: Int = RideshareEventKinds.PROFILE_BACKUP  // 30177
    override val dTag: String = ProfileBackupEvent.D_TAG  // "rideshare-profile"
    override val syncOrder: Int = 1  // After wallet (0), before history (2)
    override val displayName: String = "Profile"

    override suspend fun fetchFromNostr(
        signer: NostrSigner,
        relayManager: RelayManager
    ): SyncResult {
        return try {
            Log.d(TAG, "Fetching profile from Nostr...")

            // Try to fetch unified profile backup (Kind 30177)
            val profileData = nostrService.fetchProfileBackup()

            if (profileData != null) {
                // Restore from unified backup
                var itemsRestored = 0
                var restoredVehicles = 0
                var restoredLocations = 0

                // Restore vehicles (driver app)
                if (profileData.vehicles.isNotEmpty()) {
                    vehicleRepository?.let { repo ->
                        restoreVehicles(repo, profileData.vehicles)
                        restoredVehicles = profileData.vehicles.size
                        itemsRestored += restoredVehicles
                        Log.d(TAG, "Restored $restoredVehicles vehicles")
                    }
                }

                // Restore saved locations (rider app)
                if (profileData.savedLocations.isNotEmpty()) {
                    savedLocationRepository?.let { repo ->
                        restoreLocations(repo, profileData.savedLocations)
                        restoredLocations = profileData.savedLocations.size
                        itemsRestored += restoredLocations
                        Log.d(TAG, "Restored $restoredLocations saved locations")
                    }
                }

                // Restore settings
                val hadSettings = profileData.settings != null
                settingsManager.restoreFromBackup(profileData.settings)
                Log.d(TAG, "Restored settings")

                val metadata = SyncMetadata.Profile(
                    vehicleCount = restoredVehicles,
                    savedLocationCount = restoredLocations,
                    settingsRestored = hadSettings
                )

                if (itemsRestored > 0) {
                    SyncResult.Success(itemsRestored, metadata)
                } else {
                    // Settings were restored but no vehicles/locations
                    SyncResult.Success(1, metadata)  // Count settings as 1 item
                }
            } else {
                // No unified profile found - try legacy migration
                Log.d(TAG, "No unified profile found, trying legacy migration...")
                migrateFromLegacyBackups()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching profile from Nostr", e)
            SyncResult.Error("Failed to restore profile: ${e.message}", e)
        }
    }

    /**
     * Migrate from legacy separate backups (30175, 30176) to unified profile.
     */
    @Suppress("DEPRECATION")
    private suspend fun migrateFromLegacyBackups(): SyncResult {
        var itemsRestored = 0

        // Try legacy vehicle backup (Kind 30175)
        vehicleRepository?.let { repo ->
            try {
                val vehicleBackup = nostrService.fetchVehicleBackup()
                if (vehicleBackup != null && vehicleBackup.vehicles.isNotEmpty()) {
                    restoreVehicles(repo, vehicleBackup.vehicles)
                    itemsRestored += vehicleBackup.vehicles.size
                    Log.d(TAG, "Migrated ${vehicleBackup.vehicles.size} vehicles from legacy backup")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch legacy vehicle backup", e)
            }
        }

        // Try legacy saved location backup (Kind 30176)
        savedLocationRepository?.let { repo ->
            try {
                val locationBackup = nostrService.fetchSavedLocationBackup()
                if (locationBackup != null && locationBackup.locations.isNotEmpty()) {
                    restoreLocations(repo, locationBackup.locations)
                    itemsRestored += locationBackup.locations.size
                    Log.d(TAG, "Migrated ${locationBackup.locations.size} saved locations from legacy backup")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch legacy saved location backup", e)
            }
        }

        return if (itemsRestored > 0) {
            // Migrated from legacy - publish unified backup
            Log.d(TAG, "Migration complete, publishing unified profile backup...")
            // Note: publishToNostr will be called by ProfileSyncManager after this
            SyncResult.Success(itemsRestored)
        } else {
            Log.d(TAG, "No legacy backups found")
            SyncResult.NoData("No profile backup found")
        }
    }

    override suspend fun publishToNostr(
        signer: NostrSigner,
        relayManager: RelayManager
    ): String? {
        return try {
            Log.d(TAG, "Backing up profile to Nostr...")

            val vehicles = vehicleRepository?.vehicles?.value ?: emptyList()
            val locations = savedLocationRepository?.savedLocations?.value ?: emptyList()
            val settings = settingsManager.toBackupData()

            // Only publish if there's something to backup
            if (vehicles.isEmpty() && locations.isEmpty()) {
                Log.d(TAG, "No profile data to backup (no vehicles or locations)")
                return null
            }

            val eventId = nostrService.publishProfileBackup(vehicles, locations, settings)
            if (eventId != null) {
                Log.d(TAG, "Profile backed up as event $eventId (${vehicles.size} vehicles, ${locations.size} locations)")
            } else {
                Log.w(TAG, "Failed to backup profile")
            }
            eventId
        } catch (e: Exception) {
            Log.e(TAG, "Error backing up profile", e)
            null
        }
    }

    override fun hasLocalData(): Boolean {
        val hasVehicles = vehicleRepository?.hasVehicles() == true
        val hasLocations = savedLocationRepository?.hasLocations() == true
        return hasVehicles || hasLocations
    }

    override fun clearLocalData() {
        vehicleRepository?.clearAll()
        savedLocationRepository?.clearAll()
        // Note: We don't clear settings here - they're cleared by SettingsManager.clearAllData() on logout
        Log.d(TAG, "Cleared profile data (vehicles and locations)")
    }

    override suspend fun hasNostrBackup(
        pubKeyHex: String,
        relayManager: RelayManager
    ): Boolean {
        return try {
            val backup = nostrService.fetchProfileBackup()
            if (backup != null) {
                return backup.vehicles.isNotEmpty() || backup.savedLocations.isNotEmpty()
            }

            // Check legacy backups
            @Suppress("DEPRECATION")
            val vehicleBackup = vehicleRepository?.let { nostrService.fetchVehicleBackup() }
            @Suppress("DEPRECATION")
            val locationBackup = savedLocationRepository?.let { nostrService.fetchSavedLocationBackup() }

            (vehicleBackup?.vehicles?.isNotEmpty() == true) ||
                    (locationBackup?.locations?.isNotEmpty() == true)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for profile backup", e)
            false
        }
    }

    /**
     * Restore vehicles from backup, replacing local data.
     */
    private fun restoreVehicles(repository: VehicleRepository, vehicles: List<Vehicle>) {
        repository.clearAll()
        vehicles.forEach { vehicle ->
            repository.addVehicle(vehicle)
        }
    }

    /**
     * Restore locations from backup, replacing local data.
     */
    private fun restoreLocations(repository: SavedLocationRepository, locations: List<SavedLocation>) {
        repository.restoreFromBackup(locations)
    }
}
