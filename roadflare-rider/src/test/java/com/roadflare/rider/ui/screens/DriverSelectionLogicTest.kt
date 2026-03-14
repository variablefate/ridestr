package com.roadflare.rider.ui.screens

import com.ridestr.common.bitcoin.BitcoinPriceService
import com.ridestr.common.data.CachedDriverLocation
import com.ridestr.common.nostr.events.RoadflareLocationEvent
import com.ridestr.common.roadflare.FareState
import com.ridestr.common.roadflare.RoadflareDriverQuote
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class DriverSelectionLogicTest {

    private val priceService = mockk<BitcoinPriceService>()

    // --- Group A: computeEventFareSats ---

    @Test
    fun `null price returns null`() {
        every { priceService.usdToSats(any()) } returns null

        val result = computeEventFareSats(fareSats = null, fareUsd = 5.0, priceService)

        assertNull(result)
    }

    @Test
    fun `with price returns correct sats`() {
        every { priceService.usdToSats(5.0) } returns 7142L

        val result = computeEventFareSats(fareSats = null, fareUsd = 5.0, priceService)

        assertEquals(7142L, result)
    }

    @Test
    fun `cached fareSats preferred over price service`() {
        every { priceService.usdToSats(any()) } returns 9999L

        val result = computeEventFareSats(fareSats = 7000L, fareUsd = 5.0, priceService)

        assertEquals(7000L, result)
    }

    // --- Group B: deriveDriverUiModel sendability gating ---

    private val nowSec = System.currentTimeMillis() / 1000
    private val staleThreshold = 5L * 60

    private fun onlineCached() = CachedDriverLocation(
        lat = 40.0, lon = -74.0,
        status = RoadflareLocationEvent.Status.ONLINE,
        timestamp = nowSec - 60 // fresh (1 min ago)
    )

    private fun readyQuote() = RoadflareDriverQuote(
        fareUsd = 5.0,
        fareSats = null,
        pickupMiles = 1.5,
        rideMiles = 3.0,
        totalMiles = 4.5,
        normalRideFareUsd = 5.0,
        isTooFar = false,
        fareState = FareState.EXACT
    )

    @Test
    fun `price unavailable disables direct select and broadcast`() {
        val model = deriveDriverUiModel(
            pubkey = "abc123",
            displayName = "Test Driver",
            hasKey = true,
            cached = onlineCached(),
            quote = readyQuote(),
            isSending = false,
            now = nowSec,
            staleThresholdSec = staleThreshold,
            priceAvailable = false,
            formattedFare = "$5.00"
        )

        assertFalse(model.isDirectSelectable)
        assertFalse(model.isBroadcastEligible)
    }

    @Test
    fun `price available enables direct select and broadcast`() {
        val model = deriveDriverUiModel(
            pubkey = "abc123",
            displayName = "Test Driver",
            hasKey = true,
            cached = onlineCached(),
            quote = readyQuote(),
            isSending = false,
            now = nowSec,
            staleThresholdSec = staleThreshold,
            priceAvailable = true,
            formattedFare = "$5.00"
        )

        assertTrue(model.isDirectSelectable)
        assertTrue(model.isBroadcastEligible)
    }

    @Test
    fun `price available but fare calculating disables selection`() {
        val model = deriveDriverUiModel(
            pubkey = "abc123",
            displayName = "Test Driver",
            hasKey = true,
            cached = onlineCached(),
            quote = readyQuote().copy(fareState = FareState.CALCULATING),
            isSending = false,
            now = nowSec,
            staleThresholdSec = staleThreshold,
            priceAvailable = true,
            formattedFare = null
        )

        assertFalse(model.isDirectSelectable)
        assertFalse(model.isBroadcastEligible)
    }
}
