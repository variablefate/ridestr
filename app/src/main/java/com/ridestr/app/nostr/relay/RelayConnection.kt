package com.ridestr.app.nostr.relay

import android.util.Log
import com.vitorpamplona.quartz.nip01Core.core.Event
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
import java.util.concurrent.ConcurrentHashMap

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

    private var socket: WebSocket? = null
    private var reconnectAttempts = 0
    private var shouldReconnect = true

    private val _state = MutableStateFlow(RelayConnectionState.DISCONNECTED)
    val state: StateFlow<RelayConnectionState> = _state.asStateFlow()

    private val activeSubscriptions = ConcurrentHashMap<String, String>() // subId -> filterJson
    private val pendingEvents = ConcurrentHashMap<String, Event>() // eventId -> event

    /**
     * Connect to the relay.
     */
    fun connect() {
        if (_state.value == RelayConnectionState.CONNECTING ||
            _state.value == RelayConnectionState.CONNECTED) {
            return
        }

        shouldReconnect = true
        _state.value = RelayConnectionState.CONNECTING
        Log.d(TAG, "Connecting to $url")

        val request = Request.Builder()
            .url(url)
            .build()

        socket = client.newWebSocket(request, RelayWebSocketListener())
    }

    /**
     * Disconnect from the relay.
     */
    fun disconnect() {
        shouldReconnect = false
        _state.value = RelayConnectionState.DISCONNECTING
        socket?.close(1000, "Client disconnect")
        socket = null
        _state.value = RelayConnectionState.DISCONNECTED
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
        return socket?.send(message) ?: false
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
        if (!shouldReconnect) return

        reconnectAttempts++
        val delay = minOf(
            RelayConfig.RECONNECT_DELAY_MS * reconnectAttempts,
            60_000L // Max 60 seconds
        )

        Log.d(TAG, "[$url] Scheduling reconnect in ${delay}ms (attempt $reconnectAttempts)")

        scope.launch {
            delay(delay)
            if (shouldReconnect && _state.value == RelayConnectionState.DISCONNECTED) {
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
            Log.d(TAG, "[$url] Connected")
            _state.value = RelayConnectionState.CONNECTED
            reconnectAttempts = 0

            // Resubscribe to active subscriptions
            resubscribeAll()
            // Republish pending events
            republishPending()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "[$url] Closing: $code $reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "[$url] Closed: $code $reason")
            _state.value = RelayConnectionState.DISCONNECTED
            socket = null
            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "[$url] Connection failed: ${t.message}")
            _state.value = RelayConnectionState.DISCONNECTED
            socket = null
            scheduleReconnect()
        }
    }
}
