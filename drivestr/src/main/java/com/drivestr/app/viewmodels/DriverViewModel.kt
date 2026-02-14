package com.drivestr.app.viewmodels

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drivestr.app.service.DriverOnlineService
import com.drivestr.app.service.DriverStatus
import com.ridestr.common.notification.AlertType
import com.ridestr.common.bitcoin.BitcoinPriceService
import com.ridestr.common.data.DriverRoadflareRepository
import com.ridestr.common.data.RideHistoryRepository
import com.ridestr.common.data.Vehicle
import com.ridestr.common.roadflare.RoadflareLocationBroadcaster
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.SubscriptionManager
import com.ridestr.common.nostr.events.MutedRider
import com.ridestr.common.nostr.events.RoadflareFollower
import com.ridestr.common.nostr.events.RoadflareLocation
import com.ridestr.common.nostr.events.RoadflareLocationEvent
import com.ridestr.common.nostr.events.RideHistoryEntry
import com.ridestr.common.nostr.events.geohash
import com.ridestr.common.nostr.events.BroadcastRideOfferData
import com.ridestr.common.nostr.events.DriverAvailabilityEvent
import com.ridestr.common.nostr.events.DriverRideAction
import com.ridestr.common.nostr.events.DriverRideStateEvent
import com.ridestr.common.nostr.events.DriverRoadflareState
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
import com.ridestr.common.payment.HtlcClaimResult
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
import com.ridestr.common.util.PeriodicRefreshJob
import com.ridestr.common.util.RideHistoryBuilder
import com.drivestr.app.BuildConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Driver state machine stages.
 */
enum class DriverStage {
    OFFLINE,            // Not available for rides
    ROADFLARE_ONLY,     // Broadcasting RoadFlare location, receiving RoadFlare offers only
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

    // Remote config for platform settings (fetched from admin pubkey Kind 30182)
    private val remoteConfigManager = com.ridestr.common.settings.RemoteConfigManager(application, nostrService.relayManager)

    /** Expose remote config for UI (recommended mints, etc.) */
    val remoteConfig get() = remoteConfigManager.config

    // Wallet service for HTLC escrow settlement (injected from MainActivity)
    private var walletService: WalletService? = null

    fun setWalletService(service: WalletService?) {
        walletService = service
    }

    // Ride history repository
    private val rideHistoryRepository = RideHistoryRepository.getInstance(application)

    // RoadFlare repository for driver's follower list and broadcast key
    private val driverRoadflareRepository = DriverRoadflareRepository.getInstance(application)

    // RoadFlare location broadcaster - initialized lazily when signer is available
    private var roadflareLocationBroadcaster: RoadflareLocationBroadcaster? = null

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


    private val _uiState = MutableStateFlow(DriverUiState())
    val uiState: StateFlow<DriverUiState> = _uiState.asStateFlow()

    // Signal for background refresh after sync (observed by MainActivity)
    // Carries verified follower pubkeys to avoid re-querying, null for full refresh
    // Channel.CONFLATED: buffers one value (no lost emissions), consumed once (no stale replays)
    private val _syncTriggeredRefresh = Channel<Set<String>?>(capacity = Channel.CONFLATED)
    val syncTriggeredRefresh = _syncTriggeredRefresh.receiveAsFlow()

    private var availabilityJob: Job? = null
    private var chatRefreshJob: PeriodicRefreshJob? = null
    private var pinVerificationTimeoutJob: Job? = null
    private var confirmationTimeoutJob: Job? = null  // Timeout for rider confirmation after acceptance
    private val subs = SubscriptionManager(nostrService::closeSubscription)

    private object SubKeys {
        const val OFFERS = "offers"
        const val ROADFLARE_OFFERS = "roadflare_offers"
        const val BROADCAST_REQUESTS = "broadcast_requests"
        const val DELETION = "deletion"
        const val REQUEST_ACCEPTANCES = "request_acceptances"  // group
        const val RIDER_PROFILES = "rider_profiles"  // group
        const val CONFIRMATION = "confirmation"
        const val RIDER_RIDE_STATE = "rider_ride_state"
        const val CHAT = "chat"
        const val CANCELLATION = "cancellation"

        val RIDE_ALL = arrayOf(CONFIRMATION, RIDER_RIDE_STATE, CHAT, CANCELLATION)
        val OFFER_ALL = arrayOf(OFFERS, BROADCAST_REQUESTS)
    }

    private val publishedAvailabilityEventIds = mutableListOf<String>()
    // Track accepted offer IDs to filter out when resubscribing (avoids duplicate offers after ride completion)
    private val acceptedOfferEventIds = mutableSetOf<String>()
    // Track offer IDs that have been taken by another driver
    private val takenOfferEventIds = mutableSetOf<String>()
    // Track offer IDs that the driver has declined/passed on
    private val declinedOfferEventIds = mutableSetOf<String>()
    // Track mode before ride started (for "Stay Online" restoration)
    private var stageBeforeRide: DriverStage? = null
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
        DriverStage.ROADFLARE_ONLY -> RideState.CREATED  // Waiting for RoadFlare offers
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

    /**
     * Atomically update only ride-session fields.
     * For pure session updates (no outer UiState fields changed).
     */
    private inline fun updateRideSession(crossinline transform: DriverRideSession.() -> DriverRideSession) {
        _uiState.update { current ->
            current.copy(rideSession = current.rideSession.transform())
        }
    }

    /**
     * Reset ALL ride-related UI state fields to defaults.
     * Called at every ride boundary (completion, cancellation, timeout).
     *
     * This is the SINGLE authoritative reset function. New fields added to
     * DriverRideSession are automatically reset to defaults.
     */
    private fun resetRideUiState(
        stage: DriverStage,
        statusMessage: String,
        error: String? = null
    ) {
        _uiState.update { current ->
            current.copy(
                stage = stage,
                rideSession = DriverRideSession(),
                statusMessage = statusMessage,
                error = error
            )
        }
    }

    /**
     * Close all subscriptions active during a ride and stop ride-related jobs.
     * Does NOT close: offer subscriptions, broadcast request subscriptions,
     * deletion subscription, request acceptance subscriptions (those are
     * managed by the availability/offer lifecycle).
     */
    private fun closeAllRideSubscriptionsAndJobs() {
        subs.closeAll(*SubKeys.RIDE_ALL)

        stopChatRefreshJob()
        confirmationTimeoutJob?.cancel()
        confirmationTimeoutJob = null
        pinVerificationTimeoutJob?.cancel()
        pinVerificationTimeoutJob = null

        Log.d(TAG, "Closed all ride subscriptions and jobs")
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

        // Fetch remote config (platform settings) from admin pubkey
        viewModelScope.launch {
            remoteConfigManager.fetchConfig()
            Log.d(TAG, "Remote config loaded: fare=$${remoteConfigManager.config.value.fareRateUsdPerMile}/mi")
        }

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
            if (initialized && _uiState.value.rideSession.pendingBroadcastRequests.isNotEmpty()) {
                Log.d(TAG, "Recalculating routes for ${_uiState.value.rideSession.pendingBroadcastRequests.size} pending requests")
                _uiState.value.rideSession.pendingBroadcastRequests.forEach { request ->
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

        // If driver is AVAILABLE or ROADFLARE_ONLY, request location refresh
        // The UI will fetch GPS and call handleLocationUpdate()
        if (state.stage == DriverStage.AVAILABLE || state.stage == DriverStage.ROADFLARE_ONLY) {
            Log.d(TAG, "Driver is ${state.stage}, requesting location refresh")
            _locationRefreshRequested.value = true
        }

        // If we have an active ride with a confirmation, refresh subscriptions
        val session = state.rideSession
        val confId = session.confirmationEventId
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
            session.acceptedOffer?.riderPubKey?.let { riderPubKey ->
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
        val session = state.rideSession

        // Only save if we're in an active ride
        if (!state.isInRide) {
            clearSavedRideState()
            return
        }

        val offer = session.acceptedOffer ?: return

        try {
            val json = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("stage", state.stage.name)
                put("acceptanceEventId", session.acceptanceEventId)
                put("confirmationEventId", session.confirmationEventId)
                put("pinAttempts", session.pinAttempts)

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
                session.precisePickupLocation?.let {
                    put("precisePickupLat", it.lat)
                    put("precisePickupLon", it.lon)
                }

                // Chat messages
                val messagesArray = org.json.JSONArray()
                for (msg in session.chatMessages) {
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
            Log.d(TAG, "Saved ride state: stage=${state.stage}, messages=${session.chatMessages.size}, eventIds=${myRideEventIds.size}")
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
            _uiState.update { current ->
                current.copy(
                    stage = stage,
                    statusMessage = getStatusMessageForStage(stage),
                    rideSession = DriverRideSession(
                        acceptedOffer = offer,
                        acceptanceEventId = acceptanceEventId,
                        confirmationEventId = confirmationEventId,
                        precisePickupLocation = precisePickup,
                        pinAttempts = pinAttempts,
                        chatMessages = chatMessages
                    )
                )
            }

            // Re-subscribe to relevant events
            if (acceptanceEventId != null && stage == DriverStage.RIDE_ACCEPTED) {
                subscribeToConfirmation(acceptanceEventId, offer.riderPubKey)
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
            DriverStage.ROADFLARE_ONLY -> "Available for RoadFlare requests only"
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

        if (currentState.stage != DriverStage.OFFLINE && currentState.stage != DriverStage.ROADFLARE_ONLY) return

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
        val wasRoadflareOnly = currentState.stage == DriverStage.ROADFLARE_ONLY

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
        // Start availability broadcasting and full offer subscriptions
        resumeOfferSubscriptions(location)

        if (wasRoadflareOnly) {
            // Close roadflare-only subscription (full subscribeToOffers replaces it)
            closeRoadflareOfferSubscription()
            // RoadFlare broadcasting already running — don't restart
            // Update service status from ROADFLARE_ONLY to AVAILABLE (service is authoritative)
            DriverOnlineService.updateStatus(context, DriverStatus.Available(0))
        } else {
            // Fresh start: launch foreground service and RoadFlare broadcasting
            DriverOnlineService.start(context)
            // Sync RoadFlare state from Nostr if local state is missing (cross-device sync)
            viewModelScope.launch {
                ensureRoadflareStateSynced()
                startRoadflareBroadcasting()
            }
        }
        // Note: driverOnlineStatus is now set by DriverOnlineService (authoritative)
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
     * Go to RoadFlare-only mode: broadcast location to followers and receive
     * RoadFlare-tagged offers. Broadcasts Kind 30173 WITHOUT location/geohash
     * so driver is invisible to geographic searches but trackable by pubkey.
     */
    fun goRoadflareOnly(location: Location, vehicle: Vehicle?) {
        val context = getApplication<Application>()

        _uiState.value = _uiState.value.copy(
            stage = DriverStage.ROADFLARE_ONLY,
            currentLocation = location,
            activeVehicle = vehicle,
            statusMessage = "Available for RoadFlare requests only"
        )

        // Start foreground service in ROADFLARE_ONLY mode immediately
        // This avoids the race window where start() sets AVAILABLE then updateStatus()
        // sets ROADFLARE_ONLY - during which RoadFlare requests could be dropped
        DriverOnlineService.startRoadflareOnly(context)

        // Sync RoadFlare state from Nostr if local state is missing (cross-device sync)
        // then start RoadFlare broadcasting + offer subscription (RoadFlare-tagged only)
        viewModelScope.launch {
            ensureRoadflareStateSynced()
            startRoadflareBroadcasting()
            subscribeToRoadflareOffers()

            // Publish locationless Kind 30173 so availability subscription works
            // (Driver is trackable by pubkey but invisible to geographic searches)
            nostrService.broadcastAvailability(
                location = null,
                status = DriverAvailabilityEvent.STATUS_AVAILABLE
            )
        }
    }

    /**
     * Go offline.
     */
    fun goOffline() {
        val currentState = _uiState.value
        val context = getApplication<Application>()

        if (currentState.stage != DriverStage.AVAILABLE && currentState.stage != DriverStage.ROADFLARE_ONLY) return

        // Clear saved pre-ride mode when explicitly going offline
        stageBeforeRide = null

        // Stop broadcasting and subscriptions based on current stage
        if (currentState.stage == DriverStage.AVAILABLE) {
            stopBroadcasting()
            closeOfferSubscription()
        }
        if (currentState.stage == DriverStage.ROADFLARE_ONLY) {
            closeRoadflareOfferSubscription()
        }
        // Broadcast final OFFLINE status to followers before stopping
        broadcastRoadflareOfflineStatus()
        stopRoadflareBroadcasting()

        // Capture state before launching coroutine
        val lastLocation = currentState.currentLocation
        val wasRoadflareOnly = currentState.stage == DriverStage.ROADFLARE_ONLY

        viewModelScope.launch {
            // Broadcast offline status based on previous mode
            if (wasRoadflareOnly) {
                // ROADFLARE_ONLY: locationless offline (preserves privacy)
                val eventId = nostrService.broadcastAvailability(
                    location = null,
                    status = DriverAvailabilityEvent.STATUS_OFFLINE
                )
                if (eventId != null) {
                    Log.d(TAG, "Broadcast locationless offline status: $eventId")
                }
            } else if (lastLocation != null) {
                // AVAILABLE: offline with location (needed for geographic removal)
                val eventId = nostrService.broadcastAvailability(
                    location = lastLocation,
                    status = DriverAvailabilityEvent.STATUS_OFFLINE
                )
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
            // Note: driverOnlineStatus is now set by DriverOnlineService (authoritative)
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

        if (currentState.stage == DriverStage.AVAILABLE || currentState.stage == DriverStage.ROADFLARE_ONLY) {
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
    fun handleLocationUpdate(newLocation: Location, force: Boolean = false) {
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
        val session = state.rideSession

        // Clear saved pre-ride mode to prevent stale restore
        stageBeforeRide = null

        // Guard: Already cancelling - prevent duplicate invocations
        if (session.isCancelling) {
            Log.d(TAG, "Already cancelling, ignoring duplicate cancelRide() call")
            return
        }

        // Guard: Not in an active ride
        if (state.stage == DriverStage.OFFLINE || state.stage == DriverStage.ROADFLARE_ONLY || state.stage == DriverStage.AVAILABLE) return

        // STATE_MACHINE: Validate CANCEL transition
        val myPubkey = nostrService.getPubKeyHex() ?: ""
        validateTransition(RideEvent.Cancel(myPubkey, "Driver cancelled"))

        // Set cancelling flag IMMEDIATELY (synchronous) to prevent race conditions
        updateRideSession { copy(isCancelling = true) }

        // Synchronous cleanup
        closeAllRideSubscriptionsAndJobs()
        session.acceptedOffer?.riderPubKey?.let { unsubscribeFromRiderProfile(it) }
        clearDriverStateHistory()
        broadcastRoadflareOfflineStatus()
        stopRoadflareBroadcasting()
        DriverOnlineService.stop(getApplication())

        // Capture state values before launching coroutine
        val confId = session.confirmationEventId
        val riderPubKey = session.acceptedOffer?.riderPubKey
        val currentLoc = state.currentLocation

        viewModelScope.launch {
            // Send cancelled status if we have a confirmation
            if (confId != null && riderPubKey != null) {
                Log.d(TAG, "Publishing ride cancellation to rider")
                val cancellationEventId = nostrService.publishRideCancellation(
                    confirmationEventId = confId,
                    otherPartyPubKey = riderPubKey,
                    reason = "Driver cancelled"
                )
                cancellationEventId?.let { myRideEventIds.add(it) }

                val statusEventId = updateDriverStatus(
                    confirmationEventId = confId,
                    riderPubKey = riderPubKey,
                    status = DriverStatusType.CANCELLED,
                    location = currentLoc
                )
                statusEventId?.let { myRideEventIds.add(it) }
            }

            // Broadcast offline status so riders/followers see driver is unavailable
            nostrService.broadcastAvailability(
                location = null,
                status = DriverAvailabilityEvent.STATUS_OFFLINE
            )

            // Reset ALL ride state
            resetRideUiState(
                stage = DriverStage.OFFLINE,
                statusMessage = "Ride cancelled. Tap to go online."
            )

            clearSavedRideState()
            cleanupRideEventsInBackground("ride cancelled")
        }
    }

    /**
     * Return to available after completing a ride.
     */
    fun finishAndGoOnline(location: Location) {
        // CRITICAL: Capture state BEFORE any cleanup — used for history save + mode restore
        val state = _uiState.value
        val session = state.rideSession
        val offer = session.acceptedOffer
        val restoreRoadflareOnly = stageBeforeRide == DriverStage.ROADFLARE_ONLY

        // Synchronous cleanup
        closeAllRideSubscriptionsAndJobs()
        clearDriverStateHistory()

        // Reset ALL ride state
        resetRideUiState(
            stage = DriverStage.OFFLINE,
            statusMessage = "Tap to go online"
        )

        clearSavedRideState()

        // Save completed ride to history using PRE-RESET captured state
        if (offer != null) {
            viewModelScope.launch {
                try {
                    val vehicle = state.activeVehicle
                    val riderProfile = state.riderProfiles[offer.riderPubKey]
                    val historyEntry = RideHistoryEntry(
                        rideId = session.confirmationEventId ?: session.acceptanceEventId ?: offer.eventId,
                        timestamp = RideHistoryBuilder.currentTimestampSeconds(),
                        role = "driver",
                        counterpartyPubKey = offer.riderPubKey,
                        pickupGeohash = offer.approxPickup.geohash(6),
                        dropoffGeohash = offer.destination.geohash(6),
                        distanceMiles = RideHistoryBuilder.toDistanceMiles(offer.rideRouteKm),
                        durationMinutes = offer.rideRouteMin?.toInt() ?: 0,
                        fareSats = offer.fareEstimate.toLong(),
                        status = "completed",
                        vehicleMake = vehicle?.make,
                        vehicleModel = vehicle?.model,
                        counterpartyFirstName = RideHistoryBuilder.extractCounterpartyFirstName(riderProfile),
                        appOrigin = RideHistoryRepository.APP_ORIGIN_DRIVESTR
                    )
                    rideHistoryRepository.addRide(historyEntry)
                    Log.d(TAG, "Saved completed ride to history: ${historyEntry.rideId}")
                    rideHistoryRepository.backupToNostr(nostrService)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save ride to history", e)
                }
            }
        }

        // Post-reset cleanup
        offer?.riderPubKey?.let { unsubscribeFromRiderProfile(it) }
        cleanupRideEventsInBackground("ride completed")

        // Reset broadcast state for fresh start
        publishedAvailabilityEventIds.clear()
        lastBroadcastLocation = null
        lastBroadcastTimeMs = 0L

        stageBeforeRide = null

        // Reset RoadFlare on-ride flag BEFORE going online
        updateRoadflareOnRideStatus(false)

        // Restore pre-ride mode using PRE-RESET captured state
        if (restoreRoadflareOnly) {
            Log.d(TAG, "Restoring ROADFLARE_ONLY mode after ride completion")
            goRoadflareOnly(location, state.activeVehicle)
        } else {
            goOnline(location, state.activeVehicle)
        }
    }

    /**
     * Subscribe to a rider's profile to get their name for ride history.
     */
    private fun subscribeToRiderProfile(riderPubKey: String) {
        if (subs.groupContains(SubKeys.RIDER_PROFILES, riderPubKey)) return

        val subId = nostrService.subscribeToProfile(riderPubKey) { profile ->
            Log.d(TAG, "Got profile for rider ${riderPubKey.take(8)}: ${profile.bestName()}")
            val currentProfiles = _uiState.value.riderProfiles.toMutableMap()
            currentProfiles[riderPubKey] = profile
            _uiState.value = _uiState.value.copy(riderProfiles = currentProfiles)
        }
        subs.setInGroup(SubKeys.RIDER_PROFILES, riderPubKey, subId)
    }

    /**
     * Unsubscribe from a rider's profile when the ride ends.
     */
    private fun unsubscribeFromRiderProfile(riderPubKey: String) {
        subs.closeInGroup(SubKeys.RIDER_PROFILES, riderPubKey)
        val currentProfiles = _uiState.value.riderProfiles.toMutableMap()
        currentProfiles.remove(riderPubKey)
        _uiState.value = _uiState.value.copy(riderProfiles = currentProfiles)
    }

    private fun closeOfferSubscription() {
        subs.closeAll(*SubKeys.OFFER_ALL)
        subs.close(SubKeys.DELETION)
        subs.closeGroup(SubKeys.REQUEST_ACCEPTANCES)
        takenOfferEventIds.clear()
        stopStaleRequestCleanup()
    }

    /**
     * Resume all offer/broadcast subscriptions after returning to AVAILABLE.
     * Counterpart to closeOfferSubscription(). Must be called whenever
     * manually returning to AVAILABLE without going through proceedGoOnline().
     *
     * Note: subscribeToBroadcastRequests() implicitly restarts staleRequestCleanupJob.
     */
    private fun resumeOfferSubscriptions(location: Location) {
        startBroadcasting(location)
        subscribeToBroadcastRequests(location)
        subscribeToOffers()
        updateDeletionSubscription()
    }

    fun acceptOffer(offer: RideOfferData) {
        viewModelScope.launch {
            clearDriverStateHistory()

            // STATE_MACHINE: Initialize context for new ride and validate ACCEPT transition
            val myPubkey = nostrService.getPubKeyHex() ?: ""
            val newContext = RideContext.forOffer(
                riderPubkey = offer.riderPubKey,
                approxPickup = offer.approxPickup,
                destination = offer.destination,
                fareEstimateSats = offer.fareEstimate.toLong(),
                offerEventId = offer.eventId,
                paymentHash = null,  // paymentHash comes in confirmation (Kind 3175), not offer
                riderMintUrl = offer.mintUrl,
                paymentMethod = offer.paymentMethod
            )
            rideContext = newContext
            rideState = RideState.CREATED

            stageBeforeRide = _uiState.value.stage

            validateTransition(RideEvent.Accept(
                inputterPubkey = myPubkey,
                driverPubkey = myPubkey,
                walletPubkey = walletService?.getWalletPubKey(),
                mintUrl = walletService?.getSavedMintUrl()
            ))

            updateRideSession { copy(isProcessingOffer = true) }

            val walletPubKey = walletService?.getWalletPubKey()
            val driverMintUrl = walletService?.getSavedMintUrl()

            val eventId = nostrService.acceptRide(
                offer = offer,
                walletPubKey = walletPubKey,
                mintUrl = driverMintUrl,
                paymentMethod = offer.paymentMethod
            )

            if (eventId != null) {
                setupAcceptedRide(
                    acceptanceEventId = eventId,
                    offer = offer,
                    broadcastRequest = null,
                    walletPubKey = walletPubKey,
                    driverMintUrl = driverMintUrl,
                    cleanupTag = "ACCEPTANCE"
                )
            } else {
                _uiState.update { current ->
                    current.copy(
                        error = "Failed to accept ride",
                        rideSession = current.rideSession.copy(isProcessingOffer = false)
                    )
                }
            }
        }
    }

    /**
     * Shared post-acceptance setup for both direct and broadcast rides.
     * Called after the Kind 3174 acceptance event has been successfully published.
     *
     * Must be suspend because deleteAllAvailabilityEvents() is suspend.
     */
    private suspend fun setupAcceptedRide(
        acceptanceEventId: String,
        offer: RideOfferData,
        broadcastRequest: BroadcastRideOfferData?,
        walletPubKey: String?,
        driverMintUrl: String?,
        cleanupTag: String
    ) {
        // Track offer as accepted (so it won't show up again after ride completion)
        acceptedOfferEventIds.add(offer.eventId)
        Log.d(TAG, "Accepted ride${if (broadcastRequest != null) " (broadcast)" else ""}: $acceptanceEventId")
        trackEventForCleanup(acceptanceEventId, cleanupTag)

        // Phase 1: Stop receiving offers FIRST — close offer subscriptions immediately.
        // This must happen before deleteAllAvailabilityEvents() (a suspend network call)
        // because offer callbacks (processIncomingOffer, subscribeToBroadcastRequests) have
        // NO stage guards and would mutate pendingOffers/pendingBroadcastRequests during the
        // await window. Closing subscriptions eliminates this race.
        // Note: This also clears takenOfferEventIds — intentional. By the time the driver returns
        // to AVAILABLE (10-30+ min), all pre-ride request IDs are well past the 2-minute stale-age
        // check (line 3431), so they'd be filtered anyway. Fresh subscriptions get fresh data.
        stopBroadcasting()
        closeRoadflareOfferSubscription()
        closeOfferSubscription()

        // Phase 2: Delete availability events from relays (suspend network call).
        // Safe to do after closing offer subs — no callbacks can fire during this await.
        deleteAllAvailabilityEvents()

        // Debug: verify offer subscriptions are actually closed
        if (BuildConfig.DEBUG) {
            val leaks = listOfNotNull(
                SubKeys.OFFERS.takeIf { subs.get(it) != null },
                SubKeys.BROADCAST_REQUESTS.takeIf { subs.get(it) != null },
                SubKeys.DELETION.takeIf { subs.get(it) != null }
            )
            if (leaks.isNotEmpty()) {
                Log.w(TAG, "Offer subscription leak after closeOfferSubscription(): $leaks")
            }
        }

        // Determine payment path (same mint vs cross-mint)
        val paymentPath = PaymentPath.determine(offer.mintUrl, driverMintUrl, offer.paymentMethod)
        Log.d(TAG, "PaymentPath: $paymentPath (rider: ${offer.mintUrl}, driver: $driverMintUrl)")

        _uiState.update { current ->
            current.copy(
                stage = DriverStage.RIDE_ACCEPTED,
                statusMessage = "Ride accepted! Waiting for rider confirmation...",
                rideSession = current.rideSession.copy(
                    isProcessingOffer = false,
                    acceptedOffer = offer,
                    acceptedBroadcastRequest = broadcastRequest,
                    acceptanceEventId = acceptanceEventId,
                    confirmationEventId = null,
                    pendingOffers = emptyList(),
                    pendingBroadcastRequests = emptyList(),
                    pinAttempts = 0,
                    confirmationWaitStartMs = System.currentTimeMillis(),
                    activePaymentHash = null,
                    activePreimage = null,
                    activeEscrowToken = null,
                    canSettleEscrow = false,
                    paymentPath = paymentPath,
                    riderMintUrl = offer.mintUrl,
                    crossMintPaymentComplete = false,
                    pendingDepositQuoteId = null,
                    pendingDepositAmount = null
                )
            )
        }

        // STATE_MACHINE: Update state to ACCEPTED
        val myPubkey = nostrService.getPubKeyHex() ?: ""
        updateStateMachineState(
            RideState.ACCEPTED,
            rideContext?.withDriver(
                driverPubkey = myPubkey,
                driverWalletPubkey = walletPubKey,
                driverMintUrl = driverMintUrl
            )
        )

        // Update RoadFlare broadcaster to indicate on-ride status
        updateRoadflareOnRideStatus(true)

        // Save ride state for persistence
        saveRideState()

        // Subscribe to rider's confirmation (references our acceptance event ID)
        subscribeToConfirmation(acceptanceEventId, offer.riderPubKey)

        // Subscribe to rider profile for ride history name
        subscribeToRiderProfile(offer.riderPubKey)
    }

    /**
     * Start driving to pickup location.
     * Called after rider confirms with precise location.
     */
    fun startRouteToPickup() {
        val state = _uiState.value
        val session = state.rideSession
        if (state.stage != DriverStage.RIDE_ACCEPTED) return

        viewModelScope.launch {
            // Send status update to rider via driver ride state
            session.confirmationEventId?.let { confId ->
                session.acceptedOffer?.riderPubKey?.let { riderPubKey ->
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

            _uiState.update { current ->
                current.copy(
                    stage = DriverStage.EN_ROUTE_TO_PICKUP,
                    statusMessage = "Ride accepted, Heading to Pickup"
                )
            }
        }
    }

    /**
     * Mark arrival at pickup location.
     */
    fun arrivedAtPickup() {
        val state = _uiState.value
        val session = state.rideSession

        // Guard: Check stage and cancelling flag to prevent race conditions
        if (state.stage != DriverStage.EN_ROUTE_TO_PICKUP) return
        if (session.isCancelling) {
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
        val confId = session.confirmationEventId
        val riderPubKey = session.acceptedOffer?.riderPubKey
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
        val session = state.rideSession
        if (state.stage != DriverStage.ARRIVED_AT_PICKUP) return

        val confirmationEventId = session.confirmationEventId ?: return
        val riderPubKey = session.acceptedOffer?.riderPubKey ?: return

        viewModelScope.launch {
            _uiState.update { current ->
                current.copy(
                    statusMessage = "Verifying PIN...",
                    rideSession = current.rideSession.copy(isAwaitingPinVerification = true)
                )
            }

            // For CROSS_MINT: Share deposit invoice first so rider can pay after PIN verification
            if (session.paymentPath == PaymentPath.CROSS_MINT) {
                val fareAmount = session.acceptedOffer?.fareEstimate?.toLong() ?: 0L
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
                _uiState.update { current ->
                    current.copy(
                        statusMessage = "Waiting for rider to verify PIN...",
                        rideSession = current.rideSession.copy(lastPinSubmissionEventId = eventId)
                    )
                }
                // Start timeout for PIN verification
                startPinVerificationTimeout()
            } else {
                _uiState.update { current ->
                    current.copy(
                        error = "Failed to submit PIN",
                        rideSession = current.rideSession.copy(isAwaitingPinVerification = false)
                    )
                }
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
        // Guard: Don't create a new quote if one already exists for this ride
        // This prevents race condition where rider pays first quote but driver tracks second
        val existingQuoteId = _uiState.value.rideSession.pendingDepositQuoteId
        if (existingQuoteId != null) {
            Log.d(TAG, "Deposit invoice already exists (quote=$existingQuoteId) - skipping duplicate")
            return
        }

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
            updateRideSession { copy(
                pendingDepositQuoteId = quote.quote,
                pendingDepositAmount = amountSats
            ) }

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
            if (state.rideSession.isAwaitingPinVerification && state.stage == DriverStage.ARRIVED_AT_PICKUP) {
                Log.w(TAG, "PIN verification timed out - no response from rider")
                _uiState.update { current ->
                    current.copy(
                        statusMessage = "No response from rider. Try again or cancel.",
                        rideSession = current.rideSession.copy(
                            isAwaitingPinVerification = false,
                            pinVerificationTimedOut = true
                        )
                    )
                }
            }
        }
    }

    /**
     * Retry PIN submission after timeout.
     */
    fun retryPinSubmission(pin: String) {
        updateRideSession { copy(pinVerificationTimedOut = false) }
        submitPinForVerification(pin)
    }

    /**
     * Cancel the current ride (can be called at any stage).
     */
    fun cancelCurrentRide(reason: String = "driver_cancelled") {
        val state = _uiState.value
        val session = state.rideSession
        val confirmationEventId = session.confirmationEventId
        val riderPubKey = session.acceptedOffer?.riderPubKey
        val offer = session.acceptedOffer

        // Synchronous cleanup
        closeAllRideSubscriptionsAndJobs()
        session.acceptedOffer?.riderPubKey?.let { unsubscribeFromRiderProfile(it) }
        clearDriverStateHistory()
        stageBeforeRide = null
        broadcastRoadflareOfflineStatus()
        stopRoadflareBroadcasting()
        DriverOnlineService.stop(getApplication())

        viewModelScope.launch {
            // Save cancelled ride to history (only if we had an accepted offer)
            if (offer != null) {
                try {
                    val vehicle = state.activeVehicle
                    val historyEntry = RideHistoryEntry(
                        rideId = confirmationEventId ?: session.acceptanceEventId ?: offer.eventId,
                        timestamp = RideHistoryBuilder.currentTimestampSeconds(),
                        role = "driver",
                        counterpartyPubKey = offer.riderPubKey,
                        pickupGeohash = offer.approxPickup.geohash(6),
                        dropoffGeohash = offer.destination.geohash(6),
                        distanceMiles = RideHistoryBuilder.toDistanceMiles(offer.rideRouteKm),
                        durationMinutes = 0,
                        fareSats = 0,
                        status = "cancelled",
                        vehicleMake = vehicle?.make,
                        vehicleModel = vehicle?.model,
                        appOrigin = RideHistoryRepository.APP_ORIGIN_DRIVESTR
                    )
                    rideHistoryRepository.addRide(historyEntry)
                    Log.d(TAG, "Saved cancelled ride to history: ${historyEntry.rideId}")
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
                cancellationEventId?.let { myRideEventIds.add(it) }
                Log.d(TAG, "Sent cancellation event for ride: $confirmationEventId")
            }

            // Reset ALL ride state
            resetRideUiState(
                stage = DriverStage.OFFLINE,
                statusMessage = "Ride cancelled"
            )

            cleanupRideEventsInBackground("ride cancelled")
            clearSavedRideState()
        }
    }

    /**
     * Subscribe to rider ride state (Kind 30181) for PIN verification and precise location reveals.
     * This unified subscription replaces both subscribeToVerifications and subscribeToPreciseLocationReveals.
     */
    private fun subscribeToRiderRideState(confirmationEventId: String, riderPubKey: String) {
        Log.d(TAG, "Subscribing to rider ride state for confirmation: ${confirmationEventId.take(8)}")

        val newSubId = nostrService.subscribeToRiderRideState(
            confirmationEventId = confirmationEventId,
            riderPubKey = riderPubKey
        ) { riderState ->
            handleRiderRideState(riderState)
        }
        subs.set(SubKeys.RIDER_RIDE_STATE, newSubId)

        if (newSubId != null) {
            Log.d(TAG, "Rider ride state subscription created: $newSubId")
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
        val session = state.rideSession

        // Only process if we're at pickup AND we actually submitted a PIN (or timed out)
        if (state.stage != DriverStage.ARRIVED_AT_PICKUP) {
            Log.d(TAG, "Ignoring verification - not at pickup (stage=${state.stage})")
            return
        }

        if (!session.isAwaitingPinVerification && !session.pinVerificationTimedOut) {
            Log.d(TAG, "Ignoring verification - not awaiting verification (stale event?)")
            return
        }

        Log.d(TAG, "Processing PIN verification: verified=$verified, attempt=$attempt")

        updateRideSession { copy(
            isAwaitingPinVerification = false,
            pinVerificationTimedOut = false,
            pinAttempts = attempt
        ) }

        if (verified) {
            Log.d(TAG, "PIN verified! Starting ride.")
            startRide()
        } else {
            Log.w(TAG, "PIN rejected! Attempt $attempt")
            val remainingAttempts = 3 - attempt
            if (remainingAttempts <= 0) {
                // Rider cancelled due to brute force protection
                Log.w(TAG, "PIN brute force detected - cancelling ride")

                val riderPubKey = session.acceptedOffer?.riderPubKey

                // Full cleanup
                closeAllRideSubscriptionsAndJobs()
                riderPubKey?.let { unsubscribeFromRiderProfile(it) }
                clearDriverStateHistory()
                stageBeforeRide = null
                broadcastRoadflareOfflineStatus()
                stopRoadflareBroadcasting()
                DriverOnlineService.stop(getApplication())

                resetRideUiState(
                    stage = DriverStage.OFFLINE,
                    statusMessage = "Ride cancelled by rider",
                    error = "Ride cancelled - too many wrong PIN attempts"
                )

                clearSavedRideState()
                cleanupRideEventsInBackground("pin_brute_force")
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
        val riderPubKey = _uiState.value.rideSession.acceptedOffer?.riderPubKey ?: return

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
                    _uiState.update { current ->
                        current.copy(
                            statusMessage = "Received precise pickup location",
                            rideSession = current.rideSession.copy(precisePickupLocation = location)
                        )
                    }
                }
                RiderRideStateEvent.LocationType.DESTINATION -> {
                    Log.d(TAG, "Received precise destination location")
                    _uiState.update { current ->
                        current.copy(
                            statusMessage = "Received precise destination",
                            rideSession = current.rideSession.copy(preciseDestinationLocation = location)
                        )
                    }
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
        val session = _uiState.value.rideSession
        val riderPubKey = session.acceptedOffer?.riderPubKey ?: run {
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
                if (BuildConfig.DEBUG) Log.d(TAG, "Decrypted preimage: ${preimage.length} chars, ${preimage.take(16)}...")

                // Verify preimage matches payment hash (if we have one)
                val paymentHash = session.activePaymentHash
                if (paymentHash != null) {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Verifying preimage against payment hash: ${paymentHash.take(16)}...")
                    if (!PaymentCrypto.verifyPreimage(preimage, paymentHash)) {
                        Log.e(TAG, "Preimage verification FAILED - hash mismatch!")
                        _uiState.value = _uiState.value.copy(error = "Invalid payment preimage received")
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
                        if (BuildConfig.DEBUG) Log.d(TAG, "Decrypted escrow token: ${escrowToken.length} chars, ${escrowToken.take(30)}...")
                    } else {
                        Log.e(TAG, "Failed to decrypt escrow token - NIP-44 decryption returned null")
                    }
                } else {
                    Log.w(TAG, "No encrypted escrow token in preimage share")
                }

                // Update state with escrow info
                val canSettle = preimage != null && escrowToken != null
                Log.d(TAG, "Updating state: canSettleEscrow=$canSettle")
                updateRideSession { copy(
                    activePreimage = preimage,
                    activeEscrowToken = escrowToken,
                    canSettleEscrow = canSettle,
                    showPaymentWarningDialog = if (canSettle) false else showPaymentWarningDialog,
                    paymentWarningStatus = if (canSettle) null else paymentWarningStatus
                ) }

                if (canSettle) {
                    Log.d(TAG, "=== ESCROW READY FOR SETTLEMENT ===")
                } else {
                    Log.w(TAG, "Escrow NOT ready: preimage=${preimage != null}, escrowToken=${escrowToken != null}")

                    // EARLY WARNING: If payment was expected but can't be claimed, warn driver immediately
                    // Skip for CROSS_MINT - escrow token is expected to be null (uses Lightning bridge instead)
                    if (session.activePaymentHash != null && session.paymentPath == PaymentPath.SAME_MINT) {
                        Log.w(TAG, "Payment issue detected - showing warning dialog immediately")
                        updateRideSession { copy(
                            showPaymentWarningDialog = true,
                            paymentWarningStatus = when {
                                preimage == null -> PaymentStatus.MISSING_PREIMAGE
                                escrowToken == null -> PaymentStatus.MISSING_ESCROW_TOKEN
                                else -> PaymentStatus.UNKNOWN_ERROR
                            }
                        ) }
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
        if (BuildConfig.DEBUG) Log.d(TAG, "Preimage: ${bridgeComplete.preimage.take(16)}...")

        // The Lightning preimage proves the rider paid the deposit invoice.
        // Now we need to claim the tokens from our mint using the stored quote ID.

        val session = _uiState.value.rideSession
        val quoteId = session.pendingDepositQuoteId
        val amount = session.pendingDepositAmount

        if (quoteId == null || amount == null) {
            Log.e(TAG, "Cannot claim tokens: missing quote ID or amount")
            Log.e(TAG, "  pendingDepositQuoteId: $quoteId")
            Log.e(TAG, "  pendingDepositAmount: $amount")
            // Still mark as complete so ride can proceed (best effort)
            updateRideSession { copy(
                activePreimage = bridgeComplete.preimage,
                canSettleEscrow = false,
                crossMintPaymentComplete = true
            ) }
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
                var claimResult: com.ridestr.common.payment.ClaimResult? = null

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
                    updateRideSession { copy(
                        activePreimage = bridgeComplete.preimage,
                        canSettleEscrow = false,
                        crossMintPaymentComplete = true,
                        pendingDepositQuoteId = null,
                        pendingDepositAmount = null
                    ) }

                    Log.d(TAG, "Cross-mint payment complete: received ${claimResult.totalSats} sats")
                } else {
                    Log.e(TAG, "Failed to claim tokens after all retries")
                    Log.e(TAG, "Last result: ${claimResult?.error ?: "null"}")
                    // Still mark as complete to let ride proceed
                    // User can claim manually via Wallet Settings > Claim by Quote ID
                    updateRideSession { copy(
                        activePreimage = bridgeComplete.preimage,
                        canSettleEscrow = false,
                        crossMintPaymentComplete = true,
                        pendingDepositQuoteId = null,
                        pendingDepositAmount = null
                    ) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception claiming tokens: ${e.message}", e)
                updateRideSession { copy(
                    activePreimage = bridgeComplete.preimage,
                    canSettleEscrow = false,
                    crossMintPaymentComplete = true,
                    pendingDepositQuoteId = null,
                    pendingDepositAmount = null
                ) }
            }
        }
    }

    /**
     * Start the ride (rider is in vehicle).
     */
    private fun startRide() {
        val state = _uiState.value
        val session = state.rideSession
        val context = getApplication<Application>()

        viewModelScope.launch {
            session.confirmationEventId?.let { confId ->
                session.acceptedOffer?.riderPubKey?.let { riderPubKey ->
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

            // Update RoadFlare broadcaster to indicate on-ride status
            updateRoadflareOnRideStatus(true)

            // Save ride state for persistence
            saveRideState()
        }
    }

    /**
     * Get current payment status for HTLC escrow.
     */
    fun getPaymentStatus(): PaymentStatus {
        val session = _uiState.value.rideSession
        return when {
            // Bridge payment already completed
            session.crossMintPaymentComplete -> PaymentStatus.NO_PAYMENT_EXPECTED
            // Non-HTLC payment paths — these don't use escrow
            session.paymentPath == PaymentPath.CROSS_MINT -> PaymentStatus.NO_PAYMENT_EXPECTED
            session.paymentPath == PaymentPath.FIAT_CASH -> PaymentStatus.NO_PAYMENT_EXPECTED
            session.paymentPath == PaymentPath.NO_PAYMENT -> PaymentStatus.NO_PAYMENT_EXPECTED
            // HTLC escrow checks (SAME_MINT only from here down)
            session.activePaymentHash == null -> PaymentStatus.NO_PAYMENT_EXPECTED
            session.canSettleEscrow -> PaymentStatus.READY_TO_CLAIM
            session.activePreimage == null && session.activeEscrowToken == null -> PaymentStatus.WAITING_FOR_PREIMAGE
            session.activePreimage == null -> PaymentStatus.MISSING_PREIMAGE
            session.activeEscrowToken == null -> PaymentStatus.MISSING_ESCROW_TOKEN
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
        validateTransition(RideEvent.Complete(myPubkey, state.rideSession.acceptedOffer?.fareEstimate?.toLong()))

        val paymentStatus = getPaymentStatus()
        Log.d(TAG, "completeRide: paymentStatus=$paymentStatus")

        // Check if we can claim payment
        if (paymentStatus != PaymentStatus.READY_TO_CLAIM &&
            paymentStatus != PaymentStatus.NO_PAYMENT_EXPECTED) {
            // Show warning dialog - driver won't be able to claim payment
            Log.w(TAG, "Payment not ready for claim - showing warning dialog")
            updateRideSession { copy(
                showPaymentWarningDialog = true,
                paymentWarningStatus = paymentStatus
            ) }
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
        updateRideSession { copy(
            showPaymentWarningDialog = false,
            paymentWarningStatus = null
        ) }
        completeRideInternal()
    }

    /**
     * User chose to cancel ride due to payment issue.
     */
    fun cancelRideDueToPaymentIssue() {
        Log.d(TAG, "User chose to cancel ride due to payment issue")
        updateRideSession { copy(
            showPaymentWarningDialog = false,
            paymentWarningStatus = null
        ) }
        cancelRide()
    }

    /**
     * Dismiss payment warning dialog without action (e.g., wait for payment to arrive).
     */
    fun dismissPaymentWarningDialog() {
        _uiState.update { current ->
            current.copy(
                sliderResetToken = current.sliderResetToken + 1,
                rideSession = current.rideSession.copy(
                    showPaymentWarningDialog = false,
                    paymentWarningStatus = null
                )
            )
        }
    }

    /**
     * Internal method to actually complete the ride.
     * Settles HTLC escrow if available.
     */
    private fun completeRideInternal() {
        val state = _uiState.value
        val session = state.rideSession
        val offer = session.acceptedOffer ?: return

        // Close all ride subscriptions and jobs since ride is ending
        closeAllRideSubscriptionsAndJobs()

        // Reset RoadFlare on-ride status (broadcasts "ONLINE" instead of "ON_RIDE")
        updateRoadflareOnRideStatus(false)

        viewModelScope.launch {
            // Correlation ID for payment tracking
            val rideCorrelationId = session.confirmationEventId?.take(8) ?: "unknown"

            // Attempt to settle HTLC escrow if we have preimage and token
            var settlementMessage = ""
            if (session.canSettleEscrow && session.activePreimage != null && session.activeEscrowToken != null) {
                Log.d(TAG, "[RIDE $rideCorrelationId] Claiming HTLC: paymentHash=${session.activePaymentHash?.take(16)}...")
                try {
                    val claimResult = walletService?.claimHtlcPayment(
                        htlcToken = session.activeEscrowToken,
                        preimage = session.activePreimage,
                        paymentHash = session.activePaymentHash
                    )

                    settlementMessage = when (claimResult) {
                        is HtlcClaimResult.Success -> {
                            Log.d(TAG, "[RIDE $rideCorrelationId] Claim SUCCESS: received ${claimResult.settlement.amountSats} sats")
                            " + ${claimResult.settlement.amountSats} sats received"
                        }
                        is HtlcClaimResult.Failure.PreimageMismatch -> {
                            Log.e(TAG, "[RIDE $rideCorrelationId] Claim FAILED: preimage mismatch for hash ${claimResult.paymentHash.take(16)}...")
                            " (preimage mismatch)"
                        }
                        is HtlcClaimResult.Failure.NotConnected -> {
                            Log.e(TAG, "[RIDE $rideCorrelationId] Claim FAILED: wallet not connected")
                            " (wallet not connected)"
                        }
                        is HtlcClaimResult.Failure -> {
                            Log.e(TAG, "[RIDE $rideCorrelationId] Claim FAILED: ${claimResult.message}")
                            " (payment claim failed)"
                        }
                        null -> {
                            Log.e(TAG, "[RIDE $rideCorrelationId] Claim FAILED: WalletService not available")
                            " (wallet unavailable)"
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[RIDE $rideCorrelationId] Claim ERROR: ${e.message}", e)
                    settlementMessage = " (payment error)"
                }
            } else if (session.activePaymentHash != null && session.paymentPath == PaymentPath.SAME_MINT) {
                // SAME_MINT: Had payment hash but no preimage/token - log for debugging
                Log.w(TAG, "[RIDE $rideCorrelationId] Cannot settle - preimage=${session.activePreimage != null}, token=${session.activeEscrowToken != null}")
                settlementMessage = " (no payment received)"
            }

            session.confirmationEventId?.let { confId ->
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

            _uiState.update { current ->
                current.copy(
                    stage = DriverStage.RIDE_COMPLETED,
                    statusMessage = "Ride completed! Fare: ${offer.fareEstimate.toInt()} sats$settlementMessage",
                    rideSession = current.rideSession.copy(
                        activePaymentHash = null,
                        activePreimage = null,
                        activeEscrowToken = null,
                        canSettleEscrow = false,
                        showPaymentWarningDialog = false,
                        paymentWarningStatus = null,
                        pendingDepositQuoteId = null,
                        pendingDepositAmount = null
                    )
                )
            }
        }
    }

    /**
     * Subscribe to rider's confirmation to get precise pickup location.
     * Auto-transitions to EN_ROUTE_TO_PICKUP and sends status update.
     */
    private fun subscribeToConfirmation(acceptanceEventId: String, riderPubKey: String) {
        // Start confirmation timeout - if no confirmation arrives, rider may have cancelled
        startConfirmationTimeout()

        subs.set(SubKeys.CONFIRMATION, nostrService.subscribeToConfirmation(acceptanceEventId, viewModelScope, riderPubKey) { confirmation ->
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

            // Extract payment data from confirmation (moved from offer for correct HTLC timing)
            val paymentHash = confirmation.paymentHash
            val escrowToken = confirmation.escrowToken
            if (paymentHash != null) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Confirmation includes HTLC payment hash: ${paymentHash.take(16)}...")
            }
            if (escrowToken != null) {
                Log.d(TAG, "Confirmation includes escrow token (${escrowToken.length} chars)")
            }

            // Store confirmation data including payment info
            updateRideSession { copy(
                confirmationEventId = confirmation.eventId,
                precisePickupLocation = confirmation.precisePickup,
                activePaymentHash = paymentHash,
                activeEscrowToken = escrowToken
            ) }

            // STATE_MACHINE: Update state to CONFIRMED after receiving confirmation
            // RideContext.withConfirmation() already supports paymentHash and escrowToken
            updateStateMachineState(
                RideState.CONFIRMED,
                rideContext?.withConfirmation(
                    confirmationEventId = confirmation.eventId,
                    precisePickup = confirmation.precisePickup,
                    paymentHash = paymentHash,
                    escrowToken = escrowToken
                )
            )

            // Subscribe to chat messages for this ride
            subscribeToChatMessages(confirmation.eventId)
            // Start periodic refresh to ensure messages are received
            startChatRefreshJob(confirmation.eventId)

            // Subscribe to cancellation events from rider
            subscribeToCancellation(confirmation.eventId)

            // Subscribe to rider ride state (handles verification and location reveals)
            _uiState.value.rideSession.acceptedOffer?.riderPubKey?.let { riderPubKey ->
                subscribeToRiderRideState(confirmation.eventId, riderPubKey)
            }

            // Auto-transition to EN_ROUTE_TO_PICKUP (skip the intermediate screen)
            autoStartRouteToPickup(confirmation.eventId)
        })
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
        stageBeforeRide = null

        val context = getApplication<Application>()
        DriverOnlineService.updateStatus(context, DriverStatus.Cancelled)

        // Capture before reset
        _uiState.value.rideSession.acceptedOffer?.riderPubKey?.let { unsubscribeFromRiderProfile(it) }

        closeAllRideSubscriptionsAndJobs()
        clearDriverStateHistory()
        updateRoadflareOnRideStatus(false)

        // Reset ALL ride state
        resetRideUiState(
            stage = DriverStage.AVAILABLE,
            statusMessage = "Ride cancelled - no response from rider",
            error = "Rider may have cancelled - no confirmation received"
        )

        // Clear persisted ride state — ride was persisted on accept,
        // so timeout must clear it to prevent stale restore on app restart
        clearSavedRideState()

        // Resume broadcasting
        val location = _uiState.value.currentLocation
        if (location != null) {
            publishedAvailabilityEventIds.clear()
            lastBroadcastLocation = null
            lastBroadcastTimeMs = 0L
            resumeOfferSubscriptions(location)
            Log.d(TAG, "Resumed broadcasting after confirmation timeout")
        }
    }

    /**
     * Automatically start route to pickup when confirmation is received.
     * This removes the need for driver to manually tap "Start Route to Pickup".
     */
    private fun autoStartRouteToPickup(confirmationEventId: String) {
        val state = _uiState.value
        val riderPubKey = state.rideSession.acceptedOffer?.riderPubKey ?: return
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
        // Create new subscription FIRST — set() stores new before closing old (create-before-close)
        val newId = nostrService.subscribeToChatMessages(confirmationEventId, viewModelScope) { chatData ->
            Log.d(TAG, "Received chat message from ${chatData.senderPubKey.take(8)}: ${chatData.message}")

            // Add to chat messages list
            val currentMessages = _uiState.value.rideSession.chatMessages.toMutableList()
            // Avoid duplicates
            if (currentMessages.none { it.eventId == chatData.eventId }) {
                currentMessages.add(chatData)
                // Sort by timestamp
                currentMessages.sortBy { it.createdAt }
                updateRideSession { copy(chatMessages = currentMessages) }
                // Persist messages for app restart
                saveRideState()

                // Notify service of chat message (plays sound, adds to alert stack)
                val myPubKey = nostrService.getPubKeyHex() ?: ""
                if (chatData.senderPubKey != myPubKey) {
                    val context = getApplication<Application>()
                    DriverOnlineService.addAlert(
                        context,
                        AlertType.Chat(chatData.message)
                    )
                    Log.d(TAG, "Chat message received - added to alert stack")
                }
            }
        }
        subs.set(SubKeys.CHAT, newId)
    }

    /**
     * Start periodic chat refresh to ensure messages are received.
     * Some relays may not push real-time events reliably.
     */
    private fun startChatRefreshJob(confirmationEventId: String) {
        stopChatRefreshJob()
        chatRefreshJob = PeriodicRefreshJob(
            scope = viewModelScope,
            intervalMs = CHAT_REFRESH_INTERVAL_MS,
            onTick = {
                Log.d(TAG, "Refreshing chat subscription for ${confirmationEventId.take(8)}")
                subscribeToChatMessages(confirmationEventId)
            }
        ).also { it.start() }
    }

    /**
     * Stop the periodic chat refresh job.
     */
    private fun stopChatRefreshJob() {
        chatRefreshJob?.stop()
        chatRefreshJob = null
    }

    /**
     * Subscribe to ride cancellation events from the rider.
     */
    private fun subscribeToCancellation(confirmationEventId: String) {
        Log.d(TAG, "Subscribing to cancellation events for confirmation: ${confirmationEventId.take(8)}")

        val newSubId = nostrService.subscribeToCancellation(confirmationEventId) { cancellation ->
            Log.d(TAG, "Received ride cancellation: event conf=${cancellation.confirmationEventId.take(8)}, reason=${cancellation.reason ?: "none"}")

            val currentState = _uiState.value
            val currentSession = currentState.rideSession
            val currentConfirmationId = currentSession.confirmationEventId

            // CRITICAL: Validate the EVENT's confirmation ID matches current ride
            // This is the definitive check - the event itself knows which ride it belongs to
            if (cancellation.confirmationEventId != currentConfirmationId) {
                Log.d(TAG, "Ignoring cancellation for different ride: event=${cancellation.confirmationEventId.take(8)} vs current=${currentConfirmationId?.take(8)}")
                return@subscribeToCancellation
            }

            // Only process if we're in an active ride and not already cancelling
            if (currentSession.isCancelling) {
                Log.d(TAG, "Ignoring cancellation - already cancelling")
                return@subscribeToCancellation
            }

            if (currentState.stage == DriverStage.OFFLINE || currentState.stage == DriverStage.ROADFLARE_ONLY || currentState.stage == DriverStage.AVAILABLE) {
                Log.d(TAG, "Ignoring cancellation - not in active ride (stage=${currentState.stage})")
                return@subscribeToCancellation
            }

            // Reset driver state to available
            handleRideCancellation(cancellation.reason)
        }
        subs.set(SubKeys.CANCELLATION, newSubId)

        if (newSubId != null) {
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
        val session = state.rideSession
        val context = getApplication<Application>()
        val offer = session.acceptedOffer

        // Notify service of cancellation (plays sound, updates notification)
        DriverOnlineService.updateStatus(context, DriverStatus.Cancelled)
        Log.d(TAG, "Ride cancelled - notified service")

        // If driver can claim payment (has preimage + escrow token), show dialog instead of cleaning up
        if (session.canSettleEscrow && offer != null) {
            Log.d(TAG, "Rider cancelled after PIN verification - driver can claim payment")
            _uiState.update { current ->
                current.copy(
                    error = reason ?: "Rider cancelled the ride",
                    rideSession = current.rideSession.copy(
                        showRiderCancelledClaimDialog = true,
                        riderCancelledFareAmount = offer.fareEstimate
                    )
                )
            }
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
        val session = state.rideSession
        val offer = session.acceptedOffer
        val rideCorrelationId = session.confirmationEventId?.take(8) ?: "unknown"

        viewModelScope.launch {
            // Attempt to settle HTLC escrow
            if (session.canSettleEscrow && session.activePreimage != null && session.activeEscrowToken != null) {
                Log.d(TAG, "[RIDE $rideCorrelationId] Claiming payment after rider cancellation...")
                try {
                    val claimResult = walletService?.claimHtlcPayment(
                        htlcToken = session.activeEscrowToken,
                        preimage = session.activePreimage
                    )

                    when (claimResult) {
                        is HtlcClaimResult.Success -> {
                            Log.d(TAG, "[RIDE $rideCorrelationId] Claim after cancellation SUCCESS: ${claimResult.settlement.amountSats} sats")
                            saveCancelledRideToHistory(state, offer, claimed = true)
                        }
                        is HtlcClaimResult.Failure -> {
                            Log.e(TAG, "[RIDE $rideCorrelationId] Claim after cancellation FAILED: ${claimResult.message}")
                            saveCancelledRideToHistory(state, offer, claimed = false)
                        }
                        null -> {
                            Log.e(TAG, "[RIDE $rideCorrelationId] Claim after cancellation FAILED: WalletService not available")
                            saveCancelledRideToHistory(state, offer, claimed = false)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[RIDE $rideCorrelationId] Claim after cancellation ERROR: ${e.message}", e)
                    saveCancelledRideToHistory(state, offer, claimed = false)
                }
            }

            // Now do cleanup
            updateRideSession { copy(showRiderCancelledClaimDialog = false) }
            performCancellationCleanup(state, offer, "Rider cancelled the ride")
        }
    }

    /**
     * Skip claiming payment after rider cancelled.
     */
    fun dismissRiderCancelledDialog() {
        val state = _uiState.value
        updateRideSession { copy(showRiderCancelledClaimDialog = false) }
        performCancellationCleanup(state, state.rideSession.acceptedOffer, "Rider cancelled the ride")
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
                rideId = state.rideSession.confirmationEventId ?: state.rideSession.acceptanceEventId ?: offer.eventId,
                timestamp = RideHistoryBuilder.currentTimestampSeconds(),
                role = "driver",
                counterpartyPubKey = offer.riderPubKey,
                pickupGeohash = offer.approxPickup.geohash(6),
                dropoffGeohash = offer.destination.geohash(6),
                distanceMiles = RideHistoryBuilder.toDistanceMiles(offer.rideRouteKm),
                durationMinutes = 0,
                fareSats = if (claimed) offer.fareEstimate.toLong() else 0,
                status = if (claimed) "cancelled_claimed" else "cancelled",
                vehicleMake = vehicle?.make,
                vehicleModel = vehicle?.model,
                appOrigin = RideHistoryRepository.APP_ORIGIN_DRIVESTR
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
        stageBeforeRide = null

        val context = getApplication<Application>()

        // Save cancelled ride to history (only if we had an accepted offer and not already saved)
        if (offer != null && !state.rideSession.showRiderCancelledClaimDialog) {
            viewModelScope.launch {
                saveCancelledRideToHistory(state, offer, claimed = false)
            }
        }

        closeAllRideSubscriptionsAndJobs()
        offer?.riderPubKey?.let { unsubscribeFromRiderProfile(it) }
        clearDriverStateHistory()
        clearSavedRideState()

        // Return to available state (if they were online) or offline
        val newStage = if (state.currentLocation != null) DriverStage.AVAILABLE else DriverStage.OFFLINE

        // Stop the foreground service and RoadFlare broadcasting if going offline
        if (newStage == DriverStage.OFFLINE) {
            DriverOnlineService.stop(context)
            broadcastRoadflareOfflineStatus()
            stopRoadflareBroadcasting()
        } else {
            // Returning to AVAILABLE - reset on-ride status
            updateRoadflareOnRideStatus(false)
            DriverOnlineService.updateStatus(context, DriverStatus.Available(0))
        }

        // Reset ALL ride state BEFORE resuming subscriptions (prevents stale offers being cleared)
        resetRideUiState(
            stage = newStage,
            statusMessage = if (newStage == DriverStage.AVAILABLE) "Rider cancelled - waiting for new requests" else "Rider cancelled ride",
            error = reason ?: "Rider cancelled the ride"
        )

        // Resume broadcasting and subscriptions if returning to AVAILABLE
        if (newStage == DriverStage.AVAILABLE && state.currentLocation != null) {
            publishedAvailabilityEventIds.clear()
            lastBroadcastLocation = null
            lastBroadcastTimeMs = 0L
            Log.d(TAG, "Reset broadcast state before resuming after cancellation")
            resumeOfferSubscriptions(state.currentLocation)
            Log.d(TAG, "Resumed broadcasting after rider cancellation")
        }
    }

    /**
     * Send a chat message to the rider.
     */
    fun sendChatMessage(message: String) {
        val session = _uiState.value.rideSession
        val confirmationEventId = session.confirmationEventId ?: return
        val riderPubKey = session.acceptedOffer?.riderPubKey ?: return

        if (message.isBlank()) return

        viewModelScope.launch {
            updateRideSession { copy(isSendingMessage = true) }

            val eventId = nostrService.sendChatMessage(
                confirmationEventId = confirmationEventId,
                recipientPubKey = riderPubKey,
                message = message
            )

            if (eventId != null) {
                Log.d(TAG, "Sent chat message: $eventId")
                trackEventForCleanup(eventId, "CHAT")
                val myPubKey = nostrService.getPubKeyHex() ?: ""

                val localMessage = RideshareChatData(
                    eventId = eventId,
                    senderPubKey = myPubKey,
                    confirmationEventId = confirmationEventId,
                    recipientPubKey = riderPubKey,
                    message = message,
                    createdAt = System.currentTimeMillis() / 1000
                )

                val currentMessages = _uiState.value.rideSession.chatMessages.toMutableList()
                currentMessages.add(localMessage)
                currentMessages.sortBy { it.createdAt }

                updateRideSession { copy(
                    isSendingMessage = false,
                    chatMessages = currentMessages
                ) }

                saveRideState()
            } else {
                _uiState.update { current ->
                    current.copy(
                        error = "Failed to send message",
                        rideSession = current.rideSession.copy(isSendingMessage = false)
                    )
                }
            }
        }
    }

    fun declineOffer(offer: RideOfferData) {
        updateRideSession { copy(
            pendingOffers = pendingOffers.filter { it.eventId != offer.eventId }
        ) }
    }

    fun clearAcceptedOffer() {
        if (_uiState.value.stage != DriverStage.RIDE_COMPLETED) return

        // Capture state for ride history BEFORE clearing
        val state = _uiState.value
        val offer = state.rideSession.acceptedOffer
        val riderPubKey = offer?.riderPubKey

        // Synchronous cleanup
        closeAllRideSubscriptionsAndJobs()
        clearDriverStateHistory()
        stageBeforeRide = null
        broadcastRoadflareOfflineStatus()
        stopRoadflareBroadcasting()
        DriverOnlineService.stop(getApplication())

        // Reset ALL ride state
        resetRideUiState(
            stage = DriverStage.OFFLINE,
            statusMessage = "Tap to go online"
        )

        clearSavedRideState()

        // Save completed ride to history using PRE-RESET captured state
        if (offer != null) {
            viewModelScope.launch {
                try {
                    val vehicle = state.activeVehicle
                    val riderProfile = state.riderProfiles[offer.riderPubKey]
                    val historyEntry = RideHistoryEntry(
                        rideId = state.rideSession.confirmationEventId ?: state.rideSession.acceptanceEventId ?: offer.eventId,
                        timestamp = RideHistoryBuilder.currentTimestampSeconds(),
                        role = "driver",
                        counterpartyPubKey = offer.riderPubKey,
                        pickupGeohash = offer.approxPickup.geohash(6),
                        dropoffGeohash = offer.destination.geohash(6),
                        distanceMiles = RideHistoryBuilder.toDistanceMiles(offer.rideRouteKm),
                        durationMinutes = offer.rideRouteMin?.toInt() ?: 0,
                        fareSats = offer.fareEstimate.toLong(),
                        status = "completed",
                        vehicleMake = vehicle?.make,
                        vehicleModel = vehicle?.model,
                        counterpartyFirstName = RideHistoryBuilder.extractCounterpartyFirstName(riderProfile),
                        appOrigin = RideHistoryRepository.APP_ORIGIN_DRIVESTR
                    )
                    rideHistoryRepository.addRide(historyEntry)
                    Log.d(TAG, "Saved completed ride to history (go offline): ${historyEntry.rideId}")
                    rideHistoryRepository.backupToNostr(nostrService)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save ride to history", e)
                }
            }
        }

        riderPubKey?.let { unsubscribeFromRiderProfile(it) }
        cleanupRideEventsInBackground("ride completed")
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
            "driver went offline",
            listOf(RideshareEventKinds.DRIVER_AVAILABILITY)
        )

        if (deletionEventId != null) {
            Log.d(TAG, "Deletion request sent: $deletionEventId")
            publishedAvailabilityEventIds.clear()
        } else {
            Log.w(TAG, "Failed to send deletion request")
        }
    }

    private fun subscribeToOffers() {
        subs.set(SubKeys.OFFERS, nostrService.subscribeToOffers(viewModelScope) { offer ->
            processIncomingOffer(offer, roadflareOnly = false)
        })
    }

    /**
     * Subscribe to RoadFlare-tagged offers only (for ROADFLARE_ONLY stage).
     * Uses the same offer processing logic as subscribeToOffers but filters
     * to only RoadFlare-tagged events on the client side.
     */
    private fun subscribeToRoadflareOffers() {
        subs.set(SubKeys.ROADFLARE_OFFERS, nostrService.subscribeToOffers(viewModelScope) { offer ->
            processIncomingOffer(offer, roadflareOnly = true)
        })
    }

    /**
     * Shared offer processing logic for both full and RoadFlare-only subscriptions.
     * Handles deduplication, staleness filtering, fare boosts, notification, and route calculation.
     */
    private fun processIncomingOffer(offer: RideOfferData, roadflareOnly: Boolean) {
        // Observability: should never fire after accept-time subscription closure
        if (BuildConfig.DEBUG) {
            val stage = _uiState.value.stage
            if (stage != DriverStage.AVAILABLE && stage != DriverStage.ROADFLARE_ONLY) {
                Log.w(TAG, "processIncomingOffer called during $stage — offer sub should be closed")
            }
        }

        // In ROADFLARE_ONLY mode, only process RoadFlare-tagged offers
        if (roadflareOnly && !offer.isRoadflare) {
            Log.d(TAG, "Ignoring non-RoadFlare offer in ROADFLARE_ONLY mode")
            return
        }

        val offerType = if (offer.isRoadflare) "RoadFlare" else "Direct"
        Log.d(TAG, "Received $offerType offer from ${offer.riderPubKey.take(8)}...")

        // Filter out offers we've already accepted (prevents duplicates after ride completion)
        if (offer.eventId in acceptedOfferEventIds) {
            Log.d(TAG, "Ignoring already-accepted offer: ${offer.eventId.take(8)}")
            return
        }

        // Filter out offers older than 2 minutes (stale offers)
        val currentTimeSeconds = System.currentTimeMillis() / 1000
        val offerAgeSeconds = currentTimeSeconds - offer.createdAt
        val maxOfferAgeSeconds = 2 * 60

        if (offerAgeSeconds > maxOfferAgeSeconds) {
            Log.d(TAG, "Ignoring stale offer (${offerAgeSeconds}s old)")
            return
        }

        val currentOffers = _uiState.value.rideSession.pendingOffers
        val isNewOffer = currentOffers.none { it.eventId == offer.eventId }
        // Check if this is a fare boost (same rider, different event)
        val isFareBoost = currentOffers.any { it.riderPubKey == offer.riderPubKey }

        if (isNewOffer) {
            // Filter out stale offers AND any existing offer from same rider (fare boost case)
            val freshOffers = currentOffers.filter { existing ->
                (currentTimeSeconds - existing.createdAt) <= maxOfferAgeSeconds &&
                existing.riderPubKey != offer.riderPubKey  // Remove old offer from same rider
            }
            // Sort: RoadFlare first, then by createdAt descending (newest first)
            val sortedOffers = (freshOffers + offer).sortedWith(
                compareByDescending<RideOfferData> { it.isRoadflare }
                    .thenByDescending { it.createdAt }
            )
            updateRideSession { copy(pendingOffers = sortedOffers) }

            // Update deletion subscription to watch for this offer being cancelled
            updateDeletionSubscription()

            // Notify service of new offer (plays sound, updates notification temporarily)
            // Only for NEW offers, not fare boosts
            if (!isFareBoost) {
                val context = getApplication<Application>()
                val fareDisplay = "${offer.fareEstimate.toInt()} sats"
                val fallbackLabel = if (offer.isRoadflare) "RoadFlare request" else "Direct offer"
                val distanceDisplay = offer.rideRouteKm?.let { "${String.format("%.1f", it)} km ride" } ?: fallbackLabel
                DriverOnlineService.updateStatus(
                    context,
                    DriverStatus.NewRequest(
                        count = sortedOffers.size,
                        fare = fareDisplay,
                        distance = distanceDisplay
                    )
                )
                Log.d(TAG, "$offerType offer received - notified service")
            } else {
                Log.d(TAG, "Fare boost received from same rider - updating offer quietly")
            }

            // Calculate routes for the new offer
            calculateDirectOfferRoutes(offer)
        }
    }

    private fun closeRoadflareOfferSubscription() {
        subs.close(SubKeys.ROADFLARE_OFFERS)
    }

    /**
     * Broadcast a final OFFLINE status to RoadFlare followers so they see
     * the driver go offline immediately rather than waiting for staleness timeout.
     */
    private fun broadcastRoadflareOfflineStatus() {
        viewModelScope.launch {
            val location = _uiState.value.currentLocation ?: return@launch
            val roadflareState = driverRoadflareRepository?.state?.value ?: return@launch
            val roadflareKey = roadflareState.roadflareKey ?: return@launch
            val signer = nostrService.getSigner() ?: return@launch

            // Create location with OFFLINE status
            val offlineLocation = RoadflareLocation(
                lat = location.lat,
                lon = location.lon,
                timestamp = System.currentTimeMillis() / 1000,
                status = RoadflareLocationEvent.Status.OFFLINE
            )

            // Publish directly to Nostr with OFFLINE status
            nostrService.publishRoadflareLocation(
                signer = signer,
                roadflarePubKey = roadflareKey.publicKey,
                location = offlineLocation,
                keyVersion = roadflareKey.version
            )

            Log.d(TAG, "Published OFFLINE RoadFlare status")
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
        val expandSearch = _uiState.value.expandedSearch
        Log.d(TAG, "Subscribing to broadcast requests - expanded: $expandSearch")

        // Start cleanup timer to prune stale requests every 30 seconds
        startStaleRequestCleanup()

        subs.set(SubKeys.BROADCAST_REQUESTS, nostrService.subscribeToBroadcastRideRequests(
            location = location,
            expandSearch = expandSearch
        ) { request ->
            // Observability: should never fire after accept-time subscription closure
            if (BuildConfig.DEBUG) {
                val stage = _uiState.value.stage
                if (stage != DriverStage.AVAILABLE && stage != DriverStage.ROADFLARE_ONLY) {
                    Log.w(TAG, "subscribeToBroadcastRequests callback during $stage — sub should be closed")
                }
            }

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
            val currentRequests = _uiState.value.rideSession.pendingBroadcastRequests
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
                // Sort: RoadFlare first, then by fare (highest first), then by time (newest first)
                val sortedRequests = (freshRequests + request)
                    .sortedWith(compareByDescending<BroadcastRideOfferData> { it.isRoadflare }
                        .thenByDescending { it.fareEstimate }
                        .thenByDescending { it.createdAt })

                updateRideSession { copy(pendingBroadcastRequests = sortedRequests) }

                // Calculate route to pickup location
                calculatePickupRoute(request.eventId, request.pickupArea)

                // Subscribe to acceptances for this request to detect if another driver takes it
                subscribeToAcceptancesForRequest(request.eventId)

                // Update deletion subscription to include this new request
                updateDeletionSubscription()
            }
        })
    }

    /**
     * Subscribe to acceptances for a specific ride request.
     * When another driver accepts, we remove the request from our list.
     */
    private fun subscribeToAcceptancesForRequest(requestEventId: String) {
        // Don't double-subscribe
        if (subs.groupContains(SubKeys.REQUEST_ACCEPTANCES, requestEventId)) return

        val myPubKey = nostrService.getPubKeyHex() ?: return

        val subId = nostrService.subscribeToAcceptancesForOffer(requestEventId) { acceptance ->
            // If the acceptance is from a different driver, the ride is taken
            if (acceptance.driverPubKey != myPubKey) {
                Log.d(TAG, "Request ${requestEventId.take(8)} was taken by driver ${acceptance.driverPubKey.take(8)}")
                markRequestAsTaken(requestEventId)
            }
        }
        subs.setInGroup(SubKeys.REQUEST_ACCEPTANCES, requestEventId, subId)
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
        val currentRequests = _uiState.value.rideSession.pendingBroadcastRequests
        val updatedRequests = currentRequests.filter { it.eventId != requestEventId }

        // Remove from eventId -> cacheKey mapping (but keep location cache for other requests with same location)
        eventToCacheKey.remove(requestEventId)
        val updatedRoutes = _uiState.value.pickupRoutes - requestEventId

        _uiState.update { current ->
            current.copy(
                pickupRoutes = updatedRoutes,
                rideSession = current.rideSession.copy(pendingBroadcastRequests = updatedRequests)
            )
        }

        // Close the acceptance subscription for this request (no longer needed)
        subs.closeInGroup(SubKeys.REQUEST_ACCEPTANCES, requestEventId)

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
        val currentOffers = _uiState.value.rideSession.pendingOffers
        val updatedOffers = currentOffers.filter { it.eventId != requestEventId }
        if (updatedOffers.size != currentOffers.size) {
            Log.d(TAG, "Removed cancelled direct offer: ${requestEventId.take(8)}")
            updateRideSession { copy(pendingOffers = updatedOffers) }
        }
    }

    /**
     * Update the deletion subscription to watch all current pending requests.
     * Called when requests are added or removed.
     * Watches BOTH broadcast requests AND direct offers for cancellations.
     */
    private fun updateDeletionSubscription() {
        // Combine event IDs from BOTH broadcast requests AND direct offers
        val session = _uiState.value.rideSession
        val broadcastEventIds = session.pendingBroadcastRequests.map { it.eventId }
        val directOfferEventIds = session.pendingOffers.map { it.eventId }
        val allPendingEventIds = (broadcastEventIds + directOfferEventIds).distinct()

        if (allPendingEventIds.isEmpty()) {
            subs.close(SubKeys.DELETION)
            return
        }

        Log.d(TAG, "Watching ${allPendingEventIds.size} requests for deletions (${broadcastEventIds.size} broadcast, ${directOfferEventIds.size} direct)")
        subs.set(SubKeys.DELETION, nostrService.subscribeToRideRequestDeletions(allPendingEventIds) { deletedEventId ->
            handleCancelledRideRequest(deletedEventId)
        })
    }

    /**
     * Accept a broadcast ride request.
     * This is the new primary flow.
     */
    fun acceptBroadcastRequest(request: BroadcastRideOfferData) {
        viewModelScope.launch {
            clearDriverStateHistory()

            // STATE_MACHINE: Initialize context for new ride (was missing from broadcast path)
            val myPubkey = nostrService.getPubKeyHex() ?: ""
            val newContext = RideContext.forOffer(
                riderPubkey = request.riderPubKey,
                approxPickup = request.pickupArea,
                destination = request.destinationArea,
                fareEstimateSats = request.fareEstimate.toLong(),
                offerEventId = request.eventId,
                paymentHash = null,
                riderMintUrl = request.mintUrl,
                paymentMethod = request.paymentMethod
            )
            rideContext = newContext
            rideState = RideState.CREATED

            stageBeforeRide = _uiState.value.stage

            validateTransition(RideEvent.Accept(
                inputterPubkey = myPubkey,
                driverPubkey = myPubkey,
                walletPubkey = walletService?.getWalletPubKey(),
                mintUrl = walletService?.getSavedMintUrl()
            ))

            updateRideSession { copy(isProcessingOffer = true) }

            val walletPubKey = walletService?.getWalletPubKey()
            val driverMintUrl = walletService?.getSavedMintUrl()

            val eventId = nostrService.acceptBroadcastRide(
                request = request,
                walletPubKey = walletPubKey,
                mintUrl = driverMintUrl,
                paymentMethod = request.paymentMethod
            )

            if (eventId != null) {
                // Convert to RideOfferData for compatibility with downstream ride flow
                val compatibleOffer = RideOfferData(
                    eventId = request.eventId,
                    riderPubKey = request.riderPubKey,
                    driverEventId = "",
                    driverPubKey = myPubkey,
                    approxPickup = request.pickupArea,
                    destination = request.destinationArea,
                    fareEstimate = request.fareEstimate,
                    createdAt = request.createdAt,
                    mintUrl = request.mintUrl,
                    paymentMethod = request.paymentMethod
                )

                setupAcceptedRide(
                    acceptanceEventId = eventId,
                    offer = compatibleOffer,
                    broadcastRequest = request,
                    walletPubKey = walletPubKey,
                    driverMintUrl = driverMintUrl,
                    cleanupTag = "BROADCAST_ACCEPTANCE"
                )
            } else {
                _uiState.update { current ->
                    current.copy(
                        error = "Failed to accept ride request",
                        rideSession = current.rideSession.copy(isProcessingOffer = false)
                    )
                }
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
        val currentRequests = _uiState.value.rideSession.pendingBroadcastRequests
        val freshRequests = currentRequests.filter { request ->
            val ageSeconds = currentTimeSeconds - request.createdAt
            val isFresh = ageSeconds <= maxRequestAgeSeconds
            if (!isFresh) {
                Log.d(TAG, "Pruning stale broadcast request ${request.eventId.take(8)} (${ageSeconds}s old)")
            }
            isFresh
        }

        // Prune stale direct offers
        val currentOffers = _uiState.value.rideSession.pendingOffers
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
            updateRideSession { copy(
                pendingBroadcastRequests = freshRequests,
                pendingOffers = freshOffers
            ) }
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
        val currentRequests = _uiState.value.rideSession.pendingBroadcastRequests
        updateRideSession { copy(pendingBroadcastRequests = currentRequests.filter { it.eventId != request.eventId }) }
        // Add to declined set to prevent it from showing again on refresh
        declinedOfferEventIds.add(request.eventId)
        Log.d(TAG, "Declined request ${request.eventId.take(8)}, total declined: ${declinedOfferEventIds.size}")
    }

    // ========================================================================
    // RoadFlare Location Broadcasting
    // ========================================================================

    /**
     * Ensure RoadFlare state is synced from Nostr using union merge.
     * Sync RoadFlare state from Nostr (for debug menu).
     * @return true if state changed, false otherwise
     */
    suspend fun syncRoadflareState(): Boolean {
        return ensureRoadflareStateSynced()
    }

    /**
     * Internal: Sync RoadFlare state from Nostr.
     * Called before starting RoadFlare broadcasting to handle cross-device sync.
     *
     * Uses union merge strategy:
     * 1. Takes newer key based on keyUpdatedAt/keyVersion
     * 2. Merges follower lists (union by pubkey, prefer approved + higher keyVersionSent)
     * 3. Merges muted lists (union, never auto-unmute)
     * 4. Retries fetch once on null before pushing
     * 5. Signals background refresh after merge to catch unfollowed riders
     *
     * @return true if state was merged/updated, false otherwise
     */
    private suspend fun ensureRoadflareStateSynced(): Boolean {
        val currentState = driverRoadflareRepository.state.value
        val localKeyUpdatedAt = currentState?.keyUpdatedAt ?: 0L
        val localKeyVersion = currentState?.roadflareKey?.version ?: 0
        val localUpdatedAt = currentState?.updatedAt ?: 0L

        Log.d(TAG, "Checking RoadFlare state: local key v$localKeyVersion, " +
                  "keyUpdatedAt=$localKeyUpdatedAt, updatedAt=$localUpdatedAt")

        // Fetch remote state with one retry on failure
        var remoteState = nostrService.fetchDriverRoadflareState()
        if (remoteState == null) {
            Log.d(TAG, "First fetch returned null, retrying...")
            delay(1000)
            remoteState = nostrService.fetchDriverRoadflareState()
        }

        if (remoteState != null) {
            val remoteKeyUpdatedAt = remoteState.keyUpdatedAt ?: 0L
            val remoteKeyVersion = remoteState.roadflareKey?.version ?: 0
            val remoteUpdatedAt = remoteState.updatedAt

            Log.d(TAG, "Remote RoadFlare state: key v$remoteKeyVersion, " +
                      "keyUpdatedAt=$remoteKeyUpdatedAt, updatedAt=$remoteUpdatedAt")

            // Determine which key to use (newer keyUpdatedAt wins, then version as tiebreaker)
            val useRemoteKey = when {
                currentState?.roadflareKey == null && remoteState.roadflareKey != null -> true
                remoteKeyUpdatedAt > localKeyUpdatedAt -> true
                remoteKeyUpdatedAt == localKeyUpdatedAt && remoteKeyVersion > localKeyVersion -> true
                else -> false
            }

            // Determine selected key version for merge validation
            val selectedKeyVersion = if (useRemoteKey) remoteKeyVersion else localKeyVersion

            // Merge follower lists (union)
            val mergedFollowers = mergeFollowerLists(
                currentState?.followers ?: emptyList(),
                remoteState.followers,
                selectedKeyVersion,
                localUpdatedAt,
                remoteUpdatedAt
            )

            // Verify followers against Kind 30011 to filter out unfollowed riders immediately
            val driverPubKey = nostrService.getPubKeyHex()
            var verifiedFollowerPubkeys: Set<String>? = null  // Capture for later emission
            val verifiedFollowers = if (driverPubKey != null && mergedFollowers.isNotEmpty()) {
                val queryResult = nostrService.queryCurrentFollowerPubkeys(driverPubKey)
                if (!queryResult.success) {
                    // Query failed/timed out - fallback to merged list (null = full refresh later)
                    if (BuildConfig.DEBUG) {
                        Log.w(TAG, "Kind 30011 query timed out, using merged followers as fallback")
                    }
                    mergedFollowers
                } else {
                    // Query succeeded - capture pubkeys to avoid re-querying in refresh
                    verifiedFollowerPubkeys = queryResult.followers
                    val filteredFollowers = mergedFollowers.filter { it.pubkey in queryResult.followers }
                    val removedCount = mergedFollowers.size - filteredFollowers.size
                    if (removedCount > 0 && BuildConfig.DEBUG) {
                        Log.d(TAG, "Filtered $removedCount stale followers via Kind 30011 verification")
                    }
                    filteredFollowers
                }
            } else {
                mergedFollowers
            }

            // Merge muted lists (union - never auto-unmute)
            val mergedMuted = mergeMutedLists(
                currentState?.muted ?: emptyList(),
                remoteState.muted
            )

            // Build merged state
            val mergedState = DriverRoadflareState(
                eventId = if (useRemoteKey) remoteState.eventId else currentState?.eventId,
                roadflareKey = if (useRemoteKey) remoteState.roadflareKey else currentState?.roadflareKey,
                followers = verifiedFollowers,
                muted = mergedMuted,
                keyUpdatedAt = if (useRemoteKey) remoteKeyUpdatedAt else localKeyUpdatedAt,
                lastBroadcastAt = maxOf(currentState?.lastBroadcastAt ?: 0L, remoteState.lastBroadcastAt ?: 0L),
                updatedAt = maxOf(localUpdatedAt, remoteUpdatedAt),
                createdAt = minOf(currentState?.createdAt ?: Long.MAX_VALUE, remoteState.createdAt)
            )

            // Check if meaningful state changed (ignore eventId/createdAt/updatedAt metadata)
            val stateChanged = mergedState.roadflareKey != currentState?.roadflareKey ||
                mergedState.followers != currentState?.followers ||
                mergedState.muted != currentState?.muted ||
                mergedState.keyUpdatedAt != currentState?.keyUpdatedAt

            if (stateChanged) {
                driverRoadflareRepository.restoreFromBackup(mergedState)
                Log.d(TAG, "Merged RoadFlare state: key v${mergedState.roadflareKey?.version}, " +
                          "followers=${mergedState.followers.size}, muted=${mergedState.muted.size}")

                // Push merged state to Nostr
                nostrService.getSigner()?.let { signer ->
                    nostrService.publishDriverRoadflareState(signer, mergedState)
                    Log.d(TAG, "Pushed merged state to Nostr")
                }

                // Signal background refresh to fetch display names and detect new followers
                // Pass verified follower pubkeys to avoid re-querying Kind 30011
                _syncTriggeredRefresh.trySend(verifiedFollowerPubkeys).also { result ->
                    if (result.isFailure && BuildConfig.DEBUG) {
                        Log.w(TAG, "Failed to send sync refresh signal: ${result.exceptionOrNull()}")
                    }
                }

                return true
            }
        } else {
            Log.d(TAG, "No RoadFlare state found on Nostr after retry")

            // Push local state if we have one
            if (currentState?.roadflareKey != null) {
                Log.d(TAG, "Pushing local state to Nostr...")
                nostrService.getSigner()?.let { signer ->
                    nostrService.publishDriverRoadflareState(signer, currentState)
                }
            }
        }

        return false
    }

    /**
     * Merge two follower lists (union by pubkey).
     * For duplicates, prefer the one with approved=true or higher keyVersionSent.
     * Clamps keyVersionSent to selected key version to prevent claiming sent keys that don't exist.
     */
    private fun mergeFollowerLists(
        local: List<RoadflareFollower>,
        remote: List<RoadflareFollower>,
        selectedKeyVersion: Int,
        localUpdatedAt: Long,
        remoteUpdatedAt: Long
    ): List<RoadflareFollower> {
        val byPubkey = mutableMapOf<String, RoadflareFollower>()

        // Add all local followers
        for (follower in local) {
            byPubkey[follower.pubkey] = follower
        }

        // Merge remote followers
        for (follower in remote) {
            val existing = byPubkey[follower.pubkey]
            if (existing == null) {
                byPubkey[follower.pubkey] = follower
            } else {
                // Merge: prefer approved, higher keyVersionSent (clamped), earlier addedAt
                val mergedKeyVersionSent = maxOf(existing.keyVersionSent, follower.keyVersionSent)

                // Clamp keyVersionSent to selected key version to prevent claiming sent keys that don't exist
                val clampedKeyVersionSent = minOf(mergedKeyVersionSent, selectedKeyVersion)
                if (mergedKeyVersionSent > selectedKeyVersion) {
                    Log.w(TAG, "Clamped keyVersionSent from $mergedKeyVersionSent to $selectedKeyVersion for ${follower.pubkey.take(8)}")
                }

                byPubkey[follower.pubkey] = existing.copy(
                    approved = existing.approved || follower.approved,
                    keyVersionSent = clampedKeyVersionSent,
                    addedAt = minOf(existing.addedAt, follower.addedAt)
                )
            }
        }

        // If remote is newer than local, prune local-only followers (they were removed on Nostr)
        // If local is newer, keep local-only followers (they're legitimate, remote is stale)
        if (remoteUpdatedAt > localUpdatedAt) {
            val remotePubkeys = remote.map { it.pubkey }.toSet()
            val localOnlyPubkeys = local.map { it.pubkey }.filter { it !in remotePubkeys }

            if (localOnlyPubkeys.isNotEmpty()) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Remote newer ($remoteUpdatedAt > $localUpdatedAt), pruning ${localOnlyPubkeys.size} stale local-only followers")
                }
                for (pubkey in localOnlyPubkeys) {
                    byPubkey.remove(pubkey)
                }
            }
        }

        return byPubkey.values.toList()
    }

    /**
     * Merge two muted lists (union by pubkey).
     * Once muted, stays muted (never auto-unmute from sync).
     */
    private fun mergeMutedLists(
        local: List<MutedRider>,
        remote: List<MutedRider>
    ): List<MutedRider> {
        val byPubkey = mutableMapOf<String, MutedRider>()

        for (muted in local) {
            byPubkey[muted.pubkey] = muted
        }

        for (muted in remote) {
            if (!byPubkey.containsKey(muted.pubkey)) {
                byPubkey[muted.pubkey] = muted
            }
            // If already muted locally, keep local entry (earlier mutedAt)
        }

        return byPubkey.values.toList()
    }

    /**
     * Start RoadFlare location broadcasting to followers.
     * Creates the broadcaster if needed and starts periodic location updates.
     */
    private fun startRoadflareBroadcasting() {
        val signer = nostrService.getSigner()
        if (signer == null) {
            Log.w(TAG, "Cannot start RoadFlare broadcasting: no signer available")
            return
        }

        // Create broadcaster if not exists
        if (roadflareLocationBroadcaster == null) {
            roadflareLocationBroadcaster = RoadflareLocationBroadcaster(
                repository = driverRoadflareRepository,
                nostrService = nostrService,
                signer = signer
            )
        }

        // Start broadcasting with a location provider that reads current location from UI state
        roadflareLocationBroadcaster?.startBroadcasting {
            val location = _uiState.value.currentLocation
            if (location != null) {
                // Convert our Location to Android Location for the broadcaster
                android.location.Location("").apply {
                    latitude = location.lat
                    longitude = location.lon
                }
            } else {
                null
            }
        }

        Log.d(TAG, "RoadFlare location broadcasting started")
    }

    /**
     * Stop RoadFlare location broadcasting.
     */
    private fun stopRoadflareBroadcasting() {
        roadflareLocationBroadcaster?.stopBroadcasting()
        Log.d(TAG, "RoadFlare location broadcasting stopped")
    }

    /**
     * Update RoadFlare broadcaster's on-ride status.
     * Call when entering or exiting a ride.
     */
    private fun updateRoadflareOnRideStatus(isOnRide: Boolean) {
        roadflareLocationBroadcaster?.setOnRide(isOnRide)
        Log.d(TAG, "RoadFlare on-ride status: $isOnRide")
    }

    fun performLogoutCleanup() {
        stopBroadcasting()
        stopStaleRequestCleanup()
        chatRefreshJob?.stop()
        confirmationTimeoutJob?.cancel()
        pinVerificationTimeoutJob?.cancel()
        subs.closeAll()
        nostrService.disconnect()
        bitcoinPriceService.cleanup()
        roadflareLocationBroadcaster?.destroy()
    }

    override fun onCleared() {
        super.onCleared()
        performLogoutCleanup()
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
 * Ride-scoped state fields that are reset when a ride ends.
 * Any field added here is automatically included in reset via `resetRideUiState()`.
 *
 * Fields classified by `resetRideUiState()` — every field it resets lives here.
 */
data class DriverRideSession(
    // Active ride info
    val acceptedOffer: RideOfferData? = null,
    val acceptedBroadcastRequest: BroadcastRideOfferData? = null,
    val acceptanceEventId: String? = null,
    val confirmationEventId: String? = null,
    val precisePickupLocation: Location? = null,
    val preciseDestinationLocation: Location? = null,
    val isProcessingOffer: Boolean = false,

    // PIN verification
    val pinAttempts: Int = 0,
    val isAwaitingPinVerification: Boolean = false,
    val pinVerificationTimedOut: Boolean = false,
    val lastPinSubmissionEventId: String? = null,

    // Confirmation wait
    val confirmationWaitStartMs: Long? = null,

    // Chat
    val chatMessages: List<RideshareChatData> = emptyList(),
    val isSendingMessage: Boolean = false,

    // Guards
    val isCancelling: Boolean = false,

    // HTLC Escrow
    val activePaymentHash: String? = null,
    val activePreimage: String? = null,
    val activeEscrowToken: String? = null,
    val canSettleEscrow: Boolean = false,

    // Multi-mint payment
    val paymentPath: PaymentPath = PaymentPath.NO_PAYMENT,
    val riderMintUrl: String? = null,
    val crossMintPaymentComplete: Boolean = false,
    val pendingDepositQuoteId: String? = null,
    val pendingDepositAmount: Long? = null,

    // Payment warning dialog
    val showPaymentWarningDialog: Boolean = false,
    val paymentWarningStatus: PaymentStatus? = null,

    // Rider cancelled claim dialog
    val showRiderCancelledClaimDialog: Boolean = false,
    val riderCancelledFareAmount: Double? = null,

    // Availability-lifecycle fields — included in session for reset completeness
    val pendingOffers: List<RideOfferData> = emptyList(),
    val pendingBroadcastRequests: List<BroadcastRideOfferData> = emptyList()
)

/**
 * UI state for driver mode.
 */
data class DriverUiState(
    // Driver state
    val stage: DriverStage = DriverStage.OFFLINE,
    val currentLocation: Location? = null,
    val lastBroadcastTime: Long? = null,
    val activeVehicle: Vehicle? = null,
    val expandedSearch: Boolean = true,

    // Ride session (reset as a unit when ride ends)
    val rideSession: DriverRideSession = DriverRideSession(),

    // Cached pickup routes for broadcast requests (keyed by eventId)
    val pickupRoutes: Map<String, RouteResult> = emptyMap(),
    val directOfferPickupRoutes: Map<String, RouteResult> = emptyMap(),
    val directOfferRideRoutes: Map<String, RouteResult> = emptyMap(),

    // Confirmation wait duration (constant)
    val confirmationWaitDurationMs: Long = 30_000L,

    // User identity
    val myPubKey: String = "",
    val riderProfiles: Map<String, UserProfile> = emptyMap(),

    // UI
    val statusMessage: String = "Tap to go online",
    val error: String? = null,

    // Slider reset (monotonic counter, never reset)
    val sliderResetToken: Int = 0,

    // Wallet not set up warning
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
