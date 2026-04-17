package com.roadflare.rider.ui.screens.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ridestr.common.bitcoin.BitcoinPriceService
import com.ridestr.common.settings.DisplayCurrency

/** Pure-presentation cancellation screen — no ride state bindings. */
@Composable
fun CancelledContent(
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Ride Cancelled",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onDone) {
            Text("Done")
        }
    }
}

/** Format a USD fare amount respecting the display currency preference. */
@Composable
fun formatFareAmount(
    fareUsd: Double,
    displayCurrency: DisplayCurrency,
    priceService: BitcoinPriceService
): String {
    @Suppress("UNUSED_VARIABLE")
    val btcPrice by priceService.btcPriceUsd.collectAsState()
    return when (displayCurrency) {
        DisplayCurrency.USD -> "$${String.format("%.2f", fareUsd)}"
        DisplayCurrency.SATS -> {
            val sats = priceService.usdToSats(fareUsd)
            if (sats != null) "${String.format("%,d", sats)} sats"
            else "$${String.format("%.2f", fareUsd)}"
        }
    }
}
