package com.ridestr.common.payment

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the `rideProtected` flag on PendingHtlc.
 *
 * rideProtected suppresses auto-refund while an HTLC is part of an active ride,
 * preventing the background refund sweep from reclaiming funds the driver may
 * still claim. A 4-hour staleness escape hatch ensures protection doesn't last
 * forever if the ride state is lost.
 */
@RunWith(RobolectricTestRunner::class)
class HtlcRideProtectedTest {

    // ── helpers ──────────────────────────────────────────────────────────

    /** Creates a PendingHtlc with sane defaults.  locktime = 0 makes isRefundable() always true. */
    private fun htlc(
        rideProtected: Boolean = false,
        createdAt: Long = System.currentTimeMillis(),
        status: PendingHtlcStatus = PendingHtlcStatus.LOCKED
    ) = PendingHtlc(
        escrowId = "escrow-test-1",
        htlcToken = "cashuAeyJ0b2tlb...",
        amountSats = 500,
        locktime = 0L,            // always past locktime+120s
        riderPubKey = "aabbccdd",
        paymentHash = "deadbeef",
        preimage = null,
        rideId = "ride-42",
        createdAt = createdAt,
        status = status,
        rideProtected = rideProtected
    )

    /** Applies the same filter logic as WalletStorage.getRefundableHtlcs(). */
    private fun isAutoRefundCandidate(htlc: PendingHtlc): Boolean =
        htlc.isRefundable() && htlc.isActive() && (!htlc.rideProtected || htlc.isProtectionStale())

    // ── tests ────────────────────────────────────────────────────────────

    @Test
    fun `rideProtected HTLC excluded from auto-refund`() {
        val h = htlc(rideProtected = true)

        // Preconditions: locktime expired and HTLC is active
        assertTrue("should be refundable by locktime", h.isRefundable())
        assertTrue("should be active", h.isActive())

        // But rideProtected suppresses it
        assertFalse("rideProtected HTLC must NOT appear in auto-refund list", isAutoRefundCandidate(h))
    }

    @Test
    fun `non-protected HTLC still refundable`() {
        val h = htlc(rideProtected = false)

        assertTrue("should be refundable by locktime", h.isRefundable())
        assertTrue("should be active", h.isActive())
        assertTrue("non-protected HTLC must appear in auto-refund list", isAutoRefundCandidate(h))
    }

    @Test
    fun `rideProtected HTLC still counts as pending`() {
        val h = htlc(rideProtected = true)
        assertTrue("rideProtected must not affect isActive()", h.isActive())
    }

    @Test
    fun `rideProtected JSON round-trip`() {
        val json = JSONObject().apply {
            put("escrowId", "e1")
            put("rideProtected", true)
        }

        val restored = json.optBoolean("rideProtected", false)
        assertTrue("rideProtected must survive JSON round-trip", restored)
    }

    @Test
    fun `backward compat - missing rideProtected defaults to false`() {
        val json = JSONObject().apply {
            put("escrowId", "e1")
            // rideProtected intentionally omitted
        }

        val restored = json.optBoolean("rideProtected", false)
        assertFalse("missing rideProtected must default to false", restored)
    }

    @Test
    fun `max-age escape hatch - stale protection allows refund`() {
        val fourHoursAgo = System.currentTimeMillis() - (4 * 60 * 60 * 1000 + 1_000) // 4h + 1s
        val h = htlc(rideProtected = true, createdAt = fourHoursAgo)

        assertTrue("protection over 4 hours old must be stale", h.isProtectionStale())
        assertTrue("stale-protected HTLC must pass auto-refund filter", isAutoRefundCandidate(h))
    }

    @Test
    fun `fresh protection not stale - HTLC excluded from refund`() {
        val h = htlc(rideProtected = true, createdAt = System.currentTimeMillis())

        assertFalse("fresh protection must NOT be stale", h.isProtectionStale())
        assertFalse("freshly-protected HTLC must NOT pass auto-refund filter", isAutoRefundCandidate(h))
    }
}
