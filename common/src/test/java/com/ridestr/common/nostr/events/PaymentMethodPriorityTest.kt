package com.ridestr.common.nostr.events

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for Issue #46: Payment method priority ordering and matching.
 *
 * Covers:
 * 1. findBestCommonFiatMethod() returns first rider method in driver list
 * 2. findBestCommonFiatMethod() returns null when no common methods
 * 3. findBestCommonFiatMethod() respects rider order, not driver order
 * 4. findBestCommonFiatMethod() handles empty lists
 * 5. Missing fiat_payment_methods field → empty list (backward compat)
 */
class PaymentMethodPriorityTest {

    @Test
    fun `findBestCommonFiatMethod returns first rider method that exists in driver list`() {
        val riderMethods = listOf("zelle", "venmo", "paypal")
        val driverMethods = listOf("paypal", "zelle")

        val result = PaymentMethod.findBestCommonFiatMethod(riderMethods, driverMethods)

        assertEquals("zelle", result)
    }

    @Test
    fun `findBestCommonFiatMethod returns null when no common methods`() {
        val riderMethods = listOf("zelle", "venmo")
        val driverMethods = listOf("paypal", "cash_app")

        val result = PaymentMethod.findBestCommonFiatMethod(riderMethods, driverMethods)

        assertNull(result)
    }

    @Test
    fun `findBestCommonFiatMethod respects rider order not driver order`() {
        // Rider prefers venmo > paypal, driver prefers paypal > venmo
        val riderMethods = listOf("venmo", "paypal")
        val driverMethods = listOf("paypal", "venmo")

        val result = PaymentMethod.findBestCommonFiatMethod(riderMethods, driverMethods)

        // Should return venmo (rider's top priority) not paypal (driver's top priority)
        assertEquals("venmo", result)
    }

    @Test
    fun `findBestCommonFiatMethod handles empty rider list`() {
        val result = PaymentMethod.findBestCommonFiatMethod(emptyList(), listOf("zelle"))
        assertNull(result)
    }

    @Test
    fun `findBestCommonFiatMethod handles empty driver list`() {
        val result = PaymentMethod.findBestCommonFiatMethod(listOf("zelle"), emptyList())
        assertNull(result)
    }

    @Test
    fun `findBestCommonFiatMethod handles both empty`() {
        val result = PaymentMethod.findBestCommonFiatMethod(emptyList(), emptyList())
        assertNull(result)
    }

    @Test
    fun `findBestCommonFiatMethod handles unknown method strings`() {
        val riderMethods = listOf("apple_pay", "zelle")
        val driverMethods = listOf("zelle", "apple_pay")

        // Unknown method "apple_pay" should still match
        val result = PaymentMethod.findBestCommonFiatMethod(riderMethods, driverMethods)
        assertEquals("apple_pay", result)
    }

    @Test
    fun `RideOfferData defaults fiatPaymentMethods to empty list`() {
        val offer = RideOfferData(
            eventId = "test",
            riderPubKey = "rider",
            driverEventId = "driver-event",
            driverPubKey = "driver",
            approxPickup = Location(0.0, 0.0),
            destination = Location(1.0, 1.0),
            fareEstimate = 1000.0,
            createdAt = System.currentTimeMillis() / 1000
        )

        assertTrue(offer.fiatPaymentMethods.isEmpty())
    }

    @Test
    fun `DriverAvailabilityData defaults fiatPaymentMethods to empty list`() {
        val data = DriverAvailabilityData(
            eventId = "test",
            driverPubKey = "driver",
            approxLocation = Location(0.0, 0.0),
            createdAt = System.currentTimeMillis() / 1000
        )

        assertTrue(data.fiatPaymentMethods.isEmpty())
    }

    @Test
    fun `distinct preserves order and removes duplicates`() {
        val input = listOf("zelle", "venmo", "zelle", "paypal", "venmo")
        val result = input.distinct()

        assertEquals(listOf("zelle", "venmo", "paypal"), result)
    }

    @Test
    fun `findBestCommonFiatMethod matches case-insensitively`() {
        val riderMethods = listOf("Zelle", "PayPal")
        val driverMethods = listOf("zelle", "venmo")

        val result = PaymentMethod.findBestCommonFiatMethod(riderMethods, driverMethods)

        assertEquals("Zelle", result) // Returns rider's original casing
    }

    @Test
    fun `findBestCommonFiatMethod trims whitespace`() {
        val riderMethods = listOf(" zelle ", "paypal")
        val driverMethods = listOf("zelle", "venmo")

        val result = PaymentMethod.findBestCommonFiatMethod(riderMethods, driverMethods)

        assertEquals(" zelle ", result) // Returns rider's original string, matched via trim
    }
}
