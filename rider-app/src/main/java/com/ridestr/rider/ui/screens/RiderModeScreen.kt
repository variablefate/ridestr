package com.ridestr.rider.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.vitorpamplona.quartz.nip01Core.core.hexToByteArray
import com.ridestr.common.nostr.events.AdminConfig
import com.ridestr.common.nostr.events.DriverAvailabilityData
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.events.RideshareChatData
import com.ridestr.common.nostr.events.UserProfile
import com.ridestr.common.routing.RouteResult
import com.ridestr.common.bitcoin.BitcoinPriceService
import com.ridestr.common.location.GeocodingResult
import com.ridestr.common.location.GeocodingService
import com.ridestr.common.settings.DisplayCurrency
import com.ridestr.common.settings.DistanceUnit
import com.ridestr.common.settings.SettingsManager
import com.ridestr.common.data.SavedLocation
import com.ridestr.common.ui.ChatBottomSheet
import com.ridestr.common.ui.ChatButton
import com.ridestr.common.ui.FareDisplay
import com.ridestr.common.ui.FavoritesSection
import com.ridestr.common.ui.formatFare
import com.ridestr.common.ui.LocationSearchField
import com.ridestr.common.ui.ManualCoordinateInput
import com.ridestr.common.ui.RecentsSection
import com.ridestr.common.data.FollowedDriversRepository
import com.ridestr.common.nostr.events.FollowedDriver
import com.ridestr.rider.viewmodels.RideStage
import com.ridestr.rider.viewmodels.RiderUiState
import com.ridestr.rider.viewmodels.RiderViewModel

private const val TAG = "RiderModeScreen"

/**
 * Mode for the unified RideWaitingContent component.
 * Determines text messaging and which ViewModel state to use.
 */
private enum class WaitingMode {
    BROADCAST,  // Public broadcast - waiting for any driver to accept
    DIRECT      // Direct offer - waiting for specific driver to respond
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RiderModeScreen(
    viewModel: RiderViewModel,
    settingsManager: SettingsManager,
    followedDriversRepository: FollowedDriversRepository? = null,
    onOpenTiles: () -> Unit,
    onOpenWallet: () -> Unit = {},
    onOpenWalletWithDeposit: (Long) -> Unit = {},  // Opens wallet with pre-filled deposit amount
    onRefreshSavedLocations: (suspend () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val remoteConfig by viewModel.remoteConfig.collectAsState()
    var showChatSheet by remember { mutableStateOf(false) }

    // Driver selection sheet state - lifted to RiderModeScreen level
    // so it can be opened from WAITING_FOR_ACCEPTANCE stage
    var showDriverSelectionSheet by remember { mutableStateOf(false) }

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

    // Driver selection bottom sheet - at RiderModeScreen level so it can be opened from any stage
    if (showDriverSelectionSheet) {
        ModalBottomSheet(
            onDismissRequest = { showDriverSelectionSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            DriverSelectionSheetContent(
                uiState = uiState,
                onSelectDriver = viewModel::selectDriver,
                onSendOffer = {
                    viewModel.sendRideOffer()
                    showDriverSelectionSheet = false
                },
                onClearDriver = viewModel::clearSelectedDriver,
                onExpandSearch = viewModel::toggleExpandedSearch,
                onBroadcastRequest = {
                    viewModel.broadcastRideRequest()
                    showDriverSelectionSheet = false
                },
                showBroadcastOption = false,  // Don't show broadcast when switching drivers
                onToggleBroadcastOption = { },
                settingsManager = settingsManager,
                priceService = viewModel.bitcoinPriceService
            )
        }
    }

    // Insufficient funds dialog
    if (uiState.showInsufficientFundsDialog) {
        val roadflarePaymentMethods = settingsManager.roadflarePaymentMethods.collectAsState()
        val hasAlternateMethods = uiState.insufficientFundsIsRoadflare &&
            roadflarePaymentMethods.value.isNotEmpty()

        AlertDialog(
            onDismissRequest = { viewModel.dismissInsufficientFundsDialog() },
            icon = {
                Icon(
                    Icons.Default.AccountBalanceWallet,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = { Text(if (uiState.insufficientFundsIsRoadflare) "Insufficient Bitcoin Funds" else "Insufficient Funds") },
            text = {
                Column {
                    Text(
                        "You need ${"%,d".format(uiState.insufficientFundsAmount)} more sats to request this ride."
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "This includes a small buffer for potential mint fees.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (hasAlternateMethods) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "Since this is a RoadFlare ride, you can continue with an alternate payment method.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    Button(
                        onClick = {
                            val depositAmount = uiState.depositAmountNeeded
                            viewModel.dismissInsufficientFundsDialog()
                            onOpenWalletWithDeposit(depositAmount)
                        }
                    ) {
                        Text("Deposit ${"%,d".format(uiState.depositAmountNeeded)} sats")
                    }
                    if (hasAlternateMethods) {
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = {
                                // Use the first configured alternate payment method
                                val method = roadflarePaymentMethods.value.first()
                                viewModel.sendRoadflareOfferWithAlternatePayment(method)
                            }
                        ) {
                            val methodName = roadflarePaymentMethods.value.firstOrNull()?.let { value ->
                                com.ridestr.common.nostr.events.PaymentMethod.fromString(value)?.displayName ?: value
                            } ?: "Alternate"
                            Text("Continue with $methodName")
                        }
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissInsufficientFundsDialog() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Cancel warning dialog (shown when cancelling after PIN verification)
    if (uiState.showCancelWarningDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCancelWarning() },
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
                    onClick = { viewModel.confirmCancelAfterWarning() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Cancel Anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissCancelWarning() }) {
                    Text("Keep Ride")
                }
            }
        )
    }

    // Driver unavailable dialog (shown when selected driver goes offline)
    if (uiState.showDriverUnavailableDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDriverUnavailable() },
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
                Button(onClick = { viewModel.dismissDriverUnavailable() }) {
                    Text("Select Another Driver")
                }
            }
        )
    }

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
                    settingsManager = settingsManager,
                    priceService = viewModel.bitcoinPriceService,
                    favorites = viewModel.getFavorites(),
                    recents = viewModel.getRecents(),
                    onPinWithNickname = viewModel::pinWithNickname,
                    onUpdateNickname = viewModel::updateFavoriteNickname,
                    onUnpinFavorite = viewModel::unpinFavorite,
                    onDeleteSavedLocation = viewModel::removeSavedLocation,
                    onSwapAddresses = viewModel::swapAddresses,
                    onRefreshSavedLocations = onRefreshSavedLocations,
                    // RoadFlare parameters
                    followedDriversRepository = followedDriversRepository,
                    nostrService = viewModel.getNostrService(),
                    roadflareRatePerMile = remoteConfig.roadflareFareRateUsdPerMile,
                    onSendRoadflareOffer = viewModel::sendRoadflareOffer,
                    onSendRoadflareToAll = viewModel::sendRoadflareToAll
                )
            }
            RideStage.BROADCASTING_REQUEST -> {
                // Broadcast waiting screen with 2-minute countdown
                RideWaitingContent(
                    mode = WaitingMode.BROADCAST,
                    uiState = uiState,
                    onBoostFare = viewModel::boostFare,  // BROADCAST boost - creates public event
                    onContinueWaiting = viewModel::continueWaiting,
                    onCancel = viewModel::cancelBroadcastRequest,
                    settingsManager = settingsManager,
                    priceService = viewModel.bitcoinPriceService
                )
            }
            RideStage.WAITING_FOR_ACCEPTANCE -> {
                // Direct offer waiting screen with same UI but different callbacks
                RideWaitingContent(
                    mode = WaitingMode.DIRECT,
                    uiState = uiState,
                    onBoostFare = viewModel::boostDirectOffer,  // DIRECT boost - sends to SAME driver
                    onContinueWaiting = viewModel::continueWaitingDirect,
                    onCancel = viewModel::cancelOffer,
                    onRequestAnotherDriver = {
                        viewModel.cancelOffer()         // Cancel current offer (deletes event, closes subscription)
                        showDriverSelectionSheet = true // Reopen driver list with same route
                    },
                    settingsManager = settingsManager,
                    priceService = viewModel.bitcoinPriceService
                )
            }
            RideStage.DRIVER_ACCEPTED -> {
                DriverAcceptedContent(
                    uiState = uiState,
                    onCancel = viewModel::attemptCancelRide,
                    onOpenChat = { showChatSheet = true },
                    chatMessageCount = uiState.chatMessages.size,
                    settingsManager = settingsManager,
                    priceService = viewModel.bitcoinPriceService
                )
            }
            RideStage.RIDE_CONFIRMED -> {
                DriverOnTheWayContent(
                    uiState = uiState,
                    onOpenChat = { showChatSheet = true },
                    onCancel = { viewModel.attemptCancelRide() },
                    chatMessageCount = uiState.chatMessages.size
                )
            }
            RideStage.DRIVER_ARRIVED -> {
                DriverArrivedContent(
                    uiState = uiState,
                    onOpenChat = { showChatSheet = true },
                    onCancel = { viewModel.attemptCancelRide() },
                    chatMessageCount = uiState.chatMessages.size
                )
            }
            RideStage.IN_PROGRESS -> {
                RideInProgressContent(
                    uiState = uiState,
                    onOpenChat = { showChatSheet = true },
                    onCancelRide = viewModel::attemptCancelRide,
                    chatMessageCount = uiState.chatMessages.size
                )
            }
            RideStage.COMPLETED -> {
                // Get driver info for favorite prompt
                val driverPubKey = uiState.acceptance?.driverPubKey
                val driverProfile = driverPubKey?.let { uiState.driverProfiles[it] }
                val driverName = driverProfile?.bestName()?.split(" ")?.firstOrNull()

                // Check if driver is already in favorites
                val followedDrivers by followedDriversRepository?.drivers?.collectAsState()
                    ?: remember { mutableStateOf(emptyList<FollowedDriver>()) }
                val isAlreadyFavorite = driverPubKey?.let { pubkey ->
                    followedDrivers.any { it.pubkey == pubkey }
                } ?: true // Treat as favorite if no pubkey (don't show prompt)

                RideCompletedContent(
                    driverPubKey = driverPubKey,
                    driverName = driverName,
                    isDriverAlreadyFavorite = isAlreadyFavorite,
                    onAddToFavorites = {
                        if (driverPubKey != null && followedDriversRepository != null) {
                            // Note: driver names are fetched from Nostr profiles, not stored
                            val newDriver = FollowedDriver(
                                pubkey = driverPubKey,
                                addedAt = System.currentTimeMillis() / 1000,
                                note = ""
                            )
                            followedDriversRepository.addDriver(newDriver)
                        }
                    },
                    onNewRide = viewModel::clearRide
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    settingsManager: SettingsManager,
    priceService: BitcoinPriceService,
    favorites: List<SavedLocation>,
    recents: List<SavedLocation>,
    onPinWithNickname: (String, String?) -> Unit,
    onUpdateNickname: (String, String?) -> Unit,
    onUnpinFavorite: (String) -> Unit,
    onDeleteSavedLocation: (String) -> Unit,
    onSwapAddresses: () -> Unit,
    onRefreshSavedLocations: (suspend () -> Unit)? = null,
    // RoadFlare parameters
    followedDriversRepository: FollowedDriversRepository? = null,
    nostrService: com.ridestr.common.nostr.NostrService? = null,
    roadflareRatePerMile: Double = AdminConfig.DEFAULT_ROADFLARE_FARE_RATE,
    onSendRoadflareOffer: (String, Location?) -> Unit = { _, _ -> },
    onSendRoadflareToAll: (List<FollowedDriver>, Map<String, Location>) -> Unit = { _, _ -> }
) {
    // Track whether driver selection sheet is shown
    var showDriverSelectionSheet by remember { mutableStateOf(false) }
    // Track whether RoadFlare driver selection sheet is shown
    var showRoadflareSheet by remember { mutableStateOf(false) }
    // Track whether advanced broadcast option is expanded (in the sheet)
    var showBroadcastOption by remember { mutableStateOf(false) }
    // Privacy warning dialog state
    var showPrivacyWarningDialog by remember { mutableStateOf(false) }

    // RoadFlare driver data
    val roadflareDrivers by followedDriversRepository?.drivers?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val hasRoadflareDrivers = roadflareDrivers.isNotEmpty()

    // Driver Selection Bottom Sheet
    if (showDriverSelectionSheet) {
        ModalBottomSheet(
            onDismissRequest = { showDriverSelectionSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            DriverSelectionSheetContent(
                uiState = uiState,
                onSelectDriver = onSelectDriver,
                onSendOffer = {
                    onSendOffer()
                    showDriverSelectionSheet = false
                },
                onClearDriver = onClearDriver,
                onExpandSearch = { }, // Wide area search is now always on by default
                onBroadcastRequest = { showPrivacyWarningDialog = true },
                showBroadcastOption = showBroadcastOption,
                onToggleBroadcastOption = { showBroadcastOption = !showBroadcastOption },
                settingsManager = settingsManager,
                priceService = priceService
            )
        }
    }

    // RoadFlare driver selection sheet
    if (showRoadflareSheet && followedDriversRepository != null) {
        ModalBottomSheet(
            onDismissRequest = { showRoadflareSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            RoadflareDriverSelectionSheet(
                drivers = roadflareDrivers,
                pickupLocation = uiState.pickupLocation,
                routeResult = uiState.routeResult,
                isSending = uiState.isSendingOffer,
                nostrService = nostrService,
                settingsManager = settingsManager,
                priceService = priceService,
                roadflareRatePerMile = roadflareRatePerMile,
                onSelectDriver = { driverPubKey, driverLocation ->
                    onSendRoadflareOffer(driverPubKey, driverLocation)
                    showRoadflareSheet = false
                },
                onSendToAll = { drivers, locations ->
                    onSendRoadflareToAll(drivers, locations)
                    showRoadflareSheet = false
                },
                onDismiss = { showRoadflareSheet = false }
            )
        }
    }

    // Privacy warning dialog for broadcast
    if (showPrivacyWarningDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyWarningDialog = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Privacy Warning") },
            text = {
                Text(
                    "Broadcasting will share your approximate location (~1km) publicly " +
                    "with all Nostr users. This can reveal patterns about where you " +
                    "frequently request rides (e.g., your home location).\n\n" +
                    "For better privacy, select a specific driver instead."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPrivacyWarningDialog = false
                        onBroadcastRequest()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Broadcast Anyway")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showPrivacyWarningDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

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
                    fareEstimate = uiState.fareEstimateWithFees,  // Display fare including 2% fee buffer
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
                    nearbyDriverCount = uiState.nearbyDriverCount,
                    onOpenRoadflare = { showRoadflareSheet = true },
                    hasRoadflareDrivers = hasRoadflareDrivers,
                    settingsManager = settingsManager,
                    priceService = priceService,
                    onRequestRide = { showDriverSelectionSheet = true },
                    favorites = favorites,
                    recents = recents,
                    onPinWithNickname = onPinWithNickname,
                    onUpdateNickname = onUpdateNickname,
                    onUnpinFavorite = onUnpinFavorite,
                    onDeleteSavedLocation = onDeleteSavedLocation,
                    onSwapAddresses = onSwapAddresses,
                    onRefreshSavedLocations = onRefreshSavedLocations
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
    }
}

/**
 * Geocoding-based location input with address search.
 * Pickup defaults to "Use current location" with a checkbox to switch to search.
 * After locations are set and route is calculated, displays route info summary.
 * Driver selection and request buttons are handled in the parent IdleContent.
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
    nearbyDriverCount: Int,
    onOpenRoadflare: () -> Unit,
    hasRoadflareDrivers: Boolean,
    settingsManager: SettingsManager,
    priceService: BitcoinPriceService,
    onRequestRide: () -> Unit,
    favorites: List<SavedLocation>,
    recents: List<SavedLocation>,
    onPinWithNickname: (String, String?) -> Unit,
    onUpdateNickname: (String, String?) -> Unit,
    onUnpinFavorite: (String) -> Unit,
    onDeleteSavedLocation: (String) -> Unit,
    onSwapAddresses: () -> Unit,
    onRefreshSavedLocations: (suspend () -> Unit)? = null
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

    // Auto-fetch GPS location on first load ONLY if user prefers GPS for pickup
    // This respects the saved preference - if user unchecked the box, don't auto-request
    LaunchedEffect(Unit) {
        if (pickupLocation == null && usingCurrentLocationForPickup) {
            // User prefers GPS for pickup - fetch current location
            requestCurrentLocation()
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Header
            Text(
                text = "Where are you going?",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Pickup section - compact header with toggle
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Place,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Pickup",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.weight(1f))
                // Compact toggle for current location
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable {
                            if (usingCurrentLocationForPickup) {
                                onStopUsingCurrentLocation()
                            } else {
                                requestCurrentLocation()
                            }
                        }
                        .padding(vertical = 4.dp)
                ) {
                    Checkbox(
                        checked = usingCurrentLocationForPickup,
                        onCheckedChange = { checked ->
                            if (checked) requestCurrentLocation() else onStopUsingCurrentLocation()
                        },
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "My location",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (isFetchingLocation) {
                        Spacer(modifier = Modifier.width(6.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 1.5.dp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Pickup field or current location display
            if (usingCurrentLocationForPickup) {
                // Show current location in a styled box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onStopUsingCurrentLocation() }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = pickupLocation?.getDisplayString() ?: "Getting location...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (pickupLocation != null)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            } else {
                LocationSearchField(
                    value = pickupQuery,
                    onValueChange = { newValue ->
                        pickupQuery = newValue
                        if (newValue.isEmpty()) onClearPickup()
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

            // Swap button row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                if (pickupLocation != null || destination != null) {
                    IconButton(
                        onClick = onSwapAddresses,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.SwapVert,
                            contentDescription = "Swap pickup and destination",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Destination section
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    "Destination",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            LocationSearchField(
                value = destQuery,
                onValueChange = { newValue ->
                    destQuery = newValue
                    if (newValue.isEmpty()) onClearDest()
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

            // Show saved locations when <2 addresses are set
            val showSavedLocations = pickupLocation == null || destination == null
            if (showSavedLocations && (favorites.isNotEmpty() || recents.isNotEmpty())) {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // Saved Locations header with refresh button
                var isRefreshing by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Saved Places",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (onRefreshSavedLocations != null) {
                        IconButton(
                            onClick = {
                                if (!isRefreshing) {
                                    scope.launch {
                                        isRefreshing = true
                                        try {
                                            onRefreshSavedLocations()
                                        } finally {
                                            isRefreshing = false
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.size(32.dp)
                        ) {
                            if (isRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Refresh saved locations",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Determine which field to populate when tapping
                // Fill pickup only if:
                // - pickup field is empty AND
                // - we're NOT using GPS for pickup
                // This means: if GPS is being used for pickup, always fill destination
                val populatePickup = pickupLocation == null && !usingCurrentLocationForPickup

                if (favorites.isNotEmpty()) {
                    FavoritesSection(
                        favorites = favorites,
                        onLocationSelected = { location ->
                            if (populatePickup) {
                                onSelectPickupFromSearch(location.toGeocodingResult())
                            } else {
                                onSelectDestFromSearch(location.toGeocodingResult())
                            }
                        },
                        onUpdateNickname = onUpdateNickname,
                        onUnpin = onUnpinFavorite,
                        onDelete = onDeleteSavedLocation
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (recents.isNotEmpty()) {
                    RecentsSection(
                        recents = recents,
                        onLocationSelected = { location ->
                            if (populatePickup) {
                                onSelectPickupFromSearch(location.toGeocodingResult())
                            } else {
                                onSelectDestFromSearch(location.toGeocodingResult())
                            }
                        },
                        onPinWithNickname = onPinWithNickname,
                        onDelete = onDeleteSavedLocation
                    )
                }
            }

            // Show route info summary when both locations are selected
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

                    // Nearby drivers count and RoadFlare button
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
                        // RoadFlare button - request from favorite drivers
                        if (hasRoadflareDrivers) {
                            FilledTonalButton(
                                onClick = onOpenRoadflare,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Flare,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    "RoadFlare",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Request Ride button - opens driver selection sheet
                    Button(
                        onClick = onRequestRide,
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

/**
 * Swipeable driver card that reveals request/dismiss actions on swipe or tap.
 */
@Composable
private fun SwipeableDriverCard(
    driver: DriverAvailabilityData,
    profile: UserProfile?,
    pickupLocation: Location?,
    onRequestRide: () -> Unit,
    settingsManager: SettingsManager
) {
    val distanceUnit by settingsManager.distanceUnit.collectAsState()
    var isRevealed by remember { mutableStateOf(false) }
    var offsetX by remember { mutableFloatStateOf(0f) }

    // Animate offset
    val animatedOffset by animateFloatAsState(
        targetValue = if (isRevealed) -140f else 0f,
        animationSpec = tween(200),
        label = "swipeOffset"
    )

    val actualOffset = if (isRevealed) animatedOffset else offsetX

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
    ) {
        // Background actions (revealed when swiping)
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Dismiss button (red X)
            IconButton(
                onClick = {
                    isRevealed = false
                    offsetX = 0f
                },
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(12.dp)
                    )
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            // Request button (green check)
            IconButton(
                onClick = {
                    onRequestRide()
                    isRevealed = false
                    offsetX = 0f
                },
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        Color(0xFF4CAF50), // Green
                        RoundedCornerShape(12.dp)
                    )
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = "Request Ride",
                    tint = Color.White
                )
            }
        }

        // Main card content
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .offset(x = actualOffset.dp)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            // Snap to revealed or hidden based on threshold
                            isRevealed = offsetX < -70f
                            offsetX = 0f
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            if (!isRevealed) {
                                offsetX = (offsetX + dragAmount).coerceIn(-140f, 0f)
                            }
                        }
                    )
                }
                .clickable {
                    // Toggle reveal on tap
                    isRevealed = !isRevealed
                    offsetX = 0f
                },
            colors = CardDefaults.cardColors()
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
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile?.bestName()?.split(" ")?.firstOrNull() ?: "Driver ${driver.driverPubKey.take(8)}...",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    (driver.vehicleDescription() ?: profile?.carDescription())?.let { car ->
                        Text(
                            text = car,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    val distanceText = pickupLocation?.let { pickup ->
                        val distanceKm = driver.approxLocation.distanceToKm(pickup)
                        formatDriverDistance(distanceKm, distanceUnit)
                    } ?: "Nearby"
                    Text(
                        text = distanceText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Hint icon for swipe
                Icon(
                    Icons.Default.ChevronLeft,
                    contentDescription = "Swipe for options",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun DriverCard(
    driver: DriverAvailabilityData,
    profile: UserProfile?,
    pickupLocation: Location?,
    isSelected: Boolean,
    onSelect: () -> Unit,
    settingsManager: SettingsManager
) {
    val distanceUnit by settingsManager.distanceUnit.collectAsState()

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
                    text = profile?.bestName()?.split(" ")?.firstOrNull() ?: "Driver ${driver.driverPubKey.take(8)}...",
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
                // Show distance from pickup location
                val distanceText = pickupLocation?.let { pickup ->
                    val distanceKm = driver.approxLocation.distanceToKm(pickup)
                    formatDriverDistance(distanceKm, distanceUnit)
                } ?: "Nearby"
                Text(
                    text = distanceText,
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

/**
 * Format distance for driver card display.
 */
private fun formatDriverDistance(distanceKm: Double, unit: DistanceUnit): String {
    return when (unit) {
        DistanceUnit.MILES -> {
            val miles = distanceKm * 0.621371
            when {
                miles < 0.1 -> "${(miles * 5280).toInt()} ft away"
                miles < 10 -> String.format("%.1f mi away", miles)
                else -> String.format("%.0f mi away", miles)
            }
        }
        DistanceUnit.KILOMETERS -> {
            when {
                distanceKm < 1 -> "${(distanceKm * 1000).toInt()} m away"
                distanceKm < 10 -> String.format("%.1f km away", distanceKm)
                else -> String.format("%.0f km away", distanceKm)
            }
        }
    }
}

/**
 * Full-screen driver selection content shown in a bottom sheet.
 * Shows available drivers immediately when opened, with privacy indicator.
 */
@Composable
private fun DriverSelectionSheetContent(
    uiState: RiderUiState,
    onSelectDriver: (DriverAvailabilityData) -> Unit,
    onSendOffer: () -> Unit,
    onClearDriver: () -> Unit,
    onExpandSearch: () -> Unit,
    onBroadcastRequest: () -> Unit,
    showBroadcastOption: Boolean,
    onToggleBroadcastOption: () -> Unit,
    settingsManager: SettingsManager,
    priceService: BitcoinPriceService
) {
    val drivers = uiState.availableDrivers
    val selectedDriver = uiState.selectedDriver
    val driverProfiles = uiState.driverProfiles
    val isSending = uiState.isSendingOffer
    val pickupLocation = uiState.pickupLocation

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp)
    ) {
        // Header with privacy indicator
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Select a Driver",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            // Privacy indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Encrypted",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Your request will be sent privately using encrypted messages",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (drivers.isEmpty()) {
            // No drivers available - show waiting state
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(48.dp),
                    strokeWidth = 3.dp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Waiting for available drivers...",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Drivers will appear here as they become available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))

                // Expand search button
                OutlinedButton(onClick = onExpandSearch) {
                    Icon(
                        Icons.Default.ZoomOutMap,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (uiState.expandedSearch) "Search Area Expanded" else "Expand Search Area")
                }
            }
        } else {
            // Hint text
            Text(
                text = "Tap or swipe left on a driver to send request",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            // Driver list with swipeable cards
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 350.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(drivers, key = { it.driverPubKey }) { driver ->
                    SwipeableDriverCard(
                        driver = driver,
                        profile = driverProfiles[driver.driverPubKey],
                        pickupLocation = pickupLocation,
                        onRequestRide = {
                            // Select driver and send offer in one action
                            onSelectDriver(driver)
                            onSendOffer()
                        },
                        settingsManager = settingsManager
                    )
                }
            }

            // Show sending indicator if offer in progress
            if (isSending) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sending request...", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Expand search area option
            if (!uiState.expandedSearch) {
                TextButton(
                    onClick = onExpandSearch,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        Icons.Default.ZoomIn,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Expand Search Area")
                }
            } else {
                // Show expanded search indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.ZoomOutMap,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        "Wide area search active",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Advanced: Broadcast option (with privacy warning)
        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggleBroadcastOption() }
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.SettingsInputAntenna,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Advanced: Public Broadcast",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                if (showBroadcastOption) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (showBroadcastOption) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Reduced Privacy",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Broadcasting shares your approximate location publicly with all Nostr users.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onBroadcastRequest,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(
                            Icons.Default.SettingsInputAntenna,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Broadcast Request")
                    }
                }
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

/**
 * Unified ride waiting content - used for both broadcast and direct request flows.
 * Provides consistent UI with mode-specific text and callbacks.
 */
@Composable
private fun RideWaitingContent(
    mode: WaitingMode,
    uiState: RiderUiState,
    onBoostFare: () -> Unit,
    onContinueWaiting: () -> Unit,
    onCancel: () -> Unit,
    onRequestAnotherDriver: (() -> Unit)? = null,  // Only for DIRECT mode
    settingsManager: SettingsManager,
    priceService: BitcoinPriceService
) {
    // Mode-specific values
    val startTime = when (mode) {
        WaitingMode.BROADCAST -> uiState.broadcastStartTimeMs
        WaitingMode.DIRECT -> uiState.acceptanceTimeoutStartMs
    }
    val duration = when (mode) {
        WaitingMode.BROADCAST -> uiState.broadcastTimeoutDurationMs
        WaitingMode.DIRECT -> uiState.acceptanceTimeoutDurationMs
    }
    val timedOut = when (mode) {
        WaitingMode.BROADCAST -> uiState.broadcastTimedOut
        WaitingMode.DIRECT -> uiState.directOfferTimedOut
    }
    val totalBoostSats = when (mode) {
        WaitingMode.BROADCAST -> uiState.totalBoostSats
        WaitingMode.DIRECT -> uiState.directOfferBoostSats
    }
    val driverCount = uiState.nearbyDriverCount
    val driverName = uiState.selectedDriver?.let {
        uiState.driverProfiles[it.driverPubKey]?.bestName()?.split(" ")?.firstOrNull()
    } ?: "driver"

    // Animated progress state
    var progress by remember { mutableFloatStateOf(0f) }
    var remainingSeconds by remember { mutableIntStateOf((duration / 1000).toInt()) }

    // Update progress every 100ms (only when timer is running)
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

    // Get currency setting for boost display
    val displayCurrency by settingsManager.displayCurrency.collectAsState()

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
            if (totalBoostSats > 0) {
                val boostBadgeText = if (displayCurrency == DisplayCurrency.USD) {
                    val usdString = priceService.satsToUsdString(totalBoostSats.toLong())
                    "+${usdString ?: "${totalBoostSats.toInt()} sats"}"
                } else {
                    "+${totalBoostSats.toInt()} sats"
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

                    // Mode-specific timeout message
                    val timeoutTitle = when (mode) {
                        WaitingMode.BROADCAST -> if (driverCount > 0) "No driver accepted yet" else "No drivers nearby"
                        WaitingMode.DIRECT -> "No response from $driverName yet"
                    }
                    Text(
                        text = timeoutTitle,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Mode-specific explanation
                    val timeoutExplanation = when (mode) {
                        WaitingMode.BROADCAST -> if (driverCount > 0)
                            "There are $driverCount driver${if (driverCount != 1) "s" else ""} in your area. Try boosting the fare to get their attention!"
                        else
                            "Try again later or expand your search area."
                        WaitingMode.DIRECT -> "You can boost your fare to get their attention, or try a different driver."
                    }
                    Text(
                        text = timeoutExplanation,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Keep waiting button with current fare (displayed with fee buffer)
                    val currentFare = uiState.fareEstimateWithFees ?: 0.0
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

                    // Show boost option (always for direct, only if drivers available for broadcast)
                    val showBoostButton = when (mode) {
                        WaitingMode.BROADCAST -> driverCount > 0
                        WaitingMode.DIRECT -> true
                    }
                    if (showBoostButton) {
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

                    // Request another driver button (DIRECT mode only)
                    if (mode == WaitingMode.DIRECT && onRequestAnotherDriver != null) {
                        OutlinedButton(
                            onClick = onRequestAnotherDriver,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.SwapHoriz,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Request Another Driver")
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
                    // Normal searching state - Circular countdown timer
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

                        // Time remaining in center (mm:ss format)
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

                    // Animated dots for status text
                    var dotCount by remember { mutableIntStateOf(0) }
                    LaunchedEffect(Unit) {
                        while (true) {
                            delay(500)
                            dotCount = (dotCount + 1) % 4
                        }
                    }
                    val dots = ".".repeat(dotCount)

                    // Mode-specific status text
                    val statusText = when (mode) {
                        WaitingMode.BROADCAST -> "Searching for drivers$dots"
                        WaitingMode.DIRECT -> "Waiting for $driverName$dots"
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Mode-specific subtitle
                    val subtitleText = when (mode) {
                        WaitingMode.BROADCAST -> if (driverCount > 0)
                            "There are currently $driverCount driver${if (driverCount != 1) "s" else ""} near you"
                        else
                            "Looking for available drivers in your area"
                        WaitingMode.DIRECT -> "Your offer has been sent to the driver"
                    }
                    Text(
                        text = subtitleText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Current fare display (with fee buffer)
                    val currentFare = uiState.fareEstimateWithFees ?: 0.0
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

                    // Request another driver button (DIRECT mode only)
                    if (mode == WaitingMode.DIRECT && onRequestAnotherDriver != null) {
                        OutlinedButton(
                            onClick = onRequestAnotherDriver,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                Icons.Default.SwapHoriz,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Request Another Driver")
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
                }
            }
        }
    }
}

// NOTE: Old WaitingForAcceptanceContent and BroadcastWaitingContent functions removed.
// Both now use the unified RideWaitingContent component above.

@Composable
private fun DriverAcceptedContent(
    uiState: RiderUiState,
    onCancel: () -> Unit,
    onOpenChat: () -> Unit,
    chatMessageCount: Int,
    settingsManager: com.ridestr.common.settings.SettingsManager,
    priceService: com.ridestr.common.bitcoin.BitcoinPriceService
) {
    // Get driver info
    val driverPubKey = uiState.acceptance?.driverPubKey
    val driverProfile = driverPubKey?.let { uiState.driverProfiles[it] }
    val driverAvailability = driverPubKey?.let { pk ->
        uiState.selectedDriver?.takeIf { it.driverPubKey == pk }
            ?: uiState.availableDrivers.find { it.driverPubKey == pk }
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
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Status header with spinner
            // AtoB Pattern: Show "Ride Confirmed!" when confirmationEventId is set,
            // even though rideStage is still DRIVER_ACCEPTED (waiting for driver to start)
            val isRideConfirmed = uiState.confirmationEventId != null
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (uiState.isConfirmingRide) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Confirming ride...",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                } else {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (isRideConfirmed) "Ride Confirmed!" else "Driver Accepted!",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Show PIN prominently when ride is confirmed
            if (isRideConfirmed && uiState.pickupPin != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Your PIN",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = uiState.pickupPin,
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tell this to your driver when they arrive",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
                // Status message for waiting state
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Waiting for driver to start...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Driver info row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Driver icon
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
                    // Use vehicleDescription() like DriverOnTheWayContent does
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
                                    settingsManager = settingsManager,
                                    priceService = priceService
                                ),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
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

            Spacer(modifier = Modifier.height(12.dp))

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
            containerColor = MaterialTheme.colorScheme.surfaceVariant
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
                text = driverProfile?.bestName()?.split(" ")?.firstOrNull() ?: "Your driver",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            // Car description - prefer from availability event, fallback to profile
            (driverAvailability?.vehicleDescription() ?: driverProfile?.carDescription())?.let { carInfo ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = carInfo,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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

            // Driver name (first name only)
            driverProfile?.bestName()?.split(" ")?.firstOrNull()?.let { name ->
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
    val context = LocalContext.current
    val geocodingService = remember { GeocodingService(context) }
    var destinationAddress by remember { mutableStateOf<String?>(null) }

    // Reverse geocode destination to show address
    LaunchedEffect(uiState.destination) {
        uiState.destination?.let { dest ->
            destinationAddress = geocodingService.reverseGeocode(dest.lat, dest.lon)
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
                text = "RIDE IN PROGRESS",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            destinationAddress?.let { address ->
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
            }

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

/**
 * RoadFlare driver selection sheet.
 * Shows favorite drivers with real-time status and fare calculations.
 */
@Composable
private fun RoadflareDriverSelectionSheet(
    drivers: List<FollowedDriver>,
    pickupLocation: Location?,
    routeResult: RouteResult?,
    isSending: Boolean,
    nostrService: com.ridestr.common.nostr.NostrService?,
    settingsManager: SettingsManager,
    priceService: BitcoinPriceService,
    roadflareRatePerMile: Double = AdminConfig.DEFAULT_ROADFLARE_FARE_RATE,
    onSelectDriver: (String, Location?) -> Unit,
    onSendToAll: (List<FollowedDriver>, Map<String, Location>) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()

    // Track driver locations by pubkey (from Kind 30014)
    val driverLocations = remember { mutableStateMapOf<String, Location>() }
    val driverNames = remember { mutableStateMapOf<String, String>() }

    // Subscribe to driver locations
    var locationSubId by remember { mutableStateOf<String?>(null) }

    DisposableEffect(drivers, nostrService) {
        if (nostrService == null) return@DisposableEffect onDispose {}

        // Fetch driver profile names
        drivers.forEach { driver ->
            nostrService.subscribeToProfile(driver.pubkey) { profile ->
                profile.name?.let { name ->
                    driverNames[driver.pubkey] = name.split(" ").firstOrNull() ?: name
                }
            }
        }

        // Subscribe to driver locations - need drivers with keys
        val driversWithKeys = drivers.filter { it.roadflareKey != null }
        if (driversWithKeys.isNotEmpty()) {
            locationSubId = nostrService.subscribeToRoadflareLocations(
                driverPubkeys = driversWithKeys.map { it.pubkey }
            ) { event, relayUrl ->
                // Find the driver and decrypt their location
                val driver = driversWithKeys.find { it.pubkey == event.pubKey }
                if (driver?.roadflareKey != null) {
                    val locationData = decryptRoadflareLocation(
                        roadflarePrivKey = driver.roadflareKey!!.privateKey,
                        driverPubKey = event.pubKey,
                        event = event
                    )
                    if (locationData != null) {
                        driverLocations[event.pubKey] = Location(
                            lat = locationData.location.lat,
                            lon = locationData.location.lon
                        )
                    }
                }
            }
        }

        onDispose {
            locationSubId?.let { nostrService.closeSubscription(it) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Flare,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "RoadFlare",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            // Privacy indicator
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Private",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Request a ride from your favorite drivers",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (drivers.isEmpty()) {
            // No drivers
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.PersonAdd,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "No favorite drivers yet",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Add drivers from the RoadFlare tab to request rides directly",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            // Driver list
            Text(
                text = "Tap a driver to send request",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 350.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(drivers, key = { it.pubkey }) { driver ->
                    val driverLocation = driverLocations[driver.pubkey]
                    val driverName = driverNames[driver.pubkey] ?: driver.pubkey.take(8) + "..."
                    val hasKey = driver.roadflareKey != null
                    val isOnline = driverLocation != null

                    // Calculate fare if we have location
                    val fareSats = if (driverLocation != null && pickupLocation != null) {
                        calculateRoadflareFareSats(pickupLocation, driverLocation, routeResult, roadflareRatePerMile)
                    } else null

                    val fare = if (fareSats != null) {
                        formatFare(fareSats, settingsManager, priceService)
                    } else null

                    RoadflareDriverCard(
                        driverName = driverName,
                        driverPubKey = driver.pubkey,
                        isOnline = isOnline,
                        hasKey = hasKey,
                        fare = fare,
                        note = driver.note,
                        enabled = hasKey && !isSending,
                        onClick = {
                            onSelectDriver(driver.pubkey, driverLocation)
                        }
                    )
                }
            }

            // Show sending indicator
            if (isSending) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sending request...", style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Send to all button
            val eligibleDrivers = drivers.filter { it.roadflareKey != null }
            Button(
                onClick = {
                    onSendToAll(eligibleDrivers, driverLocations.toMap())
                },
                enabled = eligibleDrivers.isNotEmpty() && !isSending,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Campaign,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Send to All Favorites (${eligibleDrivers.size})")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Sends your request to all favorite drivers at once",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Card for a RoadFlare driver in the selection sheet.
 */
@Composable
private fun RoadflareDriverCard(
    driverName: String,
    driverPubKey: String,
    isOnline: Boolean,
    hasKey: Boolean,
    fare: String?,
    note: String?,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                // Status indicator
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            color = when {
                                !hasKey -> MaterialTheme.colorScheme.outline
                                isOnline -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.outline
                            },
                            shape = RoundedCornerShape(6.dp)
                        )
                )
                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = driverName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (enabled)
                            MaterialTheme.colorScheme.onSurface
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Text(
                        text = when {
                            !hasKey -> "Pending approval"
                            isOnline -> "Available"
                            else -> "Offline"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            !hasKey -> MaterialTheme.colorScheme.outline
                            isOnline -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (!note.isNullOrBlank()) {
                        Text(
                            text = note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Fare display
            if (fare != null && isOnline) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = fare,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "est. fare",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (!hasKey) {
                Icon(
                    Icons.Default.HourglassEmpty,
                    contentDescription = "Pending",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

/**
 * Calculate RoadFlare fare in sats.
 */
private fun calculateRoadflareFareSats(
    pickup: Location,
    driverLocation: Location,
    routeResult: RouteResult?,
    ratePerMile: Double = AdminConfig.DEFAULT_ROADFLARE_FARE_RATE
): Double {
    val ROADFLARE_RATE_PER_MILE = ratePerMile
    val METERS_PER_MILE = 1609.34

    // Driver distance to pickup (haversine)
    val R = 6371000.0
    val dLat = Math.toRadians(pickup.lat - driverLocation.lat)
    val dLon = Math.toRadians(pickup.lon - driverLocation.lon)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(driverLocation.lat)) * Math.cos(Math.toRadians(pickup.lat)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    val driverToPickupMeters = R * c
    val driverToPickupMiles = driverToPickupMeters / METERS_PER_MILE

    // Ride distance
    val rideMiles = routeResult?.let { it.distanceKm * 0.621371 } ?: 0.0

    // Total fare with minimum fare enforcement
    val baseFare = 2.50
    val minimumFareUsd = 5.0
    val calculatedFare = baseFare + (driverToPickupMiles * ROADFLARE_RATE_PER_MILE) + (rideMiles * ROADFLARE_RATE_PER_MILE)
    val totalFareUsd = maxOf(calculatedFare, minimumFareUsd)
    // Convert USD to sats using live BTC price
    val sats = BitcoinPriceService.getInstance().usdToSats(totalFareUsd)
    return sats?.toDouble() ?: (totalFareUsd * 1000.0) // Fallback: ~$100k BTC
}
