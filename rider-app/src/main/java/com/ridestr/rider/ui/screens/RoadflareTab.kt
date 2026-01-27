package com.ridestr.rider.ui.screens

import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    val status: String, // "online", "on_ride", "do_not_disturb"
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

/** Default RoadFlare rate: $1.20 per mile = 120 sats/mile at ~$100k/BTC */
private const val ROADFLARE_SATS_PER_MILE = 120L

/**
 * Calculate fare estimate for a RoadFlare ride.
 *
 * @param riderLocation Rider's current location (pickup point)
 * @param destination Ride destination
 * @param driverLocation Driver's current location (if available)
 * @return Fare estimate with distance and accuracy flag
 */
fun calculateRoadflareFare(
    riderLocation: Location,
    destination: Location,
    driverLocation: DriverLocationState?
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

    // Calculate fare based on ride distance
    val fareSats = (rideDistanceMiles * ROADFLARE_SATS_PER_MILE).toLong()

    return RoadflareFareEstimate(
        satoshis = fareSats.coerceAtLeast(100), // Minimum 100 sats
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
    riderLocation: Location? = null,
    onAddDriver: () -> Unit = {},
    onDriverClick: (FollowedDriver) -> Unit = {},
    onSendRoadflare: (List<FollowedDriver>) -> Unit = {},
    onDriverRemoved: () -> Unit = {},  // Called after driver removed - triggers Nostr backup
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    val drivers by followedDriversRepository.drivers.collectAsState()
    val driverNames by followedDriversRepository.driverNames.collectAsState()

    // Track driver locations by pubkey
    val driverLocations = remember { mutableStateMapOf<String, DriverLocationState>() }

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

        if (nostrService == null || drivers.isEmpty()) return@LaunchedEffect

        // Only subscribe to drivers who have shared their RoadFlare key
        val driversWithKeys = drivers.filter { it.roadflareKey != null }
        if (driversWithKeys.isEmpty()) return@LaunchedEffect

        val driverPubkeys = driversWithKeys.map { it.pubkey }
        Log.d(TAG, "Subscribing to locations from ${driverPubkeys.size} drivers with keys")

        subscriptionId = nostrService.subscribeToRoadflareLocations(driverPubkeys) { event, relayUrl ->
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
                Log.d(TAG, "Received location from ${driverPubKey.take(8)}: " +
                    "${locationData.location.lat}, ${locationData.location.lon}, status=${locationData.tagStatus}")

                driverLocations[driverPubKey] = DriverLocationState(
                    pubkey = driverPubKey,
                    lat = locationData.location.lat,
                    lon = locationData.location.lon,
                    status = locationData.tagStatus,
                    timestamp = locationData.location.timestamp,
                    keyVersion = locationData.keyVersion
                )
            } else {
                Log.w(TAG, "Failed to decrypt location from ${driverPubKey.take(8)}")
            }
        }
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

    // Pull to refresh state
    var isRefreshing by remember { mutableStateOf(false) }

    // Check for stale keys on refresh
    fun checkStaleKeys() {
        if (nostrService == null) return
        scope.launch {
            drivers.forEach { driver ->
                val storedKeyUpdatedAt = driver.roadflareKey?.keyUpdatedAt ?: 0L
                if (storedKeyUpdatedAt > 0) {
                    // Fetch driver's current key_updated_at from their Kind 30012
                    val currentKeyUpdatedAt = nostrService.fetchDriverKeyUpdatedAt(driver.pubkey)
                    if (currentKeyUpdatedAt != null && currentKeyUpdatedAt > storedKeyUpdatedAt) {
                        Log.d(TAG, "Stale key detected for driver ${driver.pubkey.take(8)}: stored=$storedKeyUpdatedAt, current=$currentKeyUpdatedAt")
                        staleKeyDrivers[driver.pubkey] = true
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
            driverLocations.clear()
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

    Scaffold(
        floatingActionButton = {
            if (drivers.isNotEmpty()) {
                // Add driver FAB only - Send RoadFlare is in driver selection screen
                FloatingActionButton(
                    onClick = onAddDriver
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = "Add Driver")
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
                        Text(
                            text = "Favorite Drivers",
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    items(drivers, key = { it.pubkey }) { driver ->
                        DriverCard(
                            driver = driver,
                            driverName = driverNames[driver.pubkey],
                            locationState = driverLocations[driver.pubkey],
                            riderLocation = riderLocation,
                            hasStaleKey = staleKeyDrivers[driver.pubkey] == true,
                            onClick = { onDriverClick(driver) },
                            onRemove = { showRemoveDialog = driver }
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
            imageVector = Icons.Default.People,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Build Your Network",
            style = MaterialTheme.typography.headlineMedium
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Add drivers you've met and trust to your personal RoadFlare network. Send out a RoadFlare and it requests a ride straight from your trusted circle of favorite drivers.",
            style = MaterialTheme.typography.bodyLarge,
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
 * - do_not_disturb -> "Unavailable" (Gray)
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

    val (color, text) = when {
        hasStaleKey -> MaterialTheme.colorScheme.error to "Key Outdated"
        !hasKey -> MaterialTheme.colorScheme.outline to "Pending"
        locationState == null || isStale -> MaterialTheme.colorScheme.outline to "Offline"
        locationState.status == RoadflareLocationEvent.Status.ONLINE ->
            MaterialTheme.colorScheme.primary to "Available"
        locationState.status == RoadflareLocationEvent.Status.ON_RIDE ->
            MaterialTheme.colorScheme.tertiary to "On Ride"
        locationState.status == RoadflareLocationEvent.Status.DO_NOT_DISTURB ->
            MaterialTheme.colorScheme.outline to "Unavailable"
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
private fun decryptRoadflareLocation(
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
