package com.drivestr.app.presence

/**
 * Driver state machine stages.
 */
enum class DriverStage {
    OFFLINE,
    ROADFLARE_ONLY,
    AVAILABLE,
    RIDE_ACCEPTED,
    EN_ROUTE_TO_PICKUP,
    ARRIVED_AT_PICKUP,
    IN_RIDE,
    RIDE_COMPLETED
}
