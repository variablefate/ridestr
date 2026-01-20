package com.ridestr.common.nostr.events

import com.ridestr.common.data.Vehicle
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kind 30175: Vehicle Backup Event (Parameterized Replaceable)
 *
 * Encrypted backup of driver's vehicles.
 * Content is NIP-44 encrypted to self (user's own pubkey).
 *
 * As a parameterized replaceable event with d-tag "rideshare-vehicles",
 * only the latest backup per user is kept by relays.
 *
 * This allows drivers to:
 * - Backup their vehicles across devices
 * - Restore vehicles after reinstalling the app
 * - Maintain vehicle data when switching phones
 */
object VehicleBackupEvent {

    /** The d-tag identifier for vehicle backup events */
    const val D_TAG = "rideshare-vehicles"

    /**
     * Create and sign a vehicle backup event.
     * The content is encrypted to the user's own pubkey.
     *
     * @param signer The NostrSigner to sign and encrypt the event
     * @param vehicles List of vehicles to backup
     */
    suspend fun create(
        signer: NostrSigner,
        vehicles: List<Vehicle>
    ): Event? {
        val pubKeyHex = signer.pubKey

        // Build the content JSON
        val vehiclesArray = JSONArray()
        vehicles.forEach { vehicle ->
            vehiclesArray.put(vehicle.toJson())
        }

        val contentJson = JSONObject().apply {
            put("vehicles", vehiclesArray)
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
            kind = RideshareEventKinds.VEHICLE_BACKUP,
            tags = tags,
            content = encryptedContent
        )
    }

    /**
     * Parse and decrypt a vehicle backup event.
     *
     * @param signer The NostrSigner to decrypt the content
     * @param event The event to parse
     * @return Decrypted vehicle data, or null if parsing/decryption fails
     */
    suspend fun parseAndDecrypt(
        signer: NostrSigner,
        event: Event
    ): VehicleBackupData? {
        if (event.kind != RideshareEventKinds.VEHICLE_BACKUP) return null
        if (event.pubKey != signer.pubKey) return null // Can only decrypt our own

        return try {
            // Decrypt using NIP-44 (encrypted to self)
            val decryptedContent = signer.nip44Decrypt(event.content, event.pubKey)
            val json = JSONObject(decryptedContent)

            val vehicles = mutableListOf<Vehicle>()
            val vehiclesArray = json.getJSONArray("vehicles")
            for (i in 0 until vehiclesArray.length()) {
                vehicles.add(Vehicle.fromJson(vehiclesArray.getJSONObject(i)))
            }

            val updatedAt = json.getLong("updated_at")

            VehicleBackupData(
                eventId = event.id,
                vehicles = vehicles,
                updatedAt = updatedAt,
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Parsed and decrypted vehicle backup data.
 */
data class VehicleBackupData(
    val eventId: String,
    val vehicles: List<Vehicle>,
    val updatedAt: Long,
    val createdAt: Long
)
