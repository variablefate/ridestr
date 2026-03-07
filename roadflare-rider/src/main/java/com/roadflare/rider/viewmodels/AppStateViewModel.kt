package com.roadflare.rider.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.routing.NostrTileDiscoveryService
import com.ridestr.common.routing.TileDownloadService
import com.ridestr.common.routing.TileManager
import com.ridestr.common.settings.SettingsManager
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import androidx.lifecycle.viewModelScope

/**
 * Root app state ViewModel for onboarding + tile setup gating.
 *
 * Exposes nullable loading states so the UI can distinguish
 * "still loading" (null) from "loaded with value" (true/false),
 * preventing flash of wrong screen during initialization.
 */
class AppStateViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsManager = SettingsManager.getInstance(application)
    val tileManager = TileManager.getInstance(application)
    val tileDownloadService = TileDownloadService(application, tileManager)
    val nostrTileDiscoveryService = NostrTileDiscoveryService(
        application,
        NostrService.getInstance(application).relayManager
    )

    /** null = stateIn initial value (resolves on first collection frame) */
    val tilesSetupCompleted: StateFlow<Boolean?> = settingsManager.tilesSetupCompleted
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** null = stateIn initial value (resolves on first collection frame) */
    val hasAnyTileLoaded: StateFlow<Boolean?> = tileManager.downloadedRegions
        .map<Set<String>, Boolean?> { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun markTilesSetupCompleted() {
        settingsManager.setTilesSetupCompleted(true)
    }
}
