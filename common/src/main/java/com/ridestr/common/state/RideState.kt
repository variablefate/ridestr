package com.ridestr.common.state

/**
 * Unified ride state enum for the Ridestr state machine.
 *
 * This consolidates the separate DriverStage and RideStage enums into
 * a single canonical state representation that both apps can use.
 *
 * State Flow:
 * ```
 * CREATED → ACCEPTED → CONFIRMED → EN_ROUTE → ARRIVED → IN_PROGRESS → COMPLETED
 *    ↓         ↓          ↓           ↓          ↓           ↓
 *    └─────────┴──────────┴───────────┴──────────┴───────────┴──→ CANCELLED
 * ```
 */
enum class RideState {
    /**
     * Initial state - ride offer has been sent by rider.
     * Rider is waiting for driver acceptance.
     *
     * Driver equivalent: AVAILABLE (waiting for offers)
     * Rider equivalent: WAITING_FOR_ACCEPTANCE / BROADCASTING_REQUEST
     */
    CREATED,

    /**
     * Driver has accepted the ride offer.
     * Rider needs to confirm with precise pickup location.
     *
     * Driver equivalent: RIDE_ACCEPTED
     * Rider equivalent: DRIVER_ACCEPTED
     */
    ACCEPTED,

    /**
     * Rider has confirmed the ride with precise location.
     * Driver is now en route to pickup.
     * This state establishes the confirmationEventId used for all state events.
     *
     * Driver equivalent: EN_ROUTE_TO_PICKUP
     * Rider equivalent: RIDE_CONFIRMED
     */
    CONFIRMED,

    /**
     * Driver is en route to pickup location.
     * This is an explicit sub-state of CONFIRMED for tracking.
     *
     * Driver equivalent: EN_ROUTE_TO_PICKUP
     * Rider equivalent: RIDE_CONFIRMED (with EN_ROUTE status)
     */
    EN_ROUTE,

    /**
     * Driver has arrived at pickup location.
     * Waiting for PIN verification.
     *
     * Driver equivalent: ARRIVED_AT_PICKUP
     * Rider equivalent: DRIVER_ARRIVED
     */
    ARRIVED,

    /**
     * PIN verified, ride is in progress.
     * Driver is transporting rider to destination.
     *
     * Driver equivalent: IN_RIDE
     * Rider equivalent: IN_PROGRESS
     */
    IN_PROGRESS,

    /**
     * Ride successfully completed.
     * Payment settlement occurs in this state.
     *
     * Driver equivalent: RIDE_COMPLETED
     * Rider equivalent: COMPLETED
     */
    COMPLETED,

    /**
     * Ride was cancelled by either party.
     * Terminal state - no further transitions possible.
     *
     * Both apps return to IDLE/OFFLINE after processing.
     */
    CANCELLED;

    /**
     * Check if this state allows cancellation.
     * Cannot cancel a completed or already cancelled ride.
     */
    fun canCancel(): Boolean = this !in listOf(COMPLETED, CANCELLED)

    /**
     * Check if this is a terminal state (no further transitions).
     */
    fun isTerminal(): Boolean = this in listOf(COMPLETED, CANCELLED)

    /**
     * Check if this is an active ride state (after confirmation, before completion).
     */
    fun isActiveRide(): Boolean = this in listOf(CONFIRMED, EN_ROUTE, ARRIVED, IN_PROGRESS)

    /**
     * Check if PIN verification is expected in this state.
     */
    fun expectsPinVerification(): Boolean = this == ARRIVED

    /**
     * Check if payment settlement should occur in this state.
     */
    fun isSettlementState(): Boolean = this == COMPLETED

    companion object {
        /**
         * Map from driver's currentStatus string to RideState.
         * Used when parsing Kind 30180 events.
         */
        fun fromDriverStatus(status: String): RideState? = when (status) {
            "en_route_pickup" -> EN_ROUTE
            "arrived" -> ARRIVED
            "in_progress" -> IN_PROGRESS
            "completed" -> COMPLETED
            "cancelled" -> CANCELLED
            else -> null
        }

        /**
         * Map from rider's currentPhase string to RideState.
         * Used when parsing Kind 30181 events.
         */
        fun fromRiderPhase(phase: String): RideState? = when (phase) {
            "awaiting_driver" -> CREATED
            "awaiting_pin" -> ARRIVED
            "verified" -> IN_PROGRESS
            "in_ride" -> IN_PROGRESS
            else -> null
        }

        /**
         * Convert to driver status string for Kind 30180.
         */
        fun RideState.toDriverStatus(): String? = when (this) {
            EN_ROUTE -> "en_route_pickup"
            ARRIVED -> "arrived"
            IN_PROGRESS -> "in_progress"
            COMPLETED -> "completed"
            CANCELLED -> "cancelled"
            else -> null
        }
    }
}

/**
 * Extension to convert DriverStage enum to RideState.
 * Used for integration with existing DriverViewModel.
 *
 * Note: This function lives in common/ but references DriverStage by string
 * to avoid circular dependency. The actual conversion is done in the driver app.
 */
fun RideState.Companion.fromDriverStage(stageName: String): RideState = when (stageName) {
    "OFFLINE" -> RideState.CANCELLED  // No active ride when offline
    "AVAILABLE" -> RideState.CREATED  // Waiting for offers = pre-created state
    "RIDE_ACCEPTED" -> RideState.ACCEPTED
    "EN_ROUTE_TO_PICKUP" -> RideState.EN_ROUTE
    "ARRIVED_AT_PICKUP" -> RideState.ARRIVED
    "IN_RIDE" -> RideState.IN_PROGRESS
    "RIDE_COMPLETED" -> RideState.COMPLETED
    else -> RideState.CANCELLED
}

/**
 * Convert RideState to DriverStage name string.
 */
fun RideState.toDriverStageName(): String = when (this) {
    RideState.CREATED -> "AVAILABLE"  // Pre-offer state
    RideState.ACCEPTED -> "RIDE_ACCEPTED"
    RideState.CONFIRMED -> "EN_ROUTE_TO_PICKUP"  // After confirmation, driver starts route
    RideState.EN_ROUTE -> "EN_ROUTE_TO_PICKUP"
    RideState.ARRIVED -> "ARRIVED_AT_PICKUP"
    RideState.IN_PROGRESS -> "IN_RIDE"
    RideState.COMPLETED -> "RIDE_COMPLETED"
    RideState.CANCELLED -> "OFFLINE"
}
