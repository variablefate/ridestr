package com.roadflare.rider.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.ridestr.common.bitcoin.BitcoinPriceService
import com.ridestr.common.data.FollowedDriversRepository
import com.ridestr.common.data.RideHistoryRepository
import com.ridestr.common.data.SavedLocationRepository
import com.ridestr.common.location.GeocodingResult
import com.ridestr.common.location.GeocodingService
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.events.PaymentMethod
import com.ridestr.common.routing.NostrTileDiscoveryService
import com.ridestr.common.routing.TileDownloadService
import com.ridestr.common.routing.TileManager
import com.ridestr.common.routing.ValhallaRoutingService
import com.ridestr.common.settings.SettingsRepository
import com.ridestr.common.settings.SettingsUiState
import com.ridestr.common.sync.ProfileSyncManager
import com.ridestr.common.util.FareCalculator
import com.roadflare.rider.state.RideStage
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Thin ViewModel projector for the rider's main screen.
 *
 * Delegates domain logic to focused coordinators:
 * - [RideSessionManager] — ride lifecycle, batched offers, NIP-09 cleanup
 * - [ChatCoordinator] — in-ride encrypted chat
 * - [FareCoordinator] — fare calculation and route estimation
 *
 * This ViewModel exposes combined state flows for the UI and acts as
 * the integration point between coordinators.
 */
@HiltViewModel
class RiderViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val settingsRepository: SettingsRepository
) : AndroidViewModel(application) {

    // Combined settings state for UI
    val settings: StateFlow<SettingsUiState> = settingsRepository.settings

    // Shared singletons
    val nostrService = NostrService.getInstance(application, settingsRepository.getEffectiveRelays())
    val followedDriversRepository = FollowedDriversRepository.getInstance(application)
    val rideHistoryRepository = RideHistoryRepository.getInstance(application)
    val savedLocationRepository = SavedLocationRepository.getInstance(application)
    val geocodingService = GeocodingService(application)
    val tileManager = TileManager.getInstance(application)
    val tileDownloadService = TileDownloadService(application, tileManager)
    val nostrTileDiscoveryService = NostrTileDiscoveryService(application, nostrService.relayManager)

    // Coordinators — constructed with shared services
    val rideSessionManager = RideSessionManager(nostrService)
    val chatCoordinator = ChatCoordinator(nostrService)
    val fareCoordinator = FareCoordinator(
        fareCalculator = FareCalculator,
        bitcoinPriceService = BitcoinPriceService.getInstance(),
        valhallaRoutingService = ValhallaRoutingService(application)
    )

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

    // Last known GPS position for biasing geocoding results nearby
    private var lastGpsLat: Double? = null
    private var lastGpsLon: Double? = null

    fun searchPickupLocations(query: String) {
        if (query.length < 3) {
            _pickupSearchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            _isSearchingPickup.value = true
            try {
                _pickupSearchResults.value = geocodingService.searchAddress(
                    query, biasLat = lastGpsLat, biasLon = lastGpsLon
                )
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
        // Bias destination search toward pickup location if set, else GPS
        val biasLat = _pickupLocation.value?.lat ?: lastGpsLat
        val biasLon = _pickupLocation.value?.lon ?: lastGpsLon
        viewModelScope.launch {
            _isSearchingDest.value = true
            try {
                _destSearchResults.value = geocodingService.searchAddress(
                    query, biasLat = biasLat, biasLon = biasLon
                )
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

    fun swapLocations() {
        val tempPickup = _pickupLocation.value
        val tempDest = _destLocation.value
        _pickupLocation.value = tempDest
        _destLocation.value = tempPickup
        _pickupSearchResults.value = emptyList()
        _destSearchResults.value = emptyList()
        _fareEstimate.value = null
        recalculateFare()
    }

    fun pinWithNickname(id: String, nickname: String?) = savedLocationRepository.pinAsFavorite(id, nickname)
    fun unpinFavorite(id: String) = savedLocationRepository.unpinFavorite(id)
    fun removeSavedLocation(id: String) = savedLocationRepository.removeLocation(id)

    fun setPickupFromGps(lat: Double, lon: Double) {
        lastGpsLat = lat
        lastGpsLon = lon
        _pickupLocation.value = Location(lat, lon)
        recalculateFare()
        // Async reverse geocode to fill in the address label
        viewModelScope.launch {
            val label = geocodingService.reverseGeocode(lat, lon, localOnly = true)
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
            val fiatPaymentMethods = settingsRepository.getRoadflarePaymentMethods()

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
     * except routing tiles.
     */
    override fun onCleared() {
        super.onCleared()
        rideSessionManager.destroy()
        chatCoordinator.destroy()
    }

    suspend fun performLogout() {
        // Clear in-memory state (all synchronous)
        rideSessionManager.clearRide()
        chatCoordinator.clearMessages()
        nostrService.clearAllSubscriptions()
        // Intentional: tear down relay connections on logout.
        // Equivalent to LogoutManager.performFullCleanup() in ridestr/drivestr.
        nostrService.disconnect()

        // Clear persisted local data (synchronous SharedPreferences)
        followedDriversRepository.clearAll()
        savedLocationRepository.clearAll()
        rideHistoryRepository.clearAllHistory()

        // Clear settings (DataStore via SettingsRepository)
        try {
            settingsRepository.clearAllData()
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            android.util.Log.e("RiderViewModel", "Failed to clear settings on logout", e)
        }

        // Clean up ProfileSyncManager (don't null singleton — composables hold references)
        val syncManager = ProfileSyncManager.getInstance(getApplication())
        syncManager.keyManager.logout()
        syncManager.disconnect()
        syncManager.resetSyncState()
    }

    // --- Settings mediation methods (screens call these, ViewModel wraps suspend setters) ---

    fun onToggleDisplayCurrency() = viewModelScope.launch { settingsRepository.toggleDisplayCurrency() }
    fun onToggleDistanceUnit() = viewModelScope.launch { settingsRepository.toggleDistanceUnit() }
    fun onSetNotificationSoundEnabled(enabled: Boolean) = viewModelScope.launch { settingsRepository.setNotificationSoundEnabled(enabled) }
    fun onSetNotificationVibrationEnabled(enabled: Boolean) = viewModelScope.launch { settingsRepository.setNotificationVibrationEnabled(enabled) }
    fun onSetRoadflarePaymentMethods(methods: List<String>) = viewModelScope.launch { settingsRepository.setRoadflarePaymentMethods(methods) }
    fun onAddRelay(url: String) = viewModelScope.launch { settingsRepository.addRelay(url) }
    fun onRemoveRelay(url: String) = viewModelScope.launch { settingsRepository.removeRelay(url) }
    fun onResetRelays() = viewModelScope.launch { settingsRepository.resetRelaysToDefault() }
    fun onToggleUseGeocodingSearch() = viewModelScope.launch { settingsRepository.toggleUseGeocodingSearch() }
    fun onSetUseManualDriverLocation(enabled: Boolean) = viewModelScope.launch { settingsRepository.setUseManualDriverLocation(enabled) }
    fun onSetManualDriverLocation(lat: Double, lon: Double) = viewModelScope.launch { settingsRepository.setManualDriverLocation(lat, lon) }
    fun onSetAlwaysShowWalletDiagnostics(enabled: Boolean) = viewModelScope.launch { settingsRepository.setAlwaysShowWalletDiagnostics(enabled) }
    fun onSetIgnoreFollowNotifications(enabled: Boolean) = viewModelScope.launch { settingsRepository.setIgnoreFollowNotifications(enabled) }
    fun onSetDistanceUnit(unit: com.ridestr.common.settings.DistanceUnit) = viewModelScope.launch { settingsRepository.setDistanceUnit(unit) }
    fun onSetDisplayCurrency(currency: com.ridestr.common.settings.DisplayCurrency) = viewModelScope.launch { settingsRepository.setDisplayCurrency(currency) }
}
