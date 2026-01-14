package com.ridestr.common.nostr.events

import org.json.JSONObject
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Represents a geographic location with latitude and longitude.
 *
 * @param lat Latitude coordinate
 * @param lon Longitude coordinate
 * @param addressLabel Optional human-readable address for UI display only.
 *                     Not serialized to Nostr events.
 */
data class Location(
    val lat: Double,
    val lon: Double,
    val addressLabel: String? = null
) {
    /**
     * Convert to JSON object for event content.
     */
    fun toJson(): JSONObject = JSONObject().apply {
        put("lat", lat)
        put("lon", lon)
    }

    /**
     * Get an approximate location for privacy (reduces precision).
     * Rounds to ~1km precision by limiting to 2 decimal places.
     */
    fun approximate(): Location = Location(
        lat = (lat * 100).roundToInt() / 100.0,
        lon = (lon * 100).roundToInt() / 100.0,
        addressLabel = addressLabel
    )

    /**
     * Create a copy with the given address label.
     */
    fun withAddress(label: String?): Location = copy(addressLabel = label)

    /**
     * Get display string: address label if available, otherwise coordinates.
     */
    fun getDisplayString(): String = addressLabel ?: "${String.format("%.4f", lat)}, ${String.format("%.4f", lon)}"

    /**
     * Calculate distance to another location in kilometers using Haversine formula.
     * This provides accurate distance calculations for coordinates on Earth's surface.
     */
    fun distanceToKm(other: Location): Double {
        val R = 6371.0 // Earth's radius in kilometers
        val dLat = Math.toRadians(other.lat - lat)
        val dLon = Math.toRadians(other.lon - lon)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat)) * cos(Math.toRadians(other.lat)) *
                sin(dLon / 2).pow(2)
        return 2 * R * asin(sqrt(a))
    }

    /**
     * Check if this location is within approximately 1 mile (~1.6 km) of another location.
     * Used for progressive location reveal - precise location shared when driver is close.
     */
    fun isWithinMile(other: Location): Boolean = distanceToKm(other) <= 1.6

    companion object {
        /**
         * Parse location from JSON object.
         */
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

        /**
         * Parse location from a JSON string.
         */
        fun fromJsonString(jsonString: String): Location? {
            return try {
                fromJson(JSONObject(jsonString))
            } catch (e: Exception) {
                null
            }
        }
    }
}
