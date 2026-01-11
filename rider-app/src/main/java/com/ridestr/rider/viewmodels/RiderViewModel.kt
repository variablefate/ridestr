package com.ridestr.rider.viewmodels

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.DriverAvailabilityData
import com.ridestr.common.nostr.events.DriverStatusData
import com.ridestr.common.nostr.events.Geohash
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.events.PinSubmissionData
import com.ridestr.common.nostr.events.RideAcceptanceData
import com.ridestr.common.nostr.events.RideshareChatData
import com.ridestr.common.nostr.events.UserProfile
import com.ridestr.common.nostr.events.geohash
import kotlin.random.Random
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
 * ViewModel for Rider mode.
 * Manages available drivers, route calculation, and ride requests.
 */
class RiderViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "RiderViewModel"
        // Base fare in sats per km
        private const val FARE_PER_KM = 100.0
        // Base fare minimum
        private const val BASE_FARE = 500.0
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
        // Time to wait for driver to accept before timeout (20 seconds)
        private const val ACCEPTANCE_TIMEOUT_MS = 20_000L
    }

    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val nostrService = NostrService(application)
    private val routingService = ValhallaRoutingService(application)

    private val _uiState = MutableStateFlow(RiderUiState())
    val uiState: StateFlow<RiderUiState> = _uiState.asStateFlow()

    private var driverSubscriptionId: String? = null
    private var acceptanceSubscriptionId: String? = null
    private var pinSubmissionSubscriptionId: String? = null
    private var chatSubscriptionId: String? = null
    private var cancellationSubscriptionId: String? = null
    private var statusSubscriptionId: String? = null
    private var staleDriverCleanupJob: Job? = null
    private var chatRefreshJob: Job? = null
    private var acceptanceTimeoutJob: Job? = null
    private val profileSubscriptionIds = mutableMapOf<String, String>()
    private var currentSubscriptionGeohash: String? = null

    init {
        // Connect to relays and subscribe to drivers
        nostrService.connect()
        subscribeToDrivers()
        startStaleDriverCleanup()

        // Set user's public key
        nostrService.getPubKeyHex()?.let { pubKey ->
            _uiState.value = _uiState.value.copy(myPubKey = pubKey)
        }

        // Initialize routing service
        viewModelScope.launch {
            val success = routingService.initialize()
            _uiState.value = _uiState.value.copy(
                isRoutingReady = success
            )
            if (!success) {
                Log.w(TAG, "Routing service failed to initialize")
            }
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
        val acceptance = state.acceptance
        if (confId != null && state.rideStage in listOf(
                RideStage.RIDE_CONFIRMED,
                RideStage.IN_PROGRESS
            )) {
            Log.d(TAG, "Refreshing subscriptions for confirmation: $confId")
            subscribeToChatMessages(confId)
            subscribeToCancellation(confId)
            subscribeToStatusUpdates(confId)
            // Restart the periodic chat refresh job
            startChatRefreshJob(confId)

            // Also refresh PIN subscription if not yet verified
            if (!state.pinVerified && acceptance != null) {
                Log.d(TAG, "Refreshing PIN submission subscription")
                subscribeToPinSubmissions(confId, acceptance.driverPubKey)
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
                put("destLat", destination.lat)
                put("destLon", destination.lon)

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

            // Reconstruct locations
            val pickup = Location(
                lat = data.getDouble("pickupLat"),
                lon = data.getDouble("pickupLon")
            )
            val destination = Location(
                lat = data.getDouble("destLat"),
                lon = data.getDouble("destLon")
            )

            val pendingOfferEventId: String? = if (data.has("pendingOfferEventId")) data.getString("pendingOfferEventId") else null
            val confirmationEventId: String? = if (data.has("confirmationEventId")) data.getString("confirmationEventId") else null
            val pickupPin: String? = if (data.has("pickupPin")) data.getString("pickupPin") else null
            val pinAttempts = data.optInt("pinAttempts", 0)
            val pinVerified = data.optBoolean("pinVerified", false)
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
                subscribeToStatusUpdates(confirmationEventId)
                // Start periodic chat refresh
                startChatRefreshJob(confirmationEventId)
                if (!pinVerified) {
                    subscribeToPinSubmissions(confirmationEventId, acceptance.driverPubKey)
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
    private fun getStatusMessageForStage(stage: RideStage, pin: String?): String {
        return when (stage) {
            RideStage.IDLE -> "Find available drivers"
            RideStage.WAITING_FOR_ACCEPTANCE -> "Waiting for driver to accept..."
            RideStage.DRIVER_ACCEPTED -> "Driver accepted! Confirming ride..."
            RideStage.RIDE_CONFIRMED -> "Ride confirmed! Tell driver PIN: ${pin ?: "N/A"}"
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
            driverProfiles = emptyMap()
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
     */
    fun sendRideOffer() {
        val state = _uiState.value
        val driver = state.selectedDriver ?: return
        val pickup = state.pickupLocation ?: return
        val destination = state.destination ?: return
        val fareEstimate = state.fareEstimate ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSendingOffer = true)

            val eventId = nostrService.sendRideOffer(
                driverAvailability = driver,
                pickup = pickup,
                destination = destination,
                fareEstimate = fareEstimate
            )

            if (eventId != null) {
                Log.d(TAG, "Sent ride offer: $eventId")
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
        val state = _uiState.value

        // Cancel the acceptance timeout
        cancelAcceptanceTimeout()

        acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }
        acceptanceSubscriptionId = null

        // Clean up our offer event (NIP-09)
        viewModelScope.launch {
            state.pendingOfferEventId?.let { offerId ->
                Log.d(TAG, "Requesting deletion of offer event: $offerId")
                nostrService.deleteEvent(offerId, "offer cancelled")
            }
        }

        _uiState.value = state.copy(
            pendingOfferEventId = null,
            rideStage = RideStage.IDLE,
            acceptanceTimeoutStartMs = null,
            statusMessage = "Offer cancelled"
        )

        // Clear persisted ride state (in case any was saved)
        clearSavedRideState()
    }

    /**
     * Confirm the ride after driver accepts (would send precise location).
     */
    fun confirmRide() {
        val state = _uiState.value
        val acceptance = state.acceptance ?: return
        val pickup = state.pickupLocation ?: return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isConfirmingRide = true)

            val eventId = nostrService.confirmRide(
                acceptance = acceptance,
                precisePickup = pickup
            )

            if (eventId != null) {
                Log.d(TAG, "Confirmed ride: $eventId")

                // Close acceptance subscription - we don't need it anymore
                // This prevents duplicate acceptance events from affecting state
                acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }
                acceptanceSubscriptionId = null

                // Subscribe to cancellation events from driver
                subscribeToCancellation(eventId)

                _uiState.value = _uiState.value.copy(
                    isConfirmingRide = false,
                    confirmationEventId = eventId,  // Track for cleanup later
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

        pinSubmissionSubscriptionId?.let { nostrService.closeSubscription(it) }
        pinSubmissionSubscriptionId = null

        chatSubscriptionId?.let { nostrService.closeSubscription(it) }
        chatSubscriptionId = null

        cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
        cancellationSubscriptionId = null

        statusSubscriptionId?.let { nostrService.closeSubscription(it) }
        statusSubscriptionId = null

        // If we have an active confirmed ride, notify the driver of cancellation
        val confirmationId = state.confirmationEventId
        val driverPubKey = state.acceptance?.driverPubKey
        if (confirmationId != null && driverPubKey != null &&
            state.rideStage in listOf(RideStage.RIDE_CONFIRMED, RideStage.IN_PROGRESS)) {
            viewModelScope.launch {
                Log.d(TAG, "Publishing ride cancellation to driver")
                nostrService.publishRideCancellation(
                    confirmationEventId = confirmationId,
                    otherPartyPubKey = driverPubKey,
                    reason = "Rider cancelled"
                )
            }
        }

        // Clean up our events (NIP-09): ride events and our sent chat messages
        viewModelScope.launch {
            val eventsToDelete = mutableListOf<String>()
            val myPubKey = nostrService.getPubKeyHex() ?: ""

            // Add ride events
            state.pendingOfferEventId?.let { eventsToDelete.add(it) }
            state.confirmationEventId?.let { eventsToDelete.add(it) }

            // Add chat messages we sent (only delete our own)
            state.chatMessages
                .filter { it.senderPubKey == myPubKey }
                .forEach { eventsToDelete.add(it.eventId) }

            if (eventsToDelete.isNotEmpty()) {
                Log.d(TAG, "Requesting deletion of ${eventsToDelete.size} events (ride + chat)")
                nostrService.deleteEvents(eventsToDelete, "ride cancelled")
            }
        }

        _uiState.value = state.copy(
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

        // Clear persisted ride state
        clearSavedRideState()
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

            _uiState.value = _uiState.value.copy(availableDrivers = currentDrivers)
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
                selectedDriver = if (selectedStillExists) selectedDriver else null
            )
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

        viewModelScope.launch {
            val eventId = nostrService.confirmRide(
                acceptance = acceptance,
                precisePickup = pickup
            )

            if (eventId != null) {
                Log.d(TAG, "Auto-confirmed ride: $eventId")

                // Close acceptance subscription - we don't need it anymore
                acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }
                acceptanceSubscriptionId = null

                _uiState.value = _uiState.value.copy(
                    confirmationEventId = eventId,
                    pickupPin = pickupPin,
                    pinAttempts = 0,
                    rideStage = RideStage.RIDE_CONFIRMED,
                    statusMessage = "Ride confirmed! Tell driver PIN: $pickupPin"
                )

                // Save ride state for persistence
                saveRideState()

                // Subscribe to PIN submissions from the driver
                subscribeToPinSubmissions(eventId, acceptance.driverPubKey)

                // Subscribe to chat messages for this ride
                subscribeToChatMessages(eventId)
                // Start periodic refresh to ensure messages are received
                startChatRefreshJob(eventId)

                // Subscribe to driver status updates (for ride completion)
                subscribeToStatusUpdates(eventId)

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
     * Subscribe to PIN submissions from the driver.
     * Automatically verifies and sends response.
     */
    private fun subscribeToPinSubmissions(confirmationEventId: String, driverPubKey: String) {
        pinSubmissionSubscriptionId?.let { nostrService.closeSubscription(it) }

        pinSubmissionSubscriptionId = nostrService.subscribeToPinSubmissions(confirmationEventId) { submission ->
            Log.d(TAG, "Received PIN submission from driver: ${submission.submittedPin}")
            verifyPinSubmission(submission, driverPubKey)
        }
    }

    /**
     * Verify a PIN submission from the driver.
     * Sends verification response and handles brute force protection.
     */
    private fun verifyPinSubmission(submission: PinSubmissionData, driverPubKey: String) {
        val state = _uiState.value
        val expectedPin = state.pickupPin ?: return

        val newAttempts = state.pinAttempts + 1
        val isCorrect = submission.submittedPin == expectedPin

        viewModelScope.launch {
            // Send verification response
            nostrService.sendPinVerification(
                pinSubmissionEventId = submission.eventId,
                driverPubKey = driverPubKey,
                verified = isCorrect,
                attemptNumber = newAttempts
            )

            if (isCorrect) {
                Log.d(TAG, "PIN verified successfully!")
                // Close PIN subscription
                pinSubmissionSubscriptionId?.let { nostrService.closeSubscription(it) }
                pinSubmissionSubscriptionId = null

                _uiState.value = state.copy(
                    pinAttempts = newAttempts,
                    pinVerified = true,
                    rideStage = RideStage.IN_PROGRESS,
                    statusMessage = "PIN verified! Ride in progress."
                )

                // Save ride state for persistence
                saveRideState()
            } else {
                Log.w(TAG, "PIN incorrect! Attempt $newAttempts of $MAX_PIN_ATTEMPTS")

                if (newAttempts >= MAX_PIN_ATTEMPTS) {
                    // Brute force protection - cancel the ride
                    Log.e(TAG, "Max PIN attempts reached! Cancelling ride for security.")
                    pinSubmissionSubscriptionId?.let { nostrService.closeSubscription(it) }
                    pinSubmissionSubscriptionId = null

                    _uiState.value = state.copy(
                        pinAttempts = newAttempts,
                        rideStage = RideStage.IDLE,
                        statusMessage = "Ride cancelled - too many wrong PIN attempts",
                        error = "Security alert: Driver entered wrong PIN $MAX_PIN_ATTEMPTS times. Ride cancelled."
                    )

                    // Clean up events
                    state.confirmationEventId?.let { nostrService.deleteEvent(it, "security cancellation") }
                    state.pendingOfferEventId?.let { nostrService.deleteEvent(it, "security cancellation") }

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
     * Start periodic refresh to ensure messages and status updates are received.
     * Some relays may not push real-time events reliably.
     */
    private fun startChatRefreshJob(confirmationEventId: String) {
        stopChatRefreshJob()
        chatRefreshJob = viewModelScope.launch {
            while (isActive) {
                delay(CHAT_REFRESH_INTERVAL_MS)
                Log.d(TAG, "Refreshing chat and status subscriptions")
                subscribeToChatMessages(confirmationEventId)
                subscribeToStatusUpdates(confirmationEventId)
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
     */
    private fun handleAcceptanceTimeout() {
        val state = _uiState.value

        // Only timeout if we're still waiting for acceptance
        if (state.rideStage != RideStage.WAITING_FOR_ACCEPTANCE) {
            Log.d(TAG, "Acceptance timeout ignored - no longer waiting (stage=${state.rideStage})")
            return
        }

        Log.d(TAG, "Acceptance timeout - driver did not respond")

        // Close acceptance subscription
        acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }
        acceptanceSubscriptionId = null

        // Clean up our offer event (NIP-09)
        viewModelScope.launch {
            state.pendingOfferEventId?.let { offerId ->
                Log.d(TAG, "Requesting deletion of timed-out offer: $offerId")
                nostrService.deleteEvent(offerId, "offer timed out")
            }
        }

        _uiState.value = state.copy(
            pendingOfferEventId = null,
            selectedDriver = null,
            rideStage = RideStage.IDLE,
            acceptanceTimeoutStartMs = null,
            statusMessage = "Driver did not respond. Try another driver."
        )
    }

    /**
     * Subscribe to ride cancellation events from the driver.
     */
    private fun subscribeToCancellation(confirmationEventId: String) {
        cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }

        Log.d(TAG, "Subscribing to cancellation events for confirmation: ${confirmationEventId.take(8)}")

        val newSubId = nostrService.subscribeToCancellation(confirmationEventId) { cancellation ->
            Log.d(TAG, "Received ride cancellation from driver: ${cancellation.reason ?: "no reason"}")

            // Only process if we're in an active ride
            val currentStage = _uiState.value.rideStage
            if (currentStage !in listOf(RideStage.RIDE_CONFIRMED, RideStage.IN_PROGRESS)) {
                Log.d(TAG, "Ignoring cancellation - not in active ride (stage=$currentStage)")
                return@subscribeToCancellation
            }

            handleDriverCancellation(cancellation.reason)
        }

        if (newSubId != null) {
            cancellationSubscriptionId = newSubId
            Log.d(TAG, "Cancellation subscription created: $newSubId")
        } else {
            Log.e(TAG, "Failed to create cancellation subscription - not logged in?")
        }
    }

    /**
     * Subscribe to driver status updates (including ride completion).
     * Creates new subscription before closing old one to avoid gaps.
     */
    private fun subscribeToStatusUpdates(confirmationEventId: String) {
        val oldSubscriptionId = statusSubscriptionId

        Log.d(TAG, "Subscribing to status updates for confirmation: ${confirmationEventId.take(8)}")

        // Create new subscription FIRST (before closing old one to avoid gaps)
        val newSubId = nostrService.subscribeToStatusUpdates(confirmationEventId) { statusData ->
            Log.d(TAG, "Received driver status update: ${statusData.status}")

            when {
                statusData.isCompleted() -> {
                    Log.d(TAG, "Driver completed the ride! Final fare: ${statusData.finalFare}")
                    handleRideCompletion(statusData)
                }
                statusData.isCancelled() -> {
                    Log.d(TAG, "Driver cancelled via status update")
                    handleDriverCancellation("Driver ended the ride")
                }
                else -> {
                    Log.d(TAG, "Status update: ${statusData.status}")
                }
            }
        }

        if (newSubId != null) {
            statusSubscriptionId = newSubId
            Log.d(TAG, "Status subscription created: $newSubId")
        } else {
            Log.e(TAG, "Failed to create status subscription - not logged in?")
        }

        // Now close old subscription (after new one is active)
        oldSubscriptionId?.let { nostrService.closeSubscription(it) }
    }

    /**
     * Handle ride completion from driver.
     */
    private fun handleRideCompletion(statusData: DriverStatusData) {
        // Stop refresh jobs
        stopChatRefreshJob()

        // Close active subscriptions
        pinSubmissionSubscriptionId?.let { nostrService.closeSubscription(it) }
        pinSubmissionSubscriptionId = null
        chatSubscriptionId?.let { nostrService.closeSubscription(it) }
        chatSubscriptionId = null
        cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
        cancellationSubscriptionId = null
        statusSubscriptionId?.let { nostrService.closeSubscription(it) }
        statusSubscriptionId = null

        // Clear persisted ride state
        clearSavedRideState()

        val fareMessage = statusData.finalFare?.let { " Fare: ${it.toInt()} sats" } ?: ""

        _uiState.value = _uiState.value.copy(
            rideStage = RideStage.COMPLETED,
            statusMessage = "Ride completed!$fareMessage"
        )
    }

    /**
     * Handle ride cancellation from driver.
     */
    private fun handleDriverCancellation(reason: String?) {
        // Stop refresh jobs
        stopChatRefreshJob()

        // Close active subscriptions
        acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }
        acceptanceSubscriptionId = null
        pinSubmissionSubscriptionId?.let { nostrService.closeSubscription(it) }
        pinSubmissionSubscriptionId = null
        chatSubscriptionId?.let { nostrService.closeSubscription(it) }
        chatSubscriptionId = null
        cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
        cancellationSubscriptionId = null
        statusSubscriptionId?.let { nostrService.closeSubscription(it) }
        statusSubscriptionId = null

        // Clear persisted ride state
        clearSavedRideState()

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
            statusMessage = "Driver cancelled the ride",
            error = reason ?: "Driver cancelled the ride"
        )
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
        // Simple fare calculation: base fare + rate per km
        return BASE_FARE + (route.distanceKm * FARE_PER_KM)
    }

    override fun onCleared() {
        super.onCleared()
        staleDriverCleanupJob?.cancel()
        chatRefreshJob?.cancel()
        acceptanceTimeoutJob?.cancel()
        driverSubscriptionId?.let { nostrService.closeSubscription(it) }
        acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }
        pinSubmissionSubscriptionId?.let { nostrService.closeSubscription(it) }
        chatSubscriptionId?.let { nostrService.closeSubscription(it) }
        cancellationSubscriptionId?.let { nostrService.closeSubscription(it) }
        // Close all profile subscriptions
        profileSubscriptionIds.values.forEach { subId ->
            nostrService.closeSubscription(subId)
        }
        profileSubscriptionIds.clear()
        nostrService.disconnect()
    }
}

/**
 * Stages of a ride from rider's perspective.
 */
enum class RideStage {
    IDLE,                   // No active ride
    WAITING_FOR_ACCEPTANCE, // Offer sent, waiting for driver
    DRIVER_ACCEPTED,        // Driver accepted, need to confirm
    RIDE_CONFIRMED,         // Ride confirmed, driver on the way
    IN_PROGRESS,            // Currently in the ride
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
    val expandedSearch: Boolean = false,

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

    // Acceptance timeout tracking
    val acceptanceTimeoutStartMs: Long? = null,   // When we started waiting for acceptance
    val acceptanceTimeoutDurationMs: Long = 20_000L, // How long to wait

    // PIN verification (rider generates PIN and verifies driver's submissions)
    val pickupPin: String? = null,                 // PIN generated locally by rider
    val pinAttempts: Int = 0,                      // Number of driver verification attempts
    val pinVerified: Boolean = false,              // True when driver submitted correct PIN

    // Chat (NIP-17 style private messaging)
    val chatMessages: List<RideshareChatData> = emptyList(),
    val isSendingMessage: Boolean = false,

    // User identity
    val myPubKey: String = "",

    // UI
    val statusMessage: String = "Find available drivers",
    val error: String? = null
)
