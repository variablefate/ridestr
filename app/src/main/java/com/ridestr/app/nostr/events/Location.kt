package com.ridestr.app.nostr.events

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Represents a geographic location with latitude and longitude.
 */
data class Location(
    val lat: Double,
    val lon: Double
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
        lon = (lon * 100).roundToInt() / 100.0
    )

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
