package com.ridestr.common.fiat

import com.ridestr.common.nostr.events.FiatFare
import com.ridestr.common.nostr.events.RideHistoryEntry
import com.ridestr.common.settings.DisplayCurrency
import java.util.Locale

fun FiatFare.isUsd(): Boolean = currency.equals("USD", ignoreCase = true)

fun FiatFare.formatDisplayAmount(): String =
    if (isUsd()) {
        "$$amount"
    } else {
        "${currency.uppercase()} $amount"
    }

fun FiatFare.usdAmountOrNull(): Double? = if (isUsd()) amount.toDoubleOrNull() else null

/**
 * Format a USD amount as `$X.XX` with an optional prefix, using Locale.US so the
 * decimal separator stays a dot on every device locale (French/German `%.2f` would
 * otherwise emit `$12,50`).
 */
fun Double.formatUsd(prefix: String = ""): String =
    String.format(Locale.US, "%s$%.2f", prefix, this)

fun RideHistoryEntry.usdFareAmountOrNull(btcPriceUsd: Int?): Double? =
    fiatFare?.usdAmountOrNull()
        ?: btcPriceUsd?.takeIf { it > 0 }?.let { fareSats.toDouble() * it / 100_000_000.0 }

fun RideHistoryEntry.formatFareDisplay(
    displayCurrency: DisplayCurrency,
    btcPriceUsd: Int?,
    prefix: String = ""
): String = when (displayCurrency) {
    DisplayCurrency.SATS -> "$prefix${fareSats} sats"
    DisplayCurrency.USD -> usdFareAmountOrNull(btcPriceUsd)?.formatUsd(prefix)
        ?: "$prefix${fareSats} sats"
}

fun Iterable<RideHistoryEntry>.sumFareUsdOrNull(btcPriceUsd: Int?): Double? {
    var total = 0.0
    for (ride in this) {
        total += ride.usdFareAmountOrNull(btcPriceUsd) ?: return null
    }
    return total
}
