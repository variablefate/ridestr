package com.ridestr.common.data

import android.content.Context
import com.ridestr.common.nostr.events.DriverRoadflareKey
import com.ridestr.common.nostr.events.DriverRoadflareState
import com.ridestr.common.nostr.events.MutedRider
import com.ridestr.common.nostr.events.RoadflareFollower
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Repository for managing driver's RoadFlare state.
 * Stores the RoadFlare keypair, followers list, and muted riders.
 *
 * This is the local cache of Kind 30012 data. The source of truth is Nostr,
 * but local storage allows offline access and faster app startup.
 *
 * Key components:
 * - roadflareKey: The Nostr keypair used to encrypt location broadcasts
 * - followers: Riders who have been sent the decryption key
 * - muted: Riders excluded from future broadcasts (triggers key rotation)
 */
class DriverRoadflareRepository(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow<DriverRoadflareState?>(null)
    val state: StateFlow<DriverRoadflareState?> = _state.asStateFlow()

    init {
        loadState()
    }

    /**
     * Load state from SharedPreferences.
     */
    private fun loadState() {
        val stateJson = prefs.getString(KEY_STATE, null)
        if (stateJson != null) {
            try {
                val json = JSONObject(stateJson)

                // Parse roadflareKey
                val keyJson = json.optJSONObject("roadflareKey")
                val roadflareKey = keyJson?.let { DriverRoadflareKey.fromJson(it) }

                // Parse followers
                val followers = mutableListOf<RoadflareFollower>()
                val followersArray = json.optJSONArray("followers")
                if (followersArray != null) {
                    for (i in 0 until followersArray.length()) {
                        followers.add(RoadflareFollower.fromJson(followersArray.getJSONObject(i)))
                    }
                }

                // Parse muted
                val muted = mutableListOf<MutedRider>()
                val mutedArray = json.optJSONArray("muted")
                if (mutedArray != null) {
                    for (i in 0 until mutedArray.length()) {
                        muted.add(MutedRider.fromJson(mutedArray.getJSONObject(i)))
                    }
                }

                val keyUpdatedAt = if (json.has("keyUpdatedAt")) json.getLong("keyUpdatedAt") else null
                val lastBroadcastAt = if (json.has("lastBroadcastAt")) json.getLong("lastBroadcastAt") else null
                val updatedAt = json.optLong("updatedAt", System.currentTimeMillis() / 1000)

                _state.value = DriverRoadflareState(
                    roadflareKey = roadflareKey,
                    followers = followers,
                    muted = muted,
                    keyUpdatedAt = keyUpdatedAt,
                    lastBroadcastAt = lastBroadcastAt,
                    updatedAt = updatedAt
                )
            } catch (e: Exception) {
                _state.value = null
            }
        }
    }

    /**
     * Save state to SharedPreferences.
     */
    private fun saveState() {
        val state = _state.value
        if (state == null) {
            prefs.edit().remove(KEY_STATE).apply()
            return
        }

        val json = JSONObject().apply {
            state.roadflareKey?.let { put("roadflareKey", it.toJson()) }

            val followersArray = JSONArray()
            state.followers.forEach { followersArray.put(it.toJson()) }
            put("followers", followersArray)

            val mutedArray = JSONArray()
            state.muted.forEach { mutedArray.put(it.toJson()) }
            put("muted", mutedArray)

            state.keyUpdatedAt?.let { put("keyUpdatedAt", it) }
            state.lastBroadcastAt?.let { put("lastBroadcastAt", it) }
            put("updatedAt", state.updatedAt)
        }

        prefs.edit().putString(KEY_STATE, json.toString()).apply()
    }

    /**
     * Set the RoadFlare keypair.
     * Called when generating a new keypair or restoring from Nostr.
     */
    fun setRoadflareKey(key: DriverRoadflareKey) {
        val current = _state.value ?: DriverRoadflareState(
            roadflareKey = null,
            followers = emptyList(),
            muted = emptyList(),
            lastBroadcastAt = null
        )
        _state.value = current.copy(
            roadflareKey = key,
            updatedAt = System.currentTimeMillis() / 1000
        )
        saveState()
    }

    /**
     * Get the current RoadFlare keypair.
     */
    fun getRoadflareKey(): DriverRoadflareKey? = _state.value?.roadflareKey

    /**
     * Get the current key version.
     */
    fun getKeyVersion(): Int = _state.value?.roadflareKey?.version ?: 0

    /**
     * Get the key updated timestamp.
     * Used for stale key detection by riders.
     */
    fun getKeyUpdatedAt(): Long? = _state.value?.keyUpdatedAt

    /**
     * Update the key updated timestamp.
     * Called after key rotation to notify riders of stale keys.
     */
    fun updateKeyUpdatedAt(timestamp: Long) {
        val current = _state.value ?: return
        _state.value = current.copy(
            keyUpdatedAt = timestamp,
            updatedAt = System.currentTimeMillis() / 1000
        )
        saveState()
    }

    /**
     * Add a follower.
     * Called when a rider favorites this driver and we send them the key.
     */
    fun addFollower(follower: RoadflareFollower) {
        val current = _state.value ?: DriverRoadflareState(
            roadflareKey = null,
            followers = emptyList(),
            muted = emptyList(),
            lastBroadcastAt = null
        ).also { _state.value = it }
        if (current.followers.any { it.pubkey == follower.pubkey }) {
            // Update existing follower
            _state.value = current.copy(
                followers = current.followers.map {
                    if (it.pubkey == follower.pubkey) follower else it
                },
                updatedAt = System.currentTimeMillis() / 1000
            )
        } else {
            // Add new follower
            _state.value = current.copy(
                followers = current.followers + follower,
                updatedAt = System.currentTimeMillis() / 1000
            )
        }
        saveState()
    }

    /**
     * Get all followers.
     */
    fun getFollowers(): List<RoadflareFollower> = _state.value?.followers ?: emptyList()

    /**
     * Update a follower's display name (from their Kind 0 profile).
     */
    fun updateFollowerName(pubkey: String, name: String) {
        val current = _state.value ?: return
        if (current.followers.none { it.pubkey == pubkey }) return

        _state.value = current.copy(
            followers = current.followers.map {
                if (it.pubkey == pubkey) it.copy(name = name) else it
            },
            updatedAt = System.currentTimeMillis() / 1000
        )
        saveState()
    }

    /**
     * Get follower pubkeys that have the current key version and are approved.
     * Used for location broadcast subscriptions.
     */
    fun getActiveFollowerPubkeys(): List<String> {
        val state = _state.value ?: return emptyList()
        val currentVersion = state.roadflareKey?.version ?: return emptyList()
        val mutedPubkeys = state.muted.map { it.pubkey }.toSet()

        // Debug: log why followers may not be active
        if (com.ridestr.common.BuildConfig.DEBUG) {
            for (f in state.followers) {
                val isApproved = f.approved
                val hasCurrentKey = f.keyVersionSent == currentVersion
                val isMuted = f.pubkey in mutedPubkeys
                val isActive = isApproved && hasCurrentKey && !isMuted
                if (!isActive) {
                    android.util.Log.d("RoadflareRepo", "Follower ${f.pubkey.take(8)} NOT active: approved=$isApproved, keyVersionSent=${f.keyVersionSent} vs current=$currentVersion, muted=$isMuted")
                }
            }
        }

        return state.followers
            .filter { it.approved && it.keyVersionSent == currentVersion && it.pubkey !in mutedPubkeys }
            .map { it.pubkey }
    }

    /**
     * Get approved followers who need the current key (haven't received it yet).
     */
    fun getFollowersNeedingKey(): List<RoadflareFollower> {
        val state = _state.value ?: return emptyList()
        val currentVersion = state.roadflareKey?.version ?: return emptyList()
        val mutedPubkeys = state.muted.map { it.pubkey }.toSet()

        return state.followers
            .filter { it.approved && it.keyVersionSent < currentVersion && it.pubkey !in mutedPubkeys }
    }

    /**
     * Mark a follower as having received the current key.
     */
    fun markFollowerKeySent(pubkey: String, version: Int) {
        val current = _state.value ?: return
        _state.value = current.copy(
            followers = current.followers.map {
                if (it.pubkey == pubkey) it.copy(keyVersionSent = version) else it
            },
            updatedAt = System.currentTimeMillis() / 1000
        )
        saveState()
    }

    /**
     * Approve a pending follower.
     * Called when driver accepts a follow request.
     */
    fun approveFollower(pubkey: String) {
        val current = _state.value ?: return
        _state.value = current.copy(
            followers = current.followers.map {
                if (it.pubkey == pubkey) it.copy(approved = true) else it
            },
            updatedAt = System.currentTimeMillis() / 1000
        )
        saveState()
    }

    /**
     * Remove a follower (decline pending request).
     * Does NOT mute - just removes from the list entirely.
     */
    fun removeFollower(pubkey: String) {
        val current = _state.value ?: return
        _state.value = current.copy(
            followers = current.followers.filter { it.pubkey != pubkey },
            updatedAt = System.currentTimeMillis() / 1000
        )
        saveState()
    }

    /**
     * Get pending (unapproved) followers.
     */
    fun getPendingFollowers(): List<RoadflareFollower> {
        return _state.value?.followers?.filter { !it.approved } ?: emptyList()
    }

    /**
     * Get approved followers.
     */
    fun getApprovedFollowers(): List<RoadflareFollower> {
        return _state.value?.followers?.filter { it.approved } ?: emptyList()
    }

    /**
     * Mute a rider.
     * This triggers a key rotation - the muted rider won't receive the new key.
     */
    fun muteRider(pubkey: String, reason: String = "") {
        val current = _state.value ?: return
        if (current.muted.any { it.pubkey == pubkey }) return // Already muted

        val mutedRider = MutedRider(
            pubkey = pubkey,
            mutedAt = System.currentTimeMillis() / 1000,
            reason = reason
        )
        _state.value = current.copy(
            muted = current.muted + mutedRider,
            updatedAt = System.currentTimeMillis() / 1000
        )
        saveState()
    }

    /**
     * Unmute a rider.
     * They will need to be sent the current key again.
     */
    fun unmuteRider(pubkey: String) {
        val current = _state.value ?: return
        _state.value = current.copy(
            muted = current.muted.filter { it.pubkey != pubkey },
            updatedAt = System.currentTimeMillis() / 1000
        )
        saveState()
    }

    /**
     * Get all muted riders.
     */
    fun getMutedRiders(): List<MutedRider> = _state.value?.muted ?: emptyList()

    /**
     * Get muted rider pubkeys.
     */
    fun getMutedPubkeys(): Set<String> = _state.value?.muted?.map { it.pubkey }?.toSet() ?: emptySet()

    /**
     * Check if a rider is muted.
     */
    fun isMuted(pubkey: String): Boolean {
        return _state.value?.muted?.any { it.pubkey == pubkey } ?: false
    }

    /**
     * Update last broadcast timestamp.
     */
    fun updateLastBroadcast() {
        val current = _state.value ?: return
        _state.value = current.copy(
            lastBroadcastAt = System.currentTimeMillis() / 1000
        )
        saveState()
    }

    /**
     * Check if there's an active RoadFlare setup (has key and followers).
     */
    fun hasActiveSetup(): Boolean {
        val state = _state.value ?: return false
        return state.roadflareKey != null && state.followers.isNotEmpty()
    }

    /**
     * Replace entire state (used for sync restore from Nostr).
     */
    fun restoreFromBackup(state: DriverRoadflareState) {
        _state.value = state
        saveState()
    }

    /**
     * Clear all state (for logout).
     */
    fun clearAll() {
        prefs.edit().remove(KEY_STATE).apply()
        _state.value = null
    }

    companion object {
        private const val PREFS_NAME = "roadflare_driver_state"
        private const val KEY_STATE = "state"

        @Volatile
        private var INSTANCE: DriverRoadflareRepository? = null

        fun getInstance(context: Context): DriverRoadflareRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DriverRoadflareRepository(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
