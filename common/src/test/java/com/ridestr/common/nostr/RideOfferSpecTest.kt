package com.ridestr.common.nostr

import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.keys.KeyManager
import com.ridestr.common.nostr.relay.RelayManager
import io.mockk.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
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

    // ==================== Spec field shape ====================

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
        assertEquals(5.2, spec.pickupRoute?.distanceKm ?: 0.0, 0.001)
        assertEquals(12.0, spec.pickupRoute?.durationMin ?: 0.0, 0.001)
        assertEquals(8.0, spec.rideRoute?.distanceKm ?: 0.0, 0.001)
        assertEquals("https://mint.example.com", spec.mintUrl)
        assertEquals("cashu", spec.paymentMethod)
        assertEquals(listOf("zelle", "paypal"), spec.fiatPaymentMethods)
    }

    @Test
    fun `Direct spec allows null availability event id for boost after process death`() {
        val spec = RideOfferSpec.Direct(
            driverPubKey = "abc123",
            driverAvailabilityEventId = null,
            pickup = pickup,
            destination = destination,
            fareEstimate = 500.0,
            pickupRoute = null,
            rideRoute = null,
            mintUrl = null,
            paymentMethod = "cashu"
        )
        assertNull(spec.driverAvailabilityEventId)
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
        assertEquals(40.7128, spec.pickup.lat, 0.0001)
        assertEquals(-74.0060, spec.pickup.lon, 0.0001)
        assertEquals(5.2, spec.routeDistance.distanceKm, 0.001)
        assertEquals(12.0, spec.routeDistance.durationMin, 0.001)
    }

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

    // ==================== Dispatch routing ====================

    @Test
    fun `Direct spec dispatches to sendRideOffer with isRoadflare false`() = runBlocking {
        val (service, spy) = createSpiedService()

        val spec = RideOfferSpec.Direct(
            driverPubKey = "driver1",
            driverAvailabilityEventId = "avail1",
            pickup = pickup, destination = destination,
            fareEstimate = 500.0,
            pickupRoute = RouteMetrics(3.0, 8.0),
            rideRoute = RouteMetrics(10.0, 25.0),
            mintUrl = "https://mint.example.com",
            paymentMethod = "cashu",
            fiatPaymentMethods = listOf("zelle")
        )
        spy.sendOffer(spec)

        coVerify {
            spy.sendRideOffer(
                driverPubKey = "driver1",
                driverAvailabilityEventId = "avail1",
                pickup = pickup,
                destination = destination,
                fareEstimate = 500.0,
                pickupRouteKm = 3.0,
                pickupRouteMin = 8.0,
                rideRouteKm = 10.0,
                rideRouteMin = 25.0,
                mintUrl = "https://mint.example.com",
                paymentMethod = "cashu",
                isRoadflare = false,
                fiatPaymentMethods = listOf("zelle")
            )
        }
    }

    @Test
    fun `Direct spec with null availability passes null not empty string`() = runBlocking {
        val (service, spy) = createSpiedService()

        val spec = RideOfferSpec.Direct(
            driverPubKey = "driver1",
            driverAvailabilityEventId = null,
            pickup = pickup, destination = destination,
            fareEstimate = 500.0,
            pickupRoute = null, rideRoute = null,
            mintUrl = null, paymentMethod = "cashu"
        )
        spy.sendOffer(spec)

        coVerify {
            spy.sendRideOffer(
                driverPubKey = "driver1",
                driverAvailabilityEventId = null,
                pickup = any(), destination = any(),
                fareEstimate = any(),
                pickupRouteKm = null,
                pickupRouteMin = null,
                rideRouteKm = null,
                rideRouteMin = null,
                mintUrl = null,
                paymentMethod = any(),
                isRoadflare = false,
                fiatPaymentMethods = any()
            )
        }
    }

    @Test
    fun `RoadFlare spec dispatches to sendRideOffer with isRoadflare true and null availability`() = runBlocking {
        val (service, spy) = createSpiedService()

        val spec = RideOfferSpec.RoadFlare(
            driverPubKey = "driver2",
            pickup = pickup, destination = destination,
            fareEstimate = 300.0,
            pickupRoute = null, rideRoute = null,
            mintUrl = null, paymentMethod = "fiat_cash",
            fiatPaymentMethods = listOf("paypal", "venmo")
        )
        spy.sendOffer(spec)

        coVerify {
            spy.sendRideOffer(
                driverPubKey = "driver2",
                driverAvailabilityEventId = null,
                pickup = any(), destination = any(),
                fareEstimate = 300.0,
                pickupRouteKm = null,
                pickupRouteMin = null,
                rideRouteKm = null,
                rideRouteMin = null,
                mintUrl = null,
                paymentMethod = "fiat_cash",
                isRoadflare = true,
                fiatPaymentMethods = listOf("paypal", "venmo")
            )
        }
    }

    @Test
    fun `Broadcast spec dispatches to broadcastRideRequest not sendRideOffer`() = runBlocking {
        val (service, spy) = createSpiedService()

        val spec = RideOfferSpec.Broadcast(
            pickup = pickup, destination = destination,
            fareEstimate = 750.0,
            routeDistance = RouteMetrics(5.2, 12.0),
            mintUrl = "https://mint.example.com",
            paymentMethod = "cashu"
        )
        spy.sendOffer(spec)

        coVerify {
            spy.broadcastRideRequest(
                pickup = pickup,
                destination = destination,
                fareEstimate = 750.0,
                routeDistanceKm = 5.2,
                routeDurationMin = 12.0,
                mintUrl = "https://mint.example.com",
                paymentMethod = "cashu"
            )
        }
        coVerify(exactly = 0) {
            spy.sendRideOffer(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    // ==================== Test helpers ====================

    private fun createSpiedService(): Pair<RideshareDomainService, RideshareDomainService> {
        val mockKeyManager = mockk<KeyManager>()
        val mockRelayManager = mockk<RelayManager>(relaxed = true)
        val service = RideshareDomainService(mockRelayManager, mockKeyManager)
        val spy = spyk(service)

        // Stub the leaf methods so they don't need a real signer/relay
        coEvery { spy.sendRideOffer(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns "mock-event-id"
        coEvery { spy.broadcastRideRequest(any(), any(), any(), any(), any(), any(), any()) } returns "mock-event-id"

        return Pair(service, spy)
    }
}
