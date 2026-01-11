package com.ridestr.common.nostr.events

import android.util.Log
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONObject

/**
 * Kind 3178: Rideshare Chat Message
 *
 * Private chat messages between rider and driver during an active ride.
 * Uses simple NIP-44 encryption for reliability.
 *
 * Privacy features:
 * - Custom kind (won't appear in regular Nostr DM clients)
 * - NIP-44 encrypted content
 * - Tied to specific ride via confirmation event reference
 * - Messages should be deleted (NIP-09) after ride completes
 *
 * Structure:
 * - kind: 3178
 * - tags: [["p", recipient], ["e", confirmationEventId], ["t", "rideshare"]]
 * - content: NIP-44 encrypted JSON {"message": "..."}
 */
object RideshareChatEvent {
    private const val TAG = "RideshareChatEvent"
    const val KIND = 3178

    /**
     * Create and encrypt a rideshare chat message.
     *
     * @param signer The sender's signer
     * @param confirmationEventId The ride confirmation event ID (ties message to ride)
     * @param recipientPubKey The recipient's public key
     * @param message The chat message text
     * @return The signed event with encrypted content, ready to publish
     */
    suspend fun create(
        signer: NostrSigner,
        confirmationEventId: String,
        recipientPubKey: String,
        message: String
    ): Event {
        // Create the plaintext content
        val plaintext = JSONObject().apply {
            put("message", message)
        }.toString()

        // Encrypt with NIP-44 to recipient
        val encryptedContent = signer.nip44Encrypt(plaintext, recipientPubKey)

        val tags = arrayOf(
            arrayOf(RideshareTags.PUBKEY_REF, recipientPubKey),
            arrayOf(RideshareTags.EVENT_REF, confirmationEventId),
            arrayOf(RideshareTags.HASHTAG, RideshareTags.RIDESHARE_TAG)
        )

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = KIND,
            tags = tags,
            content = encryptedContent
        )
    }

    /**
     * Parse and decrypt a rideshare chat message.
     *
     * @param signer The recipient's signer (for decryption)
     * @param event The received event
     * @return Decrypted chat data, or null if parsing/decryption fails
     */
    suspend fun parseAndDecrypt(signer: NostrSigner, event: Event): RideshareChatData? {
        if (event.kind != KIND) return null

        return try {
            // Decrypt content using sender's pubkey
            val decrypted = signer.nip44Decrypt(event.content, event.pubKey)
            val json = JSONObject(decrypted)
            val message = json.getString("message")

            var confirmationEventId: String? = null
            var recipientPubKey: String? = null

            for (tag in event.tags) {
                when (tag.getOrNull(0)) {
                    RideshareTags.EVENT_REF -> confirmationEventId = tag.getOrNull(1)
                    RideshareTags.PUBKEY_REF -> recipientPubKey = tag.getOrNull(1)
                }
            }

            if (confirmationEventId == null || recipientPubKey == null) {
                Log.w(TAG, "Missing required tags in chat message")
                return null
            }

            RideshareChatData(
                eventId = event.id,
                senderPubKey = event.pubKey,
                confirmationEventId = confirmationEventId,
                recipientPubKey = recipientPubKey,
                message = message,
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse/decrypt chat message", e)
            null
        }
    }

    /**
     * Parse a rideshare chat message without decryption (for events we sent).
     * Used when we need to parse our own messages that we already have the plaintext for.
     */
    fun parseWithoutDecrypt(event: Event, knownMessage: String): RideshareChatData? {
        if (event.kind != KIND) return null

        return try {
            var confirmationEventId: String? = null
            var recipientPubKey: String? = null

            for (tag in event.tags) {
                when (tag.getOrNull(0)) {
                    RideshareTags.EVENT_REF -> confirmationEventId = tag.getOrNull(1)
                    RideshareTags.PUBKEY_REF -> recipientPubKey = tag.getOrNull(1)
                }
            }

            if (confirmationEventId == null || recipientPubKey == null) return null

            RideshareChatData(
                eventId = event.id,
                senderPubKey = event.pubKey,
                confirmationEventId = confirmationEventId,
                recipientPubKey = recipientPubKey,
                message = knownMessage,
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Parsed data from a rideshare chat message.
 */
data class RideshareChatData(
    val eventId: String,
    val senderPubKey: String,
    val confirmationEventId: String,
    val recipientPubKey: String,
    val message: String,
    val createdAt: Long
)
