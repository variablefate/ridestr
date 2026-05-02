package com.ridestr.common.roadflare

import androidx.test.core.app.ApplicationProvider
import com.ridestr.common.data.DriverRoadflareRepository
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.DriverRoadflareKey
import com.ridestr.common.nostr.events.MutedRider
import com.ridestr.common.nostr.events.RoadflareFollower
import com.ridestr.common.nostr.events.RoadflareKey
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression tests for the lightweight per-follower mute feature (issue #80).
 *
 * Covers the seven acceptance criteria from the issue:
 * 1. Mute / unmute basic flow.
 * 2. Cross-device reconciliation: older-local mute + newer-backup → unmute locally.
 * 3. Cross-device reconciliation: newer-local mute + older-backup → keep local mute.
 * 4. Offline-mute persistence (mute → SharedPreferences round-trip → still muted).
 * 5. Kind 30177 missing `muted_pubkeys` parses gracefully (delegated to
 *    [com.ridestr.common.nostr.events.ProfileBackupEventMutedPubkeysParseTest], because
 *    NIP-44 encryption is not exercisable from a unit test).
 * 6. Kind 3186 send-loop skips muted pubkeys.
 * 7. Unmute triggers Kind 3186 with the current key.
 *
 * Robolectric provides Android Context for [DriverRoadflareRepository]'s SharedPreferences;
 * MockK fakes [NostrService] so we can verify (or refuse) Kind 3186 publishes deterministically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RoadflareMuteTest {

    private lateinit var repository: DriverRoadflareRepository
    private lateinit var nostrService: NostrService
    private lateinit var keyManager: RoadflareKeyManager
    private lateinit var signer: NostrSigner

    private val driverKey = DriverRoadflareKey(
        privateKey = "a".repeat(64),
        publicKey = "b".repeat(64),
        version = 7,
        createdAt = 1_000L
    )
    private val keyUpdatedAt = 1_000L

    @Before
    fun setUp() {
        // Fresh SharedPreferences-backed repository per test (Robolectric resets app state).
        // The singleton accessor is bypassed via direct ctor so the field doesn't leak between tests.
        repository = DriverRoadflareRepository(ApplicationProvider.getApplicationContext())
        repository.clearAll()

        nostrService = mockk(relaxed = true)
        signer = mockk(relaxed = true)
        every { signer.pubKey } returns "deadbeef".repeat(8)

        // Default: any Kind 3186 publish "succeeds". Individual tests override as needed.
        coEvery {
            nostrService.publishRoadflareKeyShare(any(), any(), any(), any())
        } returns "fake-event-id"

        keyManager = RoadflareKeyManager(repository, nostrService)

        // Establish a baseline driver key + keyUpdatedAt so the unmute path has something to send.
        repository.setRoadflareKey(driverKey)
        repository.updateKeyUpdatedAt(keyUpdatedAt)
    }

    @After
    fun tearDown() {
        repository.clearAll()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun seedFollower(
        pubkey: String,
        approved: Boolean = true,
        keyVersionSent: Int = driverKey.version,
        mutedAt: Long? = null
    ) {
        repository.addFollower(
            RoadflareFollower(
                pubkey = pubkey,
                name = "rider-$pubkey",
                addedAt = 500L,
                approved = approved,
                keyVersionSent = keyVersionSent,
                mutedAt = mutedAt
            )
        )
    }

    // ── Acceptance criterion 1: mute / unmute basic flow ─────────────────────

    @Test
    fun `muteFollower sets mutedAt and reports updated`() {
        seedFollower("rider-A")

        val updated = keyManager.muteFollower("rider-A", now = 9_999L)

        assertTrue("expected updated=true for known follower", updated)
        val follower = repository.getFollowers().single { it.pubkey == "rider-A" }
        assertEquals(9_999L, follower.mutedAt)
        assertTrue("isFollowerMuted should now report true", repository.isFollowerMuted("rider-A"))
    }

    @Test
    fun `muteFollower no-ops on unknown pubkey`() {
        val updated = keyManager.muteFollower("ghost-rider")
        assertFalse("expected updated=false for unknown pubkey", updated)
        assertTrue("repository should still be empty of followers", repository.getFollowers().isEmpty())
    }

    @Test
    fun `unmuteFollower clears mutedAt and reports updated`() = runTest {
        seedFollower("rider-A", mutedAt = 100L)

        val updated = keyManager.unmuteFollower(signer, "rider-A")

        assertTrue(updated)
        val follower = repository.getFollowers().single { it.pubkey == "rider-A" }
        assertNull("mutedAt should be cleared", follower.mutedAt)
        assertFalse(repository.isFollowerMuted("rider-A"))
    }

    @Test
    fun `unmuteFollower no-ops on unknown pubkey and does not publish key`() = runTest {
        val updated = keyManager.unmuteFollower(signer, "ghost-rider")
        assertFalse(updated)
        coVerify(exactly = 0) {
            nostrService.publishRoadflareKeyShare(any(), eq("ghost-rider"), any(), any())
        }
    }

    // ── Acceptance criterion 2: cross-device — older local + newer backup ─────

    @Test
    fun `reconcile unmutes local follower when remote backup is newer and omits pubkey`() = runTest {
        // Local: muted at T0 = 1_000, but remote backup created at T1 = 2_000 omits the pubkey
        // → the remote unmute (any time at-or-after T0) wins.
        seedFollower("rider-A", mutedAt = 1_000L)

        val result = keyManager.reconcileMuteStateFromBackup(
            signer = signer,
            remoteMutedPubkeys = emptyList(),
            remoteCreatedAt = 2_000L
        )

        assertEquals(0, result.muted)
        assertEquals(1, result.unmuted)
        assertEquals(1, result.keyResent)
        assertNull(repository.getFollowers().single { it.pubkey == "rider-A" }.mutedAt)
    }

    // ── Acceptance criterion 3: cross-device — newer local + older backup ─────

    @Test
    fun `reconcile keeps local mute when local timestamp is newer than backup`() = runTest {
        // Local: muted at T1 = 5_000, but remote backup is older (T0 = 2_000) and omits the pubkey
        // → local mute is the more recent write → keep it; next publish syncs.
        seedFollower("rider-A", mutedAt = 5_000L)

        val result = keyManager.reconcileMuteStateFromBackup(
            signer = signer,
            remoteMutedPubkeys = emptyList(),
            remoteCreatedAt = 2_000L
        )

        assertEquals(0, result.muted)
        assertEquals(0, result.unmuted)
        assertEquals(0, result.keyResent)
        assertEquals(5_000L, repository.getFollowers().single { it.pubkey == "rider-A" }.mutedAt)
        // No Kind 3186 sent — the local mute stays intact.
        coVerify(exactly = 0) {
            nostrService.publishRoadflareKeyShare(any(), eq("rider-A"), any(), any())
        }
    }

    @Test
    fun `reconcile mutes local follower when remote backup includes pubkey not yet muted locally`() = runTest {
        // Inverse case: remote published a mute, local hasn't seen it.
        seedFollower("rider-A", mutedAt = null)

        val result = keyManager.reconcileMuteStateFromBackup(
            signer = signer,
            remoteMutedPubkeys = listOf("rider-A"),
            remoteCreatedAt = 3_000L
        )

        assertEquals(1, result.muted)
        assertEquals(0, result.unmuted)
        assertEquals(3_000L, repository.getFollowers().single { it.pubkey == "rider-A" }.mutedAt)
    }

    @Test
    fun `reconcile is idempotent on a second pass with same inputs`() = runTest {
        seedFollower("rider-A", mutedAt = null)

        val first = keyManager.reconcileMuteStateFromBackup(signer, listOf("rider-A"), remoteCreatedAt = 3_000L)
        val second = keyManager.reconcileMuteStateFromBackup(signer, listOf("rider-A"), remoteCreatedAt = 3_000L)

        assertEquals(1, first.muted)
        assertEquals(0, second.muted)
        assertFalse(second.changed)
    }

    @Test
    fun `reconcile skips remote-muted pubkeys not present in local follower list`() = runTest {
        // Remote backup mentions a pubkey we don't follow — nothing to mute, no error.
        val result = keyManager.reconcileMuteStateFromBackup(
            signer = signer,
            remoteMutedPubkeys = listOf("ghost-rider"),
            remoteCreatedAt = 1_500L
        )

        assertEquals(0, result.muted)
        assertEquals(0, result.unmuted)
        assertFalse(result.changed)
    }

    // ── Acceptance criterion 4: offline-mute persistence ──────────────────────

    @Test
    fun `mute persists across repository reload simulating app restart`() {
        seedFollower("rider-A")

        keyManager.muteFollower("rider-A", now = 4_242L)

        // Simulate process death: build a new repository over the same SharedPreferences.
        val reloaded = DriverRoadflareRepository(ApplicationProvider.getApplicationContext())
        val follower = reloaded.getFollowers().single { it.pubkey == "rider-A" }
        assertEquals(4_242L, follower.mutedAt)
        assertTrue(reloaded.isFollowerMuted("rider-A"))
    }

    // ── Acceptance criterion 6: Kind 3186 send-loop skips muted pubkeys ───────

    @Test
    fun `getActiveFollowerPubkeys excludes lightweight-muted followers`() {
        seedFollower("rider-A", mutedAt = null)
        seedFollower("rider-B", mutedAt = 1_500L)
        seedFollower("rider-C", mutedAt = null)

        val active = repository.getActiveFollowerPubkeys()

        assertEquals(setOf("rider-A", "rider-C"), active.toSet())
    }

    @Test
    fun `getFollowersNeedingKey excludes lightweight-muted followers`() {
        // All seeded with stale keyVersionSent so they would otherwise need a re-send.
        seedFollower("rider-A", keyVersionSent = 0, mutedAt = null)
        seedFollower("rider-B", keyVersionSent = 0, mutedAt = 1_500L)

        val needing = repository.getFollowersNeedingKey().map { it.pubkey }.toSet()

        assertEquals(setOf("rider-A"), needing)
    }

    @Test
    fun `lightweight mute is independent of heavyweight MutedRider`() {
        seedFollower("rider-A", mutedAt = 1L)
        // Heavyweight mute would also flag "rider-A" via the legacy MutedRider list — the
        // lightweight check must remain orthogonal so neither leaks into the other.
        val current = repository.state.value!!
        repository.restoreFromBackup(
            current.copy(
                muted = listOf(MutedRider(pubkey = "rider-Z", mutedAt = 1L))
            )
        )

        assertTrue(repository.isFollowerMuted("rider-A"))
        assertFalse(repository.isMuted("rider-A"))
        assertFalse(repository.isFollowerMuted("rider-Z"))
        assertTrue(repository.isMuted("rider-Z"))
    }

    // ── Acceptance criterion 7: unmute triggers Kind 3186 with current key ────

    @Test
    fun `unmuteFollower publishes Kind 3186 with current key and keyUpdatedAt`() = runTest {
        seedFollower("rider-A", mutedAt = 100L)

        keyManager.unmuteFollower(signer, "rider-A")

        coVerify(exactly = 1) {
            nostrService.publishRoadflareKeyShare(
                signer = signer,
                followerPubKey = "rider-A",
                roadflareKey = match<RoadflareKey> {
                    it.privateKey == driverKey.privateKey &&
                        it.publicKey == driverKey.publicKey &&
                        it.version == driverKey.version &&
                        it.keyUpdatedAt == keyUpdatedAt
                },
                keyUpdatedAt = keyUpdatedAt
            )
        }
        // Successful re-delivery should advance keyVersionSent on the follower row.
        assertEquals(driverKey.version, repository.getFollowers().single { it.pubkey == "rider-A" }.keyVersionSent)
    }

    @Test
    fun `unmuteFollower clears mutedAt even when Kind 3186 send fails`() = runTest {
        // A transient relay failure must not block the local unmute — the next
        // ensureFollowersHaveCurrentKey run can recover delivery.
        coEvery {
            nostrService.publishRoadflareKeyShare(any(), eq("rider-A"), any(), any())
        } returns null
        seedFollower("rider-A", mutedAt = 100L)

        val updated = keyManager.unmuteFollower(signer, "rider-A")

        assertTrue("local unmute is the source of truth — must report updated even on send failure", updated)
        assertNull(repository.getFollowers().single { it.pubkey == "rider-A" }.mutedAt)
    }

    @Test
    fun `reconcile unmute also re-delivers Kind 3186 with current key`() = runTest {
        // The cross-device unmute path (reconciliation case 3) must re-deliver the key the
        // same way as the explicit unmute UX action — otherwise the rider would be silently
        // unable to decrypt new broadcasts after a remote unmute.
        seedFollower("rider-A", mutedAt = 1_000L)

        keyManager.reconcileMuteStateFromBackup(
            signer = signer,
            remoteMutedPubkeys = emptyList(),
            remoteCreatedAt = 2_000L
        )

        coVerify(exactly = 1) {
            nostrService.publishRoadflareKeyShare(
                signer = signer,
                followerPubKey = "rider-A",
                roadflareKey = match<RoadflareKey> { it.version == driverKey.version },
                keyUpdatedAt = keyUpdatedAt
            )
        }
    }

    // ── Repository-level invariants for the new mute helpers ──────────────────

    @Test
    fun `getMutedFollowerPubkeys returns only lightweight-muted entries`() {
        seedFollower("rider-A", mutedAt = 1L)
        seedFollower("rider-B", mutedAt = null)
        seedFollower("rider-C", mutedAt = 99L)

        assertEquals(setOf("rider-A", "rider-C"), repository.getMutedFollowerPubkeys().toSet())
    }

    @Test
    fun `setFollowerMuted on already-muted follower overwrites mutedAt`() {
        seedFollower("rider-A", mutedAt = 100L)

        val updated = repository.setFollowerMuted("rider-A", 999L)

        assertTrue(updated)
        assertEquals(999L, repository.getFollowers().single { it.pubkey == "rider-A" }.mutedAt)
    }

    @Test
    fun `setFollowerUnmuted on already-unmuted follower still reports updated`() {
        // Idempotent semantic: as long as the row exists, the call is "successful".
        // Callers can still trigger republish; reconciliation doesn't rely on the boolean.
        seedFollower("rider-A", mutedAt = null)
        assertTrue(repository.setFollowerUnmuted("rider-A"))
    }
}
