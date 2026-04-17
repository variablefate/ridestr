package com.ridestr.common.nostr.events

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RideOfferEventFiatFareTest {

    @Test
    fun `decrypt parses authoritative fiat fare when both fields are present`() = runBlocking {
        val signer = mockk<NostrSigner>(relaxed = true)
        val decryptedJson = JSONObject().apply {
            put("fare_estimate", 25_000.0)
            put("fare_fiat_amount", "12.50")
            put("fare_fiat_currency", "USD")
            put("destination", JSONObject().apply {
                put("lat", 36.1)
                put("lon", -115.1)
            })
            put("approx_pickup", JSONObject().apply {
                put("lat", 36.0)
                put("lon", -115.0)
            })
        }.toString()
        coEvery { signer.nip44Decrypt("encrypted_content", "rider_pub") } returns decryptedJson

        val result = RideOfferEvent.decrypt(
            signer = signer,
            encryptedData = RideOfferDataEncrypted(
                eventId = "offer1",
                riderPubKey = "rider_pub",
                driverEventId = "availability1",
                driverPubKey = "driver_pub",
                encryptedContent = "encrypted_content",
                createdAt = 123L
            )
        )

        assertNotNull(result)
        assertEquals("12.50", result!!.fiatFare?.amount)
        assertEquals("USD", result.fiatFare?.currency)
    }

    @Test
    fun `decrypt drops incomplete fiat fare payloads`() = runBlocking {
        val signer = mockk<NostrSigner>(relaxed = true)
        val decryptedJson = JSONObject().apply {
            put("fare_estimate", 25_000.0)
            put("fare_fiat_amount", "12.50")
            put("destination", JSONObject().apply {
                put("lat", 36.1)
                put("lon", -115.1)
            })
            put("approx_pickup", JSONObject().apply {
                put("lat", 36.0)
                put("lon", -115.0)
            })
        }.toString()
        coEvery { signer.nip44Decrypt("encrypted_content", "rider_pub") } returns decryptedJson

        val result = RideOfferEvent.decrypt(
            signer = signer,
            encryptedData = RideOfferDataEncrypted(
                eventId = "offer1",
                riderPubKey = "rider_pub",
                driverEventId = "availability1",
                driverPubKey = "driver_pub",
                encryptedContent = "encrypted_content",
                createdAt = 123L
            )
        )

        assertNotNull(result)
        assertNull(result!!.fiatFare)
    }

    @Test
    fun `parseBroadcast parses authoritative fiat fare when both fields are present`() {
        val event = mockk<Event>(relaxed = true)
        every { event.kind } returns RideshareEventKinds.RIDE_OFFER
        every { event.id } returns "broadcast1"
        every { event.pubKey } returns "rider_pub"
        every { event.createdAt } returns 123L
        every { event.tags } returns arrayOf(
            arrayOf(RideshareTags.GEOHASH, "9qqj7"),
            arrayOf(RideshareTags.HASHTAG, RideOfferEvent.RIDE_REQUEST_TAG)
        )
        every { event.content } returns JSONObject().apply {
            put("pickup_area", JSONObject().apply {
                put("lat", 36.0)
                put("lon", -115.0)
            })
            put("destination_area", JSONObject().apply {
                put("lat", 36.1)
                put("lon", -115.1)
            })
            put("fare_estimate", 25_000.0)
            put("fare_fiat_amount", "12.50")
            put("fare_fiat_currency", "USD")
            put("route_distance_km", 8.5)
            put("route_duration_min", 12.0)
        }.toString()

        val result = RideOfferEvent.parseBroadcast(event)

        assertNotNull(result)
        assertEquals("12.50", result!!.fiatFare?.amount)
        assertEquals("USD", result.fiatFare?.currency)
    }

    @Test
    fun `parseBroadcast drops incomplete fiat fare payloads`() {
        val event = mockk<Event>(relaxed = true)
        every { event.kind } returns RideshareEventKinds.RIDE_OFFER
        every { event.id } returns "broadcast1"
        every { event.pubKey } returns "rider_pub"
        every { event.createdAt } returns 123L
        every { event.tags } returns arrayOf(
            arrayOf(RideshareTags.GEOHASH, "9qqj7"),
            arrayOf(RideshareTags.HASHTAG, RideOfferEvent.RIDE_REQUEST_TAG)
        )
        every { event.content } returns JSONObject().apply {
            put("pickup_area", JSONObject().apply {
                put("lat", 36.0)
                put("lon", -115.0)
            })
            put("destination_area", JSONObject().apply {
                put("lat", 36.1)
                put("lon", -115.1)
            })
            put("fare_estimate", 25_000.0)
            put("fare_fiat_amount", "12.50")
            put("route_distance_km", 8.5)
            put("route_duration_min", 12.0)
        }.toString()

        val result = RideOfferEvent.parseBroadcast(event)

        assertNotNull(result)
        assertNull(result!!.fiatFare)
    }
}
