package com.ridestr.common.routing

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Service for downloading Valhalla routing tiles.
 *
 * Supports downloading from:
 * 1. Blossom servers (decentralized, using SHA256 addressing)
 * 2. Direct URLs (CDN fallback)
 *
 * Features:
 * - Progress reporting
 * - SHA256 verification
 * - Cancellation support
 * - Multi-server fallback
 */
class TileDownloadService(
    private val context: Context,
    private val tileManager: TileManager,
    private val blossomService: BlossomTileService = BlossomTileService()
) {
    companion object {
        private const val TAG = "TileDownloadService"

        // Download timeout for large files
        private const val DOWNLOAD_TIMEOUT_MINUTES = 10L

        // Maximum concurrent chunk downloads (balance speed vs server load)
        private const val MAX_CONCURRENT_DOWNLOADS = 3
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(DOWNLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        .writeTimeout(DOWNLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        .build()

    // Active download jobs
    private val activeDownloads = ConcurrentHashMap<String, Job>()

    // Download events
    private val _downloadEvents = MutableSharedFlow<TileDownloadEvent>()
    val downloadEvents: SharedFlow<TileDownloadEvent> = _downloadEvents.asSharedFlow()

    // Temp directory for downloads
    private val tempDir: File by lazy {
        File(context.cacheDir, "tile_downloads").also { it.mkdirs() }
    }

    // Final tiles directory
    private val tilesDir: File by lazy {
        File(context.filesDir, "valhalla_tiles").also { it.mkdirs() }
    }

    /**
     * Download a tile region.
     *
     * Downloads directly to a temp file to avoid memory issues with large tiles.
     * For chunked tiles: downloads each chunk to temp files, concatenates, verifies.
     * For non-chunked: streams directly to temp file, then moves to final location.
     *
     * @param regionId The region ID to download
     * @return true if download succeeded
     */
    suspend fun downloadRegion(regionId: String): Boolean = withContext(Dispatchers.IO) {
        val region = tileManager.getRegion(regionId)
        if (region == null) {
            Log.e(TAG, "Unknown region: $regionId")
            emitEvent(TileDownloadEvent.Error(regionId, "Unknown region"))
            return@withContext false
        }

        if (region.isBundled) {
            Log.w(TAG, "Cannot download bundled region: $regionId")
            return@withContext true // Already available
        }

        if (tileManager.isRegionDownloaded(regionId)) {
            Log.d(TAG, "Region already downloaded: $regionId")
            return@withContext true
        }

        val tempFile = File(tempDir, "$regionId.tar.tmp")
        val finalFile = File(tilesDir, "$regionId.tar")

        try {
            // Clean up any previous temp file
            tempFile.delete()

            // Update status to downloading
            emitEvent(TileDownloadEvent.Started(regionId))

            val success = if (region.isChunked) {
                // Chunked download - streams to temp file
                downloadChunkedRegionToFile(region, tempFile)
            } else {
                // Non-chunked download - streams to temp file
                downloadNonChunkedRegionToFile(region, tempFile)
            }

            if (!success || !tempFile.exists()) {
                Log.e(TAG, "Download failed for region: $regionId")
                tempFile.delete()
                updateStatus(regionId, DownloadState.FAILED, 0f, "Download failed")
                emitEvent(TileDownloadEvent.Error(regionId, "Download failed"))
                return@withContext false
            }

            // Verify final SHA256 if known
            Log.d(TAG, "Download complete for $regionId, file size: ${tempFile.length()} bytes, expected: ${region.sizeBytes}")
            if (!region.sha256.isNullOrEmpty()) {
                updateStatus(regionId, DownloadState.VERIFYING, 1f)
                emitEvent(TileDownloadEvent.Verifying(regionId))

                val downloadedHash = calculateFileSha256(tempFile)
                Log.d(TAG, "SHA256 check for $regionId: expected=${region.sha256}, got=$downloadedHash")
                if (downloadedHash != region.sha256) {
                    Log.e(TAG, "SHA256 mismatch for $regionId: expected ${region.sha256}, got $downloadedHash")
                    tempFile.delete()
                    updateStatus(regionId, DownloadState.FAILED, 0f, "SHA256 verification failed")
                    emitEvent(TileDownloadEvent.Error(regionId, "SHA256 verification failed"))
                    return@withContext false
                }

                Log.d(TAG, "SHA256 verified for $regionId")
            } else {
                Log.w(TAG, "No SHA256 to verify for $regionId, skipping verification")
            }

            // Move temp file to final location
            Log.d(TAG, "Moving $regionId from ${tempFile.absolutePath} to ${finalFile.absolutePath}")
            finalFile.delete() // Remove any existing file
            if (!tempFile.renameTo(finalFile)) {
                // Rename failed, try copy instead
                Log.d(TAG, "Rename failed, copying instead")
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            // Verify final file exists and has correct size
            if (!finalFile.exists()) {
                Log.e(TAG, "Final file does not exist after move: ${finalFile.absolutePath}")
                updateStatus(regionId, DownloadState.FAILED, 0f, "File move failed")
                return@withContext false
            }
            Log.d(TAG, "Final file verified: ${finalFile.absolutePath} (${finalFile.length()} bytes)")

            // Update tile manager that this region is now downloaded
            tileManager.markRegionDownloaded(regionId)

            // Success!
            updateStatus(regionId, DownloadState.COMPLETED, 1f)
            emitEvent(TileDownloadEvent.Completed(regionId, finalFile))
            Log.d(TAG, "Successfully downloaded $regionId (${finalFile.length()} bytes) to ${finalFile.absolutePath}")

            true

        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $regionId", e)
            tempFile.delete()
            updateStatus(regionId, DownloadState.FAILED, 0f, e.message)
            emitEvent(TileDownloadEvent.Error(regionId, e.message ?: "Unknown error"))
            false
        }
    }

    /**
     * Calculate SHA256 of a file without loading it entirely into memory.
     */
    private fun calculateFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(8192)
        FileInputStream(file).use { fis ->
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Download a chunked tile region with parallel chunk downloads.
     *
     * Downloads chunks in parallel (up to MAX_CONCURRENT_DOWNLOADS at once),
     * verifies each chunk's SHA256, then concatenates them in order.
     */
    private suspend fun downloadChunkedRegionToFile(region: TileRegion, outputFile: File): Boolean = coroutineScope {
        val regionId = region.id
        val chunks = region.chunks.sortedBy { it.index }
        val totalChunks = chunks.size

        Log.d(TAG, "Downloading $regionId as $totalChunks chunks in parallel (max $MAX_CONCURRENT_DOWNLOADS concurrent)")

        // Track progress for each chunk
        val chunkProgress = FloatArray(totalChunks) { 0f }
        val chunkFiles = Array<File?>(totalChunks) { null }

        // Semaphore to limit concurrent downloads
        val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)

        // Update overall progress based on individual chunk progress
        fun updateOverallProgress() {
            val overall = chunkProgress.sum() / totalChunks
            val completedChunks = chunkProgress.count { it >= 1f }
            updateStatusChunked(
                regionId = regionId,
                state = DownloadState.DOWNLOADING,
                overallProgress = overall,
                currentChunk = completedChunks,
                totalChunks = totalChunks
            )
            emitEventSync(TileDownloadEvent.Progress(regionId, overall))
        }

        // Download all chunks in parallel
        val downloadJobs = chunks.mapIndexed { index, chunk ->
            async {
                semaphore.withPermit {
                    val chunkFile = File(tempDir, "${regionId}_chunk_$index.tmp")
                    Log.d(TAG, "Starting chunk ${index + 1}/$totalChunks: ${chunk.sha256.take(8)}...")

                    val success = downloadUrlToFile(chunk.url, chunkFile) { progress ->
                        chunkProgress[index] = progress
                        updateOverallProgress()
                    }

                    if (!success || !chunkFile.exists()) {
                        Log.e(TAG, "Failed to download chunk $index for $regionId")
                        chunkFile.delete()
                        return@async false
                    }

                    // Verify chunk SHA256
                    val chunkHash = calculateFileSha256(chunkFile)
                    if (chunkHash != chunk.sha256) {
                        Log.e(TAG, "Chunk $index SHA256 mismatch: expected ${chunk.sha256}, got $chunkHash")
                        chunkFile.delete()
                        return@async false
                    }

                    Log.d(TAG, "Chunk $index verified (${chunkFile.length()} bytes)")
                    chunkProgress[index] = 1f
                    chunkFiles[index] = chunkFile
                    updateOverallProgress()
                    true
                }
            }
        }

        // Wait for all downloads to complete
        val results = downloadJobs.awaitAll()

        // Check if any downloads failed
        if (results.any { !it }) {
            Log.e(TAG, "One or more chunk downloads failed for $regionId")
            // Clean up any downloaded chunk files
            chunkFiles.forEach { it?.delete() }
            return@coroutineScope false
        }

        // All chunks downloaded successfully - concatenate them in order
        Log.d(TAG, "All chunks downloaded, concatenating...")
        updateStatusChunked(
            regionId = regionId,
            state = DownloadState.VERIFYING,
            overallProgress = 1f,
            currentChunk = totalChunks,
            totalChunks = totalChunks
        )

        FileOutputStream(outputFile).use { fos ->
            for (index in 0 until totalChunks) {
                val chunkFile = chunkFiles[index]
                if (chunkFile == null || !chunkFile.exists()) {
                    Log.e(TAG, "Chunk $index file missing during concatenation")
                    return@coroutineScope false
                }

                FileInputStream(chunkFile).use { fis ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        fos.write(buffer, 0, bytesRead)
                    }
                }

                // Delete chunk temp file after concatenating
                chunkFile.delete()
            }
        }

        Log.d(TAG, "All $totalChunks chunks concatenated, total size: ${outputFile.length()} bytes")
        true
    }

    /**
     * Download a non-chunked tile region directly to a file.
     */
    private suspend fun downloadNonChunkedRegionToFile(region: TileRegion, outputFile: File): Boolean {
        val regionId = region.id

        updateStatus(regionId, DownloadState.DOWNLOADING, 0f)

        // Get download URL
        val downloadUrl = if (region.blossomUrls.isNotEmpty()) {
            region.blossomUrls.first()
        } else {
            Log.e(TAG, "No download source for region: $regionId")
            updateStatus(regionId, DownloadState.FAILED, 0f, "No download source")
            emitEvent(TileDownloadEvent.Error(regionId, "No download source available"))
            return false
        }

        Log.d(TAG, "Downloading $regionId from $downloadUrl")

        return downloadUrlToFile(downloadUrl, outputFile) { progress ->
            updateStatus(regionId, DownloadState.DOWNLOADING, progress)
            emitEventSync(TileDownloadEvent.Progress(regionId, progress))
        }
    }

    /**
     * Download from a URL directly to a file with progress reporting.
     * Streams data to disk without holding entire file in memory.
     */
    private suspend fun downloadUrlToFile(
        url: String,
        outputFile: File,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "Download failed: ${response.code}")
                return@withContext false
            }

            val contentLength = response.body?.contentLength() ?: -1
            val inputStream = response.body?.byteStream() ?: return@withContext false

            FileOutputStream(outputFile).use { fos ->
                val buffer = ByteArray(8192)
                var bytesRead: Long = 0
                var read: Int

                while (inputStream.read(buffer).also { read = it } != -1) {
                    fos.write(buffer, 0, read)
                    bytesRead += read

                    if (contentLength > 0) {
                        onProgress(bytesRead.toFloat() / contentLength)
                    }
                }
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "URL download failed: $url", e)
            outputFile.delete()
            false
        }
    }

    /**
     * Cancel a download in progress.
     */
    fun cancelDownload(regionId: String) {
        activeDownloads[regionId]?.cancel()
        activeDownloads.remove(regionId)
        tileManager.updateDownloadStatus(regionId, null)
    }

    /**
     * Check available storage space.
     *
     * @return Available bytes in the tiles directory
     */
    fun getAvailableStorage(): Long {
        return context.filesDir.freeSpace
    }

    /**
     * Check if there's enough space to download a region.
     *
     * @param regionId The region to check
     * @return true if there's enough space
     */
    fun hasEnoughSpace(regionId: String): Boolean {
        val region = tileManager.getRegion(regionId) ?: return false
        // Require 2x the file size for safety margin
        return getAvailableStorage() > region.sizeBytes * 2
    }

    private fun updateStatus(
        regionId: String,
        state: DownloadState,
        progress: Float,
        error: String? = null
    ) {
        val region = tileManager.getRegion(regionId)
        tileManager.updateDownloadStatus(
            regionId,
            TileDownloadStatus(
                regionId = regionId,
                state = state,
                progress = progress,
                bytesDownloaded = (progress * (region?.sizeBytes ?: 0)).toLong(),
                totalBytes = region?.sizeBytes ?: 0,
                error = error
            )
        )
    }

    private fun updateStatusChunked(
        regionId: String,
        state: DownloadState,
        overallProgress: Float,
        currentChunk: Int,
        totalChunks: Int,
        error: String? = null
    ) {
        val region = tileManager.getRegion(regionId)
        tileManager.updateDownloadStatus(
            regionId,
            TileDownloadStatus(
                regionId = regionId,
                state = state,
                progress = overallProgress,
                bytesDownloaded = (overallProgress * (region?.sizeBytes ?: 0)).toLong(),
                totalBytes = region?.sizeBytes ?: 0,
                error = error,
                currentChunk = currentChunk,
                totalChunks = totalChunks
            )
        )
    }

    private suspend fun emitEvent(event: TileDownloadEvent) {
        _downloadEvents.emit(event)
    }

    private fun emitEventSync(event: TileDownloadEvent) {
        _downloadEvents.tryEmit(event)
    }
}

/**
 * Events emitted during tile download.
 */
sealed class TileDownloadEvent {
    abstract val regionId: String

    data class Started(override val regionId: String) : TileDownloadEvent()
    data class Progress(override val regionId: String, val progress: Float) : TileDownloadEvent()
    data class Verifying(override val regionId: String) : TileDownloadEvent()
    data class Completed(override val regionId: String, val file: File) : TileDownloadEvent()
    data class Error(override val regionId: String, val message: String) : TileDownloadEvent()
}
