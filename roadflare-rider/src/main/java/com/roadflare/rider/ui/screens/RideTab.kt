package com.roadflare.rider.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.ridestr.common.nostr.events.Location
import com.roadflare.rider.state.RideStage
import com.ridestr.common.ui.LocationSearchField
import com.roadflare.rider.viewmodels.ChatMessage
import com.roadflare.rider.viewmodels.DriverInfo
import com.roadflare.rider.viewmodels.RideSession
import com.roadflare.rider.viewmodels.RiderViewModel
import com.roadflare.rider.viewmodels.RouteResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * RoadFlare tab — the main ride request entry point.
 *
 * IDLE state shows the full geocoder with autocomplete, fare estimate,
 * and "Send RoadFlare" button. Other states show ride progress.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoadFlareTab(
    viewModel: RiderViewModel,
    modifier: Modifier = Modifier
) {
    val rideStage by viewModel.rideStage.collectAsState()
    val currentRide by viewModel.currentRide.collectAsState()
    val chatMessages by viewModel.chatMessages.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        when (rideStage) {
            RideStage.IDLE -> IdleContent(viewModel = viewModel)
            RideStage.REQUESTING -> RequestingContent(
                ride = currentRide,
                onCancel = { viewModel.rideSessionManager.cancelRide() }
            )
            RideStage.CHOOSING_DRIVER -> ChoosingDriverContent(
                acceptedDrivers = viewModel.rideSessionManager.getAcceptedDrivers(),
                fareEstimateUsd = currentRide?.fareEstimateUsd,
                fareEstimateSats = currentRide?.fareEstimateSats,
                onSelectDriver = { driver ->
                    viewModel.rideSessionManager.confirmDriver(driver)
                },
                onCancel = { viewModel.rideSessionManager.cancelRide() }
            )
            RideStage.MATCHED,
            RideStage.DRIVER_EN_ROUTE,
            RideStage.DRIVER_ARRIVED -> MatchedContent(
                ride = currentRide,
                stage = rideStage,
                onCancel = { viewModel.rideSessionManager.cancelRide() }
            )
            RideStage.IN_RIDE -> InRideContent(
                ride = currentRide,
                messages = chatMessages
            )
            RideStage.COMPLETED -> CompletedContent(
                ride = currentRide,
                onDone = { viewModel.rideSessionManager.clearRide() }
            )
            RideStage.CANCELLED -> CancelledContent(
                onDone = { viewModel.rideSessionManager.clearRide() }
            )
        }
    }
}

@Composable
private fun IdleContent(
    viewModel: RiderViewModel
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Geocoding state from ViewModel
    val pickupLocation by viewModel.pickupLocation.collectAsState()
    val destLocation by viewModel.destLocation.collectAsState()
    val pickupSearchResults by viewModel.pickupSearchResults.collectAsState()
    val destSearchResults by viewModel.destSearchResults.collectAsState()
    val isSearchingPickup by viewModel.isSearchingPickup.collectAsState()
    val isSearchingDest by viewModel.isSearchingDest.collectAsState()
    val fareEstimate by viewModel.fareEstimate.collectAsState()
    val isCalculatingFare by viewModel.isCalculatingFare.collectAsState()
    val drivers by viewModel.drivers.collectAsState()

    // Local text state for the search fields
    var pickupQuery by remember { mutableStateOf("") }
    var destQuery by remember { mutableStateOf("") }

    // GPS location permission
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            scope.launch {
                try {
                    val client = LocationServices.getFusedLocationProviderClient(context)
                    val location = client.getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY, null
                    ).await()
                    if (location != null) {
                        viewModel.setPickupFromGps(location.latitude, location.longitude)
                        pickupQuery = ""
                    }
                } catch (_: Exception) { }
            }
        }
    }

    val hasLocationPermission = remember {
        ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Send a RoadFlare",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Text(
            text = "Set your pickup and destination, then light the flare to request a ride from your driver network.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Pickup location search
        LocationSearchField(
            value = pickupQuery,
            onValueChange = { newValue ->
                pickupQuery = newValue
                if (newValue.isEmpty()) viewModel.clearPickup()
            },
            selectedLocation = pickupLocation,
            onLocationSelected = { result ->
                viewModel.selectPickupFromSearch(result)
                pickupQuery = ""
            },
            searchResults = pickupSearchResults,
            isSearching = isSearchingPickup,
            onSearch = { viewModel.searchPickupLocations(it) },
            placeholder = "Search pickup address...",
            label = "Pickup",
            showMyLocation = true,
            onUseMyLocation = {
                if (hasLocationPermission) {
                    scope.launch {
                        try {
                            val client = LocationServices.getFusedLocationProviderClient(context)
                            val location = client.getCurrentLocation(
                                Priority.PRIORITY_HIGH_ACCURACY, null
                            ).await()
                            if (location != null) {
                                viewModel.setPickupFromGps(location.latitude, location.longitude)
                                pickupQuery = ""
                            }
                        } catch (_: Exception) { }
                    }
                } else {
                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        // Swap button
        if (pickupLocation != null || destLocation != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                IconButton(onClick = {
                    val tempPickup = pickupLocation
                    val tempDest = destLocation
                    if (tempDest != null) {
                        viewModel.clearPickup()
                        viewModel.clearDest()
                        // Swap by setting each to the other's value
                        viewModel.setPickupFromGps(tempDest.lat, tempDest.lon)
                    }
                    // Note: full swap would need setDestFromGps which we could add
                }) {
                    Icon(
                        Icons.Default.SwapVert,
                        contentDescription = "Swap pickup and destination"
                    )
                }
            }
        }

        // Destination location search
        LocationSearchField(
            value = destQuery,
            onValueChange = { newValue ->
                destQuery = newValue
                if (newValue.isEmpty()) viewModel.clearDest()
            },
            selectedLocation = destLocation,
            onLocationSelected = { result ->
                viewModel.selectDestFromSearch(result)
                destQuery = ""
            },
            searchResults = destSearchResults,
            isSearching = isSearchingDest,
            onSearch = { viewModel.searchDestLocations(it) },
            placeholder = "Search destination...",
            label = "Destination",
            modifier = Modifier.fillMaxWidth()
        )

        // Route estimate display (fare hidden until driver list)
        fareEstimate?.let { route ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Route Estimate",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${String.format("%.1f", route.distanceMiles)} mi",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "Distance",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${route.durationMinutes} min",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "Duration",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }

        if (isCalculatingFare) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Calculating fare...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // Driver count info
        if (drivers.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = "No drivers in your network yet. Add drivers from the Driver Network tab first.",
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        // Send RoadFlare button
        Button(
            onClick = { viewModel.sendRoadflare() },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = pickupLocation != null && destLocation != null
                    && drivers.isNotEmpty() && !isCalculatingFare
        ) {
            Icon(Icons.Default.Flare, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Send RoadFlare", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun RequestingContent(
    ride: RideSession?,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Contacting drivers...",
            style = MaterialTheme.typography.titleLarge
        )
        ride?.let {
            Text(
                text = "${it.pendingDrivers.size} drivers in your network",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        OutlinedButton(onClick = onCancel) {
            Icon(Icons.Default.Close, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Cancel")
        }
    }
}

@Composable
private fun ChoosingDriverContent(
    acceptedDrivers: List<DriverInfo>,
    fareEstimateUsd: Double?,
    fareEstimateSats: Long?,
    onSelectDriver: (DriverInfo) -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Choose a Driver",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "${acceptedDrivers.size} driver(s) accepted your request",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Trip fare card
        fareEstimateUsd?.let { fare ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Trip Fare",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "$${String.format("%.2f", fare)}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    fareEstimateSats?.let { sats ->
                        Text(
                            text = "~${String.format("%,d", sats)} sats",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        }

        acceptedDrivers.forEach { driver ->
            Card(
                onClick = { onSelectDriver(driver) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = driver.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    driver.vehicleDescription?.let {
                        Text(text = it, style = MaterialTheme.typography.bodyMedium)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        driver.etaMinutes?.let {
                            Text(
                                text = "$it min away",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        driver.distanceMiles?.let {
                            Text(
                                text = String.format("%.1f mi", it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cancel Request")
        }
    }
}

@Composable
private fun MatchedContent(
    ride: RideSession?,
    stage: RideStage,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        val statusText = when (stage) {
            RideStage.MATCHED -> "Ride confirmed!"
            RideStage.DRIVER_EN_ROUTE -> "Driver is on the way"
            RideStage.DRIVER_ARRIVED -> "Driver has arrived"
            else -> ""
        }

        Text(
            text = statusText,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        ride?.selectedDriver?.let { driver ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = driver.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    driver.vehicleDescription?.let {
                        Text(text = it, style = MaterialTheme.typography.bodyMedium)
                    }
                    driver.etaMinutes?.let {
                        Text(
                            text = "ETA: $it minutes",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        ride?.let {
            Text(
                text = "Fare: $${String.format("%.2f", it.fareEstimateUsd)}",
                style = MaterialTheme.typography.titleMedium
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("Cancel Ride")
        }
    }
}

@Composable
private fun InRideContent(
    ride: RideSession?,
    messages: List<ChatMessage>
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Ride in Progress",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        ride?.selectedDriver?.let { driver ->
            Text(
                text = "With ${driver.displayName}",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (messages.isNotEmpty()) {
            Text(
                text = "${messages.size} messages",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun CompletedContent(
    ride: RideSession?,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Ride Complete",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        ride?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Total: $${String.format("%.2f", it.fareEstimateUsd)}",
                style = MaterialTheme.typography.titleLarge
            )
        }
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onDone) {
            Text("Done")
        }
    }
}

@Composable
private fun CancelledContent(
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Ride Cancelled",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(32.dp))
        Button(onClick = onDone) {
            Text("Done")
        }
    }
}
