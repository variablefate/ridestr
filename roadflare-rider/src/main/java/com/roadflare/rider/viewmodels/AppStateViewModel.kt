package com.roadflare.rider.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roadflare.common.routing.NostrTileDiscoveryService
import com.roadflare.common.routing.TileDownloadService
import com.roadflare.common.routing.TileManager
import com.roadflare.common.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Root app state ViewModel for onboarding + tile setup gating.
 *
 * Exposes nullable loading states so the UI can distinguish
 * "still loading" (null) from "loaded with value" (true/false),
 * preventing flash of wrong screen during initialization.
 */
@HiltViewModel
class AppStateViewModel @Inject constructor(
    val settingsRepository: SettingsRepository,
    val tileManager: TileManager,
    val tileDownloadService: TileDownloadService,
    val nostrTileDiscoveryService: NostrTileDiscoveryService
) : ViewModel() {

    /** null = still loading from DataStore */
    val tilesSetupCompleted: StateFlow<Boolean?> = settingsRepository.tilesSetupCompleted
        .map<Boolean, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** null = stateIn initial value (resolves on first collection frame) */
    val hasAnyTileLoaded: StateFlow<Boolean?> = tileManager.downloadedRegions
        .map<Set<String>, Boolean?> { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun markTilesSetupCompleted() {
        viewModelScope.launch { settingsRepository.setTilesSetupCompleted(true) }
    }
}
