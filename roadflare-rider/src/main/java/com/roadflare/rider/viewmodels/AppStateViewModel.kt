package com.roadflare.rider.viewmodels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.routing.NostrTileDiscoveryService
import com.ridestr.common.routing.TileDownloadService
import com.ridestr.common.routing.TileManager
import com.ridestr.common.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

/**
 * Root app state ViewModel for onboarding + tile setup gating.
 *
 * Exposes non-nullable [tilesSetupCompleted] from SettingsRepository (DataStore-backed,
 * ready after loading gate) and nullable [hasAnyTileLoaded] from TileManager disk I/O.
 */
@HiltViewModel
class AppStateViewModel @Inject constructor(
    application: Application,
    savedStateHandle: SavedStateHandle,
    private val settingsRepository: SettingsRepository
) : AndroidViewModel(application) {

    val tileManager = TileManager.getInstance(application)
    val tileDownloadService = TileDownloadService(application, tileManager)
    val nostrTileDiscoveryService = NostrTileDiscoveryService(
        application,
        NostrService.getInstance(application, settingsRepository.getEffectiveRelays()).relayManager
    )

    /** Non-nullable — SettingsRepository's loading gate ensures DataStore is ready. */
    val tilesSetupCompleted: StateFlow<Boolean> = settingsRepository.tilesSetupCompleted

    /** null = stateIn initial value (resolves on first collection frame) */
    val hasAnyTileLoaded: StateFlow<Boolean?> = tileManager.downloadedRegions
        .map<Set<String>, Boolean?> { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun markTilesSetupCompleted() = viewModelScope.launch {
        settingsRepository.setTilesSetupCompleted(true)
    }
}
