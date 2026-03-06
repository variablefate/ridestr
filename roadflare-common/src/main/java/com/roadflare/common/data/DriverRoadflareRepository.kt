package com.roadflare.common.data

import android.content.Context
import com.roadflare.common.nostr.events.DriverRoadflareKey
import com.roadflare.common.nostr.events.DriverRoadflareState
import com.roadflare.common.nostr.events.MutedRider
import com.roadflare.common.nostr.events.RoadflareFollower
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DriverRoadflareRepository @Inject constructor(@ApplicationContext context: Context) {

    private val prefs = context.getSharedPreferences("roadflare_driver_state", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<DriverRoadflareState?>(null)
    val state: StateFlow<DriverRoadflareState?> = _state.asStateFlow()

    init { loadState() }

    private fun loadState() {
        val stateJson = prefs.getString("state", null) ?: return
        try {
            val json = JSONObject(stateJson)
            val roadflareKey = json.optJSONObject("roadflareKey")?.let { DriverRoadflareKey.fromJson(it) }
            val followers = mutableListOf<RoadflareFollower>()
            json.optJSONArray("followers")?.let { arr ->
                for (i in 0 until arr.length()) followers.add(RoadflareFollower.fromJson(arr.getJSONObject(i)))
            }
            val muted = mutableListOf<MutedRider>()
            json.optJSONArray("muted")?.let { arr ->
                for (i in 0 until arr.length()) muted.add(MutedRider.fromJson(arr.getJSONObject(i)))
            }
            _state.value = DriverRoadflareState(
                roadflareKey = roadflareKey, followers = followers, muted = muted,
                keyUpdatedAt = if (json.has("keyUpdatedAt")) json.getLong("keyUpdatedAt") else null,
                lastBroadcastAt = if (json.has("lastBroadcastAt")) json.getLong("lastBroadcastAt") else null,
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis() / 1000)
            )
        } catch (_: Exception) { _state.value = null }
    }

    private fun saveState() {
        val state = _state.value
        if (state == null) { prefs.edit().remove("state").apply(); return }
        val json = JSONObject().apply {
            state.roadflareKey?.let { put("roadflareKey", it.toJson()) }
            val fa = JSONArray(); state.followers.forEach { fa.put(it.toJson()) }; put("followers", fa)
            val ma = JSONArray(); state.muted.forEach { ma.put(it.toJson()) }; put("muted", ma)
            state.keyUpdatedAt?.let { put("keyUpdatedAt", it) }
            state.lastBroadcastAt?.let { put("lastBroadcastAt", it) }
            put("updatedAt", state.updatedAt)
        }
        prefs.edit().putString("state", json.toString()).apply()
    }

    private fun getOrCreateState(): DriverRoadflareState {
        return _state.value ?: DriverRoadflareState(
            roadflareKey = null, followers = emptyList(), muted = emptyList(), lastBroadcastAt = null
        ).also { _state.value = it }
    }

    fun setRoadflareKey(key: DriverRoadflareKey) {
        _state.value = getOrCreateState().copy(roadflareKey = key, updatedAt = System.currentTimeMillis() / 1000)
        saveState()
    }

    fun getRoadflareKey(): DriverRoadflareKey? = _state.value?.roadflareKey
    fun getKeyVersion(): Int = _state.value?.roadflareKey?.version ?: 0
    fun getKeyUpdatedAt(): Long? = _state.value?.keyUpdatedAt

    fun updateKeyUpdatedAt(timestamp: Long) {
        val current = _state.value ?: return
        _state.value = current.copy(keyUpdatedAt = timestamp, updatedAt = System.currentTimeMillis() / 1000)
        saveState()
    }

    fun addFollower(follower: RoadflareFollower) {
        val current = getOrCreateState()
        _state.value = if (current.followers.any { it.pubkey == follower.pubkey }) {
            current.copy(followers = current.followers.map { if (it.pubkey == follower.pubkey) follower else it }, updatedAt = System.currentTimeMillis() / 1000)
        } else {
            current.copy(followers = current.followers + follower, updatedAt = System.currentTimeMillis() / 1000)
        }
        saveState()
    }

    fun getFollowers(): List<RoadflareFollower> = _state.value?.followers ?: emptyList()

    fun updateFollowerName(pubkey: String, name: String) {
        val current = _state.value ?: return
        if (current.followers.none { it.pubkey == pubkey }) return
        _state.value = current.copy(
            followers = current.followers.map { if (it.pubkey == pubkey) it.copy(name = name) else it },
            updatedAt = System.currentTimeMillis() / 1000
        )
        saveState()
    }

    fun getActiveFollowerPubkeys(): List<String> {
        val state = _state.value ?: return emptyList()
        val currentVersion = state.roadflareKey?.version ?: return emptyList()
        val mutedPubkeys = state.muted.map { it.pubkey }.toSet()
        return state.followers.filter { it.approved && it.keyVersionSent == currentVersion && it.pubkey !in mutedPubkeys }.map { it.pubkey }
    }

    fun getFollowersNeedingKey(): List<RoadflareFollower> {
        val state = _state.value ?: return emptyList()
        val currentVersion = state.roadflareKey?.version ?: return emptyList()
        val mutedPubkeys = state.muted.map { it.pubkey }.toSet()
        return state.followers.filter { it.approved && it.keyVersionSent < currentVersion && it.pubkey !in mutedPubkeys }
    }

    fun markFollowerKeySent(pubkey: String, version: Int) {
        val current = _state.value ?: return
        _state.value = current.copy(
            followers = current.followers.map { if (it.pubkey == pubkey) it.copy(keyVersionSent = version) else it },
            updatedAt = System.currentTimeMillis() / 1000
        )
        saveState()
    }

    fun approveFollower(pubkey: String) {
        val current = _state.value ?: return
        _state.value = current.copy(
            followers = current.followers.map { if (it.pubkey == pubkey) it.copy(approved = true) else it },
            updatedAt = System.currentTimeMillis() / 1000
        )
        saveState()
    }

    fun removeFollower(pubkey: String) {
        val current = _state.value ?: return
        _state.value = current.copy(followers = current.followers.filter { it.pubkey != pubkey }, updatedAt = System.currentTimeMillis() / 1000)
        saveState()
    }

    fun getPendingFollowers(): List<RoadflareFollower> = _state.value?.followers?.filter { !it.approved } ?: emptyList()
    fun getApprovedFollowers(): List<RoadflareFollower> = _state.value?.followers?.filter { it.approved } ?: emptyList()

    fun muteRider(pubkey: String, reason: String = "") {
        val current = _state.value ?: return
        if (current.muted.any { it.pubkey == pubkey }) return
        _state.value = current.copy(
            muted = current.muted + MutedRider(pubkey, System.currentTimeMillis() / 1000, reason),
            updatedAt = System.currentTimeMillis() / 1000
        )
        saveState()
    }

    fun unmuteRider(pubkey: String) {
        val current = _state.value ?: return
        _state.value = current.copy(muted = current.muted.filter { it.pubkey != pubkey }, updatedAt = System.currentTimeMillis() / 1000)
        saveState()
    }

    fun getMutedRiders(): List<MutedRider> = _state.value?.muted ?: emptyList()
    fun getMutedPubkeys(): Set<String> = _state.value?.muted?.map { it.pubkey }?.toSet() ?: emptySet()
    fun isMuted(pubkey: String): Boolean = _state.value?.muted?.any { it.pubkey == pubkey } ?: false

    fun updateLastBroadcast() {
        val current = _state.value ?: return
        _state.value = current.copy(lastBroadcastAt = System.currentTimeMillis() / 1000)
        saveState()
    }

    fun hasActiveSetup(): Boolean {
        val state = _state.value ?: return false
        return state.roadflareKey != null && state.followers.isNotEmpty()
    }

    fun restoreFromBackup(state: DriverRoadflareState) { _state.value = state; saveState() }
    fun clearAll() { prefs.edit().remove("state").apply(); _state.value = null }
}
