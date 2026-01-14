package com.drivestr.app.viewmodels

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.drivestr.app.service.DriverOnlineService
import com.drivestr.app.service.DriverStatus
import com.ridestr.common.bitcoin.BitcoinPriceService
import com.ridestr.common.data.Vehicle
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.BroadcastRideOfferData
import com.ridestr.common.nostr.events.DriverStatusType
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.events.PickupVerificationData
import com.ridestr.common.nostr.events.PreciseLocationRevealEvent
import com.ridestr.common.nostr.events.RideConfirmationData
import com.ridestr.common.nostr.events.RideOfferData
import com.ridestr.common.nostr.events.RideshareChatData
import com.ridestr.common.nostr.events.RideshareEventKinds
import com.ridestr.common.routing.RouteResult
import com.ridestr.common.routing.ValhallaRoutingService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
        // Timeout for rider confirmation after accepting (15 seconds)
        // If no confirmation arrives, the ride was likely cancelled
        private const val CONFIRMATION_TIMEOUT_MS = 15 * 1000L
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

    // Bitcoin price service for fare conversion
    val bitcoinPriceService = BitcoinPriceService()

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
    private var verificationSubscriptionId: String? = null
    private var chatSubscriptionId: String? = null
    private var cancellationSubscriptionId: String? = null
    private var preciseLocationSubscriptionId: String? = null  // Progressive location reveals from rider
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
    // Track status update event IDs for cleanup on ride completion
    private val statusEventIds = mutableListOf<String>()
    // Track PIN submission event ID for cleanup on ride completion
    private var pinSubmissionEventId: String? = null
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

            // Restore PIN verification subscription if at pickup
            if (state.stage == DriverStage.ARRIVED_AT_PICKUP) {
                Log.d(TAG, "Refreshing verification subscription for ARRIVED_AT_PICKUP")
                subscribeToVerifications()
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
            }

            prefs.edit().putString(KEY_RIDE_STATE, json.toString()).apply()
            Log.d(TAG, "Saved ride state: stage=${state.stage}, messages=${state.chatMessages.size}")
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

            Log.d(TAG, "Restoring ride state: stage=$stage, confirmationId=$confirmationEventId, messages=${chatMessages.size}")

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
                subscribeToVerifications()
            }

            if (confirmationEventId != null) {
                subscribeToChatMessages(confirmationEventId)
                subscribeToCancellation(confirmationEventId)
                // Start periodic chat refresh
                startChatRefreshJob(confirmationEventId)
                if (stage == DriverStage.ARRIVED_AT_PICKUP) {
                    subscribeToVerifications()
                }
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
        val context = getApplication<Application>()

        if (currentState.stage != DriverStage.OFFLINE) return

        // Set location and vehicle FIRST so route calculations work
        _uiState.value = currentState.copy(
            stage = DriverStage.AVAILABLE,
            currentLocation = location,
            activeVehicle = vehicle,
            statusMessage = "You are now available for rides"
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
     * Go offline.
     */
    fun goOffline() {
        val currentState = _uiState.value
        val context = getApplication<Application>()

        if (currentState.stage != DriverStage.AVAILABLE) return

        viewModelScope.launch {
            val lastLocation = currentState.currentLocation
            if (lastLocation != null) {
                val eventId = nostrService.broadcastOffline(lastLocation)
                if (eventId != null) {
                    Log.d(TAG, "Broadcast offline status: $eventId")
                }
            }
            deleteAllAvailabilityEvents()
        }
        stopBroadcasting()
        closeOfferSubscription()
        // Reset throttle tracking for next time driver goes online
        lastBroadcastLocation = null
        lastBroadcastTimeMs = 0L

        // Stop the foreground service
        DriverOnlineService.stop(context)

        _uiState.value = currentState.copy(
            stage = DriverStage.OFFLINE,
            currentLocation = null,
            activeVehicle = null,
            statusMessage = "You are now offline"
        )
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
        if (state.stage == DriverStage.OFFLINE || state.stage == DriverStage.AVAILABLE) return

        // Stop chat refresh job
        stopChatRefreshJob()

        // Close subscriptions
        confirmationSubscriptionId?.let { nostrService.closeSubscription(it) }
        confirmationSubscriptionId = null
        verificationSubscriptionId?.let { nostrService.closeSubscription(it) }
        verificationSubscriptionId = null
        chatSubscriptionId?.let { nostrService.closeSubscription(it) }
        chatSubscriptionId = null
        cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
        cancellationSubscriptionId = null
        preciseLocationSubscriptionId?.let { nostrService.closeSubscription(it) }
        preciseLocationSubscriptionId = null

        viewModelScope.launch {
            // Send cancelled status if we have a confirmation
            state.confirmationEventId?.let { confId ->
                state.acceptedOffer?.riderPubKey?.let { riderPubKey ->
                    // Send cancellation event so rider gets notified
                    Log.d(TAG, "Publishing ride cancellation to rider")
                    nostrService.publishRideCancellation(
                        confirmationEventId = confId,
                        otherPartyPubKey = riderPubKey,
                        reason = "Driver cancelled"
                    )

                    // Also send status update
                    nostrService.sendStatusUpdate(
                        confirmationEventId = confId,
                        riderPubKey = riderPubKey,
                        status = DriverStatusType.CANCELLED,
                        location = state.currentLocation
                    )
                }
            }

            // Clean up events (NIP-09): acceptance, status updates, PIN submission
            val eventsToDelete = mutableListOf<String>()

            // Add acceptance event
            state.acceptanceEventId?.let { eventsToDelete.add(it) }

            // Add status update events (EN_ROUTE, ARRIVED, etc.)
            eventsToDelete.addAll(statusEventIds)

            // Add PIN submission event
            pinSubmissionEventId?.let { eventsToDelete.add(it) }

            // Add chat messages we sent (only delete our own)
            val myPubKey = state.myPubKey
            state.chatMessages
                .filter { it.senderPubKey == myPubKey }
                .forEach { eventsToDelete.add(it.eventId) }

            if (eventsToDelete.isNotEmpty()) {
                Log.d(TAG, "Requesting deletion of ${eventsToDelete.size} events (acceptance + status + PIN + chat)")
                nostrService.deleteEvents(eventsToDelete, "ride cancelled")
            }

            // Clear tracking lists
            statusEventIds.clear()
            pinSubmissionEventId = null
        }

        // Stop the foreground service
        DriverOnlineService.stop(getApplication())

        // Return to offline state
        _uiState.value = state.copy(
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
            statusMessage = "Ride cancelled. Tap to go online."
        )

        // Clear persisted ride state
        clearSavedRideState()
    }

    /**
     * Return to available after completing a ride.
     */
    fun finishAndGoOnline(location: Location) {
        val state = _uiState.value

        // Close subscriptions
        confirmationSubscriptionId?.let { nostrService.closeSubscription(it) }
        confirmationSubscriptionId = null
        verificationSubscriptionId?.let { nostrService.closeSubscription(it) }
        verificationSubscriptionId = null
        chatSubscriptionId?.let { nostrService.closeSubscription(it) }
        chatSubscriptionId = null
        cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
        cancellationSubscriptionId = null
        preciseLocationSubscriptionId?.let { nostrService.closeSubscription(it) }
        preciseLocationSubscriptionId = null

        // Clean up events (NIP-09): acceptance event, chat messages, status events, and PIN submission
        viewModelScope.launch {
            val myPubKey = state.myPubKey
            val eventsToDelete = mutableListOf<String>()

            // Add acceptance event
            state.acceptanceEventId?.let { eventsToDelete.add(it) }

            // Add chat messages we sent (only delete our own)
            state.chatMessages
                .filter { it.senderPubKey == myPubKey }
                .forEach { eventsToDelete.add(it.eventId) }

            // Add status update events (EN_ROUTE, ARRIVED, IN_PROGRESS, COMPLETED)
            eventsToDelete.addAll(statusEventIds)

            // Add PIN submission event
            pinSubmissionEventId?.let { eventsToDelete.add(it) }

            if (eventsToDelete.isNotEmpty()) {
                Log.d(TAG, "Requesting deletion of ${eventsToDelete.size} events (acceptance + chat + status + PIN)")
                nostrService.deleteEvents(eventsToDelete, "ride completed")
            }

            // Clear tracking lists
            statusEventIds.clear()
            pinSubmissionEventId = null
        }

        _uiState.value = state.copy(
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
            statusMessage = "Tap to go online"
        )

        // Clear persisted ride state
        clearSavedRideState()

        // Auto-toggle to go online
        toggleAvailability(location)
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
            _uiState.value = _uiState.value.copy(isProcessingOffer = true)

            // Track this offer as accepted (so it won't show up again after ride completion)
            acceptedOfferEventIds.add(offer.eventId)

            // PIN is now generated by rider - driver will ask rider for PIN at pickup
            val eventId = nostrService.acceptRide(offer)

            if (eventId != null) {
                Log.d(TAG, "Accepted ride offer: $eventId")
                // Stop broadcasting availability but stay in "ride" mode, not offline
                stopBroadcasting()
                deleteAllAvailabilityEvents()

                _uiState.value = _uiState.value.copy(
                    stage = DriverStage.RIDE_ACCEPTED,
                    isProcessingOffer = false,
                    acceptedOffer = offer,
                    acceptanceEventId = eventId,  // Track for cleanup later
                    pendingOffers = emptyList(), // Clear all pending offers
                    pinAttempts = 0,
                    confirmationWaitStartMs = System.currentTimeMillis(),  // Start confirmation timer
                    statusMessage = "Ride accepted! Waiting for rider confirmation..."
                )

                // Save ride state for persistence
                saveRideState()

                // Subscribe to rider's confirmation to get precise pickup location
                // Note: Confirmation references our acceptance event ID, not the offer ID
                subscribeToConfirmation(eventId)

                // Subscribe to PIN verification responses
                subscribeToVerifications()
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
            // Send status update to rider
            state.confirmationEventId?.let { confId ->
                state.acceptedOffer?.riderPubKey?.let { riderPubKey ->
                    val eventId = nostrService.sendStatusUpdate(
                        confirmationEventId = confId,
                        riderPubKey = riderPubKey,
                        status = DriverStatusType.EN_ROUTE_PICKUP,
                        location = state.currentLocation
                    )
                    // Track for cleanup on ride completion
                    eventId?.let { statusEventIds.add(it) }
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
        if (state.stage != DriverStage.EN_ROUTE_TO_PICKUP) return
        val context = getApplication<Application>()

        viewModelScope.launch {
            state.confirmationEventId?.let { confId ->
                state.acceptedOffer?.riderPubKey?.let { riderPubKey ->
                    val eventId = nostrService.sendStatusUpdate(
                        confirmationEventId = confId,
                        riderPubKey = riderPubKey,
                        status = DriverStatusType.ARRIVED,
                        location = state.currentLocation
                    )
                    // Track for cleanup on ride completion
                    eventId?.let { statusEventIds.add(it) }
                }
            }

            // Notify service of arrival (updates notification)
            val riderName: String? = null // RideOfferData doesn't contain rider name
            DriverOnlineService.updateStatus(context, DriverStatus.ArrivedAtPickup(riderName))

            _uiState.value = state.copy(
                stage = DriverStage.ARRIVED_AT_PICKUP,
                statusMessage = "Arrived! Ask rider for their PIN"
            )

            // Save ride state for persistence
            saveRideState()
        }
    }

    /**
     * Submit the PIN heard from the rider for verification.
     * The rider's app will verify and send a response.
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

            val eventId = nostrService.submitPin(
                confirmationEventId = confirmationEventId,
                riderPubKey = riderPubKey,
                submittedPin = enteredPin
            )

            if (eventId != null) {
                Log.d(TAG, "Submitted PIN for verification: $eventId")
                // Track for cleanup on ride completion
                pinSubmissionEventId = eventId
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

        // Cancel timeout jobs if running
        pinVerificationTimeoutJob?.cancel()
        pinVerificationTimeoutJob = null
        confirmationTimeoutJob?.cancel()
        confirmationTimeoutJob = null

        viewModelScope.launch {
            // Send cancellation event if we have an active ride
            if (confirmationEventId != null && riderPubKey != null) {
                nostrService.publishRideCancellation(
                    confirmationEventId = confirmationEventId,
                    otherPartyPubKey = riderPubKey,
                    reason = reason
                )
                Log.d(TAG, "Sent cancellation event for ride: $confirmationEventId")
            }

            // Clean up all subscriptions
            confirmationSubscriptionId?.let { nostrService.closeSubscription(it) }
            confirmationSubscriptionId = null
            verificationSubscriptionId?.let { nostrService.closeSubscription(it) }
            verificationSubscriptionId = null
            chatSubscriptionId?.let { nostrService.closeSubscription(it) }
            chatSubscriptionId = null
            cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
            cancellationSubscriptionId = null
            preciseLocationSubscriptionId?.let { nostrService.closeSubscription(it) }
            preciseLocationSubscriptionId = null
            chatRefreshJob?.cancel()
            chatRefreshJob = null

            // Stop the foreground service
            DriverOnlineService.stop(getApplication())

            // Reset state
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

            // Clear persisted ride state
            clearSavedRideState()
        }
    }

    /**
     * Subscribe to PIN verification responses from the rider.
     */
    private fun subscribeToVerifications() {
        verificationSubscriptionId?.let { nostrService.closeSubscription(it) }

        verificationSubscriptionId = nostrService.subscribeToPinVerifications { verification ->
            Log.d(TAG, "Received PIN verification: verified=${verification.verified}, attempt=${verification.attemptNumber}")
            handlePinVerification(verification)
        }
    }

    /**
     * Handle a PIN verification response from the rider.
     */
    private fun handlePinVerification(verification: PickupVerificationData) {
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

        _uiState.value = state.copy(
            isAwaitingPinVerification = false,
            pinVerificationTimedOut = false,
            pinAttempts = verification.attemptNumber
        )

        if (verification.verified) {
            Log.d(TAG, "PIN verified! Starting ride.")
            startRide()
        } else {
            Log.w(TAG, "PIN rejected! Attempt ${verification.attemptNumber}")
            val remainingAttempts = 3 - verification.attemptNumber
            if (remainingAttempts <= 0) {
                // Rider cancelled due to brute force protection
                confirmationSubscriptionId?.let { nostrService.closeSubscription(it) }
                confirmationSubscriptionId = null
                verificationSubscriptionId?.let { nostrService.closeSubscription(it) }
                verificationSubscriptionId = null
                chatSubscriptionId?.let { nostrService.closeSubscription(it) }
                chatSubscriptionId = null
                cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
                cancellationSubscriptionId = null
                preciseLocationSubscriptionId?.let { nostrService.closeSubscription(it) }
                preciseLocationSubscriptionId = null

                // Stop the foreground service
                val context = getApplication<Application>()
                DriverOnlineService.stop(context)

                _uiState.value = _uiState.value.copy(
                    stage = DriverStage.OFFLINE,
                    acceptedOffer = null,
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
     * Start the ride (rider is in vehicle).
     */
    private fun startRide() {
        val state = _uiState.value
        val context = getApplication<Application>()

        viewModelScope.launch {
            state.confirmationEventId?.let { confId ->
                state.acceptedOffer?.riderPubKey?.let { riderPubKey ->
                    val eventId = nostrService.sendStatusUpdate(
                        confirmationEventId = confId,
                        riderPubKey = riderPubKey,
                        status = DriverStatusType.IN_PROGRESS,
                        location = state.currentLocation
                    )
                    // Track for cleanup on ride completion
                    eventId?.let { statusEventIds.add(it) }
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
     * Complete the ride (arrived at destination).
     */
    fun completeRide() {
        val state = _uiState.value
        if (state.stage != DriverStage.IN_RIDE) return

        val offer = state.acceptedOffer ?: return

        // Stop chat refresh job since ride is ending
        stopChatRefreshJob()

        viewModelScope.launch {
            state.confirmationEventId?.let { confId ->
                val eventId = nostrService.sendStatusUpdate(
                    confirmationEventId = confId,
                    riderPubKey = offer.riderPubKey,
                    status = DriverStatusType.COMPLETED,
                    location = state.currentLocation,
                    finalFare = offer.fareEstimate
                )
                // Track for cleanup on ride completion
                eventId?.let { statusEventIds.add(it) }
            }

            _uiState.value = state.copy(
                stage = DriverStage.RIDE_COMPLETED,
                statusMessage = "Ride completed! Fare: ${offer.fareEstimate.toInt()} sats"
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

        confirmationSubscriptionId = nostrService.subscribeToConfirmation(acceptanceEventId) { confirmation ->
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

            // Subscribe to chat messages for this ride
            subscribeToChatMessages(confirmation.eventId)
            // Start periodic refresh to ensure messages are received
            startChatRefreshJob(confirmation.eventId)

            // Subscribe to cancellation events from rider
            subscribeToCancellation(confirmation.eventId)

            // Subscribe to precise location reveals (rider shares precise pickup/destination when close)
            subscribeToPreciseLocationReveals(confirmation.eventId)

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
        verificationSubscriptionId?.let { nostrService.closeSubscription(it) }
        verificationSubscriptionId = null

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
            // Send status update to rider
            val eventId = nostrService.sendStatusUpdate(
                confirmationEventId = confirmationEventId,
                riderPubKey = riderPubKey,
                status = DriverStatusType.EN_ROUTE_PICKUP,
                location = state.currentLocation
            )
            // Track for cleanup on ride completion
            eventId?.let { statusEventIds.add(it) }

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
        chatSubscriptionId = nostrService.subscribeToChatMessages(confirmationEventId) { chatData ->
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

                // Notify service of chat message (plays sound, updates notification temporarily)
                val myPubKey = nostrService.getPubKeyHex() ?: ""
                if (chatData.senderPubKey != myPubKey) {
                    val context = getApplication<Application>()
                    DriverOnlineService.updateStatus(
                        context,
                        DriverStatus.ChatReceived(
                            preview = chatData.message,
                            previousStatus = DriverStatus.RideInProgress(null) // Service tracks actual status
                        )
                    )
                    Log.d(TAG, "Chat message received - notified service")
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
                Log.d(TAG, "Refreshing chat subscription")
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

        Log.d(TAG, "Subscribing to cancellation events for confirmation: ${confirmationEventId.take(8)}")

        val newSubId = nostrService.subscribeToCancellation(confirmationEventId) { cancellation ->
            Log.d(TAG, "Received ride cancellation from rider: ${cancellation.reason ?: "no reason"}")

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
     * Subscribe to precise location reveals from rider.
     * Rider sends precise pickup when driver is close (~1 mile),
     * and precise destination after PIN is verified.
     */
    private fun subscribeToPreciseLocationReveals(confirmationEventId: String) {
        preciseLocationSubscriptionId?.let { nostrService.closeSubscription(it) }

        Log.d(TAG, "Subscribing to precise location reveals for confirmation: ${confirmationEventId.take(8)}")

        val newSubId = nostrService.subscribeToPreciseLocationReveals(confirmationEventId) { revealData ->
            Log.d(TAG, "Received precise location reveal: ${revealData.locationType}")
            Log.d(TAG, "Precise location: ${revealData.preciseLocation.lat}, ${revealData.preciseLocation.lon}")

            when (revealData.locationType) {
                PreciseLocationRevealEvent.LOCATION_TYPE_PICKUP -> {
                    Log.d(TAG, "Updating navigation to precise pickup location")
                    _uiState.value = _uiState.value.copy(
                        precisePickupLocation = revealData.preciseLocation,
                        statusMessage = "Received precise pickup location"
                    )
                }
                PreciseLocationRevealEvent.LOCATION_TYPE_DESTINATION -> {
                    Log.d(TAG, "Received precise destination location")
                    _uiState.value = _uiState.value.copy(
                        preciseDestinationLocation = revealData.preciseLocation,
                        statusMessage = "Received precise destination"
                    )
                }
                else -> {
                    Log.w(TAG, "Unknown location type: ${revealData.locationType}")
                }
            }
        }

        if (newSubId != null) {
            preciseLocationSubscriptionId = newSubId
            Log.d(TAG, "Precise location reveal subscription created: $newSubId")
        } else {
            Log.e(TAG, "Failed to create precise location reveal subscription - not logged in?")
        }
    }

    /**
     * Handle ride cancellation from rider.
     */
    private fun handleRideCancellation(reason: String?) {
        val state = _uiState.value
        val context = getApplication<Application>()

        // Notify service of cancellation (plays sound, updates notification)
        DriverOnlineService.updateStatus(context, DriverStatus.Cancelled)
        Log.d(TAG, "Ride cancelled - notified service")

        // Close active subscriptions
        confirmationSubscriptionId?.let { nostrService.closeSubscription(it) }
        confirmationSubscriptionId = null
        verificationSubscriptionId?.let { nostrService.closeSubscription(it) }
        verificationSubscriptionId = null
        chatSubscriptionId?.let { nostrService.closeSubscription(it) }
        chatSubscriptionId = null
        cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
        cancellationSubscriptionId = null
        preciseLocationSubscriptionId?.let { nostrService.closeSubscription(it) }
        preciseLocationSubscriptionId = null

        // Clear persisted ride state
        clearSavedRideState()

        // Return to available state (if they were online) or offline
        val newStage = if (state.currentLocation != null) DriverStage.AVAILABLE else DriverStage.OFFLINE

        // Stop the foreground service if going offline
        if (newStage == DriverStage.OFFLINE) {
            DriverOnlineService.stop(context)
        }

        _uiState.value = state.copy(
            stage = newStage,
            acceptedOffer = null,
            acceptedBroadcastRequest = null,
            confirmationEventId = null,
            precisePickupLocation = null,
            isAwaitingPinVerification = false,
            chatMessages = emptyList(),
            pendingBroadcastRequests = emptyList(),
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
            val state = _uiState.value
            val context = getApplication<Application>()

            // Stop chat refresh job
            stopChatRefreshJob()

            // Close subscriptions
            confirmationSubscriptionId?.let { nostrService.closeSubscription(it) }
            confirmationSubscriptionId = null
            verificationSubscriptionId?.let { nostrService.closeSubscription(it) }
            verificationSubscriptionId = null
            chatSubscriptionId?.let { nostrService.closeSubscription(it) }
            chatSubscriptionId = null
            cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
            cancellationSubscriptionId = null
            preciseLocationSubscriptionId?.let { nostrService.closeSubscription(it) }
            preciseLocationSubscriptionId = null

            // Clean up our acceptance event (NIP-09)
            viewModelScope.launch {
                state.acceptanceEventId?.let { acceptanceId ->
                    Log.d(TAG, "Requesting deletion of acceptance event: $acceptanceId")
                    nostrService.deleteEvent(acceptanceId, "ride completed")
                }
            }

            // Stop the foreground service since driver is going offline
            DriverOnlineService.stop(context)

            _uiState.value = state.copy(
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
                statusMessage = "Tap to go online"
            )

            // Clear persisted ride state
            clearSavedRideState()
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun startBroadcasting(location: Location) {
        availabilityJob?.cancel()

        // Initialize throttle tracking for first broadcast
        if (lastBroadcastLocation == null) {
            lastBroadcastLocation = location
            lastBroadcastTimeMs = System.currentTimeMillis()
        }

        availabilityJob = viewModelScope.launch {
            while (isActive) {
                val currentLocation = _uiState.value.currentLocation ?: location
                val activeVehicle = _uiState.value.activeVehicle

                // Track this broadcast for throttling
                lastBroadcastLocation = currentLocation
                lastBroadcastTimeMs = System.currentTimeMillis()

                val previousEventId = publishedAvailabilityEventIds.lastOrNull()
                if (previousEventId != null) {
                    nostrService.deleteEvent(previousEventId, "superseded")
                    Log.d(TAG, "Requested deletion of previous availability: $previousEventId")
                }

                val eventId = nostrService.broadcastAvailability(currentLocation, activeVehicle)

                if (eventId != null) {
                    Log.d(TAG, "Broadcast availability: $eventId")
                    publishedAvailabilityEventIds.add(eventId)
                    _uiState.value = _uiState.value.copy(
                        lastBroadcastTime = System.currentTimeMillis()
                    )
                } else {
                    Log.w(TAG, "Failed to broadcast availability")
                }

                delay(AVAILABILITY_BROADCAST_INTERVAL_MS)
            }
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

        offerSubscriptionId = nostrService.subscribeToOffers { offer ->
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
            _uiState.value = _uiState.value.copy(isProcessingOffer = true)

            // Track this request as accepted
            acceptedOfferEventIds.add(request.eventId)

            val eventId = nostrService.acceptBroadcastRide(request)

            if (eventId != null) {
                Log.d(TAG, "Accepted broadcast ride request: $eventId")
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
                    createdAt = request.createdAt
                )

                _uiState.value = _uiState.value.copy(
                    stage = DriverStage.RIDE_ACCEPTED,
                    isProcessingOffer = false,
                    acceptedOffer = compatibleOffer,
                    acceptedBroadcastRequest = request,  // Keep original for reference
                    acceptanceEventId = eventId,
                    pendingOffers = emptyList(),
                    pendingBroadcastRequests = emptyList(),
                    pinAttempts = 0,
                    confirmationWaitStartMs = System.currentTimeMillis(),  // Start confirmation timer
                    statusMessage = "Ride accepted! Waiting for rider confirmation..."
                )

                // Save ride state for persistence
                saveRideState()

                // Subscribe to rider's confirmation
                subscribeToConfirmation(eventId)

                // Subscribe to PIN verification responses
                subscribeToVerifications()
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
        confirmationTimeoutJob?.cancel()
        offerSubscriptionId?.let { nostrService.closeSubscription(it) }
        broadcastRequestSubscriptionId?.let { nostrService.closeSubscription(it) }
        deletionSubscriptionId?.let { nostrService.closeSubscription(it) }
        requestAcceptanceSubscriptionIds.values.forEach { nostrService.closeSubscription(it) }
        confirmationSubscriptionId?.let { nostrService.closeSubscription(it) }
        verificationSubscriptionId?.let { nostrService.closeSubscription(it) }
        chatSubscriptionId?.let { nostrService.closeSubscription(it) }
        cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
        preciseLocationSubscriptionId?.let { nostrService.closeSubscription(it) }
        nostrService.disconnect()
        // Clean up Bitcoin price service
        bitcoinPriceService.cleanup()
    }
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
    val confirmationWaitDurationMs: Long = 15_000L,     // 15 seconds to wait for confirmation

    // Chat (NIP-17 style private messaging)
    val chatMessages: List<RideshareChatData> = emptyList(),
    val isSendingMessage: Boolean = false,

    // User identity
    val myPubKey: String = "",

    // UI
    val statusMessage: String = "Tap to go online",
    val error: String? = null
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
