package com.ridestr.common.roadflare

import android.util.Log
import com.ridestr.common.data.DriverRoadflareRepository
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.DriverRoadflareKey
import com.ridestr.common.nostr.events.RoadflareFollower
import com.ridestr.common.nostr.events.RoadflareKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner

/**
 * Manages the RoadFlare keypair lifecycle for drivers.
 *
 * This handles:
 * 1. Generating new RoadFlare keypairs (separate from identity key)
 * 2. Key rotation when followers are muted/removed
 * 3. Distributing keys to followers via Kind 3186
 * 4. Cross-device sync via Kind 30012
 *
 * KEY DESIGN: The RoadFlare keypair is NOT the driver's identity key.
 * It's a separate Nostr keypair used specifically for location broadcast encryption.
 * The private key is shared with followers so they can decrypt location broadcasts.
 */
class RoadflareKeyManager(
    private val repository: DriverRoadflareRepository,
    private val nostrService: NostrService
) {
    companion object {
        private const val TAG = "RoadflareKeyManager"
    }

    /**
     * Generate a new RoadFlare keypair.
     * Called when:
     * 1. Driver gets their first follower (initial key)
     * 2. Key rotation is triggered (follower muted/removed)
     *
     * @return The new key, or null if generation failed
     */
    fun generateNewKey(): DriverRoadflareKey? {
        return try {
            val keyPair = KeyPair()
            val privKeyHex = keyPair.privKey?.toHexKey()
                ?: throw IllegalStateException("Generated keypair has no private key")
            val pubKeyHex = keyPair.pubKey.toHexKey()

            val currentVersion = repository.getKeyVersion()
            val newVersion = currentVersion + 1

            val newKey = DriverRoadflareKey(
                privateKey = privKeyHex,
                publicKey = pubKeyHex,
                version = newVersion,
                createdAt = System.currentTimeMillis() / 1000
            )

            repository.setRoadflareKey(newKey)
            Log.d(TAG, "Generated new RoadFlare key v$newVersion: ${pubKeyHex.take(8)}...")

            newKey
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate RoadFlare key", e)
            null
        }
    }

    /**
     * Rotate the RoadFlare key.
     * Called when a follower is muted/removed.
     *
     * This generates a new key and queues key distribution to all
     * remaining (non-muted) followers.
     *
     * @param signer The driver's identity signer for publishing events
     * @return true if rotation succeeded
     */
    suspend fun rotateKey(
        signer: NostrSigner
    ): Boolean {
        // Generate new key
        val newKey = generateNewKey() ?: return false
        val keyUpdatedAt = newKey.createdAt

        // Update repository with new keyUpdatedAt
        repository.updateKeyUpdatedAt(keyUpdatedAt)

        // Publish updated state to Nostr (Kind 30012)
        val state = repository.state.value ?: return false
        val published = nostrService.publishDriverRoadflareState(signer, state)
        if (published == null) {
            Log.e(TAG, "Failed to publish rotated key state to Nostr")
            return false
        }

        // Send new key to all non-muted followers
        val mutedPubkeys = repository.getMutedPubkeys()
        val activeFollowers = state.followers.filter { it.pubkey !in mutedPubkeys }

        Log.d(TAG, "Distributing rotated key v${newKey.version} to ${activeFollowers.size} followers")

        for (follower in activeFollowers) {
            val success = sendKeyToFollower(signer, follower.pubkey, newKey, keyUpdatedAt)
            if (success) {
                repository.markFollowerKeySent(follower.pubkey, newKey.version)
            }
        }

        return true
    }

    /**
     * Send the RoadFlare key to a specific follower.
     * Called when:
     * 1. A new follower is added (driver approves)
     * 2. Key rotation (send to all remaining followers)
     *
     * Publishes Kind 3186 with 12-hour expiry. Key persists in rider's Kind 30011 after receipt.
     *
     * @param signer The driver's identity signer
     * @param followerPubkey The follower's Nostr pubkey
     * @param key The RoadFlare key to share
     * @param keyUpdatedAt Timestamp when the key was last updated/rotated
     * @return true if the key was sent successfully
     */
    suspend fun sendKeyToFollower(
        signer: NostrSigner,
        followerPubkey: String,
        key: DriverRoadflareKey,
        keyUpdatedAt: Long
    ): Boolean {
        // Convert to the format expected by RoadflareKeyShareEvent
        val shareableKey = RoadflareKey(
            privateKey = key.privateKey,
            publicKey = key.publicKey,
            version = key.version,
            keyUpdatedAt = keyUpdatedAt
        )

        val eventId = nostrService.publishRoadflareKeyShare(
            signer = signer,
            followerPubKey = followerPubkey,
            roadflareKey = shareableKey,
            keyUpdatedAt = keyUpdatedAt
        )

        if (eventId != null) {
            Log.d(TAG, "Sent key v${key.version} to follower ${followerPubkey.take(8)}... (Kind 3186, 12h expiry)")
            return true
        } else {
            Log.e(TAG, "Failed to send key to follower ${followerPubkey.take(8)}...")
            return false
        }
    }

    /**
     * Add a new pending follower (not yet approved).
     * Driver must approve before they receive the key.
     *
     * @param followerPubkey The new follower's pubkey
     * @param riderName The rider's display name (from follow notification)
     * @return true if follower was added as pending
     */
    fun addPendingFollower(
        followerPubkey: String,
        riderName: String
    ): Boolean {
        // Check if already a follower
        val existing = repository.state.value?.followers?.find { it.pubkey == followerPubkey }
        if (existing != null) {
            Log.d(TAG, "Follower ${followerPubkey.take(8)}... already exists")
            return false
        }

        // Add as pending follower
        val follower = RoadflareFollower(
            pubkey = followerPubkey,
            name = riderName,
            addedAt = System.currentTimeMillis() / 1000,
            approved = false,
            keyVersionSent = 0
        )
        repository.addFollower(follower)
        Log.d(TAG, "Added pending follower: ${riderName.ifEmpty { followerPubkey.take(8) }}")

        return true
    }

    /**
     * Approve a pending follower and send them the key.
     *
     * @param signer The driver's identity signer
     * @param followerPubkey The follower's pubkey to approve
     * @return true if follower was approved and received the key
     */
    suspend fun approveFollower(
        signer: NostrSigner,
        followerPubkey: String
    ): Boolean {
        // Get or generate key
        var key = repository.getRoadflareKey()
        if (key == null) {
            key = generateNewKey()
            if (key == null) return false
        }

        // Get or set keyUpdatedAt
        var keyUpdatedAt = repository.getKeyUpdatedAt()
        if (keyUpdatedAt == null) {
            keyUpdatedAt = key.createdAt
            repository.updateKeyUpdatedAt(keyUpdatedAt)
        }

        // Send key to follower before marking approved (avoid stuck state if send fails)
        val success = sendKeyToFollower(signer, followerPubkey, key, keyUpdatedAt)
        if (success) {
            repository.approveFollower(followerPubkey)
            repository.markFollowerKeySent(followerPubkey, key.version)

            // Publish updated state to Nostr
            val state = repository.state.value
            if (state != null) {
                nostrService.publishDriverRoadflareState(signer, state)
            }
            Log.d(TAG, "Approved and sent key to follower ${followerPubkey.take(8)}...")
        }

        return success
    }

    /**
     * Decline a pending follower (remove without muting).
     *
     * @param followerPubkey The follower's pubkey to decline
     */
    fun declineFollower(followerPubkey: String) {
        repository.removeFollower(followerPubkey)
        Log.d(TAG, "Declined follower ${followerPubkey.take(8)}...")
    }

    /**
     * Handle a Kind 3187 follow notification from a rider.
     *
     * Kind 3187 is used by riders for both first follows AND as a delivery-retry
     * signal when their stored copy of the driver's RoadFlare key is unavailable
     * (fresh install, transient relay failure during backup restore, etc.).
     *
     * Receiving Kind 3187 NEVER rotates the key, advances
     * [DriverRoadflareRepository.getKeyUpdatedAt], or republishes Kind 30012 —
     * other followers' stored keys are not invalidated by one rider re-adding
     * the driver. See [FollowNotificationResult] for the per-state outcomes.
     *
     * Muted riders are left alone: the driver's "Remove" decision is preserved
     * (matches the cross-device sync invariant in
     * `RoadflareDriverCoordinator.mergeMutedLists`). The mute check runs before
     * the existence check so that a muted entry without a corresponding
     * `followers` record (possible after cross-device sync filters out a
     * follower whose Kind 30011 stopped listing the driver) is not bypassed.
     *
     * @param signer The driver's identity signer (for publishing Kind 3186).
     * @param followerPubkey The rider's pubkey from the Kind 3187 event.
     * @param riderName The rider's display name from the notification body.
     * @return The action taken. The call site uses this to decide whether to
     *   surface an OS notification (only [FollowNotificationResult.AddedAsPending])
     *   and whether to refresh profile backups.
     *   [FollowNotificationResult.AlreadyPending] covers two situations: the
     *   rider was already pending before this call, OR a concurrent path won
     *   the add race between the existence check and [addPendingFollower].
     */
    suspend fun handleFollowNotification(
        signer: NostrSigner,
        followerPubkey: String,
        riderName: String
    ): FollowNotificationResult {
        if (repository.isMuted(followerPubkey)) {
            // Muted re-add — preserve the driver's "Remove" decision.
            // Auto-unmute would conflict with mergeMutedLists' "once muted, always
            // muted" cross-device invariant and silently bypass driver consent.
            // Checked first so a stale muted entry without a `followers` record
            // (e.g., after cross-device sync) cannot be bypassed.
            return FollowNotificationResult.AlreadyMuted
        }

        val existing = repository.state.value?.followers?.find { it.pubkey == followerPubkey }

        if (existing == null) {
            // New rider — add as pending; driver must approve via UI before key is sent.
            // Preserves driver consent for genuinely new follows.
            val added = addPendingFollower(followerPubkey, riderName)
            return if (added) {
                FollowNotificationResult.AddedAsPending
            } else {
                // Race: another path added them between our state read and add. Treat as pending.
                FollowNotificationResult.AlreadyPending
            }
        }

        if (existing.approved) {
            // Approved re-add — re-deliver the current key. State unchanged, no Kind 30012 republish.
            val hasKey = repository.getRoadflareKey() != null
            val keySent = resendCurrentKey(signer, followerPubkey)
            return if (keySent) {
                Log.d(TAG, "Re-sent key to approved follower ${followerPubkey.take(8)}...")
                FollowNotificationResult.KeyResent
            } else if (!hasKey) {
                FollowNotificationResult.Failed("no current key to resend")
            } else {
                FollowNotificationResult.Failed("Kind 3186 publish failed")
            }
        }

        // Pending (not yet approved) re-add — driver still owes an approval via UI.
        return FollowNotificationResult.AlreadyPending
    }

    /**
     * Re-send the current Kind 3186 key share to a specific follower.
     * Uses the existing [DriverRoadflareRepository.getKeyUpdatedAt]; never
     * advances it.
     *
     * If [DriverRoadflareRepository.getKeyUpdatedAt] is `null` but a key
     * exists, the fallback to [DriverRoadflareKey.createdAt] is also persisted
     * via [DriverRoadflareRepository.updateKeyUpdatedAt] — this mirrors
     * [ensureFollowersHaveCurrentKey] so the rider's stored key share carries
     * the same `keyUpdatedAt` value any future Kind 30012 publish will emit.
     *
     * @return true if the key was sent; false if no key is configured yet or send failed.
     */
    private suspend fun resendCurrentKey(
        signer: NostrSigner,
        followerPubkey: String
    ): Boolean {
        val key = repository.getRoadflareKey() ?: return false
        val keyUpdatedAt = repository.getKeyUpdatedAt() ?: run {
            repository.updateKeyUpdatedAt(key.createdAt)
            key.createdAt
        }
        val sent = sendKeyToFollower(signer, followerPubkey, key, keyUpdatedAt)
        if (sent) {
            repository.markFollowerKeySent(followerPubkey, key.version)
        }
        return sent
    }

    /**
     * Handle an unfollow notification from a rider.
     * Removes follower from our list (no key rotation needed since they're voluntarily leaving).
     *
     * @param followerPubkey The rider's pubkey who unfollowed
     */
    fun handleUnfollow(followerPubkey: String) {
        repository.removeFollower(followerPubkey)
        Log.d(TAG, "Removed unfollowed rider ${followerPubkey.take(8)}...")
    }

    /**
     * Handle manually removing a follower (initiated by driver).
     * Removes from list and triggers key rotation so removed follower can't decrypt future broadcasts.
     *
     * @param signer The driver's identity signer
     * @param followerPubkey The follower to remove
     * @return true if removal and key rotation succeeded
     */
    suspend fun handleRemoveFollower(
        signer: NostrSigner,
        followerPubkey: String
    ): Boolean {
        // Remove from followers list
        repository.removeFollower(followerPubkey)
        Log.d(TAG, "Removed follower ${followerPubkey.take(8)}...")

        // Rotate key so removed follower can't decrypt future broadcasts
        return rotateKey(signer)
    }

    /**
     * Handle muting a follower.
     * Adds to muted list and triggers key rotation.
     *
     * @param signer The driver's identity signer
     * @param followerPubkey The follower to mute
     * @param reason Optional reason for muting
     * @return true if mute and rotation succeeded
     */
    suspend fun handleMuteFollower(
        signer: NostrSigner,
        followerPubkey: String,
        reason: String = ""
    ): Boolean {
        // Add to muted list
        repository.muteRider(followerPubkey, reason)

        // Rotate key so muted follower can't decrypt future broadcasts
        return rotateKey(signer)
    }

    /**
     * Ensure all followers have the current key.
     * Called on app startup to handle any pending key distributions.
     *
     * @param signer The driver's identity signer
     */
    suspend fun ensureFollowersHaveCurrentKey(
        signer: NostrSigner
    ) {
        val key = repository.getRoadflareKey() ?: return
        val followersNeedingKey = repository.getFollowersNeedingKey()

        if (followersNeedingKey.isEmpty()) return

        // Get or set keyUpdatedAt
        var keyUpdatedAt = repository.getKeyUpdatedAt()
        if (keyUpdatedAt == null) {
            keyUpdatedAt = key.createdAt
            repository.updateKeyUpdatedAt(keyUpdatedAt)
        }

        Log.d(TAG, "Sending key v${key.version} to ${followersNeedingKey.size} followers who need it")

        for (follower in followersNeedingKey) {
            val success = sendKeyToFollower(signer, follower.pubkey, key, keyUpdatedAt)
            if (success) {
                repository.markFollowerKeySent(follower.pubkey, key.version)
            }
        }

        // Publish updated state
        val state = repository.state.value
        if (state != null) {
            nostrService.publishDriverRoadflareState(signer, state)
        }
    }

    /**
     * Get the public key for location encryption.
     * This is the key that location broadcasts are encrypted TO.
     */
    fun getEncryptionPubKey(): String? = repository.getRoadflareKey()?.publicKey

    /**
     * Check if we have an active RoadFlare key.
     */
    fun hasKey(): Boolean = repository.getRoadflareKey() != null

    // ── Lightweight per-follower mute (issue #80) ─────────────────────────────
    //
    // Distinct from [handleMuteFollower] / [handleRemoveFollower] which trigger key rotation.
    // The lightweight mute just suppresses Kind 3186 delivery for one follower, so muting and
    // unmuting back-to-back has zero side-effect on other followers. Synced cross-device via
    // Kind 30177's `muted_pubkeys` (last-write-wins on `created_at`); the caller is responsible
    // for triggering a profile-backup republish after a successful mute/unmute.

    /**
     * Lightweight-mute a single follower (issue #80).
     *
     * Sets `mutedAt` on the follower row. The Kind 3186 send-loop and active-follower
     * queries skip muted entries. Does NOT rotate the RoadFlare key. Other followers are
     * untouched.
     *
     * Local state takes effect immediately, even when offline. Cross-device sync happens
     * via the next Kind 30177 publish — the caller (UI handler) should invoke
     * `profileSyncManager.backupProfileData()` (or equivalent) after this returns true.
     *
     * @param followerPubkey Hex pubkey of the follower to mute.
     * @param now Override the timestamp (epoch seconds). Tests inject a deterministic clock; in
     *   production the default `System.currentTimeMillis() / 1000` matches the rest of the
     *   repository's timestamp convention.
     * @return true if the follower row was updated; false if no matching follower exists.
     */
    fun muteFollower(
        followerPubkey: String,
        now: Long = System.currentTimeMillis() / 1000
    ): Boolean {
        val updated = repository.setFollowerMuted(followerPubkey, now)
        if (updated) {
            Log.d(TAG, "Lightweight-muted follower ${followerPubkey.take(8)}... at $now")
        } else {
            Log.w(TAG, "muteFollower: no follower row for ${followerPubkey.take(8)}...")
        }
        return updated
    }

    /**
     * Lightweight-unmute a single follower (issue #80).
     *
     * Clears `mutedAt` on the follower row and best-effort re-delivers the current RoadFlare
     * key via Kind 3186 so the rider can resume decrypting location broadcasts. Does NOT
     * rotate the key. The caller should trigger a profile-backup republish afterward.
     *
     * @param signer The driver's identity signer for publishing the Kind 3186 key share.
     * @param followerPubkey Hex pubkey of the follower to unmute.
     * @return true if the follower row was updated. The Kind 3186 send is best-effort and
     *   does not affect this return value (a transient relay failure is recoverable on next
     *   `ensureFollowersHaveCurrentKey`).
     */
    suspend fun unmuteFollower(
        signer: NostrSigner,
        followerPubkey: String
    ): Boolean {
        val updated = repository.setFollowerUnmuted(followerPubkey)
        if (!updated) {
            Log.w(TAG, "unmuteFollower: no follower row for ${followerPubkey.take(8)}...")
            return false
        }

        Log.d(TAG, "Lightweight-unmuted follower ${followerPubkey.take(8)}...")

        // Best-effort key re-delivery so the unmuted rider can decrypt new broadcasts.
        // No key rotation; uses the current key + keyUpdatedAt verbatim.
        //
        // If `getKeyUpdatedAt()` is null, persist the `key.createdAt` fallback via
        // `updateKeyUpdatedAt` — same write-back pattern PR #79 added to `resendCurrentKey`.
        // Without this, the next `publishDriverRoadflareState` would fall through to
        // `DriverRoadflareStateEvent.create`'s wall-clock fallback and silently advance the
        // public `key_updated_at` tag, invalidating riders' stored keys.
        val key = repository.getRoadflareKey()
        if (key != null) {
            val keyUpdatedAt = repository.getKeyUpdatedAt() ?: run {
                repository.updateKeyUpdatedAt(key.createdAt)
                key.createdAt
            }
            val sent = sendKeyToFollower(signer, followerPubkey, key, keyUpdatedAt)
            if (sent) {
                repository.markFollowerKeySent(followerPubkey, key.version)
            } else {
                Log.w(TAG, "unmuteFollower: Kind 3186 re-delivery failed for ${followerPubkey.take(8)}... (recoverable)")
            }
        } else {
            Log.w(TAG, "unmuteFollower: no current key to re-deliver to ${followerPubkey.take(8)}...")
        }

        return true
    }

    /**
     * Fetch the driver's own Kind 30177 profile backup and apply mute reconciliation.
     *
     * Convenience wrapper around [reconcileMuteStateFromBackup] for the app-start path: the
     * driver app calls this after Kind 30012 sync (which populates the follower list).
     * Returns null when no backup exists or the fetch fails — the caller can treat that as
     * "no remote state, nothing to reconcile" and continue with current local state.
     *
     * Does NOT trigger a profile-backup republish on its own. The caller decides whether to
     * publish based on [MuteReconciliationResult.changed]; the existing `backupProfileData()`
     * pipeline picks up the merged state via the [com.ridestr.common.sync.ProfileSyncAdapter]
     * → driver-roadflare-repository wiring.
     *
     * @return Reconciliation result, or null if no Kind 30177 backup was found / fetch failed.
     */
    suspend fun fetchAndReconcileMuteFromBackup(signer: NostrSigner): MuteReconciliationResult? {
        val backup = try {
            nostrService.fetchProfileBackup()
        } catch (e: Exception) {
            Log.w(TAG, "fetchAndReconcileMute: profile backup fetch threw — skipping", e)
            return null
        }
        if (backup == null) {
            Log.d(TAG, "fetchAndReconcileMute: no Kind 30177 backup found — nothing to reconcile")
            return null
        }
        return reconcileMuteStateFromBackup(
            signer = signer,
            remoteMutedPubkeys = backup.mutedFollowerPubkeys,
            remoteCreatedAt = backup.createdAt
        )
    }

    /**
     * Reconcile the local lightweight-mute state against a remote Kind 30177 backup
     * using last-write-wins on `created_at` (issue #80).
     *
     * Per-pubkey rules:
     * - Pubkey in remote `muted_pubkeys`, not muted locally → mute locally with `mutedAt =
     *   remoteCreatedAt`. Backup is treated as the authoritative timestamp for that mute.
     * - Pubkey muted locally, also in remote → no-op (already muted; preserve original local
     *   `mutedAt`).
     * - Pubkey muted locally, NOT in remote, `local.mutedAt > remoteCreatedAt` → keep local
     *   mute (newer than remote backup; next publish will sync).
     * - Pubkey muted locally, NOT in remote, `local.mutedAt <= remoteCreatedAt` → unmute
     *   locally and best-effort re-deliver Kind 3186 (remote backup wrote the unmute after our
     *   mute).
     *
     * Pubkeys present only as remote-muted but unknown to the local follower list are skipped:
     * we cannot mute a row that does not exist. They will resolve naturally if/when the
     * follower is re-added via Kind 3187.
     *
     * Idempotent: running it again with the same inputs makes no further mutations.
     *
     * @param signer Driver's identity signer for publishing the unmute Kind 3186 events.
     * @param remoteMutedPubkeys The `muted_pubkeys` array from the remote Kind 30177 payload.
     * @param remoteCreatedAt The `created_at` timestamp of the remote Kind 30177 event (epoch seconds).
     * @return Counts of state changes applied. Caller can use these to decide whether to
     *   republish a Kind 30177 reflecting the merged local state.
     */
    suspend fun reconcileMuteStateFromBackup(
        signer: NostrSigner,
        remoteMutedPubkeys: List<String>,
        remoteCreatedAt: Long
    ): MuteReconciliationResult {
        val followers = repository.getFollowers()
        val remoteSet = remoteMutedPubkeys.toSet()

        var muted = 0
        var unmuted = 0
        var keyResent = 0

        // Case A: pubkey in remote backup → ensure local is muted.
        for (pubkey in remoteSet) {
            val local = followers.find { it.pubkey == pubkey }
            if (local == null) {
                Log.d(TAG, "reconcileMute: skipping unknown pubkey ${pubkey.take(8)}... (not in followers)")
                continue
            }
            if (local.mutedAt == null) {
                if (repository.setFollowerMuted(pubkey, remoteCreatedAt)) {
                    muted++
                    Log.d(TAG, "reconcileMute: muted ${pubkey.take(8)}... locally (backup wins, mutedAt=$remoteCreatedAt)")
                }
            }
        }

        // Case B: pubkey muted locally, NOT in remote backup → last-write-wins on local.mutedAt vs remoteCreatedAt.
        for (follower in followers) {
            val localMutedAt = follower.mutedAt ?: continue
            if (follower.pubkey in remoteSet) continue

            if (localMutedAt > remoteCreatedAt) {
                // Local mute is newer than the backup; keep local. Next publish syncs out.
                Log.d(TAG, "reconcileMute: kept local mute on ${follower.pubkey.take(8)}... (local=$localMutedAt > remote=$remoteCreatedAt)")
            } else {
                // Backup is newer and doesn't include this pubkey → remote unmuted it.
                // BUT: if the rider was meanwhile heavyweight-muted ("Removed") on another device,
                // the key has been rotated and they MUST stay excluded. Clear our local lightweight
                // mute (so the state stays consistent with the remote backup) but skip the Kind 3186
                // re-delivery — sending the post-rotation key would defeat the heavyweight mute.
                val isHeavyweightMuted = repository.isMuted(follower.pubkey)
                if (repository.setFollowerUnmuted(follower.pubkey)) {
                    unmuted++
                    Log.d(TAG, "reconcileMute: unmuted ${follower.pubkey.take(8)}... (remote=$remoteCreatedAt >= local=$localMutedAt)")

                    if (isHeavyweightMuted) {
                        Log.d(TAG, "reconcileMute: skipping Kind 3186 re-delivery to ${follower.pubkey.take(8)}... — heavyweight-muted (Removed) on another device")
                    } else {
                        // Best-effort key re-delivery so the rider can resume decrypting.
                        // Mirror PR #79's `resendCurrentKey` write-back so the null-fallback for
                        // `keyUpdatedAt` is persisted and a future Kind 30012 publish doesn't
                        // wall-clock-advance the public `key_updated_at` tag.
                        val key = repository.getRoadflareKey()
                        if (key != null) {
                            val keyUpdatedAt = repository.getKeyUpdatedAt() ?: run {
                                repository.updateKeyUpdatedAt(key.createdAt)
                                key.createdAt
                            }
                            if (sendKeyToFollower(signer, follower.pubkey, key, keyUpdatedAt)) {
                                repository.markFollowerKeySent(follower.pubkey, key.version)
                                keyResent++
                            }
                        }
                    }
                }
            }
        }

        if (muted > 0 || unmuted > 0) {
            Log.d(TAG, "reconcileMute: muted=$muted, unmuted=$unmuted, keyResent=$keyResent")
        }

        return MuteReconciliationResult(muted = muted, unmuted = unmuted, keyResent = keyResent)
    }
}

/**
 * Aggregate result of [RoadflareKeyManager.reconcileMuteStateFromBackup].
 *
 * @param muted Number of followers newly muted by this reconciliation pass (remote → local).
 * @param unmuted Number of followers newly unmuted by this pass (remote → local).
 * @param keyResent Number of Kind 3186 re-deliveries triggered by an unmute.
 */
data class MuteReconciliationResult(
    val muted: Int,
    val unmuted: Int,
    val keyResent: Int
) {
    /** True when the reconciliation produced at least one local state change. */
    val changed: Boolean get() = muted > 0 || unmuted > 0
}
