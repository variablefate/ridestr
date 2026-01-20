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
     * Called after user imports an existing key.
     * Connects to relays and syncs all data FROM Nostr.
     *
     * Flow:
     * 1. Connect to relays
     * 2. For each syncable (sorted by order):
     *    - Fetch from Nostr
     *    - Merge/replace local data
     * 3. Update sync state
     */
    suspend fun onKeyImported() = withContext(Dispatchers.IO) {
        Log.d(TAG, "=== KEY IMPORTED: Starting restore flow ===")
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

        var totalItemsRestored = 0

        // Sync each data type in order
        for ((index, syncable) in syncables.withIndex()) {
            val progress = (index.toFloat() / syncables.size)
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

        _syncState.value = ProfileSyncState.Complete(totalItemsRestored)
        Log.d(TAG, "=== KEY IMPORTED: Restore complete ($totalItemsRestored items) ===")

        // Reset to idle after a short delay (for UI to show completion)
        delay(1000)
        _syncState.value = ProfileSyncState.Idle
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
