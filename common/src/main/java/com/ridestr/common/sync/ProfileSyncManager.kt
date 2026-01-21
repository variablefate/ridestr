package com.ridestr.common.sync

import android.content.Context
import android.util.Log
import com.ridestr.common.nostr.keys.KeyManager
import com.ridestr.common.nostr.relay.RelayConfig
import com.ridestr.common.nostr.relay.RelayManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Orchestrates all profile data syncing with Nostr relays.
 *
 * This is the central coordination point for:
 * - Wallet proofs (NIP-60)
 * - Ride history
 * - Vehicles (driver)
 * - Saved locations (rider)
 * - Future: Settings, preferences, etc.
 *
 * Responsibilities:
 * - Manages SINGLE KeyManager instance (shared by all components)
 * - Coordinates sync order (wallet before history, etc.)
 * - Handles first-login vs regular-launch flows
 * - Provides observable sync state for UI feedback
 *
 * Usage:
 * ```kotlin
 * val syncManager = ProfileSyncManager.getInstance(context, relayUrls)
 *
 * // Register syncables during app init
 * syncManager.registerSyncable(walletSyncAdapter)
 * syncManager.registerSyncable(historySyncAdapter)
 *
 * // On key import (existing user)
 * syncManager.onKeyImported()
 *
 * // On new key (new user)
 * syncManager.onKeyGenerated()
 *
 * // On app resume
 * syncManager.onAppResume()
 * ```
 */
class ProfileSyncManager private constructor(
    private val context: Context,
    relayUrls: List<String>
) {
    companion object {
        private const val TAG = "ProfileSyncManager"
        private const val CONNECTION_TIMEOUT_MS = 15_000L
        private const val CONNECTION_CHECK_INTERVAL_MS = 500L

        @Volatile
        private var INSTANCE: ProfileSyncManager? = null

        /**
         * Get or create the singleton ProfileSyncManager.
         *
         * @param context Application context
         * @param relayUrls Optional relay URLs (uses defaults if not provided)
         */
        fun getInstance(
            context: Context,
            relayUrls: List<String>? = null
        ): ProfileSyncManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProfileSyncManager(
                    context.applicationContext,
                    relayUrls ?: RelayConfig.DEFAULT_RELAYS
                ).also { INSTANCE = it }
            }
        }

        /**
         * Clear singleton (for testing or full logout).
         */
        fun clearInstance() {
            INSTANCE?.disconnect()
            INSTANCE = null
        }
    }

    /**
     * Shared KeyManager - THE single source of truth for Nostr keys.
     * All components should use this instead of creating their own.
     */
    val keyManager = KeyManager(context)

    /**
     * Relay manager for Nostr connections.
     */
    val relayManager = RelayManager(relayUrls)

    /**
     * Registered syncable data types, sorted by syncOrder.
     */
    private val syncables = mutableListOf<SyncableProfileData>()

    /**
     * Observable sync state for UI feedback.
     */
    private val _syncState = MutableStateFlow<ProfileSyncState>(ProfileSyncState.Idle)
    val syncState: StateFlow<ProfileSyncState> = _syncState.asStateFlow()

    /**
     * Register a syncable data type.
     * Call during app initialization for each data type.
     *
     * @param syncable The syncable adapter to register
     */
    fun registerSyncable(syncable: SyncableProfileData) {
        // Avoid duplicate registration
        if (syncables.any { it.kind == syncable.kind && it.dTag == syncable.dTag }) {
            Log.w(TAG, "Syncable already registered: ${syncable.displayName}")
            return
        }

        syncables.add(syncable)
        syncables.sortBy { it.syncOrder }
        Log.d(TAG, "Registered syncable: ${syncable.displayName} (order=${syncable.syncOrder}, kind=${syncable.kind})")
    }

    /**
     * Check if there's any Ridestr-specific data to restore from Nostr.
     * This checks for profile data (vehicles, locations, settings) and ride history,
     * but NOT wallet (which is handled separately by WalletSetupScreen).
     *
     * Use this to decide whether to show the sync screen during onboarding.
     *
     * @return true if there's Ridestr data to restore
     */
    suspend fun hasRidestrData(): Boolean = withContext(Dispatchers.IO) {
        Log.d(TAG, "Checking for existing Ridestr data...")

        // Connect if needed
        if (!relayManager.isConnected()) {
            relayManager.connectAll()
            if (!waitForConnection()) {
                Log.w(TAG, "Could not connect to check for data")
                return@withContext false
            }
        }

        val pubKeyHex = keyManager.getPubKeyHex() ?: return@withContext false

        // Check non-wallet syncables for existing backups
        for (syncable in syncables) {
            // Skip wallet - it's handled separately
            if (syncable.displayName == "Wallet") continue

            try {
                if (syncable.hasNostrBackup(pubKeyHex, relayManager)) {
                    Log.d(TAG, "Found existing Ridestr data: ${syncable.displayName}")
                    return@withContext true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking ${syncable.displayName}: ${e.message}")
            }
        }

        Log.d(TAG, "No existing Ridestr data found")
        false
    }

    /**
     * Called after user imports an existing key.
     * Connects to relays and syncs all data FROM Nostr.
     *
     * @param includeWallet Whether to sync wallet (default false during onboarding,
     *                      since WalletSetupScreen handles wallet separately)
     *
     * Flow:
     * 1. Connect to relays
     * 2. For each syncable (sorted by order):
     *    - Fetch from Nostr
     *    - Merge/replace local data
     * 3. Update sync state
     */
    suspend fun onKeyImported(includeWallet: Boolean = false) = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== KEY IMPORTED: Starting restore flow (includeWallet=$includeWallet) ===")
        Log.d(TAG, "Registered syncables: ${syncables.map { it.displayName }}")

        _syncState.value = ProfileSyncState.Connecting

        // Connect to relays
        relayManager.connectAll()
        if (!waitForConnection()) {
            _syncState.value = ProfileSyncState.Error(
                message = "Could not connect to relays",
                retryable = true
            )
            return@withContext
        }

        Log.d(TAG, "Connected to ${relayManager.connectedCount()} relays")

        // Filter syncables based on includeWallet flag
        val syncablesToProcess = if (includeWallet) {
            syncables
        } else {
            syncables.filter { it.displayName != "Wallet" }
        }

        var totalItemsRestored = 0

        // Track what was restored for UI feedback
        var walletBalance: Long? = null
        var vehicleCount = 0
        var savedLocationCount = 0
        var rideHistoryCount = 0
        var settingsRestored = false

        // Sync each data type in order
        for ((index, syncable) in syncablesToProcess.withIndex()) {
            val progress = (index.toFloat() / syncablesToProcess.size)
            _syncState.value = ProfileSyncState.Syncing(syncable.displayName, progress)

            val signer = keyManager.getSigner()
            if (signer == null) {
                Log.e(TAG, "No signer available for ${syncable.displayName} - skipping")
                continue
            }

            Log.d(TAG, "Syncing ${syncable.displayName}...")
            val result = syncable.fetchFromNostr(signer, relayManager)

            when (result) {
                is SyncResult.Success -> {
                    Log.d(TAG, "${syncable.displayName}: Synced ${result.itemCount} items")
                    totalItemsRestored += result.itemCount

                    // Extract metadata for UI display
                    when (val meta = result.metadata) {
                        is SyncMetadata.Wallet -> walletBalance = meta.balanceSats
                        is SyncMetadata.Vehicles -> vehicleCount = meta.count
                        is SyncMetadata.SavedLocations -> savedLocationCount = meta.count
                        is SyncMetadata.RideHistory -> rideHistoryCount = meta.count
                        is SyncMetadata.Profile -> {
                            vehicleCount = meta.vehicleCount
                            savedLocationCount = meta.savedLocationCount
                            settingsRestored = meta.settingsRestored
                        }
                        null -> {
                            // No metadata, try to infer from displayName (backward compat)
                        }
                    }
                }
                is SyncResult.NoData -> {
                    Log.d(TAG, "${syncable.displayName}: ${result.reason}")
                }
                is SyncResult.Error -> {
                    Log.e(TAG, "${syncable.displayName}: ${result.message}", result.exception)
                    // Continue with other syncables, don't fail entirely
                }
            }
        }

        val restoredData = RestoredProfileData(
            walletBalance = walletBalance,
            vehicleCount = vehicleCount,
            savedLocationCount = savedLocationCount,
            rideHistoryCount = rideHistoryCount,
            settingsRestored = settingsRestored
        )

        _syncState.value = ProfileSyncState.Complete(totalItemsRestored, restoredData)
        Log.d(TAG, "=== KEY IMPORTED: Restore complete ($totalItemsRestored items) ===")
        Log.d(TAG, "Restored: wallet=${walletBalance}sats, vehicles=$vehicleCount, locations=$savedLocationCount, rides=$rideHistoryCount")

        // DON'T reset to idle - let the UI handle navigation when user taps Continue
        // The UI screen will call resetSyncState() when done
    }

    /**
     * Check for Ridestr data and sync if found.
     * Used during onboarding to only sync if there's actually data to restore.
     *
     * Flow:
     * 1. Set state to Checking
     * 2. Check if there's any Ridestr-specific data (vehicles, locations, history)
     * 3. If found, proceed with sync (state -> Connecting -> Syncing -> Complete)
     * 4. If not found, set state to NoDataFound
     *
     * Note: Does NOT sync wallet - that's handled separately by WalletSetupScreen.
     */
    suspend fun checkAndSyncRidestrData() = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== CHECKING FOR RIDESTR DATA ===")
        _syncState.value = ProfileSyncState.Checking

        // Connect to relays first
        relayManager.connectAll()
        if (!waitForConnection()) {
            _syncState.value = ProfileSyncState.Error(
                message = "Could not connect to relays",
                retryable = true
            )
            return@withContext
        }

        // Check if there's any Ridestr data to restore
        val hasData = hasRidestrDataInternal()

        if (hasData) {
            Log.d(TAG, "Found Ridestr data - proceeding with sync")
            // Proceed with sync (wallet excluded)
            onKeyImportedInternal(includeWallet = false)
        } else {
            Log.d(TAG, "No Ridestr data found")
            _syncState.value = ProfileSyncState.NoDataFound
        }
    }

    /**
     * Internal check for Ridestr data (assumes already connected).
     */
    private suspend fun hasRidestrDataInternal(): Boolean {
        val pubKeyHex = keyManager.getPubKeyHex() ?: return false

        for (syncable in syncables) {
            // Skip wallet
            if (syncable.displayName == "Wallet") continue

            try {
                if (syncable.hasNostrBackup(pubKeyHex, relayManager)) {
                    Log.d(TAG, "Found existing Ridestr data: ${syncable.displayName}")
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error checking ${syncable.displayName}: ${e.message}")
            }
        }
        return false
    }

    /**
     * Internal sync method (assumes already connected).
     */
    private suspend fun onKeyImportedInternal(includeWallet: Boolean) {
        // Filter syncables based on includeWallet flag
        val syncablesToProcess = if (includeWallet) {
            syncables
        } else {
            syncables.filter { it.displayName != "Wallet" }
        }

        var totalItemsRestored = 0
        var walletBalance: Long? = null
        var vehicleCount = 0
        var savedLocationCount = 0
        var rideHistoryCount = 0
        var settingsRestored = false

        for ((index, syncable) in syncablesToProcess.withIndex()) {
            val progress = (index.toFloat() / syncablesToProcess.size)
            _syncState.value = ProfileSyncState.Syncing(syncable.displayName, progress)

            val signer = keyManager.getSigner() ?: continue

            Log.d(TAG, "Syncing ${syncable.displayName}...")
            val result = syncable.fetchFromNostr(signer, relayManager)

            when (result) {
                is SyncResult.Success -> {
                    Log.d(TAG, "${syncable.displayName}: Synced ${result.itemCount} items")
                    totalItemsRestored += result.itemCount

                    when (val meta = result.metadata) {
                        is SyncMetadata.Wallet -> walletBalance = meta.balanceSats
                        is SyncMetadata.Vehicles -> vehicleCount = meta.count
                        is SyncMetadata.SavedLocations -> savedLocationCount = meta.count
                        is SyncMetadata.RideHistory -> rideHistoryCount = meta.count
                        is SyncMetadata.Profile -> {
                            vehicleCount = meta.vehicleCount
                            savedLocationCount = meta.savedLocationCount
                            settingsRestored = meta.settingsRestored
                        }
                        null -> {}
                    }
                }
                is SyncResult.NoData -> Log.d(TAG, "${syncable.displayName}: ${result.reason}")
                is SyncResult.Error -> Log.e(TAG, "${syncable.displayName}: ${result.message}", result.exception)
            }
        }

        val restoredData = RestoredProfileData(
            walletBalance = walletBalance,
            vehicleCount = vehicleCount,
            savedLocationCount = savedLocationCount,
            rideHistoryCount = rideHistoryCount,
            settingsRestored = settingsRestored
        )

        _syncState.value = ProfileSyncState.Complete(totalItemsRestored, restoredData)
        Log.d(TAG, "=== SYNC COMPLETE ($totalItemsRestored items) ===")
    }

    /**
     * Called after user generates a new key.
     * New keys start fresh - nothing to sync from Nostr.
     */
    suspend fun onKeyGenerated() = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== NEW KEY: No restore needed ===")
        // New keys have no data on Nostr yet
        // First backup happens when user adds data
        _syncState.value = ProfileSyncState.Idle
    }

    /**
     * Called when app returns to foreground.
     * Ensures relay connection is active.
     */
    suspend fun onAppResume() = withContext(Dispatchers.IO) {
        if (!keyManager.hasKey()) {
            Log.d(TAG, "onAppResume: No key loaded, skipping")
            return@withContext
        }

        relayManager.ensureConnected()
    }

    /**
     * Backup all local data to Nostr.
     * Called when user explicitly requests backup.
     */
    suspend fun backupAll() = withContext(Dispatchers.IO) {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "backupAll: No signer available")
            _syncState.value = ProfileSyncState.Error("Not logged in", retryable = false)
            return@withContext
        }

        if (!relayManager.isConnected()) {
            _syncState.value = ProfileSyncState.Connecting
            relayManager.connectAll()
            if (!waitForConnection()) {
                _syncState.value = ProfileSyncState.Error("Could not connect to relays", retryable = true)
                return@withContext
            }
        }

        for (syncable in syncables) {
            if (syncable.hasLocalData()) {
                _syncState.value = ProfileSyncState.Backing(syncable.displayName)
                Log.d(TAG, "Backing up ${syncable.displayName}...")

                val eventId = syncable.publishToNostr(signer, relayManager)
                if (eventId != null) {
                    Log.d(TAG, "${syncable.displayName}: Backed up as $eventId")
                } else {
                    Log.e(TAG, "${syncable.displayName}: Backup failed")
                }
            }
        }

        _syncState.value = ProfileSyncState.Idle
    }

    /**
     * Backup profile data (vehicles, saved locations, settings) to Nostr.
     * Called automatically when these data types change.
     *
     * This is a lightweight backup that only syncs the Profile adapter,
     * not all syncables like backupAll() does.
     */
    suspend fun backupProfileData() = withContext(Dispatchers.IO) {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.d(TAG, "backupProfileData: No signer available - skipping")
            return@withContext
        }

        // Find the Profile adapter
        val profileAdapter = syncables.find { it.displayName == "Profile" }
        if (profileAdapter == null) {
            Log.w(TAG, "backupProfileData: Profile adapter not registered")
            return@withContext
        }

        if (!profileAdapter.hasLocalData()) {
            Log.d(TAG, "backupProfileData: No local data to backup")
            return@withContext
        }

        // Connect if needed (don't show UI state for background backup)
        if (!relayManager.isConnected()) {
            relayManager.connectAll()
            if (!waitForConnection()) {
                Log.w(TAG, "backupProfileData: Could not connect to relays")
                return@withContext
            }
        }

        Log.d(TAG, "backupProfileData: Backing up profile...")
        val eventId = profileAdapter.publishToNostr(signer, relayManager)
        if (eventId != null) {
            Log.d(TAG, "backupProfileData: Success - event $eventId")
        } else {
            Log.e(TAG, "backupProfileData: Failed to backup")
        }
    }

    /**
     * Clear all local data across all syncables.
     * Called during logout.
     */
    fun clearAllData() {
        Log.d(TAG, "Clearing all local data...")
        for (syncable in syncables) {
            syncable.clearLocalData()
            Log.d(TAG, "Cleared: ${syncable.displayName}")
        }
    }

    /**
     * Reset sync state to Idle.
     * Called by UI after user acknowledges sync completion.
     */
    fun resetSyncState() {
        _syncState.value = ProfileSyncState.Idle
    }

    /**
     * Check if any syncable has an existing backup on Nostr.
     * Used during onboarding to detect returning user.
     */
    suspend fun hasAnyNostrBackup(): Boolean = withContext(Dispatchers.IO) {
        val pubKeyHex = keyManager.getPubKeyHex() ?: return@withContext false

        if (!relayManager.isConnected()) {
            relayManager.connectAll()
            if (!waitForConnection()) return@withContext false
        }

        for (syncable in syncables) {
            if (syncable.hasNostrBackup(pubKeyHex, relayManager)) {
                Log.d(TAG, "Found existing backup: ${syncable.displayName}")
                return@withContext true
            }
        }

        false
    }

    /**
     * Connect to relays.
     */
    fun connect() {
        relayManager.connectAll()
    }

    /**
     * Disconnect from relays.
     */
    fun disconnect() {
        relayManager.disconnectAll()
    }

    /**
     * Check if connected to at least one relay.
     */
    fun isConnected(): Boolean = relayManager.isConnected()

    /**
     * Get number of connected relays.
     */
    fun connectedCount(): Int = relayManager.connectedCount()

    /**
     * Wait for at least one relay to connect.
     * @return true if connected, false if timeout
     */
    private suspend fun waitForConnection(): Boolean {
        var waited = 0L
        while (!relayManager.isConnected() && waited < CONNECTION_TIMEOUT_MS) {
            delay(CONNECTION_CHECK_INTERVAL_MS)
            waited += CONNECTION_CHECK_INTERVAL_MS
        }

        val connected = relayManager.isConnected()
        if (!connected) {
            Log.w(TAG, "Connection timeout after ${CONNECTION_TIMEOUT_MS}ms")
        }
        return connected
    }
}
