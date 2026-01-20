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
 * Service to fetch and cache Bitcoin price.
 * Uses UTXOracle as primary API with CoinGecko as fallback.
 * Refreshes every 5 minutes automatically.
 *
 * This is a singleton to prevent duplicate API calls when multiple
 * components (MainActivity, ViewModel) create instances.
 */
class BitcoinPriceService private constructor() {
    companion object {
        private const val TAG = "BitcoinPriceService"
        private const val PRIMARY_API_URL = "https://api.utxoracle.io/latest.json"
        private const val BACKUP_API_URL = "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin&vs_currencies=usd"
        private const val REFRESH_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes

        @Volatile
        private var instance: BitcoinPriceService? = null

        fun getInstance(): BitcoinPriceService {
            return instance ?: synchronized(this) {
                instance ?: BitcoinPriceService().also { instance = it }
            }
        }
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
     * Tries primary API (UTXOracle) first, falls back to CoinGecko if it fails.
     */
    suspend fun fetchPrice(): Boolean = withContext(Dispatchers.IO) {
        // Try primary API first
        if (fetchFromPrimaryApi()) {
            return@withContext true
        }

        Log.d(TAG, "Primary API failed, trying backup...")

        // Fall back to backup API
        if (fetchFromBackupApi()) {
            return@withContext true
        }

        Log.e(TAG, "All price APIs failed")
        false
    }

    /**
     * Fetch price from UTXOracle (primary).
     * Response format: { "price": 90610, "updated_at": "..." }
     */
    private fun fetchFromPrimaryApi(): Boolean {
        return try {
            val request = Request.Builder()
                .url(PRIMARY_API_URL)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "UTXOracle API failed: ${response.code}")
                    return false
                }

                val body = response.body?.string() ?: return false
                val json = JSONObject(body)

                _btcPriceUsd.value = json.getInt("price")
                _lastUpdated.value = json.optString("updated_at")

                Log.d(TAG, "BTC price from UTXOracle: ${_btcPriceUsd.value} USD")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching from UTXOracle", e)
            false
        }
    }

    /**
     * Fetch price from CoinGecko (backup).
     * Response format: { "bitcoin": { "usd": 90610.0 } }
     */
    private fun fetchFromBackupApi(): Boolean {
        return try {
            val request = Request.Builder()
                .url(BACKUP_API_URL)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "CoinGecko API failed: ${response.code}")
                    return false
                }

                val body = response.body?.string() ?: return false
                val json = JSONObject(body)

                val bitcoinObj = json.getJSONObject("bitcoin")
                val priceDouble = bitcoinObj.getDouble("usd")

                _btcPriceUsd.value = priceDouble.toInt()
                _lastUpdated.value = null // CoinGecko doesn't provide timestamp in this endpoint

                Log.d(TAG, "BTC price from CoinGecko: ${_btcPriceUsd.value} USD")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching from CoinGecko", e)
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

    /**
     * Convert USD cents to sats.
     * Returns null if price not available.
     */
    fun usdCentsToSats(cents: Long): Long? {
        val price = _btcPriceUsd.value ?: return null
        if (price == 0) return null
        // cents / 100 / price * 100_000_000 = cents * 1_000_000 / price
        return (cents * 1_000_000L) / price
    }

    /**
     * Convert USD dollars to sats.
     * Returns null if price not available.
     */
    fun usdToSats(dollars: Double): Long? {
        return usdCentsToSats((dollars * 100).toLong())
    }

    fun cleanup() {
        stopAutoRefresh()
        scope.cancel()
    }
}
