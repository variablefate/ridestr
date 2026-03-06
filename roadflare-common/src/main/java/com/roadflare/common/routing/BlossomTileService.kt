package com.roadflare.common.routing

import android.util.Log
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for uploading and downloading tiles via Blossom protocol.
 *
 * Blossom is a set of standards (BUDs) for storing blobs addressable by SHA-256 hash.
 * - BUD-01: Server protocol with Nostr authentication (kind 24242)
 * - BUD-02: Upload endpoint (PUT /upload)
 * - BUD-03: User server lists (kind 10063)
 */
@Singleton
class BlossomTileService @Inject constructor() {

    companion object {
        private const val TAG = "BlossomTileService"
        const val BLOSSOM_AUTH_KIND = 24242
        const val USER_SERVER_LIST_KIND = 10063

        val DEFAULT_BLOSSOM_SERVERS = listOf(
            "https://blossom.primal.net",
            "https://cdn.satellite.earth",
            "https://blossom.oxtr.dev"
        )
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun upload(
        file: File,
        serverUrl: String,
        signer: NostrSigner
    ): BlobDescriptor? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Uploading ${file.name} (${file.length()} bytes) to $serverUrl")

            val fileBytes = file.readBytes()
            val sha256 = calculateSha256(fileBytes)

            val authEvent = createAuthEvent(
                signer = signer,
                verb = "upload",
                sha256Hashes = listOf(sha256),
                serverUrl = serverUrl
            )

            val authHeader = "Nostr ${Base64.getEncoder().encodeToString(authEvent.toJson().toByteArray())}"
            val contentType = guessContentType(file.name)
            val requestBody = fileBytes.toRequestBody(contentType.toMediaType())

            val request = Request.Builder()
                .url("$serverUrl/upload")
                .put(requestBody)
                .header("Authorization", authHeader)
                .header("Content-Type", contentType)
                .header("Content-Length", fileBytes.size.toString())
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Upload failed: ${response.code}")
                return@withContext null
            }

            val responseBody = response.body?.string() ?: return@withContext null
            val json = JSONObject(responseBody)
            BlobDescriptor(
                url = json.getString("url"),
                sha256 = json.getString("sha256"),
                size = json.getLong("size"),
                type = json.optString("type", "application/octet-stream"),
                uploaded = json.optLong("uploaded", System.currentTimeMillis() / 1000)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            null
        }
    }

    suspend fun download(
        sha256: String,
        servers: List<String> = DEFAULT_BLOSSOM_SERVERS,
        extension: String = "",
        onProgress: ((Float) -> Unit)? = null
    ): ByteArray? = withContext(Dispatchers.IO) {
        for (server in servers) {
            try {
                val url = "$server/$sha256$extension"
                val request = Request.Builder().url(url).get().build()
                val response = okHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.w(TAG, "Download from $server failed: ${response.code}")
                    continue
                }

                val contentLength = response.body?.contentLength() ?: -1
                val inputStream = response.body?.byteStream() ?: continue

                val buffer = ByteArray(8192)
                val output = java.io.ByteArrayOutputStream()
                var bytesRead: Long = 0
                var read: Int

                while (inputStream.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                    bytesRead += read
                    if (contentLength > 0 && onProgress != null) {
                        onProgress(bytesRead.toFloat() / contentLength)
                    }
                }

                val downloadedBytes = output.toByteArray()
                val downloadedHash = calculateSha256(downloadedBytes)
                if (downloadedHash != sha256) {
                    Log.e(TAG, "SHA256 mismatch! Expected: $sha256, Got: $downloadedHash")
                    continue
                }

                Log.d(TAG, "Download successful from $server (${downloadedBytes.size} bytes)")
                return@withContext downloadedBytes
            } catch (e: Exception) {
                Log.w(TAG, "Download from $server failed", e)
            }
        }

        Log.e(TAG, "All servers failed for $sha256")
        null
    }

    suspend fun exists(sha256: String, serverUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url("$serverUrl/$sha256").head().build()
            okHttpClient.newCall(request).execute().isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    suspend fun createAuthEvent(
        signer: NostrSigner,
        verb: String,
        sha256Hashes: List<String> = emptyList(),
        serverUrl: String? = null,
        expirationSeconds: Long = 300
    ): Event {
        val now = System.currentTimeMillis() / 1000
        val expiration = now + expirationSeconds

        val tagsList = mutableListOf<Array<String>>()
        tagsList.add(arrayOf("t", verb))
        tagsList.add(arrayOf("expiration", expiration.toString()))

        if (sha256Hashes.isNotEmpty()) {
            sha256Hashes.forEach { hash -> tagsList.add(arrayOf("x", hash)) }
        } else if (serverUrl != null) {
            tagsList.add(arrayOf("server", serverUrl))
        }

        val content = "Authorize $verb" + when {
            sha256Hashes.size == 1 -> " for ${sha256Hashes.first().take(8)}..."
            sha256Hashes.size > 1 -> " for ${sha256Hashes.size} blobs"
            serverUrl != null -> " on $serverUrl"
            else -> ""
        }

        return signer.sign<Event>(
            createdAt = now,
            kind = BLOSSOM_AUTH_KIND,
            tags = tagsList.toTypedArray(),
            content = content
        )
    }

    fun calculateSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun guessContentType(filename: String): String {
        return when {
            filename.endsWith(".tar") -> "application/x-tar"
            filename.endsWith(".tar.gz") || filename.endsWith(".tgz") -> "application/gzip"
            filename.endsWith(".zip") -> "application/zip"
            filename.endsWith(".json") -> "application/json"
            else -> "application/octet-stream"
        }
    }
}

data class BlobDescriptor(
    val url: String,
    val sha256: String,
    val size: Long,
    val type: String,
    val uploaded: Long
)
