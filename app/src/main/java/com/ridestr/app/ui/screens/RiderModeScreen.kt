package com.ridestr.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ridestr.app.nostr.events.DriverAvailabilityData
import com.ridestr.app.viewmodels.RideStage
import com.ridestr.app.viewmodels.RiderUiState
import com.ridestr.app.viewmodels.RiderViewModel

@Composable
fun RiderModeScreen(
    viewModel: RiderViewModel,
    onSwitchToDriver: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header with mode switch
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Rider Mode",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            TextButton(onClick = onSwitchToDriver) {
                Text("Switch to Driver")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Error message
        uiState.error?.let { error ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    TextButton(onClick = { viewModel.clearError() }) {
                        Text("Dismiss")
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        when (uiState.rideStage) {
            RideStage.IDLE -> {
                // Location input and driver list
                IdleContent(
                    uiState = uiState,
                    onSetPickup = viewModel::setPickupLocation,
                    onSetDestination = viewModel::setDestination,
                    onSelectDriver = viewModel::selectDriver,
                    onClearDriver = viewModel::clearSelectedDriver,
                    onSendOffer = viewModel::sendRideOffer
                )
            }
            RideStage.WAITING_FOR_ACCEPTANCE -> {
                WaitingForAcceptanceContent(
                    uiState = uiState,
                    onCancel = viewModel::cancelOffer
                )
            }
            RideStage.DRIVER_ACCEPTED -> {
                DriverAcceptedContent(
                    uiState = uiState,
                    onConfirm = viewModel::confirmRide,
                    onCancel = viewModel::clearRide
                )
            }
            RideStage.RIDE_CONFIRMED, RideStage.IN_PROGRESS -> {
                RideInProgressContent(
                    uiState = uiState,
                    onComplete = viewModel::clearRide
                )
            }
            RideStage.COMPLETED -> {
                RideCompletedContent(
                    onNewRide = viewModel::clearRide
                )
            }
        }
    }
}

@Composable
private fun IdleContent(
    uiState: RiderUiState,
    onSetPickup: (Double, Double) -> Unit,
    onSetDestination: (Double, Double) -> Unit,
    onSelectDriver: (DriverAvailabilityData) -> Unit,
    onClearDriver: () -> Unit,
    onSendOffer: () -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Location input card
        item {
            LocationInputCard(
                pickupLocation = uiState.pickupLocation,
                destination = uiState.destination,
                onSetPickup = onSetPickup,
                onSetDestination = onSetDestination
            )
        }

        // Route info card (if available)
        if (uiState.routeResult != null || uiState.isCalculatingRoute) {
            item {
                RouteInfoCard(
                    routeResult = uiState.routeResult,
                    fareEstimate = uiState.fareEstimate,
                    isCalculating = uiState.isCalculatingRoute
                )
            }
        }

        // Available drivers section
        item {
            Text(
                text = "Available Drivers (${uiState.availableDrivers.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (uiState.availableDrivers.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Searching for nearby drivers...",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(uiState.availableDrivers) { driver ->
                DriverCard(
                    driver = driver,
                    isSelected = uiState.selectedDriver?.driverPubKey == driver.driverPubKey,
                    onSelect = { onSelectDriver(driver) }
                )
            }
        }

        // Request ride button
        if (uiState.selectedDriver != null && uiState.pickupLocation != null &&
            uiState.destination != null && uiState.fareEstimate != null) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                RequestRideCard(
                    driver = uiState.selectedDriver,
                    fareEstimate = uiState.fareEstimate,
                    isSending = uiState.isSendingOffer,
                    onRequest = onSendOffer,
                    onCancel = onClearDriver
                )
            }
        }
    }
}

@Composable
private fun LocationInputCard(
    pickupLocation: com.ridestr.app.nostr.events.Location?,
    destination: com.ridestr.app.nostr.events.Location?,
    onSetPickup: (Double, Double) -> Unit,
    onSetDestination: (Double, Double) -> Unit
) {
    var pickupLat by remember { mutableStateOf(pickupLocation?.lat?.toString() ?: "38.429719") }
    var pickupLon by remember { mutableStateOf(pickupLocation?.lon?.toString() ?: "-108.827425") }
    var destLat by remember { mutableStateOf(destination?.lat?.toString() ?: "38.4604331") }
    var destLon by remember { mutableStateOf(destination?.lon?.toString() ?: "-108.8817009") }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Trip Details",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Pickup location
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Pickup", style = MaterialTheme.typography.labelMedium)
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = pickupLat,
                    onValueChange = { pickupLat = it },
                    label = { Text("Lat") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = pickupLon,
                    onValueChange = { pickupLon = it },
                    label = { Text("Lon") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Destination
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Destination", style = MaterialTheme.typography.labelMedium)
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = destLat,
                    onValueChange = { destLat = it },
                    label = { Text("Lat") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                OutlinedTextField(
                    value = destLon,
                    onValueChange = { destLon = it },
                    label = { Text("Lon") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = {
                    pickupLat.toDoubleOrNull()?.let { lat ->
                        pickupLon.toDoubleOrNull()?.let { lon ->
                            onSetPickup(lat, lon)
                        }
                    }
                    destLat.toDoubleOrNull()?.let { lat ->
                        destLon.toDoubleOrNull()?.let { lon ->
                            onSetDestination(lat, lon)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Calculate Route")
            }
        }
    }
}

@Composable
private fun RouteInfoCard(
    routeResult: com.ridestr.app.routing.RouteResult?,
    fareEstimate: Double?,
    isCalculating: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        if (isCalculating) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Text("Calculating route...")
            }
        } else if (routeResult != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = routeResult.getFormattedDistance(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Distance",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = routeResult.getFormattedDuration(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Duration",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${fareEstimate?.toInt() ?: 0} sats",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Est. Fare",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

@Composable
private fun DriverCard(
    driver: DriverAvailabilityData,
    isSelected: Boolean,
    onSelect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (isSelected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
        onClick = onSelect
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.DirectionsCar,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = if (isSelected) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Driver ${driver.driverPubKey.take(8)}...",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Location: ${driver.approxLocation.lat}, ${driver.approxLocation.lon}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isSelected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun RequestRideCard(
    driver: DriverAvailabilityData,
    fareEstimate: Double,
    isSending: Boolean,
    onRequest: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Request Ride",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Driver: ${driver.driverPubKey.take(12)}...",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "Fare: ${fareEstimate.toInt()} sats",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = onRequest,
                    enabled = !isSending,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isSending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Send Request")
                    }
                }
            }
        }
    }
}

@Composable
private fun WaitingForAcceptanceContent(
    uiState: RiderUiState,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = uiState.statusMessage,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedButton(onClick = onCancel) {
                Text("Cancel Request")
            }
        }
    }
}

@Composable
private fun DriverAcceptedContent(
    uiState: RiderUiState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Driver Accepted!",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = uiState.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = onConfirm,
                    enabled = !uiState.isConfirmingRide,
                    modifier = Modifier.weight(1f)
                ) {
                    if (uiState.isConfirmingRide) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Confirm Ride")
                    }
                }
            }
        }
    }
}

@Composable
private fun RideInProgressContent(
    uiState: RiderUiState,
    onComplete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.DirectionsCar,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.tertiary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Ride in Progress",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = uiState.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = onComplete) {
                Text("Complete Ride")
            }
        }
    }
}

@Composable
private fun RideCompletedContent(
    onNewRide: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Ride Completed!",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = onNewRide) {
                Text("Book Another Ride")
            }
        }
    }
}
