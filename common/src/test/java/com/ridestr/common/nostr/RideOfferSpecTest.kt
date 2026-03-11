package com.ridestr.common.nostr

import com.ridestr.common.nostr.events.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RideOfferSpecTest {

    private val pickup = Location(40.7128, -74.0060)
    private val destination = Location(40.7580, -73.9855)
    private val route = RouteMetrics(distanceKm = 5.2, durationMin = 12.0)

    // ==================== RouteMetrics ====================

    @Test
    fun `fromSeconds converts duration correctly`() {
        val metrics = RouteMetrics.fromSeconds(distanceKm = 10.0, durationSeconds = 900.0)
        assertEquals(10.0, metrics.distanceKm, 0.001)
        assertEquals(15.0, metrics.durationMin, 0.001)
    }

    // ==================== Spec-to-arg mapping (field correctness) ====================

    @Test
    fun `Direct spec preserves all fields`() {
        val spec = RideOfferSpec.Direct(
            driverPubKey = "abc123",
            driverAvailabilityEventId = "event456",
            pickup = pickup,
            destination = destination,
            fareEstimate = 500.0,
            pickupRoute = route,
            rideRoute = RouteMetrics(8.0, 20.0),
            mintUrl = "https://mint.example.com",
            paymentMethod = "cashu",
            fiatPaymentMethods = listOf("zelle", "paypal")
        )
        assertEquals("abc123", spec.driverPubKey)
        assertEquals("event456", spec.driverAvailabilityEventId)
        assertEquals(500.0, spec.fareEstimate, 0.001)
        assertEquals(5.2, spec.pickupRoute!!.distanceKm, 0.001)
        assertEquals(12.0, spec.pickupRoute!!.durationMin, 0.001)
        assertEquals(8.0, spec.rideRoute!!.distanceKm, 0.001)
        assertEquals("https://mint.example.com", spec.mintUrl)
        assertEquals("cashu", spec.paymentMethod)
        assertEquals(listOf("zelle", "paypal"), spec.fiatPaymentMethods)
    }

    @Test
    fun `RoadFlare spec has no availability event id field`() {
        val spec = RideOfferSpec.RoadFlare(
            driverPubKey = "driver789",
            pickup = pickup,
            destination = destination,
            fareEstimate = 300.0,
            pickupRoute = null,
            rideRoute = null,
            mintUrl = null,
            paymentMethod = "fiat_cash"
        )
        assertEquals("driver789", spec.driverPubKey)
        assertEquals(300.0, spec.fareEstimate, 0.001)
        assertNull(spec.pickupRoute)
        assertNull(spec.rideRoute)
        assertNull(spec.mintUrl)
        assertEquals("fiat_cash", spec.paymentMethod)
        assertTrue(spec.fiatPaymentMethods.isEmpty())
    }

    @Test
    fun `Broadcast spec requires route and uses precise locations`() {
        val spec = RideOfferSpec.Broadcast(
            pickup = pickup,
            destination = destination,
            fareEstimate = 750.0,
            routeDistance = route,
            mintUrl = "https://mint.example.com",
            paymentMethod = "cashu"
        )
        // Locations are precise — event builder handles approximation
        assertEquals(40.7128, spec.pickup.lat, 0.0001)
        assertEquals(-74.0060, spec.pickup.lon, 0.0001)
        assertEquals(5.2, spec.routeDistance.distanceKm, 0.001)
        assertEquals(12.0, spec.routeDistance.durationMin, 0.001)
    }

    // ==================== Variant type checks ====================

    @Test
    fun `sealed class variants are distinct types`() {
        val direct: RideOfferSpec = RideOfferSpec.Direct(
            driverPubKey = "a", driverAvailabilityEventId = "b",
            pickup = pickup, destination = destination, fareEstimate = 100.0,
            pickupRoute = null, rideRoute = null, mintUrl = null, paymentMethod = "cashu"
        )
        val roadflare: RideOfferSpec = RideOfferSpec.RoadFlare(
            driverPubKey = "a", pickup = pickup, destination = destination,
            fareEstimate = 100.0, pickupRoute = null, rideRoute = null,
            mintUrl = null, paymentMethod = "cashu"
        )
        val broadcast: RideOfferSpec = RideOfferSpec.Broadcast(
            pickup = pickup, destination = destination, fareEstimate = 100.0,
            routeDistance = route, mintUrl = null, paymentMethod = "cashu"
        )
        assertTrue(direct is RideOfferSpec.Direct)
        assertTrue(roadflare is RideOfferSpec.RoadFlare)
        assertTrue(broadcast is RideOfferSpec.Broadcast)
    }

    @Test
    fun `Direct with null routes maps correctly`() {
        val spec = RideOfferSpec.Direct(
            driverPubKey = "abc",
            driverAvailabilityEventId = "evt",
            pickup = pickup,
            destination = destination,
            fareEstimate = 200.0,
            pickupRoute = null,
            rideRoute = null,
            mintUrl = null,
            paymentMethod = "lightning"
        )
        assertNull(spec.pickupRoute)
        assertNull(spec.rideRoute)
        assertEquals("lightning", spec.paymentMethod)
    }

    @Test
    fun `fiatPaymentMethods defaults to empty list`() {
        val direct = RideOfferSpec.Direct(
            driverPubKey = "a", driverAvailabilityEventId = "b",
            pickup = pickup, destination = destination, fareEstimate = 100.0,
            pickupRoute = null, rideRoute = null, mintUrl = null, paymentMethod = "cashu"
        )
        val roadflare = RideOfferSpec.RoadFlare(
            driverPubKey = "a", pickup = pickup, destination = destination,
            fareEstimate = 100.0, pickupRoute = null, rideRoute = null,
            mintUrl = null, paymentMethod = "cashu"
        )
        assertTrue(direct.fiatPaymentMethods.isEmpty())
        assertTrue(roadflare.fiatPaymentMethods.isEmpty())
    }
}
