package com.ridestr.common.routing

import android.util.Log
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Service for uploading and downloading tiles via Blossom protocol.
 *
 * Blossom is a set of standards (BUDs) for storing blobs addressable by SHA-256 hash.
 * - BUD-01: Server protocol with Nostr authentication (kind 24242)
 * - BUD-02: Upload endpoint (PUT /upload)
 * - BUD-03: User server lists (kind 10063)
 *
 * @see https://github.com/hzrd149/blossom
 */
class BlossomTileService(
    private val okHttpClient: OkHttpClient = createDefaultClient()
) {
    companion object {
        private const val TAG = "BlossomTileService"

        // Blossom protocol constants
        const val BLOSSOM_AUTH_KIND = 24242
        const val USER_SERVER_LIST_KIND = 10063
        const val TILE_AVAILABILITY_KIND = 30078

        // Default Blossom servers for testing
        val DEFAULT_BLOSSOM_SERVERS = listOf(
            "https://blossom.primal.net",
            "https://cdn.satellite.earth",
            "https://blossom.oxtr.dev"
        )

        private fun createDefaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }

    /**
     * Upload a file to a Blossom server.
     *
     * @param file The file to upload
     * @param serverUrl The Blossom server URL (e.g., "https://blossom.primal.net")
     * @param signer NostrSigner for creating auth event
     * @return BlobDescriptor with url, sha256, size, type on success, null on failure
     */
    suspend fun upload(
        file: File,
        serverUrl: String,
        signer: NostrSigner
    ): BlobDescriptor? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Uploading ${file.name} (${file.length()} bytes) to $serverUrl")

            // Calculate SHA256 of file content
            val fileBytes = file.readBytes()
            val sha256 = calculateSha256(fileBytes)
            Log.d(TAG, "File SHA256: $sha256")

            // Create authorization event (kind 24242)
            val authEvent = createAuthEvent(
                signer = signer,
                verb = "upload",
                sha256Hashes = listOf(sha256),
                serverUrl = serverUrl
            )

            // Encode auth event as base64 for Authorization header
            val authHeader = "Nostr ${Base64.getEncoder().encodeToString(authEvent.toJson().toByteArray())}"

            // Build upload request
            val contentType = guessContentType(file.name)
            val requestBody = fileBytes.toRequestBody(contentType.toMediaType())

            val request = Request.Builder()
                .url("$serverUrl/upload")
                .put(requestBody)
                .header("Authorization", authHeader)
                .header("Content-Type", contentType)
                .header("Content-Length", fileBytes.size.toString())
                .build()

            // Execute request
            val response = okHttpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                Log.e(TAG, "Upload failed: ${response.code} - $errorBody")
                return@withContext null
            }

            // Parse response
            val responseBody = response.body?.string()
            if (responseBody == null) {
                Log.e(TAG, "Empty response body")
                return@withContext null
            }

            val json = JSONObject(responseBody)
            val descriptor = BlobDescriptor(
                url = json.getString("url"),
                sha256 = json.getString("sha256"),
                size = json.getLong("size"),
                type = json.optString("type", "application/octet-stream"),
                uploaded = json.optLong("uploaded", System.currentTimeMillis() / 1000)
            )

            Log.d(TAG, "Upload successful: ${descriptor.url}")
            descriptor

        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            null
        }
    }

    /**
     * Download a blob from Blossom servers by SHA256 hash.
     * Tries multiple servers in order until success.
     *
     * @param sha256 The SHA256 hash of the blob
     * @param servers List of Blossom server URLs to try
     * @param extension Optional file extension (e.g., ".tar")
     * @param onProgress Optional progress callback (0.0 to 1.0)
     * @return The downloaded bytes, or null if all servers fail
     */
    suspend fun download(
        sha256: String,
        servers: List<String> = DEFAULT_BLOSSOM_SERVERS,
        extension: String = "",
        onProgress: ((Float) -> Unit)? = null
    ): ByteArray? = withContext(Dispatchers.IO) {
        for (server in servers) {
            try {
                Log.d(TAG, "Trying to download $sha256 from $server")

                val url = "$server/$sha256$extension"
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()

                val response = okHttpClient.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.w(TAG, "Download from $server failed: ${response.code}")
                    continue
                }

                val contentLength = response.body?.contentLength() ?: -1
                val inputStream = response.body?.byteStream() ?: continue

                // Read with progress reporting
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

                // Verify SHA256
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

    /**
     * Check if a blob exists on a Blossom server.
     * Uses HEAD request to avoid downloading the full file.
     *
     * @param sha256 The SHA256 hash to check
     * @param serverUrl The Blossom server URL
     * @return true if the blob exists
     */
    suspend fun exists(
        sha256: String,
        serverUrl: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$serverUrl/$sha256")
                .head()
                .build()

            val response = okHttpClient.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            Log.w(TAG, "Existence check failed for $sha256 on $serverUrl", e)
            false
        }
    }

    /**
     * Create a Blossom authorization event (kind 24242).
     *
     * @param signer NostrSigner to sign the event
     * @param verb The action: "get", "upload", "list", or "delete"
     * @param sha256Hashes List of SHA256 hashes this auth is valid for
     * @param serverUrl The server URL (used if sha256Hashes is empty)
     * @param expirationSeconds How long the auth is valid (default 5 minutes)
     */
    suspend fun createAuthEvent(
        signer: NostrSigner,
        verb: String,
        sha256Hashes: List<String> = emptyList(),
        serverUrl: String? = null,
        expirationSeconds: Long = 300
    ): Event {
        val now = System.currentTimeMillis() / 1000
        val expiration = now + expirationSeconds

        // Build tags
        val tagsList = mutableListOf<Array<String>>()

        // Add verb tag
        tagsList.add(arrayOf("t", verb))

        // Add expiration tag (NIP-40)
        tagsList.add(arrayOf("expiration", expiration.toString()))

        // Add x tags for specific hashes, or server tag for broad access
        if (sha256Hashes.isNotEmpty()) {
            sha256Hashes.forEach { hash ->
                tagsList.add(arrayOf("x", hash))
            }
        } else if (serverUrl != null) {
            tagsList.add(arrayOf("server", serverUrl))
        }

        val tags = tagsList.toTypedArray()

        // Content is human-readable description
        val content = "Authorize $verb" + when {
            sha256Hashes.size == 1 -> " for ${sha256Hashes.first().take(8)}..."
            sha256Hashes.size > 1 -> " for ${sha256Hashes.size} blobs"
            serverUrl != null -> " on $serverUrl"
            else -> ""
        }

        return signer.sign<Event>(
            createdAt = now,
            kind = BLOSSOM_AUTH_KIND,
            tags = tags,
            content = content
        )
    }

    /**
     * Calculate SHA256 hash of bytes and return as lowercase hex string.
     */
    fun calculateSha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(bytes)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Guess content type from file extension.
     */
    private fun guessContentType(filename: String): String {
        return when {
            filename.endsWith(".tar") -> "application/x-tar"
            filename.endsWith(".tar.gz") || filename.endsWith(".tgz") -> "application/gzip"
            filename.endsWith(".zip") -> "application/zip"
            filename.endsWith(".json") -> "application/json"
            filename.endsWith(".png") -> "image/png"
            filename.endsWith(".jpg") || filename.endsWith(".jpeg") -> "image/jpeg"
            else -> "application/octet-stream"
        }
    }
}

/**
 * Blob descriptor returned by Blossom upload.
 *
 * @param url Public URL to retrieve the blob
 * @param sha256 SHA256 hash of the blob content
 * @param size Size in bytes
 * @param type MIME type
 * @param uploaded Unix timestamp when uploaded
 */
data class BlobDescriptor(
    val url: String,
    val sha256: String,
    val size: Long,
    val type: String,
    val uploaded: Long
)
