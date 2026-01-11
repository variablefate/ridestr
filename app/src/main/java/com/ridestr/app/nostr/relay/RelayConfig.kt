package com.ridestr.app.nostr.relay

/**
 * Configuration for Nostr relay connections.
 */
object RelayConfig {
    /**
     * Default relays for rideshare events.
     * These are popular, reliable public relays.
     */
    val DEFAULT_RELAYS = listOf(
        "wss://relay.damus.io",
        "wss://nos.lol",
        "wss://relay.nostr.band"
    )

    /**
     * Connection timeout in milliseconds.
     */
    const val CONNECT_TIMEOUT_MS = 10_000L

    /**
     * Read timeout in milliseconds.
     */
    const val READ_TIMEOUT_MS = 30_000L

    /**
     * Write timeout in milliseconds.
     */
    const val WRITE_TIMEOUT_MS = 30_000L

    /**
     * Time to wait before attempting reconnection after failure.
     */
    const val RECONNECT_DELAY_MS = 5_000L
}
