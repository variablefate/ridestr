package com.ridestr.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONObject

/**
 * Kind 3179: Ride Cancellation Event
 *
 * Sent by either rider or driver to cancel an active ride.
 * References the confirmation event and notifies the other party.
 */
object RideCancellationEvent {

    // Use the constant from RideshareEventKinds for consistency
    const val KIND = RideshareEventKinds.RIDE_CANCELLATION

    /**
     * Create and sign a ride cancellation event.
     * @param signer The signer (rider or driver)
     * @param confirmationEventId The ride confirmation event ID being cancelled
     * @param otherPartyPubKey The other party's public key (to notify them)
     * @param reason Optional reason for cancellation
     */
    suspend fun create(
        signer: NostrSigner,
        confirmationEventId: String,
        otherPartyPubKey: String,
        reason: String? = null
    ): Event {
        val content = JSONObject().apply {
            put("status", "cancelled")
            reason?.let { put("reason", it) }
        }.toString()

        // Add NIP-40 expiration (24 hours)
        val expiration = RideshareExpiration.hoursFromNow(RideshareExpiration.RIDE_CANCELLATION_HOURS)

        val tags = arrayOf(
            arrayOf(RideshareTags.EVENT_REF, confirmationEventId),
            arrayOf(RideshareTags.PUBKEY_REF, otherPartyPubKey),
            arrayOf(RideshareTags.HASHTAG, RideshareTags.RIDESHARE_TAG),
            arrayOf(RideshareTags.EXPIRATION, expiration.toString())
        )

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = KIND,
            tags = tags,
            content = content
        )
    }

    /**
     * Parse a ride cancellation event.
     */
    fun parse(event: Event): RideCancellationData? {
        if (event.kind != KIND) return null

        return try {
            val json = JSONObject(event.content)
            val reason = if (json.has("reason")) json.getString("reason") else null

            var confirmationEventId: String? = null
            var otherPartyPubKey: String? = null

            for (tag in event.tags) {
                when (tag.getOrNull(0)) {
                    RideshareTags.EVENT_REF -> confirmationEventId = tag.getOrNull(1)
                    RideshareTags.PUBKEY_REF -> otherPartyPubKey = tag.getOrNull(1)
                }
            }

            if (confirmationEventId == null || otherPartyPubKey == null) return null

            RideCancellationData(
                eventId = event.id,
                cancelledByPubKey = event.pubKey,
                confirmationEventId = confirmationEventId,
                otherPartyPubKey = otherPartyPubKey,
                reason = reason,
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Parsed data from a ride cancellation event.
 */
data class RideCancellationData(
    val eventId: String,
    val cancelledByPubKey: String,
    val confirmationEventId: String,
    val otherPartyPubKey: String,
    val reason: String?,
    val createdAt: Long
)
