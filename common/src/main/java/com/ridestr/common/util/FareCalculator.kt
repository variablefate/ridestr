package com.ridestr.common.util

object FareCalculator {
    const val KM_TO_MILES = 0.621371

    /** Max USD surcharge a RoadFlare fare may exceed normal fare before "too far" exclusion. */
    const val ROADFLARE_MAX_SURCHARGE_USD = 15.0

    /**
     * Calculate fare in USD from total distance.
     * Both normal and RoadFlare paths compose this with their own inputs.
     */
    fun calculateFareUsd(
        totalDistanceMiles: Double,
        ratePerMile: Double,
        minimumFareUsd: Double,
        baseFareUsd: Double = 0.0
    ): Double = maxOf(baseFareUsd + totalDistanceMiles * ratePerMile, minimumFareUsd)

    /**
     * Check if a RoadFlare fare exceeds the max allowed surcharge over normal fare.
     * Single source of truth for the "too far" decision.
     */
    fun isTooFar(
        roadflareFareUsd: Double,
        normalFareUsd: Double,
        maxSurchargeUsd: Double = ROADFLARE_MAX_SURCHARGE_USD
    ): Boolean = roadflareFareUsd > normalFareUsd + maxSurchargeUsd
}
