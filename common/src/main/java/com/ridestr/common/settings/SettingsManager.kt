package com.ridestr.common.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

        // Default manual location: Las Vegas (Fremont St)
        private const val DEFAULT_MANUAL_LAT = 36.1699
        private const val DEFAULT_MANUAL_LON = -115.1398
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
}
