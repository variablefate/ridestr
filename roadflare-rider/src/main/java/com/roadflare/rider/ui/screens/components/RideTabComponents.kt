package com.roadflare.rider.ui.screens.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ridestr.common.bitcoin.BitcoinPriceService
import com.ridestr.common.fiat.formatUsd
import com.ridestr.common.settings.DisplayCurrency
import java.util.Locale

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

@Composable
fun formatFareAmount(
    fareUsd: Double,
    displayCurrency: DisplayCurrency,
    priceService: BitcoinPriceService
): String {
    val btcPrice by priceService.btcPriceUsd.collectAsState()
    return when (displayCurrency) {
        DisplayCurrency.USD -> fareUsd.formatUsd()
        DisplayCurrency.SATS -> {
            val price = btcPrice?.takeIf { it > 0 }
            if (price != null) {
                val sats = (fareUsd * 100_000_000.0 / price).toLong()
                String.format(Locale.US, "%,d sats", sats)
            } else fareUsd.formatUsd()
        }
    }
}
