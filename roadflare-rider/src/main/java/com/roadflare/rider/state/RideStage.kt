package com.roadflare.rider.state

import com.ridestr.common.state.RideState

/**
 * UI projection enum derived from RideState + session metadata.
 * RideStage is NOT persisted — it is always derived from RideState via fromRideState().
 * RideState (FSM) is canonical; RideStage is UI projection only.
 */
enum class RideStage {
    /** No active ride */
    IDLE,
    /** Offers sent, waiting for any acceptance */
    REQUESTING,
    /** One or more drivers accepted, rider choosing */
    CHOOSING_DRIVER,
    /** Ride confirmed with a specific driver */
    MATCHED,
    /** Driver is en route to pickup */
    DRIVER_EN_ROUTE,
    /** Driver has arrived at pickup */
    DRIVER_ARRIVED,
    /** Ride is in progress */
    IN_RIDE,
    /** Ride completed successfully */
    COMPLETED,
    /** Ride was cancelled */
    CANCELLED;

    companion object {
        /**
         * Derive RideStage from RideState and session metadata.
         *
         * @param state The canonical ride state from the FSM
         * @param hasAcceptances Whether any driver has accepted the offer
         * @param hasSentOffers Whether offers have been sent to drivers
         * @return The UI stage to display
         */
        fun fromRideState(
            state: RideState,
            hasAcceptances: Boolean = false,
            hasSentOffers: Boolean = false
        ): RideStage = when (state) {
            RideState.CREATED -> when {
                hasAcceptances -> CHOOSING_DRIVER
                hasSentOffers -> REQUESTING
                else -> IDLE
            }
            RideState.ACCEPTED -> CHOOSING_DRIVER
            RideState.CONFIRMED -> MATCHED
            RideState.EN_ROUTE -> DRIVER_EN_ROUTE
            RideState.ARRIVED -> DRIVER_ARRIVED
            RideState.IN_PROGRESS -> IN_RIDE
            RideState.COMPLETED -> COMPLETED
            RideState.CANCELLED -> CANCELLED
        }
    }
}
