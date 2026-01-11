package com.ridestr.app.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ridestr.app.nostr.NostrService
import com.ridestr.app.nostr.events.Location
import com.ridestr.app.nostr.events.RideOfferData
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * ViewModel for Driver mode.
 * Manages driver availability, broadcasts location, and handles incoming ride offers.
 */
class DriverViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DriverViewModel"
        private const val AVAILABILITY_BROADCAST_INTERVAL_MS = 30_000L // 30 seconds
    }

    private val nostrService = NostrService(application)

    private val _uiState = MutableStateFlow(DriverUiState())
    val uiState: StateFlow<DriverUiState> = _uiState.asStateFlow()

    private var availabilityJob: Job? = null
    private var offerSubscriptionId: String? = null

    // Track published availability event IDs for deletion
    private val publishedAvailabilityEventIds = mutableListOf<String>()

    init {
        // Connect to relays
        nostrService.connect()
    }

    /**
     * Toggle driver availability on/off.
     */
    fun toggleAvailability(location: Location) {
        val currentState = _uiState.value

        if (currentState.isAvailable) {
            // Turn off availability - send offline event and delete old availability events
            viewModelScope.launch {
                val lastLocation = currentState.currentLocation ?: location

                // Send offline status
                val eventId = nostrService.broadcastOffline(lastLocation)
                if (eventId != null) {
                    Log.d(TAG, "Broadcast offline status: $eventId")
                } else {
                    Log.w(TAG, "Failed to broadcast offline status")
                }

                // Request deletion of all previous availability events
                deleteAllAvailabilityEvents()
            }
            stopBroadcasting()
            _uiState.value = currentState.copy(
                isAvailable = false,
                currentLocation = null,
                statusMessage = "You are now offline"
            )
        } else {
            // Turn on availability
            startBroadcasting(location)
            subscribeToOffers()
            _uiState.value = currentState.copy(
                isAvailable = true,
                currentLocation = location,
                statusMessage = "You are now available for rides"
            )
        }
    }

    /**
     * Update driver location while available.
     */
    fun updateLocation(location: Location) {
        if (_uiState.value.isAvailable) {
            _uiState.value = _uiState.value.copy(currentLocation = location)
        }
    }

    /**
     * Accept a ride offer.
     */
    fun acceptOffer(offer: RideOfferData) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isProcessingOffer = true)

            val eventId = nostrService.acceptRide(offer)

            if (eventId != null) {
                Log.d(TAG, "Accepted ride offer: $eventId")
                // Move offer to accepted and stop availability
                stopBroadcasting()

                // Clean up availability events since we're no longer available
                deleteAllAvailabilityEvents()

                _uiState.value = _uiState.value.copy(
                    isAvailable = false,
                    isProcessingOffer = false,
                    acceptedOffer = offer,
                    pendingOffers = _uiState.value.pendingOffers.filter { it.eventId != offer.eventId },
                    statusMessage = "Ride accepted! Waiting for rider confirmation..."
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isProcessingOffer = false,
                    error = "Failed to accept ride"
                )
            }
        }
    }

    /**
     * Decline a ride offer.
     */
    fun declineOffer(offer: RideOfferData) {
        _uiState.value = _uiState.value.copy(
            pendingOffers = _uiState.value.pendingOffers.filter { it.eventId != offer.eventId }
        )
    }

    /**
     * Clear the accepted offer (ride completed or cancelled).
     */
    fun clearAcceptedOffer() {
        _uiState.value = _uiState.value.copy(
            acceptedOffer = null,
            statusMessage = "Ready to accept new rides"
        )
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun startBroadcasting(location: Location) {
        availabilityJob?.cancel()
        availabilityJob = viewModelScope.launch {
            while (isActive) {
                val currentLocation = _uiState.value.currentLocation ?: location

                // Delete previous availability event before broadcasting new one
                // This prevents accumulation of old events on relays
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

    /**
     * Request deletion of all published availability events.
     * Called when driver goes offline to clean up relay storage.
     */
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
        // Close existing subscription
        offerSubscriptionId?.let { nostrService.closeSubscription(it) }

        offerSubscriptionId = nostrService.subscribeToOffers { offer ->
            Log.d(TAG, "Received ride offer from ${offer.riderPubKey.take(8)}...")

            // Add to pending offers if not already there
            val currentOffers = _uiState.value.pendingOffers
            if (currentOffers.none { it.eventId == offer.eventId }) {
                _uiState.value = _uiState.value.copy(
                    pendingOffers = currentOffers + offer
                )
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopBroadcasting()
        offerSubscriptionId?.let { nostrService.closeSubscription(it) }
        nostrService.disconnect()
    }
}

/**
 * UI state for driver mode.
 */
data class DriverUiState(
    val isAvailable: Boolean = false,
    val currentLocation: Location? = null,
    val lastBroadcastTime: Long? = null,
    val pendingOffers: List<RideOfferData> = emptyList(),
    val acceptedOffer: RideOfferData? = null,
    val isProcessingOffer: Boolean = false,
    val statusMessage: String = "Tap to go online",
    val error: String? = null
)
