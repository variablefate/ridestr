package com.ridestr.common.bitcoin

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Service to fetch and cache Bitcoin price from UTXOracle.
 * Refreshes every 5 minutes automatically.
 */
class BitcoinPriceService {
    companion object {
        private const val TAG = "BitcoinPriceService"
        private const val API_URL = "https://api.utxoracle.io/latest.json"
        private const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Price in USD per 1 BTC (e.g., 90610)
    private val _btcPriceUsd = MutableStateFlow<Int?>(null)
    val btcPriceUsd: StateFlow<Int?> = _btcPriceUsd.asStateFlow()

    private val _lastUpdated = MutableStateFlow<String?>(null)
    val lastUpdated: StateFlow<String?> = _lastUpdated.asStateFlow()

    private var refreshJob: Job? = null

    /**
     * Start automatic price refresh every 5 minutes.
     */
    fun startAutoRefresh() {
        if (refreshJob?.isActive == true) return

        refreshJob = scope.launch {
            while (isActive) {
                fetchPrice()
                delay(REFRESH_INTERVAL_MS)
            }
        }
    }

    /**
     * Stop automatic price refresh.
     */
    fun stopAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }

    /**
     * Manually fetch the current price.
     */
    suspend fun fetchPrice(): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(API_URL)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to fetch price: ${response.code}")
                    return@withContext false
                }

                val body = response.body?.string() ?: return@withContext false
                val json = JSONObject(body)

                _btcPriceUsd.value = json.getInt("price")
                _lastUpdated.value = json.optString("updated_at")

                Log.d(TAG, "BTC price updated: ${_btcPriceUsd.value} USD")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching price", e)
            false
        }
    }

    /**
     * Convert sats to USD cents (for formatting).
     * Returns null if price not available.
     */
    fun satsToUsdCents(sats: Long): Long? {
        val price = _btcPriceUsd.value ?: return null
        // sats / 100_000_000 * price * 100 = sats * price / 1_000_000
        return (sats * price) / 1_000_000L
    }

    /**
     * Convert sats to formatted USD string (e.g., "$0.45").
     */
    fun satsToUsdString(sats: Long): String? {
        val cents = satsToUsdCents(sats) ?: return null
        val dollars = cents / 100.0
        return String.format("$%.2f", dollars)
    }

    fun cleanup() {
        stopAutoRefresh()
        scope.cancel()
    }
}
