package com.roadflare.rider.ui.screens.components

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.roadflare.rider.R
import com.ridestr.common.nostr.events.FollowedDriver
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.events.PaymentMethod
import com.ridestr.common.nostr.events.RoadflareLocationEvent
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.vitorpamplona.quartz.nip19Bech32.toNpub
import java.text.SimpleDateFormat
import java.util.*

/** Real-time location and status of a followed driver from their RoadFlare broadcast. */
data class DriverLocationState(
    val pubkey: String,
    val lat: Double,
    val lon: Double,
    val status: String,
    val timestamp: Long,
    val keyVersion: Int
)

@Composable
fun FollowedDriverList(
    drivers: List<FollowedDriver>,
    driverNames: Map<String, String>,
    locationStates: Map<String, DriverLocationState>,
    riderLocation: Location?,
    staleKeyDrivers: Map<String, Boolean>,
    onDriverClick: (FollowedDriver) -> Unit,
    onRemove: (FollowedDriver) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    LazyColumn(
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = 8.dp,
            bottom = 100.dp
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.fillMaxSize()
    ) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
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
                        Icon(Icons.Default.Share, contentDescription = "Share all drivers")
                    }
                }
            }
        }

        items(drivers, key = { it.pubkey }) { driver ->
            DriverCard(
                driver = driver,
                driverName = driverNames[driver.pubkey],
                locationState = locationStates[driver.pubkey],
                riderLocation = riderLocation,
                hasStaleKey = staleKeyDrivers[driver.pubkey] == true,
                onClick = { onDriverClick(driver) },
                onRemove = { onRemove(driver) },
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

@Composable
fun EmptyDriversState(
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

    val distanceMiles = remember(riderLocation, locationState) {
        if (riderLocation != null && locationState != null &&
            locationState.status == RoadflareLocationEvent.Status.ONLINE) {
            val driverLoc = Location(locationState.lat, locationState.lon)
            val distanceKm = riderLocation.distanceToKm(driverLoc)
            distanceKm * 0.621371
        } else null
    }

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

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    DriverStatusBadge(
                        hasKey = driver.roadflareKey != null,
                        locationState = locationState,
                        hasStaleKey = hasStaleKey
                    )
                }

                if (distanceMiles != null) {
                    Text(
                        text = formatDriverDistance(distanceMiles),
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

                val statusText = if (locationState != null) {
                    "Last seen ${formatDriverLastSeen(locationState.timestamp)}"
                } else {
                    "Added ${dateFormat.format(Date(driver.addedAt * 1000))}"
                }
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onShare) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "Share",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

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

@Composable
private fun DriverStatusBadge(
    hasKey: Boolean,
    locationState: DriverLocationState?,
    hasStaleKey: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isStale = locationState?.let {
        val now = System.currentTimeMillis() / 1000
        (now - it.timestamp) > 300
    } ?: true

    // Priority: stale key -> no key -> explicit OFFLINE -> stale data -> status-based
    val (color, text) = when {
        hasStaleKey -> MaterialTheme.colorScheme.error to "Key Outdated"
        !hasKey -> MaterialTheme.colorScheme.outline to "Pending"
        locationState == null -> MaterialTheme.colorScheme.outline to "Offline"
        locationState.status == RoadflareLocationEvent.Status.OFFLINE ->
            MaterialTheme.colorScheme.outline to "Offline"
        isStale -> MaterialTheme.colorScheme.outline to "Offline"
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

@Composable
fun RoadflarePaymentMethodsDialog(
    currentMethods: List<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var localMethods by remember(currentMethods) { mutableStateOf(currentMethods) }

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
                    "For your personal RoadFlare drivers, select which " +
                    "payment methods you can offer.",
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
                onSave(localMethods)
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

private fun formatDriverDistance(miles: Double): String {
    return when {
        miles < 0.1 -> "Very close"
        miles < 10.0 -> String.format(Locale.US, "%.1f mi away", miles)
        else -> String.format(Locale.US, "%.0f mi away", miles)
    }
}

private fun formatDriverLastSeen(timestamp: Long): String {
    val now = System.currentTimeMillis() / 1000
    val diff = now - timestamp
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60} min ago"
        diff < 86400 -> "${diff / 3600} hr ago"
        else -> "${diff / 86400} days ago"
    }
}
