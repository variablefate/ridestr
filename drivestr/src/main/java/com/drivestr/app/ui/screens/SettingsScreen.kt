package com.drivestr.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.drivestr.app.viewmodels.DriverStage
import com.ridestr.common.settings.DisplayCurrency
import com.ridestr.common.settings.DistanceUnit
import com.ridestr.common.settings.SettingsManager
import kotlinx.coroutines.launch

/**
 * Settings screen with back navigation (for modal use).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsManager: SettingsManager,
    onBack: () -> Unit,
    onOpenTiles: () -> Unit,
    onOpenDevOptions: () -> Unit,
    onOpenWalletSettings: () -> Unit = {},
    modifier: Modifier = Modifier
) {
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
            settingsManager = settingsManager,
            onOpenTiles = onOpenTiles,
            onOpenDevOptions = onOpenDevOptions,
            onOpenWalletSettings = onOpenWalletSettings,
            modifier = Modifier.padding(padding)
        )
    }
}

/**
 * Settings content without Scaffold - for use as a tab in bottom navigation.
 */
@Composable
fun SettingsContent(
    settingsManager: SettingsManager,
    hasMultipleVehicles: Boolean = false,
    driverStage: DriverStage? = null,
    onOpenTiles: () -> Unit,
    onOpenDevOptions: () -> Unit,
    onOpenWalletSettings: () -> Unit = {},
    onSyncProfile: (suspend () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val autoOpenNavigation by settingsManager.autoOpenNavigation.collectAsState()
    val displayCurrency by settingsManager.displayCurrency.collectAsState()
    val distanceUnit by settingsManager.distanceUnit.collectAsState()
    val notificationSoundEnabled by settingsManager.notificationSoundEnabled.collectAsState()
    val notificationVibrationEnabled by settingsManager.notificationVibrationEnabled.collectAsState()
    val alwaysAskVehicle by settingsManager.alwaysAskVehicle.collectAsState()

    // Sync state
    var isSyncing by remember { mutableStateOf(false) }
    var syncResult by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // Block vehicle setting changes during an active ride
    val isRideActive = driverStage in listOf(
        DriverStage.RIDE_ACCEPTED,
        DriverStage.EN_ROUTE_TO_PICKUP,
        DriverStage.ARRIVED_AT_PICKUP,
        DriverStage.IN_RIDE,
        DriverStage.RIDE_COMPLETED
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
            // Display Currency Setting
            SettingsSwitchRow(
                title = "Display Currency",
                description = if (displayCurrency == DisplayCurrency.USD)
                    "Showing fares in US Dollars"
                else
                    "Showing fares in Satoshis",
                checked = displayCurrency == DisplayCurrency.USD,
                onCheckedChange = { settingsManager.toggleDisplayCurrency() },
                checkedLabel = "USD",
                uncheckedLabel = "Sats"
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Distance Units Setting
            SettingsSwitchRow(
                title = "Distance Units",
                description = if (distanceUnit == DistanceUnit.MILES)
                    "Showing distances in miles"
                else
                    "Showing distances in kilometers",
                checked = distanceUnit == DistanceUnit.MILES,
                onCheckedChange = { settingsManager.toggleDistanceUnit() },
                checkedLabel = "Miles",
                uncheckedLabel = "km"
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Auto-open Navigation Setting
            SettingsSwitchRow(
                title = "Auto-open Navigation",
                description = "Automatically open your maps app when heading to pickup or destination",
                checked = autoOpenNavigation,
                onCheckedChange = { settingsManager.setAutoOpenNavigation(it) }
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
                description = "Play sound for ride requests and updates",
                checked = notificationSoundEnabled,
                onCheckedChange = { settingsManager.setNotificationSoundEnabled(it) }
            )

            // Notification Vibration Setting
            SettingsSwitchRow(
                title = "Vibration",
                description = "Vibrate for ride requests and updates",
                checked = notificationVibrationEnabled,
                onCheckedChange = { settingsManager.setNotificationVibrationEnabled(it) }
            )

            // Vehicle Section - Only show if user has multiple vehicles
            if (hasMultipleVehicles) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = "Vehicles",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                SettingsSwitchRow(
                    title = "Ask which vehicle when going online",
                    description = when {
                        isRideActive -> "Cannot change during active ride"
                        alwaysAskVehicle -> "You'll choose which vehicle to use each time"
                        else -> "Your primary vehicle will be used automatically"
                    },
                    checked = alwaysAskVehicle,
                    onCheckedChange = { settingsManager.setAlwaysAskVehicle(it) },
                    enabled = !isRideActive
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Routing Tiles Navigation
            SettingsNavigationRow(
                title = "Routing Tiles",
                description = "Manage offline routing data for your area",
                icon = Icons.Default.Map,
                onClick = onOpenTiles
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Wallet Settings Navigation
            SettingsNavigationRow(
                title = "Wallet",
                description = "Manage Cashu wallet and sync",
                icon = Icons.Default.AccountBalanceWallet,
                onClick = onOpenWalletSettings
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
                    description = if (isSyncing) "Syncing..." else syncResult ?: "Sync vehicles and ride history from Nostr",
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

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    checkedLabel: String? = null,
    uncheckedLabel: String? = null,
    enabled: Boolean = true
) {
    val contentAlpha = if (enabled) 1f else 0.5f

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (uncheckedLabel != null) {
                Text(
                    text = uncheckedLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = (if (!checked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = contentAlpha)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled
            )
            if (checkedLabel != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = checkedLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = (if (checked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = contentAlpha)
                )
            }
        }
    }
}

@Composable
private fun SettingsNavigationRow(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "Navigate",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = !isLoading, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp
            )
        }
    }
}
