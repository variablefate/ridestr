package com.ridestr.app.viewmodels

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ridestr.app.nostr.NostrService
import com.ridestr.app.nostr.events.DriverAvailabilityData
import com.ridestr.app.nostr.events.Location
import com.ridestr.app.nostr.events.RideAcceptanceData
import com.ridestr.app.routing.RouteResult
import com.ridestr.app.routing.ValhallaRoutingService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
        // Time after which a driver is considered stale (2 minutes)
        private const val STALE_DRIVER_TIMEOUT_MS = 2 * 60 * 1000L
        // How often to check for stale drivers (30 seconds)
        private const val STALE_CHECK_INTERVAL_MS = 30_000L
    }

    private val nostrService = NostrService(application)
    private val routingService = ValhallaRoutingService(application)

    private val _uiState = MutableStateFlow(RiderUiState())
    val uiState: StateFlow<RiderUiState> = _uiState.asStateFlow()

    private var driverSubscriptionId: String? = null
    private var acceptanceSubscriptionId: String? = null
    private var staleDriverCleanupJob: Job? = null

    init {
        // Connect to relays and subscribe to drivers
        nostrService.connect()
        subscribeToDrivers()
        startStaleDriverCleanup()

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
    }

    /**
     * Set pickup location.
     */
    fun setPickupLocation(lat: Double, lon: Double) {
        _uiState.value = _uiState.value.copy(
            pickupLocation = Location(lat, lon),
            routeResult = null,
            fareEstimate = null
        )
        calculateRouteIfReady()
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
                _uiState.value = _uiState.value.copy(
                    isSendingOffer = false,
                    pendingOfferEventId = eventId,
                    rideStage = RideStage.WAITING_FOR_ACCEPTANCE,
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
        acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }
        acceptanceSubscriptionId = null

        _uiState.value = _uiState.value.copy(
            pendingOfferEventId = null,
            rideStage = RideStage.IDLE,
            statusMessage = "Offer cancelled"
        )
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
                _uiState.value = _uiState.value.copy(
                    isConfirmingRide = false,
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
     * Clear ride state after completion.
     */
    fun clearRide() {
        acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }
        acceptanceSubscriptionId = null

        _uiState.value = _uiState.value.copy(
            selectedDriver = null,
            pendingOfferEventId = null,
            acceptance = null,
            rideStage = RideStage.IDLE,
            statusMessage = "Ready to book a ride"
        )
    }

    /**
     * Clear error message.
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }

    private fun subscribeToDrivers() {
        driverSubscriptionId = nostrService.subscribeToDrivers { driver ->
            Log.d(TAG, "Found driver: ${driver.driverPubKey.take(8)}... status=${driver.status}")

            val currentDrivers = _uiState.value.availableDrivers.toMutableList()
            val existingIndex = currentDrivers.indexOfFirst { it.driverPubKey == driver.driverPubKey }

            if (driver.isOffline) {
                // Driver went offline - remove from list
                if (existingIndex >= 0) {
                    Log.d(TAG, "Removing offline driver: ${driver.driverPubKey.take(8)}...")
                    currentDrivers.removeAt(existingIndex)
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
                }
            }

            _uiState.value = _uiState.value.copy(availableDrivers = currentDrivers)
        }
    }

    /**
     * Periodically clean up stale drivers (no update for >2 minutes).
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
        val freshDrivers = currentDrivers.filter { driver ->
            val isFresh = driver.createdAt >= staleThreshold
            if (!isFresh) {
                Log.d(TAG, "Removing stale driver: ${driver.driverPubKey.take(8)}... (last seen ${now - driver.createdAt}s ago)")
            }
            isFresh
        }

        if (freshDrivers.size != currentDrivers.size) {
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

            _uiState.value = _uiState.value.copy(
                acceptance = acceptance,
                rideStage = RideStage.DRIVER_ACCEPTED,
                statusMessage = "Driver accepted! Confirm your ride."
            )
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
        driverSubscriptionId?.let { nostrService.closeSubscription(it) }
        acceptanceSubscriptionId?.let { nostrService.closeSubscription(it) }
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

    // Route information
    val pickupLocation: Location? = null,
    val destination: Location? = null,
    val routeResult: RouteResult? = null,
    val fareEstimate: Double? = null,
    val isCalculatingRoute: Boolean = false,
    val isRoutingReady: Boolean = false,

    // Ride state
    val rideStage: RideStage = RideStage.IDLE,
    val pendingOfferEventId: String? = null,
    val acceptance: RideAcceptanceData? = null,
    val isSendingOffer: Boolean = false,
    val isConfirmingRide: Boolean = false,

    // UI
    val statusMessage: String = "Find available drivers",
    val error: String? = null
)
