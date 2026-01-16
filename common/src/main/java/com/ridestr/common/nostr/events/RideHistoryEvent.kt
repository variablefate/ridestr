package com.ridestr.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kind 30174: Ride History Backup Event (Parameterized Replaceable)
 *
 * Encrypted backup of user's ride history and statistics.
 * Content is NIP-44 encrypted to self (user's own pubkey).
 *
 * As a parameterized replaceable event with d-tag "rideshare-history",
 * only the latest backup per user is kept by relays.
 *
 * This allows users to:
 * - Backup their ride history across devices
 * - Restore history after reinstalling the app
 * - Maintain aggregate statistics (total rides, total distance, etc.)
 */
object RideHistoryEvent {

    /** The d-tag identifier for ride history events */
    const val D_TAG = "rideshare-history"

    /**
     * Create and sign a ride history backup event.
     * The content is encrypted to the user's own pubkey.
     *
     * @param signer The NostrSigner to sign and encrypt the event
     * @param rides List of completed rides to backup
     * @param stats Aggregate statistics
     */
    suspend fun create(
        signer: NostrSigner,
        rides: List<RideHistoryEntry>,
        stats: RideHistoryStats
    ): Event? {
        val pubKeyHex = signer.pubKey

        // Build the content JSON
        val ridesArray = JSONArray()
        rides.forEach { ride ->
            ridesArray.put(ride.toJson())
        }

        val contentJson = JSONObject().apply {
            put("rides", ridesArray)
            put("stats", stats.toJson())
            put("updated_at", System.currentTimeMillis() / 1000)
        }

        // Encrypt to self using NIP-44 (same key for sender and receiver)
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

    /**
     * Parse and decrypt a ride history backup event.
     *
     * @param signer The NostrSigner to decrypt the content
     * @param event The event to parse
     * @return Decrypted ride history data, or null if parsing/decryption fails
     */
    suspend fun parseAndDecrypt(
        signer: NostrSigner,
        event: Event
    ): RideHistoryData? {
        if (event.kind != RideshareEventKinds.RIDE_HISTORY_BACKUP) return null
        if (event.pubKey != signer.pubKey) return null // Can only decrypt our own

        return try {
            // Decrypt using NIP-44 (encrypted to self)
            val decryptedContent = signer.nip44Decrypt(event.content, event.pubKey)
            val json = JSONObject(decryptedContent)

            val rides = mutableListOf<RideHistoryEntry>()
            val ridesArrayJson = json.getJSONArray("rides")
            for (i in 0 until ridesArrayJson.length()) {
                RideHistoryEntry.fromJson(ridesArrayJson.getJSONObject(i))?.let {
                    rides.add(it)
                }
            }

            val statsJson = json.getJSONObject("stats")
            val stats = RideHistoryStats.fromJson(statsJson)
            val updatedAt = json.getLong("updated_at")

            RideHistoryData(
                eventId = event.id,
                rides = rides,
                stats = stats,
                updatedAt = updatedAt,
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * A single ride entry in the history.
 *
 * Privacy note:
 * - Drivers store only 6-character geohashes (~1.2km precision) for passenger privacy
 * - Riders store exact coordinates + addresses for their own history
 * All data is NIP-44 encrypted to the user's own pubkey.
 */
data class RideHistoryEntry(
    val rideId: String,  // The confirmation event ID
    val timestamp: Long, // When the ride occurred
    val role: String,    // "rider" or "driver"
    val counterpartyPubKey: String, // The other party's pubkey

    // Coarse location (always present, used by drivers, fallback for riders)
    val pickupGeohash: String,   // 6 chars = ~1.2km precision
    val dropoffGeohash: String,  // Neighborhood-level

    // Precise location (rider only - null for drivers for privacy)
    val pickupLat: Double? = null,       // Exact latitude
    val pickupLon: Double? = null,       // Exact longitude
    val pickupAddress: String? = null,   // Human-readable address
    val dropoffLat: Double? = null,
    val dropoffLon: Double? = null,
    val dropoffAddress: String? = null,

    val distanceMiles: Double,   // Exact distance for stats
    val durationMinutes: Int,    // Exact duration for stats
    val fareSats: Long,
    val status: String,   // "completed", "cancelled", etc.

    // Counterparty details (for ride detail screen)
    val counterpartyFirstName: String? = null,  // First word of display name
    val vehicleMake: String? = null,            // e.g. "Toyota"
    val vehicleModel: String? = null,           // e.g. "Camry"
    val lightningAddress: String? = null,       // For tips (stored, not displayed in list)

    // Tip tracking
    val tipSats: Long = 0  // Tip amount if given
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ride_id", rideId)
        put("timestamp", timestamp)
        put("role", role)
        put("counterparty", counterpartyPubKey)
        put("pickup_geohash", pickupGeohash)
        put("dropoff_geohash", dropoffGeohash)
        // Precise location fields (rider only)
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
        // Counterparty details
        counterpartyFirstName?.let { put("counterparty_first_name", it) }
        vehicleMake?.let { put("vehicle_make", it) }
        vehicleModel?.let { put("vehicle_model", it) }
        lightningAddress?.let { put("lightning_address", it) }
        // Tip tracking (only if > 0)
        if (tipSats > 0) put("tip_sats", tipSats)
    }

    companion object {
        fun fromJson(json: JSONObject): RideHistoryEntry? {
            return try {
                RideHistoryEntry(
                    rideId = json.getString("ride_id"),
                    timestamp = json.getLong("timestamp"),
                    role = json.getString("role"),
                    counterpartyPubKey = json.getString("counterparty"),
                    pickupGeohash = json.optString("pickup_geohash", ""),
                    dropoffGeohash = json.optString("dropoff_geohash", ""),
                    // Precise location fields (optional)
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
                    // Counterparty details (optional, for backwards compatibility)
                    counterpartyFirstName = json.optString("counterparty_first_name", null),
                    vehicleMake = json.optString("vehicle_make", null),
                    vehicleModel = json.optString("vehicle_model", null),
                    lightningAddress = json.optString("lightning_address", null),
                    tipSats = json.optLong("tip_sats", 0)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Aggregate statistics from ride history.
 */
data class RideHistoryStats(
    val totalRidesAsRider: Int,
    val totalRidesAsDriver: Int,
    val totalDistanceMiles: Double,
    val totalDurationMinutes: Int,
    val totalFareSatsEarned: Long,  // As driver
    val totalFareSatsPaid: Long,    // As rider
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
        fun fromJson(json: JSONObject): RideHistoryStats {
            return RideHistoryStats(
                totalRidesAsRider = json.optInt("total_rides_rider", 0),
                totalRidesAsDriver = json.optInt("total_rides_driver", 0),
                totalDistanceMiles = json.optDouble("total_distance_miles", 0.0),
                totalDurationMinutes = json.optInt("total_duration_minutes", 0),
                totalFareSatsEarned = json.optLong("total_fare_earned", 0),
                totalFareSatsPaid = json.optLong("total_fare_paid", 0),
                completedRides = json.optInt("completed_rides", 0),
                cancelledRides = json.optInt("cancelled_rides", 0)
            )
        }

        /** Create empty stats */
        fun empty() = RideHistoryStats(
            totalRidesAsRider = 0,
            totalRidesAsDriver = 0,
            totalDistanceMiles = 0.0,
            totalDurationMinutes = 0,
            totalFareSatsEarned = 0,
            totalFareSatsPaid = 0,
            completedRides = 0,
            cancelledRides = 0
        )
    }
}

/**
 * Parsed and decrypted ride history data.
 */
data class RideHistoryData(
    val eventId: String,
    val rides: List<RideHistoryEntry>,
    val stats: RideHistoryStats,
    val updatedAt: Long,
    val createdAt: Long
)
