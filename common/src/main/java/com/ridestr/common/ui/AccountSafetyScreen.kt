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
import kotlinx.coroutines.launch

/**
 * Account Safety screen for managing data privacy.
 * Allows users to delete their rideshare events from relays.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSafetyScreen(
    nostrService: NostrService,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var isDeleting by remember { mutableStateOf(false) }
    var deleteResult by remember { mutableStateOf<DeleteResult?>(null) }
    var isCheckingEvents by remember { mutableStateOf(false) }
    var eventCount by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()

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
                    "This will request deletion of all your rideshare events from connected relays. " +
                    "This includes ride offers, acceptances, chat messages, and status updates.\n\n" +
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
                                try {
                                    eventCount = nostrService.countRideshareEvents()
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
                                text = "Request removal of ride offers, acceptances, messages, and status updates from relays",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Event types that will be deleted
                    Text(
                        text = "Event types included:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(modifier = Modifier.padding(start = 8.dp)) {
                        EventTypeRow("Driver Availability", RideshareEventKinds.DRIVER_AVAILABILITY)
                        EventTypeRow("Ride Offers", RideshareEventKinds.RIDE_OFFER)
                        EventTypeRow("Ride Acceptances", RideshareEventKinds.RIDE_ACCEPTANCE)
                        EventTypeRow("Ride Confirmations", RideshareEventKinds.RIDE_CONFIRMATION)
                        EventTypeRow("PIN Submissions", RideshareEventKinds.PIN_SUBMISSION)
                        EventTypeRow("Pickup Verifications", RideshareEventKinds.PICKUP_VERIFICATION)
                        EventTypeRow("Chat Messages", RideshareEventKinds.RIDESHARE_CHAT)
                        EventTypeRow("Ride Cancellations", RideshareEventKinds.RIDE_CANCELLATION)
                        EventTypeRow("Driver Status", RideshareEventKinds.DRIVER_STATUS)
                        EventTypeRow("Location Reveals", RideshareEventKinds.PRECISE_LOCATION_REVEAL)
                        EventTypeRow("Ride History Backup", RideshareEventKinds.RIDE_HISTORY_BACKUP)
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
}

@Composable
private fun EventTypeRow(name: String, kind: Int) {
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
            text = "$name (kind $kind)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private sealed class DeleteResult {
    data class Success(val count: Int) : DeleteResult()
    data class Error(val message: String) : DeleteResult()
}
