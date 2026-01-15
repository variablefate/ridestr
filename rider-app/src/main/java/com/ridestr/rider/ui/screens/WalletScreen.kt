package com.ridestr.rider.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ridestr.common.data.RideHistoryRepository
import com.ridestr.common.nostr.events.RideHistoryStats
import com.ridestr.common.bitcoin.BitcoinPriceService
import com.ridestr.common.settings.DisplayCurrency
import com.ridestr.common.settings.SettingsManager
import java.util.*

/**
 * Rider wallet screen showing payment methods and spending summary.
 * Click spending card to view full ride history.
 */
@Composable
fun WalletScreen(
    rideHistoryRepository: RideHistoryRepository,
    settingsManager: SettingsManager,
    priceService: BitcoinPriceService,
    onViewHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val stats by rideHistoryRepository.stats.collectAsState()
    val rides by rideHistoryRepository.rides.collectAsState()
    val displayCurrency by settingsManager.displayCurrency.collectAsState()
    val btcPriceUsd by priceService.btcPriceUsd.collectAsState()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Payment Methods Section
        item {
            PaymentMethodsCard()
        }

        // Spending History Section - clickable to view full history
        item {
            SpendingHistoryCard(
                stats = stats,
                rides = rides,
                displayCurrency = displayCurrency,
                btcPriceUsd = btcPriceUsd,
                settingsManager = settingsManager,
                onClick = onViewHistory
            )
        }
    }
}

@Composable
private fun PaymentMethodsCard() {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CreditCard,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Payment Methods",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.ElectricBolt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Lightning Network",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Pay for rides instantly with Bitcoin over Lightning",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun SpendingHistoryCard(
    stats: RideHistoryStats,
    rides: List<com.ridestr.common.nostr.events.RideHistoryEntry>,
    displayCurrency: DisplayCurrency,
    btcPriceUsd: Int?,
    settingsManager: SettingsManager,
    onClick: () -> Unit
) {
    // Calculate this month's spending
    val calendar = Calendar.getInstance()
    val currentMonth = calendar.get(Calendar.MONTH)
    val currentYear = calendar.get(Calendar.YEAR)

    val thisMonthSpent = rides
        .filter { ride ->
            val rideCalendar = Calendar.getInstance().apply {
                timeInMillis = ride.timestamp * 1000
            }
            rideCalendar.get(Calendar.MONTH) == currentMonth &&
            rideCalendar.get(Calendar.YEAR) == currentYear &&
            ride.status == "completed"
        }
        .sumOf { it.fareSats }

    // Format spending values based on display currency
    val totalSpentDisplay = formatSats(stats.totalFareSatsPaid, displayCurrency, btcPriceUsd)
    val thisMonthDisplay = formatSats(thisMonthSpent, displayCurrency, btcPriceUsd)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Receipt,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Spending",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = "View history",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Stats summary
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                SpendingStat(
                    label = "This Month",
                    value = thisMonthDisplay,
                    icon = Icons.Default.DateRange,
                    onToggleCurrency = { settingsManager.toggleDisplayCurrency() }
                )
                SpendingStat(
                    label = "Total Rides",
                    value = "${stats.completedRides}",
                    icon = Icons.Default.DirectionsCar,
                    onToggleCurrency = null
                )
                SpendingStat(
                    label = "All Time",
                    value = totalSpentDisplay,
                    icon = Icons.Default.Savings,
                    onToggleCurrency = { settingsManager.toggleDisplayCurrency() }
                )
            }

            if (stats.completedRides > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Tap to view full ride history",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No rides yet!\nBook your first ride to see spending history.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SpendingStat(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onToggleCurrency: (() -> Unit)? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = if (onToggleCurrency != null) {
                Modifier.clickable { onToggleCurrency() }
            } else {
                Modifier
            }
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun formatSats(sats: Long, displayCurrency: DisplayCurrency, btcPriceUsd: Int?): String {
    return when (displayCurrency) {
        DisplayCurrency.SATS -> "${sats} sats"
        DisplayCurrency.USD -> {
            val usd = btcPriceUsd?.let { sats.toDouble() * it / 100_000_000.0 }
            usd?.let { String.format("$%.2f", it) } ?: "${sats} sats"
        }
    }
}
