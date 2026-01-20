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
 * - 30173, 30174, 30180, 30181: Parameterized replaceable (above NIP-51's 30030)
 * - 3173-3179: Regular events (clear of 3000 NKBIP-03)
 *
 * Protocol phases:
 * 1. DISCOVERY: Driver broadcasts availability (30173)
 * 2. HANDSHAKE: Offer (3173) → Accept (3174) → Confirm (3175) - separate events for notifications
 * 3. RIDE: Driver state (30180) + Rider state (30181) - consolidated for efficiency
 * 4. CHAT: Private messages (3178)
 * 5. CANCELLATION: Either party (3179)
 * 6. BACKUP: Ride history (30174)
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
     * Content is NIP-44 encrypted.
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
     * Kind 30180: Driver Ride State Event (Parameterized Replaceable)
     * Consolidates all driver actions during a ride with embedded history.
     * Replaces Kind 3180 (Driver Status) and Kind 3176 (PIN Submission).
     * Uses d-tag set to ride confirmation event ID.
     *
     * Actions in history:
     * - "status": en_route_pickup, arrived, in_progress, completed, cancelled
     * - "pin_submit": Encrypted PIN submission for pickup verification
     */
    const val DRIVER_RIDE_STATE = 30180

    /**
     * Kind 30181: Rider Ride State Event (Parameterized Replaceable)
     * Consolidates all rider actions during a ride with embedded history.
     * Replaces Kind 3177 (Pickup Verification) and Kind 3181 (Precise Location Reveal).
     * Uses d-tag set to ride confirmation event ID.
     *
     * Actions in history:
     * - "location_reveal": Encrypted precise pickup/destination location
     * - "pin_verify": Verification result (verified/rejected) with attempt count
     */
    const val RIDER_RIDE_STATE = 30181

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

    /**
     * Kind 30174: Ride History Backup Event (Parameterized Replaceable)
     * Encrypted backup of user's ride history and statistics.
     * Content is NIP-44 encrypted to self.
     * Parameterized replaceable: only the latest backup per user is kept.
     * Uses d-tag "rideshare-history" for identification.
     */
    const val RIDE_HISTORY_BACKUP = 30174

    /**
     * Kind 30175: Vehicle Backup Event (Parameterized Replaceable)
     * Encrypted backup of driver's vehicles.
     * Content is NIP-44 encrypted to self.
     * Parameterized replaceable: only the latest backup per user is kept.
     * Uses d-tag "rideshare-vehicles" for identification.
     */
    const val VEHICLE_BACKUP = 30175

    /**
     * Kind 30176: Saved Locations Backup Event (Parameterized Replaceable)
     * Encrypted backup of rider's saved/favorite locations.
     * Content is NIP-44 encrypted to self.
     * Parameterized replaceable: only the latest backup per user is kept.
     * Uses d-tag "rideshare-locations" for identification.
     */
    const val SAVED_LOCATIONS_BACKUP = 30176
}

/**
 * Status values for driver ride state (Kind 30180).
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
    const val DRIVER_RIDE_STATE_HOURS = 8
    const val RIDER_RIDE_STATE_HOURS = 8
    const val RIDESHARE_CHAT_HOURS = 8

    // Legacy constants (kept for compatibility during migration)
    const val DRIVER_STATUS_HOURS = 8
    const val PRECISE_LOCATION_HOURS = 8
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
