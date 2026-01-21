package com.ridestr.common.nostr

import android.content.Context
import android.util.Log
import com.ridestr.common.data.SavedLocation
import com.ridestr.common.data.Vehicle
import com.ridestr.common.nostr.events.*
import com.ridestr.common.settings.SettingsManager
import com.ridestr.common.nostr.keys.KeyManager
import com.ridestr.common.nostr.relay.RelayConfig
import com.ridestr.common.nostr.relay.RelayConnectionState
import com.ridestr.common.nostr.relay.RelayManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /**
     * Connection states for all relays.
     */
    val connectionStates: StateFlow<Map<String, RelayConnectionState>> = relayManager.connectionStates

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
     * @param location Current driver location
     * @return The event ID if successful, null on failure
     */
    suspend fun broadcastAvailability(
        location: Location,
        vehicle: Vehicle? = null,
        mintUrl: String? = null,
        paymentMethods: List<String> = listOf("cashu")
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot broadcast availability: Not logged in")
            return null
        }

        return try {
            val event = DriverAvailabilityEvent.create(
                signer = signer,
                location = location,
                vehicle = vehicle,
                mintUrl = mintUrl,
                paymentMethods = paymentMethods
            )
            relayManager.publish(event)
            Log.d(TAG, "Broadcast availability: ${event.id}, vehicle: ${vehicle?.shortName() ?: "none"}, mint: ${mintUrl ?: "none"}")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast availability", e)
            null
        }
    }

    /**
     * Broadcast driver going offline.
     * @param lastLocation Last known location (for context)
     * @return The event ID if successful, null on failure
     */
    suspend fun broadcastOffline(lastLocation: Location): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot broadcast offline: Not logged in")
            return null
        }

        return try {
            val event = DriverAvailabilityEvent.createOffline(signer, lastLocation)
            relayManager.publish(event)
            Log.d(TAG, "Broadcast offline: ${event.id}")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast offline", e)
            null
        }
    }

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
        val subscriptionId = relayManager.subscribe(
            kinds = rideshareKinds,
            authors = listOf(pubkey),
            limit = 1000
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

        // Wait for relays to respond (2 seconds should be enough for most events)
        delay(2000)

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
        val subscriptionId = relayManager.subscribe(
            kinds = listOf(kind),
            authors = listOf(pubkey),
            limit = 100
        ) { event, _ ->
            synchronized(eventIds) {
                eventIds.add(event.id)
            }
        }

        // Wait for relays to respond
        delay(2000)

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
        val subscriptionId = relayManager.subscribe(
            kinds = rideshareKinds,
            authors = listOf(pubkey),
            limit = 1000
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

        // Wait for relays to respond (2 seconds should be enough for most events)
        delay(2000)

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

        // Wait for relay connection (up to 15 seconds)
        var waitedMs = 0L
        while (!relayManager.isConnected() && waitedMs < 15000) {
            Log.d(TAG, "countRideshareEventsByKind: Waiting for relay... (${waitedMs}ms)")
            delay(500)
            waitedMs += 500
        }

        if (!relayManager.isConnected()) {
            Log.e(TAG, "countRideshareEventsByKind: No relays connected")
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
        val subscriptionId = relayManager.subscribe(
            kinds = rideshareKinds,
            authors = listOf(pubkey),
            limit = 1000
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

        // Wait longer for relay responses (increased from 2s to 5s)
        delay(5000)
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
        val subscriptionId = relayManager.subscribe(
            kinds = kindsToClean,
            authors = listOf(pubkey),
            limit = 500  // High limit to catch stragglers
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

        // Wait for relay responses - same as Account Safety (2 seconds)
        delay(2000)

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
        val retrySubId = relayManager.subscribe(
            kinds = kindsToClean,
            authors = listOf(pubkey),
            limit = 500
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

        delay(2000)
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
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot accept ride: Not logged in")
            return null
        }

        return try {
            val event = RideAcceptanceEvent.create(
                signer = signer,
                offerEventId = offer.eventId,
                riderPubKey = offer.riderPubKey,
                walletPubKey = walletPubKey,
                mintUrl = mintUrl,
                paymentMethod = paymentMethod
            )
            relayManager.publish(event)
            Log.d(TAG, "Accepted ride: ${event.id}, mint: ${mintUrl ?: "none"}, method: ${paymentMethod ?: "none"}")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to accept ride", e)
            null
        }
    }

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
     * @return The event ID if successful, null on failure
     */
    suspend fun publishDriverRideState(
        confirmationEventId: String,
        riderPubKey: String,
        currentStatus: String,
        history: List<DriverRideAction>,
        finalFare: Long? = null,
        invoice: String? = null
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot publish driver ride state: Not logged in")
            return null
        }

        return try {
            val event = DriverRideStateEvent.create(
                signer = signer,
                confirmationEventId = confirmationEventId,
                riderPubKey = riderPubKey,
                currentStatus = currentStatus,
                history = history,
                finalFare = finalFare,
                invoice = invoice
            )
            relayManager.publish(event)
            Log.d(TAG, "Published driver ride state: ${event.id} ($currentStatus, ${history.size} actions)")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish driver ride state", e)
            null
        }
    }

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
    ): String? {
        val myPubKey = keyManager.getPubKeyHex() ?: return null

        // Subscribe to Kind 30180 events from the driver for this ride
        // d-tag is the confirmation event ID
        // NOTE: No timestamp filter - handlers validate event's confirmationEventId against current ride
        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.DRIVER_RIDE_STATE),
            authors = listOf(driverPubKey),
            tags = mapOf("d" to listOf(confirmationEventId))
        ) { event, _ ->
            Log.d(TAG, "Received driver ride state ${event.id} from ${event.pubKey.take(8)}")
            DriverRideStateEvent.parse(event)?.let { data ->
                onState(data)
            }
        }
    }

    // ==================== Rider Ride State (Kind 30181) ====================

    /**
     * Publish or update rider ride state.
     * This is a parameterized replaceable event - only the latest state per ride is kept.
     *
     * @param confirmationEventId The ride confirmation event ID (used as d-tag)
     * @param driverPubKey The driver's public key
     * @param currentPhase Current phase (use RiderRideStateEvent.Phase constants)
     * @param history List of all actions in chronological order
     * @return The event ID if successful, null on failure
     */
    suspend fun publishRiderRideState(
        confirmationEventId: String,
        driverPubKey: String,
        currentPhase: String,
        history: List<RiderRideAction>
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot publish rider ride state: Not logged in")
            return null
        }

        return try {
            val event = RiderRideStateEvent.create(
                signer = signer,
                confirmationEventId = confirmationEventId,
                driverPubKey = driverPubKey,
                currentPhase = currentPhase,
                history = history
            )
            relayManager.publish(event)
            Log.d(TAG, "Published rider ride state: ${event.id} ($currentPhase, ${history.size} actions)")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish rider ride state", e)
            null
        }
    }

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
    ): String? {
        val myPubKey = keyManager.getPubKeyHex() ?: return null

        // Subscribe to Kind 30181 events from the rider for this ride
        // d-tag is the confirmation event ID
        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.RIDER_RIDE_STATE),
            authors = listOf(riderPubKey),
            tags = mapOf("d" to listOf(confirmationEventId))
        ) { event, _ ->
            Log.d(TAG, "Received rider ride state ${event.id} from ${event.pubKey.take(8)}")
            RiderRideStateEvent.parse(event)?.let { data ->
                onState(data)
            }
        }
    }

    /**
     * Encrypt a location for inclusion in rider ride state history.
     * @param location The location to encrypt
     * @param driverPubKey The driver's public key (recipient)
     * @return Encrypted location string, or null on failure
     */
    suspend fun encryptLocationForRiderState(
        location: Location,
        driverPubKey: String
    ): String? {
        val signer = keyManager.getSigner() ?: return null
        return try {
            signer.nip44Encrypt(location.toJson().toString(), driverPubKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt location", e)
            null
        }
    }

    /**
     * Decrypt a location from rider ride state history.
     * @param encryptedLocation The encrypted location string
     * @param riderPubKey The rider's public key (sender)
     * @return Decrypted location, or null on failure
     */
    suspend fun decryptLocationFromRiderState(
        encryptedLocation: String,
        riderPubKey: String
    ): Location? {
        val signer = keyManager.getSigner() ?: return null
        return try {
            val decrypted = signer.nip44Decrypt(encryptedLocation, riderPubKey)
            Location.fromJson(org.json.JSONObject(decrypted))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt location", e)
            null
        }
    }

    /**
     * Encrypt a PIN for inclusion in driver ride state history.
     * @param pin The PIN to encrypt
     * @param riderPubKey The rider's public key (recipient)
     * @return Encrypted PIN string, or null on failure
     */
    suspend fun encryptPinForDriverState(
        pin: String,
        riderPubKey: String
    ): String? {
        val signer = keyManager.getSigner() ?: return null
        return try {
            signer.nip44Encrypt(pin, riderPubKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt PIN", e)
            null
        }
    }

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
    ): String? {
        val signer = keyManager.getSigner() ?: return null
        return try {
            signer.nip44Encrypt(data, recipientPubKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt data for user", e)
            null
        }
    }

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
    ): String? {
        val signer = keyManager.getSigner() ?: return null
        return try {
            signer.nip44Decrypt(encryptedData, senderPubKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt data from user", e)
            null
        }
    }

    /**
     * Decrypt a PIN from driver ride state history.
     * @param encryptedPin The encrypted PIN string
     * @param driverPubKey The driver's public key (sender)
     * @return Decrypted PIN, or null on failure
     */
    suspend fun decryptPinFromDriverState(
        encryptedPin: String,
        driverPubKey: String
    ): String? {
        val signer = keyManager.getSigner() ?: return null
        return try {
            signer.nip44Decrypt(encryptedPin, driverPubKey)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt PIN", e)
            null
        }
    }

    // ==================== Rider Operations ====================

    /**
     * Send a ride offer to a driver.
     * @param driverAvailability The driver's availability event
     * @param pickup Pickup location
     * @param destination Destination location
     * @param fareEstimate Estimated fare in sats
     * @param pickupRouteKm Pre-calculated driver→pickup distance in km (optional)
     * @param pickupRouteMin Pre-calculated driver→pickup duration in minutes (optional)
     * @param rideRouteKm Pre-calculated pickup→destination distance in km (optional)
     * @param rideRouteMin Pre-calculated pickup→destination duration in minutes (optional)
     * @return The event ID if successful, null on failure
     */
    suspend fun sendRideOffer(
        driverAvailability: DriverAvailabilityData,
        pickup: Location,
        destination: Location,
        fareEstimate: Double,
        pickupRouteKm: Double? = null,
        pickupRouteMin: Double? = null,
        rideRouteKm: Double? = null,
        rideRouteMin: Double? = null,
        paymentHash: String? = null,  // HTLC payment hash for escrow
        mintUrl: String? = null,
        paymentMethod: String = "cashu"
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot send ride offer: Not logged in")
            return null
        }

        return try {
            val event = RideOfferEvent.create(
                signer = signer,
                driverAvailabilityEventId = driverAvailability.eventId,
                driverPubKey = driverAvailability.driverPubKey,
                pickup = pickup,
                destination = destination,
                fareEstimate = fareEstimate,
                pickupRouteKm = pickupRouteKm,
                pickupRouteMin = pickupRouteMin,
                rideRouteKm = rideRouteKm,
                rideRouteMin = rideRouteMin,
                paymentHash = paymentHash,
                mintUrl = mintUrl,
                paymentMethod = paymentMethod
            )
            relayManager.publish(event)
            Log.d(TAG, "Sent ride offer: ${event.id}${paymentHash?.let { " with payment hash" } ?: ""}, method: $paymentMethod")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ride offer", e)
            null
        }
    }

    /**
     * Confirm a ride with precise pickup location (encrypted).
     * @param acceptance The driver's acceptance
     * @param precisePickup Precise pickup location (will be encrypted)
     * @return The event ID if successful, null on failure
     */
    suspend fun confirmRide(
        acceptance: RideAcceptanceData,
        precisePickup: Location
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot confirm ride: Not logged in")
            return null
        }

        return try {
            val event = RideConfirmationEvent.create(
                signer = signer,
                acceptanceEventId = acceptance.eventId,
                driverPubKey = acceptance.driverPubKey,
                precisePickup = precisePickup
            )
            relayManager.publish(event)
            Log.d(TAG, "Confirmed ride: ${event.id}")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to confirm ride", e)
            null
        }
    }

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
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot broadcast ride request: Not logged in")
            return null
        }

        return try {
            val event = RideOfferEvent.createBroadcast(
                signer = signer,
                pickup = pickup,
                destination = destination,
                fareEstimate = fareEstimate,
                routeDistanceKm = routeDistanceKm,
                routeDurationMin = routeDurationMin,
                mintUrl = mintUrl,
                paymentMethod = paymentMethod
            )
            relayManager.publish(event)
            Log.d(TAG, "Broadcast ride request: ${event.id} (fare=$fareEstimate sats, method=$paymentMethod)")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to broadcast ride request", e)
            null
        }
    }

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
    ): String {
        // Get geohashes for driver's area
        val geohashes = Geohash.getSearchAreaGeohashes(location.lat, location.lon, expandSearch)
        Log.d(TAG, "=== DRIVER SUBSCRIBING TO RIDE REQUESTS ===")
        Log.d(TAG, "Driver location: ${location.lat}, ${location.lon}")
        Log.d(TAG, "Expanded search: $expandSearch")
        Log.d(TAG, "Geohash filter: $geohashes")

        // Only get requests from last 5 minutes to avoid stale ones
        val fiveMinutesAgo = (System.currentTimeMillis() / 1000) - (5 * 60)

        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.RIDE_OFFER),
            tags = mapOf(
                "g" to geohashes,
                "t" to listOf(RideOfferEvent.RIDE_REQUEST_TAG)  // Only broadcast requests
            ),
            since = fiveMinutesAgo
        ) { event, _ ->
            // Parse as broadcast offer (not direct)
            RideOfferEvent.parseBroadcast(event)?.let { data ->
                Log.d(TAG, "Received ride request ${event.id.take(8)} from ${event.pubKey.take(8)}, fare=${data.fareEstimate}")
                onRequest(data)
            }
        }
    }

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
    ): String? {
        if (eventIds.isEmpty()) return null

        Log.d(TAG, "Subscribing to deletion events for ${eventIds.size} ride requests")

        return relayManager.subscribe(
            kinds = listOf(DeletionEvent.KIND),
            tags = mapOf("e" to eventIds)
        ) { event, _ ->
            // Extract which event IDs are being deleted
            for (tag in event.tags) {
                if (tag.getOrNull(0) == "e") {
                    val deletedEventId = tag.getOrNull(1)
                    if (deletedEventId != null && deletedEventId in eventIds) {
                        Log.d(TAG, "Ride request deleted: ${deletedEventId.take(8)}")
                        onDeletion(deletedEventId)
                    }
                }
            }
        }
    }

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
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot accept broadcast ride: Not logged in")
            return null
        }

        return try {
            val event = RideAcceptanceEvent.create(
                signer = signer,
                offerEventId = request.eventId,
                riderPubKey = request.riderPubKey,
                walletPubKey = walletPubKey,
                mintUrl = mintUrl,
                paymentMethod = paymentMethod
            )
            relayManager.publish(event)
            Log.d(TAG, "Accepted broadcast ride: ${event.id}, mint: ${mintUrl ?: "none"}, method: ${paymentMethod ?: "none"}")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to accept broadcast ride", e)
            null
        }
    }

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
    ): String {
        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.RIDE_ACCEPTANCE),
            tags = mapOf("e" to listOf(offerEventId))
        ) { event, _ ->
            RideAcceptanceEvent.parse(event)?.let { data ->
                Log.d(TAG, "Received acceptance for offer ${offerEventId.take(8)} from driver ${data.driverPubKey.take(8)}")
                onAcceptance(data)
            }
        }
    }

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
        onDriver: (DriverAvailabilityData) -> Unit
    ): String {
        val tags = mutableMapOf<String, List<String>>(
            "t" to listOf(RideshareTags.RIDESHARE_TAG)
        )

        // If location provided, add geohash filters
        location?.let {
            val geohashes = Geohash.getSearchAreaGeohashes(it.lat, it.lon, expandSearch)
            tags["g"] = geohashes  // Note: RelayManager adds # prefix automatically
            Log.d(TAG, "=== RIDER SUBSCRIBING TO DRIVERS ===")
            Log.d(TAG, "Rider location: ${it.lat}, ${it.lon}")
            Log.d(TAG, "Expanded search: $expandSearch")
            Log.d(TAG, "Geohash filter: $geohashes")
        } ?: run {
            Log.d(TAG, "=== RIDER SUBSCRIBING TO DRIVERS (no location filter) ===")
        }

        // Only get events from last 15 minutes to avoid stale drivers
        val fifteenMinutesAgo = (System.currentTimeMillis() / 1000) - (15 * 60)

        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.DRIVER_AVAILABILITY),
            tags = tags,
            since = fifteenMinutesAgo
        ) { event, _ ->
            DriverAvailabilityEvent.parse(event)?.let { data ->
                onDriver(data)
            }
        }
    }

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
    ): String? {
        val myPubKey = keyManager.getPubKeyHex() ?: return null
        val signer = keyManager.getSigner() ?: return null

        // Only get offers from last 10 minutes to avoid old requests
        val tenMinutesAgo = (System.currentTimeMillis() / 1000) - (10 * 60)

        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.RIDE_OFFER),
            tags = mapOf("p" to listOf(myPubKey)),
            since = tenMinutesAgo
        ) { event, _ ->
            // Direct offers are now encrypted - parse and decrypt
            RideOfferEvent.parseEncrypted(event)?.let { encryptedData ->
                scope.launch {
                    RideOfferEvent.decrypt(signer, encryptedData)?.let { data ->
                        Log.d(TAG, "Decrypted direct offer from ${data.riderPubKey.take(8)}")
                        onOffer(data)
                    }
                }
            }
        }
    }

    /**
     * Subscribe to ride acceptances for a specific offer.
     * @param offerEventId The offer event ID to watch
     * @param onAcceptance Called when acceptance is received
     * @return Subscription ID for closing later
     */
    fun subscribeToAcceptance(
        offerEventId: String,
        onAcceptance: (RideAcceptanceData) -> Unit
    ): String {
        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.RIDE_ACCEPTANCE),
            tags = mapOf("e" to listOf(offerEventId))
        ) { event, _ ->
            RideAcceptanceEvent.parse(event)?.let { data ->
                onAcceptance(data)
            }
        }
    }

    /**
     * Subscribe to ride confirmations for a specific acceptance (driver listens for rider's confirmation).
     * Automatically decrypts the precise pickup location.
     * @param acceptanceEventId The acceptance event ID to watch
     * @param scope CoroutineScope for async decryption (use viewModelScope in ViewModels)
     * @param onConfirmation Called when confirmation is received (with decrypted location)
     * @return Subscription ID for closing later
     */
    fun subscribeToConfirmation(
        acceptanceEventId: String,
        scope: CoroutineScope,
        onConfirmation: (RideConfirmationData) -> Unit
    ): String {
        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.RIDE_CONFIRMATION),
            tags = mapOf("e" to listOf(acceptanceEventId))
        ) { event, _ ->
            // Parse encrypted confirmation
            RideConfirmationEvent.parseEncrypted(event)?.let { encryptedData ->
                // Decrypt using driver's key
                val signer = keyManager.getSigner()
                if (signer != null) {
                    scope.launch {
                        RideConfirmationEvent.decrypt(signer, encryptedData)?.let { data ->
                            onConfirmation(data)
                        }
                    }
                }
            }
        }
    }

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
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot publish cancellation: Not logged in")
            return null
        }

        return try {
            val event = RideCancellationEvent.create(
                signer = signer,
                confirmationEventId = confirmationEventId,
                otherPartyPubKey = otherPartyPubKey,
                reason = reason
            )
            relayManager.publish(event)
            Log.d(TAG, "Published ride cancellation: ${event.id}")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish cancellation", e)
            null
        }
    }

    /**
     * Subscribe to ride cancellation events for a confirmed ride.
     * @param confirmationEventId The confirmation event ID to watch
     * @param onCancellation Called when cancellation is received
     * @return Subscription ID for closing later, or null if not logged in
     */
    fun subscribeToCancellation(
        confirmationEventId: String,
        onCancellation: (RideCancellationData) -> Unit
    ): String? {
        val myPubKey = keyManager.getPubKeyHex() ?: return null

        // Subscribe to Kind 3179 events addressed to us for this ride
        // Filter by both p tag (recipient) and e tag (confirmation) for reliable delivery
        // NOTE: No timestamp filter - handlers validate event's confirmationEventId against current ride
        return relayManager.subscribe(
            kinds = listOf(RideCancellationEvent.KIND),
            tags = mapOf(
                "p" to listOf(myPubKey),
                "e" to listOf(confirmationEventId)
            )
        ) { event, _ ->
            Log.d(TAG, "Received cancellation event ${event.id} from ${event.pubKey.take(8)}")
            RideCancellationEvent.parse(event)?.let { data ->
                onCancellation(data)
            }
        }
    }

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
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot send chat message: Not logged in")
            return null
        }

        return try {
            // Create encrypted chat message (NIP-44)
            val event = RideshareChatEvent.create(
                signer = signer,
                confirmationEventId = confirmationEventId,
                recipientPubKey = recipientPubKey,
                message = message
            )

            relayManager.publish(event)
            Log.d(TAG, "Sent chat message: ${event.id} to ${recipientPubKey.take(8)}")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send chat message", e)
            null
        }
    }

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
    ): String? {
        val myPubKey = keyManager.getPubKeyHex() ?: return null

        // Subscribe to Kind 3178 chat events addressed to us
        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.RIDESHARE_CHAT),
            tags = mapOf("p" to listOf(myPubKey))
        ) { event, _ ->
            Log.d(TAG, "Received chat event ${event.id} from ${event.pubKey.take(8)}")

            // Decrypt the message
            val signer = keyManager.getSigner()
            if (signer != null) {
                scope.launch {
                    try {
                        RideshareChatEvent.parseAndDecrypt(signer, event)?.let { chatData ->
                            // Filter by confirmation event if specified
                            if (confirmationEventId == null ||
                                chatData.confirmationEventId == confirmationEventId) {
                                Log.d(TAG, "Decrypted chat message: ${chatData.message.take(20)}...")
                                onMessage(chatData)
                            } else {
                                Log.d(TAG, "Ignoring chat for different ride: ${chatData.confirmationEventId.take(8)}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to decrypt chat message", e)
                    }
                }
            }
        }
    }

    // ==================== Profile Operations ====================

    /**
     * Publish user profile (metadata).
     * @param profile The user profile to publish
     * @return The event ID if successful, null on failure
     */
    suspend fun publishProfile(profile: UserProfile): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot publish profile: Not logged in")
            return null
        }

        return try {
            val event = MetadataEvent.create(signer, profile)
            relayManager.publish(event)
            Log.d(TAG, "Published profile: ${event.id}")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish profile", e)
            null
        }
    }

    /**
     * Subscribe to a user's profile updates.
     * @param pubKeyHex The user's public key in hex format
     * @param onProfile Called when profile is received
     * @return Subscription ID for closing later
     */
    fun subscribeToProfile(
        pubKeyHex: String,
        onProfile: (UserProfile) -> Unit
    ): String {
        return relayManager.subscribe(
            kinds = listOf(MetadataEvent.KIND),
            authors = listOf(pubKeyHex),
            limit = 1
        ) { event, _ ->
            MetadataEvent.parse(event)?.let { profile ->
                onProfile(profile)
            }
        }
    }

    /**
     * Subscribe to the current user's own profile.
     * @param onProfile Called when profile is received
     * @return Subscription ID for closing later, null if not logged in
     */
    fun subscribeToOwnProfile(
        onProfile: (UserProfile) -> Unit
    ): String? {
        val myPubKey = keyManager.getPubKeyHex() ?: return null
        return subscribeToProfile(myPubKey, onProfile)
    }

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
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot publish ride history: Not logged in")
            return null
        }

        // Wait for relay connection (up to 15 seconds)
        var waitedMs = 0L
        while (!relayManager.isConnected() && waitedMs < 15000) {
            Log.d(TAG, "publishRideHistoryBackup: Waiting for relay... (${waitedMs}ms)")
            delay(500)
            waitedMs += 500
        }

        if (!relayManager.isConnected()) {
            Log.e(TAG, "publishRideHistoryBackup: No relays connected - backup NOT saved!")
            return null
        }

        return try {
            val event = RideHistoryEvent.create(signer, rides, stats)
            if (event != null) {
                relayManager.publish(event)
                Log.d(TAG, "Published ride history backup: ${event.id} (${rides.size} rides) to ${relayManager.connectedCount()} relays")
                event.id
            } else {
                Log.e(TAG, "Failed to create ride history event")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish ride history", e)
            null
        }
    }

    /**
     * Fetch the user's ride history from Nostr relays.
     * Decrypts the content using the user's keys.
     *
     * @return Decrypted ride history data, or null if not found or decryption fails
     */
    suspend fun fetchRideHistory(): RideHistoryData? {
        val signer = keyManager.getSigner()
        val myPubKey = keyManager.getPubKeyHex()
        if (signer == null || myPubKey == null) {
            Log.e(TAG, "Cannot fetch ride history: Not logged in")
            return null
        }

        return withContext(Dispatchers.IO) {
            // Wait for relay connection (up to 15 seconds)
            var waitedMs = 0L
            while (!relayManager.isConnected() && waitedMs < 15000) {
                Log.d(TAG, "fetchRideHistory: Waiting for relay... (${waitedMs}ms)")
                delay(500)
                waitedMs += 500
            }

            if (!relayManager.isConnected()) {
                Log.e(TAG, "fetchRideHistory: No relays connected - cannot restore")
                return@withContext null
            }

            Log.d(TAG, "Fetching ride history from ${relayManager.connectedCount()} relays for ${myPubKey.take(16)}...")

            try {
                var result: RideHistoryData? = null
                val subscriptionId = relayManager.subscribe(
                    kinds = listOf(RideshareEventKinds.RIDE_HISTORY_BACKUP),
                    authors = listOf(myPubKey),
                    tags = mapOf("d" to listOf(RideHistoryEvent.D_TAG)),
                    limit = 1
                ) { event, relayUrl ->
                    // This is our own history event - try to decrypt
                    Log.d(TAG, "Received ride history event ${event.id} from $relayUrl")
                    kotlinx.coroutines.runBlocking {
                        RideHistoryEvent.parseAndDecrypt(signer, event)?.let { data ->
                            result = data
                            Log.d(TAG, "Decrypted ride history: ${data.rides.size} rides")
                        }
                    }
                }

                // Wait for response (increased from 3s to 8s)
                delay(8000)
                relayManager.closeSubscription(subscriptionId)

                if (result == null) {
                    Log.d(TAG, "No ride history backup found on relays")
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch ride history", e)
                null
            }
        }
    }

    /**
     * Delete ride history backup from relays (NIP-09).
     * @param reason Reason for deletion
     * @return True if deletion request was sent
     */
    suspend fun deleteRideHistoryBackup(reason: String = "user requested"): Boolean {
        val myPubKey = keyManager.getPubKeyHex() ?: return false

        return try {
            // First find the current history event ID
            val historyData = fetchRideHistory()
            if (historyData != null) {
                deleteEvents(listOf(historyData.eventId), reason)
                Log.d(TAG, "Deleted ride history backup: ${historyData.eventId}")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete ride history", e)
            false
        }
    }

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
    ): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot backup profile: Not logged in")
            return null
        }

        // Wait for relay connection (up to 15 seconds)
        var waitedMs = 0L
        while (!relayManager.isConnected() && waitedMs < 15000) {
            Log.d(TAG, "publishProfileBackup: Waiting for relay... (${waitedMs}ms)")
            delay(500)
            waitedMs += 500
        }

        if (!relayManager.isConnected()) {
            Log.e(TAG, "publishProfileBackup: No relays connected - backup NOT saved!")
            return null
        }

        return try {
            val event = ProfileBackupEvent.create(signer, vehicles, savedLocations, settings)
            if (event != null) {
                relayManager.publish(event)
                Log.d(TAG, "Published profile backup: ${event.id} (${vehicles.size} vehicles, ${savedLocations.size} locations) to ${relayManager.connectedCount()} relays")
                event.id
            } else {
                Log.e(TAG, "Failed to create profile backup event")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to backup profile", e)
            null
        }
    }

    /**
     * Fetch the user's profile backup from Nostr relays.
     * Decrypts the content using the user's keys.
     *
     * @return Decrypted profile data, or null if not found or decryption fails
     */
    suspend fun fetchProfileBackup(): ProfileBackupData? {
        val signer = keyManager.getSigner()
        val myPubKey = keyManager.getPubKeyHex()
        if (signer == null || myPubKey == null) {
            Log.e(TAG, "Cannot fetch profile backup: Not logged in")
            return null
        }

        return withContext(Dispatchers.IO) {
            // Wait for relay connection (up to 15 seconds)
            var waitedMs = 0L
            while (!relayManager.isConnected() && waitedMs < 15000) {
                Log.d(TAG, "fetchProfileBackup: Waiting for relay... (${waitedMs}ms)")
                delay(500)
                waitedMs += 500
            }

            if (!relayManager.isConnected()) {
                Log.e(TAG, "fetchProfileBackup: No relays connected - cannot restore")
                return@withContext null
            }

            Log.d(TAG, "Fetching profile backup from ${relayManager.connectedCount()} relays for ${myPubKey.take(16)}...")

            try {
                var result: ProfileBackupData? = null
                val subscriptionId = relayManager.subscribe(
                    kinds = listOf(RideshareEventKinds.PROFILE_BACKUP),
                    authors = listOf(myPubKey),
                    tags = mapOf("d" to listOf(ProfileBackupEvent.D_TAG)),
                    limit = 1
                ) { event, relayUrl ->
                    // This is our own profile event - try to decrypt
                    Log.d(TAG, "Received profile backup event ${event.id} from $relayUrl")
                    kotlinx.coroutines.runBlocking {
                        ProfileBackupEvent.parseAndDecrypt(signer, event)?.let { data ->
                            result = data
                            Log.d(TAG, "Decrypted profile backup: ${data.vehicles.size} vehicles, ${data.savedLocations.size} locations")
                        }
                    }
                }

                // Wait for response
                delay(8000)
                relayManager.closeSubscription(subscriptionId)

                if (result == null) {
                    Log.d(TAG, "No profile backup found on relays")
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch profile backup", e)
                null
            }
        }
    }

    // ==================== Vehicle Backup (DEPRECATED) ====================

    /**
     * Backup vehicles to Nostr as an encrypted event.
     *
     * @param vehicles List of vehicles to backup
     * @return Event ID if successful, null on failure
     * @deprecated Use [publishProfileBackup] instead. Vehicles are now part of unified profile backup.
     */
    @Deprecated("Use publishProfileBackup instead", ReplaceWith("publishProfileBackup(vehicles, emptyList(), SettingsBackup())"))
    suspend fun backupVehicles(vehicles: List<Vehicle>): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot backup vehicles: Not logged in")
            return null
        }

        // Wait for relay connection (up to 15 seconds)
        var waitedMs = 0L
        while (!relayManager.isConnected() && waitedMs < 15000) {
            Log.d(TAG, "backupVehicles: Waiting for relay... (${waitedMs}ms)")
            delay(500)
            waitedMs += 500
        }

        if (!relayManager.isConnected()) {
            Log.e(TAG, "backupVehicles: No relays connected - backup NOT saved!")
            return null
        }

        return try {
            val event = VehicleBackupEvent.create(signer, vehicles)
            if (event != null) {
                relayManager.publish(event)
                Log.d(TAG, "Published vehicle backup: ${event.id} (${vehicles.size} vehicles) to ${relayManager.connectedCount()} relays")
                event.id
            } else {
                Log.e(TAG, "Failed to create vehicle backup event")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to backup vehicles", e)
            null
        }
    }

    /**
     * Fetch the user's vehicle backup from Nostr relays.
     * Decrypts the content using the user's keys.
     *
     * @return Decrypted vehicle data, or null if not found or decryption fails
     * @deprecated Use [fetchProfileBackup] instead. Vehicles are now part of unified profile backup.
     */
    @Deprecated("Use fetchProfileBackup instead", ReplaceWith("fetchProfileBackup()"))
    suspend fun fetchVehicleBackup(): VehicleBackupData? {
        val signer = keyManager.getSigner()
        val myPubKey = keyManager.getPubKeyHex()
        if (signer == null || myPubKey == null) {
            Log.e(TAG, "Cannot fetch vehicle backup: Not logged in")
            return null
        }

        return withContext(Dispatchers.IO) {
            // Wait for relay connection (up to 15 seconds)
            var waitedMs = 0L
            while (!relayManager.isConnected() && waitedMs < 15000) {
                Log.d(TAG, "fetchVehicleBackup: Waiting for relay... (${waitedMs}ms)")
                delay(500)
                waitedMs += 500
            }

            if (!relayManager.isConnected()) {
                Log.e(TAG, "fetchVehicleBackup: No relays connected - cannot restore")
                return@withContext null
            }

            Log.d(TAG, "Fetching vehicle backup from ${relayManager.connectedCount()} relays for ${myPubKey.take(16)}...")

            try {
                var result: VehicleBackupData? = null
                val subscriptionId = relayManager.subscribe(
                    kinds = listOf(RideshareEventKinds.VEHICLE_BACKUP),
                    authors = listOf(myPubKey),
                    tags = mapOf("d" to listOf(VehicleBackupEvent.D_TAG)),
                    limit = 1
                ) { event, relayUrl ->
                    // This is our own vehicle event - try to decrypt
                    Log.d(TAG, "Received vehicle backup event ${event.id} from $relayUrl")
                    kotlinx.coroutines.runBlocking {
                        VehicleBackupEvent.parseAndDecrypt(signer, event)?.let { data ->
                            result = data
                            Log.d(TAG, "Decrypted vehicle backup: ${data.vehicles.size} vehicles")
                        }
                    }
                }

                // Wait for response
                delay(8000)
                relayManager.closeSubscription(subscriptionId)

                if (result == null) {
                    Log.d(TAG, "No vehicle backup found on relays")
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch vehicle backup", e)
                null
            }
        }
    }

    // ==================== Saved Location Backup (DEPRECATED) ====================

    /**
     * Backup saved locations to Nostr as an encrypted event.
     *
     * @param locations List of saved locations to backup
     * @return Event ID if successful, null on failure
     * @deprecated Use [publishProfileBackup] instead. Saved locations are now part of unified profile backup.
     */
    @Deprecated("Use publishProfileBackup instead", ReplaceWith("publishProfileBackup(emptyList(), locations, SettingsBackup())"))
    suspend fun backupSavedLocations(locations: List<com.ridestr.common.data.SavedLocation>): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot backup saved locations: Not logged in")
            return null
        }

        // Wait for relay connection (up to 15 seconds)
        var waitedMs = 0L
        while (!relayManager.isConnected() && waitedMs < 15000) {
            Log.d(TAG, "backupSavedLocations: Waiting for relay... (${waitedMs}ms)")
            delay(500)
            waitedMs += 500
        }

        if (!relayManager.isConnected()) {
            Log.e(TAG, "backupSavedLocations: No relays connected - backup NOT saved!")
            return null
        }

        return try {
            val event = SavedLocationBackupEvent.create(signer, locations)
            if (event != null) {
                relayManager.publish(event)
                Log.d(TAG, "Published saved location backup: ${event.id} (${locations.size} locations) to ${relayManager.connectedCount()} relays")
                event.id
            } else {
                Log.e(TAG, "Failed to create saved location backup event")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to backup saved locations", e)
            null
        }
    }

    /**
     * Fetch the user's saved location backup from Nostr relays.
     * Decrypts the content using the user's keys.
     *
     * @return Decrypted saved location data, or null if not found or decryption fails
     * @deprecated Use [fetchProfileBackup] instead. Saved locations are now part of unified profile backup.
     */
    @Deprecated("Use fetchProfileBackup instead", ReplaceWith("fetchProfileBackup()"))
    suspend fun fetchSavedLocationBackup(): SavedLocationBackupData? {
        val signer = keyManager.getSigner()
        val myPubKey = keyManager.getPubKeyHex()
        if (signer == null || myPubKey == null) {
            Log.e(TAG, "Cannot fetch saved location backup: Not logged in")
            return null
        }

        return withContext(Dispatchers.IO) {
            // Wait for relay connection (up to 15 seconds)
            var waitedMs = 0L
            while (!relayManager.isConnected() && waitedMs < 15000) {
                Log.d(TAG, "fetchSavedLocationBackup: Waiting for relay... (${waitedMs}ms)")
                delay(500)
                waitedMs += 500
            }

            if (!relayManager.isConnected()) {
                Log.e(TAG, "fetchSavedLocationBackup: No relays connected - cannot restore")
                return@withContext null
            }

            Log.d(TAG, "Fetching saved location backup from ${relayManager.connectedCount()} relays for ${myPubKey.take(16)}...")

            try {
                var result: SavedLocationBackupData? = null
                val subscriptionId = relayManager.subscribe(
                    kinds = listOf(RideshareEventKinds.SAVED_LOCATIONS_BACKUP),
                    authors = listOf(myPubKey),
                    tags = mapOf("d" to listOf(SavedLocationBackupEvent.D_TAG)),
                    limit = 1
                ) { event, relayUrl ->
                    // This is our own saved locations event - try to decrypt
                    Log.d(TAG, "Received saved location backup event ${event.id} from $relayUrl")
                    kotlinx.coroutines.runBlocking {
                        SavedLocationBackupEvent.parseAndDecrypt(signer, event)?.let { data ->
                            result = data
                            Log.d(TAG, "Decrypted saved location backup: ${data.locations.size} locations")
                        }
                    }
                }

                // Wait for response
                delay(8000)
                relayManager.closeSubscription(subscriptionId)

                if (result == null) {
                    Log.d(TAG, "No saved location backup found on relays")
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch saved location backup", e)
                null
            }
        }
    }
}
