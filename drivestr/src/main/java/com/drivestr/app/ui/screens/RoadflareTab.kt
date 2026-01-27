package com.drivestr.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.ridestr.common.data.DriverRoadflareRepository
import com.ridestr.common.nostr.events.RoadflareFollower
import java.text.SimpleDateFormat
import java.util.*

/**
 * RoadFlare tab for drivers showing:
 * - QR code for riders to scan
 * - Pending followers with Approve/Decline buttons
 * - Approved followers with Mute controls
 * - DND toggle to pause location broadcasts
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoadflareTab(
    driverRoadflareRepository: DriverRoadflareRepository,
    driverPubkey: String,
    driverNpub: String = "",
    driverName: String = "",
    backgroundAlertsEnabled: Boolean = false,
    onApproveFollower: (String) -> Unit = {},
    onDeclineFollower: (String) -> Unit = {},
    onMuteFollower: (String) -> Unit = {},
    onUnmuteFollower: (String) -> Unit = {},
    onRemoveFollower: (String) -> Unit = {},
    onToggleDnd: (Boolean) -> Unit = {},
    onRefreshFollowers: (suspend () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val state by driverRoadflareRepository.state.collectAsState()
    val dndActive by driverRoadflareRepository.dndActive.collectAsState()

    val allFollowers = state?.followers ?: emptyList()
    val pendingFollowers = allFollowers.filter { !it.approved }
    val approvedFollowers = allFollowers.filter { it.approved }
    val mutedPubkeys = state?.muted?.map { it.pubkey }?.toSet() ?: emptySet()
    val hasKey = state?.roadflareKey != null

    var showMuteDialog by remember { mutableStateOf<RoadflareFollower?>(null) }
    var showRemoveDialog by remember { mutableStateOf<RoadflareFollower?>(null) }

    // Pull to refresh state
    var isRefreshing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun onRefresh() {
        scope.launch {
            isRefreshing = true
            try {
                // Query for new followers via p-tag search
                onRefreshFollowers?.invoke()
                // Small delay to ensure UI shows refresh indicator
                delay(500)
            } finally {
                isRefreshing = false
            }
        }
    }

    // Mute confirmation dialog
    showMuteDialog?.let { follower ->
        AlertDialog(
            onDismissRequest = { showMuteDialog = null },
            icon = { Icon(Icons.Default.VolumeOff, contentDescription = null) },
            title = { Text("Mute Follower?") },
            text = {
                Text("This rider will no longer see your location. Your RoadFlare key will be rotated and all other followers will receive the new key.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onMuteFollower(follower.pubkey)
                        showMuteDialog = null
                    }
                ) {
                    Text("Mute", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showMuteDialog = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Remove confirmation dialog (with key rotation for security)
    showRemoveDialog?.let { follower ->
        val displayName = follower.name.ifEmpty { "${follower.pubkey.take(8)}..." }
        AlertDialog(
            onDismissRequest = { showRemoveDialog = null },
            icon = { Icon(Icons.Default.PersonRemove, contentDescription = null) },
            title = { Text("Remove Follower?") },
            text = {
                Text("Remove $displayName from your followers? Your RoadFlare key will be rotated and all other followers will receive the new key.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveFollower(follower.pubkey)
                        showRemoveDialog = null
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

    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = { onRefresh() },
        modifier = modifier.fillMaxSize()
    ) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            // Header with status (only count approved followers)
            item {
                BroadcastStatusCard(
                    dndActive = dndActive,
                    hasKey = hasKey,
                    followerCount = approvedFollowers.size,
                    pendingCount = pendingFollowers.size,
                    backgroundAlertsEnabled = backgroundAlertsEnabled,
                    onToggleDnd = onToggleDnd
                )
            }

            // QR Code section
            item {
                QrCodeCard(
                    driverNpub = driverNpub,
                    driverName = driverName
                )
            }

            // Pending followers section (need approval)
            if (pendingFollowers.isNotEmpty()) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Text(
                            text = "Pending Requests",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "${pendingFollowers.size}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                items(pendingFollowers, key = { "pending_${it.pubkey}" }) { follower ->
                    PendingFollowerCard(
                        follower = follower,
                        onApprove = { onApproveFollower(follower.pubkey) },
                        onDecline = { onDeclineFollower(follower.pubkey) }
                    )
                }
            }

            // Approved followers section
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Text(
                        text = "Followers",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${approvedFollowers.size} rider${if (approvedFollowers.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (approvedFollowers.isEmpty()) {
                item {
                    EmptyFollowersCard()
                }
            } else {
                items(approvedFollowers, key = { it.pubkey }) { follower ->
                    FollowerCard(
                        follower = follower,
                        isMuted = follower.pubkey in mutedPubkeys,
                        onMute = { showMuteDialog = follower },
                        onUnmute = { onUnmuteFollower(follower.pubkey) },
                        onRemove = { showRemoveDialog = follower }
                    )
                }
            }

            // Bottom spacing
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

/**
 * Card showing broadcast status and DND toggle.
 */
@Composable
private fun BroadcastStatusCard(
    dndActive: Boolean,
    hasKey: Boolean,
    followerCount: Int,
    pendingCount: Int = 0,
    backgroundAlertsEnabled: Boolean = false,
    onToggleDnd: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColor = when {
        pendingCount > 0 -> MaterialTheme.colorScheme.tertiary
        dndActive -> MaterialTheme.colorScheme.error
        hasKey && followerCount > 0 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }

    val statusText = when {
        pendingCount > 0 -> "$pendingCount Pending Request${if (pendingCount != 1) "s" else ""}"
        dndActive -> "Unavailable"
        hasKey && followerCount > 0 -> "Available"
        hasKey -> "Ready (no followers yet)"
        else -> "Not Set Up"
    }

    val statusIcon = when {
        pendingCount > 0 -> Icons.Default.PersonAdd
        dndActive -> Icons.Default.DoNotDisturb
        hasKey && followerCount > 0 -> Icons.Default.Sensors
        else -> Icons.Default.SensorsOff
    }

    // Subtitle explains what's happening
    val statusSubtitle = when {
        pendingCount > 0 -> null // No subtitle for pending state
        dndActive -> "Location hidden, notifications paused"
        hasKey && followerCount > 0 && backgroundAlertsEnabled ->
            "Location visible to $followerCount follower${if (followerCount != 1) "s" else ""} \u2022 Background alerts on"
        hasKey && followerCount > 0 ->
            "Location visible to $followerCount follower${if (followerCount != 1) "s" else ""}"
        else -> null
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = statusColor.copy(alpha = 0.1f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    tint = statusColor,
                    modifier = Modifier.size(32.dp)
                )

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleMedium,
                        color = statusColor
                    )
                    if (statusSubtitle != null) {
                        Text(
                            text = statusSubtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // DND Toggle
                Switch(
                    checked = dndActive,
                    onCheckedChange = onToggleDnd,
                    thumbContent = {
                        Icon(
                            imageVector = if (dndActive) Icons.Default.DoNotDisturb else Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }

            // Explanation text
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (dndActive) {
                    "Turn off to share your location with followers and receive RoadFlare requests"
                } else {
                    "Turn on to hide your location and pause all RoadFlare notifications"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * Card with QR code for driver's profile.
 */
@Composable
private fun QrCodeCard(
    driverNpub: String,
    driverName: String,
    modifier: Modifier = Modifier
) {
    // QR code content: nostr:npub1... with optional name parameter
    // Format: nostr:npub1...?name=DriverName (URL encoded)
    val qrContent = remember(driverNpub, driverName) {
        if (driverName.isNotBlank()) {
            val encodedName = java.net.URLEncoder.encode(driverName, "UTF-8")
            "nostr:$driverNpub?name=$encodedName"
        } else {
            "nostr:$driverNpub"
        }
    }
    val qrBitmap = remember(qrContent) {
        generateQrCode(qrContent, 256)
    }

    Card(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Text(
                text = "Your RoadFlare QR Code",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Riders scan this to add you to their favorites",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // QR Code
            if (qrBitmap != null) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp
                ) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "QR Code",
                        modifier = Modifier
                            .size(200.dp)
                            .padding(8.dp)
                    )
                }
            } else {
                // Fallback if QR generation fails
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(200.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.QrCode,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (driverName.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = driverName,
                    style = MaterialTheme.typography.titleSmall
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Npub preview
            Text(
                text = "${driverNpub.take(12)}...${driverNpub.takeLast(8)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Empty state when driver has no followers.
 */
@Composable
private fun EmptyFollowersCard(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.PersonSearch,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "No followers yet",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Share your QR code with riders you meet. They'll be able to see your location and send you ride requests.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Card for a pending follower request with Approve/Decline buttons.
 */
@Composable
private fun PendingFollowerCard(
    follower: RoadflareFollower,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val displayName = follower.name.ifEmpty { "${follower.pubkey.take(8)}...${follower.pubkey.takeLast(4)}" }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Avatar placeholder with pending indicator
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.PersonAdd,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "Requested ${dateFormat.format(Date(follower.addedAt * 1000))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Approve/Decline buttons
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onDecline) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Decline",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
                FilledIconButton(
                    onClick = onApprove,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Approve",
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

/**
 * Card for an approved follower.
 */
@Composable
private fun FollowerCard(
    follower: RoadflareFollower,
    isMuted: Boolean,
    onMute: () -> Unit,
    onUnmute: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showMenu by remember { mutableStateOf(false) }
    val dateFormat = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }
    val displayName = follower.name.ifEmpty { "${follower.pubkey.take(8)}...${follower.pubkey.takeLast(4)}" }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = if (isMuted) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            )
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Avatar placeholder
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (isMuted) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.secondaryContainer
                },
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isMuted) Icons.Default.VolumeOff else Icons.Default.Person,
                        contentDescription = null,
                        tint = if (isMuted) {
                            MaterialTheme.colorScheme.onErrorContainer
                        } else {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Added ${dateFormat.format(Date(follower.addedAt * 1000))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (isMuted) {
                        Text(
                            text = "Muted",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Options menu
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Options",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    if (isMuted) {
                        DropdownMenuItem(
                            text = { Text("Unmute") },
                            onClick = {
                                showMenu = false
                                onUnmute()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.VolumeUp, contentDescription = null)
                            }
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text("Mute") },
                            onClick = {
                                showMenu = false
                                onMute()
                            },
                            leadingIcon = {
                                Icon(Icons.Default.VolumeOff, contentDescription = null)
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Remove", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            onRemove()
                        },
                        leadingIcon = {
                            Icon(
                                Icons.Default.PersonRemove,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                        }
                    )
                }
            }
        }
    }
}

/**
 * Generate a QR code bitmap.
 */
private fun generateQrCode(content: String, size: Int): Bitmap? {
    return try {
        val writer = QRCodeWriter()
        val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
        val width = bitMatrix.width
        val height = bitMatrix.height
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        for (x in 0 until width) {
            for (y in 0 until height) {
                bitmap.setPixel(x, y, if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}
