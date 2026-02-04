package com.ridestr.common.data

import android.content.Context
import android.util.Log
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.RideHistoryEntry
import com.ridestr.common.nostr.events.RideHistoryStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

private const val TAG = "RideHistoryRepository"

/**
 * Repository for managing ride history.
 *
 * Stores ride history locally in SharedPreferences and backs up to Nostr relays
 * as Kind 30174 (parameterized replaceable, encrypted to user's own npub).
 *
 * Privacy: Locations are stored as 6-char geohashes (~1.2km precision) to protect
 * user privacy while still providing useful ride context.
 */
class RideHistoryRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _rides = MutableStateFlow<List<RideHistoryEntry>>(emptyList())
    val rides: StateFlow<List<RideHistoryEntry>> = _rides.asStateFlow()

    private val _stats = MutableStateFlow(RideHistoryStats.empty())
    val stats: StateFlow<RideHistoryStats> = _stats.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Timestamp of last clear operation - prevents sync from restoring deleted data
    // for a grace period while NIP-09 deletion propagates to relays
    private var lastClearedAt: Long = 0
    private val CLEAR_GRACE_PERIOD_MS = 30_000L  // 30 seconds

    init {
        loadRides()
        updateStats()
    }

    /**
     * Load rides from SharedPreferences.
     */
    private fun loadRides() {
        val ridesJson = prefs.getString(KEY_RIDES, null)
        if (ridesJson != null) {
            try {
                val jsonArray = JSONArray(ridesJson)
                val rideList = mutableListOf<RideHistoryEntry>()
                for (i in 0 until jsonArray.length()) {
                    RideHistoryEntry.fromJson(jsonArray.getJSONObject(i))?.let {
                        rideList.add(it)
                    }
                }
                // Sort by timestamp descending (newest first)
                _rides.value = rideList.sortedByDescending { it.timestamp }
                Log.d(TAG, "Loaded ${rideList.size} rides from local storage")
            } catch (e: Exception) {
                Log.e(TAG, "Error loading rides", e)
                _rides.value = emptyList()
            }
        }
    }

    /**
     * Save rides to SharedPreferences.
     */
    private fun saveRides() {
        val jsonArray = JSONArray()
        _rides.value.forEach { ride ->
            jsonArray.put(ride.toJson())
        }
        prefs.edit().putString(KEY_RIDES, jsonArray.toString()).apply()
        Log.d(TAG, "Saved ${_rides.value.size} rides to local storage")
    }

    /**
     * Update aggregate stats from rides list.
     */
    private fun updateStats() {
        val rideList = _rides.value
        _stats.value = RideHistoryStats(
            totalRidesAsRider = rideList.count { it.role == "rider" },
            totalRidesAsDriver = rideList.count { it.role == "driver" },
            totalDistanceMiles = rideList.sumOf { it.distanceMiles },
            totalDurationMinutes = rideList.sumOf { it.durationMinutes },
            totalFareSatsEarned = rideList.filter { it.role == "driver" && it.status == "completed" }
                .sumOf { it.fareSats },
            totalFareSatsPaid = rideList.filter { it.role == "rider" && it.status == "completed" }
                .sumOf { it.fareSats },
            completedRides = rideList.count { it.status == "completed" },
            cancelledRides = rideList.count { it.status == "cancelled" }
        )
    }

    /**
     * Add a ride to history.
     * Saves to local storage and triggers Nostr backup if service provided.
     */
    fun addRide(entry: RideHistoryEntry) {
        // Check for duplicate by rideId
        if (_rides.value.any { it.rideId == entry.rideId }) {
            Log.d(TAG, "Ride ${entry.rideId} already exists, skipping")
            return
        }

        // Add to list (newest first)
        _rides.value = (listOf(entry) + _rides.value).take(MAX_RIDES)
        saveRides()
        updateStats()
        Log.d(TAG, "Added ride: ${entry.rideId}, role=${entry.role}, status=${entry.status}")
    }

    /**
     * Delete a specific ride by rideId.
     */
    fun deleteRide(rideId: String) {
        _rides.value = _rides.value.filter { it.rideId != rideId }
        saveRides()
        updateStats()
        Log.d(TAG, "Deleted ride: $rideId")
    }

    /**
     * Clear all ride history (local only).
     */
    fun clearAllHistory() {
        _rides.value = emptyList()
        saveRides()
        updateStats()
        lastClearedAt = System.currentTimeMillis()
        Log.d(TAG, "Cleared all ride history (local), grace period started")
    }

    /**
     * Clear all ride history and delete backup from Nostr relays.
     * Publishes NIP-09 deletion event for Kind 30174 backup.
     */
    suspend fun clearAllHistoryAndDeleteFromNostr(nostrService: NostrService): Boolean {
        // Clear local storage first
        _rides.value = emptyList()
        saveRides()
        updateStats()
        lastClearedAt = System.currentTimeMillis()
        Log.d(TAG, "Cleared all ride history (local), grace period started")

        // Delete from Nostr relays
        return try {
            val deleted = nostrService.deleteRideHistoryBackup("User cleared ride history")
            if (deleted) {
                Log.d(TAG, "Successfully deleted ride history backup from Nostr")
            } else {
                Log.w(TAG, "Could not delete ride history backup from Nostr (may not exist)")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting ride history from Nostr", e)
            false
        }
    }

    /**
     * Get rides filtered by role.
     */
    fun getRidesByRole(role: String): List<RideHistoryEntry> {
        return _rides.value.filter { it.role == role }
    }

    /**
     * Backup ride history to Nostr relays.
     * The event is encrypted to the user's own pubkey (NIP-44).
     */
    suspend fun backupToNostr(nostrService: NostrService): Boolean {
        return try {
            _isLoading.value = true
            val eventId = nostrService.publishRideHistoryBackup(_rides.value, _stats.value)
            if (eventId != null) {
                Log.d(TAG, "Successfully backed up history to Nostr: $eventId")
                true
            } else {
                Log.e(TAG, "Failed to backup history to Nostr")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error backing up to Nostr", e)
            false
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Restore ride history from Nostr relays (MERGE mode).
     * Merges with local history - adds rides from Nostr that don't exist locally.
     * Use this when you want to preserve local changes.
     */
    suspend fun restoreFromNostr(nostrService: NostrService): Boolean {
        return try {
            _isLoading.value = true
            val historyData = nostrService.fetchRideHistory()
            if (historyData != null) {
                Log.d(TAG, "Fetched ${historyData.rides.size} rides from Nostr")

                // Merge strategy: add any rides from Nostr that we don't have locally
                val localRideIds = _rides.value.map { it.rideId }.toSet()
                val newRides = historyData.rides.filter { it.rideId !in localRideIds }

                if (newRides.isNotEmpty()) {
                    _rides.value = (_rides.value + newRides)
                        .sortedByDescending { it.timestamp }
                        .take(MAX_RIDES)
                    saveRides()
                    updateStats()
                    Log.d(TAG, "Merged ${newRides.size} new rides from Nostr")
                }
                true
            } else {
                Log.d(TAG, "No ride history found on Nostr")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring from Nostr", e)
            false
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Sync ride history from Nostr relays (REPLACE mode).
     * Nostr is the source of truth - local data is replaced entirely.
     * Use this for pull-to-refresh and initial sync on new devices.
     */
    suspend fun syncFromNostr(nostrService: NostrService): Boolean {
        // Check if we're in grace period after clearing - don't restore deleted data
        val timeSinceClear = System.currentTimeMillis() - lastClearedAt
        if (lastClearedAt > 0 && timeSinceClear < CLEAR_GRACE_PERIOD_MS) {
            Log.d(TAG, "syncFromNostr: In grace period after clear (${timeSinceClear}ms / ${CLEAR_GRACE_PERIOD_MS}ms), skipping to prevent restoring deleted data")
            return false
        }

        return try {
            _isLoading.value = true
            val historyData = nostrService.fetchRideHistory()
            if (historyData != null) {
                Log.d(TAG, "Syncing ${historyData.rides.size} rides from Nostr (replacing local)")

                // REPLACE local with Nostr data - Nostr is source of truth
                _rides.value = historyData.rides
                    .sortedByDescending { it.timestamp }
                    .take(MAX_RIDES)
                saveRides()
                updateStats()
                Log.d(TAG, "Replaced local history with ${_rides.value.size} rides from Nostr")
                true
            } else {
                Log.d(TAG, "No ride history found on Nostr (keeping local)")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing from Nostr", e)
            false
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * Update a specific ride entry (e.g., to add tip amount).
     * Returns true if the ride was found and updated.
     */
    fun updateRide(rideId: String, updater: (RideHistoryEntry) -> RideHistoryEntry): Boolean {
        val index = _rides.value.indexOfFirst { it.rideId == rideId }
        if (index == -1) return false

        val updatedList = _rides.value.toMutableList()
        updatedList[index] = updater(updatedList[index])
        _rides.value = updatedList
        saveRides()
        updateStats()
        Log.d(TAG, "Updated ride: $rideId")
        return true
    }

    /**
     * Check if there are any rides.
     */
    fun hasRides(): Boolean = _rides.value.isNotEmpty()

    /**
     * Get total ride count.
     */
    fun getRideCount(): Int = _rides.value.size

    companion object {
        private const val PREFS_NAME = "ridestr_ride_history"
        private const val KEY_RIDES = "rides"
        private const val MAX_RIDES = 500 // Maximum number of rides to store

        // App origin identifiers for multi-app support
        const val APP_ORIGIN_RIDESTR = "ridestr"
        const val APP_ORIGIN_DRIVESTR = "drivestr"

        @Volatile
        private var INSTANCE: RideHistoryRepository? = null

        fun getInstance(context: Context): RideHistoryRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RideHistoryRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
