package com.drivestr.app.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.drivestr.app.viewmodels.DriverStage
import com.drivestr.app.viewmodels.DriverUiState
import com.drivestr.app.viewmodels.DriverViewModel
import com.drivestr.app.viewmodels.PaymentStatus
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.ridestr.common.data.Vehicle
import com.ridestr.common.data.VehicleRepository
import com.ridestr.common.nostr.events.BroadcastRideOfferData
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.events.RideOfferData
import com.ridestr.common.nostr.events.RideshareChatData
import com.ridestr.common.location.GeocodingService
import com.ridestr.common.routing.RouteResult
import com.ridestr.common.settings.DisplayCurrency
import com.ridestr.common.settings.DistanceUnit
import com.ridestr.common.settings.SettingsManager
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
    settingsManager: SettingsManager,
    vehicleRepository: VehicleRepository,
    autoOpenNavigation: Boolean = true,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsState()
    var showChatSheet by remember { mutableStateOf(false) }

    // Vehicle picker state
    val vehicles by vehicleRepository.vehicles.collectAsState()
    val alwaysAskVehicle by settingsManager.alwaysAskVehicle.collectAsState()
    val activeVehicleId by settingsManager.activeVehicleId.collectAsState()
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
    val useManualDriverLocation by settingsManager.useManualDriverLocation.collectAsState()
    val manualDriverLat by settingsManager.manualDriverLat.collectAsState()
    val manualDriverLon by settingsManager.manualDriverLon.collectAsState()

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
        if (locationRefreshRequested && uiState.stage == DriverStage.AVAILABLE) {
            Log.d(TAG, "Location refresh requested (e.g., app returned to foreground)")
            viewModel.acknowledgeLocationRefresh()

            // Check if manual location mode is enabled
            if (useManualDriverLocation) {
                Log.d(TAG, "Using manual location for refresh: $manualDriverLat, $manualDriverLon")
                val manualLocation = Location(lat = manualDriverLat, lon = manualDriverLon)
                currentLocation = manualLocation
                viewModel.updateLocation(manualLocation, force = false)
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
                        viewModel.updateLocation(loc, force = false)
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
                    settingsManager.setActiveVehicleId(vehicle.id)
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
        if (uiState.showPaymentWarningDialog) {
            AlertDialog(
                onDismissRequest = { viewModel.dismissPaymentWarningDialog() },
                title = { Text("Payment Issue") },
                text = {
                    Text(when (uiState.paymentWarningStatus) {
                        PaymentStatus.MISSING_PREIMAGE ->
                            "The rider app failed to share payment authorization. You won't be able to claim payment for this ride."
                        PaymentStatus.MISSING_ESCROW_TOKEN ->
                            "The rider app failed to lock payment escrow. You won't be able to claim payment for this ride."
                        PaymentStatus.WAITING_FOR_PREIMAGE ->
                            "Still waiting for payment authorization. This may take a moment."
                        else ->
                            "Payment setup incomplete. You may not receive payment for this ride."
                    })
                },
                confirmButton = {
                    TextButton(onClick = { viewModel.confirmCompleteWithoutPayment() }) {
                        Text("Complete Anyway")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { viewModel.cancelRideDueToPaymentIssue() }) {
                        Text("Cancel Ride", color = MaterialTheme.colorScheme.error)
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
        if (uiState.showRiderCancelledClaimDialog) {
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
                        uiState.riderCancelledFareAmount?.let { fare ->
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

        // Main content based on driver stage
        when (uiState.stage) {
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
                    }
                )
            }

            DriverStage.AVAILABLE -> {
                AvailableContent(
                    uiState = uiState,
                    onGoOffline = { viewModel.goOffline() },
                    onToggleExpandedSearch = { viewModel.toggleExpandedSearch() },
                    onAcceptBroadcastRequest = { viewModel.acceptBroadcastRequest(it) },
                    onDeclineBroadcastRequest = { viewModel.declineBroadcastRequest(it) },
                    onAcceptOffer = { viewModel.acceptOffer(it) },
                    onDeclineOffer = { viewModel.declineOffer(it) },
                    settingsManager = settingsManager,
                    priceService = viewModel.bitcoinPriceService
                )
            }

            DriverStage.RIDE_ACCEPTED -> {
                RideAcceptedContent(
                    uiState = uiState,
                    precisePickupLocation = uiState.precisePickupLocation,
                    onStartRoute = { viewModel.startRouteToPickup() },
                    onCancel = { viewModel.cancelRide() },
                    onOpenChat = { showChatSheet = true },
                    chatMessageCount = uiState.chatMessages.size,
                    settingsManager = settingsManager,
                    priceService = viewModel.bitcoinPriceService
                )
            }

            DriverStage.EN_ROUTE_TO_PICKUP -> {
                EnRouteContent(
                    uiState = uiState,
                    precisePickupLocation = uiState.precisePickupLocation,
                    autoOpenNavigation = autoOpenNavigation,
                    onArrived = { viewModel.arrivedAtPickup() },
                    onCancel = { viewModel.cancelRide() },
                    onOpenChat = { showChatSheet = true },
                    chatMessageCount = uiState.chatMessages.size,
                    settingsManager = settingsManager,
                    priceService = viewModel.bitcoinPriceService
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
                    chatMessageCount = uiState.chatMessages.size,
                    settingsManager = settingsManager,
                    priceService = viewModel.bitcoinPriceService
                )
            }

            DriverStage.RIDE_COMPLETED -> {
                RideCompletedContent(
                    uiState = uiState,
                    onFinish = { withCurrentLocation { location -> viewModel.finishAndGoOnline(location) } },
                    onGoOffline = { viewModel.clearAcceptedOffer() },
                    settingsManager = settingsManager,
                    priceService = viewModel.bitcoinPriceService
                )
            }
        }
    }
}

@Composable
private fun OfflineContent(
    statusMessage: String,
    isFetchingLocation: Boolean,
    locationError: String?,
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
                    Text("Go Online")
                }
            }
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
    settingsManager: SettingsManager,
    priceService: com.ridestr.common.bitcoin.BitcoinPriceService
) {
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
    val freshBroadcastRequests = uiState.pendingBroadcastRequests.filter { request ->
        (currentTimeSeconds - request.createdAt) < maxAgeSeconds
    }

    // Total requests count (filtered broadcast + direct)
    val totalRequests = freshBroadcastRequests.size + uiState.pendingOffers.size

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

    // Search area toggle and ride requests header
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Ride Requests ($totalRequests)",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        FilterChip(
            selected = uiState.expandedSearch,
            onClick = onToggleExpandedSearch,
            label = {
                Text(if (uiState.expandedSearch) "Wide Area" else "Expand Search")
            },
            leadingIcon = {
                Icon(
                    imageVector = if (uiState.expandedSearch)
                        Icons.Default.ZoomOutMap
                    else
                        Icons.Default.ZoomIn,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    if (totalRequests == 0) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.DirectionsCar,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Waiting for ride requests...",
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Requests from nearby riders will appear here",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Show broadcast requests first (sorted by fare, highest first)
            // Using freshBroadcastRequests which filters out stale requests
            items(freshBroadcastRequests) { request ->
                BroadcastRideRequestCard(
                    request = request,
                    pickupRoute = uiState.pickupRoutes[request.eventId],
                    isProcessing = uiState.isProcessingOffer,
                    onAccept = { onAcceptBroadcastRequest(request) },
                    onDecline = { onDeclineBroadcastRequest(request) },
                    settingsManager = settingsManager,
                    priceService = priceService
                )
            }

            // Show direct offers (legacy/advanced) if any
            if (uiState.pendingOffers.isNotEmpty()) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Direct Requests (${uiState.pendingOffers.size})",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(uiState.pendingOffers) { offer ->
                    RideOfferCard(
                        offer = offer,
                        pickupRoute = uiState.directOfferPickupRoutes[offer.eventId],
                        rideRoute = uiState.directOfferRideRoutes[offer.eventId],
                        isProcessing = uiState.isProcessingOffer,
                        onAccept = { onAcceptOffer(offer) },
                        onDecline = { onDeclineOffer(offer) },
                        settingsManager = settingsManager,
                        priceService = priceService
                    )
                }
            }
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
    settingsManager: SettingsManager,
    priceService: com.ridestr.common.bitcoin.BitcoinPriceService
) {
    val context = LocalContext.current
    val offer = uiState.acceptedOffer ?: return

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
    val startTime = uiState.confirmationWaitStartMs
    val duration = uiState.confirmationWaitDurationMs
    var progress by remember { mutableFloatStateOf(0f) }
    var remainingSeconds by remember { mutableIntStateOf((duration / 1000).toInt()) }
    val showTimer = startTime != null && uiState.precisePickupLocation == null

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

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Confirmation timer in top-right corner (only shown while waiting)
            if (showTimer) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .size(48.dp)
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        // Background circle
                        drawCircle(
                            color = timerBgColor,
                            style = Stroke(width = 4.dp.toPx())
                        )
                        // Progress arc
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
                    // Pickup location (only show if geocoded)
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
                    // Destination (only show if geocoded)
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
                        settingsManager = settingsManager,
                        priceService = priceService,
                        style = MaterialTheme.typography.titleMedium,
                        prefix = "Fare: "
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
            }  // Close Column
        }  // Close Box
    }  // Close Card
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
    settingsManager: SettingsManager,
    priceService: com.ridestr.common.bitcoin.BitcoinPriceService
) {
    val context = LocalContext.current
    val offer = uiState.acceptedOffer ?: return

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

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
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
                text = "RIDE ACCEPTED",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Heading to Pickup",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Show pickup address if available (hide if geocoding failed)
            pickupAddress?.let { address ->
                Text(
                    text = "Driving to: $address",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Fare display
            FareDisplay(
                satsAmount = offer.fareEstimate,
                settingsManager = settingsManager,
                priceService = priceService,
                style = MaterialTheme.typography.titleMedium,
                prefix = "Fare: "
            )

            // Auto-navigation countdown indicator (only show if enabled)
            if (autoOpenNavigation && countdown > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Opening navigation in $countdown...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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

            // Show timeout message if verification timed out
            if (uiState.pinVerificationTimedOut) {
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
                Spacer(modifier = Modifier.height(16.dp))
            }

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
                } else if (uiState.pinVerificationTimedOut) {
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

            // Cancel button - always enabled except when actively verifying
            TextButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
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
    chatMessageCount: Int,
    settingsManager: SettingsManager,
    priceService: com.ridestr.common.bitcoin.BitcoinPriceService
) {
    val context = LocalContext.current
    val offer = uiState.acceptedOffer ?: return

    // Use precise destination if available, otherwise fall back to approximate
    val destination = uiState.preciseDestinationLocation ?: offer.destination

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

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
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
                text = "RIDE IN PROGRESS",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Show destination address if available (hide if geocoding failed)
            destinationAddress?.let { address ->
                Text(
                    text = "Destination: $address",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

            FareDisplay(
                satsAmount = offer.fareEstimate,
                settingsManager = settingsManager,
                priceService = priceService,
                style = MaterialTheme.typography.titleMedium,
                prefix = "Fare: "
            )

            // Auto-navigation countdown indicator (only show if enabled)
            if (autoOpenNavigation && countdown > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Opening navigation in $countdown...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Navigate to destination (always visible)
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
    onGoOffline: () -> Unit,
    settingsManager: SettingsManager,
    priceService: com.ridestr.common.bitcoin.BitcoinPriceService
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
                FareDisplay(
                    satsAmount = it.fareEstimate,
                    settingsManager = settingsManager,
                    priceService = priceService,
                    style = MaterialTheme.typography.titleLarge,
                    prefix = "Fare: "
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
    pickupRoute: RouteResult?,
    rideRoute: RouteResult?,
    isProcessing: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    settingsManager: SettingsManager,
    priceService: com.ridestr.common.bitcoin.BitcoinPriceService
) {
    val context = LocalContext.current
    val geocodingService = remember { GeocodingService(context) }
    val distanceUnit by settingsManager.distanceUnit.collectAsState()
    val displayCurrency by settingsManager.displayCurrency.collectAsState()
    val btcPrice by priceService.btcPriceUsd.collectAsState()

    // Update relative time every second
    var relativeTime by remember { mutableStateOf(formatRelativeTime(offer.createdAt)) }
    LaunchedEffect(offer.createdAt) {
        while (true) {
            relativeTime = formatRelativeTime(offer.createdAt)
            kotlinx.coroutines.delay(1000)
        }
    }

    // Geocode pickup and destination addresses
    var pickupAddress by remember { mutableStateOf<String?>(null) }
    var destinationAddress by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(offer.approxPickup, offer.destination) {
        pickupAddress = geocodingService.reverseGeocode(offer.approxPickup.lat, offer.approxPickup.lon)
        destinationAddress = geocodingService.reverseGeocode(offer.destination.lat, offer.destination.lon)
    }

    // Route info calculations
    val pickupDistanceKm = pickupRoute?.distanceKm
    val pickupDurationMin = pickupRoute?.let { it.durationSeconds / 60.0 }
    val rideDistanceKm = rideRoute?.distanceKm
    val rideDurationMin = rideRoute?.let { it.durationSeconds / 60.0 }

    // Calculate earnings metrics (only if we have both routes)
    val earningsPerHour: String?
    val earningsPerDistance: String?

    if (pickupRoute != null && rideRoute != null && pickupDurationMin != null && rideDurationMin != null) {
        val totalTimeHours = (pickupDurationMin + rideDurationMin) / 60.0
        val totalDistanceKm = (pickupDistanceKm ?: 0.0) + (rideDistanceKm ?: 0.0)
        val totalDistanceForEarnings = if (distanceUnit == DistanceUnit.MILES) {
            totalDistanceKm * 0.621371
        } else {
            totalDistanceKm
        }

        earningsPerHour = if (totalTimeHours > 0) {
            formatEarnings(offer.fareEstimate / totalTimeHours, displayCurrency, btcPrice, "/hr")
        } else null

        earningsPerDistance = if (totalDistanceForEarnings > 0) {
            val perDistanceUnit = if (distanceUnit == DistanceUnit.MILES) "/mi" else "/km"
            formatEarnings(offer.fareEstimate / totalDistanceForEarnings, displayCurrency, btcPrice, perDistanceUnit)
        } else null
    } else {
        earningsPerHour = null
        earningsPerDistance = null
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Fare + Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        FareDisplay(
                            satsAmount = offer.fareEstimate,
                            settingsManager = settingsManager,
                            priceService = priceService,
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                    Text(
                        text = "Direct Request",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = relativeTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Pickup route info
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.DirectionsCar,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                if (pickupDistanceKm != null && pickupDurationMin != null) {
                    Text(
                        text = "${formatDistance(pickupDistanceKm, distanceUnit)} away",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = " • ${pickupDurationMin.toInt()} min to pickup",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Calculating route...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Ride route info
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.width(6.dp))
                if (rideDistanceKm != null && rideDurationMin != null) {
                    Text(
                        text = "${formatDistance(rideDistanceKm, distanceUnit)} ride",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = " • ${rideDurationMin.toInt()} min",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Calculating...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Earnings metrics (if available)
            if (earningsPerHour != null || earningsPerDistance != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    earningsPerHour?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    earningsPerDistance?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

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

/**
 * Card for displaying a broadcast ride request.
 * Shows fare prominently, route info, earnings metrics, and request age.
 */
@Composable
private fun BroadcastRideRequestCard(
    request: BroadcastRideOfferData,
    pickupRoute: RouteResult?,
    isProcessing: Boolean,
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    settingsManager: SettingsManager,
    priceService: com.ridestr.common.bitcoin.BitcoinPriceService
) {
    val distanceUnit by settingsManager.distanceUnit.collectAsState()
    val displayCurrency by settingsManager.displayCurrency.collectAsState()
    val btcPrice by priceService.btcPriceUsd.collectAsState()

    // Update relative time every second
    var relativeTime by remember { mutableStateOf(formatRelativeTime(request.createdAt)) }
    LaunchedEffect(request.createdAt) {
        while (true) {
            relativeTime = formatRelativeTime(request.createdAt)
            kotlinx.coroutines.delay(1000)
        }
    }

    // Distance conversion (km to miles if needed)
    val rideDistanceKm = request.routeDistanceKm
    val rideDurationMin = request.routeDurationMin

    // Format ride distance based on user preference
    val rideDistanceStr = formatDistance(rideDistanceKm, distanceUnit)

    // Pickup route info (only available if calculated)
    val pickupDistanceKm = pickupRoute?.distanceKm
    val pickupDurationMin = pickupRoute?.let { it.durationSeconds / 60.0 }
    val pickupDistanceStr = pickupDistanceKm?.let { formatDistance(it, distanceUnit) }

    // Calculate earnings metrics ONLY if we have pickup route data
    // Without pickup time, $/hr would be misleadingly high
    val earningsPerHour: String?
    val earningsPerDistance: String?

    if (pickupRoute != null && pickupDurationMin != null && pickupDistanceKm != null) {
        // Total time = pickup time + ride time (in hours)
        val totalTimeHours = (pickupDurationMin + rideDurationMin) / 60.0

        // Total distance = pickup + ride (driver has to drive both, only gets paid for ride)
        // This gives accurate $/mile for total miles driven
        val totalDistanceKm = pickupDistanceKm + rideDistanceKm
        val totalDistanceForEarnings = if (distanceUnit == DistanceUnit.MILES) {
            totalDistanceKm * 0.621371
        } else {
            totalDistanceKm
        }

        earningsPerHour = if (totalTimeHours > 0) {
            formatEarnings(request.fareEstimate / totalTimeHours, displayCurrency, btcPrice, "/hr")
        } else null

        earningsPerDistance = if (totalDistanceForEarnings > 0) {
            val perDistanceUnit = if (distanceUnit == DistanceUnit.MILES) "/mi" else "/km"
            formatEarnings(request.fareEstimate / totalDistanceForEarnings, displayCurrency, btcPrice, perDistanceUnit)
        } else null
    } else {
        // Don't show earnings without pickup route - they'd be misleading
        earningsPerHour = null
        earningsPerDistance = null
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Fare + Time
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Bolt,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        FareDisplay(
                            satsAmount = request.fareEstimate,
                            settingsManager = settingsManager,
                            priceService = priceService,
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                }
                Text(
                    text = relativeTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Pickup info - distance/time from driver's location
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.DirectionsCar,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(6.dp))
                if (pickupDistanceStr != null && pickupDurationMin != null) {
                    Text(
                        text = "$pickupDistanceStr away",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = " • ${pickupDurationMin.toInt()} min to pickup",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = "Calculating route...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Ride info - distance/time of the trip
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.tertiary
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "$rideDistanceStr ride",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = " • ${rideDurationMin.toInt()} min trip",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Earnings metrics row
            if (earningsPerHour != null || earningsPerDistance != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // $/hr metric
                    earningsPerHour?.let { perHour ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = perHour,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    // $/mi or $/km metric
                    earningsPerDistance?.let { perDistance ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Straighten,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = perDistance,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            // Action buttons
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
                    Text("Pass")
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

/**
 * Format distance based on user's preferred unit.
 */
private fun formatDistance(distanceKm: Double, unit: DistanceUnit): String {
    return when (unit) {
        DistanceUnit.MILES -> {
            val miles = distanceKm * 0.621371
            if (miles < 0.1) {
                "${(miles * 5280).toInt()} ft"
            } else {
                String.format("%.1f mi", miles)
            }
        }
        DistanceUnit.KILOMETERS -> {
            if (distanceKm < 1) {
                "${(distanceKm * 1000).toInt()} m"
            } else {
                String.format("%.1f km", distanceKm)
            }
        }
    }
}

/**
 * Format earnings rate (sats/unit) with currency conversion.
 */
private fun formatEarnings(
    satsPerUnit: Double,
    displayCurrency: DisplayCurrency,
    btcPriceUsd: Int?,
    suffix: String
): String {
    return when (displayCurrency) {
        DisplayCurrency.SATS -> {
            "${satsPerUnit.toInt()} sats$suffix"
        }
        DisplayCurrency.USD -> {
            if (btcPriceUsd != null && btcPriceUsd > 0) {
                // Convert sats to USD: sats / 100_000_000 * btcPriceUsd
                val usdValue = (satsPerUnit / 100_000_000.0) * btcPriceUsd
                String.format("$%.2f$suffix", usdValue)
            } else {
                "${satsPerUnit.toInt()} sats$suffix"
            }
        }
    }
}
