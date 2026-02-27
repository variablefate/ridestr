package com.ridestr.rider.ui.screens

import android.content.Intent
import android.util.Log
import com.ridestr.rider.BuildConfig
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import com.ridestr.common.nostr.events.PaymentMethod
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ridestr.rider.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.ridestr.common.data.FollowedDriversRepository
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.FollowedDriver
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.events.RoadflareLocationData
import com.ridestr.common.nostr.events.RoadflareLocationEvent
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.*

private const val TAG = "RoadflareTab"

/**
 * Represents a driver's real-time location and status from RoadFlare broadcast.
 */
data class DriverLocationState(
    val pubkey: String,
    val lat: Double,
    val lon: Double,
    val status: String, // "online", "on_ride", "offline"
    val timestamp: Long,
    val keyVersion: Int
)

/**
 * Fare estimate result for RoadFlare rides.
 *
 * @param satoshis Fare amount in satoshis
 * @param distanceMiles Distance in miles (pickup to destination)
 * @param pickupDistanceMiles Distance from driver to pickup
 * @param isAccurate True if using exact driver location, false if using fallback
 */
data class RoadflareFareEstimate(
    val satoshis: Long,
    val distanceMiles: Double,
    val pickupDistanceMiles: Double?,
    val isAccurate: Boolean
)

/** RoadFlare fare defaults (used when admin RemoteConfig has no RoadFlare rate) */
private const val ROADFLARE_BASE_FARE_USD = 2.50
private const val ROADFLARE_MINIMUM_FARE_USD = 5.0

/**
 * Calculate fare estimate for a RoadFlare ride.
 * Uses USD-based calculation with live BTC price conversion (same as normal rides).
 *
 * @param riderLocation Rider's current location (pickup point)
 * @param destination Ride destination
 * @param driverLocation Driver's current location (if available)
 * @return Fare estimate with distance and accuracy flag
 */
fun calculateRoadflareFare(
    riderLocation: Location,
    destination: Location,
    driverLocation: DriverLocationState?,
    ratePerMile: Double = com.ridestr.common.nostr.events.AdminConfig.DEFAULT_ROADFLARE_FARE_RATE
): RoadflareFareEstimate {
    // Calculate ride distance (pickup to destination)
    val rideDistanceKm = riderLocation.distanceToKm(destination)
    val rideDistanceMiles = rideDistanceKm * 0.621371

    // Calculate pickup distance if driver location is available
    val pickupDistanceMiles = if (driverLocation != null &&
        driverLocation.status == RoadflareLocationEvent.Status.ONLINE) {
        val driverLoc = Location(driverLocation.lat, driverLocation.lon)
        val pickupKm = driverLoc.distanceToKm(riderLocation)
        pickupKm * 0.621371
    } else null

    // Calculate fare in USD first
    val totalMiles = rideDistanceMiles + (pickupDistanceMiles ?: 0.0)
    val fareUsd = maxOf(
        ROADFLARE_BASE_FARE_USD + (totalMiles * ratePerMile),
        ROADFLARE_MINIMUM_FARE_USD
    )

    // Convert USD to sats using live BTC price
    val priceService = com.ridestr.common.bitcoin.BitcoinPriceService.getInstance()
    val fareSats = priceService.usdToSats(fareUsd) ?: (fareUsd * 1000.0).toLong() // Fallback: ~$100k

    return RoadflareFareEstimate(
        satoshis = fareSats,
        distanceMiles = rideDistanceMiles,
        pickupDistanceMiles = pickupDistanceMiles,
        isAccurate = pickupDistanceMiles != null
    )
}

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
fun RoadflareTab(
    followedDriversRepository: FollowedDriversRepository,
    nostrService: NostrService?,
    settingsManager: com.ridestr.common.settings.SettingsManager? = null,
    riderLocation: Location? = null,
    onAddDriver: () -> Unit = {},
    onDriverClick: (FollowedDriver) -> Unit = {},
    onDriverRemoved: () -> Unit = {},  // Called after driver removed - triggers Nostr backup
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drivers by followedDriversRepository.drivers.collectAsState()
    val driverNames by followedDriversRepository.driverNames.collectAsState()
    val cachedLocations by followedDriversRepository.driverLocations.collectAsState()

    // Payment methods dialog state
    var showPaymentMethodsDialog by remember { mutableStateOf(false) }

    // Track last createdAt per driver to reject out-of-order events (Part C fix)
    val lastLocationCreatedAt = remember { mutableStateMapOf<String, Long>() }

    // DEBUG: Log rider's followed drivers state (debug builds only)
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

    // Fetch driver profiles to get their display names (first name only)
    LaunchedEffect(drivers, nostrService) {
        if (nostrService == null) return@LaunchedEffect

        // Fetch profile for each driver we don't have a name for yet
        drivers.forEach { driver ->
            if (!driverNames.containsKey(driver.pubkey)) {
                nostrService.subscribeToProfile(driver.pubkey) { profile ->
                    val fullName = profile.displayName ?: profile.name
                    // Extract first name only (like ride offers do)
                    val firstName = fullName?.split(" ")?.firstOrNull()
                    if (!firstName.isNullOrBlank()) {
                        followedDriversRepository.cacheDriverName(driver.pubkey, firstName)
                    }
                }
            }
        }
    }

    // Subscription management
    var subscriptionId by remember { mutableStateOf<String?>(null) }

    // Subscribe to driver location broadcasts
    LaunchedEffect(drivers, nostrService) {
        // Close existing subscription
        subscriptionId?.let { oldId ->
            nostrService?.closeRoadflareSubscription(oldId)
        }
        subscriptionId = null

        if (nostrService == null || drivers.isEmpty()) {
            Log.d(TAG, "Subscription skip: nostrService=${nostrService != null}, drivers.size=${drivers.size}")
            return@LaunchedEffect
        }

        // Only subscribe to drivers who have shared their RoadFlare key
        val driversWithKeys = drivers.filter { it.roadflareKey != null }
        if (driversWithKeys.isEmpty()) {
            Log.d(TAG, "Subscription skip: no drivers with keys (total drivers: ${drivers.size})")
            return@LaunchedEffect
        }

        val driverPubkeys = driversWithKeys.map { it.pubkey }
        Log.d(TAG, "=== SUBSCRIBING TO ROADFLARE LOCATIONS ===")
        Log.d(TAG, "Drivers with keys: ${driverPubkeys.size}")
        for (pubkey in driverPubkeys) {
            Log.d(TAG, "  subscribing to: ${pubkey.take(8)}")
        }

        subscriptionId = nostrService.subscribeToRoadflareLocations(driverPubkeys) { event, relayUrl ->
            Log.d(TAG, "*** RECEIVED RoadFlare location event from ${event.pubKey.take(8)} kind=${event.kind} via $relayUrl")

            // Find the driver and their RoadFlare key
            val driverPubKey = event.pubKey
            val driver = driversWithKeys.find { it.pubkey == driverPubKey }
            val roadflareKey = driver?.roadflareKey

            if (roadflareKey == null) {
                Log.w(TAG, "No RoadFlare key for driver ${driverPubKey.take(8)}")
                return@subscribeToRoadflareLocations
            }

            // Decrypt the location using the shared RoadFlare key
            val locationData = decryptRoadflareLocation(
                roadflarePrivKey = roadflareKey.privateKey,
                driverPubKey = driverPubKey,
                event = event
            )

            if (locationData != null) {
                val eventCreatedAt = event.createdAt  // Use event timestamp consistently

                // Check if event has expired (NIP-40 expiration tag)
                val isExpired = RoadflareLocationEvent.isExpired(event)

                // Check if this is older than what we already have (out-of-order)
                val lastSeen = lastLocationCreatedAt[driverPubKey] ?: 0L
                val isOutOfOrder = eventCreatedAt < lastSeen

                if (!isExpired && !isOutOfOrder) {
                    lastLocationCreatedAt[driverPubKey] = eventCreatedAt
                    Log.d(TAG, "Updated driver ${driverPubKey.take(8)} status=${locationData.tagStatus} (was: ${cachedLocations[driverPubKey]?.status})")

                    followedDriversRepository.updateDriverLocation(
                        pubkey = driverPubKey,
                        lat = locationData.location.lat,
                        lon = locationData.location.lon,
                        status = locationData.tagStatus,
                        timestamp = eventCreatedAt,
                        keyVersion = locationData.keyVersion
                    )
                } else {
                    Log.d(TAG, "Rejected stale/out-of-order 30014 from ${driverPubKey.take(8)}: expired=$isExpired, outOfOrder=$isOutOfOrder (event=$eventCreatedAt, lastSeen=$lastSeen)")
                }
            } else {
                Log.w(TAG, "Failed to decrypt location from ${driverPubKey.take(8)}")
            }
        }
        Log.d(TAG, "Subscription created: subscriptionId=$subscriptionId")
    }

    // Clean up subscription on disposal
    DisposableEffect(Unit) {
        onDispose {
            subscriptionId?.let { id ->
                nostrService?.closeRoadflareSubscription(id)
                Log.d(TAG, "Closed location subscription on dispose")
            }
        }
    }

    var showRemoveDialog by remember { mutableStateOf<FollowedDriver?>(null) }

    // Track which drivers have stale keys (need key refresh from driver)
    val staleKeyDrivers = remember { mutableStateMapOf<String, Boolean>() }

    // Track when we last requested a key refresh for each driver (rate limiting)
    val keyRefreshRequests = remember { mutableStateMapOf<String, Long>() }

    // Pull to refresh state
    var isRefreshing by remember { mutableStateOf(false) }

    // Check for stale keys on refresh - also auto-request key refresh
    fun checkStaleKeys() {
        if (nostrService == null) return
        scope.launch {
            drivers.forEach { driver ->
                val storedKey = driver.roadflareKey
                val storedKeyUpdatedAt = storedKey?.keyUpdatedAt ?: 0L

                // If we have no key yet, request a fresh key share (rate-limited).
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
                    // Fetch driver's current key_updated_at from their Kind 30012
                    val currentKeyUpdatedAt = nostrService.fetchDriverKeyUpdatedAt(driver.pubkey)
                    if (currentKeyUpdatedAt != null && currentKeyUpdatedAt > storedKeyUpdatedAt) {
                        Log.d(TAG, "Stale key detected for driver ${driver.pubkey.take(8)}: stored=$storedKeyUpdatedAt, current=$currentKeyUpdatedAt")
                        staleKeyDrivers[driver.pubkey] = true

                        // Auto-request key refresh (rate-limited to once per hour per driver)
                        val lastRefreshRequest = keyRefreshRequests[driver.pubkey] ?: 0L
                        val now = System.currentTimeMillis()
                        val oneHourMs = 3600_000L  // 1 hour in milliseconds

                        if (now - lastRefreshRequest > oneHourMs) {
                            keyRefreshRequests[driver.pubkey] = now

                            // Send Kind 3188 with status="stale" to request key refresh
                            val eventId = nostrService.publishRoadflareKeyAck(
                                driverPubKey = driver.pubkey,
                                keyVersion = driver.roadflareKey?.version ?: 0,
                                keyUpdatedAt = storedKeyUpdatedAt,
                                status = "stale"  // Indicates refresh request
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

    // Check stale keys on initial load
    LaunchedEffect(drivers) {
        if (nostrService != null && drivers.isNotEmpty()) {
            delay(3000) // Wait for connection
            checkStaleKeys()
        }
    }

    fun onRefresh() {
        scope.launch {
            isRefreshing = true
            // Refresh driver locations by re-subscribing
            followedDriversRepository.clearDriverLocations()
            // Check for stale keys
            checkStaleKeys()
            delay(500) // Small delay to show refresh indicator
            isRefreshing = false
        }
    }

    // Remove driver confirmation dialog
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
                        // Remove driver from favorites - driver will see via p-tag query
                        followedDriversRepository.removeDriver(driver.pubkey)
                        showRemoveDialog = null
                        // Trigger Nostr backup to publish updated Kind 30011 (without p-tag)
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

    // Payment methods dialog
    if (showPaymentMethodsDialog && settingsManager != null) {
        RoadflarePaymentMethodsDialog(
            settingsManager = settingsManager,
            onDismiss = { showPaymentMethodsDialog = false }
        )
    }

    Scaffold(
        floatingActionButton = {
            if (drivers.isNotEmpty()) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Payment methods FAB
                    SmallFloatingActionButton(
                        onClick = { showPaymentMethodsDialog = true }
                    ) {
                        Icon(Icons.Default.Payment, contentDescription = "Payment Methods")
                    }
                    // Add driver FAB
                    FloatingActionButton(
                        onClick = onAddDriver
                    ) {
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
                // Empty state
                EmptyDriversState(
                    onAddDriver = onAddDriver,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Drivers list
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 8.dp,
                        bottom = 100.dp // Space for FAB
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        ) {
                            Text(
                                text = "Favorite Drivers",
                                style = MaterialTheme.typography.headlineSmall,
                                modifier = Modifier.weight(1f)
                            )
                            if (drivers.size > 1) {
                                IconButton(onClick = {
                                    val shareText = drivers.joinToString("\n") { d ->
                                        val name = driverNames[d.pubkey]
                                        val npub = d.pubkey.hexToByteArray().toNpub()
                                        if (name != null) "$name: nostr:$npub" else "nostr:$npub"
                                    }
                                    val intent = Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, shareText)
                                    }
                                    context.startActivity(Intent.createChooser(intent, "Share driver list"))
                                }) {
                                    Icon(
                                        Icons.Default.Share,
                                        contentDescription = "Share all drivers"
                                    )
                                }
                            }
                        }
                    }

                    items(drivers, key = { it.pubkey }) { driver ->
                        // Convert CachedDriverLocation to DriverLocationState for display
                        val locationState = cachedLocations[driver.pubkey]?.let { cached ->
                            DriverLocationState(
                                pubkey = driver.pubkey,
                                lat = cached.lat,
                                lon = cached.lon,
                                status = cached.status,
                                timestamp = cached.timestamp,
                                keyVersion = cached.keyVersion
                            )
                        }
                        DriverCard(
                            driver = driver,
                            driverName = driverNames[driver.pubkey],
                            locationState = locationState,
                            riderLocation = riderLocation,
                            hasStaleKey = staleKeyDrivers[driver.pubkey] == true,
                            onClick = { onDriverClick(driver) },
                            onRemove = { showRemoveDialog = driver },
                            onShare = {
                                val npub = driver.pubkey.hexToByteArray().toNpub()
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "nostr:$npub")
                                }
                                context.startActivity(Intent.createChooser(intent, "Share driver"))
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Empty state when rider has no followed drivers.
 */
@Composable
private fun EmptyDriversState(
    onAddDriver: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier.padding(32.dp)
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_roadflare),
            contentDescription = null,
            modifier = Modifier.size(140.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Build Your Network",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Add drivers you've met and trust to your personal RoadFlare network. Send out a RoadFlare and it requests a ride straight from your trusted circle of favorite drivers.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onAddDriver,
            modifier = Modifier.fillMaxWidth(0.7f)
        ) {
            Icon(Icons.Default.PersonAdd, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Add Your First Driver")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Scan a driver's QR code or enter their Nostr pubkey",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Card displaying a single followed driver.
 *
 * @param driver The driver data (pubkey, note, key)
 * @param driverName Display name fetched from Nostr profile (or null if not yet loaded)
 */
@Composable
private fun DriverCard(
    driver: FollowedDriver,
    driverName: String?,
    locationState: DriverLocationState?,
    riderLocation: Location?,
    hasStaleKey: Boolean = false,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val displayName = driverName ?: driver.pubkey.take(8) + "..."

    // Calculate distance to driver if both locations available
    val distanceMiles = if (riderLocation != null && locationState != null &&
        locationState.status == RoadflareLocationEvent.Status.ONLINE) {
        val driverLoc = Location(locationState.lat, locationState.lon)
        val distanceKm = riderLocation.distanceToKm(driverLoc)
        distanceKm * 0.621371
    } else null

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Driver avatar placeholder
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = displayName.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Driver info
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Real-time status from location broadcast
                    DriverStatusBadge(
                        hasKey = driver.roadflareKey != null,
                        locationState = locationState,
                        hasStaleKey = hasStaleKey
                    )
                }

                // Show driver note or distance
                if (distanceMiles != null) {
                    Text(
                        text = formatDistance(distanceMiles),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else if (driver.note.isNotEmpty()) {
                    Text(
                        text = driver.note,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                // Show last seen time or "Added" date
                val statusText = if (locationState != null) {
                    val lastSeen = formatLastSeen(locationState.timestamp)
                    "Last seen $lastSeen"
                } else {
                    "Added ${dateFormat.format(Date(driver.addedAt * 1000))}"
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Share button
            IconButton(onClick = onShare) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "Share",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Remove button
            IconButton(onClick = onRemove) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Remove",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Format distance in a user-friendly way.
 */
private fun formatDistance(miles: Double): String {
    return when {
        miles < 0.1 -> "Very close"
        miles < 1.0 -> String.format("%.1f mi away", miles)
        miles < 10.0 -> String.format("%.1f mi away", miles)
        else -> String.format("%.0f mi away", miles)
    }
}

/**
 * Status badge showing driver's real-time status.
 *
 * Status mapping:
 * - online -> "Available" (Green)
 * - on_ride -> "On Ride" (Yellow/Orange)
 * - offline -> "Offline" (Gray)
 * - No location / stale -> "Offline" or "Pending" (Gray)
 */
@Composable
private fun DriverStatusBadge(
    hasKey: Boolean,
    locationState: DriverLocationState?,
    hasStaleKey: Boolean = false,
    modifier: Modifier = Modifier
) {
    // Check if location is stale (older than 5 minutes)
    val isStale = locationState?.let {
        val now = System.currentTimeMillis() / 1000
        (now - it.timestamp) > 300 // 5 minutes
    } ?: true

    // Priority order: stale key -> no key -> explicit OFFLINE -> stale data -> status-based
    // IMPORTANT: Check explicit OFFLINE status BEFORE staleness to ensure immediate offline detection
    val (color, text) = when {
        hasStaleKey -> MaterialTheme.colorScheme.error to "Key Outdated"
        !hasKey -> MaterialTheme.colorScheme.outline to "Pending"
        locationState == null -> MaterialTheme.colorScheme.outline to "Offline"
        locationState.status == RoadflareLocationEvent.Status.OFFLINE ->
            MaterialTheme.colorScheme.outline to "Offline"  // Explicit offline FIRST (before staleness)
        isStale -> MaterialTheme.colorScheme.outline to "Offline"  // Stale fallback
        locationState.status == RoadflareLocationEvent.Status.ONLINE ->
            MaterialTheme.colorScheme.primary to "Available"
        locationState.status == RoadflareLocationEvent.Status.ON_RIDE ->
            MaterialTheme.colorScheme.tertiary to "On Ride"
        else -> MaterialTheme.colorScheme.outline to "Offline"
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.1f),
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

/**
 * Format timestamp as "just now", "X min ago", etc.
 */
private fun formatLastSeen(timestamp: Long): String {
    val now = System.currentTimeMillis() / 1000
    val diff = now - timestamp

    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60} min ago"
        diff < 86400 -> "${diff / 3600} hr ago"
        else -> "${diff / 86400} days ago"
    }
}

/**
 * Decrypt a RoadFlare location event using the shared RoadFlare private key.
 *
 * NIP-44 ECDH math:
 * - Driver encrypted with: ECDH(driver_identity_priv, roadflare_pub)
 * - We decrypt with: ECDH(roadflare_priv, driver_identity_pub)
 * - These produce the same shared secret (ECDH is commutative)
 */
internal fun decryptRoadflareLocation(
    roadflarePrivKey: String,
    driverPubKey: String,
    event: com.vitorpamplona.quartz.nip01Core.core.Event
): RoadflareLocationData? {
    return try {
        // Create a temporary signer using the shared RoadFlare private key
        val keyPair = KeyPair(privKey = roadflarePrivKey.hexToByteArray())
        val tempSigner = NostrSignerInternal(keyPair)

        // Decrypt using the RoadFlare private key + driver's identity pubkey
        RoadflareLocationEvent.parseAndDecrypt(
            roadflarePrivKey = roadflarePrivKey,
            driverPubKey = driverPubKey,
            event = event,
            decryptFn = { ciphertext, counterpartyPubKey ->
                try {
                    // NIP-44 decrypt: uses tempSigner's private key + counterpartyPubKey
                    // Using runBlocking since nip44Decrypt is a suspend function
                    // and this callback runs on relay background thread
                    runBlocking {
                        tempSigner.nip44Decrypt(ciphertext, counterpartyPubKey)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "NIP-44 decrypt failed", e)
                    null
                }
            }
        )
    } catch (e: Exception) {
        Log.e(TAG, "Failed to decrypt RoadFlare location", e)
        null
    }
}

/**
 * Dialog for selecting RoadFlare alternate payment methods.
 * These are non-bitcoin methods riders can offer to personal RoadFlare drivers.
 */
@Composable
fun RoadflarePaymentMethodsDialog(
    settingsManager: com.ridestr.common.settings.SettingsManager,
    onDismiss: () -> Unit
) {
    val currentMethods by settingsManager.roadflarePaymentMethods.collectAsState()

    // Local state for Save/Cancel pattern
    var localMethods by remember(currentMethods) { mutableStateOf(currentMethods) }

    // All known methods + any unknown stored strings
    val allMethods = remember(localMethods) {
        val known = PaymentMethod.ROADFLARE_ALTERNATE_METHODS.map { it.value }
        (known + localMethods.filter { it !in known }).distinct()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Payment,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = { Text("Payment Methods") },
        text = {
            Column {
                Text(
                    "Bitcoin is the preferred payment method and is required for rides " +
                    "with random drivers. For personal RoadFlare drivers, you can offer " +
                    "alternate payment methods.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Select and drag to set priority order:",
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                com.ridestr.common.ui.components.ReorderablePaymentMethodList(
                    allMethods = allMethods,
                    enabledMethods = localMethods,
                    onOrderChanged = { reordered -> localMethods = reordered },
                    onMethodToggled = { method, enabled ->
                        localMethods = if (enabled) {
                            localMethods + method
                        } else {
                            localMethods - method
                        }
                    },
                    modifier = Modifier.heightIn(max = 350.dp)
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                settingsManager.setRoadflarePaymentMethods(localMethods)
                onDismiss()
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
