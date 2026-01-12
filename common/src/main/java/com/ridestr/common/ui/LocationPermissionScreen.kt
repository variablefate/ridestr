package com.ridestr.common.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

/**
 * Onboarding screen for requesting location permission.
 *
 * Shows a friendly explanation of why location is needed,
 * then prompts the user to grant permission.
 *
 * @param isDriverApp Whether this is the driver app (affects messaging)
 * @param onPermissionGranted Called when location permission is granted
 * @param onSkip Called if user skips (optional - can be null to hide skip)
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun LocationPermissionScreen(
    isDriverApp: Boolean,
    onPermissionGranted: () -> Unit,
    onSkip: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Check if permission is already granted
    val hasPermission = remember {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    // If already granted, call callback immediately
    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            onPermissionGranted()
        }
    }

    // Permission state using Accompanist
    val locationPermissionsState = rememberMultiplePermissionsState(
        permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseLocationGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        if (fineLocationGranted || coarseLocationGranted) {
            onPermissionGranted()
        }
    }

    // Check if all permissions are granted
    val allPermissionsGranted = locationPermissionsState.allPermissionsGranted

    // If permissions just became granted, call callback
    LaunchedEffect(allPermissionsGranted) {
        if (allPermissionsGranted) {
            onPermissionGranted()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Icon
        Icon(
            imageVector = Icons.Default.Place,
            contentDescription = null,
            modifier = Modifier.size(100.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Title
        Text(
            text = "Enable Location",
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Description based on app type
        Text(
            text = if (isDriverApp) {
                "To receive ride requests nearby and navigate to pickups, Drivestr needs access to your location."
            } else {
                "To find drivers near you and provide accurate pickup locations, Ridestr needs access to your location."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Feature list
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                FeatureRow(
                    icon = Icons.Default.LocationOn,
                    text = if (isDriverApp) "See ride requests in your area" else "Set your pickup location"
                )
                FeatureRow(
                    icon = Icons.Default.Place,
                    text = if (isDriverApp) "Navigate to pickup and drop-off" else "Track your driver in real-time"
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Error message if denied
        if (locationPermissionsState.shouldShowRationale) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = "Location permission is required for core functionality. Please grant permission to continue.",
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Grant permission button
        Button(
            onClick = {
                locationPermissionsState.launchMultiplePermissionRequest()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (locationPermissionsState.shouldShowRationale) "Try Again" else "Enable Location")
        }

        // Skip option (if provided)
        onSkip?.let { skip ->
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = skip) {
                Text("Skip for now")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Privacy note
        Text(
            text = "Your location is only used while the app is active. We never store or share your location data.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun FeatureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
