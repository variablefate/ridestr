package com.roadflare.common.data

import android.content.Context
import com.roadflare.common.location.GeocodingResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SavedLocationRepository @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("roadflare_saved_locations", Context.MODE_PRIVATE)
    private val _savedLocations = MutableStateFlow<List<SavedLocation>>(emptyList())
    val savedLocations: StateFlow<List<SavedLocation>> = _savedLocations.asStateFlow()

    init { loadLocations() }

    private fun loadLocations() {
        val json = prefs.getString("locations", null) ?: return
        try {
            val arr = JSONArray(json)
            val list = mutableListOf<SavedLocation>()
            for (i in 0 until arr.length()) list.add(SavedLocation.fromJson(arr.getJSONObject(i)))
            _savedLocations.value = list
        } catch (_: Exception) { _savedLocations.value = emptyList() }
    }

    private fun saveLocations() {
        val arr = JSONArray()
        _savedLocations.value.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("locations", arr.toString()).apply()
    }

    fun addRecent(result: GeocodingResult) {
        val existing = findNearby(result.lat, result.lon)
        if (existing != null) {
            _savedLocations.value = _savedLocations.value.map {
                if (it.id == existing.id) it.copy(timestampMs = System.currentTimeMillis()) else it
            }
        } else {
            val newLocation = SavedLocation.fromGeocodingResult(result)
            val currentList = _savedLocations.value.toMutableList()
            currentList.add(0, newLocation)
            val pinned = currentList.filter { it.isPinned }
            val recents = currentList.filter { !it.isPinned }.sortedByDescending { it.timestampMs }.take(MAX_RECENTS)
            _savedLocations.value = pinned + recents
        }
        saveLocations()
    }

    fun pinAsFavorite(id: String, nickname: String? = null) {
        _savedLocations.value = _savedLocations.value.map {
            if (it.id == id) it.copy(isPinned = true, nickname = nickname ?: it.nickname) else it
        }
        saveLocations()
    }

    fun unpinFavorite(id: String) {
        _savedLocations.value = _savedLocations.value.map { if (it.id == id) it.copy(isPinned = false) else it }
        saveLocations()
    }

    fun removeLocation(id: String) {
        _savedLocations.value = _savedLocations.value.filter { it.id != id }; saveLocations()
    }

    fun updateNickname(id: String, nickname: String?) {
        _savedLocations.value = _savedLocations.value.map { if (it.id == id) it.copy(nickname = nickname) else it }
        saveLocations()
    }

    fun getFavorites(): List<SavedLocation> = _savedLocations.value.filter { it.isPinned }.sortedBy { it.nickname ?: it.displayName }
    fun getRecents(): List<SavedLocation> = _savedLocations.value.filter { !it.isPinned }.sortedByDescending { it.timestampMs }

    fun clearAll() { prefs.edit().remove("locations").apply(); _savedLocations.value = emptyList() }
    fun restoreFromBackup(locations: List<SavedLocation>) { _savedLocations.value = locations; saveLocations() }
    fun hasLocations(): Boolean = _savedLocations.value.isNotEmpty()

    private fun findNearby(lat: Double, lon: Double): SavedLocation? =
        _savedLocations.value.find { SavedLocation.distanceMeters(it.lat, it.lon, lat, lon) < DUPLICATE_THRESHOLD_METERS }

    companion object {
        private const val MAX_RECENTS = 15
        private const val DUPLICATE_THRESHOLD_METERS = 50.0
    }
}
