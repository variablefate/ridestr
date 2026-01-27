package com.ridestr.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kind 30012: Driver RoadFlare State (Parameterized Replaceable)
 *
 * Driver's complete RoadFlare state, encrypted to self.
 * Contains:
 * - roadflareKey: The Nostr keypair used to encrypt location broadcasts
 * - followers: List of riders who have been sent the decryption key
 * - muted: List of riders excluded from broadcasts (on key rotation)
 *
 * This is the single source of truth for cross-device sync and key import recovery.
 * When a driver imports their key on a new device, this event restores their
 * complete RoadFlare state including the keypair needed to resume broadcasting.
 *
 * Content is NIP-44 encrypted to self (driver's own pubkey).
 *
 * As a parameterized replaceable event with d-tag "roadflare-state",
 * only the latest state per driver is kept by relays.
 *
 * Includes "key_version" tag for quick staleness check without decryption.
 */
object DriverRoadflareStateEvent {

    /** The d-tag identifier for driver RoadFlare state events */
    const val D_TAG = "roadflare-state"

    /**
     * Create and sign a driver RoadFlare state event.
     * The content is encrypted to the driver's own pubkey.
     *
     * @param signer The NostrSigner to sign and encrypt the event
     * @param state The complete RoadFlare state to backup
     */
    suspend fun create(
        signer: NostrSigner,
        state: DriverRoadflareState
    ): Event? {
        val pubKeyHex = signer.pubKey

        // Build followers array
        val followersArray = JSONArray()
        state.followers.forEach { follower ->
            followersArray.put(follower.toJson())
        }

        // Build muted array
        val mutedArray = JSONArray()
        state.muted.forEach { muted ->
            mutedArray.put(muted.toJson())
        }

        // Build the content JSON
        val contentJson = JSONObject().apply {
            state.roadflareKey?.let { put("roadflareKey", it.toJson()) }
            put("followers", followersArray)
            put("muted", mutedArray)
            state.keyUpdatedAt?.let { put("keyUpdatedAt", it) }
            state.lastBroadcastAt?.let { put("lastBroadcastAt", it) }
            put("updated_at", System.currentTimeMillis() / 1000)
        }

        // Encrypt to self using NIP-44 (same key for sender and receiver)
        val encryptedContent = try {
            signer.nip44Encrypt(contentJson.toString(), pubKeyHex)
        } catch (e: Exception) {
            return null
        }

        // Include key_version and key_updated_at in public tags for quick staleness check
        val keyVersion = state.roadflareKey?.version?.toString() ?: "0"
        val keyUpdatedAt = state.keyUpdatedAt?.toString() ?: (System.currentTimeMillis() / 1000).toString()

        val tags = arrayOf(
            arrayOf("d", D_TAG),
            arrayOf(RideshareTags.HASHTAG, "roadflare"),
            arrayOf("key_version", keyVersion),
            arrayOf("key_updated_at", keyUpdatedAt)
        )

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = RideshareEventKinds.ROADFLARE_DRIVER_STATE,
            tags = tags,
            content = encryptedContent
        )
    }

    /**
     * Parse and decrypt a driver RoadFlare state event.
     *
     * @param signer The NostrSigner to decrypt the content
     * @param event The event to parse
     * @return Decrypted RoadFlare state, or null if parsing/decryption fails
     */
    suspend fun parseAndDecrypt(
        signer: NostrSigner,
        event: Event
    ): DriverRoadflareState? {
        if (event.kind != RideshareEventKinds.ROADFLARE_DRIVER_STATE) return null
        if (event.pubKey != signer.pubKey) return null // Can only decrypt our own

        return try {
            // Decrypt using NIP-44 (encrypted to self)
            val decryptedContent = signer.nip44Decrypt(event.content, event.pubKey)
            val json = JSONObject(decryptedContent)

            // Parse roadflareKey
            val roadflareKeyJson = json.optJSONObject("roadflareKey")
            val roadflareKey = roadflareKeyJson?.let { DriverRoadflareKey.fromJson(it) }

            // Parse followers
            val followers = mutableListOf<RoadflareFollower>()
            val followersArray = json.optJSONArray("followers")
            if (followersArray != null) {
                for (i in 0 until followersArray.length()) {
                    followers.add(RoadflareFollower.fromJson(followersArray.getJSONObject(i)))
                }
            }

            // Parse muted
            val muted = mutableListOf<MutedRider>()
            val mutedArray = json.optJSONArray("muted")
            if (mutedArray != null) {
                for (i in 0 until mutedArray.length()) {
                    muted.add(MutedRider.fromJson(mutedArray.getJSONObject(i)))
                }
            }

            val keyUpdatedAt = if (json.has("keyUpdatedAt")) json.getLong("keyUpdatedAt") else null
            val lastBroadcastAt = if (json.has("lastBroadcastAt")) json.getLong("lastBroadcastAt") else null
            val updatedAt = json.optLong("updated_at", event.createdAt)

            DriverRoadflareState(
                eventId = event.id,
                roadflareKey = roadflareKey,
                followers = followers,
                muted = muted,
                keyUpdatedAt = keyUpdatedAt,
                lastBroadcastAt = lastBroadcastAt,
                updatedAt = updatedAt,
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract key_version from event tags without decryption.
     * Useful for quick staleness check across devices.
     */
    fun getKeyVersion(event: Event): Int {
        val keyVersionTag = event.tags.find { it.size >= 2 && it[0] == "key_version" }
        return keyVersionTag?.get(1)?.toIntOrNull() ?: 0
    }

    /**
     * Extract key_updated_at from event tags without decryption.
     * Used by riders to detect if their stored key is stale.
     *
     * @param event The Kind 30012 event
     * @return The key update timestamp, or null if not present
     */
    fun getKeyUpdatedAt(event: Event): Long? {
        val keyUpdatedAtTag = event.tags.find { it.size >= 2 && it[0] == "key_updated_at" }
        return keyUpdatedAtTag?.get(1)?.toLongOrNull()
    }
}

/**
 * Complete driver RoadFlare state.
 *
 * @param eventId The Nostr event ID (if loaded from relay)
 * @param roadflareKey The keypair used for location broadcast encryption
 * @param followers List of riders who have been sent the decryption key
 * @param muted List of riders excluded from broadcasts
 * @param keyUpdatedAt Timestamp when key was last updated/rotated (for stale detection)
 * @param lastBroadcastAt Timestamp of last location broadcast
 * @param updatedAt Timestamp when state was last updated
 * @param createdAt Timestamp when event was created
 */
data class DriverRoadflareState(
    val eventId: String? = null,
    val roadflareKey: DriverRoadflareKey?,
    val followers: List<RoadflareFollower>,
    val muted: List<MutedRider>,
    val keyUpdatedAt: Long? = null,
    val lastBroadcastAt: Long?,
    val updatedAt: Long = System.currentTimeMillis() / 1000,
    val createdAt: Long = System.currentTimeMillis() / 1000
)

/**
 * The driver's RoadFlare keypair used for location broadcast encryption.
 *
 * This is a separate Nostr keypair (NOT the driver's identity key).
 * The private key is shared with followers so they can decrypt location broadcasts.
 *
 * @param privateKey The private key (hex format) - shared with followers
 * @param publicKey The public key (hex format) - used as encryption target
 * @param version Monotonic version counter for key rotation tracking
 * @param createdAt Timestamp when this keypair was generated
 */
data class DriverRoadflareKey(
    val privateKey: String,
    val publicKey: String,
    val version: Int,
    val createdAt: Long
) {
    /**
     * Serialize to JSON.
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("privateKey", privateKey)
            put("publicKey", publicKey)
            put("version", version)
            put("createdAt", createdAt)
        }
    }

    companion object {
        /**
         * Deserialize from JSON.
         */
        fun fromJson(json: JSONObject): DriverRoadflareKey {
            return DriverRoadflareKey(
                privateKey = json.getString("privateKey"),
                publicKey = json.getString("publicKey"),
                version = json.optInt("version", 1),
                createdAt = json.optLong("createdAt", System.currentTimeMillis() / 1000)
            )
        }
    }
}

/**
 * A follower entry - a rider who wants to follow this driver's location.
 *
 * @param pubkey Rider's Nostr pubkey (hex)
 * @param name Rider's display name (from follow notification)
 * @param addedAt Timestamp when follower was added
 * @param approved Whether driver has approved this follower (pending if false)
 * @param keyVersionSent Which key version was sent to this follower (0 if not yet sent)
 */
data class RoadflareFollower(
    val pubkey: String,
    val name: String = "",
    val addedAt: Long,
    val approved: Boolean = false,
    val keyVersionSent: Int = 0
) {
    /**
     * Serialize to JSON.
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("pubkey", pubkey)
            put("name", name)
            put("addedAt", addedAt)
            put("approved", approved)
            put("keyVersionSent", keyVersionSent)
        }
    }

    companion object {
        /**
         * Deserialize from JSON.
         */
        fun fromJson(json: JSONObject): RoadflareFollower {
            return RoadflareFollower(
                pubkey = json.getString("pubkey"),
                name = json.optString("name", ""),
                addedAt = json.getLong("addedAt"),
                approved = json.optBoolean("approved", true), // Default true for backwards compat
                keyVersionSent = json.optInt("keyVersionSent", 0)
            )
        }
    }
}

/**
 * A muted rider entry - excluded from location broadcasts.
 *
 * When a rider is muted, the driver rotates their RoadFlare key and
 * sends the new key to all non-muted followers. The muted rider
 * still has the old key but cannot decrypt new broadcasts.
 *
 * @param pubkey Rider's Nostr pubkey (hex)
 * @param mutedAt Timestamp when rider was muted
 * @param reason Optional reason for muting
 */
data class MutedRider(
    val pubkey: String,
    val mutedAt: Long,
    val reason: String = ""
) {
    /**
     * Serialize to JSON.
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("pubkey", pubkey)
            put("mutedAt", mutedAt)
            put("reason", reason)
        }
    }

    companion object {
        /**
         * Deserialize from JSON.
         */
        fun fromJson(json: JSONObject): MutedRider {
            return MutedRider(
                pubkey = json.getString("pubkey"),
                mutedAt = json.getLong("mutedAt"),
                reason = json.optString("reason", "")
            )
        }
    }
}
