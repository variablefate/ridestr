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
     * Uses short expiration (5 minutes) to reduce relay storage.
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
            Log.d(TAG, "Sent key v${key.version} to follower ${followerPubkey.take(8)}... (expires in 5 min)")
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

        // Mark as approved
        repository.approveFollower(followerPubkey)

        // Send key to follower
        val success = sendKeyToFollower(signer, followerPubkey, key, keyUpdatedAt)
        if (success) {
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
     * Handle a new follow notification from a rider.
     * Adds follower as pending (requires separate approval by driver).
     *
     * @param followerPubkey The rider's pubkey
     * @param riderName The rider's display name (from notification)
     * @return true if follower was added as pending
     */
    fun handleNewFollower(
        followerPubkey: String,
        riderName: String
    ): Boolean {
        // Just add as pending - driver must approve separately
        return addPendingFollower(followerPubkey, riderName)
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
