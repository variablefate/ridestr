package com.roadflare.common.nostr.events

/**
 * Nostr event kinds for the rideshare protocol (NIP-014173).
 * Port from ridestr (commit 94fc156) — all constants preserved.
 */
object RideshareEventKinds {
    const val DRIVER_AVAILABILITY = 30173
    const val RIDE_OFFER = 3173
    const val RIDE_ACCEPTANCE = 3174
    const val RIDE_CONFIRMATION = 3175
    const val DRIVER_RIDE_STATE = 30180
    const val RIDER_RIDE_STATE = 30181
    const val RIDESHARE_CHAT = 3178
    const val RIDE_CANCELLATION = 3179
    const val RIDE_HISTORY_BACKUP = 30174
    const val PROFILE_BACKUP = 30177
    const val ADMIN_CONFIG = 30182

    // RoadFlare Events
    const val ROADFLARE_FOLLOWED_DRIVERS = 30011
    const val ROADFLARE_DRIVER_STATE = 30012
    const val ROADFLARE_SHAREABLE_LIST = 30013
    const val ROADFLARE_LOCATION = 30014
    @Deprecated("Use RIDE_OFFER (3173) with roadflare tag instead")
    const val ROADFLARE_REQUEST = 3185
    const val ROADFLARE_KEY_SHARE = 3186
    const val ROADFLARE_FOLLOW_NOTIFY = 3187
    const val ROADFLARE_KEY_ACK = 3188

    @Deprecated("Use PROFILE_BACKUP (30177) instead")
    const val VEHICLE_BACKUP = 30175
    @Deprecated("Use PROFILE_BACKUP (30177) instead")
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
    const val EXPIRATION = "expiration"
}

/**
 * NIP-40 expiration durations for rideshare events.
 */
object RideshareExpiration {
    const val DRIVER_AVAILABILITY_LOOKBACK_SECONDS = 10L * 60
    const val DRIVER_AVAILABILITY_MINUTES = 30
    const val RIDE_OFFER_MINUTES = 15
    const val RIDE_ACCEPTANCE_MINUTES = 10
    const val RIDE_CONFIRMATION_HOURS = 8
    const val DRIVER_RIDE_STATE_HOURS = 8
    const val RIDER_RIDE_STATE_HOURS = 8
    const val RIDESHARE_CHAT_HOURS = 8
    const val DRIVER_STATUS_HOURS = 8
    const val PRECISE_LOCATION_HOURS = 8
    const val PIN_SUBMISSION_MINUTES = 30
    const val PICKUP_VERIFICATION_MINUTES = 30
    const val RIDE_CANCELLATION_HOURS = 24
    const val ROADFLARE_LOCATION_MINUTES = 5
    const val ROADFLARE_REQUEST_MINUTES = 15
    const val ROADFLARE_SHAREABLE_LIST_DAYS = 30

    fun daysFromNow(days: Int): Long =
        (System.currentTimeMillis() / 1000) + (days * 24L * 60L * 60L)

    fun minutesFromNow(minutes: Int): Long =
        (System.currentTimeMillis() / 1000) + (minutes * 60L)

    fun hoursFromNow(hours: Int): Long =
        (System.currentTimeMillis() / 1000) + (hours * 60L * 60L)
}
