package com.drivestr.app.ui.screens

import android.content.Intent
import android.net.Uri
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drivestr.app.viewmodels.DriverStage
import com.drivestr.app.viewmodels.DriverUiState
import com.drivestr.app.viewmodels.DriverViewModel
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.events.RideOfferData
import com.ridestr.common.nostr.events.RideshareChatData
import com.ridestr.common.ui.ChatBottomSheet
import com.ridestr.common.ui.SlideToConfirm

@Composable
fun DriverModeScreen(
    viewModel: DriverViewModel,
    autoOpenNavigation: Boolean = true,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var showChatSheet by remember { mutableStateOf(false) }

    // Demo location (would come from GPS in real app)
    val demoLocation = remember { Location(lat = 38.429719, lon = -108.827425) }

    // Chat bottom sheet
    ChatBottomSheet(
        showSheet = showChatSheet,
        onDismiss = { showChatSheet = false },
        messages = uiState.chatMessages,
        myPubKey = uiState.myPubKey,
        otherPartyName = "Rider",
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
        Text(
            text = "Driver Mode",
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

        // Main content based on driver stage
        when (uiState.stage) {
            DriverStage.OFFLINE -> {
                OfflineContent(
                    statusMessage = uiState.statusMessage,
                    onGoOnline = { viewModel.toggleAvailability(demoLocation) }
                )
            }

            DriverStage.AVAILABLE -> {
                AvailableContent(
                    uiState = uiState,
                    onGoOffline = { viewModel.toggleAvailability(demoLocation) },
                    onAcceptOffer = { viewModel.acceptOffer(it) },
                    onDeclineOffer = { viewModel.declineOffer(it) }
                )
            }

            DriverStage.RIDE_ACCEPTED -> {
                RideAcceptedContent(
                    uiState = uiState,
                    onStartRoute = { viewModel.startRouteToPickup() },
                    onCancel = { viewModel.cancelRide() },
                    onOpenChat = { showChatSheet = true },
                    chatMessageCount = uiState.chatMessages.size
                )
            }

            DriverStage.EN_ROUTE_TO_PICKUP -> {
                EnRouteContent(
                    uiState = uiState,
                    autoOpenNavigation = autoOpenNavigation,
                    onArrived = { viewModel.arrivedAtPickup() },
                    onCancel = { viewModel.cancelRide() },
                    onOpenChat = { showChatSheet = true },
                    chatMessageCount = uiState.chatMessages.size
                )
            }

            DriverStage.ARRIVED_AT_PICKUP -> {
                ArrivedAtPickupContent(
                    uiState = uiState,
                    onSubmitPin = { viewModel.submitPinForVerification(it) },
                    onCancel = { viewModel.cancelRide() },
                    onOpenChat = { showChatSheet = true },
                    chatMessageCount = uiState.chatMessages.size
                )
            }

            DriverStage.IN_RIDE -> {
                InRideContent(
                    uiState = uiState,
                    autoOpenNavigation = autoOpenNavigation,
                    onComplete = { viewModel.completeRide() },
                    onCancel = { viewModel.cancelRide() },
                    onOpenChat = { showChatSheet = true },
                    chatMessageCount = uiState.chatMessages.size
                )
            }

            DriverStage.RIDE_COMPLETED -> {
                RideCompletedContent(
                    uiState = uiState,
                    onFinish = { viewModel.finishAndGoOnline(demoLocation) },
                    onGoOffline = { viewModel.clearAcceptedOffer() }
                )
            }
        }
    }
}

@Composable
private fun OfflineContent(
    statusMessage: String,
    onGoOnline: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.DirectionsCar,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "OFFLINE",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onGoOnline,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Power, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Go Online")
            }
        }
    }
}

@Composable
private fun AvailableContent(
    uiState: DriverUiState,
    onGoOffline: () -> Unit,
    onAcceptOffer: (RideOfferData) -> Unit,
    onDeclineOffer: (RideOfferData) -> Unit
) {
    // Online status card
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.DirectionsCar,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "ONLINE",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                text = uiState.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )

            uiState.lastBroadcastTime?.let { time ->
                val secondsAgo = (System.currentTimeMillis() - time) / 1000
                Text(
                    text = "Last broadcast: ${secondsAgo}s ago",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onGoOffline,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Go Offline")
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Ride requests section
    Text(
        text = "Incoming Ride Requests (${uiState.pendingOffers.size})",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(8.dp))

    if (uiState.pendingOffers.isEmpty()) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Waiting for ride requests...",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(uiState.pendingOffers) { offer ->
                RideOfferCard(
                    offer = offer,
                    isProcessing = uiState.isProcessingOffer,
                    onAccept = { onAcceptOffer(offer) },
                    onDecline = { onDeclineOffer(offer) }
                )
            }
        }
    }
}

@Composable
private fun RideAcceptedContent(
    uiState: DriverUiState,
    onStartRoute: () -> Unit,
    onCancel: () -> Unit,
    onOpenChat: () -> Unit,
    chatMessageCount: Int
) {
    val context = LocalContext.current
    val offer = uiState.acceptedOffer ?: return

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "RIDE ACCEPTED",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = uiState.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Pickup location info
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "Pickup Location",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "${offer.approxPickup.lat}, ${offer.approxPickup.lon}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Destination",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(
                        text = "${offer.destination.lat}, ${offer.destination.lon}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Fare: ${offer.fareEstimate.toInt()} sats",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Chat button
            OutlinedButton(
                onClick = onOpenChat,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Chat with Rider")
                if (chatMessageCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Badge { Text(chatMessageCount.toString()) }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Navigate button (opens external map)
            OutlinedButton(
                onClick = {
                    val geoUri = "geo:0,0?q=${offer.approxPickup.lat},${offer.approxPickup.lon}(Pickup)"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(geoUri))
                    context.startActivity(Intent.createChooser(intent, "Navigate to pickup"))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Navigation, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open in Maps")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onStartRoute,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.DirectionsCar, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Start Route to Pickup")
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(onClick = onCancel) {
                Text("Cancel Ride", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun EnRouteContent(
    uiState: DriverUiState,
    autoOpenNavigation: Boolean,
    onArrived: () -> Unit,
    onCancel: () -> Unit,
    onOpenChat: () -> Unit,
    chatMessageCount: Int
) {
    val context = LocalContext.current
    val offer = uiState.acceptedOffer ?: return

    // Auto-navigation countdown (only if enabled)
    var countdown by remember { mutableStateOf(if (autoOpenNavigation) 3 else 0) }
    var navigationOpened by remember { mutableStateOf(!autoOpenNavigation) }

    // Auto-open navigation after 3 seconds (only if enabled)
    if (autoOpenNavigation) {
        LaunchedEffect(Unit) {
            while (countdown > 0) {
                kotlinx.coroutines.delay(1000)
                countdown--
            }
            if (!navigationOpened) {
                navigationOpened = true
                val geoUri = "geo:0,0?q=${offer.approxPickup.lat},${offer.approxPickup.lon}(Pickup)"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(geoUri))
                context.startActivity(Intent.createChooser(intent, "Navigate to pickup"))
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.DirectionsCar,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.tertiary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "RIDE ACCEPTED",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Heading to Pickup",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Driving to: ${offer.approxPickup.lat}, ${offer.approxPickup.lon}",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            // Auto-navigation countdown indicator (only show if enabled)
            if (autoOpenNavigation && countdown > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Opening navigation in $countdown...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Chat button
            OutlinedButton(
                onClick = onOpenChat,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Chat with Rider")
                if (chatMessageCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Badge { Text(chatMessageCount.toString()) }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Navigate button (always visible)
            OutlinedButton(
                onClick = {
                    navigationOpened = true
                    val geoUri = "geo:0,0?q=${offer.approxPickup.lat},${offer.approxPickup.lon}(Pickup)"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(geoUri))
                    context.startActivity(Intent.createChooser(intent, "Navigate to pickup"))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Navigation, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open in Maps")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = onArrived,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.LocationOn, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("I've Arrived")
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(onClick = onCancel) {
                Text("Cancel Ride", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ArrivedAtPickupContent(
    uiState: DriverUiState,
    onSubmitPin: (String) -> Unit,
    onCancel: () -> Unit,
    onOpenChat: () -> Unit,
    chatMessageCount: Int
) {
    var pinInput by remember { mutableStateOf("") }

    // Clear input when a new attempt is allowed (verification failed)
    LaunchedEffect(uiState.pinAttempts) {
        if (uiState.pinAttempts > 0 && !uiState.isAwaitingPinVerification) {
            pinInput = ""
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "ARRIVED AT PICKUP",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = uiState.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            // Show attempts remaining if any failed attempts
            if (uiState.pinAttempts > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${3 - uiState.pinAttempts} attempts remaining",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (uiState.pinAttempts >= 2)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // PIN input
            OutlinedTextField(
                value = pinInput,
                onValueChange = {
                    if (it.length <= 4 && it.all { c -> c.isDigit() }) {
                        pinInput = it
                    }
                },
                label = { Text("Enter Rider's PIN") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                enabled = !uiState.isAwaitingPinVerification,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Chat button
            OutlinedButton(
                onClick = onOpenChat,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Chat with Rider")
                if (chatMessageCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Badge { Text(chatMessageCount.toString()) }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    onSubmitPin(pinInput)
                },
                enabled = pinInput.length == 4 && !uiState.isAwaitingPinVerification,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.isAwaitingPinVerification) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Verifying...")
                } else {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Submit PIN for Verification")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = onCancel,
                enabled = !uiState.isAwaitingPinVerification
            ) {
                Text("Cancel Ride", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun InRideContent(
    uiState: DriverUiState,
    autoOpenNavigation: Boolean,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    onOpenChat: () -> Unit,
    chatMessageCount: Int
) {
    val context = LocalContext.current
    val offer = uiState.acceptedOffer ?: return

    // Auto-navigation countdown (only if enabled)
    var countdown by remember { mutableStateOf(if (autoOpenNavigation) 3 else 0) }
    var navigationOpened by remember { mutableStateOf(!autoOpenNavigation) }

    // Auto-open navigation to destination after 3 seconds (only if enabled)
    if (autoOpenNavigation) {
        LaunchedEffect(Unit) {
            while (countdown > 0) {
                kotlinx.coroutines.delay(1000)
                countdown--
            }
            if (!navigationOpened) {
                navigationOpened = true
                val geoUri = "geo:0,0?q=${offer.destination.lat},${offer.destination.lon}(Destination)"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(geoUri))
                context.startActivity(Intent.createChooser(intent, "Navigate to destination"))
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.DirectionsCar,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.tertiary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "RIDE IN PROGRESS",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Destination: ${offer.destination.lat}, ${offer.destination.lon}",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            Text(
                text = "Fare: ${offer.fareEstimate.toInt()} sats",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )

            // Auto-navigation countdown indicator (only show if enabled)
            if (autoOpenNavigation && countdown > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Opening navigation in $countdown...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Navigate to destination (always visible)
            OutlinedButton(
                onClick = {
                    navigationOpened = true
                    val geoUri = "geo:0,0?q=${offer.destination.lat},${offer.destination.lon}(Destination)"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(geoUri))
                    context.startActivity(Intent.createChooser(intent, "Navigate to destination"))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Navigation, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Navigate to Destination")
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Chat button
            OutlinedButton(
                onClick = onOpenChat,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Chat with Rider")
                if (chatMessageCount > 0) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Badge { Text(chatMessageCount.toString()) }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Slide to drop off rider
            SlideToConfirm(
                text = "Slide to drop off rider",
                onConfirm = onComplete,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(onClick = onCancel) {
                Text("Cancel Ride", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun RideCompletedContent(
    uiState: DriverUiState,
    onFinish: () -> Unit,
    onGoOffline: () -> Unit
) {
    val offer = uiState.acceptedOffer

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
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "RIDE COMPLETED!",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            offer?.let {
                Text(
                    text = "Fare: ${it.fareEstimate.toInt()} sats",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Payment will be processed via Lightning",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onFinish,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Accept Another Ride")
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onGoOffline,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Go Offline")
            }
        }
    }
}

/**
 * Format a Unix timestamp as relative time (e.g., "5s ago", "2m ago").
 */
private fun formatRelativeTime(timestampSeconds: Long): String {
    val nowSeconds = System.currentTimeMillis() / 1000
    val diffSeconds = nowSeconds - timestampSeconds

    return when {
        diffSeconds < 60 -> "${diffSeconds}s ago"
        diffSeconds < 3600 -> "${diffSeconds / 60}m ago"
        diffSeconds < 86400 -> "${diffSeconds / 3600}h ago"
        else -> "${diffSeconds / 86400}d ago"
    }
}

@Composable
private fun RideOfferCard(
    offer: RideOfferData,
    isProcessing: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit
) {
    // Update relative time every second
    var relativeTime by remember { mutableStateOf(formatRelativeTime(offer.createdAt)) }
    LaunchedEffect(offer.createdAt) {
        while (true) {
            relativeTime = formatRelativeTime(offer.createdAt)
            kotlinx.coroutines.delay(1000)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Ride Request",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = relativeTime,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = "${offer.fareEstimate.toInt()} sats",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Pickup: ${String.format("%.4f", offer.approxPickup.lat)}, ${String.format("%.4f", offer.approxPickup.lon)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Flag,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Dest: ${String.format("%.4f", offer.destination.lat)}, ${String.format("%.4f", offer.destination.lon)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Rider: ${offer.riderPubKey.take(12)}...",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onDecline,
                    enabled = !isProcessing,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Decline")
                }
                Button(
                    onClick = onAccept,
                    enabled = !isProcessing,
                    modifier = Modifier.weight(1f)
                ) {
                    if (isProcessing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Accept")
                    }
                }
            }
        }
    }
}
