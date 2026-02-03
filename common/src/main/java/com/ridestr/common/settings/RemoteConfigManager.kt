package com.ridestr.common.settings

import android.content.Context
import android.util.Log
import com.ridestr.common.nostr.events.AdminConfig
import com.ridestr.common.nostr.events.AdminConfigEvent
import com.ridestr.common.nostr.events.MintOption
import com.ridestr.common.nostr.events.RideshareEventKinds
import com.ridestr.common.nostr.relay.RelayManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

private const val TAG = "RemoteConfigManager"
private const val PREFS_NAME = "remote_config"
private const val KEY_CACHED_CONFIG = "cached_config"
private const val KEY_CACHED_AT = "cached_at"
private const val FETCH_TIMEOUT_MS = 8000L

/**
 * Manages remote platform configuration from admin pubkey.
 *
 * Fetch strategy:
 * 1. On app startup (after relay connection), fetch Kind 30182 from admin pubkey
 * 2. Wait for EOSE or timeout (whichever comes first)
 * 3. Cache locally for offline use
 * 4. Fall back to hardcoded defaults if no event and no cache
 *
 * Usage:
 * ```kotlin
 * val configManager = RemoteConfigManager(context, relayManager)
 * configManager.fetchConfig()  // Call once on startup
 * val fareRate = configManager.config.value.fareRateUsdPerMile
 * ```
 */
class RemoteConfigManager(
    private val context: Context,
    private val relayManager: RelayManager
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(loadCachedOrDefault())
    val config: StateFlow<AdminConfig> = _config.asStateFlow()

    private val _lastFetchTime = MutableStateFlow(prefs.getLong(KEY_CACHED_AT, 0))
    val lastFetchTime: StateFlow<Long> = _lastFetchTime.asStateFlow()

    /**
     * Fetch admin config from relays.
     * Should be called once on app startup after relay connection.
     *
     * @return The fetched config, or cached/default if fetch fails
     */
    suspend fun fetchConfig(): AdminConfig = withContext(Dispatchers.IO) {
        // Wait for relay connection (10 second timeout for config)
        if (!relayManager.awaitConnected(timeoutMs = 10000L, tag = "fetchConfig")) {
            Log.w(TAG, "No relays connected - using cached/default config")
            return@withContext _config.value
        }

        Log.d(TAG, "Fetching admin config from ${relayManager.connectedCount()} relays...")

        try {
            var fetchedConfig: AdminConfig? = null
            val eoseReceived = CompletableDeferred<String>()

            val subscriptionId = relayManager.subscribe(
                kinds = listOf(RideshareEventKinds.ADMIN_CONFIG),
                authors = listOf(AdminConfigEvent.ADMIN_PUBKEY),
                tags = mapOf("d" to listOf(AdminConfigEvent.D_TAG)),
                limit = 1,
                onEose = { relayUrl ->
                    // Complete on first EOSE
                    eoseReceived.complete(relayUrl)
                }
            ) { event, relayUrl ->
                Log.d(TAG, "Received admin config event ${event.id.take(8)} from $relayUrl")
                AdminConfigEvent.parse(event)?.let { config ->
                    // Keep the most recent config (by createdAt)
                    if (fetchedConfig == null || config.createdAt > (fetchedConfig?.createdAt ?: 0)) {
                        fetchedConfig = config
                    }
                }
            }

            // Wait for EOSE or timeout
            val eoseRelay = withTimeoutOrNull(FETCH_TIMEOUT_MS) {
                eoseReceived.await()
            }
            if (eoseRelay != null) {
                Log.d(TAG, "EOSE received from $eoseRelay")
                // Brief delay for other relays
                delay(200)
            } else {
                Log.d(TAG, "Timeout waiting for EOSE")
            }

            relayManager.closeSubscription(subscriptionId)

            // Use fetched config if found
            fetchedConfig?.let { config ->
                Log.d(TAG, "Using fetched config: fare=$${config.fareRateUsdPerMile}/mi")
                cacheConfig(config)
                _config.value = config
                return@withContext config
            }

            Log.d(TAG, "No admin config found - using cached/default")
            _config.value

        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch admin config", e)
            _config.value
        }
    }

    /**
     * Load cached config or return default.
     */
    private fun loadCachedOrDefault(): AdminConfig {
        val cachedJson = prefs.getString(KEY_CACHED_CONFIG, null)
        if (cachedJson != null) {
            try {
                val json = JSONObject(cachedJson)

                // Parse cached mints
                val mints = mutableListOf<MintOption>()
                val mintsArray = json.optJSONArray("mints")
                if (mintsArray != null) {
                    for (i in 0 until mintsArray.length()) {
                        val mintJson = mintsArray.getJSONObject(i)
                        mints.add(
                            MintOption(
                                name = mintJson.getString("name"),
                                url = mintJson.getString("url"),
                                description = mintJson.optString("description", ""),
                                recommended = mintJson.optBoolean("recommended", false)
                            )
                        )
                    }
                }

                val config = AdminConfig(
                    fareRateUsdPerMile = json.optDouble("fare_rate", AdminConfig.DEFAULT_FARE_RATE),
                    minimumFareUsd = json.optDouble("minimum_fare", AdminConfig.DEFAULT_MINIMUM_FARE),
                    roadflareFareRateUsdPerMile = json.optDouble("roadflare_fare_rate", AdminConfig.DEFAULT_ROADFLARE_FARE_RATE),
                    roadflareMinimumFareUsd = json.optDouble("roadflare_minimum_fare", AdminConfig.DEFAULT_ROADFLARE_MINIMUM_FARE),
                    recommendedMints = mints.ifEmpty { AdminConfig.DEFAULT_MINTS },
                    createdAt = json.optLong("created_at", 0)
                )
                Log.d(TAG, "Loaded cached config: fare=$${config.fareRateUsdPerMile}/mi, ${config.recommendedMints.size} mints")
                return config
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse cached config", e)
            }
        }
        Log.d(TAG, "Using default config")
        return AdminConfig()
    }

    /**
     * Cache config to local storage.
     */
    private fun cacheConfig(config: AdminConfig) {
        try {
            val mintsArray = org.json.JSONArray()
            config.recommendedMints.forEach { mint ->
                mintsArray.put(JSONObject().apply {
                    put("name", mint.name)
                    put("url", mint.url)
                    put("description", mint.description)
                    put("recommended", mint.recommended)
                })
            }

            val json = JSONObject().apply {
                put("fare_rate", config.fareRateUsdPerMile)
                put("minimum_fare", config.minimumFareUsd)
                put("roadflare_fare_rate", config.roadflareFareRateUsdPerMile)
                put("roadflare_minimum_fare", config.roadflareMinimumFareUsd)
                put("mints", mintsArray)
                put("created_at", config.createdAt)
            }
            val now = System.currentTimeMillis()
            prefs.edit()
                .putString(KEY_CACHED_CONFIG, json.toString())
                .putLong(KEY_CACHED_AT, now)
                .apply()
            _lastFetchTime.value = now
            Log.d(TAG, "Cached config with ${config.recommendedMints.size} mints")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache config", e)
        }
    }

    /**
     * Clear cached config (for testing/debugging).
     */
    fun clearCache() {
        prefs.edit().clear().apply()
        _config.value = AdminConfig()
        _lastFetchTime.value = 0
        Log.d(TAG, "Cleared cached config")
    }
}
