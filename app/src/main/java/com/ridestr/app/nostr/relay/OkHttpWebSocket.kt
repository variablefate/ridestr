package com.ridestr.app.nostr.relay

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

/**
 * OkHttp-based WebSocket wrapper for relay connections.
 *
 * Note: This is a standalone implementation not tied to Quartz's interface.
 * Full integration will be done when RelayManager is fully implemented.
 */
class OkHttpWebSocket(
    private val url: String,
    private val listener: Listener,
    private val client: OkHttpClient
) {
    interface Listener {
        fun onOpen(pingMs: Long, compression: Boolean)
        fun onMessage(text: String)
        fun onClosing(code: Int, reason: String)
        fun onClosed(code: Int, reason: String)
        fun onFailure(t: Throwable, message: String?)
    }

    private var socket: okhttp3.WebSocket? = null
    private var isConnected = false

    fun connect() {
        val request = Request.Builder()
            .url(url.trim())
            .build()

        socket = client.newWebSocket(request, OkHttpListener())
    }

    fun disconnect() {
        socket?.close(1000, "Client disconnect")
        socket = null
        isConnected = false
    }

    fun cancel() {
        socket?.cancel()
        socket = null
        isConnected = false
    }

    fun send(msg: String): Boolean {
        return socket?.send(msg) ?: false
    }

    fun isConnected(): Boolean = isConnected

    fun needsReconnect(): Boolean = !isConnected && socket == null

    private inner class OkHttpListener : WebSocketListener() {
        override fun onOpen(webSocket: okhttp3.WebSocket, response: Response) {
            isConnected = true
            val pingMs = response.receivedResponseAtMillis - response.sentRequestAtMillis
            val compression = response.headers["Sec-WebSocket-Extensions"]
                ?.contains("permessage-deflate") ?: false
            listener.onOpen(pingMs, compression)
        }

        override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
            listener.onMessage(text)
        }

        override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
            listener.onClosing(code, reason)
        }

        override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
            isConnected = false
            listener.onClosed(code, reason)
        }

        override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: Response?) {
            isConnected = false
            listener.onFailure(t, response?.message)
        }
    }

    companion object {
        /**
         * Create a default OkHttpClient configured for websocket connections.
         */
        fun createDefaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(RelayConfig.CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .readTimeout(RelayConfig.READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(RelayConfig.WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
        }
    }
}
