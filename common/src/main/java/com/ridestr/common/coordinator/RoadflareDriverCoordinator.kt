package com.ridestr.common.coordinator

import android.util.Log
import com.ridestr.common.data.DriverRoadflareRepository
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.DriverRoadflareState
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.events.MutedRider
import com.ridestr.common.nostr.events.RoadflareFollower
import com.ridestr.common.nostr.events.RoadflareLocation
import com.ridestr.common.nostr.events.RoadflareLocationEvent
import com.ridestr.common.roadflare.RoadflareLocationBroadcaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Return type for [RoadflareDriverCoordinator.ensureStateSynced].
 *
 * @property changed True when the merged state differed from local state and was persisted.
 * @property verifiedFollowerPubkeys Set of pubkeys confirmed via Kind 30011 query, or null if
 *   the query timed out (callers should fall back to a full refresh in that case).
 */
data class StateSyncResult(
    val changed: Boolean,
    val verifiedFollowerPubkeys: Set<String>?
)

/**
 * Manages the RoadFlare driver protocol: state synchronisation, location broadcasting,
 * and follower list merge.
 *
 * Responsibilities:
 * - Fetch and union-merge remote Kind 30012 RoadFlare state on startup
 * - Create and drive [RoadflareLocationBroadcaster] lifecycle
 * - Publish a final OFFLINE location event before going offline / entering a ride
 * - Signal the ViewModel when a sync should trigger a background follower refresh
 *
 * Unit-testable without Android context when constructed with a test-double [NostrService]
 * and an in-memory [DriverRoadflareRepository].
 *
 * // TODO(#52): convert constructor injection to @Inject once Hilt migration lands
 */
class RoadflareDriverCoordinator(
    private val nostrService: NostrService,
    private val driverRoadflareRepository: DriverRoadflareRepository,
    private val scope: CoroutineScope
) {

    companion object {
        private const val TAG = "RoadflareDriverCoord"
    }

    // Channel.CONFLATED: buffers one value (no lost emissions), consumed once (no stale replays)
    private val _syncTriggeredRefresh = Channel<Set<String>?>(capacity = Channel.CONFLATED)

    /**
     * Emits after [ensureStateSynced] merges remote state.
     * - Non-null value: verified follower pubkeys from Kind 30011 query (skip re-query).
     * - Null value: query timed out — caller should perform a full refresh.
     */
    val syncTriggeredRefresh: Flow<Set<String>?> = _syncTriggeredRefresh.receiveAsFlow()

    private var broadcaster: RoadflareLocationBroadcaster? = null

    /** Delegated from the underlying broadcaster. Null when broadcaster is not yet created. */
    val isBroadcasting: StateFlow<Boolean>?
        get() = broadcaster?.isBroadcasting

    // -------------------------------------------------------------------------
    // State synchronisation
    // -------------------------------------------------------------------------

    /**
     * Sync the local RoadFlare state with the remote Kind 30012 event using a union-merge
     * strategy:
     *
     * 1. Prefer the newer key (by `keyUpdatedAt`, with `version` as tiebreaker).
     * 2. Merge follower lists (union by pubkey; prefer approved + higher keyVersionSent).
     * 3. Merge muted lists (union — never auto-unmute).
     * 4. Verify merged followers against Kind 30011 to filter stale entries.
     * 5. Push the merged state back to Nostr if anything changed.
     * 6. Emit a refresh signal via [syncTriggeredRefresh].
     *
     * @return [StateSyncResult] with `changed=true` if state was updated.
     */
    suspend fun ensureStateSynced(): StateSyncResult {
        val currentState = driverRoadflareRepository.state.value
        val localKeyUpdatedAt = currentState?.keyUpdatedAt ?: 0L
        val localKeyVersion = currentState?.roadflareKey?.version ?: 0
        val localUpdatedAt = currentState?.updatedAt ?: 0L

        Log.d(TAG, "ensureStateSynced: local key v$localKeyVersion, keyUpdatedAt=$localKeyUpdatedAt")

        // Fetch remote state with one retry on transient failure
        var remoteState = nostrService.fetchDriverRoadflareState()
        if (remoteState == null) {
            Log.d(TAG, "First fetch null — retrying in 1s")
            delay(1_000)
            remoteState = nostrService.fetchDriverRoadflareState()
        }

        if (remoteState != null) {
            val remoteKeyUpdatedAt = remoteState.keyUpdatedAt ?: 0L
            val remoteKeyVersion = remoteState.roadflareKey?.version ?: 0
            val remoteUpdatedAt = remoteState.updatedAt

            val useRemoteKey = when {
                currentState?.roadflareKey == null && remoteState.roadflareKey != null -> true
                remoteKeyUpdatedAt > localKeyUpdatedAt -> true
                remoteKeyUpdatedAt == localKeyUpdatedAt && remoteKeyVersion > localKeyVersion -> true
                else -> false
            }

            val selectedKeyVersion = if (useRemoteKey) remoteKeyVersion else localKeyVersion

            val mergedFollowers = mergeFollowerLists(
                local = currentState?.followers ?: emptyList(),
                remote = remoteState.followers,
                selectedKeyVersion = selectedKeyVersion,
                localUpdatedAt = localUpdatedAt,
                remoteUpdatedAt = remoteUpdatedAt
            )

            // Verify followers against Kind 30011 to remove unfollowed riders
            val driverPubKey = nostrService.getPubKeyHex()
            var verifiedFollowerPubkeys: Set<String>? = null
            val verifiedFollowers = if (driverPubKey != null && mergedFollowers.isNotEmpty()) {
                val queryResult = nostrService.queryCurrentFollowerPubkeys(driverPubKey)
                if (!queryResult.success) {
                    Log.w(TAG, "Kind 30011 query timed out — using merged followers as fallback")
                    mergedFollowers
                } else {
                    verifiedFollowerPubkeys = queryResult.followers
                    val filtered = mergedFollowers.filter { it.pubkey in queryResult.followers }
                    val removed = mergedFollowers.size - filtered.size
                    if (removed > 0) Log.d(TAG, "Filtered $removed stale followers via Kind 30011")
                    filtered
                }
            } else {
                mergedFollowers
            }

            val mergedMuted = mergeMutedLists(
                local = currentState?.muted ?: emptyList(),
                remote = remoteState.muted
            )

            val mergedState = DriverRoadflareState(
                eventId = if (useRemoteKey) remoteState.eventId else currentState?.eventId,
                roadflareKey = if (useRemoteKey) remoteState.roadflareKey else currentState?.roadflareKey,
                followers = verifiedFollowers,
                muted = mergedMuted,
                keyUpdatedAt = if (useRemoteKey) remoteKeyUpdatedAt else localKeyUpdatedAt,
                lastBroadcastAt = maxOf(
                    currentState?.lastBroadcastAt ?: 0L,
                    remoteState.lastBroadcastAt ?: 0L
                ),
                updatedAt = maxOf(localUpdatedAt, remoteUpdatedAt),
                createdAt = minOf(
                    currentState?.createdAt ?: Long.MAX_VALUE,
                    remoteState.createdAt
                )
            )

            val stateChanged = mergedState.roadflareKey != currentState?.roadflareKey ||
                mergedState.followers != currentState?.followers ||
                mergedState.muted != currentState?.muted ||
                mergedState.keyUpdatedAt != currentState?.keyUpdatedAt

            if (stateChanged) {
                driverRoadflareRepository.restoreFromBackup(mergedState)
                Log.d(
                    TAG,
                    "Merged state: key v${mergedState.roadflareKey?.version}, " +
                        "followers=${mergedState.followers.size}, muted=${mergedState.muted.size}"
                )

                nostrService.getSigner()?.let { signer ->
                    nostrService.publishDriverRoadflareState(signer, mergedState)
                    Log.d(TAG, "Pushed merged state to Nostr")
                }

                _syncTriggeredRefresh.trySend(verifiedFollowerPubkeys).also { result ->
                    if (result.isFailure) {
                        Log.w(TAG, "Failed to send sync refresh signal: ${result.exceptionOrNull()}")
                    }
                }

                return StateSyncResult(changed = true, verifiedFollowerPubkeys = verifiedFollowerPubkeys)
            }
        } else {
            Log.d(TAG, "No remote state found after retry")
            // Push local state so other devices can sync from it
            val localState = currentState
            if (localState?.roadflareKey != null) {
                nostrService.getSigner()?.let { signer ->
                    nostrService.publishDriverRoadflareState(signer, localState)
                }
            }
        }

        return StateSyncResult(changed = false, verifiedFollowerPubkeys = null)
    }

    // -------------------------------------------------------------------------
    // Location broadcasting
    // -------------------------------------------------------------------------

    /**
     * Create the broadcaster (if not already created) and start the periodic location loop.
     *
     * @param locationProvider Returns the current Android [android.location.Location], or null
     *   to skip a broadcast tick.
     * @param statusProvider Returns the current RoadFlare status string
     *   (e.g. [RoadflareLocationEvent.Status.ONLINE] or ON_RIDE).
     */
    fun startBroadcasting(
        locationProvider: suspend () -> android.location.Location?,
        statusProvider: () -> String
    ) {
        val signer = nostrService.getSigner() ?: run {
            Log.w(TAG, "startBroadcasting: no signer — cannot broadcast")
            return
        }

        if (broadcaster == null) {
            broadcaster = RoadflareLocationBroadcaster(
                repository = driverRoadflareRepository,
                nostrService = nostrService,
                signer = signer
            )
        }

        broadcaster?.startBroadcasting(
            locationProvider = locationProvider,
            statusProvider = statusProvider
        )
        Log.d(TAG, "RoadFlare broadcasting started")
    }

    /** Stop periodic location broadcasting. Does not publish a final OFFLINE event. */
    fun stopBroadcasting() {
        broadcaster?.stopBroadcasting()
        Log.d(TAG, "RoadFlare broadcasting stopped")
    }

    /**
     * Request an immediate re-broadcast with the current status.
     * No-op if the broadcaster is not running.
     */
    fun requestImmediateBroadcast() {
        broadcaster?.requestImmediateBroadcast()
    }

    /**
     * Publish a final OFFLINE Kind 30014 event so followers see the status change immediately
     * rather than waiting for the staleness timeout.
     *
     * @param location The driver's last known location.
     */
    suspend fun broadcastOfflineStatus(location: Location) {
        val roadflareState = driverRoadflareRepository.state.value ?: return
        val roadflareKey = roadflareState.roadflareKey ?: return
        val signer = nostrService.getSigner() ?: return

        val offlineLocation = RoadflareLocation(
            lat = location.lat,
            lon = location.lon,
            timestamp = System.currentTimeMillis() / 1000,
            status = RoadflareLocationEvent.Status.OFFLINE
        )

        nostrService.publishRoadflareLocation(
            signer = signer,
            roadflarePubKey = roadflareKey.publicKey,
            location = offlineLocation,
            keyVersion = roadflareKey.version
        )
        Log.d(TAG, "Published OFFLINE RoadFlare status")
    }

    /** Cancel the broadcaster's internal scope and release resources. Call from `onCleared`. */
    fun destroy() {
        broadcaster?.destroy()
        broadcaster = null
    }

    // -------------------------------------------------------------------------
    // Merge utilities — internal but accessible for unit tests
    // -------------------------------------------------------------------------

    /**
     * Union-merge two follower lists by pubkey.
     *
     * Merge rules per duplicate:
     * - `approved` = logical-OR (once approved, stays approved)
     * - `keyVersionSent` = max, clamped to [selectedKeyVersion] (prevents phantom sent-key claims)
     * - `addedAt` = min (preserve the earliest known follow time)
     *
     * If `remoteUpdatedAt > localUpdatedAt`, local-only followers are pruned on the assumption
     * that the remote state is authoritative for removals. If local is newer, local additions
     * are kept (they haven't propagated to Nostr yet).
     */
    internal fun mergeFollowerLists(
        local: List<RoadflareFollower>,
        remote: List<RoadflareFollower>,
        selectedKeyVersion: Int,
        localUpdatedAt: Long,
        remoteUpdatedAt: Long
    ): List<RoadflareFollower> {
        val byPubkey = mutableMapOf<String, RoadflareFollower>()

        for (follower in local) byPubkey[follower.pubkey] = follower

        for (follower in remote) {
            val existing = byPubkey[follower.pubkey]
            if (existing == null) {
                byPubkey[follower.pubkey] = follower
            } else {
                val mergedVersion = maxOf(existing.keyVersionSent, follower.keyVersionSent)
                val clampedVersion = minOf(mergedVersion, selectedKeyVersion)
                if (mergedVersion > selectedKeyVersion) {
                    Log.w(TAG, "Clamped keyVersionSent $mergedVersion→$selectedKeyVersion for ${follower.pubkey.take(8)}")
                }
                byPubkey[follower.pubkey] = existing.copy(
                    approved = existing.approved || follower.approved,
                    keyVersionSent = clampedVersion,
                    addedAt = minOf(existing.addedAt, follower.addedAt)
                )
            }
        }

        // Remote newer → prune local-only entries (they were removed on Nostr)
        if (remoteUpdatedAt > localUpdatedAt) {
            val remotePubkeys = remote.map { it.pubkey }.toSet()
            val localOnlyPubkeys = local.map { it.pubkey }.filter { it !in remotePubkeys }
            if (localOnlyPubkeys.isNotEmpty()) {
                Log.d(TAG, "Remote newer; pruning ${localOnlyPubkeys.size} local-only stale followers")
                localOnlyPubkeys.forEach { byPubkey.remove(it) }
            }
        }

        return byPubkey.values.toList()
    }

    /**
     * Union-merge two muted lists by pubkey.
     * Once muted, always muted — auto-unmute via sync is intentionally blocked.
     */
    internal fun mergeMutedLists(
        local: List<MutedRider>,
        remote: List<MutedRider>
    ): List<MutedRider> {
        val byPubkey = mutableMapOf<String, MutedRider>()
        for (muted in local) byPubkey[muted.pubkey] = muted
        for (muted in remote) {
            if (!byPubkey.containsKey(muted.pubkey)) byPubkey[muted.pubkey] = muted
        }
        return byPubkey.values.toList()
    }
}
