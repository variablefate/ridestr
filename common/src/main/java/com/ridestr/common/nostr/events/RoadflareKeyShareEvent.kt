package com.ridestr.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONObject

/**
 * Kind 3186: RoadFlare Key Share (Regular)
 *
 * DM sharing the RoadFlare private key with a follower.
 * Sent when driver clicks "Accept" on a pending follower.
 * Uses 12-hour expiration to give the rider ample time to receive it.
 *
 * Content is NIP-44 encrypted to the follower's identity pubkey.
 * Includes `keyUpdatedAt` timestamp for stale key detection.
 *
 * ## Key Persistence Flow:
 * 1. Driver sends Kind 3186 → rider receives via subscription
 * 2. Rider stores key locally AND in their Kind 30011 backup
 * 3. On new device or re-add, key is restored from Kind 30011 (not Kind 3186)
 * 4. Kind 3186 only needed for initial handshake or key rotation
 *
 * ## Stale Key Detection:
 * - Driver's Kind 30012 has public `key_updated_at` tag
 * - Rider compares local keyUpdatedAt vs driver's tag
 * - If stale → sends Kind 3188 "stale" ack → driver re-sends Kind 3186
 */
object RoadflareKeyShareEvent {

    /** Default expiration time for key share events (12 hours) */
    const val DEFAULT_EXPIRATION_SECONDS = 12 * 60 * 60L

    /**
     * Create and sign a RoadFlare key share event.
     * The content is encrypted to the follower's identity pubkey.
     *
     * @param signer The NostrSigner (driver's identity) to sign the event
     * @param followerPubKey The follower's Nostr pubkey (encryption target)
     * @param roadflareKey The RoadFlare key to share (privateKey, publicKey, version)
     * @param keyUpdatedAt Timestamp when the key was last updated/rotated
     * @param expirationSeconds How long until the event expires (default 12 hours)
     */
    suspend fun create(
        signer: NostrSigner,
        followerPubKey: String,
        roadflareKey: RoadflareKey,
        keyUpdatedAt: Long,
        expirationSeconds: Long = DEFAULT_EXPIRATION_SECONDS
    ): Event? {
        val now = System.currentTimeMillis() / 1000

        // Build the content JSON
        val contentJson = JSONObject().apply {
            put("roadflareKey", roadflareKey.toJson())
            put("keyUpdatedAt", keyUpdatedAt)
            put("driverPubKey", signer.pubKey) // Driver's identity pubkey for decryption
        }

        // Encrypt to follower using NIP-44
        val encryptedContent = try {
            signer.nip44Encrypt(contentJson.toString(), followerPubKey)
        } catch (e: Exception) {
            return null
        }

        val tags = arrayOf(
            arrayOf(RideshareTags.PUBKEY_REF, followerPubKey),
            arrayOf(RideshareTags.HASHTAG, "roadflare-key"),
            arrayOf("expiration", (now + expirationSeconds).toString())
        )

        return signer.sign<Event>(
            createdAt = now,
            kind = RideshareEventKinds.ROADFLARE_KEY_SHARE,
            tags = tags,
            content = encryptedContent
        )
    }

    /**
     * Parse and decrypt a RoadFlare key share event.
     *
     * @param signer The NostrSigner (follower's identity) to decrypt the content
     * @param event The event to parse
     * @return Decrypted key share data, or null if parsing/decryption fails
     */
    suspend fun parseAndDecrypt(
        signer: NostrSigner,
        event: Event
    ): RoadflareKeyShareData? {
        if (event.kind != RideshareEventKinds.ROADFLARE_KEY_SHARE) return null

        // Check if we're the intended recipient
        val recipientTag = event.tags.find { it.size >= 2 && it[0] == RideshareTags.PUBKEY_REF }
        if (recipientTag?.get(1) != signer.pubKey) {
            android.util.Log.d("RoadflareKeyShareEvent", "Wrong recipient: expected=${signer.pubKey.take(8)}, got=${recipientTag?.get(1)?.take(8)}")
            return null
        }

        // Check if event has expired
        val expirationTag = event.tags.find { it.size >= 2 && it[0] == "expiration" }
        val expiration = expirationTag?.get(1)?.toLongOrNull()
        val now = System.currentTimeMillis() / 1000
        if (expiration != null && expiration < now) {
            android.util.Log.d("RoadflareKeyShareEvent", "Event expired: expiration=$expiration, now=$now, expired ${now - expiration}s ago")
            return null
        }

        return try {
            // Decrypt using NIP-44 (encrypted to our pubkey by the driver)
            val decryptedContent = signer.nip44Decrypt(event.content, event.pubKey)
            val json = JSONObject(decryptedContent)

            // Parse roadflareKey
            val roadflareKeyJson = json.getJSONObject("roadflareKey")
            val roadflareKey = RoadflareKey.fromJson(roadflareKeyJson)

            val keyUpdatedAt = json.optLong("keyUpdatedAt", event.createdAt)
            val driverPubKey = json.getString("driverPubKey")

            RoadflareKeyShareData(
                eventId = event.id,
                driverPubKey = driverPubKey,
                roadflareKey = roadflareKey,
                keyUpdatedAt = keyUpdatedAt,
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            android.util.Log.e("RoadflareKeyShareEvent", "Decrypt/parse failed for eventId=${event.id.take(8)}: ${e.message}")
            null
        }
    }

    /**
     * Check if an event is a RoadFlare key share for the given pubkey.
     * Useful for filtering subscriptions.
     */
    fun isKeyShareFor(event: Event, pubKey: String): Boolean {
        if (event.kind != RideshareEventKinds.ROADFLARE_KEY_SHARE) return false
        val recipientTag = event.tags.find { it.size >= 2 && it[0] == RideshareTags.PUBKEY_REF }
        return recipientTag?.get(1) == pubKey
    }
}

/**
 * Parsed and decrypted RoadFlare key share data.
 *
 * @param eventId The Nostr event ID
 * @param driverPubKey Driver's identity pubkey (for location decryption)
 * @param roadflareKey The shared keypair for decrypting Kind 30014 broadcasts
 * @param keyUpdatedAt Timestamp when the key was last updated/rotated
 * @param createdAt When the event was created
 */
data class RoadflareKeyShareData(
    val eventId: String,
    val driverPubKey: String,
    val roadflareKey: RoadflareKey,
    val keyUpdatedAt: Long,
    val createdAt: Long
)
