package com.roadflare.common.nostr.events

import org.junit.Assert.*
import org.junit.Test

class EventSerializationTest {

    @Test
    fun `RideshareEventKinds constants are correct`() {
        assertEquals(3173, RideshareEventKinds.RIDE_OFFER)
        assertEquals(3174, RideshareEventKinds.RIDE_ACCEPTANCE)
        assertEquals(3175, RideshareEventKinds.RIDE_CONFIRMATION)
        assertEquals(30180, RideshareEventKinds.DRIVER_RIDE_STATE)
        assertEquals(30181, RideshareEventKinds.RIDER_RIDE_STATE)
        assertEquals(3178, RideshareEventKinds.RIDESHARE_CHAT)
        assertEquals(3179, RideshareEventKinds.RIDE_CANCELLATION)
        assertEquals(30174, RideshareEventKinds.RIDE_HISTORY_BACKUP)
        assertEquals(30177, RideshareEventKinds.PROFILE_BACKUP)
        assertEquals(30011, RideshareEventKinds.ROADFLARE_FOLLOWED_DRIVERS)
        assertEquals(30012, RideshareEventKinds.ROADFLARE_DRIVER_STATE)
        assertEquals(30014, RideshareEventKinds.ROADFLARE_LOCATION)
        assertEquals(3186, RideshareEventKinds.ROADFLARE_KEY_SHARE)
        assertEquals(3188, RideshareEventKinds.ROADFLARE_KEY_ACK)
    }

    @Test
    fun `PaymentMethod values are correct`() {
        assertEquals("fiat_cash", PaymentMethod.FIAT_CASH.value)
        assertEquals("zelle", PaymentMethod.ZELLE.value)
        assertEquals("paypal", PaymentMethod.PAYPAL.value)
        assertEquals("cash_app", PaymentMethod.CASH_APP.value)
        assertEquals("venmo", PaymentMethod.VENMO.value)
        assertEquals("cash", PaymentMethod.CASH.value)
    }

    @Test
    fun `PaymentMethod fromString round-trips correctly`() {
        PaymentMethod.entries.forEach { method ->
            assertEquals(method, PaymentMethod.fromString(method.value))
        }
    }

    @Test
    fun `PaymentMethod fromString returns null for unknown`() {
        assertNull(PaymentMethod.fromString("bitcoin"))
        assertNull(PaymentMethod.fromString("cashu"))
        assertNull(PaymentMethod.fromString(""))
    }

    @Test
    fun `Location distanceToKm calculates haversine distance`() {
        // New York to Los Angeles ~3944 km
        val nyc = Location(40.7128, -74.0060)
        val la = Location(34.0522, -118.2437)
        val distance = nyc.distanceToKm(la)
        assertTrue("NYC to LA should be ~3944 km, got $distance", distance in 3900.0..4000.0)
    }

    @Test
    fun `Location distanceToKm for same point is zero`() {
        val loc = Location(37.7749, -122.4194)
        assertEquals(0.0, loc.distanceToKm(loc), 0.001)
    }

    @Test
    fun `Location distanceToKm for close points`() {
        // ~1 km apart
        val a = Location(37.7749, -122.4194)
        val b = Location(37.7839, -122.4194) // ~1km north
        val distance = a.distanceToKm(b)
        assertTrue("Points ~1km apart should be ~1km, got $distance", distance in 0.9..1.1)
    }

    @Test
    fun `Geohash encode and decode round-trip`() {
        val lat = 37.7749
        val lon = -122.4194
        val hash = Geohash.encode(lat, lon, 7)
        assertNotNull(hash)
        assertTrue("Geohash should be 7 characters", hash.length == 7)

        val decoded = Geohash.decode(hash)
        assertNotNull(decoded)
        assertEquals(lat, decoded!!.first, 0.01)
        assertEquals(lon, decoded.second, 0.01)
    }

    @Test
    fun `Geohash encode produces valid geohash`() {
        val hash = Geohash.encode(37.7749, -122.4194, 5)
        assertNotNull(hash)
        assertEquals(5, hash.length)
        // Geohash chars are base32
        assertTrue(hash.all { it in "0123456789bcdefghjkmnpqrstuvwxyz" })
    }

    @Test
    fun `UserProfile data class construction works correctly`() {
        val profile = UserProfile(
            pubKey = "pubkey123",
            name = "Alice",
            displayName = "Alice D",
            about = "Test user",
            picture = "https://example.com/pic.jpg"
        )

        assertEquals("Alice", profile.name)
        assertEquals("Alice D", profile.displayName)
        assertEquals("Test user", profile.about)
        assertEquals("https://example.com/pic.jpg", profile.picture)
        assertEquals("pubkey123", profile.pubKey)
    }

    @Test
    fun `UserProfile resolveDisplayName falls back correctly`() {
        assertEquals("Alice", UserProfile(displayName = "Alice").resolveDisplayName())
        assertEquals("Bob", UserProfile(name = "Bob").resolveDisplayName())
        assertEquals("Anonymous", UserProfile().resolveDisplayName())
        assertEquals("Display", UserProfile(name = "Name", displayName = "Display").resolveDisplayName())
    }

    @Test
    fun `UserProfile getFirstName returns first word`() {
        assertEquals("Alice", UserProfile(displayName = "Alice Wonderland").getFirstName())
        assertEquals("Bob", UserProfile(name = "Bob Smith").getFirstName())
    }

    @Test
    fun `DriverStatusType constants exist`() {
        assertNotNull(DriverStatusType.EN_ROUTE_PICKUP)
        assertNotNull(DriverStatusType.ARRIVED)
        assertNotNull(DriverStatusType.IN_PROGRESS)
        assertNotNull(DriverStatusType.COMPLETED)
        assertNotNull(DriverStatusType.CANCELLED)
    }

    @Test
    fun `RideshareExpiration constants are positive`() {
        assertTrue(RideshareExpiration.RIDE_OFFER_MINUTES > 0)
        assertTrue(RideshareExpiration.RIDE_ACCEPTANCE_MINUTES > 0)
        assertTrue(RideshareExpiration.RIDE_CONFIRMATION_HOURS > 0)
    }
}
