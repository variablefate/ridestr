package com.ridestr.common.data

import android.content.Context
import com.ridestr.common.nostr.events.FollowedDriver
import com.ridestr.common.nostr.events.RoadflareKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/**
 * Repository for managing rider's followed drivers list for RoadFlare.
 * Stores drivers and their RoadFlare decryption keys in SharedPreferences.
 *
 * NOTE: Driver names are NOT stored here - they are fetched from Nostr profiles
 * dynamically and cached in memory via [driverNames].
 *
 * This is the local cache of Kind 30011 data. The source of truth is Nostr,
 * but local storage allows offline access and faster app startup.
 */
class FollowedDriversRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * In-memory cache of driver display names, fetched from Nostr profiles.
     * Key = driver pubkey, Value = display name
     */
    private val _driverNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val driverNames: StateFlow<Map<String, String>> = _driverNames.asStateFlow()

    /**
     * Cache a driver's display name (fetched from their Nostr profile).
     */
    fun cacheDriverName(pubkey: String, name: String) {
        _driverNames.value = _driverNames.value + (pubkey to name)
    }

    /**
     * Get a cached driver display name, or null if not yet fetched.
     */
    fun getCachedDriverName(pubkey: String): String? = _driverNames.value[pubkey]

    private val _drivers = MutableStateFlow<List<FollowedDriver>>(emptyList())
    val drivers: StateFlow<List<FollowedDriver>> = _drivers.asStateFlow()

    init {
        loadDrivers()
    }

    /**
     * Load drivers from SharedPreferences.
     */
    private fun loadDrivers() {
        val driversJson = prefs.getString(KEY_DRIVERS, null)
        if (driversJson != null) {
            try {
                val jsonArray = JSONArray(driversJson)
                val driverList = mutableListOf<FollowedDriver>()
                for (i in 0 until jsonArray.length()) {
                    driverList.add(FollowedDriver.fromJson(jsonArray.getJSONObject(i)))
                }
                _drivers.value = driverList
            } catch (e: Exception) {
                _drivers.value = emptyList()
            }
        }
    }

    /**
     * Save drivers to SharedPreferences.
     */
    private fun saveDrivers() {
        val jsonArray = JSONArray()
        _drivers.value.forEach { driver ->
            jsonArray.put(driver.toJson())
        }
        prefs.edit().putString(KEY_DRIVERS, jsonArray.toString()).apply()
    }

    /**
     * Add a driver to favorites.
     * If driver already exists, updates the entry.
     */
    fun addDriver(driver: FollowedDriver) {
        val existing = _drivers.value.find { it.pubkey == driver.pubkey }
        if (existing != null) {
            // Update existing driver
            _drivers.value = _drivers.value.map {
                if (it.pubkey == driver.pubkey) driver else it
            }
        } else {
            // Add new driver
            _drivers.value = _drivers.value + driver
        }
        saveDrivers()
    }

    /**
     * Remove a driver from favorites.
     */
    fun removeDriver(pubkey: String) {
        _drivers.value = _drivers.value.filter { it.pubkey != pubkey }
        saveDrivers()
    }

    /**
     * Update the RoadFlare key for a driver.
     * Called when receiving Kind 3186 (key share) from the driver.
     */
    fun updateDriverKey(driverPubkey: String, roadflareKey: RoadflareKey) {
        _drivers.value = _drivers.value.map { driver ->
            if (driver.pubkey == driverPubkey) {
                driver.copy(roadflareKey = roadflareKey)
            } else {
                driver
            }
        }
        saveDrivers()
    }

    /**
     * Update note for a driver.
     */
    fun updateDriverNote(driverPubkey: String, note: String) {
        _drivers.value = _drivers.value.map { driver ->
            if (driver.pubkey == driverPubkey) {
                driver.copy(note = note)
            } else {
                driver
            }
        }
        saveDrivers()
    }

    /**
     * Get a specific driver by pubkey.
     */
    fun getDriver(pubkey: String): FollowedDriver? {
        return _drivers.value.find { it.pubkey == pubkey }
    }

    /**
     * Get all followed drivers.
     */
    fun getAll(): List<FollowedDriver> = _drivers.value

    /**
     * Get all driver pubkeys (for subscriptions).
     */
    fun getAllPubkeys(): List<String> = _drivers.value.map { it.pubkey }

    /**
     * Get the RoadFlare key for a specific driver.
     * Used for decrypting Kind 30014 location broadcasts.
     */
    fun getRoadflareKey(driverPubkey: String): RoadflareKey? {
        return _drivers.value.find { it.pubkey == driverPubkey }?.roadflareKey
    }

    /**
     * Check if a driver is followed.
     */
    fun isFollowing(pubkey: String): Boolean {
        return _drivers.value.any { it.pubkey == pubkey }
    }

    /**
     * Check if there are any followed drivers.
     */
    fun hasDrivers(): Boolean = _drivers.value.isNotEmpty()

    /**
     * Replace all drivers (used for sync restore from Nostr).
     */
    fun replaceAll(drivers: List<FollowedDriver>) {
        _drivers.value = drivers
        saveDrivers()
    }

    /**
     * Clear all followed drivers (for logout).
     */
    fun clearAll() {
        prefs.edit().remove(KEY_DRIVERS).apply()
        _drivers.value = emptyList()
    }

    companion object {
        private const val PREFS_NAME = "roadflare_followed_drivers"
        private const val KEY_DRIVERS = "drivers"

        @Volatile
        private var INSTANCE: FollowedDriversRepository? = null

        fun getInstance(context: Context): FollowedDriversRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: FollowedDriversRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
