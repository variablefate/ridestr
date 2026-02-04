package com.ridestr.common.payment.cashu

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Production implementation of MintApi using OkHttpClient.
 *
 * Extracts HTTP transport logic from CashuBackend for:
 * - POST /v1/swap
 * - POST /v1/checkstate
 *
 * Provides typed results that distinguish transport failures from HTTP errors,
 * enabling proper error wiring in CashuBackend (MintUnreachable vs SwapRejected/MintRejected).
 */
class OkHttpMintApi(private val client: OkHttpClient) : MintApi {

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    override suspend fun postSwap(mintUrl: String, requestBody: String): MintApi.Result =
        withContext(Dispatchers.IO) {
            executePost("$mintUrl/v1/swap", requestBody)
        }

    override suspend fun postCheckState(mintUrl: String, requestBody: String): MintApi.Result =
        withContext(Dispatchers.IO) {
            executePost("$mintUrl/v1/checkstate", requestBody)
        }

    /**
     * Execute a POST request and return a typed result.
     */
    private fun executePost(url: String, body: String): MintApi.Result {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (response.isSuccessful) {
                MintApi.Result.Success(responseBody ?: "")
            } else {
                MintApi.Result.HttpError(response.code, responseBody)
            }
        } catch (e: IOException) {
            MintApi.Result.TransportFailure(e.message ?: "IO error")
        }
    }
}
