package com.ridestr.common.ui

import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ridestr.common.sync.ProfileSyncState
import com.ridestr.common.sync.RestoredProfileData

/**
 * Ridestr data sync screen shown during onboarding when importing an existing key.
 *
 * First checks if there's any Ridestr-specific data (vehicles, saved locations,
 * ride history) to restore. If found, syncs and shows what was restored.
 * If not found, shows a brief message and allows user to continue.
 *
 * Note: This does NOT sync wallet - that's handled separately by WalletSetupScreen.
 */
@Composable
fun ProfileSyncScreen(
    syncState: ProfileSyncState,
    isDriverApp: Boolean,
    onComplete: (RestoredProfileData) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        when (syncState) {
            is ProfileSyncState.Idle -> {
                // Shouldn't normally see this - check should start immediately
                SyncCheckingContent()
            }
            is ProfileSyncState.Checking -> {
                SyncCheckingContent()
            }
            is ProfileSyncState.Connecting -> {
                SyncConnectingContent()
            }
            is ProfileSyncState.Syncing -> {
                SyncProgressContent(syncState.dataType, syncState.progress)
            }
            is ProfileSyncState.NoDataFound -> {
                NoDataFoundContent(
                    isDriverApp = isDriverApp,
                    onContinue = { onComplete(RestoredProfileData()) }
                )
            }
            is ProfileSyncState.Complete -> {
                SyncCompleteContent(
                    restoredData = syncState.restoredData,
                    isDriverApp = isDriverApp,
                    onContinue = { onComplete(syncState.restoredData) }
                )
            }
            is ProfileSyncState.Error -> {
                SyncErrorContent(
                    message = syncState.message,
                    retryable = syncState.retryable,
                    onSkip = onSkip
                )
            }
            is ProfileSyncState.Backing -> {
                // Shouldn't happen during import, but handle gracefully
                SyncProgressContent(syncState.dataType, null)
            }
        }
    }
}

@Composable
private fun SyncCheckingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Checking for Data",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Looking for existing Ridestr data\non Nostr relays...",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        CircularProgressIndicator()
    }
}

@Composable
private fun SyncConnectingContent() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CloudSync,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Connecting",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Connecting to Nostr relays...",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        CircularProgressIndicator()
    }
}

@Composable
private fun SyncProgressContent(dataType: String, progress: Float?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CloudDownload,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "Restoring Data",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Syncing $dataType...",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
            )
        } else {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun NoDataFoundContent(
    isDriverApp: Boolean,
    onContinue: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "No Existing Data",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = if (isDriverApp) {
                "No saved vehicles or ride history found.\nYou'll set up your vehicle profile next."
            } else {
                "No saved locations or ride history found.\nYou'll start with a fresh profile."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }
    }
}

@Composable
private fun SyncCompleteContent(
    restoredData: RestoredProfileData,
    isDriverApp: Boolean,
    onContinue: () -> Unit
) {
    // Check for Ridestr-specific data (not wallet - that's handled separately)
    val hasData = restoredData.vehicleCount > 0 ||
            restoredData.savedLocationCount > 0 ||
            restoredData.rideHistoryCount > 0 ||
            restoredData.settingsRestored

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CloudDone,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Text(
            text = "Data Restored!",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        if (hasData) {
            // Show what was restored
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Restored from Nostr:",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Vehicles (driver app)
                    if (restoredData.vehicleCount > 0 && isDriverApp) {
                        RestoredItem(
                            icon = Icons.Default.DriveEta,
                            label = "Vehicles",
                            value = "${restoredData.vehicleCount} vehicle${if (restoredData.vehicleCount > 1) "s" else ""}"
                        )
                    }

                    // Saved locations (rider app)
                    if (restoredData.savedLocationCount > 0 && !isDriverApp) {
                        RestoredItem(
                            icon = Icons.Default.Place,
                            label = "Saved Places",
                            value = "${restoredData.savedLocationCount} location${if (restoredData.savedLocationCount > 1) "s" else ""}"
                        )
                    }

                    // Ride history
                    if (restoredData.rideHistoryCount > 0) {
                        RestoredItem(
                            icon = Icons.Default.History,
                            label = "Ride History",
                            value = "${restoredData.rideHistoryCount} ride${if (restoredData.rideHistoryCount > 1) "s" else ""}"
                        )
                    }

                    // Settings
                    if (restoredData.settingsRestored) {
                        RestoredItem(
                            icon = Icons.Default.Settings,
                            label = "Settings",
                            value = "Restored"
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue")
        }
    }
}

@Composable
private fun RestoredItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SyncErrorContent(
    message: String,
    retryable: Boolean,
    onSkip: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector = Icons.Default.CloudOff,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Text(
            text = "Sync Failed",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Continue without backup")
        }

        Text(
            text = "You can try syncing again later from Settings",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}
