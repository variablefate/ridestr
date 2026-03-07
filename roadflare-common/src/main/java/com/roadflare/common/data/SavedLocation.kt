package com.roadflare.common.data

import com.roadflare.common.location.GeocodingResult
import org.json.JSONObject
import java.util.UUID

/**
 * A saved location (recent or favorite) for quick address selection.
 * Port from ridestr — exact format preserved.
 */
data class SavedLocation(
    val id: String = UUID.randomUUID().toString(),
    val lat: Double,
    val lon: Double,
    val displayName: String,
    val addressLine: String,
    val locality: String?,
    val isPinned: Boolean = false,
    val timestampMs: Long = System.currentTimeMillis(),
    val nickname: String? = null
) {
    fun toGeocodingResult(): GeocodingResult = GeocodingResult(
        lat = lat, lon = lon,
        addressLine = addressLine,
        featureName = nickname ?: displayName.takeIf { it != addressLine },
        locality = locality
    )

    fun getDisplayText(): String = nickname ?: displayName
    fun getSubtitle(): String = locality ?: addressLine

    fun toJson(): JSONObject = JSONObject().apply {
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

    companion object {
        fun fromGeocodingResult(result: GeocodingResult): SavedLocation = SavedLocation(
            lat = result.lat, lon = result.lon,
            displayName = result.getDisplayName(),
            addressLine = result.addressLine,
            locality = result.locality
        )

        fun fromJson(json: JSONObject): SavedLocation = SavedLocation(
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

        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val metersPerDegreeLat = 111_000.0
            val metersPerDegreeLon = 111_000.0 * kotlin.math.cos(Math.toRadians((lat1 + lat2) / 2))
            val dLat = (lat2 - lat1) * metersPerDegreeLat
            val dLon = (lon2 - lon1) * metersPerDegreeLon
            return kotlin.math.sqrt(dLat * dLat + dLon * dLon)
        }
    }
}
