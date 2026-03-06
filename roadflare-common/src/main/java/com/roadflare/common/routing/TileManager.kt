package com.roadflare.common.routing

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages Valhalla routing tile regions for the app.
 *
 * Responsibilities:
 * - Define available tile regions with bounding boxes
 * - Track which tiles are downloaded locally
 * - Manage tile storage (download, delete, verify)
 * - Determine if a location has routing coverage
 */
@Singleton
class TileManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "TileManager"
        private const val TILES_DIR = "valhalla_tiles"

        /** Bundled tile regions shipped with the app. Currently empty. */
        val BUNDLED_REGIONS = emptyList<TileRegion>()
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
        // IMPORTANT: Must remain synchronous — async scan would race with
        // TILE_SETUP gating in AppStateViewModel. By the time Hilt returns
        // this singleton, downloadedRegions already has the real disk state.
        scanDownloadedTiles()
    }

    private fun scanDownloadedTiles() {
        val downloaded = mutableSetOf<String>()

        BUNDLED_REGIONS.filter { it.isBundled }.forEach { downloaded.add(it.id) }

        Log.d(TAG, "Scanning tilesDir: ${tilesDir.absolutePath}")
        val files = tilesDir.listFiles()
        Log.d(TAG, "Found ${files?.size ?: 0} files in tilesDir")

        files?.forEach { file ->
            if (file.isFile && file.extension == "tar") {
                val regionId = file.nameWithoutExtension
                downloaded.add(regionId)
                Log.d(TAG, "  Found tile: $regionId")
            }
        }

        _downloadedRegions.value = downloaded
        Log.d(TAG, "Downloaded regions after scan: $downloaded")
    }

    fun getKnownRegions(): List<TileRegion> {
        val discovered = _discoveredRegions.value
        val discoveredIds = discovered.map { it.id }.toSet()
        val regions = BUNDLED_REGIONS.filter { it.id !in discoveredIds }.toMutableList()
        regions.addAll(discovered)
        return regions.sortedWith(compareBy({ !it.isBundled }, { it.name }))
    }

    fun getRegion(regionId: String): TileRegion? {
        _discoveredRegions.value.find { it.id == regionId }?.let { return it }
        return BUNDLED_REGIONS.find { it.id == regionId }
    }

    fun findRegionsContaining(lat: Double, lon: Double): List<TileRegion> {
        return getKnownRegions().filter { it.boundingBox.contains(lat, lon) }
    }

    fun hasRoutingCoverage(lat: Double, lon: Double): Boolean {
        val downloadedSet = _downloadedRegions.value
        return getKnownRegions().any { region ->
            region.boundingBox.contains(lat, lon) && downloadedSet.contains(region.id)
        }
    }

    fun getTileForLocation(lat: Double, lon: Double): TileSource? {
        val downloadedSet = _downloadedRegions.value
        val regions = getKnownRegions().filter { region ->
            region.boundingBox.contains(lat, lon) && downloadedSet.contains(region.id)
        }

        val downloadedRegion = regions.find { !it.isBundled }
        if (downloadedRegion != null) {
            val file = File(tilesDir, "${downloadedRegion.id}.tar")
            if (file.exists()) {
                return TileSource.Downloaded(file, downloadedRegion)
            }
        }

        val bundledRegion = regions.find { it.isBundled && it.assetName != null }
        if (bundledRegion != null) {
            return TileSource.Bundled(bundledRegion.assetName!!, bundledRegion)
        }

        return null
    }

    fun updateDiscoveredRegions(regions: List<TileRegion>) {
        _discoveredRegions.value = regions
        Log.d(TAG, "Updated discovered regions: ${regions.size} tiles available")
        scanDownloadedTiles()
    }

    fun isRegionDownloaded(regionId: String): Boolean {
        return _downloadedRegions.value.contains(regionId)
    }

    fun markRegionDownloaded(regionId: String) {
        _downloadedRegions.value = _downloadedRegions.value + regionId
        Log.d(TAG, "Marked region as downloaded: $regionId")
    }

    fun getDownloadStatus(regionId: String): TileDownloadStatus? {
        return _downloadStatus.value[regionId]
    }

    suspend fun saveTile(regionId: String, bytes: ByteArray): File? = withContext(Dispatchers.IO) {
        try {
            val file = File(tilesDir, "$regionId.tar")
            file.writeBytes(bytes)
            _downloadedRegions.value = _downloadedRegions.value + regionId
            Log.d(TAG, "Saved tile: ${file.name} (${bytes.size} bytes)")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save tile: $regionId", e)
            null
        }
    }

    suspend fun deleteTile(regionId: String): Boolean = withContext(Dispatchers.IO) {
        val region = getRegion(regionId)
        if (region?.isBundled == true) {
            Log.w(TAG, "Cannot delete bundled tile: $regionId")
            return@withContext false
        }

        try {
            val file = File(tilesDir, "$regionId.tar")
            val deleted = file.delete()
            if (deleted) {
                _downloadedRegions.value = _downloadedRegions.value - regionId
                Log.d(TAG, "Deleted tile: $regionId")
            }
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete tile: $regionId", e)
            false
        }
    }

    fun getTileFile(regionId: String): File? {
        val file = File(tilesDir, "$regionId.tar")
        return if (file.exists()) file else null
    }

    fun getTotalStorageUsed(): Long {
        return tilesDir.listFiles()?.sumOf { it.length() } ?: 0
    }

    fun updateDownloadStatus(regionId: String, status: TileDownloadStatus?) {
        if (status == null) {
            _downloadStatus.value = _downloadStatus.value - regionId
        } else {
            _downloadStatus.value = _downloadStatus.value + (regionId to status)
        }
    }

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
 * Geographic bounding box for tile regions.
 */
data class BoundingBox(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double
) {
    val crossesDateLine: Boolean = west > 0 && east < 0
    val hasSwappedCoordinates: Boolean = west > east && !crossesDateLine

    fun toTagValue(): String = "$west,$south,$east,$north"

    fun contains(lat: Double, lon: Double): Boolean {
        val latInRange = lat in south..north
        if (!latInRange) return false

        val lonInRange = when {
            crossesDateLine -> lon >= west || lon <= east
            hasSwappedCoordinates -> lon in east..west
            else -> lon in west..east
        }
        return lonInRange
    }

    companion object {
        fun fromTagValue(value: String): BoundingBox? {
            return try {
                val parts = value.split(",")
                if (parts.size != 4) return null
                BoundingBox(
                    west = parts[0].toDouble(),
                    south = parts[1].toDouble(),
                    east = parts[2].toDouble(),
                    north = parts[3].toDouble()
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}

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
    val isChunked: Boolean get() = chunks.isNotEmpty()

    fun getFormattedSize(): String {
        return when {
            sizeBytes < 1024 -> "$sizeBytes B"
            sizeBytes < 1024 * 1024 -> "${sizeBytes / 1024} KB"
            sizeBytes < 1024 * 1024 * 1024 -> "${sizeBytes / (1024 * 1024)} MB"
            else -> String.format("%.1f GB", sizeBytes.toDouble() / (1024 * 1024 * 1024))
        }
    }
}

data class TileDownloadStatus(
    val regionId: String,
    val state: DownloadState,
    val progress: Float = 0f,
    val bytesDownloaded: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null,
    val currentChunk: Int = 0,
    val totalChunks: Int = 0
) {
    fun getStatusMessage(): String {
        return when (state) {
            DownloadState.PENDING -> "Waiting..."
            DownloadState.DOWNLOADING -> {
                if (totalChunks > 1) "Downloading chunk ${currentChunk + 1}/$totalChunks"
                else "Downloading..."
            }
            DownloadState.VERIFYING -> "Verifying..."
            DownloadState.COMPLETED -> "Complete"
            DownloadState.FAILED -> error ?: "Failed"
        }
    }
}

enum class DownloadState {
    PENDING, DOWNLOADING, VERIFYING, COMPLETED, FAILED
}

sealed class TileSource {
    abstract val region: TileRegion
    data class Bundled(val assetName: String, override val region: TileRegion) : TileSource()
    data class Downloaded(val file: File, override val region: TileRegion) : TileSource()
}

data class TileChunk(
    val index: Int,
    val sha256: String,
    val sizeBytes: Long,
    val url: String
)
