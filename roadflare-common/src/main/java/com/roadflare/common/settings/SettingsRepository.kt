package com.roadflare.common.settings

import kotlinx.coroutines.flow.Flow

/**
 * Interface for settings persistence.
 * Implementations use Preferences DataStore (per ridestr#52).
 */
interface SettingsRepository {

    val displayCurrency: Flow<DisplayCurrency>
    val distanceUnit: Flow<DistanceUnit>
    val autoOpenNavigation: Flow<Boolean>
    val onboardingCompleted: Flow<Boolean>
    val notificationSound: Flow<Boolean>
    val notificationVibration: Flow<Boolean>
    val relayUrls: Flow<List<String>>
    val fiatPaymentMethods: Flow<List<String>>
    val useGeocodingSearch: Flow<Boolean>
    val useManualDriverLocation: Flow<Boolean>
    val manualDriverLat: Flow<Double>
    val manualDriverLon: Flow<Double>
    val tilesSetupCompleted: Flow<Boolean>

    val settings: Flow<AppSettings>

    suspend fun setDisplayCurrency(currency: DisplayCurrency)
    suspend fun setDistanceUnit(unit: DistanceUnit)
    suspend fun setAutoOpenNavigation(enabled: Boolean)
    suspend fun setOnboardingCompleted(completed: Boolean)
    suspend fun setNotificationSound(enabled: Boolean)
    suspend fun setNotificationVibration(enabled: Boolean)
    suspend fun setRelayUrls(urls: List<String>)
    suspend fun setFiatPaymentMethods(methods: List<String>)
    suspend fun setUseGeocodingSearch(enabled: Boolean)
    suspend fun setUseManualDriverLocation(enabled: Boolean)
    suspend fun setManualDriverLat(lat: Double)
    suspend fun setManualDriverLon(lon: Double)
    suspend fun setTilesSetupCompleted(completed: Boolean)
    suspend fun addRelayUrl(url: String)
    suspend fun removeRelayUrl(url: String)
    suspend fun resetRelaysToDefault()
    suspend fun clearAll()
}
