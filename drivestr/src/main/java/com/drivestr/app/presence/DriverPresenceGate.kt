package com.drivestr.app.presence

/**
 * Typed gate values for the background listener notification decision.
 * AVAILABLE/IN_RIDE = main app is handling offers, suppress background notifications.
 * ROADFLARE_ONLY = background listener shows RoadFlare notifications.
 */
enum class DriverPresenceGate {
    AVAILABLE,
    ROADFLARE_ONLY,
    IN_RIDE
}
