package com.ridestr.common.fiat

import com.ridestr.common.nostr.events.FiatFare
import com.ridestr.common.nostr.events.RideHistoryEntry
import com.ridestr.common.settings.DisplayCurrency

fun FiatFare.isUsd(): Boolean = currency.equals("USD", ignoreCase = true)

fun FiatFare.formatDisplayAmount(): String =
    if (isUsd()) {
        "$$amount"
    } else {
        "${currency.uppercase()} $amount"
    }

fun FiatFare.usdAmountOrNull(): Double? = if (isUsd()) amount.toDoubleOrNull() else null

fun RideHistoryEntry.usdFareAmountOrNull(btcPriceUsd: Int?): Double? =
    fiatFare?.usdAmountOrNull()
        ?: btcPriceUsd?.takeIf { it > 0 }?.let { fareSats.toDouble() * it / 100_000_000.0 }

fun RideHistoryEntry.formatFareDisplay(
    displayCurrency: DisplayCurrency,
    btcPriceUsd: Int?,
    prefix: String = ""
): String = when (displayCurrency) {
    DisplayCurrency.SATS -> "$prefix${fareSats} sats"
    DisplayCurrency.USD -> {
        val usdAmount = usdFareAmountOrNull(btcPriceUsd)
        usdAmount?.let { String.format("%s$%.2f", prefix, it) } ?: "$prefix${fareSats} sats"
    }
}

fun Iterable<RideHistoryEntry>.sumFareUsdOrNull(btcPriceUsd: Int?): Double? {
    var total = 0.0
    for (ride in this) {
        total += ride.usdFareAmountOrNull(btcPriceUsd) ?: return null
    }
    return total
}
