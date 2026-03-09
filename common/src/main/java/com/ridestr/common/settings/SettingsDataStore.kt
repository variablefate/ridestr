package com.ridestr.common.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Canonical DataStore delegate for settings persistence.
 * Defined ONCE in common — both app Hilt modules reference this.
 * Includes SharedPreferences migration from legacy SettingsManager.
 */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings_datastore",
    produceMigrations = { context ->
        listOf(SharedPreferencesMigration(context, "ridestr_settings"))
    }
)
