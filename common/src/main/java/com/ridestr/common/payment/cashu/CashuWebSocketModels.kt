package com.ridestr.common.payment.cashu

import org.json.JSONArray
import org.json.JSONObject

/**
 * NUT-17 WebSocket Subscription Models
 *
 * Implements JSON-RPC 2.0 protocol for real-time mint state updates.
 * Reference: https://github.com/cashubtc/nuts/blob/main/17.md
 */

/**
 * WebSocket connection state.
 */
enum class WebSocketState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    DISCONNECTING
}

/**
 * NUT-17 subscription kinds for different state updates.
 */
enum class SubscriptionKind(val value: String) {
    /** Subscribe to mint quote state changes (deposits) */
    BOLT11_MINT_QUOTE("bolt11_mint_quote"),

    /** Subscribe to melt quote state changes (withdrawals) */
    BOLT11_MELT_QUOTE("bolt11_melt_quote"),

    /** Subscribe to proof state changes (spent/unspent) */
    PROOF_STATE("proof_state");

    companion object {
        fun fromValue(value: String): SubscriptionKind? {
            return entries.find { it.value == value }
        }
    }
}

/**
 * Active subscription tracking.
 */
data class Subscription(
    val subId: String,
    val kind: SubscriptionKind,
    val filters: List<String>,
    val createdAt: Long = System.currentTimeMillis()
)

// ============================================================================
// JSON-RPC 2.0 Request Messages
// ============================================================================

/**
 * JSON-RPC request for subscribe/unsubscribe operations.
 *
 * Subscribe example:
 * ```json
 * {
 *   "jsonrpc": "2.0",
 *   "method": "subscribe",
 *   "params": {
 *     "kind": "bolt11_melt_quote",
 *     "subId": "uuid-here",
 *     "filters": ["quote_id_here"]
 *   },
 *   "id": 1
 * }
 * ```
 */
data class WsRequest(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: WsRequestParams,
    val id: Int
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("jsonrpc", jsonrpc)
        json.put("method", method)
        json.put("params", params.toJsonObject())
        json.put("id", id)
        return json.toString()
    }
}

/**
 * Parameters for subscribe/unsubscribe requests.
 */
data class WsRequestParams(
    val kind: String? = null,
    val subId: String,
    val filters: List<String>? = null
) {
    fun toJsonObject(): JSONObject {
        val json = JSONObject()
        kind?.let { json.put("kind", it) }
        json.put("subId", subId)
        filters?.let {
            val arr = JSONArray()
            it.forEach { filter -> arr.put(filter) }
            json.put("filters", arr)
        }
        return json
    }
}

// ============================================================================
// JSON-RPC 2.0 Response Messages
// ============================================================================

/**
 * JSON-RPC response from the mint.
 *
 * Success example:
 * ```json
 * {
 *   "jsonrpc": "2.0",
 *   "result": { "status": "OK", "subId": "uuid-here" },
 *   "id": 1
 * }
 * ```
 *
 * Error example:
 * ```json
 * {
 *   "jsonrpc": "2.0",
 *   "error": { "code": -32600, "message": "Invalid Request" },
 *   "id": 1
 * }
 * ```
 */
data class WsResponse(
    val jsonrpc: String,
    val result: WsResult? = null,
    val error: WsError? = null,
    val id: Int
) {
    val isSuccess: Boolean get() = error == null && result != null

    companion object {
        fun fromJson(json: String): WsResponse? {
            return try {
                val obj = JSONObject(json)
                WsResponse(
                    jsonrpc = obj.optString("jsonrpc", "2.0"),
                    result = obj.optJSONObject("result")?.let { WsResult.fromJson(it) },
                    error = obj.optJSONObject("error")?.let { WsError.fromJson(it) },
                    id = obj.optInt("id", -1)
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Successful response result.
 */
data class WsResult(
    val status: String,
    val subId: String
) {
    companion object {
        fun fromJson(obj: JSONObject): WsResult {
            return WsResult(
                status = obj.optString("status", ""),
                subId = obj.optString("subId", "")
            )
        }
    }
}

/**
 * JSON-RPC error object.
 * Standard codes: -32700 (parse), -32600 (invalid), -32601 (method not found)
 */
data class WsError(
    val code: Int,
    val message: String
) {
    companion object {
        fun fromJson(obj: JSONObject): WsError {
            return WsError(
                code = obj.optInt("code", 0),
                message = obj.optString("message", "Unknown error")
            )
        }
    }
}

// ============================================================================
// JSON-RPC 2.0 Notification Messages (Async Push from Mint)
// ============================================================================

/**
 * Async notification from the mint with state updates.
 *
 * Example:
 * ```json
 * {
 *   "jsonrpc": "2.0",
 *   "method": "subscribe",
 *   "params": {
 *     "subId": "uuid-here",
 *     "payload": { "quote": "...", "state": "PAID", ... }
 *   }
 * }
 * ```
 *
 * Note: Notifications have NO "id" field (per JSON-RPC 2.0 spec).
 */
data class WsNotification(
    val jsonrpc: String,
    val method: String,
    val params: WsNotificationParams
) {
    companion object {
        fun fromJson(json: String): WsNotification? {
            return try {
                val obj = JSONObject(json)
                // Notifications don't have an "id" field
                if (obj.has("id")) return null

                WsNotification(
                    jsonrpc = obj.optString("jsonrpc", "2.0"),
                    method = obj.optString("method", ""),
                    params = WsNotificationParams.fromJson(obj.getJSONObject("params"))
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

/**
 * Notification parameters containing the subscription ID and state payload.
 */
data class WsNotificationParams(
    val subId: String,
    val payload: JSONObject
) {
    companion object {
        fun fromJson(obj: JSONObject): WsNotificationParams {
            return WsNotificationParams(
                subId = obj.optString("subId", ""),
                payload = obj.optJSONObject("payload") ?: JSONObject()
            )
        }
    }
}

// ============================================================================
// Payload State Models (parsed from notification payload)
// ============================================================================
// Note: We use type aliases and converters to avoid conflicts with
// com.ridestr.common.payment.MintQuoteState and MeltQuoteState

/**
 * WebSocket mint quote state from bolt11_mint_quote subscription.
 * Maps to NUT-04 MintQuoteResponse states.
 *
 * Use toPaymentState() to convert to com.ridestr.common.payment.MintQuoteState
 */
enum class WsMintQuoteState(val value: String) {
    /** Quote created, waiting for payment */
    UNPAID("UNPAID"),

    /** Payment received, tokens can be minted */
    PAID("PAID"),

    /** Tokens have been minted (final state) */
    ISSUED("ISSUED"),

    /** Quote expired without payment */
    EXPIRED("EXPIRED");

    /**
     * Convert to com.ridestr.common.payment.MintQuoteState.
     * Note: EXPIRED maps to UNPAID (expired = never paid).
     */
    fun toPaymentState(): com.ridestr.common.payment.MintQuoteState = when (this) {
        UNPAID, EXPIRED -> com.ridestr.common.payment.MintQuoteState.UNPAID
        PAID -> com.ridestr.common.payment.MintQuoteState.PAID
        ISSUED -> com.ridestr.common.payment.MintQuoteState.ISSUED
    }

    companion object {
        fun fromValue(value: String): WsMintQuoteState? {
            return entries.find { it.value == value.uppercase() }
        }
    }
}

/**
 * WebSocket melt quote state from bolt11_melt_quote subscription.
 * Maps to NUT-05 MeltQuoteResponse states.
 *
 * Use toPaymentState() to convert to com.ridestr.common.payment.MeltQuoteState
 */
enum class WsMeltQuoteState(val value: String) {
    /** Quote created, waiting for payment execution */
    UNPAID("UNPAID"),

    /** Payment is being processed */
    PENDING("PENDING"),

    /** Payment completed successfully */
    PAID("PAID"),

    /** Payment failed */
    FAILED("FAILED");

    /**
     * Convert to com.ridestr.common.payment.MeltQuoteState.
     * Note: FAILED maps to UNPAID (payment failed = not paid).
     */
    fun toPaymentState(): com.ridestr.common.payment.MeltQuoteState = when (this) {
        UNPAID, FAILED -> com.ridestr.common.payment.MeltQuoteState.UNPAID
        PENDING -> com.ridestr.common.payment.MeltQuoteState.PENDING
        PAID -> com.ridestr.common.payment.MeltQuoteState.PAID
    }

    companion object {
        fun fromValue(value: String): WsMeltQuoteState? {
            return entries.find { it.value == value.uppercase() }
        }
    }
}

/**
 * Proof state from proof_state subscription.
 * Maps to NUT-07 CheckStateResponse states.
 */
enum class ProofStateResult(val value: String) {
    /** Proof is unspent and valid */
    UNSPENT("UNSPENT"),

    /** Proof has been spent */
    SPENT("SPENT"),

    /** Proof is pending (in-flight swap/melt) */
    PENDING("PENDING");

    companion object {
        fun fromValue(value: String): ProofStateResult? {
            return entries.find { it.value == value.uppercase() }
        }
    }
}

/**
 * Parsed mint quote payload from WebSocket notification.
 */
data class MintQuotePayload(
    val quote: String,
    val state: WsMintQuoteState,
    val expiry: Long? = null
) {
    companion object {
        fun fromJson(obj: JSONObject): MintQuotePayload? {
            val stateStr = obj.optString("state", "")
            val state = WsMintQuoteState.fromValue(stateStr) ?: return null

            return MintQuotePayload(
                quote = obj.optString("quote", ""),
                state = state,
                expiry = if (obj.has("expiry")) obj.optLong("expiry") else null
            )
        }
    }
}

/**
 * Parsed melt quote payload from WebSocket notification.
 */
data class MeltQuotePayload(
    val quote: String,
    val state: WsMeltQuoteState,
    val paid: Boolean = false,
    val paymentPreimage: String? = null
) {
    companion object {
        fun fromJson(obj: JSONObject): MeltQuotePayload? {
            val stateStr = obj.optString("state", "")
            val state = WsMeltQuoteState.fromValue(stateStr) ?: return null

            return MeltQuotePayload(
                quote = obj.optString("quote", ""),
                state = state,
                paid = obj.optBoolean("paid", false),
                paymentPreimage = if (obj.has("payment_preimage") && !obj.isNull("payment_preimage"))
                    obj.getString("payment_preimage") else null
            )
        }
    }
}

/**
 * Parsed proof state payload from WebSocket notification.
 */
data class ProofStatePayload(
    val Y: String,
    val state: ProofStateResult,
    val witness: String? = null
) {
    companion object {
        fun fromJson(obj: JSONObject): ProofStatePayload? {
            val stateStr = obj.optString("state", "")
            val state = ProofStateResult.fromValue(stateStr) ?: return null

            return ProofStatePayload(
                Y = obj.optString("Y", ""),
                state = state,
                witness = if (obj.has("witness") && !obj.isNull("witness"))
                    obj.getString("witness") else null
            )
        }
    }
}

// ============================================================================
// Message Parsing Utilities
// ============================================================================

/**
 * Determines the type of incoming WebSocket message.
 */
sealed class WsMessage {
    data class Response(val response: WsResponse) : WsMessage()
    data class Notification(val notification: WsNotification) : WsMessage()
    data class ParseError(val rawMessage: String, val error: String) : WsMessage()

    companion object {
        /**
         * Parse an incoming WebSocket message.
         * Distinguishes between responses (have "id") and notifications (no "id").
         */
        fun parse(json: String): WsMessage {
            return try {
                val obj = JSONObject(json)

                if (obj.has("id")) {
                    // This is a response to our request
                    val response = WsResponse.fromJson(json)
                    if (response != null) {
                        Response(response)
                    } else {
                        ParseError(json, "Failed to parse response")
                    }
                } else {
                    // This is an async notification
                    val notification = WsNotification.fromJson(json)
                    if (notification != null) {
                        Notification(notification)
                    } else {
                        ParseError(json, "Failed to parse notification")
                    }
                }
            } catch (e: Exception) {
                ParseError(json, e.message ?: "Unknown parse error")
            }
        }
    }
}
