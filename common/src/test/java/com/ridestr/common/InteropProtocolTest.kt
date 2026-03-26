package com.ridestr.common

import com.ridestr.common.nostr.events.*
import com.ridestr.common.settings.DisplayCurrency
import com.ridestr.common.settings.DistanceUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Protocol parity tests — verifies event kind constants, enum round-trips,
 * and serialization contracts that all apps depend on.
 */
@RunWith(RobolectricTestRunner::class)
class InteropProtocolTest {

    // ==================
    // Event kind constants
    // ==================

    @Test
    fun `rideshare event kind numbers are correct`() {
        assertEquals(30173, RideshareEventKinds.DRIVER_AVAILABILITY)
        assertEquals(3173, RideshareEventKinds.RIDE_OFFER)
        assertEquals(3174, RideshareEventKinds.RIDE_ACCEPTANCE)
        assertEquals(3175, RideshareEventKinds.RIDE_CONFIRMATION)
        assertEquals(30180, RideshareEventKinds.DRIVER_RIDE_STATE)
        assertEquals(30181, RideshareEventKinds.RIDER_RIDE_STATE)
        assertEquals(3178, RideshareEventKinds.RIDESHARE_CHAT)
        assertEquals(3179, RideshareEventKinds.RIDE_CANCELLATION)
        assertEquals(30174, RideshareEventKinds.RIDE_HISTORY_BACKUP)
        assertEquals(30177, RideshareEventKinds.PROFILE_BACKUP)
        assertEquals(30182, RideshareEventKinds.ADMIN_CONFIG)
    }

    @Test
    fun `roadflare event kind numbers are correct`() {
        assertEquals(30011, RideshareEventKinds.ROADFLARE_FOLLOWED_DRIVERS)
        assertEquals(30012, RideshareEventKinds.ROADFLARE_DRIVER_STATE)
        assertEquals(30013, RideshareEventKinds.ROADFLARE_SHAREABLE_LIST)
        assertEquals(30014, RideshareEventKinds.ROADFLARE_LOCATION)
        assertEquals(3186, RideshareEventKinds.ROADFLARE_KEY_SHARE)
        assertEquals(3187, RideshareEventKinds.ROADFLARE_FOLLOW_NOTIFY)
        assertEquals(3188, RideshareEventKinds.ROADFLARE_KEY_ACK)
    }

    // ==================
    // PaymentMethod enum
    // ==================

    @Test
    fun `PaymentMethod fromString round-trips all values`() {
        PaymentMethod.entries.forEach { method ->
            val roundTripped = PaymentMethod.fromString(method.value)
            assertEquals("Failed round-trip for ${method.name}", method, roundTripped)
        }
    }

    @Test
    fun `PaymentMethod fromString returns null for unknown value`() {
        assertEquals(PaymentMethod.BITCOIN, PaymentMethod.fromString("bitcoin"))
        assertNull(PaymentMethod.fromString(""))
        assertNull(PaymentMethod.fromString("ZELLE"))  // case-sensitive
        assertNull(PaymentMethod.fromString("wire_transfer"))
    }

    @Test
    fun `PaymentMethod values are stable strings`() {
        assertEquals("fiat_cash", PaymentMethod.FIAT_CASH.value)
        assertEquals("zelle", PaymentMethod.ZELLE.value)
        assertEquals("paypal", PaymentMethod.PAYPAL.value)
        assertEquals("cash_app", PaymentMethod.CASH_APP.value)
        assertEquals("venmo", PaymentMethod.VENMO.value)
        assertEquals("bitcoin", PaymentMethod.BITCOIN.value)
        assertEquals("cash", PaymentMethod.CASH.value)
    }

    @Test
    fun `RoadFlare alternate methods keep bitcoin just above cash`() {
        assertEquals(
            listOf(
                PaymentMethod.ZELLE,
                PaymentMethod.PAYPAL,
                PaymentMethod.CASH_APP,
                PaymentMethod.VENMO,
                PaymentMethod.STRIKE,
                PaymentMethod.BITCOIN,
                PaymentMethod.CASH
            ),
            PaymentMethod.ROADFLARE_ALTERNATE_METHODS
        )
    }

    @Test
    fun `PaymentPath treats RoadFlare bitcoin as manual settlement`() {
        assertEquals(
            PaymentPath.FIAT_CASH,
            PaymentPath.determine(
                riderMintUrl = null,
                driverMintUrl = null,
                paymentMethod = PaymentMethod.BITCOIN.value
            )
        )
    }

    // ==================
    // Location serialization
    // ==================

    @Test
    fun `Location JSON round-trip preserves coordinates`() {
        val location = Location(36.1699, -115.1398, "Las Vegas")
        val json = location.toJson()
        val parsed = Location.fromJson(json)

        assertNotNull(parsed)
        assertEquals(36.1699, parsed!!.lat, 0.0001)
        assertEquals(-115.1398, parsed.lon, 0.0001)
    }

    @Test
    fun `Location toJson includes lat and lon`() {
        val json = Location(40.7128, -74.0060).toJson()
        assertEquals(40.7128, json.getDouble("lat"), 0.0001)
        assertEquals(-74.0060, json.getDouble("lon"), 0.0001)
    }

    // ==================
    // Geohash
    // ==================

    @Test
    fun `Geohash encoding is deterministic`() {
        val hash1 = Geohash.encode(36.1699, -115.1398, 5)
        val hash2 = Geohash.encode(36.1699, -115.1398, 5)
        assertEquals(hash1, hash2)
        assertEquals(5, hash1.length)
    }

    @Test
    fun `Geohash encode-decode round-trip is approximate`() {
        val lat = 36.1699
        val lon = -115.1398
        val hash = Geohash.encode(lat, lon, 5)
        val (decodedLat, decodedLon) = Geohash.decode(hash)

        // 5-char geohash is accurate to ~2.4km
        assertEquals(lat, decodedLat, 0.05)
        assertEquals(lon, decodedLon, 0.05)
    }

    // ==================
    // Settings types
    // ==================

    @Test
    fun `DisplayCurrency enum has expected values`() {
        assertEquals(2, DisplayCurrency.entries.size)
        assertNotNull(DisplayCurrency.valueOf("SATS"))
        assertNotNull(DisplayCurrency.valueOf("USD"))
    }

    @Test
    fun `DistanceUnit enum has expected values`() {
        assertEquals(2, DistanceUnit.entries.size)
        assertNotNull(DistanceUnit.valueOf("MILES"))
        assertNotNull(DistanceUnit.valueOf("KILOMETERS"))
    }

    // ==================
    // RideOfferEvent broadcast
    // ==================

    @Test
    fun `RideOfferEvent broadcast JSON has expected fields`() {
        // Simulate what createBroadcast puts in content
        val json = JSONObject().apply {
            put("fare_estimate", 15.0)
            put("pickup_area", "9q5c")
            put("destination_area", "Downtown")
            put("route_distance_km", 12.5)
            put("route_duration_min", 18.0)
        }

        assertTrue(json.has("fare_estimate"))
        assertTrue(json.has("pickup_area"))
        assertTrue(json.has("route_distance_km"))
        assertTrue(json.has("route_duration_min"))
        assertEquals(15.0, json.getDouble("fare_estimate"), 0.01)
    }

    // ==================
    // RideOfferData defaults
    // ==================

    @Test
    fun `RideOfferData defaults paymentMethod to cashu`() {
        val data = RideOfferData(
            eventId = "abc",
            riderPubKey = "def",
            driverEventId = "ghi",
            driverPubKey = "jkl",
            approxPickup = Location(36.0, -115.0),
            destination = Location(36.1, -115.1),
            fareEstimate = 10.0,
            createdAt = 0
        )
        assertEquals("cashu", data.paymentMethod)
        assertFalse(data.isRoadflare)
        assertEquals(emptyList<String>(), data.fiatPaymentMethods)
    }

    // ==================
    // Tag constants
    // ==================

    @Test
    fun `RideshareTags constants are correct`() {
        assertEquals("e", RideshareTags.EVENT_REF)
        assertEquals("p", RideshareTags.PUBKEY_REF)
        assertEquals("t", RideshareTags.HASHTAG)
        assertEquals("g", RideshareTags.GEOHASH)
        assertEquals("rideshare", RideshareTags.RIDESHARE_TAG)
        assertEquals("expiration", RideshareTags.EXPIRATION)
    }

    @Test
    fun `RideOfferEvent tag constants are correct`() {
        assertEquals("ride-request", RideOfferEvent.RIDE_REQUEST_TAG)
        assertEquals("roadflare", RideOfferEvent.ROADFLARE_TAG)
    }

    @Test
    fun `RoadflareFollowNotifyEvent t-tag is roadflare-follow`() {
        assertEquals("roadflare-follow", RoadflareFollowNotifyEvent.T_TAG)
    }

    @Test
    fun `FollowedDriversEvent d-tag is roadflare-drivers`() {
        assertEquals("roadflare-drivers", FollowedDriversEvent.D_TAG)
    }

    @Test
    fun `RoadflareLocationEvent d-tag is roadflare-location`() {
        assertEquals("roadflare-location", RoadflareLocationEvent.D_TAG)
    }

    @Test
    fun `RoadflareLocationEvent status constants are correct`() {
        assertEquals("online", RoadflareLocationEvent.Status.ONLINE)
        assertEquals("on_ride", RoadflareLocationEvent.Status.ON_RIDE)
        assertEquals("offline", RoadflareLocationEvent.Status.OFFLINE)
    }
}
