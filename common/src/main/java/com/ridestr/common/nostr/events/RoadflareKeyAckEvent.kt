package com.ridestr.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONObject

/**
 * Kind 3188: RoadFlare Key Acknowledgement (Regular)
 *
 * Ephemeral confirmation from rider to driver after receiving the RoadFlare key.
 * Sent after rider successfully stores the key from Kind 3186.
 * Uses short expiration (5 minutes) to reduce relay storage.
 *
 * Content is NIP-44 encrypted to the driver's identity pubkey.
 *
 * This allows drivers to confirm that a follower has received the current key,
 * completing the key exchange handshake.
 */
object RoadflareKeyAckEvent {

    /** Default expiration time for key ack events (5 minutes) */
    const val DEFAULT_EXPIRATION_SECONDS = 5 * 60L

    /**
     * Create and sign a RoadFlare key acknowledgement event.
     * The content is encrypted to the driver's identity pubkey.
     * Uses short expiration (5 minutes) to reduce relay storage.
     *
     * @param signer The NostrSigner (rider's identity) to sign the event
     * @param driverPubKey The driver's Nostr pubkey (encryption target)
     * @param keyVersion The key version that was received
     * @param keyUpdatedAt The key update timestamp that was received
     * @param expirationSeconds How long until the event expires (default 5 minutes)
     */
    suspend fun create(
        signer: NostrSigner,
        driverPubKey: String,
        keyVersion: Int,
        keyUpdatedAt: Long,
        expirationSeconds: Long = DEFAULT_EXPIRATION_SECONDS
    ): Event? {
        val now = System.currentTimeMillis() / 1000

        // Build the content JSON
        val contentJson = JSONObject().apply {
            put("keyVersion", keyVersion)
            put("keyUpdatedAt", keyUpdatedAt)
            put("status", "received")
            put("riderPubKey", signer.pubKey)
        }

        // Encrypt to driver using NIP-44
        val encryptedContent = try {
            signer.nip44Encrypt(contentJson.toString(), driverPubKey)
        } catch (e: Exception) {
            return null
        }

        val tags = arrayOf(
            arrayOf(RideshareTags.PUBKEY_REF, driverPubKey),
            arrayOf(RideshareTags.HASHTAG, "roadflare-key-ack"),
            arrayOf("expiration", (now + expirationSeconds).toString())
        )

        return signer.sign<Event>(
            createdAt = now,
            kind = RideshareEventKinds.ROADFLARE_KEY_ACK,
            tags = tags,
            content = encryptedContent
        )
    }

    /**
     * Parse and decrypt a RoadFlare key acknowledgement event.
     *
     * @param signer The NostrSigner (driver's identity) to decrypt the content
     * @param event The event to parse
     * @return Decrypted key ack data, or null if parsing/decryption fails
     */
    suspend fun parseAndDecrypt(
        signer: NostrSigner,
        event: Event
    ): RoadflareKeyAckData? {
        if (event.kind != RideshareEventKinds.ROADFLARE_KEY_ACK) return null

        // Check if we're the intended recipient
        val recipientTag = event.tags.find { it.size >= 2 && it[0] == RideshareTags.PUBKEY_REF }
        if (recipientTag?.get(1) != signer.pubKey) return null

        // Check if event has expired
        val expirationTag = event.tags.find { it.size >= 2 && it[0] == "expiration" }
        val expiration = expirationTag?.get(1)?.toLongOrNull()
        if (expiration != null && expiration < System.currentTimeMillis() / 1000) {
            return null // Event has expired
        }

        return try {
            // Decrypt using NIP-44 (encrypted to our pubkey by the rider)
            val decryptedContent = signer.nip44Decrypt(event.content, event.pubKey)
            val json = JSONObject(decryptedContent)

            val keyVersion = json.getInt("keyVersion")
            val keyUpdatedAt = json.getLong("keyUpdatedAt")
            val status = json.getString("status")
            val riderPubKey = json.optString("riderPubKey", event.pubKey)

            RoadflareKeyAckData(
                eventId = event.id,
                riderPubKey = riderPubKey,
                keyVersion = keyVersion,
                keyUpdatedAt = keyUpdatedAt,
                status = status,
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Check if an event is a RoadFlare key ack for the given pubkey.
     * Useful for filtering subscriptions.
     */
    fun isKeyAckFor(event: Event, pubKey: String): Boolean {
        if (event.kind != RideshareEventKinds.ROADFLARE_KEY_ACK) return false
        val recipientTag = event.tags.find { it.size >= 2 && it[0] == RideshareTags.PUBKEY_REF }
        return recipientTag?.get(1) == pubKey
    }
}

/**
 * Parsed and decrypted RoadFlare key acknowledgement data.
 *
 * @param eventId The Nostr event ID
 * @param riderPubKey The rider's pubkey who sent the ack
 * @param keyVersion The key version that was received
 * @param keyUpdatedAt The key update timestamp that was received
 * @param status The ack status ("received")
 * @param createdAt When the event was created
 */
data class RoadflareKeyAckData(
    val eventId: String,
    val riderPubKey: String,
    val keyVersion: Int,
    val keyUpdatedAt: Long,
    val status: String,
    val createdAt: Long
)
