package com.ridestr.common.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Vehicle data class representing a driver's vehicle.
 */
data class Vehicle(
    val id: String = java.util.UUID.randomUUID().toString(),
    val make: String,
    val model: String,
    val year: Int,
    val color: String,
    val licensePlate: String = "",
    val isPrimary: Boolean = false
) {
    /**
     * Returns a display string like "2022 Toyota Camry (White)"
     */
    fun displayName(): String = "$year $make $model ($color)"

    /**
     * Returns a short display string like "Toyota Camry"
     */
    fun shortName(): String = "$make $model"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("make", make)
        put("model", model)
        put("year", year)
        put("color", color)
        put("licensePlate", licensePlate)
        put("isPrimary", isPrimary)
    }

    companion object {
        fun fromJson(json: JSONObject): Vehicle = Vehicle(
            id = json.optString("id", java.util.UUID.randomUUID().toString()),
            make = json.optString("make", ""),
            model = json.optString("model", ""),
            year = json.optInt("year", 2024),
            color = json.optString("color", ""),
            licensePlate = json.optString("licensePlate", ""),
            isPrimary = json.optBoolean("isPrimary", false)
        )
    }
}

/**
 * Repository for managing driver vehicles.
 * Stores vehicles in SharedPreferences as JSON.
 */
class VehicleRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _vehicles = MutableStateFlow<List<Vehicle>>(emptyList())
    val vehicles: StateFlow<List<Vehicle>> = _vehicles.asStateFlow()

    init {
        loadVehicles()
    }

    /**
     * Load vehicles from SharedPreferences.
     */
    private fun loadVehicles() {
        val vehiclesJson = prefs.getString(KEY_VEHICLES, null)
        if (vehiclesJson != null) {
            try {
                val jsonArray = JSONArray(vehiclesJson)
                val vehicleList = mutableListOf<Vehicle>()
                for (i in 0 until jsonArray.length()) {
                    vehicleList.add(Vehicle.fromJson(jsonArray.getJSONObject(i)))
                }
                _vehicles.value = vehicleList
            } catch (e: Exception) {
                _vehicles.value = emptyList()
            }
        }
    }

    /**
     * Save vehicles to SharedPreferences.
     */
    private fun saveVehicles() {
        val jsonArray = JSONArray()
        _vehicles.value.forEach { vehicle ->
            jsonArray.put(vehicle.toJson())
        }
        prefs.edit().putString(KEY_VEHICLES, jsonArray.toString()).apply()
    }

    /**
     * Add a new vehicle.
     * If this is the first vehicle, it becomes primary automatically.
     */
    fun addVehicle(vehicle: Vehicle) {
        val newVehicle = if (_vehicles.value.isEmpty()) {
            vehicle.copy(isPrimary = true)
        } else {
            vehicle
        }
        _vehicles.value = _vehicles.value + newVehicle
        saveVehicles()
    }

    /**
     * Update an existing vehicle.
     */
    fun updateVehicle(vehicle: Vehicle) {
        _vehicles.value = _vehicles.value.map {
            if (it.id == vehicle.id) vehicle else it
        }
        saveVehicles()
    }

    /**
     * Delete a vehicle by ID.
     * If the deleted vehicle was primary, the first remaining vehicle becomes primary.
     */
    fun deleteVehicle(vehicleId: String) {
        val deletedVehicle = _vehicles.value.find { it.id == vehicleId }
        _vehicles.value = _vehicles.value.filter { it.id != vehicleId }

        // If we deleted the primary vehicle, make the first one primary
        if (deletedVehicle?.isPrimary == true && _vehicles.value.isNotEmpty()) {
            _vehicles.value = _vehicles.value.mapIndexed { index, v ->
                if (index == 0) v.copy(isPrimary = true) else v
            }
        }
        saveVehicles()
    }

    /**
     * Set a vehicle as the primary vehicle.
     */
    fun setPrimaryVehicle(vehicleId: String) {
        _vehicles.value = _vehicles.value.map { vehicle ->
            vehicle.copy(isPrimary = vehicle.id == vehicleId)
        }
        saveVehicles()
    }

    /**
     * Get the primary vehicle, or null if none exists.
     */
    fun getPrimaryVehicle(): Vehicle? {
        return _vehicles.value.find { it.isPrimary } ?: _vehicles.value.firstOrNull()
    }

    /**
     * Check if there are any vehicles.
     */
    fun hasVehicles(): Boolean = _vehicles.value.isNotEmpty()

    /**
     * Check if user has multiple vehicles (for vehicle picker dialog logic).
     */
    fun hasMultipleVehicles(): Boolean = _vehicles.value.size > 1

    /**
     * Get a vehicle by its ID, or null if not found.
     */
    fun getVehicleById(vehicleId: String): Vehicle? {
        return _vehicles.value.find { it.id == vehicleId }
    }

    /**
     * Get the active vehicle for driving.
     * Uses the provided activeVehicleId if set and valid, otherwise primary, otherwise first.
     */
    fun getActiveVehicle(activeVehicleId: String?): Vehicle? {
        return activeVehicleId?.let { getVehicleById(it) } ?: getPrimaryVehicle()
    }

    companion object {
        private const val PREFS_NAME = "ridestr_vehicles"
        private const val KEY_VEHICLES = "vehicles"

        @Volatile
        private var INSTANCE: VehicleRepository? = null

        fun getInstance(context: Context): VehicleRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VehicleRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
