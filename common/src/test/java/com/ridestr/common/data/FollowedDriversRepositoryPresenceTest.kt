package com.ridestr.common.data

import androidx.test.core.app.ApplicationProvider
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
 * Tests for issue #82's lightweight presence channel — the public-tag-only path on
 * Kind 30014 that lets the rider know a driver is available even when their stored
 * RoadFlare key can't decrypt the encrypted lat/lon content.
 *
 * Robolectric provides Android Context for SharedPreferences. Presence itself is
 * in-memory only (matches `CachedDriverLocation`'s ephemeral semantic) but the repo
 * needs Context to construct.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class FollowedDriversRepositoryPresenceTest {

    private lateinit var repo: FollowedDriversRepository

    @Before
    fun setUp() {
        repo = FollowedDriversRepository(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `driverPresence starts empty`() {
        assertTrue(repo.driverPresence.value.isEmpty())
    }

    @Test
    fun `updateDriverPresence stores entry keyed by pubkey`() {
        repo.updateDriverPresence(pubkey = "rider-A", status = "online", timestamp = 1_000L, keyVersion = 5)

        val entry = repo.driverPresence.value["rider-A"]
        assertEquals("online", entry?.status)
        assertEquals(1_000L, entry?.timestamp)
        assertEquals(5, entry?.keyVersion)
    }

    @Test
    fun `updateDriverPresence overwrites with newer timestamp`() {
        repo.updateDriverPresence("rider-A", "online", 1_000L, 5)
        repo.updateDriverPresence("rider-A", "on_ride", 2_000L, 6)

        val entry = repo.driverPresence.value["rider-A"]
        assertEquals("on_ride", entry?.status)
        assertEquals(2_000L, entry?.timestamp)
        assertEquals(6, entry?.keyVersion)
    }

    @Test
    fun `updateDriverPresence skips out-of-order updates`() {
        // Issue #82: protocol guarantee: Kind 30014 events have monotonic createdAt per driver
        // when delivered in order, but relay reordering can deliver an older event after a newer
        // one. The repository must reject the older one to keep presence consistent.
        repo.updateDriverPresence("rider-A", "on_ride", 2_000L, 6)
        repo.updateDriverPresence("rider-A", "online", 1_500L, 5) // older — must be skipped

        val entry = repo.driverPresence.value["rider-A"]
        assertEquals("on_ride", entry?.status)
        assertEquals(2_000L, entry?.timestamp)
        assertEquals(6, entry?.keyVersion)
    }

    @Test
    fun `updateDriverPresence skips equal-timestamp duplicate`() {
        // Same event arriving from two relays must not overwrite the first.
        repo.updateDriverPresence("rider-A", "online", 2_000L, 5)
        repo.updateDriverPresence("rider-A", "on_ride", 2_000L, 5)

        // The first wins because the second is not strictly newer.
        assertEquals("online", repo.driverPresence.value["rider-A"]?.status)
    }

    @Test
    fun `removeDriverPresence drops entry`() {
        repo.updateDriverPresence("rider-A", "online", 1_000L, 5)
        repo.removeDriverPresence("rider-A")
        assertNull(repo.driverPresence.value["rider-A"])
    }

    @Test
    fun `clearDriverPresence wipes all entries`() {
        repo.updateDriverPresence("rider-A", "online", 1_000L)
        repo.updateDriverPresence("rider-B", "on_ride", 1_500L)
        repo.clearDriverPresence()
        assertTrue(repo.driverPresence.value.isEmpty())
    }

    @Test
    fun `driverPresence and driverLocations are independent state flows`() {
        repo.updateDriverPresence("rider-A", "online", 1_000L, 5)
        repo.updateDriverLocation("rider-B", lat = 36.0, lon = -115.0, status = "online", timestamp = 1_000L)

        assertTrue("rider-A appears in presence", repo.driverPresence.value.containsKey("rider-A"))
        assertFalse("rider-A NOT in locations", repo.driverLocations.value.containsKey("rider-A"))
        assertTrue("rider-B appears in locations", repo.driverLocations.value.containsKey("rider-B"))
        assertFalse("rider-B NOT in presence (writes are independent)", repo.driverPresence.value.containsKey("rider-B"))
    }

    @Test
    fun `clearAll wipes the presence channel along with locations`() {
        // Issue #82 pass-1 fix: presence has the same in-memory-only semantic as locations
        // and MUST be cleared on logout so the next user's session doesn't inherit stale
        // data from the previous identity. The test pins this so a future refactor that
        // adds another in-memory state can't silently regress the cleanup.
        repo.updateDriverPresence("driver-A", "online", 1_000L, 5)
        repo.updateDriverPresence("driver-B", "on_ride", 1_500L, 6)
        repo.updateDriverLocation("driver-C", lat = 36.0, lon = -115.0, status = "online", timestamp = 1_000L)

        repo.clearAll()

        assertTrue("presence map must be empty after clearAll", repo.driverPresence.value.isEmpty())
        assertTrue("locations map must be empty after clearAll", repo.driverLocations.value.isEmpty())
    }

    @Test
    fun `removeDriver clears the presence entry for that pubkey`() {
        // Issue #82 pass-3 fix: removeDriver was clearing names + locations but not presence.
        // The leak meant a re-add of the same driver in a single session could see the
        // `(timestamp >= timestamp)` guard hit on the same Kind 30014 event (still within
        // its 5-min relay TTL), rendering the re-added driver as offline until the next
        // broadcast tick. Pin the cleanup symmetric with locations.
        // (Note: this repo doesn't expose addDriver as a unit-test seam — `_drivers` is only
        // mutated via the wider Kind 30011 sync path. The presence clear is unconditional on
        // pubkey match, so test it directly without going through the drivers list.)
        repo.updateDriverPresence("driver-A", "online", 1_000L, 5)
        repo.updateDriverPresence("driver-B", "online", 1_000L, 5)
        repo.updateDriverLocation("driver-A", lat = 36.0, lon = -115.0, status = "online", timestamp = 1_000L)

        repo.removeDriver("driver-A")

        assertNull("driver-A presence must be cleared", repo.driverPresence.value["driver-A"])
        assertNull("driver-A location must be cleared", repo.driverLocations.value["driver-A"])
        assertNotNull("driver-B presence must NOT be touched", repo.driverPresence.value["driver-B"])
    }

    @Test
    fun `updateDriverPresence converges to highest timestamp under concurrent updates`() {
        // Issue #82 pass-3 fix: updateDriverPresence used direct `_driverPresence.value =`
        // assignment with a non-atomic read-then-check-then-write guard. Two relay threads
        // calling updateDriverPresence for the same pubkey with different timestamps could
        // both pass the `existing.timestamp >= timestamp` guard (both reading the same
        // `existing` before either writes), and the lower-timestamp write could win the
        // StateFlow assignment. The fix uses StateFlow.update {} for CAS-retried
        // read-modify-write.
        //
        // Sanity check: launch many concurrent updates with strictly-ascending timestamps
        // (in shuffled order to avoid happens-before ordering by accident) and confirm the
        // highest timestamp wins. Uses plain JVM threads to avoid coroutine-scope plumbing
        // in a Robolectric test.
        val concurrency = 64
        val timestamps = (1..concurrency).toList().shuffled()
        val threads = timestamps.map { ts ->
            Thread { repo.updateDriverPresence("driver-X", "online", ts.toLong(), 0) }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        val finalEntry = repo.driverPresence.value["driver-X"]
        assertNotNull("driver-X presence must be set", finalEntry)
        assertEquals(
            "highest timestamp must win after concurrent updates",
            concurrency.toLong(),
            finalEntry?.timestamp
        )
    }
}
