package com.ridestr.common.nostr.events

import android.util.Log
import com.ridestr.common.data.Vehicle
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "DriverAvailability"

/**
 * Kind 30173: Driver Availability Event (Parameterized Replaceable)
 *
 * Broadcast by drivers to indicate their availability status.
 * Contains approximate location to protect driver privacy.
 *
 * As a parameterized replaceable event, only the latest availability
 * per driver is kept by relays.
 */
object DriverAvailabilityEvent {

    /** Status indicating driver is available for rides */
    const val STATUS_AVAILABLE = "available"
    /** Status indicating driver is offline/unavailable */
    const val STATUS_OFFLINE = "offline"

    /**
     * Create and sign a driver availability event.
     * Includes geohash tags for geographic filtering and optional vehicle info.
     *
     * @param signer The NostrSigner to sign the event
     * @param location Current driver location
     * @param status Driver status (STATUS_AVAILABLE or STATUS_OFFLINE)
     * @param vehicle Optional vehicle info to include in the event
     * @param mintUrl Driver's Cashu mint URL (for multi-mint support)
     * @param paymentMethods Supported payment methods (e.g., ["cashu", "fiat_cash"])
     */
    suspend fun create(
        signer: NostrSigner,
        location: Location,
        status: String = STATUS_AVAILABLE,
        vehicle: Vehicle? = null,
        mintUrl: String? = null,
        paymentMethods: List<String> = listOf("cashu")
    ): Event {
        val approxLocation = location.approximate()
        val content = JSONObject().apply {
            put("approx_location", approxLocation.toJson())
            put("status", status)
            // Include vehicle info if provided
            vehicle?.let {
                put("car_make", it.make)
                put("car_model", it.model)
                put("car_color", it.color)
                put("car_year", it.year.toString())
            }
            // Payment info for multi-mint support (Issue #13)
            mintUrl?.let { put("mint_url", it) }
            if (paymentMethods.isNotEmpty()) {
                put("payment_methods", JSONArray(paymentMethods))
            }
        }.toString()

        // Generate geohash tags at different precision levels
        // 3 chars (~100mi) for wide area, 4 chars (~24mi) for regional, 5 chars (~5km) for local
        val geohashTags = approxLocation.geohashTags(minPrecision = 3, maxPrecision = 5)

        Log.d(TAG, "=== DRIVER AVAILABILITY EVENT ===")
        Log.d(TAG, "Original: ${location.lat}, ${location.lon}")
        Log.d(TAG, "Approx: ${approxLocation.lat}, ${approxLocation.lon}")
        Log.d(TAG, "Geohash tags: $geohashTags")

        val tags = mutableListOf(
            // d-tag required for parameterized replaceable events (Kind 30xxx)
            // Relays use this to identify which event to replace per author
            arrayOf("d", "rideshare-availability"),
            arrayOf(RideshareTags.HASHTAG, RideshareTags.RIDESHARE_TAG)
        )

        // Add geohash tags for location-based filtering
        geohashTags.forEach { geohash ->
            tags.add(arrayOf(RideshareTags.GEOHASH, geohash))
        }

        // Add NIP-40 expiration (30 minutes)
        val expiration = RideshareExpiration.minutesFromNow(RideshareExpiration.DRIVER_AVAILABILITY_MINUTES)
        tags.add(arrayOf(RideshareTags.EXPIRATION, expiration.toString()))

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = RideshareEventKinds.DRIVER_AVAILABILITY,
            tags = tags.toTypedArray(),
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
     * Parse a driver availability event to extract the location, status, vehicle, and payment info.
     */
    fun parse(event: Event): DriverAvailabilityData? {
        if (event.kind != RideshareEventKinds.DRIVER_AVAILABILITY) return null

        return try {
            val json = JSONObject(event.content)
            val locationJson = json.getJSONObject("approx_location")
            val location = Location.fromJson(locationJson)
            // Default to "available" for backwards compatibility
            val status = json.optString("status", STATUS_AVAILABLE)

            // Extract vehicle info if present
            val carMake = json.optString("car_make", null)
            val carModel = json.optString("car_model", null)
            val carColor = json.optString("car_color", null)
            val carYear = json.optString("car_year", null)

            // Extract payment info (Issue #13 - multi-mint support)
            val mintUrl = json.optString("mint_url", null).takeIf { !it.isNullOrBlank() }
            val paymentMethods = mutableListOf<String>()
            val paymentMethodsArray = json.optJSONArray("payment_methods")
            if (paymentMethodsArray != null) {
                for (i in 0 until paymentMethodsArray.length()) {
                    paymentMethods.add(paymentMethodsArray.getString(i))
                }
            }
            // Default to cashu if not specified (backwards compatibility)
            if (paymentMethods.isEmpty()) {
                paymentMethods.add("cashu")
            }

            location?.let {
                DriverAvailabilityData(
                    eventId = event.id,
                    driverPubKey = event.pubKey,
                    approxLocation = it,
                    createdAt = event.createdAt,
                    status = status,
                    carMake = carMake,
                    carModel = carModel,
                    carColor = carColor,
                    carYear = carYear,
                    mintUrl = mintUrl,
                    paymentMethods = paymentMethods
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
    val status: String = DriverAvailabilityEvent.STATUS_AVAILABLE,
    // Vehicle info (may be null for older events without vehicle data)
    val carMake: String? = null,
    val carModel: String? = null,
    val carColor: String? = null,
    val carYear: String? = null,
    // Payment info (Issue #13 - multi-mint support)
    val mintUrl: String? = null,
    val paymentMethods: List<String> = listOf("cashu")
) {
    /** Returns true if the driver is available */
    val isAvailable: Boolean
        get() = status == DriverAvailabilityEvent.STATUS_AVAILABLE

    /** Returns true if the driver is offline */
    val isOffline: Boolean
        get() = status == DriverAvailabilityEvent.STATUS_OFFLINE

    /** Returns true if vehicle info is present */
    val hasVehicleInfo: Boolean
        get() = carMake != null && carModel != null

    /** Returns a formatted vehicle description like "White 2024 Toyota Camry" */
    fun vehicleDescription(): String? {
        if (!hasVehicleInfo) return null
        val parts = listOfNotNull(carColor, carYear, carMake, carModel)
        return if (parts.isNotEmpty()) parts.joinToString(" ") else null
    }
}
