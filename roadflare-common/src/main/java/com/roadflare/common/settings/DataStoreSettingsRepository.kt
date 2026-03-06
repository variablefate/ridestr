package com.roadflare.common.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.roadflare.common.nostr.relay.RelayConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Preferences DataStore implementation of SettingsRepository.
 */
@Singleton
class DataStoreSettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : SettingsRepository {

    private object Keys {
        val DISPLAY_CURRENCY = stringPreferencesKey("display_currency")
        val DISTANCE_UNIT = stringPreferencesKey("distance_unit")
        val AUTO_OPEN_NAVIGATION = booleanPreferencesKey("auto_open_navigation")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val NOTIFICATION_SOUND = booleanPreferencesKey("notification_sound")
        val NOTIFICATION_VIBRATION = booleanPreferencesKey("notification_vibration")
        val RELAY_URLS = stringPreferencesKey("relay_urls")
        val FIAT_PAYMENT_METHODS = stringPreferencesKey("fiat_payment_methods")
        val USE_GEOCODING_SEARCH = booleanPreferencesKey("use_geocoding_search")
        val USE_MANUAL_DRIVER_LOCATION = booleanPreferencesKey("use_manual_driver_location")
        val MANUAL_DRIVER_LAT = doublePreferencesKey("manual_driver_lat")
        val MANUAL_DRIVER_LON = doublePreferencesKey("manual_driver_lon")
        val TILES_SETUP_COMPLETED = booleanPreferencesKey("tiles_setup_completed")
    }

    override val displayCurrency: Flow<DisplayCurrency> = dataStore.data.map { prefs ->
        prefs[Keys.DISPLAY_CURRENCY]?.let {
            try { DisplayCurrency.valueOf(it) } catch (_: Exception) { DisplayCurrency.USD }
        } ?: DisplayCurrency.USD
    }

    override val distanceUnit: Flow<DistanceUnit> = dataStore.data.map { prefs ->
        prefs[Keys.DISTANCE_UNIT]?.let {
            try { DistanceUnit.valueOf(it) } catch (_: Exception) { DistanceUnit.MILES }
        } ?: DistanceUnit.MILES
    }

    override val autoOpenNavigation: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.AUTO_OPEN_NAVIGATION] ?: false
    }

    override val onboardingCompleted: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.ONBOARDING_COMPLETED] ?: false
    }

    override val notificationSound: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.NOTIFICATION_SOUND] ?: true
    }

    override val notificationVibration: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.NOTIFICATION_VIBRATION] ?: true
    }

    override val relayUrls: Flow<List<String>> = dataStore.data.map { prefs ->
        val urls = prefs[Keys.RELAY_URLS]?.split(",")?.filter { it.isNotBlank() }
        if (urls.isNullOrEmpty()) RelayConfig.DEFAULT_RELAYS else urls
    }

    override val fiatPaymentMethods: Flow<List<String>> = dataStore.data.map { prefs ->
        prefs[Keys.FIAT_PAYMENT_METHODS]?.split(",")?.filter { it.isNotBlank() }
            ?: listOf("fiat_cash")
    }

    override val useGeocodingSearch: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.USE_GEOCODING_SEARCH] ?: true
    }

    override val useManualDriverLocation: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.USE_MANUAL_DRIVER_LOCATION] ?: false
    }

    override val manualDriverLat: Flow<Double> = dataStore.data.map { prefs ->
        prefs[Keys.MANUAL_DRIVER_LAT] ?: 0.0
    }

    override val manualDriverLon: Flow<Double> = dataStore.data.map { prefs ->
        prefs[Keys.MANUAL_DRIVER_LON] ?: 0.0
    }

    override val tilesSetupCompleted: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[Keys.TILES_SETUP_COMPLETED] ?: false
    }

    override val settings: Flow<AppSettings> = combine(
        displayCurrency, distanceUnit, autoOpenNavigation, onboardingCompleted,
        notificationSound, notificationVibration, relayUrls, fiatPaymentMethods,
        useGeocodingSearch, useManualDriverLocation, manualDriverLat, manualDriverLon,
        tilesSetupCompleted
    ) { values ->
        AppSettings(
            displayCurrency = values[0] as DisplayCurrency,
            distanceUnit = values[1] as DistanceUnit,
            autoOpenNavigation = values[2] as Boolean,
            onboardingCompleted = values[3] as Boolean,
            notificationSound = values[4] as Boolean,
            notificationVibration = values[5] as Boolean,
            relayUrls = @Suppress("UNCHECKED_CAST") (values[6] as List<String>),
            fiatPaymentMethods = @Suppress("UNCHECKED_CAST") (values[7] as List<String>),
            useGeocodingSearch = values[8] as Boolean,
            useManualDriverLocation = values[9] as Boolean,
            manualDriverLat = values[10] as Double,
            manualDriverLon = values[11] as Double,
            tilesSetupCompleted = values[12] as Boolean
        )
    }

    override suspend fun setDisplayCurrency(currency: DisplayCurrency) {
        dataStore.edit { it[Keys.DISPLAY_CURRENCY] = currency.name }
    }

    override suspend fun setDistanceUnit(unit: DistanceUnit) {
        dataStore.edit { it[Keys.DISTANCE_UNIT] = unit.name }
    }

    override suspend fun setAutoOpenNavigation(enabled: Boolean) {
        dataStore.edit { it[Keys.AUTO_OPEN_NAVIGATION] = enabled }
    }

    override suspend fun setOnboardingCompleted(completed: Boolean) {
        dataStore.edit { it[Keys.ONBOARDING_COMPLETED] = completed }
    }

    override suspend fun setNotificationSound(enabled: Boolean) {
        dataStore.edit { it[Keys.NOTIFICATION_SOUND] = enabled }
    }

    override suspend fun setNotificationVibration(enabled: Boolean) {
        dataStore.edit { it[Keys.NOTIFICATION_VIBRATION] = enabled }
    }

    override suspend fun setRelayUrls(urls: List<String>) {
        dataStore.edit { it[Keys.RELAY_URLS] = urls.joinToString(",") }
    }

    override suspend fun setFiatPaymentMethods(methods: List<String>) {
        dataStore.edit { it[Keys.FIAT_PAYMENT_METHODS] = methods.joinToString(",") }
    }

    override suspend fun setUseGeocodingSearch(enabled: Boolean) {
        dataStore.edit { it[Keys.USE_GEOCODING_SEARCH] = enabled }
    }

    override suspend fun setUseManualDriverLocation(enabled: Boolean) {
        dataStore.edit { it[Keys.USE_MANUAL_DRIVER_LOCATION] = enabled }
    }

    override suspend fun setManualDriverLat(lat: Double) {
        dataStore.edit { it[Keys.MANUAL_DRIVER_LAT] = lat }
    }

    override suspend fun setManualDriverLon(lon: Double) {
        dataStore.edit { it[Keys.MANUAL_DRIVER_LON] = lon }
    }

    override suspend fun setTilesSetupCompleted(completed: Boolean) {
        dataStore.edit { it[Keys.TILES_SETUP_COMPLETED] = completed }
    }

    override suspend fun addRelayUrl(url: String) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.RELAY_URLS]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
            if (url !in current) {
                prefs[Keys.RELAY_URLS] = (current + url).joinToString(",")
            }
        }
    }

    override suspend fun removeRelayUrl(url: String) {
        dataStore.edit { prefs ->
            val current = prefs[Keys.RELAY_URLS]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
            val updated = current - url
            if (updated.isEmpty()) {
                prefs.remove(Keys.RELAY_URLS) // Falls back to DEFAULT_RELAYS via isNullOrEmpty check
            } else {
                prefs[Keys.RELAY_URLS] = updated.joinToString(",")
            }
        }
    }

    override suspend fun resetRelaysToDefault() {
        dataStore.edit { prefs -> prefs.remove(Keys.RELAY_URLS) }
    }

    override suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }
}
