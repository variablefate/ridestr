package com.ridestr.common.nostr.events

import android.util.Log
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONObject

private const val TAG = "RideOfferEvent"

/**
 * Kind 3173: Ride Offer Event
 *
 * Two modes:
 * 1. BROADCAST: Rider broadcasts a ride request to all drivers in an area (geohash filtered)
 * 2. DIRECT: Rider sends a targeted request to a specific driver (legacy behavior)
 */
object RideOfferEvent {

    /** Tag value to identify broadcast ride requests */
    const val RIDE_REQUEST_TAG = "ride-request"

    /**
     * Create and sign a BROADCAST ride offer event.
     * This is visible to all drivers in the pickup area.
     *
     * @param signer The NostrSigner to sign the event
     * @param pickup Pickup location (will be approximated for privacy)
     * @param destination Destination location (will be approximated for privacy)
     * @param fareEstimate Fare in sats
     * @param routeDistanceKm Route distance in kilometers
     * @param routeDurationMin Route duration in minutes
     */
    suspend fun createBroadcast(
        signer: NostrSigner,
        pickup: Location,
        destination: Location,
        fareEstimate: Double,
        routeDistanceKm: Double,
        routeDurationMin: Double
    ): Event {
        val content = JSONObject().apply {
            put("fare_estimate", fareEstimate)
            put("pickup_area", pickup.approximate().toJson())
            put("destination_area", destination.approximate().toJson())
            put("route_distance_km", routeDistanceKm)
            put("route_duration_min", routeDurationMin)
        }.toString()

        // Build tags with geohash for geographic filtering
        val tagsList = mutableListOf<Array<String>>()

        // Add geohash tags at precision 3, 4, and 5 for the pickup location
        // 3 chars (~100mi) for wide area matching, 4 chars (~24mi) regional, 5 chars (~5km) local
        val geohashTags = pickup.geohashTags(minPrecision = 3, maxPrecision = 5)
        Log.d(TAG, "=== RIDE REQUEST (BROADCAST) ===")
        Log.d(TAG, "Pickup: ${pickup.lat}, ${pickup.lon}")
        Log.d(TAG, "Geohash tags: $geohashTags")

        geohashTags.forEach { geohash ->
            tagsList.add(arrayOf(RideshareTags.GEOHASH, geohash))
        }

        // Add hashtags
        tagsList.add(arrayOf(RideshareTags.HASHTAG, RideshareTags.RIDESHARE_TAG))
        tagsList.add(arrayOf(RideshareTags.HASHTAG, RIDE_REQUEST_TAG))

        // Add NIP-40 expiration (15 minutes)
        val expiration = RideshareExpiration.minutesFromNow(RideshareExpiration.RIDE_OFFER_MINUTES)
        tagsList.add(arrayOf(RideshareTags.EXPIRATION, expiration.toString()))

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = RideshareEventKinds.RIDE_OFFER,
            tags = tagsList.toTypedArray(),
            content = content
        )
    }

    /**
     * Create and sign a DIRECT ride offer event targeted to a specific driver.
     * Content is NIP-44 encrypted to protect pickup/destination from public view.
     * This is now the default flow for privacy.
     *
     * @param pickupRouteKm Optional pre-calculated driver→pickup distance in km
     * @param pickupRouteMin Optional pre-calculated driver→pickup duration in minutes
     * @param rideRouteKm Optional pre-calculated pickup→destination distance in km
     * @param rideRouteMin Optional pre-calculated pickup→destination duration in minutes
     */
    suspend fun create(
        signer: NostrSigner,
        driverAvailabilityEventId: String,
        driverPubKey: String,
        pickup: Location,
        destination: Location,
        fareEstimate: Double,
        pickupRouteKm: Double? = null,
        pickupRouteMin: Double? = null,
        rideRouteKm: Double? = null,
        rideRouteMin: Double? = null,
        // Payment rails fields
        paymentHash: String? = null,
        destinationGeohash: String? = null
    ): Event {
        // Build plaintext content
        val plaintext = JSONObject().apply {
            put("fare_estimate", fareEstimate)
            put("destination", destination.toJson())
            put("approx_pickup", pickup.approximate().toJson())
            // Add route metrics if available (pre-calculated by rider)
            pickupRouteKm?.let { put("pickup_route_km", it) }
            pickupRouteMin?.let { put("pickup_route_min", it) }
            rideRouteKm?.let { put("ride_route_km", it) }
            rideRouteMin?.let { put("ride_route_min", it) }
            // Payment rails: hash for HTLC escrow, geohash for settlement verification
            paymentHash?.let { put("payment_hash", it) }
            destinationGeohash?.let { put("destination_geohash", it) }
        }.toString()

        // Encrypt using NIP-44 so only the target driver can read it
        val encryptedContent = signer.nip44Encrypt(plaintext, driverPubKey)

        // Add NIP-40 expiration (15 minutes)
        val expiration = RideshareExpiration.minutesFromNow(RideshareExpiration.RIDE_OFFER_MINUTES)

        val tags = arrayOf(
            arrayOf(RideshareTags.EVENT_REF, driverAvailabilityEventId),
            arrayOf(RideshareTags.PUBKEY_REF, driverPubKey),
            arrayOf(RideshareTags.HASHTAG, RideshareTags.RIDESHARE_TAG),
            arrayOf(RideshareTags.EXPIRATION, expiration.toString())
        )

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = RideshareEventKinds.RIDE_OFFER,
            tags = tags,
            content = encryptedContent
        )
    }

    /**
     * Parse a BROADCAST ride offer event.
     * Returns null if this is a direct (targeted) offer.
     */
    fun parseBroadcast(event: Event): BroadcastRideOfferData? {
        if (event.kind != RideshareEventKinds.RIDE_OFFER) return null

        return try {
            val json = JSONObject(event.content)

            // Check for broadcast-specific fields
            if (!json.has("pickup_area") || !json.has("route_distance_km")) {
                return null // This is a direct offer, not broadcast
            }

            val pickupJson = json.getJSONObject("pickup_area")
            val pickupArea = Location.fromJson(pickupJson) ?: return null

            val destinationJson = json.getJSONObject("destination_area")
            val destinationArea = Location.fromJson(destinationJson) ?: return null

            val fareEstimate = json.getDouble("fare_estimate")
            val routeDistanceKm = json.getDouble("route_distance_km")
            val routeDurationMin = json.getDouble("route_duration_min")

            // Extract geohashes from tags
            val geohashes = mutableListOf<String>()
            for (tag in event.tags) {
                if (tag.getOrNull(0) == RideshareTags.GEOHASH) {
                    tag.getOrNull(1)?.let { geohashes.add(it) }
                }
            }

            BroadcastRideOfferData(
                eventId = event.id,
                riderPubKey = event.pubKey,
                pickupArea = pickupArea,
                destinationArea = destinationArea,
                fareEstimate = fareEstimate,
                routeDistanceKm = routeDistanceKm,
                routeDurationMin = routeDurationMin,
                createdAt = event.createdAt,
                geohashes = geohashes
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Parse a DIRECT ride offer event (encrypted, targeted to specific driver).
     * Returns encrypted data that must be decrypted separately.
     * Returns null if this is a broadcast offer (has ride-request tag).
     */
    fun parseEncrypted(event: Event): RideOfferDataEncrypted? {
        if (event.kind != RideshareEventKinds.RIDE_OFFER) return null

        // If it has the ride-request tag, it's a broadcast, not direct
        if (isBroadcast(event)) return null

        return try {
            var driverEventId: String? = null
            var driverPubKey: String? = null

            for (tag in event.tags) {
                when (tag.getOrNull(0)) {
                    RideshareTags.EVENT_REF -> driverEventId = tag.getOrNull(1)
                    RideshareTags.PUBKEY_REF -> driverPubKey = tag.getOrNull(1)
                }
            }

            if (driverEventId == null || driverPubKey == null) return null

            RideOfferDataEncrypted(
                eventId = event.id,
                riderPubKey = event.pubKey,
                driverEventId = driverEventId,
                driverPubKey = driverPubKey,
                encryptedContent = event.content,
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse encrypted direct offer", e)
            null
        }
    }

    /**
     * Decrypt a direct ride offer to get the full data.
     * @param signer The driver's signer (must be the intended recipient)
     * @param encryptedData The encrypted offer data from parseEncrypted()
     * @return Decrypted RideOfferData, or null if decryption fails
     */
    suspend fun decrypt(
        signer: NostrSigner,
        encryptedData: RideOfferDataEncrypted
    ): RideOfferData? {
        return try {
            val decrypted = signer.nip44Decrypt(encryptedData.encryptedContent, encryptedData.riderPubKey)
            val json = JSONObject(decrypted)

            val destinationJson = json.getJSONObject("destination")
            val destination = Location.fromJson(destinationJson) ?: return null

            val pickupJson = json.getJSONObject("approx_pickup")
            val approxPickup = Location.fromJson(pickupJson) ?: return null

            val fareEstimate = json.getDouble("fare_estimate")

            // Parse optional route metrics (pre-calculated by rider)
            val pickupRouteKm = json.optDouble("pickup_route_km").takeIf { !it.isNaN() }
            val pickupRouteMin = json.optDouble("pickup_route_min").takeIf { !it.isNaN() }
            val rideRouteKm = json.optDouble("ride_route_km").takeIf { !it.isNaN() }
            val rideRouteMin = json.optDouble("ride_route_min").takeIf { !it.isNaN() }

            // Parse payment rails fields
            val paymentHash = if (json.has("payment_hash")) json.getString("payment_hash") else null
            val destinationGeohash = if (json.has("destination_geohash")) json.getString("destination_geohash") else null

            RideOfferData(
                eventId = encryptedData.eventId,
                riderPubKey = encryptedData.riderPubKey,
                driverEventId = encryptedData.driverEventId,
                driverPubKey = encryptedData.driverPubKey,
                approxPickup = approxPickup,
                destination = destination,
                fareEstimate = fareEstimate,
                createdAt = encryptedData.createdAt,
                pickupRouteKm = pickupRouteKm,
                pickupRouteMin = pickupRouteMin,
                rideRouteKm = rideRouteKm,
                rideRouteMin = rideRouteMin,
                paymentHash = paymentHash,
                destinationGeohash = destinationGeohash
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt direct offer", e)
            null
        }
    }

    /**
     * Parse a DIRECT ride offer event (targeted to specific driver).
     * @deprecated Use parseEncrypted() + decrypt() instead. Direct offers are now encrypted.
     * This function is kept for backwards compatibility with unencrypted offers.
     */
    @Deprecated("Direct offers are now encrypted. Use parseEncrypted() + decrypt()")
    fun parse(event: Event): RideOfferData? {
        if (event.kind != RideshareEventKinds.RIDE_OFFER) return null

        return try {
            val json = JSONObject(event.content)

            // Check for direct offer fields
            val destinationJson = if (json.has("destination")) {
                json.getJSONObject("destination")
            } else {
                return null // This might be a broadcast offer
            }
            val destination = Location.fromJson(destinationJson) ?: return null

            val pickupJson = json.getJSONObject("approx_pickup")
            val approxPickup = Location.fromJson(pickupJson) ?: return null

            val fareEstimate = json.getDouble("fare_estimate")

            var driverEventId: String? = null
            var driverPubKey: String? = null

            for (tag in event.tags) {
                when (tag.getOrNull(0)) {
                    RideshareTags.EVENT_REF -> driverEventId = tag.getOrNull(1)
                    RideshareTags.PUBKEY_REF -> driverPubKey = tag.getOrNull(1)
                }
            }

            if (driverEventId == null || driverPubKey == null) return null

            RideOfferData(
                eventId = event.id,
                riderPubKey = event.pubKey,
                driverEventId = driverEventId,
                driverPubKey = driverPubKey,
                approxPickup = approxPickup,
                destination = destination,
                fareEstimate = fareEstimate,
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if an event is a broadcast ride request (vs direct/targeted).
     */
    fun isBroadcast(event: Event): Boolean {
        if (event.kind != RideshareEventKinds.RIDE_OFFER) return false
        // Broadcast offers have "ride-request" hashtag
        return event.tags.any { tag ->
            tag.getOrNull(0) == RideshareTags.HASHTAG && tag.getOrNull(1) == RIDE_REQUEST_TAG
        }
    }
}

/**
 * Encrypted data from a DIRECT ride offer event (before decryption).
 */
data class RideOfferDataEncrypted(
    val eventId: String,
    val riderPubKey: String,
    val driverEventId: String,
    val driverPubKey: String,
    val encryptedContent: String,
    val createdAt: Long
)

/**
 * Decrypted data from a DIRECT ride offer event (targeted to specific driver).
 */
data class RideOfferData(
    val eventId: String,
    val riderPubKey: String,
    val driverEventId: String,
    val driverPubKey: String,
    val approxPickup: Location,
    val destination: Location,
    val fareEstimate: Double,
    val createdAt: Long,
    // Route metrics pre-calculated by rider (null if not provided)
    val pickupRouteKm: Double? = null,
    val pickupRouteMin: Double? = null,
    val rideRouteKm: Double? = null,
    val rideRouteMin: Double? = null,
    // Payment rails fields (null for legacy non-escrow rides)
    val paymentHash: String? = null,
    val destinationGeohash: String? = null
)

/**
 * Parsed data from a BROADCAST ride offer event (visible to all drivers in area).
 */
data class BroadcastRideOfferData(
    val eventId: String,
    val riderPubKey: String,
    val pickupArea: Location,
    val destinationArea: Location,
    val fareEstimate: Double,
    val routeDistanceKm: Double,
    val routeDurationMin: Double,
    val createdAt: Long,
    val geohashes: List<String>
)
