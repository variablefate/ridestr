package com.ridestr.common.payment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
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
 *
 * Tests use real WalletStorage (via Robolectric) to verify the production
 * getRefundableHtlcs() filter, not a duplicated predicate.
 */
@RunWith(RobolectricTestRunner::class)
class HtlcRideProtectedTest {

    private lateinit var storage: WalletStorage

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        storage = WalletStorage(context)
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** Creates a PendingHtlc with sane defaults.  locktime = 0 makes isRefundable() always true. */
    private fun htlc(
        escrowId: String = "escrow-test-1",
        rideProtected: Boolean = false,
        createdAt: Long = System.currentTimeMillis(),
        status: PendingHtlcStatus = PendingHtlcStatus.LOCKED
    ) = PendingHtlc(
        escrowId = escrowId,
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

    // ── PendingHtlc model tests (no storage needed) ─────────────────────

    @Test
    fun `rideProtected HTLC still counts as pending`() {
        val h = htlc(rideProtected = true)
        assertTrue("rideProtected must not affect isActive()", h.isActive())
    }

    @Test
    fun `max-age escape hatch - isProtectionStale after 4 hours`() {
        val fourHoursAgo = System.currentTimeMillis() - (4 * 60 * 60 * 1000 + 1_000) // 4h + 1s
        val h = htlc(rideProtected = true, createdAt = fourHoursAgo)
        assertTrue("protection over 4 hours old must be stale", h.isProtectionStale())
    }

    @Test
    fun `fresh protection not stale`() {
        val h = htlc(rideProtected = true, createdAt = System.currentTimeMillis())
        assertFalse("fresh protection must NOT be stale", h.isProtectionStale())
    }

    // ── WalletStorage integration tests ─────────────────────────────────

    @Test
    fun `rideProtected HTLC excluded from getRefundableHtlcs`() {
        storage.savePendingHtlc(htlc(rideProtected = true))

        val refundable = storage.getRefundableHtlcs()
        assertTrue("rideProtected HTLC must NOT appear in refundable list", refundable.isEmpty())
    }

    @Test
    fun `non-protected HTLC included in getRefundableHtlcs`() {
        storage.savePendingHtlc(htlc(rideProtected = false))

        val refundable = storage.getRefundableHtlcs()
        assertEquals("non-protected HTLC must appear in refundable list", 1, refundable.size)
    }

    @Test
    fun `stale rideProtected HTLC included in getRefundableHtlcs`() {
        val fourHoursAgo = System.currentTimeMillis() - (4 * 60 * 60 * 1000 + 1_000)
        storage.savePendingHtlc(htlc(rideProtected = true, createdAt = fourHoursAgo))

        val refundable = storage.getRefundableHtlcs()
        assertEquals("stale-protected HTLC must pass auto-refund filter", 1, refundable.size)
    }

    @Test
    fun `rideProtected JSON round-trip through WalletStorage`() {
        storage.savePendingHtlc(htlc(rideProtected = true))

        val restored = storage.getPendingHtlcs()
        assertEquals(1, restored.size)
        assertTrue("rideProtected must survive storage round-trip", restored[0].rideProtected)
    }

    @Test
    fun `backward compat - missing rideProtected defaults to false`() {
        // Verify the data class default
        val h = PendingHtlc(
            escrowId = "e1",
            htlcToken = "tok",
            amountSats = 100,
            locktime = 0L,
            riderPubKey = "pk",
            paymentHash = "hash"
        )
        assertFalse("default rideProtected must be false", h.rideProtected)
    }

    @Test
    fun `clearing rideProtected makes HTLC refundable`() {
        // Save a protected HTLC
        storage.savePendingHtlc(htlc(escrowId = "clear-test", rideProtected = true))
        assertTrue("should be excluded while protected", storage.getRefundableHtlcs().isEmpty())

        // Clear protection (simulates clearHtlcRideProtected path)
        storage.updatePendingHtlc("clear-test") { it.copy(rideProtected = false) }

        val refundable = storage.getRefundableHtlcs()
        assertEquals("HTLC must be refundable after clearing protection", 1, refundable.size)
        assertEquals("clear-test", refundable[0].escrowId)
    }
}
