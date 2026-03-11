package com.drivestr.app.presence

import com.ridestr.common.nostr.events.RoadflareLocationEvent

/**
 * Centralizes pure stage-to-status derivations across driver presence channels.
 *
 * Three channels:
 * - Kind 30014 (RoadFlare location) via [roadflareStatus]
 * - Base operational mode via [presenceMode]
 * - Base presence gate via [presenceGate]
 *
 * NOT mapped here (intentionally):
 * - Kind 30173 availability (context-dependent: location, vehicle, mint, payment methods)
 * - Notification overlays: NewRequest, Cancelled, Available(requestCount)
 * - Overlay gate derivation: gateForStatus() in DriverOnlineService.kt
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
     * Channel 2: Base operational mode.
     * Returns OFF for stages where the service should be stopped or is transient.
     * Does NOT handle NewRequest, Cancelled, or Available(requestCount) — those are
     * notification overlays that stay in DriverStatus.
     */
    internal fun presenceMode(stage: DriverStage): PresenceMode = when (stage) {
        DriverStage.OFFLINE -> PresenceMode.OFF
        DriverStage.ROADFLARE_ONLY -> PresenceMode.ROADFLARE_ONLY
        DriverStage.AVAILABLE -> PresenceMode.AVAILABLE
        DriverStage.RIDE_ACCEPTED -> PresenceMode.OFF        // Transient — EN_ROUTE follows immediately
        DriverStage.EN_ROUTE_TO_PICKUP -> PresenceMode.EN_ROUTE
        DriverStage.ARRIVED_AT_PICKUP -> PresenceMode.AT_PICKUP
        DriverStage.IN_RIDE -> PresenceMode.IN_RIDE
        DriverStage.RIDE_COMPLETED -> PresenceMode.OFF       // Completion screen, service stopped later
    }

    /**
     * Channel 3: Base presence gate for the background listener.
     * Returns null for OFF (store uses null = offline/service not running).
     * Does NOT handle overlay statuses — those use gateForStatus() in DriverOnlineService.
     */
    internal fun presenceGate(mode: PresenceMode): DriverPresenceGate? = when (mode) {
        PresenceMode.OFF -> null
        PresenceMode.ROADFLARE_ONLY -> DriverPresenceGate.ROADFLARE_ONLY
        PresenceMode.AVAILABLE -> DriverPresenceGate.AVAILABLE
        PresenceMode.EN_ROUTE,
        PresenceMode.AT_PICKUP,
        PresenceMode.IN_RIDE -> DriverPresenceGate.IN_RIDE
    }
}
