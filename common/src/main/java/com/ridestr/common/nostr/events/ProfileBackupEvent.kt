package com.ridestr.common.nostr.events

import com.ridestr.common.data.SavedLocation
import com.ridestr.common.data.Vehicle
import com.ridestr.common.settings.DisplayCurrency
import com.ridestr.common.settings.DistanceUnit
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kind 30177: Profile Backup Event (Parameterized Replaceable)
 *
 * Unified encrypted backup of user profile data including:
 * - Vehicles (driver)
 * - Saved locations (rider)
 * - App settings (both)
 *
 * Content is NIP-44 encrypted to self (user's own pubkey).
 *
 * As a parameterized replaceable event with d-tag "rideshare-profile",
 * only the latest backup per user is kept by relays.
 *
 * This replaces the separate Kind 30175 (vehicles) and Kind 30176 (locations)
 * events with a single unified profile backup.
 */
object ProfileBackupEvent {

    /** The d-tag identifier for profile backup events */
    const val D_TAG = "rideshare-profile"

    /**
     * Create and sign a profile backup event.
     * The content is encrypted to the user's own pubkey.
     *
     * @param signer The NostrSigner to sign and encrypt the event
     * @param vehicles List of vehicles to backup (driver)
     * @param savedLocations List of saved locations to backup (rider)
     * @param settings Settings to backup
     */
    suspend fun create(
        signer: NostrSigner,
        vehicles: List<Vehicle>,
        savedLocations: List<SavedLocation>,
        settings: SettingsBackup
    ): Event? {
        val pubKeyHex = signer.pubKey

        // Build vehicles array
        val vehiclesArray = JSONArray()
        vehicles.forEach { vehicle ->
            vehiclesArray.put(vehicle.toJson())
        }

        // Build saved locations array
        val locationsArray = JSONArray()
        savedLocations.forEach { location ->
            locationsArray.put(location.toJson())
        }

        // Build settings object
        val settingsJson = settings.toJson()

        // Build the content JSON
        val contentJson = JSONObject().apply {
            put("vehicles", vehiclesArray)
            put("savedLocations", locationsArray)
            put("settings", settingsJson)
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
            kind = RideshareEventKinds.PROFILE_BACKUP,
            tags = tags,
            content = encryptedContent
        )
    }

    /**
     * Parse and decrypt a profile backup event.
     *
     * @param signer The NostrSigner to decrypt the content
     * @param event The event to parse
     * @return Decrypted profile data, or null if parsing/decryption fails
     */
    suspend fun parseAndDecrypt(
        signer: NostrSigner,
        event: Event
    ): ProfileBackupData? {
        if (event.kind != RideshareEventKinds.PROFILE_BACKUP) return null
        if (event.pubKey != signer.pubKey) return null // Can only decrypt our own

        return try {
            // Decrypt using NIP-44 (encrypted to self)
            val decryptedContent = signer.nip44Decrypt(event.content, event.pubKey)
            val json = JSONObject(decryptedContent)

            // Parse vehicles
            val vehicles = mutableListOf<Vehicle>()
            val vehiclesArray = json.optJSONArray("vehicles")
            if (vehiclesArray != null) {
                for (i in 0 until vehiclesArray.length()) {
                    vehicles.add(Vehicle.fromJson(vehiclesArray.getJSONObject(i)))
                }
            }

            // Parse saved locations
            val savedLocations = mutableListOf<SavedLocation>()
            val locationsArray = json.optJSONArray("savedLocations")
            if (locationsArray != null) {
                for (i in 0 until locationsArray.length()) {
                    savedLocations.add(SavedLocation.fromJson(locationsArray.getJSONObject(i)))
                }
            }

            // Parse settings
            val settingsJson = json.optJSONObject("settings")
            val settings = if (settingsJson != null) {
                SettingsBackup.fromJson(settingsJson)
            } else {
                SettingsBackup() // Default settings if not present
            }

            val updatedAt = json.getLong("updated_at")

            ProfileBackupData(
                eventId = event.id,
                vehicles = vehicles,
                savedLocations = savedLocations,
                settings = settings,
                updatedAt = updatedAt,
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Parsed and decrypted profile backup data.
 */
data class ProfileBackupData(
    val eventId: String,
    val vehicles: List<Vehicle>,
    val savedLocations: List<SavedLocation>,
    val settings: SettingsBackup,
    val updatedAt: Long,
    val createdAt: Long
)

/**
 * Settings data for backup/restore.
 * Contains only user-facing settings from the main settings menu.
 * Does NOT include internal state like wallet setup flags or debug settings.
 */
data class SettingsBackup(
    val displayCurrency: DisplayCurrency = DisplayCurrency.USD,
    val distanceUnit: DistanceUnit = DistanceUnit.MILES,
    val notificationSoundEnabled: Boolean = true,
    val notificationVibrationEnabled: Boolean = true,
    val autoOpenNavigation: Boolean = true,
    val alwaysAskVehicle: Boolean = true,
    val customRelays: List<String> = emptyList()
) {
    /**
     * Serialize to JSON.
     */
    fun toJson(): JSONObject {
        val relaysArray = JSONArray()
        customRelays.forEach { relaysArray.put(it) }

        return JSONObject().apply {
            put("displayCurrency", displayCurrency.name)
            put("distanceUnit", distanceUnit.name)
            put("notificationSoundEnabled", notificationSoundEnabled)
            put("notificationVibrationEnabled", notificationVibrationEnabled)
            put("autoOpenNavigation", autoOpenNavigation)
            put("alwaysAskVehicle", alwaysAskVehicle)
            put("customRelays", relaysArray)
        }
    }

    companion object {
        /**
         * Deserialize from JSON.
         */
        fun fromJson(json: JSONObject): SettingsBackup {
            // Parse custom relays array
            val relays = mutableListOf<String>()
            val relaysArray = json.optJSONArray("customRelays")
            if (relaysArray != null) {
                for (i in 0 until relaysArray.length()) {
                    relays.add(relaysArray.getString(i))
                }
            }

            return SettingsBackup(
                displayCurrency = try {
                    DisplayCurrency.valueOf(json.optString("displayCurrency", DisplayCurrency.USD.name))
                } catch (e: IllegalArgumentException) {
                    DisplayCurrency.USD
                },
                distanceUnit = try {
                    DistanceUnit.valueOf(json.optString("distanceUnit", DistanceUnit.MILES.name))
                } catch (e: IllegalArgumentException) {
                    DistanceUnit.MILES
                },
                notificationSoundEnabled = json.optBoolean("notificationSoundEnabled", true),
                notificationVibrationEnabled = json.optBoolean("notificationVibrationEnabled", true),
                autoOpenNavigation = json.optBoolean("autoOpenNavigation", true),
                alwaysAskVehicle = json.optBoolean("alwaysAskVehicle", true),
                customRelays = relays
            )
        }
    }
}
