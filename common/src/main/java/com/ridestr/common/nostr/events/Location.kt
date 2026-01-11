package com.ridestr.common.nostr.events

import org.json.JSONObject
import kotlin.math.roundToInt

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
