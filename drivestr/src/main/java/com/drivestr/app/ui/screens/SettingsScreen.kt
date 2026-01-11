package com.drivestr.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ridestr.common.settings.DisplayCurrency
import com.ridestr.common.settings.DistanceUnit
import com.ridestr.common.settings.SettingsManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsManager: SettingsManager,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val autoOpenNavigation by settingsManager.autoOpenNavigation.collectAsState()
    val displayCurrency by settingsManager.displayCurrency.collectAsState()
    val distanceUnit by settingsManager.distanceUnit.collectAsState()

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
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
    uncheckedLabel: String? = null
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (uncheckedLabel != null) {
                Text(
                    text = uncheckedLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!checked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
            if (checkedLabel != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = checkedLabel,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (checked) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
