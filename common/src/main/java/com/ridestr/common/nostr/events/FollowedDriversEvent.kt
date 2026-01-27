package com.ridestr.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kind 30011: Followed Drivers List (Parameterized Replaceable)
 *
 * Rider's personal list of favorite drivers for RoadFlare feature.
 *
 * **Privacy Model:**
 * - Driver pubkeys are PUBLIC via `p` tags (allows drivers to query "who follows me")
 * - Sensitive data (RoadFlare keys, notes) are ENCRYPTED in content
 * - Driver names are NOT stored - fetch from Nostr profiles dynamically
 *
 * Event structure:
 * ```json
 * {
 *   "kind": 30011,
 *   "tags": [
 *     ["d", "roadflare-drivers"],
 *     ["t", "roadflare"],
 *     ["p", "driver1_pubkey"],
 *     ["p", "driver2_pubkey"]
 *   ],
 *   "content": "{NIP-44 encrypted sensitive data}"
 * }
 * ```
 *
 * Encrypted content:
 * ```json
 * {
 *   "drivers": [
 *     { "pubkey": "hex", "addedAt": 123, "note": "...", "roadflareKey": {...} }
 *   ],
 *   "updated_at": 123456789
 * }
 * ```
 *
 * The RoadFlare key stored for each driver allows the rider to decrypt
 * that driver's location broadcasts (Kind 30014).
 */
object FollowedDriversEvent {

    /** The d-tag identifier for followed drivers events */
    const val D_TAG = "roadflare-drivers"

    /**
     * Create and sign a followed drivers event.
     * Driver pubkeys are PUBLIC in tags, sensitive data is ENCRYPTED in content.
     *
     * @param signer The NostrSigner to sign and encrypt the event
     * @param drivers List of followed drivers to backup
     */
    suspend fun create(
        signer: NostrSigner,
        drivers: List<FollowedDriver>
    ): Event? {
        val pubKeyHex = signer.pubKey

        // Build drivers array for encrypted content (includes sensitive data)
        val driversArray = JSONArray()
        drivers.forEach { driver ->
            driversArray.put(driver.toJson())
        }

        // Build the content JSON (sensitive data - encrypted)
        val contentJson = JSONObject().apply {
            put("drivers", driversArray)
            put("updated_at", System.currentTimeMillis() / 1000)
        }

        // Encrypt to self using NIP-44 (same key for sender and receiver)
        val encryptedContent = try {
            signer.nip44Encrypt(contentJson.toString(), pubKeyHex)
        } catch (e: Exception) {
            return null
        }

        // Build tags - driver pubkeys are PUBLIC for queryability
        val tagsList = mutableListOf(
            arrayOf("d", D_TAG),
            arrayOf(RideshareTags.HASHTAG, "roadflare")
        )

        // Add each driver as a public p tag (enables "who follows me" queries)
        drivers.forEach { driver ->
            tagsList.add(arrayOf(RideshareTags.PUBKEY_REF, driver.pubkey))
        }

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = RideshareEventKinds.ROADFLARE_FOLLOWED_DRIVERS,
            tags = tagsList.toTypedArray(),
            content = encryptedContent
        )
    }

    /**
     * Parse and decrypt a followed drivers event.
     *
     * @param signer The NostrSigner to decrypt the content
     * @param event The event to parse
     * @return Decrypted followed drivers data, or null if parsing/decryption fails
     */
    suspend fun parseAndDecrypt(
        signer: NostrSigner,
        event: Event
    ): FollowedDriversData? {
        if (event.kind != RideshareEventKinds.ROADFLARE_FOLLOWED_DRIVERS) return null
        if (event.pubKey != signer.pubKey) return null // Can only decrypt our own

        return try {
            // Decrypt using NIP-44 (encrypted to self)
            val decryptedContent = signer.nip44Decrypt(event.content, event.pubKey)
            val json = JSONObject(decryptedContent)

            // Parse drivers from encrypted content
            val drivers = mutableListOf<FollowedDriver>()
            val driversArray = json.optJSONArray("drivers")
            if (driversArray != null) {
                for (i in 0 until driversArray.length()) {
                    drivers.add(FollowedDriver.fromJson(driversArray.getJSONObject(i)))
                }
            }

            val updatedAt = json.getLong("updated_at")

            FollowedDriversData(
                eventId = event.id,
                drivers = drivers,
                updatedAt = updatedAt,
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Extract driver pubkeys from event tags (public data - no decryption needed).
     * Useful for drivers to check who follows them without needing to decrypt.
     *
     * @param event The Kind 30011 event
     * @return List of driver pubkeys found in p tags
     */
    fun extractDriverPubkeys(event: Event): List<String> {
        if (event.kind != RideshareEventKinds.ROADFLARE_FOLLOWED_DRIVERS) return emptyList()

        return event.tags
            .filter { it.getOrNull(0) == RideshareTags.PUBKEY_REF }
            .mapNotNull { it.getOrNull(1) }
    }
}

/**
 * Parsed and decrypted followed drivers data.
 */
data class FollowedDriversData(
    val eventId: String,
    val drivers: List<FollowedDriver>,
    val updatedAt: Long,
    val createdAt: Long
)

/**
 * A single followed driver entry.
 *
 * NOTE: Driver names are NOT stored - fetch from Nostr profiles dynamically.
 * This keeps the backup smaller and ensures names stay up-to-date.
 *
 * @param pubkey Driver's Nostr pubkey (hex)
 * @param addedAt Timestamp when driver was added to favorites
 * @param note Optional note about the driver (e.g., "Great driver, Toyota Camry")
 * @param roadflareKey The driver's RoadFlare key (needed to decrypt their location broadcasts)
 */
data class FollowedDriver(
    val pubkey: String,
    val addedAt: Long,
    val note: String = "",
    val roadflareKey: RoadflareKey? = null
) {
    /**
     * Serialize to JSON.
     * Note: pubkey is also stored in public p-tags for queryability.
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("pubkey", pubkey)
            put("addedAt", addedAt)
            put("note", note)
            roadflareKey?.let { put("roadflareKey", it.toJson()) }
        }
    }

    companion object {
        /**
         * Deserialize from JSON.
         * Handles legacy format that may include deprecated 'name' field.
         */
        fun fromJson(json: JSONObject): FollowedDriver {
            val roadflareKeyJson = json.optJSONObject("roadflareKey")
            val roadflareKey = roadflareKeyJson?.let { RoadflareKey.fromJson(it) }

            return FollowedDriver(
                pubkey = json.getString("pubkey"),
                addedAt = json.getLong("addedAt"),
                note = json.optString("note", ""),
                roadflareKey = roadflareKey
            )
            // Note: 'name' field is intentionally ignored - fetch from profile instead
        }
    }
}

/**
 * A RoadFlare keypair used for location broadcast encryption.
 *
 * Drivers generate this keypair and share the private key with followers.
 * Followers use it to decrypt Kind 30014 location broadcasts.
 *
 * @param privateKey The shared private key (nsec or hex format)
 * @param publicKey The public key (npub or hex format)
 * @param version Monotonic version counter for key rotation tracking
 * @param keyUpdatedAt Timestamp when key was last updated (for stale detection)
 */
data class RoadflareKey(
    val privateKey: String,
    val publicKey: String,
    val version: Int,
    val keyUpdatedAt: Long = 0L
) {
    /**
     * Serialize to JSON.
     */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("privateKey", privateKey)
            put("publicKey", publicKey)
            put("version", version)
            if (keyUpdatedAt > 0) put("keyUpdatedAt", keyUpdatedAt)
        }
    }

    companion object {
        /**
         * Deserialize from JSON.
         */
        fun fromJson(json: JSONObject): RoadflareKey {
            return RoadflareKey(
                privateKey = json.getString("privateKey"),
                publicKey = json.getString("publicKey"),
                version = json.optInt("version", 1),
                keyUpdatedAt = json.optLong("keyUpdatedAt", 0L)
            )
        }
    }
}
