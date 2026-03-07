package com.roadflare.rider.viewmodels

import com.ridestr.common.bitcoin.BitcoinPriceService
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.routing.ValhallaRoutingService
import com.ridestr.common.util.FareCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Result of a route calculation between two locations.
 */
data class RouteResult(
    val distanceMiles: Double,
    val durationMinutes: Int,
    val fareUsd: Double,
    val fareSats: Long? = null,
    val polyline: String? = null
)

/**
 * Coordinates fare calculation and route estimation.
 *
 * Uses FareCalculator for USD fare math and BitcoinPriceService for
 * optional sats conversion. Valhalla routing will be integrated for
 * road-distance calculations; until then, haversine is used as fallback.
 *
 * The RiderViewModel delegates fare/route operations here.
 */
class FareCoordinator(
    private val fareCalculator: FareCalculator,
    private val bitcoinPriceService: BitcoinPriceService,
    private val valhallaRoutingService: ValhallaRoutingService
) {
    companion object {
        private const val DEFAULT_RATE_PER_MILE = 2.50
        private const val DEFAULT_MINIMUM_FARE = 8.0
        private const val DEFAULT_BASE_FARE = 3.0

        // Rough estimate: 2.5 minutes per mile for duration fallback
        private const val MINUTES_PER_MILE_ESTIMATE = 2.5
    }

    /**
     * Calculate the fare in USD for a given distance.
     */
    fun calculateFare(distanceMiles: Double): Double {
        return fareCalculator.calculateFareUsd(
            totalDistanceMiles = distanceMiles,
            ratePerMile = DEFAULT_RATE_PER_MILE,
            minimumFareUsd = DEFAULT_MINIMUM_FARE,
            baseFareUsd = DEFAULT_BASE_FARE
        )
    }

    /**
     * Convert a USD amount to satoshis using the current BTC price.
     * Returns null if the price is unavailable.
     */
    fun convertToSats(usdAmount: Double): Long? {
        return bitcoinPriceService.usdToSats(usdAmount)
    }

    /**
     * Check if a RoadFlare fare exceeds the allowed surcharge over a normal fare.
     */
    fun isTooFar(roadflareFareUsd: Double, normalFareUsd: Double): Boolean {
        return fareCalculator.isTooFar(roadflareFareUsd, normalFareUsd)
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
                val fareUsd = calculateFare(distanceMiles)
                val fareSats = convertToSats(fareUsd)
                return@withContext RouteResult(
                    distanceMiles = distanceMiles,
                    durationMinutes = durationMinutes,
                    fareUsd = fareUsd,
                    fareSats = fareSats,
                    polyline = result.encodedPolyline
                )
            }
        }

        // Fallback: haversine distance via Location.distanceToKm()
        val distanceKm = pickup.distanceToKm(dropoff)
        val distanceMiles = distanceKm * FareCalculator.KM_TO_MILES
        val durationMinutes = (distanceMiles * MINUTES_PER_MILE_ESTIMATE).toInt()
        val fareUsd = calculateFare(distanceMiles)
        val fareSats = convertToSats(fareUsd)

        RouteResult(
            distanceMiles = distanceMiles,
            durationMinutes = durationMinutes,
            fareUsd = fareUsd,
            fareSats = fareSats
        )
    }
}
