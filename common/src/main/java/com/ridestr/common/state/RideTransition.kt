package com.ridestr.common.state

/**
 * Defines a valid state transition in the ride state machine.
 *
 * Each transition specifies:
 * - The starting state (from)
 * - The event that triggers the transition (eventType)
 * - The resulting state (to)
 * - Optional guard that must pass (guard name)
 * - Optional action to execute (action name)
 */
data class RideTransition(
    /** State the machine must be in for this transition */
    val from: RideState,

    /** Event type that triggers this transition */
    val eventType: String,

    /** State to transition to */
    val to: RideState,

    /** Name of guard function that must return true (null = no guard) */
    val guard: String? = null,

    /** Name of action function to execute on transition (null = no action) */
    val action: String? = null,

    /** Human-readable description of this transition */
    val description: String = ""
)

/**
 * The complete transition table for the Ridestr ride state machine.
 *
 * This is THE authoritative definition of all valid state transitions.
 * Guards and actions are referenced by name and looked up at runtime.
 *
 * Transition Flow:
 * ```
 * CREATED ──ACCEPT──► ACCEPTED ──CONFIRM──► CONFIRMED ──START_ROUTE──► EN_ROUTE
 *                                                                          │
 *    ┌─────────────────────────────────────────────────────────────────────┘
 *    │
 *    └──ARRIVE──► ARRIVED ──VERIFY_PIN(true)──► IN_PROGRESS ──COMPLETE──► COMPLETED
 *
 * Any state except COMPLETED can transition to CANCELLED via CANCEL event.
 * ```
 */
object RideTransitions {

    val all: List<RideTransition> = listOf(
        // === CREATED State Transitions ===
        RideTransition(
            from = RideState.CREATED,
            eventType = "ACCEPT",
            to = RideState.ACCEPTED,
            guard = "isNotRider",  // Driver cannot be the rider
            action = "assignDriver",
            description = "Driver accepts ride offer"
        ),
        RideTransition(
            from = RideState.CREATED,
            eventType = "CANCEL",
            to = RideState.CANCELLED,
            guard = "isRiderOrDriver",
            action = "notifyCancellation",
            description = "Rider or driver cancels before acceptance"
        ),
        RideTransition(
            from = RideState.CREATED,
            eventType = "CONFIRMATION_TIMEOUT",
            to = RideState.CANCELLED,
            guard = null,  // System event, no guard needed
            action = "notifyTimeout",
            description = "Offer expired without acceptance"
        ),

        // === ACCEPTED State Transitions ===
        RideTransition(
            from = RideState.ACCEPTED,
            eventType = "CONFIRM",
            to = RideState.CONFIRMED,
            guard = "isRider",  // Only rider can confirm
            action = "lockEscrow",
            description = "Rider confirms with precise location"
        ),
        RideTransition(
            from = RideState.ACCEPTED,
            eventType = "CANCEL",
            to = RideState.CANCELLED,
            guard = "isRiderOrDriver",
            action = "notifyCancellation",
            description = "Either party cancels after acceptance"
        ),
        RideTransition(
            from = RideState.ACCEPTED,
            eventType = "CONFIRMATION_TIMEOUT",
            to = RideState.CANCELLED,
            guard = null,
            action = "notifyTimeout",
            description = "Confirmation timeout after 30 seconds"
        ),

        // === CONFIRMED State Transitions ===
        RideTransition(
            from = RideState.CONFIRMED,
            eventType = "START_ROUTE",
            to = RideState.EN_ROUTE,
            guard = "isDriver",
            action = null,
            description = "Driver starts navigation to pickup"
        ),
        // Allow direct transition to EN_ROUTE from CONFIRMED (implicit start)
        RideTransition(
            from = RideState.CONFIRMED,
            eventType = "ARRIVE",
            to = RideState.ARRIVED,
            guard = "isDriver",
            action = null,
            description = "Driver arrives (skipping explicit EN_ROUTE)"
        ),
        RideTransition(
            from = RideState.CONFIRMED,
            eventType = "CANCEL",
            to = RideState.CANCELLED,
            guard = "isRiderOrDriver",
            action = "notifyCancellation",
            description = "Either party cancels after confirmation"
        ),

        // === EN_ROUTE State Transitions ===
        RideTransition(
            from = RideState.EN_ROUTE,
            eventType = "ARRIVE",
            to = RideState.ARRIVED,
            guard = "isDriver",
            action = null,
            description = "Driver arrives at pickup location"
        ),
        RideTransition(
            from = RideState.EN_ROUTE,
            eventType = "CANCEL",
            to = RideState.CANCELLED,
            guard = "isRiderOrDriver",
            action = "notifyCancellation",
            description = "Either party cancels while en route"
        ),

        // === ARRIVED State Transitions ===
        RideTransition(
            from = RideState.ARRIVED,
            eventType = "VERIFY_PIN",
            to = RideState.IN_PROGRESS,
            guard = "isPinVerified",  // Special guard checks event.verified
            action = "startRideAfterPin",
            description = "PIN verified, ride begins"
        ),
        RideTransition(
            from = RideState.ARRIVED,
            eventType = "START_RIDE",
            to = RideState.IN_PROGRESS,
            guard = "isDriverAndPinVerified",  // PIN must be verified
            action = null,
            description = "Driver starts ride after PIN verification"
        ),
        RideTransition(
            from = RideState.ARRIVED,
            eventType = "CANCEL",
            to = RideState.CANCELLED,
            guard = "isRiderOrDriver",
            action = "notifyCancellation",
            description = "Either party cancels at pickup"
        ),
        // PIN brute force leads to cancellation
        RideTransition(
            from = RideState.ARRIVED,
            eventType = "VERIFY_PIN",
            to = RideState.CANCELLED,
            guard = "isPinBruteForce",  // 3+ failed attempts
            action = "notifyPinBruteForce",
            description = "PIN brute force limit exceeded"
        ),

        // === IN_PROGRESS State Transitions ===
        RideTransition(
            from = RideState.IN_PROGRESS,
            eventType = "COMPLETE",
            to = RideState.COMPLETED,
            guard = "isDriver",
            action = "settlePayment",
            description = "Driver completes ride at destination"
        ),
        RideTransition(
            from = RideState.IN_PROGRESS,
            eventType = "CANCEL",
            to = RideState.CANCELLED,
            guard = "isRiderOrDriver",
            action = "notifyCancellation",
            description = "Either party cancels during ride"
        )

        // COMPLETED and CANCELLED are terminal states - no transitions out
    )

    /**
     * Find a transition matching the current state and event type.
     *
     * Returns the first matching transition, or null if no valid transition exists.
     * Note: Multiple transitions may match (e.g., VERIFY_PIN can go to IN_PROGRESS
     * or CANCELLED depending on guard). Guards are evaluated separately.
     */
    fun findTransition(from: RideState, eventType: String): List<RideTransition> {
        return all.filter { it.from == from && it.eventType == eventType }
    }

    /**
     * Get all valid event types from a given state.
     */
    fun validEventsFrom(state: RideState): Set<String> {
        return all.filter { it.from == state }.map { it.eventType }.toSet()
    }

    /**
     * Check if a transition is valid (ignoring guards).
     */
    fun isValidTransition(from: RideState, eventType: String): Boolean {
        return findTransition(from, eventType).isNotEmpty()
    }

    /**
     * Get all states that can be reached from a given state.
     */
    fun reachableStatesFrom(state: RideState): Set<RideState> {
        return all.filter { it.from == state }.map { it.to }.toSet()
    }
}
