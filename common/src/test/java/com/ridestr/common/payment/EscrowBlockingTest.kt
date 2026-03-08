package com.ridestr.common.payment

import com.ridestr.common.nostr.events.PaymentPath
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for PaymentPath determination — the core logic deciding when escrow blocking fires.
 * PaymentPath.determine() is called in both autoConfirmRide() (rider) and setupAcceptedRide() (driver).
 * SAME_MINT result triggers escrow blocking on rider side and hides "Complete Anyway" on driver side.
 */
class EscrowBlockingTest {

    @Test
    fun `SAME_MINT when both mint URLs match`() {
        val result = PaymentPath.determine("https://mint.example.com", "https://mint.example.com", "cashu")
        assertEquals(PaymentPath.SAME_MINT, result)
    }

    @Test
    fun `SAME_MINT is case-insensitive`() {
        val result = PaymentPath.determine("https://Mint.Example.COM", "https://mint.example.com", "cashu")
        assertEquals(PaymentPath.SAME_MINT, result)
    }

    @Test
    fun `SAME_MINT ignores trailing slash`() {
        val result = PaymentPath.determine("https://mint.example.com/", "https://mint.example.com", "cashu")
        assertEquals(PaymentPath.SAME_MINT, result)
    }

    @Test
    fun `CROSS_MINT when mint URLs differ`() {
        val result = PaymentPath.determine("https://mint-a.com", "https://mint-b.com", "cashu")
        assertEquals(PaymentPath.CROSS_MINT, result)
    }

    @Test
    fun `FIAT_CASH for fiat payment method`() {
        val result = PaymentPath.determine(null, null, "fiat_cash")
        assertEquals(PaymentPath.FIAT_CASH, result)
    }

    @Test
    fun `FIAT_CASH ignores mint URLs`() {
        val result = PaymentPath.determine("https://mint.com", "https://mint.com", "fiat_cash")
        assertEquals(PaymentPath.FIAT_CASH, result)
    }

    @Test
    fun `NO_PAYMENT when rider has no mint URL`() {
        val result = PaymentPath.determine(null, "https://mint.example.com", "cashu")
        assertEquals(PaymentPath.NO_PAYMENT, result)
    }

    @Test
    fun `NO_PAYMENT when driver has no mint URL`() {
        val result = PaymentPath.determine("https://mint.example.com", null, "cashu")
        assertEquals(PaymentPath.NO_PAYMENT, result)
    }

    @Test
    fun `CROSS_MINT for lightning payment method`() {
        val result = PaymentPath.determine(null, null, "lightning")
        assertEquals(PaymentPath.CROSS_MINT, result)
    }

    @Test
    fun `FIAT_CASH paths do not require escrow`() {
        // Escrow is only required for SAME_MINT
        assertNotEquals(PaymentPath.SAME_MINT, PaymentPath.FIAT_CASH)
    }

    @Test
    fun `NO_PAYMENT for unknown payment method`() {
        val result = PaymentPath.determine("https://mint.com", "https://mint.com", "unknown")
        assertEquals(PaymentPath.NO_PAYMENT, result)
    }
}
