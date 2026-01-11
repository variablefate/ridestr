package com.drivestr.app.viewmodels

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.DriverStatusType
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.events.PickupVerificationData
import com.ridestr.common.nostr.events.RideConfirmationData
import com.ridestr.common.nostr.events.RideOfferData
import com.ridestr.common.nostr.events.RideshareChatData
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
        // SharedPreferences keys for ride state persistence
        private const val PREFS_NAME = "drivestr_ride_state"
        private const val KEY_RIDE_STATE = "active_ride"
        // Maximum age of persisted ride state (2 hours)
        private const val MAX_RIDE_STATE_AGE_MS = 2 * 60 * 60 * 1000L
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val nostrService = NostrService(application)

    private val _uiState = MutableStateFlow(DriverUiState())
    val uiState: StateFlow<DriverUiState> = _uiState.asStateFlow()

    private var availabilityJob: Job? = null
    private var chatRefreshJob: Job? = null
    private var offerSubscriptionId: String? = null
    private var confirmationSubscriptionId: String? = null
    private var verificationSubscriptionId: String? = null
    private var chatSubscriptionId: String? = null
    private var cancellationSubscriptionId: String? = null
    private val publishedAvailabilityEventIds = mutableListOf<String>()
    // Track accepted offer IDs to filter out when resubscribing (avoids duplicate offers after ride completion)
    private val acceptedOfferEventIds = mutableSetOf<String>()

    init {
        nostrService.connect()

        // Set user's public key
        nostrService.getPubKeyHex()?.let { pubKey ->
            _uiState.value = _uiState.value.copy(myPubKey = pubKey)
        }

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

    fun toggleAvailability(location: Location) {
        val currentState = _uiState.value

        if (currentState.stage == DriverStage.AVAILABLE) {
            // Go offline
            viewModelScope.launch {
                val lastLocation = currentState.currentLocation ?: location
                val eventId = nostrService.broadcastOffline(lastLocation)
                if (eventId != null) {
                    Log.d(TAG, "Broadcast offline status: $eventId")
                }
                deleteAllAvailabilityEvents()
            }
            stopBroadcasting()
            closeOfferSubscription()
            _uiState.value = currentState.copy(
                stage = DriverStage.OFFLINE,
                currentLocation = null,
                statusMessage = "You are now offline"
            )
        } else if (currentState.stage == DriverStage.OFFLINE) {
            // Go online
            startBroadcasting(location)
            subscribeToOffers()
            _uiState.value = currentState.copy(
                stage = DriverStage.AVAILABLE,
                currentLocation = location,
                statusMessage = "You are now available for rides"
            )
        }
        // Don't allow toggling during a ride
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

            // Clean up our acceptance event (NIP-09)
            state.acceptanceEventId?.let { acceptanceId ->
                Log.d(TAG, "Requesting deletion of acceptance event: $acceptanceId")
                nostrService.deleteEvent(acceptanceId, "ride cancelled")
            }
        }

        // Return to offline state
        _uiState.value = state.copy(
            stage = DriverStage.OFFLINE,
            acceptedOffer = null,
            acceptanceEventId = null,
            confirmationEventId = null,
            precisePickupLocation = null,
            pinAttempts = 0,
            isAwaitingPinVerification = false,
            lastPinSubmissionEventId = null,
            chatMessages = emptyList(),
            isSendingMessage = false,
            pendingOffers = emptyList(),
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

        // Clean up events (NIP-09): acceptance event and our sent chat messages
        viewModelScope.launch {
            val myPubKey = state.myPubKey
            val eventsToDelete = mutableListOf<String>()

            // Add acceptance event
            state.acceptanceEventId?.let { eventsToDelete.add(it) }

            // Add chat messages we sent (only delete our own)
            state.chatMessages
                .filter { it.senderPubKey == myPubKey }
                .forEach { eventsToDelete.add(it.eventId) }

            if (eventsToDelete.isNotEmpty()) {
                Log.d(TAG, "Requesting deletion of ${eventsToDelete.size} events (acceptance + chat)")
                nostrService.deleteEvents(eventsToDelete, "ride completed")
            }
        }

        _uiState.value = state.copy(
            stage = DriverStage.OFFLINE,
            acceptedOffer = null,
            acceptanceEventId = null,
            confirmationEventId = null,
            precisePickupLocation = null,
            pinAttempts = 0,
            isAwaitingPinVerification = false,
            lastPinSubmissionEventId = null,
            chatMessages = emptyList(),
            isSendingMessage = false,
            pendingOffers = emptyList(),
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
    }

    fun updateLocation(location: Location) {
        // Update location when available or during a ride
        if (_uiState.value.stage != DriverStage.OFFLINE) {
            _uiState.value = _uiState.value.copy(currentLocation = location)
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
                    nostrService.sendStatusUpdate(
                        confirmationEventId = confId,
                        riderPubKey = riderPubKey,
                        status = DriverStatusType.EN_ROUTE_PICKUP,
                        location = state.currentLocation
                    )
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

        viewModelScope.launch {
            state.confirmationEventId?.let { confId ->
                state.acceptedOffer?.riderPubKey?.let { riderPubKey ->
                    nostrService.sendStatusUpdate(
                        confirmationEventId = confId,
                        riderPubKey = riderPubKey,
                        status = DriverStatusType.ARRIVED,
                        location = state.currentLocation
                    )
                }
            }

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
                _uiState.value = _uiState.value.copy(
                    lastPinSubmissionEventId = eventId,
                    statusMessage = "Waiting for rider to verify PIN..."
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isAwaitingPinVerification = false,
                    error = "Failed to submit PIN"
                )
            }
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
        val state = _uiState.value

        // Only process if we're at pickup AND we actually submitted a PIN
        if (state.stage != DriverStage.ARRIVED_AT_PICKUP) {
            Log.d(TAG, "Ignoring verification - not at pickup (stage=${state.stage})")
            return
        }

        if (!state.isAwaitingPinVerification) {
            Log.d(TAG, "Ignoring verification - not awaiting verification (stale event?)")
            return
        }

        _uiState.value = state.copy(
            isAwaitingPinVerification = false,
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

        viewModelScope.launch {
            state.confirmationEventId?.let { confId ->
                state.acceptedOffer?.riderPubKey?.let { riderPubKey ->
                    nostrService.sendStatusUpdate(
                        confirmationEventId = confId,
                        riderPubKey = riderPubKey,
                        status = DriverStatusType.IN_PROGRESS,
                        location = state.currentLocation
                    )
                }
            }

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
                nostrService.sendStatusUpdate(
                    confirmationEventId = confId,
                    riderPubKey = offer.riderPubKey,
                    status = DriverStatusType.COMPLETED,
                    location = state.currentLocation,
                    finalFare = offer.fareEstimate
                )
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

        confirmationSubscriptionId = nostrService.subscribeToConfirmation(acceptanceEventId) { confirmation ->
            Log.d(TAG, "Received ride confirmation: ${confirmation.eventId}")
            Log.d(TAG, "Precise pickup: ${confirmation.precisePickup.lat}, ${confirmation.precisePickup.lon}")

            val currentStage = _uiState.value.stage

            // Only process if we're still waiting for confirmation (RIDE_ACCEPTED stage)
            // This prevents re-triggering auto-transition when receiving duplicate events
            // (e.g., from relay history when returning to foreground)
            if (currentStage != DriverStage.RIDE_ACCEPTED) {
                Log.d(TAG, "Ignoring confirmation - already in stage $currentStage")
                return@subscribeToConfirmation
            }

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

            // Auto-transition to EN_ROUTE_TO_PICKUP (skip the intermediate screen)
            autoStartRouteToPickup(confirmation.eventId)
        }
    }

    /**
     * Automatically start route to pickup when confirmation is received.
     * This removes the need for driver to manually tap "Start Route to Pickup".
     */
    private fun autoStartRouteToPickup(confirmationEventId: String) {
        val state = _uiState.value
        val riderPubKey = state.acceptedOffer?.riderPubKey ?: return

        viewModelScope.launch {
            // Send status update to rider
            nostrService.sendStatusUpdate(
                confirmationEventId = confirmationEventId,
                riderPubKey = riderPubKey,
                status = DriverStatusType.EN_ROUTE_PICKUP,
                location = state.currentLocation
            )

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
     * Handle ride cancellation from rider.
     */
    private fun handleRideCancellation(reason: String?) {
        val state = _uiState.value

        // Close active subscriptions
        confirmationSubscriptionId?.let { nostrService.closeSubscription(it) }
        confirmationSubscriptionId = null
        verificationSubscriptionId?.let { nostrService.closeSubscription(it) }
        verificationSubscriptionId = null
        chatSubscriptionId?.let { nostrService.closeSubscription(it) }
        chatSubscriptionId = null
        cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
        cancellationSubscriptionId = null

        // Clear persisted ride state
        clearSavedRideState()

        // Return to available state (if they were online) or offline
        val newStage = if (state.currentLocation != null) DriverStage.AVAILABLE else DriverStage.OFFLINE

        _uiState.value = state.copy(
            stage = newStage,
            acceptedOffer = null,
            confirmationEventId = null,
            precisePickupLocation = null,
            isAwaitingPinVerification = false,
            chatMessages = emptyList(),
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

            // Clean up our acceptance event (NIP-09)
            viewModelScope.launch {
                state.acceptanceEventId?.let { acceptanceId ->
                    Log.d(TAG, "Requesting deletion of acceptance event: $acceptanceId")
                    nostrService.deleteEvent(acceptanceId, "ride completed")
                }
            }

            _uiState.value = state.copy(
                stage = DriverStage.OFFLINE,
                acceptedOffer = null,
                acceptanceEventId = null,
                confirmationEventId = null,
                precisePickupLocation = null,
                pinAttempts = 0,
                isAwaitingPinVerification = false,
                lastPinSubmissionEventId = null,
                chatMessages = emptyList(),
                isSendingMessage = false,
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
        availabilityJob = viewModelScope.launch {
            while (isActive) {
                val currentLocation = _uiState.value.currentLocation ?: location

                val previousEventId = publishedAvailabilityEventIds.lastOrNull()
                if (previousEventId != null) {
                    nostrService.deleteEvent(previousEventId, "superseded")
                    Log.d(TAG, "Requested deletion of previous availability: $previousEventId")
                }

                val eventId = nostrService.broadcastAvailability(currentLocation)

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

            // Filter out offers older than 10 minutes (stale offers)
            val currentTimeSeconds = System.currentTimeMillis() / 1000
            val offerAgeSeconds = currentTimeSeconds - offer.createdAt
            val maxOfferAgeSeconds = 10 * 60 // 10 minutes

            if (offerAgeSeconds > maxOfferAgeSeconds) {
                Log.d(TAG, "Ignoring stale offer (${offerAgeSeconds}s old)")
                return@subscribeToOffers
            }

            val currentOffers = _uiState.value.pendingOffers
            if (currentOffers.none { it.eventId == offer.eventId }) {
                // Also filter out any existing stale offers while adding the new one
                val freshOffers = currentOffers.filter {
                    (currentTimeSeconds - it.createdAt) <= maxOfferAgeSeconds
                }
                // Sort by createdAt descending (newest first)
                val sortedOffers = (freshOffers + offer).sortedByDescending { it.createdAt }
                _uiState.value = _uiState.value.copy(
                    pendingOffers = sortedOffers
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopBroadcasting()
        offerSubscriptionId?.let { nostrService.closeSubscription(it) }
        confirmationSubscriptionId?.let { nostrService.closeSubscription(it) }
        verificationSubscriptionId?.let { nostrService.closeSubscription(it) }
        chatSubscriptionId?.let { nostrService.closeSubscription(it) }
        cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
        nostrService.disconnect()
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

    // Ride offers (only shown when AVAILABLE)
    val pendingOffers: List<RideOfferData> = emptyList(),
    val isProcessingOffer: Boolean = false,

    // Active ride info
    val acceptedOffer: RideOfferData? = null,
    val acceptanceEventId: String? = null,  // Our acceptance event (Kind 3174) for cleanup
    val confirmationEventId: String? = null,
    val precisePickupLocation: Location? = null,

    // PIN verification (rider generates PIN, driver submits for verification)
    val pinAttempts: Int = 0,                           // Number of PIN submission attempts
    val isAwaitingPinVerification: Boolean = false,     // Waiting for rider to verify
    val lastPinSubmissionEventId: String? = null,       // Last PIN submission event ID

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
