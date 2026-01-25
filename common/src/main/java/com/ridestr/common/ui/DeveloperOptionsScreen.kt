package com.ridestr.common.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ridestr.common.settings.SettingsManager

/**
 * Developer Options screen with debug tools and advanced settings.
 * This is a full-screen replacement for the collapsible section in Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperOptionsScreen(
    settingsManager: SettingsManager,
    isDriverApp: Boolean,
    onOpenRelaySettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val useGeocodingSearch by settingsManager.useGeocodingSearch.collectAsState()

    // Driver-specific settings
    val useManualDriverLocation by settingsManager.useManualDriverLocation.collectAsState()
    val manualDriverLat by settingsManager.manualDriverLat.collectAsState()
    val manualDriverLon by settingsManager.manualDriverLon.collectAsState()

    // Manual location input state (driver only)
    var latInput by remember(manualDriverLat) { mutableStateOf(manualDriverLat.toString()) }
    var lonInput by remember(manualDriverLon) { mutableStateOf(manualDriverLon.toString()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Developer Options") },
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
            // Geocoding Search Toggle
            SettingsSwitchRow(
                title = "Geocoding Search",
                description = if (useGeocodingSearch)
                    "Using address search for locations"
                else
                    "Using manual coordinate entry",
                checked = useGeocodingSearch,
                onCheckedChange = { settingsManager.toggleUseGeocodingSearch() }
            )

            // Driver-specific: Manual Driver Location
            if (isDriverApp) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                SettingsSwitchRow(
                    title = "Manual Driver Location",
                    description = if (useManualDriverLocation)
                        "Using manually set coordinates"
                    else
                        "Using GPS for driver location",
                    checked = useManualDriverLocation,
                    onCheckedChange = { settingsManager.setUseManualDriverLocation(it) }
                )

                // Manual location inputs (shown when enabled)
                if (useManualDriverLocation) {
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = latInput,
                                onValueChange = { latInput = it },
                                label = { Text("Latitude") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                            )
                            OutlinedTextField(
                                value = lonInput,
                                onValueChange = { lonInput = it },
                                label = { Text("Longitude") },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                val lat = latInput.toDoubleOrNull()
                                val lon = lonInput.toDoubleOrNull()
                                if (lat != null && lon != null) {
                                    settingsManager.setManualDriverLocation(lat, lon)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = latInput.toDoubleOrNull() != null && lonInput.toDoubleOrNull() != null
                        ) {
                            Icon(Icons.Filled.LocationOn, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Apply Location")
                        }

                        Text(
                            text = "Tip: Las Vegas = 36.1699, -115.1398",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Relay Settings Navigation
            SettingsNavigationRow(
                title = "Relay Settings",
                description = "Manage relay connections and configuration",
                icon = Icons.Filled.Cloud,
                onClick = onOpenRelaySettings
            )

            // Bottom padding for scroll
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
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
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
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
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
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
}
