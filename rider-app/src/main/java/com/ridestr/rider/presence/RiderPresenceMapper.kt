package com.ridestr.rider.presence

import com.ridestr.rider.viewmodels.RideStage

/**
 * Centralizes RideStage → RiderPresenceMode derivation.
 *
 * Single channel (no gate, no RoadFlare location):
 * - Base notification mode via [presenceMode]
 *
 * NOT mapped here:
 * - Overlay: Cancelled (terminal, always updateStatus)
 * - Service lifecycle: startSearching(), stop()
 */
object RiderPresenceMapper {

    /**
     * Maps ride stage to the base presence mode for the notification.
     * Returns null for stages where the service is not running or should be
     * started/stopped explicitly (IDLE, BROADCASTING_REQUEST, WAITING_FOR_ACCEPTANCE, COMPLETED).
     */
    fun presenceMode(stage: RideStage): RiderPresenceMode? = when (stage) {
        RideStage.IDLE,
        RideStage.BROADCASTING_REQUEST,
        RideStage.WAITING_FOR_ACCEPTANCE,
        RideStage.COMPLETED -> null
        RideStage.DRIVER_ACCEPTED -> RiderPresenceMode.DRIVER_ACCEPTED
        RideStage.RIDE_CONFIRMED -> RiderPresenceMode.DRIVER_EN_ROUTE
        RideStage.DRIVER_ARRIVED -> RiderPresenceMode.DRIVER_ARRIVED
        RideStage.IN_PROGRESS -> RiderPresenceMode.IN_RIDE
    }
}
