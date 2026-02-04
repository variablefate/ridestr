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
     *
     * @param signer The NostrSigner to sign the event
     * @param acceptanceEventId The acceptance event this confirms
     * @param driverPubKey The driver's public key
     * @param precisePickup Precise pickup location
     * @param paymentHash HTLC payment hash for escrow verification
     * @param escrowToken Optional escrow token (for cross-mint bridge)
     */
    suspend fun create(
        signer: NostrSigner,
        acceptanceEventId: String,
        driverPubKey: String,
        precisePickup: Location,
        paymentHash: String? = null,
        escrowToken: String? = null
    ): Event {
        // Build the plaintext content
        val plaintext = JSONObject().apply {
            put("precise_pickup", precisePickup.toJson())
            paymentHash?.let { put("payment_hash", it) }
            escrowToken?.let { put("escrow_token", it) }
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
     * @param event The event to parse
     * @param expectedRiderPubKey Optional: If provided, validates that event.pubKey matches.
     *                            Use when expecting confirmation from a specific rider.
     */
    fun parseEncrypted(event: Event, expectedRiderPubKey: String? = null): RideConfirmationDataEncrypted? {
        if (event.kind != RideshareEventKinds.RIDE_CONFIRMATION) return null

        // Validate sender if expected pubkey provided (security: prevents spoofed confirmations)
        if (expectedRiderPubKey != null && event.pubKey != expectedRiderPubKey) {
            android.util.Log.w("RideConfirmationEvent",
                "Rejecting confirmation from unexpected pubkey: ${event.pubKey.take(16)} (expected ${expectedRiderPubKey.take(16)})")
            return null
        }

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
     * Decrypt the confirmation content to get the precise pickup location and payment data.
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

            // Extract payment data (new in paymentHash migration)
            val paymentHash = if (json.has("payment_hash")) json.getString("payment_hash") else null
            val escrowToken = if (json.has("escrow_token")) json.getString("escrow_token") else null

            RideConfirmationData(
                eventId = encryptedData.eventId,
                riderPubKey = encryptedData.riderPubKey,
                acceptanceEventId = encryptedData.acceptanceEventId,
                driverPubKey = encryptedData.driverPubKey,
                precisePickup = precisePickup,
                createdAt = encryptedData.createdAt,
                paymentHash = paymentHash,
                escrowToken = escrowToken
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
    val createdAt: Long,
    // Payment data (moved from offer for correct HTLC timing)
    val paymentHash: String? = null,
    val escrowToken: String? = null
)
