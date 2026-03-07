package com.roadflare.common.nostr.relay

object RelayConfig {
    val DEFAULT_RELAYS = listOf("wss://relay.damus.io", "wss://relay.primal.net", "wss://nos.lol")
    const val CONNECT_TIMEOUT_MS = 10_000L
    const val READ_TIMEOUT_MS = 30_000L
    const val WRITE_TIMEOUT_MS = 30_000L
    const val RECONNECT_DELAY_MS = 5_000L
}
