package com.ridestr.common.settings

import android.content.Context
import android.content.SharedPreferences
import com.ridestr.common.nostr.events.SettingsBackup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine

/**
 * Currency display preference for fare amounts.
 */
enum class DisplayCurrency {
    SATS,  // Display fares in satoshis
    USD    // Display fares in US dollars
}

/**
 * Distance unit preference for displaying distances.
 */
enum class DistanceUnit {
    MILES,      // Display distances in miles
    KILOMETERS  // Display distances in kilometers
}

/**
 * Manages app settings using SharedPreferences with StateFlow for reactive UI updates.
 */
class SettingsManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "ridestr_settings"
        private const val KEY_AUTO_OPEN_NAVIGATION = "auto_open_navigation"
        private const val KEY_DISPLAY_CURRENCY = "display_currency"
        private const val KEY_DISTANCE_UNIT = "distance_unit"

        // Onboarding
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

        // Debug settings
        private const val KEY_USE_GEOCODING_SEARCH = "use_geocoding_search"
        private const val KEY_USE_MANUAL_DRIVER_LOCATION = "use_manual_driver_location"
        private const val KEY_MANUAL_DRIVER_LAT = "manual_driver_lat"
        private const val KEY_MANUAL_DRIVER_LON = "manual_driver_lon"

        // Custom relays (stored as comma-separated URLs)
        private const val KEY_CUSTOM_RELAYS = "custom_relays"

        // Notification settings
        private const val KEY_NOTIFICATION_SOUND = "notification_sound"
        private const val KEY_NOTIFICATION_VIBRATION = "notification_vibration"

        // Vehicle selection settings (driver app only)
        private const val KEY_ALWAYS_ASK_VEHICLE = "always_ask_vehicle"
        private const val KEY_ACTIVE_VEHICLE_ID = "active_vehicle_id"

        // Rider pickup location preference
        private const val KEY_USE_GPS_FOR_PICKUP = "use_gps_for_pickup"

        // Wallet settings
        private const val KEY_WALLET_SETUP_COMPLETED = "wallet_setup_completed"
        private const val KEY_WALLET_SETUP_SKIPPED = "wallet_setup_skipped"
        private const val KEY_ALWAYS_SHOW_WALLET_DIAGNOSTICS = "always_show_wallet_diagnostics"

        // RoadFlare debug settings
        private const val KEY_IGNORE_FOLLOW_NOTIFICATIONS = "ignore_follow_notifications"

        // RoadFlare alerts (driver app only)
        private const val KEY_ROADFLARE_ALERTS_ENABLED = "roadflare_alerts_enabled"

        // Payment settings (Issue #13 - multi-mint support)
        private const val KEY_PAYMENT_METHODS = "payment_methods"
        private const val KEY_DEFAULT_PAYMENT_METHOD = "default_payment_method"
        private const val KEY_MINT_URL = "mint_url"

        // RoadFlare alternate payment methods (rider: what they offer, driver: what they accept)
        private const val KEY_ROADFLARE_PAYMENT_METHODS = "roadflare_payment_methods"

        // Favorite Lightning addresses (Issue #14)
        private const val KEY_FAVORITE_LN_ADDRESSES = "favorite_ln_addresses"
        const val MAX_FAVORITE_ADDRESSES = 10

        // Security warning dismissal
        private const val KEY_ENCRYPTION_FALLBACK_WARNED = "encryption_fallback_warned"

        // Default manual location: Las Vegas (Fremont St)
        private const val DEFAULT_MANUAL_LAT = 36.1699
        private const val DEFAULT_MANUAL_LON = -115.1398

        // Default relays (copied from RelayConfig for convenience)
        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net"
        )

        // Maximum number of relays allowed
        const val MAX_RELAYS = 10
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Auto-open navigation setting (default: true)
    private val _autoOpenNavigation = MutableStateFlow(prefs.getBoolean(KEY_AUTO_OPEN_NAVIGATION, true))
    val autoOpenNavigation: StateFlow<Boolean> = _autoOpenNavigation.asStateFlow()

    /**
     * Set whether to automatically open navigation app when ride stage changes.
     */
    fun setAutoOpenNavigation(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_OPEN_NAVIGATION, enabled).apply()
        _autoOpenNavigation.value = enabled
    }

    /**
     * Toggle auto-open navigation setting.
     */
    fun toggleAutoOpenNavigation() {
        setAutoOpenNavigation(!_autoOpenNavigation.value)
    }

    // Notification sound setting (default: true)
    private val _notificationSoundEnabled = MutableStateFlow(prefs.getBoolean(KEY_NOTIFICATION_SOUND, true))
    val notificationSoundEnabled: StateFlow<Boolean> = _notificationSoundEnabled.asStateFlow()

    /**
     * Set whether notification sounds are enabled.
     */
    fun setNotificationSoundEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATION_SOUND, enabled).apply()
        _notificationSoundEnabled.value = enabled
    }

    /**
     * Toggle notification sound on/off.
     */
    fun toggleNotificationSound() {
        setNotificationSoundEnabled(!_notificationSoundEnabled.value)
    }

    // Notification vibration setting (default: true)
    private val _notificationVibrationEnabled = MutableStateFlow(prefs.getBoolean(KEY_NOTIFICATION_VIBRATION, true))
    val notificationVibrationEnabled: StateFlow<Boolean> = _notificationVibrationEnabled.asStateFlow()

    /**
     * Set whether notification vibration is enabled.
     */
    fun setNotificationVibrationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NOTIFICATION_VIBRATION, enabled).apply()
        _notificationVibrationEnabled.value = enabled
    }

    /**
     * Toggle notification vibration on/off.
     */
    fun toggleNotificationVibration() {
        setNotificationVibrationEnabled(!_notificationVibrationEnabled.value)
    }

    // ===================
    // RIDER PICKUP LOCATION (Rider App)
    // ===================

    // "Use my location" checkbox preference (default: false)
    private val _useGpsForPickup = MutableStateFlow(prefs.getBoolean(KEY_USE_GPS_FOR_PICKUP, false))
    val useGpsForPickup: StateFlow<Boolean> = _useGpsForPickup.asStateFlow()

    /**
     * Set whether to use GPS for pickup location by default.
     */
    fun setUseGpsForPickup(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_GPS_FOR_PICKUP, enabled).apply()
        _useGpsForPickup.value = enabled
    }

    // ===================
    // VEHICLE SELECTION (Driver App)
    // ===================

    // "Always ask which vehicle when going online" toggle (default: true)
    private val _alwaysAskVehicle = MutableStateFlow(prefs.getBoolean(KEY_ALWAYS_ASK_VEHICLE, true))
    val alwaysAskVehicle: StateFlow<Boolean> = _alwaysAskVehicle.asStateFlow()

    /**
     * Set whether to always ask which vehicle when going online.
     * When true and user has multiple vehicles, shows a vehicle picker dialog.
     */
    fun setAlwaysAskVehicle(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALWAYS_ASK_VEHICLE, enabled).apply()
        _alwaysAskVehicle.value = enabled
    }

    // Active vehicle ID - the vehicle currently being used for driving
    private val _activeVehicleId = MutableStateFlow<String?>(prefs.getString(KEY_ACTIVE_VEHICLE_ID, null))
    val activeVehicleId: StateFlow<String?> = _activeVehicleId.asStateFlow()

    /**
     * Set the active vehicle ID (the one being used for the current driving session).
     */
    fun setActiveVehicleId(vehicleId: String?) {
        if (vehicleId == null) {
            prefs.edit().remove(KEY_ACTIVE_VEHICLE_ID).apply()
        } else {
            prefs.edit().putString(KEY_ACTIVE_VEHICLE_ID, vehicleId).apply()
        }
        _activeVehicleId.value = vehicleId
    }

    // Currency display setting (default: USD)
    private val _displayCurrency = MutableStateFlow(
        try {
            DisplayCurrency.valueOf(
                prefs.getString(KEY_DISPLAY_CURRENCY, DisplayCurrency.USD.name)
                    ?: DisplayCurrency.USD.name
            )
        } catch (e: IllegalArgumentException) {
            DisplayCurrency.USD
        }
    )
    val displayCurrency: StateFlow<DisplayCurrency> = _displayCurrency.asStateFlow()

    // Distance unit setting (default: MILES)
    private val _distanceUnit = MutableStateFlow(
        try {
            DistanceUnit.valueOf(
                prefs.getString(KEY_DISTANCE_UNIT, DistanceUnit.MILES.name)
                    ?: DistanceUnit.MILES.name
            )
        } catch (e: IllegalArgumentException) {
            DistanceUnit.MILES
        }
    )
    val distanceUnit: StateFlow<DistanceUnit> = _distanceUnit.asStateFlow()

    /**
     * Set the currency display preference.
     */
    fun setDisplayCurrency(currency: DisplayCurrency) {
        prefs.edit().putString(KEY_DISPLAY_CURRENCY, currency.name).apply()
        _displayCurrency.value = currency
    }

    /**
     * Toggle between SATS and USD display.
     */
    fun toggleDisplayCurrency() {
        val newCurrency = if (_displayCurrency.value == DisplayCurrency.SATS) {
            DisplayCurrency.USD
        } else {
            DisplayCurrency.SATS
        }
        setDisplayCurrency(newCurrency)
    }

    /**
     * Set the distance unit preference.
     */
    fun setDistanceUnit(unit: DistanceUnit) {
        prefs.edit().putString(KEY_DISTANCE_UNIT, unit.name).apply()
        _distanceUnit.value = unit
    }

    /**
     * Toggle between MILES and KILOMETERS display.
     */
    fun toggleDistanceUnit() {
        val newUnit = if (_distanceUnit.value == DistanceUnit.MILES) {
            DistanceUnit.KILOMETERS
        } else {
            DistanceUnit.MILES
        }
        setDistanceUnit(newUnit)
    }

    // ===================
    // DEBUG SETTINGS
    // ===================

    // Use geocoding search instead of manual coordinate entry (default: true)
    private val _useGeocodingSearch = MutableStateFlow(prefs.getBoolean(KEY_USE_GEOCODING_SEARCH, true))
    val useGeocodingSearch: StateFlow<Boolean> = _useGeocodingSearch.asStateFlow()

    /**
     * Set whether to use geocoding search for location input.
     * When false, shows manual coordinate entry fields.
     */
    fun setUseGeocodingSearch(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_GEOCODING_SEARCH, enabled).apply()
        _useGeocodingSearch.value = enabled
    }

    /**
     * Toggle geocoding search on/off.
     */
    fun toggleUseGeocodingSearch() {
        setUseGeocodingSearch(!_useGeocodingSearch.value)
    }

    // Manual driver location override (for testing when GPS isn't working)
    private val _useManualDriverLocation = MutableStateFlow(prefs.getBoolean(KEY_USE_MANUAL_DRIVER_LOCATION, false))
    val useManualDriverLocation: StateFlow<Boolean> = _useManualDriverLocation.asStateFlow()

    private val _manualDriverLat = MutableStateFlow(
        prefs.getFloat(KEY_MANUAL_DRIVER_LAT, DEFAULT_MANUAL_LAT.toFloat()).toDouble()
    )
    val manualDriverLat: StateFlow<Double> = _manualDriverLat.asStateFlow()

    private val _manualDriverLon = MutableStateFlow(
        prefs.getFloat(KEY_MANUAL_DRIVER_LON, DEFAULT_MANUAL_LON.toFloat()).toDouble()
    )
    val manualDriverLon: StateFlow<Double> = _manualDriverLon.asStateFlow()

    /**
     * Enable/disable manual driver location override.
     */
    fun setUseManualDriverLocation(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_MANUAL_DRIVER_LOCATION, enabled).apply()
        _useManualDriverLocation.value = enabled
    }

    /**
     * Toggle manual driver location on/off.
     */
    fun toggleUseManualDriverLocation() {
        setUseManualDriverLocation(!_useManualDriverLocation.value)
    }

    /**
     * Set manual driver location coordinates.
     */
    fun setManualDriverLocation(lat: Double, lon: Double) {
        prefs.edit()
            .putFloat(KEY_MANUAL_DRIVER_LAT, lat.toFloat())
            .putFloat(KEY_MANUAL_DRIVER_LON, lon.toFloat())
            .apply()
        _manualDriverLat.value = lat
        _manualDriverLon.value = lon
    }

    // ===================
    // ONBOARDING
    // ===================

    /**
     * Check if the user has completed the full onboarding flow
     * (key setup, profile, location permission, tile setup).
     */
    fun isOnboardingCompleted(): Boolean {
        return prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    /**
     * Mark onboarding as completed or not.
     */
    fun setOnboardingCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, completed).apply()
    }

    /**
     * Reset onboarding (for logout).
     */
    fun resetOnboarding() {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, false).apply()
    }

    // ===================
    // WALLET SETUP
    // ===================

    // Wallet setup completed (user selected a mint and connected)
    private val _walletSetupCompleted = MutableStateFlow(prefs.getBoolean(KEY_WALLET_SETUP_COMPLETED, false))
    val walletSetupCompleted: StateFlow<Boolean> = _walletSetupCompleted.asStateFlow()

    // Wallet setup skipped (user chose to skip for now)
    private val _walletSetupSkipped = MutableStateFlow(prefs.getBoolean(KEY_WALLET_SETUP_SKIPPED, false))
    val walletSetupSkipped: StateFlow<Boolean> = _walletSetupSkipped.asStateFlow()

    /**
     * Mark wallet setup as completed.
     */
    fun setWalletSetupCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_WALLET_SETUP_COMPLETED, completed).apply()
        _walletSetupCompleted.value = completed
    }

    /**
     * Mark wallet setup as skipped.
     */
    fun setWalletSetupSkipped(skipped: Boolean) {
        prefs.edit().putBoolean(KEY_WALLET_SETUP_SKIPPED, skipped).apply()
        _walletSetupSkipped.value = skipped
    }

    /**
     * Check if wallet setup has been done (either completed or skipped).
     */
    fun isWalletSetupDone(): Boolean {
        return _walletSetupCompleted.value || _walletSetupSkipped.value
    }

    /**
     * Reset wallet setup status (for logout or re-setup).
     */
    fun resetWalletSetup() {
        prefs.edit()
            .putBoolean(KEY_WALLET_SETUP_COMPLETED, false)
            .putBoolean(KEY_WALLET_SETUP_SKIPPED, false)
            .apply()
        _walletSetupCompleted.value = false
        _walletSetupSkipped.value = false
    }

    // Always show wallet diagnostics (developer option, default: false)
    private val _alwaysShowWalletDiagnostics = MutableStateFlow(prefs.getBoolean(KEY_ALWAYS_SHOW_WALLET_DIAGNOSTICS, false))
    val alwaysShowWalletDiagnostics: StateFlow<Boolean> = _alwaysShowWalletDiagnostics.asStateFlow()

    /**
     * Set whether to always show the wallet diagnostics icon (even when synced).
     * When enabled, shows a green icon when wallet is fully synced.
     */
    fun setAlwaysShowWalletDiagnostics(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ALWAYS_SHOW_WALLET_DIAGNOSTICS, enabled).apply()
        _alwaysShowWalletDiagnostics.value = enabled
    }

    // ===================
    // ROADFLARE DEBUG SETTINGS
    // ===================

    // Ignore Kind 3187 follow notifications (for testing p-tag queries)
    private val _ignoreFollowNotifications = MutableStateFlow(prefs.getBoolean(KEY_IGNORE_FOLLOW_NOTIFICATIONS, false))
    val ignoreFollowNotifications: StateFlow<Boolean> = _ignoreFollowNotifications.asStateFlow()

    /**
     * Set whether to ignore Kind 3187 follow notifications.
     * When enabled, driver app won't process real-time follow notifications,
     * forcing use of p-tag queries on Kind 30011 for follower discovery.
     * Useful for testing that the p-tag query mechanism works correctly.
     */
    fun setIgnoreFollowNotifications(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_IGNORE_FOLLOW_NOTIFICATIONS, enabled).apply()
        _ignoreFollowNotifications.value = enabled
    }

    // ===================
    // ROADFLARE ALERTS (Driver app only)
    // ===================

    // RoadFlare alerts enabled - when true, driver receives notifications even when offline
    private val _roadflareAlertsEnabled = MutableStateFlow(prefs.getBoolean(KEY_ROADFLARE_ALERTS_ENABLED, false))
    val roadflareAlertsEnabled: StateFlow<Boolean> = _roadflareAlertsEnabled.asStateFlow()

    /**
     * Set whether RoadFlare alerts are enabled.
     * When enabled, driver app runs a background service that listens for RoadFlare
     * requests and shows notifications even when the main app is closed.
     */
    fun setRoadflareAlertsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ROADFLARE_ALERTS_ENABLED, enabled).apply()
        _roadflareAlertsEnabled.value = enabled
    }

    // ===================
    // PAYMENT SETTINGS (Issue #13 - Multi-mint support)
    // ===================

    // Supported payment methods (default: cashu only)
    private val _paymentMethods = MutableStateFlow(loadPaymentMethods())
    val paymentMethods: StateFlow<List<String>> = _paymentMethods.asStateFlow()

    private fun loadPaymentMethods(): List<String> {
        val methodsString = prefs.getString(KEY_PAYMENT_METHODS, null)
        return if (methodsString.isNullOrBlank()) {
            listOf("cashu") // Default to cashu
        } else {
            methodsString.split(",").filter { it.isNotBlank() }
        }
    }

    /**
     * Set supported payment methods.
     */
    fun setPaymentMethods(methods: List<String>) {
        if (methods.isEmpty()) {
            prefs.edit().remove(KEY_PAYMENT_METHODS).apply()
            _paymentMethods.value = listOf("cashu")
        } else {
            prefs.edit().putString(KEY_PAYMENT_METHODS, methods.joinToString(",")).apply()
            _paymentMethods.value = methods
        }
    }

    // Default payment method for new rides (default: cashu)
    private val _defaultPaymentMethod = MutableStateFlow(
        prefs.getString(KEY_DEFAULT_PAYMENT_METHOD, "cashu") ?: "cashu"
    )
    val defaultPaymentMethod: StateFlow<String> = _defaultPaymentMethod.asStateFlow()

    /**
     * Set the default payment method for new rides.
     */
    fun setDefaultPaymentMethod(method: String) {
        prefs.edit().putString(KEY_DEFAULT_PAYMENT_METHOD, method).apply()
        _defaultPaymentMethod.value = method
    }

    // RoadFlare alternate payment methods (e.g., "zelle", "venmo", "cash")
    private val _roadflarePaymentMethods = MutableStateFlow(loadRoadflarePaymentMethods())
    val roadflarePaymentMethods: StateFlow<Set<String>> = _roadflarePaymentMethods.asStateFlow()

    private fun loadRoadflarePaymentMethods(): Set<String> {
        val stored = prefs.getString(KEY_ROADFLARE_PAYMENT_METHODS, null)
        return if (stored.isNullOrBlank()) emptySet()
        else stored.split(",").filter { it.isNotBlank() }.toSet()
    }

    fun setRoadflarePaymentMethods(methods: Set<String>) {
        if (methods.isEmpty()) {
            prefs.edit().remove(KEY_ROADFLARE_PAYMENT_METHODS).apply()
        } else {
            prefs.edit().putString(KEY_ROADFLARE_PAYMENT_METHODS, methods.joinToString(",")).apply()
        }
        _roadflarePaymentMethods.value = methods
    }

    // Current Cashu mint URL (for backup/restore purposes)
    private val _mintUrl = MutableStateFlow<String?>(prefs.getString(KEY_MINT_URL, null))
    val mintUrl: StateFlow<String?> = _mintUrl.asStateFlow()

    /**
     * Set the current mint URL (called when user changes mint in wallet settings).
     */
    fun setMintUrl(url: String?) {
        if (url == null) {
            prefs.edit().remove(KEY_MINT_URL).apply()
        } else {
            prefs.edit().putString(KEY_MINT_URL, url).apply()
        }
        _mintUrl.value = url
    }

    // ===================
    // RELAY MANAGEMENT
    // ===================

    // Custom relays (empty means use defaults)
    private val _customRelays = MutableStateFlow(loadRelays())
    val customRelays: StateFlow<List<String>> = _customRelays.asStateFlow()

    /**
     * Load relays from SharedPreferences.
     * Returns empty list if no custom relays are set (meaning use defaults).
     */
    private fun loadRelays(): List<String> {
        val relaysString = prefs.getString(KEY_CUSTOM_RELAYS, null)
        return if (relaysString.isNullOrBlank()) {
            emptyList() // Use defaults
        } else {
            relaysString.split(",").filter { it.isNotBlank() }
        }
    }

    /**
     * Save relays to SharedPreferences.
     */
    private fun saveRelays(relays: List<String>) {
        if (relays.isEmpty()) {
            prefs.edit().remove(KEY_CUSTOM_RELAYS).apply()
        } else {
            prefs.edit().putString(KEY_CUSTOM_RELAYS, relays.joinToString(",")).apply()
        }
        _customRelays.value = relays
    }

    /**
     * Get the effective relay list (custom if set, otherwise defaults).
     */
    fun getEffectiveRelays(): List<String> {
        val custom = _customRelays.value
        return if (custom.isEmpty()) DEFAULT_RELAYS else custom
    }

    /**
     * Add a relay URL. Auto-prepends wss:// if not present.
     * Initializes from defaults if this is the first custom relay.
     * Maximum 10 relays allowed.
     */
    fun addRelay(url: String) {
        var trimmedUrl = url.trim()
        if (trimmedUrl.isBlank()) return

        // Auto-prepend wss:// if missing
        if (!trimmedUrl.startsWith("wss://") && !trimmedUrl.startsWith("ws://")) {
            trimmedUrl = "wss://$trimmedUrl"
        }

        val current = _customRelays.value.toMutableList()
        // If empty (using defaults), initialize with defaults first
        if (current.isEmpty()) {
            current.addAll(DEFAULT_RELAYS)
        }
        // Enforce max 10 relay limit
        if (current.size >= MAX_RELAYS) return

        if (!current.contains(trimmedUrl)) {
            current.add(trimmedUrl)
            saveRelays(current)
        }
    }

    /**
     * Remove a relay URL.
     */
    fun removeRelay(url: String) {
        val current = _customRelays.value.toMutableList()
        // If empty (using defaults), initialize with defaults first
        if (current.isEmpty()) {
            current.addAll(DEFAULT_RELAYS)
        }
        if (current.remove(url)) {
            saveRelays(current)
        }
    }

    /**
     * Reset to default relays.
     */
    fun resetRelaysToDefault() {
        saveRelays(emptyList())
    }

    /**
     * Check if using custom relays (vs defaults).
     */
    fun isUsingCustomRelays(): Boolean {
        return _customRelays.value.isNotEmpty()
    }

    // ===================
    // SYNCABLE SETTINGS HASH (for auto-backup observer)
    // ===================

    /**
     * Combined hash of all settings that are synced to Nostr.
     * Observe this to trigger auto-backup when any synced setting changes.
     * Uses combine() to merge all syncable settings into a single hash value.
     */
    val syncableSettingsHash = combine(
        _displayCurrency,
        _distanceUnit,
        _notificationSoundEnabled,
        _notificationVibrationEnabled,
        _autoOpenNavigation
    ) { dc, du, ns, nv, ao ->
        listOf(dc.ordinal, du.ordinal, ns, nv, ao).hashCode()
    }.combine(combine(
        _alwaysAskVehicle,
        _customRelays,
        _paymentMethods,
        _defaultPaymentMethod,
        _mintUrl
    ) { aav, cr, pm, dpm, mu ->
        listOf(aav, cr, pm, dpm, mu).hashCode()
    }) { hash1, hash2 ->
        hash1 * 31 + hash2
    }

    // ===================
    // BACKUP / RESTORE (Nostr Profile Sync)
    // ===================

    /**
     * Export current settings to a backup data object for Nostr sync.
     * Only includes user-facing settings, not internal state.
     */
    fun toBackupData(): SettingsBackup {
        return SettingsBackup(
            displayCurrency = _displayCurrency.value,
            distanceUnit = _distanceUnit.value,
            notificationSoundEnabled = _notificationSoundEnabled.value,
            notificationVibrationEnabled = _notificationVibrationEnabled.value,
            autoOpenNavigation = _autoOpenNavigation.value,
            alwaysAskVehicle = _alwaysAskVehicle.value,
            customRelays = _customRelays.value,
            // Payment settings (Issue #13)
            paymentMethods = _paymentMethods.value,
            defaultPaymentMethod = _defaultPaymentMethod.value,
            mintUrl = _mintUrl.value,
            // RoadFlare alternate payment methods
            roadflarePaymentMethods = _roadflarePaymentMethods.value.toList()
        )
    }

    /**
     * Restore settings from a backup data object (from Nostr sync).
     * Only restores user-facing settings, not internal state.
     */
    fun restoreFromBackup(backup: SettingsBackup) {
        // Apply each setting
        setDisplayCurrency(backup.displayCurrency)
        setDistanceUnit(backup.distanceUnit)
        setNotificationSoundEnabled(backup.notificationSoundEnabled)
        setNotificationVibrationEnabled(backup.notificationVibrationEnabled)
        setAutoOpenNavigation(backup.autoOpenNavigation)
        setAlwaysAskVehicle(backup.alwaysAskVehicle)

        // Restore custom relays (empty means use defaults)
        if (backup.customRelays.isNotEmpty()) {
            saveRelays(backup.customRelays)
        } else {
            resetRelaysToDefault()
        }

        // Restore payment settings (Issue #13)
        setPaymentMethods(backup.paymentMethods)
        setDefaultPaymentMethod(backup.defaultPaymentMethod)
        setMintUrl(backup.mintUrl)
        // Restore RoadFlare alternate payment methods
        setRoadflarePaymentMethods(backup.roadflarePaymentMethods.toSet())
    }

    /**
     * Clear all settings data (for logout).
     * Resets everything to defaults.
     */
    fun clearAllData() {
        prefs.edit().clear().apply()

        // Reset all StateFlows to defaults
        _autoOpenNavigation.value = true
        _displayCurrency.value = DisplayCurrency.SATS
        _distanceUnit.value = DistanceUnit.MILES
        _useGeocodingSearch.value = true
        _useManualDriverLocation.value = false
        _manualDriverLat.value = DEFAULT_MANUAL_LAT
        _manualDriverLon.value = DEFAULT_MANUAL_LON
        _customRelays.value = emptyList()
        _notificationSoundEnabled.value = true
        _notificationVibrationEnabled.value = true
        _alwaysAskVehicle.value = true
        _activeVehicleId.value = null
        _useGpsForPickup.value = false
        _walletSetupCompleted.value = false
        _walletSetupSkipped.value = false
        _alwaysShowWalletDiagnostics.value = false
        // Payment settings (Issue #13)
        _paymentMethods.value = listOf("cashu")
        _defaultPaymentMethod.value = "cashu"
        _mintUrl.value = null
        _roadflarePaymentMethods.value = emptySet()
        // Favorite LN addresses (Issue #14)
        _favoriteLnAddresses.value = emptyList()
    }

    // ========================================
    // Favorite Lightning Addresses (Issue #14)
    // ========================================

    /**
     * A favorite lightning address with optional label.
     */
    data class FavoriteLnAddress(
        val address: String,
        val label: String? = null,
        val lastUsed: Long = System.currentTimeMillis()
    )

    private val _favoriteLnAddresses = MutableStateFlow(loadFavoriteAddresses())
    val favoriteLnAddresses: StateFlow<List<FavoriteLnAddress>> = _favoriteLnAddresses.asStateFlow()

    private fun loadFavoriteAddresses(): List<FavoriteLnAddress> {
        val json = prefs.getString(KEY_FAVORITE_LN_ADDRESSES, null) ?: return emptyList()
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

    private fun saveFavoriteAddresses(addresses: List<FavoriteLnAddress>) {
        val jsonArray = org.json.JSONArray()
        addresses.forEach { fav ->
            val obj = org.json.JSONObject()
            obj.put("address", fav.address)
            fav.label?.let { obj.put("label", it) }
            obj.put("lastUsed", fav.lastUsed)
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_FAVORITE_LN_ADDRESSES, jsonArray.toString()).apply()
        _favoriteLnAddresses.value = addresses
    }

    /**
     * Add a lightning address to favorites.
     * @param address The lightning address (user@domain.com)
     * @param label Optional display label
     * @return true if added, false if already exists or limit reached
     */
    fun addFavoriteLnAddress(address: String, label: String? = null): Boolean {
        val normalized = address.lowercase().trim()
        val current = _favoriteLnAddresses.value

        // Check if already exists
        if (current.any { it.address.lowercase() == normalized }) {
            // Update last used time instead
            updateFavoriteLastUsed(normalized)
            return false
        }

        // Check limit
        if (current.size >= MAX_FAVORITE_ADDRESSES) {
            return false
        }

        val newFavorite = FavoriteLnAddress(
            address = normalized,
            label = label?.trim()?.takeIf { it.isNotEmpty() },
            lastUsed = System.currentTimeMillis()
        )
        saveFavoriteAddresses(current + newFavorite)
        return true
    }

    /**
     * Remove a lightning address from favorites.
     */
    fun removeFavoriteLnAddress(address: String) {
        val normalized = address.lowercase().trim()
        val updated = _favoriteLnAddresses.value.filter { it.address.lowercase() != normalized }
        saveFavoriteAddresses(updated)
    }

    /**
     * Update the label for a favorite address.
     */
    fun updateFavoriteLnAddressLabel(address: String, newLabel: String?) {
        val normalized = address.lowercase().trim()
        val updated = _favoriteLnAddresses.value.map { fav ->
            if (fav.address.lowercase() == normalized) {
                fav.copy(label = newLabel?.trim()?.takeIf { it.isNotEmpty() })
            } else {
                fav
            }
        }
        saveFavoriteAddresses(updated)
    }

    /**
     * Update the last used time for a favorite (called when used for withdrawal).
     */
    fun updateFavoriteLastUsed(address: String) {
        val normalized = address.lowercase().trim()
        val updated = _favoriteLnAddresses.value.map { fav ->
            if (fav.address.lowercase() == normalized) {
                fav.copy(lastUsed = System.currentTimeMillis())
            } else {
                fav
            }
        }
        saveFavoriteAddresses(updated)
    }

    /**
     * Check if an address is in favorites.
     */
    fun isFavoriteLnAddress(address: String): Boolean {
        val normalized = address.lowercase().trim()
        return _favoriteLnAddresses.value.any { it.address.lowercase() == normalized }
    }

    // ===================
    // SECURITY WARNING DISMISSAL
    // ===================

    /**
     * Check if the user has been warned about encryption fallback.
     * Direct prefs read (not StateFlow) to avoid sync issues on startup.
     */
    fun getEncryptionFallbackWarned(): Boolean =
        prefs.getBoolean(KEY_ENCRYPTION_FALLBACK_WARNED, false)

    /**
     * Mark that the user has been warned about encryption fallback.
     */
    fun setEncryptionFallbackWarned(warned: Boolean) {
        prefs.edit().putBoolean(KEY_ENCRYPTION_FALLBACK_WARNED, warned).apply()
    }
}
