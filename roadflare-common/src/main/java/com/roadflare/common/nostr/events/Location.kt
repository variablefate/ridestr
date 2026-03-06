package com.roadflare.common.nostr.events

import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Represents a geographic location with latitude and longitude.
 */
data class Location(
    val lat: Double,
    val lon: Double,
    val addressLabel: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("lat", lat)
        put("lon", lon)
    }

    fun approximate(): Location = Location(
        lat = (lat * 100).roundToInt() / 100.0,
        lon = (lon * 100).roundToInt() / 100.0,
        addressLabel = addressLabel
    )

    fun withAddress(label: String?): Location = copy(addressLabel = label)

    fun getDisplayString(): String = addressLabel ?: "${String.format("%.4f", lat)}, ${String.format("%.4f", lon)}"

    fun distanceToKm(other: Location): Double {
        val R = 6371.0
        val dLat = Math.toRadians(other.lat - lat)
        val dLon = Math.toRadians(other.lon - lon)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat)) * cos(Math.toRadians(other.lat)) *
                sin(dLon / 2).pow(2)
        return 2 * R * asin(sqrt(a))
    }

    fun isWithinMile(other: Location): Boolean = distanceToKm(other) <= 1.6

    companion object {
        fun fromJson(json: JSONObject): Location? {
            return try {
                Location(
                    lat = json.getDouble("lat"),
                    lon = json.getDouble("lon")
                )
            } catch (e: Exception) {
                null
            }
        }

        fun fromJsonString(jsonString: String): Location? {
            return try {
                fromJson(JSONObject(jsonString))
            } catch (e: Exception) {
                null
            }
        }
    }
}
