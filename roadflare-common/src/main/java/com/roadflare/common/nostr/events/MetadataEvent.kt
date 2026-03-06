package com.roadflare.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONObject

/**
 * Kind 0: User Metadata Event (NIP-01)
 * Port from ridestr — exact wire format preserved.
 */
object MetadataEvent {
    const val KIND = 0

    suspend fun create(signer: NostrSigner, profile: UserProfile): Event {
        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = KIND,
            tags = emptyArray(),
            content = profile.toJson()
        )
    }

    fun parse(event: Event): UserProfile? {
        if (event.kind != KIND) return null
        return try { UserProfile.fromJson(event.content, event.pubKey) } catch (e: Exception) { null }
    }
}

data class UserProfile(
    val pubKey: String? = null,
    val name: String? = null,
    val displayName: String? = null,
    val about: String? = null,
    val picture: String? = null,
    val banner: String? = null,
    val website: String? = null,
    val nip05: String? = null,
    val lud16: String? = null,
    val lud06: String? = null,
    val carMake: String? = null,
    val carModel: String? = null,
    val carColor: String? = null,
    val carYear: String? = null
) {
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

    fun resolveDisplayName(): String = displayName ?: name ?: "Anonymous"
    fun getFirstName(): String = resolveDisplayName().split(" ").first()

    companion object {
        fun fromJson(jsonString: String, pubKey: String? = null): UserProfile {
            val json = JSONObject(jsonString)
            return UserProfile(
                pubKey = pubKey,
                name = json.optString("name", null),
                displayName = json.optString("display_name", null),
                about = json.optString("about", null),
                picture = json.optString("picture", null),
                banner = json.optString("banner", null),
                website = json.optString("website", null),
                nip05 = json.optString("nip05", null),
                lud16 = json.optString("lud16", null),
                lud06 = json.optString("lud06", null),
                carMake = json.optString("car_make", null),
                carModel = json.optString("car_model", null),
                carColor = json.optString("car_color", null),
                carYear = json.optString("car_year", null)
            )
        }
    }
}
