package com.roadflare.rider.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ridestr.common.settings.DistanceUnit
import com.ridestr.common.ui.components.SettingsActionRow
import com.ridestr.common.ui.components.SettingsNavigationRow
import com.ridestr.common.ui.components.SettingsSwitchRow
import kotlinx.coroutines.launch

/**
 * Settings screen with back navigation (for modal use).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    distanceUnit: DistanceUnit,
    notificationSoundEnabled: Boolean,
    notificationVibrationEnabled: Boolean,
    onSetDistanceUnit: (DistanceUnit) -> Unit,
    onSetNotificationSoundEnabled: (Boolean) -> Unit,
    onSetNotificationVibrationEnabled: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenTiles: () -> Unit,
    onOpenDevOptions: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
        SettingsContent(
            distanceUnit = distanceUnit,
            notificationSoundEnabled = notificationSoundEnabled,
            notificationVibrationEnabled = notificationVibrationEnabled,
            onSetDistanceUnit = onSetDistanceUnit,
            onSetNotificationSoundEnabled = onSetNotificationSoundEnabled,
            onSetNotificationVibrationEnabled = onSetNotificationVibrationEnabled,
            onOpenTiles = onOpenTiles,
            onOpenDevOptions = onOpenDevOptions,
            modifier = Modifier.padding(padding)
        )
    }
}

/**
 * Settings content without Scaffold - for use as a tab in bottom navigation.
 */
@Composable
fun SettingsContent(
    distanceUnit: DistanceUnit,
    notificationSoundEnabled: Boolean,
    notificationVibrationEnabled: Boolean,
    onSetDistanceUnit: (DistanceUnit) -> Unit,
    onSetNotificationSoundEnabled: (Boolean) -> Unit,
    onSetNotificationVibrationEnabled: (Boolean) -> Unit,
    onOpenTiles: () -> Unit,
    onOpenDevOptions: () -> Unit,
    onSyncProfile: (suspend () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    // Sync state
    var isSyncing by remember { mutableStateOf(false) }
    var syncResult by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
            // Distance Units Setting
            SettingsSwitchRow(
                title = "Distance Units",
                description = if (distanceUnit == DistanceUnit.MILES)
                    "Showing distances in miles"
                else
                    "Showing distances in kilometers",
                checked = distanceUnit == DistanceUnit.MILES,
                onCheckedChange = {
                    val newUnit = if (distanceUnit == DistanceUnit.MILES) DistanceUnit.KILOMETERS else DistanceUnit.MILES
                    onSetDistanceUnit(newUnit)
                },
                checkedLabel = "Miles",
                uncheckedLabel = "km"
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Notifications Section Header
            Text(
                text = "Notifications",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            // Notification Sound Setting
            SettingsSwitchRow(
                title = "Sound",
                description = "Play sound for ride updates",
                checked = notificationSoundEnabled,
                onCheckedChange = { enabled -> onSetNotificationSoundEnabled(enabled) }
            )

            // Notification Vibration Setting
            SettingsSwitchRow(
                title = "Vibration",
                description = "Vibrate for ride updates",
                checked = notificationVibrationEnabled,
                onCheckedChange = { enabled -> onSetNotificationVibrationEnabled(enabled) }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Routing Tiles Navigation
            SettingsNavigationRow(
                title = "Routing Tiles",
                description = "Manage offline routing data",
                icon = Icons.Default.Map,
                onClick = onOpenTiles
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Developer Options Navigation
            SettingsNavigationRow(
                title = "Developer Options",
                description = "Debug tools and advanced settings",
                icon = Icons.Default.Code,
                onClick = onOpenDevOptions
            )

            // Profile Sync Section (last item)
            if (onSyncProfile != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = "Data Sync",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                SettingsActionRow(
                    title = "Sync Profile Data",
                    description = if (isSyncing) "Syncing..." else syncResult ?: "Sync saved locations and ride history from Nostr",
                    icon = Icons.Default.CloudSync,
                    isLoading = isSyncing,
                    onClick = {
                        if (!isSyncing) {
                            coroutineScope.launch {
                                isSyncing = true
                                syncResult = null
                                try {
                                    onSyncProfile()
                                    syncResult = "Sync complete"
                                } catch (e: Exception) {
                                    syncResult = "Sync failed: ${e.message}"
                                }
                                isSyncing = false
                            }
                        }
                    }
                )
            }
    }
}
