package com.ridestr.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONObject

/**
 * Kind 3177: Pickup Verification Event
 *
 * Sent by riders to verify the PIN submitted by the driver.
 * Contains verification result (verified/rejected) and attempt count.
 */
object PickupVerificationEvent {

    /**
     * Create and sign a pickup verification event.
     * @param signer The rider's signer
     * @param pinSubmissionEventId The PIN submission event ID being verified
     * @param driverPubKey The driver's public key
     * @param verified True if PIN was correct, false if rejected
     * @param attemptNumber The current attempt number (for brute force tracking)
     */
    suspend fun create(
        signer: NostrSigner,
        pinSubmissionEventId: String,
        driverPubKey: String,
        verified: Boolean,
        attemptNumber: Int
    ): Event {
        val content = JSONObject().apply {
            put("status", if (verified) "verified" else "rejected")
            put("attempt", attemptNumber)
        }.toString()

        // Add NIP-40 expiration (30 minutes)
        val expiration = RideshareExpiration.minutesFromNow(RideshareExpiration.PICKUP_VERIFICATION_MINUTES)

        val tags = arrayOf(
            arrayOf(RideshareTags.EVENT_REF, pinSubmissionEventId),
            arrayOf(RideshareTags.PUBKEY_REF, driverPubKey),
            arrayOf(RideshareTags.HASHTAG, RideshareTags.RIDESHARE_TAG),
            arrayOf(RideshareTags.EXPIRATION, expiration.toString())
        )

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = RideshareEventKinds.PICKUP_VERIFICATION,
            tags = tags,
            content = content
        )
    }

    /**
     * Parse a pickup verification event.
     */
    fun parse(event: Event): PickupVerificationData? {
        if (event.kind != RideshareEventKinds.PICKUP_VERIFICATION) return null

        return try {
            val json = JSONObject(event.content)
            val status = json.getString("status")
            val attemptNumber = json.optInt("attempt", 1)

            var pinSubmissionEventId: String? = null
            var driverPubKey: String? = null

            for (tag in event.tags) {
                when (tag.getOrNull(0)) {
                    RideshareTags.EVENT_REF -> pinSubmissionEventId = tag.getOrNull(1)
                    RideshareTags.PUBKEY_REF -> driverPubKey = tag.getOrNull(1)
                }
            }

            if (pinSubmissionEventId == null || driverPubKey == null) return null

            PickupVerificationData(
                eventId = event.id,
                riderPubKey = event.pubKey,
                pinSubmissionEventId = pinSubmissionEventId,
                driverPubKey = driverPubKey,
                verified = status == "verified",
                attemptNumber = attemptNumber,
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Verification status values.
 */
object VerificationStatus {
    const val VERIFIED = "verified"
    const val REJECTED = "rejected"
}

/**
 * Parsed data from a pickup verification event.
 */
data class PickupVerificationData(
    val eventId: String,
    val riderPubKey: String,
    val pinSubmissionEventId: String,
    val driverPubKey: String,
    val verified: Boolean,
    val attemptNumber: Int,
    val createdAt: Long
)
