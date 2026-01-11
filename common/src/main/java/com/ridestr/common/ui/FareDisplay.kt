package com.ridestr.common.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.ridestr.common.bitcoin.BitcoinPriceService
import com.ridestr.common.settings.DisplayCurrency
import com.ridestr.common.settings.SettingsManager

/**
 * Clickable fare display that shows amount in sats or USD.
 * Tapping toggles between currencies.
 *
 * @param satsAmount The fare amount in satoshis
 * @param settingsManager Settings manager for currency preference
 * @param priceService Bitcoin price service for USD conversion
 * @param style Text style to use
 * @param fontWeight Font weight (default Bold)
 * @param color Text color (default primary)
 * @param prefix Optional prefix text (e.g., "Fare: ")
 * @param modifier Modifier for the text
 */
@Composable
fun FareDisplay(
    satsAmount: Double,
    settingsManager: SettingsManager,
    priceService: BitcoinPriceService,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    fontWeight: FontWeight = FontWeight.Bold,
    color: Color = MaterialTheme.colorScheme.primary,
    prefix: String = "",
    modifier: Modifier = Modifier
) {
    val displayCurrency by settingsManager.displayCurrency.collectAsState()
    // Observe btcPrice to trigger recomposition when price updates
    val btcPrice by priceService.btcPriceUsd.collectAsState()

    val displayText = when (displayCurrency) {
        DisplayCurrency.SATS -> "$prefix${satsAmount.toInt()} sats"
        DisplayCurrency.USD -> {
            val usdString = priceService.satsToUsdString(satsAmount.toLong())
            if (usdString != null) {
                "$prefix$usdString"
            } else {
                "$prefix${satsAmount.toInt()} sats" // Fallback if price unavailable
            }
        }
    }

    Text(
        text = displayText,
        style = style,
        fontWeight = fontWeight,
        color = color,
        modifier = modifier.clickable {
            settingsManager.toggleDisplayCurrency()
        }
    )
}

/**
 * Format fare amount as a string based on current currency setting.
 * Use this for embedding fare text in buttons or other non-clickable contexts.
 *
 * @param satsAmount The fare amount in satoshis
 * @param settingsManager Settings manager for currency preference
 * @param priceService Bitcoin price service for USD conversion
 * @return Formatted fare string (e.g., "500 sats" or "$4.53")
 */
@Composable
fun formatFare(
    satsAmount: Double,
    settingsManager: SettingsManager,
    priceService: BitcoinPriceService
): String {
    val displayCurrency by settingsManager.displayCurrency.collectAsState()
    // Observe btcPrice to trigger recomposition when price updates
    val btcPrice by priceService.btcPriceUsd.collectAsState()

    return when (displayCurrency) {
        DisplayCurrency.SATS -> "${satsAmount.toInt()} sats"
        DisplayCurrency.USD -> {
            priceService.satsToUsdString(satsAmount.toLong())
                ?: "${satsAmount.toInt()} sats" // Fallback if price unavailable
        }
    }
}
