package com.drivestr.app.service

import com.ridestr.common.nostr.events.FiatFare
import org.junit.Assert.assertEquals
import org.junit.Test

class RoadflareNotificationFormattingTest {

    @Test
    fun `notification content prefers authoritative fiat fare over sats conversion`() {
        val result = formatRoadflareNotificationContent(
            displayName = "Alice",
            fareSats = 30_000.0,
            fiatFare = FiatFare(amount = "12.50", currency = "USD")
        )

        assertEquals("Alice has broadcasted a RoadFlare for $12.50!", result)
    }

    @Test
    fun `notification content falls back to sats conversion for legacy offers`() {
        val result = formatRoadflareNotificationContent(
            displayName = "Alice",
            fareSats = 25_000.0,
            fiatFare = null
        )

        assertEquals("Alice has broadcasted a RoadFlare for $12.50!", result)
    }

    @Test
    fun `notification content omits fare when none is available`() {
        val result = formatRoadflareNotificationContent(
            displayName = "Alice",
            fareSats = null,
            fiatFare = null
        )

        assertEquals("Alice has broadcasted a RoadFlare!", result)
    }
}
