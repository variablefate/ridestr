package com.ridestr.common.settings

import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.ridestr.common.nostr.events.SettingsBackup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Combined read projection for ViewModels. Screens read settings via this data class.
 * Contains all user-facing settings. ViewModels expose this; screens never see the repository.
 */
data class SettingsUiState(
    val displayCurrency: DisplayCurrency = DisplayCurrency.USD,
    val distanceUnit: DistanceUnit = DistanceUnit.MILES,
    val autoOpenNavigation: Boolean = true,
    val notificationSoundEnabled: Boolean = true,
    val notificationVibrationEnabled: Boolean = true,
    val useGpsForPickup: Boolean = false,
    val alwaysAskVehicle: Boolean = true,
    val activeVehicleId: String? = null,
    val useGeocodingSearch: Boolean = true,
    val useManualDriverLocation: Boolean = false,
    val manualDriverLat: Double = 36.1699,
    val manualDriverLon: Double = -115.1398,
    val roadflareAlertsEnabled: Boolean = false,
    val ignoreFollowNotifications: Boolean = false,
    val customRelays: List<String> = emptyList(),
    val isUsingCustomRelays: Boolean = false,
    val paymentMethods: List<String> = listOf("cashu"),
    val defaultPaymentMethod: String = "cashu",
    val mintUrl: String? = null,
    val roadflarePaymentMethods: List<String> = emptyList(),
    val favoriteLnAddresses: List<FavoriteLnAddress> = emptyList(),
    val alwaysShowWalletDiagnostics: Boolean = false,
    val walletSetupCompleted: Boolean = false,
    val walletSetupSkipped: Boolean = false
)

/**
 * DataStore-backed settings repository. Single source of truth for all app settings.
 *
 * Replaces SettingsManager with reactive DataStore, automatic SharedPreferences migration,
 * and atomic writes. All derived flows read from a single canonical [_preferences] cache.
 *
 * @param dataStore The DataStore instance (provided by Hilt module in production, test factory in tests)
 * @param scope Coroutine scope for background collection (test scope in tests)
 */
class SettingsRepository(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {

    companion object {
        private const val TAG = "SettingsRepository"

        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net"
        )
        const val MAX_RELAYS = 10
        const val MAX_FAVORITE_ADDRESSES = 10
    }

    // ========================================
    // DataStore Keys
    // ========================================

    private object Keys {
        val AUTO_OPEN_NAVIGATION = booleanPreferencesKey("auto_open_navigation")
        val DISPLAY_CURRENCY = stringPreferencesKey("display_currency")
        val DISTANCE_UNIT = stringPreferencesKey("distance_unit")
        val NOTIFICATION_SOUND = booleanPreferencesKey("notification_sound")
        val NOTIFICATION_VIBRATION = booleanPreferencesKey("notification_vibration")
        val USE_GPS_FOR_PICKUP = booleanPreferencesKey("use_gps_for_pickup")
        val ALWAYS_ASK_VEHICLE = booleanPreferencesKey("always_ask_vehicle")
        val ACTIVE_VEHICLE_ID = stringPreferencesKey("active_vehicle_id")
        val USE_GEOCODING_SEARCH = booleanPreferencesKey("use_geocoding_search")
        val USE_MANUAL_DRIVER_LOCATION = booleanPreferencesKey("use_manual_driver_location")
        val MANUAL_DRIVER_LAT = floatPreferencesKey("manual_driver_lat")
        val MANUAL_DRIVER_LON = floatPreferencesKey("manual_driver_lon")
        val CUSTOM_RELAYS = stringPreferencesKey("custom_relays")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val TILES_SETUP_COMPLETED = booleanPreferencesKey("tiles_setup_completed")
        val WALLET_SETUP_COMPLETED = booleanPreferencesKey("wallet_setup_completed")
        val WALLET_SETUP_SKIPPED = booleanPreferencesKey("wallet_setup_skipped")
        val ALWAYS_SHOW_WALLET_DIAGNOSTICS = booleanPreferencesKey("always_show_wallet_diagnostics")
        val IGNORE_FOLLOW_NOTIFICATIONS = booleanPreferencesKey("ignore_follow_notifications")
        val ROADFLARE_ALERTS_ENABLED = booleanPreferencesKey("roadflare_alerts_enabled")
        val PAYMENT_METHODS = stringPreferencesKey("payment_methods")
        val DEFAULT_PAYMENT_METHOD = stringPreferencesKey("default_payment_method")
        val MINT_URL = stringPreferencesKey("mint_url")
        val ROADFLARE_PAYMENT_METHODS = stringPreferencesKey("roadflare_payment_methods")
        val FAVORITE_LN_ADDRESSES = stringPreferencesKey("favorite_ln_addresses")
        val ENCRYPTION_FALLBACK_WARNED = booleanPreferencesKey("encryption_fallback_warned")
    }

    /** Coerce defaultPaymentMethod — only "cashu" is valid. */
    private fun coerceDefaultPaymentMethod(raw: String?): String {
        val method = raw ?: "cashu"
        return if (method != "cashu" && method.isNotBlank()) "cashu" else method.ifBlank { "cashu" }
    }

    // ========================================
    // Canonical Preferences Cache + Loading Gate
    // ========================================

    /** THE single source of truth for all settings. All derived flows read from this. */
    private val _preferences = MutableStateFlow(emptyPreferences())

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    init {
        // Subscribe to DataStore for ongoing updates (writes, external changes)
        scope.launch {
            dataStore.data.collect { _preferences.value = it }
        }
    }

    /**
     * Force disk read + migration. Call from loading gate before app navigates.
     * Fail-open: app launches with defaults rather than permanent blank screen.
     */
    suspend fun awaitInitialLoad() {
        try {
            val prefs = dataStore.data.first()
            _preferences.value = prefs
        } catch (e: Exception) {
            Log.e(TAG, "DataStore initial load failed, proceeding with defaults", e)
        }
        _isReady.value = true
    }

    /**
     * Cancel the internal collector scope. Call in tests' @After to prevent leaks.
     * NOT needed in production — the repository lives for the process lifetime.
     */
    @VisibleForTesting
    fun close() {
        scope.cancel()
    }

    // ========================================
    // Derived StateFlows (for UI observation via SettingsUiState)
    // Do NOT use .value for synchronous reads — use sync getters below.
    // ========================================

    val autoOpenNavigation: StateFlow<Boolean> = _preferences
        .map { it[Keys.AUTO_OPEN_NAVIGATION] ?: true }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, true)

    val displayCurrency: StateFlow<DisplayCurrency> = _preferences
        .map { prefs ->
            try { DisplayCurrency.valueOf(prefs[Keys.DISPLAY_CURRENCY] ?: "USD") }
            catch (e: IllegalArgumentException) { DisplayCurrency.USD }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, DisplayCurrency.USD)

    val distanceUnit: StateFlow<DistanceUnit> = _preferences
        .map { prefs ->
            try { DistanceUnit.valueOf(prefs[Keys.DISTANCE_UNIT] ?: "MILES") }
            catch (e: IllegalArgumentException) { DistanceUnit.MILES }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, DistanceUnit.MILES)

    val notificationSoundEnabled: StateFlow<Boolean> = _preferences
        .map { it[Keys.NOTIFICATION_SOUND] ?: true }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, true)

    val notificationVibrationEnabled: StateFlow<Boolean> = _preferences
        .map { it[Keys.NOTIFICATION_VIBRATION] ?: true }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, true)

    val useGpsForPickup: StateFlow<Boolean> = _preferences
        .map { it[Keys.USE_GPS_FOR_PICKUP] ?: false }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    val alwaysAskVehicle: StateFlow<Boolean> = _preferences
        .map { it[Keys.ALWAYS_ASK_VEHICLE] ?: true }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, true)

    val activeVehicleId: StateFlow<String?> = _preferences
        .map { it[Keys.ACTIVE_VEHICLE_ID] }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    val useGeocodingSearch: StateFlow<Boolean> = _preferences
        .map { it[Keys.USE_GEOCODING_SEARCH] ?: true }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, true)

    val useManualDriverLocation: StateFlow<Boolean> = _preferences
        .map { it[Keys.USE_MANUAL_DRIVER_LOCATION] ?: false }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    val manualDriverLat: StateFlow<Double> = _preferences
        .map { (it[Keys.MANUAL_DRIVER_LAT] ?: 36.1699f).toDouble() }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, 36.1699)

    val manualDriverLon: StateFlow<Double> = _preferences
        .map { (it[Keys.MANUAL_DRIVER_LON] ?: -115.1398f).toDouble() }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, -115.1398)

    val onboardingCompleted: StateFlow<Boolean> = _preferences
        .map { it[Keys.ONBOARDING_COMPLETED] ?: false }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    val tilesSetupCompleted: StateFlow<Boolean> = _preferences
        .map { it[Keys.TILES_SETUP_COMPLETED] ?: false }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    val walletSetupCompleted: StateFlow<Boolean> = _preferences
        .map { it[Keys.WALLET_SETUP_COMPLETED] ?: false }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    val walletSetupSkipped: StateFlow<Boolean> = _preferences
        .map { it[Keys.WALLET_SETUP_SKIPPED] ?: false }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    val alwaysShowWalletDiagnostics: StateFlow<Boolean> = _preferences
        .map { it[Keys.ALWAYS_SHOW_WALLET_DIAGNOSTICS] ?: false }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    val ignoreFollowNotifications: StateFlow<Boolean> = _preferences
        .map { it[Keys.IGNORE_FOLLOW_NOTIFICATIONS] ?: false }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    val roadflareAlertsEnabled: StateFlow<Boolean> = _preferences
        .map { it[Keys.ROADFLARE_ALERTS_ENABLED] ?: false }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, false)

    val customRelays: StateFlow<List<String>> = _preferences
        .map { prefs ->
            prefs[Keys.CUSTOM_RELAYS]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val paymentMethods: StateFlow<List<String>> = _preferences
        .map { prefs ->
            val methods = prefs[Keys.PAYMENT_METHODS]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
            if (methods.isEmpty()) listOf("cashu") else methods
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, listOf("cashu"))

    val defaultPaymentMethod: StateFlow<String> = _preferences
        .map { prefs -> coerceDefaultPaymentMethod(prefs[Keys.DEFAULT_PAYMENT_METHOD]) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, "cashu")

    val roadflarePaymentMethods: StateFlow<List<String>> = _preferences
        .map { prefs ->
            prefs[Keys.ROADFLARE_PAYMENT_METHODS]?.split(",")
                ?.filter { it.isNotBlank() }?.distinct() ?: emptyList()
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val mintUrl: StateFlow<String?> = _preferences
        .map { it[Keys.MINT_URL] }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    val favoriteLnAddresses: StateFlow<List<FavoriteLnAddress>> = _preferences
        .map { prefs -> parseFavoriteLnAddresses(prefs[Keys.FAVORITE_LN_ADDRESSES]) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    // Aliases for roadflare-rider compatibility
    val notificationSound: StateFlow<Boolean> get() = notificationSoundEnabled
    val notificationVibration: StateFlow<Boolean> get() = notificationVibrationEnabled

    // ========================================
    // Combined SettingsUiState (screen-facing projection)
    // ========================================

    val settings: StateFlow<SettingsUiState> = _preferences
        .map { prefs ->
            SettingsUiState(
                displayCurrency = try {
                    DisplayCurrency.valueOf(prefs[Keys.DISPLAY_CURRENCY] ?: "USD")
                } catch (e: IllegalArgumentException) { DisplayCurrency.USD },
                distanceUnit = try {
                    DistanceUnit.valueOf(prefs[Keys.DISTANCE_UNIT] ?: "MILES")
                } catch (e: IllegalArgumentException) { DistanceUnit.MILES },
                autoOpenNavigation = prefs[Keys.AUTO_OPEN_NAVIGATION] ?: true,
                notificationSoundEnabled = prefs[Keys.NOTIFICATION_SOUND] ?: true,
                notificationVibrationEnabled = prefs[Keys.NOTIFICATION_VIBRATION] ?: true,
                useGpsForPickup = prefs[Keys.USE_GPS_FOR_PICKUP] ?: false,
                alwaysAskVehicle = prefs[Keys.ALWAYS_ASK_VEHICLE] ?: true,
                activeVehicleId = prefs[Keys.ACTIVE_VEHICLE_ID],
                useGeocodingSearch = prefs[Keys.USE_GEOCODING_SEARCH] ?: true,
                useManualDriverLocation = prefs[Keys.USE_MANUAL_DRIVER_LOCATION] ?: false,
                manualDriverLat = (prefs[Keys.MANUAL_DRIVER_LAT] ?: 36.1699f).toDouble(),
                manualDriverLon = (prefs[Keys.MANUAL_DRIVER_LON] ?: -115.1398f).toDouble(),
                roadflareAlertsEnabled = prefs[Keys.ROADFLARE_ALERTS_ENABLED] ?: false,
                ignoreFollowNotifications = prefs[Keys.IGNORE_FOLLOW_NOTIFICATIONS] ?: false,
                customRelays = prefs[Keys.CUSTOM_RELAYS]?.split(",")?.filter { it.isNotBlank() } ?: emptyList(),
                isUsingCustomRelays = prefs[Keys.CUSTOM_RELAYS]?.split(",")?.any { it.isNotBlank() } ?: false,
                paymentMethods = (prefs[Keys.PAYMENT_METHODS]?.split(",")?.filter { it.isNotBlank() } ?: emptyList())
                    .ifEmpty { listOf("cashu") },
                defaultPaymentMethod = coerceDefaultPaymentMethod(prefs[Keys.DEFAULT_PAYMENT_METHOD]),
                mintUrl = prefs[Keys.MINT_URL],
                roadflarePaymentMethods = prefs[Keys.ROADFLARE_PAYMENT_METHODS]?.split(",")
                    ?.filter { it.isNotBlank() }?.distinct() ?: emptyList(),
                favoriteLnAddresses = parseFavoriteLnAddresses(prefs[Keys.FAVORITE_LN_ADDRESSES]),
                alwaysShowWalletDiagnostics = prefs[Keys.ALWAYS_SHOW_WALLET_DIAGNOSTICS] ?: false,
                walletSetupCompleted = prefs[Keys.WALLET_SETUP_COMPLETED] ?: false,
                walletSetupSkipped = prefs[Keys.WALLET_SETUP_SKIPPED] ?: false
            )
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, SettingsUiState())

    // ========================================
    // Synchronous Getters (for ViewModel/Service internal reads)
    // Read from _preferences.value[Key] directly — bypasses derived flow pipeline.
    // ========================================

    fun isOnboardingCompleted(): Boolean = _preferences.value[Keys.ONBOARDING_COMPLETED] ?: false
    fun isTilesSetupCompleted(): Boolean = _preferences.value[Keys.TILES_SETUP_COMPLETED] ?: false
    fun isWalletSetupDone(): Boolean =
        (_preferences.value[Keys.WALLET_SETUP_COMPLETED] ?: false) ||
        (_preferences.value[Keys.WALLET_SETUP_SKIPPED] ?: false)
    fun getEncryptionFallbackWarned(): Boolean = _preferences.value[Keys.ENCRYPTION_FALLBACK_WARNED] ?: false

    fun getDisplayCurrency(): DisplayCurrency =
        try { DisplayCurrency.valueOf(_preferences.value[Keys.DISPLAY_CURRENCY] ?: "USD") }
        catch (e: IllegalArgumentException) { DisplayCurrency.USD }

    fun getDistanceUnit(): DistanceUnit =
        try { DistanceUnit.valueOf(_preferences.value[Keys.DISTANCE_UNIT] ?: "MILES") }
        catch (e: IllegalArgumentException) { DistanceUnit.MILES }

    fun getUseGpsForPickup(): Boolean = _preferences.value[Keys.USE_GPS_FOR_PICKUP] ?: false
    fun getNotificationSoundEnabled(): Boolean = _preferences.value[Keys.NOTIFICATION_SOUND] ?: true
    fun getNotificationVibrationEnabled(): Boolean = _preferences.value[Keys.NOTIFICATION_VIBRATION] ?: true
    fun getIgnoreFollowNotifications(): Boolean = _preferences.value[Keys.IGNORE_FOLLOW_NOTIFICATIONS] ?: false

    fun getDefaultPaymentMethod(): String =
        coerceDefaultPaymentMethod(_preferences.value[Keys.DEFAULT_PAYMENT_METHOD])

    fun getPaymentMethods(): List<String> =
        (_preferences.value[Keys.PAYMENT_METHODS]?.split(",")?.filter { it.isNotBlank() } ?: emptyList())
            .ifEmpty { listOf("cashu") }

    fun getRoadflarePaymentMethods(): List<String> =
        _preferences.value[Keys.ROADFLARE_PAYMENT_METHODS]?.split(",")
            ?.filter { it.isNotBlank() }?.distinct() ?: emptyList()

    fun getEffectiveRelays(): List<String> {
        val custom = getCustomRelaysInternal()
        return if (custom.isNotEmpty()) custom else DEFAULT_RELAYS
    }

    fun isUsingCustomRelays(): Boolean = getCustomRelaysInternal().isNotEmpty()

    private fun getCustomRelaysInternal(): List<String> =
        _preferences.value[Keys.CUSTOM_RELAYS]?.split(",")?.filter { it.isNotBlank() } ?: emptyList()

    // ========================================
    // Suspend Setters
    // Every setter updates _preferences synchronously from updateData return.
    // ========================================

    suspend fun setAutoOpenNavigation(enabled: Boolean) {
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { set(Keys.AUTO_OPEN_NAVIGATION, enabled) }
        }
        _preferences.value = updated
    }

    suspend fun setDisplayCurrency(currency: DisplayCurrency) {
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { set(Keys.DISPLAY_CURRENCY, currency.name) }
        }
        _preferences.value = updated
    }

    suspend fun toggleDisplayCurrency() {
        val updated = dataStore.updateData { prefs ->
            val current = try { DisplayCurrency.valueOf(prefs[Keys.DISPLAY_CURRENCY] ?: "USD") }
            catch (e: IllegalArgumentException) { DisplayCurrency.USD }
            val new = if (current == DisplayCurrency.SATS) DisplayCurrency.USD else DisplayCurrency.SATS
            prefs.toMutablePreferences().apply { set(Keys.DISPLAY_CURRENCY, new.name) }
        }
        _preferences.value = updated
    }

    suspend fun setDistanceUnit(unit: DistanceUnit) {
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { set(Keys.DISTANCE_UNIT, unit.name) }
        }
        _preferences.value = updated
    }

    suspend fun toggleDistanceUnit() {
        val updated = dataStore.updateData { prefs ->
            val current = try { DistanceUnit.valueOf(prefs[Keys.DISTANCE_UNIT] ?: "MILES") }
            catch (e: IllegalArgumentException) { DistanceUnit.MILES }
            val new = if (current == DistanceUnit.MILES) DistanceUnit.KILOMETERS else DistanceUnit.MILES
            prefs.toMutablePreferences().apply { set(Keys.DISTANCE_UNIT, new.name) }
        }
        _preferences.value = updated
    }

    suspend fun setNotificationSoundEnabled(enabled: Boolean) {
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { set(Keys.NOTIFICATION_SOUND, enabled) }
        }
        _preferences.value = updated
    }

    suspend fun setNotificationVibrationEnabled(enabled: Boolean) {
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { set(Keys.NOTIFICATION_VIBRATION, enabled) }
        }
        _preferences.value = updated
    }

    suspend fun setUseGpsForPickup(enabled: Boolean) {
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { set(Keys.USE_GPS_FOR_PICKUP, enabled) }
        }
        _preferences.value = updated
    }

    suspend fun setAlwaysAskVehicle(enabled: Boolean) {
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { set(Keys.ALWAYS_ASK_VEHICLE, enabled) }
        }
        _preferences.value = updated
    }

    suspend fun setActiveVehicleId(vehicleId: String?) {
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                if (vehicleId == null) remove(Keys.ACTIVE_VEHICLE_ID)
                else set(Keys.ACTIVE_VEHICLE_ID, vehicleId)
            }
        }
        _preferences.value = updated
    }

    suspend fun setUseGeocodingSearch(enabled: Boolean) {
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { set(Keys.USE_GEOCODING_SEARCH, enabled) }
        }
        _preferences.value = updated
    }

    suspend fun toggleUseGeocodingSearch() {
        val updated = dataStore.updateData { prefs ->
            val current = prefs[Keys.USE_GEOCODING_SEARCH] ?: true
            prefs.toMutablePreferences().apply { set(Keys.USE_GEOCODING_SEARCH, !current) }
        }
        _preferences.value = updated
    }

    suspend fun setUseManualDriverLocation(enabled: Boolean) {
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { set(Keys.USE_MANUAL_DRIVER_LOCATION, enabled) }
        }
        _preferences.value = updated
    }

    suspend fun setManualDriverLocation(lat: Double, lon: Double) {
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                set(Keys.MANUAL_DRIVER_LAT, lat.toFloat())
                set(Keys.MANUAL_DRIVER_LON, lon.toFloat())
            }
        }
        _preferences.value = updated
    }

    suspend fun setOnboardingCompleted(completed: Boolean) {
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { set(Keys.ONBOARDING_COMPLETED, completed) }
        }
        _preferences.value = updated
    }

    suspend fun setTilesSetupCompleted(completed: Boolean) {
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { set(Keys.TILES_SETUP_COMPLETED, completed) }
        }
        _preferences.value = updated
    }

    suspend fun setWalletSetupCompleted(completed: Boolean) {
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { set(Keys.WALLET_SETUP_COMPLETED, completed) }
        }
        _preferences.value = updated
    }

    suspend fun setWalletSetupSkipped(skipped: Boolean) {
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { set(Keys.WALLET_SETUP_SKIPPED, skipped) }
        }
        _preferences.value = updated
    }

    suspend fun setAlwaysShowWalletDiagnostics(enabled: Boolean) {
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { set(Keys.ALWAYS_SHOW_WALLET_DIAGNOSTICS, enabled) }
        }
        _preferences.value = updated
    }

    suspend fun setIgnoreFollowNotifications(enabled: Boolean) {
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { set(Keys.IGNORE_FOLLOW_NOTIFICATIONS, enabled) }
        }
        _preferences.value = updated
    }

    suspend fun setRoadflareAlertsEnabled(enabled: Boolean) {
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { set(Keys.ROADFLARE_ALERTS_ENABLED, enabled) }
        }
        _preferences.value = updated
    }

    suspend fun setPaymentMethods(methods: List<String>) {
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                val filtered = methods.filter { it.isNotBlank() }
                if (filtered.isEmpty()) {
                    remove(Keys.PAYMENT_METHODS)
                } else {
                    set(Keys.PAYMENT_METHODS, filtered.joinToString(","))
                }
            }
        }
        _preferences.value = updated
    }

    suspend fun setDefaultPaymentMethod(method: String) {
        val coerced = coerceDefaultPaymentMethod(method)
        if (coerced != method) {
            Log.w(TAG, "Non-cashu defaultPaymentMethod '$method' coerced to cashu")
        }
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { set(Keys.DEFAULT_PAYMENT_METHOD, coerced) }
        }
        _preferences.value = updated
    }

    suspend fun setRoadflarePaymentMethods(methods: List<String>) {
        val normalized = methods.filter { it.isNotBlank() }.distinct()
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                if (normalized.isEmpty()) {
                    remove(Keys.ROADFLARE_PAYMENT_METHODS)
                } else {
                    set(Keys.ROADFLARE_PAYMENT_METHODS, normalized.joinToString(","))
                }
            }
        }
        _preferences.value = updated
    }

    suspend fun setMintUrl(url: String?) {
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                if (url == null) remove(Keys.MINT_URL)
                else set(Keys.MINT_URL, url)
            }
        }
        _preferences.value = updated
    }

    suspend fun setEncryptionFallbackWarned(warned: Boolean) {
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply { set(Keys.ENCRYPTION_FALLBACK_WARNED, warned) }
        }
        _preferences.value = updated
    }

    // ========================================
    // Relay Management
    // ========================================

    suspend fun addRelay(url: String) {
        var trimmedUrl = url.trim()
        if (trimmedUrl.isBlank()) return

        if (!trimmedUrl.startsWith("wss://") && !trimmedUrl.startsWith("ws://")) {
            trimmedUrl = "wss://$trimmedUrl"
        }

        val current = getCustomRelaysInternal().ifEmpty { DEFAULT_RELAYS }.toMutableList()
        if (current.size >= MAX_RELAYS) return
        if (current.contains(trimmedUrl)) return
        current.add(trimmedUrl)
        setCustomRelays(current)
    }

    suspend fun removeRelay(url: String) {
        val current = getCustomRelaysInternal().ifEmpty { DEFAULT_RELAYS }.toMutableList()
        if (current.remove(url)) {
            setCustomRelays(current)
        }
    }

    suspend fun resetRelaysToDefault() {
        setCustomRelays(emptyList())
    }

    private suspend fun setCustomRelays(relays: List<String>) {
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                if (relays.isEmpty()) {
                    remove(Keys.CUSTOM_RELAYS)
                } else {
                    set(Keys.CUSTOM_RELAYS, relays.joinToString(","))
                }
            }
        }
        _preferences.value = updated
    }

    // ========================================
    // Favorite LN Addresses
    // ========================================

    suspend fun addFavoriteLnAddress(address: String, label: String? = null): Boolean {
        val normalized = address.lowercase().trim()
        val current = parseFavoriteLnAddresses(_preferences.value[Keys.FAVORITE_LN_ADDRESSES])

        if (current.any { it.address.lowercase() == normalized }) {
            updateFavoriteLastUsed(normalized)
            return false
        }
        if (current.size >= MAX_FAVORITE_ADDRESSES) return false

        val newFavorite = FavoriteLnAddress(
            address = normalized,
            label = label?.trim()?.takeIf { it.isNotEmpty() },
            lastUsed = System.currentTimeMillis()
        )
        saveFavoriteLnAddresses(current + newFavorite)
        return true
    }

    suspend fun removeFavoriteLnAddress(address: String) {
        val normalized = address.lowercase().trim()
        val current = parseFavoriteLnAddresses(_preferences.value[Keys.FAVORITE_LN_ADDRESSES])
        val updated = current.filter { it.address.lowercase() != normalized }
        saveFavoriteLnAddresses(updated)
    }

    suspend fun updateFavoriteLnAddressLabel(address: String, newLabel: String?) {
        val normalized = address.lowercase().trim()
        val current = parseFavoriteLnAddresses(_preferences.value[Keys.FAVORITE_LN_ADDRESSES])
        val updated = current.map { fav ->
            if (fav.address.lowercase() == normalized) {
                fav.copy(label = newLabel?.trim()?.takeIf { it.isNotEmpty() })
            } else fav
        }
        saveFavoriteLnAddresses(updated)
    }

    suspend fun updateFavoriteLastUsed(address: String) {
        val normalized = address.lowercase().trim()
        val current = parseFavoriteLnAddresses(_preferences.value[Keys.FAVORITE_LN_ADDRESSES])
        val updated = current.map { fav ->
            if (fav.address.lowercase() == normalized) {
                fav.copy(lastUsed = System.currentTimeMillis())
            } else fav
        }
        saveFavoriteLnAddresses(updated)
    }

    fun isFavoriteLnAddress(address: String): Boolean {
        val normalized = address.lowercase().trim()
        return parseFavoriteLnAddresses(_preferences.value[Keys.FAVORITE_LN_ADDRESSES])
            .any { it.address.lowercase() == normalized }
    }

    private suspend fun saveFavoriteLnAddresses(addresses: List<FavoriteLnAddress>) {
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                set(Keys.FAVORITE_LN_ADDRESSES, serializeFavoriteLnAddresses(addresses))
            }
        }
        _preferences.value = updated
    }

    // ========================================
    // Vehicle Sanitization
    // ========================================

    suspend fun sanitizeActiveVehicleId(validVehicleIds: Set<String>) {
        val currentId = _preferences.value[Keys.ACTIVE_VEHICLE_ID]
        if (currentId != null && currentId !in validVehicleIds) {
            setActiveVehicleId(null)
        }
    }

    // ========================================
    // Syncable Settings Hash (for auto-backup observer)
    // ========================================

    val syncableSettingsHash: Flow<Int> = combine(
        displayCurrency, distanceUnit, notificationSoundEnabled,
        notificationVibrationEnabled, autoOpenNavigation
    ) { dc, du, ns, nv, ao ->
        listOf(dc.ordinal, du.ordinal, ns, nv, ao).hashCode()
    }.combine(combine(
        alwaysAskVehicle, customRelays, paymentMethods,
        defaultPaymentMethod, mintUrl
    ) { aav, cr, pm, dpm, mu ->
        listOf(aav, cr, pm, dpm, mu).hashCode()
    }) { h1, h2 -> h1 * 31 + h2 }
    .combine(roadflarePaymentMethods) { hash, rpm -> hash * 31 + rpm.hashCode() }

    // ========================================
    // Backup / Restore
    // ========================================

    fun hasCustomSettings(): Boolean = toBackupData() != SettingsBackup()

    fun toBackupData(): SettingsBackup {
        return SettingsBackup(
            displayCurrency = getDisplayCurrency(),
            distanceUnit = getDistanceUnit(),
            notificationSoundEnabled = getNotificationSoundEnabled(),
            notificationVibrationEnabled = getNotificationVibrationEnabled(),
            autoOpenNavigation = _preferences.value[Keys.AUTO_OPEN_NAVIGATION] ?: true,
            alwaysAskVehicle = _preferences.value[Keys.ALWAYS_ASK_VEHICLE] ?: true,
            customRelays = getCustomRelaysInternal(),
            paymentMethods = getPaymentMethods(),
            defaultPaymentMethod = getDefaultPaymentMethod(),
            mintUrl = _preferences.value[Keys.MINT_URL],
            roadflarePaymentMethods = getRoadflarePaymentMethods()
        )
    }

    suspend fun restoreFromBackup(backup: SettingsBackup) {
        val updated = dataStore.updateData { prefs ->
            prefs.toMutablePreferences().apply {
                set(Keys.DISPLAY_CURRENCY, backup.displayCurrency.name)
                set(Keys.DISTANCE_UNIT, backup.distanceUnit.name)
                set(Keys.NOTIFICATION_SOUND, backup.notificationSoundEnabled)
                set(Keys.NOTIFICATION_VIBRATION, backup.notificationVibrationEnabled)
                set(Keys.AUTO_OPEN_NAVIGATION, backup.autoOpenNavigation)
                set(Keys.ALWAYS_ASK_VEHICLE, backup.alwaysAskVehicle)
                if (backup.customRelays.isNotEmpty()) {
                    set(Keys.CUSTOM_RELAYS, backup.customRelays.joinToString(","))
                } else {
                    remove(Keys.CUSTOM_RELAYS)
                }
                val methods = backup.paymentMethods.filter { it.isNotBlank() }.ifEmpty { listOf("cashu") }
                set(Keys.PAYMENT_METHODS, methods.joinToString(","))
                val defaultMethod = coerceDefaultPaymentMethod(backup.defaultPaymentMethod)
                set(Keys.DEFAULT_PAYMENT_METHOD, defaultMethod)
                if (backup.mintUrl != null) set(Keys.MINT_URL, backup.mintUrl) else remove(Keys.MINT_URL)
                val rfMethods = backup.roadflarePaymentMethods.filter { it.isNotBlank() }.distinct()
                if (rfMethods.isNotEmpty()) {
                    set(Keys.ROADFLARE_PAYMENT_METHODS, rfMethods.joinToString(","))
                } else {
                    remove(Keys.ROADFLARE_PAYMENT_METHODS)
                }
            }
        }
        _preferences.value = updated
    }

    suspend fun clearAllData() {
        val updated = dataStore.updateData { emptyPreferences() }
        _preferences.value = updated
    }

    // ========================================
    // JSON Helpers for Favorite LN Addresses
    // ========================================

    private fun parseFavoriteLnAddresses(json: String?): List<FavoriteLnAddress> {
        if (json == null) return emptyList()
        return try {
            val jsonArray = org.json.JSONArray(json)
            (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                FavoriteLnAddress(
                    address = obj.getString("address"),
                    label = obj.optString("label", "").takeIf { it.isNotEmpty() },
                    lastUsed = obj.optLong("lastUsed", System.currentTimeMillis())
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun serializeFavoriteLnAddresses(addresses: List<FavoriteLnAddress>): String {
        val jsonArray = org.json.JSONArray()
        addresses.forEach { fav ->
            val obj = org.json.JSONObject()
            obj.put("address", fav.address)
            fav.label?.let { obj.put("label", it) }
            obj.put("lastUsed", fav.lastUsed)
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }
}
