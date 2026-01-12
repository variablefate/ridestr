package com.ridestr.common.routing

import android.util.Log
import com.ridestr.common.nostr.events.BoundingBox
import com.ridestr.common.nostr.relay.RelayManager
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Service for discovering available routing tiles from Nostr.
 *
 * Subscribes to kind 1063 (NIP-94 File Metadata) events from the official
 * Ridestr tile publisher to discover available routing tile regions.
 *
 * Tags parsed (NIP-94 compatible):
 * - x: SHA256 hash of the file
 * - size: File size in bytes
 * - m: MIME type (application/x-tar)
 * - url: Blossom download URL (for non-chunked files)
 * - title: Human-readable region name
 * - region: Region identifier (e.g., "us-nevada")
 * - bbox: Bounding box as "west,south,east,north"
 * - chunk: For chunked files - [index, sha256, size, url]
 */
class NostrTileDiscoveryService(
    private val relayManager: RelayManager
) {
    companion object {
        private const val TAG = "NostrTileDiscovery"

        // NIP-94 File Metadata kind
        const val KIND_FILE_METADATA = 1063

        // Official Ridestr tile publisher pubkey
        const val OFFICIAL_PUBKEY = "da790ba18e63ae79b16e172907301906957a45f38ef0c9f219d0f016eaf16128"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Discovered tile regions from Nostr
    private val _discoveredRegions = MutableStateFlow<List<TileRegion>>(emptyList())
    val discoveredRegions: StateFlow<List<TileRegion>> = _discoveredRegions.asStateFlow()

    // Discovery state
    private val _isDiscovering = MutableStateFlow(false)
    val isDiscovering: StateFlow<Boolean> = _isDiscovering.asStateFlow()

    // Error state
    private val _discoveryError = MutableStateFlow<String?>(null)
    val discoveryError: StateFlow<String?> = _discoveryError.asStateFlow()

    // Active subscription ID
    private var subscriptionId: String? = null

    // Keep track of seen event IDs to avoid duplicates
    private val seenEventIds = mutableSetOf<String>()

    /**
     * Start discovering tile availability from Nostr relays.
     *
     * Subscribes to kind 1063 events from the official tile publisher pubkey.
     * Events are parsed and converted to TileRegion objects.
     */
    fun startDiscovery() {
        if (_isDiscovering.value) {
            Log.d(TAG, "Discovery already in progress")
            return
        }

        Log.d(TAG, "Starting tile discovery from Nostr...")
        _isDiscovering.value = true
        _discoveryError.value = null

        // Ensure relays are connected
        relayManager.ensureConnected()

        // Wait a moment for connections to establish
        scope.launch {
            delay(500)

            // Subscribe to kind 1063 events from official pubkey
            subscriptionId = relayManager.subscribe(
                kinds = listOf(KIND_FILE_METADATA),
                authors = listOf(OFFICIAL_PUBKEY),
                onEvent = { event, relayUrl ->
                    handleTileEvent(event, relayUrl)
                }
            )

            Log.d(TAG, "Subscribed with ID: $subscriptionId")

            // Set a timeout for discovery completion
            delay(5000)
            _isDiscovering.value = false
            Log.d(TAG, "Discovery complete. Found ${_discoveredRegions.value.size} regions")
        }
    }

    /**
     * Stop tile discovery and close subscription.
     */
    fun stopDiscovery() {
        subscriptionId?.let { id ->
            relayManager.closeSubscription(id)
            subscriptionId = null
        }
        _isDiscovering.value = false
        Log.d(TAG, "Stopped tile discovery")
    }

    /**
     * Refresh tile discovery (clear and restart).
     */
    fun refreshDiscovery() {
        stopDiscovery()
        seenEventIds.clear()
        _discoveredRegions.value = emptyList()
        startDiscovery()
    }

    /**
     * Handle a tile availability event from Nostr.
     */
    private fun handleTileEvent(event: Event, relayUrl: String) {
        // Skip duplicates
        if (seenEventIds.contains(event.id)) {
            return
        }
        seenEventIds.add(event.id)

        Log.d(TAG, "Received tile event ${event.id.take(8)} from $relayUrl")

        // Parse the event into a TileRegion
        val region = parseTileEvent(event)
        if (region != null) {
            Log.d(TAG, "Discovered region: ${region.name} (${region.id})")

            // Add to discovered regions (or update if exists)
            val currentRegions = _discoveredRegions.value.toMutableList()
            val existingIndex = currentRegions.indexOfFirst { it.id == region.id }
            if (existingIndex >= 0) {
                currentRegions[existingIndex] = region
            } else {
                currentRegions.add(region)
            }
            _discoveredRegions.value = currentRegions
        } else {
            Log.w(TAG, "Failed to parse tile event ${event.id.take(8)}")
        }
    }

    /**
     * Parse a kind 1063 event into a TileRegion.
     *
     * Expected tags (NIP-94 compatible):
     * - x: SHA256 hash
     * - size: File size in bytes
     * - region: Region ID
     * - title: Region name
     * - bbox: Bounding box
     * - url: Download URL (for non-chunked)
     * - chunk: [index, sha256, size, url] (for chunked)
     */
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
                        // Chunk tag format: ["chunk", index, sha256, size, url]
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

            // Validate required fields
            if (regionId == null) {
                Log.w(TAG, "Missing region ID in event ${event.id.take(8)}")
                return null
            }

            // Use region ID as name if title not provided
            if (regionName == null) {
                regionName = regionId.replace("-", " ").replaceFirstChar { it.uppercase() }
            }

            // Size is required
            if (sizeBytes == null) {
                Log.w(TAG, "Missing size in event ${event.id.take(8)}")
                return null
            }

            // Either URL or chunks are required (unless bundled)
            if (blossomUrls.isEmpty() && chunks.isEmpty()) {
                Log.w(TAG, "No download source in event ${event.id.take(8)}")
                // Still create region but mark as not downloadable
            }

            // Sort chunks by index
            val sortedChunks = chunks.sortedBy { it.index }

            // Require bbox for location-based recommendations
            // Without bbox, tile will show in "Other Regions" but won't be recommended
            val boundingBox = bbox ?: run {
                Log.w(TAG, "Missing bbox in event ${event.id.take(8)} for region $regionId, using invalid bbox")
                // Invalid bbox that won't match any real location
                BoundingBox(0.0, 0.0, 0.0, 0.0)
            }

            // Log parsed bbox for debugging
            Log.d(TAG, "Region $regionId bbox: west=${boundingBox.west}, south=${boundingBox.south}, east=${boundingBox.east}, north=${boundingBox.north}")

            return TileRegion(
                id = regionId,
                name = regionName,
                boundingBox = boundingBox,
                sizeBytes = sizeBytes,
                sha256 = sha256,
                blossomUrls = blossomUrls,
                chunks = sortedChunks,
                lastUpdated = event.createdAt,
                isBundled = false,
                assetName = null
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing tile event: ${e.message}", e)
            return null
        }
    }

    /**
     * Get a discovered region by ID.
     */
    fun getRegion(regionId: String): TileRegion? {
        return _discoveredRegions.value.find { it.id == regionId }
    }

    /**
     * Check if any tiles are available for a location.
     */
    fun hasRegionsForLocation(lat: Double, lon: Double): Boolean {
        return _discoveredRegions.value.any { it.boundingBox.contains(lat, lon) }
    }

    /**
     * Find all discovered regions containing a location.
     */
    fun findRegionsForLocation(lat: Double, lon: Double): List<TileRegion> {
        return _discoveredRegions.value.filter { it.boundingBox.contains(lat, lon) }
    }
}
