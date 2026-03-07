package com.roadflare.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kind 30011: Followed Drivers List (Parameterized Replaceable)
 * Port from ridestr — exact wire format preserved.
 */
object FollowedDriversEvent {

    const val D_TAG = "roadflare-drivers"

    suspend fun create(signer: NostrSigner, drivers: List<FollowedDriver>): Event? {
        val pubKeyHex = signer.pubKey

        val driversArray = JSONArray()
        drivers.forEach { driversArray.put(it.toJson()) }

        val contentJson = JSONObject().apply {
            put("drivers", driversArray)
            put("updated_at", System.currentTimeMillis() / 1000)
        }

        val encryptedContent = try {
            signer.nip44Encrypt(contentJson.toString(), pubKeyHex)
        } catch (e: Exception) {
            return null
        }

        val tagsList = mutableListOf(
            arrayOf("d", D_TAG),
            arrayOf(RideshareTags.HASHTAG, "roadflare")
        )
        drivers.forEach { tagsList.add(arrayOf(RideshareTags.PUBKEY_REF, it.pubkey)) }

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = RideshareEventKinds.ROADFLARE_FOLLOWED_DRIVERS,
            tags = tagsList.toTypedArray(),
            content = encryptedContent
        )
    }

    suspend fun parseAndDecrypt(signer: NostrSigner, event: Event): FollowedDriversData? {
        if (event.kind != RideshareEventKinds.ROADFLARE_FOLLOWED_DRIVERS) return null
        if (event.pubKey != signer.pubKey) return null

        return try {
            val decryptedContent = signer.nip44Decrypt(event.content, event.pubKey)
            val json = JSONObject(decryptedContent)

            val drivers = mutableListOf<FollowedDriver>()
            val driversArray = json.optJSONArray("drivers")
            if (driversArray != null) {
                for (i in 0 until driversArray.length()) {
                    drivers.add(FollowedDriver.fromJson(driversArray.getJSONObject(i)))
                }
            }

            FollowedDriversData(
                eventId = event.id,
                drivers = drivers,
                updatedAt = json.getLong("updated_at"),
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }

    fun extractDriverPubkeys(event: Event): List<String> {
        if (event.kind != RideshareEventKinds.ROADFLARE_FOLLOWED_DRIVERS) return emptyList()
        return event.tags
            .filter { it.getOrNull(0) == RideshareTags.PUBKEY_REF }
            .mapNotNull { it.getOrNull(1) }
    }
}

data class FollowedDriversData(
    val eventId: String,
    val drivers: List<FollowedDriver>,
    val updatedAt: Long,
    val createdAt: Long
)

data class FollowedDriver(
    val pubkey: String,
    val addedAt: Long,
    val note: String = "",
    val roadflareKey: RoadflareKey? = null
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("pubkey", pubkey)
        put("addedAt", addedAt)
        put("note", note)
        roadflareKey?.let { put("roadflareKey", it.toJson()) }
    }

    companion object {
        fun fromJson(json: JSONObject): FollowedDriver = FollowedDriver(
            pubkey = json.getString("pubkey"),
            addedAt = json.getLong("addedAt"),
            note = json.optString("note", ""),
            roadflareKey = json.optJSONObject("roadflareKey")?.let { RoadflareKey.fromJson(it) }
        )
    }
}

data class RoadflareKey(
    val privateKey: String,
    val publicKey: String,
    val version: Int,
    val keyUpdatedAt: Long = 0L
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("privateKey", privateKey)
        put("publicKey", publicKey)
        put("version", version)
        if (keyUpdatedAt > 0) put("keyUpdatedAt", keyUpdatedAt)
    }

    companion object {
        fun fromJson(json: JSONObject): RoadflareKey = RoadflareKey(
            privateKey = json.getString("privateKey"),
            publicKey = json.getString("publicKey"),
            version = json.optInt("version", 1),
            keyUpdatedAt = json.optLong("keyUpdatedAt", 0L)
        )
    }
}
