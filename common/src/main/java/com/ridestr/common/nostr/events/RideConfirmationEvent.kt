package com.ridestr.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONObject

/**
 * Kind 3175: Ride Confirmation Event
 *
 * Sent by riders to confirm the ride after driver acceptance.
 * Contains NIP-44 encrypted precise pickup location for privacy.
 */
object RideConfirmationEvent {

    /**
     * Create and sign a ride confirmation event with encrypted content.
     */
    suspend fun create(
        signer: NostrSigner,
        acceptanceEventId: String,
        driverPubKey: String,
        precisePickup: Location
    ): Event {
        // Build the plaintext content
        val plaintext = JSONObject().apply {
            put("precise_pickup", precisePickup.toJson())
        }.toString()

        // Encrypt using NIP-44
        val encryptedContent = signer.nip44Encrypt(plaintext, driverPubKey)

        // Add NIP-40 expiration (8 hours)
        val expiration = RideshareExpiration.hoursFromNow(RideshareExpiration.RIDE_CONFIRMATION_HOURS)

        val tags = arrayOf(
            arrayOf(RideshareTags.EVENT_REF, acceptanceEventId),
            arrayOf(RideshareTags.PUBKEY_REF, driverPubKey),
            arrayOf(RideshareTags.HASHTAG, RideshareTags.RIDESHARE_TAG),
            arrayOf(RideshareTags.EXPIRATION, expiration.toString())
        )

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = RideshareEventKinds.RIDE_CONFIRMATION,
            tags = tags,
            content = encryptedContent
        )
    }

    /**
     * Parse a ride confirmation event (encrypted content must be decrypted separately).
     */
    fun parseEncrypted(event: Event): RideConfirmationDataEncrypted? {
        if (event.kind != RideshareEventKinds.RIDE_CONFIRMATION) return null

        return try {
            var acceptanceEventId: String? = null
            var driverPubKey: String? = null

            for (tag in event.tags) {
                when (tag.getOrNull(0)) {
                    RideshareTags.EVENT_REF -> acceptanceEventId = tag.getOrNull(1)
                    RideshareTags.PUBKEY_REF -> driverPubKey = tag.getOrNull(1)
                }
            }

            if (acceptanceEventId == null || driverPubKey == null) return null

            RideConfirmationDataEncrypted(
                eventId = event.id,
                riderPubKey = event.pubKey,
                acceptanceEventId = acceptanceEventId,
                driverPubKey = driverPubKey,
                encryptedContent = event.content,
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decrypt the confirmation content to get the precise pickup location.
     */
    suspend fun decrypt(
        signer: NostrSigner,
        encryptedData: RideConfirmationDataEncrypted
    ): RideConfirmationData? {
        return try {
            val decrypted = signer.nip44Decrypt(encryptedData.encryptedContent, encryptedData.riderPubKey)
            val json = JSONObject(decrypted)
            val pickupJson = json.getJSONObject("precise_pickup")
            val precisePickup = Location.fromJson(pickupJson) ?: return null

            RideConfirmationData(
                eventId = encryptedData.eventId,
                riderPubKey = encryptedData.riderPubKey,
                acceptanceEventId = encryptedData.acceptanceEventId,
                driverPubKey = encryptedData.driverPubKey,
                precisePickup = precisePickup,
                createdAt = encryptedData.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Parsed data from a ride confirmation event (still encrypted).
 */
data class RideConfirmationDataEncrypted(
    val eventId: String,
    val riderPubKey: String,
    val acceptanceEventId: String,
    val driverPubKey: String,
    val encryptedContent: String,
    val createdAt: Long
)

/**
 * Decrypted data from a ride confirmation event.
 */
data class RideConfirmationData(
    val eventId: String,
    val riderPubKey: String,
    val acceptanceEventId: String,
    val driverPubKey: String,
    val precisePickup: Location,
    val createdAt: Long
)
