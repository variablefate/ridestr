package com.ridestr.common.payment.cashu

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * NUT-17 WebSocket connection for real-time mint state updates.
 *
 * Implements JSON-RPC 2.0 protocol over WebSocket for subscription-based
 * state change notifications from Cashu mints.
 *
 * Features:
 * - Automatic reconnection with exponential backoff
 * - Subscription management and re-subscription on reconnect
 * - Request/response correlation for subscribe/unsubscribe operations
 * - Async notification handling for state updates
 *
 * Reference: https://github.com/cashubtc/nuts/blob/main/17.md
 *
 * @param mintUrl The HTTP(S) URL of the Cashu mint (e.g., "https://mint.example.com")
 * @param client Optional OkHttpClient with custom timeouts
 */
class CashuWebSocket(
    private val mintUrl: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)  // Long for streaming
        .writeTimeout(10, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)  // Keep connection alive
        .build()
) {
    companion object {
        private const val TAG = "CashuWebSocket"
        private const val MAX_RECONNECT_DELAY_MS = 60_000L
        private const val BASE_RECONNECT_DELAY_MS = 1_000L
        private const val REQUEST_TIMEOUT_MS = 10_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var socket: WebSocket? = null
    private var reconnectAttempts = 0
    private var shouldReconnect = true

    private val jsonRpcId = AtomicInteger(1)
    private val subscriptions = ConcurrentHashMap<String, Subscription>()
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<WsResponse>>()

    private val _connectionState = MutableStateFlow(WebSocketState.DISCONNECTED)
    val connectionState: StateFlow<WebSocketState> = _connectionState.asStateFlow()

    // ============================================================================
    // Notification Callbacks
    // ============================================================================

    /**
     * Called when a mint quote (deposit) state changes.
     * @param quoteId The quote ID that changed
     * @param payload The parsed state payload
     */
    var onMintQuoteUpdate: ((quoteId: String, payload: MintQuotePayload) -> Unit)? = null

    /**
     * Called when a melt quote (withdrawal) state changes.
     * @param quoteId The quote ID that changed
     * @param payload The parsed state payload
     */
    var onMeltQuoteUpdate: ((quoteId: String, payload: MeltQuotePayload) -> Unit)? = null

    /**
     * Called when a proof state changes.
     * @param Y The proof Y value (public key point)
     * @param payload The parsed state payload
     */
    var onProofStateUpdate: ((Y: String, payload: ProofStatePayload) -> Unit)? = null

    /**
     * Called when connection state changes (for UI binding).
     */
    var onConnectionStateChanged: ((WebSocketState) -> Unit)? = null

    // ============================================================================
    // Connection Management
    // ============================================================================

    /**
     * Connect to the mint's WebSocket endpoint.
     *
     * @return true if connection was initiated, false if already connecting/connected
     */
    fun connect(): Boolean {
        val currentState = _connectionState.value
        if (currentState == WebSocketState.CONNECTING ||
            currentState == WebSocketState.CONNECTED) {
            Log.d(TAG, "Already ${currentState.name.lowercase()}, skipping connect")
            return false
        }

        shouldReconnect = true
        updateState(WebSocketState.CONNECTING)

        val wsUrl = getWebSocketUrl()
        Log.d(TAG, "Connecting to $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        socket = client.newWebSocket(request, CashuWebSocketListener())
        return true
    }

    /**
     * Disconnect from the mint's WebSocket endpoint.
     * Clears all subscriptions and pending requests.
     */
    fun disconnect() {
        shouldReconnect = false
        updateState(WebSocketState.DISCONNECTING)

        // Cancel all pending requests
        pendingRequests.forEach { (_, deferred) ->
            deferred.cancel()
        }
        pendingRequests.clear()

        socket?.close(1000, "Client disconnect")
        socket = null

        updateState(WebSocketState.DISCONNECTED)
        Log.d(TAG, "Disconnected from ${getWebSocketUrl()}")
    }

    /**
     * Check if WebSocket is currently connected.
     */
    fun isConnected(): Boolean = _connectionState.value == WebSocketState.CONNECTED

    /**
     * Get the WebSocket URL from the mint URL.
     * Converts http(s) to ws(s) and appends /v1/ws path.
     */
    private fun getWebSocketUrl(): String {
        return mintUrl.trimEnd('/')
            .replace("http://", "ws://")
            .replace("https://", "wss://") + "/v1/ws"
    }

    private fun updateState(newState: WebSocketState) {
        _connectionState.value = newState
        onConnectionStateChanged?.invoke(newState)
    }

    // ============================================================================
    // Subscription Management
    // ============================================================================

    /**
     * Subscribe to state changes for the given filters.
     *
     * Per NUT-17, the mint will immediately respond with the current state
     * of subscribed objects, then send notifications for any changes.
     *
     * @param kind The type of subscription (mint quote, melt quote, proof state)
     * @param filters List of IDs to subscribe to (quote IDs or proof Y values)
     * @return The subscription ID if successful, null on failure
     */
    suspend fun subscribe(kind: SubscriptionKind, filters: List<String>): String? {
        if (!isConnected()) {
            Log.w(TAG, "Cannot subscribe: not connected")
            return null
        }

        val subId = UUID.randomUUID().toString()
        val requestId = jsonRpcId.getAndIncrement()

        val request = WsRequest(
            method = "subscribe",
            params = WsRequestParams(
                kind = kind.value,
                subId = subId,
                filters = filters
            ),
            id = requestId
        )

        val response = sendAndWaitForResponse(request)
        if (response == null) {
            Log.e(TAG, "Subscribe timeout for ${kind.value}")
            return null
        }

        if (!response.isSuccess) {
            Log.e(TAG, "Subscribe failed: ${response.error?.message} (code ${response.error?.code})")
            return null
        }

        // Track the subscription
        val subscription = Subscription(
            subId = subId,
            kind = kind,
            filters = filters
        )
        subscriptions[subId] = subscription

        Log.d(TAG, "Subscribed to ${kind.value} with ${filters.size} filters, subId=$subId")
        return subId
    }

    /**
     * Unsubscribe from a previous subscription.
     *
     * @param subId The subscription ID returned from subscribe()
     * @return true if unsubscribe succeeded, false otherwise
     */
    suspend fun unsubscribe(subId: String): Boolean {
        if (!isConnected()) {
            // Just remove from local tracking if disconnected
            subscriptions.remove(subId)
            return true
        }

        val requestId = jsonRpcId.getAndIncrement()

        val request = WsRequest(
            method = "unsubscribe",
            params = WsRequestParams(subId = subId),
            id = requestId
        )

        val response = sendAndWaitForResponse(request)
        subscriptions.remove(subId)

        if (response == null) {
            Log.w(TAG, "Unsubscribe timeout for subId=$subId")
            return false
        }

        if (!response.isSuccess) {
            Log.w(TAG, "Unsubscribe failed: ${response.error?.message}")
            return false
        }

        Log.d(TAG, "Unsubscribed from subId=$subId")
        return true
    }

    /**
     * Get all active subscription IDs.
     */
    fun getActiveSubscriptions(): List<String> = subscriptions.keys().toList()

    /**
     * Clear all subscriptions (local only, doesn't notify mint).
     */
    fun clearSubscriptions() {
        subscriptions.clear()
    }

    // ============================================================================
    // Request/Response Handling
    // ============================================================================

    private suspend fun sendAndWaitForResponse(request: WsRequest): WsResponse? {
        val deferred = CompletableDeferred<WsResponse>()
        pendingRequests[request.id] = deferred

        val json = request.toJson()
        val sent = socket?.send(json) ?: false

        if (!sent) {
            pendingRequests.remove(request.id)
            Log.e(TAG, "Failed to send request: ${request.method}")
            return null
        }

        Log.d(TAG, "Sent: $json")

        return try {
            withTimeoutOrNull(REQUEST_TIMEOUT_MS) {
                deferred.await()
            }
        } finally {
            pendingRequests.remove(request.id)
        }
    }

    // ============================================================================
    // Message Handling
    // ============================================================================

    private fun handleMessage(text: String) {
        Log.d(TAG, "Received: $text")

        when (val message = WsMessage.parse(text)) {
            is WsMessage.Response -> handleResponse(message.response)
            is WsMessage.Notification -> handleNotification(message.notification)
            is WsMessage.ParseError -> {
                Log.e(TAG, "Failed to parse message: ${message.error}")
            }
        }
    }

    private fun handleResponse(response: WsResponse) {
        val deferred = pendingRequests.remove(response.id)
        if (deferred != null) {
            deferred.complete(response)
        } else {
            Log.w(TAG, "Received response for unknown request id=${response.id}")
        }
    }

    private fun handleNotification(notification: WsNotification) {
        val subId = notification.params.subId
        val subscription = subscriptions[subId]

        if (subscription == null) {
            Log.w(TAG, "Received notification for unknown subscription: $subId")
            return
        }

        val payload = notification.params.payload

        when (subscription.kind) {
            SubscriptionKind.BOLT11_MINT_QUOTE -> {
                val parsed = MintQuotePayload.fromJson(payload)
                if (parsed != null) {
                    Log.d(TAG, "Mint quote update: ${parsed.quote} -> ${parsed.state}")
                    onMintQuoteUpdate?.invoke(parsed.quote, parsed)
                } else {
                    Log.w(TAG, "Failed to parse mint quote payload")
                }
            }

            SubscriptionKind.BOLT11_MELT_QUOTE -> {
                val parsed = MeltQuotePayload.fromJson(payload)
                if (parsed != null) {
                    Log.d(TAG, "Melt quote update: ${parsed.quote} -> ${parsed.state}")
                    onMeltQuoteUpdate?.invoke(parsed.quote, parsed)
                } else {
                    Log.w(TAG, "Failed to parse melt quote payload")
                }
            }

            SubscriptionKind.PROOF_STATE -> {
                val parsed = ProofStatePayload.fromJson(payload)
                if (parsed != null) {
                    Log.d(TAG, "Proof state update: ${parsed.Y.take(16)}... -> ${parsed.state}")
                    onProofStateUpdate?.invoke(parsed.Y, parsed)
                } else {
                    Log.w(TAG, "Failed to parse proof state payload")
                }
            }
        }
    }

    // ============================================================================
    // Reconnection Logic
    // ============================================================================

    private fun scheduleReconnect() {
        if (!shouldReconnect) return

        reconnectAttempts++
        val delay = minOf(
            BASE_RECONNECT_DELAY_MS * reconnectAttempts,
            MAX_RECONNECT_DELAY_MS
        )

        Log.d(TAG, "Scheduling reconnect in ${delay}ms (attempt $reconnectAttempts)")

        scope.launch {
            delay(delay)
            if (shouldReconnect && _connectionState.value == WebSocketState.DISCONNECTED) {
                connect()
            }
        }
    }

    private fun resubscribeAll() {
        if (subscriptions.isEmpty()) return

        Log.d(TAG, "Resubscribing to ${subscriptions.size} subscriptions")

        scope.launch {
            subscriptions.values.toList().forEach { subscription ->
                val requestId = jsonRpcId.getAndIncrement()

                val request = WsRequest(
                    method = "subscribe",
                    params = WsRequestParams(
                        kind = subscription.kind.value,
                        subId = subscription.subId,
                        filters = subscription.filters
                    ),
                    id = requestId
                )

                val json = request.toJson()
                val sent = socket?.send(json) ?: false

                if (sent) {
                    Log.d(TAG, "Resubscribed: ${subscription.kind.value} (${subscription.subId})")
                } else {
                    Log.e(TAG, "Failed to resubscribe: ${subscription.subId}")
                }
            }
        }
    }

    // ============================================================================
    // WebSocket Listener
    // ============================================================================

    private inner class CashuWebSocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "Connected to ${getWebSocketUrl()}")
            updateState(WebSocketState.CONNECTED)
            reconnectAttempts = 0

            // Resubscribe to any active subscriptions
            resubscribeAll()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "Closing: $code $reason")
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "Closed: $code $reason")
            socket = null
            updateState(WebSocketState.DISCONNECTED)

            // Cancel pending requests
            pendingRequests.forEach { (_, deferred) ->
                deferred.cancel()
            }
            pendingRequests.clear()

            scheduleReconnect()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Connection failed: ${t.message}")
            socket = null
            updateState(WebSocketState.DISCONNECTED)

            // Cancel pending requests
            pendingRequests.forEach { (_, deferred) ->
                deferred.cancel()
            }
            pendingRequests.clear()

            scheduleReconnect()
        }
    }
}
