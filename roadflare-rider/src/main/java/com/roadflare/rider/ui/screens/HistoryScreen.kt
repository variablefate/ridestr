package com.roadflare.rider.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.ridestr.common.data.RideHistoryRepository
import com.ridestr.common.nostr.events.RideHistoryEntry
import com.ridestr.common.nostr.events.RideHistoryStats
import com.ridestr.common.settings.DisplayCurrency
import com.roadflare.rider.ui.screens.components.HistoryList
import kotlinx.coroutines.launch

/**
 * Ride history screen showing past rides and statistics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    rideHistoryRepository: RideHistoryRepository,
    displayCurrency: DisplayCurrency,
    onToggleCurrency: () -> Unit,
    nostrService: com.ridestr.common.nostr.NostrService? = null,
    onRideClick: (RideHistoryEntry) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val rides by rideHistoryRepository.rides.collectAsState()

    val stats = remember(rides) {
        RideHistoryStats(
            totalRidesAsRider = rides.count { it.role == "rider" },
            totalRidesAsDriver = rides.count { it.role == "driver" },
            totalDistanceMiles = rides.sumOf { it.distanceMiles },
            totalDurationMinutes = rides.sumOf { it.durationMinutes },
            totalFareSatsEarned = rides.filter { it.role == "driver" && it.status == "completed" }
                .sumOf { it.fareSats },
            totalFareSatsPaid = rides.filter { it.role == "rider" && it.status == "completed" }
                .sumOf { it.fareSats },
            completedRides = rides.count { it.status == "completed" },
            cancelledRides = rides.count { it.status == "cancelled" }
        )
    }
    val coroutineScope = rememberCoroutineScope()

    var showClearDialog by remember { mutableStateOf(false) }
    var rideToDelete by remember { mutableStateOf<RideHistoryEntry?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            icon = { Icon(Icons.Default.DeleteForever, contentDescription = null) },
            title = { Text("Clear Ride History?") },
            text = {
                Text("This will permanently delete all your ride history from this device. This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        rideHistoryRepository.clearAllHistory()
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

    rideToDelete?.let { ride ->
        AlertDialog(
            onDismissRequest = { rideToDelete = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Delete This Ride?") },
            text = { Text("This ride will be removed from your history.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        rideHistoryRepository.deleteRide(ride.rideId)
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
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            coroutineScope.launch {
                if (nostrService != null) {
                    rideHistoryRepository.syncFromNostr(nostrService)
                }
                isRefreshing = false
            }
        },
        modifier = modifier
    ) {
        HistoryList(
            stats = stats,
            rides = rides,
            displayCurrency = displayCurrency,
            onToggleCurrency = onToggleCurrency,
            onRideClick = onRideClick,
            onDeleteRide = { rideToDelete = it },
            onClearAll = { showClearDialog = true }
        )
    }
}
