package com.ridestr.common.nostr.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression coverage for the subscription-seeding gap surfaced by
 * `forceReconnectAll(newRelayUrls)`. Without this fix, a relay added
 * mid-session (e.g., a user editing custom relays then tapping Reconnect)
 * would connect successfully but receive zero events, because
 * `subscribe()` only fans out to connections that existed at call time
 * and `RelayConnection.resubscribeAll()` only sends subscriptions stored
 * in its own local map.
 */
@RunWith(RobolectricTestRunner::class)
class RelayManagerAddRelayTest {

    /** Use an unreachable URL pool; we never let any of these actually connect. */
    private val initialRelays = listOf(
        "wss://invalid.localhost.invalid:1/a",
        "wss://invalid.localhost.invalid:1/b"
    )

    private fun newSubscriptionFilter() = listOf(
        mapOf<String, Any>("kinds" to listOf(1), "limit" to 10)
    )

    @Test
    fun `addRelay seeds the new connection with every existing subscription`() {
        val manager = RelayManager(initialRelays)

        // Pre-existing subscriptions registered before the new relay is added.
        val subId1 = manager.subscribe(kinds = listOf(1)) { _, _ -> }
        val subId2 = manager.subscribe(kinds = listOf(30173), authors = listOf("abc")) { _, _ -> }

        // Both existing connections should have both subscriptions.
        val initialConnections = initialRelays.map { url ->
            // Use the manager's internal map via getRelayUrls + state — but the
            // simplest verification is to peek at the connection set we know.
            // We need access to RelayConnection instances; use the test accessor
            // approach via reflection-free path: call addRelay on a NEW url and
            // verify it receives the seeds.
            url
        }

        // Add a new relay mid-session.
        val newUrl = "wss://invalid.localhost.invalid:1/new"
        manager.addRelay(newUrl)

        // The manager should report the new relay alongside the originals.
        val urls = manager.getRelayUrls()
        assertTrue("New relay must be present in the manager", newUrl in urls)
        assertEquals(initialRelays.size + 1, urls.size)

        // Verify the new connection has both subscriptions seeded.
        val newConnection = manager.connectionForTest(newUrl)
            ?: error("New connection should exist after addRelay")
        val seededIds = newConnection.activeSubscriptionIdsForTest()
        assertTrue(
            "New connection must have subId1=$subId1 seeded so resubscribeAll() " +
                "actually sends it on socket open. Found: $seededIds",
            subId1 in seededIds
        )
        assertTrue(
            "New connection must have subId2=$subId2 seeded. Found: $seededIds",
            subId2 in seededIds
        )
    }

    @Test
    fun `addRelay with no existing subscriptions is a no-op for seeding`() {
        // Construction-time addRelay calls (in init) hit this path before any
        // subscribe() — must not error or do weird things.
        val manager = RelayManager(initialRelays)

        val url = "wss://invalid.localhost.invalid:1/c"
        manager.addRelay(url)

        val conn = manager.connectionForTest(url)
            ?: error("Connection should exist after addRelay")
        assertEquals(
            "With no existing subscriptions, seeded map must be empty",
            emptySet<String>(),
            conn.activeSubscriptionIdsForTest()
        )
    }

    @Test
    fun `forceReconnectAll with new relay url seeds subscriptions into the newcomer`() {
        val manager = RelayManager(initialRelays)
        val existingSubId = manager.subscribe(kinds = listOf(1)) { _, _ -> }

        // Simulate the user adding a relay in settings, then pressing Reconnect.
        val expandedRelays = initialRelays + "wss://invalid.localhost.invalid:1/added-via-ui"
        manager.forceReconnectAll(expandedRelays)

        val newConn = manager.connectionForTest(expandedRelays.last())
            ?: error("Newly-synced connection should exist")
        assertTrue(
            "forceReconnectAll's new relay must inherit the existing subscription " +
                "($existingSubId). Without this, the relay would open CONNECTED but " +
                "deliver no events.",
            existingSubId in newConn.activeSubscriptionIdsForTest()
        )
    }
}
