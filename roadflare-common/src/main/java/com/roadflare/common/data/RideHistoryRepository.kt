package com.roadflare.common.data

import android.content.Context
import android.util.Log
import com.roadflare.common.nostr.events.RideHistoryEntry
import com.roadflare.common.nostr.events.RideHistoryStats
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "RideHistoryRepo"

@Singleton
class RideHistoryRepository @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("roadflare_ride_history", Context.MODE_PRIVATE)

    private val _rides = MutableStateFlow<List<RideHistoryEntry>>(emptyList())
    val rides: StateFlow<List<RideHistoryEntry>> = _rides.asStateFlow()

    private val _stats = MutableStateFlow(RideHistoryStats.empty())
    val stats: StateFlow<RideHistoryStats> = _stats.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init { loadRides() }

    private fun loadRides() {
        val json = prefs.getString("rides", null) ?: return
        try {
            val arr = JSONArray(json)
            val list = mutableListOf<RideHistoryEntry>()
            for (i in 0 until arr.length()) {
                RideHistoryEntry.fromJson(arr.getJSONObject(i))?.let { list.add(it) }
            }
            _rides.value = list.sortedByDescending { it.timestamp }
            recalculateStats()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load rides", e)
            _rides.value = emptyList()
        }
    }

    private fun saveRides() {
        val arr = JSONArray()
        _rides.value.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("rides", arr.toString()).apply()
    }

    private fun recalculateStats() {
        val rides = _rides.value
        _stats.value = RideHistoryStats(
            totalRidesAsRider = rides.count { it.role == "rider" },
            totalRidesAsDriver = rides.count { it.role == "driver" },
            totalDistanceMiles = rides.sumOf { it.distanceMiles },
            totalDurationMinutes = rides.sumOf { it.durationMinutes },
            totalFareSatsEarned = rides.filter { it.role == "driver" }.sumOf { it.fareSats },
            totalFareSatsPaid = rides.filter { it.role == "rider" }.sumOf { it.fareSats },
            completedRides = rides.count { it.status == "completed" },
            cancelledRides = rides.count { it.status == "cancelled" }
        )
    }

    fun addRide(entry: RideHistoryEntry) {
        if (_rides.value.any { it.rideId == entry.rideId }) {
            _rides.value = _rides.value.map { if (it.rideId == entry.rideId) entry else it }
        } else {
            _rides.value = (_rides.value + entry).sortedByDescending { it.timestamp }
        }
        saveRides(); recalculateStats()
    }

    fun deleteRide(rideId: String) {
        _rides.value = _rides.value.filter { it.rideId != rideId }
        saveRides(); recalculateStats()
    }

    fun clearAllHistory() {
        prefs.edit().remove("rides").apply()
        _rides.value = emptyList(); _stats.value = RideHistoryStats.empty()
    }

    fun getRidesByRole(role: String): List<RideHistoryEntry> = _rides.value.filter { it.role == role }

    fun updateRide(rideId: String, updater: (RideHistoryEntry) -> RideHistoryEntry): Boolean {
        val existing = _rides.value.find { it.rideId == rideId } ?: return false
        _rides.value = _rides.value.map { if (it.rideId == rideId) updater(it) else it }
        saveRides(); recalculateStats()
        return true
    }

    fun hasRides(): Boolean = _rides.value.isNotEmpty()
    fun getRideCount(): Int = _rides.value.size
}
