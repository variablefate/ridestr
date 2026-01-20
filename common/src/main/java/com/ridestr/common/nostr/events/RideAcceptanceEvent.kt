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
     * @param walletPubKey Driver's wallet pubkey for P2PK escrow (separate from Nostr key)
     * @param escrowType Type of escrow: "cashu_nut14" or "hodl" (null for non-escrow)
     * @param escrowInvoice HTLC token or BOLT11 invoice (null if escrow not used)
     * @param escrowExpiry Unix timestamp when escrow expires (null if no escrow)
     */
    suspend fun create(
        signer: NostrSigner,
        offerEventId: String,
        riderPubKey: String,
        walletPubKey: String? = null,
        escrowType: String? = null,
        escrowInvoice: String? = null,
        escrowExpiry: Long? = null
    ): Event {
        val content = JSONObject().apply {
            put("status", "accepted")
            // Driver's wallet pubkey for P2PK escrow claims
            walletPubKey?.let { put("wallet_pubkey", it) }
            // Payment rails: escrow details for HTLC-based payment
            escrowType?.let { put("escrow_type", it) }
            escrowInvoice?.let { put("escrow_invoice", it) }
            escrowExpiry?.let { put("escrow_expiry", it) }
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

            // Parse wallet pubkey for P2PK escrow
            val walletPubKey = if (json.has("wallet_pubkey")) json.getString("wallet_pubkey") else null

            // Parse escrow fields (null for legacy events)
            val escrowType = if (json.has("escrow_type")) json.getString("escrow_type") else null
            val escrowInvoice = if (json.has("escrow_invoice")) json.getString("escrow_invoice") else null
            val escrowExpiry = if (json.has("escrow_expiry")) json.getLong("escrow_expiry") else null

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
                createdAt = event.createdAt,
                walletPubKey = walletPubKey,
                escrowType = escrowType,
                escrowInvoice = escrowInvoice,
                escrowExpiry = escrowExpiry
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
    val createdAt: Long,
    // Driver's wallet pubkey for P2PK escrow (separate from Nostr key)
    val walletPubKey: String? = null,
    // Payment rails escrow fields (null for legacy non-escrow rides)
    val escrowType: String? = null,      // "cashu_nut14" or "hodl"
    val escrowInvoice: String? = null,   // HTLC token or BOLT11 invoice
    val escrowExpiry: Long? = null       // Unix timestamp when escrow expires
)
