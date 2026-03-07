package com.ridestr.common

import com.ridestr.common.nostr.events.*
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Wiring-level tests — verifies the actual tag structure, JSON field names,
 * and null coalescing behavior that drivestr's RoadflareListenerService
 * depends on for filtering and parsing RoadFlare events.
 *
 * These catch real bugs even when all apps share the same :common code,
 * because they lock down the contract that event creation methods produce.
 */
@RunWith(RobolectricTestRunner::class)
class RoadflareWiringTest {

    // ==================
    // RoadFlare tag structure
    // ==================

    @Test
    fun `RideOfferEvent isRoadflare check uses t-tag with roadflare value`() {
        assertEquals("t", RideshareTags.HASHTAG)
        assertEquals("roadflare", RideOfferEvent.ROADFLARE_TAG)
    }

    @Test
    fun `RideOfferEvent also uses rideshare t-tag`() {
        assertEquals("ride-request", RideOfferEvent.RIDE_REQUEST_TAG)
        assertEquals("rideshare", RideshareTags.RIDESHARE_TAG)
    }

    @Test
    fun `RideOfferData isRoadflare defaults to false`() {
        val data = RideOfferData(
            eventId = "abc",
            riderPubKey = "def",
            driverEventId = "evt1",
            driverPubKey = "ghi",
            approxPickup = Location(36.0, -115.0),
            destination = Location(36.1, -115.1),
            fareEstimate = 10.0,
            createdAt = 0
        )
        assertFalse(data.isRoadflare)
    }

    @Test
    fun `RideOfferData can be created with isRoadflare true`() {
        val data = RideOfferData(
            eventId = "abc",
            riderPubKey = "def",
            driverEventId = "evt1",
            driverPubKey = "ghi",
            approxPickup = Location(36.0, -115.0),
            destination = Location(36.1, -115.1),
            fareEstimate = 10.0,
            createdAt = 0,
            isRoadflare = true
        )
        assertTrue(data.isRoadflare)
    }

    // ==================
    // fiatPaymentMethods JSON contract
    // ==================

    @Test
    fun `fiatPaymentMethods survives JSON roundtrip as array`() {
        val methods = listOf("venmo", "cash_app", "zelle")
        val json = JSONObject()
        json.put("fiat_payment_methods", JSONArray(methods))

        val parsed = json.getJSONArray("fiat_payment_methods")
        val result = (0 until parsed.length()).map { parsed.getString(it) }
        assertEquals(methods, result)
    }

    @Test
    fun `empty fiatPaymentMethods produces empty JSON array`() {
        val methods = emptyList<String>()
        val json = JSONObject()
        json.put("fiat_payment_methods", JSONArray(methods))

        val parsed = json.getJSONArray("fiat_payment_methods")
        assertEquals(0, parsed.length())
    }

    @Test
    fun `RideOfferData fiatPaymentMethods defaults to empty`() {
        val data = RideOfferData(
            eventId = "abc",
            riderPubKey = "def",
            driverEventId = "evt1",
            driverPubKey = "ghi",
            approxPickup = Location(36.0, -115.0),
            destination = Location(36.1, -115.1),
            fareEstimate = 10.0,
            createdAt = 0
        )
        assertEquals(emptyList<String>(), data.fiatPaymentMethods)
    }

    @Test
    fun `RideOfferData preserves fiatPaymentMethods order`() {
        val methods = listOf("zelle", "venmo", "cash_app")
        val data = RideOfferData(
            eventId = "abc",
            riderPubKey = "def",
            driverEventId = "evt1",
            driverPubKey = "ghi",
            approxPickup = Location(36.0, -115.0),
            destination = Location(36.1, -115.1),
            fareEstimate = 10.0,
            createdAt = 0,
            fiatPaymentMethods = methods
        )
        assertEquals(methods, data.fiatPaymentMethods)
    }

    // ==================
    // paymentMethod null coalescing
    // ==================

    @Test
    fun `RideOfferData defaults paymentMethod to cashu`() {
        val data = RideOfferData(
            eventId = "abc",
            riderPubKey = "def",
            driverEventId = "evt1",
            driverPubKey = "ghi",
            approxPickup = Location(36.0, -115.0),
            destination = Location(36.1, -115.1),
            fareEstimate = 10.0,
            createdAt = 0
        )
        assertEquals("cashu", data.paymentMethod)
    }

    @Test
    fun `RideOfferData preserves explicit paymentMethod`() {
        val data = RideOfferData(
            eventId = "abc",
            riderPubKey = "def",
            driverEventId = "evt1",
            driverPubKey = "ghi",
            approxPickup = Location(36.0, -115.0),
            destination = Location(36.1, -115.1),
            fareEstimate = 10.0,
            createdAt = 0,
            paymentMethod = "fiat_cash"
        )
        assertEquals("fiat_cash", data.paymentMethod)
    }

    // ==================
    // BroadcastRideOfferData RoadFlare flag
    // ==================

    @Test
    fun `BroadcastRideOfferData isRoadflare defaults to false`() {
        val data = BroadcastRideOfferData(
            eventId = "abc",
            riderPubKey = "def",
            pickupArea = Location(36.0, -115.0),
            destinationArea = Location(36.1, -115.1),
            fareEstimate = 10.0,
            routeDistanceKm = 12.0,
            routeDurationMin = 15.0,
            geohashes = emptyList(),
            createdAt = 0
        )
        assertFalse(data.isRoadflare)
    }

    // ==================
    // RideAcceptanceData preserves fields
    // ==================

    @Test
    fun `RideAcceptanceData preserves paymentMethod field`() {
        val data = RideAcceptanceData(
            eventId = "accept1",
            driverPubKey = "driver1",
            offerEventId = "offer1",
            riderPubKey = "rider1",
            status = "accepted",
            createdAt = 0,
            paymentMethod = "fiat_cash"
        )
        assertEquals("fiat_cash", data.paymentMethod)
        assertEquals("offer1", data.offerEventId)
    }

    @Test
    fun `RideAcceptanceData paymentMethod defaults to null`() {
        val data = RideAcceptanceData(
            eventId = "accept1",
            driverPubKey = "driver1",
            offerEventId = "offer1",
            riderPubKey = "rider1",
            status = "accepted",
            createdAt = 0
        )
        assertNull(data.paymentMethod)
    }

    // ==================
    // Event creation tag verification (MockK-based)
    // Verifies createBroadcast() produces the actual tags that
    // drivestr's RoadflareListenerService subscribes to.
    // ==================

    private fun createMockSigner(): Pair<NostrSigner, CapturingSlot<Array<Array<String>>>> {
        val tagsSlot = slot<Array<Array<String>>>()
        val mockSigner = mockk<NostrSigner>(relaxed = true)
        val mockEvent = mockk<Event>(relaxed = true)

        coEvery {
            mockSigner.sign<Event>(
                createdAt = any(),
                kind = any(),
                tags = capture(tagsSlot),
                content = any()
            )
        } returns mockEvent

        return Pair(mockSigner, tagsSlot)
    }

    @Test
    fun `createBroadcast with isRoadflare=true produces roadflare t-tag`() = runBlocking {
        val (mockSigner, tagsSlot) = createMockSigner()

        RideOfferEvent.createBroadcast(
            signer = mockSigner,
            pickup = Location(36.0, -115.0),
            destination = Location(36.1, -115.1),
            fareEstimate = 10.0,
            routeDistanceKm = 12.0,
            routeDurationMin = 15.0,
            isRoadflare = true
        )

        val tags = tagsSlot.captured
        val tTags = tags.filter { it[0] == "t" }.map { it[1] }
        assertTrue("Expected 'roadflare' in t-tags: $tTags", tTags.contains("roadflare"))
        assertTrue("Expected 'rideshare' in t-tags: $tTags", tTags.contains("rideshare"))
        assertTrue("Expected 'ride-request' in t-tags: $tTags", tTags.contains("ride-request"))
    }

    @Test
    fun `createBroadcast with isRoadflare=false has no roadflare t-tag`() = runBlocking {
        val (mockSigner, tagsSlot) = createMockSigner()

        RideOfferEvent.createBroadcast(
            signer = mockSigner,
            pickup = Location(36.0, -115.0),
            destination = Location(36.1, -115.1),
            fareEstimate = 10.0,
            routeDistanceKm = 12.0,
            routeDurationMin = 15.0,
            isRoadflare = false
        )

        val tags = tagsSlot.captured
        val tTags = tags.filter { it[0] == "t" }.map { it[1] }
        assertFalse("Unexpected 'roadflare' in t-tags", tTags.contains("roadflare"))
        // rideshare and ride-request should still be present
        assertTrue("Expected 'rideshare' in t-tags: $tTags", tTags.contains("rideshare"))
        assertTrue("Expected 'ride-request' in t-tags: $tTags", tTags.contains("ride-request"))
    }

    @Test
    fun `createBroadcast includes geohash tags for pickup location`() = runBlocking {
        val (mockSigner, tagsSlot) = createMockSigner()

        RideOfferEvent.createBroadcast(
            signer = mockSigner,
            pickup = Location(36.0, -115.0),
            destination = Location(36.1, -115.1),
            fareEstimate = 10.0,
            routeDistanceKm = 12.0,
            routeDurationMin = 15.0
        )

        val tags = tagsSlot.captured
        val gTags = tags.filter { it[0] == "g" }
        // Should have geohash tags at precision 3, 4, and 5
        assertTrue("Expected geohash tags, got none", gTags.isNotEmpty())
        assertTrue("Expected at least 3 geohash tags (precision 3-5)", gTags.size >= 3)
    }
}
