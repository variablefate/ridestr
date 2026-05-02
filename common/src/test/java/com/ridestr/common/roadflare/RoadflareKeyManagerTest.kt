package com.ridestr.common.roadflare

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ridestr.common.data.DriverRoadflareRepository
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.DriverRoadflareKey
import com.ridestr.common.nostr.events.DriverRoadflareState
import com.ridestr.common.nostr.events.RoadflareFollower
import com.ridestr.common.nostr.events.RoadflareKey
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.confirmVerified
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins driver-side Kind 3187 follow-notification handling against
 * variablefate/ridestr#78: re-add must NEVER rotate the shared
 * RoadFlare key or advance keyUpdatedAt — other followers' stored
 * keys must not go stale because one rider re-added the driver.
 */
@RunWith(RobolectricTestRunner::class)
class RoadflareKeyManagerTest {

    private lateinit var repository: DriverRoadflareRepository
    private lateinit var nostrService: NostrService
    private lateinit var signer: NostrSigner
    private lateinit var keyManager: RoadflareKeyManager

    private val alicePubkey = "a".repeat(64)
    private val bobPubkey = "b".repeat(64)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Clear persistent state across tests; the repository persists to SharedPreferences.
        context.getSharedPreferences("roadflare_driver_state", Context.MODE_PRIVATE)
            .edit().clear().commit()
        repository = DriverRoadflareRepository(context)

        nostrService = mockk(relaxed = true)
        signer = mockk(relaxed = true)
        coEvery {
            nostrService.publishRoadflareKeyShare(any(), any(), any(), any())
        } returns "evt-share"
        coEvery {
            nostrService.publishDriverRoadflareState(any(), any())
        } returns "evt-state"

        keyManager = RoadflareKeyManager(repository, nostrService)
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun seedKey(version: Int, keyUpdatedAt: Long): DriverRoadflareKey {
        val key = DriverRoadflareKey(
            privateKey = "p".repeat(64),
            publicKey = "k".repeat(64),
            version = version,
            createdAt = keyUpdatedAt
        )
        repository.setRoadflareKey(key)
        repository.updateKeyUpdatedAt(keyUpdatedAt)
        return key
    }

    private fun seedFollower(
        pubkey: String,
        approved: Boolean,
        keyVersionSent: Int
    ) {
        repository.addFollower(
            RoadflareFollower(
                pubkey = pubkey,
                name = "",
                addedAt = 1_000L,
                approved = approved,
                keyVersionSent = keyVersionSent
            )
        )
    }

    // ---------------------------------------------------------------------
    // New follower path
    // ---------------------------------------------------------------------

    @Test
    fun `new follow adds rider as pending and does not change keyUpdatedAt`() = runBlocking {
        seedKey(version = 1, keyUpdatedAt = 1_000L)
        seedFollower(alicePubkey, approved = true, keyVersionSent = 1)

        val result = keyManager.handleFollowNotification(signer, bobPubkey, "Bob")

        assertEquals(FollowNotificationResult.AddedAsPending, result)
        assertEquals(1_000L, repository.getKeyUpdatedAt())
        val bob = repository.getFollowers().single { it.pubkey == bobPubkey }
        assertFalse("Bob must be pending until driver approves", bob.approved)
        assertEquals(0, bob.keyVersionSent)

        // Pinning the issue's regression contract: rider B following must not
        // resend a key to rider A and must not republish Kind 30012.
        coVerify(exactly = 0) { nostrService.publishRoadflareKeyShare(any(), any(), any(), any()) }
        coVerify(exactly = 0) { nostrService.publishDriverRoadflareState(any(), any()) }
    }

    @Test
    fun `new follow does not affect existing approved follower's keyVersionSent`() = runBlocking {
        seedKey(version = 1, keyUpdatedAt = 1_000L)
        seedFollower(alicePubkey, approved = true, keyVersionSent = 1)

        keyManager.handleFollowNotification(signer, bobPubkey, "Bob")

        val alice = repository.getFollowers().single { it.pubkey == alicePubkey }
        assertEquals(1, alice.keyVersionSent)
        assertTrue(alice.approved)
    }

    // ---------------------------------------------------------------------
    // Approved re-add path
    // ---------------------------------------------------------------------

    @Test
    fun `re-add of approved follower resends current key without bumping keyUpdatedAt`() = runBlocking {
        seedKey(version = 1, keyUpdatedAt = 1_000L)
        seedFollower(alicePubkey, approved = true, keyVersionSent = 1)

        val keySlot = slot<RoadflareKey>()
        val pubkeySlot = slot<String>()
        val keyUpdatedAtSlot = slot<Long>()
        coEvery {
            nostrService.publishRoadflareKeyShare(
                signer = any(),
                followerPubKey = capture(pubkeySlot),
                roadflareKey = capture(keySlot),
                keyUpdatedAt = capture(keyUpdatedAtSlot)
            )
        } returns "evt-share"

        val result = keyManager.handleFollowNotification(signer, alicePubkey, "Alice")

        assertEquals(FollowNotificationResult.KeyResent, result)
        assertEquals(1_000L, repository.getKeyUpdatedAt())
        assertEquals(alicePubkey, pubkeySlot.captured)
        assertEquals(1, keySlot.captured.version)
        assertEquals(1_000L, keyUpdatedAtSlot.captured)
        coVerify(exactly = 1) { nostrService.publishRoadflareKeyShare(any(), any(), any(), any()) }
        // State is unchanged for an approved-rider re-delivery; no Kind 30012 republish.
        coVerify(exactly = 0) { nostrService.publishDriverRoadflareState(any(), any()) }
        // markFollowerKeySent must be called so the follower record stays in sync with what was sent.
        val alice = repository.getFollowers().single { it.pubkey == alicePubkey }
        assertEquals(1, alice.keyVersionSent)
    }

    @Test
    fun `re-add of approved follower fails if no current key configured`() = runBlocking {
        // No seedKey — driver lost local state but still has follower record.
        seedFollower(alicePubkey, approved = true, keyVersionSent = 1)

        val result = keyManager.handleFollowNotification(signer, alicePubkey, "Alice")

        assertTrue("expected Failed result, got $result", result is FollowNotificationResult.Failed)
        coVerify(exactly = 0) { nostrService.publishRoadflareKeyShare(any(), any(), any(), any()) }
        coVerify(exactly = 0) { nostrService.publishDriverRoadflareState(any(), any()) }
    }

    @Test
    fun `re-add of approved follower returns Failed when key share publish fails`() = runBlocking {
        seedKey(version = 1, keyUpdatedAt = 1_000L)
        seedFollower(alicePubkey, approved = true, keyVersionSent = 1)
        coEvery {
            nostrService.publishRoadflareKeyShare(any(), any(), any(), any())
        } returns null

        val result = keyManager.handleFollowNotification(signer, alicePubkey, "Alice")

        assertTrue("expected Failed, got $result", result is FollowNotificationResult.Failed)
        // markFollowerKeySent must NOT be called when the publish fails.
        val alice = repository.getFollowers().single { it.pubkey == alicePubkey }
        assertEquals(1, alice.keyVersionSent)
        assertEquals(1_000L, repository.getKeyUpdatedAt())
    }

    @Test
    fun `re-add of approved follower with null keyUpdatedAt persists fallback to repository`() = runBlocking {
        // Edge case: driver has a key but keyUpdatedAt was never set (post-restore).
        // resendCurrentKey must mirror ensureFollowersHaveCurrentKey and write the
        // fallback back so a later Kind 30012 publish does not fall through to
        // DriverRoadflareStateEvent.create's wall-clock fallback (which would
        // silently advance the public key_updated_at tag).
        val key = DriverRoadflareKey(
            privateKey = "p".repeat(64),
            publicKey = "k".repeat(64),
            version = 1,
            createdAt = 4_242L
        )
        repository.setRoadflareKey(key)
        // Deliberately do NOT call updateKeyUpdatedAt — leave it null.
        seedFollower(alicePubkey, approved = true, keyVersionSent = 1)
        assertNull("setup precondition", repository.getKeyUpdatedAt())

        val result = keyManager.handleFollowNotification(signer, alicePubkey, "Alice")

        assertEquals(FollowNotificationResult.KeyResent, result)
        assertEquals(4_242L, repository.getKeyUpdatedAt())
    }

    // ---------------------------------------------------------------------
    // Muted re-add path
    // ---------------------------------------------------------------------

    @Test
    fun `re-add of muted follower is no-op and preserves driver's Remove decision`() = runBlocking {
        // Auto-unmuting on Kind 3187 would silently bypass driver consent and
        // conflict with mergeMutedLists' "once muted, always muted" cross-device
        // invariant. The handler must leave muted re-adds alone.
        seedKey(version = 2, keyUpdatedAt = 2_000L)
        seedFollower(alicePubkey, approved = true, keyVersionSent = 1)
        repository.muteRider(alicePubkey, reason = "removed by driver")

        val result = keyManager.handleFollowNotification(signer, alicePubkey, "Alice")

        assertEquals(FollowNotificationResult.AlreadyMuted, result)
        assertEquals(2_000L, repository.getKeyUpdatedAt())
        assertTrue("Alice must remain muted", repository.isMuted(alicePubkey))
        coVerify(exactly = 0) { nostrService.publishRoadflareKeyShare(any(), any(), any(), any()) }
        coVerify(exactly = 0) { nostrService.publishDriverRoadflareState(any(), any()) }
    }

    @Test
    fun `muted pubkey with no followers record is still treated as muted`() = runBlocking {
        // After cross-device sync, a mute entry can outlive its followers record
        // (e.g., the rider unfollowed and Kind 30011 verification filtered them
        // out of the followers list, but the mute entry survives via union-merge).
        // The handler must NOT fall through to AddedAsPending and silently let
        // the muted rider back in as pending.
        seedKey(version = 1, keyUpdatedAt = 1_000L)
        repository.muteRider(alicePubkey, reason = "removed by driver")
        // No seedFollower(alice, ...) — alice is muted but not in followers list.

        val result = keyManager.handleFollowNotification(signer, alicePubkey, "Alice")

        assertEquals(FollowNotificationResult.AlreadyMuted, result)
        assertTrue("alice must remain muted", repository.isMuted(alicePubkey))
        assertTrue(
            "alice must NOT be re-added as pending",
            repository.getFollowers().none { it.pubkey == alicePubkey }
        )
        coVerify(exactly = 0) { nostrService.publishRoadflareKeyShare(any(), any(), any(), any()) }
        coVerify(exactly = 0) { nostrService.publishDriverRoadflareState(any(), any()) }
    }

    @Test
    fun `re-add of muted follower without current key is also no-op`() = runBlocking {
        // Edge case: driver has a follower record + a stale mute but no local key
        // (post-restore). The no-op contract holds regardless of key presence.
        seedFollower(alicePubkey, approved = true, keyVersionSent = 1)
        repository.muteRider(alicePubkey)

        val result = keyManager.handleFollowNotification(signer, alicePubkey, "Alice")

        assertEquals(FollowNotificationResult.AlreadyMuted, result)
        assertTrue(repository.isMuted(alicePubkey))
        assertNull(repository.getKeyUpdatedAt())
        coVerify(exactly = 0) { nostrService.publishRoadflareKeyShare(any(), any(), any(), any()) }
        coVerify(exactly = 0) { nostrService.publishDriverRoadflareState(any(), any()) }
    }

    // ---------------------------------------------------------------------
    // Pending re-add path
    // ---------------------------------------------------------------------

    @Test
    fun `re-add of pending follower is no-op and does not change state`() = runBlocking {
        seedKey(version = 1, keyUpdatedAt = 1_000L)
        seedFollower(alicePubkey, approved = false, keyVersionSent = 0)

        val result = keyManager.handleFollowNotification(signer, alicePubkey, "Alice")

        assertEquals(FollowNotificationResult.AlreadyPending, result)
        val alice = repository.getFollowers().single { it.pubkey == alicePubkey }
        assertFalse("Alice must remain pending until driver approves", alice.approved)
        assertEquals(0, alice.keyVersionSent)
        assertEquals(1_000L, repository.getKeyUpdatedAt())
        coVerify(exactly = 0) { nostrService.publishRoadflareKeyShare(any(), any(), any(), any()) }
        coVerify(exactly = 0) { nostrService.publishDriverRoadflareState(any(), any()) }
    }

    // ---------------------------------------------------------------------
    // Cross-rider isolation invariant — the headline bug from #78
    // ---------------------------------------------------------------------

    @Test
    fun `rider B follow leaves rider A's stored key valid`() = runBlocking {
        // Setup matches the issue's prescribed regression test:
        // rider A is approved with the current key; rider B follows for the first time.
        seedKey(version = 3, keyUpdatedAt = 3_000L)
        seedFollower(alicePubkey, approved = true, keyVersionSent = 3)

        keyManager.handleFollowNotification(signer, bobPubkey, "Bob")

        // Driver's published keyUpdatedAt must be unchanged so rider A's
        // checkStaleKeys() does not flag her stored key.
        assertEquals(3_000L, repository.getKeyUpdatedAt())
        val alice = repository.getFollowers().single { it.pubkey == alicePubkey }
        assertEquals(3, alice.keyVersionSent)
        coVerify(exactly = 0) { nostrService.publishRoadflareKeyShare(any(), eq(alicePubkey), any(), any()) }
        coVerify(exactly = 0) { nostrService.publishDriverRoadflareState(any(), any()) }
        confirmVerified(nostrService)
    }

    @Test
    fun `rider B re-add leaves rider A's stored key valid`() = runBlocking {
        // Same invariant under a different trigger: rider B is an approved
        // follower re-adding the driver from a fresh install.
        seedKey(version = 3, keyUpdatedAt = 3_000L)
        seedFollower(alicePubkey, approved = true, keyVersionSent = 3)
        seedFollower(bobPubkey, approved = true, keyVersionSent = 3)

        keyManager.handleFollowNotification(signer, bobPubkey, "Bob")

        assertEquals(3_000L, repository.getKeyUpdatedAt())
        val alice = repository.getFollowers().single { it.pubkey == alicePubkey }
        assertEquals(3, alice.keyVersionSent)
        // Bob received a re-delivered key, but Alice did not.
        coVerify(exactly = 1) { nostrService.publishRoadflareKeyShare(any(), eq(bobPubkey), any(), any()) }
        coVerify(exactly = 0) { nostrService.publishRoadflareKeyShare(any(), eq(alicePubkey), any(), any()) }
        // Pin the no-Kind-30012-republish invariant: any unexpected publishDriverRoadflareState
        // would be a regression of the keyUpdatedAt-can-silently-advance hazard.
        coVerify(exactly = 0) { nostrService.publishDriverRoadflareState(any(), any()) }
        confirmVerified(nostrService)
    }
}
