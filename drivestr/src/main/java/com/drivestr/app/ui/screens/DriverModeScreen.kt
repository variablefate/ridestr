package com.drivestr.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.drivestr.app.presence.DriverStage
import com.drivestr.app.ui.screens.components.AvailabilityControls
import com.drivestr.app.ui.screens.components.NoCommonPaymentMethodDialog
import com.drivestr.app.ui.screens.components.OfferInbox
import com.drivestr.app.ui.screens.components.RoadflareFollowerList
import com.drivestr.app.viewmodels.DriverUiState
import com.drivestr.app.viewmodels.DriverViewModel
import com.ridestr.common.payment.PaymentStatus
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.ridestr.common.data.Vehicle
import com.ridestr.common.data.VehicleRepository
import com.ridestr.common.nostr.events.BroadcastRideOfferData
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.events.PaymentPath
import com.ridestr.common.nostr.events.RideOfferData
import com.ridestr.common.location.GeocodingService
import com.ridestr.common.settings.DisplayCurrency
import com.ridestr.common.settings.DistanceUnit
import com.ridestr.common.settings.SettingsUiState
import com.ridestr.common.ui.ActiveRideCard
import com.ridestr.common.ui.ChatBottomSheet
import com.ridestr.common.ui.FareDisplay
import com.ridestr.common.ui.SlideToConfirm
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private const val TAG = "DriverModeScreen"

// Demo location coordinates for fallback
private const val DEMO_DRIVER_LAT = 38.4604331
private const val DEMO_DRIVER_LON = -108.8817009

@Composable
fun DriverModeScreen(
    viewModel: DriverViewModel,
    settings: SettingsUiState,
    vehicleRepository: VehicleRepository,
    autoOpenNavigation: Boolean = true,
    onToggleCurrency: () -> Unit = {},
    onSetActiveVehicleId: (String?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    var showChatSheet by remember { mutableStateOf(false) }

    // Vehicle picker state
    val vehicles by vehicleRepository.vehicles.collectAsState()
    val alwaysAskVehicle = settings.alwaysAskVehicle
    val activeVehicleId = settings.activeVehicleId
    var showVehiclePickerDialog by remember { mutableStateOf(false) }
    var pendingLocationForGoOnline by remember { mutableStateOf<Location?>(null) }

    // Demo location fallback
    val demoLocation = remember { Location(lat = DEMO_DRIVER_LAT, lon = DEMO_DRIVER_LON) }

    // Current driver location from GPS
    var currentLocation by remember { mutableStateOf<Location?>(null) }
    var isFetchingLocation by remember { mutableStateOf(false) }
    var locationError by remember { mutableStateOf<String?>(null) }

    // FusedLocationProviderClient for GPS
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    // Function to fetch GPS location
    fun fetchGpsLocation(onComplete: (Location) -> Unit) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.w(TAG, "No location permission, using demo location")
            currentLocation = demoLocation
            locationError = "Location permission required"
            onComplete(demoLocation)
            return
        }

        isFetchingLocation = true
        scope.launch {
            try {
                Log.d(TAG, "Fetching GPS location...")
                val cancellationTokenSource = CancellationTokenSource()
                val location = fusedLocationClient.getCurrentLocation(
                    Priority.PRIORITY_HIGH_ACCURACY,
                    cancellationTokenSource.token
                ).await()

                if (location != null) {
                    Log.d(TAG, "GPS location received: ${location.latitude}, ${location.longitude}")
                    val loc = Location(lat = location.latitude, lon = location.longitude)
                    currentLocation = loc
                    locationError = null
                    onComplete(loc)
                } else {
                    Log.w(TAG, "GPS returned null, using demo location")
                    currentLocation = demoLocation
                    locationError = "GPS unavailable"
                    onComplete(demoLocation)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception fetching location", e)
                currentLocation = demoLocation
                locationError = "Location permission denied"
                onComplete(demoLocation)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching GPS location", e)
                currentLocation = demoLocation
                locationError = "Location error"
                onComplete(demoLocation)
            } finally {
                isFetchingLocation = false
            }
        }
    }

    // Permission launcher
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        Log.d(TAG, "Permission result: fine=$fineGranted, coarse=$coarseGranted")

        if (fineGranted || coarseGranted) {
            // Permission granted, execute pending action
            pendingAction?.invoke()
            pendingAction = null
        } else {
            Log.w(TAG, "Location permission denied")
            currentLocation = demoLocation
            locationError = "Permission denied - using demo"
            // Execute pending action with demo location
            pendingAction?.invoke()
            pendingAction = null
        }
    }

    // Notification permission launcher (Android 13+)
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "Notification permission result: $granted")
        // Continue regardless of permission - notifications are optional but helpful
    }

    // Check and request notification permission if needed (Android 13+)
    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasPermission) {
                Log.d(TAG, "Requesting notification permission (Android 13+)")
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Manual location settings
    val useManualDriverLocation = settings.useManualDriverLocation
    val manualDriverLat = settings.manualDriverLat
    val manualDriverLon = settings.manualDriverLon

    // Function to get current location and execute action
    fun withCurrentLocation(action: (Location) -> Unit) {
        // Check if manual location mode is enabled
        if (useManualDriverLocation) {
            Log.d(TAG, "Using manual location: $manualDriverLat, $manualDriverLon")
            val manualLocation = Location(lat = manualDriverLat, lon = manualDriverLon)
            currentLocation = manualLocation
            locationError = null
            action(manualLocation)
            return
        }

        // Check permission
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            // Fetch GPS and then execute action
            fetchGpsLocation { location ->
                action(location)
            }
        } else {
            // Request permission, then execute action
            pendingAction = {
                fetchGpsLocation { location ->
                    action(location)
                }
            }
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // Watch for location refresh requests (e.g., when app returns to foreground)
    val locationRefreshRequested by viewModel.locationRefreshRequested.collectAsState()
    LaunchedEffect(locationRefreshRequested, useManualDriverLocation) {
        if (locationRefreshRequested && (uiState.stage == DriverStage.AVAILABLE || uiState.stage == DriverStage.ROADFLARE_ONLY)) {
            Log.d(TAG, "Location refresh requested (e.g., app returned to foreground)")
            viewModel.acknowledgeLocationRefresh()

            // Check if manual location mode is enabled
            if (useManualDriverLocation) {
                Log.d(TAG, "Using manual location for refresh: $manualDriverLat, $manualDriverLon")
                val manualLocation = Location(lat = manualDriverLat, lon = manualDriverLon)
                currentLocation = manualLocation
                viewModel.handleLocationUpdate(manualLocation, force = false)
                return@LaunchedEffect
            }

            // Fetch real GPS location
            val hasPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (hasPermission) {
                try {
                    val cancellationTokenSource = CancellationTokenSource()
                    val location = fusedLocationClient.getCurrentLocation(
                        Priority.PRIORITY_HIGH_ACCURACY,
                        cancellationTokenSource.token
                    ).await()

                    if (location != null) {
                        val loc = Location(lat = location.latitude, lon = location.longitude)
                        currentLocation = loc
                        // Use default throttling (not forced) for background refresh
                        viewModel.handleLocationUpdate(loc, force = false)
                    } else {
                        Log.w(TAG, "GPS returned null on foreground refresh")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error fetching GPS on foreground refresh", e)
                }
            }
        }
    }

    // Chat bottom sheet
    ChatBottomSheet(
        showSheet = showChatSheet,
        onDismiss = { showChatSheet = false },
        messages = uiState.rideSession.chatMessages,
        myPubKey = uiState.myPubKey,
        otherPartyName = "Rider",
        isSending = uiState.rideSession.isSendingMessage,
        onSendMessage = { message ->
            viewModel.sendChatMessage(message)
        }
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Error message with auto-dismiss after 10 seconds
        uiState.error?.let { error ->
            // Auto-dismiss after 10 seconds
            LaunchedEffect(error) {
                delay(10_000L)
                viewModel.clearError()
            }

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
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { viewModel.clearError() }) {
                        Text("Dismiss")
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Vehicle picker dialog
        if (showVehiclePickerDialog) {
            VehiclePickerDialog(
                vehicles = vehicles,
                lastUsedVehicleId = activeVehicleId,
                onSelect = { vehicle ->
                    showVehiclePickerDialog = false
                    onSetActiveVehicleId(vehicle.id)
                    pendingLocationForGoOnline?.let { location ->
                        viewModel.goOnline(location, vehicle)
                    }
                    pendingLocationForGoOnline = null
                },
                onDismiss = {
                    showVehiclePickerDialog = false
                    pendingLocationForGoOnline = null
                }
            )
        }

        // Payment warning dialog (shown when trying to complete ride without payment)
        if (uiState.rideSession.showPaymentWarningDialog) {
            val isWaiting = uiState.rideSession.paymentWarningStatus == PaymentStatus.WAITING_FOR_PREIMAGE

            AlertDialog(
                onDismissRequest = {
                    viewModel.dismissPaymentWarningDialog()
                },
                icon = {
                    if (isWaiting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 3.dp
                        )
                    } else {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                },
                title = {
                    Text(if (isWaiting) "Waiting for Payment" else "Payment Issue")
                },
                text = {
                    Text(when {
                        uiState.rideSession.paymentWarningStatus == PaymentStatus.WAITING_FOR_PREIMAGE ->
                            "Waiting for payment authorization from rider. This may take a moment."
                        uiState.rideSession.paymentWarningStatus == PaymentStatus.MISSING_PREIMAGE ->
                            "Payment authorization not received from rider."
                        uiState.rideSession.paymentWarningStatus == PaymentStatus.MISSING_ESCROW_TOKEN ->
                            "Escrow token not received. Payment cannot be claimed."
                        uiState.rideSession.paymentWarningStatus == PaymentStatus.MISSING_PAYMENT_HASH ->
                            "Payment data lost (app restarted). Cannot claim escrow payment."
                        else -> "There was an issue with the payment."
                    })
                },
                confirmButton = {
                    // Hide "Complete Anyway" for SAME_MINT rides — escrow payment is required
                    if (!isWaiting && uiState.rideSession.paymentPath != PaymentPath.SAME_MINT) {
                        TextButton(onClick = { viewModel.confirmCompleteWithoutPayment() }) {
                            Text("Complete Anyway")
                        }
                    }
                },
                dismissButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { viewModel.dismissPaymentWarningDialog() }) {
                            Text("Go Back")
                        }
                        if (!isWaiting) {
                            TextButton(onClick = { viewModel.cancelRideDueToPaymentIssue() }) {
                                Text("Cancel Ride", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            )
        }

        // Wallet not set up warning (shown when going online without wallet configured)
        if (uiState.showWalletNotSetupWarning) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissWalletWarning() },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(32.dp)
                    )
                },
                title = { Text("Wallet Not Set Up") },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "Your Cashu wallet is not configured. Riders using Cashu payments won't be able to complete rides with you."
                        )
                        Text(
                            "Set up your wallet in Settings → Wallet to receive payments.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.proceedWithoutWallet() }) {
                        Text("Go Online Anyway")
                    }
                },
                dismissButton = {
                    Button(onClick = { viewModel.dismissWalletWarning() }) {
                        Text("Set Up Wallet")
                    }
                }
            )
        }

        // Rider cancelled claim dialog (shown when rider cancels after PIN verification)
        if (uiState.rideSession.showRiderCancelledClaimDialog) {
            AlertDialog(
                onDismissRequest = { /* Don't dismiss on outside tap - require explicit choice */ },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp)
                    )
                },
                title = { Text("Rider Cancelled") },
                text = {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "The rider cancelled after payment was authorized."
                        )
                        uiState.rideSession.riderCancelledFareAmount?.let { fare ->
                            Text(
                                "You can still claim the fare of ${String.format("%.0f", fare)} sats.",
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { viewModel.claimPaymentAfterCancellation() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Claim Payment")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.dismissRiderCancelledDialog() }) {
                        Text("Skip")
                    }
                }
            )
        }

        // Per-offer callbacks stabilized so children that `remember`-wrap them
        // (OfferInbox / RoadflareFollowerList / item children) see the same
        // lambda identity across recompositions — otherwise the child-side
        // `remember(offer, onAccept)` would invalidate every frame (see #71).
        val onAcceptOfferStable = remember(viewModel) { { offer: RideOfferData -> viewModel.acceptOffer(offer) } }
        val onDeclineOfferStable = remember(viewModel) { { offer: RideOfferData -> viewModel.declineOffer(offer) } }
        val onAcceptBroadcastStable = remember(viewModel) { { req: BroadcastRideOfferData -> viewModel.acceptBroadcastRequest(req) } }
        val onDeclineBroadcastStable = remember(viewModel) { { req: BroadcastRideOfferData -> viewModel.declineBroadcastRequest(req) } }
        val onSetNoMatchWarningStable = remember(viewModel) { { id: String -> viewModel.setNoMatchWarningOffer(id) } }
        val onDismissNoMatchWarningStable = remember(viewModel) { { viewModel.dismissNoMatchWarning() } }

        // Main content based on driver stage
        Crossfade(
            targetState = uiState.stage,
            animationSpec = tween(durationMillis = 300),
            label = "driverStage"
        ) { stage ->
        when (stage) {
            DriverStage.OFFLINE -> {
                OfflineContent(
                    statusMessage = uiState.statusMessage,
                    isFetchingLocation = isFetchingLocation,
                    locationError = locationError,
                    onGoOnline = {
                        // Request notification permission first (Android 13+)
                        requestNotificationPermissionIfNeeded()
                        // Then proceed with location and going online
                        withCurrentLocation { location ->
                            // Check if we need to show vehicle picker
                            val shouldShowPicker = alwaysAskVehicle && vehicles.size > 1
                            if (shouldShowPicker) {
                                pendingLocationForGoOnline = location
                                showVehiclePickerDialog = true
                            } else {
                                // Use primary/active vehicle automatically
                                val vehicle = vehicleRepository.getActiveVehicle(activeVehicleId)
                                viewModel.goOnline(location, vehicle)
                            }
                        }
                    },
                    onGoRoadflareOnly = {
                        requestNotificationPermissionIfNeeded()
                        withCurrentLocation { location ->
                            val vehicle = vehicleRepository.getActiveVehicle(activeVehicleId)
                            viewModel.goRoadflareOnly(location, vehicle)
                        }
                    }
                )
            }

            DriverStage.ROADFLARE_ONLY -> {
                RoadflareOnlyContent(
                    uiState = uiState,
                    onGoFullyOnline = {
                        withCurrentLocation { location ->
                            val shouldShowPicker = alwaysAskVehicle && vehicles.size > 1
                            if (shouldShowPicker) {
                                pendingLocationForGoOnline = location
                                showVehiclePickerDialog = true
                            } else {
                                val vehicle = vehicleRepository.getActiveVehicle(activeVehicleId)
                                viewModel.goOnline(location, vehicle)
                            }
                        }
                    },
                    onGoOffline = { viewModel.goOffline() },
                    onAcceptOffer = onAcceptOfferStable,
                    onDeclineOffer = onDeclineOfferStable,
                    onSetNoMatchWarning = onSetNoMatchWarningStable,
                    onDismissNoMatchWarning = onDismissNoMatchWarningStable,
                    displayCurrency = settings.displayCurrency,
                    distanceUnit = settings.distanceUnit,
                    roadflarePaymentMethods = settings.roadflarePaymentMethods,
                    onToggleCurrency = onToggleCurrency,
                    priceService = viewModel.bitcoinPriceService
                )
            }

            DriverStage.AVAILABLE -> {
                AvailableContent(
                    uiState = uiState,
                    onGoOffline = { viewModel.goOffline() },
                    onToggleExpandedSearch = { viewModel.toggleExpandedSearch() },
                    onAcceptBroadcastRequest = onAcceptBroadcastStable,
                    onDeclineBroadcastRequest = onDeclineBroadcastStable,
                    onAcceptOffer = onAcceptOfferStable,
                    onDeclineOffer = onDeclineOfferStable,
                    onSetNoMatchWarning = onSetNoMatchWarningStable,
                    onDismissNoMatchWarning = onDismissNoMatchWarningStable,
                    displayCurrency = settings.displayCurrency,
                    distanceUnit = settings.distanceUnit,
                    roadflarePaymentMethods = settings.roadflarePaymentMethods,
                    onToggleCurrency = onToggleCurrency,
                    priceService = viewModel.bitcoinPriceService
                )
            }

            DriverStage.RIDE_ACCEPTED -> {
                RideAcceptedContent(
                    uiState = uiState,
                    precisePickupLocation = uiState.rideSession.precisePickupLocation,
                    onStartRoute = { viewModel.startRouteToPickup() },
                    onCancel = { viewModel.cancelRide() },
                    onOpenChat = { showChatSheet = true },
                    chatMessageCount = uiState.rideSession.chatMessages.size,
                    displayCurrency = settings.displayCurrency,
                    onToggleCurrency = onToggleCurrency,
                    priceService = viewModel.bitcoinPriceService
                )
            }

            DriverStage.EN_ROUTE_TO_PICKUP -> {
                EnRouteContent(
                    uiState = uiState,
                    precisePickupLocation = uiState.rideSession.precisePickupLocation,
                    autoOpenNavigation = autoOpenNavigation,
                    onArrived = { viewModel.arrivedAtPickup() },
                    onCancel = { viewModel.cancelRide() },
                    onOpenChat = { showChatSheet = true },
                    chatMessageCount = uiState.rideSession.chatMessages.size,
                    displayCurrency = settings.displayCurrency,
                    onToggleCurrency = onToggleCurrency,
                    priceService = viewModel.bitcoinPriceService
                )
            }

            DriverStage.ARRIVED_AT_PICKUP -> {
                ArrivedAtPickupContent(
                    uiState = uiState,
                    onSubmitPin = { viewModel.submitPinForVerification(it) },
                    onCancel = { viewModel.cancelRide() },
                    onOpenChat = { showChatSheet = true },
                    chatMessageCount = uiState.rideSession.chatMessages.size
                )
            }

            DriverStage.IN_RIDE -> {
                InRideContent(
                    uiState = uiState,
                    autoOpenNavigation = autoOpenNavigation,
                    onComplete = { viewModel.completeRide() },
                    onCancel = { viewModel.cancelRide() },
                    onOpenChat = { showChatSheet = true },
                    chatMessageCount = uiState.rideSession.chatMessages.size,
                    displayCurrency = settings.displayCurrency,
                    onToggleCurrency = onToggleCurrency,
                    priceService = viewModel.bitcoinPriceService,
                    sliderResetToken = uiState.sliderResetToken
                )
            }

            DriverStage.RIDE_COMPLETED -> {
                RideCompletedContent(
                    uiState = uiState,
                    onFinish = { withCurrentLocation { location -> viewModel.finishAndGoOnline(location) } },
                    onGoOffline = { viewModel.clearAcceptedOffer() },
                    displayCurrency = settings.displayCurrency,
                    onToggleCurrency = onToggleCurrency,
                    priceService = viewModel.bitcoinPriceService
                )
            }
        }
        }
    }
}

@Composable
private fun OfflineContent(
    statusMessage: String,
    isFetchingLocation: Boolean,
    locationError: String?,
    onGoOnline: () -> Unit,
    onGoRoadflareOnly: () -> Unit = {}
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

            // Show location error if any
            locationError?.let { error ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = onGoOnline,
                enabled = !isFetchingLocation,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isFetchingLocation) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Getting Location...")
                } else {
                    Icon(Icons.Default.Power, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Go Online - All Rides")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = onGoRoadflareOnly,
                enabled = !isFetchingLocation,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.People, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Go Online - RoadFlare Only")
            }
        }
    }
}

@Composable
private fun RoadflareOnlyContent(
    uiState: DriverUiState,
    onGoFullyOnline: () -> Unit,
    onGoOffline: () -> Unit,
    onAcceptOffer: (RideOfferData) -> Unit,
    onDeclineOffer: (RideOfferData) -> Unit,
    onSetNoMatchWarning: (String) -> Unit,
    onDismissNoMatchWarning: () -> Unit,
    displayCurrency: DisplayCurrency,
    distanceUnit: DistanceUnit,
    roadflarePaymentMethods: List<String>,
    onToggleCurrency: () -> Unit,
    priceService: com.ridestr.common.bitcoin.BitcoinPriceService
) {
    val driverFiatMethods = roadflarePaymentMethods

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AvailabilityControls(
            onGoFullyOnline = onGoFullyOnline,
            onGoOffline = onGoOffline
        )

        RoadflareFollowerList(
            pendingOffers = uiState.rideSession.pendingOffers,
            isProcessingOffer = uiState.rideSession.isProcessingOffer,
            directOfferPickupRoutes = uiState.directOfferPickupRoutes,
            directOfferRideRoutes = uiState.directOfferRideRoutes,
            displayCurrency = displayCurrency,
            distanceUnit = distanceUnit,
            driverFiatMethods = driverFiatMethods,
            onToggleCurrency = onToggleCurrency,
            priceService = priceService,
            onAcceptOffer = onAcceptOffer,
            onDeclineOffer = onDeclineOffer,
            onSetNoMatchWarning = onSetNoMatchWarning
        )

        // Auto-dismiss dialog when underlying offer disappears from pendingOffers
        LaunchedEffect(uiState.rideSession.noMatchWarningOfferEventId, uiState.rideSession.pendingOffers) {
            uiState.rideSession.noMatchWarningOfferEventId?.let { id ->
                if (uiState.rideSession.pendingOffers.none { it.eventId == id }) {
                    onDismissNoMatchWarning()
                }
            }
        }
        // Resolve current offer by eventId for stable identity
        val activeWarningOffer = uiState.rideSession.noMatchWarningOfferEventId?.let { id ->
            uiState.rideSession.pendingOffers.find { it.eventId == id }
        }
        activeWarningOffer?.let { warningOffer ->
            NoCommonPaymentMethodDialog(
                riderFiatMethods = warningOffer.fiatPaymentMethods,
                onAcceptAnyway = {
                    onDismissNoMatchWarning()
                    onAcceptOffer(warningOffer)
                },
                onDecline = { onDismissNoMatchWarning() }
            )
        }
    }
}

@Composable
private fun AvailableContent(
    uiState: DriverUiState,
    onGoOffline: () -> Unit,
    onToggleExpandedSearch: () -> Unit,
    onAcceptBroadcastRequest: (BroadcastRideOfferData) -> Unit,
    onDeclineBroadcastRequest: (BroadcastRideOfferData) -> Unit,
    onAcceptOffer: (RideOfferData) -> Unit,
    onDeclineOffer: (RideOfferData) -> Unit,
    onSetNoMatchWarning: (String) -> Unit,
    onDismissNoMatchWarning: () -> Unit,
    displayCurrency: DisplayCurrency,
    distanceUnit: DistanceUnit,
    roadflarePaymentMethods: List<String>,
    onToggleCurrency: () -> Unit,
    priceService: com.ridestr.common.bitcoin.BitcoinPriceService
) {
    val driverFiatMethods = roadflarePaymentMethods

    // Ticker for updating "time since last broadcast" display
    var currentTimeMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L) // Update every second
            currentTimeMs = System.currentTimeMillis()
        }
    }

    // Filter out stale broadcast requests (older than 2 minutes)
    // This is a UI-level safety net in addition to ViewModel cleanup
    val currentTimeSeconds = currentTimeMs / 1000
    val maxAgeSeconds = 2 * 60 // 2 minutes
    val freshBroadcastRequests = uiState.rideSession.pendingBroadcastRequests.filter { request ->
        (currentTimeSeconds - request.createdAt) < maxAgeSeconds
    }

    Column(modifier = Modifier.fillMaxSize()) {
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
                val secondsAgo = (currentTimeMs - time) / 1000
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

    OfferInbox(
        expandedSearch = uiState.expandedSearch,
        freshBroadcastRequests = freshBroadcastRequests,
        pendingOffers = uiState.rideSession.pendingOffers,
        isProcessingOffer = uiState.rideSession.isProcessingOffer,
        pickupRoutes = uiState.pickupRoutes,
        directOfferPickupRoutes = uiState.directOfferPickupRoutes,
        directOfferRideRoutes = uiState.directOfferRideRoutes,
        displayCurrency = displayCurrency,
        distanceUnit = distanceUnit,
        driverFiatMethods = driverFiatMethods,
        onToggleCurrency = onToggleCurrency,
        priceService = priceService,
        onAcceptBroadcastRequest = onAcceptBroadcastRequest,
        onDeclineBroadcastRequest = onDeclineBroadcastRequest,
        onAcceptOffer = onAcceptOffer,
        onDeclineOffer = onDeclineOffer,
        onSetNoMatchWarning = onSetNoMatchWarning,
        onToggleExpandedSearch = onToggleExpandedSearch,
        modifier = Modifier.weight(1f)
    )

    // Auto-dismiss dialog when underlying offer disappears from pendingOffers
    LaunchedEffect(uiState.rideSession.noMatchWarningOfferEventId, uiState.rideSession.pendingOffers) {
        uiState.rideSession.noMatchWarningOfferEventId?.let { id ->
            if (uiState.rideSession.pendingOffers.none { it.eventId == id }) {
                onDismissNoMatchWarning()
            }
        }
    }
    // Resolve current offer by eventId for stable identity
    val activeWarningOffer = uiState.rideSession.noMatchWarningOfferEventId?.let { id ->
        uiState.rideSession.pendingOffers.find { it.eventId == id }
    }
    activeWarningOffer?.let { warningOffer ->
        NoCommonPaymentMethodDialog(
            riderFiatMethods = warningOffer.fiatPaymentMethods,
            onAcceptAnyway = {
                onDismissNoMatchWarning()
                onAcceptOffer(warningOffer)
            },
            onDecline = { onDismissNoMatchWarning() }
        )
    }
    }
}

@Composable
private fun RideAcceptedContent(
    uiState: DriverUiState,
    precisePickupLocation: Location?,
    onStartRoute: () -> Unit,
    onCancel: () -> Unit,
    onOpenChat: () -> Unit,
    chatMessageCount: Int,
    displayCurrency: DisplayCurrency,
    onToggleCurrency: () -> Unit,
    priceService: com.ridestr.common.bitcoin.BitcoinPriceService
) {
    val context = LocalContext.current
    val offer = uiState.rideSession.acceptedOffer ?: return

    // Use precise pickup if available, otherwise fall back to approximate
    val pickup = precisePickupLocation ?: offer.approxPickup

    // Geocoding for addresses
    val geocodingService = remember { GeocodingService(context) }
    var pickupAddress by remember { mutableStateOf<String?>(null) }
    var destinationAddress by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pickup, offer.destination) {
        pickupAddress = geocodingService.reverseGeocode(pickup.lat, pickup.lon)
        destinationAddress = geocodingService.reverseGeocode(offer.destination.lat, offer.destination.lon)
    }

    // Confirmation countdown timer state
    val startTime = uiState.rideSession.confirmationWaitStartMs
    val duration = uiState.confirmationWaitDurationMs
    var progress by remember { mutableFloatStateOf(0f) }
    var remainingSeconds by remember { mutableIntStateOf((duration / 1000).toInt()) }
    val showTimer = startTime != null && uiState.rideSession.precisePickupLocation == null

    LaunchedEffect(startTime, showTimer) {
        if (startTime != null && showTimer) {
            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                remainingSeconds = ((duration - elapsed) / 1000).toInt().coerceAtLeast(0)
                delay(100)
            }
        }
    }

    val timerColor = MaterialTheme.colorScheme.primary
    val timerBgColor = MaterialTheme.colorScheme.surfaceVariant

    ActiveRideCard(
        overlay = if (showTimer) {
            {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(48.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(
                            color = timerBgColor,
                            style = Stroke(width = 4.dp.toPx())
                        )
                        drawArc(
                            color = timerColor,
                            startAngle = -90f,
                            sweepAngle = 360f * progress,
                            useCenter = false,
                            style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                    Text(
                        text = "${remainingSeconds}s",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }
        } else null,
        header = {
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
        },
        body = {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    pickupAddress?.let { address ->
                        Text(
                            text = "Pickup Location",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = address,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    destinationAddress?.let { address ->
                        Text(
                            text = "Destination",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = address,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    FareDisplay(
                        satsAmount = offer.fareEstimate,
                        displayCurrency = displayCurrency,
                        onToggleCurrency = onToggleCurrency,
                        priceService = priceService,
                        fiatFare = offer.fiatFare,
                        style = MaterialTheme.typography.titleMedium,
                        prefix = "Fare: "
                    )
                }
            }
        },
        actions = {
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

            OutlinedButton(
                onClick = {
                    val geoUri = "geo:0,0?q=${pickup.lat},${pickup.lon}(Pickup)"
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
    )
}

@Composable
private fun EnRouteContent(
    uiState: DriverUiState,
    precisePickupLocation: Location?,
    autoOpenNavigation: Boolean,
    onArrived: () -> Unit,
    onCancel: () -> Unit,
    onOpenChat: () -> Unit,
    chatMessageCount: Int,
    displayCurrency: DisplayCurrency,
    onToggleCurrency: () -> Unit,
    priceService: com.ridestr.common.bitcoin.BitcoinPriceService
) {
    val context = LocalContext.current
    val offer = uiState.rideSession.acceptedOffer ?: return

    // Use precise pickup if available, otherwise fall back to approximate
    val pickup = precisePickupLocation ?: offer.approxPickup

    // Geocoding for pickup address
    val geocodingService = remember { GeocodingService(context) }
    var pickupAddress by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(pickup) {
        pickupAddress = geocodingService.reverseGeocode(pickup.lat, pickup.lon)
    }

    // Auto-navigation countdown (only if enabled)
    var countdown by remember { mutableStateOf(if (autoOpenNavigation) 3 else 0) }
    var navigationOpened by remember { mutableStateOf(!autoOpenNavigation) }

    // Auto-open navigation after 3 seconds (only if enabled)
    if (autoOpenNavigation) {
        LaunchedEffect(pickup) {
            while (countdown > 0) {
                kotlinx.coroutines.delay(1000)
                countdown--
            }
            if (!navigationOpened) {
                navigationOpened = true
                val geoUri = "geo:0,0?q=${pickup.lat},${pickup.lon}(Pickup)"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(geoUri))
                context.startActivity(Intent.createChooser(intent, "Navigate to pickup"))
            }
        }
    }

    ActiveRideCard(
        header = {
            Icon(
                imageVector = Icons.Default.DirectionsCar,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
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
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        body = {
            pickupAddress?.let { address ->
                Text(
                    text = "Driving to: $address",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            FareDisplay(
                satsAmount = offer.fareEstimate,
                displayCurrency = displayCurrency,
                onToggleCurrency = onToggleCurrency,
                priceService = priceService,
                fiatFare = offer.fiatFare,
                style = MaterialTheme.typography.titleMedium,
                prefix = "Fare: "
            )

            if (autoOpenNavigation && countdown > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Opening navigation in $countdown...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        actions = {
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

            OutlinedButton(
                onClick = {
                    navigationOpened = true
                    val geoUri = "geo:0,0?q=${pickup.lat},${pickup.lon}(Pickup)"
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
    )
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
    LaunchedEffect(uiState.rideSession.pinAttempts) {
        if (uiState.rideSession.pinAttempts > 0 && !uiState.rideSession.isAwaitingPinVerification) {
            pinInput = ""
        }
    }

    ActiveRideCard(
        header = {
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
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = uiState.statusMessage,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            if (uiState.rideSession.pinAttempts > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${3 - uiState.rideSession.pinAttempts} attempts remaining",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (uiState.rideSession.pinAttempts >= 2)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        body = {
            OutlinedTextField(
                value = pinInput,
                onValueChange = {
                    if (it.length <= 4 && it.all { c -> c.isDigit() }) {
                        pinInput = it
                    }
                },
                label = { Text("Enter Rider's PIN") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                enabled = !uiState.rideSession.isAwaitingPinVerification,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            if (uiState.rideSession.pinVerificationTimedOut) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No response from rider",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = "The rider's app may not have received the PIN. Try again or cancel.",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        },
        actions = {
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
                enabled = pinInput.length == 4 && !uiState.rideSession.isAwaitingPinVerification,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.rideSession.isAwaitingPinVerification) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Verifying...")
                } else if (uiState.rideSession.pinVerificationTimedOut) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Retry PIN Verification")
                } else {
                    Icon(Icons.Default.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Submit PIN for Verification")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel Ride", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}

@Composable
private fun InRideContent(
    uiState: DriverUiState,
    autoOpenNavigation: Boolean,
    onComplete: () -> Unit,
    onCancel: () -> Unit,
    onOpenChat: () -> Unit,
    chatMessageCount: Int,
    displayCurrency: DisplayCurrency,
    onToggleCurrency: () -> Unit,
    priceService: com.ridestr.common.bitcoin.BitcoinPriceService,
    sliderResetToken: Int = 0  // Pass through to SlideToConfirm
) {
    val context = LocalContext.current
    val offer = uiState.rideSession.acceptedOffer ?: return

    // Use precise destination if available, otherwise fall back to approximate
    val destination = uiState.rideSession.preciseDestinationLocation ?: offer.destination

    // Geocoding for destination address
    val geocodingService = remember { GeocodingService(context) }
    var destinationAddress by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(destination) {
        destinationAddress = geocodingService.reverseGeocode(destination.lat, destination.lon)
    }

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
                val geoUri = "geo:0,0?q=${destination.lat},${destination.lon}(Destination)"
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(geoUri))
                context.startActivity(Intent.createChooser(intent, "Navigate to destination"))
            }
        }
    }

    ActiveRideCard(
        header = {
            Icon(
                imageVector = Icons.Default.DirectionsCar,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "RIDE IN PROGRESS",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
        },
        body = {
            destinationAddress?.let { address ->
                Text(
                    text = "Destination: $address",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            FareDisplay(
                satsAmount = offer.fareEstimate,
                displayCurrency = displayCurrency,
                onToggleCurrency = onToggleCurrency,
                priceService = priceService,
                fiatFare = offer.fiatFare,
                style = MaterialTheme.typography.titleMedium,
                prefix = "Fare: "
            )

            if (autoOpenNavigation && countdown > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Opening navigation in $countdown...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        actions = {
            OutlinedButton(
                onClick = {
                    navigationOpened = true
                    val geoUri = "geo:0,0?q=${destination.lat},${destination.lon}(Destination)"
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

            SlideToConfirm(
                text = "Slide to drop off rider",
                onConfirm = onComplete,
                modifier = Modifier.fillMaxWidth(),
                resetTrigger = sliderResetToken
            )

            Spacer(modifier = Modifier.height(8.dp))

            TextButton(onClick = onCancel) {
                Text("Cancel Ride", color = MaterialTheme.colorScheme.error)
            }
        }
    )
}

@Composable
private fun RideCompletedContent(
    uiState: DriverUiState,
    onFinish: () -> Unit,
    onGoOffline: () -> Unit,
    displayCurrency: DisplayCurrency,
    onToggleCurrency: () -> Unit,
    priceService: com.ridestr.common.bitcoin.BitcoinPriceService
) {
    val offer = uiState.rideSession.acceptedOffer

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
                FareDisplay(
                    satsAmount = it.fareEstimate,
                    displayCurrency = displayCurrency,
                    onToggleCurrency = onToggleCurrency,
                    priceService = priceService,
                    fiatFare = it.fiatFare,
                    style = MaterialTheme.typography.titleLarge,
                    prefix = "Fare: "
                )
            }

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

