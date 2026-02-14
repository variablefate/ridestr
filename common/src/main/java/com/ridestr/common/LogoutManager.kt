package com.ridestr.common

import android.content.Context
import com.ridestr.common.data.DriverRoadflareRepository
import com.ridestr.common.data.FollowedDriversRepository
import com.ridestr.common.data.RideHistoryRepository
import com.ridestr.common.data.SavedLocationRepository
import com.ridestr.common.data.VehicleRepository
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.payment.WalletKeyManager
import com.ridestr.common.payment.WalletService
import com.ridestr.common.routing.NostrTileDiscoveryService
import com.ridestr.common.settings.SettingsManager
import com.ridestr.common.sync.ProfileSyncManager

object LogoutManager {

    fun performFullCleanup(
        context: Context,
        nostrService: NostrService,
        settingsManager: SettingsManager,
        walletService: WalletService,
        walletKeyManager: WalletKeyManager,
        tileDiscoveryService: NostrTileDiscoveryService
    ) {
        // 1. Stop relay connections first
        nostrService.disconnect()

        // 2. Clear identity key
        nostrService.keyManager.logout()

        // 3. Clear all settings + StateFlows
        settingsManager.clearAllData()

        // 4. Clear wallet storage + delete DB
        walletService.resetWallet()

        // 5. Clear wallet key
        walletKeyManager.clearWalletKey()

        // 6-10. Clear all repositories (no-op for repos the current app doesn't use)
        RideHistoryRepository.getInstance(context).clearAllHistory()
        SavedLocationRepository.getInstance(context).clearAll()
        VehicleRepository.getInstance(context).clearAll()
        FollowedDriversRepository.getInstance(context).clearAll()
        DriverRoadflareRepository.getInstance(context).clearAll()

        // 11-12. Clear persisted ride state for both apps
        context.getSharedPreferences("ridestr_ride_state", Context.MODE_PRIVATE)
            .edit().clear().apply()
        context.getSharedPreferences("drivestr_ride_state", Context.MODE_PRIVATE)
            .edit().clear().apply()

        // 13. Clear tile discovery cache
        tileDiscoveryService.clearCache()

        // 14. Clear remote config cache
        context.getSharedPreferences("remote_config", Context.MODE_PRIVATE)
            .edit().clear().apply()

        // 15. Clear profile picture metadata
        context.getSharedPreferences("profile_picture_prefs", Context.MODE_PRIVATE)
            .edit().clear().apply()

        // 16. Reset ProfileSyncManager singleton (last — disconnects relays)
        ProfileSyncManager.clearInstance()
    }
}
