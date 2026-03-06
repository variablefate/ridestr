package com.roadflare.common.util

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FareCalculatorTest {

    private lateinit var calculator: FareCalculator

    @Before
    fun setup() {
        calculator = FareCalculator()
    }

    @Test
    fun `calculateFareUsd applies rate per mile`() {
        val fare = calculator.calculateFareUsd(
            totalDistanceMiles = 10.0,
            ratePerMile = 2.50,
            minimumFareUsd = 8.0,
            baseFareUsd = 3.0
        )
        // base(3) + 10 * 2.50(25) = 28.0
        assertEquals(28.0, fare, 0.01)
    }

    @Test
    fun `calculateFareUsd enforces minimum fare`() {
        val fare = calculator.calculateFareUsd(
            totalDistanceMiles = 1.0,
            ratePerMile = 2.50,
            minimumFareUsd = 8.0,
            baseFareUsd = 0.0
        )
        // 1 * 2.50 = 2.50, but minimum is 8.0
        assertEquals(8.0, fare, 0.01)
    }

    @Test
    fun `calculateFareUsd with zero distance returns minimum`() {
        val fare = calculator.calculateFareUsd(
            totalDistanceMiles = 0.0,
            ratePerMile = 2.50,
            minimumFareUsd = 5.0
        )
        assertEquals(5.0, fare, 0.01)
    }

    @Test
    fun `calculateFareUsd with base fare adds correctly`() {
        val fare = calculator.calculateFareUsd(
            totalDistanceMiles = 5.0,
            ratePerMile = 2.0,
            minimumFareUsd = 5.0,
            baseFareUsd = 3.0
        )
        // base(3) + 5 * 2.0(10) = 13.0
        assertEquals(13.0, fare, 0.01)
    }

    @Test
    fun `isTooFar returns true when surcharge exceeds limit`() {
        assertTrue(
            calculator.isTooFar(
                roadflareFareUsd = 30.0,
                normalFareUsd = 10.0,
                maxSurchargeUsd = 15.0
            )
        )
    }

    @Test
    fun `isTooFar returns false when surcharge within limit`() {
        assertFalse(
            calculator.isTooFar(
                roadflareFareUsd = 25.0,
                normalFareUsd = 10.0,
                maxSurchargeUsd = 15.0
            )
        )
    }

    @Test
    fun `isTooFar returns false at exact boundary`() {
        assertFalse(
            calculator.isTooFar(
                roadflareFareUsd = 25.0,
                normalFareUsd = 10.0,
                maxSurchargeUsd = 15.0
            )
        )
    }

    @Test
    fun `isTooFar uses default max surcharge`() {
        // Default is ROADFLARE_MAX_SURCHARGE_USD = 15.0
        assertTrue(
            calculator.isTooFar(
                roadflareFareUsd = 30.01,
                normalFareUsd = 15.0
            )
        )
        assertFalse(
            calculator.isTooFar(
                roadflareFareUsd = 30.0,
                normalFareUsd = 15.0
            )
        )
    }

    @Test
    fun `KM_TO_MILES constant is correct`() {
        assertEquals(0.621371, FareCalculator.KM_TO_MILES, 0.0001)
    }
}
