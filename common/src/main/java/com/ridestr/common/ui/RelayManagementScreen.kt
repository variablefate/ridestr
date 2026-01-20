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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ridestr.common.settings.SettingsManager

/**
 * Standalone relay management screen accessible from:
 * - Settings menu
 * - RelaySignalIndicator (connectivity icon)
 * - AccountBottomSheet relay badge
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelayManagementScreen(
    settingsManager: SettingsManager,
    connectedCount: Int,
    totalRelays: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Relay management state - derive effectiveRelays reactively from customRelays
    val customRelays by settingsManager.customRelays.collectAsState()
    val effectiveRelays = remember(customRelays) {
        if (customRelays.isEmpty()) SettingsManager.DEFAULT_RELAYS else customRelays
    }
    var newRelayInput by remember { mutableStateOf("") }
    val maxRelays = 10

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Relay Settings") },
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
            // Connection Status Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        connectedCount == 0 -> MaterialTheme.colorScheme.errorContainer
                        connectedCount < totalRelays -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.primaryContainer
                    }
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Connection Status",
                            style = MaterialTheme.typography.titleMedium,
                            color = when {
                                connectedCount == 0 -> MaterialTheme.colorScheme.onErrorContainer
                                connectedCount < totalRelays -> MaterialTheme.colorScheme.onSecondaryContainer
                                else -> MaterialTheme.colorScheme.onPrimaryContainer
                            }
                        )
                        Text(
                            text = "$connectedCount of $totalRelays relays connected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                connectedCount == 0 -> MaterialTheme.colorScheme.onErrorContainer
                                connectedCount < totalRelays -> MaterialTheme.colorScheme.onSecondaryContainer
                                else -> MaterialTheme.colorScheme.onPrimaryContainer
                            }.copy(alpha = 0.8f)
                        )
                    }
                    RelaySignalIndicator(
                        connectedCount = connectedCount,
                        totalRelays = totalRelays
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Relay Info
            Text(
                text = "About Relays",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Text(
                text = "Relays are servers that store and forward your ride data. Using multiple relays improves reliability and ensures your ride requests reach more drivers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            HorizontalDivider()

            // Relay Management Section
            Text(
                text = "Your Relays",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )

            Text(
                text = if (customRelays.isEmpty()) "Using default relays" else "Using custom relays",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Current relays list
            effectiveRelays.forEach { relay ->
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Cloud,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = relay.removePrefix("wss://"),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        IconButton(
                            onClick = { settingsManager.removeRelay(relay) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Remove",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Add new relay
            Text(
                text = "Add Relay",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newRelayInput,
                    onValueChange = { newRelayInput = it },
                    label = { Text("relay.example.com") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    leadingIcon = {
                        Text(
                            text = "wss://",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
                Button(
                    onClick = {
                        settingsManager.addRelay(newRelayInput)
                        newRelayInput = ""
                    },
                    enabled = newRelayInput.isNotBlank() && effectiveRelays.size < maxRelays
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add")
                }
            }

            // Relay count hint
            Text(
                text = "${effectiveRelays.size}/$maxRelays relays",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )

            // Reset to defaults button
            if (customRelays.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(
                    onClick = { settingsManager.resetRelaysToDefault() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reset to Default Relays")
                }
            }

            // Bottom padding for scroll
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
