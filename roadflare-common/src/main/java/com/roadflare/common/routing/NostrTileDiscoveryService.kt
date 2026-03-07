package com.roadflare.common.routing

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.roadflare.common.nostr.NostrService
import com.vitorpamplona.quartz.nip01Core.core.Event
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for discovering available routing tiles from Nostr.
 *
 * Subscribes to kind 1063 (NIP-94 File Metadata) events from the official
 * tile publisher to discover available routing tile regions.
 *
 * Caching: Discovered tiles are cached to SharedPreferences and loaded
 * immediately on init. Users can manually refresh via pull-to-refresh.
 */
@Singleton
class NostrTileDiscoveryService @Inject constructor(
    @ApplicationContext context: Context,
    private val nostrService: NostrService
) {
    companion object {
        private const val TAG = "NostrTileDiscovery"
        private const val PREFS_NAME = "tile_discovery_cache"
        private const val KEY_CACHED_REGIONS = "cached_regions"
        private const val KEY_LAST_DISCOVERY_TIME = "last_discovery_time"
        const val KIND_FILE_METADATA = 1063
        const val OFFICIAL_PUBKEY = "da790ba18e63ae79b16e172907301906957a45f38ef0c9f219d0f016eaf16128"
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _discoveredRegions = MutableStateFlow<List<TileRegion>>(emptyList())
    val discoveredRegions: StateFlow<List<TileRegion>> = _discoveredRegions.asStateFlow()

    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    private val _discoveryError = MutableStateFlow<String?>(null)
    val discoveryError: StateFlow<String?> = _discoveryError.asStateFlow()

    private var subscriptionId: String? = null
    private val seenEventIds = mutableSetOf<String>()

    init {
        loadCachedRegions()
    }

    fun startDiscovery() {
        if (_isDiscovering.value) return

        Log.d(TAG, "Starting tile discovery from Nostr...")
        _isDiscovering.value = true
        _discoveryError.value = null

        nostrService.relayManager.ensureConnected()

        scope.launch {
            delay(500)

            val subId = nostrService.relayManager.subscribe(
                kinds = listOf(KIND_FILE_METADATA),
                authors = listOf(OFFICIAL_PUBKEY),
                onEvent = { event, relayUrl -> handleTileEvent(event, relayUrl) }
            )
            subscriptionId = subId

            delay(15000)

            if (subscriptionId == subId) {
                nostrService.relayManager.closeSubscription(subId)
                subscriptionId = null
                _isDiscovering.value = false
                saveCachedRegions()
                Log.d(TAG, "Discovery complete. Found ${_discoveredRegions.value.size} regions")
            }
        }
    }

    fun stopDiscovery() {
        subscriptionId?.let { nostrService.relayManager.closeSubscription(it) }
        subscriptionId = null
        _isDiscovering.value = false
    }

    fun refreshDiscovery() {
        stopDiscovery()
        seenEventIds.clear()
        _discoveredRegions.value = emptyList()
        startDiscovery()
    }

    private fun handleTileEvent(event: Event, relayUrl: String) {
        if (seenEventIds.contains(event.id)) return
        seenEventIds.add(event.id)

        val region = parseTileEvent(event)
        if (region != null) {
            Log.d(TAG, "Discovered region: ${region.name} (${region.id})")
            val currentRegions = _discoveredRegions.value.toMutableList()
            val existingIndex = currentRegions.indexOfFirst { it.id == region.id }
            if (existingIndex >= 0) {
                currentRegions[existingIndex] = region
            } else {
                currentRegions.add(region)
            }
            _discoveredRegions.value = currentRegions
        }
    }

    private fun parseTileEvent(event: Event): TileRegion? {
        if (event.kind != KIND_FILE_METADATA) return null

        try {
            var sha256: String? = null
            var sizeBytes: Long? = null
            var regionId: String? = null
            var regionName: String? = null
            var bbox: BoundingBox? = null
            val blossomUrls = mutableListOf<String>()
            val chunks = mutableListOf<TileChunk>()

            for (tag in event.tags) {
                val tagName = tag.getOrNull(0) ?: continue
                when (tagName) {
                    "x" -> sha256 = tag.getOrNull(1)
                    "size" -> sizeBytes = tag.getOrNull(1)?.toLongOrNull()
                    "region" -> regionId = tag.getOrNull(1)
                    "title" -> regionName = tag.getOrNull(1)
                    "bbox" -> bbox = BoundingBox.fromTagValue(tag.getOrNull(1) ?: "")
                    "url" -> tag.getOrNull(1)?.let { blossomUrls.add(it) }
                    "chunk" -> {
                        val index = tag.getOrNull(1)?.toIntOrNull()
                        val chunkSha256 = tag.getOrNull(2)
                        val chunkSize = tag.getOrNull(3)?.toLongOrNull()
                        val chunkUrl = tag.getOrNull(4)
                        if (index != null && chunkSha256 != null && chunkSize != null && chunkUrl != null) {
                            chunks.add(TileChunk(index, chunkSha256, chunkSize, chunkUrl))
                        }
                    }
                }
            }

            if (regionId == null || sizeBytes == null) return null
            if (regionName == null) regionName = regionId.replace("-", " ").replaceFirstChar { it.uppercase() }

            val boundingBox = bbox ?: BoundingBox(0.0, 0.0, 0.0, 0.0)

            return TileRegion(
                id = regionId,
                name = regionName,
                boundingBox = boundingBox,
                sizeBytes = sizeBytes,
                sha256 = sha256,
                blossomUrls = blossomUrls,
                chunks = chunks.sortedBy { it.index },
                lastUpdated = event.createdAt,
                isBundled = false,
                assetName = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing tile event: ${e.message}", e)
            return null
        }
    }

    fun getRegion(regionId: String): TileRegion? {
        return _discoveredRegions.value.find { it.id == regionId }
    }

    fun hasCachedRegions(): Boolean = _discoveredRegions.value.isNotEmpty()

    fun getLastDiscoveryTime(): Long = prefs.getLong(KEY_LAST_DISCOVERY_TIME, 0L)

    private fun loadCachedRegions() {
        try {
            val json = prefs.getString(KEY_CACHED_REGIONS, null) ?: return
            val jsonArray = JSONArray(json)
            val regions = mutableListOf<TileRegion>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                deserializeRegion(obj)?.let { regions.add(it) }
            }

            if (regions.isNotEmpty()) {
                _discoveredRegions.value = regions
                Log.d(TAG, "Loaded ${regions.size} cached tile regions")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cached regions: ${e.message}")
        }
    }

    private fun saveCachedRegions() {
        try {
            val regions = _discoveredRegions.value
            if (regions.isEmpty()) return

            val jsonArray = JSONArray()
            for (region in regions) jsonArray.put(serializeRegion(region))

            prefs.edit()
                .putString(KEY_CACHED_REGIONS, jsonArray.toString())
                .putLong(KEY_LAST_DISCOVERY_TIME, System.currentTimeMillis())
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache regions: ${e.message}")
        }
    }

    private fun serializeRegion(region: TileRegion): JSONObject {
        return JSONObject().apply {
            put("id", region.id)
            put("name", region.name)
            put("bbox_west", region.boundingBox.west)
            put("bbox_south", region.boundingBox.south)
            put("bbox_east", region.boundingBox.east)
            put("bbox_north", region.boundingBox.north)
            put("sizeBytes", region.sizeBytes)
            region.sha256?.let { put("sha256", it) }
            put("blossomUrls", JSONArray(region.blossomUrls))
            put("lastUpdated", region.lastUpdated)
            put("isBundled", region.isBundled)
            region.assetName?.let { put("assetName", it) }
            val chunksArray = JSONArray()
            for (chunk in region.chunks) {
                chunksArray.put(JSONObject().apply {
                    put("index", chunk.index)
                    put("sha256", chunk.sha256)
                    put("sizeBytes", chunk.sizeBytes)
                    put("url", chunk.url)
                })
            }
            put("chunks", chunksArray)
        }
    }

    private fun deserializeRegion(obj: JSONObject): TileRegion? {
        return try {
            val blossomUrls = mutableListOf<String>()
            obj.optJSONArray("blossomUrls")?.let { arr ->
                for (i in 0 until arr.length()) blossomUrls.add(arr.getString(i))
            }

            val chunks = mutableListOf<TileChunk>()
            obj.optJSONArray("chunks")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    chunks.add(TileChunk(c.getInt("index"), c.getString("sha256"), c.getLong("sizeBytes"), c.getString("url")))
                }
            }

            TileRegion(
                id = obj.getString("id"),
                name = obj.getString("name"),
                boundingBox = BoundingBox(
                    west = obj.getDouble("bbox_west"),
                    south = obj.getDouble("bbox_south"),
                    east = obj.getDouble("bbox_east"),
                    north = obj.getDouble("bbox_north")
                ),
                sizeBytes = obj.getLong("sizeBytes"),
                sha256 = obj.optString("sha256").takeIf { it.isNotEmpty() },
                blossomUrls = blossomUrls,
                chunks = chunks,
                lastUpdated = obj.optLong("lastUpdated", 0L),
                isBundled = obj.optBoolean("isBundled", false),
                assetName = obj.optString("assetName").takeIf { it.isNotEmpty() }
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to deserialize region: ${e.message}")
            null
        }
    }

    fun clearCache() {
        prefs.edit().clear().apply()
        _discoveredRegions.value = emptyList()
        seenEventIds.clear()
    }
}
