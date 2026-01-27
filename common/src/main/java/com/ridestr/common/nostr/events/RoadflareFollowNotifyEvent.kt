package com.ridestr.common.nostr.events

import android.util.Log
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONObject

/**
 * Kind 3187: RoadFlare Follow Notification Event
 *
 * Sent by riders to notify drivers when they add them to RoadFlare favorites.
 * This enables the two-way connection flow:
 * 1. Rider adds driver → sends Kind 3187 to driver
 * 2. Driver receives notification → adds rider to followers → sends Kind 3186 key
 *
 * Content is NIP-44 encrypted to the driver's identity pubkey.
 *
 * Event structure:
 * ```json
 * {
 *   "kind": 3187,
 *   "pubkey": "rider_pubkey_hex",
 *   "content": "{NIP-44 encrypted to driver}",
 *   "tags": [
 *     ["p", "driver_pubkey_hex"],
 *     ["t", "roadflare-follow"]
 *   ]
 * }
 * ```
 *
 * Encrypted content:
 * ```json
 * {
 *   "action": "follow",  // or "unfollow"
 *   "riderName": "Alice",
 *   "timestamp": 1234567890
 * }
 * ```
 */
object RoadflareFollowNotifyEvent {
    private const val TAG = "RoadflareFollowNotify"
    const val T_TAG = "roadflare-follow"

    /**
     * Create a follow notification event with short expiration (5 minutes).
     *
     * This provides immediate notification to drivers when someone follows them.
     * The short expiration reduces relay storage since p-tag queries on Kind 30011
     * are the primary discovery mechanism.
     *
     * @param signer The rider's nostr signer
     * @param driverPubKey The driver's pubkey (hex)
     * @param riderName The rider's display name
     * @param action "follow" or "unfollow"
     * @param expirationMinutes Time until event expires (default 5 minutes)
     * @return The signed event, or null on error
     */
    suspend fun create(
        signer: NostrSigner,
        driverPubKey: String,
        riderName: String,
        action: String = "follow",
        expirationMinutes: Int = 5
    ): Event? {
        return try {
            val content = JSONObject().apply {
                put("action", action)
                put("riderName", riderName)
                put("timestamp", System.currentTimeMillis() / 1000)
            }.toString()

            // Encrypt content to driver
            val encryptedContent = signer.nip44Encrypt(content, driverPubKey)
                ?: throw Exception("NIP-44 encryption failed")

            // Expiration timestamp (5 minutes from now by default)
            val expirationTime = (System.currentTimeMillis() / 1000) + (expirationMinutes * 60)

            val tags = arrayOf(
                arrayOf(RideshareTags.PUBKEY_REF, driverPubKey),
                arrayOf(RideshareTags.HASHTAG, T_TAG),
                arrayOf(RideshareTags.EXPIRATION, expirationTime.toString())
            )

            signer.sign(
                createdAt = System.currentTimeMillis() / 1000,
                kind = RideshareEventKinds.ROADFLARE_FOLLOW_NOTIFY,
                tags = tags,
                content = encryptedContent
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create follow notify event", e)
            null
        }
    }

    /**
     * Parse and decrypt a follow notification event.
     *
     * @param event The raw event
     * @param decryptFn Function to decrypt NIP-44 content
     * @return Parsed data or null on error
     */
    fun parseAndDecrypt(
        event: Event,
        decryptFn: (ciphertext: String, senderPubKey: String) -> String?
    ): RoadflareFollowNotifyData? {
        if (event.kind != RideshareEventKinds.ROADFLARE_FOLLOW_NOTIFY) {
            Log.w(TAG, "Wrong event kind: ${event.kind}")
            return null
        }

        return try {
            val decrypted = decryptFn(event.content, event.pubKey)
                ?: throw Exception("Decryption failed")

            val json = JSONObject(decrypted)
            RoadflareFollowNotifyData(
                riderPubKey = event.pubKey,
                riderName = json.optString("riderName", ""),
                action = json.optString("action", "follow"),
                timestamp = json.optLong("timestamp", System.currentTimeMillis() / 1000)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse follow notify event", e)
            null
        }
    }

    /**
     * Check if an event is a follow notification for this driver.
     */
    fun isFollowNotifyFor(event: Event, driverPubKey: String): Boolean {
        if (event.kind != RideshareEventKinds.ROADFLARE_FOLLOW_NOTIFY) return false
        return event.tags.any { tag ->
            tag.getOrNull(0) == RideshareTags.PUBKEY_REF && tag.getOrNull(1) == driverPubKey
        }
    }
}

/**
 * Data from a parsed follow notification.
 */
data class RoadflareFollowNotifyData(
    val riderPubKey: String,
    val riderName: String,
    val action: String,  // "follow" or "unfollow"
    val timestamp: Long
)
