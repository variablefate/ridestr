package com.roadflare.rider.ui.screens

import android.util.Log
import com.roadflare.rider.BuildConfig
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.ridestr.common.data.FollowedDriversRepository
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.FollowedDriver
import com.ridestr.common.nostr.events.Location
import com.roadflare.rider.ui.screens.components.DriverLocationState
import com.roadflare.rider.ui.screens.components.EmptyDriversState
import com.roadflare.rider.ui.screens.components.FollowedDriverList
import com.roadflare.rider.ui.screens.components.RoadflarePaymentMethodsDialog

private const val TAG = "DriverNetworkTab"

/**
 * RoadFlare tab showing rider's favorite drivers list.
 *
 * Features:
 * - List of followed drivers with status indicators
 * - Add driver button (opens QR scanner or manual entry)
 * - Send RoadFlare broadcast to all/selected drivers
 * - Real-time location display when driver is online
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriverNetworkTab(
    followedDriversRepository: FollowedDriversRepository,
    nostrService: NostrService?,
    roadflarePaymentMethods: List<String> = emptyList(),
    onSetRoadflarePaymentMethods: (List<String>) -> Unit = {},
    riderLocation: Location? = null,
    onAddDriver: () -> Unit = {},
    onDriverClick: (FollowedDriver) -> Unit = {},
    onDriverRemoved: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val drivers by followedDriversRepository.drivers.collectAsState()
    val driverNames by followedDriversRepository.driverNames.collectAsState()
    val cachedLocations by followedDriversRepository.driverLocations.collectAsState()

    var showPaymentMethodsDialog by remember { mutableStateOf(false) }

    LaunchedEffect(drivers) {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "=== RIDER ROADFLARE STATE ===")
            Log.d(TAG, "Total followed drivers: ${drivers.size}")
            for (driver in drivers) {
                val hasKey = driver.roadflareKey != null
                Log.d(TAG, "  driver: ${driver.pubkey.take(8)} hasKey=$hasKey keyVersion=${driver.roadflareKey?.version} keyUpdatedAt=${driver.roadflareKey?.keyUpdatedAt}")
                if (hasKey) {
                    Log.d(TAG, "    roadflareKey.publicKey: ${driver.roadflareKey?.publicKey?.take(16)}...")
                    Log.d(TAG, "    roadflareKey.privateKey exists: ${driver.roadflareKey?.privateKey?.isNotEmpty() == true}")
                }
            }
            Log.d(TAG, "=============================")
        }
    }

    var showRemoveDialog by remember { mutableStateOf<FollowedDriver?>(null) }

    val staleKeyDrivers = remember { mutableStateMapOf<String, Boolean>() }
    val keyRefreshRequests = remember { mutableStateMapOf<String, Long>() }

    var isRefreshing by remember { mutableStateOf(false) }

    fun checkStaleKeys() {
        if (nostrService == null) return
        scope.launch {
            drivers.forEach { driver ->
                val storedKey = driver.roadflareKey
                val storedKeyUpdatedAt = storedKey?.keyUpdatedAt ?: 0L

                if (storedKey == null) {
                    val lastRefreshRequest = keyRefreshRequests[driver.pubkey] ?: 0L
                    val now = System.currentTimeMillis()
                    val oneHourMs = 3600_000L
                    if (now - lastRefreshRequest > oneHourMs) {
                        keyRefreshRequests[driver.pubkey] = now
                        val eventId = nostrService.publishRoadflareKeyAck(
                            driverPubKey = driver.pubkey,
                            keyVersion = 0,
                            keyUpdatedAt = 0L,
                            status = "stale"
                        )
                        if (eventId != null) {
                            Log.d(TAG, "Requested key refresh (no key) for ${driver.pubkey.take(8)}: eventId=${eventId.take(8)}")
                        } else {
                            Log.w(TAG, "Failed to request key refresh (no key) for ${driver.pubkey.take(8)}")
                        }
                    } else {
                        Log.d(TAG, "Skipping key refresh (no key) for ${driver.pubkey.take(8)} - rate limited (last request ${(now - lastRefreshRequest) / 1000}s ago)")
                    }
                    staleKeyDrivers[driver.pubkey] = false
                    return@forEach
                }

                if (storedKeyUpdatedAt > 0) {
                    val currentKeyUpdatedAt = nostrService.fetchDriverKeyUpdatedAt(driver.pubkey)
                    if (currentKeyUpdatedAt != null && currentKeyUpdatedAt > storedKeyUpdatedAt) {
                        Log.d(TAG, "Stale key detected for driver ${driver.pubkey.take(8)}: stored=$storedKeyUpdatedAt, current=$currentKeyUpdatedAt")
                        staleKeyDrivers[driver.pubkey] = true

                        val lastRefreshRequest = keyRefreshRequests[driver.pubkey] ?: 0L
                        val now = System.currentTimeMillis()
                        val oneHourMs = 3600_000L
                        if (now - lastRefreshRequest > oneHourMs) {
                            keyRefreshRequests[driver.pubkey] = now
                            val eventId = nostrService.publishRoadflareKeyAck(
                                driverPubKey = driver.pubkey,
                                keyVersion = driver.roadflareKey?.version ?: 0,
                                keyUpdatedAt = storedKeyUpdatedAt,
                                status = "stale"
                            )
                            if (eventId != null) {
                                Log.d(TAG, "Requested key refresh for ${driver.pubkey.take(8)}: eventId=${eventId.take(8)}")
                            } else {
                                Log.w(TAG, "Failed to request key refresh for ${driver.pubkey.take(8)}")
                            }
                        } else {
                            Log.d(TAG, "Skipping key refresh request for ${driver.pubkey.take(8)} - rate limited (last request ${(now - lastRefreshRequest) / 1000}s ago)")
                        }
                    } else {
                        staleKeyDrivers[driver.pubkey] = false
                    }
                }
            }
        }
    }

    LaunchedEffect(drivers) {
        if (nostrService != null && drivers.isNotEmpty()) {
            delay(3000)
            checkStaleKeys()
        }
    }

    fun onRefresh() {
        scope.launch {
            isRefreshing = true
            followedDriversRepository.clearDriverLocations()
            checkStaleKeys()
            delay(500)
            isRefreshing = false
        }
    }

    showRemoveDialog?.let { driver ->
        val dialogDriverName = driverNames[driver.pubkey] ?: driver.pubkey.take(8) + "..."
        AlertDialog(
            onDismissRequest = { showRemoveDialog = null },
            icon = { Icon(Icons.Default.PersonRemove, contentDescription = null) },
            title = { Text("Remove Driver?") },
            text = {
                Text("Remove $dialogDriverName from your favorites? You can add them back later.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        followedDriversRepository.removeDriver(driver.pubkey)
                        showRemoveDialog = null
                        onDriverRemoved()
                    }
                ) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showPaymentMethodsDialog) {
        RoadflarePaymentMethodsDialog(
            currentMethods = roadflarePaymentMethods,
            onSave = onSetRoadflarePaymentMethods,
            onDismiss = { showPaymentMethodsDialog = false }
        )
    }

    // Convert cached locations to DriverLocationState for presentation
    val locationStates = remember(cachedLocations) {
        cachedLocations.mapValues { (pubkey, cached) ->
            DriverLocationState(
                pubkey = pubkey,
                lat = cached.lat,
                lon = cached.lon,
                status = cached.status,
                timestamp = cached.timestamp,
                keyVersion = cached.keyVersion
            )
        }
    }

    Scaffold(
        floatingActionButton = {
            if (drivers.isNotEmpty()) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SmallFloatingActionButton(
                        onClick = { showPaymentMethodsDialog = true }
                    ) {
                        Icon(Icons.Default.Payment, contentDescription = "Payment Methods")
                    }
                    FloatingActionButton(onClick = onAddDriver) {
                        Icon(Icons.Default.PersonAdd, contentDescription = "Add Driver")
                    }
                }
            }
        },
        modifier = modifier
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { onRefresh() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (drivers.isEmpty()) {
                EmptyDriversState(
                    onAddDriver = onAddDriver,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                FollowedDriverList(
                    drivers = drivers,
                    driverNames = driverNames,
                    locationStates = locationStates,
                    riderLocation = riderLocation,
                    staleKeyDrivers = staleKeyDrivers,
                    onDriverClick = onDriverClick,
                    onRemove = { showRemoveDialog = it }
                )
            }
        }
    }
}
