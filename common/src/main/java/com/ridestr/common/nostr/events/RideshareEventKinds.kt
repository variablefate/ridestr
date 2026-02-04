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
     * Kind 30177: Profile Backup Event (Parameterized Replaceable)
     * Unified encrypted backup of user profile data including:
     * - Vehicles (driver)
     * - Saved locations (rider)
     * - App settings (both)
     * Content is NIP-44 encrypted to self.
     * Parameterized replaceable: only the latest backup per user is kept.
     * Uses d-tag "rideshare-profile" for identification.
     */
    const val PROFILE_BACKUP = 30177

    /**
     * Kind 30182: Admin Config Event (Parameterized Replaceable)
     * Platform-wide configuration published by official admin pubkey.
     * Contains fare rates, recommended mints, and version info.
     * Apps fetch once on startup and cache locally.
     * Uses d-tag "ridestr-admin-config" for identification.
     */
    const val ADMIN_CONFIG = 30182

    // ==================== RoadFlare Events ====================
    // Personal rideshare network - riders build favorite driver lists

    /**
     * Kind 30011: Followed Drivers List (Parameterized Replaceable)
     * Rider's personal list of favorite drivers, encrypted to self.
     * Contains driver pubkeys, names, notes, and their RoadFlare decryption keys.
     * Content is NIP-44 encrypted to self.
     * Uses d-tag "roadflare-drivers" for identification.
     */
    const val ROADFLARE_FOLLOWED_DRIVERS = 30011

    /**
     * Kind 30012: Driver RoadFlare State (Parameterized Replaceable)
     * Driver's complete RoadFlare state, encrypted to self.
     * Contains: roadflareKey (Nostr keypair), followers list, muted riders.
     * Single source of truth for cross-device sync and key import recovery.
     * Content is NIP-44 encrypted to self.
     * Uses d-tag "roadflare-state" for identification.
     * Includes "key_version" tag for quick staleness check.
     */
    const val ROADFLARE_DRIVER_STATE = 30012

    /**
     * Kind 30013: Shareable Driver List (Parameterized Replaceable)
     * Public list of recommended drivers that can be shared with friends.
     * Content is NOT encrypted (public for sharing via deep link).
     * Includes p-tags for each driver pubkey.
     * Uses d-tag "roadflare-share-{randomId}" for identification.
     * Optional expiration tag for 30-day TTL.
     */
    const val ROADFLARE_SHAREABLE_LIST = 30013

    /**
     * Kind 30014: RoadFlare Location Broadcast (Parameterized Replaceable)
     * Driver's real-time location, encrypted to their RoadFlare keypair.
     * Followers decrypt using the shared private key received via Kind 3186.
     * Content is NIP-44 encrypted to driver's roadflarePubKey.
     * Uses d-tag "roadflare-location" for identification.
     * Includes "status" tag: online, on_ride, offline.
     * Includes "key_version" tag for rotation tracking.
     * Published every ~30 seconds when driver app is active.
     */
    const val ROADFLARE_LOCATION = 30014

    /**
     * Kind 3185: RoadFlare Request
     * @deprecated Use RIDE_OFFER (3173) with ["t", "roadflare"] tag instead.
     * RoadFlare requests use the same structure as regular offers.
     */
    @Deprecated("Use RIDE_OFFER (3173) with roadflare tag instead")
    const val ROADFLARE_REQUEST = 3185

    /**
     * Kind 3186: RoadFlare Key Share (Regular)
     * Ephemeral DM sharing the RoadFlare private key with a follower.
     * Sent when driver clicks "Accept" on a pending follower.
     * Content is NIP-44 encrypted to follower's identity pubkey.
     * Contains: roadflareKey (privateKey, publicKey, version), keyUpdatedAt, driverPubKey.
     * Uses "expiration" tag with short TTL (5 minutes).
     * Follower stores key in Kind 30011, sends Kind 3188 confirmation.
     */
    const val ROADFLARE_KEY_SHARE = 3186

    /**
     * Kind 3187: RoadFlare Follow Notification Event (Regular)
     * Short-expiring notification (5 min) sent by rider when following a driver.
     * Primary discovery is via p-tag query on Kind 30011, but this provides
     * immediate real-time feedback when driver is online.
     *
     * Content is NIP-44 encrypted to driver's identity pubkey.
     * Contains: action ("follow" or "unfollow"), riderName, timestamp.
     * Uses "expiration" tag with short TTL (5 minutes) to reduce relay storage.
     */
    const val ROADFLARE_FOLLOW_NOTIFY = 3187

    /**
     * Kind 3188: RoadFlare Key Acknowledgement (Regular)
     * Ephemeral confirmation from rider to driver after receiving key.
     * Sent after rider successfully stores the RoadFlare key.
     * Content is NIP-44 encrypted to driver's identity pubkey.
     * Contains: keyVersion, keyUpdatedAt, status ("received").
     * Uses "expiration" tag with short TTL (5 minutes).
     * Driver uses this to confirm follower has current key.
     */
    const val ROADFLARE_KEY_ACK = 3188

    /**
     * Kind 30175: Vehicle Backup Event (Parameterized Replaceable)
     * @deprecated Use PROFILE_BACKUP (30177) instead. Vehicles are now part of unified profile backup.
     */
    @Deprecated("Use PROFILE_BACKUP (30177) instead")
    const val VEHICLE_BACKUP = 30175

    /**
     * Kind 30176: Saved Locations Backup Event (Parameterized Replaceable)
     * @deprecated Use PROFILE_BACKUP (30177) instead. Saved locations are now part of unified profile backup.
     */
    @Deprecated("Use PROFILE_BACKUP (30177) instead")
    const val SAVED_LOCATIONS_BACKUP = 30176
}

/**
 * Payment methods supported by the rideshare protocol.
 * Used in profile backup (supported methods) and ride events (requested method).
 */
enum class PaymentMethod(val value: String, val displayName: String = value) {
    CASHU("cashu", "Bitcoin (Cashu)"),
    LIGHTNING("lightning", "Lightning"),
    FIAT_CASH("fiat_cash", "Cash"),
    // RoadFlare-only alternate payment methods
    ZELLE("zelle", "Zelle"),
    PAYPAL("paypal", "PayPal"),
    CASH_APP("cash_app", "Cash App"),
    VENMO("venmo", "Venmo"),
    CASH("cash", "Cash"),
    STRIKE("strike", "Strike");

    companion object {
        /** Alternate payment methods available for RoadFlare rides only */
        val ROADFLARE_ALTERNATE_METHODS = listOf(ZELLE, PAYPAL, CASH_APP, VENMO, CASH, STRIKE)

        fun fromString(s: String): PaymentMethod? =
            entries.find { it.value == s }

        fun fromStringList(list: List<String>): List<PaymentMethod> =
            list.mapNotNull { fromString(it) }

        fun toStringList(methods: List<PaymentMethod>): List<String> =
            methods.map { it.value }
    }
}

/**
 * Payment path determined by comparing rider and driver mints.
 * Used to select the appropriate payment flow for a ride.
 */
enum class PaymentPath {
    /** Both rider and driver use the same Cashu mint - zero-fee HTLC flow */
    SAME_MINT,
    /** Rider and driver use different mints - Lightning bridge at pickup */
    CROSS_MINT,
    /** Cash payment - no digital escrow */
    FIAT_CASH,
    /** Wallet not connected or payment methods incompatible */
    NO_PAYMENT;

    companion object {
        /**
         * Determine the payment path based on rider/driver mint URLs and payment method.
         */
        fun determine(
            riderMintUrl: String?,
            driverMintUrl: String?,
            paymentMethod: String
        ): PaymentPath {
            // Handle non-ecash payment methods
            if (paymentMethod == "fiat_cash") return FIAT_CASH
            if (paymentMethod == "lightning") return CROSS_MINT  // Always Lightning bridge

            // For cashu: check mint compatibility
            if (paymentMethod == "cashu") {
                if (riderMintUrl == null || driverMintUrl == null) return NO_PAYMENT

                val normalizedRider = riderMintUrl.trimEnd('/').lowercase()
                val normalizedDriver = driverMintUrl.trimEnd('/').lowercase()

                return if (normalizedRider == normalizedDriver) {
                    SAME_MINT
                } else {
                    CROSS_MINT
                }
            }

            return NO_PAYMENT
        }
    }
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

    // RoadFlare events
    const val ROADFLARE_LOCATION_MINUTES = 5     // Real-time location, short TTL
    const val ROADFLARE_REQUEST_MINUTES = 15     // Same as ride offer
    const val ROADFLARE_SHAREABLE_LIST_DAYS = 30 // Shareable driver lists

    // Helper function for days
    fun daysFromNow(days: Int): Long =
        (System.currentTimeMillis() / 1000) + (days * 24L * 60L * 60L)

    // Helper functions to calculate expiration timestamp
    fun minutesFromNow(minutes: Int): Long =
        (System.currentTimeMillis() / 1000) + (minutes * 60L)

    fun hoursFromNow(hours: Int): Long =
        (System.currentTimeMillis() / 1000) + (hours * 60L * 60L)
}
