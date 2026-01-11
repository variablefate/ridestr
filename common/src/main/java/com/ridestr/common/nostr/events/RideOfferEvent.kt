package com.ridestr.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONObject

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

        // Add geohash tags at precision 4 and 5 for the pickup location
        pickup.geohashTags(minPrecision = 4, maxPrecision = 5).forEach { geohash ->
            tagsList.add(arrayOf(RideshareTags.GEOHASH, geohash))
        }

        // Add hashtags
        tagsList.add(arrayOf(RideshareTags.HASHTAG, RideshareTags.RIDESHARE_TAG))
        tagsList.add(arrayOf(RideshareTags.HASHTAG, RIDE_REQUEST_TAG))

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = RideshareEventKinds.RIDE_OFFER,
            tags = tagsList.toTypedArray(),
            content = content
        )
    }

    /**
     * Create and sign a DIRECT ride offer event targeted to a specific driver.
     * (Legacy behavior - used for advanced/direct driver selection)
     */
    suspend fun create(
        signer: NostrSigner,
        driverAvailabilityEventId: String,
        driverPubKey: String,
        pickup: Location,
        destination: Location,
        fareEstimate: Double
    ): Event {
        val content = JSONObject().apply {
            put("fare_estimate", fareEstimate)
            put("destination", destination.toJson())
            put("approx_pickup", pickup.approximate().toJson())
        }.toString()

        val tags = arrayOf(
            arrayOf(RideshareTags.EVENT_REF, driverAvailabilityEventId),
            arrayOf(RideshareTags.PUBKEY_REF, driverPubKey),
            arrayOf(RideshareTags.HASHTAG, RideshareTags.RIDESHARE_TAG)
        )

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = RideshareEventKinds.RIDE_OFFER,
            tags = tags,
            content = content
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
     * Parse a DIRECT ride offer event (targeted to specific driver).
     * Returns null if this is a broadcast offer.
     */
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
 * Parsed data from a DIRECT ride offer event (targeted to specific driver).
 */
data class RideOfferData(
    val eventId: String,
    val riderPubKey: String,
    val driverEventId: String,
    val driverPubKey: String,
    val approxPickup: Location,
    val destination: Location,
    val fareEstimate: Double,
    val createdAt: Long
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
