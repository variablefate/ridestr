package com.ridestr.app.nostr.events

/**
 * LEGACY MODULE - This file is kept for backwards compatibility.
 * The canonical version is in the common module.
 *
 * Nostr event kinds for the rideshare protocol (NIP-014173).
 *
 * Kind ranges follow Nostr conventions:
 * - 1000-9999: Regular events (stored by relays)
 * - 20000-29999: Ephemeral events (NOT stored by relays)
 * - 30000-39999: Parameterized replaceable events (latest per d-tag)
 *
 * Kind numbers use "173" base (from NIP-014173) to avoid conflicts.
 */
object RideshareEventKinds {
    /**
     * Kind 30173: Driver Availability Event (Parameterized Replaceable)
     * Broadcast by drivers to indicate they are available for rides.
     * Contains approximate location for privacy.
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
     * Kind 20173: Driver Status Event (Ephemeral)
     * Sent by drivers during the ride to update status.
     * Can include: en_route_pickup, arrived, in_progress, completed.
     */
    const val DRIVER_STATUS = 20173

    /**
     * Kind 3176: PIN Submission Event (Regular)
     * Sent by drivers when they arrive at pickup to submit the PIN.
     */
    const val PIN_SUBMISSION = 3176

    /**
     * Kind 3177: Pickup Verification Event (Regular)
     * Sent by riders to verify the PIN submitted by the driver.
     */
    const val PICKUP_VERIFICATION = 3177

    /**
     * Kind 3178: Rideshare Chat Event (Regular)
     * Private chat messages between rider and driver during active ride.
     */
    const val RIDESHARE_CHAT = 3178

    /**
     * Kind 30174: Ride History Backup Event (Parameterized Replaceable)
     * Encrypted backup of user's ride history and statistics.
     */
    const val RIDE_HISTORY_BACKUP = 30174
}

/**
 * Status values for driver status updates (Kind 20173).
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
}
