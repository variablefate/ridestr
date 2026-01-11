package com.ridestr.rider.ui.screens

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.ridestr.common.nostr.events.DriverAvailabilityData
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.events.RideshareChatData
import com.ridestr.common.nostr.events.UserProfile
import com.ridestr.common.routing.RouteResult
import com.ridestr.common.ui.ChatBottomSheet
import com.ridestr.common.ui.ChatButton
import com.ridestr.rider.viewmodels.RideStage
import com.ridestr.rider.viewmodels.RiderUiState
import com.ridestr.rider.viewmodels.RiderViewModel

@Composable
fun RiderModeScreen(
    viewModel: RiderViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var showChatSheet by remember { mutableStateOf(false) }

    // Chat bottom sheet
    ChatBottomSheet(
        showSheet = showChatSheet,
        onDismiss = { showChatSheet = false },
        messages = uiState.chatMessages,
        myPubKey = uiState.myPubKey,
        otherPartyName = "Driver",
        isSending = uiState.isSendingMessage,
        onSendMessage = { message ->
            viewModel.sendChatMessage(message)
        }
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Text(
            text = "Rider Mode",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

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
                    onSendOffer = viewModel::sendRideOffer,
                    onToggleExpandedSearch = viewModel::toggleExpandedSearch
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
                    onCancel = viewModel::clearRide,
                    onOpenChat = { showChatSheet = true },
                    chatMessageCount = uiState.chatMessages.size
                )
            }
            RideStage.RIDE_CONFIRMED -> {
                DriverOnTheWayContent(
                    uiState = uiState,
                    onOpenChat = { showChatSheet = true },
                    onCancel = { viewModel.clearRide() },
                    chatMessageCount = uiState.chatMessages.size
                )
            }
            RideStage.IN_PROGRESS -> {
                RideInProgressContent(
                    uiState = uiState,
                    onOpenChat = { showChatSheet = true },
                    onCancelRide = viewModel::clearRide,
                    chatMessageCount = uiState.chatMessages.size
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
    onSendOffer: () -> Unit,
    onToggleExpandedSearch: () -> Unit
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

        // Only show driver list after route is calculated
        if (uiState.routeResult != null) {
            // Available drivers section header with expand toggle
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Available Drivers (${uiState.availableDrivers.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    FilterChip(
                        selected = uiState.expandedSearch,
                        onClick = onToggleExpandedSearch,
                        label = {
                            Text(if (uiState.expandedSearch) "20+ mi" else "Nearby")
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (uiState.expandedSearch)
                                    Icons.Default.ZoomOutMap
                                else
                                    Icons.Default.NearMe,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                }
            }

            if (uiState.availableDrivers.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Searching for nearby drivers...",
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!uiState.expandedSearch) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Tap \"20+ mi\" to expand search area",
                                    style = MaterialTheme.typography.bodySmall,
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            } else {
                items(uiState.availableDrivers) { driver ->
                    DriverCard(
                        driver = driver,
                        profile = uiState.driverProfiles[driver.driverPubKey],
                        isSelected = uiState.selectedDriver?.driverPubKey == driver.driverPubKey,
                        onSelect = { onSelectDriver(driver) }
                    )
                }
            }
        }

        // Request ride button
        if (uiState.selectedDriver != null && uiState.pickupLocation != null &&
            uiState.destination != null && uiState.fareEstimate != null) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                RequestRideCard(
                    driver = uiState.selectedDriver,
                    driverProfile = uiState.driverProfiles[uiState.selectedDriver.driverPubKey],
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
    pickupLocation: Location?,
    destination: Location?,
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
                    Icons.Default.MyLocation,
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
    routeResult: RouteResult?,
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
    profile: UserProfile?,
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
                    text = profile?.bestName() ?: "Driver ${driver.driverPubKey.take(8)}...",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                // Show car info if available
                profile?.carDescription()?.let { car ->
                    Text(
                        text = car,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = "Near: ${String.format("%.4f", driver.approxLocation.lat)}, ${String.format("%.4f", driver.approxLocation.lon)}",
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
    driverProfile: UserProfile?,
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
                text = "Driver: ${driverProfile?.bestName() ?: driver.driverPubKey.take(12) + "..."}",
                style = MaterialTheme.typography.bodyMedium
            )
            driverProfile?.carDescription()?.let { car ->
                Text(
                    text = "Vehicle: $car",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
    // Calculate progress based on timeout
    val startTime = uiState.acceptanceTimeoutStartMs
    val duration = uiState.acceptanceTimeoutDurationMs

    // Animated progress state
    var progress by remember { mutableFloatStateOf(0f) }
    var remainingSeconds by remember { mutableIntStateOf((duration / 1000).toInt()) }

    // Update progress every 100ms
    LaunchedEffect(startTime) {
        if (startTime != null) {
            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                remainingSeconds = ((duration - elapsed) / 1000).toInt().coerceAtLeast(0)
                delay(100)
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            // Circular countdown timer in top-right corner
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(48.dp)
            ) {
                val primaryColor = MaterialTheme.colorScheme.primary
                val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

                Canvas(modifier = Modifier.fillMaxSize()) {
                    val strokeWidth = 4.dp.toPx()
                    val radius = (size.minDimension - strokeWidth) / 2
                    val center = Offset(size.width / 2, size.height / 2)

                    // Background circle
                    drawCircle(
                        color = surfaceVariant,
                        radius = radius,
                        center = center,
                        style = Stroke(width = strokeWidth)
                    )

                    // Progress arc (fills clockwise as time runs out)
                    drawArc(
                        color = primaryColor,
                        startAngle = -90f,
                        sweepAngle = 360f * progress,
                        useCenter = false,
                        topLeft = Offset(
                            center.x - radius,
                            center.y - radius
                        ),
                        size = Size(radius * 2, radius * 2),
                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                    )
                }

                // Seconds remaining in center
                Text(
                    text = "$remainingSeconds",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // Main content centered
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
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
}

@Composable
private fun DriverAcceptedContent(
    uiState: RiderUiState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onOpenChat: () -> Unit,
    chatMessageCount: Int
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
                text = "Driver is on the way to pick you up",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            // Display pickup PIN prominently
            uiState.pickupPin?.let { pin ->
                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Pin,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Your Pickup PIN",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = pin,
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            letterSpacing = 8.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tell this PIN to your driver at pickup",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Chat button
            OutlinedButton(
                onClick = onOpenChat,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Chat,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Chat with Driver")
                if (chatMessageCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Badge {
                        Text(chatMessageCount.toString())
                    }
                }
            }

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
private fun DriverOnTheWayContent(
    uiState: RiderUiState,
    onOpenChat: () -> Unit,
    onCancel: () -> Unit,
    chatMessageCount: Int
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
                Icons.Default.DirectionsCar,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Driver is on the way",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Your driver is heading to pick you up",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            // Display pickup PIN prominently
            uiState.pickupPin?.let { pin ->
                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Pin,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Your Pickup PIN",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = pin,
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            letterSpacing = 8.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tell this PIN to your driver at pickup",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Chat button
            OutlinedButton(
                onClick = onOpenChat,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Chat,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Chat with Driver")
                if (chatMessageCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Badge {
                        Text(chatMessageCount.toString())
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Cancel button
            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Cancel Ride")
            }
        }
    }
}

@Composable
private fun RideInProgressContent(
    uiState: RiderUiState,
    onOpenChat: () -> Unit,
    onCancelRide: () -> Unit,
    chatMessageCount: Int
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

            // Show PIN from state (rider generated)
            uiState.pickupPin?.let { pin ->
                Spacer(modifier = Modifier.height(16.dp))

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Pin,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Your PIN: ",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = pin,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                letterSpacing = 4.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Chat button
            OutlinedButton(
                onClick = onOpenChat,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Chat,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Chat with Driver")
                if (chatMessageCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Badge { Text(chatMessageCount.toString()) }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Driver will complete the ride when you arrive at your destination",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Cancel button (for emergencies/testing)
            TextButton(
                onClick = onCancelRide,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cancel Ride")
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
