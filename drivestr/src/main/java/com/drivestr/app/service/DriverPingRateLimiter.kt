package com.drivestr.app.service

import java.util.concurrent.ConcurrentHashMap

/**
 * Rate limiter for incoming Kind 3189 driver ping notifications.
 *
 * Enforces two independent limits (per protocol spec §1.5):
 * - Per-rider 30-second notification spam throttle (prevents a single rider from generating multiple notifications in rapid succession)
 * - Global cap of 2 notifications per 10-minute rolling window
 *
 * Both limits are applied after HMAC auth validation — [tryAccept] is only called
 * for events that already passed [RoadflareDriverPingEvent.parseAndDecrypt].
 *
 * Thread-safe: [tryAccept] is @Synchronized.
 * [nowMs] is injectable for deterministic unit testing.
 *
 * State is intentionally in-memory only. Process restart clears all limits; this is
 * acceptable because the global cap is 2 notifications per 10 minutes and a driver
 * restarting mid-window is effectively starting a new session.
 */
class DriverPingRateLimiter(
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    companion object {
        const val RIDER_DEDUP_WINDOW_MS     = 30_000L    // 30 seconds
        const val GLOBAL_WINDOW_MS          = 600_000L   // 10 minutes
        const val GLOBAL_MAX_NOTIFICATIONS  = 2
    }

    // Per-rider: maps riderPubKey → timestamp (ms) of last accepted ping
    private val riderLastAccepted = ConcurrentHashMap<String, Long>()

    // Global rolling window: timestamps (ms) of accepted notifications, oldest first
    // Guarded by `this` via @Synchronized on tryAccept
    private val globalTimestamps = ArrayDeque<Long>()

    /**
     * Try to accept a ping from [riderPubKey].
     *
     * Records a notification slot and returns true if the ping should be delivered.
     * Returns false if it should be silently dropped (dedup or cap).
     *
     * IMPORTANT: call this ONLY after all suppression checks (presence gate, mute) have
     * passed. Rate-limit slots must not be consumed by events that will be suppressed anyway.
     */
    @Synchronized
    fun tryAccept(riderPubKey: String): Boolean {
        val now = nowMs()

        // 1. Per-rider 30 s dedup
        val lastSeen = riderLastAccepted[riderPubKey]
        if (lastSeen != null && (now - lastSeen) < RIDER_DEDUP_WINDOW_MS) {
            return false
        }

        // 2. Global rolling window: evict entries older than GLOBAL_WINDOW_MS
        val windowStart = now - GLOBAL_WINDOW_MS
        while (globalTimestamps.isNotEmpty() && globalTimestamps.first() < windowStart) {
            globalTimestamps.removeFirst()
        }
        if (globalTimestamps.size >= GLOBAL_MAX_NOTIFICATIONS) {
            return false
        }

        // Accept — record timestamps
        riderLastAccepted[riderPubKey] = now
        globalTimestamps.addLast(now)
        return true
    }

    /**
     * Reset all rate-limit state.
     *
     * Call from [RoadflareListenerService.stopListening] so that stale slots from a previous
     * service run do not bleed into the next one (e.g. service killed and restarted mid-ride).
     */
    @Synchronized
    fun reset() {
        riderLastAccepted.clear()
        globalTimestamps.clear()
    }
}
