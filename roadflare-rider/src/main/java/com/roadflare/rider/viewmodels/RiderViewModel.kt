package com.roadflare.rider.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadflare.common.data.FollowedDriversRepository
import com.roadflare.common.data.RideHistoryRepository
import com.roadflare.common.data.SavedLocationRepository
import com.roadflare.common.location.GeocodingResult
import com.roadflare.common.location.GeocodingService
import com.roadflare.common.nostr.NostrService
import com.roadflare.common.nostr.events.Location
import com.roadflare.common.nostr.events.PaymentMethod
import com.roadflare.common.routing.NostrTileDiscoveryService
import com.roadflare.common.routing.TileDownloadService
import com.roadflare.common.routing.TileManager
import com.roadflare.common.settings.AppSettings
import com.roadflare.common.settings.SettingsRepository
import com.roadflare.rider.state.RideStage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Thin ViewModel projector for the rider's main screen.
 *
 * Unlike the original monolithic ridestr RiderViewModel (~5400 lines),
 * this class delegates domain logic to focused coordinators:
 * - [RideSessionManager] — ride lifecycle, batched offers, NIP-09 cleanup
 * - [ChatCoordinator] — in-ride encrypted chat
 * - [FareCoordinator] — fare calculation and route estimation
 *
 * This ViewModel exposes combined state flows for the UI and acts as
 * the integration point between coordinators.
 */
@HiltViewModel
class RiderViewModel @Inject constructor(
    val nostrService: NostrService,
    val rideSessionManager: RideSessionManager,
    val chatCoordinator: ChatCoordinator,
    val fareCoordinator: FareCoordinator,
    val followedDriversRepository: FollowedDriversRepository,
    val rideHistoryRepository: RideHistoryRepository,
    val savedLocationRepository: SavedLocationRepository,
    val settingsRepository: SettingsRepository,
    val geocodingService: GeocodingService,
    val tileManager: TileManager,
    val tileDownloadService: TileDownloadService,
    val nostrTileDiscoveryService: NostrTileDiscoveryService
) : ViewModel() {

    init {
        nostrService.ensureConnected()
    }

    /** Current UI stage derived from the ride state machine. */
    val rideStage: StateFlow<RideStage> = rideSessionManager.rideStage

    /** Current ride session, or null if no ride is active. */
    val currentRide: StateFlow<RideSession?> = rideSessionManager.currentRide

    /** Chat messages for the active ride. */
    val chatMessages: StateFlow<List<ChatMessage>> = chatCoordinator.messages

    /** List of followed drivers. */
    val drivers = followedDriversRepository.drivers

    /** Cached display names for followed drivers (pubkey -> name). */
    val driverNames = followedDriversRepository.driverNames

    /** Last-known locations for followed drivers. */
    val driverLocations = followedDriversRepository.driverLocations

    /** User settings, collected as a StateFlow with default null until loaded. */
    val settings: StateFlow<AppSettings?> = settingsRepository.settings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        null
    )

    // --- Geocoding search state ---

    private val _pickupSearchResults = MutableStateFlow<List<GeocodingResult>>(emptyList())
    val pickupSearchResults: StateFlow<List<GeocodingResult>> = _pickupSearchResults.asStateFlow()

    private val _destSearchResults = MutableStateFlow<List<GeocodingResult>>(emptyList())
    val destSearchResults: StateFlow<List<GeocodingResult>> = _destSearchResults.asStateFlow()

    private val _isSearchingPickup = MutableStateFlow(false)
    val isSearchingPickup: StateFlow<Boolean> = _isSearchingPickup.asStateFlow()

    private val _isSearchingDest = MutableStateFlow(false)
    val isSearchingDest: StateFlow<Boolean> = _isSearchingDest.asStateFlow()

    private val _pickupLocation = MutableStateFlow<Location?>(null)
    val pickupLocation: StateFlow<Location?> = _pickupLocation.asStateFlow()

    private val _destLocation = MutableStateFlow<Location?>(null)
    val destLocation: StateFlow<Location?> = _destLocation.asStateFlow()

    private val _fareEstimate = MutableStateFlow<RouteResult?>(null)
    val fareEstimate: StateFlow<RouteResult?> = _fareEstimate.asStateFlow()

    private val _isCalculatingFare = MutableStateFlow(false)
    val isCalculatingFare: StateFlow<Boolean> = _isCalculatingFare.asStateFlow()

    /** Saved/favorite locations for quick selection. */
    val savedLocations = savedLocationRepository.savedLocations

    // --- Developer options StateFlow wrappers ---
    val useGeocodingSearchState: StateFlow<Boolean> = settingsRepository.useGeocodingSearch
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val useManualDriverLocationState: StateFlow<Boolean> = settingsRepository.useManualDriverLocation
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val manualDriverLatState: StateFlow<Double> = settingsRepository.manualDriverLat
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)
    val manualDriverLonState: StateFlow<Double> = settingsRepository.manualDriverLon
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)

    fun searchPickupLocations(query: String) {
        if (query.length < 3) {
            _pickupSearchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            _isSearchingPickup.value = true
            try {
                _pickupSearchResults.value = geocodingService.searchAddress(query)
            } catch (_: Exception) {
                _pickupSearchResults.value = emptyList()
            } finally {
                _isSearchingPickup.value = false
            }
        }
    }

    fun searchDestLocations(query: String) {
        if (query.length < 3) {
            _destSearchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            _isSearchingDest.value = true
            try {
                _destSearchResults.value = geocodingService.searchAddress(query)
            } catch (_: Exception) {
                _destSearchResults.value = emptyList()
            } finally {
                _isSearchingDest.value = false
            }
        }
    }

    fun selectPickupFromSearch(result: GeocodingResult) {
        _pickupLocation.value = Location(result.lat, result.lon, addressLabel = result.addressLine)
        _pickupSearchResults.value = emptyList()
        savedLocationRepository.addRecent(result)
        recalculateFare()
    }

    fun selectDestFromSearch(result: GeocodingResult) {
        _destLocation.value = Location(result.lat, result.lon, addressLabel = result.addressLine)
        _destSearchResults.value = emptyList()
        savedLocationRepository.addRecent(result)
        recalculateFare()
    }

    fun clearPickup() {
        _pickupLocation.value = null
        _pickupSearchResults.value = emptyList()
        _fareEstimate.value = null
    }

    fun clearDest() {
        _destLocation.value = null
        _destSearchResults.value = emptyList()
        _fareEstimate.value = null
    }

    fun setPickupFromGps(lat: Double, lon: Double) {
        _pickupLocation.value = Location(lat, lon)
        recalculateFare()
        // Async reverse geocode to fill in the address label
        viewModelScope.launch {
            val label = geocodingService.reverseGeocode(lat, lon)
            if (label != null && _pickupLocation.value?.lat == lat && _pickupLocation.value?.lon == lon) {
                _pickupLocation.value = Location(lat, lon, addressLabel = label)
            }
        }
    }

    private fun recalculateFare() {
        val pickup = _pickupLocation.value ?: return
        val dest = _destLocation.value ?: return
        viewModelScope.launch {
            _isCalculatingFare.value = true
            try {
                _fareEstimate.value = fareCoordinator.calculateRoute(pickup, dest)
            } catch (_: Exception) {
                _fareEstimate.value = null
            } finally {
                _isCalculatingFare.value = false
            }
        }
    }

    /** Check if the user is logged in (has a Nostr keypair). */
    fun isLoggedIn(): Boolean = nostrService.isLoggedIn()

    /** Get the current user's public key in hex format. */
    fun getPubKeyHex(): String? = nostrService.getPubKeyHex()

    /**
     * Send a RoadFlare to all followed drivers.
     * Uses already-selected pickup/destination and pre-calculated fare.
     */
    fun sendRoadflare() {
        val pickup = _pickupLocation.value ?: return
        val dest = _destLocation.value ?: return
        val route = _fareEstimate.value

        viewModelScope.launch {
            val currentDrivers = followedDriversRepository.drivers.value
            val currentNames = followedDriversRepository.driverNames.value
            val currentLocations = followedDriversRepository.driverLocations.value

            if (currentDrivers.isEmpty()) return@launch

            // Build driver list with distances from pickup
            val driversWithDistance = currentDrivers.map { driver ->
                val cachedLoc = currentLocations[driver.pubkey]
                val distanceMiles = if (cachedLoc != null) {
                    pickup.distanceToKm(Location(cachedLoc.lat, cachedLoc.lon)) * 0.621371
                } else {
                    Double.MAX_VALUE
                }
                driver.pubkey to distanceMiles
            }

            val fareUsd = route?.fareUsd ?: fareCoordinator.calculateRoute(pickup, dest).fareUsd
            val fareSats = route?.fareSats
            val fiatPaymentMethods = settingsRepository.fiatPaymentMethods.first()

            rideSessionManager.sendRoadflareToAll(
                drivers = driversWithDistance,
                driverNames = currentNames,
                pickup = pickup,
                destination = dest,
                fareEstimate = fareUsd,
                fareEstimateSats = fareSats,
                paymentMethod = PaymentMethod.FIAT_CASH,
                fiatPaymentMethods = fiatPaymentMethods
            )
        }
    }

    /**
     * Full logout cleanup. Clears all in-memory and persisted state
     * except routing tiles. Must be awaited before OnboardingViewModel.logout()
     * to prevent race with DataStore I/O.
     */
    suspend fun performLogout() {
        // Clear in-memory state (all synchronous)
        rideSessionManager.clearRide()
        chatCoordinator.clearMessages()
        nostrService.clearAllSubscriptions()
        nostrService.disconnect()

        // Clear persisted local data (synchronous SharedPreferences)
        followedDriversRepository.clearAll()
        savedLocationRepository.clearAll()
        rideHistoryRepository.clearAllHistory()

        // Clear settings (suspend — DataStore I/O, must complete before logout)
        try {
            settingsRepository.clearAll()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            android.util.Log.e("RiderViewModel", "Failed to clear settings on logout", e)
        }
    }
}
