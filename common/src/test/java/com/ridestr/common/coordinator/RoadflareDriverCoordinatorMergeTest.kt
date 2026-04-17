package com.ridestr.common.coordinator

import com.ridestr.common.data.DriverRoadflareRepository
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.MutedRider
import com.ridestr.common.nostr.events.RoadflareFollower
import io.mockk.mockk
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
 * Pure logic tests for RoadflareDriverCoordinator's internal merge helpers.
 * No Nostr I/O; NostrService and DriverRoadflareRepository are mocked but never called.
 * Robolectric runner provides android.util.Log stubs used by the clamp-warning path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RoadflareDriverCoordinatorMergeTest {

    private lateinit var coordinator: RoadflareDriverCoordinator

    @Before
    fun setUp() {
        coordinator = RoadflareDriverCoordinator(
            nostrService = mockk(relaxed = true),
            driverRoadflareRepository = mockk(relaxed = true)
        )
    }

    private fun follower(
        pubkey: String,
        approved: Boolean = false,
        keyVersionSent: Int = 0,
        addedAt: Long = 1_000L
    ) = RoadflareFollower(
        pubkey = pubkey,
        name = "",
        addedAt = addedAt,
        approved = approved,
        keyVersionSent = keyVersionSent
    )

    // ── mergeFollowerLists ────────────────────────────────────────────────────

    @Test
    fun `mergeFollowerLists unions disjoint pubkeys`() {
        val local = listOf(follower("A"))
        val remote = listOf(follower("B"))

        val merged = coordinator.mergeFollowerLists(
            local = local,
            remote = remote,
            selectedKeyVersion = 1,
            localUpdatedAt = 100L,
            remoteUpdatedAt = 100L
        )

        assertEquals(setOf("A", "B"), merged.map { it.pubkey }.toSet())
    }

    @Test
    fun `mergeFollowerLists approved is logical OR across duplicates`() {
        val local = listOf(follower("A", approved = true))
        val remote = listOf(follower("A", approved = false))

        val merged = coordinator.mergeFollowerLists(local, remote, 1, 100L, 100L)

        assertEquals(1, merged.size)
        assertTrue("approved=true must survive merge", merged[0].approved)
    }

    @Test
    fun `mergeFollowerLists approved reverse direction also OR`() {
        val local = listOf(follower("A", approved = false))
        val remote = listOf(follower("A", approved = true))

        val merged = coordinator.mergeFollowerLists(local, remote, 1, 100L, 100L)

        assertTrue(merged[0].approved)
    }

    @Test
    fun `mergeFollowerLists keyVersionSent takes max clamped to selected`() {
        val local = listOf(follower("A", keyVersionSent = 5))
        val remote = listOf(follower("A", keyVersionSent = 7))

        val merged = coordinator.mergeFollowerLists(
            local = local,
            remote = remote,
            selectedKeyVersion = 6,  // clamp ceiling
            localUpdatedAt = 100L,
            remoteUpdatedAt = 100L
        )

        assertEquals(6, merged[0].keyVersionSent)
    }

    @Test
    fun `mergeFollowerLists keyVersionSent unclamped when under ceiling`() {
        val local = listOf(follower("A", keyVersionSent = 2))
        val remote = listOf(follower("A", keyVersionSent = 3))

        val merged = coordinator.mergeFollowerLists(local, remote, 10, 100L, 100L)

        assertEquals(3, merged[0].keyVersionSent)
    }

    @Test
    fun `mergeFollowerLists addedAt uses min to preserve earliest follow time`() {
        val local = listOf(follower("A", addedAt = 5_000L))
        val remote = listOf(follower("A", addedAt = 1_000L))

        val merged = coordinator.mergeFollowerLists(local, remote, 1, 100L, 100L)

        assertEquals(1_000L, merged[0].addedAt)
    }

    @Test
    fun `mergeFollowerLists prunes local-only when remote is newer`() {
        val local = listOf(follower("A"), follower("B"))
        val remote = listOf(follower("A"))  // B removed upstream

        val merged = coordinator.mergeFollowerLists(
            local = local,
            remote = remote,
            selectedKeyVersion = 1,
            localUpdatedAt = 100L,
            remoteUpdatedAt = 200L  // remote newer → prune B
        )

        assertEquals(listOf("A"), merged.map { it.pubkey })
    }

    @Test
    fun `mergeFollowerLists keeps local-only when local is newer`() {
        val local = listOf(follower("A"), follower("B"))
        val remote = listOf(follower("A"))

        val merged = coordinator.mergeFollowerLists(
            local = local,
            remote = remote,
            selectedKeyVersion = 1,
            localUpdatedAt = 200L,  // local newer → keep B
            remoteUpdatedAt = 100L
        )

        assertEquals(setOf("A", "B"), merged.map { it.pubkey }.toSet())
    }

    // ── mergeMutedLists ───────────────────────────────────────────────────────

    @Test
    fun `mergeMutedLists unions by pubkey`() {
        val local = listOf(MutedRider(pubkey = "A", mutedAt = 1_000L))
        val remote = listOf(MutedRider(pubkey = "B", mutedAt = 2_000L))

        val merged = coordinator.mergeMutedLists(local, remote)

        assertEquals(setOf("A", "B"), merged.map { it.pubkey }.toSet())
    }

    @Test
    fun `mergeMutedLists keeps local entry on duplicate - never auto-unmutes`() {
        // Local has A muted; remote has A unchanged — merge must not drop A.
        val local = listOf(MutedRider(pubkey = "A", mutedAt = 1_000L, reason = "spam"))
        val remote = listOf(MutedRider(pubkey = "A", mutedAt = 2_000L, reason = "other"))

        val merged = coordinator.mergeMutedLists(local, remote)

        assertEquals(1, merged.size)
        // Local wins on duplicate — earliest mute reason preserved
        assertEquals("spam", merged[0].reason)
    }

    @Test
    fun `mergeMutedLists keeps remote-only entries`() {
        // Remote muted someone on another device — sync pulls them in.
        val local = emptyList<MutedRider>()
        val remote = listOf(MutedRider(pubkey = "A", mutedAt = 1_000L))

        val merged = coordinator.mergeMutedLists(local, remote)

        assertEquals(1, merged.size)
        assertEquals("A", merged[0].pubkey)
    }
}
