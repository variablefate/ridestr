package com.ridestr.common.nostr.events

import com.ridestr.common.data.SavedLocation
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kind 30176: Saved Locations Backup Event (Parameterized Replaceable)
 *
 * Encrypted backup of rider's saved/favorite locations.
 * Content is NIP-44 encrypted to self (user's own pubkey).
 *
 * As a parameterized replaceable event with d-tag "rideshare-locations",
 * only the latest backup per user is kept by relays.
 *
 * This allows riders to:
 * - Backup their saved locations across devices
 * - Restore favorites after reinstalling the app
 * - Maintain location data when switching phones
 *
 * @deprecated Use [ProfileBackupEvent] (Kind 30177) instead. Saved locations are now part of unified profile backup.
 */
@Deprecated("Use ProfileBackupEvent (Kind 30177) instead")
object SavedLocationBackupEvent {

    /** The d-tag identifier for saved location backup events */
    const val D_TAG = "rideshare-locations"

    /**
     * Create and sign a saved location backup event.
     * The content is encrypted to the user's own pubkey.
     *
     * @param signer The NostrSigner to sign and encrypt the event
     * @param locations List of saved locations to backup
     */
    suspend fun create(
        signer: NostrSigner,
        locations: List<SavedLocation>
    ): Event? {
        val pubKeyHex = signer.pubKey

        // Build the content JSON
        val locationsArray = JSONArray()
        locations.forEach { location ->
            locationsArray.put(location.toJson())
        }

        val contentJson = JSONObject().apply {
            put("locations", locationsArray)
            put("updated_at", System.currentTimeMillis() / 1000)
        }

        // Encrypt to self using NIP-44 (same key for sender and receiver)
        val encryptedContent = try {
            signer.nip44Encrypt(contentJson.toString(), pubKeyHex)
        } catch (e: Exception) {
            return null
        }

        val tags = arrayOf(
            arrayOf("d", D_TAG),
            arrayOf(RideshareTags.HASHTAG, RideshareTags.RIDESHARE_TAG)
        )

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = RideshareEventKinds.SAVED_LOCATIONS_BACKUP,
            tags = tags,
            content = encryptedContent
        )
    }

    /**
     * Parse and decrypt a saved location backup event.
     *
     * @param signer The NostrSigner to decrypt the content
     * @param event The event to parse
     * @return Decrypted location data, or null if parsing/decryption fails
     */
    suspend fun parseAndDecrypt(
        signer: NostrSigner,
        event: Event
    ): SavedLocationBackupData? {
        if (event.kind != RideshareEventKinds.SAVED_LOCATIONS_BACKUP) return null
        if (event.pubKey != signer.pubKey) return null // Can only decrypt our own

        return try {
            // Decrypt using NIP-44 (encrypted to self)
            val decryptedContent = signer.nip44Decrypt(event.content, event.pubKey)
            val json = JSONObject(decryptedContent)

            val locations = mutableListOf<SavedLocation>()
            val locationsArray = json.getJSONArray("locations")
            for (i in 0 until locationsArray.length()) {
                locations.add(SavedLocation.fromJson(locationsArray.getJSONObject(i)))
            }

            val updatedAt = json.getLong("updated_at")

            SavedLocationBackupData(
                eventId = event.id,
                locations = locations,
                updatedAt = updatedAt,
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Parsed and decrypted saved location backup data.
 */
data class SavedLocationBackupData(
    val eventId: String,
    val locations: List<SavedLocation>,
    val updatedAt: Long,
    val createdAt: Long
)
