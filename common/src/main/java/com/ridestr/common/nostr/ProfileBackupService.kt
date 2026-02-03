package com.ridestr.common.nostr

import android.util.Log
import com.ridestr.common.data.SavedLocation
import com.ridestr.common.data.Vehicle
import com.ridestr.common.nostr.events.*
import com.ridestr.common.nostr.keys.KeyManager
import com.ridestr.common.nostr.relay.RelayManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Service for managing user profile data backup and sync via Nostr relays.
 *
 * Handles:
 * - User profile (Kind 0 metadata)
 * - Ride history backup (Kind 30174)
 * - Unified profile backup (Kind 30177) - vehicles, locations, settings
 * - Legacy vehicle backup (Kind 30175, deprecated)
 * - Legacy saved locations backup (Kind 30176, deprecated)
 *
 * @param relayManager The RelayManager instance for relay connections
 * @param keyManager The KeyManager instance for accessing the user's signer
 */
class ProfileBackupService(
    private val relayManager: RelayManager,
    private val keyManager: KeyManager
) {

    companion object {
        private const val TAG = "ProfileBackupService"
    }

    /**
     * Cached display name of the logged-in user.
     * Updated when profile is fetched.
     */
    private val _userDisplayName = MutableStateFlow("")
    val userDisplayName: StateFlow<String> = _userDisplayName.asStateFlow()

    /**
     * Set the user's display name (called when profile is loaded).
     */
    fun setUserDisplayName(name: String) {
        _userDisplayName.value = name
    }

    /**
     * Fetch and cache the current user's display name from their profile.
     */
    fun fetchAndCacheUserDisplayName() {
        val myPubKey = keyManager.getPubKeyHex() ?: return
        subscribeToProfile(myPubKey) { profile ->
            val name = profile.displayName ?: profile.name ?: ""
            if (name.isNotEmpty()) {
                _userDisplayName.value = name
            }
        }
    }

    // ==================== Profile Operations ====================

    /**
     * Publish user profile (metadata).
     * @param profile The user profile to publish
     * @return The event ID if successful, null on failure
     */
    suspend fun publishProfile(profile: UserProfile): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot publish profile: Not logged in")
            return null
        }

        return try {
            val event = MetadataEvent.create(signer, profile)
            relayManager.publish(event)
            Log.d(TAG, "Published profile: ${event.id}")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish profile", e)
            null
        }
    }

    /**
     * Subscribe to a user's profile updates.
     * @param pubKeyHex The user's public key in hex format
     * @param onProfile Called when profile is received
     * @return Subscription ID for closing later
     */
    fun subscribeToProfile(
        pubKeyHex: String,
        onProfile: (UserProfile) -> Unit
    ): String {
        return relayManager.subscribe(
            kinds = listOf(MetadataEvent.KIND),
            authors = listOf(pubKeyHex),
            limit = 1
        ) { event, _ ->
            MetadataEvent.parse(event)?.let { profile ->
                onProfile(profile)
            }
        }
    }

    /**
     * Subscribe to the current user's own profile.
     * @param onProfile Called when profile is received
     * @return Subscription ID for closing later, null if not logged in
     */
    fun subscribeToOwnProfile(
        onProfile: (UserProfile) -> Unit
    ): String? {
        val myPubKey = keyManager.getPubKeyHex() ?: return null
        return subscribeToProfile(myPubKey, onProfile)
    }

    // ========================================
    // RIDE HISTORY BACKUP (Kind 30174)
    // ========================================

    /**
     * Publish ride history backup to Nostr relays.
     * The content is NIP-44 encrypted to the user's own pubkey for privacy.
     *
     * @param rides List of ride history entries
     * @param stats Aggregate statistics
     * @return Event ID if successful, null on failure
     */
    suspend fun publishRideHistoryBackup(
        rides: List<RideHistoryEntry>,
        stats: RideHistoryStats
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot publish ride history: Not logged in")
            return null
        }

        // Wait for relay connection
        if (!relayManager.awaitConnected(tag = "publishRideHistoryBackup")) {
            Log.e(TAG, "publishRideHistoryBackup: No relays connected - backup NOT saved!")
            return null
        }

        return try {
            val event = RideHistoryEvent.create(signer, rides, stats)
            if (event != null) {
                relayManager.publish(event)
                Log.d(TAG, "Published ride history backup: ${event.id} (${rides.size} rides) to ${relayManager.connectedCount()} relays")
                event.id
            } else {
                Log.e(TAG, "Failed to create ride history event")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish ride history", e)
            null
        }
    }

    /**
     * Fetch the user's ride history from Nostr relays.
     * Decrypts the content using the user's keys.
     *
     * @return Decrypted ride history data, or null if not found or decryption fails
     */
    suspend fun fetchRideHistory(): RideHistoryData? {
        val signer = keyManager.getSigner()
        val myPubKey = keyManager.getPubKeyHex()
        if (signer == null || myPubKey == null) {
            Log.e(TAG, "Cannot fetch ride history: Not logged in")
            return null
        }

        return withContext(Dispatchers.IO) {
            // Wait for relay connection
            if (!relayManager.awaitConnected(tag = "fetchRideHistory")) {
                Log.e(TAG, "fetchRideHistory: No relays connected - cannot restore")
                return@withContext null
            }

            Log.d(TAG, "Fetching ride history from ${relayManager.connectedCount()} relays for ${myPubKey.take(16)}...")

            try {
                var rawEvent: com.vitorpamplona.quartz.nip01Core.core.Event? = null
                val eoseHistory = CompletableDeferred<String>()
                val subscriptionId = relayManager.subscribe(
                    kinds = listOf(RideshareEventKinds.RIDE_HISTORY_BACKUP),
                    authors = listOf(myPubKey),
                    tags = mapOf("d" to listOf(RideHistoryEvent.D_TAG)),
                    limit = 1,
                    onEose = { relayUrl -> eoseHistory.complete(relayUrl) }
                ) { event, relayUrl ->
                    Log.d(TAG, "Received ride history event ${event.id} from $relayUrl")
                    rawEvent = event
                }

                // Wait for EOSE or timeout
                if (withTimeoutOrNull(8000L) { eoseHistory.await() } != null) delay(200)
                relayManager.closeSubscription(subscriptionId)

                // Decrypt outside callback
                val result = rawEvent?.let { event ->
                    RideHistoryEvent.parseAndDecrypt(signer, event)?.also { data ->
                        Log.d(TAG, "Decrypted ride history: ${data.rides.size} rides")
                    }
                }
                if (result == null) {
                    Log.d(TAG, "No ride history backup found on relays")
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch ride history", e)
                null
            }
        }
    }

    /**
     * Delete ride history backup from relays (NIP-09).
     * @param deleteEvents Function to delete events (provided by NostrService)
     * @param reason Reason for deletion
     * @return True if deletion request was sent
     */
    suspend fun deleteRideHistoryBackup(
        deleteEvents: suspend (List<String>, String) -> String?,
        reason: String = "user requested"
    ): Boolean {
        return try {
            // First find the current history event ID
            val historyData = fetchRideHistory()
            if (historyData != null) {
                deleteEvents(listOf(historyData.eventId), reason)
                Log.d(TAG, "Deleted ride history backup: ${historyData.eventId}")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete ride history", e)
            false
        }
    }

    // ==================== Profile Backup (Unified) ====================

    /**
     * Backup user profile to Nostr as an encrypted event.
     * Includes vehicles, saved locations, and settings in a single unified event.
     *
     * @param vehicles List of vehicles to backup (driver)
     * @param savedLocations List of saved locations to backup (rider)
     * @param settings Settings backup data
     * @return Event ID if successful, null on failure
     */
    suspend fun publishProfileBackup(
        vehicles: List<Vehicle>,
        savedLocations: List<SavedLocation>,
        settings: SettingsBackup
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot backup profile: Not logged in")
            return null
        }

        // Wait for relay connection
        if (!relayManager.awaitConnected(tag = "publishProfileBackup")) {
            Log.e(TAG, "publishProfileBackup: No relays connected - backup NOT saved!")
            return null
        }

        return try {
            val event = ProfileBackupEvent.create(signer, vehicles, savedLocations, settings)
            if (event != null) {
                relayManager.publish(event)
                Log.d(TAG, "Published profile backup: ${event.id} (${vehicles.size} vehicles, ${savedLocations.size} locations) to ${relayManager.connectedCount()} relays")
                event.id
            } else {
                Log.e(TAG, "Failed to create profile backup event")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to backup profile", e)
            null
        }
    }

    /**
     * Fetch the user's profile backup from Nostr relays.
     * Decrypts the content using the user's keys.
     *
     * @return Decrypted profile data, or null if not found or decryption fails
     */
    suspend fun fetchProfileBackup(): ProfileBackupData? {
        val signer = keyManager.getSigner()
        val myPubKey = keyManager.getPubKeyHex()
        if (signer == null || myPubKey == null) {
            Log.e(TAG, "Cannot fetch profile backup: Not logged in")
            return null
        }

        return withContext(Dispatchers.IO) {
            // Wait for relay connection
            if (!relayManager.awaitConnected(tag = "fetchProfileBackup")) {
                Log.e(TAG, "fetchProfileBackup: No relays connected - cannot restore")
                return@withContext null
            }

            Log.d(TAG, "Fetching profile backup from ${relayManager.connectedCount()} relays for ${myPubKey.take(16)}...")

            try {
                var rawEvent: com.vitorpamplona.quartz.nip01Core.core.Event? = null
                val eoseProfile = CompletableDeferred<String>()
                val subscriptionId = relayManager.subscribe(
                    kinds = listOf(RideshareEventKinds.PROFILE_BACKUP),
                    authors = listOf(myPubKey),
                    tags = mapOf("d" to listOf(ProfileBackupEvent.D_TAG)),
                    limit = 1,
                    onEose = { relayUrl -> eoseProfile.complete(relayUrl) }
                ) { event, relayUrl ->
                    Log.d(TAG, "Received profile backup event ${event.id} from $relayUrl")
                    rawEvent = event
                }

                // Wait for EOSE or timeout
                if (withTimeoutOrNull(8000L) { eoseProfile.await() } != null) delay(200)
                relayManager.closeSubscription(subscriptionId)

                // Decrypt outside callback
                val result = rawEvent?.let { event ->
                    ProfileBackupEvent.parseAndDecrypt(signer, event)?.also { data ->
                        Log.d(TAG, "Decrypted profile backup: ${data.vehicles.size} vehicles, ${data.savedLocations.size} locations")
                    }
                }
                if (result == null) {
                    Log.d(TAG, "No profile backup found on relays")
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch profile backup", e)
                null
            }
        }
    }

    // ==================== Vehicle Backup (DEPRECATED) ====================

    /**
     * Backup vehicles to Nostr as an encrypted event.
     *
     * @param vehicles List of vehicles to backup
     * @return Event ID if successful, null on failure
     * @deprecated Use [publishProfileBackup] instead. Vehicles are now part of unified profile backup.
     */
    @Deprecated("Use publishProfileBackup instead", ReplaceWith("publishProfileBackup(vehicles, emptyList(), SettingsBackup())"))
    suspend fun backupVehicles(vehicles: List<Vehicle>): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot backup vehicles: Not logged in")
            return null
        }

        // Wait for relay connection
        if (!relayManager.awaitConnected(tag = "backupVehicles")) {
            Log.e(TAG, "backupVehicles: No relays connected - backup NOT saved!")
            return null
        }

        return try {
            val event = VehicleBackupEvent.create(signer, vehicles)
            if (event != null) {
                relayManager.publish(event)
                Log.d(TAG, "Published vehicle backup: ${event.id} (${vehicles.size} vehicles) to ${relayManager.connectedCount()} relays")
                event.id
            } else {
                Log.e(TAG, "Failed to create vehicle backup event")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to backup vehicles", e)
            null
        }
    }

    /**
     * Fetch the user's vehicle backup from Nostr relays.
     * Decrypts the content using the user's keys.
     *
     * @return Decrypted vehicle data, or null if not found or decryption fails
     * @deprecated Use [fetchProfileBackup] instead. Vehicles are now part of unified profile backup.
     */
    @Deprecated("Use fetchProfileBackup instead", ReplaceWith("fetchProfileBackup()"))
    suspend fun fetchVehicleBackup(): VehicleBackupData? {
        val signer = keyManager.getSigner()
        val myPubKey = keyManager.getPubKeyHex()
        if (signer == null || myPubKey == null) {
            Log.e(TAG, "Cannot fetch vehicle backup: Not logged in")
            return null
        }

        return withContext(Dispatchers.IO) {
            // Wait for relay connection
            if (!relayManager.awaitConnected(tag = "fetchVehicleBackup")) {
                Log.e(TAG, "fetchVehicleBackup: No relays connected - cannot restore")
                return@withContext null
            }

            Log.d(TAG, "Fetching vehicle backup from ${relayManager.connectedCount()} relays for ${myPubKey.take(16)}...")

            try {
                var rawEvent: com.vitorpamplona.quartz.nip01Core.core.Event? = null
                val eoseVehicle = CompletableDeferred<String>()
                val subscriptionId = relayManager.subscribe(
                    kinds = listOf(RideshareEventKinds.VEHICLE_BACKUP),
                    authors = listOf(myPubKey),
                    tags = mapOf("d" to listOf(VehicleBackupEvent.D_TAG)),
                    limit = 1,
                    onEose = { relayUrl -> eoseVehicle.complete(relayUrl) }
                ) { event, relayUrl ->
                    Log.d(TAG, "Received vehicle backup event ${event.id} from $relayUrl")
                    rawEvent = event
                }

                // Wait for EOSE or timeout
                if (withTimeoutOrNull(8000L) { eoseVehicle.await() } != null) delay(200)
                relayManager.closeSubscription(subscriptionId)

                // Decrypt outside callback
                val result = rawEvent?.let { event ->
                    VehicleBackupEvent.parseAndDecrypt(signer, event)?.also { data ->
                        Log.d(TAG, "Decrypted vehicle backup: ${data.vehicles.size} vehicles")
                    }
                }
                if (result == null) {
                    Log.d(TAG, "No vehicle backup found on relays")
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch vehicle backup", e)
                null
            }
        }
    }

    // ==================== Saved Location Backup (DEPRECATED) ====================

    /**
     * Backup saved locations to Nostr as an encrypted event.
     *
     * @param locations List of saved locations to backup
     * @return Event ID if successful, null on failure
     * @deprecated Use [publishProfileBackup] instead. Saved locations are now part of unified profile backup.
     */
    @Deprecated("Use publishProfileBackup instead", ReplaceWith("publishProfileBackup(emptyList(), locations, SettingsBackup())"))
    suspend fun backupSavedLocations(locations: List<SavedLocation>): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot backup saved locations: Not logged in")
            return null
        }

        // Wait for relay connection
        if (!relayManager.awaitConnected(tag = "backupSavedLocations")) {
            Log.e(TAG, "backupSavedLocations: No relays connected - backup NOT saved!")
            return null
        }

        return try {
            val event = SavedLocationBackupEvent.create(signer, locations)
            if (event != null) {
                relayManager.publish(event)
                Log.d(TAG, "Published saved location backup: ${event.id} (${locations.size} locations) to ${relayManager.connectedCount()} relays")
                event.id
            } else {
                Log.e(TAG, "Failed to create saved location backup event")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to backup saved locations", e)
            null
        }
    }

    /**
     * Fetch the user's saved location backup from Nostr relays.
     * Decrypts the content using the user's keys.
     *
     * @return Decrypted saved location data, or null if not found or decryption fails
     * @deprecated Use [fetchProfileBackup] instead. Saved locations are now part of unified profile backup.
     */
    @Deprecated("Use fetchProfileBackup instead", ReplaceWith("fetchProfileBackup()"))
    suspend fun fetchSavedLocationBackup(): SavedLocationBackupData? {
        val signer = keyManager.getSigner()
        val myPubKey = keyManager.getPubKeyHex()
        if (signer == null || myPubKey == null) {
            Log.e(TAG, "Cannot fetch saved location backup: Not logged in")
            return null
        }

        return withContext(Dispatchers.IO) {
            // Wait for relay connection
            if (!relayManager.awaitConnected(tag = "fetchSavedLocationBackup")) {
                Log.e(TAG, "fetchSavedLocationBackup: No relays connected - cannot restore")
                return@withContext null
            }

            Log.d(TAG, "Fetching saved location backup from ${relayManager.connectedCount()} relays for ${myPubKey.take(16)}...")

            try {
                var rawEvent: com.vitorpamplona.quartz.nip01Core.core.Event? = null
                val eoseLocations = CompletableDeferred<String>()
                val subscriptionId = relayManager.subscribe(
                    kinds = listOf(RideshareEventKinds.SAVED_LOCATIONS_BACKUP),
                    authors = listOf(myPubKey),
                    tags = mapOf("d" to listOf(SavedLocationBackupEvent.D_TAG)),
                    limit = 1,
                    onEose = { relayUrl -> eoseLocations.complete(relayUrl) }
                ) { event, relayUrl ->
                    Log.d(TAG, "Received saved location backup event ${event.id} from $relayUrl")
                    rawEvent = event
                }

                // Wait for EOSE or timeout
                if (withTimeoutOrNull(8000L) { eoseLocations.await() } != null) delay(200)
                relayManager.closeSubscription(subscriptionId)

                // Decrypt outside callback
                val result = rawEvent?.let { event ->
                    SavedLocationBackupEvent.parseAndDecrypt(signer, event)?.also { data ->
                        Log.d(TAG, "Decrypted saved location backup: ${data.locations.size} locations")
                    }
                }
                if (result == null) {
                    Log.d(TAG, "No saved location backup found on relays")
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch saved location backup", e)
                null
            }
        }
    }
}
