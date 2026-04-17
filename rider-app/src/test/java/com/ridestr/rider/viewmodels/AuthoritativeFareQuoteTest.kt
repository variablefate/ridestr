package com.ridestr.rider.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AuthoritativeFareQuoteTest {

    @Test
    fun `fallback keeps authoritative usd amount`() {
        val quote = authoritativeFareQuote(
            fareUsd = 12.345,
            sats = null,
            fallbackSats = 5000.0
        )

        assertEquals(5000.0, quote.sats, 0.001)
        assertEquals("12.35", quote.usdAmount)
    }

    @Test
    fun `authoritative usd amount uses protocol stable decimal formatting`() {
        val originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.FRANCE)
        try {
            val quote = authoritativeFareQuote(
                fareUsd = 12.5,
                sats = 1234L,
                fallbackSats = 5000.0
            )

            assertEquals("12.50", quote.usdAmount)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
