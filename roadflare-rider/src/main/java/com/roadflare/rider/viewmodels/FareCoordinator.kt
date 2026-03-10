package com.roadflare.rider.viewmodels

import com.ridestr.common.nostr.events.Location
import com.ridestr.common.routing.ValhallaRoutingService
import com.ridestr.common.util.FareCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Result of a route calculation between two locations.
 * Contains distance and duration only — fare computation is handled
 * by RoadflareFarePolicy (per-driver, config-aware).
 */
data class RouteResult(
    val distanceMiles: Double,
    val durationMinutes: Int,
    val polyline: String? = null
)

/**
 * Coordinates route estimation between pickup and destination.
 *
 * Uses Valhalla offline routing when tiles are available,
 * falling back to haversine (great-circle) distance otherwise.
 *
 * Fare calculation has been extracted to RoadflareFarePolicy in :common
 * (per-driver, config-aware, no hardcoded rates).
 */
class FareCoordinator(
    private val valhallaRoutingService: ValhallaRoutingService
) {
    companion object {
        // Rough estimate: 2.5 minutes per mile for duration fallback
        private const val MINUTES_PER_MILE_ESTIMATE = 2.5
    }

    /**
     * Calculate a route between pickup and dropoff locations.
     *
     * Uses Valhalla offline routing when tiles are available,
     * falling back to haversine (great-circle) distance otherwise.
     */
    suspend fun calculateRoute(pickup: Location, dropoff: Location): RouteResult = withContext(Dispatchers.IO) {
        // Try Valhalla routing first
        if (valhallaRoutingService.isReady()) {
            val result = valhallaRoutingService.calculateRoute(
                pickup.lat, pickup.lon, dropoff.lat, dropoff.lon
            )
            if (result != null) {
                val distanceMiles = result.distanceKm * FareCalculator.KM_TO_MILES
                val durationMinutes = (result.durationSeconds / 60).toInt()
                return@withContext RouteResult(
                    distanceMiles = distanceMiles,
                    durationMinutes = durationMinutes,
                    polyline = result.encodedPolyline
                )
            }
        }

        // Fallback: haversine distance via Location.distanceToKm()
        val distanceKm = pickup.distanceToKm(dropoff)
        val distanceMiles = distanceKm * FareCalculator.KM_TO_MILES
        val durationMinutes = (distanceMiles * MINUTES_PER_MILE_ESTIMATE).toInt()

        RouteResult(
            distanceMiles = distanceMiles,
            durationMinutes = durationMinutes
        )
    }
}
