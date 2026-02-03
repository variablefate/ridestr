package com.ridestr.common.payment.cashu

/**
 * Fake implementation of MintApi for unit testing.
 *
 * Allows tests to inject specific responses (success, HTTP errors, transport failures)
 * without making real network calls.
 *
 * Usage:
 * ```kotlin
 * val fakeMintApi = FakeMintApi()
 *
 * // Configure success response
 * fakeMintApi.swapResponses.add(MintApi.Result.Success("""{"signatures":[...]}"""))
 *
 * // Configure error
 * fakeMintApi.swapResponses.add(MintApi.Result.HttpError(400, "Bad request"))
 *
 * // Configure transport failure
 * fakeMintApi.swapResponses.add(MintApi.Result.TransportFailure("Connection timeout"))
 * ```
 */
class FakeMintApi : MintApi {

    /** Queue of responses for postSwap calls. Responses are consumed in order. */
    val swapResponses = mutableListOf<MintApi.Result>()

    /** Queue of responses for postCheckState calls. Responses are consumed in order. */
    val checkStateResponses = mutableListOf<MintApi.Result>()

    /** Record of all postSwap calls for verification. */
    val swapCalls = mutableListOf<SwapCall>()

    /** Record of all postCheckState calls for verification. */
    val checkStateCalls = mutableListOf<CheckStateCall>()

    data class SwapCall(val mintUrl: String, val requestBody: String)
    data class CheckStateCall(val mintUrl: String, val requestBody: String)

    override suspend fun postSwap(mintUrl: String, requestBody: String): MintApi.Result {
        swapCalls.add(SwapCall(mintUrl, requestBody))
        return swapResponses.removeFirstOrNull()
            ?: MintApi.Result.TransportFailure("No response configured in FakeMintApi")
    }

    override suspend fun postCheckState(mintUrl: String, requestBody: String): MintApi.Result {
        checkStateCalls.add(CheckStateCall(mintUrl, requestBody))
        return checkStateResponses.removeFirstOrNull()
            ?: MintApi.Result.TransportFailure("No response configured in FakeMintApi")
    }

    /** Reset all state for reuse between tests. */
    fun reset() {
        swapResponses.clear()
        checkStateResponses.clear()
        swapCalls.clear()
        checkStateCalls.clear()
    }

    /** Configure a successful swap response with the given JSON body. */
    fun queueSwapSuccess(body: String) {
        swapResponses.add(MintApi.Result.Success(body))
    }

    /** Configure an HTTP error for the next swap call. */
    fun queueSwapHttpError(code: Int, body: String? = null) {
        swapResponses.add(MintApi.Result.HttpError(code, body))
    }

    /** Configure a transport failure for the next swap call. */
    fun queueSwapTransportFailure(cause: String = "Connection failed") {
        swapResponses.add(MintApi.Result.TransportFailure(cause))
    }

    /** Configure a successful checkstate response with the given JSON body. */
    fun queueCheckStateSuccess(body: String) {
        checkStateResponses.add(MintApi.Result.Success(body))
    }

    /** Configure an HTTP error for the next checkstate call. */
    fun queueCheckStateHttpError(code: Int, body: String? = null) {
        checkStateResponses.add(MintApi.Result.HttpError(code, body))
    }

    /** Configure a transport failure for the next checkstate call. */
    fun queueCheckStateTransportFailure(cause: String = "Connection failed") {
        checkStateResponses.add(MintApi.Result.TransportFailure(cause))
    }
}
