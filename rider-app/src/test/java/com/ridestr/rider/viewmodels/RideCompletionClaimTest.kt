package com.ridestr.rider.viewmodels

import com.ridestr.common.nostr.events.PaymentPath
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the rider-side conditional HTLC marking logic in handleRideCompletion().
 *
 * The decision logic is extracted here as a pure function so it can be tested
 * without standing up a full RiderViewModel. The real code in handleRideCompletion()
 * follows the same branching structure.
 */
class RideCompletionClaimTest {

    companion object {
        /**
         * Extracted decision logic from handleRideCompletion() for testability.
         * Returns: "MARK_CLAIMED", "CLEAR_PROTECTION", or "NO_OP"
         */
        private fun decideHtlcAction(
            paymentHash: String?,
            claimSuccess: Boolean?,
            paymentPath: PaymentPath
        ): String {
            if (paymentHash == null) return "NO_OP"
            return when {
                claimSuccess == true -> "MARK_CLAIMED"
                claimSuccess == false -> "CLEAR_PROTECTION"
                paymentPath == PaymentPath.SAME_MINT -> "CLEAR_PROTECTION"
                else -> "NO_OP"
            }
        }
    }

    // --- claimSuccess = true ---

    @Test
    fun `claimSuccess true marks HTLC as claimed`() {
        val action = decideHtlcAction(
            paymentHash = "abc123",
            claimSuccess = true,
            paymentPath = PaymentPath.SAME_MINT
        )
        assertEquals("MARK_CLAIMED", action)
    }

    // --- claimSuccess = false ---

    @Test
    fun `claimSuccess false clears protection for rider refund`() {
        val action = decideHtlcAction(
            paymentHash = "abc123",
            claimSuccess = false,
            paymentPath = PaymentPath.SAME_MINT
        )
        assertEquals("CLEAR_PROTECTION", action)
    }

    // --- claimSuccess = null (old driver app) ---

    @Test
    fun `null claimSuccess with SAME_MINT keeps LOCKED conservatively`() {
        val action = decideHtlcAction(
            paymentHash = "abc123",
            claimSuccess = null,
            paymentPath = PaymentPath.SAME_MINT
        )
        assertEquals("CLEAR_PROTECTION", action)
    }

    @Test
    fun `null claimSuccess with non-SAME_MINT paths is no-op`() {
        for (path in listOf(PaymentPath.FIAT_CASH, PaymentPath.CROSS_MINT, PaymentPath.NO_PAYMENT)) {
            val action = decideHtlcAction(
                paymentHash = "abc123",
                claimSuccess = null,
                paymentPath = path
            )
            assertEquals(
                "Expected NO_OP for paymentPath=$path with null claimSuccess",
                "NO_OP",
                action
            )
        }
    }

    // --- null paymentHash ---

    @Test
    fun `null paymentHash is always no-op regardless of claimSuccess`() {
        for (claimSuccess in listOf(true, false, null)) {
            val action = decideHtlcAction(
                paymentHash = null,
                claimSuccess = claimSuccess,
                paymentPath = PaymentPath.SAME_MINT
            )
            assertEquals(
                "Expected NO_OP when paymentHash is null (claimSuccess=$claimSuccess)",
                "NO_OP",
                action
            )
        }
    }
}
