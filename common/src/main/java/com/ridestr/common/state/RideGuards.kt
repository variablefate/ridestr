package com.ridestr.common.state

/**
 * Type alias for guard functions.
 *
 * A guard takes the current context and the triggering event,
 * and returns true if the transition should be allowed.
 */
typealias Guard = (context: RideContext, event: RideEvent) -> Boolean

/**
 * Named guard functions for the ride state machine.
 *
 * Guards are authorization checks that determine whether a transition
 * is allowed. They follow the AtoB pattern of named guards that can
 * be referenced in the transition table and evaluated at runtime.
 *
 * Benefits:
 * - Testable in isolation
 * - Clearly documented authorization logic
 * - Reusable across transitions
 * - Auditable (names appear in logs/errors)
 */
object RideGuards {

    /**
     * Registry of all named guards.
     */
    val registry: Map<String, Guard> = mapOf(
        "isRider" to ::isRider,
        "isDriver" to ::isDriver,
        "isNotRider" to ::isNotRider,
        "isRiderOrDriver" to ::isRiderOrDriver,
        "isPinVerified" to ::isPinVerified,
        "isPinBruteForce" to ::isPinBruteForce,
        "isDriverAndPinVerified" to ::isDriverAndPinVerified,
        "hasEscrowLocked" to ::hasEscrowLocked,
        "canSettle" to ::canSettle,
        "isSameMint" to ::isSameMint
    )

    /**
     * Get a guard by name from the registry.
     */
    fun get(name: String): Guard? = registry[name]

    /**
     * Evaluate a guard by name.
     *
     * @return true if guard passes, false if it fails or doesn't exist
     */
    fun evaluate(name: String?, context: RideContext, event: RideEvent): Boolean {
        if (name == null) return true  // No guard = always pass
        val guard = registry[name] ?: return false
        return guard(context, event)
    }

    // === Identity Guards ===

    /**
     * Check if the event inputter is the rider.
     */
    fun isRider(context: RideContext, event: RideEvent): Boolean {
        return event.inputterPubkey == context.riderPubkey
    }

    /**
     * Check if the event inputter is the driver.
     */
    fun isDriver(context: RideContext, event: RideEvent): Boolean {
        return context.driverPubkey != null && event.inputterPubkey == context.driverPubkey
    }

    /**
     * Check if the event inputter is NOT the rider.
     * Used for acceptance (driver cannot accept their own offer).
     */
    fun isNotRider(context: RideContext, event: RideEvent): Boolean {
        return event.inputterPubkey != context.riderPubkey
    }

    /**
     * Check if the event inputter is either the rider or the driver.
     * Used for cancellation (either party can cancel).
     */
    fun isRiderOrDriver(context: RideContext, event: RideEvent): Boolean {
        return isRider(context, event) || isDriver(context, event)
    }

    // === PIN Verification Guards ===

    /**
     * Check if the PIN was verified successfully.
     * Only applicable to VerifyPin events.
     */
    fun isPinVerified(context: RideContext, event: RideEvent): Boolean {
        return when (event) {
            is RideEvent.VerifyPin -> event.verified && !context.isPinBruteForceLimitReached()
            else -> context.pinVerified
        }
    }

    /**
     * Check if PIN brute force limit has been reached.
     * Returns true if this verification pushed us over the limit.
     */
    fun isPinBruteForce(context: RideContext, event: RideEvent): Boolean {
        return when (event) {
            is RideEvent.VerifyPin -> {
                // Check if this attempt (not previous) triggers brute force
                !event.verified && event.attempt >= context.maxPinAttempts
            }
            else -> context.isPinBruteForceLimitReached()
        }
    }

    /**
     * Check if inputter is driver AND PIN has been verified.
     * Used for explicit START_RIDE transition.
     */
    fun isDriverAndPinVerified(context: RideContext, event: RideEvent): Boolean {
        return isDriver(context, event) && context.pinVerified
    }

    // === Payment Guards ===

    /**
     * Check if escrow has been successfully locked.
     */
    fun hasEscrowLocked(context: RideContext, event: RideEvent): Boolean {
        return context.escrowLocked
    }

    /**
     * Check if all conditions for settlement are met.
     */
    fun canSettle(context: RideContext, event: RideEvent): Boolean {
        return context.canSettle()
    }

    /**
     * Check if rider and driver are using the same mint.
     * Determines if direct HTLC or Lightning bridge is needed.
     */
    fun isSameMint(context: RideContext, event: RideEvent): Boolean {
        return context.isSameMint()
    }

    // === Utility ===

    /**
     * Always passes - used for transitions with no guard.
     */
    fun alwaysTrue(context: RideContext, event: RideEvent): Boolean = true

    /**
     * Always fails - used for disabled transitions.
     */
    fun alwaysFalse(context: RideContext, event: RideEvent): Boolean = false

    /**
     * Get a human-readable explanation of why a guard failed.
     * Useful for error messages and debugging.
     */
    fun explainFailure(guardName: String, context: RideContext, event: RideEvent): String {
        return when (guardName) {
            "isRider" -> "Only the rider (${context.riderPubkey.take(8)}...) can perform this action. " +
                         "Attempted by: ${event.inputterPubkey.take(8)}..."
            "isDriver" -> "Only the driver (${context.driverPubkey?.take(8) ?: "none"}...) can perform this action. " +
                          "Attempted by: ${event.inputterPubkey.take(8)}..."
            "isNotRider" -> "The rider cannot perform this action on their own offer."
            "isRiderOrDriver" -> "Only ride participants can perform this action. " +
                                 "Attempted by: ${event.inputterPubkey.take(8)}..."
            "isPinVerified" -> "PIN verification required. Verified: ${context.pinVerified}"
            "isPinBruteForce" -> "PIN attempt limit (${context.maxPinAttempts}) reached. " +
                                 "Attempts: ${context.pinAttempts}"
            "isDriverAndPinVerified" -> "Only driver can start ride, and PIN must be verified. " +
                                        "Is driver: ${isDriver(context, event)}, PIN verified: ${context.pinVerified}"
            "hasEscrowLocked" -> "Escrow must be locked. Locked: ${context.escrowLocked}"
            "canSettle" -> "Cannot settle. Escrow: ${context.escrowLocked}, " +
                          "Preimage: ${context.preimage != null}, Token: ${context.escrowToken != null}"
            "isSameMint" -> "Mints differ. Rider: ${context.riderMintUrl}, Driver: ${context.driverMintUrl}"
            else -> "Unknown guard: $guardName"
        }
    }
}
