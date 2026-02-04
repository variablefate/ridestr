package com.ridestr.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONObject

/**
 * Kind 30014: RoadFlare Location Broadcast (Parameterized Replaceable)
 *
 * Driver's real-time location, encrypted using Nostr-native NIP-44.
 *
 * ENCRYPTION MODEL (Nostr-native shared keypair):
 * 1. Driver encrypts TO their RoadFlare public key (roadflarePubKey)
 * 2. Followers decrypt using the shared RoadFlare private key + driver's identity pubkey
 *
 * NIP-44 ECDH math makes this work:
 *   Driver encrypts:  nip44Encrypt(content, roadflarePubKey)
 *                     → shared_secret = ECDH(driver_identity_priv, roadflare_pub)
 *
 *   Follower decrypts: nip44Decrypt(ciphertext, driver_identity_pubkey)
 *                      → uses roadflare_priv (shared) + driver_pub
 *                      → shared_secret = ECDH(roadflare_priv, driver_pub)
 *
 *   ECDH is commutative: ECDH(A_priv, B_pub) == ECDH(B_priv, A_pub) ✓
 *
 * Published every ~2 minutes when driver app is active.
 * As a parameterized replaceable event with d-tag "roadflare-location",
 * only the latest location per driver is kept by relays.
 *
 * Status values:
 * - "online": Available for RoadFlare requests
 * - "on_ride": Currently giving a ride
 */
object RoadflareLocationEvent {

    /** The d-tag identifier for RoadFlare location events */
    const val D_TAG = "roadflare-location"

    /** Status values */
    object Status {
        const val ONLINE = "online"
        const val ON_RIDE = "on_ride"
        const val OFFLINE = "offline"
    }

    /**
     * Create and sign a RoadFlare location broadcast event.
     * The content is encrypted to the driver's RoadFlare public key.
     *
     * @param signer The NostrSigner (driver's identity) to sign the event
     * @param roadflarePubKey The driver's RoadFlare public key (encryption target)
     * @param location The location data to broadcast
     * @param keyVersion Current key version for rotation tracking
     */
    suspend fun create(
        signer: NostrSigner,
        roadflarePubKey: String,
        location: RoadflareLocation,
        keyVersion: Int
    ): Event? {
        // Build the content JSON
        val contentJson = JSONObject().apply {
            put("lat", location.lat)
            put("lon", location.lon)
            put("timestamp", location.timestamp)
            put("status", location.status)
            put("onRide", location.onRide)
        }

        // Encrypt to RoadFlare pubkey using NIP-44
        // Followers will decrypt using: roadflarePrivKey + signer.pubKey
        val encryptedContent = try {
            signer.nip44Encrypt(contentJson.toString(), roadflarePubKey)
        } catch (e: Exception) {
            return null
        }

        // Calculate expiration (5 minutes for real-time data)
        val expiration = RideshareExpiration.minutesFromNow(
            RideshareExpiration.ROADFLARE_LOCATION_MINUTES
        )

        val tags = arrayOf(
            arrayOf("d", D_TAG),
            arrayOf(RideshareTags.HASHTAG, "roadflare-location"),
            arrayOf("status", location.status),
            arrayOf("key_version", keyVersion.toString()),
            arrayOf(RideshareTags.EXPIRATION, expiration.toString())
        )

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = RideshareEventKinds.ROADFLARE_LOCATION,
            tags = tags,
            content = encryptedContent
        )
    }

    /**
     * Decrypt a RoadFlare location event as a follower.
     *
     * The follower uses the shared RoadFlare private key combined with
     * the driver's identity public key to decrypt.
     *
     * @param roadflarePrivKey The shared RoadFlare private key (received via Kind 3186)
     * @param driverPubKey The driver's identity public key (event.pubKey)
     * @param event The location event to decrypt
     * @param decryptFn NIP-44 decrypt function: (ciphertext, counterpartyPubKey) -> plaintext
     * @return Decrypted location data, or null if decryption fails
     */
    fun parseAndDecrypt(
        roadflarePrivKey: String,
        driverPubKey: String,
        event: Event,
        decryptFn: (ciphertext: String, counterpartyPubKey: String) -> String?
    ): RoadflareLocationData? {
        if (event.kind != RideshareEventKinds.ROADFLARE_LOCATION) return null

        return try {
            // Decrypt using roadflarePrivKey + driverPubKey
            // The decrypt function should use roadflarePrivKey as the local private key
            val decryptedContent = decryptFn(event.content, driverPubKey) ?: return null
            val json = JSONObject(decryptedContent)

            val location = RoadflareLocation(
                lat = json.getDouble("lat"),
                lon = json.getDouble("lon"),
                timestamp = json.getLong("timestamp"),
                status = json.optString("status", Status.ONLINE),
                onRide = json.optBoolean("onRide", false)
            )

            // Extract key_version from tags
            val keyVersion = getKeyVersion(event)

            // Extract status from tags (fallback to content)
            val tagStatus = event.tags.find { it.size >= 2 && it[0] == "status" }?.get(1)

            RoadflareLocationData(
                eventId = event.id,
                driverPubKey = event.pubKey,
                location = location,
                keyVersion = keyVersion,
                tagStatus = tagStatus ?: location.status,
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract key_version from event tags without decryption.
     */
    fun getKeyVersion(event: Event): Int {
        val keyVersionTag = event.tags.find { it.size >= 2 && it[0] == "key_version" }
        return keyVersionTag?.get(1)?.toIntOrNull() ?: 0
    }

    /**
     * Extract status from event tags without decryption.
     */
    fun getStatus(event: Event): String {
        val statusTag = event.tags.find { it.size >= 2 && it[0] == "status" }
        return statusTag?.get(1) ?: Status.ONLINE
    }

    /**
     * Check if the event is expired based on its expiration tag.
     */
    fun isExpired(event: Event): Boolean {
        val expirationTag = event.tags.find { it.size >= 2 && it[0] == RideshareTags.EXPIRATION }
        val expiration = expirationTag?.get(1)?.toLongOrNull() ?: return false
        return System.currentTimeMillis() / 1000 > expiration
    }
}

/**
 * Location data for RoadFlare broadcast.
 */
data class RoadflareLocation(
    val lat: Double,
    val lon: Double,
    val timestamp: Long = System.currentTimeMillis() / 1000,
    val status: String = RoadflareLocationEvent.Status.ONLINE,
    val onRide: Boolean = false
)

/**
 * Parsed and decrypted RoadFlare location data.
 */
data class RoadflareLocationData(
    val eventId: String,
    val driverPubKey: String,
    val location: RoadflareLocation,
    val keyVersion: Int,
    val tagStatus: String,
    val createdAt: Long
)
