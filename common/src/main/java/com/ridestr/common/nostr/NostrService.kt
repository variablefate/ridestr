package com.ridestr.common.nostr

import android.content.Context
import android.util.Log
import com.ridestr.common.data.SavedLocation
import com.ridestr.common.data.Vehicle
import com.ridestr.common.nostr.events.*
import com.ridestr.common.settings.SettingsManager
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.ridestr.common.nostr.keys.KeyManager
import com.ridestr.common.nostr.relay.RelayConfig
import com.ridestr.common.nostr.relay.RelayConnectionState
import com.ridestr.common.nostr.relay.RelayManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
// GlobalScope removed - all decrypt coroutines now use caller-provided scope

/**
 * High-level facade for all Nostr operations in the rideshare app.
 *
 * This service provides a simplified API for:
 * - Key management (via KeyManager)
 * - Relay connections (via RelayManager)
 * - Publishing rideshare events
 * - Subscribing to rideshare events
 *
 * @param context Android context for key management
 * @param relays Custom relay list (defaults to RelayConfig.DEFAULT_RELAYS)
 */
class NostrService(
    context: Context,
    relays: List<String> = RelayConfig.DEFAULT_RELAYS
) {

    companion object {
        private const val TAG = "NostrService"
    }

    val keyManager = KeyManager(context)
    val relayManager = RelayManager(relays)

    // Domain services
    private val cryptoHelper = NostrCryptoHelper(keyManager)
    private val profileBackupService = ProfileBackupService(relayManager, keyManager)
    private val roadflareDomainService = RoadflareDomainService(relayManager, keyManager)
    private val rideshareDomainService = RideshareDomainService(relayManager, keyManager)

    /**
     * Connection states for all relays.
     */
    val connectionStates: StateFlow<Map<String, RelayConnectionState>> = relayManager.connectionStates

    /**
     * Cached display name of the logged-in user.
     * Updated when profile is fetched.
     *
     * NOTE: Delegates to profileBackupService but kept on facade for backward compatibility
     * with existing callers (MainActivity, etc.) that use nostrService.userDisplayName.collectAsState()
     */
    val userDisplayName: StateFlow<String>
        get() = profileBackupService.userDisplayName

    /**
     * Set the user's display name (called when profile is loaded).
     */
    fun setUserDisplayName(name: String) = profileBackupService.setUserDisplayName(name)

    /**
     * Fetch and cache the current user's display name from their profile.
     */
    fun fetchAndCacheUserDisplayName() = profileBackupService.fetchAndCacheUserDisplayName()

    /**
     * Connect to all configured relays.
     */
    fun connect() {
        Log.d(TAG, "Connecting to relays")
        relayManager.connectAll()
    }

    /**
     * Disconnect from all relays.
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting from relays")
        relayManager.disconnectAll()
    }

    /**
     * Ensure all relays are connected. Call this when app returns to foreground.
     * Reconnects any dropped connections, cleans up stale subscriptions (>30min),
     * and resends active subscriptions.
     */
    fun ensureConnected() {
        Log.d(TAG, "Ensuring relay connections")
        relayManager.ensureConnected()
    }

    /**
     * Clear all subscriptions. Use for debugging or to reset state.
     */
    fun clearAllSubscriptions() {
        Log.d(TAG, "Clearing all subscriptions")
        relayManager.clearAllSubscriptions()
    }

    /**
     * Check if the user is logged in (has a key).
     */
    fun isLoggedIn(): Boolean = keyManager.hasKey()

    /**
     * Get the current user's public key in hex format.
     * @return The public key hex string, or null if not logged in
     */
    fun getPubKeyHex(): String? = keyManager.getPubKeyHex()

    /**
     * Check if connected to at least one relay.
     */
    fun isConnected(): Boolean = relayManager.isConnected()

    /**
     * Get the NostrSigner for signing Blossom upload/delete events.
     * @return The signer, or null if not logged in
     */
    fun getSigner() = keyManager.getSigner()

    // ==================== Driver Operations ====================

    /**
     * Broadcast driver availability.
     *
     * @param location Driver location (null for ROADFLARE_ONLY mode - invisible to geographic search)
     * @param status Driver status (STATUS_AVAILABLE or STATUS_OFFLINE)
     * @param vehicle Optional vehicle info
     * @param mintUrl Driver's Cashu mint URL
     * @param paymentMethods Supported payment methods
     * @return The event ID if successful, null on failure
     */
    suspend fun broadcastAvailability(
        location: Location? = null,
        status: String = DriverAvailabilityEvent.STATUS_AVAILABLE,
        vehicle: Vehicle? = null,
        mintUrl: String? = null,
        paymentMethods: List<String> = listOf("cashu")
    ): String? = rideshareDomainService.broadcastAvailability(location, status, vehicle, mintUrl, paymentMethods)

    /**
     * Request deletion of events (NIP-09).
     * Used to clean up old availability events to prevent relay spam.
     *
     * @param eventIds List of event IDs to request deletion of
     * @param reason Optional reason for deletion
     * @param kinds Optional list of kinds being deleted (for NIP-09 k-tags)
     * @return The deletion event ID if successful, null on failure
     */
    suspend fun deleteEvents(
        eventIds: List<String>,
        reason: String = "",
        kinds: List<Int>? = null
    ): String? {
        if (eventIds.isEmpty()) return null

        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot delete events: Not logged in")
            return null
        }

        return try {
            val event = DeletionEvent.create(signer, eventIds, reason, kinds)
            relayManager.publish(event)
            Log.d(TAG, "Requested deletion of ${eventIds.size} event(s): ${event.id}")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request event deletion", e)
            null
        }
    }

    /**
     * Request deletion of a single event (NIP-09).
     *
     * @param eventId The event ID to request deletion of
     * @param reason Optional reason for deletion
     * @param kind Optional kind of the event being deleted (for NIP-09 k-tag)
     * @return The deletion event ID if successful, null on failure
     */
    suspend fun deleteEvent(eventId: String, reason: String = "", kind: Int? = null): String? {
        return deleteEvents(listOf(eventId), reason, kind?.let { listOf(it) })
    }

    /**
     * Delete all rideshare events created by the current user.
     * Queries relays for events authored by the user with rideshare kinds,
     * then publishes NIP-09 deletion requests for each.
     * @return Count of events for which deletion was requested
     */
    suspend fun deleteAllRideshareEvents(): Int = withContext(Dispatchers.IO) {
        val pubkey = keyManager.getPubKeyHex()
        if (pubkey == null) {
            Log.e(TAG, "Cannot delete events: Not logged in")
            return@withContext 0
        }

        // All rideshare event kinds EXCEPT:
        // - RIDE_CANCELLATION (3179) - expires naturally, needed for state consistency
        val rideshareKinds = listOf(
            // Current kinds (RideshareEventKinds)
            RideshareEventKinds.DRIVER_AVAILABILITY,  // 30173
            RideshareEventKinds.RIDE_OFFER,           // 3173
            RideshareEventKinds.RIDE_ACCEPTANCE,      // 3174
            RideshareEventKinds.RIDE_CONFIRMATION,    // 3175
            RideshareEventKinds.DRIVER_RIDE_STATE,    // 30180 (consolidated)
            RideshareEventKinds.RIDER_RIDE_STATE,     // 30181 (consolidated)
            RideshareEventKinds.RIDESHARE_CHAT,       // 3178
            // EXCLUDED: RIDE_CANCELLATION (3179) - expires in 24h, both apps need it for state
            // Legacy kinds (may have old events on relays)
            20173   // Old ephemeral driver status (changed to 3180)
            // Note: NOT including 1059/1060 (Gift Wrap/Seal) - those are standard
            // NIP-17 kinds used by other apps, would delete user's other DMs!
        )

        // Collect event IDs from subscription
        val eventIds = mutableListOf<String>()
        val eoseReceived = CompletableDeferred<String>()
        val subscriptionId = relayManager.subscribe(
            kinds = rideshareKinds,
            authors = listOf(pubkey),
            limit = 1000,
            onEose = { relayUrl -> eoseReceived.complete(relayUrl) }
        ) { event, _ ->
            // Skip offline driver availability events - they're signals, not active data
            if (event.kind == RideshareEventKinds.DRIVER_AVAILABILITY) {
                val parsed = DriverAvailabilityEvent.parse(event)
                if (parsed?.isOffline == true) {
                    return@subscribe  // Skip offline events
                }
            }
            synchronized(eventIds) {
                eventIds.add(event.id)
            }
        }

        // Wait for EOSE or timeout
        if (withTimeoutOrNull(2000L) { eoseReceived.await() } != null) delay(200)

        // Close subscription
        relayManager.closeSubscription(subscriptionId)

        Log.d(TAG, "Found ${eventIds.size} rideshare events to delete")

        if (eventIds.isEmpty()) {
            return@withContext 0
        }

        // Delete in batches of 50 to avoid huge events
        val batchSize = 50
        var deletedCount = 0
        eventIds.chunked(batchSize).forEach { batch ->
            val result = deleteEvents(batch, "User requested account data deletion")
            if (result != null) {
                deletedCount += batch.size
            }
        }

        Log.d(TAG, "Requested deletion of $deletedCount events")
        deletedCount
    }

    /**
     * Delete all events of a specific kind created by the current user.
     * Useful for cleaning up stale events on app startup (e.g., old availability events).
     * @param kind The event kind to delete
     * @param reason Optional reason for deletion
     * @return Count of events for which deletion was requested
     */
    suspend fun deleteMyEventsByKind(kind: Int, reason: String = "cleanup"): Int = withContext(Dispatchers.IO) {
        val pubkey = keyManager.getPubKeyHex()
        if (pubkey == null) {
            Log.e(TAG, "Cannot delete events: Not logged in")
            return@withContext 0
        }

        // Collect event IDs from subscription
        val eventIds = mutableListOf<String>()
        val eoseReceived2 = CompletableDeferred<String>()
        val subscriptionId = relayManager.subscribe(
            kinds = listOf(kind),
            authors = listOf(pubkey),
            limit = 100,
            onEose = { relayUrl -> eoseReceived2.complete(relayUrl) }
        ) { event, _ ->
            synchronized(eventIds) {
                eventIds.add(event.id)
            }
        }

        // Wait for EOSE or timeout
        if (withTimeoutOrNull(2000L) { eoseReceived2.await() } != null) delay(200)

        // Close subscription
        relayManager.closeSubscription(subscriptionId)

        Log.d(TAG, "Found ${eventIds.size} events of kind $kind to delete")

        if (eventIds.isEmpty()) {
            return@withContext 0
        }

        // Delete in batches of 50
        val batchSize = 50
        var deletedCount = 0
        eventIds.chunked(batchSize).forEach { batch ->
            val result = deleteEvents(batch, reason, listOf(kind))  // Pass kind for k-tag
            if (result != null) {
                deletedCount += batch.size
            }
        }

        Log.d(TAG, "Requested deletion of $deletedCount events of kind $kind")
        deletedCount
    }

    /**
     * Count all rideshare events created by the current user.
     * Used to verify if deletion worked or see how many events exist.
     * @return Count of rideshare events found on relays
     */
    suspend fun countRideshareEvents(): Int = withContext(Dispatchers.IO) {
        val pubkey = keyManager.getPubKeyHex()
        if (pubkey == null) {
            Log.e(TAG, "Cannot count events: Not logged in")
            return@withContext 0
        }

        // All rideshare event kinds (current + legacy for thorough counting)
        val rideshareKinds = listOf(
            // Current kinds (RideshareEventKinds)
            RideshareEventKinds.DRIVER_AVAILABILITY,  // 30173
            RideshareEventKinds.RIDE_OFFER,           // 3173
            RideshareEventKinds.RIDE_ACCEPTANCE,      // 3174
            RideshareEventKinds.RIDE_CONFIRMATION,    // 3175
            RideshareEventKinds.DRIVER_RIDE_STATE,    // 30180 (consolidated)
            RideshareEventKinds.RIDER_RIDE_STATE,     // 30181 (consolidated)
            RideshareEventKinds.RIDESHARE_CHAT,       // 3178
            RideshareEventKinds.RIDE_CANCELLATION,    // 3179
            // Legacy rideshare-specific kinds (may have old events on relays)
            20173   // Old ephemeral driver status (changed to 3180)
            // Note: NOT including 1059/1060 (Gift Wrap/Seal) - those are standard
            // NIP-17 kinds used by other apps, would delete user's other DMs!
        )

        // Collect event IDs from subscription
        val eventIds = mutableListOf<String>()
        val eoseReceived3 = CompletableDeferred<String>()
        val subscriptionId = relayManager.subscribe(
            kinds = rideshareKinds,
            authors = listOf(pubkey),
            limit = 1000,
            onEose = { relayUrl -> eoseReceived3.complete(relayUrl) }
        ) { event, _ ->
            // Skip offline driver availability events - they're signals, not active data
            if (event.kind == RideshareEventKinds.DRIVER_AVAILABILITY) {
                val parsed = DriverAvailabilityEvent.parse(event)
                Log.d(TAG, "countRideshareEvents: Availability event, parsed=${parsed != null}, isOffline=${parsed?.isOffline}, status=${parsed?.status}")
                if (parsed?.isOffline == true) {
                    Log.d(TAG, "countRideshareEvents: Skipping offline availability event")
                    return@subscribe  // Skip offline events
                }
            }
            synchronized(eventIds) {
                eventIds.add(event.id)
                Log.d(TAG, "countRideshareEvents: Added event kind=${event.kind}")
            }
        }

        // Wait for EOSE or timeout
        if (withTimeoutOrNull(2000L) { eoseReceived3.await() } != null) delay(200)

        // Close subscription
        relayManager.closeSubscription(subscriptionId)

        Log.d(TAG, "Found ${eventIds.size} rideshare events")
        eventIds.size
    }

    /**
     * Count rideshare events by kind.
     * Returns a map of kind -> count for debugging which event types are persisting.
     */
    suspend fun countRideshareEventsByKind(): Map<Int, Int> = withContext(Dispatchers.IO) {
        val pubkey = keyManager.getPubKeyHex()
        if (pubkey == null) {
            Log.e(TAG, "Cannot count events: Not logged in")
            return@withContext emptyMap()
        }

        // Wait for relay connection
        if (!relayManager.awaitConnected(tag = "countRideshareEventsByKind")) {
            return@withContext emptyMap()
        }

        Log.d(TAG, "Counting events from ${relayManager.connectedCount()} relays for ${pubkey.take(16)}...")

        val rideshareKinds = listOf(
            RideshareEventKinds.DRIVER_AVAILABILITY,
            RideshareEventKinds.RIDE_OFFER,
            RideshareEventKinds.RIDE_ACCEPTANCE,
            RideshareEventKinds.RIDE_CONFIRMATION,
            RideshareEventKinds.DRIVER_RIDE_STATE,
            RideshareEventKinds.RIDER_RIDE_STATE,
            RideshareEventKinds.RIDESHARE_CHAT,
            RideshareEventKinds.RIDE_CANCELLATION,
            RideshareEventKinds.RIDE_HISTORY_BACKUP,
            20173  // Legacy ephemeral driver status
        )

        // Collect events with their kinds
        val kindCounts = mutableMapOf<Int, Int>()
        val eoseReceived4 = CompletableDeferred<String>()
        val subscriptionId = relayManager.subscribe(
            kinds = rideshareKinds,
            authors = listOf(pubkey),
            limit = 1000,
            onEose = { relayUrl -> eoseReceived4.complete(relayUrl) }
        ) { event, _ ->
            // Skip offline driver availability events - they're signals, not active data
            if (event.kind == RideshareEventKinds.DRIVER_AVAILABILITY) {
                val parsed = DriverAvailabilityEvent.parse(event)
                if (parsed?.isOffline == true) {
                    return@subscribe  // Skip offline events
                }
            }
            synchronized(kindCounts) {
                kindCounts[event.kind] = (kindCounts[event.kind] ?: 0) + 1
            }
        }

        // Wait for EOSE or timeout
        if (withTimeoutOrNull(5000L) { eoseReceived4.await() } != null) delay(200)
        relayManager.closeSubscription(subscriptionId)

        val total = kindCounts.values.sum()
        Log.d(TAG, "Found $total events by kind: $kindCounts")
        kindCounts.toMap()
    }

    /**
     * Background cleanup of ALL rideshare events on connected relays.
     * Runs asynchronously and does NOT block the caller - designed to be launched
     * from viewModelScope.launch {} without awaiting.
     *
     * Use this after targeted cleanup (cleanupRideEvents) to catch any stragglers
     * from crashed sessions or untracked events.
     *
     * @param reason Description of why cleanup is happening (for logs)
     * @return Count of events for which deletion was requested
     */
    suspend fun backgroundCleanupRideshareEvents(reason: String): Int = withContext(Dispatchers.IO) {
        val pubkey = keyManager.getPubKeyHex()
        if (pubkey == null) {
            Log.e(TAG, "Cannot do background cleanup: Not logged in")
            return@withContext 0
        }

        // All rideshare kinds EXCEPT:
        // - RIDE_CANCELLATION (3179) - expires naturally, needed for state consistency
        val kindsToClean = listOf(
            RideshareEventKinds.DRIVER_AVAILABILITY,     // 30173
            RideshareEventKinds.RIDE_OFFER,              // 3173
            RideshareEventKinds.RIDE_ACCEPTANCE,         // 3174
            RideshareEventKinds.RIDE_CONFIRMATION,       // 3175
            RideshareEventKinds.DRIVER_RIDE_STATE,       // 30180 (consolidated)
            RideshareEventKinds.RIDER_RIDE_STATE,        // 30181 (consolidated)
            RideshareEventKinds.RIDESHARE_CHAT,          // 3178
            // EXCLUDED: RIDE_CANCELLATION (3179) - expires in 24h, both apps need it for state
            20173  // Legacy ephemeral driver status
        )

        Log.d(TAG, "=== BACKGROUND CLEANUP START: $reason ===")

        // Collect event IDs from subscription (same approach as Account Safety)
        val foundEventIds = mutableListOf<String>()
        val eoseCleanup1 = CompletableDeferred<String>()
        val subscriptionId = relayManager.subscribe(
            kinds = kindsToClean,
            authors = listOf(pubkey),
            limit = 500,  // High limit to catch stragglers
            onEose = { relayUrl -> eoseCleanup1.complete(relayUrl) }
        ) { event, _ ->
            // Skip offline driver availability events - they're signals to riders
            if (event.kind == RideshareEventKinds.DRIVER_AVAILABILITY) {
                val parsed = DriverAvailabilityEvent.parse(event)
                if (parsed?.isOffline == true) {
                    return@subscribe  // Skip offline events
                }
            }
            synchronized(foundEventIds) {
                foundEventIds.add(event.id)
                Log.d(TAG, "BACKGROUND CLEANUP found event: ${event.id} kind=${event.kind}")
            }
        }

        // Wait for EOSE or timeout
        if (withTimeoutOrNull(2000L) { eoseCleanup1.await() } != null) delay(200)

        // Close subscription
        relayManager.closeSubscription(subscriptionId)

        if (foundEventIds.isEmpty()) {
            Log.d(TAG, "=== BACKGROUND CLEANUP: No stray events found ===")
            return@withContext 0
        }

        // Store original IDs for retry check (don't delete new events if user goes back online)
        val originalEventIds = foundEventIds.toSet()
        Log.d(TAG, "=== BACKGROUND CLEANUP: Found ${originalEventIds.size} events to delete ===")

        // Delete in batches of 50 (same as Account Safety)
        // Include k-tags for better relay processing
        var deletedCount = 0
        foundEventIds.chunked(50).forEach { batch ->
            val result = deleteEvents(batch, reason, kindsToClean)  // Pass k-tags
            if (result != null) {
                deletedCount += batch.size
            }
        }

        Log.d(TAG, "=== BACKGROUND CLEANUP: Requested deletion of $deletedCount events ===")

        // RETRY LOGIC: Wait and check if events persisted, re-delete if needed
        // This runs in background and won't block - safe if user goes back online
        delay(3000)  // Give relays time to process deletion

        // Re-query to find any stubborn events that weren't deleted
        val persistingEventIds = mutableListOf<String>()
        val eoseRetry = CompletableDeferred<String>()
        val retrySubId = relayManager.subscribe(
            kinds = kindsToClean,
            authors = listOf(pubkey),
            limit = 500,
            onEose = { relayUrl -> eoseRetry.complete(relayUrl) }
        ) { event, _ ->
            // Only track events from our ORIGINAL list (ignore new events if user went back online)
            if (event.id in originalEventIds) {
                synchronized(persistingEventIds) {
                    if (event.id !in persistingEventIds) {
                        persistingEventIds.add(event.id)
                    }
                }
            }
        }

        if (withTimeoutOrNull(2000L) { eoseRetry.await() } != null) delay(200)
        relayManager.closeSubscription(retrySubId)

        if (persistingEventIds.isNotEmpty()) {
            Log.d(TAG, "=== BACKGROUND CLEANUP RETRY: ${persistingEventIds.size} events persisted, retrying deletion ===")
            persistingEventIds.chunked(50).forEach { batch ->
                deleteEvents(batch, "$reason (retry)", kindsToClean)  // Pass k-tags on retry
            }
            Log.d(TAG, "=== BACKGROUND CLEANUP RETRY: Done ===")
        } else {
            Log.d(TAG, "=== BACKGROUND CLEANUP: All events deleted successfully ===")
        }

        deletedCount
    }

    /**
     * Accept a ride offer.
     * PIN is no longer included - the rider generates it and shares verbally.
     * @param offer The offer to accept
     * @param walletPubKey Driver's wallet pubkey for P2PK escrow (separate from Nostr key)
     * @return The event ID if successful, null on failure
     */
    suspend fun acceptRide(
        offer: RideOfferData,
        walletPubKey: String? = null,
        mintUrl: String? = null,
        paymentMethod: String? = null
    ): String? = rideshareDomainService.acceptRide(offer, walletPubKey, mintUrl, paymentMethod)

    // ==================== Driver Ride State (Kind 30180) ====================

    /**
     * Publish or update driver ride state.
     * This is a parameterized replaceable event - only the latest state per ride is kept.
     *
     * @param confirmationEventId The ride confirmation event ID (used as d-tag)
     * @param riderPubKey The rider's public key
     * @param currentStatus Current status (use DriverStatusType constants)
     * @param history List of all actions in chronological order
     * @param finalFare Final fare in satoshis (for completed rides)
     * @param invoice Lightning invoice (for completed rides)
     * @param lastTransitionId Event ID of last rider state event processed (for chain integrity)
     * @return The event ID if successful, null on failure
     */
    suspend fun publishDriverRideState(
        confirmationEventId: String,
        riderPubKey: String,
        currentStatus: String,
        history: List<DriverRideAction>,
        finalFare: Long? = null,
        invoice: String? = null,
        lastTransitionId: String? = null
    ): String? = rideshareDomainService.publishDriverRideState(confirmationEventId, riderPubKey, currentStatus, history, finalFare, invoice, lastTransitionId)

    /**
     * Subscribe to driver ride state updates for a confirmed ride.
     * @param confirmationEventId The confirmation event ID to watch
     * @param driverPubKey The driver's public key (to filter by author)
     * @param onState Called when state update is received
     * @return Subscription ID for closing later, or null if not logged in
     */
    fun subscribeToDriverRideState(
        confirmationEventId: String,
        driverPubKey: String,
        onState: (DriverRideStateData) -> Unit
    ): String? = rideshareDomainService.subscribeToDriverRideState(confirmationEventId, driverPubKey, onState)

    // ==================== Rider Ride State (Kind 30181) ====================

    /**
     * Publish or update rider ride state.
     * This is a parameterized replaceable event - only the latest state per ride is kept.
     *
     * @param confirmationEventId The ride confirmation event ID (used as d-tag)
     * @param driverPubKey The driver's public key
     * @param currentPhase Current phase (use RiderRideStateEvent.Phase constants)
     * @param history List of all actions in chronological order
     * @param lastTransitionId Event ID of last driver state event processed (for chain integrity)
     * @return The event ID if successful, null on failure
     */
    suspend fun publishRiderRideState(
        confirmationEventId: String,
        driverPubKey: String,
        currentPhase: String,
        history: List<RiderRideAction>,
        lastTransitionId: String? = null
    ): String? = rideshareDomainService.publishRiderRideState(confirmationEventId, driverPubKey, currentPhase, history, lastTransitionId)

    /**
     * Subscribe to rider ride state updates for a confirmed ride.
     * @param confirmationEventId The confirmation event ID to watch
     * @param riderPubKey The rider's public key (to filter by author)
     * @param onState Called when state update is received
     * @return Subscription ID for closing later, or null if not logged in
     */
    fun subscribeToRiderRideState(
        confirmationEventId: String,
        riderPubKey: String,
        onState: (RiderRideStateData) -> Unit
    ): String? = rideshareDomainService.subscribeToRiderRideState(confirmationEventId, riderPubKey, onState)

    /**
     * Encrypt a location for inclusion in rider ride state history.
     * @param location The location to encrypt
     * @param driverPubKey The driver's public key (recipient)
     * @return Encrypted location string, or null on failure
     */
    suspend fun encryptLocationForRiderState(
        location: Location,
        driverPubKey: String
    ): String? = cryptoHelper.encryptLocationForRiderState(location, driverPubKey)

    /**
     * Decrypt a location from rider ride state history.
     * @param encryptedLocation The encrypted location string
     * @param riderPubKey The rider's public key (sender)
     * @return Decrypted location, or null on failure
     */
    suspend fun decryptLocationFromRiderState(
        encryptedLocation: String,
        riderPubKey: String
    ): Location? = cryptoHelper.decryptLocationFromRiderState(encryptedLocation, riderPubKey)

    /**
     * Encrypt a PIN for inclusion in driver ride state history.
     * @param pin The PIN to encrypt
     * @param riderPubKey The rider's public key (recipient)
     * @return Encrypted PIN string, or null on failure
     */
    suspend fun encryptPinForDriverState(
        pin: String,
        riderPubKey: String
    ): String? = cryptoHelper.encryptPinForDriverState(pin, riderPubKey)

    /**
     * Encrypt arbitrary string data for a specific user (NIP-44).
     * Generic helper for encrypting any data to a recipient.
     *
     * @param data The data to encrypt
     * @param recipientPubKey The recipient's public key
     * @return Encrypted string, or null on failure
     */
    suspend fun encryptForUser(
        data: String,
        recipientPubKey: String
    ): String? = cryptoHelper.encryptForUser(data, recipientPubKey)

    /**
     * Decrypt arbitrary string data from a specific user (NIP-44).
     * Generic helper for decrypting any data from a sender.
     *
     * @param encryptedData The encrypted data
     * @param senderPubKey The sender's public key
     * @return Decrypted string, or null on failure
     */
    suspend fun decryptFromUser(
        encryptedData: String,
        senderPubKey: String
    ): String? = cryptoHelper.decryptFromUser(encryptedData, senderPubKey)

    /**
     * Decrypt a PIN from driver ride state history.
     * @param encryptedPin The encrypted PIN string
     * @param driverPubKey The driver's public key (sender)
     * @return Decrypted PIN, or null on failure
     */
    suspend fun decryptPinFromDriverState(
        encryptedPin: String,
        driverPubKey: String
    ): String? = cryptoHelper.decryptPinFromDriverState(encryptedPin, driverPubKey)

    // ==================== Rider Operations ====================

    /**
     * Send a ride offer to a driver.
     *
     * This unified method handles both regular offers (with driver availability event)
     * and RoadFlare offers (direct to driver pubkey without availability event).
     *
     * @param driverPubKey The driver's Nostr public key
     * @param driverAvailabilityEventId The driver's availability event ID (null for RoadFlare)
     * @param pickup Pickup location
     * @param destination Destination location
     * @param fareEstimate Estimated fare in sats
     * @param pickupRouteKm Pre-calculated driver→pickup distance in km (optional)
     * @param pickupRouteMin Pre-calculated driver→pickup duration in minutes (optional)
     * @param rideRouteKm Pre-calculated pickup→destination distance in km (optional)
     * @param rideRouteMin Pre-calculated pickup→destination duration in minutes (optional)
     * @param mintUrl Rider's Cashu mint URL (optional)
     * @param paymentMethod Payment method (default "cashu")
     * @param isRoadflare True for RoadFlare requests from favorite drivers
     *
     * NOTE: paymentHash is NOT included in offers - it's sent in confirmation (Kind 3175)
     * after driver accepts. This ensures HTLC is locked with the correct driver wallet key.
     *
     * @return The event ID if successful, null on failure
     */
    suspend fun sendRideOffer(
        driverPubKey: String,
        driverAvailabilityEventId: String? = null,
        pickup: Location,
        destination: Location,
        fareEstimate: Double,
        pickupRouteKm: Double? = null,
        pickupRouteMin: Double? = null,
        rideRouteKm: Double? = null,
        rideRouteMin: Double? = null,
        mintUrl: String? = null,
        paymentMethod: String? = "cashu",
        isRoadflare: Boolean = false
    ): String? = rideshareDomainService.sendRideOffer(driverPubKey, driverAvailabilityEventId, pickup, destination, fareEstimate, pickupRouteKm, pickupRouteMin, rideRouteKm, rideRouteMin, mintUrl, paymentMethod, isRoadflare)

    /**
     * Confirm a ride with precise pickup location (encrypted).
     * @param acceptance The driver's acceptance
     * @param precisePickup Precise pickup location (will be encrypted)
     * @param paymentHash HTLC payment hash for escrow verification (moved from offer for correct timing)
     * @param escrowToken Optional escrow token (for cross-mint bridge)
     * @return The event ID if successful, null on failure
     */
    suspend fun confirmRide(
        acceptance: RideAcceptanceData,
        precisePickup: Location,
        paymentHash: String? = null,
        escrowToken: String? = null
    ): String? = rideshareDomainService.confirmRide(acceptance, precisePickup, paymentHash, escrowToken)

    // ==================== Broadcast Ride Operations ====================

    /**
     * Broadcast a public ride request (visible to all drivers in the pickup area).
     * This is the new primary flow where riders broadcast and drivers choose to accept.
     *
     * @param pickup Pickup location
     * @param destination Destination location
     * @param fareEstimate Fare in sats
     * @param routeDistanceKm Route distance in kilometers
     * @param routeDurationMin Route duration in minutes
     * @return The event ID if successful, null on failure
     */
    suspend fun broadcastRideRequest(
        pickup: Location,
        destination: Location,
        fareEstimate: Double,
        routeDistanceKm: Double,
        routeDurationMin: Double,
        mintUrl: String? = null,
        paymentMethod: String = "cashu"
    ): String? = rideshareDomainService.broadcastRideRequest(pickup, destination, fareEstimate, routeDistanceKm, routeDurationMin, mintUrl, paymentMethod)

    /**
     * Subscribe to broadcast ride requests in an area (for drivers).
     * Filters by geohash to only see local requests.
     *
     * @param location Driver's current location for geohash filtering
     * @param expandSearch If true, include neighboring cells for wider coverage
     * @param onRequest Called when a ride request is received
     * @return Subscription ID for closing later
     */
    fun subscribeToBroadcastRideRequests(
        location: Location,
        expandSearch: Boolean = false,
        onRequest: (BroadcastRideOfferData) -> Unit
    ): String = rideshareDomainService.subscribeToBroadcastRideRequests(location, expandSearch, onRequest)

    /**
     * Subscribe to deletion events (NIP-09 Kind 5) for ride request event IDs.
     * Used to detect when a rider cancels their broadcast request.
     *
     * @param eventIds List of ride request event IDs to watch for deletion
     * @param onDeletion Called with the event ID that was deleted
     * @return Subscription ID for closing later
     */
    fun subscribeToRideRequestDeletions(
        eventIds: List<String>,
        onDeletion: (String) -> Unit
    ): String? = rideshareDomainService.subscribeToRideRequestDeletions(eventIds, onDeletion)

    /**
     * Accept a broadcast ride request.
     * @param request The broadcast ride request to accept
     * @param walletPubKey Driver's wallet pubkey for P2PK escrow (separate from Nostr key)
     * @return The event ID if successful, null on failure
     */
    suspend fun acceptBroadcastRide(
        request: BroadcastRideOfferData,
        walletPubKey: String? = null,
        mintUrl: String? = null,
        paymentMethod: String? = null
    ): String? = rideshareDomainService.acceptBroadcastRide(request, walletPubKey, mintUrl, paymentMethod)

    /**
     * Subscribe to acceptances for a broadcast ride request.
     * Used by riders to detect when a driver accepts, and by drivers to detect
     * when another driver has accepted (ride is taken).
     *
     * @param offerEventId The ride offer event ID to watch
     * @param onAcceptance Called when acceptance is received
     * @return Subscription ID for closing later
     */
    fun subscribeToAcceptancesForOffer(
        offerEventId: String,
        onAcceptance: (RideAcceptanceData) -> Unit
    ): String = rideshareDomainService.subscribeToAcceptancesForOffer(offerEventId, onAcceptance)

    // ==================== Subscriptions ====================

    /**
     * Subscribe to available drivers near the specified location.
     * Uses geohash filtering for efficient relay queries.
     *
     * Coverage:
     * - Default (expandSearch=false): ~24mi x 12mi (single cell), auto-expands near edges
     * - Expanded (expandSearch=true): ~72mi x 36mi (9 cells), guaranteed 20+ mile radius
     *
     * @param location The rider's current location for geohash filtering (optional)
     * @param expandSearch If true, always include neighboring cells for wider coverage
     * @param onDriver Called when a driver availability event is received
     * @return Subscription ID for closing later
     */
    fun subscribeToDrivers(
        location: Location? = null,
        expandSearch: Boolean = false,
        onEose: ((String) -> Unit)? = null,
        onDriver: (DriverAvailabilityData) -> Unit
    ): String = rideshareDomainService.subscribeToDrivers(
        location = location,
        expandSearch = expandSearch,
        onEose = onEose,
        onDriver = onDriver
    )

    /**
     * Subscribe to a specific driver's availability updates.
     * Used to monitor if the selected driver goes offline while waiting for acceptance.
     * @param driverPubKey The driver's public key to monitor
     * @param onAvailability Called when the driver's availability status changes
     * @return Subscription ID for closing later
     */
    fun subscribeToDriverAvailability(
        driverPubKey: String,
        onAvailability: (DriverAvailabilityData) -> Unit
    ): String = rideshareDomainService.subscribeToDriverAvailability(driverPubKey, onAvailability)

    /**
     * Subscribe to ride offers for the current user (as driver).
     * Direct offers are now NIP-44 encrypted for privacy.
     * Only returns offers from the last 10 minutes to avoid stale requests.
     * @param scope CoroutineScope for async decryption (use viewModelScope in ViewModels)
     * @param onOffer Called when a ride offer is received (after decryption)
     * @return Subscription ID for closing later, null if not logged in
     */
    fun subscribeToOffers(
        scope: CoroutineScope,
        onOffer: (RideOfferData) -> Unit
    ): String? = rideshareDomainService.subscribeToOffers(scope, onOffer)

    /**
     * Subscribe to ride acceptances for a specific direct offer.
     * Use subscribeToAcceptancesForOffer() for broadcast offers that accept any driver.
     *
     * @param offerEventId The offer event ID to watch
     * @param expectedDriverPubKey The driver pubkey this direct offer was sent to (required)
     * @param onAcceptance Called when acceptance is received from expected driver
     * @return Subscription ID for closing later
     */
    fun subscribeToAcceptance(
        offerEventId: String,
        expectedDriverPubKey: String,
        onAcceptance: (RideAcceptanceData) -> Unit
    ): String = rideshareDomainService.subscribeToAcceptance(offerEventId, expectedDriverPubKey, onAcceptance)

    /**
     * Subscribe to ride confirmations for a specific acceptance (driver listens for rider's confirmation).
     * Automatically decrypts the precise pickup location.
     *
     * @param acceptanceEventId The acceptance event ID to watch
     * @param scope CoroutineScope for async decryption (use viewModelScope in ViewModels)
     * @param expectedRiderPubKey The rider pubkey from the offer (required for validation)
     * @param onConfirmation Called when confirmation is received (with decrypted location)
     * @return Subscription ID for closing later
     */
    fun subscribeToConfirmation(
        acceptanceEventId: String,
        scope: CoroutineScope,
        expectedRiderPubKey: String,
        onConfirmation: (RideConfirmationData) -> Unit
    ): String = rideshareDomainService.subscribeToConfirmation(acceptanceEventId, scope, expectedRiderPubKey, onConfirmation)

    /**
     * Publish a ride cancellation event.
     * @param confirmationEventId The ride confirmation event ID being cancelled
     * @param otherPartyPubKey The other party's public key (to notify them)
     * @param reason Optional reason for cancellation
     * @return The event ID if successful, null on failure
     */
    suspend fun publishRideCancellation(
        confirmationEventId: String,
        otherPartyPubKey: String,
        reason: String? = null
    ): String? = rideshareDomainService.publishRideCancellation(confirmationEventId, otherPartyPubKey, reason)

    /**
     * Subscribe to ride cancellation events for a confirmed ride.
     * @param confirmationEventId The confirmation event ID to watch
     * @param onCancellation Called when cancellation is received
     * @return Subscription ID for closing later, or null if not logged in
     */
    fun subscribeToCancellation(
        confirmationEventId: String,
        onCancellation: (RideCancellationData) -> Unit
    ): String? = rideshareDomainService.subscribeToCancellation(confirmationEventId, onCancellation)

    /**
     * Close a subscription.
     */
    fun closeSubscription(subscriptionId: String) {
        relayManager.closeSubscription(subscriptionId)
    }

    // ==================== Chat Operations ====================

    /**
     * Send a private chat message to the other party in a ride.
     * Uses simple NIP-44 encryption (no gift wrapping) for reliability.
     * Messages should be deleted via NIP-09 after ride completes.
     *
     * @param confirmationEventId The ride confirmation event ID
     * @param recipientPubKey The recipient's public key (driver or rider)
     * @param message The message text
     * @return The event ID if successful, null on failure
     */
    suspend fun sendChatMessage(
        confirmationEventId: String,
        recipientPubKey: String,
        message: String
    ): String? = rideshareDomainService.sendChatMessage(confirmationEventId, recipientPubKey, message)

    /**
     * Subscribe to private chat messages for the current user.
     * Automatically decrypts NIP-44 encrypted messages.
     *
     * @param confirmationEventId The ride confirmation event ID to filter by (optional)
     * @param scope CoroutineScope for async decryption (use viewModelScope in ViewModels)
     * @param onMessage Called when a chat message is received (decrypted)
     * @return Subscription ID for closing later
     */
    fun subscribeToChatMessages(
        confirmationEventId: String? = null,
        scope: CoroutineScope,
        onMessage: (RideshareChatData) -> Unit
    ): String? = rideshareDomainService.subscribeToChatMessages(confirmationEventId, scope, onMessage)

    // ==================== Profile Operations ====================

    /**
     * Publish user profile (metadata).
     * @param profile The user profile to publish
     * @return The event ID if successful, null on failure
     */
    suspend fun publishProfile(profile: UserProfile): String? =
        profileBackupService.publishProfile(profile)

    /**
     * Subscribe to a user's profile updates.
     * @param pubKeyHex The user's public key in hex format
     * @param onProfile Called when profile is received
     * @return Subscription ID for closing later
     */
    fun subscribeToProfile(
        pubKeyHex: String,
        onProfile: (UserProfile) -> Unit
    ): String = profileBackupService.subscribeToProfile(pubKeyHex, onProfile)

    /**
     * Subscribe to the current user's own profile.
     * @param onProfile Called when profile is received
     * @return Subscription ID for closing later, null if not logged in
     */
    fun subscribeToOwnProfile(
        onProfile: (UserProfile) -> Unit
    ): String? = profileBackupService.subscribeToOwnProfile(onProfile)

    // ========================================
    // RIDE HISTORY BACKUP (Kind 30174)
    // ========================================

    /**
     * Publish ride history backup to Nostr relays.
     * The content is NIP-44 encrypted to the user's own pubkey for privacy.
     *
     * @param rides List of ride history entries
     * @param stats Aggregate statistics
     * @return Event ID if successful, null on failure
     */
    suspend fun publishRideHistoryBackup(
        rides: List<RideHistoryEntry>,
        stats: RideHistoryStats
    ): String? = profileBackupService.publishRideHistoryBackup(rides, stats)

    /**
     * Fetch the user's ride history from Nostr relays.
     * Decrypts the content using the user's keys.
     *
     * @return Decrypted ride history data, or null if not found or decryption fails
     */
    suspend fun fetchRideHistory(): RideHistoryData? = profileBackupService.fetchRideHistory()

    /**
     * Delete ride history backup from relays (NIP-09).
     * @param reason Reason for deletion
     * @return True if deletion request was sent
     */
    suspend fun deleteRideHistoryBackup(reason: String = "user requested"): Boolean =
        profileBackupService.deleteRideHistoryBackup(
            deleteEvents = { eventIds, r -> deleteEvents(eventIds, r, listOf(RideshareEventKinds.RIDE_HISTORY_BACKUP)) },
            reason = reason
        )

    // ==================== Profile Backup (Unified) ====================

    /**
     * Backup user profile to Nostr as an encrypted event.
     * Includes vehicles, saved locations, and settings in a single unified event.
     *
     * @param vehicles List of vehicles to backup (driver)
     * @param savedLocations List of saved locations to backup (rider)
     * @param settings Settings backup data
     * @return Event ID if successful, null on failure
     */
    suspend fun publishProfileBackup(
        vehicles: List<Vehicle>,
        savedLocations: List<SavedLocation>,
        settings: SettingsBackup
    ): String? = profileBackupService.publishProfileBackup(vehicles, savedLocations, settings)

    /**
     * Fetch the user's profile backup from Nostr relays.
     * Decrypts the content using the user's keys.
     *
     * @return Decrypted profile data, or null if not found or decryption fails
     */
    suspend fun fetchProfileBackup(): ProfileBackupData? = profileBackupService.fetchProfileBackup()

    // ==================== Vehicle Backup (DEPRECATED) ====================

    /**
     * Backup vehicles to Nostr as an encrypted event.
     *
     * @param vehicles List of vehicles to backup
     * @return Event ID if successful, null on failure
     * @deprecated Use [publishProfileBackup] instead. Vehicles are now part of unified profile backup.
     */
    @Deprecated("Use publishProfileBackup instead", ReplaceWith("publishProfileBackup(vehicles, emptyList(), SettingsBackup())"))
    @Suppress("DEPRECATION")
    suspend fun backupVehicles(vehicles: List<Vehicle>): String? =
        profileBackupService.backupVehicles(vehicles)

    /**
     * Fetch the user's vehicle backup from Nostr relays.
     * Decrypts the content using the user's keys.
     *
     * @return Decrypted vehicle data, or null if not found or decryption fails
     * @deprecated Use [fetchProfileBackup] instead. Vehicles are now part of unified profile backup.
     */
    @Deprecated("Use fetchProfileBackup instead", ReplaceWith("fetchProfileBackup()"))
    @Suppress("DEPRECATION")
    suspend fun fetchVehicleBackup(): VehicleBackupData? = profileBackupService.fetchVehicleBackup()

    // ==================== Saved Location Backup (DEPRECATED) ====================

    /**
     * Backup saved locations to Nostr as an encrypted event.
     *
     * @param locations List of saved locations to backup
     * @return Event ID if successful, null on failure
     * @deprecated Use [publishProfileBackup] instead. Saved locations are now part of unified profile backup.
     */
    @Deprecated("Use publishProfileBackup instead", ReplaceWith("publishProfileBackup(emptyList(), locations, SettingsBackup())"))
    @Suppress("DEPRECATION")
    suspend fun backupSavedLocations(locations: List<SavedLocation>): String? =
        profileBackupService.backupSavedLocations(locations)

    /**
     * Fetch the user's saved location backup from Nostr relays.
     * Decrypts the content using the user's keys.
     *
     * @return Decrypted saved location data, or null if not found or decryption fails
     * @deprecated Use [fetchProfileBackup] instead. Saved locations are now part of unified profile backup.
     */
    @Deprecated("Use fetchProfileBackup instead", ReplaceWith("fetchProfileBackup()"))
    @Suppress("DEPRECATION")
    suspend fun fetchSavedLocationBackup(): SavedLocationBackupData? =
        profileBackupService.fetchSavedLocationBackup()

    // ==================== RoadFlare Operations ====================

    /**
     * Publish followed drivers list to Nostr (Kind 30011).
     */
    suspend fun publishFollowedDrivers(drivers: List<FollowedDriver>): String? =
        roadflareDomainService.publishFollowedDrivers(drivers)

    /**
     * Fetch followed drivers list from Nostr (Kind 30011).
     */
    suspend fun fetchFollowedDrivers(): FollowedDriversData? =
        roadflareDomainService.fetchFollowedDrivers()

    /**
     * Publish driver RoadFlare state to Nostr (Kind 30012).
     */
    suspend fun publishDriverRoadflareState(
        signer: NostrSigner,
        state: DriverRoadflareState
    ): String? = roadflareDomainService.publishDriverRoadflareState(signer, state)

    /**
     * Fetch driver RoadFlare state from Nostr (Kind 30012).
     */
    suspend fun fetchDriverRoadflareState(): DriverRoadflareState? =
        roadflareDomainService.fetchDriverRoadflareState()

    /**
     * Fetch a driver's public key_updated_at timestamp from their Kind 30012 event.
     */
    suspend fun fetchDriverKeyUpdatedAt(driverPubKey: String): Long? =
        roadflareDomainService.fetchDriverKeyUpdatedAt(driverPubKey)

    /**
     * Publish RoadFlare location broadcast (Kind 30014).
     */
    suspend fun publishRoadflareLocation(
        signer: NostrSigner,
        roadflarePubKey: String,
        location: RoadflareLocation,
        keyVersion: Int
    ): String? = roadflareDomainService.publishRoadflareLocation(signer, roadflarePubKey, location, keyVersion)

    /**
     * Subscribe to RoadFlare location broadcasts from specific drivers (Kind 30014).
     */
    fun subscribeToRoadflareLocations(
        driverPubkeys: List<String>,
        onLocation: (event: com.vitorpamplona.quartz.nip01Core.core.Event, relayUrl: String) -> Unit
    ): String = roadflareDomainService.subscribeToRoadflareLocations(driverPubkeys, onLocation)

    /**
     * Publish RoadFlare key share to a follower (Kind 3186).
     */
    suspend fun publishRoadflareKeyShare(
        signer: NostrSigner,
        followerPubKey: String,
        roadflareKey: RoadflareKey,
        keyUpdatedAt: Long
    ): String? = roadflareDomainService.publishRoadflareKeyShare(signer, followerPubKey, roadflareKey, keyUpdatedAt)

    /**
     * Subscribe to RoadFlare key shares sent to us (Kind 3186).
     */
    fun subscribeToRoadflareKeyShares(
        onKeyShare: (event: com.vitorpamplona.quartz.nip01Core.core.Event, relayUrl: String) -> Unit
    ): String = roadflareDomainService.subscribeToRoadflareKeyShares(onKeyShare)

    /**
     * Publish RoadFlare key acknowledgement (Kind 3188).
     */
    suspend fun publishRoadflareKeyAck(
        driverPubKey: String,
        keyVersion: Int,
        keyUpdatedAt: Long,
        status: String = "received"
    ): String? = roadflareDomainService.publishRoadflareKeyAck(driverPubKey, keyVersion, keyUpdatedAt, status)

    /**
     * Subscribe to RoadFlare key acknowledgements sent to us (Kind 3188).
     */
    fun subscribeToRoadflareKeyAcks(
        onKeyAck: (event: com.vitorpamplona.quartz.nip01Core.core.Event, relayUrl: String) -> Unit
    ): String = roadflareDomainService.subscribeToRoadflareKeyAcks(onKeyAck)

    /**
     * Close a RoadFlare-related subscription.
     */
    fun closeRoadflareSubscription(subscriptionId: String) {
        relayManager.closeSubscription(subscriptionId)
    }

    // ==================== RoadFlare Follow Notifications (DEPRECATED) ====================

    @Deprecated("Use p-tag query on Kind 30011 instead of push notifications")
    @Suppress("DEPRECATION")
    suspend fun publishRoadflareFollowNotify(
        driverPubKey: String,
        riderName: String,
        action: String = "follow"
    ): String? = roadflareDomainService.publishRoadflareFollowNotify(driverPubKey, riderName, action)

    @Deprecated("Use queryRoadflareFollowers() with p-tag query instead")
    @Suppress("DEPRECATION")
    fun subscribeToRoadflareFollowNotifications(
        onFollowNotify: (event: com.vitorpamplona.quartz.nip01Core.core.Event, relayUrl: String) -> Unit
    ): String = roadflareDomainService.subscribeToRoadflareFollowNotifications(onFollowNotify)

    /**
     * Query for riders who follow this driver via Kind 30011 p-tags.
     */
    fun queryRoadflareFollowers(
        driverPubKey: String,
        onFollower: (riderPubKey: String) -> Unit
    ): String = roadflareDomainService.queryRoadflareFollowers(driverPubKey, onFollower)

    /**
     * Result of querying current followers via Kind 30011.
     */
    data class FollowerQueryResult(
        val followers: Set<String>,
        val success: Boolean
    )

    /**
     * Query for current followers (EOSE-aware, suspending).
     */
    suspend fun queryCurrentFollowerPubkeys(
        driverPubKey: String,
        timeoutMs: Long = 3000L
    ): FollowerQueryResult {
        val result = roadflareDomainService.queryCurrentFollowerPubkeys(driverPubKey, timeoutMs)
        return FollowerQueryResult(result.followers, result.success)
    }

    /**
     * Result of verifying a follower's status.
     */
    data class FollowerVerification(
        val stillFollowing: Boolean,
        val hasCurrentKey: Boolean?,
        val followerKeyUpdatedAt: Long?
    )

    /**
     * Verify a follower's status by checking their Kind 30011 event.
     */
    suspend fun verifyFollowerStatus(
        followerPubKey: String,
        driverPubKey: String,
        currentKeyUpdatedAt: Long?
    ): FollowerVerification? {
        val result = roadflareDomainService.verifyFollowerStatus(followerPubKey, driverPubKey, currentKeyUpdatedAt)
        return result?.let { FollowerVerification(it.stillFollowing, it.hasCurrentKey, it.followerKeyUpdatedAt) }
    }

    // ==================== RoadFlare Event Cleanup ====================

    /**
     * Fetch all Kind 3187 (follow notify) events sent by the current user.
     */
    suspend fun fetchOwnFollowNotifyEvents(): List<String> =
        roadflareDomainService.fetchOwnFollowNotifyEvents()

    /**
     * Delete Kind 3187 (follow notify) events.
     */
    suspend fun deleteFollowNotifyEvents(eventIds: List<String>): Int =
        roadflareDomainService.deleteFollowNotifyEvents(eventIds) { ids, reason, kinds ->
            deleteEvents(ids, reason, kinds)
        }

    // ==================== Generic Event Operations for Debug ====================

    /**
     * Count events of a specific kind authored by the current user.
     * Used by developer tools to inspect RoadFlare events.
     *
     * @param kind The event kind to count
     * @return Number of events found
     */
    suspend fun countOwnEventsOfKind(kind: Int): Int = withContext(Dispatchers.IO) {
        val myPubKey = keyManager.getPubKeyHex()
        if (myPubKey == null) {
            Log.e(TAG, "countOwnEventsOfKind: Not logged in")
            return@withContext 0
        }

        Log.d(TAG, "Counting own events of Kind $kind...")

        val eventIds = mutableListOf<String>()
        val eoseCount = CompletableDeferred<String>()
        val subscriptionId = relayManager.subscribe(
            kinds = listOf(kind),
            authors = listOf(myPubKey),
            onEose = { relayUrl -> eoseCount.complete(relayUrl) }
        ) { event, _ ->
            synchronized(eventIds) {
                eventIds.add(event.id)
            }
        }

        // Wait for EOSE or timeout
        if (withTimeoutOrNull(3000L) { eoseCount.await() } != null) delay(200)
        relayManager.closeSubscription(subscriptionId)

        Log.d(TAG, "Found ${eventIds.size} Kind $kind events")
        eventIds.size
    }

    /**
     * Delete all events of a specific kind authored by the current user.
     * Used by developer tools to clean up RoadFlare events during testing.
     *
     * @param kind The event kind to delete
     * @return Number of events deleted
     */
    suspend fun deleteOwnEventsOfKind(kind: Int): Int = withContext(Dispatchers.IO) {
        val myPubKey = keyManager.getPubKeyHex()
        if (myPubKey == null) {
            Log.e(TAG, "deleteOwnEventsOfKind: Not logged in")
            return@withContext 0
        }

        Log.d(TAG, "Fetching own Kind $kind events for deletion...")

        val eventIds = mutableListOf<String>()
        val eoseDelete = CompletableDeferred<String>()
        val subscriptionId = relayManager.subscribe(
            kinds = listOf(kind),
            authors = listOf(myPubKey),
            onEose = { relayUrl -> eoseDelete.complete(relayUrl) }
        ) { event, _ ->
            synchronized(eventIds) {
                eventIds.add(event.id)
            }
        }

        // Wait for EOSE or timeout
        if (withTimeoutOrNull(3000L) { eoseDelete.await() } != null) delay(200)
        relayManager.closeSubscription(subscriptionId)

        if (eventIds.isEmpty()) {
            Log.d(TAG, "No Kind $kind events to delete")
            return@withContext 0
        }

        Log.d(TAG, "Deleting ${eventIds.size} Kind $kind events...")
        val deleteEventId = deleteEvents(
            eventIds = eventIds,
            reason = "RoadFlare debug cleanup",
            kinds = eventIds.map { kind }
        )
        return@withContext if (deleteEventId != null) eventIds.size else 0
    }
}
