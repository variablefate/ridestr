package com.ridestr.common.nostr.relay

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/**
 * Regression coverage for the manual-reconnect bug
 * (variablefate/ridestr#86): "Reconnect to Relays" disconnected from all and never recovered.
 *
 * Verifies the two invariants that the broken `disconnectAll() + connectAll()` path
 * was violating:
 *   1. Force-reconnect resets the auto-retry backoff counter so a manual press
 *      isn't shadowed by long-pending scheduled retries.
 *   2. Force-reconnect bumps the connection generation so any in-flight
 *      callbacks/messages from the prior socket are recognized as stale.
 *
 * These tests use a real OkHttpClient pointed at an invalid URL so no
 * network I/O is needed — we only assert on in-memory state mutations.
 */
@RunWith(RobolectricTestRunner::class)
class RelayConnectionForceReconnectTest {

    /** Cheap client; we never actually open a working connection in this test. */
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(100, TimeUnit.MILLISECONDS)
        .readTimeout(100, TimeUnit.MILLISECONDS)
        .build()

    private fun newConnection(): RelayConnection = RelayConnection(
        url = "wss://invalid.localhost.invalid:1/",
        client = client,
        onEvent = { _, _, _ -> },
        onEose = { _, _ -> },
        onOk = { _, _, _, _ -> },
        onNotice = { _, _ -> }
    )

    @Test
    fun `forceReconnect resets reconnectAttempts to zero`() {
        val connection = newConnection()

        // Simulate prior auto-retry failures having ramped backoff up.
        connection.setReconnectAttemptsForTest(12)
        assertEquals(12, connection.reconnectAttemptsForTest())

        connection.forceReconnect()

        // Backoff must be reset so the user's manual press triggers an immediate retry
        // rather than waiting on the 60s scheduled-retry slot.
        assertEquals(
            "forceReconnect must reset auto-retry backoff so manual reconnects aren't " +
                "shadowed by long-pending scheduled retries",
            0,
            connection.reconnectAttemptsForTest()
        )
    }

    @Test
    fun `forceReconnect bumps connection generation`() {
        val connection = newConnection()
        val genBefore = connection.connectionGenerationForTest()

        connection.forceReconnect()

        val genAfter = connection.connectionGenerationForTest()
        assertTrue(
            "Generation must strictly increase so stale callbacks from prior socket " +
                "are filtered out (before=$genBefore, after=$genAfter)",
            genAfter > genBefore
        )
    }

    @Test
    fun `forceReconnect transitions state to CONNECTING`() {
        val connection = newConnection()

        // Initial state is DISCONNECTED.
        assertEquals(RelayConnectionState.DISCONNECTED, connection.state.value)

        connection.forceReconnect()

        assertEquals(
            "forceReconnect must immediately move state to CONNECTING — the prior " +
                "disconnectAll+connectAll path could leave it in DISCONNECTED if a " +
                "scheduled-retry coroutine raced with the user's press",
            RelayConnectionState.CONNECTING,
            connection.state.value
        )
    }

    @Test
    fun `forceReconnect after disconnect re-enables auto-reconnect`() {
        val connection = newConnection()

        // disconnect() sets shouldReconnect=false. Anything left over from a logout
        // path could otherwise sneak in and suppress future retries.
        connection.disconnect()
        assertEquals(RelayConnectionState.DISCONNECTED, connection.state.value)

        // Bump backoff to verify reset happens even from a fully-torn-down state.
        connection.setReconnectAttemptsForTest(7)

        connection.forceReconnect()

        assertEquals(0, connection.reconnectAttemptsForTest())
        assertEquals(RelayConnectionState.CONNECTING, connection.state.value)
    }
}
