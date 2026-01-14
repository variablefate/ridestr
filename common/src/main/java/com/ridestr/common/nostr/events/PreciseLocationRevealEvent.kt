package com.ridestr.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONObject

/**
 * Kind 3181: Precise Location Reveal Event
 *
 * Sent by riders to share precise pickup or destination location when driver is close (~1 mile).
 * This enables progressive privacy - approximate location shared initially, precise location
 * only revealed when driver is nearby.
 *
 * Content is NIP-44 encrypted and includes:
 * - location_type: "pickup" or "destination"
 * - precise_location: The precise lat/lon coordinates
 */
object PreciseLocationRevealEvent {

    const val LOCATION_TYPE_PICKUP = "pickup"
    const val LOCATION_TYPE_DESTINATION = "destination"

    /**
     * Create and sign a precise location reveal event with encrypted content.
     * @param signer The rider's signer
     * @param confirmationEventId The ride confirmation event ID
     * @param driverPubKey The driver's public key (recipient)
     * @param locationType Either "pickup" or "destination"
     * @param preciseLocation The precise coordinates to share
     */
    suspend fun create(
        signer: NostrSigner,
        confirmationEventId: String,
        driverPubKey: String,
        locationType: String,
        preciseLocation: Location
    ): Event {
        // Build the plaintext content
        val plaintext = JSONObject().apply {
            put("location_type", locationType)
            put("precise_location", preciseLocation.toJson())
        }.toString()

        // Encrypt using NIP-44
        val encryptedContent = signer.nip44Encrypt(plaintext, driverPubKey)

        // Add NIP-40 expiration (8 hours)
        val expiration = RideshareExpiration.hoursFromNow(RideshareExpiration.PRECISE_LOCATION_HOURS)

        val tags = arrayOf(
            arrayOf(RideshareTags.EVENT_REF, confirmationEventId),
            arrayOf(RideshareTags.PUBKEY_REF, driverPubKey),
            arrayOf(RideshareTags.HASHTAG, RideshareTags.RIDESHARE_TAG),
            arrayOf(RideshareTags.EXPIRATION, expiration.toString())
        )

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = RideshareEventKinds.PRECISE_LOCATION_REVEAL,
            tags = tags,
            content = encryptedContent
        )
    }

    /**
     * Parse a precise location reveal event (encrypted content must be decrypted separately).
     */
    fun parseEncrypted(event: Event): PreciseLocationRevealEncrypted? {
        if (event.kind != RideshareEventKinds.PRECISE_LOCATION_REVEAL) return null

        return try {
            var confirmationEventId: String? = null
            var driverPubKey: String? = null

            for (tag in event.tags) {
                when (tag.getOrNull(0)) {
                    RideshareTags.EVENT_REF -> confirmationEventId = tag.getOrNull(1)
                    RideshareTags.PUBKEY_REF -> driverPubKey = tag.getOrNull(1)
                }
            }

            if (confirmationEventId == null || driverPubKey == null) return null

            PreciseLocationRevealEncrypted(
                eventId = event.id,
                riderPubKey = event.pubKey,
                confirmationEventId = confirmationEventId,
                driverPubKey = driverPubKey,
                encryptedContent = event.content,
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decrypt the reveal content to get the precise location and type.
     * @param signer The driver's signer (to decrypt content from rider)
     * @param encryptedData The encrypted event data
     */
    suspend fun decrypt(
        signer: NostrSigner,
        encryptedData: PreciseLocationRevealEncrypted
    ): PreciseLocationRevealData? {
        return try {
            val decrypted = signer.nip44Decrypt(encryptedData.encryptedContent, encryptedData.riderPubKey)
            val json = JSONObject(decrypted)
            val locationType = json.getString("location_type")
            val locationJson = json.getJSONObject("precise_location")
            val preciseLocation = Location.fromJson(locationJson) ?: return null

            PreciseLocationRevealData(
                eventId = encryptedData.eventId,
                riderPubKey = encryptedData.riderPubKey,
                confirmationEventId = encryptedData.confirmationEventId,
                locationType = locationType,
                preciseLocation = preciseLocation,
                createdAt = encryptedData.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Parsed data from a precise location reveal event (still encrypted).
 */
data class PreciseLocationRevealEncrypted(
    val eventId: String,
    val riderPubKey: String,
    val confirmationEventId: String,
    val driverPubKey: String,
    val encryptedContent: String,
    val createdAt: Long
)

/**
 * Decrypted data from a precise location reveal event.
 */
data class PreciseLocationRevealData(
    val eventId: String,
    val riderPubKey: String,
    val confirmationEventId: String,
    val locationType: String,  // "pickup" or "destination"
    val preciseLocation: Location,
    val createdAt: Long
)
