package com.ridestr.common.nostr.relay

import android.util.Log
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Relay connection state.
 */
enum class RelayConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

/**
 * Subscription info for tracking active subscriptions.
 */
data class Subscription(
    val id: String,
    val filters: List<Map<String, Any>>,
    val onEvent: (Event, String) -> Unit, // event, relayUrl
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Manages connections to multiple Nostr relays.
 * Handles subscriptions, event publishing, and reconnection.
 */
class RelayManager(
    relayUrls: List<String> = RelayConfig.DEFAULT_RELAYS
) {
    companion object {
        private const val TAG = "RelayManager"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val client = OkHttpClient.Builder()
        .connectTimeout(RelayConfig.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(RelayConfig.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(RelayConfig.WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val connections = ConcurrentHashMap<String, RelayConnection>()
    private val subscriptions = ConcurrentHashMap<String, Subscription>()

    private val _connectionStates = MutableStateFlow<Map<String, RelayConnectionState>>(emptyMap())
    val connectionStates: StateFlow<Map<String, RelayConnectionState>> = _connectionStates.asStateFlow()

    private val _events = MutableStateFlow<List<Pair<Event, String>>>(emptyList()) // event, relayUrl
    val events: StateFlow<List<Pair<Event, String>>> = _events.asStateFlow()

    private val _notices = MutableStateFlow<List<Pair<String, String>>>(emptyList()) // message, relayUrl
    val notices: StateFlow<List<Pair<String, String>>> = _notices.asStateFlow()

    init {
        // Initialize connections for default relays
        relayUrls.forEach { url ->
            addRelay(url)
        }
    }

    /**
     * Add a relay to the pool.
     */
    fun addRelay(url: String) {
        if (connections.containsKey(url)) return

        val connection = RelayConnection(
            url = url,
            client = client,
            onEvent = { event, subId, relayUrl -> handleEvent(event, subId, relayUrl) },
            onEose = { subId, relayUrl -> handleEose(subId, relayUrl) },
            onOk = { eventId, success, message, relayUrl -> handleOk(eventId, success, message, relayUrl) },
            onNotice = { message, relayUrl -> handleNotice(message, relayUrl) }
        )

        connections[url] = connection

        // Watch connection state changes
        scope.launch {
            connection.state.collect { state ->
                updateConnectionStates()
            }
        }

        updateConnectionStates()
        Log.d(TAG, "Added relay: $url")
    }

    /**
     * Remove a relay from the pool.
     */
    fun removeRelay(url: String) {
        connections.remove(url)?.disconnect()
        updateConnectionStates()
        Log.d(TAG, "Removed relay: $url")
    }

    /**
     * Connect to all configured relays.
     */
    fun connectAll() {
        Log.d(TAG, "Connecting to ${connections.size} relays")
        connections.values.forEach { it.connect() }
    }

    /**
     * Disconnect from all relays.
     */
    fun disconnectAll() {
        Log.d(TAG, "Disconnecting from all relays")
        connections.values.forEach { it.disconnect() }
    }

    /**
     * Publish an event to all connected relays.
     */
    fun publish(event: Event) {
        Log.d(TAG, "Publishing event ${event.id} (kind ${event.kind}) to ${connections.size} relays")
        connections.values.forEach { it.publish(event) }
    }

    /**
     * Subscribe to events matching the given filter criteria.
     * @return Subscription ID for closing later
     */
    fun subscribe(
        kinds: List<Int>? = null,
        authors: List<String>? = null,
        tags: Map<String, List<String>>? = null,
        since: Long? = null,
        until: Long? = null,
        limit: Int? = null,
        onEvent: (Event, String) -> Unit
    ): String {
        val subId = generateSubscriptionId()

        // Build filter map
        val filter = mutableMapOf<String, Any>()
        kinds?.let { filter["kinds"] = it }
        authors?.let { filter["authors"] = it }
        tags?.forEach { (key, values) ->
            filter["#$key"] = values
        }
        since?.let { filter["since"] = it }
        until?.let { filter["until"] = it }
        limit?.let { filter["limit"] = it }

        val subscription = Subscription(
            id = subId,
            filters = listOf(filter),
            onEvent = onEvent
        )

        subscriptions[subId] = subscription

        // Send to all connected relays
        connections.values.forEach { connection ->
            connection.subscribe(subId, listOf(filter))
        }

        Log.d(TAG, "Created subscription $subId with filter: $filter")
        return subId
    }

    /**
     * Close a subscription.
     */
    fun closeSubscription(subscriptionId: String) {
        subscriptions.remove(subscriptionId)
        connections.values.forEach { it.closeSubscription(subscriptionId) }
        Log.d(TAG, "Closed subscription $subscriptionId")
    }

    /**
     * Check if connected to at least one relay.
     */
    fun isConnected(): Boolean {
        return connections.values.any { it.state.value == RelayConnectionState.CONNECTED }
    }

    /**
     * Get number of connected relays.
     */
    fun connectedCount(): Int {
        return connections.values.count { it.state.value == RelayConnectionState.CONNECTED }
    }

    /**
     * Ensure all relays are connected. Reconnects any disconnected relays,
     * clears stale subscriptions, and resends active subscriptions.
     * Call this when the app returns to foreground.
     *
     * @param maxSubscriptionAgeMs Maximum age for subscriptions before they're considered stale
     *                              (default: 30 minutes). Set to 0 to disable cleanup.
     */
    fun ensureConnected(maxSubscriptionAgeMs: Long = 30 * 60 * 1000L) {
        // First, clean up stale subscriptions
        if (maxSubscriptionAgeMs > 0) {
            cleanupStaleSubscriptions(maxSubscriptionAgeMs)
        }

        var reconnectedCount = 0

        connections.forEach { (url, connection) ->
            val state = connection.state.value
            if (state == RelayConnectionState.DISCONNECTED) {
                Log.d(TAG, "Reconnecting to $url")
                connection.connect()
                reconnectedCount++

                // Resend existing subscriptions to this relay once connected
                scope.launch {
                    // Wait a moment for connection to establish
                    kotlinx.coroutines.delay(500)
                    if (connection.state.value == RelayConnectionState.CONNECTED) {
                        subscriptions.values.forEach { subscription ->
                            connection.subscribe(subscription.id, subscription.filters)
                            Log.d(TAG, "Resent subscription ${subscription.id} to $url")
                        }
                    }
                }
            }
        }

        if (reconnectedCount > 0) {
            Log.d(TAG, "Reconnecting to $reconnectedCount relay(s)")
        } else {
            Log.d(TAG, "All relays already connected")
        }
    }

    /**
     * Rideshare event kinds per NIP-014173.
     * Kind numbers use "173" base to avoid conflicts with other NIPs.
     * Kinds span multiple ranges:
     * - 30173: Driver Availability (parameterized replaceable)
     * - 3173-3175: Ride Offer, Acceptance, Confirmation (regular)
     * - 3176-3178: PIN Submission, Verification, Chat (regular)
     * - 3179: Ride Cancellation (regular)
     * - 3180: Driver Status (regular - changed from ephemeral 20173)
     * - 30174: Ride History Backup (parameterized replaceable)
     */
    private val RIDESHARE_KINDS = setOf(
        30173,                    // Driver Availability
        3173, 3174, 3175,         // Ride flow
        3176, 3177, 3178,         // PIN & Chat
        3179,                     // Ride Cancellation
        3180,                     // Driver Status (regular)
        30174                     // Ride History Backup
    )

    /**
     * Check if a subscription is for rideshare events based on the kinds filter.
     */
    private fun isRideshareSubscription(subscription: Subscription): Boolean {
        return subscription.filters.any { filter ->
            val kinds = filter["kinds"] as? List<*>
            kinds?.any { kind ->
                (kind as? Int)?.let { it in RIDESHARE_KINDS } == true
            } == true
        }
    }

    /**
     * Remove stale rideshare subscriptions older than the specified age.
     * Only cleans up subscriptions for rideshare event kinds.
     * This preserves social/profile subscriptions from other Nostr functionality.
     */
    private fun cleanupStaleSubscriptions(maxAgeMs: Long) {
        val now = System.currentTimeMillis()
        val staleIds = mutableListOf<String>()

        subscriptions.forEach { (id, subscription) ->
            // Only clean up rideshare subscriptions (kinds 3000-3004)
            if (isRideshareSubscription(subscription)) {
                val age = now - subscription.createdAt
                if (age > maxAgeMs) {
                    staleIds.add(id)
                }
            }
        }

        if (staleIds.isNotEmpty()) {
            Log.d(TAG, "Cleaning up ${staleIds.size} stale rideshare subscription(s)")
            staleIds.forEach { id ->
                subscriptions.remove(id)
                // Also close on relays in case they're still connected
                connections.values.forEach { it.closeSubscription(id) }
            }
        }
    }

    /**
     * Clear all subscriptions. Use with caution - call this when you want
     * to reset subscription state completely.
     */
    fun clearAllSubscriptions() {
        Log.d(TAG, "Clearing all ${subscriptions.size} subscription(s)")
        subscriptions.keys.toList().forEach { id ->
            closeSubscription(id)
        }
    }

    /**
     * Get all relay URLs.
     */
    fun getRelayUrls(): List<String> = connections.keys.toList()

    private fun handleEvent(event: Event, subscriptionId: String, relayUrl: String) {
        Log.d(TAG, "Received event ${event.id} (kind ${event.kind}) from $relayUrl")

        // Add to events list for debug UI
        scope.launch(Dispatchers.Main) {
            val current = _events.value.toMutableList()
            current.add(0, event to relayUrl)
            if (current.size > 100) {
                _events.value = current.take(100)
            } else {
                _events.value = current
            }
        }

        // Dispatch to subscription callback
        subscriptions[subscriptionId]?.onEvent?.invoke(event, relayUrl)
    }

    private fun handleEose(subscriptionId: String, relayUrl: String) {
        Log.d(TAG, "EOSE for subscription $subscriptionId from $relayUrl")
    }

    private fun handleOk(eventId: String, success: Boolean, message: String, relayUrl: String) {
        if (success) {
            Log.d(TAG, "Event $eventId accepted by $relayUrl")
        } else {
            Log.w(TAG, "Event $eventId rejected by $relayUrl: $message")
        }
    }

    private fun handleNotice(message: String, relayUrl: String) {
        Log.d(TAG, "Notice from $relayUrl: $message")

        scope.launch(Dispatchers.Main) {
            val current = _notices.value.toMutableList()
            current.add(0, message to relayUrl)
            if (current.size > 50) {
                _notices.value = current.take(50)
            } else {
                _notices.value = current
            }
        }
    }

    private fun updateConnectionStates() {
        val states = connections.mapValues { it.value.state.value }
        _connectionStates.value = states
    }

    private fun generateSubscriptionId(): String {
        return java.util.UUID.randomUUID().toString().take(8)
    }
}
