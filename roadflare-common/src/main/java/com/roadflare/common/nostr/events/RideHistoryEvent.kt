package com.roadflare.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kind 30174: Ride History Backup Event (Parameterized Replaceable)
 * Port from ridestr — exact wire format preserved.
 */
object RideHistoryEvent {

    const val D_TAG = "rideshare-history"

    suspend fun create(signer: NostrSigner, rides: List<RideHistoryEntry>, stats: RideHistoryStats): Event? {
        val pubKeyHex = signer.pubKey

        val ridesArray = JSONArray()
        rides.forEach { ridesArray.put(it.toJson()) }

        val contentJson = JSONObject().apply {
            put("rides", ridesArray)
            put("stats", stats.toJson())
            put("updated_at", System.currentTimeMillis() / 1000)
        }

        val encryptedContent = try {
            signer.nip44Encrypt(contentJson.toString(), pubKeyHex)
        } catch (e: Exception) {
            return null
        }

        val tags = arrayOf(
            arrayOf("d", D_TAG),
            arrayOf(RideshareTags.HASHTAG, RideshareTags.RIDESHARE_TAG)
        )

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = RideshareEventKinds.RIDE_HISTORY_BACKUP,
            tags = tags,
            content = encryptedContent
        )
    }

    suspend fun parseAndDecrypt(signer: NostrSigner, event: Event): RideHistoryData? {
        if (event.kind != RideshareEventKinds.RIDE_HISTORY_BACKUP) return null
        if (event.pubKey != signer.pubKey) return null

        return try {
            val decryptedContent = signer.nip44Decrypt(event.content, event.pubKey)
            val json = JSONObject(decryptedContent)

            val rides = mutableListOf<RideHistoryEntry>()
            val ridesArrayJson = json.getJSONArray("rides")
            for (i in 0 until ridesArrayJson.length()) {
                RideHistoryEntry.fromJson(ridesArrayJson.getJSONObject(i))?.let { rides.add(it) }
            }

            RideHistoryData(
                eventId = event.id,
                rides = rides,
                stats = RideHistoryStats.fromJson(json.getJSONObject("stats")),
                updatedAt = json.getLong("updated_at"),
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }
}

data class RideHistoryEntry(
    val rideId: String,
    val timestamp: Long,
    val role: String,
    val counterpartyPubKey: String,
    val pickupGeohash: String,
    val dropoffGeohash: String,
    val pickupLat: Double? = null,
    val pickupLon: Double? = null,
    val pickupAddress: String? = null,
    val dropoffLat: Double? = null,
    val dropoffLon: Double? = null,
    val dropoffAddress: String? = null,
    val distanceMiles: Double,
    val durationMinutes: Int,
    val fareSats: Long,
    val status: String,
    val counterpartyFirstName: String? = null,
    val vehicleMake: String? = null,
    val vehicleModel: String? = null,
    val lightningAddress: String? = null,
    val tipSats: Long = 0,
    val appOrigin: String? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ride_id", rideId)
        put("timestamp", timestamp)
        put("role", role)
        put("counterparty", counterpartyPubKey)
        put("pickup_geohash", pickupGeohash)
        put("dropoff_geohash", dropoffGeohash)
        pickupLat?.let { put("pickup_lat", it) }
        pickupLon?.let { put("pickup_lon", it) }
        pickupAddress?.let { put("pickup_address", it) }
        dropoffLat?.let { put("dropoff_lat", it) }
        dropoffLon?.let { put("dropoff_lon", it) }
        dropoffAddress?.let { put("dropoff_address", it) }
        put("distance_miles", distanceMiles)
        put("duration_minutes", durationMinutes)
        put("fare_sats", fareSats)
        put("status", status)
        counterpartyFirstName?.let { put("counterparty_first_name", it) }
        vehicleMake?.let { put("vehicle_make", it) }
        vehicleModel?.let { put("vehicle_model", it) }
        lightningAddress?.let { put("lightning_address", it) }
        if (tipSats > 0) put("tip_sats", tipSats)
        appOrigin?.let { put("app_origin", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): RideHistoryEntry? = try {
            RideHistoryEntry(
                rideId = json.getString("ride_id"),
                timestamp = json.getLong("timestamp"),
                role = json.getString("role"),
                counterpartyPubKey = json.getString("counterparty"),
                pickupGeohash = json.optString("pickup_geohash", ""),
                dropoffGeohash = json.optString("dropoff_geohash", ""),
                pickupLat = if (json.has("pickup_lat")) json.getDouble("pickup_lat") else null,
                pickupLon = if (json.has("pickup_lon")) json.getDouble("pickup_lon") else null,
                pickupAddress = if (json.has("pickup_address")) json.getString("pickup_address") else null,
                dropoffLat = if (json.has("dropoff_lat")) json.getDouble("dropoff_lat") else null,
                dropoffLon = if (json.has("dropoff_lon")) json.getDouble("dropoff_lon") else null,
                dropoffAddress = if (json.has("dropoff_address")) json.getString("dropoff_address") else null,
                distanceMiles = json.getDouble("distance_miles"),
                durationMinutes = json.getInt("duration_minutes"),
                fareSats = json.getLong("fare_sats"),
                status = json.getString("status"),
                counterpartyFirstName = json.optString("counterparty_first_name", null),
                vehicleMake = json.optString("vehicle_make", null),
                vehicleModel = json.optString("vehicle_model", null),
                lightningAddress = json.optString("lightning_address", null),
                tipSats = json.optLong("tip_sats", 0),
                appOrigin = json.optString("app_origin", null).takeIf { it?.isNotEmpty() == true }
            )
        } catch (e: Exception) { null }
    }
}

data class RideHistoryStats(
    val totalRidesAsRider: Int,
    val totalRidesAsDriver: Int,
    val totalDistanceMiles: Double,
    val totalDurationMinutes: Int,
    val totalFareSatsEarned: Long,
    val totalFareSatsPaid: Long,
    val completedRides: Int,
    val cancelledRides: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("total_rides_rider", totalRidesAsRider)
        put("total_rides_driver", totalRidesAsDriver)
        put("total_distance_miles", totalDistanceMiles)
        put("total_duration_minutes", totalDurationMinutes)
        put("total_fare_earned", totalFareSatsEarned)
        put("total_fare_paid", totalFareSatsPaid)
        put("completed_rides", completedRides)
        put("cancelled_rides", cancelledRides)
    }

    companion object {
        fun fromJson(json: JSONObject): RideHistoryStats = RideHistoryStats(
            totalRidesAsRider = json.optInt("total_rides_rider", 0),
            totalRidesAsDriver = json.optInt("total_rides_driver", 0),
            totalDistanceMiles = json.optDouble("total_distance_miles", 0.0),
            totalDurationMinutes = json.optInt("total_duration_minutes", 0),
            totalFareSatsEarned = json.optLong("total_fare_earned", 0),
            totalFareSatsPaid = json.optLong("total_fare_paid", 0),
            completedRides = json.optInt("completed_rides", 0),
            cancelledRides = json.optInt("cancelled_rides", 0)
        )

        fun empty() = RideHistoryStats(0, 0, 0.0, 0, 0, 0, 0, 0)
    }
}

data class RideHistoryData(
    val eventId: String,
    val rides: List<RideHistoryEntry>,
    val stats: RideHistoryStats,
    val updatedAt: Long,
    val createdAt: Long
)
