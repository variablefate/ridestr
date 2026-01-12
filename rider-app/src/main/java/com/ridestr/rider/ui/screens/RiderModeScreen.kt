package com.ridestr.rider.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.ridestr.common.nostr.events.DriverAvailabilityData
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.events.RideshareChatData
import com.ridestr.common.nostr.events.UserProfile
import com.ridestr.common.routing.RouteResult
import com.ridestr.common.bitcoin.BitcoinPriceService
import com.ridestr.common.location.GeocodingResult
import com.ridestr.common.settings.DisplayCurrency
import com.ridestr.common.settings.SettingsManager
import com.ridestr.common.ui.ChatBottomSheet
import com.ridestr.common.ui.ChatButton
import com.ridestr.common.ui.FareDisplay
import com.ridestr.common.ui.formatFare
import com.ridestr.common.ui.LocationSearchField
import com.ridestr.common.ui.ManualCoordinateInput
import com.ridestr.rider.viewmodels.RideStage
import com.ridestr.rider.viewmodels.RiderUiState
import com.ridestr.rider.viewmodels.RiderViewModel

private const val TAG = "RiderModeScreen"

@Composable
fun RiderModeScreen(
    viewModel: RiderViewModel,
    settingsManager: SettingsManager,
    onOpenTiles: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    var showChatSheet by remember { mutableStateOf(false) }

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
        // Error message - tappable for routing errors, with X to dismiss
        uiState.error?.let { error ->
            val isRoutingError = error.contains("routing data", ignoreCase = true)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (isRoutingError) Modifier.clickable { onOpenTiles() }
                        else Modifier
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isRoutingError) "No routing data for this area" else error,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        if (isRoutingError) {
                            Text(
                                text = "Tap to download routing tiles",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                    IconButton(onClick = { viewModel.clearError() }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        when (uiState.rideStage) {
            RideStage.IDLE -> {
                // Collect geocoding state
                val pickupSearchResults by viewModel.pickupSearchResults.collectAsState()
                val destSearchResults by viewModel.destSearchResults.collectAsState()
                val isSearchingPickup by viewModel.isSearchingPickup.collectAsState()
                val isSearchingDest by viewModel.isSearchingDest.collectAsState()
                val useGeocodingSearch by settingsManager.useGeocodingSearch.collectAsState()
                val usingCurrentLocationForPickup by viewModel.usingCurrentLocationForPickup.collectAsState()
                val isFetchingLocation by viewModel.isFetchingLocation.collectAsState()

                // Location input and driver list
                IdleContent(
                    uiState = uiState,
                    useGeocodingSearch = useGeocodingSearch,
                    usingCurrentLocationForPickup = usingCurrentLocationForPickup,
                    isFetchingLocation = isFetchingLocation,
                    pickupSearchResults = pickupSearchResults,
                    destSearchResults = destSearchResults,
                    isSearchingPickup = isSearchingPickup,
                    isSearchingDest = isSearchingDest,
                    onSearchPickup = viewModel::searchPickupLocations,
                    onSearchDest = viewModel::searchDestLocations,
                    onSelectPickupFromSearch = viewModel::selectPickupFromSearch,
                    onSelectDestFromSearch = viewModel::selectDestFromSearch,
                    onUseCurrentLocation = { lat, lon -> viewModel.useCurrentLocationForPickup(lat, lon) },
                    onStopUsingCurrentLocation = viewModel::stopUsingCurrentLocationForPickup,
                    onClearPickup = viewModel::clearPickupLocation,
                    onClearDest = viewModel::clearDestination,
                    onSetPickup = viewModel::setPickupLocation,
                    onSetDestination = viewModel::setDestination,
                    onSelectDriver = viewModel::selectDriver,
                    onClearDriver = viewModel::clearSelectedDriver,
                    onSendOffer = viewModel::sendRideOffer,
                    onBroadcastRequest = {
                        // Request notification permission first (Android 13+)
                        requestNotificationPermissionIfNeeded()
                        // Then broadcast the ride request
                        viewModel.broadcastRideRequest()
                    },
                    onToggleExpandedSearch = viewModel::toggleExpandedSearch,
                    settingsManager = settingsManager,
                    priceService = viewModel.bitcoinPriceService
                )
            }
            RideStage.BROADCASTING_REQUEST -> {
                // Broadcast waiting screen with 2-minute countdown
                BroadcastWaitingContent(
                    uiState = uiState,
                    onBoostFare = viewModel::boostFare,
                    onContinueWaiting = viewModel::continueWaiting,
                    onCancel = viewModel::cancelBroadcastRequest,
                    settingsManager = settingsManager,
                    priceService = viewModel.bitcoinPriceService
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
            RideStage.DRIVER_ARRIVED -> {
                DriverArrivedContent(
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
    useGeocodingSearch: Boolean,
    usingCurrentLocationForPickup: Boolean,
    isFetchingLocation: Boolean,
    pickupSearchResults: List<GeocodingResult>,
    destSearchResults: List<GeocodingResult>,
    isSearchingPickup: Boolean,
    isSearchingDest: Boolean,
    onSearchPickup: (String) -> Unit,
    onSearchDest: (String) -> Unit,
    onSelectPickupFromSearch: (GeocodingResult) -> Unit,
    onSelectDestFromSearch: (GeocodingResult) -> Unit,
    onUseCurrentLocation: (Double?, Double?) -> Unit,
    onStopUsingCurrentLocation: () -> Unit,
    onClearPickup: () -> Unit,
    onClearDest: () -> Unit,
    onSetPickup: (Double, Double) -> Unit,
    onSetDestination: (Double, Double) -> Unit,
    onSelectDriver: (DriverAvailabilityData) -> Unit,
    onClearDriver: () -> Unit,
    onSendOffer: () -> Unit,
    onBroadcastRequest: () -> Unit,
    onToggleExpandedSearch: () -> Unit,
    settingsManager: SettingsManager,
    priceService: BitcoinPriceService
) {
    // Track whether advanced driver selection is expanded
    var showAdvancedDriverSelection by remember { mutableStateOf(false) }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Location input card - switches between geocoding and manual mode
        item {
            if (useGeocodingSearch) {
                GeocodingLocationInputCard(
                    pickupLocation = uiState.pickupLocation,
                    destination = uiState.destination,
                    pickupSearchResults = pickupSearchResults,
                    destSearchResults = destSearchResults,
                    isSearchingPickup = isSearchingPickup,
                    isSearchingDest = isSearchingDest,
                    usingCurrentLocationForPickup = usingCurrentLocationForPickup,
                    isFetchingLocation = isFetchingLocation,
                    routeResult = uiState.routeResult,
                    fareEstimate = uiState.fareEstimate,
                    isCalculatingRoute = uiState.isCalculatingRoute,
                    isSendingOffer = uiState.isSendingOffer,
                    onSearchPickup = onSearchPickup,
                    onSearchDest = onSearchDest,
                    onSelectPickupFromSearch = onSelectPickupFromSearch,
                    onSelectDestFromSearch = onSelectDestFromSearch,
                    onUseCurrentLocation = onUseCurrentLocation,
                    onStopUsingCurrentLocation = onStopUsingCurrentLocation,
                    onClearPickup = onClearPickup,
                    onClearDest = onClearDest,
                    onBroadcastRequest = onBroadcastRequest,
                    expandedSearch = uiState.expandedSearch,
                    nearbyDriverCount = uiState.nearbyDriverCount,
                    onToggleExpandedSearch = onToggleExpandedSearch,
                    settingsManager = settingsManager,
                    priceService = priceService
                )
            } else {
                ManualLocationInputCard(
                    pickupLocation = uiState.pickupLocation,
                    destination = uiState.destination,
                    onSetPickup = onSetPickup,
                    onSetDestination = onSetDestination
                )
            }
        }

        // Show advanced driver selection after route is calculated
        if (uiState.routeResult != null && uiState.fareEstimate != null) {
            // Advanced: Select specific driver (collapsible)
            item {
                TextButton(
                    onClick = { showAdvancedDriverSelection = !showAdvancedDriverSelection },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        if (showAdvancedDriverSelection) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (showAdvancedDriverSelection) "Hide driver list" else "Select specific driver (advanced)",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            // Driver list (only shown if expanded)
            if (showAdvancedDriverSelection) {
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
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "No drivers found yet...",
                                    textAlign = TextAlign.Center,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
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

                // Direct request card (for specific driver)
                if (uiState.selectedDriver != null && uiState.pickupLocation != null &&
                    uiState.destination != null) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        RequestRideCard(
                            driver = uiState.selectedDriver,
                            driverProfile = uiState.driverProfiles[uiState.selectedDriver.driverPubKey],
                            fareEstimate = uiState.fareEstimate,
                            isSending = uiState.isSendingOffer,
                            onRequest = onSendOffer,
                            onCancel = onClearDriver,
                            settingsManager = settingsManager,
                            priceService = priceService
                        )
                    }
                }
            }
        }
    }
}

/**
 * Geocoding-based location input with address search.
 * Pickup defaults to "Use current location" with a checkbox to switch to search.
 */
@Composable
private fun GeocodingLocationInputCard(
    pickupLocation: Location?,
    destination: Location?,
    pickupSearchResults: List<GeocodingResult>,
    destSearchResults: List<GeocodingResult>,
    isSearchingPickup: Boolean,
    isSearchingDest: Boolean,
    usingCurrentLocationForPickup: Boolean,
    isFetchingLocation: Boolean,
    routeResult: RouteResult?,
    fareEstimate: Double?,
    isCalculatingRoute: Boolean,
    isSendingOffer: Boolean,
    onSearchPickup: (String) -> Unit,
    onSearchDest: (String) -> Unit,
    onSelectPickupFromSearch: (GeocodingResult) -> Unit,
    onSelectDestFromSearch: (GeocodingResult) -> Unit,
    onUseCurrentLocation: (Double?, Double?) -> Unit,
    onStopUsingCurrentLocation: () -> Unit,
    onClearPickup: () -> Unit,
    onClearDest: () -> Unit,
    onBroadcastRequest: () -> Unit,
    expandedSearch: Boolean,
    nearbyDriverCount: Int,
    onToggleExpandedSearch: () -> Unit,
    settingsManager: SettingsManager,
    priceService: BitcoinPriceService
) {
    val context = LocalContext.current
    var pickupQuery by rememberSaveable { mutableStateOf("") }
    var destQuery by rememberSaveable { mutableStateOf("") }

    // Track if we need to fetch GPS after permission is granted
    var pendingLocationRequest by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // FusedLocationProviderClient for GPS
    val fusedLocationClient = remember { LocationServices.getFusedLocationProviderClient(context) }

    // Function to fetch GPS location and call the callback
    // Always tries to get GPS - ViewModel decides whether to use it based on demo setting
    fun fetchGpsLocation() {
        // Check if we have permission
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            // No permission - let ViewModel handle with null coords
            Log.w(TAG, "No location permission, passing null coords to ViewModel")
            onUseCurrentLocation(null, null)
            return
        }

        // We have permission, fetch location
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
                    onUseCurrentLocation(location.latitude, location.longitude)
                } else {
                    Log.w(TAG, "GPS returned null location")
                    // ViewModel will handle null coords based on demo setting
                    onUseCurrentLocation(null, null)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception fetching location", e)
                onUseCurrentLocation(null, null)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching GPS location", e)
                onUseCurrentLocation(null, null)
            }
        }
    }

    // Permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true

        Log.d(TAG, "Permission result: fine=$fineGranted, coarse=$coarseGranted")

        if (fineGranted || coarseGranted) {
            // Permission granted, fetch location if we have a pending request
            if (pendingLocationRequest) {
                pendingLocationRequest = false
                fetchGpsLocation()
            }
        } else {
            // Permission denied - ViewModel will handle null coords
            Log.w(TAG, "Location permission denied")
            pendingLocationRequest = false
            onUseCurrentLocation(null, null)
        }
    }

    // Wrapper function that handles permission request if needed
    // Always tries to get GPS - ViewModel decides whether to use it based on demo setting
    fun requestCurrentLocation() {
        // Check permission first
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            fetchGpsLocation()
        } else {
            // Request permission
            pendingLocationRequest = true
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    // Auto-use current location on first load if not already set
    LaunchedEffect(Unit) {
        if (pickupLocation == null && !usingCurrentLocationForPickup) {
            // Default to using current location
            requestCurrentLocation()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "Where are you going?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Pickup section with checkbox
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 8.dp)
            ) {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Pickup", style = MaterialTheme.typography.labelMedium)
            }

            // "Use current location" checkbox row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (usingCurrentLocationForPickup) {
                            onStopUsingCurrentLocation()
                        } else {
                            requestCurrentLocation()
                        }
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = usingCurrentLocationForPickup,
                    onCheckedChange = { checked ->
                        if (checked) {
                            requestCurrentLocation()
                        } else {
                            onStopUsingCurrentLocation()
                        }
                    }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Use current location",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (usingCurrentLocationForPickup && pickupLocation != null) {
                        Text(
                            text = pickupLocation.getDisplayString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    } else if (isFetchingLocation) {
                        Text(
                            text = "Getting location...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                if (isFetchingLocation) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                }
            }

            // Show search field only when not using current location
            if (!usingCurrentLocationForPickup) {
                LocationSearchField(
                    value = pickupQuery,
                    onValueChange = { newValue ->
                        pickupQuery = newValue
                        if (newValue.isEmpty()) {
                            onClearPickup()
                        }
                    },
                    selectedLocation = pickupLocation,
                    onLocationSelected = { result ->
                        onSelectPickupFromSearch(result)
                        pickupQuery = ""
                    },
                    searchResults = pickupSearchResults,
                    isSearching = isSearchingPickup,
                    onSearch = onSearchPickup,
                    placeholder = "Search pickup address...",
                    showMyLocation = true,
                    onUseMyLocation = { requestCurrentLocation() }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Destination search
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 4.dp)
            ) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Destination", style = MaterialTheme.typography.labelMedium)
            }

            LocationSearchField(
                value = destQuery,
                onValueChange = { newValue ->
                    destQuery = newValue
                    if (newValue.isEmpty()) {
                        onClearDest()
                    }
                },
                selectedLocation = destination,
                onLocationSelected = { result ->
                    onSelectDestFromSearch(result)
                    destQuery = ""
                },
                searchResults = destSearchResults,
                isSearching = isSearchingDest,
                onSearch = onSearchDest,
                placeholder = "Search destination address...",
                showMyLocation = false,
                onUseMyLocation = null
            )

            // Show Request Ride button when both locations are selected
            if (pickupLocation != null && destination != null) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                // Show route info summary if available
                if (routeResult != null && fareEstimate != null) {
                    val distanceUnit by settingsManager.distanceUnit.collectAsState()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = routeResult.getFormattedDistance(distanceUnit),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Distance",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = routeResult.getFormattedDuration(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Duration",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            FareDisplay(
                                satsAmount = fareEstimate,
                                settingsManager = settingsManager,
                                priceService = priceService,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Est. Fare",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Nearby drivers and search area toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.DirectionsCar,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "$nearbyDriverCount driver${if (nearbyDriverCount != 1) "s" else ""} nearby",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        FilterChip(
                            selected = expandedSearch,
                            onClick = onToggleExpandedSearch,
                            label = {
                                Text(
                                    if (expandedSearch) "Wide Area" else "Expand Search",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = if (expandedSearch)
                                        Icons.Default.ZoomOutMap
                                    else
                                        Icons.Default.ZoomIn,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Request Ride button
                    Button(
                        onClick = onBroadcastRequest,
                        enabled = !isSendingOffer,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isSendingOffer) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Requesting...")
                        } else {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Request Ride")
                        }
                    }
                } else if (isCalculatingRoute) {
                    // Show calculating state
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Calculating route...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Manual coordinate input for debug mode.
 */
@Composable
private fun ManualLocationInputCard(
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Trip Details",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                // Debug mode indicator
                Badge(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = "Manual Mode",
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }

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
    routeResult: RouteResult?,
    fareEstimate: Double?,
    isCalculating: Boolean,
    settingsManager: SettingsManager,
    priceService: com.ridestr.common.bitcoin.BitcoinPriceService
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
            val distanceUnit by settingsManager.distanceUnit.collectAsState()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = routeResult.getFormattedDistance(distanceUnit),
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
                    FareDisplay(
                        satsAmount = fareEstimate ?: 0.0,
                        settingsManager = settingsManager,
                        priceService = priceService,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
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
                // Show car info if available - prefer from availability event, fallback to profile
                (driver.vehicleDescription() ?: profile?.carDescription())?.let { car ->
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
    onCancel: () -> Unit,
    settingsManager: SettingsManager,
    priceService: com.ridestr.common.bitcoin.BitcoinPriceService
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
                settingsManager = settingsManager,
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
private fun BroadcastWaitingContent(
    uiState: RiderUiState,
    onBoostFare: () -> Unit,
    onContinueWaiting: () -> Unit,
    onCancel: () -> Unit,
    settingsManager: SettingsManager,
    priceService: com.ridestr.common.bitcoin.BitcoinPriceService
) {
    // Calculate progress based on broadcast timeout (2 minutes)
    val startTime = uiState.broadcastStartTimeMs
    val duration = uiState.broadcastTimeoutDurationMs

    // Animated progress state
    var progress by remember { mutableFloatStateOf(0f) }
    var remainingSeconds by remember { mutableIntStateOf((duration / 1000).toInt()) }
    var timedOut by remember { mutableStateOf(false) }

    // Update progress every 100ms
    LaunchedEffect(startTime) {
        if (startTime != null) {
            timedOut = false
            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                progress = (elapsed.toFloat() / duration).coerceIn(0f, 1f)
                remainingSeconds = ((duration - elapsed) / 1000).toInt().coerceAtLeast(0)
                if (remainingSeconds <= 0 && !timedOut) {
                    timedOut = true
                }
                delay(100)
            }
        }
    }

    // Get currency setting for boost display
    val displayCurrency by settingsManager.displayCurrency.collectAsState()
    val driverCount = uiState.nearbyDriverCount

    // State for price info dialog
    var showPriceInfoDialog by remember { mutableStateOf(false) }

    // Price info dialog
    if (showPriceInfoDialog) {
        AlertDialog(
            onDismissRequest = { showPriceInfoDialog = false },
            icon = {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text("About Pricing") },
            text = {
                Text(
                    "Displayed prices may fluctuate when shown in local currency (USD), " +
                    "as the underlying fare offer is locked to a set amount of Bitcoin (sats).\n\n" +
                    "Tap the boost badge to switch between USD and sats display."
                )
            },
            confirmButton = {
                TextButton(onClick = { showPriceInfoDialog = false }) {
                    Text("Got it")
                }
            }
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            // Boost badge in top-right corner (shows total boost in user's currency)
            // Tapping toggles currency setting
            if (uiState.totalBoostSats > 0) {
                val boostBadgeText = if (displayCurrency == DisplayCurrency.USD) {
                    // Convert sats to USD for display
                    val usdString = priceService.satsToUsdString(uiState.totalBoostSats.toLong())
                    "+${usdString ?: "${uiState.totalBoostSats.toInt()} sats"}"
                } else {
                    "+${uiState.totalBoostSats.toInt()} sats"
                }
                Badge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .clickable { settingsManager.toggleDisplayCurrency() },
                    containerColor = MaterialTheme.colorScheme.error
                ) {
                    Text(
                        text = boostBadgeText,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }

            // Info button in bottom-right corner
            IconButton(
                onClick = { showPriceInfoDialog = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(32.dp)
            ) {
                Icon(
                    Icons.Default.Help,
                    contentDescription = "Price info",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Show different UI based on timeout state
                if (timedOut) {
                    // Timeout state - show options
                    Icon(
                        Icons.Default.Timer,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.error
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = if (driverCount > 0) "No driver accepted yet" else "No drivers nearby",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (driverCount > 0)
                            "There are $driverCount driver${if (driverCount != 1) "s" else ""} in your area. Try boosting the fare to get their attention!"
                        else
                            "Try again later or expand your search area.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Keep waiting button with current fare
                    val currentFare = uiState.fareEstimate ?: 0.0
                    val fareText = formatFare(currentFare, settingsManager, priceService)
                    OutlinedButton(
                        onClick = onContinueWaiting,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Keep waiting ($fareText)")
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Show boost option if drivers are available
                    if (driverCount > 0) {
                        val boostText = if (displayCurrency == DisplayCurrency.USD) {
                            "Boost Fare (+$1)"
                        } else {
                            "Boost Fare (+1000 sats)"
                        }
                        Button(
                            onClick = onBoostFare,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.TrendingUp,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(boostText)
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Cancel button
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Cancel Request")
                    }
                } else {
                    // Normal searching state
                    // Circular countdown timer
                    Box(
                        modifier = Modifier.size(100.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val primaryColor = MaterialTheme.colorScheme.primary
                        val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant

                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokeWidth = 8.dp.toPx()
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

                        // Time remaining in center
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            val minutes = remainingSeconds / 60
                            val seconds = remainingSeconds % 60
                            Text(
                                text = String.format("%d:%02d", minutes, seconds),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Animated dots for "Searching"
                    var dotCount by remember { mutableIntStateOf(0) }
                    LaunchedEffect(Unit) {
                        while (true) {
                            delay(500)
                            dotCount = (dotCount + 1) % 4
                        }
                    }
                    val dots = ".".repeat(dotCount)

                    Text(
                        text = "Searching for drivers$dots",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Show driver count instead of generic message
                    Text(
                        text = if (driverCount > 0)
                            "There are currently $driverCount driver${if (driverCount != 1) "s" else ""} near you"
                        else
                            "Looking for available drivers in your area",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Current fare display
                    val currentFare = uiState.fareEstimate ?: 0.0
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            FareDisplay(
                                satsAmount = currentFare,
                                settingsManager = settingsManager,
                                priceService = priceService,
                                style = MaterialTheme.typography.titleSmall,
                                prefix = "Current fare: ",
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Boost fare button
                    val boostText = if (displayCurrency == DisplayCurrency.USD) {
                        "Boost Fare (+$1)"
                    } else {
                        "Boost Fare (+1000 sats)"
                    }
                    OutlinedButton(
                        onClick = onBoostFare,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Default.TrendingUp,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(boostText)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Cancel button
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Cancel Request")
                    }
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
            // Get driver info from acceptance
            val driverPubKey = uiState.acceptance?.driverPubKey
            val driverProfile = driverPubKey?.let { uiState.driverProfiles[it] }
            // Find driver availability data for vehicle info
            val driverAvailability = driverPubKey?.let { pk ->
                uiState.selectedDriver?.takeIf { it.driverPubKey == pk }
                    ?: uiState.availableDrivers.find { it.driverPubKey == pk }
            }

            Icon(
                Icons.Default.DirectionsCar,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Driver name
            Text(
                text = driverProfile?.bestName() ?: "Your driver",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            // Car description - prefer from availability event, fallback to profile
            (driverAvailability?.vehicleDescription() ?: driverProfile?.carDescription())?.let { carInfo ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = carInfo,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "is on the way to pick you up",
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
private fun DriverArrivedContent(
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
            // Get driver info from acceptance
            val driverPubKey = uiState.acceptance?.driverPubKey
            val driverProfile = driverPubKey?.let { uiState.driverProfiles[it] }
            // Find driver availability data for vehicle info
            val driverAvailability = driverPubKey?.let { pk ->
                uiState.selectedDriver?.takeIf { it.driverPubKey == pk }
                    ?: uiState.availableDrivers.find { it.driverPubKey == pk }
            }

            Icon(
                Icons.Default.LocationOn,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Your driver has arrived!",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Car description - show prominently so rider knows what to look for
            val carInfo = driverAvailability?.vehicleDescription() ?: driverProfile?.carDescription()
            if (carInfo != null) {
                Text(
                    text = "Look for the $carInfo",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    textAlign = TextAlign.Center
                )
            }

            // Driver name
            driverProfile?.bestName()?.let { name ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Driver: $name",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

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
                            text = "Tell this PIN to your driver",
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
