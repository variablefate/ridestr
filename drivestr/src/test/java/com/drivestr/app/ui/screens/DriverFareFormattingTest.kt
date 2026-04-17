package com.drivestr.app.ui.screens

import com.ridestr.common.settings.DisplayCurrency
import org.junit.Assert.assertEquals
import org.junit.Test

class DriverFareFormattingTest {

    @Test
    fun `formatEarnings uses authoritative fiat amount when present`() {
        val result = formatEarnings(
            satsPerUnit = 50_000.0,
            displayCurrency = DisplayCurrency.USD,
            btcPriceUsd = 90_000,
            suffix = "/hr",
            fiatAmountPerUnitUsd = 12.5
        )

        assertEquals("$12.50/hr", result)
    }

    @Test
    fun `formatEarnings falls back to sats conversion when fiat amount is unavailable`() {
        val result = formatEarnings(
            satsPerUnit = 25_000.0,
            displayCurrency = DisplayCurrency.USD,
            btcPriceUsd = 50_000,
            suffix = "/hr"
        )

        assertEquals("$12.50/hr", result)
    }

    @Test
    fun `formatEarnings preserves sats display when sats currency selected`() {
        val result = formatEarnings(
            satsPerUnit = 25_000.9,
            displayCurrency = DisplayCurrency.SATS,
            btcPriceUsd = 50_000,
            suffix = "/hr",
            fiatAmountPerUnitUsd = 99.0
        )

        assertEquals("25000 sats/hr", result)
    }
}
