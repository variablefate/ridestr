package com.ridestr.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONObject

/**
 * Kind 0: User Metadata Event (NIP-01)
 *
 * Contains user profile information like name, about, picture, etc.
 * This is a replaceable event - only the latest kind 0 from a user matters.
 */
object MetadataEvent {
    const val KIND = 0

    /**
     * Create and sign a metadata (profile) event.
     */
    suspend fun create(
        signer: NostrSigner,
        profile: UserProfile
    ): Event {
        val content = profile.toJson()

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = KIND,
            tags = emptyArray(),
            content = content
        )
    }

    /**
     * Parse a metadata event to extract the user profile.
     */
    fun parse(event: Event): UserProfile? {
        if (event.kind != KIND) return null

        return try {
            UserProfile.fromJson(event.content, event.pubKey)
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * User profile data from a metadata event.
 */
data class UserProfile(
    val pubKey: String? = null,
    val name: String? = null,
    val displayName: String? = null,
    val about: String? = null,
    val picture: String? = null,
    val banner: String? = null,
    val website: String? = null,
    val nip05: String? = null,
    val lud16: String? = null, // Lightning address
    val lud06: String? = null, // LNURL
    // Driver-specific fields (stored in profile for rideshare)
    val carMake: String? = null,
    val carModel: String? = null,
    val carColor: String? = null,
    val carYear: String? = null
) {
    /**
     * Convert to JSON string for event content.
     */
    fun toJson(): String {
        val json = JSONObject()
        name?.let { json.put("name", it) }
        displayName?.let { json.put("display_name", it) }
        about?.let { json.put("about", it) }
        picture?.let { json.put("picture", it) }
        banner?.let { json.put("banner", it) }
        website?.let { json.put("website", it) }
        nip05?.let { json.put("nip05", it) }
        lud16?.let { json.put("lud16", it) }
        lud06?.let { json.put("lud06", it) }
        carMake?.let { json.put("car_make", it) }
        carModel?.let { json.put("car_model", it) }
        carColor?.let { json.put("car_color", it) }
        carYear?.let { json.put("car_year", it) }
        return json.toString()
    }

    /**
     * Get formatted car description.
     * Example output: "Blue 2024 Toyota Camry"
     */
    fun carDescription(): String? {
        val parts = listOfNotNull(carColor, carYear, carMake, carModel)
        return if (parts.isNotEmpty()) parts.joinToString(" ") else null
    }

    /**
     * Get the best display name available.
     */
    fun bestName(): String {
        return displayName?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: pubKey?.take(8)?.let { "nostr:$it..." }
            ?: "Anonymous"
    }

    companion object {
        /**
         * Parse from JSON content string.
         */
        fun fromJson(jsonString: String, pubKey: String? = null): UserProfile {
            val json = JSONObject(jsonString)
            return UserProfile(
                pubKey = pubKey,
                name = json.optString("name").takeIf { it.isNotBlank() },
                displayName = json.optString("display_name").takeIf { it.isNotBlank() },
                about = json.optString("about").takeIf { it.isNotBlank() },
                picture = json.optString("picture").takeIf { it.isNotBlank() },
                banner = json.optString("banner").takeIf { it.isNotBlank() },
                website = json.optString("website").takeIf { it.isNotBlank() },
                nip05 = json.optString("nip05").takeIf { it.isNotBlank() },
                lud16 = json.optString("lud16").takeIf { it.isNotBlank() },
                lud06 = json.optString("lud06").takeIf { it.isNotBlank() },
                carMake = json.optString("car_make").takeIf { it.isNotBlank() },
                carModel = json.optString("car_model").takeIf { it.isNotBlank() },
                carColor = json.optString("car_color").takeIf { it.isNotBlank() },
                carYear = json.optString("car_year").takeIf { it.isNotBlank() }
            )
        }
    }
}
