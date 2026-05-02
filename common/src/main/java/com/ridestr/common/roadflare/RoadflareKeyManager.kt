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
     * Receiving Kind 3187 NEVER rotates the key or advances
     * [DriverRoadflareRepository.getKeyUpdatedAt] — other followers' stored keys
     * are not invalidated by one rider re-adding the driver. See
     * [FollowNotificationResult] for the per-state outcomes.
     *
     * @param signer The driver's identity signer (for publishing Kind 3186/30012).
     * @param followerPubkey The rider's pubkey from the Kind 3187 event.
     * @param riderName The rider's display name from the notification body.
     * @return The action taken; the call site uses this to decide whether to
     *   surface an OS notification (only for [FollowNotificationResult.AddedAsPending])
     *   and whether to refresh profile backups.
     */
    suspend fun handleFollowNotification(
        signer: NostrSigner,
        followerPubkey: String,
        riderName: String
    ): FollowNotificationResult {
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

        if (repository.isMuted(followerPubkey)) {
            // Muted re-add — unmute and re-deliver the current key.
            // Does NOT rotate; mute removal is published via Kind 30012 with
            // keyUpdatedAt unchanged so other followers' stored keys stay valid.
            repository.unmuteRider(followerPubkey)
            val keySent = resendCurrentKey(signer, followerPubkey)
            // Publish state regardless of key send outcome — the unmute itself is observable.
            val newState = repository.state.value
            if (newState != null) {
                nostrService.publishDriverRoadflareState(signer, newState)
            }
            return if (keySent) {
                Log.d(TAG, "Unmuted and re-sent key to ${followerPubkey.take(8)}...")
                FollowNotificationResult.UnmutedAndKeyResent
            } else {
                FollowNotificationResult.Failed("unmuted but key resend failed")
            }
        }

        if (existing.approved) {
            // Approved re-add — re-deliver the current key. State unchanged, no Kind 30012 republish.
            val keySent = resendCurrentKey(signer, followerPubkey)
            return if (keySent) {
                Log.d(TAG, "Re-sent key to approved follower ${followerPubkey.take(8)}...")
                FollowNotificationResult.KeyResent
            } else {
                FollowNotificationResult.Failed("no current key to resend")
            }
        }

        // Pending (not yet approved) re-add — driver still owes an approval via UI.
        return FollowNotificationResult.AlreadyPending
    }

    /**
     * Re-send the current Kind 3186 key share to a specific follower.
     * Uses the existing keyUpdatedAt — does NOT advance it.
     *
     * @return true if the key was sent; false if no key is configured yet or send failed.
     */
    private suspend fun resendCurrentKey(
        signer: NostrSigner,
        followerPubkey: String
    ): Boolean {
        val key = repository.getRoadflareKey() ?: return false
        val keyUpdatedAt = repository.getKeyUpdatedAt() ?: key.createdAt
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
}
