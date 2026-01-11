package com.ridestr.app.nostr.relay

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
    val onEvent: (Event, String) -> Unit // event, relayUrl
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
