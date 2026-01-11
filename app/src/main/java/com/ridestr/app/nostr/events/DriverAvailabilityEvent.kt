package com.ridestr.app.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONObject

/**
 * LEGACY MODULE - The canonical version is in the common module.
 *
 * Kind 30173: Driver Availability Event (Parameterized Replaceable)
 *
 * Broadcast by drivers to indicate their availability status.
 * Contains approximate location to protect driver privacy.
 */
object DriverAvailabilityEvent {

    /** Status indicating driver is available for rides */
    const val STATUS_AVAILABLE = "available"
    /** Status indicating driver is offline/unavailable */
    const val STATUS_OFFLINE = "offline"

    /**
     * Create and sign a driver availability event.
     * @param signer The NostrSigner to sign the event
     * @param location Current driver location
     * @param status Driver status (STATUS_AVAILABLE or STATUS_OFFLINE)
     */
    suspend fun create(
        signer: NostrSigner,
        location: Location,
        status: String = STATUS_AVAILABLE
    ): Event {
        val content = JSONObject().apply {
            put("approx_location", location.approximate().toJson())
            put("status", status)
        }.toString()

        val tags = arrayOf(
            arrayOf(RideshareTags.HASHTAG, RideshareTags.RIDESHARE_TAG)
        )

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = RideshareEventKinds.DRIVER_AVAILABILITY,
            tags = tags,
            content = content
        )
    }

    /**
     * Create an offline event (driver going unavailable).
     * @param signer The NostrSigner to sign the event
     * @param lastLocation Last known location (will be approximate)
     */
    suspend fun createOffline(
        signer: NostrSigner,
        lastLocation: Location
    ): Event {
        return create(signer, lastLocation, STATUS_OFFLINE)
    }

    /**
     * Parse a driver availability event to extract the location and status.
     */
    fun parse(event: Event): DriverAvailabilityData? {
        if (event.kind != RideshareEventKinds.DRIVER_AVAILABILITY) return null

        return try {
            val json = JSONObject(event.content)
            val locationJson = json.getJSONObject("approx_location")
            val location = Location.fromJson(locationJson)
            // Default to "available" for backwards compatibility
            val status = json.optString("status", STATUS_AVAILABLE)

            location?.let {
                DriverAvailabilityData(
                    eventId = event.id,
                    driverPubKey = event.pubKey,
                    approxLocation = it,
                    createdAt = event.createdAt,
                    status = status
                )
            }
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Parsed data from a driver availability event.
 */
data class DriverAvailabilityData(
    val eventId: String,
    val driverPubKey: String,
    val approxLocation: Location,
    val createdAt: Long,
    val status: String = DriverAvailabilityEvent.STATUS_AVAILABLE
) {
    /** Returns true if the driver is available */
    val isAvailable: Boolean
        get() = status == DriverAvailabilityEvent.STATUS_AVAILABLE

    /** Returns true if the driver is offline */
    val isOffline: Boolean
        get() = status == DriverAvailabilityEvent.STATUS_OFFLINE
}
