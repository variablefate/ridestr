package com.ridestr.rider.viewmodels

import java.util.Locale

internal data class AuthoritativeFareQuote(
    val sats: Double,
    val usdAmount: String
)

/**
 * Build a fare quote where USD remains authoritative for fiat/manual rails,
 * even when sats falls back heuristically because BTC price lookup failed.
 */
internal fun authoritativeFareQuote(
    fareUsd: Double,
    sats: Long?,
    fallbackSats: Double
): AuthoritativeFareQuote = AuthoritativeFareQuote(
    sats = sats?.toDouble() ?: fallbackSats,
    usdAmount = String.format(Locale.US, "%.2f", fareUsd)
)
