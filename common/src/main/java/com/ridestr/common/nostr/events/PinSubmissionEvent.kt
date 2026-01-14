package com.ridestr.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONObject

/**
 * Kind 3176: PIN Submission Event
 *
 * Sent by drivers when they arrive at pickup to submit the PIN
 * that the rider told them verbally. Content is NIP-44 encrypted
 * so only the rider can verify it.
 */
object PinSubmissionEvent {

    /**
     * Create and sign a PIN submission event with encrypted content.
     * @param signer The driver's signer
     * @param confirmationEventId The ride confirmation event ID
     * @param riderPubKey The rider's public key
     * @param submittedPin The PIN the driver heard from the rider
     */
    suspend fun create(
        signer: NostrSigner,
        confirmationEventId: String,
        riderPubKey: String,
        submittedPin: String
    ): Event {
        // Build plaintext content with the submitted PIN
        val plaintext = JSONObject().apply {
            put("submitted_pin", submittedPin)
        }.toString()

        // Encrypt using NIP-44 so only the rider can verify
        val encryptedContent = signer.nip44Encrypt(plaintext, riderPubKey)

        // Add NIP-40 expiration (30 minutes)
        val expiration = RideshareExpiration.minutesFromNow(RideshareExpiration.PIN_SUBMISSION_MINUTES)

        val tags = arrayOf(
            arrayOf(RideshareTags.EVENT_REF, confirmationEventId),
            arrayOf(RideshareTags.PUBKEY_REF, riderPubKey),
            arrayOf(RideshareTags.HASHTAG, RideshareTags.RIDESHARE_TAG),
            arrayOf(RideshareTags.EXPIRATION, expiration.toString())
        )

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = RideshareEventKinds.PIN_SUBMISSION,
            tags = tags,
            content = encryptedContent
        )
    }

    /**
     * Parse a PIN submission event (encrypted content).
     */
    fun parseEncrypted(event: Event): PinSubmissionDataEncrypted? {
        if (event.kind != RideshareEventKinds.PIN_SUBMISSION) return null

        return try {
            var confirmationEventId: String? = null
            var riderPubKey: String? = null

            for (tag in event.tags) {
                when (tag.getOrNull(0)) {
                    RideshareTags.EVENT_REF -> confirmationEventId = tag.getOrNull(1)
                    RideshareTags.PUBKEY_REF -> riderPubKey = tag.getOrNull(1)
                }
            }

            if (confirmationEventId == null || riderPubKey == null) return null

            PinSubmissionDataEncrypted(
                eventId = event.id,
                driverPubKey = event.pubKey,
                confirmationEventId = confirmationEventId,
                riderPubKey = riderPubKey,
                encryptedContent = event.content,
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decrypt the PIN submission to verify.
     * @param signer The rider's signer (must be the intended recipient)
     * @param encryptedData The encrypted submission data
     */
    suspend fun decrypt(
        signer: NostrSigner,
        encryptedData: PinSubmissionDataEncrypted
    ): PinSubmissionData? {
        return try {
            val decrypted = signer.nip44Decrypt(encryptedData.encryptedContent, encryptedData.driverPubKey)
            val json = JSONObject(decrypted)
            val submittedPin = json.getString("submitted_pin")

            PinSubmissionData(
                eventId = encryptedData.eventId,
                driverPubKey = encryptedData.driverPubKey,
                confirmationEventId = encryptedData.confirmationEventId,
                riderPubKey = encryptedData.riderPubKey,
                submittedPin = submittedPin,
                createdAt = encryptedData.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Encrypted PIN submission data (before decryption).
 */
data class PinSubmissionDataEncrypted(
    val eventId: String,
    val driverPubKey: String,
    val confirmationEventId: String,
    val riderPubKey: String,
    val encryptedContent: String,
    val createdAt: Long
)

/**
 * Decrypted PIN submission data.
 */
data class PinSubmissionData(
    val eventId: String,
    val driverPubKey: String,
    val confirmationEventId: String,
    val riderPubKey: String,
    val submittedPin: String,
    val createdAt: Long
)
