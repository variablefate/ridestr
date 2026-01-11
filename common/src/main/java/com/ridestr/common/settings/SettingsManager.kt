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
 * Manages app settings using SharedPreferences with StateFlow for reactive UI updates.
 */
class SettingsManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "ridestr_settings"
        private const val KEY_AUTO_OPEN_NAVIGATION = "auto_open_navigation"
        private const val KEY_DISPLAY_CURRENCY = "display_currency"
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

    // Currency display setting (default: SATS)
    private val _displayCurrency = MutableStateFlow(
        try {
            DisplayCurrency.valueOf(
                prefs.getString(KEY_DISPLAY_CURRENCY, DisplayCurrency.SATS.name)
                    ?: DisplayCurrency.SATS.name
            )
        } catch (e: IllegalArgumentException) {
            DisplayCurrency.SATS
        }
    )
    val displayCurrency: StateFlow<DisplayCurrency> = _displayCurrency.asStateFlow()

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
}
