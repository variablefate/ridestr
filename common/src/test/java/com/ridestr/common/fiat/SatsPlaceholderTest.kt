package com.ridestr.common.fiat

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins the SATS-mode placeholder contract (Issue #72): when no BTC price is
 * available and a caller would otherwise silently fall back to a `$X.XX`
 * string that looks identical to USD mode, formatters must render the
 * [SATS_PLACEHOLDER] instead so the currency-toggle UX stays consistent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SatsPlaceholderTest {

    @Test
    fun `null sats yields placeholder not a dollar string`() {
        val formatted = formatSatsOrPlaceholder(null)
        assertEquals(SATS_PLACEHOLDER, formatted)
        assert(!formatted.contains("$")) { "placeholder must not look like USD: '$formatted'" }
    }

    @Test
    fun `non-null sats renders with thousands separator and sats suffix`() {
        assertEquals("12,345 sats", formatSatsOrPlaceholder(12_345L))
    }

    @Test
    fun `zero sats still renders the labeled sats string`() {
        assertEquals("0 sats", formatSatsOrPlaceholder(0L))
    }
}
