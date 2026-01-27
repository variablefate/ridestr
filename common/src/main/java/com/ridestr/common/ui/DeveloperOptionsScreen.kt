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
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.RideshareEventKinds
import com.ridestr.common.settings.SettingsManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Developer Options screen with debug tools and advanced settings.
 * This is a full-screen replacement for the collapsible section in Settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeveloperOptionsScreen(
    settingsManager: SettingsManager,
    isDriverApp: Boolean,
    nostrService: NostrService? = null,
    onOpenRelaySettings: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val scope = rememberCoroutineScope()
    val useGeocodingSearch by settingsManager.useGeocodingSearch.collectAsState()

    // RoadFlare debug settings
    val ignoreFollowNotifications by settingsManager.ignoreFollowNotifications.collectAsState()

    // RoadFlare event inspector state
    data class EventTypeState(
        val kind: Int,
        val name: String,
        val count: Int? = null,
        val isChecking: Boolean = false,
        val isDeleting: Boolean = false
    )
    var eventStates by remember { mutableStateOf(mapOf<Int, EventTypeState>()) }
    var inspectorMessage by remember { mutableStateOf<String?>(null) }

    // Legacy cleanup state (kept for backwards compat)
    var followNotifyCount by remember { mutableStateOf<Int?>(null) }
    var isCheckingFollowNotify by remember { mutableStateOf(false) }
    var isDeletingFollowNotify by remember { mutableStateOf(false) }
    var cleanupMessage by remember { mutableStateOf<String?>(null) }

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

            // RoadFlare Debug Section
            if (nostrService != null) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                Text(
                    text = "RoadFlare Debug",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )

                // Toggle to ignore follow notifications (for testing p-tag queries)
                if (isDriverApp) {
                    SettingsSwitchRow(
                        title = "Ignore Follow Notifications",
                        description = if (ignoreFollowNotifications)
                            "Kind 3187 notifications ignored - using p-tag query only"
                        else
                            "Processing Kind 3187 notifications normally",
                        checked = ignoreFollowNotifications,
                        onCheckedChange = { settingsManager.setIgnoreFollowNotifications(it) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                }

                Text(
                    text = "RoadFlare Event Inspector",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                Text(
                    text = "Query and delete RoadFlare events for testing. Events are fetched from connected relays.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // Event type cards
                val eventTypes = listOf(
                    Triple(RideshareEventKinds.ROADFLARE_FOLLOWED_DRIVERS, "30011 - Followed Drivers", "Rider's list of favorite drivers"),
                    Triple(RideshareEventKinds.ROADFLARE_DRIVER_STATE, "30012 - Driver State", "Driver's RoadFlare keypair + followers"),
                    Triple(RideshareEventKinds.ROADFLARE_LOCATION, "30014 - Location", "Driver's encrypted location broadcasts"),
                    Triple(RideshareEventKinds.ROADFLARE_KEY_SHARE, "3186 - Key Share", "Driver → Rider key distribution"),
                    Triple(RideshareEventKinds.ROADFLARE_FOLLOW_NOTIFY, "3187 - Follow Notify", "Rider → Driver follow notification"),
                    Triple(RideshareEventKinds.ROADFLARE_KEY_ACK, "3188 - Key Ack", "Rider → Driver key acknowledgement")
                )

                eventTypes.forEach { (kind, name, description) ->
                    val state = eventStates[kind]
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (state?.count != null) {
                                        Text(
                                            text = "Found: ${state.count} events",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (state.count > 0) MaterialTheme.colorScheme.primary
                                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Check button
                                OutlinedButton(
                                    onClick = {
                                        eventStates = eventStates + (kind to (state?.copy(isChecking = true) ?: EventTypeState(kind, name, isChecking = true)))
                                        scope.launch {
                                            val count = nostrService.countOwnEventsOfKind(kind)
                                            eventStates = eventStates + (kind to EventTypeState(kind, name, count = count, isChecking = false))
                                        }
                                    },
                                    enabled = state?.isChecking != true && state?.isDeleting != true,
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    if (state?.isChecking == true) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Check", style = MaterialTheme.typography.labelSmall)
                                }

                                // Delete button
                                Button(
                                    onClick = {
                                        eventStates = eventStates + (kind to (state?.copy(isDeleting = true) ?: EventTypeState(kind, name, isDeleting = true)))
                                        scope.launch {
                                            val deleted = nostrService.deleteOwnEventsOfKind(kind)
                                            inspectorMessage = "Deleted $deleted Kind $kind events"
                                            eventStates = eventStates + (kind to EventTypeState(kind, name, count = 0, isDeleting = false))
                                        }
                                    },
                                    enabled = state?.isChecking != true && state?.isDeleting != true && (state?.count == null || state.count > 0),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    if (state?.isDeleting == true) {
                                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onError)
                                    } else {
                                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Delete", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }

                // Check All / Delete All buttons
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                eventTypes.forEach { (kind, name, _) ->
                                    eventStates = eventStates + (kind to EventTypeState(kind, name, isChecking = true))
                                }
                                eventTypes.forEach { (kind, name, _) ->
                                    val count = nostrService.countOwnEventsOfKind(kind)
                                    eventStates = eventStates + (kind to EventTypeState(kind, name, count = count))
                                }
                                inspectorMessage = "Checked all event types"
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Checklist, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Check All")
                    }

                    Button(
                        onClick = {
                            scope.launch {
                                var totalDeleted = 0
                                eventTypes.forEach { (kind, name, _) ->
                                    eventStates = eventStates + (kind to EventTypeState(kind, name, isDeleting = true))
                                    val deleted = nostrService.deleteOwnEventsOfKind(kind)
                                    totalDeleted += deleted
                                    eventStates = eventStates + (kind to EventTypeState(kind, name, count = 0))
                                }
                                inspectorMessage = "Deleted $totalDeleted total RoadFlare events"
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.DeleteForever, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Delete All")
                    }
                }

                // Status message
                inspectorMessage?.let { message ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = message, style = MaterialTheme.typography.bodySmall)
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = { inspectorMessage = null },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Dismiss", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }

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
