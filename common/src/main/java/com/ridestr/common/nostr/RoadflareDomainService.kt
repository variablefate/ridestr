package com.ridestr.common.nostr

import android.util.Log
import com.ridestr.common.nostr.events.*
import com.ridestr.common.nostr.keys.KeyManager
import com.ridestr.common.nostr.relay.RelayManager
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Domain service for all RoadFlare-related Nostr operations.
 *
 * RoadFlare enables riders to build a personal rideshare network of trusted drivers.
 * Drivers broadcast encrypted locations; only approved followers can see them.
 *
 * Event kinds handled:
 * - Kind 30011: Followed drivers (rider's favorites + keys)
 * - Kind 30012: Driver RoadFlare state (keypair, followers, muted)
 * - Kind 30014: Location broadcast (encrypted, 5-min expiry)
 * - Kind 3186: Key share (driver→follower DM, 5-min expiry)
 * - Kind 3187: Follow notification (deprecated, real-time UX)
 * - Kind 3188: Key acknowledgement (confirm receipt or refresh request)
 *
 * @param relayManager The RelayManager instance for relay connections
 * @param keyManager The KeyManager instance for accessing the user's signer
 */
class RoadflareDomainService(
    private val relayManager: RelayManager,
    private val keyManager: KeyManager
) {

    companion object {
        private const val TAG = "RoadflareDomainService"
    }

    // ==================== Followed Drivers (Kind 30011) ====================

    /**
     * Publish followed drivers list to Nostr (Kind 30011).
     * Encrypted to self - only the rider can read their own followed drivers.
     *
     * @param drivers List of followed drivers with their RoadFlare keys
     * @return Event ID if successful, null on failure
     */
    suspend fun publishFollowedDrivers(drivers: List<FollowedDriver>): String? {
        val signer = keyManager.getSigner()
        if (signer == null) {
            Log.e(TAG, "Cannot publish followed drivers: Not logged in")
            return null
        }

        // Wait for relay connection
        if (!relayManager.awaitConnected(tag = "publishFollowedDrivers")) {
            return null
        }

        return try {
            val event = FollowedDriversEvent.create(signer, drivers)
            if (event != null) {
                relayManager.publish(event)
                Log.d(TAG, "Published followed drivers: ${event.id} (${drivers.size} drivers)")
                event.id
            } else {
                Log.e(TAG, "Failed to create followed drivers event")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish followed drivers", e)
            null
        }
    }

    /**
     * Fetch followed drivers list from Nostr (Kind 30011).
     *
     * @return Decrypted followed drivers data, or null if not found
     */
    suspend fun fetchFollowedDrivers(): FollowedDriversData? {
        val signer = keyManager.getSigner()
        val myPubKey = keyManager.getPubKeyHex()
        if (signer == null || myPubKey == null) {
            Log.e(TAG, "Cannot fetch followed drivers: Not logged in")
            return null
        }

        return withContext(Dispatchers.IO) {
            // Wait for relay connection
            if (!relayManager.awaitConnected(tag = "fetchFollowedDrivers")) {
                return@withContext null
            }

            Log.d(TAG, "Fetching followed drivers for ${myPubKey.take(16)}...")

            try {
                // AtomicReference for thread-safe event storage (relay callbacks may run on different thread)
                val eventRef = AtomicReference<Event?>(null)
                val eoseFollowed = CompletableDeferred<String>()
                val subscriptionId = relayManager.subscribe(
                    kinds = listOf(RideshareEventKinds.ROADFLARE_FOLLOWED_DRIVERS),
                    authors = listOf(myPubKey),
                    tags = mapOf("d" to listOf(FollowedDriversEvent.D_TAG)),
                    limit = 1,
                    onEose = { relayUrl -> eoseFollowed.complete(relayUrl) }
                ) { event, relayUrl ->
                    Log.d(TAG, "Received followed drivers event ${event.id} from $relayUrl")
                    eventRef.set(event)  // Thread-safe assignment, last event wins (timestamps resolve ordering)
                }

                // Wait for EOSE or timeout
                if (withTimeoutOrNull(8000L) { eoseFollowed.await() } != null) delay(200)
                relayManager.closeSubscription(subscriptionId)

                // Decrypt outside callback
                val result = eventRef.get()?.let { event ->
                    FollowedDriversEvent.parseAndDecrypt(signer, event)?.also { data ->
                        Log.d(TAG, "Decrypted followed drivers: ${data.drivers.size} drivers")
                    }
                }
                if (result == null) {
                    Log.d(TAG, "No followed drivers found on relays")
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch followed drivers", e)
                null
            }
        }
    }

    // ==================== Driver RoadFlare State (Kind 30012) ====================

    /**
     * Publish driver RoadFlare state to Nostr (Kind 30012).
     * Encrypted to self - contains keypair, followers, muted list.
     *
     * @param signer The driver's signer (can be different from keyManager.getSigner in some cases)
     * @param state The complete RoadFlare state
     * @return Event ID if successful, null on failure
     */
    suspend fun publishDriverRoadflareState(
        signer: NostrSigner,
        state: DriverRoadflareState
    ): String? {
        // Wait for relay connection
        if (!relayManager.awaitConnected(tag = "publishDriverRoadflareState")) {
            return null
        }

        return try {
            val event = DriverRoadflareStateEvent.create(signer, state)
            if (event != null) {
                relayManager.publish(event)
                Log.d(TAG, "Published driver RoadFlare state: ${event.id} (key v${state.roadflareKey?.version}, ${state.followers.size} followers)")
                event.id
            } else {
                Log.e(TAG, "Failed to create driver RoadFlare state event")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish driver RoadFlare state", e)
            null
        }
    }

    /**
     * Fetch driver RoadFlare state from Nostr (Kind 30012).
     *
     * @return Decrypted RoadFlare state, or null if not found
     */
    suspend fun fetchDriverRoadflareState(): DriverRoadflareState? {
        val signer = keyManager.getSigner()
        val myPubKey = keyManager.getPubKeyHex()
        if (signer == null || myPubKey == null) {
            Log.e(TAG, "Cannot fetch driver RoadFlare state: Not logged in")
            return null
        }

        return withContext(Dispatchers.IO) {
            // Wait for relay connection
            if (!relayManager.awaitConnected(tag = "fetchDriverRoadflareState")) {
                return@withContext null
            }

            Log.d(TAG, "Fetching driver RoadFlare state for ${myPubKey.take(16)}...")

            try {
                // AtomicReference for thread-safe event storage (relay callbacks may run on different thread)
                val eventRef = AtomicReference<Event?>(null)
                val eoseRoadflare = CompletableDeferred<String>()
                val subscriptionId = relayManager.subscribe(
                    kinds = listOf(RideshareEventKinds.ROADFLARE_DRIVER_STATE),
                    authors = listOf(myPubKey),
                    tags = mapOf("d" to listOf(DriverRoadflareStateEvent.D_TAG)),
                    limit = 1,
                    onEose = { relayUrl -> eoseRoadflare.complete(relayUrl) }
                ) { event, relayUrl ->
                    Log.d(TAG, "Received driver RoadFlare state event ${event.id} from $relayUrl")
                    eventRef.set(event)  // Thread-safe assignment, last event wins (timestamps resolve ordering)
                }

                // Wait for EOSE or timeout
                if (withTimeoutOrNull(8000L) { eoseRoadflare.await() } != null) delay(200)
                relayManager.closeSubscription(subscriptionId)

                // Decrypt outside callback
                val result = eventRef.get()?.let { event ->
                    DriverRoadflareStateEvent.parseAndDecrypt(signer, event)?.also { data ->
                        Log.d(TAG, "Decrypted driver RoadFlare state: key v${data.roadflareKey?.version}, ${data.followers.size} followers")
                    }
                }
                if (result == null) {
                    Log.d(TAG, "No driver RoadFlare state found on relays")
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch driver RoadFlare state", e)
                null
            }
        }
    }

    /**
     * Fetch a driver's public key_updated_at timestamp from their Kind 30012 event.
     * This does NOT require decryption - the timestamp is in a public tag.
     * Used by riders to detect stale RoadFlare keys.
     *
     * @param driverPubKey The driver's public key
     * @return The key_updated_at timestamp, or null if not found
     */
    suspend fun fetchDriverKeyUpdatedAt(driverPubKey: String): Long? {
        return withContext(Dispatchers.IO) {
            if (!relayManager.isConnected()) {
                Log.e(TAG, "fetchDriverKeyUpdatedAt: No relays connected")
                return@withContext null
            }

            Log.d(TAG, "Fetching key_updated_at for driver ${driverPubKey.take(16)}...")

            try {
                var result: Long? = null
                val eoseKeyUpdate = CompletableDeferred<String>()
                val subscriptionId = relayManager.subscribe(
                    kinds = listOf(RideshareEventKinds.ROADFLARE_DRIVER_STATE),
                    authors = listOf(driverPubKey),
                    tags = mapOf("d" to listOf(DriverRoadflareStateEvent.D_TAG)),
                    limit = 1,
                    onEose = { relayUrl -> eoseKeyUpdate.complete(relayUrl) }
                ) { event, _ ->
                    // Extract public key_updated_at tag without decryption
                    val keyUpdatedAt = DriverRoadflareStateEvent.getKeyUpdatedAt(event)
                    if (keyUpdatedAt != null) {
                        Log.d(TAG, "Found key_updated_at=${keyUpdatedAt} for driver ${driverPubKey.take(16)}")
                        result = keyUpdatedAt
                    }
                }

                // Wait for EOSE or timeout
                if (withTimeoutOrNull(5000L) { eoseKeyUpdate.await() } != null) delay(200)
                relayManager.closeSubscription(subscriptionId)

                if (result == null) {
                    Log.d(TAG, "No key_updated_at found for driver ${driverPubKey.take(16)}")
                }
                result
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch driver key_updated_at", e)
                null
            }
        }
    }

    // ==================== Location Broadcast (Kind 30014) ====================

    /**
     * Publish RoadFlare location broadcast (Kind 30014).
     * Encrypted to the driver's RoadFlare public key - only followers with the
     * shared private key can decrypt.
     *
     * @param signer The driver's identity signer
     * @param roadflarePubKey The driver's RoadFlare public key (encryption target)
     * @param location The location data to broadcast
     * @param keyVersion Current key version for rotation tracking
     * @return Event ID if successful, null on failure
     */
    suspend fun publishRoadflareLocation(
        signer: NostrSigner,
        roadflarePubKey: String,
        location: RoadflareLocation,
        keyVersion: Int
    ): String? {
        if (!relayManager.isConnected()) {
            Log.w(TAG, "publishRoadflareLocation: No relays connected")
            return null
        }

        return try {
            val event = RoadflareLocationEvent.create(signer, roadflarePubKey, location, keyVersion)
            if (event != null) {
                relayManager.publish(event)
                Log.d(TAG, "Published RoadFlare location: ${location.status}, key v$keyVersion")
                event.id
            } else {
                Log.e(TAG, "Failed to create RoadFlare location event")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish RoadFlare location", e)
            null
        }
    }

    /**
     * Subscribe to RoadFlare location broadcasts from specific drivers (Kind 30014).
     *
     * @param driverPubkeys List of driver identity pubkeys to subscribe to
     * @param onLocation Callback for received location events (raw event, caller decrypts)
     * @return Subscription ID for later closing
     */
    fun subscribeToRoadflareLocations(
        driverPubkeys: List<String>,
        onLocation: (event: Event, relayUrl: String) -> Unit
    ): String {
        Log.d(TAG, "Subscribing to RoadFlare locations from ${driverPubkeys.size} drivers")
        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.ROADFLARE_LOCATION),
            authors = driverPubkeys,
            tags = mapOf("d" to listOf(RoadflareLocationEvent.D_TAG)),
            limit = driverPubkeys.size  // One per driver (replaceable event)
        ) { event, relayUrl ->
            onLocation(event, relayUrl)
        }
    }

    // ==================== Key Share (Kind 3186) ====================

    /**
     * Publish RoadFlare key share to a follower (Kind 3186).
     * Encrypted to the follower's identity pubkey.
     * Uses short expiration (5 minutes) to reduce relay storage.
     *
     * @param signer The driver's identity signer
     * @param followerPubKey The follower's Nostr pubkey
     * @param roadflareKey The RoadFlare key to share
     * @param keyUpdatedAt Timestamp when the key was last updated/rotated
     * @return Event ID if successful, null on failure
     */
    suspend fun publishRoadflareKeyShare(
        signer: NostrSigner,
        followerPubKey: String,
        roadflareKey: RoadflareKey,
        keyUpdatedAt: Long
    ): String? {
        // Wait for relay connection
        if (!relayManager.awaitConnected(tag = "publishRoadflareKeyShare")) {
            return null
        }

        return try {
            val event = RoadflareKeyShareEvent.create(signer, followerPubKey, roadflareKey, keyUpdatedAt)
            if (event != null) {
                Log.d(TAG, "Publishing Kind 3186 to ${followerPubKey.take(8)}: eventId=${event.id.take(8)}, relays=${relayManager.connectedCount()}")
                relayManager.publish(event)
                Log.d(TAG, "Published Kind 3186 to ${followerPubKey.take(8)}: eventId=${event.id.take(8)}, v${roadflareKey.version} (expires in 5 min)")
                event.id
            } else {
                Log.e(TAG, "Failed to create Kind 3186 event for ${followerPubKey.take(8)}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish Kind 3186 to ${followerPubKey.take(8)}", e)
            null
        }
    }

    /**
     * Subscribe to RoadFlare key shares sent to us (Kind 3186).
     *
     * @param onKeyShare Callback for received key share events
     * @return Subscription ID for later closing
     */
    fun subscribeToRoadflareKeyShares(
        onKeyShare: (event: Event, relayUrl: String) -> Unit
    ): String {
        val myPubKey = keyManager.getPubKeyHex() ?: return ""
        Log.d(TAG, "Subscribing to RoadFlare key shares for ${myPubKey.take(16)}...")
        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.ROADFLARE_KEY_SHARE),
            tags = mapOf("p" to listOf(myPubKey))
        ) { event, relayUrl ->
            onKeyShare(event, relayUrl)
        }
    }

    // ==================== Key Acknowledgement (Kind 3188) ====================

    /**
     * Publish RoadFlare key acknowledgement (Kind 3188).
     * Sent by rider after receiving and storing the key from driver.
     * Uses short expiration (5 minutes) to reduce relay storage.
     *
     * @param driverPubKey The driver's pubkey (encryption target)
     * @param keyVersion The key version that was received
     * @param keyUpdatedAt The key update timestamp that was received
     * @param status The ack status: "received" (normal) or "stale" (request key refresh)
     * @return Event ID if successful, null on failure
     */
    suspend fun publishRoadflareKeyAck(
        driverPubKey: String,
        keyVersion: Int,
        keyUpdatedAt: Long,
        status: String = "received"
    ): String? {
        val signer = keyManager.getSigner() ?: run {
            Log.e(TAG, "publishRoadflareKeyAck: No signer available")
            return null
        }

        // Wait for relay connection
        if (!relayManager.awaitConnected(tag = "publishRoadflareKeyAck")) {
            return null
        }

        return try {
            val event = RoadflareKeyAckEvent.create(signer, driverPubKey, keyVersion, keyUpdatedAt, status)
            if (event != null) {
                relayManager.publish(event)
                Log.d(TAG, "Published RoadFlare key ack v$keyVersion status=$status to ${driverPubKey.take(8)}... (expires in 5 min)")
                event.id
            } else {
                Log.e(TAG, "Failed to create RoadFlare key ack event")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish RoadFlare key ack", e)
            null
        }
    }

    /**
     * Subscribe to RoadFlare key acknowledgements sent to us (Kind 3188).
     * Used by drivers to confirm followers have received the key.
     *
     * @param onKeyAck Callback for received key ack events
     * @return Subscription ID for later closing
     */
    fun subscribeToRoadflareKeyAcks(
        onKeyAck: (event: Event, relayUrl: String) -> Unit
    ): String {
        val myPubKey = keyManager.getPubKeyHex() ?: return ""
        Log.d(TAG, "Subscribing to RoadFlare key acks for ${myPubKey.take(16)}...")
        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.ROADFLARE_KEY_ACK),
            tags = mapOf("p" to listOf(myPubKey))
        ) { event, relayUrl ->
            onKeyAck(event, relayUrl)
        }
    }

    // ==================== Follower Queries ====================

    /**
     * Query for riders who follow this driver via Kind 30011 p-tags.
     *
     * Since Kind 30011 now uses public p-tags for driver pubkeys, drivers can
     * query relays to find all riders who have them in their followed list.
     *
     * @param driverPubKey The driver's pubkey (hex) to search for
     * @param onFollower Callback for each follower found (rider pubkey)
     * @return Subscription ID for later closing
     */
    fun queryRoadflareFollowers(
        driverPubKey: String,
        onFollower: (riderPubKey: String) -> Unit
    ): String {
        Log.d(TAG, "Querying for RoadFlare followers of ${driverPubKey.take(16)}...")

        // Query for Kind 30011 events that have this driver in their p-tags
        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.ROADFLARE_FOLLOWED_DRIVERS),
            tags = mapOf("p" to listOf(driverPubKey))
        ) { event, _ ->
            // The event author (pubKey) is the rider who follows this driver
            Log.d(TAG, "Found follower: ${event.pubKey.take(16)} follows ${driverPubKey.take(16)}")
            onFollower(event.pubKey)
        }
    }

    /**
     * Result of querying current followers via Kind 30011.
     * Distinguishes successful query (even if empty) from timeout/failure.
     */
    data class FollowerQueryResult(
        val followers: Set<String>,
        val success: Boolean  // true if EOSE received, false if timeout
    )

    /**
     * Query for current followers (EOSE-aware, suspending).
     * Returns a result containing rider pubkeys who currently have this driver in their Kind 30011.
     * Used for filtering stale followers during sync.
     *
     * @param driverPubKey The driver's pubkey (hex) to search for
     * @param timeoutMs Query timeout in milliseconds
     * @return FollowerQueryResult with followers set and success flag (true if EOSE received)
     */
    suspend fun queryCurrentFollowerPubkeys(
        driverPubKey: String,
        timeoutMs: Long = 3000L
    ): FollowerQueryResult = withContext(Dispatchers.IO) {
        val followers = mutableSetOf<String>()
        val eoseFollowers = CompletableDeferred<String>()

        val subscriptionId = relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.ROADFLARE_FOLLOWED_DRIVERS),
            tags = mapOf("p" to listOf(driverPubKey)),
            onEose = { relayUrl -> eoseFollowers.complete(relayUrl) }
        ) { event, _ ->
            followers.add(event.pubKey)
        }

        // Wait for EOSE or timeout
        val eoseReceived = withTimeoutOrNull(timeoutMs) { eoseFollowers.await() } != null
        if (eoseReceived) {
            delay(100)  // Brief delay for any trailing events
        }
        relayManager.closeSubscription(subscriptionId)

        if (com.ridestr.common.BuildConfig.DEBUG) {
            Log.d(TAG, "queryCurrentFollowerPubkeys: found ${followers.size} followers, success=$eoseReceived")
        }
        return@withContext FollowerQueryResult(followers, eoseReceived)
    }

    /**
     * Result of verifying a follower's status.
     */
    data class FollowerVerification(
        val stillFollowing: Boolean,
        val hasCurrentKey: Boolean?, // null if unknown (can't decrypt their event)
        val followerKeyUpdatedAt: Long?
    )

    /**
     * Verify a follower's status by checking their Kind 30011 event.
     * Returns info about whether:
     * 1. Driver is still in follower's p-tags (not unfollowed)
     * 2. Follower has the current key (by comparing keyUpdatedAt timestamps)
     *
     * @param followerPubKey The rider's pubkey (hex) to verify
     * @param driverPubKey The driver's pubkey (hex) to look for in p-tags
     * @param currentKeyUpdatedAt The driver's current key timestamp
     * @return FollowerVerification result, or null if event not found
     */
    suspend fun verifyFollowerStatus(
        followerPubKey: String,
        driverPubKey: String,
        currentKeyUpdatedAt: Long?
    ): FollowerVerification? = withContext(Dispatchers.IO) {
        val signer = keyManager.getSigner() ?: return@withContext null

        var foundEvent: Event? = null
        val eoseVerify = CompletableDeferred<String>()
        val subscriptionId = relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.ROADFLARE_FOLLOWED_DRIVERS),
            authors = listOf(followerPubKey),
            onEose = { relayUrl -> eoseVerify.complete(relayUrl) }
        ) { event, _ ->
            // Replaceable event (d-tag) - relays should return only one, keep newest
            if (foundEvent == null || event.createdAt > foundEvent!!.createdAt) {
                foundEvent = event
            }
        }

        if (withTimeoutOrNull(3000L) { eoseVerify.await() } != null) delay(200)
        relayManager.closeSubscription(subscriptionId)

        val event = foundEvent ?: return@withContext FollowerVerification(
            stillFollowing = false,
            hasCurrentKey = false,
            followerKeyUpdatedAt = null
        )

        val hasDriverInPTags = event.tags.any { tag: Array<String> ->
            tag.getOrNull(0) == "p" && tag.getOrNull(1) == driverPubKey
        }

        if (!hasDriverInPTags) {
            Log.d(TAG, "Follower ${followerPubKey.take(16)} unfollowed (driver not in p-tags)")
            return@withContext FollowerVerification(
                stillFollowing = false,
                hasCurrentKey = false,
                followerKeyUpdatedAt = null
            )
        }

        return@withContext FollowerVerification(
            stillFollowing = true,
            hasCurrentKey = null,
            followerKeyUpdatedAt = null
        )
    }

    // ==================== Follow Notifications (DEPRECATED) ====================

    /**
     * Publish a follow notification to a driver (Kind 3187).
     *
     * @deprecated Use p-tag query on Kind 30011 instead.
     * Drivers now discover followers by querying Kind 30011 events that have
     * their pubkey in p-tags, rather than receiving push notifications.
     *
     * @param driverPubKey The driver's pubkey (hex)
     * @param riderName The rider's display name
     * @param action "follow" or "unfollow"
     * @return Event ID on success, null on failure
     */
    @Deprecated("Use p-tag query on Kind 30011 instead of push notifications")
    suspend fun publishRoadflareFollowNotify(
        driverPubKey: String,
        riderName: String,
        action: String = "follow"
    ): String? {
        val signer = keyManager.getSigner() ?: run {
            Log.e(TAG, "publishRoadflareFollowNotify: No signer available")
            return null
        }

        if (relayManager.connectedCount() == 0) {
            Log.e(TAG, "publishRoadflareFollowNotify: No relays connected")
            return null
        }

        return try {
            val event = RoadflareFollowNotifyEvent.create(signer, driverPubKey, riderName, action)
            if (event != null) {
                relayManager.publish(event)
                Log.d(TAG, "Published follow notify to ${driverPubKey.take(16)}, action=$action")
                event.id
            } else {
                Log.e(TAG, "Failed to create follow notify event")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error publishing follow notify", e)
            null
        }
    }

    /**
     * Subscribe to RoadFlare follow notifications (Kind 3187).
     *
     * @deprecated Use queryRoadflareFollowers() with p-tag query instead.
     * Drivers now discover followers by querying Kind 30011 events that have
     * their pubkey in p-tags, rather than receiving push notifications.
     *
     * @param onFollowNotify Callback for received notifications
     * @return Subscription ID for later closing
     */
    @Deprecated("Use queryRoadflareFollowers() with p-tag query instead")
    fun subscribeToRoadflareFollowNotifications(
        onFollowNotify: (event: Event, relayUrl: String) -> Unit
    ): String {
        val myPubKey = keyManager.getPubKeyHex() ?: return ""
        Log.d(TAG, "Subscribing to RoadFlare follow notifications for ${myPubKey.take(16)}...")
        return relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.ROADFLARE_FOLLOW_NOTIFY),
            tags = mapOf("p" to listOf(myPubKey))
        ) { event, relayUrl ->
            onFollowNotify(event, relayUrl)
        }
    }

    // ==================== Event Cleanup ====================

    /**
     * Fetch all Kind 3187 (follow notify) events sent by the current user.
     * Used for cleanup - these events can be deleted since we now use p-tags.
     *
     * @return List of event IDs that can be deleted
     */
    suspend fun fetchOwnFollowNotifyEvents(): List<String> = withContext(Dispatchers.IO) {
        val myPubKey = keyManager.getPubKeyHex()
        if (myPubKey == null) {
            Log.e(TAG, "Cannot fetch follow notify events: Not logged in")
            return@withContext emptyList()
        }

        Log.d(TAG, "Fetching own Kind 3187 (follow notify) events...")

        val eventIds = mutableListOf<String>()
        val eoseNotify = CompletableDeferred<String>()
        val subscriptionId = relayManager.subscribe(
            kinds = listOf(RideshareEventKinds.ROADFLARE_FOLLOW_NOTIFY),
            authors = listOf(myPubKey),
            onEose = { relayUrl -> eoseNotify.complete(relayUrl) }
        ) { event, _ ->
            synchronized(eventIds) {
                eventIds.add(event.id)
            }
        }

        // Wait for EOSE or timeout
        if (withTimeoutOrNull(3000L) { eoseNotify.await() } != null) delay(200)
        relayManager.closeSubscription(subscriptionId)

        Log.d(TAG, "Found ${eventIds.size} follow notify events to potentially delete")
        eventIds
    }

    /**
     * Delete Kind 3187 (follow notify) events.
     * These are no longer needed since Kind 30011 now uses public p-tags
     * that drivers can query directly.
     *
     * @param eventIds List of event IDs to delete
     * @param deleteEvents Function to delete events (provided by NostrService)
     * @return Number of events deleted (eventIds.size if successful, 0 on failure)
     */
    suspend fun deleteFollowNotifyEvents(
        eventIds: List<String>,
        deleteEvents: suspend (List<String>, String, List<Int>?) -> String?
    ): Int {
        if (eventIds.isEmpty()) return 0

        Log.d(TAG, "Deleting ${eventIds.size} follow notify events...")
        val deleteEventId = deleteEvents(
            eventIds,
            "RoadFlare cleanup - follow notifications deprecated",
            eventIds.map { RideshareEventKinds.ROADFLARE_FOLLOW_NOTIFY }
        )
        return if (deleteEventId != null) eventIds.size else 0
    }
}
