package com.roadflare.common.data

import android.content.Context
import android.util.Log
import com.roadflare.common.nostr.events.FollowedDriver
import com.roadflare.common.nostr.events.RoadflareKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FollowedDriversRepo"

data class CachedDriverLocation(
    val lat: Double, val lon: Double, val status: String, val timestamp: Long, val keyVersion: Int = 0
)

@Singleton
class FollowedDriversRepository @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("roadflare_followed_drivers", Context.MODE_PRIVATE)

    private val _driverNames = MutableStateFlow<Map<String, String>>(loadCachedNames())
    val driverNames: StateFlow<Map<String, String>> = _driverNames.asStateFlow()

    private val _driverLocations = MutableStateFlow<Map<String, CachedDriverLocation>>(emptyMap())
    val driverLocations: StateFlow<Map<String, CachedDriverLocation>> = _driverLocations.asStateFlow()

    private val _drivers = MutableStateFlow<List<FollowedDriver>>(emptyList())
    val drivers: StateFlow<List<FollowedDriver>> = _drivers.asStateFlow()

    init { loadDrivers() }

    fun updateDriverLocation(pubkey: String, lat: Double, lon: Double, status: String, timestamp: Long, keyVersion: Int = 0) {
        _driverLocations.value = _driverLocations.value + (pubkey to CachedDriverLocation(lat, lon, status, timestamp, keyVersion))
    }

    fun removeDriverLocation(pubkey: String) { _driverLocations.value = _driverLocations.value - pubkey }
    fun clearDriverLocations() { _driverLocations.value = emptyMap() }

    private fun loadCachedNames(): Map<String, String> {
        val json = prefs.getString("driver_names", null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            val result = mutableMapOf<String, String>()
            obj.keys().forEach { result[it] = obj.getString(it) }
            result
        } catch (e: Exception) { emptyMap() }
    }

    private fun persistNames() {
        prefs.edit().putString("driver_names", JSONObject(_driverNames.value).toString()).apply()
    }

    fun cacheDriverName(pubkey: String, name: String) {
        if (_drivers.value.none { it.pubkey == pubkey }) return
        _driverNames.value = _driverNames.value + (pubkey to name)
        persistNames()
    }

    fun getCachedDriverName(pubkey: String): String? = _driverNames.value[pubkey]

    private fun loadDrivers() {
        val driversJson = prefs.getString("drivers", null) ?: return
        try {
            val arr = JSONArray(driversJson)
            val list = mutableListOf<FollowedDriver>()
            for (i in 0 until arr.length()) list.add(FollowedDriver.fromJson(arr.getJSONObject(i)))
            _drivers.value = list
        } catch (_: Exception) { _drivers.value = emptyList() }
    }

    private fun saveDrivers() {
        val arr = JSONArray()
        _drivers.value.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("drivers", arr.toString()).apply()
    }

    fun addDriver(driver: FollowedDriver) {
        val existing = _drivers.value.find { it.pubkey == driver.pubkey }
        _drivers.value = if (existing != null) {
            _drivers.value.map { if (it.pubkey == driver.pubkey) driver else it }
        } else {
            _drivers.value + driver
        }
        saveDrivers()
    }

    fun removeDriver(pubkey: String) {
        _drivers.value = _drivers.value.filter { it.pubkey != pubkey }
        saveDrivers()
        if (_driverNames.value.containsKey(pubkey)) {
            _driverNames.value = _driverNames.value - pubkey; persistNames()
        }
        _driverLocations.value = _driverLocations.value - pubkey
    }

    fun updateDriverKey(driverPubkey: String, roadflareKey: RoadflareKey) {
        _drivers.value = _drivers.value.map { if (it.pubkey == driverPubkey) it.copy(roadflareKey = roadflareKey) else it }
        saveDrivers()
    }

    fun updateDriverNote(driverPubkey: String, note: String) {
        _drivers.value = _drivers.value.map { if (it.pubkey == driverPubkey) it.copy(note = note) else it }
        saveDrivers()
    }

    fun getDriver(pubkey: String): FollowedDriver? = _drivers.value.find { it.pubkey == pubkey }
    fun getAll(): List<FollowedDriver> = _drivers.value
    fun getAllPubkeys(): List<String> = _drivers.value.map { it.pubkey }
    fun getRoadflareKey(driverPubkey: String): RoadflareKey? = _drivers.value.find { it.pubkey == driverPubkey }?.roadflareKey
    fun isFollowing(pubkey: String): Boolean = _drivers.value.any { it.pubkey == pubkey }
    fun hasDrivers(): Boolean = _drivers.value.isNotEmpty()

    fun replaceAll(drivers: List<FollowedDriver>) { _drivers.value = drivers; saveDrivers() }

    fun clearAll() {
        prefs.edit().remove("drivers").remove("driver_names").apply()
        _drivers.value = emptyList(); _driverNames.value = emptyMap(); _driverLocations.value = emptyMap()
    }
}
