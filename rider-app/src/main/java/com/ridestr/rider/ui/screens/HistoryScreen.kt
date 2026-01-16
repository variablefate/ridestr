package com.ridestr.rider.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ridestr.common.data.RideHistoryRepository
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.RideHistoryEntry
import com.ridestr.common.nostr.events.RideHistoryStats
import com.ridestr.common.bitcoin.BitcoinPriceService
import com.ridestr.common.settings.DisplayCurrency
import com.ridestr.common.settings.SettingsManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

/**
 * Ride history screen showing past rides and statistics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    rideHistoryRepository: RideHistoryRepository,
    settingsManager: SettingsManager,
    nostrService: NostrService,
    priceService: BitcoinPriceService,
    onRideClick: (RideHistoryEntry) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val rides by rideHistoryRepository.rides.collectAsState()
    val stats by rideHistoryRepository.stats.collectAsState()
    val isLoading by rideHistoryRepository.isLoading.collectAsState()
    val displayCurrency by settingsManager.displayCurrency.collectAsState()
    val btcPriceUsd by priceService.btcPriceUsd.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    var showClearDialog by remember { mutableStateOf(false) }
    var rideToDelete by remember { mutableStateOf<RideHistoryEntry?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }

    // Sync from Nostr on first load
    LaunchedEffect(Unit) {
        rideHistoryRepository.syncFromNostr(nostrService)
    }

    // Clear all history confirmation dialog
    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null) },
            title = { Text("Clear Ride History?") },
            text = {
                Text("This will permanently delete all your ride history from this device and Nostr relays. This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            rideHistoryRepository.clearAllHistoryAndDeleteFromNostr(nostrService)
                        }
                        showClearDialog = false
                    }
                ) {
                    Text("Clear All", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete single ride confirmation dialog
    rideToDelete?.let { ride ->
        AlertDialog(
            onDismissRequest = { rideToDelete = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Delete This Ride?") },
            text = {
                Text("This ride will be removed from your history.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        rideHistoryRepository.deleteRide(ride.rideId)
                        // Sync deletion to Nostr (Nostr is source of truth)
                        coroutineScope.launch {
                            rideHistoryRepository.backupToNostr(nostrService)
                        }
                        rideToDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { rideToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    PullToRefreshBox(
        isRefreshing = isRefreshing || isLoading,
        onRefresh = {
            isRefreshing = true
            coroutineScope.launch {
                rideHistoryRepository.syncFromNostr(nostrService)
                isRefreshing = false
            }
        },
        modifier = modifier.fillMaxSize()
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Stats Summary Card
            item {
                StatsCard(
                    stats = stats,
                    displayCurrency = displayCurrency,
                    btcPriceUsd = btcPriceUsd,
                    settingsManager = settingsManager
                )
            }

            // Header with Clear All option
            if (rides.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recent Rides",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        TextButton(
                            onClick = { showClearDialog = true }
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Clear All")
                        }
                    }
                }
            }

            // Ride List
            if (rides.isEmpty()) {
                item {
                    EmptyHistoryCard()
                }
            } else {
                items(rides, key = { it.rideId }) { ride ->
                    RideHistoryCard(
                        ride = ride,
                        displayCurrency = displayCurrency,
                        btcPriceUsd = btcPriceUsd,
                        settingsManager = settingsManager,
                        onClick = { onRideClick(ride) },
                        onDelete = { rideToDelete = ride }
                    )
                }
            }

        }
    }
}

@Composable
private fun StatsCard(
    stats: RideHistoryStats,
    displayCurrency: DisplayCurrency,
    btcPriceUsd: Int?,
    settingsManager: SettingsManager
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
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
                    imageVector = Icons.Default.Analytics,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Your Stats",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(
                    label = "Rides",
                    value = "${stats.completedRides}",
                    icon = Icons.Default.DirectionsCar
                )
                StatItem(
                    label = "Distance",
                    value = String.format("%.1f mi", stats.totalDistanceMiles),
                    icon = Icons.Default.Route
                )
                StatItem(
                    label = "Time",
                    value = formatDuration(stats.totalDurationMinutes),
                    icon = Icons.Default.Timer
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Total spent
            val totalSpentDisplay = when (displayCurrency) {
                DisplayCurrency.SATS -> "${stats.totalFareSatsPaid} sats"
                DisplayCurrency.USD -> {
                    val usd = btcPriceUsd?.let { stats.totalFareSatsPaid.toDouble() * it / 100_000_000.0 }
                    usd?.let { String.format("$%.2f", it) } ?: "${stats.totalFareSatsPaid} sats"
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Total Spent",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = totalSpentDisplay,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.clickable { settingsManager.toggleDisplayCurrency() }
                    )
                }
            }

            // Show cancelled rides if any
            if (stats.cancelledRides > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "${stats.cancelledRides} cancelled ride${if (stats.cancelledRides > 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun StatItem(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun RideHistoryCard(
    ride: RideHistoryEntry,
    displayCurrency: DisplayCurrency,
    btcPriceUsd: Int?,
    settingsManager: SettingsManager,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault()) }
    val formattedDate = remember(ride.timestamp) {
        dateFormat.format(Date(ride.timestamp * 1000))
    }

    val fareDisplay = when (displayCurrency) {
        DisplayCurrency.SATS -> "${ride.fareSats} sats"
        DisplayCurrency.USD -> {
            val usd = btcPriceUsd?.let { ride.fareSats.toDouble() * it / 100_000_000.0 }
            usd?.let { String.format("$%.2f", it) } ?: "${ride.fareSats} sats"
        }
    }

    val isCompleted = ride.status == "completed"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isCompleted)
                MaterialTheme.colorScheme.surface
            else
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        )
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
                        imageVector = if (isCompleted) Icons.Default.CheckCircle else Icons.Default.Cancel,
                        contentDescription = null,
                        tint = if (isCompleted)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isCompleted) "Completed" else "Cancelled",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isCompleted)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.error
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete ride",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Date
            Text(
                text = formattedDate,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Pickup location - show address if available, else coords, else geohash
            val pickupDisplay = ride.pickupAddress
                ?: ride.pickupLat?.let { lat ->
                    ride.pickupLon?.let { lon ->
                        String.format("%.4f, %.4f", lat, lon)
                    }
                }
                ?: "${ride.pickupGeohash.take(4)}..."

            val dropoffDisplay = ride.dropoffAddress
                ?: ride.dropoffLat?.let { lat ->
                    ride.dropoffLon?.let { lon ->
                        String.format("%.4f, %.4f", lat, lon)
                    }
                }
                ?: "${ride.dropoffGeohash.take(4)}..."

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.TripOrigin,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "From: $pickupDisplay",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "To: $dropoffDisplay",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Ride details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = String.format("%.1f miles", ride.distanceMiles),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (ride.durationMinutes > 0) {
                        Text(
                            text = "${ride.durationMinutes} min",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (isCompleted && ride.fareSats > 0) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            text = fareDisplay,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .clickable { settingsManager.toggleDisplayCurrency() }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHistoryCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "No Rides Yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Your completed rides will appear here.\nStart by booking your first ride!",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun formatDuration(minutes: Int): String {
    return when {
        minutes < 60 -> "${minutes}m"
        minutes < 1440 -> "${minutes / 60}h ${minutes % 60}m"
        else -> "${minutes / 1440}d ${(minutes % 1440) / 60}h"
    }
}
