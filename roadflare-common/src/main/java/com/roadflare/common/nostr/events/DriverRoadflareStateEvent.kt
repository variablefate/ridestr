package com.roadflare.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import org.json.JSONArray
import org.json.JSONObject

/**
 * Kind 30012: Driver RoadFlare State (Parameterized Replaceable)
 * Port from ridestr — exact wire format preserved.
 */
object DriverRoadflareStateEvent {

    const val D_TAG = "roadflare-state"

    suspend fun create(signer: NostrSigner, state: DriverRoadflareState): Event? {
        val pubKeyHex = signer.pubKey

        val followersArray = JSONArray()
        state.followers.forEach { followersArray.put(it.toJson()) }

        val mutedArray = JSONArray()
        state.muted.forEach { mutedArray.put(it.toJson()) }

        val contentJson = JSONObject().apply {
            state.roadflareKey?.let { put("roadflareKey", it.toJson()) }
            put("followers", followersArray)
            put("muted", mutedArray)
            state.keyUpdatedAt?.let { put("keyUpdatedAt", it) }
            state.lastBroadcastAt?.let { put("lastBroadcastAt", it) }
            put("updated_at", System.currentTimeMillis() / 1000)
        }

        val encryptedContent = try {
            signer.nip44Encrypt(contentJson.toString(), pubKeyHex)
        } catch (e: Exception) {
            return null
        }

        val keyVersion = state.roadflareKey?.version?.toString() ?: "0"
        val keyUpdatedAt = state.keyUpdatedAt?.toString() ?: (System.currentTimeMillis() / 1000).toString()

        val tags = arrayOf(
            arrayOf("d", D_TAG),
            arrayOf(RideshareTags.HASHTAG, "roadflare"),
            arrayOf("key_version", keyVersion),
            arrayOf("key_updated_at", keyUpdatedAt)
        )

        return signer.sign<Event>(
            createdAt = System.currentTimeMillis() / 1000,
            kind = RideshareEventKinds.ROADFLARE_DRIVER_STATE,
            tags = tags,
            content = encryptedContent
        )
    }

    suspend fun parseAndDecrypt(signer: NostrSigner, event: Event): DriverRoadflareState? {
        if (event.kind != RideshareEventKinds.ROADFLARE_DRIVER_STATE) return null
        if (event.pubKey != signer.pubKey) return null

        return try {
            val decryptedContent = signer.nip44Decrypt(event.content, event.pubKey)
            val json = JSONObject(decryptedContent)

            val roadflareKey = json.optJSONObject("roadflareKey")?.let { DriverRoadflareKey.fromJson(it) }

            val followers = mutableListOf<RoadflareFollower>()
            json.optJSONArray("followers")?.let { arr ->
                for (i in 0 until arr.length()) followers.add(RoadflareFollower.fromJson(arr.getJSONObject(i)))
            }

            val muted = mutableListOf<MutedRider>()
            json.optJSONArray("muted")?.let { arr ->
                for (i in 0 until arr.length()) muted.add(MutedRider.fromJson(arr.getJSONObject(i)))
            }

            DriverRoadflareState(
                eventId = event.id,
                roadflareKey = roadflareKey,
                followers = followers,
                muted = muted,
                keyUpdatedAt = if (json.has("keyUpdatedAt")) json.getLong("keyUpdatedAt") else null,
                lastBroadcastAt = if (json.has("lastBroadcastAt")) json.getLong("lastBroadcastAt") else null,
                updatedAt = json.optLong("updated_at", event.createdAt),
                createdAt = event.createdAt
            )
        } catch (e: Exception) {
            null
        }
    }

    fun getKeyVersion(event: Event): Int {
        return event.tags.find { it.size >= 2 && it[0] == "key_version" }?.get(1)?.toIntOrNull() ?: 0
    }

    fun getKeyUpdatedAt(event: Event): Long? {
        return event.tags.find { it.size >= 2 && it[0] == "key_updated_at" }?.get(1)?.toLongOrNull()
    }
}

data class DriverRoadflareState(
    val eventId: String? = null,
    val roadflareKey: DriverRoadflareKey?,
    val followers: List<RoadflareFollower>,
    val muted: List<MutedRider>,
    val keyUpdatedAt: Long? = null,
    val lastBroadcastAt: Long?,
    val updatedAt: Long = System.currentTimeMillis() / 1000,
    val createdAt: Long = System.currentTimeMillis() / 1000
)

data class DriverRoadflareKey(
    val privateKey: String,
    val publicKey: String,
    val version: Int,
    val createdAt: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("privateKey", privateKey)
        put("publicKey", publicKey)
        put("version", version)
        put("createdAt", createdAt)
    }

    companion object {
        fun fromJson(json: JSONObject): DriverRoadflareKey = DriverRoadflareKey(
            privateKey = json.getString("privateKey"),
            publicKey = json.getString("publicKey"),
            version = json.optInt("version", 1),
            createdAt = json.optLong("createdAt", System.currentTimeMillis() / 1000)
        )
    }
}

data class RoadflareFollower(
    val pubkey: String,
    val name: String = "",
    val addedAt: Long,
    val approved: Boolean = false,
    val keyVersionSent: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("pubkey", pubkey)
        put("name", name)
        put("addedAt", addedAt)
        put("approved", approved)
        put("keyVersionSent", keyVersionSent)
    }

    companion object {
        fun fromJson(json: JSONObject): RoadflareFollower = RoadflareFollower(
            pubkey = json.getString("pubkey"),
            name = json.optString("name", ""),
            addedAt = json.getLong("addedAt"),
            approved = json.optBoolean("approved", true),
            keyVersionSent = json.optInt("keyVersionSent", 0)
        )
    }
}

data class MutedRider(
    val pubkey: String,
    val mutedAt: Long,
    val reason: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("pubkey", pubkey)
        put("mutedAt", mutedAt)
        put("reason", reason)
    }

    companion object {
        fun fromJson(json: JSONObject): MutedRider = MutedRider(
            pubkey = json.getString("pubkey"),
            mutedAt = json.getLong("mutedAt"),
            reason = json.optString("reason", "")
        )
    }
}
