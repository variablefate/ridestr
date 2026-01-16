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
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.events.RideAcceptanceData
import com.ridestr.common.nostr.events.RiderRideAction
import com.ridestr.common.nostr.events.RiderRideStateEvent
import com.ridestr.common.nostr.events.RideshareChatData
import com.ridestr.common.nostr.events.UserProfile
import com.ridestr.common.nostr.events.geohash
import com.ridestr.rider.service.RiderActiveService
import com.ridestr.rider.service.RiderStatus
import com.ridestr.rider.service.StackableAlert
import kotlin.random.Random
import com.ridestr.common.bitcoin.BitcoinPriceService
import com.ridestr.common.routing.RouteResult
import com.ridestr.common.routing.TileManager
import com.ridestr.common.routing.TileSource
import com.ridestr.common.settings.DisplayCurrency
import com.ridestr.common.settings.SettingsManager
import com.ridestr.common.routing.ValhallaRoutingService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * ViewModel for Rider mode.
 * Manages available drivers, route calculation, and ride requests.
 */
class RiderViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "RiderViewModel"
        // Fare pricing in USD
        private const val FARE_USD_PER_MILE = 1.85  // $1.85 per mile (competitive with Uber/Lyft)
        private const val MINIMUM_FARE_USD = 5.0    // $5.00 minimum fare to ensure driver profitability
        // Fare boost amounts
        private const val FARE_BOOST_USD = 1.0     // $1 boost in USD mode
        private const val FARE_BOOST_SATS = 1000.0 // 1000 sats boost in sats mode
        // Fallback sats per mile when BTC price unavailable (assumes ~$100k BTC)
        private const val FALLBACK_SATS_PER_MILE = 2000.0
        // Conversion factor: 1 km = 0.621371 miles
        private const val KM_TO_MILES = 0.621371
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

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val nostrService = NostrService(application)
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
    val bitcoinPriceService = BitcoinPriceService()

    private val _uiState = MutableStateFlow(RiderUiState())
    val uiState: StateFlow<RiderUiState> = _uiState.asStateFlow()

    private var driverSubscriptionId: String? = null
    private var acceptanceSubscriptionId: String? = null
    private var chatSubscriptionId: String? = null
    private var cancellationSubscriptionId: String? = null
    // Driver ride state subscription (replaces pinSubmissionSubscriptionId and statusSubscriptionId)
    private var driverRideStateSubscriptionId: String? = null
    private var staleDriverCleanupJob: Job? = null
    private var chatRefreshJob: Job? = null
    private var acceptanceTimeoutJob: Job? = null
    private var broadcastTimeoutJob: Job? = null
    private val profileSubscriptionIds = mutableMapOf<String, String>()
    private var currentSubscriptionGeohash: String? = null
    // First-acceptance-wins flag for broadcast mode
    private var hasAcceptedDriver: Boolean = false

    // ALL events I publish during a ride (for NIP-09 deletion on completion/cancellation)
    private val myRideEventIds = mutableListOf<String>()

    // Rider ride state history for consolidated Kind 30181 events
    // This accumulates all rider actions during a ride (location reveals, PIN verifications)
    private val riderStateHistory = mutableListOf<RiderRideAction>()

    // Track how many driver actions we've processed (to detect new actions)
    private var lastProcessedDriverActionCount = 0

    // Event deduplication sets - prevents stale events from affecting new rides
    // These track processed event IDs to avoid re-processing queued events from closed subscriptions
    private val processedDriverStateEventIds = mutableSetOf<String>()
    private val processedCancellationEventIds = mutableSetOf<String>()

    // Current phase for rider ride state
    private var currentRiderPhase = RiderRideStateEvent.Phase.AWAITING_DRIVER

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
        riderStateHistory.add(locationAction)

        // Publish consolidated rider ride state
        return nostrService.publishRiderRideState(
            confirmationEventId = confirmationEventId,
            driverPubKey = driverPubKey,
            currentPhase = currentRiderPhase,
            history = riderStateHistory.toList()
        )
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
        riderStateHistory.add(pinAction)

        // Update phase based on verification result
        if (verified) {
            currentRiderPhase = RiderRideStateEvent.Phase.VERIFIED
        }

        // Publish consolidated rider ride state
        return nostrService.publishRiderRideState(
            confirmationEventId = confirmationEventId,
            driverPubKey = driverPubKey,
            currentPhase = currentRiderPhase,
            history = riderStateHistory.toList()
        )
    }

    /**
     * Clear rider state history (called when ride ends or is cancelled).
     * Also clears event deduplication sets to allow fresh events for new rides.
     */
    private fun clearRiderStateHistory() {
        riderStateHistory.clear()
        lastProcessedDriverActionCount = 0
        currentRiderPhase = RiderRideStateEvent.Phase.AWAITING_DRIVER
        // Clear deduplication sets so new rides can process fresh events
        processedDriverStateEventIds.clear()
        processedCancellationEventIds.clear()
        Log.d(TAG, "Cleared rider state history and event deduplication sets")
    }

    init {
        // Connect to relays and subscribe to drivers
        nostrService.connect()
        subscribeToDrivers()
        startStaleDriverCleanup()
        // Start Bitcoin price auto-refresh (every 5 minutes)
        bitcoinPriceService.startAutoRefresh()

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
        val confId = state.confirmationEventId
        val acceptance = state.acceptance
        if (confId != null && state.rideStage in listOf(
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

        // Only save if we're in an active ride
        if (state.rideStage == RideStage.IDLE || state.rideStage == RideStage.COMPLETED) {
            clearSavedRideState()
            return
        }

        val acceptance = state.acceptance
        val pickup = state.pickupLocation
        val destination = state.destination

        if (acceptance == null || pickup == null || destination == null) return

        try {
            val json = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("stage", state.rideStage.name)
                put("pendingOfferEventId", state.pendingOfferEventId)
                put("confirmationEventId", state.confirmationEventId)
                put("pickupPin", state.pickupPin)
                put("pinAttempts", state.pinAttempts)
                put("pinVerified", state.pinVerified)
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
            }

            prefs.edit().putString(KEY_RIDE_STATE, json.toString()).apply()
            Log.d(TAG, "Saved ride state: stage=${state.rideStage}, messages=${state.chatMessages.size}")
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

            Log.d(TAG, "Restoring ride state: stage=$stage, confirmationId=$confirmationEventId, messages=${chatMessages.size}")

            // Restore state
            _uiState.value = _uiState.value.copy(
                rideStage = stage,
                pickupLocation = pickup,
                destination = destination,
                fareEstimate = fareEstimate,
                pendingOfferEventId = pendingOfferEventId,
                acceptance = acceptance,
                confirmationEventId = confirmationEventId,
                pickupPin = pickupPin,
                pinAttempts = pinAttempts,
                pinVerified = pinVerified,
                chatMessages = chatMessages,
                statusMessage = getStatusMessageForStage(stage, pickupPin)
            )

            // Re-subscribe to relevant events
            if (confirmationEventId != null) {
                subscribeToChatMessages(confirmationEventId)
                subscribeToCancellation(confirmationEventId)
                subscribeToDriverRideState(confirmationEventId, acceptance.driverPubKey)
                // Start periodic chat refresh
                startChatRefreshJob(confirmationEventId)
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
            fareEstimate = null
        )

        // If geohash changed significantly, resubscribe to get local drivers
        if (newGeohash != currentSubscriptionGeohash) {
            Log.d(TAG, "Pickup location geohash changed: $currentSubscriptionGeohash -> $newGeohash")
            resubscribeToDrivers()
        }

        calculateRouteIfReady()
    }

    /**
     * Resubscribe to drivers with updated location filter.
     */
    private fun resubscribeToDrivers() {
        // Close existing subscription
        driverSubscriptionId?.let { nostrService.closeSubscription(it) }

        // Clear current drivers since we're changing regions
        _uiState.value = _uiState.value.copy(
            availableDrivers = emptyList(),
            selectedDriver = null,
            driverProfiles = emptyMap(),
            nearbyDriverCount = 0  // Reset counter - will be updated by new subscription callbacks
        )

        // Close profile subscriptions
        profileSubscriptionIds.values.forEach { nostrService.closeSubscription(it) }
        profileSubscriptionIds.clear()

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
            fareEstimate = null
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
            fareEstimate = null
        )

        // If geohash changed significantly, resubscribe to get local drivers
        if (newGeohash != currentSubscriptionGeohash) {
            Log.d(TAG, "Pickup location geohash changed: $currentSubscriptionGeohash -> $newGeohash")
            resubscribeToDrivers()
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
            fareEstimate = null
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
            fareEstimate = null
        )
    }

    /**
     * Clear destination location.
     */
    fun clearDestination() {
        _uiState.value = _uiState.value.copy(
            destination = null,
            routeResult = null,
            fareEstimate = null
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
        _uiState.value = _uiState.value.copy(selectedDriver = driver)
    }

    /**
     * Clear selected driver.
     */
    fun clearSelectedDriver() {
        _uiState.value = _uiState.value.copy(selectedDriver = null)
    }

    /**
     * Send a ride offer to the selected driver.
     * Pre-calculates driver→pickup route so driver sees accurate metrics immediately.
     */
    fun sendRideOffer() {
        val state = _uiState.value
        val driver = state.selectedDriver ?: return
        val pickup = state.pickupLocation ?: return
        val destination = state.destination ?: return
        val fareEstimate = state.fareEstimate ?: return
        val rideRoute = state.routeResult  // Already calculated pickup→destination route

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSendingOffer = true)

            // Calculate driver→pickup route for accurate metrics on driver's card
            val pickupRoute = if (routingService.isReady()) {
                routingService.calculateRoute(
                    originLat = driver.approxLocation.lat,
                    originLon = driver.approxLocation.lon,
                    destLat = pickup.lat,
                    destLon = pickup.lon
                )
            } else null

            Log.d(TAG, "Pre-calculated routes for direct offer: " +
                "pickup=${pickupRoute?.distanceKm}km, ride=${rideRoute?.distanceKm}km")

            val eventId = nostrService.sendRideOffer(
                driverAvailability = driver,
                pickup = pickup,
                destination = destination,
                fareEstimate = fareEstimate,
                pickupRouteKm = pickupRoute?.distanceKm,
                pickupRouteMin = pickupRoute?.let { it.durationSeconds / 60.0 },
                rideRouteKm = rideRoute?.distanceKm,
                rideRouteMin = rideRoute?.let { it.durationSeconds / 60.0 }
            )

            if (eventId != null) {
                Log.d(TAG, "Sent ride offer: $eventId")
                myRideEventIds.add(eventId)  // Track for cleanup
                // Subscribe to acceptance for this offer
                subscribeToAcceptance(eventId)
                // Start timeout for acceptance
                startAcceptanceTimeout()
                _uiState.value = _uiState.value.copy(
                    isSendingOffer = false,
                    pendingOfferEventId = eventId,
                    rideStage = RideStage.WAITING_FOR_ACCEPTANCE,
                    acceptanceTimeoutStartMs = System.currentTimeMillis(),
                    statusMessage = "Waiting for driver to accept..."
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isSendingOffer = false,
                    error = "Failed to send ride offer"
                )
            }
        }
    }

    /**
     * Cancel the pending ride offer.
     */
    fun cancelOffer() {
        // Cancel the acceptance timeout
        cancelAcceptanceTimeout()

        acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }
        acceptanceSubscriptionId = null

        viewModelScope.launch {
            // Clean up all our ride events (NIP-09) - AWAIT before state reset
            cleanupRideEventsInBackground("offer cancelled")

            // THEN update state after deletion completes
            _uiState.value = _uiState.value.copy(
                pendingOfferEventId = null,
                rideStage = RideStage.IDLE,
                acceptanceTimeoutStartMs = null,
                directOfferBoostSats = 0.0,
                statusMessage = "Offer cancelled"
            )

            // Resubscribe to get fresh driver list
            resubscribeToDrivers()

            // Clear persisted ride state (in case any was saved)
            clearSavedRideState()
        }
    }

    /**
     * Boost the fare on a direct offer and resend to the same driver.
     * Includes pre-calculated route metrics.
     */
    fun boostDirectOffer() {
        val state = _uiState.value
        val driver = state.selectedDriver ?: return
        val currentFare = state.fareEstimate ?: return
        val pickup = state.pickupLocation ?: return
        val destination = state.destination ?: return
        val rideRoute = state.routeResult  // Already calculated pickup→destination route
        val boostAmount = getBoostAmount()
        val newFare = currentFare + boostAmount

        viewModelScope.launch {
            // Cancel current timeout
            cancelAcceptanceTimeout()

            // Delete old offer
            state.pendingOfferEventId?.let { offerId ->
                Log.d(TAG, "Deleting old direct offer before boost: $offerId")
                nostrService.deleteEvent(offerId, "fare boosted")
            }

            // Close old acceptance subscription
            acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }
            acceptanceSubscriptionId = null

            // Update fare in state - reset timeout flag since we're boosting
            _uiState.value = _uiState.value.copy(
                fareEstimate = newFare,
                directOfferBoostSats = state.directOfferBoostSats + boostAmount,
                pendingOfferEventId = null,
                directOfferTimedOut = false,
                isSendingOffer = true
            )

            Log.d(TAG, "Boosting direct offer fare from $currentFare to $newFare sats (total boost: ${state.directOfferBoostSats + boostAmount} sats)")

            // Calculate driver→pickup route for accurate metrics
            val pickupRoute = if (routingService.isReady()) {
                routingService.calculateRoute(
                    originLat = driver.approxLocation.lat,
                    originLon = driver.approxLocation.lon,
                    destLat = pickup.lat,
                    destLon = pickup.lon
                )
            } else null

            // Resend offer to same driver with new fare and route metrics
            val eventId = nostrService.sendRideOffer(
                driverAvailability = driver,
                pickup = pickup,
                destination = destination,
                fareEstimate = newFare,
                pickupRouteKm = pickupRoute?.distanceKm,
                pickupRouteMin = pickupRoute?.let { it.durationSeconds / 60.0 },
                rideRouteKm = rideRoute?.distanceKm,
                rideRouteMin = rideRoute?.let { it.durationSeconds / 60.0 }
            )

            if (eventId != null) {
                Log.d(TAG, "Sent boosted ride offer: $eventId")
                myRideEventIds.add(eventId)  // Track for cleanup
                subscribeToAcceptance(eventId)
                startAcceptanceTimeout()
                _uiState.value = _uiState.value.copy(
                    isSendingOffer = false,
                    pendingOfferEventId = eventId,
                    acceptanceTimeoutStartMs = System.currentTimeMillis(),
                    statusMessage = "Waiting for driver to accept boosted offer..."
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isSendingOffer = false,
                    error = "Failed to resend boosted offer"
                )
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
        _uiState.value = _uiState.value.copy(
            acceptanceTimeoutStartMs = System.currentTimeMillis(),
            directOfferTimedOut = false,
            statusMessage = "Continuing to wait for driver..."
        )

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

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSendingOffer = true)

            // Send APPROXIMATE locations for privacy in broadcast
            // Precise locations revealed progressively when driver is close
            val approxPickup = pickup.approximate()
            val approxDestination = destination.approximate()
            Log.d(TAG, "Broadcasting with approximate locations - pickup: ${approxPickup.lat},${approxPickup.lon}, dest: ${approxDestination.lat},${approxDestination.lon}")

            val eventId = nostrService.broadcastRideRequest(
                pickup = approxPickup,
                destination = approxDestination,
                fareEstimate = fareEstimate,
                routeDistanceKm = routeResult.distanceKm,
                routeDurationMin = routeResult.durationSeconds / 60.0
            )

            if (eventId != null) {
                Log.d(TAG, "Broadcast ride request: $eventId")
                myRideEventIds.add(eventId)  // Track for cleanup
                // Reset first-acceptance flag
                hasAcceptedDriver = false
                // Subscribe to acceptances from any driver
                subscribeToAcceptancesForBroadcast(eventId)
                // Start 2-minute timeout
                startBroadcastTimeout()

                // Start foreground service to keep app alive while searching
                RiderActiveService.startSearching(getApplication())

                _uiState.value = _uiState.value.copy(
                    isSendingOffer = false,
                    pendingOfferEventId = eventId,
                    rideStage = RideStage.BROADCASTING_REQUEST,
                    broadcastStartTimeMs = System.currentTimeMillis(),
                    broadcastTimedOut = false,
                    statusMessage = "Searching for drivers..."
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isSendingOffer = false,
                    error = "Failed to broadcast ride request"
                )
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
        val currentFare = state.fareEstimate ?: return
        val boostAmount = getBoostAmount()
        val newFare = currentFare + boostAmount

        viewModelScope.launch {
            // Cancel current timeout
            cancelBroadcastTimeout()

            // Delete old offer
            state.pendingOfferEventId?.let { offerId ->
                Log.d(TAG, "Deleting old offer before boost: $offerId")
                nostrService.deleteEvent(offerId, "fare boosted")
            }

            // Close old acceptance subscription
            acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }
            acceptanceSubscriptionId = null

            // Update fare in state - store actual sats boosted (not count)
            // Reset broadcastTimedOut AND broadcastStartTimeMs to prevent race condition with UI
            // The explicit null reset ensures LaunchedEffect properly restarts when new time is set
            _uiState.value = _uiState.value.copy(
                fareEstimate = newFare,
                totalBoostSats = state.totalBoostSats + boostAmount,
                pendingOfferEventId = null,
                broadcastTimedOut = false,
                broadcastStartTimeMs = null  // Explicit reset for clean timer restart
            )

            // Re-broadcast with new fare
            Log.d(TAG, "Boosting fare from $currentFare to $newFare sats (total boost: ${state.totalBoostSats + boostAmount} sats)")
            broadcastRideRequest()
        }
    }

    /**
     * Cancel the broadcast ride request.
     */
    fun cancelBroadcastRequest() {
        // Cancel the timeout
        cancelBroadcastTimeout()

        // Close acceptance subscription
        acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }
        acceptanceSubscriptionId = null

        // Stop foreground service
        RiderActiveService.stop(getApplication())

        viewModelScope.launch {
            // Clean up all our ride events (NIP-09) - AWAIT before state reset
            cleanupRideEventsInBackground("request cancelled")

            // THEN update state after deletion completes
            _uiState.value = _uiState.value.copy(
                pendingOfferEventId = null,
                rideStage = RideStage.IDLE,
                broadcastStartTimeMs = null,
                totalBoostSats = 0.0,
                statusMessage = "Request cancelled"
            )

            // Resubscribe to get fresh driver list
            resubscribeToDrivers()

            // Clear persisted ride state
            clearSavedRideState()
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

        _uiState.value = _uiState.value.copy(
            broadcastStartTimeMs = System.currentTimeMillis(),
            broadcastTimedOut = false,
            statusMessage = "Searching for drivers..."
        )
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
            if (_uiState.value.rideStage != RideStage.BROADCASTING_REQUEST) {
                Log.d(TAG, "Ignoring acceptance - not in broadcasting stage (stage=${_uiState.value.rideStage})")
                return@subscribeToAcceptancesForOffer
            }

            Log.d(TAG, "First driver accepted! ${acceptance.driverPubKey.take(8)}")
            hasAcceptedDriver = true

            // Cancel the timeout
            cancelBroadcastTimeout()

            _uiState.value = _uiState.value.copy(
                acceptance = acceptance,
                rideStage = RideStage.DRIVER_ACCEPTED,
                broadcastStartTimeMs = null,
                statusMessage = "Driver accepted! Confirming ride..."
            )

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

        // Only timeout if we're still broadcasting
        if (state.rideStage != RideStage.BROADCASTING_REQUEST) {
            Log.d(TAG, "Broadcast timeout ignored - not broadcasting (stage=${state.rideStage})")
            return
        }

        Log.d(TAG, "Broadcast timeout - no driver accepted. Total boost: ${state.totalBoostSats} sats")

        // Don't automatically delete the offer - let user decide to boost or cancel
        // Set broadcastTimedOut = true so UI shows the options menu persistently
        _uiState.value = state.copy(
            broadcastStartTimeMs = null,
            broadcastTimedOut = true,
            statusMessage = "No drivers responded. Boost fare or try again?"
        )
    }

    /**
     * Confirm the ride after driver accepts (would send precise location).
     */
    fun confirmRide() {
        val state = _uiState.value
        val acceptance = state.acceptance ?: return
        val pickup = state.pickupLocation ?: return

        // CRITICAL: Close any lingering subscriptions from previous rides
        // This prevents old cancellation events from affecting this new ride
        closeAllRideSubscriptions()

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isConfirmingRide = true)

            val eventId = nostrService.confirmRide(
                acceptance = acceptance,
                precisePickup = pickup
            )

            if (eventId != null) {
                Log.d(TAG, "Confirmed ride: $eventId")
                myRideEventIds.add(eventId)  // Track for cleanup

                // Close acceptance subscription - we don't need it anymore
                // This prevents duplicate acceptance events from affecting state
                acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }
                acceptanceSubscriptionId = null

                // Subscribe to cancellation events from driver
                subscribeToCancellation(eventId)

                _uiState.value = _uiState.value.copy(
                    isConfirmingRide = false,
                    confirmationEventId = eventId,
                    rideStage = RideStage.RIDE_CONFIRMED,
                    statusMessage = "Ride confirmed! Driver is on the way."
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isConfirmingRide = false,
                    error = "Failed to confirm ride"
                )
            }
        }
    }

    /**
     * Clear ride state after completion or cancellation.
     */
    fun clearRide() {
        val state = _uiState.value

        // Stop chat refresh job
        stopChatRefreshJob()

        acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }
        acceptanceSubscriptionId = null

        driverRideStateSubscriptionId?.let { nostrService.closeSubscription(it) }
        driverRideStateSubscriptionId = null

        chatSubscriptionId?.let { nostrService.closeSubscription(it) }
        chatSubscriptionId = null

        cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
        cancellationSubscriptionId = null

        // Clear rider state history
        clearRiderStateHistory()

        // Stop foreground service
        RiderActiveService.stop(getApplication())

        // Capture state values before launching coroutine
        val confirmationId = state.confirmationEventId
        val driverPubKey = state.acceptance?.driverPubKey
        val wasConfirmedRide = state.rideStage in listOf(RideStage.RIDE_CONFIRMED, RideStage.DRIVER_ARRIVED, RideStage.IN_PROGRESS)

        viewModelScope.launch {
            // Save cancelled ride to history (only if ride was confirmed/in progress)
            if (wasConfirmedRide) {
                try {
                    val driver = state.selectedDriver
                    val driverProfile = driver?.let { state.driverProfiles[it.driverPubKey] }
                    val historyEntry = RideHistoryEntry(
                        rideId = confirmationId ?: state.pendingOfferEventId ?: "",
                        timestamp = System.currentTimeMillis() / 1000,
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
                        distanceMiles = (state.routeResult?.distanceKm ?: 0.0) * 0.621371,
                        durationMinutes = 0,  // Ride was cancelled, no actual duration
                        fareSats = 0,  // No fare charged for cancelled ride
                        status = "cancelled",
                        // Driver details for ride history
                        counterpartyFirstName = driverProfile?.bestName()?.split(" ")?.firstOrNull(),
                        vehicleMake = driver?.carMake,
                        vehicleModel = driver?.carModel
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
            if (confirmationId != null && driverPubKey != null && wasConfirmedRide) {
                Log.d(TAG, "Publishing ride cancellation to driver")
                val cancellationEventId = nostrService.publishRideCancellation(
                    confirmationEventId = confirmationId,
                    otherPartyPubKey = driverPubKey,
                    reason = "Rider cancelled"
                )
                cancellationEventId?.let { myRideEventIds.add(it) }
            }

            // Clean up all our ride events (NIP-09) - AWAIT before state reset
            cleanupRideEventsInBackground("ride cancelled")

            // THEN update state after deletion completes
            _uiState.value = _uiState.value.copy(
                selectedDriver = null,
                pendingOfferEventId = null,
                acceptance = null,
                confirmationEventId = null,
                pickupPin = null,
                pinAttempts = 0,
                pinVerified = false,
                chatMessages = emptyList(),
                isSendingMessage = false,
                rideStage = RideStage.IDLE,
                statusMessage = "Ready to book a ride"
            )

            // Resubscribe to get fresh driver list (clears stale data from previous ride)
            resubscribeToDrivers()

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
     * Toggle expanded search to find drivers in a wider area (~20 mile radius).
     */
    fun toggleExpandedSearch() {
        _uiState.value = _uiState.value.copy(
            expandedSearch = !_uiState.value.expandedSearch
        )
        Log.d(TAG, "Expanded search: ${_uiState.value.expandedSearch}")
        resubscribeToDrivers()
    }

    private fun subscribeToDrivers() {
        // Use pickup location for geohash filtering if available
        val filterLocation = _uiState.value.pickupLocation
        val expandSearch = _uiState.value.expandedSearch

        // Track current geohash for subscription
        currentSubscriptionGeohash = filterLocation?.geohash(precision = 4)
        Log.d(TAG, "Subscribing to drivers - geohash: $currentSubscriptionGeohash, expanded: $expandSearch")

        driverSubscriptionId = nostrService.subscribeToDrivers(filterLocation, expandSearch) { driver ->
            Log.d(TAG, "Found driver: ${driver.driverPubKey.take(8)}... status=${driver.status}")

            val currentDrivers = _uiState.value.availableDrivers.toMutableList()
            val existingIndex = currentDrivers.indexOfFirst { it.driverPubKey == driver.driverPubKey }

            if (driver.isOffline) {
                // Driver went offline - remove from list
                if (existingIndex >= 0) {
                    Log.d(TAG, "Removing offline driver: ${driver.driverPubKey.take(8)}...")
                    currentDrivers.removeAt(existingIndex)
                    // Clean up profile subscription
                    unsubscribeFromDriverProfile(driver.driverPubKey)
                    // Clear selection if this driver was selected
                    if (_uiState.value.selectedDriver?.driverPubKey == driver.driverPubKey) {
                        _uiState.value = _uiState.value.copy(
                            availableDrivers = currentDrivers,
                            selectedDriver = null
                        )
                        return@subscribeToDrivers
                    }
                }
            } else {
                // Driver is available - add or update
                if (existingIndex >= 0) {
                    // Update existing driver
                    currentDrivers[existingIndex] = driver
                } else {
                    // Add new driver
                    currentDrivers.add(driver)
                    // Subscribe to their profile to get name
                    subscribeToDriverProfile(driver.driverPubKey)
                }
            }

            // Validate selectedDriver is still in the updated list
            val currentSelected = _uiState.value.selectedDriver
            val selectedStillValid = currentSelected == null ||
                currentDrivers.any { it.driverPubKey == currentSelected.driverPubKey }

            _uiState.value = _uiState.value.copy(
                availableDrivers = currentDrivers,
                nearbyDriverCount = currentDrivers.size,
                selectedDriver = if (selectedStillValid) currentSelected else null
            )
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
     */
    private fun cleanupStaleDrivers() {
        val now = System.currentTimeMillis() / 1000 // Convert to seconds (Nostr uses seconds)
        val staleThreshold = now - (STALE_DRIVER_TIMEOUT_MS / 1000)

        val currentDrivers = _uiState.value.availableDrivers
        val staleDrivers = mutableListOf<String>()
        val freshDrivers = currentDrivers.filter { driver ->
            val isFresh = driver.createdAt >= staleThreshold
            if (!isFresh) {
                Log.d(TAG, "Removing stale driver: ${driver.driverPubKey.take(8)}... (last seen ${now - driver.createdAt}s ago)")
                staleDrivers.add(driver.driverPubKey)
            }
            isFresh
        }

        if (freshDrivers.size != currentDrivers.size) {
            // Clean up profile subscriptions for stale drivers
            staleDrivers.forEach { pubKey ->
                unsubscribeFromDriverProfile(pubKey)
            }

            // Check if selected driver was removed
            val selectedDriver = _uiState.value.selectedDriver
            val selectedStillExists = selectedDriver == null ||
                freshDrivers.any { it.driverPubKey == selectedDriver.driverPubKey }

            _uiState.value = _uiState.value.copy(
                availableDrivers = freshDrivers,
                nearbyDriverCount = freshDrivers.size,  // Also update the count!
                selectedDriver = if (selectedStillExists) selectedDriver else null
            )
            Log.d(TAG, "Removed ${staleDrivers.size} stale drivers, ${freshDrivers.size} remaining")
        }
    }

    private fun subscribeToAcceptance(offerEventId: String) {
        acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }

        acceptanceSubscriptionId = nostrService.subscribeToAcceptance(offerEventId) { acceptance ->
            Log.d(TAG, "Driver accepted ride: ${acceptance.eventId}")

            // Cancel the acceptance timeout - driver responded
            cancelAcceptanceTimeout()

            // Only process if we're still waiting for acceptance
            // This prevents duplicate events from resetting the state after confirmation
            if (_uiState.value.rideStage == RideStage.WAITING_FOR_ACCEPTANCE) {
                _uiState.value = _uiState.value.copy(
                    acceptance = acceptance,
                    rideStage = RideStage.DRIVER_ACCEPTED,
                    acceptanceTimeoutStartMs = null,
                    statusMessage = "Driver accepted! Confirming ride..."
                )

                // Auto-confirm the ride (send precise pickup location)
                autoConfirmRide(acceptance)
            } else {
                Log.d(TAG, "Ignoring duplicate acceptance - already in stage ${_uiState.value.rideStage}")
            }
        }
    }

    /**
     * Automatically confirm the ride when driver accepts.
     * Generates PIN locally and sends precise pickup location to the driver.
     */
    private fun autoConfirmRide(acceptance: RideAcceptanceData) {
        val pickup = _uiState.value.pickupLocation ?: return

        // Generate PIN locally - rider is the one with money at stake
        val pickupPin = String.format("%04d", Random.nextInt(10000))
        Log.d(TAG, "Generated pickup PIN: $pickupPin")

        // Check if driver is already close (within 1 mile) - if so, send precise pickup immediately
        // Look up driver's location from available drivers list
        val driverLocation = _uiState.value.availableDrivers
            .find { it.driverPubKey == acceptance.driverPubKey }
            ?.approxLocation

        val driverAlreadyClose = driverLocation?.let { pickup.isWithinMile(it) } == true

        viewModelScope.launch {
            // Send APPROXIMATE pickup for privacy - precise location revealed when driver is close
            // UNLESS driver is already within 1 mile, then send precise immediately
            val pickupToSend = if (driverAlreadyClose) {
                Log.d(TAG, "Driver already within 1 mile - sending precise pickup immediately")
                pickup
            } else {
                pickup.approximate()
            }
            Log.d(TAG, "Sending pickup: ${pickupToSend.lat}, ${pickupToSend.lon} (precise: ${pickup.lat}, ${pickup.lon}, driver close: $driverAlreadyClose)")

            val eventId = nostrService.confirmRide(
                acceptance = acceptance,
                precisePickup = pickupToSend  // Send precise if driver is close, approximate otherwise
            )

            if (eventId != null) {
                Log.d(TAG, "Auto-confirmed ride: $eventId")
                myRideEventIds.add(eventId)  // Track for cleanup

                // Close acceptance subscription - we don't need it anymore
                acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }
                acceptanceSubscriptionId = null

                // Update service status - plays confirmation sound and updates notification
                val driverName = _uiState.value.driverProfiles[acceptance.driverPubKey]?.bestName()
                RiderActiveService.updateStatus(getApplication(), RiderStatus.DriverEnRoute(driverName))

                _uiState.value = _uiState.value.copy(
                    confirmationEventId = eventId,
                    pickupPin = pickupPin,
                    pinAttempts = 0,
                    precisePickupShared = driverAlreadyClose,  // Mark as shared if sent precise in confirmation
                    rideStage = RideStage.RIDE_CONFIRMED,
                    statusMessage = "Ride confirmed! Tell driver PIN: $pickupPin"
                )

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
                _uiState.value = _uiState.value.copy(
                    error = "Failed to confirm ride"
                )
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
        Log.w(TAG, "  Current state confirmationEventId: ${_uiState.value.confirmationEventId}")
        Log.w(TAG, "  Current rideStage: ${_uiState.value.rideStage}")
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

        val currentState = _uiState.value

        // DEBUG: Extensive logging to trace phantom cancellation bug
        Log.w(TAG, "=== DRIVER STATE RECEIVED ===")
        Log.w(TAG, "  Event ID: ${driverState.eventId.take(8)}")
        Log.w(TAG, "  Event confirmationEventId: ${driverState.confirmationEventId}")
        Log.w(TAG, "  Closure confirmationEventId: $confirmationEventId")
        Log.w(TAG, "  Current state confirmationEventId: ${currentState.confirmationEventId}")
        Log.w(TAG, "  Current rideStage: ${currentState.rideStage}")
        Log.w(TAG, "  Event status: ${driverState.currentStatus}")
        Log.w(TAG, "  Event history size: ${driverState.history.size}")
        Log.w(TAG, "  lastProcessedDriverActionCount: $lastProcessedDriverActionCount")

        // SECOND: Validate the EVENT's confirmation ID matches current ride
        // This is the definitive check - the event itself knows which ride it belongs to
        if (driverState.confirmationEventId != currentState.confirmationEventId) {
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
            }
        }
    }

    /**
     * Handle a status action from the driver.
     */
    private fun handleDriverStatusAction(action: DriverRideAction.Status, driverState: DriverRideStateData, confirmationEventId: String) {
        val state = _uiState.value
        val context = getApplication<Application>()

        // CRITICAL: Validate this event is for the CURRENT ride
        // This prevents old events from affecting new rides
        if (state.confirmationEventId != confirmationEventId) {
            Log.d(TAG, "Ignoring driver status for old ride: ${confirmationEventId.take(8)} vs current ${state.confirmationEventId?.take(8)}")
            return
        }

        val driverPubKey = state.acceptance?.driverPubKey
        val driverName = driverPubKey?.let { _uiState.value.driverProfiles[it]?.bestName() }

        when (action.status) {
            DriverStatusType.EN_ROUTE_PICKUP -> {
                Log.d(TAG, "Driver is en route to pickup")
                RiderActiveService.updateStatus(context, RiderStatus.DriverEnRoute(driverName))
                _uiState.value = state.copy(
                    rideStage = RideStage.RIDE_CONFIRMED,
                    statusMessage = "Driver is on the way!"
                )
                saveRideState()
            }
            DriverStatusType.ARRIVED -> {
                Log.d(TAG, "Driver has arrived!")
                RiderActiveService.updateStatus(context, RiderStatus.DriverArrived(driverName))
                currentRiderPhase = RiderRideStateEvent.Phase.AWAITING_PIN
                _uiState.value = state.copy(
                    rideStage = RideStage.DRIVER_ARRIVED,
                    statusMessage = "Driver has arrived! Tell them your PIN: ${state.pickupPin}"
                )
                saveRideState()
            }
            DriverStatusType.IN_PROGRESS -> {
                Log.d(TAG, "Ride is in progress")
                RiderActiveService.updateStatus(context, RiderStatus.RideInProgress(driverName))
                currentRiderPhase = RiderRideStateEvent.Phase.IN_RIDE
                _uiState.value = state.copy(
                    rideStage = RideStage.IN_PROGRESS,
                    statusMessage = "Ride in progress"
                )
                saveRideState()
            }
            DriverStatusType.COMPLETED -> {
                Log.d(TAG, "Ride completed!")
                // Use the dedicated completion handler
                handleRideCompletion(driverState)
            }
            DriverStatusType.CANCELLED -> {
                Log.w(TAG, "=== CANCELLED STATUS DETECTED ===")
                Log.w(TAG, "  Closure confirmationEventId: $confirmationEventId")
                Log.w(TAG, "  Current state confirmationEventId: ${state.confirmationEventId}")
                Log.w(TAG, "  Current rideStage: ${state.rideStage}")
                handleDriverCancellation()
            }
        }
    }

    /**
     * Handle a PIN submission action from the driver.
     */
    private fun handlePinSubmission(action: DriverRideAction.PinSubmit, confirmationEventId: String, driverPubKey: String) {
        val state = _uiState.value

        // CRITICAL: Skip if already verified (prevents duplicate verification on app restart)
        // After app restart, subscription may receive full history including already-verified PIN actions
        if (state.pinVerified) {
            Log.d(TAG, "PIN already verified, ignoring duplicate pin action")
            return
        }

        val expectedPin = state.pickupPin ?: return

        viewModelScope.launch {
            // Decrypt the PIN
            val decryptedPin = nostrService.decryptPinFromDriverState(action.pinEncrypted, driverPubKey)
            if (decryptedPin == null) {
                Log.e(TAG, "Failed to decrypt PIN")
                return@launch
            }

            Log.d(TAG, "Received PIN submission from driver: $decryptedPin")

            val newAttempts = state.pinAttempts + 1
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

                // Update service status to in progress
                val context = getApplication<Application>()
                val driverName = _uiState.value.driverProfiles[driverPubKey]?.bestName()
                RiderActiveService.updateStatus(context, RiderStatus.RideInProgress(driverName))

                _uiState.value = state.copy(
                    pinAttempts = newAttempts,
                    pinVerified = true,
                    rideStage = RideStage.IN_PROGRESS,
                    statusMessage = "PIN verified! Ride in progress."
                )

                // Save ride state for persistence
                saveRideState()

                // Reveal precise destination to driver now that ride is starting
                revealPreciseDestination(confirmationEventId)
            } else {
                Log.w(TAG, "PIN incorrect! Attempt $newAttempts of $MAX_PIN_ATTEMPTS")

                if (newAttempts >= MAX_PIN_ATTEMPTS) {
                    // Brute force protection - cancel the ride
                    Log.e(TAG, "Max PIN attempts reached! Cancelling ride for security.")

                    // CRITICAL: Stop chat refresh job to prevent phantom events from affecting future rides
                    stopChatRefreshJob()

                    // Close ALL active subscriptions (prevents old events affecting new rides)
                    acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }
                    acceptanceSubscriptionId = null
                    driverRideStateSubscriptionId?.let { nostrService.closeSubscription(it) }
                    driverRideStateSubscriptionId = null
                    chatSubscriptionId?.let { nostrService.closeSubscription(it) }
                    chatSubscriptionId = null
                    cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
                    cancellationSubscriptionId = null

                    // Clear rider state history
                    clearRiderStateHistory()

                    // Stop foreground service
                    RiderActiveService.stop(getApplication())

                    _uiState.value = state.copy(
                        pinAttempts = newAttempts,
                        rideStage = RideStage.IDLE,
                        acceptance = null,
                        confirmationEventId = null,
                        pickupPin = null,
                        pinVerified = false,
                        chatMessages = emptyList(),
                        statusMessage = "Ride cancelled - too many wrong PIN attempts",
                        error = "Security alert: Driver entered wrong PIN $MAX_PIN_ATTEMPTS times. Ride cancelled."
                    )

                    // Clean up events
                    cleanupRideEventsInBackground("pin brute force security")

                    // Resubscribe to get fresh driver list
                    resubscribeToDrivers()

                    // Clear persisted ride state
                    clearSavedRideState()
                } else {
                    _uiState.value = state.copy(
                        pinAttempts = newAttempts,
                        statusMessage = "Wrong PIN! ${MAX_PIN_ATTEMPTS - newAttempts} attempts remaining. PIN: $expectedPin"
                    )
                }
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
                    RiderActiveService.addAlert(
                        context,
                        StackableAlert.Chat(chatData.message)
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
        chatRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(CHAT_REFRESH_INTERVAL_MS)

                // CRITICAL: Check cancellation IMMEDIATELY after delay
                // Kotlin coroutine cancellation is cooperative - cancel() only sets a flag.
                // Without this check, a cancelled job suspended in delay() would execute
                // one more iteration after waking up, potentially creating stale subscriptions
                // that receive events from a previous ride (phantom cancellation bug).
                ensureActive()

                Log.d(TAG, "Refreshing chat and driver state subscriptions for ${confirmationEventId.take(8)}")
                subscribeToChatMessages(confirmationEventId)
                // Refresh driver ride state subscription if we have acceptance data
                _uiState.value.acceptance?.driverPubKey?.let { driverPubKey ->
                    subscribeToDriverRideState(confirmationEventId, driverPubKey)
                }
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

        // Only timeout if we're still waiting for acceptance
        if (state.rideStage != RideStage.WAITING_FOR_ACCEPTANCE) {
            Log.d(TAG, "Acceptance timeout ignored - no longer waiting (stage=${state.rideStage})")
            return
        }

        Log.d(TAG, "Direct offer timeout - no response from driver")

        // Don't auto-cancel - let user decide (boost, keep waiting, or cancel)
        // Keep acceptance subscription open in case driver responds late
        _uiState.value = state.copy(
            acceptanceTimeoutStartMs = null,
            directOfferTimedOut = true,
            statusMessage = "No response from driver. Boost fare or try again?"
        )
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
            val currentConfirmationId = currentState.confirmationEventId

            // DEBUG: Extensive logging for Kind 3179 cancellation events
            Log.w(TAG, "=== KIND 3179 CANCELLATION RECEIVED ===")
            Log.w(TAG, "  Event ID: ${cancellation.eventId.take(8)}")
            Log.w(TAG, "  Event confirmationEventId: ${cancellation.confirmationEventId}")
            Log.w(TAG, "  Closure confirmationEventId: $confirmationEventId")
            Log.w(TAG, "  Current state confirmationEventId: $currentConfirmationId")
            Log.w(TAG, "  Current rideStage: ${currentState.rideStage}")
            Log.w(TAG, "  Reason: ${cancellation.reason ?: "none"}")

            // SECOND: Validate the EVENT's confirmation ID matches current ride
            // This is the definitive check - the event itself knows which ride it belongs to
            if (cancellation.confirmationEventId != currentConfirmationId) {
                Log.w(TAG, "  >>> REJECTED: event confId doesn't match current state <<<")
                return@subscribeToCancellation
            }

            // Only process if we're in an active ride
            if (currentState.rideStage !in listOf(RideStage.RIDE_CONFIRMED, RideStage.DRIVER_ARRIVED, RideStage.IN_PROGRESS)) {
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
        if (state.precisePickupShared) return

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
        val driverPubKey = state.acceptance?.driverPubKey ?: return

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
                _uiState.value = _uiState.value.copy(
                    precisePickupShared = true,
                    statusMessage = "Precise pickup shared with driver"
                )
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
        val driverPubKey = state.acceptance?.driverPubKey ?: return

        // Don't send if already shared
        if (state.preciseDestinationShared) return

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
                _uiState.value = _uiState.value.copy(
                    preciseDestinationShared = true
                )
            } else {
                Log.e(TAG, "Failed to reveal precise destination")
            }
        }
    }

    /**
     * Handle ride completion from driver.
     */
    private fun handleRideCompletion(statusData: DriverRideStateData) {
        // Stop refresh jobs
        stopChatRefreshJob()

        // Close active subscriptions
        driverRideStateSubscriptionId?.let { nostrService.closeSubscription(it) }
        driverRideStateSubscriptionId = null
        chatSubscriptionId?.let { nostrService.closeSubscription(it) }
        chatSubscriptionId = null
        cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
        cancellationSubscriptionId = null

        // Clear rider state history
        clearRiderStateHistory()

        // Stop foreground service (this also clears the notification)
        RiderActiveService.stop(getApplication())

        // Clear persisted ride state
        clearSavedRideState()

        // Capture fare info before launching coroutine
        val fareMessage = statusData.finalFare?.let { " Fare: ${it.toInt()} sats" } ?: ""

        // Capture state for ride history before launching coroutine
        val state = _uiState.value
        val finalFareSats = statusData.finalFare?.toLong() ?: state.fareEstimate?.toLong() ?: 0L
        val driver = state.selectedDriver
        val driverProfile = driver?.let { state.driverProfiles[it.driverPubKey] }

        viewModelScope.launch {
            // Save to ride history (rider gets exact coords + addresses for their own history)
            try {
                val historyEntry = RideHistoryEntry(
                    rideId = state.confirmationEventId ?: state.pendingOfferEventId ?: "",
                    timestamp = System.currentTimeMillis() / 1000,
                    role = "rider",
                    counterpartyPubKey = driver?.driverPubKey ?: state.acceptance?.driverPubKey ?: "",
                    pickupGeohash = state.pickupLocation?.geohash(6) ?: "",  // ~1.2km for compatibility
                    dropoffGeohash = state.destination?.geohash(6) ?: "",
                    // Rider gets exact locations for their own history
                    pickupLat = state.pickupLocation?.lat,
                    pickupLon = state.pickupLocation?.lon,
                    pickupAddress = state.pickupLocation?.addressLabel,
                    dropoffLat = state.destination?.lat,
                    dropoffLon = state.destination?.lon,
                    dropoffAddress = state.destination?.addressLabel,
                    distanceMiles = (state.routeResult?.distanceKm ?: 0.0) * 0.621371,
                    durationMinutes = ((state.routeResult?.durationSeconds ?: 0.0) / 60).toInt(),
                    fareSats = finalFareSats,
                    status = "completed",
                    // Driver details for ride history
                    counterpartyFirstName = driverProfile?.bestName()?.split(" ")?.firstOrNull(),
                    vehicleMake = driver?.carMake,
                    vehicleModel = driver?.carModel
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
            _uiState.value = _uiState.value.copy(
                rideStage = RideStage.COMPLETED,
                statusMessage = "Ride completed!$fareMessage"
            )
        }
    }

    /**
     * Handle ride cancellation from driver.
     */
    private fun handleDriverCancellation(reason: String?) {
        // DEBUG: Log cancellation handling
        Log.w(TAG, "=== HANDLE DRIVER CANCELLATION ===")
        Log.w(TAG, "  Reason: $reason")
        Log.w(TAG, "  Current confirmationEventId: ${_uiState.value.confirmationEventId}")
        Log.w(TAG, "  Current rideStage: ${_uiState.value.rideStage}")
        Log.w(TAG, "  driverRideStateSubscriptionId: $driverRideStateSubscriptionId")

        // Stop refresh jobs
        stopChatRefreshJob()

        // Close active subscriptions
        acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }
        acceptanceSubscriptionId = null
        driverRideStateSubscriptionId?.let { nostrService.closeSubscription(it) }
        driverRideStateSubscriptionId = null
        chatSubscriptionId?.let { nostrService.closeSubscription(it) }
        chatSubscriptionId = null
        cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
        cancellationSubscriptionId = null

        // Clear rider state history
        clearRiderStateHistory()

        // Notify service of cancellation (plays sound) then stop
        val context = getApplication<Application>()
        RiderActiveService.updateStatus(context, RiderStatus.Cancelled)
        RiderActiveService.stop(context)

        // Clear persisted ride state
        clearSavedRideState()

        // Capture reason before launching coroutine
        val errorMessage = reason ?: "Driver cancelled the ride"

        // Capture state for ride history before launching coroutine
        val state = _uiState.value
        val driver = state.selectedDriver
        val driverProfile = driver?.let { state.driverProfiles[it.driverPubKey] }

        // CRITICAL: Reset ride-related fields SYNCHRONOUSLY before launching coroutine
        // This prevents phantom cancellations where delayed events from ride #1
        // could pass validation and affect ride #2. The validation checks
        // driverState.confirmationEventId != currentState.confirmationEventId,
        // so setting it to null immediately ensures any stale events are rejected.
        // Also clear selectedDriver and acceptance to prevent stale UI state.
        _uiState.value = _uiState.value.copy(
            confirmationEventId = null,
            rideStage = RideStage.IDLE,
            selectedDriver = null,
            acceptance = null
        )
        Log.w(TAG, "  >>> State reset: confirmationEventId=null, rideStage=IDLE, selectedDriver=null, acceptance=null <<<")

        viewModelScope.launch {
            // Save cancelled ride to history (only if ride was confirmed/in progress)
            if (state.confirmationEventId != null || state.acceptance != null) {
                try {
                    val historyEntry = RideHistoryEntry(
                        rideId = state.confirmationEventId ?: state.pendingOfferEventId ?: "",
                        timestamp = System.currentTimeMillis() / 1000,
                        role = "rider",
                        counterpartyPubKey = driver?.driverPubKey ?: state.acceptance?.driverPubKey ?: "",
                        pickupGeohash = state.pickupLocation?.geohash(6) ?: "",
                        dropoffGeohash = state.destination?.geohash(6) ?: "",
                        // Rider gets exact locations for their own history
                        pickupLat = state.pickupLocation?.lat,
                        pickupLon = state.pickupLocation?.lon,
                        pickupAddress = state.pickupLocation?.addressLabel,
                        dropoffLat = state.destination?.lat,
                        dropoffLon = state.destination?.lon,
                        dropoffAddress = state.destination?.addressLabel,
                        distanceMiles = (state.routeResult?.distanceKm ?: 0.0) * 0.621371,
                        durationMinutes = 0,  // Ride was cancelled, no actual duration
                        fareSats = 0,  // No fare charged for cancelled ride
                        status = "cancelled",
                        // Driver details for ride history
                        counterpartyFirstName = driverProfile?.bestName()?.split(" ")?.firstOrNull(),
                        vehicleMake = driver?.carMake,
                        vehicleModel = driver?.carModel
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

            // Only update remaining state and resubscribe if we're still in IDLE (no new ride started)
            // The critical fields (confirmationEventId, rideStage, selectedDriver, acceptance)
            // were already reset synchronously. This updates the remaining UI state.
            val currentState = _uiState.value
            if (currentState.rideStage == RideStage.IDLE && currentState.confirmationEventId == null) {
                _uiState.value = currentState.copy(
                    pendingOfferEventId = null,
                    pickupPin = null,
                    pinAttempts = 0,
                    pinVerified = false,
                    chatMessages = emptyList(),
                    isSendingMessage = false,
                    statusMessage = "Driver cancelled the ride",
                    error = errorMessage
                )

                // Resubscribe to get fresh driver list (only if still IDLE)
                resubscribeToDrivers()
            } else {
                Log.d(TAG, "Skipping state cleanup and resubscribe - new ride already started (stage=${currentState.rideStage})")
            }
        }
    }

    /**
     * Handle driver arrived at pickup location.
     */
    private fun handleDriverArrived() {
        // Reject stale events if we've already reached or passed DRIVER_ARRIVED stage
        // This prevents Nostr relay cached events from reverting state after PIN verification
        if (_uiState.value.rideStage in listOf(
                RideStage.DRIVER_ARRIVED,
                RideStage.IN_PROGRESS,
                RideStage.COMPLETED
            )) {
            Log.d(TAG, "Stage already at or past DRIVER_ARRIVED (${_uiState.value.rideStage}), ignoring stale event")
            return
        }

        val context = getApplication<Application>()
        val driverPubKey = _uiState.value.acceptance?.driverPubKey
        val driverName = driverPubKey?.let { _uiState.value.driverProfiles[it]?.bestName() }

        // Update service - handles notification update and sound
        RiderActiveService.updateStatus(context, RiderStatus.DriverArrived(driverName))

        // Update UI state
        _uiState.value = _uiState.value.copy(
            rideStage = RideStage.DRIVER_ARRIVED,
            statusMessage = "Driver has arrived at pickup!"
        )

        Log.d(TAG, "Driver arrived - service notified")
    }

    /**
     * Send a chat message to the driver.
     */
    fun sendChatMessage(message: String) {
        val state = _uiState.value
        val confirmationEventId = state.confirmationEventId ?: return
        val driverPubKey = state.acceptance?.driverPubKey ?: return

        if (message.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSendingMessage = true)

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
                _uiState.value = _uiState.value.copy(
                    isCalculatingRoute = false,
                    routeResult = result,
                    fareEstimate = fareEstimate
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
        // Calculate fare in USD
        val distanceBasedFare = distanceMiles * FARE_USD_PER_MILE

        // Enforce minimum fare of $5.00 to ensure driver profitability on short rides
        val fareUsd = maxOf(distanceBasedFare, MINIMUM_FARE_USD)

        // Convert USD to sats using current BTC price
        val sats = bitcoinPriceService.usdToSats(fareUsd)

        // Return sats, or fallback if price unavailable
        // For fallback, also enforce minimum (assuming ~$100k BTC, $5 = ~5000 sats)
        val minimumFallbackSats = 5000.0
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
        chatRefreshJob?.cancel()
        acceptanceTimeoutJob?.cancel()
        broadcastTimeoutJob?.cancel()
        driverSubscriptionId?.let { nostrService.closeSubscription(it) }
        acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }
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
            fareEstimate = null
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
 * UI state for rider mode.
 */
data class RiderUiState(
    // Available drivers
    val availableDrivers: List<DriverAvailabilityData> = emptyList(),
    val selectedDriver: DriverAvailabilityData? = null,
    val driverProfiles: Map<String, UserProfile> = emptyMap(),
    val expandedSearch: Boolean = true,  // Default to 20+ mile radius for better driver coverage
    val nearbyDriverCount: Int = 0,               // Count of nearby drivers (for display)

    // Route information
    val pickupLocation: Location? = null,
    val destination: Location? = null,
    val routeResult: RouteResult? = null,
    val fareEstimate: Double? = null,
    val isCalculatingRoute: Boolean = false,
    val isRoutingReady: Boolean = false,

    // Ride state
    val rideStage: RideStage = RideStage.IDLE,
    val pendingOfferEventId: String? = null,      // Our offer event (Kind 3173) for cleanup
    val acceptance: RideAcceptanceData? = null,
    val confirmationEventId: String? = null,       // Our confirmation event (Kind 3175) for cleanup
    val isSendingOffer: Boolean = false,
    val isConfirmingRide: Boolean = false,

    // Broadcast mode (new flow)
    val broadcastStartTimeMs: Long? = null,       // When we started broadcasting
    val broadcastTimeoutDurationMs: Long = 120_000L, // How long to wait (2 minutes)
    val broadcastTimedOut: Boolean = false,       // True when broadcast timer has expired (persist until user action)
    val totalBoostSats: Double = 0.0,              // Total sats added via boosts (stored in sats for accuracy)

    // Acceptance timeout tracking (for direct offers - legacy/advanced)
    val acceptanceTimeoutStartMs: Long? = null,   // When we started waiting for acceptance
    val acceptanceTimeoutDurationMs: Long = 15_000L, // How long before showing options (15 seconds)
    val directOfferBoostSats: Double = 0.0,        // Total sats boosted on direct offer
    val directOfferTimedOut: Boolean = false,      // True when direct offer timer has expired

    // PIN verification (rider generates PIN and verifies driver's submissions)
    val pickupPin: String? = null,                 // PIN generated locally by rider
    val pinAttempts: Int = 0,                      // Number of driver verification attempts
    val pinVerified: Boolean = false,              // True when driver submitted correct PIN

    // Chat (NIP-17 style private messaging)
    val chatMessages: List<RideshareChatData> = emptyList(),
    val isSendingMessage: Boolean = false,

    // Progressive location reveal (privacy feature)
    val precisePickupShared: Boolean = false,      // True when precise pickup sent to driver
    val preciseDestinationShared: Boolean = false, // True when precise destination sent to driver
    val driverLocation: Location? = null,          // Driver's current location from status updates

    // User identity
    val myPubKey: String = "",

    // UI
    val statusMessage: String = "Find available drivers",
    val error: String? = null
)
