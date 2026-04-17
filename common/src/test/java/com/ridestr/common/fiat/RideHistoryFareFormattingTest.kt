package com.ridestr.common.fiat

import com.ridestr.common.nostr.events.FiatFare
import com.ridestr.common.nostr.events.RideHistoryEntry
import com.ridestr.common.settings.DisplayCurrency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RideHistoryFareFormattingTest {

    @Test
    fun `ride history entry round-trips authoritative fiat fare`() {
        val entry = historyEntry(
            rideId = "ride-fiat-round-trip",
            fareSats = 25_000,
            fiatFare = FiatFare("12.50", "USD")
        )

        val restored = RideHistoryEntry.fromJson(entry.toJson())

        assertNotNull(restored)
        assertEquals("12.50", restored!!.fiatFare?.amount)
        assertEquals("USD", restored.fiatFare?.currency)
        assertEquals(25_000L, restored.fareSats)
    }

    @Test
    fun `ride history entry drops incomplete authoritative fiat payloads`() {
        val json = historyEntry(rideId = "ride-incomplete").toJson().apply {
            put("fare_fiat_amount", "12.50")
        }

        val restored = RideHistoryEntry.fromJson(json)

        assertNotNull(restored)
        assertNull(restored!!.fiatFare)
    }

    @Test
    fun `formatFareDisplay prefers authoritative fiat in usd mode`() {
        val ride = historyEntry(
            fareSats = 25_000,
            fiatFare = FiatFare("12.50", "USD")
        )

        val formatted = ride.formatFareDisplay(
            displayCurrency = DisplayCurrency.USD,
            btcPriceUsd = 90_000,
            prefix = "+"
        )

        assertEquals("+\$12.50", formatted)
    }

    @Test
    fun `sumFareUsdOrNull combines authoritative fiat with sats conversion`() {
        val rides = listOf(
            historyEntry(rideId = "ride-fiat", fareSats = 25_000, fiatFare = FiatFare("12.50", "USD")),
            historyEntry(rideId = "ride-sats", fareSats = 5_000, fiatFare = null)
        )

        val totalUsd = rides.sumFareUsdOrNull(btcPriceUsd = 90_000)

        assertEquals(17.0, totalUsd!!, 0.001)
    }

    @Test
    fun `sumFareUsdOrNull returns null when sats ride has no usd conversion`() {
        val rides = listOf(
            historyEntry(rideId = "ride-fiat", fareSats = 25_000, fiatFare = FiatFare("12.50", "USD")),
            historyEntry(rideId = "ride-sats", fareSats = 5_000, fiatFare = null)
        )

        assertNull(rides.sumFareUsdOrNull(btcPriceUsd = null))
    }

    private fun historyEntry(
        rideId: String = "ride-1",
        fareSats: Long = 10_000,
        fiatFare: FiatFare? = null
    ) = RideHistoryEntry(
        rideId = rideId,
        timestamp = 1_710_000_000,
        role = "driver",
        counterpartyPubKey = "npub1counterparty",
        pickupGeohash = "9q8yy7",
        dropoffGeohash = "9q8yyk",
        distanceMiles = 4.2,
        durationMinutes = 15,
        fareSats = fareSats,
        fiatFare = fiatFare,
        status = "completed"
    )
}
