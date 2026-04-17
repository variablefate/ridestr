package com.ridestr.common.nostr

import com.ridestr.common.nostr.events.Location

/**
 * Typed intent for ride offer publishing. Each variant enforces valid parameter
 * combinations at compile time — eliminates the boolean-flag antipattern in
 * sendRideOffer() and the manual-conversion burden of broadcastRideRequest().
 *
 * Dispatch authority: RideshareDomainService.sendOffer(spec) only.
 */
sealed class RideOfferSpec {

    /** Direct offer to a specific driver discovered via Kind 30173 availability.
     *  availabilityEventId is nullable: boost/restore after process death loses it. */
    data class Direct(
        val driverPubKey: String,
        val driverAvailabilityEventId: String?,  // Nullable — lost after process death boost
        val pickup: Location,
        val destination: Location,
        val fareEstimate: Double,
        val pickupRoute: RouteMetrics?,
        val rideRoute: RouteMetrics?,
        val mintUrl: String?,
        val paymentMethod: String,
        val fiatPaymentMethods: List<String> = emptyList(),
        // Authoritative fiat fare per ADR-0008 (both-or-neither)
        val fareFiatAmount: String? = null,
        val fareFiatCurrency: String? = null
    ) : RideOfferSpec()

    /** RoadFlare offer to a followed driver. No availability event reference. */
    data class RoadFlare(
        val driverPubKey: String,
        // No driverAvailabilityEventId — compile-time enforcement
        val pickup: Location,
        val destination: Location,
        val fareEstimate: Double,
        val pickupRoute: RouteMetrics?,
        val rideRoute: RouteMetrics?,
        val mintUrl: String?,
        val paymentMethod: String,
        val fiatPaymentMethods: List<String> = emptyList(),
        // Authoritative fiat fare per ADR-0008 (both-or-neither)
        val fareFiatAmount: String? = null,
        val fareFiatCurrency: String? = null
    ) : RideOfferSpec()

    /** Public broadcast visible to all drivers in pickup area. Precise locations —
     *  RideOfferEvent.createBroadcast() handles privacy approximation internally. */
    data class Broadcast(
        val pickup: Location,
        val destination: Location,
        val fareEstimate: Double,
        val routeDistance: RouteMetrics,  // Required for broadcasts
        val mintUrl: String?,
        val paymentMethod: String,
        // Authoritative fiat fare per ADR-0008 (both-or-neither)
        val fareFiatAmount: String? = null,
        val fareFiatCurrency: String? = null
    ) : RideOfferSpec()
}

/** Route distance/duration pair — eliminates 4 separate nullable Double params. */
data class RouteMetrics(val distanceKm: Double, val durationMin: Double) {
    companion object {
        /** Convert from seconds-based source (e.g. RouteResult.durationSeconds). */
        fun fromSeconds(distanceKm: Double, durationSeconds: Double) =
            RouteMetrics(distanceKm, durationSeconds / 60.0)
    }
}
