package com.ridestr.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONObject

/**
 * Kind 3174: Ride Acceptance Event
 *
 * Sent by drivers to accept a ride offer from a rider.
 * No PIN included - the rider generates the PIN locally for security.
 */
object RideAcceptanceEvent {

    /**
     * Create and sign a ride acceptance event.
     * @param signer The driver's signer
     * @param offerEventId The ride offer event ID being accepted
     * @param riderPubKey The rider's public key
     */
    suspend fun create(
        signer: NostrSigner,
        offerEventId: String,
        riderPubKey: String
    ): Event {
        val content = JSONObject().apply {
            put("status", "accepted")
        }.toString()

        // Add NIP-40 expiration (10 minutes)
        val expiration = RideshareExpiration.minutesFromNow(RideshareExpiration.RIDE_ACCEPTANCE_MINUTES)

        val tags = arrayOf(
            arrayOf(RideshareTags.EVENT_REF, offerEventId),
            arrayOf(RideshareTags.PUBKEY_REF, riderPubKey),
            arrayOf(RideshareTags.HASHTAG, RideshareTags.RIDESHARE_TAG),
            arrayOf(RideshareTags.EXPIRATION, expiration.toString())
        )

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = RideshareEventKinds.RIDE_ACCEPTANCE,
            tags = tags,
            content = content
        )
    }

    /**
     * Parse a ride acceptance event.
     */
    fun parse(event: Event): RideAcceptanceData? {
        if (event.kind != RideshareEventKinds.RIDE_ACCEPTANCE) return null

        return try {
            val json = JSONObject(event.content)
            val status = json.getString("status")

            var offerEventId: String? = null
            var riderPubKey: String? = null

            for (tag in event.tags) {
                when (tag.getOrNull(0)) {
                    RideshareTags.EVENT_REF -> offerEventId = tag.getOrNull(1)
                    RideshareTags.PUBKEY_REF -> riderPubKey = tag.getOrNull(1)
                }
            }

            if (offerEventId == null || riderPubKey == null) return null

            RideAcceptanceData(
                eventId = event.id,
                driverPubKey = event.pubKey,
                offerEventId = offerEventId,
                riderPubKey = riderPubKey,
                status = status,
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Parsed data from a ride acceptance event.
 */
data class RideAcceptanceData(
    val eventId: String,
    val driverPubKey: String,
    val offerEventId: String,
    val riderPubKey: String,
    val status: String,
    val createdAt: Long
)
