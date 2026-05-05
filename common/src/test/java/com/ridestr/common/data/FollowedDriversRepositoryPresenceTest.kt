package com.ridestr.common.data

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
