package com.ridestr.common.nostr.relay

import android.util.Log
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages a single WebSocket connection to a Nostr relay.
 */
class RelayConnection(
    val url: String,
    private val client: OkHttpClient,
    private val onEvent: (Event, String, String) -> Unit, // event, subscriptionId, relayUrl
    private val onEose: (String, String) -> Unit, // subscriptionId, relayUrl
    private val onOk: (String, Boolean, String, String) -> Unit, // eventId, success, message, relayUrl
    private val onNotice: (String, String) -> Unit // message, relayUrl
) {
    companion object {
        private const val TAG = "RelayConnection"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Thread-safe state - all mutations synchronized on `this`
    private var socket: WebSocket? = null
    private var connectionGeneration = 0L  // Incremented on each connect, for message validation
    private val reconnectAttempts = AtomicInteger(0)
    private val shouldReconnect = AtomicBoolean(true)

    // Bounded channel for ordered message processing (prevents memory growth under bursty traffic)
    // Pair contains (generation, message) for stale message filtering
    private val messageChannel = Channel<Pair<Long, String>>(capacity = 256)

    init {
        // Single consumer ensures messages are processed in order
        scope.launch {
            messageChannel.consumeEach { (generation, text) ->
                // Revalidate generation before processing (defensive against race in trySend)
                val currentGen = synchronized(this@RelayConnection) { connectionGeneration }
                if (generation == currentGen) {
                    handleMessage(text)
                } else {
                    Log.d(TAG, "[$url] Dropping message from stale connection (gen $generation, current $currentGen)")
                }
            }
        }
    }

    private val _state = MutableStateFlow(RelayConnectionState.DISCONNECTED)
    val state: StateFlow<RelayConnectionState> = _state.asStateFlow()

    private val activeSubscriptions = ConcurrentHashMap<String, String>() // subId -> filterJson
    private val pendingEvents = ConcurrentHashMap<String, Event>() // eventId -> event

    /**
     * Connect to the relay.
     */
    fun connect() {
        shouldReconnect.set(true)

        val request = Request.Builder()
            .url(url)
            .build()

        // Single lock for state + socket coherence
        synchronized(this) {
            if (_state.value == RelayConnectionState.CONNECTING ||
                _state.value == RelayConnectionState.CONNECTED) {
                return
            }
            _state.value = RelayConnectionState.CONNECTING
            connectionGeneration++  // Invalidate any queued messages from previous connection
            socket = client.newWebSocket(request, RelayWebSocketListener())
        }

        Log.d(TAG, "Connecting to $url (generation $connectionGeneration)")
    }

    /**
     * Disconnect from the relay.
     */
    fun disconnect() {
        shouldReconnect.set(false)

        val socketToClose: WebSocket?
        synchronized(this) {
            _state.value = RelayConnectionState.DISCONNECTING
            socketToClose = socket
            socket = null
            _state.value = RelayConnectionState.DISCONNECTED
        }

        // Close outside lock to avoid holding lock during network I/O
        socketToClose?.close(1000, "Client disconnect")
        Log.d(TAG, "Disconnected from $url")
    }

    /**
     * Send a subscription request to the relay.
     */
    fun subscribe(subscriptionId: String, filters: List<Map<String, Any>>) {
        val filterArray = JSONArray()
        filters.forEach { filter ->
            filterArray.put(JSONObject(filter))
        }

        val message = JSONArray().apply {
            put("REQ")
            put(subscriptionId)
            filters.forEach { filter ->
                put(JSONObject(filter))
            }
        }

        val json = message.toString()
        activeSubscriptions[subscriptionId] = json

        if (_state.value == RelayConnectionState.CONNECTED) {
            send(json)
            Log.d(TAG, "[$url] Sent subscription $subscriptionId")
        }
    }

    /**
     * Close a subscription.
     */
    fun closeSubscription(subscriptionId: String) {
        activeSubscriptions.remove(subscriptionId)

        if (_state.value == RelayConnectionState.CONNECTED) {
            val message = JSONArray().apply {
                put("CLOSE")
                put(subscriptionId)
            }
            send(message.toString())
            Log.d(TAG, "[$url] Closed subscription $subscriptionId")
        }
    }

    /**
     * Publish an event to the relay.
     */
    fun publish(event: Event) {
        pendingEvents[event.id] = event

        val message = JSONArray().apply {
            put("EVENT")
            put(JSONObject(event.toJson()))
        }

        if (_state.value == RelayConnectionState.CONNECTED) {
            send(message.toString())
            Log.d(TAG, "[$url] Published event ${event.id}")
        }
    }

    private fun send(message: String): Boolean {
        val s = synchronized(this) {
            // Only send if connected (reduces 'send on closing socket' noise)
            if (_state.value != RelayConnectionState.CONNECTED) return false
            socket
        }
        return s?.send(message) ?: false
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONArray(text)
            val type = json.getString(0)

            when (type) {
                "EVENT" -> {
                    val subscriptionId = json.getString(1)
                    val eventJson = json.getJSONObject(2)
                    val event = Event.fromJson(eventJson.toString())

                    // Verify signature before processing (NIP-01 compliance)
                    if (!event.verify()) {
                        Log.w(TAG, "[$url] Rejecting event ${event.id.take(8)} - invalid signature from ${event.pubKey.take(8)}")
                        return
                    }

                    onEvent(event, subscriptionId, url)
                }
                "EOSE" -> {
                    val subscriptionId = json.getString(1)
                    onEose(subscriptionId, url)
                }
                "OK" -> {
                    val eventId = json.getString(1)
                    val success = json.getBoolean(2)
                    val message = json.optString(3, "")
                    pendingEvents.remove(eventId)
                    onOk(eventId, success, message, url)
                }
                "NOTICE" -> {
                    val message = json.getString(1)
                    onNotice(message, url)
                }
                "CLOSED" -> {
                    val subscriptionId = json.getString(1)
                    val reason = json.optString(2, "")
                    Log.d(TAG, "[$url] Subscription $subscriptionId closed by relay: $reason")
                    activeSubscriptions.remove(subscriptionId)
                }
                else -> {
                    Log.d(TAG, "[$url] Unknown message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$url] Failed to parse message: $text", e)
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect.get()) return

        val attempts = reconnectAttempts.incrementAndGet()
        val delayMs = minOf(
            RelayConfig.RECONNECT_DELAY_MS * attempts,
            60_000L // Max 60 seconds
        )

        Log.d(TAG, "[$url] Scheduling reconnect in ${delayMs}ms (attempt $attempts)")

        scope.launch {
            delay(delayMs)
            if (shouldReconnect.get() && _state.value == RelayConnectionState.DISCONNECTED) {
                connect()
            }
        }
    }

    private fun resubscribeAll() {
        activeSubscriptions.forEach { (subId, json) ->
            send(json)
            Log.d(TAG, "[$url] Resubscribed $subId")
        }
    }

    private fun republishPending() {
        pendingEvents.values.forEach { event ->
            val message = JSONArray().apply {
                put("EVENT")
                put(JSONObject(event.toJson()))
            }
            send(message.toString())
            Log.d(TAG, "[$url] Republished event ${event.id}")
        }
    }

    private inner class RelayWebSocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@RelayConnection) {
                // Guard: Ignore callbacks from stale sockets after reconnect
                if (socket !== webSocket) {
                    Log.d(TAG, "[$url] Ignoring onOpen from stale socket")
                    return
                }

                Log.d(TAG, "[$url] Connected (generation $connectionGeneration)")
                _state.value = RelayConnectionState.CONNECTED
            }
            reconnectAttempts.set(0)

            // Resubscribe to active subscriptions
            resubscribeAll()
            // Republish pending events
            republishPending()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val generation: Long
            synchronized(this@RelayConnection) {
                // Guard: Ignore messages from stale sockets
                if (socket !== webSocket) return
                generation = connectionGeneration
            }

            // Send to bounded channel for ordered processing (non-blocking)
            val sent = messageChannel.trySend(generation to text)
            if (!sent.isSuccess) {
                Log.w(TAG, "[$url] Message channel full, dropping message (generation $generation)")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "[$url] Closing: $code $reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            // Early exit if disconnect was intentional (prevents reconnect scheduling)
            if (!shouldReconnect.get()) {
                Log.d(TAG, "[$url] Closed after intentional disconnect, not reconnecting")
                return
            }

            synchronized(this@RelayConnection) {
                // Guard: Ignore stale callbacks after rapid disconnect/reconnect
                if (socket !== webSocket) {
                    Log.d(TAG, "[$url] Ignoring onClosed from stale socket")
                    return
                }

                Log.d(TAG, "[$url] Closed: $code $reason")
                _state.value = RelayConnectionState.DISCONNECTED
                socket = null
            }
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            // Early exit if disconnect was intentional (prevents reconnect scheduling)
            if (!shouldReconnect.get()) {
                Log.d(TAG, "[$url] Failed after intentional disconnect, not reconnecting")
                return
            }

            synchronized(this@RelayConnection) {
                // Guard: Ignore stale callbacks after rapid disconnect/reconnect
                if (socket !== webSocket) {
                    Log.d(TAG, "[$url] Ignoring onFailure from stale socket")
                    return
                }

                Log.e(TAG, "[$url] Connection failed: ${t.message}")
                _state.value = RelayConnectionState.DISCONNECTED
                socket = null
            }
            scheduleReconnect()
        }
    }
}
