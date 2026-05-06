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
     *
     * Excludes followers muted via either path:
     * - Heavyweight mute via [MutedRider] / `state.muted` (key rotation, "Remove" UX).
     * - Lightweight mute via [RoadflareFollower.mutedAt] (issue #80, "Mute" UX).
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
                val isLightMuted = f.mutedAt != null
                val isActive = isApproved && hasCurrentKey && !isMuted && !isLightMuted
                if (!isActive) {
                    android.util.Log.d("RoadflareRepo", "Follower ${f.pubkey.take(8)} NOT active: approved=$isApproved, keyVersionSent=${f.keyVersionSent} vs current=$currentVersion, muted=$isMuted, lightMuted=$isLightMuted")
                }
            }
        }

        return state.followers
            .filter {
                it.approved &&
                    it.keyVersionSent == currentVersion &&
                    it.pubkey !in mutedPubkeys &&
                    it.mutedAt == null
            }
            .map { it.pubkey }
    }

    /**
     * Get approved followers who need the current key (haven't received it yet).
     * Excludes both heavyweight-muted ([MutedRider]) and lightweight-muted ([RoadflareFollower.mutedAt]) entries.
     */
    fun getFollowersNeedingKey(): List<RoadflareFollower> {
        val state = _state.value ?: return emptyList()
        val currentVersion = state.roadflareKey?.version ?: return emptyList()
        val mutedPubkeys = state.muted.map { it.pubkey }.toSet()

        return state.followers
            .filter {
                it.approved &&
                    it.keyVersionSent < currentVersion &&
                    it.pubkey !in mutedPubkeys &&
                    it.mutedAt == null
            }
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

    // ── Lightweight per-follower mute (issue #80) ─────────────────────────────
    //
    // Distinct from the heavyweight [MutedRider] / "Remove" path above:
    // - No key rotation; just suppresses Kind 3186 delivery for one follower.
    // - Synced cross-device via Kind 30177's `muted_pubkeys` (last-write-wins),
    //   not via Kind 30012.

    /**
     * Set the lightweight-mute timestamp on a single follower.
     * No-op if the follower is not present in the current state.
     *
     * @param pubkey Follower pubkey to mute.
     * @param mutedAt Timestamp (epoch seconds) recording when the mute took effect.
     *   Used as the comparison value for last-write-wins reconciliation against
     *   remote Kind 30177 backups.
     * @return true if a follower row was updated; false if no matching follower exists.
     */
    fun setFollowerMuted(pubkey: String, mutedAt: Long): Boolean {
        val current = _state.value ?: return false
        if (current.followers.none { it.pubkey == pubkey }) return false

        _state.value = current.copy(
            followers = current.followers.map {
                if (it.pubkey == pubkey) it.copy(mutedAt = mutedAt) else it
            },
            updatedAt = System.currentTimeMillis() / 1000
        )
        saveState()
        return true
    }

    /**
     * Clear the lightweight-mute timestamp on a single follower.
     * No-op if the follower is not present.
     *
     * @return true if a follower row was updated; false if no matching follower exists.
     */
    fun setFollowerUnmuted(pubkey: String): Boolean {
        val current = _state.value ?: return false
        if (current.followers.none { it.pubkey == pubkey }) return false

        _state.value = current.copy(
            followers = current.followers.map {
                if (it.pubkey == pubkey) it.copy(mutedAt = null) else it
            },
            updatedAt = System.currentTimeMillis() / 1000
        )
        saveState()
        return true
    }

    /**
     * Pubkeys of followers currently lightweight-muted (per-follower mutedAt non-null).
     * Order is not significant (matches Kind 30177's `muted_pubkeys` array semantics).
     */
    fun getMutedFollowerPubkeys(): List<String> {
        return _state.value?.followers
            ?.filter { it.mutedAt != null }
            ?.map { it.pubkey }
            ?: emptyList()
    }

    /**
     * Check if a follower is lightweight-muted.
     * Independent of [isMuted] (which checks the heavyweight [MutedRider] list).
     */
    fun isFollowerMuted(pubkey: String): Boolean {
        return _state.value?.followers?.any { it.pubkey == pubkey && it.mutedAt != null } ?: false
    }

    /**
     * True if the rider is muted via either path (issue #82). Single helper so receive-side
     * filters across the codebase use the same check. Callers:
     * - `DriverViewModel.processIncomingOffer` — Kind 3173 direct/RoadFlare ride offers
     * - `DriverViewModel.subscribeToBroadcastRequests` — Kind 3173 broadcast offers
     * - `RoadflareListenerService.subscribeToRoadflareRequests` — Kind 3173 RoadFlare offers
     *   reaching the foreground service
     * - `RoadflareListenerService.processPingEvent` — Kind 3189 driver pings
     *
     * Kind 3187 (`RoadflareKeyManager.handleFollowNotification`) and Kind 3188
     * (`MainActivity` ack handler) implement the equivalent two-path check inline via
     * [isMuted] + [isFollowerMuted] separately, predating this helper. Functionally
     * equivalent — kept inline because each call site has additional surrounding logic
     * that wraps the mute decision.
     */
    fun isAnyMuted(pubkey: String): Boolean = isMuted(pubkey) || isFollowerMuted(pubkey)

    // In-memory flag (resets across process restarts) tracking whether the lightweight-mute
    // reconciliation against Kind 30177 has run at least once this session.
    //
    // Why: on a fresh device first key-import the local follower list and mute set are empty
    // until both Kind 30012 sync AND Kind 30177 mute reconciliation complete. Auto-backup
    // hooks (settings hash, vehicles) can fire BEFORE that, and a publish with an empty local
    // mute list would silently overwrite the remote `muted_pubkeys` set on Kind 30177 — wiping
    // cross-device mutes before reconciliation ever sees them.
    //
    // Consumed by [ProfileSyncAdapter.publishToNostr]: when this flag is false the publish
    // path preserves the remote `muted_pubkeys` instead of trusting the (possibly empty) local
    // list. Once reconciliation has run, local is authoritative and an empty local list is
    // treated as "user unmuted everyone" — i.e., a deliberate empty publish.
    @Volatile
    private var muteReconciledThisSession: Boolean = false

    /** True once [markMuteReconciled] has been called in this process lifetime. */
    fun isMuteReconciled(): Boolean = muteReconciledThisSession

    /** Mark the lightweight-mute reconciliation as having run at least once this session. */
    fun markMuteReconciled() {
        muteReconciledThisSession = true
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
