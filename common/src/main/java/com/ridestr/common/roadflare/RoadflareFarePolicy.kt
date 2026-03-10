package com.ridestr.common.roadflare

import com.ridestr.common.bitcoin.BitcoinPriceService
import com.ridestr.common.nostr.events.AdminConfig
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.util.FareCalculator

/**
 * Per-driver fare state — controls what the UI shows and whether interaction is allowed.
 */
enum class FareState {
    /** Exact route in progress — "Fare calculating...", no fare amount, card disabled */
    CALCULATING,
    /** Road route succeeded — normal fare display, no label, card enabled */
    EXACT,
    /** Route failed or no tile coverage — fare + small "Approx." label, card enabled */
    FALLBACK,
    /** Pre-send estimate (haversine) — fare + "Est. fare" subtitle, card enabled.
     *  Used by rider-app sheet where exact routing happens later at send time. */
    ESTIMATED
}

/**
 * Per-driver fare quote with routing and eligibility data.
 */
data class RoadflareDriverQuote(
    val fareUsd: Double,
    val fareSats: Long?,
    val pickupMiles: Double,
    val rideMiles: Double,
    val totalMiles: Double,
    val normalRideFareUsd: Double,
    val isTooFar: Boolean,
    val fareState: FareState
)

/**
 * Shared fare quoting logic for RoadFlare rides, used by both apps.
 *
 * RoadFlare fares: ratePerMile * totalMiles with minimum enforced.
 * No base fare — RoadFlare has no base fare.
 */
object RoadflareFarePolicy {

    /**
     * Per-driver fare: (pickupMiles + rideMiles) * roadflareRate, min fare enforced.
     *
     * @param pickupLat Rider's pickup latitude.
     * @param pickupLon Rider's pickup longitude.
     * @param driverLat Driver's current latitude.
     * @param driverLon Driver's current longitude.
     * @param rideMiles Pre-computed ride leg distance in miles.
     * @param config Admin config with fare rates.
     * @param pickupDistanceMiles Pre-computed pickup distance. Pass null to use haversine fallback.
     * @param fareState Caller specifies EXACT, FALLBACK, ESTIMATED, or CALCULATING based on routing outcome.
     * @param bitcoinPriceService Optional service for USD-to-sats conversion.
     */
    fun quoteDriver(
        pickupLat: Double, pickupLon: Double,
        driverLat: Double, driverLon: Double,
        rideMiles: Double,
        config: AdminConfig,
        pickupDistanceMiles: Double? = null,
        fareState: FareState = FareState.FALLBACK,
        bitcoinPriceService: BitcoinPriceService? = null
    ): RoadflareDriverQuote {
        val haversineMiles = Location(pickupLat, pickupLon)
            .distanceToKm(Location(driverLat, driverLon)) * FareCalculator.KM_TO_MILES
        val pickupMiles = pickupDistanceMiles ?: haversineMiles
        val totalMiles = pickupMiles + rideMiles

        val roadflareFareUsd = FareCalculator.calculateFareUsd(
            totalDistanceMiles = totalMiles,
            ratePerMile = config.roadflareFareRateUsdPerMile,
            minimumFareUsd = config.roadflareMinimumFareUsd
            // No baseFareUsd — RoadFlare has no base fare
        )
        val normalFareUsd = FareCalculator.calculateFareUsd(
            totalDistanceMiles = rideMiles,
            ratePerMile = config.fareRateUsdPerMile,
            minimumFareUsd = config.minimumFareUsd
        )
        val isTooFar = FareCalculator.isTooFar(roadflareFareUsd, normalFareUsd)
        val fareSats = bitcoinPriceService?.usdToSats(roadflareFareUsd)

        return RoadflareDriverQuote(
            fareUsd = roadflareFareUsd,
            fareSats = fareSats,
            pickupMiles = pickupMiles,
            rideMiles = rideMiles,
            totalMiles = totalMiles,
            normalRideFareUsd = normalFareUsd,
            isTooFar = isTooFar,
            fareState = fareState
        )
    }

    /**
     * Ride-only reference fare (no pickup distance).
     * Used for summary display where per-driver fares are shown separately.
     */
    fun rideReferenceFareUsd(rideMiles: Double, config: AdminConfig): Double =
        FareCalculator.calculateFareUsd(
            totalDistanceMiles = rideMiles,
            ratePerMile = config.roadflareFareRateUsdPerMile,
            minimumFareUsd = config.roadflareMinimumFareUsd
        )
}
