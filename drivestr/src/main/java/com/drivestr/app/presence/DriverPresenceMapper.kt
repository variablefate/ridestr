package com.drivestr.app.presence

import com.drivestr.app.service.DriverStatus
import com.ridestr.common.nostr.events.RoadflareLocationEvent

/**
 * Centralizes pure stage-to-status derivations across driver presence channels.
 *
 * Three channels:
 * - Kind 30014 (RoadFlare location) via [roadflareStatus]
 * - Foreground service base state via [serviceBaseStatus]
 * - Background listener gate via [listenerGateStatus]
 *
 * NOT mapped here (intentionally):
 * - Kind 30173 availability (context-dependent: location, vehicle, mint, payment methods)
 * - Notification overlays: NewRequest, Cancelled, Available(requestCount)
 * - Service lifecycle: start(), stop()
 */
object DriverPresenceMapper {

    /** Channel 1: RoadFlare location broadcast status (Kind 30014). */
    fun roadflareStatus(stage: DriverStage): String = when (stage) {
        DriverStage.OFFLINE -> RoadflareLocationEvent.Status.OFFLINE
        DriverStage.ROADFLARE_ONLY,
        DriverStage.AVAILABLE -> RoadflareLocationEvent.Status.ONLINE
        DriverStage.RIDE_ACCEPTED,
        DriverStage.EN_ROUTE_TO_PICKUP,
        DriverStage.ARRIVED_AT_PICKUP,
        DriverStage.IN_RIDE,
        DriverStage.RIDE_COMPLETED -> RoadflareLocationEvent.Status.ON_RIDE
    }

    /**
     * Channel 2: Base foreground service status.
     * Returns null for stages where the service should be stopped or is transient.
     * Does NOT handle NewRequest, Cancelled, or Available(requestCount).
     */
    fun serviceBaseStatus(stage: DriverStage): DriverStatus? = when (stage) {
        DriverStage.OFFLINE -> null
        DriverStage.ROADFLARE_ONLY -> DriverStatus.RoadflareOnly
        DriverStage.AVAILABLE -> DriverStatus.Available(0)
        DriverStage.RIDE_ACCEPTED -> null  // Transient — EN_ROUTE follows immediately
        DriverStage.EN_ROUTE_TO_PICKUP -> DriverStatus.EnRouteToPickup(null)
        DriverStage.ARRIVED_AT_PICKUP -> DriverStatus.ArrivedAtPickup(null)
        DriverStage.IN_RIDE -> DriverStatus.RideInProgress(null)
        DriverStage.RIDE_COMPLETED -> null  // Completion screen, service stopped later
    }

    /**
     * Channel 3: Listener gate — determines whether the background
     * RoadflareListenerService should show notifications or defer to the main app.
     * Exhaustive on DriverStatus (sealed class).
     */
    fun listenerGateStatus(status: DriverStatus): DriverPresenceGate = when (status) {
        is DriverStatus.Available -> DriverPresenceGate.AVAILABLE
        is DriverStatus.RoadflareOnly -> DriverPresenceGate.ROADFLARE_ONLY
        is DriverStatus.EnRouteToPickup,
        is DriverStatus.ArrivedAtPickup,
        is DriverStatus.RideInProgress -> DriverPresenceGate.IN_RIDE
        is DriverStatus.NewRequest -> DriverPresenceGate.AVAILABLE
        is DriverStatus.Cancelled -> DriverPresenceGate.AVAILABLE
    }
}
