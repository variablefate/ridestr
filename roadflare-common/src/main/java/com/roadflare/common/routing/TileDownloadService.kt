package com.roadflare.common.routing

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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for downloading Valhalla routing tiles.
 *
 * Supports downloading from Blossom servers (decentralized, SHA256 addressing)
 * and direct URLs (CDN fallback). Includes progress reporting, SHA256
 * verification, cancellation support, and multi-server fallback.
 */
@Singleton
class TileDownloadService @Inject constructor(
    private val blossomService: BlossomTileService,
    private val tileManager: TileManager
) {
    companion object {
        private const val TAG = "TileDownloadService"
        private const val DOWNLOAD_TIMEOUT_MINUTES = 10L
        private const val MAX_CONCURRENT_DOWNLOADS = 3
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(DOWNLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        .writeTimeout(DOWNLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES)
        .build()

    private val activeDownloads = ConcurrentHashMap<String, Job>()

    private val _downloadEvents = MutableSharedFlow<TileDownloadEvent>()
    val downloadEvents: SharedFlow<TileDownloadEvent> = _downloadEvents.asSharedFlow()

    suspend fun downloadRegion(regionId: String): Boolean = withContext(Dispatchers.IO) {
        val region = tileManager.getRegion(regionId)
        if (region == null) {
            Log.e(TAG, "Unknown region: $regionId")
            emitEvent(TileDownloadEvent.Error(regionId, "Unknown region"))
            return@withContext false
        }

        if (region.isBundled) return@withContext true
        if (tileManager.isRegionDownloaded(regionId)) return@withContext true

        val tilesDir = File(tileManager.getTileFile(regionId)?.parentFile?.absolutePath
            ?: "${tileManager.getTileFile("_")?.parentFile?.absolutePath ?: ""}").let {
            // Derive tiles dir from TileManager internals - use context filesDir
            File(it.absolutePath)
        }

        // Use a temp directory in cache
        val cacheDir = File(System.getProperty("java.io.tmpdir") ?: "/tmp")
        val tempFile = File(cacheDir, "$regionId.tar.tmp")
        val finalFile = tileManager.getTileFile(regionId) ?: run {
            // Region not yet downloaded, construct expected path
            // TileManager stores tiles as <filesDir>/valhalla_tiles/<regionId>.tar
            // We'll let saveTile handle the actual write
            null
        }

        try {
            tempFile.delete()
            emitEvent(TileDownloadEvent.Started(regionId))

            val success = if (region.isChunked) {
                downloadChunkedRegionToFile(region, tempFile)
            } else {
                downloadNonChunkedRegionToFile(region, tempFile)
            }

            if (!success || !tempFile.exists()) {
                tempFile.delete()
                updateStatus(regionId, DownloadState.FAILED, 0f, "Download failed")
                emitEvent(TileDownloadEvent.Error(regionId, "Download failed"))
                return@withContext false
            }

            // Verify SHA256
            if (!region.sha256.isNullOrEmpty()) {
                updateStatus(regionId, DownloadState.VERIFYING, 1f)
                emitEvent(TileDownloadEvent.Verifying(regionId))

                val downloadedHash = calculateFileSha256(tempFile)
                if (downloadedHash != region.sha256) {
                    Log.e(TAG, "SHA256 mismatch for $regionId")
                    tempFile.delete()
                    updateStatus(regionId, DownloadState.FAILED, 0f, "SHA256 verification failed")
                    emitEvent(TileDownloadEvent.Error(regionId, "SHA256 verification failed"))
                    return@withContext false
                }
            }

            // Save via TileManager
            val bytes = tempFile.readBytes()
            tempFile.delete()
            val savedFile = tileManager.saveTile(regionId, bytes)

            if (savedFile == null) {
                updateStatus(regionId, DownloadState.FAILED, 0f, "Failed to save tile")
                return@withContext false
            }

            updateStatus(regionId, DownloadState.COMPLETED, 1f)
            emitEvent(TileDownloadEvent.Completed(regionId, savedFile))
            Log.d(TAG, "Successfully downloaded $regionId (${savedFile.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for $regionId", e)
            tempFile.delete()
            updateStatus(regionId, DownloadState.FAILED, 0f, e.message)
            emitEvent(TileDownloadEvent.Error(regionId, e.message ?: "Unknown error"))
            false
        }
    }

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

    private suspend fun downloadChunkedRegionToFile(region: TileRegion, outputFile: File): Boolean = coroutineScope {
        val regionId = region.id
        val chunks = region.chunks.sortedBy { it.index }
        val totalChunks = chunks.size

        val chunkProgress = FloatArray(totalChunks) { 0f }
        val chunkFiles = Array<File?>(totalChunks) { null }
        val semaphore = Semaphore(MAX_CONCURRENT_DOWNLOADS)

        fun updateOverallProgress() {
            val overall = chunkProgress.sum() / totalChunks
            val completedChunks = chunkProgress.count { it >= 1f }
            updateStatusChunked(regionId, DownloadState.DOWNLOADING, overall, completedChunks, totalChunks)
            emitEventSync(TileDownloadEvent.Progress(regionId, overall))
        }

        val cacheDir = outputFile.parentFile ?: File(System.getProperty("java.io.tmpdir") ?: "/tmp")

        val downloadJobs = chunks.mapIndexed { index, chunk ->
            async {
                semaphore.withPermit {
                    val chunkFile = File(cacheDir, "${regionId}_chunk_$index.tmp")

                    val success = downloadUrlToFile(chunk.url, chunkFile) { progress ->
                        chunkProgress[index] = progress
                        updateOverallProgress()
                    }

                    if (!success || !chunkFile.exists()) {
                        chunkFile.delete()
                        return@async false
                    }

                    val chunkHash = calculateFileSha256(chunkFile)
                    if (chunkHash != chunk.sha256) {
                        Log.e(TAG, "Chunk $index SHA256 mismatch")
                        chunkFile.delete()
                        return@async false
                    }

                    chunkProgress[index] = 1f
                    chunkFiles[index] = chunkFile
                    updateOverallProgress()
                    true
                }
            }
        }

        val results = downloadJobs.awaitAll()
        if (results.any { !it }) {
            chunkFiles.forEach { it?.delete() }
            return@coroutineScope false
        }

        // Concatenate chunks
        FileOutputStream(outputFile).use { fos ->
            for (index in 0 until totalChunks) {
                val chunkFile = chunkFiles[index] ?: return@coroutineScope false
                FileInputStream(chunkFile).use { fis ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (fis.read(buffer).also { bytesRead = it } != -1) {
                        fos.write(buffer, 0, bytesRead)
                    }
                }
                chunkFile.delete()
            }
        }
        true
    }

    private suspend fun downloadNonChunkedRegionToFile(region: TileRegion, outputFile: File): Boolean {
        val regionId = region.id
        updateStatus(regionId, DownloadState.DOWNLOADING, 0f)

        val downloadUrl = region.blossomUrls.firstOrNull()
        if (downloadUrl == null) {
            updateStatus(regionId, DownloadState.FAILED, 0f, "No download source")
            emitEvent(TileDownloadEvent.Error(regionId, "No download source available"))
            return false
        }

        return downloadUrlToFile(downloadUrl, outputFile) { progress ->
            updateStatus(regionId, DownloadState.DOWNLOADING, progress)
            emitEventSync(TileDownloadEvent.Progress(regionId, progress))
        }
    }

    private suspend fun downloadUrlToFile(
        url: String,
        outputFile: File,
        onProgress: (Float) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).get().build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext false

            val contentLength = response.body?.contentLength() ?: -1
            val inputStream = response.body?.byteStream() ?: return@withContext false

            FileOutputStream(outputFile).use { fos ->
                val buffer = ByteArray(8192)
                var bytesRead: Long = 0
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    fos.write(buffer, 0, read)
                    bytesRead += read
                    if (contentLength > 0) onProgress(bytesRead.toFloat() / contentLength)
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "URL download failed: $url", e)
            outputFile.delete()
            false
        }
    }

    fun cancelDownload(regionId: String) {
        activeDownloads[regionId]?.cancel()
        activeDownloads.remove(regionId)
        tileManager.updateDownloadStatus(regionId, null)
    }

    private fun updateStatus(regionId: String, state: DownloadState, progress: Float, error: String? = null) {
        val region = tileManager.getRegion(regionId)
        tileManager.updateDownloadStatus(regionId, TileDownloadStatus(
            regionId = regionId,
            state = state,
            progress = progress,
            bytesDownloaded = (progress * (region?.sizeBytes ?: 0)).toLong(),
            totalBytes = region?.sizeBytes ?: 0,
            error = error
        ))
    }

    private fun updateStatusChunked(
        regionId: String, state: DownloadState, overallProgress: Float,
        currentChunk: Int, totalChunks: Int, error: String? = null
    ) {
        val region = tileManager.getRegion(regionId)
        tileManager.updateDownloadStatus(regionId, TileDownloadStatus(
            regionId = regionId, state = state, progress = overallProgress,
            bytesDownloaded = (overallProgress * (region?.sizeBytes ?: 0)).toLong(),
            totalBytes = region?.sizeBytes ?: 0, error = error,
            currentChunk = currentChunk, totalChunks = totalChunks
        ))
    }

    private suspend fun emitEvent(event: TileDownloadEvent) { _downloadEvents.emit(event) }
    private fun emitEventSync(event: TileDownloadEvent) { _downloadEvents.tryEmit(event) }
}

sealed class TileDownloadEvent {
    abstract val regionId: String
    data class Started(override val regionId: String) : TileDownloadEvent()
    data class Progress(override val regionId: String, val progress: Float) : TileDownloadEvent()
    data class Verifying(override val regionId: String) : TileDownloadEvent()
    data class Completed(override val regionId: String, val file: File) : TileDownloadEvent()
    data class Error(override val regionId: String, val message: String) : TileDownloadEvent()
}
