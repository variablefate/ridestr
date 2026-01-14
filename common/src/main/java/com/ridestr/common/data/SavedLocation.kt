package com.ridestr.common.data

import com.ridestr.common.location.GeocodingResult
import org.json.JSONObject
import java.util.UUID

/**
 * A saved location (recent or favorite) for quick address selection.
 */
data class SavedLocation(
    val id: String = UUID.randomUUID().toString(),
    val lat: Double,
    val lon: Double,
    val displayName: String,      // Short name or feature name
    val addressLine: String,      // Full address
    val locality: String?,        // City/town
    val isPinned: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis(),
    val nickname: String? = null  // User-defined name like "Home", "Work"
) {
    /**
     * Convert to GeocodingResult for use with existing location selection logic.
     */
    fun toGeocodingResult(): GeocodingResult {
        return GeocodingResult(
            lat = lat,
            lon = lon,
            addressLine = addressLine,
            featureName = nickname ?: displayName.takeIf { it != addressLine },
            locality = locality
        )
    }

    /**
     * Get the best display text for this location.
     */
    fun getDisplayText(): String {
        return nickname ?: displayName
    }

    /**
     * Get a subtitle (locality or address) for display.
     */
    fun getSubtitle(): String {
        return locality ?: addressLine
    }

    /**
     * Serialize to JSON for storage.
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("lat", lat)
            put("lon", lon)
            put("displayName", displayName)
            put("addressLine", addressLine)
            put("locality", locality ?: JSONObject.NULL)
            put("isPinned", isPinned)
            put("timestampMs", timestampMs)
            put("nickname", nickname ?: JSONObject.NULL)
        }
    }

    companion object {
        /**
         * Create from a GeocodingResult (when user selects a search result).
         */
        fun fromGeocodingResult(result: GeocodingResult): SavedLocation {
            return SavedLocation(
                lat = result.lat,
                lon = result.lon,
                displayName = result.getDisplayName(),
                addressLine = result.addressLine,
                locality = result.locality
            )
        }

        /**
         * Deserialize from JSON.
         */
        fun fromJson(json: JSONObject): SavedLocation {
            return SavedLocation(
                id = json.getString("id"),
                lat = json.getDouble("lat"),
                lon = json.getDouble("lon"),
                displayName = json.getString("displayName"),
                addressLine = json.getString("addressLine"),
                locality = json.optString("locality").takeIf { it.isNotEmpty() && it != "null" },
                isPinned = json.optBoolean("isPinned", false),
                timestampMs = json.optLong("timestampMs", System.currentTimeMillis()),
                nickname = json.optString("nickname").takeIf { it.isNotEmpty() && it != "null" }
            )
        }

        /**
         * Calculate approximate distance between two locations in meters.
         * Uses simple Euclidean approximation (good enough for nearby duplicates).
         */
        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            // Approximate meters per degree at mid-latitudes
            val metersPerDegreeLat = 111_000.0
            val metersPerDegreeLon = 111_000.0 * kotlin.math.cos(Math.toRadians((lat1 + lat2) / 2))

            val dLat = (lat2 - lat1) * metersPerDegreeLat
            val dLon = (lon2 - lon1) * metersPerDegreeLon

            return kotlin.math.sqrt(dLat * dLat + dLon * dLon)
        }
    }
}
