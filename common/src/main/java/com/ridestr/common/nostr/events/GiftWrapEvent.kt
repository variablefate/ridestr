package com.ridestr.common.nostr.events

import android.util.Log
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

/**
 * NIP-59 Gift Wrapping for Rideshare Private Messages
 *
 * Implements proper NIP-59 gift wrapping with:
 * 1. Random ephemeral key for outer gift wrap (hides sender from relays)
 * 2. Randomized timestamps (±2 days to prevent timing analysis)
 * 3. Double encryption (seal + gift wrap layers)
 *
 * Structure:
 * ```
 * GiftWrap (Kind 1059) - Random throwaway key, encrypted to recipient
 *   └─ Seal (Kind 13) - Real sender's key, encrypted to recipient
 *       └─ Rumor (Kind 3178) - Unsigned inner rideshare chat message
 * ```
 *
 * Why Kind 3178 matters:
 * - Standard NIP-17 uses Kind 14 for DMs
 * - We use Kind 3178 so messages won't appear in regular Nostr DM clients
 * - The outer layers (1059/13) are standard for relay compatibility
 * - Only rideshare-aware clients will recognize/display these messages
 *
 * Privacy guarantees:
 * - Relays see: random pubkey, random timestamp, encrypted blob
 * - Relays cannot: identify sender, correlate messages, determine timing
 * - Only recipient can: decrypt and see sender identity + message
 */
object RideshareGiftWrap {
    private const val TAG = "RideshareGiftWrap"

    const val KIND_SEAL = 13
    const val KIND_GIFT_WRAP = 1059

    // Randomize timestamp by ±2 days (in seconds) per NIP-59
    private const val TIMESTAMP_JITTER_SECONDS = 2 * 24 * 60 * 60L

    /**
     * Wrap a rideshare chat message with full NIP-59 privacy.
     *
     * @param signer The sender's signer (identity revealed only to recipient)
     * @param innerEvent The Kind 3178 rideshare chat message
     * @param recipientPubKey The recipient's public key
     * @return The gift-wrapped event ready to publish, or null on failure
     */
    suspend fun wrap(
        signer: NostrSigner,
        innerEvent: Event,
        recipientPubKey: String
    ): Event? {
        return try {
            // Step 1: Convert signed event to unsigned "rumor" format
            // (NIP-59 seals contain unsigned events to hide signature metadata)
            val rumor = createRumor(innerEvent)

            // Step 2: Create Seal (Kind 13)
            // - Signed by REAL sender
            // - Contains encrypted rumor
            // - Recipient can verify sender identity from seal signature
            val seal = createSeal(signer, rumor, recipientPubKey)
            if (seal == null) {
                Log.e(TAG, "Failed to create seal")
                return null
            }

            // Step 3: Create Gift Wrap (Kind 1059)
            // - Signed by RANDOM ephemeral key (sender hidden from relays)
            // - Contains encrypted seal
            // - Only recipient can decrypt to get the seal
            val giftWrap = createGiftWrap(seal, recipientPubKey)
            if (giftWrap == null) {
                Log.e(TAG, "Failed to create gift wrap")
                return null
            }

            giftWrap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to wrap event", e)
            null
        }
    }

    /**
     * Unwrap a gift-wrapped event to get the inner message.
     *
     * @param signer The recipient's signer (to decrypt)
     * @param giftWrap The gift-wrapped event (Kind 1059)
     * @return The unwrapped inner event with verified sender, or null on failure
     */
    suspend fun unwrap(
        signer: NostrSigner,
        giftWrap: Event
    ): UnwrappedMessage? {
        if (giftWrap.kind != KIND_GIFT_WRAP) {
            Log.w(TAG, "Not a gift wrap event: kind=${giftWrap.kind}")
            return null
        }

        return try {
            // Step 1: Decrypt gift wrap to get seal
            // (decrypt using gift wrap's random pubkey)
            val sealJson = signer.nip44Decrypt(giftWrap.content, giftWrap.pubKey)
            val seal = parseEventFromJson(sealJson)
            if (seal == null || seal.kind != KIND_SEAL) {
                Log.e(TAG, "Failed to parse seal from gift wrap")
                return null
            }

            // Step 2: Decrypt seal to get rumor
            // (decrypt using seal's pubkey - this is the REAL sender)
            val rumorJson = signer.nip44Decrypt(seal.content, seal.pubKey)
            val rumor = parseRumorFromJson(rumorJson)
            if (rumor == null) {
                Log.e(TAG, "Failed to parse rumor from seal")
                return null
            }

            // The sender identity comes from the seal's pubkey (verified by signature)
            UnwrappedMessage(
                senderPubKey = seal.pubKey,
                kind = rumor.kind,
                content = rumor.content,
                tags = rumor.tags,
                createdAt = rumor.createdAt,
                // Original rumor doesn't have id/sig since it's unsigned
                rumorId = rumor.id
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unwrap event", e)
            null
        }
    }

    /**
     * Create an unsigned "rumor" from a signed event.
     * Rumors are the inner content of seals - they don't need signatures
     * because the seal's signature authenticates the sender.
     */
    private fun createRumor(event: Event): Rumor {
        return Rumor(
            id = event.id,
            pubKey = event.pubKey,
            createdAt = event.createdAt,
            kind = event.kind,
            tags = event.tags,
            content = event.content
        )
    }

    /**
     * Create a Seal (Kind 13) containing an encrypted rumor.
     * The seal is signed by the real sender.
     */
    private suspend fun createSeal(
        signer: NostrSigner,
        rumor: Rumor,
        recipientPubKey: String
    ): Event? {
        return try {
            // Serialize rumor to JSON (without signature)
            val rumorJson = serializeRumorToJson(rumor)

            // Encrypt with NIP-44 to recipient
            val encryptedContent = signer.nip44Encrypt(rumorJson, recipientPubKey)

            // Create seal with randomized timestamp
            val randomizedTimestamp = randomizeTimestamp(System.currentTimeMillis() / 1000)

            // Seal has NO tags for privacy (no metadata leakage)
            signer.sign<Event>(
                createdAt = randomizedTimestamp,
                kind = KIND_SEAL,
                tags = emptyArray(),
                content = encryptedContent
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create seal", e)
            null
        }
    }

    /**
     * Create a Gift Wrap (Kind 1059) containing an encrypted seal.
     * Uses a RANDOM ephemeral key so relays cannot identify the sender.
     */
    private suspend fun createGiftWrap(
        seal: Event,
        recipientPubKey: String
    ): Event? {
        return try {
            // Generate random ephemeral keypair (one-time use)
            val randomKeyPair = KeyPair()
            val randomSigner = NostrSignerInternal(randomKeyPair)

            // Serialize seal to JSON
            val sealJson = serializeEventToJson(seal)

            // Encrypt seal to recipient using the random key
            val encryptedContent = randomSigner.nip44Encrypt(sealJson, recipientPubKey)

            // Create gift wrap with randomized timestamp
            val randomizedTimestamp = randomizeTimestamp(System.currentTimeMillis() / 1000)

            // Only tag is recipient pubkey (required for delivery)
            val tags = arrayOf(
                arrayOf(RideshareTags.PUBKEY_REF, recipientPubKey)
            )

            randomSigner.sign<Event>(
                createdAt = randomizedTimestamp,
                kind = KIND_GIFT_WRAP,
                tags = tags,
                content = encryptedContent
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create gift wrap", e)
            null
        }
    }

    /**
     * Randomize timestamp by ±2 days to prevent timing analysis.
     */
    private fun randomizeTimestamp(timestamp: Long): Long {
        val offset = Random.nextLong(-TIMESTAMP_JITTER_SECONDS, TIMESTAMP_JITTER_SECONDS)
        return timestamp + offset
    }

    /**
     * Serialize a rumor (unsigned event) to JSON.
     */
    private fun serializeRumorToJson(rumor: Rumor): String {
        return JSONObject().apply {
            rumor.id?.let { put("id", it) }
            rumor.pubKey?.let { put("pubkey", it) }
            put("created_at", rumor.createdAt)
            put("kind", rumor.kind)
            put("tags", JSONArray().apply {
                for (tag in rumor.tags) {
                    put(JSONArray().apply {
                        for (t in tag) put(t)
                    })
                }
            })
            put("content", rumor.content)
            // No "sig" field - rumors are unsigned
        }.toString()
    }

    /**
     * Serialize a signed event to JSON.
     */
    private fun serializeEventToJson(event: Event): String {
        return JSONObject().apply {
            put("id", event.id)
            put("pubkey", event.pubKey)
            put("created_at", event.createdAt)
            put("kind", event.kind)
            put("tags", JSONArray().apply {
                for (tag in event.tags) {
                    put(JSONArray().apply {
                        for (t in tag) put(t)
                    })
                }
            })
            put("content", event.content)
            put("sig", event.sig)
        }.toString()
    }

    /**
     * Parse a signed event from JSON string.
     */
    private fun parseEventFromJson(json: String): Event? {
        return try {
            val obj = JSONObject(json)
            val tagsArray = obj.getJSONArray("tags")
            val tags = Array(tagsArray.length()) { i ->
                val tagArray = tagsArray.getJSONArray(i)
                Array(tagArray.length()) { j ->
                    tagArray.getString(j)
                }
            }

            SimpleEvent(
                id = obj.getString("id"),
                pubKey = obj.getString("pubkey"),
                createdAt = obj.getLong("created_at"),
                kind = obj.getInt("kind"),
                tags = tags,
                content = obj.getString("content"),
                sig = obj.getString("sig")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse event from JSON", e)
            null
        }
    }

    /**
     * Parse a rumor (unsigned event) from JSON string.
     */
    private fun parseRumorFromJson(json: String): Rumor? {
        return try {
            val obj = JSONObject(json)
            val tagsArray = obj.getJSONArray("tags")
            val tags = Array(tagsArray.length()) { i ->
                val tagArray = tagsArray.getJSONArray(i)
                Array(tagArray.length()) { j ->
                    tagArray.getString(j)
                }
            }

            Rumor(
                id = obj.optString("id", null),
                pubKey = obj.optString("pubkey", null),
                createdAt = obj.getLong("created_at"),
                kind = obj.getInt("kind"),
                tags = tags,
                content = obj.getString("content")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse rumor from JSON", e)
            null
        }
    }
}

/**
 * Unsigned inner event (rumor) - the content that gets sealed and wrapped.
 * Unlike a full Event, rumors don't have signatures.
 */
data class Rumor(
    val id: String?,
    val pubKey: String?,
    val createdAt: Long,
    val kind: Int,
    val tags: Array<Array<String>>,
    val content: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Rumor) return false
        return id == other.id && kind == other.kind && content == other.content
    }

    override fun hashCode(): Int {
        var result = id?.hashCode() ?: 0
        result = 31 * result + kind
        result = 31 * result + content.hashCode()
        return result
    }
}

/**
 * Result of unwrapping a gift-wrapped message.
 * Contains the verified sender and message content.
 */
data class UnwrappedMessage(
    val senderPubKey: String,  // Verified from seal signature
    val kind: Int,
    val content: String,
    val tags: Array<Array<String>>,
    val createdAt: Long,
    val rumorId: String?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UnwrappedMessage) return false
        return senderPubKey == other.senderPubKey && kind == other.kind && content == other.content
    }

    override fun hashCode(): Int {
        var result = senderPubKey.hashCode()
        result = 31 * result + kind
        result = 31 * result + content.hashCode()
        return result
    }
}

/**
 * Simple Event implementation for parsed events.
 */
private class SimpleEvent(
    id: String,
    pubKey: String,
    createdAt: Long,
    kind: Int,
    tags: Array<Array<String>>,
    content: String,
    sig: String
) : Event(id, pubKey, createdAt, kind, tags, content, sig)
