package com.ridestr.rider.presence

/**
 * Base operational mode for the rider foreground service.
 * Maps RideStage to the service's notification state without overlay concerns.
 */
enum class RiderPresenceMode : java.io.Serializable {
    /** Searching for drivers / waiting for acceptance */
    SEARCHING,
    /** Driver accepted, awaiting pickup */
    DRIVER_ACCEPTED,
    /** Driver en route to pickup location */
    DRIVER_EN_ROUTE,
    /** Driver arrived at pickup */
    DRIVER_ARRIVED,
    /** Ride in progress */
    IN_RIDE
}
