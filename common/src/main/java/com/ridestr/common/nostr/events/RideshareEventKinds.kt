package com.ridestr.common.nostr.events

/**
 * Nostr event kinds for the rideshare protocol (NIP-014173).
 *
 * Kind ranges follow Nostr conventions:
 * - 1000-9999: Regular events (stored by relays)
 * - 20000-29999: Ephemeral events (NOT stored by relays)
 * - 30000-39999: Parameterized replaceable events (latest per d-tag)
 *
 * Kind numbers use "173" base (from NIP-014173) to avoid conflicts:
 * - 30173: Parameterized replaceable (above NIP-51's 30030)
 * - 20173: Ephemeral (clear of 21000 Lightning.Pub)
 * - 3173-3178: Regular events (clear of 3000 NKBIP-03)
 *
 * These kinds define the different stages of a rideshare transaction:
 * 1. Driver broadcasts availability (parameterized replaceable)
 * 2. Rider sends offer to driver (regular)
 * 3. Driver accepts the offer (regular)
 * 4. Rider confirms with precise pickup location (regular, encrypted)
 * 5. Driver sends status updates during ride (ephemeral)
 * 6. PIN verification at pickup (regular)
 * 7. Private chat during ride (regular, encrypted)
 */
object RideshareEventKinds {
    /**
     * Kind 30173: Driver Availability Event (Parameterized Replaceable)
     * Broadcast by drivers to indicate they are available for rides.
     * Contains approximate location for privacy.
     * Parameterized replaceable: only the latest availability per driver is kept.
     * Uses d-tag "rideshare-availability" for identification.
     */
    const val DRIVER_AVAILABILITY = 30173

    /**
     * Kind 3173: Ride Offer Event (Regular)
     * Sent by riders to request a ride from a specific driver.
     * References the driver's availability event.
     */
    const val RIDE_OFFER = 3173

    /**
     * Kind 3174: Ride Acceptance Event (Regular)
     * Sent by drivers to accept a ride offer.
     * References the rider's offer event.
     */
    const val RIDE_ACCEPTANCE = 3174

    /**
     * Kind 3175: Ride Confirmation Event (Regular)
     * Sent by riders to confirm the ride after acceptance.
     * Contains NIP-44 encrypted precise pickup location.
     */
    const val RIDE_CONFIRMATION = 3175

    /**
     * Kind 3181: Precise Location Reveal Event (Regular)
     * Sent by riders when driver is close (~1 mile) to share precise pickup/destination.
     * Contains NIP-44 encrypted precise location.
     * This enables progressive privacy - approximate location shared first,
     * precise location only when driver is nearby.
     * Note: Kind 3179 is reserved for RideCancellationEvent.
     */
    const val PRECISE_LOCATION_REVEAL = 3181

    /**
     * Kind 3180: Driver Status Event (Regular)
     * Sent by drivers during the ride to update status.
     * Can include: en_route_pickup, arrived, in_progress, completed.
     * Regular (not ephemeral) so completion events can be fetched later
     * if rider's app was offline when driver completed the ride.
     */
    const val DRIVER_STATUS = 3180

    /**
     * Kind 3176: PIN Submission Event (Regular)
     * Sent by drivers when they arrive at pickup to submit the PIN
     * the rider told them verbally. Content is NIP-44 encrypted.
     */
    const val PIN_SUBMISSION = 3176

    /**
     * Kind 3177: Pickup Verification Event (Regular)
     * Sent by riders to verify the PIN submitted by the driver.
     * Contains verification result (verified/rejected).
     */
    const val PICKUP_VERIFICATION = 3177

    /**
     * Kind 3178: Rideshare Chat Event (Regular)
     * Private chat messages between rider and driver during active ride.
     * Content is NIP-44 encrypted to the recipient.
     */
    const val RIDESHARE_CHAT = 3178

    /**
     * Kind 3179: Ride Cancellation Event (Regular)
     * Sent by either rider or driver to cancel an active ride.
     * References the confirmation event and notifies the other party.
     */
    const val RIDE_CANCELLATION = 3179

    // Kind 30174 (RIDE_HISTORY_BACKUP) removed - was dead code, never integrated
    // Can be re-added when ride history feature is actually built
}

/**
 * Status values for driver status updates (Kind 3180).
 */
object DriverStatusType {
    const val EN_ROUTE_PICKUP = "en_route_pickup"
    const val ARRIVED = "arrived"
    const val IN_PROGRESS = "in_progress"
    const val COMPLETED = "completed"
    const val CANCELLED = "cancelled"
}

/**
 * Common tag identifiers used in rideshare events.
 */
object RideshareTags {
    const val EVENT_REF = "e"
    const val PUBKEY_REF = "p"
    const val HASHTAG = "t"
    const val GEOHASH = "g"
    const val RIDESHARE_TAG = "rideshare"
    const val EXPIRATION = "expiration"  // NIP-40
}

/**
 * NIP-40 expiration durations for rideshare events.
 * Events include expiration tags to enable automatic relay cleanup.
 */
object RideshareExpiration {
    // Pre-ride events: short TTL (stale if not acted on quickly)
    const val DRIVER_AVAILABILITY_MINUTES = 30
    const val RIDE_OFFER_MINUTES = 15
    const val RIDE_ACCEPTANCE_MINUTES = 10

    // During-ride events: 8 hours (covers long rides + buffer)
    const val RIDE_CONFIRMATION_HOURS = 8
    const val DRIVER_STATUS_HOURS = 8
    const val RIDESHARE_CHAT_HOURS = 8
    const val PRECISE_LOCATION_HOURS = 8

    // Pickup verification: 30 minutes (happens quickly)
    const val PIN_SUBMISSION_MINUTES = 30
    const val PICKUP_VERIFICATION_MINUTES = 30

    // Post-ride: 24 hours (for dispute resolution)
    const val RIDE_CANCELLATION_HOURS = 24

    // Helper functions to calculate expiration timestamp
    fun minutesFromNow(minutes: Int): Long =
        (System.currentTimeMillis() / 1000) + (minutes * 60L)

    fun hoursFromNow(hours: Int): Long =
        (System.currentTimeMillis() / 1000) + (hours * 60L * 60L)
}
