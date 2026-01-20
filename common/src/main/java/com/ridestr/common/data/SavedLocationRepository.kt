package com.ridestr.common.data

import android.content.Context
import com.ridestr.common.location.GeocodingResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Repository for managing saved locations (recents and favorites).
 * Stores locations in SharedPreferences as JSON.
 */
class SavedLocationRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _savedLocations = MutableStateFlow<List<SavedLocation>>(emptyList())
    val savedLocations: StateFlow<List<SavedLocation>> = _savedLocations.asStateFlow()

    init {
        loadLocations()
    }

    /**
     * Load locations from SharedPreferences.
     */
    private fun loadLocations() {
        val locationsJson = prefs.getString(KEY_LOCATIONS, null)
        if (locationsJson != null) {
            try {
                val jsonArray = JSONArray(locationsJson)
                val locationList = mutableListOf<SavedLocation>()
                for (i in 0 until jsonArray.length()) {
                    locationList.add(SavedLocation.fromJson(jsonArray.getJSONObject(i)))
                }
                _savedLocations.value = locationList
            } catch (e: Exception) {
                _savedLocations.value = emptyList()
            }
        }
    }

    /**
     * Save locations to SharedPreferences.
     */
    private fun saveLocations() {
        val jsonArray = JSONArray()
        _savedLocations.value.forEach { location ->
            jsonArray.put(location.toJson())
        }
        prefs.edit().putString(KEY_LOCATIONS, jsonArray.toString()).apply()
    }

    /**
     * Add a recent location from a geocoding result.
     * Updates timestamp if location already exists (within ~50m threshold).
     * Limits recents to MAX_RECENTS (oldest auto-removed).
     */
    fun addRecent(result: GeocodingResult) {
        val existing = findNearby(result.lat, result.lon)

        if (existing != null) {
            // Update timestamp on existing location
            _savedLocations.value = _savedLocations.value.map {
                if (it.id == existing.id) it.copy(timestampMs = System.currentTimeMillis()) else it
            }
        } else {
            // Add new location
            val newLocation = SavedLocation.fromGeocodingResult(result)
            val currentList = _savedLocations.value.toMutableList()
            currentList.add(0, newLocation)

            // Remove oldest non-pinned locations if over limit
            val pinned = currentList.filter { it.isPinned }
            val recents = currentList.filter { !it.isPinned }
                .sortedByDescending { it.timestampMs }
                .take(MAX_RECENTS)

            _savedLocations.value = pinned + recents
        }
        saveLocations()
    }

    /**
     * Pin a location as a favorite.
     */
    fun pinAsFavorite(id: String, nickname: String? = null) {
        _savedLocations.value = _savedLocations.value.map {
            if (it.id == id) {
                it.copy(isPinned = true, nickname = nickname ?: it.nickname)
            } else it
        }
        saveLocations()
    }

    /**
     * Unpin a favorite (convert back to recent).
     */
    fun unpinFavorite(id: String) {
        _savedLocations.value = _savedLocations.value.map {
            if (it.id == id) it.copy(isPinned = false) else it
        }
        saveLocations()
    }

    /**
     * Remove a location entirely.
     */
    fun removeLocation(id: String) {
        _savedLocations.value = _savedLocations.value.filter { it.id != id }
        saveLocations()
    }

    /**
     * Update nickname for a favorite.
     */
    fun updateNickname(id: String, nickname: String?) {
        _savedLocations.value = _savedLocations.value.map {
            if (it.id == id) it.copy(nickname = nickname) else it
        }
        saveLocations()
    }

    /**
     * Get favorites (pinned locations), sorted by nickname then displayName.
     */
    fun getFavorites(): List<SavedLocation> {
        return _savedLocations.value
            .filter { it.isPinned }
            .sortedBy { it.nickname ?: it.displayName }
    }

    /**
     * Get recents (non-pinned), sorted by most recent first.
     */
    fun getRecents(): List<SavedLocation> {
        return _savedLocations.value
            .filter { !it.isPinned }
            .sortedByDescending { it.timestampMs }
    }

    /**
     * Clear all saved locations (for logout).
     */
    fun clearAll() {
        prefs.edit().remove(KEY_LOCATIONS).apply()
        _savedLocations.value = emptyList()
    }

    /**
     * Restore locations from a backup, replacing all local data.
     * Used during sync from Nostr.
     */
    fun restoreFromBackup(locations: List<SavedLocation>) {
        _savedLocations.value = locations
        saveLocations()
    }

    /**
     * Check if there are any saved locations.
     */
    fun hasLocations(): Boolean = _savedLocations.value.isNotEmpty()

    /**
     * Find an existing location within ~50m of the given coordinates.
     */
    private fun findNearby(lat: Double, lon: Double): SavedLocation? {
        return _savedLocations.value.find { location ->
            SavedLocation.distanceMeters(location.lat, location.lon, lat, lon) < DUPLICATE_THRESHOLD_METERS
        }
    }

    companion object {
        private const val PREFS_NAME = "ridestr_saved_locations"
        private const val KEY_LOCATIONS = "locations"
        private const val MAX_RECENTS = 15
        private const val DUPLICATE_THRESHOLD_METERS = 50.0

        @Volatile
        private var INSTANCE: SavedLocationRepository? = null

        fun getInstance(context: Context): SavedLocationRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SavedLocationRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
