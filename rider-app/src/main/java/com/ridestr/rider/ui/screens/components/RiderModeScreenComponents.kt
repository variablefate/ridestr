package com.ridestr.rider.ui.screens.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ridestr.common.bitcoin.BitcoinPriceService
import com.ridestr.common.nostr.events.DriverAvailabilityData
import com.ridestr.common.nostr.events.UserProfile
import com.ridestr.common.settings.DisplayCurrency
import com.ridestr.common.ui.ActiveRideCard
import com.ridestr.common.ui.FareDisplay
import com.ridestr.common.ui.formatFare
import com.ridestr.rider.viewmodels.RiderUiState

@Composable
fun RideRequestPanel(
    driver: DriverAvailabilityData,
    driverProfile: UserProfile?,
    fareEstimate: Double,
    isSending: Boolean,
    onRequest: () -> Unit,
    onCancel: () -> Unit,
    displayCurrency: DisplayCurrency,
    onToggleCurrency: () -> Unit,
    priceService: BitcoinPriceService
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
                text = "Driver: ${driverProfile?.bestName()?.split(" ")?.firstOrNull() ?: driver.driverPubKey.take(12) + "..."}",
                style = MaterialTheme.typography.bodyMedium
            )
            // Prefer vehicle info from availability event, fallback to profile
            (driver.vehicleDescription() ?: driverProfile?.carDescription())?.let { car ->
                Text(
                    text = "Vehicle: $car",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FareDisplay(
                satsAmount = fareEstimate,
                displayCurrency = displayCurrency,
                onToggleCurrency = onToggleCurrency,
                priceService = priceService,
                style = MaterialTheme.typography.bodyMedium,
                prefix = "Fare: "
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

// ─────────────────────────────────────────────────────────────
// RiderPinCard  (unchanged name — used by ActiveRidePanel)
// ─────────────────────────────────────────────────────────────

@Composable
fun RiderPinCard(pin: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                text = "Tell this PIN to your driver",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun ActiveRidePanel(
    uiState: RiderUiState,
    onCancel: () -> Unit,
    onOpenChat: () -> Unit,
    chatMessageCount: Int,
    displayCurrency: DisplayCurrency,
    onToggleCurrency: () -> Unit,
    priceService: BitcoinPriceService
) {
    // Get driver info
    val driverPubKey = uiState.rideSession.acceptance?.driverPubKey
    val driverProfile = driverPubKey?.let { uiState.driverProfiles[it] }
    val driverAvailability = driverPubKey?.let { pk ->
        uiState.rideSession.selectedDriver?.takeIf { it.driverPubKey == pk }
            ?: uiState.availableDrivers.find { it.driverPubKey == pk }
    }

    // AtoB Pattern: Show "Ride Confirmed!" when confirmationEventId is set,
    // even though rideStage is still DRIVER_ACCEPTED (waiting for driver to start)
    val isRideConfirmed = uiState.rideSession.confirmationEventId != null

    ActiveRideCard(
        header = {
            // Standardized 48.dp icon header
            if (uiState.rideSession.isConfirmingRide) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    strokeWidth = 3.dp
                )
            } else {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = when {
                    uiState.rideSession.isConfirmingRide -> "Confirming ride..."
                    isRideConfirmed -> "Ride Confirmed!"
                    else -> "Driver Accepted!"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        body = {
            // Show PIN prominently when ride is confirmed
            if (isRideConfirmed && uiState.rideSession.pickupPin != null) {
                RiderPinCard(pin = uiState.rideSession.pickupPin!!)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Waiting for driver to start...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Driver info row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = "Driver",
                    modifier = Modifier
                        .size(56.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(28.dp)
                        )
                        .padding(12.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = driverProfile?.bestName() ?: "Driver",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    (driverAvailability?.vehicleDescription() ?: driverProfile?.carDescription())?.let { carInfo ->
                        Text(
                            text = carInfo,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Ride summary card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    // Pickup
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.TripOrigin,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.pickupLocation?.addressLabel ?: "Pickup location",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Dropoff
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Place,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.destination?.addressLabel ?: "Destination",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Fare (displayed with fee buffer)
                    uiState.fareEstimateWithFees?.let { fare ->
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Fare",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = formatFare(
                                    satsAmount = fare.toDouble(),
                                    displayCurrency = displayCurrency,
                                    priceService = priceService
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        },
        actions = {
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

            Spacer(modifier = Modifier.height(8.dp))

            // Cancel button only (no confirm button - auto-confirm happens automatically)
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
    )
}

@Composable
fun CompletionPanel(
    driverPubKey: String?,
    driverName: String?,
    isDriverAlreadyFavorite: Boolean,
    onAddToFavorites: () -> Unit,
    onNewRide: () -> Unit
) {
    var showAddedConfirmation by remember { mutableStateOf(false) }

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

            // Show "Add to Favorites" prompt if driver isn't already a favorite
            if (driverPubKey != null && !isDriverAlreadyFavorite && !showAddedConfirmation) {
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Did you enjoy your ride?",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Add ${driverName ?: "this driver"} to your RoadFlare network",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = {
                                onAddToFavorites()
                                showAddedConfirmation = true
                            }
                        ) {
                            Icon(
                                Icons.Default.PersonAdd,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Add to Favorites")
                        }
                    }
                }
            }

            // Show confirmation after adding
            if (showAddedConfirmation) {
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${driverName ?: "Driver"} added to favorites!",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(onClick = onNewRide) {
                Text("Book Another Ride")
            }
        }
    }
}

@Composable
internal fun CancelDialogStack(
    showCancelWarning: Boolean,
    showDriverUnavailable: Boolean,
    showEscrowFailed: Boolean,
    escrowFailedMessage: String?,
    onDismissCancelWarning: () -> Unit,
    onConfirmCancelWarning: () -> Unit,
    onDismissDriverUnavailable: () -> Unit,
    onRetryEscrow: () -> Unit,
    onCancelAfterEscrowFailure: () -> Unit,
) {
    // Cancel warning dialog (shown when cancelling after PIN verification)
    if (showCancelWarning) {
        AlertDialog(
            onDismissRequest = onDismissCancelWarning,
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Payment Already Sent") },
            text = {
                Column {
                    Text(
                        "Your payment has already been authorized to the driver. If you cancel now, the driver can still claim the fare."
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Cancelling does not guarantee a refund.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirmCancelWarning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Cancel Anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissCancelWarning) {
                    Text("Keep Ride")
                }
            }
        )
    }

    // Driver unavailable dialog (shown when selected driver goes offline)
    if (showDriverUnavailable) {
        AlertDialog(
            onDismissRequest = onDismissDriverUnavailable,
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Driver Unavailable") },
            text = {
                Column {
                    Text(
                        "The driver you selected is no longer available. They may have taken another ride or gone offline."
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Please select another driver.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = onDismissDriverUnavailable) {
                    Text("Select Another Driver")
                }
            }
        )
    }

    // Escrow lock failure dialog (SAME_MINT payment setup failed)
    if (showEscrowFailed) {
        AlertDialog(
            onDismissRequest = { },  // Non-dismissable: must choose retry or cancel
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Payment Setup Failed") },
            text = {
                Column {
                    Text(escrowFailedMessage ?: "Could not set up payment escrow.")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "You can retry or cancel the ride.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = onRetryEscrow) { Text("Retry") }
            },
            dismissButton = {
                TextButton(onClick = onCancelAfterEscrowFailure) {
                    Text("Cancel Ride")
                }
            }
        )
    }
}
