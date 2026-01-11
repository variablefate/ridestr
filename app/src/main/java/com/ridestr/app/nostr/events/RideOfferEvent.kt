package com.ridestr.app.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONObject

/**
 * LEGACY MODULE - The canonical version is in the common module.
 *
 * Kind 3173: Ride Offer Event
 *
 * Sent by riders to request a ride from a specific driver.
 */
object RideOfferEvent {

    /**
     * Create and sign a ride offer event.
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
     * Parse a ride offer event to extract the offer details.
     */
    fun parse(event: Event): RideOfferData? {
        if (event.kind != RideshareEventKinds.RIDE_OFFER) return null

        return try {
            val json = JSONObject(event.content)

            val destinationJson = json.getJSONObject("destination")
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
}

/**
 * Parsed data from a ride offer event.
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
