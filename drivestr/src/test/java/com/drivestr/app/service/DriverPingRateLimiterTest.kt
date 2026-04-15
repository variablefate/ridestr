package com.drivestr.app.service

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DriverPingRateLimiterTest {

    private var fakeNow = 1_000_000L  // arbitrary stable start time
    private lateinit var limiter: DriverPingRateLimiter

    @Before
    fun setUp() {
        fakeNow = 1_000_000L
        limiter = DriverPingRateLimiter(nowMs = { fakeNow })
    }

    // ── Per-rider 30 s dedup ─────────────────────────────────────────────────

    @Test
    fun `tryAccept allows first ping from rider`() {
        assertTrue(limiter.tryAccept("rider_A"))
    }

    @Test
    fun `tryAccept drops duplicate within 30 seconds`() {
        assertTrue(limiter.tryAccept("rider_A"))
        fakeNow += 29_000L  // 29 s later — still within window
        assertFalse(limiter.tryAccept("rider_A"))
    }

    @Test
    fun `tryAccept allows ping after 30 seconds have elapsed`() {
        assertTrue(limiter.tryAccept("rider_A"))
        fakeNow += 30_001L  // just past the 30 s window
        assertTrue(limiter.tryAccept("rider_A"))
    }

    @Test
    fun `tryAccept per-rider dedup does not affect different riders`() {
        assertTrue(limiter.tryAccept("rider_A"))
        assertTrue(limiter.tryAccept("rider_B"))  // different pubkey → independent window
    }

    // ── Global 2-per-10-min cap ──────────────────────────────────────────────

    @Test
    fun `tryAccept enforces global cap of 2 per 10 minutes`() {
        // 1st ping (rider_A)
        assertTrue(limiter.tryAccept("rider_A"))
        fakeNow += 31_000L  // >30 s so rider dedup clears between calls below

        // 2nd ping (rider_B) — still within 10-min global window
        assertTrue(limiter.tryAccept("rider_B"))
        fakeNow += 31_000L

        // 3rd ping (rider_C) — global cap exceeded
        assertFalse(limiter.tryAccept("rider_C"))
    }

    @Test
    fun `tryAccept allows again after 10-minute global window rolls past first entry`() {
        assertTrue(limiter.tryAccept("rider_A"))   // recorded at 1_000_000
        fakeNow += 31_000L
        assertTrue(limiter.tryAccept("rider_B"))   // recorded at 1_031_000 — now at cap
        // Advance so the 1_000_000 entry falls outside the 10-min window:
        // windowStart = fakeNow - 600_000 must exceed 1_000_000 → fakeNow > 1_600_000
        fakeNow = 1_600_001L
        assertTrue(limiter.tryAccept("rider_C"))   // first entry evicted; count=1 < 2 → allowed
    }

    // ── reset() ─────────────────────────────────────────────────────────────────

    @Test
    fun `reset clears all state allowing immediate acceptance`() {
        // Fill dedup for rider_A and reach global cap within the same 10-min window.
        // fakeNow starts at 1_000_000 (from setUp).
        assertTrue(limiter.tryAccept("rider_A"))   // slot 1; rider_A dedup window starts
        fakeNow += 5_000L                           // only 5 s — rider_A is still within 30 s dedup
        assertTrue(limiter.tryAccept("rider_B"))   // slot 2; global cap (2) reached

        // Confirm both limits are blocking before reset
        assertFalse(limiter.tryAccept("rider_A"))  // per-rider dedup active: 5 s < 30 s
        assertFalse(limiter.tryAccept("rider_C"))  // global cap active

        limiter.reset()

        // Per-rider dedup cleared → rider_A accepted immediately (5 s has NOT elapsed naturally)
        assertTrue(limiter.tryAccept("rider_A"))
        // Global cap cleared → rider_C also accepted
        assertTrue(limiter.tryAccept("rider_C"))
    }
}
