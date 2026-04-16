package com.ridestr.common.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.ridestr.common.bitcoin.BitcoinPriceService
import com.ridestr.common.nostr.events.FiatFare
import com.ridestr.common.settings.DisplayCurrency

/**
 * Clickable fare display that shows amount in sats or USD.
 * Tapping toggles between currencies.
 *
 * When [fiatFare] is provided and matches the displayed currency (USD),
 * the authoritative fiat amount is shown directly — no BTC price conversion.
 * This prevents drift between rider-quoted and driver-displayed fares.
 *
 * @param satsAmount The fare amount in satoshis
 * @param displayCurrency Current display currency preference
 * @param onToggleCurrency Callback to toggle between sats/USD
 * @param priceService Bitcoin price service for USD conversion (fallback only)
 * @param fiatFare Optional authoritative fiat amount from offer event (per ADR-0008)
 * @param style Text style to use
 * @param fontWeight Font weight (default Bold)
 * @param color Text color (default primary)
 * @param prefix Optional prefix text (e.g., "Fare: ")
 * @param modifier Modifier for the text
 */
@Composable
fun FareDisplay(
    satsAmount: Double,
    displayCurrency: DisplayCurrency,
    onToggleCurrency: () -> Unit,
    priceService: BitcoinPriceService,
    fiatFare: FiatFare? = null,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    fontWeight: FontWeight = FontWeight.Bold,
    color: Color = MaterialTheme.colorScheme.primary,
    prefix: String = "",
    modifier: Modifier = Modifier
) {
    // Observe btcPrice to trigger recomposition when price updates
    val btcPrice by priceService.btcPriceUsd.collectAsState()

    val displayText = when (displayCurrency) {
        DisplayCurrency.SATS -> "$prefix${satsAmount.toInt()} sats"
        DisplayCurrency.USD -> {
            // Prefer authoritative fiat amount when available (USD only for now)
            if (fiatFare != null && fiatFare.currency == "USD") {
                "$prefix\$${fiatFare.amount}"
            } else {
                val usdString = priceService.satsToUsdString(satsAmount.toLong())
                if (usdString != null) {
                    "$prefix$usdString"
                } else {
                    "$prefix${satsAmount.toInt()} sats" // Fallback if price unavailable
                }
            }
        }
    }

    Text(
        text = displayText,
        style = style,
        fontWeight = fontWeight,
        color = color,
        modifier = modifier.clickable(role = Role.Button, onClickLabel = "Toggle currency") {
            onToggleCurrency()
        }
    )
}

/**
 * Format fare amount as a string based on current currency setting.
 * Use this for embedding fare text in buttons or other non-clickable contexts.
 *
 * When [fiatFare] is provided and matches the displayed currency (USD),
 * the authoritative fiat amount is returned directly.
 *
 * @param satsAmount The fare amount in satoshis
 * @param displayCurrency Current display currency preference
 * @param priceService Bitcoin price service for USD conversion (fallback only)
 * @param fiatFare Optional authoritative fiat amount from offer event (per ADR-0008)
 * @return Formatted fare string (e.g., "500 sats" or "$4.53")
 */
@Composable
fun formatFare(
    satsAmount: Double,
    displayCurrency: DisplayCurrency,
    priceService: BitcoinPriceService,
    fiatFare: FiatFare? = null
): String {
    // Observe btcPrice to trigger recomposition when price updates
    val btcPrice by priceService.btcPriceUsd.collectAsState()

    return when (displayCurrency) {
        DisplayCurrency.SATS -> "${satsAmount.toInt()} sats"
        DisplayCurrency.USD -> {
            // Prefer authoritative fiat amount when available (USD only for now)
            if (fiatFare != null && fiatFare.currency == "USD") {
                "\$${fiatFare.amount}"
            } else {
                priceService.satsToUsdString(satsAmount.toLong())
                    ?: "${satsAmount.toInt()} sats" // Fallback if price unavailable
            }
        }
    }
}
