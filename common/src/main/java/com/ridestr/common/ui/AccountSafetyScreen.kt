package com.ridestr.common.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.RideshareEventKinds
import com.ridestr.common.payment.WalletService
import kotlinx.coroutines.launch

/**
 * Account Safety screen for managing data privacy.
 * Allows users to delete their rideshare events from relays.
 *
 * @param onLocalStateClear Called after successful event deletion to clear local app state
 *                          (SharedPreferences ride state, etc.). This ensures phantom events
 *                          from local storage don't affect new rides.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSafetyScreen(
    nostrService: NostrService,
    onBack: () -> Unit,
    onLocalStateClear: (() -> Unit)? = null,
    walletService: WalletService? = null,
    modifier: Modifier = Modifier
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var deleteResult by remember { mutableStateOf<DeleteResult?>(null) }
    var isCheckingEvents by remember { mutableStateOf(false) }
    var eventCount by remember { mutableStateOf<Int?>(null) }
    var kindCounts by remember { mutableStateOf<Map<Int, Int>?>(null) }
    val scope = rememberCoroutineScope()

    // Note: Wallet-related state (wallet reset, NIP-60 deletion) moved to Settings → Wallet

    // Confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { if (!isDeleting) showDeleteDialog = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Delete All Rideshare Events?") },
            text = {
                Text(
                    "This will request deletion of your rideshare activity events from connected relays. " +
                    "This includes ride offers, acceptances, chat messages, and status updates.\n\n" +
                    "Your profile backup (vehicles, saved locations, settings) and ride history backup will NOT be deleted. " +
                    "Use Advanced Cleanup to delete those separately.\n\n" +
                    "Note: Relays may not honor deletion requests. This action cannot be undone."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            isDeleting = true
                            deleteResult = null
                            try {
                                val count = nostrService.deleteAllRideshareEvents()
                                deleteResult = DeleteResult.Success(count)
                                // Clear local state after successful relay deletion
                                // This prevents phantom events from local storage affecting new rides
                                onLocalStateClear?.invoke()
                            } catch (e: Exception) {
                                deleteResult = DeleteResult.Error(e.message ?: "Unknown error")
                            }
                            isDeleting = false
                            showDeleteDialog = false
                        }
                    },
                    enabled = !isDeleting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onError
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(if (isDeleting) "Deleting..." else "Delete All")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showDeleteDialog = false },
                    enabled = !isDeleting
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account Safety") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                }
            )
        },
        modifier = modifier
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Header info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Your rideshare events are stored on Nostr relays. " +
                               "You can request deletion of these events to protect your privacy.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Section: Data Management
            Text(
                text = "Data Management",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Check events card - verify how many events exist
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Check My Events",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "See how many rideshare events are stored on relays",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Show event count result if available
                    eventCount?.let { count ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (count == 0)
                                    MaterialTheme.colorScheme.primaryContainer
                                else
                                    MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (count == 0) Icons.Default.CheckCircle else Icons.Default.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = if (count == 0)
                                        MaterialTheme.colorScheme.primary
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (count == 0)
                                        "No rideshare events found on relays"
                                    else
                                        "Found $count rideshare event${if (count != 1) "s" else ""} on relays",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                isCheckingEvents = true
                                eventCount = null
                                kindCounts = null
                                try {
                                    val counts = nostrService.countRideshareEventsByKind()
                                    kindCounts = counts
                                    eventCount = counts.values.sum()
                                } catch (e: Exception) {
                                    eventCount = -1 // Error indicator
                                }
                                isCheckingEvents = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isCheckingEvents
                    ) {
                        if (isCheckingEvents) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Checking...")
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (eventCount != null) "Check Again" else "Check Events")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Delete events card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Delete All Rideshare Events",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text = "Request removal of ride activity events (offers, acceptances, messages, status). Does NOT delete profile or history backups.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Event types that will be deleted
                    Text(
                        text = if (kindCounts != null) "Event types found:" else "Event types included:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        EventTypeRow("Driver Availability", RideshareEventKinds.DRIVER_AVAILABILITY, kindCounts?.get(RideshareEventKinds.DRIVER_AVAILABILITY))
                        EventTypeRow("Ride Offers", RideshareEventKinds.RIDE_OFFER, kindCounts?.get(RideshareEventKinds.RIDE_OFFER))
                        EventTypeRow("Ride Acceptances", RideshareEventKinds.RIDE_ACCEPTANCE, kindCounts?.get(RideshareEventKinds.RIDE_ACCEPTANCE))
                        EventTypeRow("Ride Confirmations", RideshareEventKinds.RIDE_CONFIRMATION, kindCounts?.get(RideshareEventKinds.RIDE_CONFIRMATION))
                        EventTypeRow("Driver Ride State", RideshareEventKinds.DRIVER_RIDE_STATE, kindCounts?.get(RideshareEventKinds.DRIVER_RIDE_STATE))
                        EventTypeRow("Rider Ride State", RideshareEventKinds.RIDER_RIDE_STATE, kindCounts?.get(RideshareEventKinds.RIDER_RIDE_STATE))
                        EventTypeRow("Chat Messages", RideshareEventKinds.RIDESHARE_CHAT, kindCounts?.get(RideshareEventKinds.RIDESHARE_CHAT))
                        EventTypeRow("Ride Cancellations", RideshareEventKinds.RIDE_CANCELLATION, kindCounts?.get(RideshareEventKinds.RIDE_CANCELLATION))
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "NOT deleted (use Advanced Cleanup):",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        EventTypeRow("Profile Backup", RideshareEventKinds.PROFILE_BACKUP, kindCounts?.get(RideshareEventKinds.PROFILE_BACKUP))
                        EventTypeRow("Ride History Backup", RideshareEventKinds.RIDE_HISTORY_BACKUP, kindCounts?.get(RideshareEventKinds.RIDE_HISTORY_BACKUP))
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { showDeleteDialog = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete All Events")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Advanced Cleanup - Granular per-kind deletion
            var showAdvancedCleanup by remember { mutableStateOf(false) }
            var isDeletingKind by remember { mutableStateOf<Int?>(null) }
            var kindDeleteResult by remember { mutableStateOf<Pair<Int, DeleteResult>?>(null) }

            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Build,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Advanced Cleanup",
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = "Delete specific event types only",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = { showAdvancedCleanup = !showAdvancedCleanup }) {
                            Icon(
                                imageVector = if (showAdvancedCleanup) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = if (showAdvancedCleanup) "Collapse" else "Expand"
                            )
                        }
                    }

                    if (showAdvancedCleanup) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))

                        // Per-kind deletion buttons
                        val kindOptions = listOf(
                            Triple("Availability Events", RideshareEventKinds.DRIVER_AVAILABILITY, "Stale online status from crashed sessions"),
                            Triple("Driver Ride State", RideshareEventKinds.DRIVER_RIDE_STATE, "Status + PIN submissions (consolidated)"),
                            Triple("Rider Ride State", RideshareEventKinds.RIDER_RIDE_STATE, "Verifications + locations (consolidated)"),
                            Triple("Chat Messages", RideshareEventKinds.RIDESHARE_CHAT, "In-ride messages"),
                            Triple("Ride Cancellations", RideshareEventKinds.RIDE_CANCELLATION, "Cancelled ride notices"),
                            Triple("Ride Acceptances", RideshareEventKinds.RIDE_ACCEPTANCE, "Your accepted rides")
                        )

                        Text(
                            text = "Ride Activity Events",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )

                        kindOptions.forEach { (name, kind, description) ->
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        isDeletingKind = kind
                                        kindDeleteResult = null
                                        try {
                                            val count = nostrService.deleteMyEventsByKind(kind, "manual cleanup")
                                            kindDeleteResult = kind to DeleteResult.Success(count)
                                        } catch (e: Exception) {
                                            kindDeleteResult = kind to DeleteResult.Error(e.message ?: "Unknown error")
                                        }
                                        isDeletingKind = null
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                enabled = isDeletingKind == null
                            ) {
                                if (isDeletingKind == kind) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Text(
                                        text = "$name (kind $kind)",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // Show result for this kind
                            kindDeleteResult?.let { (resultKind, result) ->
                                if (resultKind == kind) {
                                    Row(
                                        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = when (result) {
                                                is DeleteResult.Success -> Icons.Default.CheckCircle
                                                is DeleteResult.Error -> Icons.Default.Warning
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = when (result) {
                                                is DeleteResult.Success -> MaterialTheme.colorScheme.primary
                                                is DeleteResult.Error -> MaterialTheme.colorScheme.error
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = when (result) {
                                                is DeleteResult.Success -> "Deleted ${result.count} events"
                                                is DeleteResult.Error -> "Error: ${result.message}"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = when (result) {
                                                is DeleteResult.Success -> MaterialTheme.colorScheme.primary
                                                is DeleteResult.Error -> MaterialTheme.colorScheme.error
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Backup Events (Encrypted)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )

                        // Backup event deletion options
                        val backupOptions = listOf(
                            Triple("Profile Backup", RideshareEventKinds.PROFILE_BACKUP, "Vehicles, saved locations, settings (Kind 30177)"),
                            Triple("Ride History Backup", RideshareEventKinds.RIDE_HISTORY_BACKUP, "Encrypted ride history (Kind 30174)"),
                            @Suppress("DEPRECATION")
                            Triple("Vehicles (Legacy)", RideshareEventKinds.VEHICLE_BACKUP, "Deprecated - migrated to Profile Backup"),
                            @Suppress("DEPRECATION")
                            Triple("Saved Locations (Legacy)", RideshareEventKinds.SAVED_LOCATIONS_BACKUP, "Deprecated - migrated to Profile Backup")
                        )

                        backupOptions.forEach { (name, kind, description) ->
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        isDeletingKind = kind
                                        kindDeleteResult = null
                                        try {
                                            val count = nostrService.deleteMyEventsByKind(kind, "manual cleanup")
                                            kindDeleteResult = kind to DeleteResult.Success(count)
                                        } catch (e: Exception) {
                                            kindDeleteResult = kind to DeleteResult.Error(e.message ?: "Unknown error")
                                        }
                                        isDeletingKind = null
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                enabled = isDeletingKind == null
                            ) {
                                if (isDeletingKind == kind) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Column(
                                    modifier = Modifier.weight(1f),
                                    horizontalAlignment = Alignment.Start
                                ) {
                                    Text(
                                        text = "$name (kind $kind)",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            // Show result for this kind
                            kindDeleteResult?.let { (resultKind, result) ->
                                if (resultKind == kind) {
                                    Row(
                                        modifier = Modifier.padding(start = 8.dp, bottom = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = when (result) {
                                                is DeleteResult.Success -> Icons.Default.CheckCircle
                                                is DeleteResult.Error -> Icons.Default.Warning
                                            },
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = when (result) {
                                                is DeleteResult.Success -> MaterialTheme.colorScheme.primary
                                                is DeleteResult.Error -> MaterialTheme.colorScheme.error
                                            }
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = when (result) {
                                                is DeleteResult.Success -> "Deleted ${result.count} events"
                                                is DeleteResult.Error -> "Error: ${result.message}"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = when (result) {
                                                is DeleteResult.Success -> MaterialTheme.colorScheme.primary
                                                is DeleteResult.Error -> MaterialTheme.colorScheme.error
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Result message
            deleteResult?.let { result ->
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when (result) {
                            is DeleteResult.Success -> MaterialTheme.colorScheme.primaryContainer
                            is DeleteResult.Error -> MaterialTheme.colorScheme.errorContainer
                        }
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = when (result) {
                                is DeleteResult.Success -> Icons.Default.CheckCircle
                                is DeleteResult.Error -> Icons.Default.Warning
                            },
                            contentDescription = null,
                            tint = when (result) {
                                is DeleteResult.Success -> MaterialTheme.colorScheme.primary
                                is DeleteResult.Error -> MaterialTheme.colorScheme.error
                            }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = when (result) {
                                is DeleteResult.Success -> "Deletion requested for ${result.count} events"
                                is DeleteResult.Error -> "Error: ${result.message}"
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Note: Wallet data options moved to Settings → Wallet

            Spacer(modifier = Modifier.height(24.dp))

            // Warning section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Important",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "• Deletion requests (NIP-09) are suggestions - relays may not honor them\n" +
                                   "• Other users' copies of shared events cannot be deleted\n" +
                                   "• Encrypted content remains unreadable without your private key",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    // Note: Wallet reset and NIP-60 delete dialogs moved to Settings → Wallet
}

@Composable
private fun EventTypeRow(name: String, kind: Int, count: Int? = null) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (count != null && count > 0) {
                "$name ($count)"
            } else {
                name
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (count != null && count > 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

private sealed class DeleteResult {
    data class Success(val count: Int) : DeleteResult()
    data class Error(val message: String) : DeleteResult()
}
