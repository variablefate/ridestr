package com.ridestr.common.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages app settings using SharedPreferences with StateFlow for reactive UI updates.
 */
class SettingsManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "ridestr_settings"
        private const val KEY_AUTO_OPEN_NAVIGATION = "auto_open_navigation"
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
}
