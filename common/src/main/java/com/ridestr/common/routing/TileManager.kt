package com.ridestr.common.routing

import android.content.Context
import android.util.Log
import com.ridestr.common.nostr.events.BoundingBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages Valhalla routing tile regions for the app.
 *
 * Responsibilities:
 * - Define available tile regions with bounding boxes
 * - Track which tiles are downloaded locally
 * - Manage tile storage (download, delete, verify)
 * - Determine if a location has routing coverage
 *
 * This is a singleton to ensure discovered regions are shared across all components
 * (MainActivity, RiderViewModel, DriverViewModel, etc.)
 */
class TileManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "TileManager"
        private const val TILES_DIR = "valhalla_tiles"

        /**
         * Bundled tile regions shipped with the app.
         * Currently empty - all tiles are discovered from Nostr (kind 1063 events).
         */
        val BUNDLED_REGIONS = emptyList<TileRegion>()

        @Volatile
        private var instance: TileManager? = null

        /**
         * Get the singleton TileManager instance.
         * @param context Application context (will use applicationContext internally)
         */
        fun getInstance(context: Context): TileManager {
            return instance ?: synchronized(this) {
                instance ?: TileManager(context.applicationContext).also { instance = it }
            }
        }
    }

    // Discovered tile regions from Nostr (kind 1063 events)
    private val _discoveredRegions = MutableStateFlow<List<TileRegion>>(emptyList())
    val discoveredRegions: StateFlow<List<TileRegion>> = _discoveredRegions.asStateFlow()

    // Tiles directory in app storage
    private val tilesDir: File by lazy {
        File(context.filesDir, TILES_DIR).also { it.mkdirs() }
    }

    // Track download status for each region
    private val _downloadStatus = MutableStateFlow<Map<String, TileDownloadStatus>>(emptyMap())
    val downloadStatus: StateFlow<Map<String, TileDownloadStatus>> = _downloadStatus.asStateFlow()

    // Track which regions are downloaded
    private val _downloadedRegions = MutableStateFlow<Set<String>>(emptySet())
    val downloadedRegions: StateFlow<Set<String>> = _downloadedRegions.asStateFlow()

    init {
        // Scan for already downloaded tiles on init
        scanDownloadedTiles()
    }

    /**
     * Scan the tiles directory for already downloaded tiles.
     */
    private fun scanDownloadedTiles() {
        val downloaded = mutableSetOf<String>()

        // Bundled tile is always "downloaded"
        BUNDLED_REGIONS.filter { it.isBundled }.forEach { downloaded.add(it.id) }

        // Check for downloaded tile files
        Log.d(TAG, "Scanning tilesDir: ${tilesDir.absolutePath}")
        val files = tilesDir.listFiles()
        Log.d(TAG, "Found ${files?.size ?: 0} files in tilesDir")

        files?.forEach { file ->
            Log.d(TAG, "  File: ${file.name}, isFile=${file.isFile}, ext=${file.extension}, size=${file.length()}")
            if (file.isFile && file.extension == "tar") {
                val regionId = file.nameWithoutExtension
                downloaded.add(regionId)
                Log.d(TAG, "  -> Added regionId: $regionId")
            }
        }

        _downloadedRegions.value = downloaded
        Log.d(TAG, "Downloaded regions after scan: $downloaded")
    }

    /**
     * Get all known tile regions (bundled + discovered from Nostr).
     * Discovered regions take precedence over bundled if IDs match.
     */
    fun getKnownRegions(): List<TileRegion> {
        val discovered = _discoveredRegions.value
        val discoveredIds = discovered.map { it.id }.toSet()

        // Start with bundled regions that aren't overridden by discovered
        val regions = BUNDLED_REGIONS.filter { it.id !in discoveredIds }.toMutableList()

        // Add all discovered regions
        regions.addAll(discovered)

        // Sort: bundled first, then by name
        return regions.sortedWith(compareBy({ !it.isBundled }, { it.name }))
    }

    /**
     * Get a specific tile region by ID.
     * Checks discovered regions first, then bundled.
     */
    fun getRegion(regionId: String): TileRegion? {
        // Check discovered first (they have download URLs)
        _discoveredRegions.value.find { it.id == regionId }?.let { return it }
        // Fall back to bundled
        return BUNDLED_REGIONS.find { it.id == regionId }
    }

    /**
     * Find all regions that contain a given point.
     */
    fun findRegionsContaining(lat: Double, lon: Double): List<TileRegion> {
        return getKnownRegions().filter { it.boundingBox.contains(lat, lon) }
    }

    /**
     * Check if we have tile coverage for a given location.
     */
    fun hasRoutingCoverage(lat: Double, lon: Double): Boolean {
        val downloadedSet = _downloadedRegions.value
        return getKnownRegions().any { region ->
            region.boundingBox.contains(lat, lon) && downloadedSet.contains(region.id)
        }
    }

    /**
     * Get the best tile file for a given location.
     * Returns the bundled asset name if using bundled tile, or file path for downloaded tiles.
     */
    fun getTileForLocation(lat: Double, lon: Double): TileSource? {
        val downloadedSet = _downloadedRegions.value
        Log.d(TAG, "getTileForLocation($lat, $lon) - downloaded regions: $downloadedSet")

        // List all files in tilesDir for debugging
        val tilesInDir = tilesDir.listFiles()?.map { "${it.name} (${it.length()} bytes)" } ?: emptyList()
        Log.d(TAG, "Files in tilesDir: $tilesInDir")

        val allRegions = getKnownRegions()
        Log.d(TAG, "Known regions: ${allRegions.map { "${it.id} (bbox: ${it.boundingBox.west},${it.boundingBox.south},${it.boundingBox.east},${it.boundingBox.north})" }}")

        val regions = allRegions.filter { region ->
            val inBbox = region.boundingBox.contains(lat, lon)
            val isDownloaded = downloadedSet.contains(region.id)
            Log.d(TAG, "  ${region.id}: inBbox=$inBbox, isDownloaded=$isDownloaded")
            inBbox && isDownloaded
        }

        // Prefer downloaded tiles over bundled
        val downloadedRegion = regions.find { !it.isBundled }
        if (downloadedRegion != null) {
            val file = File(tilesDir, "${downloadedRegion.id}.tar")
            Log.d(TAG, "Looking for file: ${file.absolutePath}, exists=${file.exists()}, size=${if (file.exists()) file.length() else 0}")
            if (file.exists()) {
                return TileSource.Downloaded(file, downloadedRegion)
            } else {
                Log.e(TAG, "Downloaded region ${downloadedRegion.id} file NOT FOUND at ${file.absolutePath}")
            }
        }

        // Fall back to bundled
        val bundledRegion = regions.find { it.isBundled && it.assetName != null }
        if (bundledRegion != null) {
            return TileSource.Bundled(bundledRegion.assetName!!, bundledRegion)
        }

        return null
    }

    /**
     * Update discovered regions from Nostr.
     * Called by NostrTileDiscoveryService when new tile events are received.
     */
    fun updateDiscoveredRegions(regions: List<TileRegion>) {
        _discoveredRegions.value = regions
        Log.d(TAG, "Updated discovered regions: ${regions.size} tiles available")

        // Re-scan to include newly discovered regions in download tracking
        scanDownloadedTiles()
    }

    /**
     * Check if a region is downloaded.
     */
    fun isRegionDownloaded(regionId: String): Boolean {
        return _downloadedRegions.value.contains(regionId)
    }

    /**
     * Mark a region as downloaded (called after file is saved to disk).
     */
    fun markRegionDownloaded(regionId: String) {
        _downloadedRegions.value = _downloadedRegions.value + regionId
        Log.d(TAG, "Marked region as downloaded: $regionId")
    }

    /**
     * Get the download status for a region.
     */
    fun getDownloadStatus(regionId: String): TileDownloadStatus? {
        return _downloadStatus.value[regionId]
    }

    /**
     * Save downloaded tile bytes to storage.
     *
     * @param regionId The region ID
     * @param bytes The tile file bytes
     * @return The saved file, or null on failure
     */
    suspend fun saveTile(regionId: String, bytes: ByteArray): File? = withContext(Dispatchers.IO) {
        try {
            val file = File(tilesDir, "$regionId.tar")
            file.writeBytes(bytes)

            // Update downloaded regions
            _downloadedRegions.value = _downloadedRegions.value + regionId

            Log.d(TAG, "Saved tile: ${file.name} (${bytes.size} bytes)")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save tile: $regionId", e)
            null
        }
    }

    /**
     * Delete a downloaded tile.
     *
     * @param regionId The region ID to delete
     * @return true if successfully deleted
     */
    suspend fun deleteTile(regionId: String): Boolean = withContext(Dispatchers.IO) {
        // Don't allow deleting bundled tiles
        val region = getRegion(regionId)
        if (region?.isBundled == true) {
            Log.w(TAG, "Cannot delete bundled tile: $regionId")
            return@withContext false
        }

        try {
            val file = File(tilesDir, "$regionId.tar")
            val deleted = file.delete()

            if (deleted) {
                // Update downloaded regions
                _downloadedRegions.value = _downloadedRegions.value - regionId
                Log.d(TAG, "Deleted tile: $regionId")
            }

            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete tile: $regionId", e)
            false
        }
    }

    /**
     * Get the file path for a downloaded tile.
     */
    fun getTileFile(regionId: String): File? {
        val file = File(tilesDir, "$regionId.tar")
        return if (file.exists()) file else null
    }

    /**
     * Get total storage used by downloaded tiles.
     */
    fun getTotalStorageUsed(): Long {
        return tilesDir.listFiles()?.sumOf { it.length() } ?: 0
    }

    /**
     * Update download status for a region.
     */
    fun updateDownloadStatus(regionId: String, status: TileDownloadStatus?) {
        if (status == null) {
            _downloadStatus.value = _downloadStatus.value - regionId
        } else {
            _downloadStatus.value = _downloadStatus.value + (regionId to status)
        }
    }

    /**
     * Add a single dynamically discovered region (from Nostr or CDN manifest).
     */
    fun addDiscoveredRegion(region: TileRegion) {
        val current = _discoveredRegions.value.toMutableList()
        val existingIndex = current.indexOfFirst { it.id == region.id }
        if (existingIndex >= 0) {
            current[existingIndex] = region
        } else {
            current.add(region)
        }
        _discoveredRegions.value = current
        Log.d(TAG, "Added/updated discovered region: ${region.name}")
    }
}

/**
 * A tile region definition.
 *
 * @param id Unique identifier (e.g., "us-nevada")
 * @param name Human-readable name (e.g., "Nevada, USA")
 * @param boundingBox Geographic bounding box
 * @param sizeBytes Estimated file size in bytes
 * @param sha256 SHA256 hash of the complete tile file (for verification)
 * @param blossomUrls List of Blossom server URLs hosting this tile (for non-chunked)
 * @param chunks List of chunks if file is chunked (for files >100MB)
 * @param lastUpdated Unix timestamp when tile data was generated
 * @param isBundled Whether this tile is bundled with the app
 * @param assetName Asset filename for bundled tiles (e.g., "nevada.tar")
 */
data class TileRegion(
    val id: String,
    val name: String,
    val boundingBox: BoundingBox,
    val sizeBytes: Long,
    val sha256: String? = null,
    val blossomUrls: List<String> = emptyList(),
    val chunks: List<TileChunk> = emptyList(),
    val lastUpdated: Long = 0,
    val isBundled: Boolean = false,
    val assetName: String? = null
) {
    /**
     * Whether this tile is chunked (multiple parts to download).
     */
    val isChunked: Boolean get() = chunks.isNotEmpty()
    /**
     * Get formatted file size string.
     */
    fun getFormattedSize(): String {
        return when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
            sizeBytes < 1024 * 1024 * 1024 -> "${sizeBytes / (1024 * 1024)} MB"
            else -> String.format("%.1f GB", sizeBytes.toDouble() / (1024 * 1024 * 1024))
        }
    }
}

/**
 * Download status for a tile region.
 */
data class TileDownloadStatus(
    val regionId: String,
    val state: DownloadState,
    val progress: Float = 0f,  // 0.0 to 1.0
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
    // Chunk progress (for chunked downloads)
    val currentChunk: Int = 0,
    val totalChunks: Int = 0
) {
    /**
     * Get a user-friendly status message.
     */
    fun getStatusMessage(): String {
        return when (state) {
            DownloadState.PENDING -> "Waiting..."
            DownloadState.DOWNLOADING -> {
                if (totalChunks > 1) {
                    "Downloading chunk ${currentChunk + 1}/$totalChunks"
                } else {
                    "Downloading..."
                }
            }
            DownloadState.VERIFYING -> "Verifying..."
            DownloadState.COMPLETED -> "Complete"
            DownloadState.FAILED -> error ?: "Failed"
        }
    }
}

/**
 * Tile download state.
 */
enum class DownloadState {
    PENDING,
    DOWNLOADING,
    VERIFYING,
    COMPLETED,
    FAILED
}

/**
 * Represents a tile source (bundled or downloaded).
 */
sealed class TileSource {
    abstract val region: TileRegion

    /**
     * Bundled tile from app assets.
     */
    data class Bundled(
        val assetName: String,
        override val region: TileRegion
    ) : TileSource()

    /**
     * Downloaded tile from storage.
     */
    data class Downloaded(
        val file: File,
        override val region: TileRegion
    ) : TileSource()
}

/**
 * A chunk of a large tile file.
 *
 * For tiles >100MB, we split into ~80MB chunks to stay under Blossom server limits.
 *
 * @param index Chunk index (0-based, determines concatenation order)
 * @param sha256 SHA256 hash of this chunk
 * @param sizeBytes Size of this chunk in bytes
 * @param url Blossom URL to download this chunk
 */
data class TileChunk(
    val index: Int,
    val sha256: String,
    val sizeBytes: Long,
    val url: String
)
