package com.roadflare.common.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class Vehicle(
    val id: String = java.util.UUID.randomUUID().toString(),
    val make: String,
    val model: String,
    val year: Int,
    val color: String,
    val licensePlate: String = "",
    val isPrimary: Boolean = false
) {
    fun displayName(): String = "$year $make $model ($color)"
    fun shortName(): String = "$make $model"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("make", make); put("model", model)
        put("year", year); put("color", color); put("licensePlate", licensePlate)
        put("isPrimary", isPrimary)
    }

    companion object {
        fun fromJson(json: JSONObject): Vehicle = Vehicle(
            id = json.optString("id", java.util.UUID.randomUUID().toString()),
            make = json.optString("make", ""), model = json.optString("model", ""),
            year = json.optInt("year", 2024), color = json.optString("color", ""),
            licensePlate = json.optString("licensePlate", ""),
            isPrimary = json.optBoolean("isPrimary", false)
        )
    }
}

@Singleton
class VehicleRepository @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("roadflare_vehicles", Context.MODE_PRIVATE)
    private val _vehicles = MutableStateFlow<List<Vehicle>>(emptyList())
    val vehicles: StateFlow<List<Vehicle>> = _vehicles.asStateFlow()

    init { loadVehicles() }

    private fun loadVehicles() {
        val json = prefs.getString("vehicles", null) ?: return
        try {
            val arr = JSONArray(json)
            val list = mutableListOf<Vehicle>()
            for (i in 0 until arr.length()) list.add(Vehicle.fromJson(arr.getJSONObject(i)))
            _vehicles.value = list
        } catch (_: Exception) { _vehicles.value = emptyList() }
    }

    private fun saveVehicles() {
        val arr = JSONArray()
        _vehicles.value.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("vehicles", arr.toString()).apply()
    }

    fun addVehicle(vehicle: Vehicle) {
        val v = if (_vehicles.value.isEmpty()) vehicle.copy(isPrimary = true) else vehicle
        _vehicles.value = _vehicles.value + v; saveVehicles()
    }

    fun updateVehicle(vehicle: Vehicle) {
        _vehicles.value = _vehicles.value.map { if (it.id == vehicle.id) vehicle else it }; saveVehicles()
    }

    fun deleteVehicle(vehicleId: String) {
        val deleted = _vehicles.value.find { it.id == vehicleId }
        _vehicles.value = _vehicles.value.filter { it.id != vehicleId }
        if (deleted?.isPrimary == true && _vehicles.value.isNotEmpty()) {
            _vehicles.value = _vehicles.value.mapIndexed { i, v -> if (i == 0) v.copy(isPrimary = true) else v }
        }
        saveVehicles()
    }

    fun setPrimaryVehicle(vehicleId: String) {
        _vehicles.value = _vehicles.value.map { it.copy(isPrimary = it.id == vehicleId) }; saveVehicles()
    }

    fun getPrimaryVehicle(): Vehicle? = _vehicles.value.find { it.isPrimary } ?: _vehicles.value.firstOrNull()
    fun hasVehicles(): Boolean = _vehicles.value.isNotEmpty()
    fun hasMultipleVehicles(): Boolean = _vehicles.value.size > 1
    fun getVehicleById(vehicleId: String): Vehicle? = _vehicles.value.find { it.id == vehicleId }
    fun getActiveVehicle(activeVehicleId: String?): Vehicle? = activeVehicleId?.let { getVehicleById(it) } ?: getPrimaryVehicle()

    fun clearAll() { prefs.edit().remove("vehicles").apply(); _vehicles.value = emptyList() }

    fun restoreFromBackup(vehicles: List<Vehicle>) {
        if (vehicles.isEmpty()) { clearAll(); return }
        val primaryCount = vehicles.count { it.isPrimary }
        _vehicles.value = when {
            primaryCount == 1 -> vehicles
            primaryCount == 0 -> vehicles.mapIndexed { i, v -> if (i == 0) v.copy(isPrimary = true) else v }
            else -> {
                val firstPrimaryId = vehicles.first { it.isPrimary }.id
                vehicles.map { if (it.isPrimary && it.id != firstPrimaryId) it.copy(isPrimary = false) else it }
            }
        }
        saveVehicles()
    }
}
