package com.ridestr.rider.viewmodels

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ridestr.common.data.RideHistoryRepository
import com.ridestr.common.data.SavedLocation
import com.ridestr.common.data.SavedLocationRepository
import com.ridestr.common.nostr.events.RideHistoryEntry
import com.ridestr.common.location.GeocodingResult
import com.ridestr.common.location.GeocodingService
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.DriverAvailabilityData
import com.ridestr.common.nostr.events.DriverRideAction
import com.ridestr.common.nostr.events.DriverRideStateData
import com.ridestr.common.nostr.events.DriverStatusType
import com.ridestr.common.nostr.events.Geohash
import com.ridestr.common.nostr.events.PaymentPath
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.events.RideAcceptanceData
import com.ridestr.common.nostr.events.RiderRideAction
import com.ridestr.common.nostr.events.RiderRideStateEvent
import com.ridestr.common.nostr.events.RideshareChatData
import com.ridestr.common.nostr.events.UserProfile
import com.ridestr.common.nostr.events.geohash
import com.ridestr.common.notification.AlertType
import com.ridestr.rider.service.RiderActiveService
import com.ridestr.rider.service.RiderStatus
import kotlin.random.Random
import com.ridestr.common.bitcoin.BitcoinPriceService
import com.ridestr.common.routing.RouteResult
import com.ridestr.common.routing.TileManager
import com.ridestr.common.routing.TileSource
import com.ridestr.common.settings.DisplayCurrency
import com.ridestr.common.settings.RemoteConfigManager
import com.ridestr.common.settings.SettingsManager
import com.ridestr.common.routing.ValhallaRoutingService
import com.ridestr.common.payment.BridgePaymentStatus
import com.ridestr.common.payment.LockResult
import com.ridestr.common.payment.MeltQuoteState
import com.ridestr.common.payment.PaymentCrypto
import com.ridestr.common.payment.WalletService
import com.ridestr.common.state.RideContext
import com.ridestr.common.state.RideEvent
import com.ridestr.common.state.RideState
import com.ridestr.common.state.RideStateMachine
import com.ridestr.common.state.TransitionResult
import com.ridestr.common.state.riderStageFromDriverStatus
import com.ridestr.common.util.PeriodicRefreshJob
import com.ridestr.common.util.RideHistoryBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import com.ridestr.rider.BuildConfig

/**
 * ViewModel for Rider mode.
 * Manages available drivers, route calculation, and ride requests.
 */
class RiderViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "RiderViewModel"
        // Fare pricing now comes from RemoteConfigManager (admin config)
        // Defaults: $1.85/mile, $5.00 minimum - fetched from Kind 30182 on startup
        // Fare boost amounts
        private const val FARE_BOOST_USD = 1.0     // $1 boost in USD mode
        private const val FARE_BOOST_SATS = 1000.0 // 1000 sats boost in sats mode
        // Fallback sats per mile when BTC price unavailable (assumes ~$100k BTC)
        private const val FALLBACK_SATS_PER_MILE = 2000.0
        // Conversion factor: 1 km = 0.621371 miles
        private const val KM_TO_MILES = 0.621371
        // Fee buffer for cross-mint payments (Lightning routing + melt fees)
        private const val FEE_BUFFER_PERCENT = 0.02  // 2% buffer
        // Time after which a driver is considered stale (10 minutes)
        private const val STALE_DRIVER_TIMEOUT_MS = 10 * 60 * 1000L
        // How often to check for stale drivers (30 seconds)
        private const val STALE_CHECK_INTERVAL_MS = 30_000L
        // Refresh chat subscription every 15 seconds to ensure messages are received
        private const val CHAT_REFRESH_INTERVAL_MS = 15 * 1000L
        // Maximum PIN verification attempts before cancelling (brute force protection)
        private const val MAX_PIN_ATTEMPTS = 3
        // SharedPreferences keys for ride state persistence
        private const val PREFS_NAME = "ridestr_ride_state"
        private const val KEY_RIDE_STATE = "active_ride"
        // Maximum age of persisted ride state (2 hours)
        private const val MAX_RIDE_STATE_AGE_MS = 2 * 60 * 60 * 1000L
        // Time to wait for driver to accept direct offer before showing options (15 seconds)
        private const val ACCEPTANCE_TIMEOUT_MS = 15_000L
        // Time to wait for any driver to accept broadcast request (2 minutes)
        private const val BROADCAST_TIMEOUT_MS = 120_000L

        // Demo location coordinates for testing
        // Demo coordinates - Las Vegas
        private const val DEMO_PICKUP_LAT = 36.1699      // Fremont Street Experience
        private const val DEMO_PICKUP_LON = -115.1398
        private const val DEMO_DEST_LAT = 36.1147        // Welcome to Las Vegas sign
        private const val DEMO_DEST_LON = -115.1728
    }

    /**
     * Computed parameters for sending a ride offer.
     * Each offer entry point constructs this, then delegates to shared send logic.
     */
    private data class OfferParams(
        val driverPubKey: String,
        val driverAvailabilityEventId: String?,  // null for RoadFlare
        val driverLocation: Location?,
        val pickup: Location,
        val destination: Location,
        val fareEstimate: Double,
        val rideRoute: RouteResult?,
        val preimage: String?,                   // null for alternate payment
        val paymentHash: String?,                // null for alternate payment
        val paymentMethod: String,
        val isRoadflare: Boolean,
        val isBroadcast: Boolean,
        // Post-send UI config
        val statusMessage: String,
        val roadflareTargetPubKey: String?,
        val roadflareTargetLocation: Location?,
    )

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val nostrService = NostrService(application)

    /** Expose NostrService for RoadFlare location subscriptions */
    fun getNostrService(): NostrService = nostrService

    // Remote config for fare rates and mints (fetched from admin pubkey Kind 30182)
    private val remoteConfigManager = RemoteConfigManager(application, nostrService.relayManager)

    /** Expose remote config for UI (recommended mints, etc.) */
    val remoteConfig get() = remoteConfigManager.config

    private val routingService = ValhallaRoutingService(application)
    private val geocodingService = GeocodingService(application)
    private val tileManager = TileManager.getInstance(application)
    private val savedLocationRepository = SavedLocationRepository.getInstance(application)
    private val rideHistoryRepository = RideHistoryRepository.getInstance(application)

    // Track which tile region is currently loaded
    private var currentTileRegion: String? = null

    // Settings manager for user preferences
    val settingsManager = SettingsManager(application)

    // Geocoding state
    private val _pickupSearchResults = MutableStateFlow<List<GeocodingResult>>(emptyList())
    val pickupSearchResults: StateFlow<List<GeocodingResult>> = _pickupSearchResults.asStateFlow()

    private val _destSearchResults = MutableStateFlow<List<GeocodingResult>>(emptyList())
    val destSearchResults: StateFlow<List<GeocodingResult>> = _destSearchResults.asStateFlow()

    private val _isSearchingPickup = MutableStateFlow(false)
    val isSearchingPickup: StateFlow<Boolean> = _isSearchingPickup.asStateFlow()

    private val _isSearchingDest = MutableStateFlow(false)
    val isSearchingDest: StateFlow<Boolean> = _isSearchingDest.asStateFlow()

    // Bitcoin price service for fare conversion
    val bitcoinPriceService = BitcoinPriceService.getInstance()

    // Wallet service for balance checks (injected from MainActivity)
    private var walletService: WalletService? = null

    fun setWalletService(service: WalletService?) {
        walletService = service
    }

    private val _uiState = MutableStateFlow(RiderUiState())
    val uiState: StateFlow<RiderUiState> = _uiState.asStateFlow()

    private var driverSubscriptionId: String? = null
    private var acceptanceSubscriptionId: String? = null
    private var chatSubscriptionId: String? = null
    private var cancellationSubscriptionId: String? = null
    // Driver ride state subscription (replaces pinSubmissionSubscriptionId and statusSubscriptionId)
    private var driverRideStateSubscriptionId: String? = null
    // Monitor selected driver's availability while waiting for acceptance
    private var selectedDriverAvailabilitySubId: String? = null
    // Track last seen timestamp for selected driver availability (reject out-of-order events)
    private var selectedDriverLastAvailabilityTimestamp: Long = 0L
    private var staleDriverCleanupJob: Job? = null
    private var chatRefreshJob: PeriodicRefreshJob? = null
    private var acceptanceTimeoutJob: Job? = null
    private var broadcastTimeoutJob: Job? = null
    private var bridgePendingPollJob: Job? = null
    private val profileSubscriptionIds = mutableMapOf<String, String>()
    private var currentSubscriptionGeohash: String? = null
    // First-acceptance-wins flag for broadcast mode
    private var hasAcceptedDriver: Boolean = false
    // Track when we last received an event per driver (receivedAt, not createdAt)
    // Used for accurate staleness detection - network latency can make fresh events appear stale
    private val driverLastReceivedAt = mutableMapOf<String, Long>()

    // ALL events I publish during a ride (for NIP-09 deletion on completion/cancellation)
    private val myRideEventIds = mutableListOf<String>()

    // Rider ride state history for consolidated Kind 30181 events
    // This accumulates all rider actions during a ride (location reveals, PIN verifications)
    // THREAD SAFETY: Use synchronized list and historyMutex to prevent race conditions
    // when multiple coroutines add actions and publish concurrently
    private val riderStateHistory = java.util.Collections.synchronizedList(mutableListOf<RiderRideAction>())
    private val historyMutex = kotlinx.coroutines.sync.Mutex()

    // Track how many driver actions we've processed (to detect new actions)
    private var lastProcessedDriverActionCount = 0

    // Track last received driver state event ID for chain integrity (AtoB pattern)
    private var lastReceivedDriverStateId: String? = null

    // Event deduplication sets - prevents stale events from affecting new rides
    // These track processed event IDs to avoid re-processing queued events from closed subscriptions
    private val processedDriverStateEventIds = mutableSetOf<String>()
    private val processedCancellationEventIds = mutableSetOf<String>()

    // Current phase for rider ride state - INFORMATIONAL ONLY (AtoB pattern)
    // This is published in Kind 30181 for logging/debugging, but:
    // - Driver ignores it (processes history array actions, not phase)
    // - Rider UI uses rideStage (derived from driver's status), not this phase
    // The driver is the single source of truth for post-confirmation state.
    private var currentRiderPhase = RiderRideStateEvent.Phase.AWAITING_DRIVER

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
            riderPubkey = nostrService.getPubKeyHex() ?: "",
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
     * Map current RideStage to RideState.
     */
    private fun currentRideState(): RideState = when (_uiState.value.rideSession.rideStage) {
        RideStage.IDLE -> RideState.CANCELLED
        RideStage.BROADCASTING_REQUEST -> RideState.CREATED
        RideStage.WAITING_FOR_ACCEPTANCE -> RideState.CREATED
        RideStage.DRIVER_ACCEPTED -> RideState.ACCEPTED
        RideStage.RIDE_CONFIRMED -> RideState.CONFIRMED
        RideStage.DRIVER_ARRIVED -> RideState.ARRIVED
        RideStage.IN_PROGRESS -> RideState.IN_PROGRESS
        RideStage.COMPLETED -> RideState.COMPLETED
    }

    /**
     * Close all ride-related subscriptions.
     * CRITICAL: Call this before starting a new ride to prevent old events affecting the new ride.
     */
    private fun closeAllRideSubscriptions() {
        cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
        cancellationSubscriptionId = null

        driverRideStateSubscriptionId?.let { nostrService.closeSubscription(it) }
        driverRideStateSubscriptionId = null

        chatSubscriptionId?.let { nostrService.closeSubscription(it) }
        chatSubscriptionId = null

        acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }
        acceptanceSubscriptionId = null

        Log.d(TAG, "Closed all ride subscriptions before new ride")
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
        myRideEventIds.clear()

        // Launch cleanup in background - does NOT block caller
        viewModelScope.launch {
            Log.d(TAG, "=== BACKGROUND CLEANUP START: $reason ===")
            val deletedCount = nostrService.backgroundCleanupRideshareEvents(reason)
            Log.d(TAG, "=== BACKGROUND CLEANUP DONE: Deleted $deletedCount rider events ===")
        }
    }

    /**
     * Helper to add a location reveal action to history and publish rider ride state.
     * @param locationType The location type ("pickup" or "destination")
     * @param location The precise location to reveal
     * @return The event ID if successful, null on failure
     */
    private suspend fun revealLocation(
        confirmationEventId: String,
        driverPubKey: String,
        locationType: String,
        location: Location
    ): String? {
        // Encrypt the location for the driver
        val encryptedLocation = nostrService.encryptLocationForRiderState(location, driverPubKey)
        if (encryptedLocation == null) {
            Log.e(TAG, "Failed to encrypt location")
            return null
        }

        // Add location reveal action to history
        val locationAction = RiderRideStateEvent.createLocationRevealAction(
            locationType = locationType,
            locationEncrypted = encryptedLocation
        )

        // CRITICAL: Use mutex to prevent race condition with PIN verification
        return historyMutex.withLock {
            riderStateHistory.add(locationAction)

            // Publish consolidated rider ride state
            nostrService.publishRiderRideState(
                confirmationEventId = confirmationEventId,
                driverPubKey = driverPubKey,
                currentPhase = currentRiderPhase,
                history = riderStateHistory.toList(),
                lastTransitionId = lastReceivedDriverStateId
            )
        }
    }

    /**
     * Helper to add a PIN verification action to history and publish rider ride state.
     * @param verified Whether the PIN was verified successfully
     * @param attempt The attempt number (1-3)
     * @return The event ID if successful, null on failure
     */
    private suspend fun publishPinVerification(
        confirmationEventId: String,
        driverPubKey: String,
        verified: Boolean,
        attempt: Int
    ): String? {
        // Add PIN verification action to history
        val pinAction = RiderRideStateEvent.createPinVerifyAction(
            verified = verified,
            attempt = attempt
        )

        // CRITICAL: Use mutex to prevent race condition with location reveals
        return historyMutex.withLock {
            riderStateHistory.add(pinAction)

            // Update phase based on verification result
            if (verified) {
                currentRiderPhase = RiderRideStateEvent.Phase.VERIFIED
            }

            // Publish consolidated rider ride state
            nostrService.publishRiderRideState(
                confirmationEventId = confirmationEventId,
                driverPubKey = driverPubKey,
                currentPhase = currentRiderPhase,
                history = riderStateHistory.toList(),
                lastTransitionId = lastReceivedDriverStateId
            )
        }
    }

    /**
     * Share the HTLC preimage and escrow token with the driver after PIN verification.
     * The preimage allows the driver to claim the escrow payment at ride completion.
     *
     * @param confirmationEventId The ride confirmation event ID
     * @param driverPubKey The driver's public key
     * @param preimage The 64-char hex preimage that unlocks the HTLC
     * @param escrowToken The HTLC token containing locked funds (optional)
     */
    private suspend fun sharePreimageWithDriver(
        confirmationEventId: String,
        driverPubKey: String,
        preimage: String,
        escrowToken: String? = null
    ) {
        try {
            // Encrypt preimage for driver using NIP-44
            val encryptedPreimage = nostrService.encryptForUser(preimage, driverPubKey)
            if (encryptedPreimage == null) {
                Log.e(TAG, "Failed to encrypt preimage for driver")
                return
            }

            // Encrypt escrow token if available
            val encryptedEscrowToken = escrowToken?.let {
                nostrService.encryptForUser(it, driverPubKey)
            }

            // Add PreimageShare action to rider state
            val preimageAction = RiderRideStateEvent.createPreimageShareAction(
                preimageEncrypted = encryptedPreimage,
                escrowTokenEncrypted = encryptedEscrowToken
            )

            // CRITICAL: Use mutex to prevent race condition with other state updates
            val eventId = historyMutex.withLock {
                riderStateHistory.add(preimageAction)

                // Publish updated rider ride state with preimage share
                nostrService.publishRiderRideState(
                    confirmationEventId = confirmationEventId,
                    driverPubKey = driverPubKey,
                    currentPhase = currentRiderPhase,
                    history = riderStateHistory.toList(),
                    lastTransitionId = lastReceivedDriverStateId
                )
            }

            if (eventId != null) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Shared encrypted preimage with driver: ${preimage.take(16)}...")
                if (escrowToken != null) {
                    Log.d(TAG, "Also shared HTLC escrow token")
                }
                myRideEventIds.add(eventId)
                // Mark preimage as shared - driver can now claim payment
                updateRideSession { copy(preimageShared = true) }
            } else {
                Log.e(TAG, "Failed to publish preimage share event")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing preimage: ${e.message}", e)
        }
    }

    /**
     * Clear rider state history (called when ride ends or is cancelled).
     * Also clears event deduplication sets to allow fresh events for new rides.
     */
    private fun clearRiderStateHistory() {
        riderStateHistory.clear()
        lastProcessedDriverActionCount = 0
        lastReceivedDriverStateId = null  // Reset chain for new ride
        currentRiderPhase = RiderRideStateEvent.Phase.AWAITING_DRIVER
        // Clear deduplication sets so new rides can process fresh events
        processedDriverStateEventIds.clear()
        processedCancellationEventIds.clear()
        Log.d(TAG, "Cleared rider state history and event deduplication sets")
    }

    /**
     * Reset ALL ride-related UI state fields to defaults.
     * Called at every ride boundary (completion, cancellation, new ride start).
     *
     * This is the SINGLE authoritative reset function. Any new field added to
     * RiderRideSession is automatically reset to its default — no need to list it here.
     *
     * Fields NOT reset (persist across rides): everything in outer RiderUiState
     * (availableDrivers, route, identity, dialog state, etc.)
     */
    private fun resetRideUiState(
        stage: RideStage,
        statusMessage: String,
        error: String? = null
    ) {
        _uiState.update { current ->
            current.copy(
                rideSession = RiderRideSession(rideStage = stage),
                statusMessage = statusMessage,
                error = error
            )
        }
    }

    /**
     * Atomically update only ride-session fields (no outer UiState fields changed).
     * For mixed updates (session + outer), use _uiState.update {} directly.
     */
    private inline fun updateRideSession(crossinline transform: RiderRideSession.() -> RiderRideSession) {
        _uiState.update { current ->
            current.copy(rideSession = current.rideSession.transform())
        }
    }

    /**
     * Close ALL ride-related subscriptions and stop ALL ride-related jobs.
     * Called at ride boundaries (completion, cancellation, new ride start)
     * to ensure no stale callbacks fire.
     *
     * This is the SUPERSET of closeAllRideSubscriptions(). It additionally
     * closes the driver availability subscription and cancels all jobs.
     *
     * Use closeAllRideSubscriptions() for MID-RIDE transitions (e.g., confirmRide)
     * where availability monitoring must stay active.
     * Use this function for RIDE-ENDING paths where everything must stop.
     *
     * Does NOT close: driverSubscriptionId (driver list), profileSubscriptionIds (profiles).
     * Those are managed by the driver discovery lifecycle, not ride lifecycle.
     */
    private fun closeAllRideSubscriptionsAndJobs() {
        // Close the base ride subscriptions (cancellation, driverRideState, chat, acceptance)
        closeAllRideSubscriptions()

        // ADDITIONALLY close driver availability monitoring (not closed by base function)
        selectedDriverAvailabilitySubId?.let { nostrService.closeSubscription(it) }
        selectedDriverAvailabilitySubId = null
        selectedDriverLastAvailabilityTimestamp = 0L

        // Jobs
        stopChatRefreshJob()
        acceptanceTimeoutJob?.cancel()
        acceptanceTimeoutJob = null
        broadcastTimeoutJob?.cancel()
        broadcastTimeoutJob = null
        bridgePendingPollJob?.cancel()
        bridgePendingPollJob = null

        // RoadFlare batch state — prevent stale offers to drivers from finished ride
        roadflareBatchJob?.cancel()
        roadflareBatchJob = null
        contactedDrivers.clear()

        Log.d(TAG, "Closed all ride subscriptions and jobs")
    }

    init {
        // Connect to relays and subscribe to drivers
        nostrService.connect()
        subscribeToDrivers()
        startStaleDriverCleanup()
        // Start Bitcoin price auto-refresh (every 5 minutes)
        bitcoinPriceService.startAutoRefresh()

        // Fetch remote config (fare rates, recommended mints) from admin pubkey
        viewModelScope.launch {
            remoteConfigManager.fetchConfig()
            Log.d(TAG, "Remote config loaded: fare=$${remoteConfigManager.config.value.fareRateUsdPerMile}/mi, min=$${remoteConfigManager.config.value.minimumFareUsd}")
        }

        // Set user's public key
        nostrService.getPubKeyHex()?.let { pubKey ->
            _uiState.value = _uiState.value.copy(myPubKey = pubKey)
        }

        // Routing is initialized on-demand based on location
        // Mark as ready - actual tile loading happens when route is calculated
        _uiState.value = _uiState.value.copy(isRoutingReady = true)

        // Restore any active ride state from previous session
        restoreRideState()
    }

    /**
     * Called when app returns to foreground.
     * Ensures relay connections are active and resubscribes to chat and cancellation if needed.
     */
    fun onResume() {
        Log.d(TAG, "onResume - ensuring relay connections and refreshing subscriptions")
        nostrService.ensureConnected()

        // If we have an active ride with a confirmation, refresh subscriptions
        val state = _uiState.value
        val session = state.rideSession
        val confId = session.confirmationEventId
        val acceptance = session.acceptance
        if (confId != null && session.rideStage in listOf(
                RideStage.RIDE_CONFIRMED,
                RideStage.DRIVER_ARRIVED,
                RideStage.IN_PROGRESS
            )) {
            Log.d(TAG, "Refreshing subscriptions for confirmation: $confId")
            subscribeToChatMessages(confId)
            subscribeToCancellation(confId)
            // Restart the periodic chat refresh job
            startChatRefreshJob(confId)

            // Refresh driver ride state subscription (handles PIN submissions and status updates)
            if (acceptance != null) {
                Log.d(TAG, "Refreshing driver ride state subscription")
                subscribeToDriverRideState(confId, acceptance.driverPubKey)
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
        if (session.rideStage == RideStage.IDLE || session.rideStage == RideStage.COMPLETED) {
            clearSavedRideState()
            return
        }

        val acceptance = session.acceptance
        val pickup = state.pickupLocation
        val destination = state.destination

        if (acceptance == null || pickup == null || destination == null) return

        try {
            val json = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("stage", session.rideStage.name)
                put("pendingOfferEventId", session.pendingOfferEventId)
                put("confirmationEventId", session.confirmationEventId)
                put("pickupPin", session.pickupPin)
                put("pinAttempts", session.pinAttempts)
                put("pinVerified", session.pinVerified)
                put("lastProcessedDriverActionCount", lastProcessedDriverActionCount)
                put("fareEstimate", state.fareEstimate)

                // Acceptance data
                put("acceptance_eventId", acceptance.eventId)
                put("acceptance_driverPubKey", acceptance.driverPubKey)
                put("acceptance_offerEventId", acceptance.offerEventId)
                put("acceptance_riderPubKey", acceptance.riderPubKey)
                put("acceptance_status", acceptance.status)
                put("acceptance_createdAt", acceptance.createdAt)

                // Locations
                put("pickupLat", pickup.lat)
                put("pickupLon", pickup.lon)
                put("pickupAddressLabel", pickup.addressLabel ?: "")
                put("destLat", destination.lat)
                put("destLon", destination.lon)
                put("destAddressLabel", destination.addressLabel ?: "")

                // HTLC Escrow state (critical for payment settlement)
                session.activePreimage?.let { put("activePreimage", it) }
                session.activePaymentHash?.let { put("activePaymentHash", it) }
                session.escrowToken?.let { put("escrowToken", it) }

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
            }

            prefs.edit().putString(KEY_RIDE_STATE, json.toString()).apply()
            Log.d(TAG, "Saved ride state: stage=${session.rideStage}, messages=${session.chatMessages.size}")
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
                RideStage.valueOf(stageName)
            } catch (e: Exception) {
                Log.w(TAG, "Invalid stage in saved state: $stageName")
                clearSavedRideState()
                return
            }

            // Reconstruct acceptance
            val acceptance = RideAcceptanceData(
                eventId = data.getString("acceptance_eventId"),
                driverPubKey = data.getString("acceptance_driverPubKey"),
                offerEventId = data.getString("acceptance_offerEventId"),
                riderPubKey = data.getString("acceptance_riderPubKey"),
                status = data.getString("acceptance_status"),
                createdAt = data.getLong("acceptance_createdAt")
            )

            // Reconstruct locations (with address labels if available)
            val pickupAddressLabel: String? = if (data.has("pickupAddressLabel"))
                data.getString("pickupAddressLabel").takeIf { it.isNotEmpty() } else null
            val destAddressLabel: String? = if (data.has("destAddressLabel"))
                data.getString("destAddressLabel").takeIf { it.isNotEmpty() } else null

            val pickup = Location(
                lat = data.getDouble("pickupLat"),
                lon = data.getDouble("pickupLon"),
                addressLabel = pickupAddressLabel
            )
            val destination = Location(
                lat = data.getDouble("destLat"),
                lon = data.getDouble("destLon"),
                addressLabel = destAddressLabel
            )

            val pendingOfferEventId: String? = if (data.has("pendingOfferEventId")) data.getString("pendingOfferEventId") else null
            val confirmationEventId: String? = if (data.has("confirmationEventId")) data.getString("confirmationEventId") else null
            val pickupPin: String? = if (data.has("pickupPin")) data.getString("pickupPin") else null
            val pinAttempts = data.optInt("pinAttempts", 0)
            val pinVerified = data.optBoolean("pinVerified", false)
            // Restore action count to prevent re-processing events on app restart
            lastProcessedDriverActionCount = data.optInt("lastProcessedDriverActionCount", 0)
            val fareEstimate = if (data.has("fareEstimate")) data.getDouble("fareEstimate") else null

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

            // Restore HTLC escrow state (critical for payment settlement)
            val activePreimage = data.optString("activePreimage", null)
            val activePaymentHash = data.optString("activePaymentHash", null)
            val escrowToken = data.optString("escrowToken", null)

            Log.d(TAG, "Restoring ride state: stage=$stage, confirmationId=$confirmationEventId, messages=${chatMessages.size}, escrow=${escrowToken != null}")

            // Restore state
            val fareEstimateWithFees = fareEstimate?.let { it * (1 + FEE_BUFFER_PERCENT) }
            _uiState.update { current ->
                current.copy(
                    pickupLocation = pickup,
                    destination = destination,
                    fareEstimate = fareEstimate,
                    fareEstimateWithFees = fareEstimateWithFees,
                    statusMessage = getStatusMessageForStage(stage, pickupPin),
                    rideSession = RiderRideSession(
                        rideStage = stage,
                        pendingOfferEventId = pendingOfferEventId,
                        acceptance = acceptance,
                        confirmationEventId = confirmationEventId,
                        pickupPin = pickupPin,
                        pinAttempts = pinAttempts,
                        pinVerified = pinVerified,
                        chatMessages = chatMessages,
                        activePreimage = activePreimage,
                        activePaymentHash = activePaymentHash,
                        escrowToken = escrowToken
                    )
                )
            }

            // Re-subscribe to relevant events
            if (confirmationEventId != null) {
                subscribeToChatMessages(confirmationEventId)
                subscribeToCancellation(confirmationEventId)
                subscribeToDriverRideState(confirmationEventId, acceptance.driverPubKey)
                // Start periodic chat refresh
                startChatRefreshJob(confirmationEventId)
            }

            // Start foreground service with appropriate status for restored ride
            // This ensures notification appears when app is reopened with active ride
            val context = getApplication<Application>()
            val driverName = _uiState.value.driverProfiles[acceptance.driverPubKey]?.bestName()?.split(" ")?.firstOrNull()

            when (stage) {
                RideStage.RIDE_CONFIRMED -> {
                    RiderActiveService.updateStatus(context, RiderStatus.DriverEnRoute(driverName))
                }
                RideStage.DRIVER_ARRIVED -> {
                    RiderActiveService.updateStatus(context, RiderStatus.DriverArrived(driverName))
                }
                RideStage.IN_PROGRESS -> {
                    RiderActiveService.updateStatus(context, RiderStatus.RideInProgress(driverName))
                }
                else -> { /* No service needed for other stages */ }
            }

            // Check for pending bridge payments for this ride (app restart recovery)
            if (confirmationEventId != null) {
                viewModelScope.launch {
                    val pendingBridge = walletService?.getInProgressBridgePayments()
                        ?.find { it.rideId == confirmationEventId }

                    if (pendingBridge != null && pendingBridge.status == BridgePaymentStatus.MELT_EXECUTED) {
                        Log.d(TAG, "Restoring pending bridge payment UI for ride $confirmationEventId")
                        _uiState.update { current ->
                            current.copy(
                                infoMessage = "Payment routing... Lightning may take a few minutes.",
                                rideSession = current.rideSession.copy(bridgeInProgress = true)
                            )
                        }

                        // Start polling to resolve the pending state
                        startBridgePendingPoll(pendingBridge.id, confirmationEventId, acceptance.driverPubKey)
                    }
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore ride state", e)
            clearSavedRideState()
        }
    }

    /**
     * Clear saved ride state from SharedPreferences.
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
        // Reset action counter so we don't skip legitimate new events
        lastProcessedDriverActionCount = 0
        // Clear tracked event IDs
        myRideEventIds.clear()
        // Clear rider state history
        riderStateHistory.clear()
        currentRiderPhase = RiderRideStateEvent.Phase.AWAITING_DRIVER
    }

    /**
     * Get appropriate status message for a stage.
     */
    private fun getStatusMessageForStage(stage: RideStage, pin: String?): String {
        return when (stage) {
            RideStage.IDLE -> "Find available drivers"
            RideStage.BROADCASTING_REQUEST -> "Searching for drivers..."
            RideStage.WAITING_FOR_ACCEPTANCE -> "Waiting for driver to accept..."
            RideStage.DRIVER_ACCEPTED -> "Driver accepted! Confirming ride..."
            RideStage.RIDE_CONFIRMED -> "Ride confirmed! Tell driver PIN: ${pin ?: "N/A"}"
            RideStage.DRIVER_ARRIVED -> "Driver has arrived at pickup!"
            RideStage.IN_PROGRESS -> "PIN verified! Ride in progress."
            RideStage.COMPLETED -> "Ride completed!"
        }
    }

    /**
     * Set pickup location.
     * Will resubscribe to drivers if the location changes to a different geohash area.
     */
    fun setPickupLocation(lat: Double, lon: Double) {
        val newLocation = Location(lat, lon)
        val newGeohash = newLocation.geohash(precision = 4)

        _uiState.value = _uiState.value.copy(
            pickupLocation = newLocation,
            routeResult = null,
            fareEstimate = null,
            fareEstimateWithFees = null
        )

        // If geohash changed significantly, resubscribe to get local drivers
        if (newGeohash != currentSubscriptionGeohash) {
            Log.d(TAG, "Pickup location geohash changed: $currentSubscriptionGeohash -> $newGeohash")
            resubscribeToDrivers(clearExisting = true)  // Region changed - clear old drivers
        }

        calculateRouteIfReady()
    }

    /**
     * Resubscribe to drivers with updated location filter.
     * @param clearExisting True if changing regions (geohash changed), false for same-region refresh.
     *                      Only clear driver list when changing regions - same-region refreshes should
     *                      preserve the existing driver list to avoid "bounce" effect.
     */
    private fun resubscribeToDrivers(clearExisting: Boolean = false) {
        // Close existing subscription
        driverSubscriptionId?.let { nostrService.closeSubscription(it) }

        if (clearExisting) {
            // Only clear when changing regions - drivers from old region are invalid
            _uiState.update { current ->
                current.copy(
                    availableDrivers = emptyList(),
                    driverProfiles = emptyMap(),
                    nearbyDriverCount = 0,  // Reset counter - will be updated by new subscription callbacks
                    rideSession = current.rideSession.copy(selectedDriver = null)
                )
            }

            // Close profile subscriptions
            profileSubscriptionIds.values.forEach { nostrService.closeSubscription(it) }
            profileSubscriptionIds.clear()

            // Clear receivedAt tracking (Fix 2 map cleanup)
            driverLastReceivedAt.clear()
        }
        // When clearExisting=false, new subscription will merge/update drivers via callback

        // Subscribe with new location
        subscribeToDrivers()
    }

    /**
     * Set destination location.
     */
    fun setDestination(lat: Double, lon: Double) {
        _uiState.value = _uiState.value.copy(
            destination = Location(lat, lon),
            routeResult = null,
            fareEstimate = null,
            fareEstimateWithFees = null
        )
        calculateRouteIfReady()
    }

    // ==================== Geocoding Methods ====================

    /**
     * Search for pickup locations by address.
     */
    fun searchPickupLocations(query: String) {
        if (query.length < 3) {
            _pickupSearchResults.value = emptyList()
            return
        }

        viewModelScope.launch {
            _isSearchingPickup.value = true
            try {
                val results = geocodingService.searchAddress(query)
                _pickupSearchResults.value = results
                Log.d(TAG, "Pickup search '$query' returned ${results.size} results")
            } catch (e: Exception) {
                Log.e(TAG, "Pickup search failed", e)
                _pickupSearchResults.value = emptyList()
            } finally {
                _isSearchingPickup.value = false
            }
        }
    }

    /**
     * Search for destination locations by address.
     */
    fun searchDestLocations(query: String) {
        if (query.length < 3) {
            _destSearchResults.value = emptyList()
            return
        }

        viewModelScope.launch {
            _isSearchingDest.value = true
            try {
                val results = geocodingService.searchAddress(query)
                _destSearchResults.value = results
                Log.d(TAG, "Destination search '$query' returned ${results.size} results")
            } catch (e: Exception) {
                Log.e(TAG, "Destination search failed", e)
                _destSearchResults.value = emptyList()
            } finally {
                _isSearchingDest.value = false
            }
        }
    }

    /**
     * Select a pickup location from search results.
     */
    fun selectPickupFromSearch(result: GeocodingResult) {
        val location = Location(
            lat = result.lat,
            lon = result.lon,
            addressLabel = result.addressLine
        )
        setPickupLocationWithAddress(location)
        _pickupSearchResults.value = emptyList()
        // Auto-save to recents
        savedLocationRepository.addRecent(result)
    }

    /**
     * Select a destination from search results.
     */
    fun selectDestFromSearch(result: GeocodingResult) {
        val location = Location(
            lat = result.lat,
            lon = result.lon,
            addressLabel = result.addressLine
        )
        setDestinationWithAddress(location)
        _destSearchResults.value = emptyList()
        // Auto-save to recents
        savedLocationRepository.addRecent(result)
    }

    /**
     * Set pickup location with address label.
     */
    fun setPickupLocationWithAddress(location: Location) {
        val newGeohash = location.geohash(precision = 4)

        _uiState.value = _uiState.value.copy(
            pickupLocation = location,
            routeResult = null,
            fareEstimate = null,
            fareEstimateWithFees = null
        )

        // If geohash changed significantly, resubscribe to get local drivers
        if (newGeohash != currentSubscriptionGeohash) {
            Log.d(TAG, "Pickup location geohash changed: $currentSubscriptionGeohash -> $newGeohash")
            resubscribeToDrivers(clearExisting = true)  // Region changed - clear old drivers
        }

        calculateRouteIfReady()
    }

    /**
     * Set destination location with address label.
     */
    fun setDestinationWithAddress(location: Location) {
        _uiState.value = _uiState.value.copy(
            destination = location,
            routeResult = null,
            fareEstimate = null,
            fareEstimateWithFees = null
        )
        calculateRouteIfReady()
    }

    // Track if using current location for pickup (initialized from saved preference)
    private val _usingCurrentLocationForPickup = MutableStateFlow(settingsManager.useGpsForPickup.value)
    val usingCurrentLocationForPickup: StateFlow<Boolean> = _usingCurrentLocationForPickup.asStateFlow()

    // Track if we're fetching current location
    private val _isFetchingLocation = MutableStateFlow(false)
    val isFetchingLocation: StateFlow<Boolean> = _isFetchingLocation.asStateFlow()

    /**
     * Use current location for pickup.
     * Uses the provided GPS coordinates (caller must handle permission).
     *
     * @param gpsLat GPS latitude (null if GPS unavailable)
     * @param gpsLon GPS longitude (null if GPS unavailable)
     */
    fun useCurrentLocationForPickup(gpsLat: Double? = null, gpsLon: Double? = null) {
        viewModelScope.launch {
            _isFetchingLocation.value = true
            _usingCurrentLocationForPickup.value = true
            settingsManager.setUseGpsForPickup(true)  // Persist preference

            Log.d(TAG, "useCurrentLocationForPickup: gpsLat=$gpsLat, gpsLon=$gpsLon")

            // GPS coordinates required
            if (gpsLat == null || gpsLon == null) {
                Log.w(TAG, "GPS unavailable - cannot use current location")
                _isFetchingLocation.value = false
                _usingCurrentLocationForPickup.value = false
                settingsManager.setUseGpsForPickup(false)  // Revert preference on failure
                return@launch
            }

            val lat = gpsLat
            val lon = gpsLon
            val labelSuffix: String? = null // Will use reverse geocoded address

            val location = Location(lat = lat, lon = lon)

            // Try to get address for the location
            val address = try {
                geocodingService.reverseGeocode(lat, lon)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to reverse geocode pickup location", e)
                null
            }

            // Determine the label
            val label = when {
                labelSuffix == "Demo Location" -> address ?: "Demo Location"
                labelSuffix == "GPS unavailable" -> address?.let { "$it (GPS unavailable)" } ?: "Demo Location (GPS unavailable)"
                address != null -> address
                else -> "Current Location"
            }

            setPickupLocationWithAddress(location.withAddress(label))
            _isFetchingLocation.value = false
        }
    }

    /**
     * Stop using current location for pickup (user wants to type address).
     */
    fun stopUsingCurrentLocationForPickup() {
        _usingCurrentLocationForPickup.value = false
        settingsManager.setUseGpsForPickup(false)  // Persist preference
        clearPickupLocation()
    }

    /**
     * Use demo pickup location for testing.
     */
    fun useDemoPickupLocation() {
        useCurrentLocationForPickup(null, null)
    }

    /**
     * Use demo destination location for testing.
     */
    fun useDemoDestLocation() {
        viewModelScope.launch {
            val location = Location(
                lat = DEMO_DEST_LAT,
                lon = DEMO_DEST_LON
            )

            // Try to get address for demo location
            val address = try {
                geocodingService.reverseGeocode(DEMO_DEST_LAT, DEMO_DEST_LON)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to reverse geocode demo destination", e)
                null
            }

            setDestinationWithAddress(location.withAddress(address ?: "Demo Destination"))
            Log.d(TAG, "Using demo destination location: $DEMO_DEST_LAT, $DEMO_DEST_LON")
        }
    }

    /**
     * Clear pickup search results.
     */
    fun clearPickupSearchResults() {
        _pickupSearchResults.value = emptyList()
    }

    /**
     * Clear destination search results.
     */
    fun clearDestSearchResults() {
        _destSearchResults.value = emptyList()
    }

    /**
     * Clear pickup location.
     */
    fun clearPickupLocation() {
        _uiState.value = _uiState.value.copy(
            pickupLocation = null,
            routeResult = null,
            fareEstimate = null,
            fareEstimateWithFees = null
        )
    }

    /**
     * Clear destination location.
     */
    fun clearDestination() {
        _uiState.value = _uiState.value.copy(
            destination = null,
            routeResult = null,
            fareEstimate = null,
            fareEstimateWithFees = null
        )
    }

    /**
     * Check if geocoding is available on this device.
     */
    fun isGeocodingAvailable(): Boolean = geocodingService.isAvailable()

    /**
     * Select a driver from the available list.
     */
    fun selectDriver(driver: DriverAvailabilityData) {
        updateRideSession { copy(selectedDriver = driver) }
    }

    /**
     * Clear selected driver.
     */
    fun clearSelectedDriver() {
        updateRideSession { copy(selectedDriver = null) }
    }

    /**
     * Handle when rider acknowledges driver went unavailable.
     * Cancels the pending offer and returns to driver selection.
     */
    fun dismissDriverUnavailable() {
        Log.d(TAG, "Rider acknowledged driver unavailable - cancelling offer")
        // Hide the dialog
        updateRideSession { copy(showDriverUnavailableDialog = false) }
        // Cancel the offer (this will handle cleanup and return to IDLE)
        cancelOffer()
        // Clear the selected driver
        clearSelectedDriver()
    }

    // ==================== Offer Helpers (Phase 2) ====================

    /**
     * Calculate driver→pickup route for accurate metrics on driver's card.
     * Returns null if driver location is unknown or routing service isn't ready.
     */
    private suspend fun calculatePickupRoute(
        driverLocation: Location?,
        pickup: Location
    ): RouteResult? {
        if (driverLocation == null || !routingService.isReady()) return null
        return routingService.calculateRoute(
            originLat = driverLocation.lat,
            originLon = driverLocation.lon,
            destLat = pickup.lat,
            destLon = pickup.lon
        )
    }

    /**
     * Send a ride offer event via NostrService.
     * Wraps the common nostrService.sendRideOffer() call used by direct and RoadFlare offers.
     * NOT used by broadcastRideRequest() which calls a different NostrService method.
     */
    private suspend fun sendOfferToNostr(
        params: OfferParams,
        pickupRoute: RouteResult?
    ): String? {
        val riderMintUrl = walletService?.getSavedMintUrl()
        return nostrService.sendRideOffer(
            driverPubKey = params.driverPubKey,
            driverAvailabilityEventId = params.driverAvailabilityEventId,
            pickup = params.pickup,
            destination = params.destination,
            fareEstimate = params.fareEstimate,
            pickupRouteKm = pickupRoute?.distanceKm,
            pickupRouteMin = pickupRoute?.let { it.durationSeconds / 60.0 },
            rideRouteKm = params.rideRoute?.distanceKm,
            rideRouteMin = params.rideRoute?.let { it.durationSeconds / 60.0 },
            mintUrl = riderMintUrl,
            paymentMethod = params.paymentMethod,
            isRoadflare = params.isRoadflare
        )
    }

    /**
     * Set up post-send subscriptions: acceptance monitoring, driver availability, and timeout.
     * NOTE: clearRiderStateHistory() is NOT called here — callers are responsible for calling
     * it at the correct time (before send for RoadFlare, after send for direct/broadcast).
     */
    private fun setupOfferSubscriptions(
        eventId: String,
        driverPubKey: String,
        isBroadcast: Boolean
    ) {
        myRideEventIds.add(eventId)

        if (isBroadcast) {
            hasAcceptedDriver = false
            subscribeToAcceptancesForBroadcast(eventId)
            startBroadcastTimeout()
            RiderActiveService.startSearching(getApplication())
        } else {
            subscribeToAcceptance(eventId, driverPubKey)
            subscribeToSelectedDriverAvailability(driverPubKey)
            startAcceptanceTimeout()
        }
    }

    /**
     * Apply success UI state after an offer is sent.
     * Sets ride stage, clears stale state, stores HTLC fields, and updates state machine.
     */
    private fun applyOfferSuccessState(params: OfferParams, eventId: String) {
        val myPubkey = nostrService.getPubKeyHex() ?: ""
        val riderMintUrl = walletService?.getSavedMintUrl()
        val fareWithFees = params.fareEstimate * (1 + FEE_BUFFER_PERCENT)

        _uiState.update { current ->
            current.copy(
                statusMessage = params.statusMessage,
                // Fare (always set — Direct uses pre-calculated, RoadFlare uses computed)
                fareEstimate = params.fareEstimate,
                fareEstimateWithFees = fareWithFees,
                rideSession = current.rideSession.copy(
                    isSendingOffer = false,
                    pendingOfferEventId = eventId,
                    rideStage = if (params.isBroadcast) RideStage.BROADCASTING_REQUEST
                                else RideStage.WAITING_FOR_ACCEPTANCE,
                    // Timeout tracking
                    acceptanceTimeoutStartMs = if (!params.isBroadcast) System.currentTimeMillis() else null,
                    broadcastStartTimeMs = if (params.isBroadcast) System.currentTimeMillis() else null,
                    directOfferTimedOut = false,
                    broadcastTimedOut = false,
                    // Clear stale ride state
                    confirmationEventId = null,
                    acceptance = null,
                    pinVerified = false,
                    pickupPin = null,
                    pinAttempts = 0,
                    escrowToken = null,
                    // HTLC (null for alternate payment)
                    activePreimage = params.preimage,
                    activePaymentHash = params.paymentHash,
                    // RoadFlare tracking (null for non-RoadFlare)
                    roadflareTargetDriverPubKey = params.roadflareTargetPubKey,
                    roadflareTargetDriverLocation = params.roadflareTargetLocation
                )
            )
        }

        // State machine update
        val newContext = RideContext.forOffer(
            riderPubkey = myPubkey,
            approxPickup = params.pickup,
            destination = params.destination,
            fareEstimateSats = params.fareEstimate.toLong(),
            offerEventId = eventId,
            paymentHash = params.paymentHash,
            riderMintUrl = riderMintUrl,
            paymentMethod = params.paymentMethod
        )
        updateStateMachineState(RideState.CREATED, newContext)
    }

    /**
     * Apply failure UI state when an offer fails to send.
     * Clears sending flag and HTLC fields (prevents stale values from prior rides).
     */
    private fun applyOfferFailureState(errorMessage: String) {
        _uiState.update { current ->
            current.copy(
                error = errorMessage,
                rideSession = current.rideSession.copy(
                    isSendingOffer = false,
                    activePreimage = null,
                    activePaymentHash = null,
                    escrowToken = null
                )
            )
        }
    }

    /**
     * Verify wallet has sufficient balance for the fare (with fee buffer).
     * Shows insufficient funds dialog if balance is too low.
     * @return true if wallet is ready, false if insufficient funds (dialog shown).
     */
    private suspend fun verifyWalletBalance(fareWithBuffer: Long): Boolean {
        val walletReady = walletService?.ensureWalletReady(fareWithBuffer) ?: false
        if (!walletReady) {
            val currentBalance = walletService?.getBalance() ?: 0L
            val shortfall = fareWithBuffer - currentBalance
            Log.w(TAG, "Wallet not ready: need $fareWithBuffer sats, verified balance insufficient")
            _uiState.update { current ->
                current.copy(
                    showInsufficientFundsDialog = true,
                    insufficientFundsAmount = shortfall.coerceAtLeast(0),
                    depositAmountNeeded = shortfall.coerceAtLeast(0),
                    rideSession = current.rideSession.copy(isSendingOffer = false)
                )
            }
            return false
        }
        return true
    }

    // ==================== Offer Senders ====================

    /**
     * Send a ride offer to the selected driver.
     * Pre-calculates driver→pickup route so driver sees accurate metrics immediately.
     */
    fun sendRideOffer() {
        val state = _uiState.value
        val driver = state.rideSession.selectedDriver ?: return
        val pickup = state.pickupLocation ?: return
        val destination = state.destination ?: return
        val fareEstimate = state.fareEstimate ?: return
        val fareWithBuffer = (fareEstimate * (1 + FEE_BUFFER_PERCENT)).toLong()

        viewModelScope.launch {
            updateRideSession { copy(isSendingOffer = true) }
            if (!verifyWalletBalance(fareWithBuffer)) return@launch

            rideState = RideState.CANCELLED
            val preimage = PaymentCrypto.generatePreimage()
            val paymentHash = PaymentCrypto.computePaymentHash(preimage)
            if (BuildConfig.DEBUG) Log.d(TAG, "Generated HTLC payment hash: ${paymentHash.take(16)}...")

            val params = OfferParams(
                driverPubKey = driver.driverPubKey,
                driverAvailabilityEventId = driver.eventId,
                driverLocation = driver.approxLocation,
                pickup = pickup, destination = destination,
                fareEstimate = fareEstimate, rideRoute = state.routeResult,
                preimage = preimage, paymentHash = paymentHash,
                paymentMethod = settingsManager.defaultPaymentMethod.value,
                isRoadflare = false, isBroadcast = false,
                statusMessage = "Waiting for driver to accept...",
                roadflareTargetPubKey = null, roadflareTargetLocation = null
            )

            val pickupRoute = calculatePickupRoute(driver.approxLocation, pickup)
            val eventId = sendOfferToNostr(params, pickupRoute)
            if (eventId != null) {
                Log.d(TAG, "Sent ride offer: $eventId with payment hash")
                clearRiderStateHistory()  // Direct: clear AFTER send, success-only
                setupOfferSubscriptions(eventId, driver.driverPubKey, isBroadcast = false)
                applyOfferSuccessState(params, eventId)
            } else {
                applyOfferFailureState("Failed to send ride offer")
            }
        }
    }

    /**
     * Send a RoadFlare ride offer to a favorite driver.
     *
     * @param driverPubKey The driver's Nostr public key
     * @param driverLocation The driver's current location (from Kind 30014), or null if offline
     */
    fun sendRoadflareOffer(driverPubKey: String, driverLocation: Location?) {
        val state = _uiState.value
        val pickup = state.pickupLocation ?: return
        val destination = state.destination ?: return
        val rideRoute = state.routeResult

        val fareEstimate = if (driverLocation != null) {
            calculateRoadflareFare(pickup, driverLocation, rideRoute)
        } else { state.fareEstimate ?: return }

        // Sync balance check (RoadFlare-specific — can show dialog without coroutine)
        val fareWithBuffer = (fareEstimate * (1 + FEE_BUFFER_PERCENT)).toLong()
        val currentBalance = walletService?.getBalance() ?: 0L
        if (currentBalance < fareWithBuffer) {
            val shortfall = fareWithBuffer - currentBalance
            Log.w(TAG, "Insufficient funds for RoadFlare: need $fareWithBuffer, have $currentBalance")
            _uiState.value = state.copy(
                showInsufficientFundsDialog = true,
                insufficientFundsAmount = shortfall,
                depositAmountNeeded = shortfall,
                insufficientFundsIsRoadflare = true,
                pendingRoadflareDriverPubKey = driverPubKey,
                pendingRoadflareDriverLocation = driverLocation
            )
            return
        }

        viewModelScope.launch {
            updateRideSession { copy(isSendingOffer = true) }
            rideState = RideState.CANCELLED
            clearRiderStateHistory()  // RoadFlare: clear BEFORE send

            val preimage = PaymentCrypto.generatePreimage()
            val paymentHash = PaymentCrypto.computePaymentHash(preimage)
            if (BuildConfig.DEBUG) Log.d(TAG, "Generated RoadFlare HTLC payment hash: ${paymentHash.take(16)}...")

            val params = OfferParams(
                driverPubKey = driverPubKey,
                driverAvailabilityEventId = null,
                driverLocation = driverLocation,
                pickup = pickup, destination = destination,
                fareEstimate = fareEstimate, rideRoute = rideRoute,
                preimage = preimage, paymentHash = paymentHash,
                paymentMethod = settingsManager.defaultPaymentMethod.value,
                isRoadflare = true, isBroadcast = false,
                statusMessage = "Waiting for driver to accept...",
                roadflareTargetPubKey = driverPubKey, roadflareTargetLocation = driverLocation
            )

            val pickupRoute = calculatePickupRoute(driverLocation, pickup)
            val eventId = sendOfferToNostr(params, pickupRoute)
            if (eventId != null) {
                Log.d(TAG, "Sent RoadFlare offer to ${driverPubKey.take(16)}: $eventId")
                setupOfferSubscriptions(eventId, driverPubKey, isBroadcast = false)
                applyOfferSuccessState(params, eventId)
            } else {
                applyOfferFailureState("Failed to send RoadFlare offer")
            }
        }
    }

    /**
     * Send a RoadFlare offer with an alternate (non-bitcoin) payment method.
     * Called when rider chooses "Continue with Alternate Payment" from insufficient funds dialog.
     * Skips the bitcoin balance check since payment will be handled outside the app.
     */
    fun sendRoadflareOfferWithAlternatePayment(paymentMethod: String) {
        val state = _uiState.value
        val driverPubKey = state.pendingRoadflareDriverPubKey ?: return
        val driverLocation = state.pendingRoadflareDriverLocation
        val pickup = state.pickupLocation ?: return
        val destination = state.destination ?: return
        val rideRoute = state.routeResult

        val fareEstimate = if (driverLocation != null) {
            calculateRoadflareFare(pickup, driverLocation, rideRoute)
        } else { state.fareEstimate ?: return }

        // Clear the pending state
        _uiState.value = _uiState.value.copy(
            showInsufficientFundsDialog = false,
            insufficientFundsIsRoadflare = false,
            pendingRoadflareDriverPubKey = null,
            pendingRoadflareDriverLocation = null
        )

        viewModelScope.launch {
            updateRideSession { copy(isSendingOffer = true) }
            rideState = RideState.CANCELLED
            clearRiderStateHistory()  // RoadFlare: clear BEFORE send

            // No HTLC for alternate payment — payment happens outside the app
            val params = OfferParams(
                driverPubKey = driverPubKey,
                driverAvailabilityEventId = null,
                driverLocation = driverLocation,
                pickup = pickup, destination = destination,
                fareEstimate = fareEstimate, rideRoute = rideRoute,
                preimage = null, paymentHash = null,
                paymentMethod = paymentMethod,
                isRoadflare = true, isBroadcast = false,
                statusMessage = "Waiting for driver to accept...",
                roadflareTargetPubKey = driverPubKey, roadflareTargetLocation = driverLocation
            )

            val pickupRoute = calculatePickupRoute(driverLocation, pickup)
            val eventId = sendOfferToNostr(params, pickupRoute)
            if (eventId != null) {
                Log.d(TAG, "Sent RoadFlare offer with $paymentMethod to ${driverPubKey.take(16)}: $eventId")
                setupOfferSubscriptions(eventId, driverPubKey, isBroadcast = false)
                applyOfferSuccessState(params, eventId)
            } else {
                applyOfferFailureState("Failed to send RoadFlare offer")
            }
        }
    }

    /** Batch size for RoadFlare broadcasts */
    private val ROADFLARE_BATCH_SIZE = 3
    /** Delay between batches in milliseconds (15 seconds) */
    private val ROADFLARE_BATCH_DELAY_MS = 15_000L

    /** Track active RoadFlare batch job for cancellation */
    private var roadflareBatchJob: kotlinx.coroutines.Job? = null
    /** Track which drivers have been contacted in current RoadFlare broadcast */
    private val contactedDrivers = mutableSetOf<String>()

    /**
     * Data class to hold driver info with pre-calculated route for sorting and sending.
     */
    private data class DriverWithRoute(
        val driver: com.ridestr.common.nostr.events.FollowedDriver,
        val location: Location?,
        val pickupRoute: RouteResult?,
        val distanceKm: Double // Route distance or haversine fallback
    )

    /**
     * Send RoadFlare ride offers to favorite drivers in batches.
     * Sends to 3 closest drivers at a time, waits 15 seconds for response,
     * then sends to the next 3 until a driver accepts or all are contacted.
     *
     * @param drivers List of followed drivers to send offers to
     * @param driverLocations Map of driver pubkey -> current location (from Kind 30014)
     */
    fun sendRoadflareToAll(
        drivers: List<com.ridestr.common.nostr.events.FollowedDriver>,
        driverLocations: Map<String, Location>
    ) {
        val state = _uiState.value
        val pickup = state.pickupLocation ?: return
        val destination = state.destination ?: return

        // Filter to eligible drivers (have key = approved connection)
        val eligibleDrivers = drivers.filter { driver ->
            driver.roadflareKey != null
        }

        if (eligibleDrivers.isEmpty()) {
            Log.w(TAG, "No eligible RoadFlare drivers to send to")
            _uiState.value = state.copy(error = "No favorite drivers available")
            return
        }

        Log.d(TAG, "Starting RoadFlare broadcast to ${eligibleDrivers.size} drivers - calculating routes...")

        // Clear previous state
        contactedDrivers.clear()
        roadflareBatchJob?.cancel()

        // Start batched sending (with route calculation)
        roadflareBatchJob = viewModelScope.launch {
            // Pre-calculate routes for all online drivers to get accurate sorting
            val driversWithRoutes = eligibleDrivers.map { driver ->
                val location = driverLocations[driver.pubkey]

                if (location != null && routingService.isReady()) {
                    // Calculate actual route from driver to pickup
                    val pickupRoute = routingService.calculateRoute(
                        originLat = location.lat,
                        originLon = location.lon,
                        destLat = pickup.lat,
                        destLon = pickup.lon
                    )

                    if (pickupRoute != null) {
                        DriverWithRoute(driver, location, pickupRoute, pickupRoute.distanceKm)
                    } else {
                        // Route calculation failed - use haversine as fallback
                        val haversineDist = haversineDistance(pickup.lat, pickup.lon, location.lat, location.lon) / 1000.0
                        DriverWithRoute(driver, location, null, haversineDist)
                    }
                } else if (location != null) {
                    // Routing service not ready - use haversine
                    val haversineDist = haversineDistance(pickup.lat, pickup.lon, location.lat, location.lon) / 1000.0
                    DriverWithRoute(driver, location, null, haversineDist)
                } else {
                    // No location (offline) - put at end with large distance
                    DriverWithRoute(driver, null, null, Double.MAX_VALUE)
                }
            }

            // Sort by actual route distance (online first, then offline)
            val sortedDrivers = driversWithRoutes.sortedBy { it.distanceKm }

            Log.d(TAG, "Route calculation complete. Order:")
            sortedDrivers.forEachIndexed { index, dwr ->
                val distStr = if (dwr.distanceKm == Double.MAX_VALUE) "offline"
                    else String.format("%.1f km (%.1f mi)", dwr.distanceKm, dwr.distanceKm * 0.621371)
                val routeType = if (dwr.pickupRoute != null) "route" else "haversine"
                Log.d(TAG, "  ${index + 1}. ${dwr.driver.pubkey.take(12)} - $distStr ($routeType)")
            }

            sendRoadflareBatches(sortedDrivers, pickup)
        }
    }

    /**
     * Send RoadFlare offers in batches, waiting between each batch for acceptance.
     */
    private suspend fun sendRoadflareBatches(
        sortedDrivers: List<DriverWithRoute>,
        pickup: Location
    ) {
        val batches = sortedDrivers.chunked(ROADFLARE_BATCH_SIZE)
        var batchIndex = 0

        for (batch in batches) {
            // Check if we already got an acceptance
            if (_uiState.value.rideSession.rideStage == RideStage.DRIVER_ACCEPTED ||
                _uiState.value.rideSession.rideStage == RideStage.RIDE_CONFIRMED ||
                _uiState.value.rideSession.rideStage == RideStage.IN_PROGRESS) {
                Log.d(TAG, "RoadFlare broadcast: Driver already accepted, stopping batch sending")
                return
            }

            // Check if cancelled
            if (_uiState.value.rideSession.rideStage == RideStage.IDLE) {
                Log.d(TAG, "RoadFlare broadcast: Cancelled, stopping batch sending")
                return
            }

            batchIndex++
            Log.d(TAG, "RoadFlare batch $batchIndex/${batches.size}: Sending to ${batch.size} drivers")

            // Update status message
            _uiState.value = _uiState.value.copy(
                statusMessage = "Contacting drivers... (${contactedDrivers.size + batch.size}/${sortedDrivers.size})"
            )

            // Send to all drivers in this batch
            for (dwr in batch) {
                if (dwr.driver.pubkey in contactedDrivers) continue
                contactedDrivers.add(dwr.driver.pubkey)

                val distanceInfo = if (dwr.location != null) {
                    val distMiles = dwr.distanceKm * 0.621371
                    String.format("%.1f mi away", distMiles)
                } else "offline"

                Log.d(TAG, "  -> Sending to ${dwr.driver.pubkey.take(12)} ($distanceInfo)")
                sendRoadflareOfferSilent(dwr.driver.pubkey, dwr.location, dwr.pickupRoute)
            }

            // Wait for acceptance (unless this is the last batch)
            if (batchIndex < batches.size) {
                Log.d(TAG, "RoadFlare batch $batchIndex: Waiting ${ROADFLARE_BATCH_DELAY_MS/1000}s for response...")

                // Wait in 1-second increments so we can check for acceptance
                repeat((ROADFLARE_BATCH_DELAY_MS / 1000).toInt()) {
                    kotlinx.coroutines.delay(1000)

                    // Check if driver accepted
                    if (_uiState.value.rideSession.rideStage == RideStage.DRIVER_ACCEPTED ||
                        _uiState.value.rideSession.rideStage == RideStage.RIDE_CONFIRMED) {
                        Log.d(TAG, "RoadFlare broadcast: Got acceptance during wait, stopping")
                        return
                    }

                    // Check if cancelled
                    if (_uiState.value.rideSession.rideStage == RideStage.IDLE) {
                        Log.d(TAG, "RoadFlare broadcast: Cancelled during wait, stopping")
                        return
                    }
                }
            }
        }

        Log.d(TAG, "RoadFlare broadcast complete: Contacted ${contactedDrivers.size} drivers")

        // If still waiting after all batches, update message
        if (_uiState.value.rideSession.rideStage == RideStage.WAITING_FOR_ACCEPTANCE) {
            _uiState.value = _uiState.value.copy(
                statusMessage = "Waiting for response from ${contactedDrivers.size} drivers..."
            )
        }
    }

    /**
     * Send a RoadFlare offer without updating the main UI state.
     * Used for batch sending where we don't want each send to reset the UI.
     *
     * @param driverPubKey The driver's public key
     * @param driverLocation The driver's current location (if known)
     * @param preCalculatedRoute Pre-calculated route from driver to pickup (for efficiency)
     */
    private suspend fun sendRoadflareOfferSilent(
        driverPubKey: String,
        driverLocation: Location?,
        preCalculatedRoute: RouteResult? = null
    ) {
        val state = _uiState.value
        val pickup = state.pickupLocation ?: return
        val destination = state.destination ?: return
        val rideRoute = state.routeResult

        val fareEstimate = if (driverLocation != null) {
            calculateRoadflareFareWithRoute(pickup, driverLocation, rideRoute, preCalculatedRoute)
        } else {
            state.fareEstimate ?: return
        }

        // Reuse existing HTLC if this is part of a batch
        val preimage = state.rideSession.activePreimage ?: PaymentCrypto.generatePreimage()
        val paymentHash = state.rideSession.activePaymentHash ?: PaymentCrypto.computePaymentHash(preimage)

        // Use pre-calculated route or calculate via helper
        val pickupRoute = preCalculatedRoute ?: calculatePickupRoute(driverLocation, pickup)

        val params = OfferParams(
            driverPubKey = driverPubKey,
            driverAvailabilityEventId = null,
            driverLocation = driverLocation,
            pickup = pickup, destination = destination,
            fareEstimate = fareEstimate, rideRoute = rideRoute,
            preimage = preimage, paymentHash = paymentHash,
            paymentMethod = settingsManager.defaultPaymentMethod.value,
            isRoadflare = true, isBroadcast = false,
            statusMessage = "",  // Batch doesn't set status per-offer
            roadflareTargetPubKey = driverPubKey, roadflareTargetLocation = driverLocation
        )

        val eventId = sendOfferToNostr(params, pickupRoute)

        if (eventId != null) {
            Log.d(TAG, "Sent RoadFlare offer to ${driverPubKey.take(12)}: ${eventId.take(12)}")
            myRideEventIds.add(eventId)

            // First offer in batch sets up subscriptions and UI state
            if (state.rideSession.pendingOfferEventId == null) {
                subscribeToAcceptance(eventId, driverPubKey)
                startAcceptanceTimeout()
                clearRiderStateHistory()

                val fareWithFees = fareEstimate * (1 + FEE_BUFFER_PERCENT)
                _uiState.update { current ->
                    current.copy(
                        fareEstimate = fareEstimate,
                        fareEstimateWithFees = fareWithFees,
                        rideSession = current.rideSession.copy(
                            isSendingOffer = false,
                            pendingOfferEventId = eventId,
                            rideStage = RideStage.WAITING_FOR_ACCEPTANCE,
                            acceptanceTimeoutStartMs = System.currentTimeMillis(),
                            directOfferTimedOut = false,
                            confirmationEventId = null,
                            acceptance = null,
                            pinVerified = false,
                            pickupPin = null,
                            pinAttempts = 0,
                            escrowToken = null,
                            activePreimage = preimage,
                            activePaymentHash = paymentHash,
                            roadflareTargetDriverPubKey = driverPubKey,
                            roadflareTargetDriverLocation = driverLocation
                        )
                    )
                }
            } else {
                // Additional offer in batch - just subscribe to this one too
                nostrService.subscribeToAcceptance(eventId, driverPubKey) { acceptance ->
                    handleBatchAcceptance(acceptance)
                }
            }
        }
    }

    /**
     * Handle acceptance from a batch RoadFlare offer.
     */
    private fun handleBatchAcceptance(acceptance: com.ridestr.common.nostr.events.RideAcceptanceData) {
        Log.d(TAG, "RoadFlare batch: Driver accepted! ${acceptance.driverPubKey.take(12)}")

        // Cancel the batch job
        roadflareBatchJob?.cancel()

        // Cancel the acceptance timeout
        cancelAcceptanceTimeout()
        // NOTE: Don't close availability subscription yet - keep monitoring through DRIVER_ACCEPTED
        // in case driver goes offline before we send confirmation (Kind 3179 won't work without confId)

        // Only process if we're still waiting
        if (_uiState.value.rideSession.rideStage == RideStage.WAITING_FOR_ACCEPTANCE) {
            _uiState.update { current ->
                current.copy(
                    statusMessage = "Driver accepted! Confirming ride...",
                    rideSession = current.rideSession.copy(
                        acceptance = acceptance,
                        rideStage = RideStage.DRIVER_ACCEPTED,
                        // Update to actual accepting driver (may differ from first driver in batch)
                        roadflareTargetDriverPubKey = acceptance.driverPubKey,
                        roadflareTargetDriverLocation = null  // Clear stale location
                    )
                )
            }

            // Auto-confirm the ride
            viewModelScope.launch {
                autoConfirmRide(acceptance)
            }
        }
    }


    /**
     * Calculate fare for RoadFlare using exact driver location.
     * Uses $1.20/mile rate (cheaper than geohash estimate due to accuracy).
     */
    private fun calculateRoadflareFare(
        pickup: Location,
        driverLocation: Location,
        rideRoute: RouteResult?
    ): Double {
        return calculateRoadflareFareWithRoute(pickup, driverLocation, rideRoute, null)
    }

    /**
     * Calculate RoadFlare fare using actual route distance when available.
     * Falls back to haversine if route is not provided.
     */
    private fun calculateRoadflareFareWithRoute(
        pickup: Location,
        driverLocation: Location,
        rideRoute: RouteResult?,
        pickupRoute: RouteResult?
    ): Double {
        val ROADFLARE_RATE_PER_MILE = remoteConfigManager.config.value.roadflareFareRateUsdPerMile
        val METERS_PER_MILE = 1609.34

        // Driver distance to pickup - use route if available, else haversine
        val driverToPickupMiles = if (pickupRoute != null) {
            pickupRoute.distanceKm * 0.621371
        } else {
            val driverToPickupMeters = haversineDistance(
                driverLocation.lat, driverLocation.lon,
                pickup.lat, pickup.lon
            )
            driverToPickupMeters / METERS_PER_MILE
        }

        // Ride distance (from route if available)
        val rideMiles = rideRoute?.let { it.distanceKm * 0.621371 } ?: 0.0

        // Total fare = driver pickup distance + ride distance, with minimum from remote config
        val config = remoteConfigManager.config.value
        val minimumFareUsd = config.roadflareMinimumFareUsd
        val calculatedFare = (driverToPickupMiles + rideMiles) * ROADFLARE_RATE_PER_MILE
        val fareUsd = maxOf(calculatedFare, minimumFareUsd)

        // Convert USD to sats using live BTC price
        val sats = bitcoinPriceService.usdToSats(fareUsd)
        // Fallback: 5000 sats when no BTC price available
        val MINIMUM_FALLBACK_SATS = 5000.0
        return sats?.toDouble() ?: MINIMUM_FALLBACK_SATS
    }

    /**
     * Haversine distance calculation in meters.
     */
    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }

    /**
     * Cancel the pending ride offer or broadcast request.
     * Handles both direct/RoadFlare offers and broadcast requests.
     */
    fun cancelOffer() {
        val isBroadcast = _uiState.value.rideSession.rideStage == RideStage.BROADCASTING_REQUEST

        closeAllRideSubscriptionsAndJobs()  // cancels ALL timeout jobs
        RiderActiveService.stop(getApplication())  // no-op if not running

        cleanupRideEventsInBackground(if (isBroadcast) "request cancelled" else "offer cancelled")

        resetRideUiState(
            stage = RideStage.IDLE,
            statusMessage = if (isBroadcast) "Request cancelled" else "Offer cancelled"
        )

        resubscribeToDrivers(clearExisting = false)
        clearSavedRideState()
    }

    /**
     * Boost the fare on a direct offer and resend to the same driver.
     * Includes pre-calculated route metrics.
     *
     * Supports both regular offers (with selectedDriver) and RoadFlare offers
     * (using roadflareTargetDriverPubKey/roadflareTargetDriverLocation fallback).
     */
    fun boostDirectOffer() {
        val state = _uiState.value
        val session = state.rideSession

        // Guard against double-tap race condition
        if (session.isSendingOffer) {
            Log.d(TAG, "boostDirectOffer: already sending, ignoring")
            return
        }

        // Support both regular offers (selectedDriver) and RoadFlare (tracked pubkey)
        val driverPubKey = session.selectedDriver?.driverPubKey
            ?: session.roadflareTargetDriverPubKey
            ?: return

        val driverAvailabilityEventId = session.selectedDriver?.eventId  // null for RoadFlare
        val driverLocation = session.selectedDriver?.approxLocation
            ?: session.roadflareTargetDriverLocation

        val isRoadflare = session.selectedDriver == null  // True if using RoadFlare target

        val currentFare = state.fareEstimate ?: return
        val pickup = state.pickupLocation ?: return
        val destination = state.destination ?: return
        val rideRoute = state.routeResult
        val boostAmount = getBoostAmount()
        val newFare = currentFare + boostAmount

        // Check wallet balance before boosting (includes fee buffer for cross-mint)
        val newFareWithFees = (newFare * (1 + FEE_BUFFER_PERCENT)).toLong()
        val currentBalance = walletService?.getBalance() ?: 0L

        if (currentBalance < newFareWithFees) {
            val shortfall = newFareWithFees - currentBalance
            Log.w(TAG, "Insufficient funds for boost: need $newFareWithFees sats, have $currentBalance sats")
            _uiState.value = state.copy(
                showInsufficientFundsDialog = true,
                insufficientFundsAmount = shortfall,
                depositAmountNeeded = shortfall
            )
            return
        }

        // Set flag BEFORE coroutine to close race window
        updateRideSession { copy(isSendingOffer = true) }

        viewModelScope.launch {
            cancelAcceptanceTimeout()

            // Delete old offer
            session.pendingOfferEventId?.let { offerId ->
                Log.d(TAG, "Deleting old direct offer before boost: $offerId")
                nostrService.deleteEvent(offerId, "fare boosted")
            }

            // Close old acceptance subscription
            acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }
            acceptanceSubscriptionId = null

            // Update fare in state - reset timeout flag since we're boosting
            val newFareWithFeesDouble = newFare * (1 + FEE_BUFFER_PERCENT)
            _uiState.update { current ->
                current.copy(
                    fareEstimate = newFare,
                    fareEstimateWithFees = newFareWithFeesDouble,
                    rideSession = current.rideSession.copy(
                        directOfferBoostSats = session.directOfferBoostSats + boostAmount,
                        pendingOfferEventId = null,
                        directOfferTimedOut = false,
                        isSendingOffer = true
                    )
                )
            }

            val offerType = if (isRoadflare) "RoadFlare" else "direct"
            Log.d(TAG, "Boosting $offerType offer fare from $currentFare to $newFare sats (displayed: $newFareWithFeesDouble, total boost: ${session.directOfferBoostSats + boostAmount} sats)")

            val pickupRoute = calculatePickupRoute(driverLocation, pickup)

            val params = OfferParams(
                driverPubKey = driverPubKey,
                driverAvailabilityEventId = driverAvailabilityEventId,
                driverLocation = driverLocation,
                pickup = pickup, destination = destination,
                fareEstimate = newFare, rideRoute = rideRoute,
                preimage = null, paymentHash = null,  // Boost reuses existing HTLC
                paymentMethod = settingsManager.defaultPaymentMethod.value,
                isRoadflare = isRoadflare, isBroadcast = false,
                statusMessage = "Waiting for driver to accept boosted offer...",
                roadflareTargetPubKey = null, roadflareTargetLocation = null
            )

            val eventId = sendOfferToNostr(params, pickupRoute)
            if (eventId != null) {
                Log.d(TAG, "Sent boosted $offerType offer: $eventId")
                myRideEventIds.add(eventId)
                subscribeToAcceptance(eventId, driverPubKey)
                startAcceptanceTimeout()
                _uiState.update { current ->
                    current.copy(
                        statusMessage = "Waiting for driver to accept boosted offer...",
                        rideSession = current.rideSession.copy(
                            isSendingOffer = false,
                            pendingOfferEventId = eventId,
                            acceptanceTimeoutStartMs = System.currentTimeMillis()
                        )
                    )
                }
            } else {
                applyOfferFailureState("Failed to resend boosted offer")
            }
        }
    }

    /**
     * Continue waiting for driver acceptance by resetting the timeout timer.
     */
    fun continueWaitingDirect() {
        // Cancel old timeout and start fresh
        cancelAcceptanceTimeout()
        startAcceptanceTimeout()

        // Reset the timer start and timeout flag
        _uiState.update { current ->
            current.copy(
                statusMessage = "Continuing to wait for driver...",
                rideSession = current.rideSession.copy(
                    acceptanceTimeoutStartMs = System.currentTimeMillis(),
                    directOfferTimedOut = false
                )
            )
        }

        Log.d(TAG, "Continue waiting for direct offer acceptance")
    }

    // ==================== Broadcast Ride Request (New Flow) ====================

    /**
     * Broadcast a public ride request visible to all drivers in the pickup area.
     * This is the new primary flow - riders broadcast and any driver can accept.
     */
    fun broadcastRideRequest() {
        val state = _uiState.value
        val pickup = state.pickupLocation ?: return
        val destination = state.destination ?: return
        val fareEstimate = state.fareEstimate ?: return
        val routeResult = state.routeResult ?: return
        val fareWithBuffer = (fareEstimate * (1 + FEE_BUFFER_PERCENT)).toLong()

        viewModelScope.launch {
            updateRideSession { copy(isSendingOffer = true) }
            if (!verifyWalletBalance(fareWithBuffer)) return@launch

            val preimage = PaymentCrypto.generatePreimage()
            val paymentHash = PaymentCrypto.computePaymentHash(preimage)
            if (BuildConfig.DEBUG) Log.d(TAG, "Generated HTLC payment hash for broadcast: ${paymentHash.take(16)}...")

            // Send APPROXIMATE locations for privacy in broadcast
            val approxPickup = pickup.approximate()
            val approxDestination = destination.approximate()
            Log.d(TAG, "Broadcasting with approximate locations - pickup: ${approxPickup.lat},${approxPickup.lon}, dest: ${approxDestination.lat},${approxDestination.lon}")

            val riderMintUrl = walletService?.getSavedMintUrl()
            val paymentMethod = settingsManager.defaultPaymentMethod.value

            val eventId = nostrService.broadcastRideRequest(
                pickup = approxPickup,
                destination = approxDestination,
                fareEstimate = fareEstimate,
                routeDistanceKm = routeResult.distanceKm,
                routeDurationMin = routeResult.durationSeconds / 60.0,
                mintUrl = riderMintUrl,
                paymentMethod = paymentMethod
            )

            if (eventId != null) {
                Log.d(TAG, "Broadcast ride request: $eventId")
                clearRiderStateHistory()  // Broadcast: clear AFTER send, success-only

                // Broadcast uses setupOfferSubscriptions for acceptance + timeout + foreground service
                val broadcastParams = OfferParams(
                    driverPubKey = "",  // broadcast has no specific driver
                    driverAvailabilityEventId = null,
                    driverLocation = null,
                    pickup = pickup, destination = destination,
                    fareEstimate = fareEstimate, rideRoute = routeResult,
                    preimage = preimage, paymentHash = paymentHash,
                    paymentMethod = paymentMethod,
                    isRoadflare = false, isBroadcast = true,
                    statusMessage = "Searching for drivers...",
                    roadflareTargetPubKey = null, roadflareTargetLocation = null
                )
                setupOfferSubscriptions(eventId, "", isBroadcast = true)
                applyOfferSuccessState(broadcastParams, eventId)
            } else {
                applyOfferFailureState("Failed to broadcast ride request")
            }
        }
    }

    /**
     * Boost the fare and re-broadcast the ride request.
     * Deletes the old request and creates a new one with higher fare.
     * Stores actual sats boosted (not count) to handle currency switching correctly.
     */
    fun boostFare() {
        val state = _uiState.value
        val session = state.rideSession

        // Guard against double-tap race condition
        if (session.isSendingOffer) {
            Log.d(TAG, "boostFare: already sending, ignoring")
            return
        }

        val currentFare = state.fareEstimate ?: return
        val boostAmount = getBoostAmount()
        val newFare = currentFare + boostAmount

        // Check wallet balance before boosting (includes 2% fee buffer for cross-mint)
        val newFareWithFees = (newFare * (1 + FEE_BUFFER_PERCENT)).toLong()
        val currentBalance = walletService?.getBalance() ?: 0L

        if (currentBalance < newFareWithFees) {
            val shortfall = newFareWithFees - currentBalance
            Log.w(TAG, "Insufficient funds for boost: need $newFareWithFees sats, have $currentBalance sats")
            _uiState.value = state.copy(
                showInsufficientFundsDialog = true,
                insufficientFundsAmount = shortfall,
                depositAmountNeeded = shortfall
            )
            return
        }

        // Set flag BEFORE coroutine to close race window
        updateRideSession { copy(isSendingOffer = true) }

        viewModelScope.launch {
            // Cancel current timeout
            cancelBroadcastTimeout()

            // Delete old offer
            session.pendingOfferEventId?.let { offerId ->
                Log.d(TAG, "Deleting old offer before boost: $offerId")
                nostrService.deleteEvent(offerId, "fare boosted")
            }

            // Close old acceptance subscription
            acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }
            acceptanceSubscriptionId = null

            // Update fare in state - store actual sats boosted (not count)
            // Reset broadcastTimedOut AND broadcastStartTimeMs to prevent race condition with UI
            // The explicit null reset ensures LaunchedEffect properly restarts when new time is set
            val newFareWithFees = newFare * (1 + FEE_BUFFER_PERCENT)
            _uiState.update { current ->
                current.copy(
                    fareEstimate = newFare,
                    fareEstimateWithFees = newFareWithFees,
                    rideSession = current.rideSession.copy(
                        totalBoostSats = session.totalBoostSats + boostAmount,
                        pendingOfferEventId = null,
                        broadcastTimedOut = false,
                        broadcastStartTimeMs = null  // Explicit reset for clean timer restart
                    )
                )
            }

            // Re-broadcast with new fare
            Log.d(TAG, "Boosting fare from $currentFare to $newFare sats (displayed: $newFareWithFees, total boost: ${session.totalBoostSats + boostAmount} sats)")
            broadcastRideRequest()
        }
    }


    /**
     * Continue waiting with the current fare.
     * Restarts the 2-minute broadcast timeout without modifying the fare.
     */
    fun continueWaiting() {
        Log.d(TAG, "Continuing to wait with current fare: ${_uiState.value.fareEstimate}")

        // Restart the broadcast timeout
        startBroadcastTimeout()

        _uiState.update { current ->
            current.copy(
                statusMessage = "Searching for drivers...",
                rideSession = current.rideSession.copy(
                    broadcastStartTimeMs = System.currentTimeMillis(),
                    broadcastTimedOut = false
                )
            )
        }
    }

    /**
     * Subscribe to acceptances for a broadcast ride request.
     * First acceptance wins - subsequent ones are ignored.
     */
    private fun subscribeToAcceptancesForBroadcast(offerEventId: String) {
        acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }

        acceptanceSubscriptionId = nostrService.subscribeToAcceptancesForOffer(offerEventId) { acceptance ->
            // First-acceptance-wins logic
            if (hasAcceptedDriver) {
                Log.d(TAG, "Ignoring duplicate acceptance from ${acceptance.driverPubKey.take(8)} - already accepted")
                return@subscribeToAcceptancesForOffer
            }

            // Only process if we're still broadcasting
            if (_uiState.value.rideSession.rideStage != RideStage.BROADCASTING_REQUEST) {
                Log.d(TAG, "Ignoring acceptance - not in broadcasting stage (stage=${_uiState.value.rideSession.rideStage})")
                return@subscribeToAcceptancesForOffer
            }

            Log.d(TAG, "First driver accepted! ${acceptance.driverPubKey.take(8)}")
            hasAcceptedDriver = true

            // Cancel the timeout
            cancelBroadcastTimeout()

            _uiState.update { current ->
                current.copy(
                    statusMessage = "Driver accepted! Confirming ride...",
                    rideSession = current.rideSession.copy(
                        acceptance = acceptance,
                        rideStage = RideStage.DRIVER_ACCEPTED,
                        broadcastStartTimeMs = null
                    )
                )
            }

            // Auto-confirm the ride (send precise pickup location)
            autoConfirmRide(acceptance)
        }
    }

    /**
     * Start the 2-minute broadcast timeout.
     */
    private fun startBroadcastTimeout() {
        cancelBroadcastTimeout()
        Log.d(TAG, "Starting broadcast timeout (${BROADCAST_TIMEOUT_MS / 1000}s)")
        broadcastTimeoutJob = viewModelScope.launch {
            delay(BROADCAST_TIMEOUT_MS)
            handleBroadcastTimeout()
        }
    }

    /**
     * Cancel the broadcast timeout.
     */
    private fun cancelBroadcastTimeout() {
        broadcastTimeoutJob?.cancel()
        broadcastTimeoutJob = null
    }

    /**
     * Handle broadcast timeout - no driver accepted in 2 minutes.
     */
    private fun handleBroadcastTimeout() {
        val state = _uiState.value
        val session = state.rideSession

        // Only timeout if we're still broadcasting
        if (session.rideStage != RideStage.BROADCASTING_REQUEST) {
            Log.d(TAG, "Broadcast timeout ignored - not broadcasting (stage=${session.rideStage})")
            return
        }

        Log.d(TAG, "Broadcast timeout - no driver accepted. Total boost: ${session.totalBoostSats} sats")

        // Don't automatically delete the offer - let user decide to boost or cancel
        // Set broadcastTimedOut = true so UI shows the options menu persistently
        _uiState.update { current ->
            current.copy(
                statusMessage = "No drivers responded. Boost fare or try again?",
                rideSession = current.rideSession.copy(
                    broadcastStartTimeMs = null,
                    broadcastTimedOut = true
                )
            )
        }
    }

    /**
     * Confirm the ride after driver accepts (would send precise location).
     * NOTE: This is rarely used since autoConfirmRide() handles most confirmations.
     */
    fun confirmRide() {
        val state = _uiState.value
        val acceptance = state.rideSession.acceptance ?: return
        val pickup = state.pickupLocation ?: return

        // CRITICAL: Prevent duplicate confirmations
        // autoConfirmRide() runs async, so user could tap confirm button during that window
        if (state.rideSession.isConfirmingRide) {
            Log.d(TAG, "Ignoring confirmRide - already confirming")
            return
        }
        if (state.rideSession.confirmationEventId != null) {
            Log.d(TAG, "Ignoring confirmRide - already confirmed: ${state.rideSession.confirmationEventId.take(8)}")
            return
        }

        // CRITICAL: Close any lingering subscriptions from previous rides
        // This prevents old cancellation events from affecting this new ride
        closeAllRideSubscriptions()

        viewModelScope.launch {
            updateRideSession { copy(isConfirmingRide = true) }

            // Pass paymentHash in confirmation (moved from offer for correct HTLC timing)
            // Manual path doesn't have escrow locked yet, so escrowToken is null
            val eventId = nostrService.confirmRide(
                acceptance = acceptance,
                precisePickup = pickup,
                paymentHash = state.rideSession.activePaymentHash,
                escrowToken = null  // Manual path doesn't lock escrow
            )

            if (eventId != null) {
                Log.d(TAG, "Confirmed ride: $eventId${state.rideSession.activePaymentHash?.let { " with payment hash" } ?: ""}")
                myRideEventIds.add(eventId)  // Track for cleanup

                // Guard: If ride was cancelled while we were suspended at confirmRide(),
                // clearRide() already reset state to IDLE but couldn't publish cancellation
                // (confirmationEventId was null in its snapshot). We must notify the driver.
                if (_uiState.value.rideSession.rideStage != RideStage.DRIVER_ACCEPTED) {
                    Log.w(TAG, "Ride cancelled during confirmation - publishing cancellation for $eventId")
                    nostrService.publishRideCancellation(
                        confirmationEventId = eventId,
                        otherPartyPubKey = acceptance.driverPubKey,
                        reason = "Rider cancelled"
                    )?.let { myRideEventIds.add(it) }
                    cleanupRideEventsInBackground("ride cancelled during confirmation")
                    return@launch
                }

                // Close acceptance subscription - we don't need it anymore
                // This prevents duplicate acceptance events from affecting state
                acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }
                acceptanceSubscriptionId = null

                // Keep availability subscription OPEN - don't close until EN_ROUTE_PICKUP
                // Driver may go offline after confirmation but before acknowledging.
                // The availability subscription detects this and triggers cancellation.

                // Start foreground service to keep process alive during ride
                val driverName = _uiState.value.driverProfiles[acceptance.driverPubKey]?.bestName()?.split(" ")?.firstOrNull()
                RiderActiveService.updateStatus(getApplication(), RiderStatus.DriverAccepted(driverName))

                // AtoB Pattern: Don't transition to RIDE_CONFIRMED yet - wait for driver's
                // EN_ROUTE_PICKUP status. The driver is the single source of truth.
                // We store confirmationEventId to track the ride, but keep DRIVER_ACCEPTED
                // until driver acknowledges with Kind 30180.
                _uiState.update { current ->
                    current.copy(
                        statusMessage = "Ride confirmed! Waiting for driver to start...",
                        rideSession = current.rideSession.copy(
                            isConfirmingRide = false,
                            confirmationEventId = eventId
                        )
                    )
                }

                // Save ride state for restart recovery
                saveRideState()

                // Subscribe AFTER state is set — handlers validate against
                // rideSession.confirmationEventId, so early events would be
                // rejected if confirmationEventId were still null.
                subscribeToDriverRideState(eventId, acceptance.driverPubKey)
                subscribeToChatMessages(eventId)
                startChatRefreshJob(eventId)
                subscribeToCancellation(eventId)
            } else {
                _uiState.update { current ->
                    current.copy(
                        error = "Failed to confirm ride",
                        rideSession = current.rideSession.copy(isConfirmingRide = false)
                    )
                }
            }
        }
    }

    /**
     * Attempt to cancel ride. If preimage was already shared with driver,
     * shows a warning dialog since driver can still claim payment.
     */
    fun attemptCancelRide() {
        val state = _uiState.value
        if (state.rideSession.preimageShared || state.rideSession.pinVerified) {
            // Show warning - driver has preimage and can claim payment
            updateRideSession { copy(showCancelWarningDialog = true) }
        } else {
            // Safe to cancel - driver doesn't have preimage
            clearRide()
        }
    }

    /**
     * Dismiss the cancel warning dialog without cancelling.
     */
    fun dismissCancelWarning() {
        updateRideSession { copy(showCancelWarningDialog = false) }
    }

    /**
     * Confirm cancellation after warning - proceeds with cancel even though driver can claim payment.
     */
    fun confirmCancelAfterWarning() {
        updateRideSession { copy(showCancelWarningDialog = false) }
        clearRide()
    }

    /**
     * Clear ride state after completion or cancellation.
     */
    fun clearRide() {
        val state = _uiState.value
        val session = state.rideSession

        // STATE_MACHINE: Validate CANCEL transition (clearRide is rider's cancel)
        val myPubkey = nostrService.getPubKeyHex() ?: ""
        if (session.rideStage != RideStage.IDLE && session.rideStage != RideStage.COMPLETED) {
            validateTransition(RideEvent.Cancel(myPubkey, "Rider cleared ride"))
        }

        // DEBUG: Log cancellation with stack trace to help identify phantom cancellations
        Log.w(TAG, "=== CLEAR RIDE CALLED ===")
        Log.w(TAG, "  confirmationEventId: ${session.confirmationEventId}")
        Log.w(TAG, "  rideStage: ${session.rideStage}")
        Log.w(TAG, "  pinVerified: ${session.pinVerified}")
        Log.w(TAG, "  bridgeInProgress: ${session.bridgeInProgress}")
        // Print abbreviated stack trace to identify caller
        val stackTrace = Thread.currentThread().stackTrace
        val relevantFrames = stackTrace.drop(2).take(5)  // Skip getStackTrace and clearRide itself
        relevantFrames.forEach { frame ->
            Log.w(TAG, "    at ${frame.className}.${frame.methodName}(${frame.fileName}:${frame.lineNumber})")
        }

        // Synchronous cleanup
        closeAllRideSubscriptionsAndJobs()
        clearRiderStateHistory()
        RiderActiveService.stop(getApplication())

        // Capture state values before launching coroutine
        val confirmationId = session.confirmationEventId
        val driverPubKey = session.acceptance?.driverPubKey
        val shouldSaveCancelledHistory = session.rideStage in listOf(RideStage.RIDE_CONFIRMED, RideStage.DRIVER_ARRIVED, RideStage.IN_PROGRESS)
        val shouldPublishCancellation = confirmationId != null && session.rideStage in listOf(
            RideStage.DRIVER_ACCEPTED, RideStage.RIDE_CONFIRMED, RideStage.DRIVER_ARRIVED, RideStage.IN_PROGRESS
        )

        viewModelScope.launch {
            // Save cancelled ride to history (only if ride was confirmed/in progress)
            if (shouldSaveCancelledHistory) {
                try {
                    val driver = session.selectedDriver
                    val driverProfile = driver?.let { state.driverProfiles[it.driverPubKey] }
                    val historyEntry = RideHistoryEntry(
                        rideId = confirmationId ?: session.pendingOfferEventId ?: "",
                        timestamp = RideHistoryBuilder.currentTimestampSeconds(),
                        role = "rider",
                        counterpartyPubKey = driver?.driverPubKey ?: driverPubKey ?: "",
                        pickupGeohash = state.pickupLocation?.geohash(6) ?: "",
                        dropoffGeohash = state.destination?.geohash(6) ?: "",
                        // Rider gets exact locations for their own history
                        pickupLat = state.pickupLocation?.lat,
                        pickupLon = state.pickupLocation?.lon,
                        pickupAddress = state.pickupLocation?.addressLabel,
                        dropoffLat = state.destination?.lat,
                        dropoffLon = state.destination?.lon,
                        dropoffAddress = state.destination?.addressLabel,
                        distanceMiles = RideHistoryBuilder.toDistanceMiles(state.routeResult?.distanceKm),
                        durationMinutes = 0,  // Ride was cancelled, no actual duration
                        fareSats = 0,  // No fare charged for cancelled ride
                        status = "cancelled",
                        // Driver details for ride history
                        counterpartyFirstName = RideHistoryBuilder.extractCounterpartyFirstName(driverProfile),
                        vehicleMake = driver?.carMake,
                        vehicleModel = driver?.carModel,
                        appOrigin = RideHistoryRepository.APP_ORIGIN_RIDESTR
                    )
                    rideHistoryRepository.addRide(historyEntry)
                    Log.d(TAG, "Saved rider-cancelled ride to history: ${historyEntry.rideId}")

                    // Backup to Nostr (encrypted to self)
                    rideHistoryRepository.backupToNostr(nostrService)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save cancelled ride to history", e)
                }
            }

            // If we have an active confirmed ride, notify the driver of cancellation
            if (confirmationId != null && driverPubKey != null && shouldPublishCancellation) {
                Log.d(TAG, "Publishing ride cancellation to driver")
                val cancellationEventId = nostrService.publishRideCancellation(
                    confirmationEventId = confirmationId,
                    otherPartyPubKey = driverPubKey,
                    reason = "Rider cancelled"
                )
                cancellationEventId?.let { myRideEventIds.add(it) }
            }

            // Clean up all our ride events (NIP-09)
            cleanupRideEventsInBackground("ride cancelled")

            // Reset ALL ride state
            resetRideUiState(
                stage = RideStage.IDLE,
                statusMessage = "Ready to book a ride"
            )

            // Resubscribe to get fresh driver list - same region, preserve existing
            resubscribeToDrivers(clearExisting = false)

            // Clear persisted ride state
            clearSavedRideState()
        }
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    /**
     * Dismiss insufficient funds dialog.
     */
    fun dismissInsufficientFundsDialog() {
        _uiState.value = _uiState.value.copy(
            showInsufficientFundsDialog = false,
            insufficientFundsIsRoadflare = false,
            pendingRoadflareDriverPubKey = null,
            pendingRoadflareDriverLocation = null
        )
    }

    /**
     * Show alternate payment setup dialog for RoadFlare rides.
     */
    fun showAlternatePaymentSetup() {
        _uiState.value = _uiState.value.copy(
            showAlternatePaymentSetupDialog = true
        )
    }

    /**
     * Dismiss alternate payment setup dialog.
     */
    fun dismissAlternatePaymentSetup() {
        _uiState.value = _uiState.value.copy(
            showAlternatePaymentSetupDialog = false
        )
    }

    /**
     * Toggle expanded search to find drivers in a wider area (~20 mile radius).
     */
    fun toggleExpandedSearch() {
        _uiState.value = _uiState.value.copy(
            expandedSearch = !_uiState.value.expandedSearch
        )
        Log.d(TAG, "Expanded search: ${_uiState.value.expandedSearch}")
        // Same region, just wider search - preserve existing drivers
        resubscribeToDrivers(clearExisting = false)
    }

    private fun subscribeToDrivers() {
        // Use pickup location for geohash filtering if available
        val filterLocation = _uiState.value.pickupLocation
        val expandSearch = _uiState.value.expandedSearch

        // Track current geohash for subscription
        currentSubscriptionGeohash = filterLocation?.geohash(precision = 4)
        Log.d(TAG, "Subscribing to drivers - geohash: $currentSubscriptionGeohash, expanded: $expandSearch")

        driverSubscriptionId = nostrService.subscribeToDrivers(filterLocation, expandSearch) { driver ->
            Log.d(TAG, "Found driver: ${driver.driverPubKey.take(8)}... status=${driver.status}, methods=${driver.paymentMethods}")

            val currentDrivers = _uiState.value.availableDrivers.toMutableList()
            val existingIndex = currentDrivers.indexOfFirst { it.driverPubKey == driver.driverPubKey }

            if (driver.isOffline) {
                // Driver went offline - remove from list
                if (existingIndex >= 0) {
                    Log.d(TAG, "Removing offline driver: ${driver.driverPubKey.take(8)}...")
                    currentDrivers.removeAt(existingIndex)
                    driverLastReceivedAt.remove(driver.driverPubKey)  // Cleanup tracking
                    // Clean up profile subscription
                    unsubscribeFromDriverProfile(driver.driverPubKey)
                    // Clear selection if this driver was selected
                    if (_uiState.value.rideSession.selectedDriver?.driverPubKey == driver.driverPubKey) {
                        _uiState.update { current ->
                            current.copy(
                                availableDrivers = currentDrivers,
                                rideSession = current.rideSession.copy(selectedDriver = null)
                            )
                        }
                        return@subscribeToDrivers
                    }
                }
            } else {
                // Check payment method compatibility before showing driver
                val riderMethods = settingsManager.paymentMethods.value
                if (!isPaymentCompatible(riderMethods, driver.paymentMethods)) {
                    Log.d(TAG, "Filtering out driver ${driver.driverPubKey.take(8)} - incompatible payment methods: rider=$riderMethods, driver=${driver.paymentMethods}")
                    // Remove if they were already in the list (e.g., updated their methods)
                    if (existingIndex >= 0) {
                        currentDrivers.removeAt(existingIndex)
                        driverLastReceivedAt.remove(driver.driverPubKey)  // Cleanup tracking
                        unsubscribeFromDriverProfile(driver.driverPubKey)
                    }
                    return@subscribeToDrivers
                }

                // Driver is available and compatible - add or update
                if (existingIndex >= 0) {
                    // Update existing driver
                    currentDrivers[existingIndex] = driver
                } else {
                    // Add new driver
                    currentDrivers.add(driver)
                    // Subscribe to their profile to get name
                    subscribeToDriverProfile(driver.driverPubKey)
                }
                // Track when we received this event (not when it was created)
                driverLastReceivedAt[driver.driverPubKey] = System.currentTimeMillis() / 1000
            }

            // Validate selectedDriver is still in the updated list
            val currentSelected = _uiState.value.rideSession.selectedDriver
            val selectedStillValid = currentSelected == null ||
                currentDrivers.any { it.driverPubKey == currentSelected.driverPubKey }

            _uiState.update { current ->
                current.copy(
                    availableDrivers = currentDrivers,
                    nearbyDriverCount = currentDrivers.size,
                    rideSession = current.rideSession.copy(
                        selectedDriver = if (selectedStillValid) currentSelected else null
                    )
                )
            }
        }
    }

    /**
     * Subscribe to a driver's profile to get their name.
     */
    private fun subscribeToDriverProfile(driverPubKey: String) {
        if (profileSubscriptionIds.containsKey(driverPubKey)) return

        val subId = nostrService.subscribeToProfile(driverPubKey) { profile ->
            Log.d(TAG, "Got profile for driver ${driverPubKey.take(8)}: ${profile.bestName()}")
            val currentProfiles = _uiState.value.driverProfiles.toMutableMap()
            currentProfiles[driverPubKey] = profile
            _uiState.value = _uiState.value.copy(driverProfiles = currentProfiles)
        }
        profileSubscriptionIds[driverPubKey] = subId
    }

    /**
     * Unsubscribe from a driver's profile.
     */
    private fun unsubscribeFromDriverProfile(driverPubKey: String) {
        profileSubscriptionIds.remove(driverPubKey)?.let { subId ->
            nostrService.closeSubscription(subId)
        }
        // Also remove from cached profiles
        val currentProfiles = _uiState.value.driverProfiles.toMutableMap()
        currentProfiles.remove(driverPubKey)
        _uiState.value = _uiState.value.copy(driverProfiles = currentProfiles)
    }

    /**
     * Periodically clean up stale drivers (no update for >10 minutes).
     */
    private fun startStaleDriverCleanup() {
        staleDriverCleanupJob?.cancel()
        staleDriverCleanupJob = viewModelScope.launch {
            while (isActive) {
                delay(STALE_CHECK_INTERVAL_MS)
                cleanupStaleDrivers()
            }
        }
    }

    /**
     * Remove drivers whose last event is older than STALE_DRIVER_TIMEOUT_MS.
     * Uses driverLastReceivedAt (when we received the event) instead of createdAt
     * (when the event was published) for accurate staleness detection.
     */
    private fun cleanupStaleDrivers() {
        // Don't cleanup during active ride stages - prevents false removals
        // During rides, the UI shows ride progress instead of driver list anyway
        val stage = _uiState.value.rideSession.rideStage
        if (stage != RideStage.IDLE) {
            return  // Silent skip - cleanup will resume when back to IDLE
        }

        val now = System.currentTimeMillis() / 1000 // Convert to seconds (Nostr uses seconds)
        val staleThreshold = now - (STALE_DRIVER_TIMEOUT_MS / 1000)

        val currentDrivers = _uiState.value.availableDrivers
        val staleDrivers = mutableListOf<String>()
        val freshDrivers = currentDrivers.filter { driver ->
            // Use receivedAt (when we got the event) for freshness, not createdAt (when published)
            // Network latency can make fresh events appear stale if we use createdAt
            val lastReceived = driverLastReceivedAt[driver.driverPubKey] ?: driver.createdAt
            val isFresh = lastReceived >= staleThreshold
            if (!isFresh) {
                Log.d(TAG, "Removing stale driver: ${driver.driverPubKey.take(8)}... (last received ${now - lastReceived}s ago)")
                staleDrivers.add(driver.driverPubKey)
                driverLastReceivedAt.remove(driver.driverPubKey)  // Cleanup tracking
            }
            isFresh
        }

        if (freshDrivers.size != currentDrivers.size) {
            // Clean up profile subscriptions for stale drivers
            staleDrivers.forEach { pubKey ->
                unsubscribeFromDriverProfile(pubKey)
            }

            // Check if selected driver was removed
            val selectedDriver = _uiState.value.rideSession.selectedDriver
            val selectedStillExists = selectedDriver == null ||
                freshDrivers.any { it.driverPubKey == selectedDriver.driverPubKey }

            _uiState.update { current ->
                current.copy(
                    availableDrivers = freshDrivers,
                    nearbyDriverCount = freshDrivers.size,
                    rideSession = current.rideSession.copy(
                        selectedDriver = if (selectedStillExists) selectedDriver else null
                    )
                )
            }
            Log.d(TAG, "Removed ${staleDrivers.size} stale drivers, ${freshDrivers.size} remaining")
        }
    }

    private fun subscribeToAcceptance(offerEventId: String, expectedDriverPubKey: String) {
        acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }

        acceptanceSubscriptionId = nostrService.subscribeToAcceptance(offerEventId, expectedDriverPubKey) { acceptance ->
            Log.d(TAG, "Driver accepted ride: ${acceptance.eventId}")

            // Cancel the acceptance timeout - driver responded
            cancelAcceptanceTimeout()
            // NOTE: Don't close availability subscription yet - keep monitoring through DRIVER_ACCEPTED
            // in case driver goes offline before confirmation is sent (Kind 3179 won't work without confId)

            // Only process if we're still waiting for acceptance
            // This prevents duplicate events from resetting the state after confirmation
            if (_uiState.value.rideSession.rideStage == RideStage.WAITING_FOR_ACCEPTANCE) {
                _uiState.update { current ->
                    current.copy(
                        statusMessage = "Driver accepted! Confirming ride...",
                        rideSession = current.rideSession.copy(
                            acceptance = acceptance,
                            rideStage = RideStage.DRIVER_ACCEPTED,
                            acceptanceTimeoutStartMs = null
                        )
                    )
                }

                // Auto-confirm the ride (send precise pickup location)
                autoConfirmRide(acceptance)
            } else {
                Log.d(TAG, "Ignoring duplicate acceptance - already in stage ${_uiState.value.rideSession.rideStage}")
            }
        }
    }

    /**
     * Monitor the selected driver's availability while waiting for acceptance.
     * If driver goes offline (takes another ride, loses connection), notify rider.
     */
    private fun subscribeToSelectedDriverAvailability(driverPubKey: String) {
        // Close any existing subscription AND reset timestamp
        closeDriverAvailabilitySubscription()

        Log.d(TAG, "Monitoring availability for selected driver ${driverPubKey.take(8)}")

        selectedDriverAvailabilitySubId = nostrService.subscribeToDriverAvailability(driverPubKey) { availability ->
            // Reject out-of-order events - late OFFLINE events shouldn't override newer ONLINE
            if (availability.createdAt < selectedDriverLastAvailabilityTimestamp) {
                Log.d(TAG, "Ignoring out-of-order availability event (${availability.createdAt} < $selectedDriverLastAvailabilityTimestamp)")
                return@subscribeToDriverAvailability
            }
            selectedDriverLastAvailabilityTimestamp = availability.createdAt

            val currentStage = _uiState.value.rideSession.rideStage

            // Only care about this if we're waiting for acceptance OR in early ride stages
            // CRITICAL: Also check DRIVER_ACCEPTED because driver may cancel before confirmation
            // arrives (the confirmation hasn't been sent yet, so Kind 3179 cancellation won't work)
            if (currentStage !in listOf(RideStage.WAITING_FOR_ACCEPTANCE, RideStage.DRIVER_ACCEPTED)) {
                Log.d(TAG, "Ignoring driver availability - not in pre-confirmation stage (stage=$currentStage)")
                return@subscribeToDriverAvailability
            }

            // Check if driver went offline or became unavailable
            if (!availability.isAvailable) {
                Log.w(TAG, "Selected driver ${driverPubKey.take(8)} is no longer available (status: ${availability.status})")

                if (currentStage == RideStage.DRIVER_ACCEPTED) {
                    // Driver cancelled after accepting but before we confirmed
                    // This is effectively a cancellation - clean up and return to IDLE
                    Log.w(TAG, "Driver went offline during DRIVER_ACCEPTED - treating as cancellation")
                    handleDriverCancellation("Driver went offline")
                } else {
                    // Still waiting for acceptance - show dialog
                    _uiState.update { current ->
                        current.copy(
                            statusMessage = "Driver is no longer available",
                            rideSession = current.rideSession.copy(showDriverUnavailableDialog = true)
                        )
                    }
                }
            }
        }
    }

    /**
     * Close the driver availability monitoring subscription.
     * Also resets the timestamp guard to prevent stale events from affecting future subscriptions.
     */
    private fun closeDriverAvailabilitySubscription() {
        selectedDriverAvailabilitySubId?.let {
            nostrService.closeSubscription(it)
            selectedDriverAvailabilitySubId = null
        }
        // Reset timestamp when subscription closes - new subscription should accept fresh events
        selectedDriverLastAvailabilityTimestamp = 0L
    }

    /**
     * Automatically confirm the ride when driver accepts.
     * Generates PIN locally and sends precise pickup location to the driver.
     */
    private fun autoConfirmRide(acceptance: RideAcceptanceData) {
        val pickup = _uiState.value.pickupLocation ?: return

        // CRITICAL: Set flag IMMEDIATELY (before coroutine) to prevent race condition
        // Without this, user could tap manual confirm button while coroutine is launching
        updateRideSession { copy(isConfirmingRide = true) }

        // Generate PIN locally - rider is the one with money at stake
        val pickupPin = String.format("%04d", Random.nextInt(10000))
        Log.d(TAG, "Generated pickup PIN: $pickupPin")

        // Check if driver is already close (within 1 mile) - if so, send precise pickup immediately
        // Look up driver's location from available drivers list
        val driverLocation = _uiState.value.availableDrivers
            .find { it.driverPubKey == acceptance.driverPubKey }
            ?.approxLocation

        val driverAlreadyClose = driverLocation?.let { pickup.isWithinMile(it) } == true

        // RoadFlare rides always get precise pickup - it's a trusted driver network
        val isRoadflareRide = _uiState.value.rideSession.roadflareTargetDriverPubKey != null

        viewModelScope.launch {
            // Send APPROXIMATE pickup for privacy - precise location revealed when driver is close
            // UNLESS: driver is already within 1 mile, OR this is a RoadFlare ride (trusted network)
            val pickupToSend = if (driverAlreadyClose || isRoadflareRide) {
                val reason = if (isRoadflareRide) "RoadFlare trusted network" else "driver within 1 mile"
                Log.d(TAG, "Sending precise pickup immediately ($reason)")
                pickup
            } else {
                pickup.approximate()
            }
            Log.d(TAG, "Sending pickup: ${pickupToSend.lat}, ${pickupToSend.lon} (precise: ${pickup.lat}, ${pickup.lon}, roadflare: $isRoadflareRide, driver close: $driverAlreadyClose)")

            // Determine payment path (same mint vs cross-mint)
            val riderMintUrl = walletService?.getCurrentMintUrl()
            val driverMintUrl = acceptance.mintUrl
            val paymentMethod = acceptance.paymentMethod ?: "cashu"
            val paymentPath = PaymentPath.determine(riderMintUrl, driverMintUrl, paymentMethod)
            Log.d(TAG, "PaymentPath: $paymentPath (rider: $riderMintUrl, driver: $driverMintUrl)")

            // Lock HTLC escrow NOW using driver's wallet pubkey from acceptance
            // This ensures the P2PK condition matches the key the driver will sign with
            val paymentHash = _uiState.value.rideSession.activePaymentHash
            val fareAmount = _uiState.value.fareEstimate?.toLong() ?: 0L
            // Use driver's wallet pubkey for P2PK, fall back to Nostr key if not provided (legacy)
            val rawDriverKey = acceptance.walletPubKey ?: acceptance.driverPubKey
            // Cashu NUT-11 requires compressed pubkey (33 bytes = 66 hex chars)
            // If driver sent x-only pubkey (32 bytes = 64 hex), add "02" prefix
            val driverP2pkKey = if (rawDriverKey.length == 64) "02$rawDriverKey" else rawDriverKey

            // HTLC Escrow Locking - only for SAME_MINT path
            // For CROSS_MINT, payment happens via Lightning bridge at pickup
            // Correlation ID: Use acceptanceEventId for pre-confirmation logging
            val rideCorrelationId = acceptance.eventId.take(8)

            val escrowToken = if (paymentPath == PaymentPath.SAME_MINT) {
                Log.d(TAG, "[RIDE $rideCorrelationId] Locking HTLC: fareAmount=$fareAmount, paymentHash=${paymentHash?.take(16)}...")
                Log.d(TAG, "[RIDE $rideCorrelationId] driverP2pkKey (${driverP2pkKey.length} chars)=${driverP2pkKey.take(16)}...")

                if (paymentHash != null && fareAmount > 0) {
                    try {
                        val lockResult = walletService?.lockForRide(
                            amountSats = fareAmount,
                            paymentHash = paymentHash,
                            driverPubKey = driverP2pkKey,
                            expirySeconds = 900L,  // 15 minutes
                            preimage = _uiState.value.rideSession.activePreimage  // Store for future-proof refunds
                        )
                        when (lockResult) {
                            is LockResult.Success -> {
                                Log.d(TAG, "[RIDE $rideCorrelationId] Lock SUCCESS (token: ${lockResult.escrowLock.htlcToken.length} chars)")
                                lockResult.escrowLock.htlcToken
                            }
                            is LockResult.Failure.InsufficientBalance -> {
                                Log.e(TAG, "[RIDE $rideCorrelationId] Lock FAILED: ${lockResult.message} (need ${lockResult.required}, have ${lockResult.available})")
                                null
                            }
                            is LockResult.Failure.ProofsSpent -> {
                                Log.e(TAG, "[RIDE $rideCorrelationId] Lock FAILED: ${lockResult.message} (${lockResult.spentCount}/${lockResult.totalSelected} spent)")
                                null
                            }
                            is LockResult.Failure -> {
                                Log.e(TAG, "[RIDE $rideCorrelationId] Lock FAILED: ${lockResult.message}")
                                null
                            }
                            null -> {
                                Log.e(TAG, "[RIDE $rideCorrelationId] Lock FAILED: WalletService not available")
                                null
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "[RIDE $rideCorrelationId] Exception during lockForRide: ${e.message}", e)
                        null
                    }
                } else {
                    Log.w(TAG, "[RIDE $rideCorrelationId] Cannot lock escrow: paymentHash=$paymentHash, fareAmount=$fareAmount")
                    null
                }
            } else {
                Log.d(TAG, "[RIDE $rideCorrelationId] Skipping escrow lock (${paymentPath.name}) - payment via ${if (paymentPath == PaymentPath.CROSS_MINT) "Lightning bridge" else paymentPath.name}")
                null  // No escrow token for cross-mint - payment happens at pickup
            }

            if (escrowToken == null && paymentPath == PaymentPath.SAME_MINT) {
                Log.e(TAG, "[RIDE $rideCorrelationId] ESCROW LOCK FAILED - ride will proceed WITHOUT payment security")
            }

            // Pass paymentHash in confirmation (moved from offer for correct HTLC timing)
            // Driver needs paymentHash for PIN verification and HTLC claim
            val eventId = nostrService.confirmRide(
                acceptance = acceptance,
                precisePickup = pickupToSend,  // Send precise if driver is close, approximate otherwise
                paymentHash = paymentHash,
                escrowToken = escrowToken
            )

            if (eventId != null) {
                Log.d(TAG, "Auto-confirmed ride: $eventId")
                myRideEventIds.add(eventId)  // Track for cleanup

                // Guard: If ride was cancelled while we were suspended at confirmRide(),
                // clearRide() already reset state to IDLE but couldn't publish cancellation
                // (confirmationEventId was null in its snapshot). We must notify the driver.
                if (_uiState.value.rideSession.rideStage != RideStage.DRIVER_ACCEPTED) {
                    Log.w(TAG, "Ride cancelled during confirmation - publishing cancellation for $eventId")
                    nostrService.publishRideCancellation(
                        confirmationEventId = eventId,
                        otherPartyPubKey = acceptance.driverPubKey,
                        reason = "Rider cancelled"
                    )?.let { myRideEventIds.add(it) }
                    cleanupRideEventsInBackground("ride cancelled during confirmation")
                    return@launch
                }

                // Close acceptance subscription - we don't need it anymore
                acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }
                acceptanceSubscriptionId = null

                // Keep availability subscription OPEN - don't close until EN_ROUTE_PICKUP
                // Driver may go offline after confirmation but before acknowledging.
                // The availability subscription detects this and triggers cancellation.

                // Update service status - keep DriverAccepted until driver sends EN_ROUTE_PICKUP
                // AtoB Pattern: Don't transition notification until driver confirms state
                val driverName = _uiState.value.driverProfiles[acceptance.driverPubKey]?.bestName()?.split(" ")?.firstOrNull()
                RiderActiveService.updateStatus(getApplication(), RiderStatus.DriverAccepted(driverName))

                // AtoB Pattern: Don't transition to RIDE_CONFIRMED yet - wait for driver's
                // EN_ROUTE_PICKUP status. The driver is the single source of truth.
                // We store confirmationEventId and PIN, but keep DRIVER_ACCEPTED stage.
                _uiState.update { current ->
                    current.copy(
                        statusMessage = "Ride confirmed! Your PIN is: $pickupPin",
                        rideSession = current.rideSession.copy(
                            isConfirmingRide = false,
                            confirmationEventId = eventId,
                            pickupPin = pickupPin,
                            pinAttempts = 0,
                            precisePickupShared = driverAlreadyClose,
                            escrowToken = escrowToken,
                            paymentPath = paymentPath,
                            driverMintUrl = driverMintUrl
                        )
                    )
                }

                // Save ride state for persistence
                saveRideState()

                // Subscribe to driver ride state (PIN submissions and status updates)
                subscribeToDriverRideState(eventId, acceptance.driverPubKey)

                // Subscribe to chat messages for this ride
                subscribeToChatMessages(eventId)
                // Start periodic refresh to ensure messages are received
                startChatRefreshJob(eventId)

                // Subscribe to cancellation events
                subscribeToCancellation(eventId)
            } else {
                _uiState.update { current ->
                    current.copy(
                        error = "Failed to confirm ride",
                        rideSession = current.rideSession.copy(isConfirmingRide = false)
                    )
                }
            }
        }
    }

    /**
     * Subscribe to driver ride state (Kind 30180) for PIN submissions and status updates.
     * This unified subscription replaces subscribeToPinSubmissions and subscribeToDriverStatus.
     */
    private fun subscribeToDriverRideState(confirmationEventId: String, driverPubKey: String) {
        // DEBUG: Log subscription creation to trace phantom cancellation
        Log.w(TAG, "=== CREATING DRIVER STATE SUBSCRIPTION ===")
        Log.w(TAG, "  For confirmationEventId: $confirmationEventId")
        Log.w(TAG, "  Current state confirmationEventId: ${_uiState.value.rideSession.confirmationEventId}")
        Log.w(TAG, "  Current rideStage: ${_uiState.value.rideSession.rideStage}")
        Log.w(TAG, "  Old subscriptionId: $driverRideStateSubscriptionId")

        driverRideStateSubscriptionId?.let {
            Log.w(TAG, "  Closing old subscription: $it")
            nostrService.closeSubscription(it)
        }

        Log.d(TAG, "Subscribing to driver ride state for confirmation: ${confirmationEventId.take(8)}")

        driverRideStateSubscriptionId = nostrService.subscribeToDriverRideState(
            confirmationEventId = confirmationEventId,
            driverPubKey = driverPubKey
        ) { driverState ->
            handleDriverRideState(driverState, confirmationEventId, driverPubKey)
        }

        if (driverRideStateSubscriptionId != null) {
            Log.d(TAG, "Driver ride state subscription created: $driverRideStateSubscriptionId")
        } else {
            Log.e(TAG, "Failed to create driver ride state subscription - not logged in?")
        }
    }

    /**
     * Handle updates to driver ride state - processes new actions in history.
     */
    private fun handleDriverRideState(driverState: DriverRideStateData, confirmationEventId: String, driverPubKey: String) {
        // FIRST: Event deduplication - prevents stale queued events from affecting new rides
        // This is the definitive fix for the phantom cancellation bug
        if (driverState.eventId in processedDriverStateEventIds) {
            Log.w(TAG, "=== IGNORING ALREADY-PROCESSED DRIVER STATE EVENT: ${driverState.eventId.take(8)} ===")
            return
        }

        // Track for chain integrity (AtoB pattern) - save the event ID we received
        lastReceivedDriverStateId = driverState.eventId

        // Log chain integrity info for debugging
        driverState.lastTransitionId?.let { transitionId ->
            Log.d(TAG, "Chain: Driver state references our previous event: ${transitionId.take(8)}")
        }

        val currentState = _uiState.value

        // DEBUG: Extensive logging to trace phantom cancellation bug
        Log.w(TAG, "=== DRIVER STATE RECEIVED ===")
        Log.w(TAG, "  Event ID: ${driverState.eventId.take(8)}")
        Log.w(TAG, "  Event confirmationEventId: ${driverState.confirmationEventId}")
        Log.w(TAG, "  Closure confirmationEventId: $confirmationEventId")
        Log.w(TAG, "  Current state confirmationEventId: ${currentState.rideSession.confirmationEventId}")
        Log.w(TAG, "  Current rideStage: ${currentState.rideSession.rideStage}")
        Log.w(TAG, "  Event status: ${driverState.currentStatus}")
        Log.w(TAG, "  Event history size: ${driverState.history.size}")
        Log.w(TAG, "  lastProcessedDriverActionCount: $lastProcessedDriverActionCount")

        // SECOND: Validate the EVENT's confirmation ID matches current ride
        // This is the definitive check - the event itself knows which ride it belongs to
        if (driverState.confirmationEventId != currentState.rideSession.confirmationEventId) {
            Log.w(TAG, "  >>> REJECTED: event confId doesn't match current state <<<")
            return
        }

        // Mark as processed AFTER validation passes
        processedDriverStateEventIds.add(driverState.eventId)
        Log.w(TAG, "  >>> VALIDATION PASSED - processing event (marked as processed) <<<")
        Log.w(TAG, "===============================")

        // Process only NEW actions (ones we haven't seen yet)
        val newActions = driverState.history.drop(lastProcessedDriverActionCount)
        lastProcessedDriverActionCount = driverState.history.size

        if (newActions.isEmpty()) {
            Log.d(TAG, "No new actions to process")
            return
        }

        Log.d(TAG, "Processing ${newActions.size} new driver actions")

        newActions.forEach { action ->
            when (action) {
                is DriverRideAction.Status -> {
                    handleDriverStatusAction(action, driverState, confirmationEventId)
                }
                is DriverRideAction.PinSubmit -> {
                    handlePinSubmission(action, confirmationEventId, driverPubKey)
                }
                is DriverRideAction.Settlement -> {
                    // TODO: Handle settlement confirmation from driver (Stage 5)
                    Log.d(TAG, "Received settlement confirmation: ${action.settledAmount} sats")
                }
                is DriverRideAction.DepositInvoiceShare -> {
                    // Store deposit invoice for cross-mint bridge payment
                    Log.d(TAG, "Received deposit invoice from driver: ${action.amount} sats")
                    handleDepositInvoiceShare(action)
                }
            }
        }
    }

    /**
     * Handle a status action from the driver.
     *
     * AtoB Pattern: Driver is the single source of truth for post-confirmation ride state.
     * The rider's UI stage is DERIVED from the driver's status, not set independently.
     * This eliminates state divergence between the two apps.
     */
    private fun handleDriverStatusAction(action: DriverRideAction.Status, driverState: DriverRideStateData, confirmationEventId: String) {
        val state = _uiState.value
        val context = getApplication<Application>()

        // CRITICAL: Validate this event is for the CURRENT ride
        // This prevents old events from affecting new rides
        if (state.rideSession.confirmationEventId != confirmationEventId) {
            Log.d(TAG, "Ignoring driver status for old ride: ${confirmationEventId.take(8)} vs current ${state.rideSession.confirmationEventId?.take(8)}")
            return
        }

        val driverPubKey = state.rideSession.acceptance?.driverPubKey
        val driverName = driverPubKey?.let { _uiState.value.driverProfiles[it]?.bestName()?.split(" ")?.firstOrNull() }

        // Store the authoritative driver status (AtoB: driver is custodian)
        Log.d(TAG, "Driver status update: ${action.status}")

        // Derive rider's UI stage from driver's status
        val derivedStageName = riderStageFromDriverStatus(action.status)
        val derivedStage = derivedStageName?.let {
            try { RideStage.valueOf(it) } catch (e: Exception) { null }
        }

        when (action.status) {
            DriverStatusType.EN_ROUTE_PICKUP -> {
                Log.d(TAG, "Driver is en route to pickup (derived stage: $derivedStage)")
                // NOW safe to close availability subscription - driver has acknowledged the ride
                // and is actively driving. Any cancellation from here uses Kind 3179.
                closeDriverAvailabilitySubscription()
                RiderActiveService.updateStatus(context, RiderStatus.DriverEnRoute(driverName))
                _uiState.update { current ->
                    current.copy(
                        statusMessage = "Driver is on the way!",
                        rideSession = current.rideSession.copy(
                            lastDriverStatus = action.status,
                            rideStage = derivedStage ?: RideStage.RIDE_CONFIRMED
                        )
                    )
                }
                saveRideState()
            }
            DriverStatusType.ARRIVED -> {
                Log.d(TAG, "Driver has arrived! (derived stage: $derivedStage)")
                RiderActiveService.updateStatus(context, RiderStatus.DriverArrived(driverName))
                currentRiderPhase = RiderRideStateEvent.Phase.AWAITING_PIN
                _uiState.update { current ->
                    current.copy(
                        statusMessage = "Driver has arrived! Tell them your PIN: ${current.rideSession.pickupPin}",
                        rideSession = current.rideSession.copy(
                            lastDriverStatus = action.status,
                            rideStage = derivedStage ?: RideStage.DRIVER_ARRIVED
                        )
                    )
                }
                saveRideState()
            }
            DriverStatusType.IN_PROGRESS -> {
                Log.d(TAG, "Ride is in progress (derived stage: $derivedStage)")
                RiderActiveService.updateStatus(context, RiderStatus.RideInProgress(driverName))
                currentRiderPhase = RiderRideStateEvent.Phase.IN_RIDE
                _uiState.update { current ->
                    current.copy(
                        statusMessage = "Ride in progress",
                        rideSession = current.rideSession.copy(
                            lastDriverStatus = action.status,
                            rideStage = derivedStage ?: RideStage.IN_PROGRESS
                        )
                    )
                }
                saveRideState()
            }
            DriverStatusType.COMPLETED -> {
                Log.d(TAG, "Ride completed!")
                // Store status first, then use dedicated completion handler
                updateRideSession { copy(lastDriverStatus = action.status) }
                handleRideCompletion(driverState)
            }
            DriverStatusType.CANCELLED -> {
                Log.w(TAG, "=== CANCELLED STATUS DETECTED ===")
                Log.w(TAG, "  Closure confirmationEventId: $confirmationEventId")
                Log.w(TAG, "  Current state confirmationEventId: ${state.rideSession.confirmationEventId}")
                Log.w(TAG, "  Current rideStage: ${state.rideSession.rideStage}")
                updateRideSession { copy(lastDriverStatus = action.status) }
                handleDriverCancellation()
            }
        }
    }

    /**
     * Handle a PIN submission action from the driver.
     */
    private fun handlePinSubmission(action: DriverRideAction.PinSubmit, confirmationEventId: String, driverPubKey: String) {
        val state = _uiState.value
        val session = state.rideSession

        // CRITICAL: Skip if already verified (prevents duplicate verification on app restart)
        // After app restart, subscription may receive full history including already-verified PIN actions
        if (session.pinVerified) {
            Log.d(TAG, "PIN already verified, ignoring duplicate pin action")
            return
        }

        val expectedPin = session.pickupPin ?: return

        viewModelScope.launch {
            // Decrypt the PIN
            val decryptedPin = nostrService.decryptPinFromDriverState(action.pinEncrypted, driverPubKey)
            if (decryptedPin == null) {
                Log.e(TAG, "Failed to decrypt PIN")
                return@launch
            }

            Log.d(TAG, "Received PIN submission from driver: $decryptedPin")

            val newAttempts = session.pinAttempts + 1
            val isCorrect = decryptedPin == expectedPin

            // Send verification response via rider ride state
            val verificationEventId = publishPinVerification(
                confirmationEventId = confirmationEventId,
                driverPubKey = driverPubKey,
                verified = isCorrect,
                attempt = newAttempts
            )
            verificationEventId?.let { myRideEventIds.add(it) }  // Track for cleanup

            if (isCorrect) {
                Log.d(TAG, "PIN verified successfully!")

                // CRITICAL: Set pinVerified IMMEDIATELY to prevent race condition
                // If handlePinSubmission is called twice (from duplicate events), the second call
                // must see pinVerified=true and skip, otherwise we get double bridge payments
                updateRideSession { copy(pinVerified = true) }

                // CRITICAL: Add delay to ensure distinct timestamp for payment/preimage events
                // NIP-33 replaceable events use timestamp (seconds) + event ID for ordering.
                delay(1100L)

                // Branch based on payment path
                when (session.paymentPath) {
                    PaymentPath.SAME_MINT -> {
                        // SAME_MINT: Share preimage and escrow token with driver for HTLC settlement
                        val preimage = session.activePreimage
                        val escrowToken = session.escrowToken
                        Log.d(TAG, "SAME_MINT: Preparing preimage share: preimage=${preimage != null}, escrowToken=${escrowToken != null}")
                        if (preimage != null) {
                            sharePreimageWithDriver(confirmationEventId, driverPubKey, preimage, escrowToken)
                        } else {
                            Log.w(TAG, "No preimage to share - escrow was not set up")
                        }
                    }
                    PaymentPath.CROSS_MINT -> {
                        // CROSS_MINT: Execute Lightning bridge payment to driver's mint
                        val depositInvoice = session.driverDepositInvoice
                        Log.d(TAG, "CROSS_MINT: Preparing bridge payment, invoice=${depositInvoice != null}")
                        if (depositInvoice != null) {
                            executeBridgePayment(confirmationEventId, driverPubKey, depositInvoice)
                        } else {
                            Log.w(TAG, "No deposit invoice from driver - cannot execute bridge payment")
                            // TODO: Show error to user - driver didn't share deposit invoice
                        }
                    }
                    PaymentPath.FIAT_CASH -> {
                        // FIAT_CASH: No digital payment needed
                        Log.d(TAG, "FIAT_CASH: No digital payment required")
                    }
                    PaymentPath.NO_PAYMENT -> {
                        Log.w(TAG, "NO_PAYMENT: Ride proceeding without payment setup")
                    }
                }

                // CRITICAL: Check if ride is still active after async payment operation
                // If ride was cancelled during bridge/preimage sharing, don't overwrite state
                val currentState = _uiState.value
                if (currentState.rideSession.confirmationEventId != confirmationEventId) {
                    Log.w(TAG, "Ride was cancelled during payment operation - not updating state to IN_PROGRESS")
                    Log.w(TAG, "  Expected confirmationEventId: $confirmationEventId")
                    Log.w(TAG, "  Current confirmationEventId: ${currentState.rideSession.confirmationEventId}")
                    return@launch
                }

                // AtoB Pattern: Don't transition to IN_PROGRESS yet - wait for driver's
                // IN_PROGRESS status. The driver is the single source of truth.
                // We update pinVerified locally, but keep service status at DriverArrived
                // until driver acknowledges with Kind 30180 IN_PROGRESS.

                // Use fresh state to preserve any changes made during async operations
                _uiState.update { current ->
                    current.copy(
                        statusMessage = "PIN verified! Starting ride...",
                        rideSession = current.rideSession.copy(
                            pinAttempts = newAttempts,
                            pinVerified = true,
                            pickupPin = null
                        )
                    )
                }

                // Save ride state for persistence
                saveRideState()

                // CRITICAL: Add delay to ensure distinct timestamp for LocationReveal event
                delay(1100L)

                // Reveal precise destination to driver now that ride is starting
                revealPreciseDestination(confirmationEventId)
            } else {
                Log.w(TAG, "PIN incorrect! Attempt $newAttempts of $MAX_PIN_ATTEMPTS")

                if (newAttempts >= MAX_PIN_ATTEMPTS) {
                    // Brute force protection - cancel the ride
                    Log.e(TAG, "Max PIN attempts reached! Cancelling ride for security.")

                    closeAllRideSubscriptionsAndJobs()
                    clearRiderStateHistory()
                    RiderActiveService.stop(getApplication())

                    resetRideUiState(
                        stage = RideStage.IDLE,
                        statusMessage = "Ride cancelled - too many wrong PIN attempts",
                        error = "Security alert: Driver entered wrong PIN $MAX_PIN_ATTEMPTS times. Ride cancelled."
                    )

                    cleanupRideEventsInBackground("pin brute force security")
                    resubscribeToDrivers(clearExisting = false)
                    clearSavedRideState()
                } else {
                    _uiState.update { current ->
                        current.copy(
                            statusMessage = "Wrong PIN! ${MAX_PIN_ATTEMPTS - newAttempts} attempts remaining. PIN: $expectedPin",
                            rideSession = current.rideSession.copy(pinAttempts = newAttempts)
                        )
                    }
                }
            }
        }
    }

    /**
     * Handle deposit invoice share from driver (for cross-mint bridge payment).
     * Stores the invoice so it can be used when PIN is verified.
     */
    private fun handleDepositInvoiceShare(action: DriverRideAction.DepositInvoiceShare) {
        Log.d(TAG, "Storing deposit invoice for bridge payment: ${action.invoice.take(20)}... (${action.amount} sats)")
        updateRideSession { copy(driverDepositInvoice = action.invoice) }
    }

    /**
     * Execute cross-mint bridge payment via Lightning.
     * Melts rider's tokens to pay driver's deposit invoice.
     *
     * @param confirmationEventId The ride confirmation event ID
     * @param driverPubKey The driver's public key
     * @param depositInvoice BOLT11 invoice from driver's mint
     */
    private suspend fun executeBridgePayment(
        confirmationEventId: String,
        driverPubKey: String,
        depositInvoice: String
    ) {
        Log.d(TAG, "=== EXECUTING CROSS-MINT BRIDGE ===")
        Log.d(TAG, "  rideId=$confirmationEventId")
        Log.d(TAG, "  invoice=${depositInvoice.take(30)}...")
        _uiState.update { current ->
            current.copy(
                infoMessage = null,
                rideSession = current.rideSession.copy(bridgeInProgress = true)
            )
        }

        try {
            val result = walletService?.bridgePayment(depositInvoice, rideId = confirmationEventId)

            if (result?.success == true) {
                // Log warning if cleanup had issues (payment still succeeded)
                if (result.error != null) {
                    Log.w(TAG, "Bridge payment succeeded with wallet sync warning: ${result.error}")
                }
                Log.d(TAG, "Bridge payment successful: ${result.amountSats} sats + ${result.feesSats} fees")

                // Cancel any pending poll job
                bridgePendingPollJob?.cancel()
                bridgePendingPollJob = null

                // Clear info message and error (mutual exclusivity)
                _uiState.value = _uiState.value.copy(infoMessage = null, error = null)

                // Publish BridgeComplete action to rider ride state
                val bridgeAction = RiderRideStateEvent.createBridgeCompleteAction(
                    preimage = result.preimage ?: "",
                    amountSats = result.amountSats,
                    feesSats = result.feesSats
                )

                val eventId = historyMutex.withLock {
                    riderStateHistory.add(bridgeAction)
                    nostrService.publishRiderRideState(
                        confirmationEventId = confirmationEventId,
                        driverPubKey = driverPubKey,
                        currentPhase = currentRiderPhase,
                        history = riderStateHistory.toList(),
                        lastTransitionId = lastReceivedDriverStateId
                    )
                }

                if (eventId != null) {
                    Log.d(TAG, "Published BridgeComplete action: $eventId")
                    myRideEventIds.add(eventId)
                    _uiState.update { current ->
                        current.copy(
                            infoMessage = null,
                            error = null,
                            rideSession = current.rideSession.copy(
                                bridgeInProgress = false,
                                bridgeComplete = true
                            )
                        )
                    }
                } else {
                    Log.e(TAG, "Failed to publish BridgeComplete action")
                    _uiState.update { current ->
                        current.copy(
                            infoMessage = null, error = null,
                            rideSession = current.rideSession.copy(bridgeInProgress = false)
                        )
                    }
                }
            } else if (result?.isPending == true) {
                // Payment is PENDING - may still complete. Do NOT auto-cancel!
                Log.w(TAG, "Bridge payment PENDING - Lightning still routing. NOT cancelling ride.")

                // Keep spinner showing, but DON'T show info message yet
                // Wait 8 seconds to see if payment resolves before alarming user
                _uiState.update { current ->
                    current.copy(
                        error = null,
                        rideSession = current.rideSession.copy(bridgeInProgress = true)
                    )
                }

                // Delayed info message - only show if still pending after 8 seconds
                val currentRideId = confirmationEventId  // Capture current ride context
                viewModelScope.launch {
                    delay(8000L)  // Wait 8 seconds
                    // Check if still in same ride and bridge still in progress
                    if (_uiState.value.rideSession.bridgeInProgress && _uiState.value.rideSession.confirmationEventId == currentRideId) {
                        _uiState.value = _uiState.value.copy(
                            infoMessage = "Payment routing... Lightning may take a few minutes."
                        )
                    }
                    // If ride completed/cancelled during delay, skip showing message
                }

                // Start polling to resolve pending state
                val bridgePaymentId = walletService?.getInProgressBridgePayments()
                    ?.find { it.rideId == currentRideId }?.id

                if (bridgePaymentId != null) {
                    startBridgePendingPoll(bridgePaymentId, currentRideId, driverPubKey)
                }

                // DO NOT call clearRide() - payment may still complete!
                // User can manually cancel if they want, or wait for driver to cancel
            } else {
                // Actual failure (not pending)
                Log.e(TAG, "Bridge payment failed: ${result?.error} - auto-cancelling ride")
                bridgePendingPollJob?.cancel()
                bridgePendingPollJob = null
                _uiState.update { current ->
                    current.copy(
                        infoMessage = null,
                        error = "Payment failed: ${result?.error ?: "Unknown error"}. Ride cancelled.",
                        rideSession = current.rideSession.copy(bridgeInProgress = false)
                    )
                }
                // Auto-cancel the ride since payment failed
                clearRide()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during bridge payment: ${e.message} - auto-cancelling ride", e)
            bridgePendingPollJob?.cancel()
            bridgePendingPollJob = null
            _uiState.update { current ->
                current.copy(
                    infoMessage = null,
                    error = "Payment failed: ${e.message}. Ride cancelled.",
                    rideSession = current.rideSession.copy(bridgeInProgress = false)
                )
            }
            // Auto-cancel the ride since payment failed
            clearRide()
        }
    }

    /**
     * Start polling to resolve a pending bridge payment.
     * Polls the mint every 30s for up to 10 minutes.
     */
    private fun startBridgePendingPoll(bridgePaymentId: String, rideId: String, driverPubKey: String) {
        bridgePendingPollJob?.cancel()
        bridgePendingPollJob = viewModelScope.launch {
            val startMs = System.currentTimeMillis()
            val timeoutMs = 10 * 60_000L  // 10 minutes
            val pollIntervalMs = 30_000L  // 30 seconds

            while (isActive && System.currentTimeMillis() - startMs < timeoutMs) {
                delay(pollIntervalMs)

                // Check if ride still active
                if (_uiState.value.rideSession.confirmationEventId != rideId) {
                    Log.d(TAG, "Bridge poll: Ride changed, stopping poll")
                    return@launch
                }

                // Get full MeltQuote to access preimage
                val quote = walletService?.checkBridgeMeltQuote(bridgePaymentId)
                Log.d(TAG, "Bridge poll: state=${quote?.state}, preimage=${quote?.paymentPreimage?.take(8)} for payment $bridgePaymentId")

                when (quote?.state) {
                    MeltQuoteState.PAID -> {
                        Log.d(TAG, "Bridge poll: Payment PAID! Triggering success path")
                        handleBridgeSuccessFromPoll(bridgePaymentId, quote.paymentPreimage, driverPubKey)
                        return@launch
                    }
                    MeltQuoteState.UNPAID -> {
                        // Quote expired or failed - update storage status THEN cancel
                        Log.e(TAG, "Bridge poll: Payment UNPAID/expired - cancelling ride")
                        walletService?.walletStorage?.updateBridgePaymentStatus(
                            bridgePaymentId, BridgePaymentStatus.FAILED,
                            errorMessage = "Lightning route expired"
                        )
                        _uiState.update { current ->
                            current.copy(
                                infoMessage = null,
                                error = "Payment failed: Lightning route expired. Ride cancelled.",
                                rideSession = current.rideSession.copy(bridgeInProgress = false)
                            )
                        }
                        clearRide()
                        return@launch
                    }
                    MeltQuoteState.PENDING, null -> {
                        // Still pending or error checking - continue polling
                    }
                }
            }

            // Timeout after 10 minutes - update storage status THEN cancel
            Log.w(TAG, "Bridge poll: Timeout after 10 minutes")
            walletService?.walletStorage?.updateBridgePaymentStatus(
                bridgePaymentId, BridgePaymentStatus.FAILED,
                errorMessage = "Payment timed out after 10 minutes"
            )
            _uiState.update { current ->
                current.copy(
                    infoMessage = null,
                    error = "Payment timed out. Please check your wallet balance. Ride cancelled.",
                    rideSession = current.rideSession.copy(bridgeInProgress = false)
                )
            }
            clearRide()
        }
    }

    /**
     * Handle successful bridge payment detected via polling.
     */
    private suspend fun handleBridgeSuccessFromPoll(bridgePaymentId: String, preimage: String?, driverPubKey: String) {
        // Get the bridge payment details for logging
        val payment = walletService?.getBridgePayment(bridgePaymentId)
        Log.d(TAG, "Bridge payment resolved via poll: ${payment?.amountSats} sats, preimage=${preimage?.take(8)}")

        // Cancel polling job first
        bridgePendingPollJob?.cancel()
        bridgePendingPollJob = null

        // Update bridge payment status to COMPLETE with preimage
        walletService?.walletStorage?.updateBridgePaymentStatus(
            bridgePaymentId, BridgePaymentStatus.COMPLETE,
            lightningPreimage = preimage  // CRITICAL: Store preimage from MeltQuote
        )

        val confirmationId = _uiState.value.rideSession.confirmationEventId ?: return

        // Publish BridgeComplete action (same as normal success path)
        val bridgeAction = RiderRideStateEvent.createBridgeCompleteAction(
            preimage = preimage ?: "",
            amountSats = payment?.amountSats ?: 0,
            feesSats = payment?.feeReserveSats ?: 0
        )

        val eventId = historyMutex.withLock {
            riderStateHistory.add(bridgeAction)
            nostrService.publishRiderRideState(
                confirmationEventId = confirmationId,
                driverPubKey = driverPubKey,
                currentPhase = currentRiderPhase,
                history = riderStateHistory.toList(),
                lastTransitionId = lastReceivedDriverStateId
            )
        }

        if (eventId != null) {
            Log.d(TAG, "Published BridgeComplete action from poll: $eventId")
            myRideEventIds.add(eventId)
            _uiState.update { current ->
                current.copy(
                    infoMessage = null,
                    error = null,
                    rideSession = current.rideSession.copy(
                        bridgeInProgress = false,
                        bridgeComplete = true
                    )
                )
            }
        } else {
            Log.e(TAG, "Failed to publish BridgeComplete action from poll")
            _uiState.update { current ->
                current.copy(
                    infoMessage = null,
                    error = null,
                    rideSession = current.rideSession.copy(bridgeInProgress = false)
                )
            }
        }
    }

    /**
     * Handle driver cancellation (called from driver ride state CANCELLED status).
     * Delegates to the full handleDriverCancellation(reason) for consistent cleanup.
     */
    private fun handleDriverCancellation() {
        // CRITICAL: Delegate to the full cancellation handler for proper cleanup
        // This ensures chatRefreshJob is stopped and all subscriptions are closed,
        // preventing phantom cancellations where old events affect new rides.
        handleDriverCancellation(null)
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
                    RiderActiveService.addAlert(
                        context,
                        AlertType.Chat(chatData.message)
                    )
                    Log.d(TAG, "Chat message received - added to alert stack")
                }
            }
        }

        // Now close old subscription (after new one is active)
        oldSubscriptionId?.let { nostrService.closeSubscription(it) }
    }

    /**
     * Start periodic refresh to ensure messages and status updates are received.
     * Some relays may not push real-time events reliably.
     */
    private fun startChatRefreshJob(confirmationEventId: String) {
        stopChatRefreshJob()
        chatRefreshJob = PeriodicRefreshJob(
            scope = viewModelScope,
            intervalMs = CHAT_REFRESH_INTERVAL_MS,
            onTick = {
                Log.d(TAG, "Refreshing chat and driver state subscriptions for ${confirmationEventId.take(8)}")
                subscribeToChatMessages(confirmationEventId)
                // Refresh driver ride state subscription if we have acceptance data
                _uiState.value.rideSession.acceptance?.driverPubKey?.let { driverPubKey ->
                    subscribeToDriverRideState(confirmationEventId, driverPubKey)
                }
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
     * Start the acceptance timeout.
     * If the driver doesn't accept within the timeout, the offer is cancelled.
     */
    private fun startAcceptanceTimeout() {
        cancelAcceptanceTimeout()
        Log.d(TAG, "Starting acceptance timeout (${ACCEPTANCE_TIMEOUT_MS / 1000}s)")
        acceptanceTimeoutJob = viewModelScope.launch {
            delay(ACCEPTANCE_TIMEOUT_MS)
            handleAcceptanceTimeout()
        }
    }

    /**
     * Cancel the acceptance timeout.
     */
    private fun cancelAcceptanceTimeout() {
        acceptanceTimeoutJob?.cancel()
        acceptanceTimeoutJob = null
    }

    /**
     * Handle acceptance timeout - driver didn't respond in time.
     * Instead of auto-canceling, show timeout options so user can boost fare or keep waiting.
     */
    private fun handleAcceptanceTimeout() {
        val state = _uiState.value
        val session = state.rideSession

        // Only timeout if we're still waiting for acceptance
        if (session.rideStage != RideStage.WAITING_FOR_ACCEPTANCE) {
            Log.d(TAG, "Acceptance timeout ignored - no longer waiting (stage=${session.rideStage})")
            return
        }

        Log.d(TAG, "Direct offer timeout - no response from driver")

        // Don't auto-cancel - let user decide (boost, keep waiting, or cancel)
        // Keep acceptance subscription open in case driver responds late
        _uiState.update { current ->
            current.copy(
                statusMessage = "No response from driver. Boost fare or try again?",
                rideSession = current.rideSession.copy(
                    acceptanceTimeoutStartMs = null,
                    directOfferTimedOut = true
                )
            )
        }
    }

    /**
     * Subscribe to ride cancellation events from the driver.
     */
    private fun subscribeToCancellation(confirmationEventId: String) {
        cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }

        Log.d(TAG, "Subscribing to cancellation events for confirmation: ${confirmationEventId.take(8)}")

        val newSubId = nostrService.subscribeToCancellation(confirmationEventId) { cancellation ->
            // FIRST: Event deduplication - prevents stale queued events from affecting new rides
            if (cancellation.eventId in processedCancellationEventIds) {
                Log.w(TAG, "=== IGNORING ALREADY-PROCESSED CANCELLATION EVENT: ${cancellation.eventId.take(8)} ===")
                return@subscribeToCancellation
            }

            val currentState = _uiState.value
            val currentSession = currentState.rideSession
            val currentConfirmationId = currentSession.confirmationEventId

            // DEBUG: Extensive logging for Kind 3179 cancellation events
            Log.w(TAG, "=== KIND 3179 CANCELLATION RECEIVED ===")
            Log.w(TAG, "  Event ID: ${cancellation.eventId.take(8)}")
            Log.w(TAG, "  Event confirmationEventId: ${cancellation.confirmationEventId}")
            Log.w(TAG, "  Closure confirmationEventId: $confirmationEventId")
            Log.w(TAG, "  Current state confirmationEventId: $currentConfirmationId")
            Log.w(TAG, "  Current rideStage: ${currentSession.rideStage}")
            Log.w(TAG, "  Reason: ${cancellation.reason ?: "none"}")

            // SECOND: Validate the EVENT's confirmation ID matches current ride
            // This is the definitive check - the event itself knows which ride it belongs to
            if (cancellation.confirmationEventId != currentConfirmationId) {
                Log.w(TAG, "  >>> REJECTED: event confId doesn't match current state <<<")
                return@subscribeToCancellation
            }

            // Only process if we're in an active ride
            // CRITICAL: Include DRIVER_ACCEPTED - rider stays in this stage until driver sends EN_ROUTE_PICKUP
            // (AtoB pattern), so driver can cancel during the handshake window
            if (currentSession.rideStage !in listOf(RideStage.DRIVER_ACCEPTED, RideStage.RIDE_CONFIRMED, RideStage.DRIVER_ARRIVED, RideStage.IN_PROGRESS)) {
                Log.w(TAG, "  >>> REJECTED: not in active ride stage <<<")
                return@subscribeToCancellation
            }

            // Mark as processed AFTER validation passes
            processedCancellationEventIds.add(cancellation.eventId)
            Log.w(TAG, "  >>> VALIDATION PASSED - processing cancellation (marked as processed) <<<")
            handleDriverCancellation(cancellation.reason)
        }

        if (newSubId != null) {
            cancellationSubscriptionId = newSubId
            Log.d(TAG, "Cancellation subscription created: $newSubId")
        } else {
            Log.e(TAG, "Failed to create cancellation subscription - not logged in?")
        }
    }

    // ==================== Progressive Location Reveal ====================

    /**
     * Check if driver is close enough to reveal precise pickup location.
     * Called when driver status updates include location.
     */
    private fun checkAndRevealPrecisePickup(confirmationEventId: String, driverLocation: Location) {
        val state = _uiState.value
        val pickup = state.pickupLocation ?: return

        // Already shared precise pickup, nothing to do
        if (state.rideSession.precisePickupShared) return

        // Check if driver is within 1 mile (~1.6 km)
        if (pickup.isWithinMile(driverLocation)) {
            Log.d(TAG, "Driver is within 1 mile! Revealing precise pickup location.")
            revealPrecisePickup(confirmationEventId)
        } else {
            val distanceKm = pickup.distanceToKm(driverLocation)
            Log.d(TAG, "Driver is ${String.format("%.2f", distanceKm)} km away, waiting to reveal precise pickup.")
        }
    }

    /**
     * Send precise pickup location to driver via rider ride state.
     */
    private fun revealPrecisePickup(confirmationEventId: String) {
        val state = _uiState.value
        val pickup = state.pickupLocation ?: return
        val driverPubKey = state.rideSession.acceptance?.driverPubKey ?: return

        viewModelScope.launch {
            val eventId = revealLocation(
                confirmationEventId = confirmationEventId,
                driverPubKey = driverPubKey,
                locationType = RiderRideStateEvent.LocationType.PICKUP,
                location = pickup
            )

            if (eventId != null) {
                Log.d(TAG, "Revealed precise pickup: $eventId")
                myRideEventIds.add(eventId)  // Track for cleanup
                _uiState.update { current ->
                    current.copy(
                        statusMessage = "Precise pickup shared with driver",
                        rideSession = current.rideSession.copy(precisePickupShared = true)
                    )
                }
            } else {
                Log.e(TAG, "Failed to reveal precise pickup")
            }
        }
    }

    /**
     * Send precise destination location to driver via rider ride state.
     * Called after PIN is verified and ride begins.
     */
    private fun revealPreciseDestination(confirmationEventId: String) {
        val state = _uiState.value
        val destination = state.destination ?: return
        val driverPubKey = state.rideSession.acceptance?.driverPubKey ?: return

        // Don't send if already shared
        if (state.rideSession.preciseDestinationShared) return

        viewModelScope.launch {
            val eventId = revealLocation(
                confirmationEventId = confirmationEventId,
                driverPubKey = driverPubKey,
                locationType = RiderRideStateEvent.LocationType.DESTINATION,
                location = destination
            )

            if (eventId != null) {
                Log.d(TAG, "Revealed precise destination: $eventId")
                myRideEventIds.add(eventId)  // Track for cleanup
                updateRideSession { copy(preciseDestinationShared = true) }
            } else {
                Log.e(TAG, "Failed to reveal precise destination")
            }
        }
    }

    /**
     * Handle ride completion from driver.
     */
    private fun handleRideCompletion(statusData: DriverRideStateData) {
        // Close subscriptions and jobs
        closeAllRideSubscriptionsAndJobs()
        clearRiderStateHistory()
        RiderActiveService.stop(getApplication())
        clearSavedRideState()

        // Capture fare info before launching coroutine
        val fareMessage = statusData.finalFare?.let { " Fare: ${it.toInt()} sats" } ?: ""

        // Capture state for ride history before launching coroutine
        val state = _uiState.value
        val session = state.rideSession
        val finalFareSats = statusData.finalFare?.toLong() ?: state.fareEstimate?.toLong() ?: 0L
        val driver = session.selectedDriver
        val driverProfile = driver?.let { state.driverProfiles[it.driverPubKey] }

        // Mark HTLC as claimed by the driver (prevents false refund attempts)
        val paymentHash = session.activePaymentHash
        if (paymentHash != null) {
            val marked = walletService?.markHtlcClaimedByPaymentHash(paymentHash) ?: false
            if (marked) {
                Log.d(TAG, "Marked HTLC escrow as claimed for ride completion")
            }
        }

        viewModelScope.launch {
            // Refresh wallet balance from NIP-60 (ensures consistency after ride)
            try {
                walletService?.refreshBalance()
                Log.d(TAG, "Refreshed wallet balance after ride completion")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh wallet balance after ride: ${e.message}")
            }

            // Save to ride history (rider gets exact coords + addresses for their own history)
            try {
                val historyEntry = RideHistoryEntry(
                    rideId = session.confirmationEventId ?: session.pendingOfferEventId ?: "",
                    timestamp = RideHistoryBuilder.currentTimestampSeconds(),
                    role = "rider",
                    counterpartyPubKey = driver?.driverPubKey ?: session.acceptance?.driverPubKey ?: "",
                    pickupGeohash = state.pickupLocation?.geohash(6) ?: "",  // ~1.2km for compatibility
                    dropoffGeohash = state.destination?.geohash(6) ?: "",
                    // Rider gets exact locations for their own history
                    pickupLat = state.pickupLocation?.lat,
                    pickupLon = state.pickupLocation?.lon,
                    pickupAddress = state.pickupLocation?.addressLabel,
                    dropoffLat = state.destination?.lat,
                    dropoffLon = state.destination?.lon,
                    dropoffAddress = state.destination?.addressLabel,
                    distanceMiles = RideHistoryBuilder.toDistanceMiles(state.routeResult?.distanceKm),
                    durationMinutes = ((state.routeResult?.durationSeconds ?: 0.0) / 60).toInt(),
                    fareSats = finalFareSats,
                    status = "completed",
                    // Driver details for ride history
                    counterpartyFirstName = RideHistoryBuilder.extractCounterpartyFirstName(driverProfile),
                    vehicleMake = driver?.carMake,
                    vehicleModel = driver?.carModel,
                    appOrigin = RideHistoryRepository.APP_ORIGIN_RIDESTR
                )
                rideHistoryRepository.addRide(historyEntry)
                Log.d(TAG, "Saved completed ride to history: ${historyEntry.rideId}")

                // Backup to Nostr (encrypted to self)
                rideHistoryRepository.backupToNostr(nostrService)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save ride to history", e)
            }

            // Clean up all our ride events (NIP-09) - AWAIT before state reset
            cleanupRideEventsInBackground("ride completed")

            // THEN update state after deletion completes
            _uiState.update { current ->
                current.copy(
                    statusMessage = "Ride completed!$fareMessage",
                    rideSession = current.rideSession.copy(rideStage = RideStage.COMPLETED)
                )
            }
        }
    }

    /**
     * Handle ride cancellation from driver.
     */
    private fun handleDriverCancellation(reason: String?) {
        // DEBUG: Log cancellation handling
        Log.w(TAG, "=== HANDLE DRIVER CANCELLATION ===")
        Log.w(TAG, "  Reason: $reason")
        Log.w(TAG, "  Current confirmationEventId: ${_uiState.value.rideSession.confirmationEventId}")
        Log.w(TAG, "  Current rideStage: ${_uiState.value.rideSession.rideStage}")
        Log.w(TAG, "  driverRideStateSubscriptionId: $driverRideStateSubscriptionId")

        // Synchronous cleanup
        closeAllRideSubscriptionsAndJobs()
        clearRiderStateHistory()
        val context = getApplication<Application>()
        RiderActiveService.updateStatus(context, RiderStatus.Cancelled)
        RiderActiveService.stop(context)
        clearSavedRideState()

        // Capture state for ride history BEFORE reset
        val state = _uiState.value
        val session = state.rideSession
        val driver = session.selectedDriver
        val driverProfile = driver?.let { state.driverProfiles[it.driverPubKey] }

        // CRITICAL: Reset ALL ride state SYNCHRONOUSLY
        // This prevents phantom cancellations where delayed events from ride #1
        // could pass validation and affect ride #2.
        val errorMessage = if (reason != null) "Driver cancelled: $reason" else null
        resetRideUiState(
            stage = RideStage.IDLE,
            statusMessage = reason ?: "Driver cancelled the ride",
            error = errorMessage
        )
        Log.w(TAG, "  >>> State reset: all ride fields cleared via resetRideUiState() <<<")

        viewModelScope.launch {
            // Refresh wallet balance from NIP-60 (ensures consistency after cancellation)
            // This also checks for expired HTLCs that can be refunded
            try {
                walletService?.refreshBalance()
                Log.d(TAG, "Refreshed wallet balance after ride cancellation")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh wallet balance after cancellation: ${e.message}")
            }

            // Save cancelled ride to history (only if ride was confirmed/in progress)
            if (session.confirmationEventId != null || session.acceptance != null) {
                try {
                    val historyEntry = RideHistoryEntry(
                        rideId = session.confirmationEventId ?: session.pendingOfferEventId ?: "",
                        timestamp = RideHistoryBuilder.currentTimestampSeconds(),
                        role = "rider",
                        counterpartyPubKey = driver?.driverPubKey ?: session.acceptance?.driverPubKey ?: "",
                        pickupGeohash = state.pickupLocation?.geohash(6) ?: "",
                        dropoffGeohash = state.destination?.geohash(6) ?: "",
                        // Rider gets exact locations for their own history
                        pickupLat = state.pickupLocation?.lat,
                        pickupLon = state.pickupLocation?.lon,
                        pickupAddress = state.pickupLocation?.addressLabel,
                        dropoffLat = state.destination?.lat,
                        dropoffLon = state.destination?.lon,
                        dropoffAddress = state.destination?.addressLabel,
                        distanceMiles = RideHistoryBuilder.toDistanceMiles(state.routeResult?.distanceKm),
                        durationMinutes = 0,  // Ride was cancelled, no actual duration
                        fareSats = 0,  // No fare charged for cancelled ride
                        status = "cancelled",
                        // Driver details for ride history
                        counterpartyFirstName = RideHistoryBuilder.extractCounterpartyFirstName(driverProfile),
                        vehicleMake = driver?.carMake,
                        vehicleModel = driver?.carModel,
                        appOrigin = RideHistoryRepository.APP_ORIGIN_RIDESTR
                    )
                    rideHistoryRepository.addRide(historyEntry)
                    Log.d(TAG, "Saved cancelled ride to history: ${historyEntry.rideId}")

                    // Backup to Nostr (encrypted to self)
                    rideHistoryRepository.backupToNostr(nostrService)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to save cancelled ride to history", e)
                }
            }

            // Clean up all our ride events (NIP-09)
            cleanupRideEventsInBackground("driver cancelled")

            // Only resubscribe if still in IDLE (no new ride started)
            val currentState = _uiState.value
            if (currentState.rideSession.rideStage == RideStage.IDLE && currentState.rideSession.confirmationEventId == null) {
                resubscribeToDrivers(clearExisting = false)
            } else {
                Log.d(TAG, "Skipping resubscribe - new ride already started (stage=${currentState.rideSession.rideStage})")
            }
        }
    }

    /**
     * Handle driver arrived at pickup location.
     */
    private fun handleDriverArrived() {
        // Reject stale events if we've already reached or passed DRIVER_ARRIVED stage
        // This prevents Nostr relay cached events from reverting state after PIN verification
        if (_uiState.value.rideSession.rideStage in listOf(
                RideStage.DRIVER_ARRIVED,
                RideStage.IN_PROGRESS,
                RideStage.COMPLETED
            )) {
            Log.d(TAG, "Stage already at or past DRIVER_ARRIVED (${_uiState.value.rideSession.rideStage}), ignoring stale event")
            return
        }

        val context = getApplication<Application>()
        val driverPubKey = _uiState.value.rideSession.acceptance?.driverPubKey
        val driverName = driverPubKey?.let { _uiState.value.driverProfiles[it]?.bestName()?.split(" ")?.firstOrNull() }

        // Update service - handles notification update and sound
        RiderActiveService.updateStatus(context, RiderStatus.DriverArrived(driverName))

        // Update UI state
        _uiState.update { current ->
            current.copy(
                statusMessage = "Driver has arrived at pickup!",
                rideSession = current.rideSession.copy(rideStage = RideStage.DRIVER_ARRIVED)
            )
        }

        Log.d(TAG, "Driver arrived - service notified")
    }

    /**
     * Send a chat message to the driver.
     */
    fun sendChatMessage(message: String) {
        val state = _uiState.value
        val confirmationEventId = state.rideSession.confirmationEventId ?: return
        val driverPubKey = state.rideSession.acceptance?.driverPubKey ?: return

        if (message.isBlank()) return

        viewModelScope.launch {
            updateRideSession { copy(isSendingMessage = true) }

            val eventId = nostrService.sendChatMessage(
                confirmationEventId = confirmationEventId,
                recipientPubKey = driverPubKey,
                message = message
            )

            if (eventId != null) {
                Log.d(TAG, "Sent chat message: $eventId")
                myRideEventIds.add(eventId)  // Track for cleanup
                // Note: We'll see our own message when we receive it via subscription
                // due to how gift wrapping works (addressed to recipient, not sender)
                // For immediate feedback, add it to local state
                val myPubKey = nostrService.getPubKeyHex() ?: ""

                val localMessage = RideshareChatData(
                    eventId = eventId,
                    senderPubKey = myPubKey,
                    confirmationEventId = confirmationEventId,
                    recipientPubKey = driverPubKey,
                    message = message,
                    createdAt = System.currentTimeMillis() / 1000
                )

                val currentMessages = _uiState.value.rideSession.chatMessages.toMutableList()
                currentMessages.add(localMessage)
                currentMessages.sortBy { it.createdAt }

                updateRideSession { copy(isSendingMessage = false, chatMessages = currentMessages) }

                // Persist messages for app restart
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

    /**
     * Check if rider and driver have at least one compatible payment method.
     * Used to filter drivers in the available list.
     */
    private fun isPaymentCompatible(
        riderMethods: List<String>,
        driverMethods: List<String>
    ): Boolean {
        // If either list is empty, assume compatibility (fallback to default cashu)
        if (riderMethods.isEmpty() || driverMethods.isEmpty()) return true
        return riderMethods.any { it in driverMethods }
    }

    private fun calculateRouteIfReady() {
        val state = _uiState.value
        val pickup = state.pickupLocation ?: return
        val destination = state.destination ?: return

        if (!state.isRoutingReady) {
            Log.w(TAG, "Routing not ready yet")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isCalculatingRoute = true)

            // Find tile coverage for pickup location
            val tileSource = tileManager.getTileForLocation(pickup.lat, pickup.lon)

            if (tileSource == null) {
                Log.w(TAG, "No tile coverage for location: ${pickup.lat}, ${pickup.lon}")
                _uiState.value = _uiState.value.copy(
                    isCalculatingRoute = false,
                    error = "No routing data for this area. Go to Settings > Routing Tiles to download."
                )
                return@launch
            }

            // Initialize tiles if region changed
            val regionId = tileSource.region.id
            if (currentTileRegion != regionId) {
                Log.d(TAG, "Switching to tile region: $regionId")

                val initialized = routingService.initializeWithTileSource(tileSource)
                if (!initialized) {
                    Log.e(TAG, "Failed to initialize tiles for region: $regionId")
                    _uiState.value = _uiState.value.copy(
                        isCalculatingRoute = false,
                        error = "Failed to load routing data for ${tileSource.region.name}"
                    )
                    return@launch
                }
                currentTileRegion = regionId
            }

            val result = routingService.calculateRoute(
                originLat = pickup.lat,
                originLon = pickup.lon,
                destLat = destination.lat,
                destLon = destination.lon
            )

            if (result != null) {
                val fareEstimate = calculateFare(result)
                val fareEstimateWithFees = fareEstimate * (1 + FEE_BUFFER_PERCENT)
                _uiState.value = _uiState.value.copy(
                    isCalculatingRoute = false,
                    routeResult = result,
                    fareEstimate = fareEstimate,
                    fareEstimateWithFees = fareEstimateWithFees
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isCalculatingRoute = false,
                    error = "Failed to calculate route"
                )
            }
        }
    }

    private fun calculateFare(route: RouteResult): Double {
        // Convert km to miles
        val distanceMiles = route.distanceKm * KM_TO_MILES

        // Get fare rates from remote config (admin settings)
        val config = remoteConfigManager.config.value
        val farePerMile = config.fareRateUsdPerMile
        val minimumFare = config.minimumFareUsd

        // Calculate fare in USD
        val distanceBasedFare = distanceMiles * farePerMile

        // Enforce minimum fare to ensure driver profitability on short rides
        val fareUsd = maxOf(distanceBasedFare, minimumFare)

        // Convert USD to sats using current BTC price
        val sats = bitcoinPriceService.usdToSats(fareUsd)

        // Return sats, or fallback if price unavailable
        // For fallback, calculate minimum based on config minimum fare (assuming ~$100k BTC)
        val minimumFallbackSats = minimumFare * 1000.0  // $1 = ~1000 sats at $100k BTC
        return sats?.toDouble() ?: maxOf(distanceMiles * FALLBACK_SATS_PER_MILE, minimumFallbackSats)
    }

    /**
     * Get the fare boost amount based on currency setting.
     * USD mode: $1 converted to sats
     * SATS mode: 1000 sats
     *
     * Note: Reads directly from SharedPreferences to ensure we get the current setting,
     * since the ViewModel's SettingsManager instance may be out of sync with the UI's instance.
     */
    private fun getBoostAmount(): Double {
        // Read currency setting directly from SharedPreferences to get current value
        val settingsPrefs = getApplication<Application>().getSharedPreferences("ridestr_settings", Context.MODE_PRIVATE)
        val currencyName = settingsPrefs.getString("display_currency", DisplayCurrency.USD.name) ?: DisplayCurrency.USD.name
        val currency = try {
            DisplayCurrency.valueOf(currencyName)
        } catch (e: IllegalArgumentException) {
            DisplayCurrency.USD
        }

        return when (currency) {
            DisplayCurrency.USD -> {
                // $1 boost, convert to sats
                bitcoinPriceService.usdToSats(FARE_BOOST_USD)?.toDouble() ?: FARE_BOOST_SATS
            }
            DisplayCurrency.SATS -> FARE_BOOST_SATS
        }
    }

    override fun onCleared() {
        super.onCleared()
        staleDriverCleanupJob?.cancel()
        chatRefreshJob?.stop()
        acceptanceTimeoutJob?.cancel()
        broadcastTimeoutJob?.cancel()
        driverSubscriptionId?.let { nostrService.closeSubscription(it) }
        acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }
        selectedDriverAvailabilitySubId?.let { nostrService.closeSubscription(it) }
        driverRideStateSubscriptionId?.let { nostrService.closeSubscription(it) }
        chatSubscriptionId?.let { nostrService.closeSubscription(it) }
        cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
        // Close all profile subscriptions
        profileSubscriptionIds.values.forEach { subId ->
            nostrService.closeSubscription(subId)
        }
        profileSubscriptionIds.clear()
        nostrService.disconnect()
        // Clean up Bitcoin price service
        bitcoinPriceService.cleanup()
    }

    // ============ Saved Locations (Favorites & Recents) ============

    /**
     * Get pinned favorite locations.
     */
    @Composable
    fun getFavorites(): List<SavedLocation> {
        val locations by savedLocationRepository.savedLocations.collectAsState()
        return locations.filter { it.isPinned }.sortedBy { it.nickname ?: it.displayName }
    }

    /**
     * Get recent (non-pinned) locations, sorted by most recent first.
     */
    @Composable
    fun getRecents(): List<SavedLocation> {
        val locations by savedLocationRepository.savedLocations.collectAsState()
        return locations.filter { !it.isPinned }.sortedByDescending { it.timestampMs }
    }

    /**
     * Pin a location as a favorite with optional nickname.
     */
    fun pinWithNickname(id: String, nickname: String?) {
        savedLocationRepository.pinAsFavorite(id, nickname)
    }

    /**
     * Update nickname for an already pinned favorite.
     */
    fun updateFavoriteNickname(id: String, nickname: String?) {
        savedLocationRepository.pinAsFavorite(id, nickname)
    }

    /**
     * Unpin a favorite (convert back to recent).
     */
    fun unpinFavorite(id: String) {
        savedLocationRepository.unpinFavorite(id)
    }

    /**
     * Remove a saved location entirely.
     */
    fun removeSavedLocation(id: String) {
        savedLocationRepository.removeLocation(id)
    }

    /**
     * Swap pickup and destination addresses.
     */
    fun swapAddresses() {
        val current = _uiState.value
        val newPickup = current.destination
        val newDest = current.pickupLocation

        _uiState.value = current.copy(
            pickupLocation = newPickup,
            destination = newDest,
            routeResult = null,
            fareEstimate = null,
            fareEstimateWithFees = null
        )

        // Recalculate route if both addresses are now set
        if (newPickup != null && newDest != null) {
            calculateRouteIfReady()
        }
    }
}

/**
 * Stages of a ride from rider's perspective.
 */
enum class RideStage {
    IDLE,                   // No active ride
    BROADCASTING_REQUEST,   // Broadcasting request, waiting for any driver (new flow)
    WAITING_FOR_ACCEPTANCE, // Direct offer sent, waiting for specific driver (legacy/advanced)
    DRIVER_ACCEPTED,        // Driver accepted, need to confirm
    RIDE_CONFIRMED,         // Ride confirmed, driver on the way
    DRIVER_ARRIVED,         // Driver has arrived at pickup location
    IN_PROGRESS,            // Currently in the ride (PIN verified)
    COMPLETED               // Ride completed
}

/**
 * Ride-scoped state fields that are reset together when a ride ends.
 * Any new field added here is automatically included in reset (via RiderRideSession() default).
 */
data class RiderRideSession(
    // Ride stage
    val rideStage: RideStage = RideStage.IDLE,

    // Ride identification
    val pendingOfferEventId: String? = null,
    val acceptance: RideAcceptanceData? = null,
    val confirmationEventId: String? = null,
    val selectedDriver: DriverAvailabilityData? = null,

    // Offer state
    val isSendingOffer: Boolean = false,
    val isConfirmingRide: Boolean = false,

    // Broadcast timeout state
    val broadcastStartTimeMs: Long? = null,
    val broadcastTimedOut: Boolean = false,
    val totalBoostSats: Double = 0.0,

    // Direct offer timeout state
    val acceptanceTimeoutStartMs: Long? = null,
    val directOfferBoostSats: Double = 0.0,
    val directOfferTimedOut: Boolean = false,

    // PIN verification
    val pickupPin: String? = null,
    val pinAttempts: Int = 0,
    val pinVerified: Boolean = false,

    // Chat
    val chatMessages: List<RideshareChatData> = emptyList(),
    val isSendingMessage: Boolean = false,

    // Progressive location reveal
    val precisePickupShared: Boolean = false,
    val preciseDestinationShared: Boolean = false,
    val driverLocation: Location? = null,

    // Driver state
    val lastDriverStatus: String? = null,

    // HTLC Escrow
    val activePreimage: String? = null,
    val activePaymentHash: String? = null,
    val escrowToken: String? = null,
    val preimageShared: Boolean = false,

    // Multi-mint payment
    val paymentPath: PaymentPath = PaymentPath.NO_PAYMENT,
    val driverMintUrl: String? = null,
    val driverDepositInvoice: String? = null,
    val bridgeInProgress: Boolean = false,
    val bridgeComplete: Boolean = false,

    // Driver availability dialog
    val showDriverUnavailableDialog: Boolean = false,

    // Cancel warning dialog
    val showCancelWarningDialog: Boolean = false,

    // RoadFlare target tracking
    val roadflareTargetDriverPubKey: String? = null,
    val roadflareTargetDriverLocation: Location? = null
)

/**
 * UI state for rider mode.
 */
data class RiderUiState(
    // Ride session (all ride-scoped fields — reset together via RiderRideSession())
    val rideSession: RiderRideSession = RiderRideSession(),

    // Available drivers
    val availableDrivers: List<DriverAvailabilityData> = emptyList(),
    val driverProfiles: Map<String, UserProfile> = emptyMap(),
    val expandedSearch: Boolean = true,  // Default to 20+ mile radius for better driver coverage
    val nearbyDriverCount: Int = 0,               // Count of nearby drivers (for display)

    // Route information
    val pickupLocation: Location? = null,
    val destination: Location? = null,
    val routeResult: RouteResult? = null,
    val fareEstimate: Double? = null,             // Actual fare (sent to driver in offer)
    val fareEstimateWithFees: Double? = null,     // Displayed fare (fare + 2% buffer for cross-mint fees)
    val isCalculatingRoute: Boolean = false,
    val isRoutingReady: Boolean = false,

    // Broadcast/acceptance timeout durations (configuration, not ride-scoped)
    val broadcastTimeoutDurationMs: Long = 120_000L,
    val acceptanceTimeoutDurationMs: Long = 15_000L,

    // User identity
    val myPubKey: String = "",

    // Insufficient funds dialog
    val showInsufficientFundsDialog: Boolean = false,
    val insufficientFundsAmount: Long = 0,          // How many more sats needed (display only)
    val depositAmountNeeded: Long = 0,              // Amount to deposit (includes 2% fee buffer)
    val insufficientFundsIsRoadflare: Boolean = false, // True when from RoadFlare offer path
    val pendingRoadflareDriverPubKey: String? = null,   // Driver pubkey for deferred RoadFlare offer
    val pendingRoadflareDriverLocation: Location? = null, // Driver location for deferred RoadFlare offer
    val showAlternatePaymentSetupDialog: Boolean = false, // Show dialog to set alternate payment methods

    // UI
    val statusMessage: String = "Find available drivers",
    val error: String? = null,
    val infoMessage: String? = null  // Non-error status messages (neutral styling)
)
