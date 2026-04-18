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

/**
 * Placeholder shown in SATS-display mode when no BTC price is available (cold start,
 * network blip, relay outage). Visually distinct from any `$X.XX` formatting so the
 * user can see their toggle choice was applied and understand conversion is pending
 * rather than mistake the output for a dollar amount.
 */
const val SATS_PLACEHOLDER = "— sats"

/**
 * Format a sats value with a thousands separator (`12,345 sats`), or [SATS_PLACEHOLDER]
 * when [sats] is null. Use when the caller is in SATS display mode and the sats value
 * is derived from a USD amount via [com.ridestr.common.bitcoin.BitcoinPriceService.usdToSats] —
 * a null result there means the price service hasn't populated a price yet, and callers
 * MUST render the placeholder instead of silently falling back to USD formatting (which
 * looks identical to USD mode and breaks the currency-toggle UX, see Issue #72).
 */
fun formatSatsOrPlaceholder(sats: Long?): String =
    if (sats != null) String.format(Locale.US, "%,d sats", sats)
    else SATS_PLACEHOLDER

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
