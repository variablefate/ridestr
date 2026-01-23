package com.drivestr.app.viewmodels

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drivestr.app.service.DriverOnlineService
import com.drivestr.app.service.DriverStackableAlert
import com.drivestr.app.service.DriverStatus
import com.ridestr.common.bitcoin.BitcoinPriceService
import com.ridestr.common.data.RideHistoryRepository
import com.ridestr.common.data.Vehicle
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.RideHistoryEntry
import com.ridestr.common.nostr.events.geohash
import com.ridestr.common.nostr.events.BroadcastRideOfferData
import com.ridestr.common.nostr.events.DriverRideAction
import com.ridestr.common.nostr.events.DriverRideStateEvent
import com.ridestr.common.nostr.events.DriverStatusType
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.events.PaymentPath
import com.ridestr.common.nostr.events.RideConfirmationData
import com.ridestr.common.nostr.events.RideOfferData
import com.ridestr.common.nostr.events.RiderRideAction
import com.ridestr.common.nostr.events.RiderRideStateData
import com.ridestr.common.nostr.events.RiderRideStateEvent
import com.ridestr.common.nostr.events.RideshareChatData
import com.ridestr.common.nostr.events.RideshareEventKinds
import com.ridestr.common.nostr.events.UserProfile
import com.ridestr.common.payment.ClaimResult
import com.ridestr.common.payment.PaymentCrypto
import com.ridestr.common.payment.WalletService
import com.ridestr.common.routing.RouteResult
import com.ridestr.common.routing.ValhallaRoutingService
import com.ridestr.common.state.RideContext
import com.ridestr.common.state.RideEvent
import com.ridestr.common.state.RideState
import com.ridestr.common.state.RideStateMachine
import com.ridestr.common.state.TransitionResult
import com.ridestr.common.state.fromDriverStage
import com.ridestr.common.state.toDriverStageName
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Driver state machine stages.
 */
enum class DriverStage {
    OFFLINE,            // Not available for rides
    AVAILABLE,          // Broadcasting availability, waiting for ride requests
    RIDE_ACCEPTED,      // Accepted a ride, waiting for rider confirmation
    EN_ROUTE_TO_PICKUP, // Driving to pick up the rider
    ARRIVED_AT_PICKUP,  // At the pickup location, waiting for rider
    IN_RIDE,            // Rider is in the car, driving to destination
    RIDE_COMPLETED      // Ride finished, ready for payment
}

/**
 * ViewModel for Driver mode.
 */
class DriverViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DriverViewModel"
        // Broadcast availability every 5 minutes to stay visible to riders
        private const val AVAILABILITY_BROADCAST_INTERVAL_MS = 5 * 60 * 1000L
        // Refresh chat subscription every 15 seconds to ensure messages are received
        private const val CHAT_REFRESH_INTERVAL_MS = 15 * 1000L
        // Timeout for PIN verification response (30 seconds)
        private const val PIN_VERIFICATION_TIMEOUT_MS = 30 * 1000L
        // Timeout for rider confirmation after accepting (30 seconds)
        // If no confirmation arrives, the ride was likely cancelled
        private const val CONFIRMATION_TIMEOUT_MS = 30 * 1000L
        // SharedPreferences keys for ride state persistence
        private const val PREFS_NAME = "drivestr_ride_state"
        private const val KEY_RIDE_STATE = "active_ride"
        // Maximum age of persisted ride state (2 hours)
        private const val MAX_RIDE_STATE_AGE_MS = 2 * 60 * 60 * 1000L
        // Route cache settings
        private const val LOCATION_CACHE_PRECISION = 3 // ~100m precision for cache key
        private const val DRIVER_MOVEMENT_THRESHOLD_KM = 0.5 // Recalculate if driver moved >500m
        private const val ROUTE_RECALC_THROTTLE_MS = 30 * 1000L // Min 30s between full recalculations
        // Location update throttling - don't spam relays with frequent updates
        private const val MIN_LOCATION_UPDATE_DISTANCE_M = 1000.0 // Min 1000m movement
        private const val MIN_LOCATION_UPDATE_INTERVAL_MS = 30 * 1000L // Min 30 seconds between updates

        /**
         * Calculate distance between two locations using Haversine formula.
         * @return Distance in meters
         */
        fun calculateDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val earthRadiusM = 6371000.0 // Earth's radius in meters
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                    Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                    Math.sin(dLon / 2) * Math.sin(dLon / 2)
            val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
            return earthRadiusM * c
        }
    }

    // Track last broadcast for throttling
    private var lastBroadcastLocation: Location? = null
    private var lastBroadcastTimeMs: Long = 0L

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Settings manager for user preferences
    private val settingsManager = com.ridestr.common.settings.SettingsManager(application)

    private val nostrService = NostrService(application)

    // Wallet service for HTLC escrow settlement (injected from MainActivity)
    private var walletService: WalletService? = null

    fun setWalletService(service: WalletService?) {
        walletService = service
    }

    // Ride history repository
    private val rideHistoryRepository = RideHistoryRepository.getInstance(application)

    // Bitcoin price service for fare conversion
    val bitcoinPriceService = BitcoinPriceService.getInstance()

    // Valhalla routing service for calculating distances
    private val routingService = ValhallaRoutingService(application)

    // Cache for pickup route calculations
    // Key: location pair hash (driver location + pickup location, rounded to ~100m precision)
    // This allows reuse when rider boosts fare (new event, same locations)
    private val locationRouteCache = mutableMapOf<String, RouteResult>()

    // Map eventId -> cache key for UI state lookup
    private val eventToCacheKey = mutableMapOf<String, String>()

    // Track the driver location used for each cached route (to invalidate on significant movement)
    private var lastCacheDriverLocation: Location? = null
    // Track last time we did a full route recalculation (throttle to avoid excessive recalcs)
    private var lastRouteRecalcTimeMs: Long = 0

    private val _uiState = MutableStateFlow(DriverUiState())
    val uiState: StateFlow<DriverUiState> = _uiState.asStateFlow()

    private var availabilityJob: Job? = null
    private var chatRefreshJob: Job? = null
    private var pinVerificationTimeoutJob: Job? = null
    private var confirmationTimeoutJob: Job? = null  // Timeout for rider confirmation after acceptance
    private var offerSubscriptionId: String? = null                    // Direct offers (legacy/advanced)
    private var broadcastRequestSubscriptionId: String? = null         // Broadcast ride requests (new flow)
    private var confirmationSubscriptionId: String? = null
    private var chatSubscriptionId: String? = null
    private var cancellationSubscriptionId: String? = null
    private var deletionSubscriptionId: String? = null           // Watch for cancelled ride requests (NIP-09)
    private val publishedAvailabilityEventIds = mutableListOf<String>()
    // Track accepted offer IDs to filter out when resubscribing (avoids duplicate offers after ride completion)
    private val acceptedOfferEventIds = mutableSetOf<String>()
    // Track subscription IDs for watching acceptances of each visible ride request
    private val requestAcceptanceSubscriptionIds = mutableMapOf<String, String>()
    // Track offer IDs that have been taken by another driver
    private val takenOfferEventIds = mutableSetOf<String>()
    // Track offer IDs that the driver has declined/passed on
    private val declinedOfferEventIds = mutableSetOf<String>()
    // Unified tracker: ALL events I publish during a ride (for cleanup on completion/cancellation)
    // Includes: acceptance, status updates, PIN submission, chat messages
    // Thread-safe list since events are added from multiple coroutines
    private val myRideEventIds = java.util.Collections.synchronizedList(mutableListOf<String>())

    // Driver ride state history for consolidated Kind 30180 events
    // This accumulates all driver actions during a ride (status changes, PIN submissions)
    // THREAD SAFETY: Use synchronized list and historyMutex to prevent race conditions
    private val driverStateHistory = java.util.Collections.synchronizedList(mutableListOf<DriverRideAction>())
    private val historyMutex = kotlinx.coroutines.sync.Mutex()

    // Track how many rider actions we've processed (to detect new actions)
    private var lastProcessedRiderActionCount = 0

    // Track last received rider state event ID for chain integrity (AtoB pattern)
    private var lastReceivedRiderStateId: String? = null

    // Subscription ID for rider ride state (replaces verificationSubscriptionId and preciseLocationSubscriptionId)
    private var riderRideStateSubscriptionId: String? = null

    // Rider profile subscription tracking
    private val riderProfileSubscriptionIds = mutableMapOf<String, String>()

    // === STATE MACHINE (Phase 1: Validation Only) ===
    // The state machine validates transitions but doesn't control flow yet.
    // It logs warnings when existing code attempts invalid transitions.
    private val stateMachine = RideStateMachine()
    private var rideState: RideState = RideState.CANCELLED  // No active ride
    private var rideContext: RideContext? = null

    /**
     * Validate a state transition without executing it.
     * Returns true if valid, logs warning and returns false if invalid.
     */
    private fun validateTransition(event: RideEvent): Boolean {
        val ctx = rideContext ?: RideContext(
            riderPubkey = "",
            inputterPubkey = nostrService.getPubKeyHex() ?: ""
        )
        val canTransition = stateMachine.canTransition(rideState, ctx, event)
        if (!canTransition) {
            Log.w(TAG, "STATE_MACHINE: Invalid transition attempted - " +
                      "State: $rideState, Event: ${event.eventType}")
        }
        return canTransition
    }

    /**
     * Update the state machine state (for tracking only in Phase 1).
     */
    private fun updateStateMachineState(newState: RideState, newContext: RideContext? = null) {
        val oldState = rideState
        rideState = newState
        if (newContext != null) {
            rideContext = newContext
        }
        Log.d(TAG, "STATE_MACHINE: $oldState -> $newState")
    }

    /**
     * Map current DriverStage to RideState.
     */
    private fun currentRideState(): RideState = when (_uiState.value.stage) {
        DriverStage.OFFLINE -> RideState.CANCELLED
        DriverStage.AVAILABLE -> RideState.CREATED  // Waiting for acceptance = pre-created
        DriverStage.RIDE_ACCEPTED -> RideState.ACCEPTED
        DriverStage.EN_ROUTE_TO_PICKUP -> RideState.EN_ROUTE
        DriverStage.ARRIVED_AT_PICKUP -> RideState.ARRIVED
        DriverStage.IN_RIDE -> RideState.IN_PROGRESS
        DriverStage.RIDE_COMPLETED -> RideState.COMPLETED
    }

    /**
     * Helper to add a status action to history and publish driver ride state.
     * @param status The new status (use DriverStatusType constants)
     * @param location Optional approximate location for status
     * @param finalFare Final fare in satoshis (for completed rides)
     * @param invoice Lightning invoice (for completed rides)
     * @return The event ID if successful, null on failure
     */
    private suspend fun updateDriverStatus(
        confirmationEventId: String,
        riderPubKey: String,
        status: String,
        location: Location? = null,
        finalFare: Long? = null,
        invoice: String? = null
    ): String? {
        // Add status action to history
        val statusAction = DriverRideStateEvent.createStatusAction(
            status = status,
            location = location,
            finalFare = finalFare,
            invoice = invoice
        )

        // CRITICAL: Use mutex to prevent race condition with PIN submissions
        return historyMutex.withLock {
            driverStateHistory.add(statusAction)

            // Publish consolidated driver ride state
            nostrService.publishDriverRideState(
                confirmationEventId = confirmationEventId,
                riderPubKey = riderPubKey,
                currentStatus = status,
                history = driverStateHistory.toList(),
                finalFare = finalFare,
                invoice = invoice,
                lastTransitionId = lastReceivedRiderStateId
            )
        }
    }

    /**
     * Helper to add a PIN submission action to history and publish driver ride state.
     * @param pin The PIN submitted by the driver
     * @return The event ID if successful, null on failure
     */
    private suspend fun submitPinToHistory(
        confirmationEventId: String,
        riderPubKey: String,
        pin: String
    ): String? {
        // Encrypt the PIN for the rider
        val encryptedPin = nostrService.encryptPinForDriverState(pin, riderPubKey)
        if (encryptedPin == null) {
            Log.e(TAG, "Failed to encrypt PIN")
            return null
        }

        // Add PIN submission action to history
        val pinAction = DriverRideStateEvent.createPinSubmitAction(encryptedPin)

        // CRITICAL: Use mutex to prevent race condition with status updates
        return historyMutex.withLock {
            driverStateHistory.add(pinAction)

            // Get current status from the last status action in history
            val currentStatus = driverStateHistory
                .filterIsInstance<DriverRideAction.Status>()
                .lastOrNull()?.status ?: DriverStatusType.ARRIVED

            // Publish consolidated driver ride state
            nostrService.publishDriverRideState(
                confirmationEventId = confirmationEventId,
                riderPubKey = riderPubKey,
                currentStatus = currentStatus,
                history = driverStateHistory.toList(),
                lastTransitionId = lastReceivedRiderStateId
            )
        }
    }

    /**
     * Clear driver state history (called when ride ends or is cancelled).
     */
    private fun clearDriverStateHistory() {
        driverStateHistory.clear()
        lastProcessedRiderActionCount = 0
        lastReceivedRiderStateId = null  // Reset chain for new ride
    }

    /** Track an event for cleanup with diagnostic logging */
    private fun trackEventForCleanup(eventId: String, eventType: String) {
        myRideEventIds.add(eventId)
        Log.d(TAG, "TRACKED $eventType: $eventId (total: ${myRideEventIds.size})")
    }

    /**
     * Clean up all ride events in background (NIP-09 deletion).
     * NON-BLOCKING - launches cleanup in separate coroutine so UI transitions immediately.
     *
     * Uses the same query-then-delete approach as Account Safety which is proven to work.
     * Excludes RIDE_HISTORY_BACKUP (30174) and RIDE_CANCELLATION (3179).
     */
    private fun cleanupRideEventsInBackground(reason: String) {
        // Clear tracked IDs synchronously
        synchronized(myRideEventIds) {
            myRideEventIds.clear()
        }

        // Launch cleanup in background - does NOT block caller
        viewModelScope.launch {
            Log.d(TAG, "=== BACKGROUND CLEANUP START: $reason ===")
            val deletedCount = nostrService.backgroundCleanupRideshareEvents(reason)
            Log.d(TAG, "=== BACKGROUND CLEANUP DONE: Deleted $deletedCount events ===")
        }
    }
    // Cleanup timer for pruning stale requests
    private var staleRequestCleanupJob: kotlinx.coroutines.Job? = null

    init {
        nostrService.connect()
        // Start Bitcoin price auto-refresh (every 5 minutes)
        bitcoinPriceService.startAutoRefresh()

        // Clean up any stale availability events from previous sessions
        // This handles the case where app was killed while driver was online
        viewModelScope.launch {
            val deleted = nostrService.deleteMyEventsByKind(
                RideshareEventKinds.DRIVER_AVAILABILITY,
                "cleanup stale availability on app start"
            )
            if (deleted > 0) {
                Log.d(TAG, "Cleaned up $deleted stale availability events from previous session")
            }
        }

        // Initialize Valhalla routing service
        viewModelScope.launch {
            val initialized = routingService.initialize()
            Log.d(TAG, "Valhalla routing service initialized: $initialized")

            // If we have pending requests that couldn't calculate routes, retry now
            if (initialized && _uiState.value.pendingBroadcastRequests.isNotEmpty()) {
                Log.d(TAG, "Recalculating routes for ${_uiState.value.pendingBroadcastRequests.size} pending requests")
                _uiState.value.pendingBroadcastRequests.forEach { request ->
                    calculatePickupRoute(request.eventId, request.pickupArea)
                }
            }
        }

        // Set user's public key
        nostrService.getPubKeyHex()?.let { pubKey ->
            _uiState.value = _uiState.value.copy(myPubKey = pubKey)
        }

        // Restore any active ride state from previous session
        restoreRideState()
    }

    // Flag to request location refresh from UI (since GPS lives there)
    private val _locationRefreshRequested = MutableStateFlow(false)
    val locationRefreshRequested: StateFlow<Boolean> = _locationRefreshRequested.asStateFlow()

    /**
     * Called by UI after it has fetched and provided the new location.
     */
    fun acknowledgeLocationRefresh() {
        _locationRefreshRequested.value = false
    }

    /**
     * Called when app returns to foreground.
     * Ensures relay connections are active and resubscribes to chat and cancellation if needed.
     */
    fun onResume() {
        Log.d(TAG, "onResume - ensuring relay connections and refreshing subscriptions")
        nostrService.ensureConnected()

        val state = _uiState.value

        // If driver is AVAILABLE, request location refresh
        // The UI will fetch GPS and call updateLocation()
        if (state.stage == DriverStage.AVAILABLE) {
            Log.d(TAG, "Driver is AVAILABLE, requesting location refresh")
            _locationRefreshRequested.value = true
        }

        // If we have an active ride with a confirmation, refresh subscriptions
        val confId = state.confirmationEventId
        if (confId != null && state.stage in listOf(
                DriverStage.EN_ROUTE_TO_PICKUP,
                DriverStage.ARRIVED_AT_PICKUP,
                DriverStage.IN_RIDE
            )) {
            Log.d(TAG, "Refreshing chat and cancellation subscriptions for confirmation: $confId")
            subscribeToChatMessages(confId)
            subscribeToCancellation(confId)
            // Restart the periodic chat refresh job
            startChatRefreshJob(confId)

            // Restore rider ride state subscription (handles verification and location reveals)
            state.acceptedOffer?.riderPubKey?.let { riderPubKey ->
                Log.d(TAG, "Refreshing rider ride state subscription")
                subscribeToRiderRideState(confId, riderPubKey)
            }
        }
    }

    /**
     * Save current ride state to SharedPreferences for persistence across app restarts.
     */
    private fun saveRideState() {
        val state = _uiState.value

        // Only save if we're in an active ride
        if (!state.isInRide) {
            clearSavedRideState()
            return
        }

        val offer = state.acceptedOffer ?: return

        try {
            val json = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("stage", state.stage.name)
                put("acceptanceEventId", state.acceptanceEventId)
                put("confirmationEventId", state.confirmationEventId)
                put("pinAttempts", state.pinAttempts)

                // Offer data
                put("offer_eventId", offer.eventId)
                put("offer_riderPubKey", offer.riderPubKey)
                put("offer_driverEventId", offer.driverEventId)
                put("offer_driverPubKey", offer.driverPubKey)
                put("offer_fareEstimate", offer.fareEstimate)
                put("offer_createdAt", offer.createdAt)
                put("offer_pickupLat", offer.approxPickup.lat)
                put("offer_pickupLon", offer.approxPickup.lon)
                put("offer_destLat", offer.destination.lat)
                put("offer_destLon", offer.destination.lon)

                // Precise pickup if available
                state.precisePickupLocation?.let {
                    put("precisePickupLat", it.lat)
                    put("precisePickupLon", it.lon)
                }

                // Chat messages
                val messagesArray = org.json.JSONArray()
                for (msg in state.chatMessages) {
                    val msgJson = JSONObject().apply {
                        put("eventId", msg.eventId)
                        put("senderPubKey", msg.senderPubKey)
                        put("confirmationEventId", msg.confirmationEventId)
                        put("recipientPubKey", msg.recipientPubKey)
                        put("message", msg.message)
                        put("createdAt", msg.createdAt)
                    }
                    messagesArray.put(msgJson)
                }
                put("chatMessages", messagesArray)

                // Event IDs for cleanup (NIP-09 deletion on completion/cancellation)
                val eventIdsArray = org.json.JSONArray()
                synchronized(myRideEventIds) {
                    for (eventId in myRideEventIds) {
                        eventIdsArray.put(eventId)
                    }
                }
                put("myRideEventIds", eventIdsArray)
            }

            prefs.edit().putString(KEY_RIDE_STATE, json.toString()).apply()
            Log.d(TAG, "Saved ride state: stage=${state.stage}, messages=${state.chatMessages.size}, eventIds=${myRideEventIds.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save ride state", e)
        }
    }

    /**
     * Restore ride state from SharedPreferences after app restart.
     */
    private fun restoreRideState() {
        val json = prefs.getString(KEY_RIDE_STATE, null) ?: return

        try {
            val data = JSONObject(json)
            val timestamp = data.getLong("timestamp")

            // Check if state is too old
            if (System.currentTimeMillis() - timestamp > MAX_RIDE_STATE_AGE_MS) {
                Log.d(TAG, "Saved ride state is too old, clearing")
                clearSavedRideState()
                return
            }

            val stageName = data.getString("stage")
            val stage = try {
                DriverStage.valueOf(stageName)
            } catch (e: Exception) {
                Log.w(TAG, "Invalid stage in saved state: $stageName")
                clearSavedRideState()
                return
            }

            // Reconstruct the offer
            val offer = RideOfferData(
                eventId = data.getString("offer_eventId"),
                riderPubKey = data.getString("offer_riderPubKey"),
                driverEventId = data.getString("offer_driverEventId"),
                driverPubKey = data.getString("offer_driverPubKey"),
                approxPickup = Location(
                    lat = data.getDouble("offer_pickupLat"),
                    lon = data.getDouble("offer_pickupLon")
                ),
                destination = Location(
                    lat = data.getDouble("offer_destLat"),
                    lon = data.getDouble("offer_destLon")
                ),
                fareEstimate = data.getDouble("offer_fareEstimate"),
                createdAt = data.getLong("offer_createdAt")
            )

            // Reconstruct precise pickup if available
            val precisePickup = if (data.has("precisePickupLat")) {
                Location(
                    lat = data.getDouble("precisePickupLat"),
                    lon = data.getDouble("precisePickupLon")
                )
            } else null

            val acceptanceEventId: String? = if (data.has("acceptanceEventId")) data.getString("acceptanceEventId") else null
            val confirmationEventId: String? = if (data.has("confirmationEventId")) data.getString("confirmationEventId") else null
            val pinAttempts = data.optInt("pinAttempts", 0)

            // Restore chat messages
            val chatMessages = mutableListOf<RideshareChatData>()
            if (data.has("chatMessages")) {
                val messagesArray = data.getJSONArray("chatMessages")
                for (i in 0 until messagesArray.length()) {
                    val msgJson = messagesArray.getJSONObject(i)
                    chatMessages.add(RideshareChatData(
                        eventId = msgJson.getString("eventId"),
                        senderPubKey = msgJson.getString("senderPubKey"),
                        confirmationEventId = msgJson.getString("confirmationEventId"),
                        recipientPubKey = msgJson.optString("recipientPubKey", ""),
                        message = msgJson.getString("message"),
                        createdAt = msgJson.getLong("createdAt")
                    ))
                }
            }

            // Restore event IDs for cleanup (NIP-09 deletion on completion/cancellation)
            if (data.has("myRideEventIds")) {
                val eventIdsArray = data.getJSONArray("myRideEventIds")
                synchronized(myRideEventIds) {
                    myRideEventIds.clear()
                    for (i in 0 until eventIdsArray.length()) {
                        myRideEventIds.add(eventIdsArray.getString(i))
                    }
                }
            }

            Log.d(TAG, "Restoring ride state: stage=$stage, confirmationId=$confirmationEventId, messages=${chatMessages.size}, eventIds=${myRideEventIds.size}")

            // Restore state
            _uiState.value = _uiState.value.copy(
                stage = stage,
                acceptedOffer = offer,
                acceptanceEventId = acceptanceEventId,
                confirmationEventId = confirmationEventId,
                precisePickupLocation = precisePickup,
                pinAttempts = pinAttempts,
                chatMessages = chatMessages,
                statusMessage = getStatusMessageForStage(stage)
            )

            // Re-subscribe to relevant events
            if (acceptanceEventId != null && stage == DriverStage.RIDE_ACCEPTED) {
                subscribeToConfirmation(acceptanceEventId)
                // Note: We don't subscribe to rider ride state here yet - we do that after confirmation
            }

            if (confirmationEventId != null) {
                subscribeToChatMessages(confirmationEventId)
                subscribeToCancellation(confirmationEventId)
                // Start periodic chat refresh
                startChatRefreshJob(confirmationEventId)
                // Subscribe to rider ride state (handles verification and location reveals)
                subscribeToRiderRideState(confirmationEventId, offer.riderPubKey)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore ride state", e)
            clearSavedRideState()
        }
    }

    /**
     * Clear saved ride state.
     */
    private fun clearSavedRideState() {
        prefs.edit().remove(KEY_RIDE_STATE).apply()
    }

    /**
     * Clear all local ride state.
     * Called from Account Safety screen after deleting events from relays.
     * This ensures phantom events from local storage don't affect new rides.
     */
    fun clearLocalRideState() {
        Log.d(TAG, "Clearing all local ride state (Account Safety cleanup)")
        clearSavedRideState()
        clearDriverStateHistory()
        // Clear tracked event IDs
        synchronized(myRideEventIds) {
            myRideEventIds.clear()
        }
    }

    /**
     * Get appropriate status message for a stage.
     */
    private fun getStatusMessageForStage(stage: DriverStage): String {
        return when (stage) {
            DriverStage.OFFLINE -> "Tap to go online"
            DriverStage.AVAILABLE -> "Waiting for ride requests..."
            DriverStage.RIDE_ACCEPTED -> "Waiting for rider confirmation..."
            DriverStage.EN_ROUTE_TO_PICKUP -> "Ride accepted, Heading to Pickup"
            DriverStage.ARRIVED_AT_PICKUP -> "Ask rider for their PIN"
            DriverStage.IN_RIDE -> "Ride in progress..."
            DriverStage.RIDE_COMPLETED -> "Ride completed!"
        }
    }

    /**
     * Go online with a specific vehicle.
     * This is the new preferred method for going online, allowing vehicle selection.
     */
    fun goOnline(location: Location, vehicle: Vehicle?) {
        val currentState = _uiState.value

        if (currentState.stage != DriverStage.OFFLINE) return

        // Check if wallet is set up (has mint URL configured)
        // Without a wallet, riders using Cashu payments won't be able to complete rides
        val mintUrl = walletService?.getSavedMintUrl()
        val paymentMethods = settingsManager.paymentMethods.value

        // Warn if Cashu is a payment method but wallet isn't configured
        if (paymentMethods.contains("cashu") && mintUrl == null) {
            Log.w(TAG, "Going online with Cashu payment method but no wallet configured")
            _uiState.value = currentState.copy(
                showWalletNotSetupWarning = true,
                pendingGoOnlineLocation = location,
                pendingGoOnlineVehicle = vehicle
            )
            return
        }

        // Wallet is set up or not using Cashu - proceed normally
        proceedGoOnline(location, vehicle)
    }

    /**
     * Actually go online (called after wallet check passes or user acknowledges warning).
     */
    private fun proceedGoOnline(location: Location, vehicle: Vehicle?) {
        val currentState = _uiState.value
        val context = getApplication<Application>()

        // Set location and vehicle FIRST so route calculations work
        _uiState.value = currentState.copy(
            stage = DriverStage.AVAILABLE,
            currentLocation = location,
            activeVehicle = vehicle,
            statusMessage = "You are now available for rides",
            // Clear any pending warning state
            showWalletNotSetupWarning = false,
            pendingGoOnlineLocation = null,
            pendingGoOnlineVehicle = null
        )
        // Initialize cache location tracker
        lastCacheDriverLocation = location

        // Start the foreground service to keep app alive
        DriverOnlineService.start(context)

        // Now start subscriptions (location is already set for route calculations)
        startBroadcasting(location)
        subscribeToBroadcastRequests(location)
        // Also subscribe to direct offers (legacy/advanced mode)
        subscribeToOffers()
    }

    /**
     * Dismiss wallet warning and don't go online.
     */
    fun dismissWalletWarning() {
        _uiState.value = _uiState.value.copy(
            showWalletNotSetupWarning = false,
            pendingGoOnlineLocation = null,
            pendingGoOnlineVehicle = null
        )
    }

    /**
     * User acknowledged wallet warning - proceed to go online anyway.
     */
    fun proceedWithoutWallet() {
        val state = _uiState.value
        val location = state.pendingGoOnlineLocation
        val vehicle = state.pendingGoOnlineVehicle

        if (location != null) {
            proceedGoOnline(location, vehicle)
        } else {
            // Shouldn't happen, but clear state anyway
            dismissWalletWarning()
        }
    }

    /**
     * Go offline.
     */
    fun goOffline() {
        val currentState = _uiState.value
        val context = getApplication<Application>()

        if (currentState.stage != DriverStage.AVAILABLE) return

        // Stop broadcasting and subscriptions first
        stopBroadcasting()
        closeOfferSubscription()

        // Capture location before launching coroutine
        val lastLocation = currentState.currentLocation

        viewModelScope.launch {
            // Broadcast offline status - let it remain on relays as signal to riders
            // (expires in 30 min via NIP-40, giving riders time to receive and remove driver)
            if (lastLocation != null) {
                val eventId = nostrService.broadcastOffline(lastLocation)
                if (eventId != null) {
                    Log.d(TAG, "Broadcast offline status: $eventId")
                    // Don't add to publishedAvailabilityEventIds - we want this to persist
                    // so riders see the offline signal before it expires naturally
                }
            }

            // Delete all availability events - AWAIT before state reset
            deleteAllAvailabilityEvents()

            // Reset throttle tracking for next time driver goes online
            lastBroadcastLocation = null
            lastBroadcastTimeMs = 0L

            // Stop the foreground service
            DriverOnlineService.stop(context)

            // THEN update state after deletion completes
            _uiState.value = _uiState.value.copy(
                stage = DriverStage.OFFLINE,
                currentLocation = null,
                activeVehicle = null,
                statusMessage = "You are now offline"
            )

            // Background sweep for any stragglers - doesn't block UI
            viewModelScope.launch {
                val cleaned = nostrService.backgroundCleanupRideshareEvents("driver went offline")
                if (cleaned > 0) {
                    Log.d(TAG, "Background cleanup removed $cleaned stray events")
                }
            }
        }
    }

    /**
     * Legacy method for toggling availability.
     * Prefer using goOnline(location, vehicle) and goOffline() directly.
     */
    fun toggleAvailability(location: Location, vehicle: Vehicle? = null) {
        val currentState = _uiState.value

        if (currentState.stage == DriverStage.AVAILABLE) {
            goOffline()
        } else if (currentState.stage == DriverStage.OFFLINE) {
            goOnline(location, vehicle)
        }
        // Don't allow toggling during a ride
    }

    /**
     * Update the driver's location while online.
     * This updates the stored location and triggers an immediate re-broadcast.
     *
     * Throttling: To avoid spamming relays, updates are only broadcast if:
     * - At least MIN_LOCATION_UPDATE_DISTANCE_M (1000m) from last broadcast, AND
     * - At least MIN_LOCATION_UPDATE_INTERVAL_MS (30s) since last broadcast
     *
     * @param newLocation The new location
     * @param force If true, bypasses throttling (use for deliberate user actions like toggling demo mode)
     */
    fun updateLocation(newLocation: Location, force: Boolean = false) {
        val currentState = _uiState.value

        // Only update if we're available (online and not in a ride)
        if (currentState.stage != DriverStage.AVAILABLE) {
            Log.d(TAG, "Not updating location - not in AVAILABLE stage (current: ${currentState.stage})")
            return
        }

        // Check throttling unless forced
        if (!force && lastBroadcastLocation != null) {
            val timeSinceLastBroadcast = System.currentTimeMillis() - lastBroadcastTimeMs
            val distanceFromLastBroadcast = calculateDistanceMeters(
                lastBroadcastLocation!!.lat, lastBroadcastLocation!!.lon,
                newLocation.lat, newLocation.lon
            )

            val timeOk = timeSinceLastBroadcast >= MIN_LOCATION_UPDATE_INTERVAL_MS
            val distanceOk = distanceFromLastBroadcast >= MIN_LOCATION_UPDATE_DISTANCE_M

            if (!timeOk || !distanceOk) {
                Log.d(TAG, "Location update throttled: distance=${distanceFromLastBroadcast.toInt()}m (need ${MIN_LOCATION_UPDATE_DISTANCE_M.toInt()}m), " +
                        "time=${timeSinceLastBroadcast/1000}s (need ${MIN_LOCATION_UPDATE_INTERVAL_MS/1000}s)")
                // Still update the local state for UI, just don't broadcast
                _uiState.value = currentState.copy(currentLocation = newLocation)
                return
            }
        }

        Log.d(TAG, "Updating driver location to: ${newLocation.lat}, ${newLocation.lon}" + if (force) " (forced)" else "")

        // Update the stored location
        _uiState.value = currentState.copy(
            currentLocation = newLocation
        )

        // Update cache location tracker for route calculations
        lastCacheDriverLocation = newLocation

        // Track this broadcast for throttling
        lastBroadcastLocation = newLocation
        lastBroadcastTimeMs = System.currentTimeMillis()

        // Restart broadcasting with the new location to trigger immediate update
        // The periodic broadcast will now use the new location from state
        stopBroadcasting()
        startBroadcasting(newLocation)

        // Also resubscribe to broadcast requests with new geohash
        subscribeToBroadcastRequests(newLocation)
    }

    /**
     * Cancel an active ride and return to available state.
     */
    fun cancelRide() {
        val state = _uiState.value

        // Guard: Already cancelling - prevent duplicate invocations
        if (state.isCancelling) {
            Log.d(TAG, "Already cancelling, ignoring duplicate cancelRide() call")
            return
        }

        // Guard: Not in an active ride
        if (state.stage == DriverStage.OFFLINE || state.stage == DriverStage.AVAILABLE) return

        // STATE_MACHINE: Validate CANCEL transition
        val myPubkey = nostrService.getPubKeyHex() ?: ""
        validateTransition(RideEvent.Cancel(myPubkey, "Driver cancelled"))

        // Set cancelling flag IMMEDIATELY (synchronous) to prevent race conditions
        _uiState.value = state.copy(isCancelling = true)

        // Stop chat refresh job
        stopChatRefreshJob()

        // Close subscriptions
        confirmationSubscriptionId?.let { nostrService.closeSubscription(it) }
        confirmationSubscriptionId = null
        riderRideStateSubscriptionId?.let { nostrService.closeSubscription(it) }
        riderRideStateSubscriptionId = null
        chatSubscriptionId?.let { nostrService.closeSubscription(it) }
        chatSubscriptionId = null
        cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
        cancellationSubscriptionId = null

        // Unsubscribe from rider profile
        state.acceptedOffer?.riderPubKey?.let { unsubscribeFromRiderProfile(it) }

        // Clear driver state history
        clearDriverStateHistory()

        // Stop the foreground service
        DriverOnlineService.stop(getApplication())

        // Capture state values before launching coroutine
        val confId = state.confirmationEventId
        val riderPubKey = state.acceptedOffer?.riderPubKey
        val currentLoc = state.currentLocation

        viewModelScope.launch {
            // Send cancelled status if we have a confirmation
            if (confId != null && riderPubKey != null) {
                // Send cancellation event so rider gets notified
                Log.d(TAG, "Publishing ride cancellation to rider")
                val cancellationEventId = nostrService.publishRideCancellation(
                    confirmationEventId = confId,
                    otherPartyPubKey = riderPubKey,
                    reason = "Driver cancelled"
                )
                cancellationEventId?.let { myRideEventIds.add(it) }

                // Also send cancelled status via driver ride state
                val statusEventId = updateDriverStatus(
                    confirmationEventId = confId,
                    riderPubKey = riderPubKey,
                    status = DriverStatusType.CANCELLED,
                    location = currentLoc
                )
                statusEventId?.let { myRideEventIds.add(it) }
            }

            // Update state immediately (UI transition first)
            _uiState.value = _uiState.value.copy(
                stage = DriverStage.OFFLINE,
                isCancelling = false,  // Reset cancelling flag
                acceptedOffer = null,
                acceptedBroadcastRequest = null,
                acceptanceEventId = null,
                confirmationEventId = null,
                precisePickupLocation = null,
                pinAttempts = 0,
                isAwaitingPinVerification = false,
                lastPinSubmissionEventId = null,
                chatMessages = emptyList(),
                isSendingMessage = false,
                pendingOffers = emptyList(),
                pendingBroadcastRequests = emptyList(),
                statusMessage = "Ride cancelled. Tap to go online.",
                // Clear HTLC escrow state
                activePaymentHash = null,
                activePreimage = null,
                activeEscrowToken = null,
                canSettleEscrow = false
            )

            // Clear persisted ride state
            clearSavedRideState()

            // Clean up ride events in background (non-blocking)
            cleanupRideEventsInBackground("ride cancelled")
        }
    }

    /**
     * Return to available after completing a ride.
     */
    fun finishAndGoOnline(location: Location) {
        // Capture state for ride history BEFORE clearing
        val state = _uiState.value
        val offer = state.acceptedOffer

        // Close subscriptions
        confirmationSubscriptionId?.let { nostrService.closeSubscription(it) }
        confirmationSubscriptionId = null
        riderRideStateSubscriptionId?.let { nostrService.closeSubscription(it) }
        riderRideStateSubscriptionId = null
        chatSubscriptionId?.let { nostrService.closeSubscription(it) }
        chatSubscriptionId = null
        cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
        cancellationSubscriptionId = null

        // Clear driver state history
        clearDriverStateHistory()

        // Update state immediately (UI transition first)
        _uiState.value = _uiState.value.copy(
            stage = DriverStage.OFFLINE,
            acceptedOffer = null,
            acceptedBroadcastRequest = null,
            acceptanceEventId = null,
            confirmationEventId = null,
            precisePickupLocation = null,
            pinAttempts = 0,
            isAwaitingPinVerification = false,
            lastPinSubmissionEventId = null,
            chatMessages = emptyList(),
            isSendingMessage = false,
            pendingOffers = emptyList(),
            pendingBroadcastRequests = emptyList(),
            statusMessage = "Tap to go online",
            // Clear HTLC escrow state
            activePaymentHash = null,
            activePreimage = null,
            activeEscrowToken = null,
            canSettleEscrow = false
        )

        // Clear persisted ride state
        clearSavedRideState()

        // Save completed ride to history (using 6-char geohashes for ~1.2km precision)
        if (offer != null) {
            viewModelScope.launch {
                try {
                    val vehicle = state.activeVehicle
                    val riderProfile = state.riderProfiles[offer.riderPubKey]
                    val historyEntry = RideHistoryEntry(
                        rideId = state.confirmationEventId ?: state.acceptanceEventId ?: offer.eventId,
                        timestamp = System.currentTimeMillis() / 1000,
                        role = "driver",
                        counterpartyPubKey = offer.riderPubKey,
                        pickupGeohash = offer.approxPickup.geohash(6),  // ~1.2km precision
                        dropoffGeohash = offer.destination.geohash(6),
                        distanceMiles = offer.rideRouteKm?.let { it * 0.621371 } ?: 0.0,  // Convert km to miles
                        durationMinutes = offer.rideRouteMin?.toInt() ?: 0,
                        fareSats = offer.fareEstimate.toLong(),
                        status = "completed",
                        // Vehicle info for ride details
                        vehicleMake = vehicle?.make,
                        vehicleModel = vehicle?.model,
                        // Rider profile info
                        counterpartyFirstName = riderProfile?.bestName()?.split(" ")?.firstOrNull()
                    )
                    rideHistoryRepository.addRide(historyEntry)
                    Log.d(TAG, "Saved completed ride to history: ${historyEntry.rideId}")

                    // Backup to Nostr (encrypted to self)
                    rideHistoryRepository.backupToNostr(nostrService)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save ride to history", e)
                }
            }
        }

        // Unsubscribe from rider profile
        offer?.riderPubKey?.let { unsubscribeFromRiderProfile(it) }

        // Clean up ride events in background (non-blocking)
        cleanupRideEventsInBackground("ride completed")

        // Reset broadcast state for a fresh start
        // This ensures we don't try to delete already-deleted events
        publishedAvailabilityEventIds.clear()
        lastBroadcastLocation = null
        lastBroadcastTimeMs = 0L
        Log.d(TAG, "Reset broadcast state before going back online")

        // Go online immediately - don't wait for cleanup
        // Use goOnline directly with the captured vehicle (from state at start of function)
        goOnline(location, state.activeVehicle)
    }

    /**
     * Subscribe to a rider's profile to get their name for ride history.
     */
    private fun subscribeToRiderProfile(riderPubKey: String) {
        if (riderProfileSubscriptionIds.containsKey(riderPubKey)) return

        val subId = nostrService.subscribeToProfile(riderPubKey) { profile ->
            Log.d(TAG, "Got profile for rider ${riderPubKey.take(8)}: ${profile.bestName()}")
            val currentProfiles = _uiState.value.riderProfiles.toMutableMap()
            currentProfiles[riderPubKey] = profile
            _uiState.value = _uiState.value.copy(riderProfiles = currentProfiles)
        }
        riderProfileSubscriptionIds[riderPubKey] = subId
    }

    /**
     * Unsubscribe from a rider's profile when the ride ends.
     */
    private fun unsubscribeFromRiderProfile(riderPubKey: String) {
        riderProfileSubscriptionIds.remove(riderPubKey)?.let { subId ->
            nostrService.closeSubscription(subId)
        }
        val currentProfiles = _uiState.value.riderProfiles.toMutableMap()
        currentProfiles.remove(riderPubKey)
        _uiState.value = _uiState.value.copy(riderProfiles = currentProfiles)
    }

    private fun closeOfferSubscription() {
        offerSubscriptionId?.let { nostrService.closeSubscription(it) }
        offerSubscriptionId = null
        broadcastRequestSubscriptionId?.let { nostrService.closeSubscription(it) }
        broadcastRequestSubscriptionId = null
        // Close all per-request acceptance subscriptions
        requestAcceptanceSubscriptionIds.values.forEach { nostrService.closeSubscription(it) }
        requestAcceptanceSubscriptionIds.clear()
        takenOfferEventIds.clear()
        // Stop cleanup timer
        stopStaleRequestCleanup()
    }

    fun updateLocation(location: Location) {
        // Update location when available or during a ride
        if (_uiState.value.stage != DriverStage.OFFLINE) {
            _uiState.value = _uiState.value.copy(currentLocation = location)

            // Check if driver moved significantly and needs route recalculation
            if (_uiState.value.stage == DriverStage.AVAILABLE) {
                checkDriverLocationChange(location)
            }
        }
    }

    fun acceptOffer(offer: RideOfferData) {
        viewModelScope.launch {
            // CRITICAL: Clear history from any previous ride to prevent phantom cancellation bug
            // Without this, history from ride #1 (e.g., CANCELLED) would pollute ride #2's events
            clearDriverStateHistory()

            // STATE_MACHINE: Initialize context for new ride and validate ACCEPT transition
            val myPubkey = nostrService.getPubKeyHex() ?: ""
            val newContext = RideContext.forOffer(
                riderPubkey = offer.riderPubKey,
                approxPickup = offer.approxPickup,
                destination = offer.destination,
                fareEstimateSats = offer.fareEstimate.toLong(),
                offerEventId = offer.eventId,
                paymentHash = offer.paymentHash,
                riderMintUrl = offer.mintUrl,
                paymentMethod = offer.paymentMethod ?: "cashu"
            )
            rideContext = newContext
            rideState = RideState.CREATED  // Offer exists = CREATED state
            validateTransition(RideEvent.Accept(
                inputterPubkey = myPubkey,
                driverPubkey = myPubkey,
                walletPubkey = walletService?.getWalletPubKey(),
                mintUrl = walletService?.getSavedMintUrl()
            ))

            _uiState.value = _uiState.value.copy(isProcessingOffer = true)

            // Get wallet pubkey for P2PK escrow (driver will sign with this key to claim)
            val walletPubKey = walletService?.getWalletPubKey()
            Log.d(TAG, "Including wallet pubkey in acceptance: ${walletPubKey?.take(16)}...")

            // Get driver's mint URL for multi-mint support
            val driverMintUrl = walletService?.getSavedMintUrl()

            // PIN is now generated by rider - driver will ask rider for PIN at pickup
            // Accept with rider's payment method and driver's mint URL
            val eventId = nostrService.acceptRide(
                offer = offer,
                walletPubKey = walletPubKey,
                mintUrl = driverMintUrl,
                paymentMethod = offer.paymentMethod  // Confirm rider's requested method
            )

            if (eventId != null) {
                // Track this offer as accepted AFTER success (so it won't show up again after ride completion)
                // CRITICAL: Must be inside success block - if accept fails, offer should remain visible for retry
                acceptedOfferEventIds.add(offer.eventId)
                Log.d(TAG, "Accepted ride offer: $eventId")
                // Track for unified cleanup on ride completion
                trackEventForCleanup(eventId, "ACCEPTANCE")
                // Stop broadcasting availability but stay in "ride" mode, not offline
                stopBroadcasting()
                deleteAllAvailabilityEvents()

                // Extract payment hash for HTLC escrow (if present in offer)
                val paymentHash = offer.paymentHash
                if (paymentHash != null) {
                    Log.d(TAG, "Offer includes HTLC payment hash: ${paymentHash.take(16)}...")
                }

                // Determine payment path (same mint vs cross-mint)
                val riderMintUrl = offer.mintUrl
                val paymentPath = PaymentPath.determine(riderMintUrl, driverMintUrl, offer.paymentMethod)
                Log.d(TAG, "PaymentPath: $paymentPath (rider: $riderMintUrl, driver: $driverMintUrl)")

                _uiState.value = _uiState.value.copy(
                    stage = DriverStage.RIDE_ACCEPTED,
                    isProcessingOffer = false,
                    acceptedOffer = offer,
                    acceptanceEventId = eventId,  // Track for cleanup later
                    confirmationEventId = null,  // CRITICAL: Clear old confirmation to prevent stale state
                    pendingOffers = emptyList(), // Clear all pending offers
                    pinAttempts = 0,
                    confirmationWaitStartMs = System.currentTimeMillis(),  // Start confirmation timer
                    statusMessage = "Ride accepted! Waiting for rider confirmation...",
                    // HTLC escrow tracking
                    activePaymentHash = paymentHash,
                    activePreimage = null,
                    activeEscrowToken = null,
                    canSettleEscrow = false,
                    // Multi-mint support
                    paymentPath = paymentPath,
                    riderMintUrl = riderMintUrl,
                    crossMintPaymentComplete = false,  // Reset for new ride
                    pendingDepositQuoteId = null,      // Reset pending deposit
                    pendingDepositAmount = null
                )

                // STATE_MACHINE: Update state after successful acceptance
                updateStateMachineState(
                    RideState.ACCEPTED,
                    rideContext?.withDriver(
                        driverPubkey = myPubkey,
                        driverWalletPubkey = walletPubKey,
                        driverMintUrl = driverMintUrl
                    )
                )

                // Save ride state for persistence
                saveRideState()

                // Subscribe to rider's confirmation to get precise pickup location
                // Note: Confirmation references our acceptance event ID, not the offer ID
                subscribeToConfirmation(eventId)
                // Note: We'll subscribe to rider ride state after receiving confirmation

                // Subscribe to rider profile to get their name for ride history
                subscribeToRiderProfile(offer.riderPubKey)
            } else {
                _uiState.value = _uiState.value.copy(
                    isProcessingOffer = false,
                    error = "Failed to accept ride"
                )
            }
        }
    }

    /**
     * Start driving to pickup location.
     * Called after rider confirms with precise location.
     */
    fun startRouteToPickup() {
        val state = _uiState.value
        if (state.stage != DriverStage.RIDE_ACCEPTED) return

        viewModelScope.launch {
            // Send status update to rider via driver ride state
            state.confirmationEventId?.let { confId ->
                state.acceptedOffer?.riderPubKey?.let { riderPubKey ->
                    val eventId = updateDriverStatus(
                        confirmationEventId = confId,
                        riderPubKey = riderPubKey,
                        status = DriverStatusType.EN_ROUTE_PICKUP,
                        location = state.currentLocation
                    )
                    // Track for cleanup on ride completion
                    eventId?.let { trackEventForCleanup(it, "DRIVER_RIDE_STATE") }
                }
            }

            _uiState.value = state.copy(
                stage = DriverStage.EN_ROUTE_TO_PICKUP,
                statusMessage = "Ride accepted, Heading to Pickup"
            )
        }
    }

    /**
     * Mark arrival at pickup location.
     */
    fun arrivedAtPickup() {
        val state = _uiState.value

        // Guard: Check stage and cancelling flag to prevent race conditions
        if (state.stage != DriverStage.EN_ROUTE_TO_PICKUP) return
        if (state.isCancelling) {
            Log.d(TAG, "Ignoring arrivedAtPickup - ride is being cancelled")
            return
        }

        // CRITICAL: Update stage IMMEDIATELY (synchronously) to prevent double-tap
        // This ensures a second tap will fail the stage check above
        _uiState.value = state.copy(
            stage = DriverStage.ARRIVED_AT_PICKUP,
            statusMessage = "Arrived! Ask rider for their PIN"
        )

        val context = getApplication<Application>()

        // Capture values for coroutine
        val confId = state.confirmationEventId
        val riderPubKey = state.acceptedOffer?.riderPubKey
        val currentLoc = state.currentLocation

        viewModelScope.launch {
            // Publish status event (now safe from double-invocation)
            if (confId != null && riderPubKey != null) {
                val eventId = updateDriverStatus(
                    confirmationEventId = confId,
                    riderPubKey = riderPubKey,
                    status = DriverStatusType.ARRIVED,
                    location = currentLoc
                )
                // Track for cleanup on ride completion
                eventId?.let { trackEventForCleanup(it, "DRIVER_RIDE_STATE") }
            }

            // Notify service of arrival (updates notification)
            val riderName: String? = null // RideOfferData doesn't contain rider name
            DriverOnlineService.updateStatus(context, DriverStatus.ArrivedAtPickup(riderName))

            // Save ride state for persistence
            saveRideState()
        }
    }

    /**
     * Submit the PIN heard from the rider for verification.
     * The rider's app will verify and send a response.
     * For cross-mint scenarios, also shares deposit invoice for Lightning bridge payment.
     * @param enteredPin The PIN the rider told the driver
     */
    fun submitPinForVerification(enteredPin: String) {
        val state = _uiState.value
        if (state.stage != DriverStage.ARRIVED_AT_PICKUP) return

        val confirmationEventId = state.confirmationEventId ?: return
        val riderPubKey = state.acceptedOffer?.riderPubKey ?: return

        viewModelScope.launch {
            _uiState.value = state.copy(
                isAwaitingPinVerification = true,
                statusMessage = "Verifying PIN..."
            )

            // For CROSS_MINT: Share deposit invoice first so rider can pay after PIN verification
            if (state.paymentPath == PaymentPath.CROSS_MINT) {
                val fareAmount = state.acceptedOffer?.fareEstimate?.toLong() ?: 0L
                if (fareAmount > 0) {
                    Log.d(TAG, "CROSS_MINT: Requesting deposit invoice for $fareAmount sats")
                    shareDepositInvoice(confirmationEventId, riderPubKey, fareAmount)
                } else {
                    Log.w(TAG, "CROSS_MINT: No fare amount - skipping deposit invoice")
                }
            }

            // Submit PIN via driver ride state (adds to history)
            val eventId = submitPinToHistory(
                confirmationEventId = confirmationEventId,
                riderPubKey = riderPubKey,
                pin = enteredPin
            )

            if (eventId != null) {
                Log.d(TAG, "Submitted PIN via driver ride state: $eventId")
                // Track for cleanup on ride completion
                trackEventForCleanup(eventId, "DRIVER_RIDE_STATE")
                _uiState.value = _uiState.value.copy(
                    lastPinSubmissionEventId = eventId,
                    statusMessage = "Waiting for rider to verify PIN..."
                )
                // Start timeout for PIN verification
                startPinVerificationTimeout()
            } else {
                _uiState.value = _uiState.value.copy(
                    isAwaitingPinVerification = false,
                    error = "Failed to submit PIN"
                )
            }
        }
    }

    /**
     * Share deposit invoice with rider for cross-mint Lightning bridge payment.
     * Driver's mint creates an invoice, rider pays it via Lightning from their mint.
     */
    private suspend fun shareDepositInvoice(
        confirmationEventId: String,
        riderPubKey: String,
        amountSats: Long
    ) {
        try {
            Log.d(TAG, "Requesting deposit invoice for $amountSats sats")
            val quote = walletService?.getDepositInvoice(amountSats)
            if (quote == null) {
                Log.e(TAG, "Failed to get deposit invoice from mint")
                return
            }

            Log.d(TAG, "Got deposit invoice: ${quote.request.take(30)}...")
            Log.d(TAG, "Quote ID for claiming tokens: ${quote.quote}")

            // Store the quote ID so we can claim tokens when BridgeComplete arrives
            _uiState.value = _uiState.value.copy(
                pendingDepositQuoteId = quote.quote,
                pendingDepositAmount = amountSats
            )

            // Also save to WalletStorage for recovery if app restarts
            walletService?.savePendingDeposit(
                quoteId = quote.quote,
                amount = amountSats,
                invoice = quote.request,
                expiry = quote.expiry
            )

            // Create DepositInvoiceShare action
            val invoiceAction = DriverRideAction.DepositInvoiceShare(
                invoice = quote.request,
                amount = amountSats,
                at = System.currentTimeMillis() / 1000
            )

            // Add to history and publish
            val eventId = historyMutex.withLock {
                driverStateHistory.add(invoiceAction)

                // Get current status from the last status action in history
                val currentStatus = driverStateHistory
                    .filterIsInstance<DriverRideAction.Status>()
                    .lastOrNull()?.status ?: DriverStatusType.ARRIVED

                nostrService.publishDriverRideState(
                    confirmationEventId = confirmationEventId,
                    riderPubKey = riderPubKey,
                    currentStatus = currentStatus,
                    history = driverStateHistory.toList(),
                    lastTransitionId = lastReceivedRiderStateId
                )
            }

            if (eventId != null) {
                Log.d(TAG, "Shared deposit invoice via driver ride state: $eventId")
                trackEventForCleanup(eventId, "DRIVER_RIDE_STATE")
            } else {
                Log.e(TAG, "Failed to publish deposit invoice share")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing deposit invoice: ${e.message}", e)
        }
    }

    /**
     * Start a timeout for PIN verification. If no response is received,
     * show options to retry or cancel.
     */
    private fun startPinVerificationTimeout() {
        pinVerificationTimeoutJob?.cancel()
        pinVerificationTimeoutJob = viewModelScope.launch {
            delay(PIN_VERIFICATION_TIMEOUT_MS)

            // Check if still waiting for verification
            val state = _uiState.value
            if (state.isAwaitingPinVerification && state.stage == DriverStage.ARRIVED_AT_PICKUP) {
                Log.w(TAG, "PIN verification timed out - no response from rider")
                _uiState.value = state.copy(
                    isAwaitingPinVerification = false,
                    pinVerificationTimedOut = true,
                    statusMessage = "No response from rider. Try again or cancel."
                )
            }
        }
    }

    /**
     * Retry PIN submission after timeout.
     */
    fun retryPinSubmission(pin: String) {
        _uiState.value = _uiState.value.copy(pinVerificationTimedOut = false)
        submitPinForVerification(pin)
    }

    /**
     * Cancel the current ride (can be called at any stage).
     */
    fun cancelCurrentRide(reason: String = "driver_cancelled") {
        val state = _uiState.value
        val confirmationEventId = state.confirmationEventId
        val riderPubKey = state.acceptedOffer?.riderPubKey
        val offer = state.acceptedOffer

        // Cancel timeout jobs if running
        pinVerificationTimeoutJob?.cancel()
        pinVerificationTimeoutJob = null
        confirmationTimeoutJob?.cancel()
        confirmationTimeoutJob = null

        viewModelScope.launch {
            // Save cancelled ride to history (only if we had an accepted offer)
            if (offer != null) {
                try {
                    val vehicle = state.activeVehicle
                    val historyEntry = RideHistoryEntry(
                        rideId = confirmationEventId ?: state.acceptanceEventId ?: offer.eventId,
                        timestamp = System.currentTimeMillis() / 1000,
                        role = "driver",
                        counterpartyPubKey = offer.riderPubKey,
                        pickupGeohash = offer.approxPickup.geohash(6),
                        dropoffGeohash = offer.destination.geohash(6),
                        distanceMiles = offer.rideRouteKm?.let { it * 0.621371 } ?: 0.0,
                        durationMinutes = 0,  // Ride was cancelled, no actual duration
                        fareSats = 0,  // No fare earned for cancelled ride
                        status = "cancelled",
                        vehicleMake = vehicle?.make,
                        vehicleModel = vehicle?.model
                    )
                    rideHistoryRepository.addRide(historyEntry)
                    Log.d(TAG, "Saved cancelled ride to history: ${historyEntry.rideId}")

                    // Backup to Nostr (encrypted to self)
                    rideHistoryRepository.backupToNostr(nostrService)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save cancelled ride to history", e)
                }
            }

            // Send cancellation event if we have an active ride
            if (confirmationEventId != null && riderPubKey != null) {
                val cancellationEventId = nostrService.publishRideCancellation(
                    confirmationEventId = confirmationEventId,
                    otherPartyPubKey = riderPubKey,
                    reason = reason
                )
                cancellationEventId?.let { myRideEventIds.add(it) }  // Track for cleanup
                Log.d(TAG, "Sent cancellation event for ride: $confirmationEventId")
            }

            // Clean up all subscriptions
            confirmationSubscriptionId?.let { nostrService.closeSubscription(it) }
            confirmationSubscriptionId = null
            riderRideStateSubscriptionId?.let { nostrService.closeSubscription(it) }
            riderRideStateSubscriptionId = null
            chatSubscriptionId?.let { nostrService.closeSubscription(it) }
            chatSubscriptionId = null
            cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
            cancellationSubscriptionId = null
            chatRefreshJob?.cancel()
            chatRefreshJob = null

            // Clear driver state history
            clearDriverStateHistory()

            // Stop the foreground service
            DriverOnlineService.stop(getApplication())

            // Update state immediately (UI transition first)
            _uiState.value = _uiState.value.copy(
                stage = DriverStage.OFFLINE,
                acceptedOffer = null,
                confirmationEventId = null,
                precisePickupLocation = null,
                pinAttempts = 0,
                isAwaitingPinVerification = false,
                pinVerificationTimedOut = false,
                lastPinSubmissionEventId = null,
                chatMessages = emptyList(),
                isSendingMessage = false,
                error = null,
                statusMessage = "Ride cancelled"
            )

            // Clean up ride events in background (non-blocking)
            cleanupRideEventsInBackground("ride cancelled")

            // Clear persisted ride state
            clearSavedRideState()
        }
    }

    /**
     * Subscribe to rider ride state (Kind 30181) for PIN verification and precise location reveals.
     * This unified subscription replaces both subscribeToVerifications and subscribeToPreciseLocationReveals.
     */
    private fun subscribeToRiderRideState(confirmationEventId: String, riderPubKey: String) {
        riderRideStateSubscriptionId?.let { nostrService.closeSubscription(it) }

        Log.d(TAG, "Subscribing to rider ride state for confirmation: ${confirmationEventId.take(8)}")

        riderRideStateSubscriptionId = nostrService.subscribeToRiderRideState(
            confirmationEventId = confirmationEventId,
            riderPubKey = riderPubKey
        ) { riderState ->
            handleRiderRideState(riderState)
        }

        if (riderRideStateSubscriptionId != null) {
            Log.d(TAG, "Rider ride state subscription created: $riderRideStateSubscriptionId")
        } else {
            Log.e(TAG, "Failed to create rider ride state subscription - not logged in?")
        }
    }

    /**
     * Handle updates to rider ride state - processes new actions in history.
     */
    private fun handleRiderRideState(riderState: RiderRideStateData) {
        Log.d(TAG, "Received rider ride state: phase=${riderState.currentPhase}, actions=${riderState.history.size}")

        // Track for chain integrity (AtoB pattern) - save the event ID we received
        lastReceivedRiderStateId = riderState.eventId

        // Log chain integrity info for debugging
        riderState.lastTransitionId?.let { transitionId ->
            Log.d(TAG, "Chain: Rider state references our previous event: ${transitionId.take(8)}")
        }

        // Process only NEW actions (ones we haven't seen yet)
        val newActions = riderState.history.drop(lastProcessedRiderActionCount)
        lastProcessedRiderActionCount = riderState.history.size

        if (newActions.isEmpty()) {
            Log.d(TAG, "No new actions to process")
            return
        }

        Log.d(TAG, "Processing ${newActions.size} new rider actions")

        newActions.forEach { action ->
            when (action) {
                is RiderRideAction.PinVerify -> {
                    handlePinVerification(action.verified, action.attempt)
                }
                is RiderRideAction.LocationReveal -> {
                    handleLocationReveal(action)
                }
                is RiderRideAction.PreimageShare -> {
                    handlePreimageShare(action)
                }
                is RiderRideAction.BridgeComplete -> {
                    handleBridgeComplete(action)
                }
            }
        }
    }

    /**
     * Handle a PIN verification action from the rider.
     */
    private fun handlePinVerification(verified: Boolean, attempt: Int) {
        // Cancel timeout job since we received a response
        pinVerificationTimeoutJob?.cancel()
        pinVerificationTimeoutJob = null

        val state = _uiState.value

        // Only process if we're at pickup AND we actually submitted a PIN (or timed out)
        if (state.stage != DriverStage.ARRIVED_AT_PICKUP) {
            Log.d(TAG, "Ignoring verification - not at pickup (stage=${state.stage})")
            return
        }

        if (!state.isAwaitingPinVerification && !state.pinVerificationTimedOut) {
            Log.d(TAG, "Ignoring verification - not awaiting verification (stale event?)")
            return
        }

        Log.d(TAG, "Processing PIN verification: verified=$verified, attempt=$attempt")

        _uiState.value = state.copy(
            isAwaitingPinVerification = false,
            pinVerificationTimedOut = false,
            pinAttempts = attempt
        )

        if (verified) {
            Log.d(TAG, "PIN verified! Starting ride.")
            startRide()
        } else {
            Log.w(TAG, "PIN rejected! Attempt $attempt")
            val remainingAttempts = 3 - attempt
            if (remainingAttempts <= 0) {
                // Rider cancelled due to brute force protection
                confirmationSubscriptionId?.let { nostrService.closeSubscription(it) }
                confirmationSubscriptionId = null
                riderRideStateSubscriptionId?.let { nostrService.closeSubscription(it) }
                riderRideStateSubscriptionId = null
                chatSubscriptionId?.let { nostrService.closeSubscription(it) }
                chatSubscriptionId = null
                cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
                cancellationSubscriptionId = null

                // Stop the foreground service
                val context = getApplication<Application>()
                DriverOnlineService.stop(context)

                // Clear driver state history
                clearDriverStateHistory()

                // Clean up ride events from relays (NIP-09 deletion)
                // CRITICAL: Must call this to prevent event accumulation on relays
                cleanupRideEventsInBackground("pin_brute_force")

                _uiState.value = _uiState.value.copy(
                    stage = DriverStage.OFFLINE,
                    acceptedOffer = null,
                    acceptanceEventId = null,
                    confirmationEventId = null,
                    chatMessages = emptyList(),
                    isSendingMessage = false,
                    error = "Ride cancelled - too many wrong PIN attempts",
                    statusMessage = "Ride cancelled by rider"
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    statusMessage = "Wrong PIN! $remainingAttempts attempts remaining. Ask rider again."
                )
            }
        }
    }

    /**
     * Handle a location reveal action from the rider.
     */
    private fun handleLocationReveal(reveal: RiderRideAction.LocationReveal) {
        val state = _uiState.value
        val riderPubKey = state.acceptedOffer?.riderPubKey ?: return

        viewModelScope.launch {
            // Decrypt the location
            val location = nostrService.decryptLocationFromRiderState(reveal.locationEncrypted, riderPubKey)
            if (location == null) {
                Log.e(TAG, "Failed to decrypt location reveal")
                return@launch
            }

            Log.d(TAG, "Decrypted ${reveal.locationType} location: ${location.lat}, ${location.lon}")

            when (reveal.locationType) {
                RiderRideStateEvent.LocationType.PICKUP -> {
                    Log.d(TAG, "Updating navigation to precise pickup location")
                    _uiState.value = _uiState.value.copy(
                        precisePickupLocation = location,
                        statusMessage = "Received precise pickup location"
                    )
                }
                RiderRideStateEvent.LocationType.DESTINATION -> {
                    Log.d(TAG, "Received precise destination location")
                    _uiState.value = _uiState.value.copy(
                        preciseDestinationLocation = location,
                        statusMessage = "Received precise destination"
                    )
                }
                else -> {
                    Log.w(TAG, "Unknown location type: ${reveal.locationType}")
                }
            }
        }
    }

    /**
     * Handle preimage share from rider (received after successful PIN verification).
     * This provides the driver with the preimage and escrow token needed to claim payment.
     */
    private fun handlePreimageShare(preimageShare: RiderRideAction.PreimageShare) {
        Log.d(TAG, "=== HANDLING PREIMAGE SHARE ===")
        val state = _uiState.value
        val riderPubKey = state.acceptedOffer?.riderPubKey ?: run {
            Log.e(TAG, "No accepted offer - cannot handle preimage share")
            return
        }
        Log.d(TAG, "Rider pubkey: ${riderPubKey.take(16)}...")
        Log.d(TAG, "Encrypted preimage present: ${preimageShare.preimageEncrypted != null}")
        Log.d(TAG, "Encrypted escrow token present: ${preimageShare.escrowTokenEncrypted != null}")

        viewModelScope.launch {
            try {
                // Decrypt the preimage
                val preimage = nostrService.decryptFromUser(preimageShare.preimageEncrypted, riderPubKey)
                if (preimage == null) {
                    Log.e(TAG, "Failed to decrypt preimage - NIP-44 decryption returned null")
                    return@launch
                }
                Log.d(TAG, "Decrypted preimage: ${preimage.length} chars, ${preimage.take(16)}...")

                // Verify preimage matches payment hash (if we have one)
                val paymentHash = state.activePaymentHash
                if (paymentHash != null) {
                    Log.d(TAG, "Verifying preimage against payment hash: ${paymentHash.take(16)}...")
                    if (!PaymentCrypto.verifyPreimage(preimage, paymentHash)) {
                        Log.e(TAG, "Preimage verification FAILED - hash mismatch!")
                        _uiState.value = state.copy(
                            error = "Invalid payment preimage received"
                        )
                        return@launch
                    }
                    Log.d(TAG, "Preimage verified successfully!")
                } else {
                    Log.w(TAG, "No payment hash stored - cannot verify preimage")
                }

                // Decrypt escrow token if present
                var escrowToken: String? = null
                val encryptedToken = preimageShare.escrowTokenEncrypted
                if (encryptedToken != null) {
                    escrowToken = nostrService.decryptFromUser(encryptedToken, riderPubKey)
                    if (escrowToken != null) {
                        Log.d(TAG, "Decrypted escrow token: ${escrowToken.length} chars, ${escrowToken.take(30)}...")
                    } else {
                        Log.e(TAG, "Failed to decrypt escrow token - NIP-44 decryption returned null")
                    }
                } else {
                    Log.w(TAG, "No encrypted escrow token in preimage share")
                }

                // Update state with escrow info
                val canSettle = preimage != null && escrowToken != null
                Log.d(TAG, "Updating state: canSettleEscrow=$canSettle")
                _uiState.value = state.copy(
                    activePreimage = preimage,
                    activeEscrowToken = escrowToken,
                    canSettleEscrow = canSettle,
                    // Auto-dismiss payment warning dialog if payment is now ready
                    showPaymentWarningDialog = if (canSettle) false else state.showPaymentWarningDialog,
                    paymentWarningStatus = if (canSettle) null else state.paymentWarningStatus
                )

                if (canSettle) {
                    Log.d(TAG, "=== ESCROW READY FOR SETTLEMENT ===")
                } else {
                    Log.w(TAG, "Escrow NOT ready: preimage=${preimage != null}, escrowToken=${escrowToken != null}")

                    // EARLY WARNING: If payment was expected but can't be claimed, warn driver immediately
                    // This ensures driver finds out RIGHT after PIN verification, not at dropoff
                    if (state.activePaymentHash != null) {
                        Log.w(TAG, "Payment issue detected - showing warning dialog immediately")
                        _uiState.value = _uiState.value.copy(
                            showPaymentWarningDialog = true,
                            paymentWarningStatus = when {
                                preimage == null -> PaymentStatus.MISSING_PREIMAGE
                                escrowToken == null -> PaymentStatus.MISSING_ESCROW_TOKEN
                                else -> PaymentStatus.UNKNOWN_ERROR
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling preimage share: ${e.message}", e)
            }
        }
    }

    /**
     * Handle a bridge complete action from the rider (cross-mint payment).
     * The Lightning preimage proves the rider paid driver's deposit invoice.
     */
    private fun handleBridgeComplete(bridgeComplete: RiderRideAction.BridgeComplete) {
        Log.d(TAG, "=== HANDLING BRIDGE COMPLETE (CROSS-MINT) ===")
        Log.d(TAG, "Amount: ${bridgeComplete.amountSats} sats")
        Log.d(TAG, "Fees: ${bridgeComplete.feesSats} sats")
        Log.d(TAG, "Preimage: ${bridgeComplete.preimage.take(16)}...")

        // The Lightning preimage proves the rider paid the deposit invoice.
        // Now we need to claim the tokens from our mint using the stored quote ID.

        val state = _uiState.value
        val quoteId = state.pendingDepositQuoteId
        val amount = state.pendingDepositAmount

        if (quoteId == null || amount == null) {
            Log.e(TAG, "Cannot claim tokens: missing quote ID or amount")
            Log.e(TAG, "  pendingDepositQuoteId: $quoteId")
            Log.e(TAG, "  pendingDepositAmount: $amount")
            // Still mark as complete so ride can proceed (best effort)
            _uiState.value = state.copy(
                activePreimage = bridgeComplete.preimage,
                canSettleEscrow = false,
                crossMintPaymentComplete = true
            )
            return
        }

        // Claim tokens from mint in background with retries
        // Lightning settlement can take a few seconds, so we retry
        viewModelScope.launch {
            try {
                Log.d(TAG, "Claiming tokens from mint: quoteId=$quoteId, amount=$amount")

                // Use claimDepositByQuoteId with retries - it has better error handling
                // and waits for Lightning settlement
                val delays = listOf(0L, 2000L, 4000L, 8000L) // Immediate, then 2s, 4s, 8s
                var claimResult: ClaimResult? = null

                for ((attempt, delay) in delays.withIndex()) {
                    if (delay > 0) {
                        Log.d(TAG, "Waiting ${delay}ms before claim attempt ${attempt + 1}...")
                        kotlinx.coroutines.delay(delay)
                    }

                    Log.d(TAG, "Claim attempt ${attempt + 1}/${delays.size}")
                    claimResult = walletService?.claimDepositByQuoteId(quoteId)

                    if (claimResult?.success == true && claimResult.claimedCount > 0) {
                        Log.d(TAG, "Successfully claimed ${claimResult.totalSats} sats on attempt ${attempt + 1}")
                        break
                    } else if (claimResult?.error?.contains("not paid yet") == true) {
                        Log.d(TAG, "Quote not paid yet, will retry...")
                        // Continue retrying - Lightning hasn't settled
                    } else if (claimResult?.error != null) {
                        Log.e(TAG, "Claim error: ${claimResult.error}")
                        // If it's a permanent error (not found, already claimed), stop retrying
                        if (claimResult.error?.contains("not found") == true ||
                            claimResult.error?.contains("already issued") == true) {
                            break
                        }
                    }
                }

                if (claimResult?.success == true && claimResult.claimedCount > 0) {
                    _uiState.value = _uiState.value.copy(
                        activePreimage = bridgeComplete.preimage,
                        canSettleEscrow = false,
                        crossMintPaymentComplete = true,
                        // Clear the pending deposit fields
                        pendingDepositQuoteId = null,
                        pendingDepositAmount = null,
                        // Auto-dismiss payment warning dialog since payment succeeded
                        showPaymentWarningDialog = false,
                        paymentWarningStatus = null
                    )

                    Log.d(TAG, "Cross-mint payment complete: received ${claimResult.totalSats} sats")
                } else {
                    Log.e(TAG, "Failed to claim tokens after all retries")
                    Log.e(TAG, "Last result: ${claimResult?.error ?: "null"}")
                    // Still mark as complete to let ride proceed
                    // User can claim manually via Wallet Settings > Claim by Quote ID
                    _uiState.value = _uiState.value.copy(
                        activePreimage = bridgeComplete.preimage,
                        canSettleEscrow = false,
                        crossMintPaymentComplete = true
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception claiming tokens: ${e.message}", e)
                _uiState.value = _uiState.value.copy(
                    activePreimage = bridgeComplete.preimage,
                    canSettleEscrow = false,
                    crossMintPaymentComplete = true
                )
            }
        }
    }

    /**
     * Start the ride (rider is in vehicle).
     */
    private fun startRide() {
        val state = _uiState.value
        val context = getApplication<Application>()

        viewModelScope.launch {
            state.confirmationEventId?.let { confId ->
                state.acceptedOffer?.riderPubKey?.let { riderPubKey ->
                    val eventId = updateDriverStatus(
                        confirmationEventId = confId,
                        riderPubKey = riderPubKey,
                        status = DriverStatusType.IN_PROGRESS,
                        location = state.currentLocation
                    )
                    // Track for cleanup on ride completion
                    eventId?.let { trackEventForCleanup(it, "DRIVER_RIDE_STATE") }
                }
            }

            // Notify service of ride in progress (updates notification)
            val riderName: String? = null // RideOfferData doesn't contain rider name
            DriverOnlineService.updateStatus(context, DriverStatus.RideInProgress(riderName))

            _uiState.value = state.copy(
                stage = DriverStage.IN_RIDE,
                statusMessage = "Ride in progress..."
            )

            // Save ride state for persistence
            saveRideState()
        }
    }

    /**
     * Get current payment status for HTLC escrow.
     */
    fun getPaymentStatus(): PaymentStatus {
        val state = _uiState.value
        return when {
            // Cross-mint payment already completed via Lightning bridge - no action needed
            state.crossMintPaymentComplete -> PaymentStatus.NO_PAYMENT_EXPECTED
            state.activePaymentHash == null -> PaymentStatus.NO_PAYMENT_EXPECTED
            state.canSettleEscrow -> PaymentStatus.READY_TO_CLAIM
            state.activePreimage == null && state.activeEscrowToken == null -> PaymentStatus.WAITING_FOR_PREIMAGE
            state.activePreimage == null -> PaymentStatus.MISSING_PREIMAGE
            state.activeEscrowToken == null -> PaymentStatus.MISSING_ESCROW_TOKEN
            else -> PaymentStatus.UNKNOWN_ERROR
        }
    }

    /**
     * Complete the ride (arrived at destination).
     * Shows warning dialog if payment cannot be claimed.
     */
    fun completeRide() {
        val state = _uiState.value
        if (state.stage != DriverStage.IN_RIDE) return

        // STATE_MACHINE: Validate COMPLETE transition
        val myPubkey = nostrService.getPubKeyHex() ?: ""
        validateTransition(RideEvent.Complete(myPubkey, state.acceptedOffer?.fareEstimate?.toLong()))

        val paymentStatus = getPaymentStatus()
        Log.d(TAG, "completeRide: paymentStatus=$paymentStatus")

        // Check if we can claim payment
        if (paymentStatus != PaymentStatus.READY_TO_CLAIM &&
            paymentStatus != PaymentStatus.NO_PAYMENT_EXPECTED) {
            // Show warning dialog - driver won't be able to claim payment
            Log.w(TAG, "Payment not ready for claim - showing warning dialog")
            _uiState.value = state.copy(
                showPaymentWarningDialog = true,
                paymentWarningStatus = paymentStatus
            )
            return
        }

        // Payment ready or not expected - proceed with completion
        completeRideInternal()
    }

    /**
     * User confirmed to complete ride without payment.
     */
    fun confirmCompleteWithoutPayment() {
        Log.d(TAG, "User confirmed to complete ride without payment")
        _uiState.value = _uiState.value.copy(
            showPaymentWarningDialog = false,
            paymentWarningStatus = null
        )
        completeRideInternal()
    }

    /**
     * User chose to cancel ride due to payment issue.
     */
    fun cancelRideDueToPaymentIssue() {
        Log.d(TAG, "User chose to cancel ride due to payment issue")
        _uiState.value = _uiState.value.copy(
            showPaymentWarningDialog = false,
            paymentWarningStatus = null
        )
        cancelRide()
    }

    /**
     * Dismiss payment warning dialog without action (e.g., wait for payment to arrive).
     */
    fun dismissPaymentWarningDialog() {
        _uiState.value = _uiState.value.copy(
            showPaymentWarningDialog = false,
            paymentWarningStatus = null
        )
    }

    /**
     * Internal method to actually complete the ride.
     * Settles HTLC escrow if available.
     */
    private fun completeRideInternal() {
        val state = _uiState.value
        val offer = state.acceptedOffer ?: return

        // Stop chat refresh job since ride is ending
        stopChatRefreshJob()

        viewModelScope.launch {
            // Attempt to settle HTLC escrow if we have preimage and token
            var settlementMessage = ""
            if (state.canSettleEscrow && state.activePreimage != null && state.activeEscrowToken != null) {
                Log.d(TAG, "=== ATTEMPTING HTLC SETTLEMENT ===")
                Log.d(TAG, "Preimage: ${state.activePreimage.take(16)}...")
                Log.d(TAG, "Escrow token: ${state.activeEscrowToken.take(30)}...")
                try {
                    val settlement = walletService?.claimHtlcPayment(
                        htlcToken = state.activeEscrowToken,
                        preimage = state.activePreimage,
                        paymentHash = state.activePaymentHash
                    )

                    if (settlement != null) {
                        Log.d(TAG, "=== HTLC SETTLED SUCCESSFULLY ===")
                        Log.d(TAG, "Received ${settlement.amountSats} sats")
                        settlementMessage = " + ${settlement.amountSats} sats received"
                    } else {
                        Log.e(TAG, "Failed to settle HTLC escrow - claimHtlcPayment returned null")
                        settlementMessage = " (payment claim failed)"
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error settling HTLC escrow: ${e.message}", e)
                    settlementMessage = " (payment error)"
                }
            } else if (state.activePaymentHash != null) {
                // Had payment hash but no preimage/token - log for debugging
                Log.w(TAG, "Ride had HTLC but settlement not possible - preimage=${state.activePreimage != null}, token=${state.activeEscrowToken != null}")
                settlementMessage = " (no payment received)"
            }

            state.confirmationEventId?.let { confId ->
                val eventId = updateDriverStatus(
                    confirmationEventId = confId,
                    riderPubKey = offer.riderPubKey,
                    status = DriverStatusType.COMPLETED,
                    location = state.currentLocation,
                    finalFare = offer.fareEstimate.toLong()
                )
                // Track for cleanup on ride completion
                eventId?.let { trackEventForCleanup(it, "DRIVER_RIDE_STATE") }
            }

            _uiState.value = state.copy(
                stage = DriverStage.RIDE_COMPLETED,
                statusMessage = "Ride completed! Fare: ${offer.fareEstimate.toInt()} sats$settlementMessage",
                // Clear HTLC state
                activePaymentHash = null,
                activePreimage = null,
                activeEscrowToken = null,
                canSettleEscrow = false,
                showPaymentWarningDialog = false,
                paymentWarningStatus = null
            )
        }
    }

    /**
     * Subscribe to rider's confirmation to get precise pickup location.
     * Auto-transitions to EN_ROUTE_TO_PICKUP and sends status update.
     */
    private fun subscribeToConfirmation(acceptanceEventId: String) {
        confirmationSubscriptionId?.let { nostrService.closeSubscription(it) }

        // Start confirmation timeout - if no confirmation arrives, rider may have cancelled
        startConfirmationTimeout()

        confirmationSubscriptionId = nostrService.subscribeToConfirmation(acceptanceEventId, viewModelScope) { confirmation ->
            Log.d(TAG, "Received ride confirmation: ${confirmation.eventId}")
            Log.d(TAG, "Precise pickup: ${confirmation.precisePickup.lat}, ${confirmation.precisePickup.lon}")

            // Cancel the confirmation timeout - we got a response
            cancelConfirmationTimeout()

            val currentStage = _uiState.value.stage

            // Only process if we're still waiting for confirmation (RIDE_ACCEPTED stage)
            // This prevents re-triggering auto-transition when receiving duplicate events
            // (e.g., from relay history when returning to foreground)
            if (currentStage != DriverStage.RIDE_ACCEPTED) {
                Log.d(TAG, "Ignoring confirmation - already in stage $currentStage")
                return@subscribeToConfirmation
            }

            // Notify service of confirmation - this triggers EN_ROUTE status update
            // Note: The actual service status update happens in autoStartRouteToPickup() below
            val context = getApplication<Application>()

            // Store confirmation data
            _uiState.value = _uiState.value.copy(
                confirmationEventId = confirmation.eventId,
                precisePickupLocation = confirmation.precisePickup
            )

            // STATE_MACHINE: Update state to CONFIRMED after receiving confirmation
            updateStateMachineState(
                RideState.CONFIRMED,
                rideContext?.withConfirmation(
                    confirmationEventId = confirmation.eventId,
                    precisePickup = confirmation.precisePickup
                )
            )

            // Subscribe to chat messages for this ride
            subscribeToChatMessages(confirmation.eventId)
            // Start periodic refresh to ensure messages are received
            startChatRefreshJob(confirmation.eventId)

            // Subscribe to cancellation events from rider
            subscribeToCancellation(confirmation.eventId)

            // Subscribe to rider ride state (handles verification and location reveals)
            _uiState.value.acceptedOffer?.riderPubKey?.let { riderPubKey ->
                subscribeToRiderRideState(confirmation.eventId, riderPubKey)
            }

            // Auto-transition to EN_ROUTE_TO_PICKUP (skip the intermediate screen)
            autoStartRouteToPickup(confirmation.eventId)
        }
    }

    /**
     * Start the confirmation timeout.
     * If rider doesn't confirm within the timeout, assume the ride was cancelled.
     */
    private fun startConfirmationTimeout() {
        cancelConfirmationTimeout()
        Log.d(TAG, "Starting confirmation timeout (${CONFIRMATION_TIMEOUT_MS / 1000}s)")

        confirmationTimeoutJob = viewModelScope.launch {
            kotlinx.coroutines.delay(CONFIRMATION_TIMEOUT_MS)

            // Check if we're still waiting for confirmation
            if (_uiState.value.stage == DriverStage.RIDE_ACCEPTED) {
                Log.d(TAG, "Confirmation timeout - rider may have cancelled")
                handleConfirmationTimeout()
            }
        }
    }

    /**
     * Cancel the confirmation timeout.
     */
    private fun cancelConfirmationTimeout() {
        confirmationTimeoutJob?.cancel()
        confirmationTimeoutJob = null
    }

    /**
     * Handle confirmation timeout - rider didn't confirm in time.
     * This typically means the rider cancelled before we accepted.
     */
    private fun handleConfirmationTimeout() {
        val context = getApplication<Application>()

        // Notify service of cancellation (plays sound, updates notification)
        DriverOnlineService.updateStatus(context, DriverStatus.Cancelled)

        // Close subscriptions
        confirmationSubscriptionId?.let { nostrService.closeSubscription(it) }
        confirmationSubscriptionId = null
        riderRideStateSubscriptionId?.let { nostrService.closeSubscription(it) }
        riderRideStateSubscriptionId = null

        // Clear driver state history
        clearDriverStateHistory()

        // Return to available state
        _uiState.value = _uiState.value.copy(
            stage = DriverStage.AVAILABLE,
            acceptedOffer = null,
            acceptedBroadcastRequest = null,
            acceptanceEventId = null,
            confirmationEventId = null,
            error = "Rider may have cancelled - no confirmation received",
            statusMessage = "Ride cancelled - no response from rider"
        )

        // Restart availability broadcasts if we have a location
        _uiState.value.currentLocation?.let { location ->
            startBroadcasting(location)
            subscribeToBroadcastRequests(location)
            subscribeToOffers()
            Log.d(TAG, "Resumed broadcasting after confirmation timeout")
        }
    }

    /**
     * Automatically start route to pickup when confirmation is received.
     * This removes the need for driver to manually tap "Start Route to Pickup".
     */
    private fun autoStartRouteToPickup(confirmationEventId: String) {
        val state = _uiState.value
        val riderPubKey = state.acceptedOffer?.riderPubKey ?: return
        val context = getApplication<Application>()

        viewModelScope.launch {
            // Send status update to rider via driver ride state
            val eventId = updateDriverStatus(
                confirmationEventId = confirmationEventId,
                riderPubKey = riderPubKey,
                status = DriverStatusType.EN_ROUTE_PICKUP,
                location = state.currentLocation
            )
            // Track for cleanup on ride completion
            eventId?.let { trackEventForCleanup(it, "DRIVER_RIDE_STATE") }

            // Notify service of status change (updates notification)
            val riderName: String? = null // RideOfferData doesn't contain rider name
            DriverOnlineService.updateStatus(context, DriverStatus.EnRouteToPickup(riderName))

            _uiState.value = _uiState.value.copy(
                stage = DriverStage.EN_ROUTE_TO_PICKUP,
                statusMessage = "Ride accepted, Heading to Pickup"
            )

            // Save ride state for persistence
            saveRideState()
        }
    }

    /**
     * Subscribe to chat messages for this ride.
     * Creates new subscription before closing old one to avoid gaps in message delivery.
     */
    private fun subscribeToChatMessages(confirmationEventId: String) {
        val oldSubscriptionId = chatSubscriptionId

        // Create new subscription FIRST (before closing old one to avoid gaps)
        chatSubscriptionId = nostrService.subscribeToChatMessages(confirmationEventId, viewModelScope) { chatData ->
            Log.d(TAG, "Received chat message from ${chatData.senderPubKey.take(8)}: ${chatData.message}")

            // Add to chat messages list
            val currentMessages = _uiState.value.chatMessages.toMutableList()
            // Avoid duplicates
            if (currentMessages.none { it.eventId == chatData.eventId }) {
                currentMessages.add(chatData)
                // Sort by timestamp
                currentMessages.sortBy { it.createdAt }
                _uiState.value = _uiState.value.copy(chatMessages = currentMessages)
                // Persist messages for app restart
                saveRideState()

                // Notify service of chat message (plays sound, adds to alert stack)
                val myPubKey = nostrService.getPubKeyHex() ?: ""
                if (chatData.senderPubKey != myPubKey) {
                    val context = getApplication<Application>()
                    DriverOnlineService.addAlert(
                        context,
                        DriverStackableAlert.Chat(chatData.message)
                    )
                    Log.d(TAG, "Chat message received - added to alert stack")
                }
            }
        }

        // Now close old subscription (after new one is active)
        oldSubscriptionId?.let { nostrService.closeSubscription(it) }
    }

    /**
     * Start periodic chat refresh to ensure messages are received.
     * Some relays may not push real-time events reliably.
     */
    private fun startChatRefreshJob(confirmationEventId: String) {
        stopChatRefreshJob()
        chatRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(CHAT_REFRESH_INTERVAL_MS)

                // CRITICAL: Check cancellation IMMEDIATELY after delay
                // Kotlin coroutine cancellation is cooperative - without this check,
                // a cancelled job could execute one more iteration after waking up.
                ensureActive()

                Log.d(TAG, "Refreshing chat subscription for ${confirmationEventId.take(8)}")
                subscribeToChatMessages(confirmationEventId)
            }
        }
    }

    /**
     * Stop the periodic chat refresh job.
     */
    private fun stopChatRefreshJob() {
        chatRefreshJob?.cancel()
        chatRefreshJob = null
    }

    /**
     * Subscribe to ride cancellation events from the rider.
     */
    private fun subscribeToCancellation(confirmationEventId: String) {
        cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
        cancellationSubscriptionId = null

        Log.d(TAG, "Subscribing to cancellation events for confirmation: ${confirmationEventId.take(8)}")

        val newSubId = nostrService.subscribeToCancellation(confirmationEventId) { cancellation ->
            Log.d(TAG, "Received ride cancellation: event conf=${cancellation.confirmationEventId.take(8)}, reason=${cancellation.reason ?: "none"}")

            val currentState = _uiState.value
            val currentConfirmationId = currentState.confirmationEventId

            // CRITICAL: Validate the EVENT's confirmation ID matches current ride
            // This is the definitive check - the event itself knows which ride it belongs to
            if (cancellation.confirmationEventId != currentConfirmationId) {
                Log.d(TAG, "Ignoring cancellation for different ride: event=${cancellation.confirmationEventId.take(8)} vs current=${currentConfirmationId?.take(8)}")
                return@subscribeToCancellation
            }

            // Only process if we're in an active ride and not already cancelling
            if (currentState.isCancelling) {
                Log.d(TAG, "Ignoring cancellation - already cancelling")
                return@subscribeToCancellation
            }

            if (currentState.stage == DriverStage.OFFLINE || currentState.stage == DriverStage.AVAILABLE) {
                Log.d(TAG, "Ignoring cancellation - not in active ride (stage=${currentState.stage})")
                return@subscribeToCancellation
            }

            // Reset driver state to available
            handleRideCancellation(cancellation.reason)
        }

        if (newSubId != null) {
            cancellationSubscriptionId = newSubId
            Log.d(TAG, "Cancellation subscription created: $newSubId")
        } else {
            Log.e(TAG, "Failed to create cancellation subscription - not logged in?")
        }
    }

    /**
     * Handle ride cancellation from rider.
     * If driver has preimage/escrow token, shows dialog to offer claiming payment.
     */
    private fun handleRideCancellation(reason: String?) {
        val state = _uiState.value
        val context = getApplication<Application>()
        val offer = state.acceptedOffer

        // Notify service of cancellation (plays sound, updates notification)
        DriverOnlineService.updateStatus(context, DriverStatus.Cancelled)
        Log.d(TAG, "Ride cancelled - notified service")

        // If driver can claim payment (has preimage + escrow token), show dialog instead of cleaning up
        if (state.canSettleEscrow && offer != null) {
            Log.d(TAG, "Rider cancelled after PIN verification - driver can claim payment")
            _uiState.value = state.copy(
                showRiderCancelledClaimDialog = true,
                riderCancelledFareAmount = offer.fareEstimate,
                error = reason ?: "Rider cancelled the ride"
            )
            return  // Don't cleanup yet - wait for driver decision
        }

        // Normal cancellation cleanup (driver can't claim)
        performCancellationCleanup(state, offer, reason)
    }

    /**
     * Claim payment after rider cancelled (driver had preimage).
     */
    fun claimPaymentAfterCancellation() {
        val state = _uiState.value
        val offer = state.acceptedOffer

        viewModelScope.launch {
            // Attempt to settle HTLC escrow
            if (state.canSettleEscrow && state.activePreimage != null && state.activeEscrowToken != null) {
                Log.d(TAG, "Claiming payment after rider cancellation...")
                try {
                    val result = walletService?.claimHtlcPayment(
                        htlcToken = state.activeEscrowToken,
                        preimage = state.activePreimage
                    )
                    val claimed = result != null

                    if (claimed) {
                        Log.d(TAG, "Successfully claimed payment after rider cancellation")
                        // Save to history with fare earned
                        saveCancelledRideToHistory(state, offer, claimed = true)
                    } else {
                        Log.e(TAG, "Failed to claim payment after rider cancellation")
                        saveCancelledRideToHistory(state, offer, claimed = false)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error claiming payment: ${e.message}", e)
                    saveCancelledRideToHistory(state, offer, claimed = false)
                }
            }

            // Now do cleanup
            _uiState.value = _uiState.value.copy(showRiderCancelledClaimDialog = false)
            performCancellationCleanup(state, offer, "Rider cancelled the ride")
        }
    }

    /**
     * Skip claiming payment after rider cancelled.
     */
    fun dismissRiderCancelledDialog() {
        val state = _uiState.value
        _uiState.value = state.copy(showRiderCancelledClaimDialog = false)
        performCancellationCleanup(state, state.acceptedOffer, "Rider cancelled the ride")
    }

    /**
     * Save cancelled ride to history.
     */
    private suspend fun saveCancelledRideToHistory(
        state: DriverUiState,
        offer: RideOfferData?,
        claimed: Boolean
    ) {
        if (offer == null) return
        try {
            val vehicle = state.activeVehicle
            val historyEntry = RideHistoryEntry(
                rideId = state.confirmationEventId ?: state.acceptanceEventId ?: offer.eventId,
                timestamp = System.currentTimeMillis() / 1000,
                role = "driver",
                counterpartyPubKey = offer.riderPubKey,
                pickupGeohash = offer.approxPickup.geohash(6),
                dropoffGeohash = offer.destination.geohash(6),
                distanceMiles = offer.rideRouteKm?.let { it * 0.621371 } ?: 0.0,
                durationMinutes = 0,
                fareSats = if (claimed) offer.fareEstimate.toLong() else 0,
                status = if (claimed) "cancelled_claimed" else "cancelled",
                vehicleMake = vehicle?.make,
                vehicleModel = vehicle?.model
            )
            rideHistoryRepository.addRide(historyEntry)
            Log.d(TAG, "Saved cancelled ride to history: ${historyEntry.rideId}, claimed=$claimed")
            rideHistoryRepository.backupToNostr(nostrService)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cancelled ride to history", e)
        }
    }

    /**
     * Perform cancellation cleanup (subscriptions, state, etc.)
     */
    private fun performCancellationCleanup(state: DriverUiState, offer: RideOfferData?, reason: String?) {
        val context = getApplication<Application>()

        // Save cancelled ride to history (only if we had an accepted offer and not already saved)
        if (offer != null && !state.showRiderCancelledClaimDialog) {
            viewModelScope.launch {
                saveCancelledRideToHistory(state, offer, claimed = false)
            }
        }

        // Close active subscriptions
        confirmationSubscriptionId?.let { nostrService.closeSubscription(it) }
        confirmationSubscriptionId = null
        riderRideStateSubscriptionId?.let { nostrService.closeSubscription(it) }
        riderRideStateSubscriptionId = null
        chatSubscriptionId?.let { nostrService.closeSubscription(it) }
        chatSubscriptionId = null
        cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
        cancellationSubscriptionId = null

        // Clear driver state history
        clearDriverStateHistory()

        // Clear persisted ride state
        clearSavedRideState()

        // Return to available state (if they were online) or offline
        val newStage = if (state.currentLocation != null) DriverStage.AVAILABLE else DriverStage.OFFLINE

        // Stop the foreground service if going offline
        if (newStage == DriverStage.OFFLINE) {
            DriverOnlineService.stop(context)
        }

        // Resume broadcasting and subscriptions if returning to AVAILABLE
        if (newStage == DriverStage.AVAILABLE && state.currentLocation != null) {
            // Reset broadcast state for a fresh start (same as finishAndGoOnline)
            publishedAvailabilityEventIds.clear()
            lastBroadcastLocation = null
            lastBroadcastTimeMs = 0L
            Log.d(TAG, "Reset broadcast state before resuming after cancellation")

            startBroadcasting(state.currentLocation)
            subscribeToBroadcastRequests(state.currentLocation)
            subscribeToOffers()
            Log.d(TAG, "Resumed broadcasting after rider cancellation")
        }

        _uiState.value = _uiState.value.copy(
            stage = newStage,
            acceptedOffer = null,
            acceptedBroadcastRequest = null,
            acceptanceEventId = null,
            confirmationEventId = null,
            precisePickupLocation = null,
            isAwaitingPinVerification = false,
            chatMessages = emptyList(),
            pendingBroadcastRequests = emptyList(),
            // Clear escrow state
            activePaymentHash = null,
            activePreimage = null,
            activeEscrowToken = null,
            canSettleEscrow = false,
            riderCancelledFareAmount = null,
            statusMessage = if (newStage == DriverStage.AVAILABLE) "Rider cancelled - waiting for new requests" else "Rider cancelled ride",
            error = reason ?: "Rider cancelled the ride"
        )
    }

    /**
     * Send a chat message to the rider.
     */
    fun sendChatMessage(message: String) {
        val state = _uiState.value
        val confirmationEventId = state.confirmationEventId ?: return
        val riderPubKey = state.acceptedOffer?.riderPubKey ?: return

        if (message.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSendingMessage = true)

            val eventId = nostrService.sendChatMessage(
                confirmationEventId = confirmationEventId,
                recipientPubKey = riderPubKey,
                message = message
            )

            if (eventId != null) {
                Log.d(TAG, "Sent chat message: $eventId")
                // Track for unified cleanup on ride completion
                trackEventForCleanup(eventId, "CHAT")
                // Add locally for immediate feedback
                val myPubKey = nostrService.getPubKeyHex() ?: ""

                val localMessage = RideshareChatData(
                    eventId = eventId,
                    senderPubKey = myPubKey,
                    confirmationEventId = confirmationEventId,
                    recipientPubKey = riderPubKey,
                    message = message,
                    createdAt = System.currentTimeMillis() / 1000
                )

                val currentMessages = _uiState.value.chatMessages.toMutableList()
                currentMessages.add(localMessage)
                currentMessages.sortBy { it.createdAt }

                _uiState.value = _uiState.value.copy(
                    isSendingMessage = false,
                    chatMessages = currentMessages
                )

                // Persist messages for app restart
                saveRideState()
            } else {
                _uiState.value = _uiState.value.copy(
                    isSendingMessage = false,
                    error = "Failed to send message"
                )
            }
        }
    }

    fun declineOffer(offer: RideOfferData) {
        _uiState.value = _uiState.value.copy(
            pendingOffers = _uiState.value.pendingOffers.filter { it.eventId != offer.eventId }
        )
    }

    fun clearAcceptedOffer() {
        // Only clear if ride is completed
        if (_uiState.value.stage == DriverStage.RIDE_COMPLETED) {
            // Capture state for ride history BEFORE clearing
            val state = _uiState.value
            val offer = state.acceptedOffer
            val context = getApplication<Application>()

            // Stop chat refresh job
            stopChatRefreshJob()

            // Close subscriptions
            confirmationSubscriptionId?.let { nostrService.closeSubscription(it) }
            confirmationSubscriptionId = null
            riderRideStateSubscriptionId?.let { nostrService.closeSubscription(it) }
            riderRideStateSubscriptionId = null
            chatSubscriptionId?.let { nostrService.closeSubscription(it) }
            chatSubscriptionId = null
            cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
            cancellationSubscriptionId = null

            // Clear driver state history
            clearDriverStateHistory()

            // Stop the foreground service since driver is going offline
            DriverOnlineService.stop(context)

            // Update state immediately (UI transition first)
            _uiState.value = _uiState.value.copy(
                stage = DriverStage.OFFLINE,
                acceptedOffer = null,
                acceptedBroadcastRequest = null,
                acceptanceEventId = null,
                confirmationEventId = null,
                precisePickupLocation = null,
                pinAttempts = 0,
                isAwaitingPinVerification = false,
                lastPinSubmissionEventId = null,
                chatMessages = emptyList(),
                isSendingMessage = false,
                pendingBroadcastRequests = emptyList(),
                statusMessage = "Tap to go online",
                // Clear HTLC escrow state
                activePaymentHash = null,
                activePreimage = null,
                activeEscrowToken = null,
                canSettleEscrow = false
            )

            // Clear persisted ride state
            clearSavedRideState()

            // Save completed ride to history (using 6-char geohashes for ~1.2km precision)
            if (offer != null) {
                viewModelScope.launch {
                    try {
                        val vehicle = state.activeVehicle
                        val riderProfile = state.riderProfiles[offer.riderPubKey]
                        val historyEntry = RideHistoryEntry(
                            rideId = state.confirmationEventId ?: state.acceptanceEventId ?: offer.eventId,
                            timestamp = System.currentTimeMillis() / 1000,
                            role = "driver",
                            counterpartyPubKey = offer.riderPubKey,
                            pickupGeohash = offer.approxPickup.geohash(6),  // ~1.2km precision
                            dropoffGeohash = offer.destination.geohash(6),
                            distanceMiles = offer.rideRouteKm?.let { it * 0.621371 } ?: 0.0,  // Convert km to miles
                            durationMinutes = offer.rideRouteMin?.toInt() ?: 0,
                            fareSats = offer.fareEstimate.toLong(),
                            status = "completed",
                            // Vehicle info for ride details
                            vehicleMake = vehicle?.make,
                            vehicleModel = vehicle?.model,
                            // Rider profile info
                            counterpartyFirstName = riderProfile?.bestName()?.split(" ")?.firstOrNull()
                        )
                        rideHistoryRepository.addRide(historyEntry)
                        Log.d(TAG, "Saved completed ride to history (go offline): ${historyEntry.rideId}")

                        // Backup to Nostr (encrypted to self)
                        rideHistoryRepository.backupToNostr(nostrService)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save ride to history", e)
                    }
                }
            }

            // Unsubscribe from rider profile
            offer?.riderPubKey?.let { unsubscribeFromRiderProfile(it) }

            // Clean up ride events in background (non-blocking)
            cleanupRideEventsInBackground("ride completed")
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun startBroadcasting(location: Location) {
        Log.d(TAG, "=== START BROADCASTING ===")
        Log.d(TAG, "Location: ${location.lat}, ${location.lon}")
        Log.d(TAG, "Existing event IDs: ${publishedAvailabilityEventIds.size}")

        availabilityJob?.cancel()

        // Initialize throttle tracking for first broadcast
        if (lastBroadcastLocation == null) {
            lastBroadcastLocation = location
            lastBroadcastTimeMs = System.currentTimeMillis()
            Log.d(TAG, "Initialized throttle tracking (fresh start)")
        }

        availabilityJob = viewModelScope.launch {
            var loopCount = 0
            while (isActive) {
                loopCount++
                Log.d(TAG, "=== BROADCAST LOOP #$loopCount ===")

                val currentLocation = _uiState.value.currentLocation ?: location
                val activeVehicle = _uiState.value.activeVehicle

                // Track this broadcast for throttling
                lastBroadcastLocation = currentLocation
                lastBroadcastTimeMs = System.currentTimeMillis()

                val previousEventId = publishedAvailabilityEventIds.lastOrNull()
                if (previousEventId != null) {
                    Log.d(TAG, "Deleting previous availability: ${previousEventId.take(16)}...")
                    nostrService.deleteEvent(previousEventId, "superseded")
                }

                Log.d(TAG, "Broadcasting availability at ${currentLocation.lat}, ${currentLocation.lon}")
                val mintUrl = walletService?.getSavedMintUrl()
                val paymentMethods = settingsManager.paymentMethods.value
                val eventId = nostrService.broadcastAvailability(
                    location = currentLocation,
                    vehicle = activeVehicle,
                    mintUrl = mintUrl,
                    paymentMethods = paymentMethods
                )

                if (eventId != null) {
                    Log.d(TAG, "Broadcast SUCCESS: ${eventId.take(16)}... (total: ${publishedAvailabilityEventIds.size + 1})")
                    publishedAvailabilityEventIds.add(eventId)
                    _uiState.value = _uiState.value.copy(
                        lastBroadcastTime = System.currentTimeMillis()
                    )
                } else {
                    Log.e(TAG, "Broadcast FAILED - no event ID returned")
                }

                Log.d(TAG, "Next broadcast in ${AVAILABILITY_BROADCAST_INTERVAL_MS / 1000} seconds")
                delay(AVAILABILITY_BROADCAST_INTERVAL_MS)
            }
            Log.d(TAG, "Broadcast loop ended (isActive=$isActive)")
        }
    }

    private fun stopBroadcasting() {
        availabilityJob?.cancel()
        availabilityJob = null
    }

    private suspend fun deleteAllAvailabilityEvents() {
        if (publishedAvailabilityEventIds.isEmpty()) {
            Log.d(TAG, "No availability events to delete")
            return
        }

        Log.d(TAG, "Requesting deletion of ${publishedAvailabilityEventIds.size} availability events")
        val deletionEventId = nostrService.deleteEvents(
            publishedAvailabilityEventIds.toList(),
            "driver went offline"
        )

        if (deletionEventId != null) {
            Log.d(TAG, "Deletion request sent: $deletionEventId")
            publishedAvailabilityEventIds.clear()
        } else {
            Log.w(TAG, "Failed to send deletion request")
        }
    }

    private fun subscribeToOffers() {
        offerSubscriptionId?.let { nostrService.closeSubscription(it) }

        offerSubscriptionId = nostrService.subscribeToOffers(viewModelScope) { offer ->
            Log.d(TAG, "Received ride offer from ${offer.riderPubKey.take(8)}...")

            // Filter out offers we've already accepted (prevents duplicates after ride completion)
            if (offer.eventId in acceptedOfferEventIds) {
                Log.d(TAG, "Ignoring already-accepted offer: ${offer.eventId.take(8)}")
                return@subscribeToOffers
            }

            // Filter out offers older than 2 minutes (stale offers) - same as broadcast requests
            val currentTimeSeconds = System.currentTimeMillis() / 1000
            val offerAgeSeconds = currentTimeSeconds - offer.createdAt
            val maxOfferAgeSeconds = 2 * 60 // 2 minutes (was 10 minutes)

            if (offerAgeSeconds > maxOfferAgeSeconds) {
                Log.d(TAG, "Ignoring stale offer (${offerAgeSeconds}s old)")
                return@subscribeToOffers
            }

            val currentOffers = _uiState.value.pendingOffers
            val isNewOffer = currentOffers.none { it.eventId == offer.eventId }
            // Check if this is a fare boost (same rider, different event)
            val isFareBoost = currentOffers.any { it.riderPubKey == offer.riderPubKey }

            if (isNewOffer) {
                // Filter out stale offers AND any existing offer from same rider (fare boost case)
                val freshOffers = currentOffers.filter { existing ->
                    (currentTimeSeconds - existing.createdAt) <= maxOfferAgeSeconds &&
                    existing.riderPubKey != offer.riderPubKey  // Remove old offer from same rider
                }
                // Sort by createdAt descending (newest first)
                val sortedOffers = (freshOffers + offer).sortedByDescending { it.createdAt }
                _uiState.value = _uiState.value.copy(
                    pendingOffers = sortedOffers
                )

                // Update deletion subscription to watch for this offer being cancelled
                updateDeletionSubscription()

                // Notify service of new offer (plays sound, updates notification temporarily)
                // Only for NEW offers, not fare boosts (same as broadcast requests)
                if (!isFareBoost) {
                    val context = getApplication<Application>()
                    val fareDisplay = "${offer.fareEstimate.toInt()} sats"
                    val distanceDisplay = offer.rideRouteKm?.let { "${String.format("%.1f", it)} km ride" } ?: "Direct offer"
                    DriverOnlineService.updateStatus(
                        context,
                        DriverStatus.NewRequest(
                            count = sortedOffers.size,
                            fare = fareDisplay,
                            distance = distanceDisplay
                        )
                    )
                    Log.d(TAG, "Direct offer received - notified service")
                } else {
                    Log.d(TAG, "Fare boost received from same rider - updating offer quietly")
                }

                // Calculate routes for the new offer
                calculateDirectOfferRoutes(offer)
            }
        }
    }

    /**
     * Create a cache key from driver location and pickup location.
     * Rounds to ~100m precision to allow cache hits for nearby locations.
     */
    private fun createLocationCacheKey(driverLoc: Location, pickupLoc: Location): String {
        // Round to 3 decimal places (~111m at equator, good enough for cache)
        val dLat = String.format("%.${LOCATION_CACHE_PRECISION}f", driverLoc.lat)
        val dLon = String.format("%.${LOCATION_CACHE_PRECISION}f", driverLoc.lon)
        val pLat = String.format("%.${LOCATION_CACHE_PRECISION}f", pickupLoc.lat)
        val pLon = String.format("%.${LOCATION_CACHE_PRECISION}f", pickupLoc.lon)
        return "$dLat,$dLon->$pLat,$pLon"
    }

    /**
     * Check if driver has moved significantly since last cache update.
     * If so, clear the cache and recalculate routes for visible requests.
     * Throttled to prevent excessive recalculations when many rides are available.
     */
    private fun checkDriverLocationChange(newLocation: Location) {
        val lastLoc = lastCacheDriverLocation
        if (lastLoc != null) {
            // Simple distance approximation (good enough for ~500m threshold)
            val latDiff = Math.abs(newLocation.lat - lastLoc.lat)
            val lonDiff = Math.abs(newLocation.lon - lastLoc.lon)
            // Roughly convert to km (1 degree ~= 111km)
            val distKm = Math.sqrt(latDiff * latDiff + lonDiff * lonDiff) * 111.0

            if (distKm > DRIVER_MOVEMENT_THRESHOLD_KM) {
                // Check time throttle - don't recalculate too frequently
                val now = System.currentTimeMillis()
                if (now - lastRouteRecalcTimeMs < ROUTE_RECALC_THROTTLE_MS) {
                    Log.d(TAG, "Driver moved but throttling recalc (${(now - lastRouteRecalcTimeMs) / 1000}s since last)")
                    return
                }

                Log.d(TAG, "Driver moved ${String.format("%.2f", distKm)} km, clearing route cache")
                clearRouteCache()
                lastCacheDriverLocation = newLocation
                lastRouteRecalcTimeMs = now

                // Recalculate routes for all visible requests
                _uiState.value.pendingBroadcastRequests.forEach { request ->
                    calculatePickupRoute(request.eventId, request.pickupArea)
                }
            }
        } else {
            lastCacheDriverLocation = newLocation
        }
    }

    /**
     * Clear all cached routes.
     */
    private fun clearRouteCache() {
        locationRouteCache.clear()
        eventToCacheKey.clear()
        _uiState.value = _uiState.value.copy(pickupRoutes = emptyMap())
    }

    /**
     * Calculate the route from driver's current location to a pickup location.
     * Uses location-based caching to avoid recalculating when:
     * - Rider boosts fare (new event, same locations)
     * - Same pickup location from different requests
     * Falls back to straight-line distance if routing fails.
     */
    private fun calculatePickupRoute(requestEventId: String, pickupLocation: Location) {
        val driverLocation = _uiState.value.currentLocation
        if (driverLocation == null) {
            Log.w(TAG, "No driver location available for route calculation")
            return
        }

        // Create cache key based on locations
        val cacheKey = createLocationCacheKey(driverLocation, pickupLocation)

        // Check if we already have this event mapped
        if (eventToCacheKey[requestEventId] == cacheKey && _uiState.value.pickupRoutes.containsKey(requestEventId)) {
            return // Already calculated with same locations
        }

        // Check if we have a cached route for these locations
        val cachedRoute = locationRouteCache[cacheKey]
        if (cachedRoute != null) {
            Log.d(TAG, "Using cached route for ${requestEventId.take(8)} (same locations)")
            eventToCacheKey[requestEventId] = cacheKey
            _uiState.value = _uiState.value.copy(
                pickupRoutes = _uiState.value.pickupRoutes + (requestEventId to cachedRoute)
            )
            return
        }

        // Need to calculate new route
        viewModelScope.launch {
            if (!routingService.isReady()) {
                Log.w(TAG, "Routing service not ready, using straight-line fallback")
                val fallbackRoute = calculateStraightLineRoute(driverLocation, pickupLocation)
                storeFallbackRoute(requestEventId, cacheKey, fallbackRoute)
                return@launch
            }

            Log.d(TAG, "Calculating route: driver(${driverLocation.lat}, ${driverLocation.lon}) -> pickup(${pickupLocation.lat}, ${pickupLocation.lon})")

            val route = routingService.calculateRoute(
                originLat = driverLocation.lat,
                originLon = driverLocation.lon,
                destLat = pickupLocation.lat,
                destLon = pickupLocation.lon
            )

            if (route != null) {
                Log.d(TAG, "Calculated pickup route for ${requestEventId.take(8)}: ${route.distanceKm} km, ${route.durationSeconds}s")

                // Store in location-based cache
                locationRouteCache[cacheKey] = route
                eventToCacheKey[requestEventId] = cacheKey

                // Update UI state with new route
                _uiState.value = _uiState.value.copy(
                    pickupRoutes = _uiState.value.pickupRoutes + (requestEventId to route)
                )
            } else {
                Log.w(TAG, "Valhalla routing failed for ${requestEventId.take(8)}, using straight-line fallback")
                val fallbackRoute = calculateStraightLineRoute(driverLocation, pickupLocation)
                storeFallbackRoute(requestEventId, cacheKey, fallbackRoute)
            }
        }
    }

    /**
     * Calculate straight-line distance between two points as a fallback.
     * Uses Haversine formula and estimates driving time at ~40 km/h average.
     */
    private fun calculateStraightLineRoute(from: Location, to: Location): RouteResult {
        val earthRadiusKm = 6371.0
        val dLat = Math.toRadians(to.lat - from.lat)
        val dLon = Math.toRadians(to.lon - from.lon)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(from.lat)) * Math.cos(Math.toRadians(to.lat)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        val straightLineKm = earthRadiusKm * c

        // Estimate driving distance as 1.3x straight line, time at 40 km/h
        val estimatedDrivingKm = straightLineKm * 1.3
        val estimatedTimeSeconds = (estimatedDrivingKm / 40.0) * 3600.0

        Log.d(TAG, "Straight-line fallback: ${String.format("%.2f", straightLineKm)} km -> est ${String.format("%.2f", estimatedDrivingKm)} km driving")

        return RouteResult(
            distanceKm = estimatedDrivingKm,
            durationSeconds = estimatedTimeSeconds,
            encodedPolyline = null,
            maneuvers = emptyList()
        )
    }

    /**
     * Store a fallback route in cache and UI state.
     */
    private fun storeFallbackRoute(requestEventId: String, cacheKey: String, route: RouteResult) {
        locationRouteCache[cacheKey] = route
        eventToCacheKey[requestEventId] = cacheKey
        _uiState.value = _uiState.value.copy(
            pickupRoutes = _uiState.value.pickupRoutes + (requestEventId to route)
        )
    }

    /**
     * Calculate both pickup route (driver to pickup) and ride route (pickup to destination)
     * for a direct offer. This provides full ride info display.
     *
     * If rider pre-calculated route metrics, uses those directly for instant display.
     * Otherwise falls back to calculating routes locally.
     */
    private fun calculateDirectOfferRoutes(offer: RideOfferData) {
        // Already calculated?
        if (_uiState.value.directOfferPickupRoutes.containsKey(offer.eventId) &&
            _uiState.value.directOfferRideRoutes.containsKey(offer.eventId)) {
            return
        }

        // If rider provided route metrics, use them directly (no calculation needed)
        val pickupKm = offer.pickupRouteKm
        val pickupMin = offer.pickupRouteMin
        val rideKm = offer.rideRouteKm
        val rideMin = offer.rideRouteMin

        if (pickupKm != null && pickupMin != null && rideKm != null && rideMin != null) {
            val pickupRoute = RouteResult(
                distanceKm = pickupKm,
                durationSeconds = pickupMin * 60.0,
                encodedPolyline = null,
                maneuvers = emptyList()
            )
            val rideRoute = RouteResult(
                distanceKm = rideKm,
                durationSeconds = rideMin * 60.0,
                encodedPolyline = null,
                maneuvers = emptyList()
            )

            _uiState.value = _uiState.value.copy(
                directOfferPickupRoutes = _uiState.value.directOfferPickupRoutes + (offer.eventId to pickupRoute),
                directOfferRideRoutes = _uiState.value.directOfferRideRoutes + (offer.eventId to rideRoute)
            )
            Log.d(TAG, "Using rider-provided route metrics for ${offer.eventId.take(8)}: " +
                "pickup ${pickupRoute.distanceKm}km/${(pickupRoute.durationSeconds/60).toInt()}min, " +
                "ride ${rideRoute.distanceKm}km/${(rideRoute.durationSeconds/60).toInt()}min")
            return
        }

        // Fallback: calculate routes locally
        val driverLocation = _uiState.value.currentLocation
        if (driverLocation == null) {
            Log.w(TAG, "No driver location for direct offer route calculation")
            return
        }

        viewModelScope.launch {
            // Calculate pickup route (driver -> pickup)
            val pickupRoute: RouteResult = if (routingService.isReady()) {
                routingService.calculateRoute(
                    originLat = driverLocation.lat,
                    originLon = driverLocation.lon,
                    destLat = offer.approxPickup.lat,
                    destLon = offer.approxPickup.lon
                ) ?: calculateStraightLineRoute(driverLocation, offer.approxPickup)
            } else {
                calculateStraightLineRoute(driverLocation, offer.approxPickup)
            }

            // Calculate ride route (pickup -> destination)
            val rideRoute: RouteResult = if (routingService.isReady()) {
                routingService.calculateRoute(
                    originLat = offer.approxPickup.lat,
                    originLon = offer.approxPickup.lon,
                    destLat = offer.destination.lat,
                    destLon = offer.destination.lon
                ) ?: calculateStraightLineRoute(offer.approxPickup, offer.destination)
            } else {
                calculateStraightLineRoute(offer.approxPickup, offer.destination)
            }

            Log.d(TAG, "Calculated direct offer routes locally for ${offer.eventId.take(8)}: " +
                "pickup ${pickupRoute.distanceKm}km, ride ${rideRoute.distanceKm}km")

            _uiState.value = _uiState.value.copy(
                directOfferPickupRoutes = _uiState.value.directOfferPickupRoutes + (offer.eventId to pickupRoute),
                directOfferRideRoutes = _uiState.value.directOfferRideRoutes + (offer.eventId to rideRoute)
            )
        }
    }

    /**
     * Toggle expanded search to find ride requests in a wider area.
     */
    fun toggleExpandedSearch() {
        _uiState.value = _uiState.value.copy(
            expandedSearch = !_uiState.value.expandedSearch
        )
        Log.d(TAG, "Expanded search: ${_uiState.value.expandedSearch}")

        // Resubscribe with new search area
        _uiState.value.currentLocation?.let { location ->
            subscribeToBroadcastRequests(location)
        }
    }

    /**
     * Subscribe to broadcast ride requests in the driver's area.
     * This is the new primary flow where riders broadcast and any driver can accept.
     */
    private fun subscribeToBroadcastRequests(location: Location) {
        broadcastRequestSubscriptionId?.let { nostrService.closeSubscription(it) }

        val expandSearch = _uiState.value.expandedSearch
        Log.d(TAG, "Subscribing to broadcast requests - expanded: $expandSearch")

        // Start cleanup timer to prune stale requests every 30 seconds
        startStaleRequestCleanup()

        broadcastRequestSubscriptionId = nostrService.subscribeToBroadcastRideRequests(
            location = location,
            expandSearch = expandSearch
        ) { request ->
            Log.d(TAG, "Received broadcast ride request from ${request.riderPubKey.take(8)}, fare=${request.fareEstimate}")

            // Filter out requests we've already accepted
            if (request.eventId in acceptedOfferEventIds) {
                Log.d(TAG, "Ignoring already-accepted request: ${request.eventId.take(8)}")
                return@subscribeToBroadcastRideRequests
            }

            // Filter out requests that were taken by another driver
            if (request.eventId in takenOfferEventIds) {
                Log.d(TAG, "Ignoring taken request: ${request.eventId.take(8)}")
                return@subscribeToBroadcastRideRequests
            }

            // Filter out requests we've already declined/passed on
            if (request.eventId in declinedOfferEventIds) {
                Log.d(TAG, "Ignoring declined request: ${request.eventId.take(8)}")
                return@subscribeToBroadcastRideRequests
            }

            // Filter out stale requests (>2 minutes old for broadcast)
            val currentTimeSeconds = System.currentTimeMillis() / 1000
            val requestAgeSeconds = currentTimeSeconds - request.createdAt
            val maxRequestAgeSeconds = 2 * 60 // 2 minutes for broadcast requests

            if (requestAgeSeconds > maxRequestAgeSeconds) {
                Log.d(TAG, "Ignoring stale broadcast request (${requestAgeSeconds}s old)")
                return@subscribeToBroadcastRideRequests
            }

            // Check if we already have this exact request
            val currentRequests = _uiState.value.pendingBroadcastRequests
            val isNewRequest = currentRequests.none { it.eventId == request.eventId }

            // Check if this is a fare boost (same rider, new event with higher fare)
            val isFareBoost = currentRequests.any { it.riderPubKey == request.riderPubKey }

            if (isNewRequest) {
                val context = getApplication<Application>()

                // Notify service of new request (plays sound, updates notification temporarily)
                // Don't notify for fare boosts (same rider increasing their offer)
                if (!isFareBoost) {
                    // Calculate distance display for notification
                    val fareDisplay = "${request.fareEstimate.toInt()} sats"
                    val driverLocation = _uiState.value.currentLocation
                    val pickupDistanceKm = driverLocation?.distanceToKm(request.pickupArea)
                    val distanceDisplay = if (pickupDistanceKm != null) {
                        if (pickupDistanceKm < 1.0) {
                            "${(pickupDistanceKm * 1000).toInt()} m away"
                        } else {
                            "${String.format("%.1f", pickupDistanceKm)} km away"
                        }
                    } else {
                        "${String.format("%.1f", request.routeDistanceKm)} km ride"
                    }

                    DriverOnlineService.updateStatus(
                        context,
                        DriverStatus.NewRequest(
                            count = currentRequests.size + 1,
                            fare = fareDisplay,
                            distance = distanceDisplay
                        )
                    )
                    Log.d(TAG, "NEW ride request - notified service")
                }
                // Filter out stale requests and any previous requests from the same rider
                // (handles fare boosts - new request replaces old one from same rider)
                val freshRequests = currentRequests.filter {
                    (currentTimeSeconds - it.createdAt) <= maxRequestAgeSeconds &&
                    it.eventId !in takenOfferEventIds &&
                    it.riderPubKey != request.riderPubKey  // Remove old request from same rider
                }
                // Sort by fare (highest first), then by time (newest first)
                val sortedRequests = (freshRequests + request)
                    .sortedWith(compareByDescending<BroadcastRideOfferData> { it.fareEstimate }
                        .thenByDescending { it.createdAt })

                _uiState.value = _uiState.value.copy(
                    pendingBroadcastRequests = sortedRequests
                )

                // Calculate route to pickup location
                calculatePickupRoute(request.eventId, request.pickupArea)

                // Subscribe to acceptances for this request to detect if another driver takes it
                subscribeToAcceptancesForRequest(request.eventId)

                // Update deletion subscription to include this new request
                updateDeletionSubscription()
            }
        }
    }

    /**
     * Subscribe to acceptances for a specific ride request.
     * When another driver accepts, we remove the request from our list.
     */
    private fun subscribeToAcceptancesForRequest(requestEventId: String) {
        // Don't double-subscribe
        if (requestAcceptanceSubscriptionIds.containsKey(requestEventId)) return

        val myPubKey = nostrService.getPubKeyHex() ?: return

        val subId = nostrService.subscribeToAcceptancesForOffer(requestEventId) { acceptance ->
            // If the acceptance is from a different driver, the ride is taken
            if (acceptance.driverPubKey != myPubKey) {
                Log.d(TAG, "Request ${requestEventId.take(8)} was taken by driver ${acceptance.driverPubKey.take(8)}")
                markRequestAsTaken(requestEventId)
            }
        }

        requestAcceptanceSubscriptionIds[requestEventId] = subId
    }

    /**
     * Mark a ride request as taken and remove from visible list.
     */
    private fun markRequestAsTaken(requestEventId: String) {
        takenOfferEventIds.add(requestEventId)
        removeRideRequest(requestEventId)
    }

    /**
     * Remove a ride request from the visible list (used for taken, cancelled, or stale requests).
     */
    private fun removeRideRequest(requestEventId: String) {
        // Remove from pending requests
        val currentRequests = _uiState.value.pendingBroadcastRequests
        val updatedRequests = currentRequests.filter { it.eventId != requestEventId }

        // Remove from eventId -> cacheKey mapping (but keep location cache for other requests with same location)
        eventToCacheKey.remove(requestEventId)
        val updatedRoutes = _uiState.value.pickupRoutes - requestEventId

        _uiState.value = _uiState.value.copy(
            pendingBroadcastRequests = updatedRequests,
            pickupRoutes = updatedRoutes
        )

        // Close the acceptance subscription for this request (no longer needed)
        requestAcceptanceSubscriptionIds.remove(requestEventId)?.let {
            nostrService.closeSubscription(it)
        }

        // Update deletion subscription with new list of event IDs
        updateDeletionSubscription()
    }

    /**
     * Handle a cancelled ride request (rider deleted it).
     * Removes from BOTH broadcast requests and direct offers.
     */
    private fun handleCancelledRideRequest(requestEventId: String) {
        Log.d(TAG, "Rider cancelled request: ${requestEventId.take(8)}")

        // Remove from broadcast requests
        removeRideRequest(requestEventId)

        // Also remove from direct offers
        val currentOffers = _uiState.value.pendingOffers
        val updatedOffers = currentOffers.filter { it.eventId != requestEventId }
        if (updatedOffers.size != currentOffers.size) {
            Log.d(TAG, "Removed cancelled direct offer: ${requestEventId.take(8)}")
            _uiState.value = _uiState.value.copy(
                pendingOffers = updatedOffers
            )
        }
    }

    /**
     * Update the deletion subscription to watch all current pending requests.
     * Called when requests are added or removed.
     * Watches BOTH broadcast requests AND direct offers for cancellations.
     */
    private fun updateDeletionSubscription() {
        // Close existing subscription
        deletionSubscriptionId?.let { nostrService.closeSubscription(it) }
        deletionSubscriptionId = null

        // Combine event IDs from BOTH broadcast requests AND direct offers
        val broadcastEventIds = _uiState.value.pendingBroadcastRequests.map { it.eventId }
        val directOfferEventIds = _uiState.value.pendingOffers.map { it.eventId }
        val allPendingEventIds = (broadcastEventIds + directOfferEventIds).distinct()

        if (allPendingEventIds.isEmpty()) return

        Log.d(TAG, "Watching ${allPendingEventIds.size} requests for deletions (${broadcastEventIds.size} broadcast, ${directOfferEventIds.size} direct)")
        deletionSubscriptionId = nostrService.subscribeToRideRequestDeletions(allPendingEventIds) { deletedEventId ->
            handleCancelledRideRequest(deletedEventId)
        }
    }

    /**
     * Accept a broadcast ride request.
     * This is the new primary flow.
     */
    fun acceptBroadcastRequest(request: BroadcastRideOfferData) {
        viewModelScope.launch {
            // CRITICAL: Clear history from any previous ride to prevent phantom cancellation bug
            // Without this, history from ride #1 (e.g., CANCELLED) would pollute ride #2's events
            clearDriverStateHistory()

            _uiState.value = _uiState.value.copy(isProcessingOffer = true)

            // Include wallet pubkey for P2PK escrow - driver signs with wallet key, not Nostr key
            val walletPubKey = walletService?.getWalletPubKey()
            Log.d(TAG, "Including wallet pubkey in broadcast acceptance: ${walletPubKey?.take(16)}...")

            // Get driver's mint URL for multi-mint support
            val driverMintUrl = walletService?.getSavedMintUrl()

            // Accept with rider's payment method and driver's mint URL
            val eventId = nostrService.acceptBroadcastRide(
                request = request,
                walletPubKey = walletPubKey,
                mintUrl = driverMintUrl,
                paymentMethod = request.paymentMethod  // Confirm rider's requested method
            )

            if (eventId != null) {
                // Track this request as accepted AFTER success
                // CRITICAL: Must be inside success block - if accept fails, request should remain visible for retry
                acceptedOfferEventIds.add(request.eventId)
                Log.d(TAG, "Accepted broadcast ride request: $eventId")
                // Track for unified cleanup on ride completion
                trackEventForCleanup(eventId, "BROADCAST_ACCEPTANCE")
                // Stop broadcasting availability
                stopBroadcasting()
                deleteAllAvailabilityEvents()

                // Create a RideOfferData from broadcast data for compatibility
                // (needed for existing ride flow which uses RideOfferData)
                val compatibleOffer = RideOfferData(
                    eventId = request.eventId,
                    riderPubKey = request.riderPubKey,
                    driverEventId = "", // No driver event reference in broadcast mode
                    driverPubKey = nostrService.getPubKeyHex() ?: "",
                    approxPickup = request.pickupArea,
                    destination = request.destinationArea,
                    fareEstimate = request.fareEstimate,
                    createdAt = request.createdAt,
                    // Carry over payment fields for multi-mint support
                    mintUrl = request.mintUrl,
                    paymentMethod = request.paymentMethod
                )

                // Determine payment path (same mint vs cross-mint)
                val riderMintUrl = request.mintUrl
                val paymentPath = PaymentPath.determine(riderMintUrl, driverMintUrl, request.paymentMethod)
                Log.d(TAG, "PaymentPath (broadcast): $paymentPath (rider: $riderMintUrl, driver: $driverMintUrl)")

                _uiState.value = _uiState.value.copy(
                    stage = DriverStage.RIDE_ACCEPTED,
                    isProcessingOffer = false,
                    acceptedOffer = compatibleOffer,
                    acceptedBroadcastRequest = request,  // Keep original for reference
                    acceptanceEventId = eventId,
                    confirmationEventId = null,  // CRITICAL: Clear old confirmation to prevent stale state
                    pendingOffers = emptyList(),
                    pendingBroadcastRequests = emptyList(),
                    pinAttempts = 0,
                    confirmationWaitStartMs = System.currentTimeMillis(),  // Start confirmation timer
                    statusMessage = "Ride accepted! Waiting for rider confirmation...",
                    // Multi-mint support
                    paymentPath = paymentPath,
                    riderMintUrl = riderMintUrl,
                    crossMintPaymentComplete = false,  // Reset for new ride
                    pendingDepositQuoteId = null,      // Reset pending deposit
                    pendingDepositAmount = null
                )

                // Save ride state for persistence
                saveRideState()

                // Subscribe to rider's confirmation
                subscribeToConfirmation(eventId)
                // Note: We'll subscribe to rider ride state after receiving confirmation

                // Subscribe to rider profile to get their name for ride history
                subscribeToRiderProfile(request.riderPubKey)
            } else {
                _uiState.value = _uiState.value.copy(
                    isProcessingOffer = false,
                    error = "Failed to accept ride request"
                )
            }
        }
    }

    /**
     * Start periodic cleanup of stale ride requests.
     * Runs every 30 seconds to remove requests older than 2 minutes.
     */
    private fun startStaleRequestCleanup() {
        staleRequestCleanupJob?.cancel()
        staleRequestCleanupJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(30_000) // 30 seconds
                pruneStaleRequests()
            }
        }
    }

    /**
     * Stop the stale request cleanup timer.
     */
    private fun stopStaleRequestCleanup() {
        staleRequestCleanupJob?.cancel()
        staleRequestCleanupJob = null
    }

    /**
     * Remove stale requests from the pending list.
     * Cleans up BOTH broadcast requests AND direct offers.
     */
    private fun pruneStaleRequests() {
        val currentTimeSeconds = System.currentTimeMillis() / 1000
        val maxRequestAgeSeconds = 2 * 60 // 2 minutes

        var stateChanged = false

        // Prune stale broadcast requests
        val currentRequests = _uiState.value.pendingBroadcastRequests
        val freshRequests = currentRequests.filter { request ->
            val ageSeconds = currentTimeSeconds - request.createdAt
            val isFresh = ageSeconds <= maxRequestAgeSeconds
            if (!isFresh) {
                Log.d(TAG, "Pruning stale broadcast request ${request.eventId.take(8)} (${ageSeconds}s old)")
            }
            isFresh
        }

        // Prune stale direct offers
        val currentOffers = _uiState.value.pendingOffers
        val freshOffers = currentOffers.filter { offer ->
            val ageSeconds = currentTimeSeconds - offer.createdAt
            val isFresh = ageSeconds <= maxRequestAgeSeconds
            if (!isFresh) {
                Log.d(TAG, "Pruning stale direct offer ${offer.eventId.take(8)} (${ageSeconds}s old)")
            }
            isFresh
        }

        if (freshRequests.size != currentRequests.size) {
            Log.d(TAG, "Pruned ${currentRequests.size - freshRequests.size} stale broadcast requests")
            stateChanged = true
        }

        if (freshOffers.size != currentOffers.size) {
            Log.d(TAG, "Pruned ${currentOffers.size - freshOffers.size} stale direct offers")
            stateChanged = true
        }

        if (stateChanged) {
            _uiState.value = _uiState.value.copy(
                pendingBroadcastRequests = freshRequests,
                pendingOffers = freshOffers
            )
            // Update foreground service notification with total count
            val totalRequests = freshRequests.size + freshOffers.size
            DriverOnlineService.updateStatus(getApplication(), DriverStatus.Available(totalRequests))
            // Update deletion subscription since lists changed
            updateDeletionSubscription()
        }
    }

    /**
     * Decline a broadcast ride request (remove from list without accepting).
     */
    fun declineBroadcastRequest(request: BroadcastRideOfferData) {
        val currentRequests = _uiState.value.pendingBroadcastRequests
        _uiState.value = _uiState.value.copy(
            pendingBroadcastRequests = currentRequests.filter { it.eventId != request.eventId }
        )
        // Add to declined set to prevent it from showing again on refresh
        declinedOfferEventIds.add(request.eventId)
        Log.d(TAG, "Declined request ${request.eventId.take(8)}, total declined: ${declinedOfferEventIds.size}")
    }

    override fun onCleared() {
        super.onCleared()
        stopBroadcasting()
        stopStaleRequestCleanup()
        chatRefreshJob?.cancel()
        confirmationTimeoutJob?.cancel()
        offerSubscriptionId?.let { nostrService.closeSubscription(it) }
        broadcastRequestSubscriptionId?.let { nostrService.closeSubscription(it) }
        deletionSubscriptionId?.let { nostrService.closeSubscription(it) }
        requestAcceptanceSubscriptionIds.values.forEach { nostrService.closeSubscription(it) }
        confirmationSubscriptionId?.let { nostrService.closeSubscription(it) }
        riderRideStateSubscriptionId?.let { nostrService.closeSubscription(it) }
        chatSubscriptionId?.let { nostrService.closeSubscription(it) }
        cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
        nostrService.disconnect()
        // Clean up Bitcoin price service
        bitcoinPriceService.cleanup()
    }
}

/**
 * Payment status for HTLC escrow settlement.
 */
enum class PaymentStatus {
    NO_PAYMENT_EXPECTED,    // No HTLC (cash ride or legacy)
    READY_TO_CLAIM,         // Both preimage and escrow token received
    WAITING_FOR_PREIMAGE,   // Not yet shared (ride still in progress)
    MISSING_PREIMAGE,       // Escrow token received but no preimage
    MISSING_ESCROW_TOKEN,   // Preimage received but no escrow token
    UNKNOWN_ERROR
}

/**
 * UI state for driver mode.
 */
data class DriverUiState(
    // Driver state
    val stage: DriverStage = DriverStage.OFFLINE,
    val currentLocation: Location? = null,
    val lastBroadcastTime: Long? = null,
    // Active vehicle for this driving session (shown to riders)
    val activeVehicle: Vehicle? = null,
    // Search settings - default to expanded (20+ mile radius) for better coverage
    val expandedSearch: Boolean = true,

    // Ride offers - Direct offers (legacy/advanced - only shown when AVAILABLE)
    val pendingOffers: List<RideOfferData> = emptyList(),
    // Ride offers - Broadcast requests (new primary flow)
    val pendingBroadcastRequests: List<BroadcastRideOfferData> = emptyList(),
    // Cached pickup routes for broadcast requests (keyed by eventId)
    val pickupRoutes: Map<String, RouteResult> = emptyMap(),
    // Cached routes for direct offers (keyed by eventId)
    val directOfferPickupRoutes: Map<String, RouteResult> = emptyMap(),  // Driver to pickup
    val directOfferRideRoutes: Map<String, RouteResult> = emptyMap(),    // Pickup to destination
    val isProcessingOffer: Boolean = false,

    // Active ride info
    val acceptedOffer: RideOfferData? = null,                 // Compatible format for ride flow
    val acceptedBroadcastRequest: BroadcastRideOfferData? = null, // Original broadcast data (if applicable)
    val acceptanceEventId: String? = null,  // Our acceptance event (Kind 3174) for cleanup
    val confirmationEventId: String? = null,
    val precisePickupLocation: Location? = null,
    val preciseDestinationLocation: Location? = null,  // Received via progressive reveal after PIN verified

    // PIN verification (rider generates PIN, driver submits for verification)
    val pinAttempts: Int = 0,                           // Number of PIN submission attempts
    val isAwaitingPinVerification: Boolean = false,     // Waiting for rider to verify
    val pinVerificationTimedOut: Boolean = false,       // True if verification timed out (show retry/cancel)
    val lastPinSubmissionEventId: String? = null,       // Last PIN submission event ID

    // Confirmation wait tracking (waiting for rider to confirm after accepting)
    val confirmationWaitStartMs: Long? = null,          // When we started waiting for confirmation
    val confirmationWaitDurationMs: Long = 30_000L,     // 30 seconds to wait for confirmation

    // Chat (NIP-17 style private messaging)
    val chatMessages: List<RideshareChatData> = emptyList(),
    val isSendingMessage: Boolean = false,

    // User identity
    val myPubKey: String = "",

    // Rider profiles (for displaying name in history)
    val riderProfiles: Map<String, UserProfile> = emptyMap(),

    // UI
    val statusMessage: String = "Tap to go online",
    val error: String? = null,

    // Guards for race condition prevention
    val isCancelling: Boolean = false,  // Prevents concurrent cancel/arrive operations

    // HTLC Escrow state (NUT-14)
    val activePaymentHash: String? = null,      // Payment hash from ride offer
    val activePreimage: String? = null,         // Preimage received from rider after PIN verification
    val activeEscrowToken: String? = null,      // HTLC token received from rider
    val canSettleEscrow: Boolean = false,       // True when we have preimage + escrow token

    // Multi-mint payment (Issue #13)
    val paymentPath: PaymentPath = PaymentPath.NO_PAYMENT,  // How payment will be handled
    val riderMintUrl: String? = null,                        // Rider's mint URL (from offer)
    val crossMintPaymentComplete: Boolean = false,           // True when cross-mint bridge payment completed
    val pendingDepositQuoteId: String? = null,               // Quote ID for cross-mint deposit (to claim tokens)
    val pendingDepositAmount: Long? = null,                  // Amount for cross-mint deposit

    // Payment warning dialog (shown when trying to complete without valid escrow)
    val showPaymentWarningDialog: Boolean = false,
    val paymentWarningStatus: PaymentStatus? = null,

    // Rider cancelled claim dialog (shown when rider cancels after PIN verification)
    val showRiderCancelledClaimDialog: Boolean = false,
    val riderCancelledFareAmount: Double? = null,  // Fare amount to potentially claim

    // Wallet not set up warning (shown when going online without wallet configured)
    val showWalletNotSetupWarning: Boolean = false,
    val pendingGoOnlineLocation: Location? = null,
    val pendingGoOnlineVehicle: Vehicle? = null
) {
    /** Convenience property for backward compatibility */
    val isAvailable: Boolean get() = stage == DriverStage.AVAILABLE

    /** True if driver is currently in a ride (any stage from accepted to completed) */
    val isInRide: Boolean get() = stage in listOf(
        DriverStage.RIDE_ACCEPTED,
        DriverStage.EN_ROUTE_TO_PICKUP,
        DriverStage.ARRIVED_AT_PICKUP,
        DriverStage.IN_RIDE,
        DriverStage.RIDE_COMPLETED
    )
}
