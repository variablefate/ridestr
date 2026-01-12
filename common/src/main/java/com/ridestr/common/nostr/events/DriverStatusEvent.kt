package com.ridestr.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONObject

/**
 * Kind 3180: Driver Status Event (Regular)
 *
 * Sent by drivers during the ride to update status.
 * Stored by relays so completion events can be fetched later
 * if the rider's app was offline when the driver completed the ride.
 */
object DriverStatusEvent {

    /**
     * Create and sign a driver status update event.
     */
    suspend fun create(
        signer: NostrSigner,
        confirmationEventId: String,
        riderPubKey: String,
        status: String,
        location: Location? = null,
        finalFare: Double? = null,
        invoice: String? = null
    ): Event {
        val content = JSONObject().apply {
            put("status", status)
            location?.let { put("approx_location", it.approximate().toJson()) }
            finalFare?.let { put("final_fare", it) }
            invoice?.let { put("invoice", it) }
        }.toString()

        val tags = arrayOf(
            arrayOf(RideshareTags.EVENT_REF, confirmationEventId),
            arrayOf(RideshareTags.PUBKEY_REF, riderPubKey),
            arrayOf(RideshareTags.HASHTAG, RideshareTags.RIDESHARE_TAG)
        )

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = RideshareEventKinds.DRIVER_STATUS,
            tags = tags,
            content = content
        )
    }

    /**
     * Parse a driver status event to extract the status details.
     */
    fun parse(event: Event): DriverStatusData? {
        if (event.kind != RideshareEventKinds.DRIVER_STATUS) return null

        return try {
            val json = JSONObject(event.content)
            val status = json.getString("status")

            val location = if (json.has("approx_location")) {
                Location.fromJson(json.getJSONObject("approx_location"))
            } else null

            val finalFare = if (json.has("final_fare")) {
                json.getDouble("final_fare")
            } else null

            val invoice = if (json.has("invoice")) {
                json.getString("invoice")
            } else null

            var confirmationEventId: String? = null
            var riderPubKey: String? = null

            for (tag in event.tags) {
                when (tag.getOrNull(0)) {
                    RideshareTags.EVENT_REF -> confirmationEventId = tag.getOrNull(1)
                    RideshareTags.PUBKEY_REF -> riderPubKey = tag.getOrNull(1)
                }
            }

            if (confirmationEventId == null || riderPubKey == null) return null

            DriverStatusData(
                eventId = event.id,
                driverPubKey = event.pubKey,
                confirmationEventId = confirmationEventId,
                riderPubKey = riderPubKey,
                status = status,
                approxLocation = location,
                finalFare = finalFare,
                invoice = invoice,
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Parsed data from a driver status event.
 */
data class DriverStatusData(
    val eventId: String,
    val driverPubKey: String,
    val confirmationEventId: String,
    val riderPubKey: String,
    val status: String,
    val approxLocation: Location?,
    val finalFare: Double?,
    val invoice: String?,
    val createdAt: Long
) {
    fun isCompleted(): Boolean = status == DriverStatusType.COMPLETED
    fun isCancelled(): Boolean = status == DriverStatusType.CANCELLED
    fun isArrived(): Boolean = status == DriverStatusType.ARRIVED
}
