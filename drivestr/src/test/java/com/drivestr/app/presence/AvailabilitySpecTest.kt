package com.drivestr.app.presence

import com.ridestr.common.data.Vehicle
import com.ridestr.common.nostr.events.DriverAvailabilityEvent
import com.ridestr.common.nostr.events.Location
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AvailabilitySpecTest {

    // --- Available ---

    @Test
    fun `Available produces STATUS_AVAILABLE with location and full metadata`() {
        val loc = Location(40.7, -74.0)
        val vehicle = Vehicle(id = "v1", make = "Toyota", model = "Camry", color = "White", year = 2024)
        val spec = AvailabilitySpec.Available(loc, vehicle, "https://mint.example.com", listOf("cashu", "lightning"))
        val args = spec.toPublishArgs()

        assertEquals(DriverAvailabilityEvent.STATUS_AVAILABLE, args.status)
        assertEquals(loc, args.location)
        assertEquals(vehicle, args.vehicle)
        assertEquals("https://mint.example.com", args.mintUrl)
        assertEquals(listOf("cashu", "lightning"), args.paymentMethods)
    }

    // --- RoadflarePresence ---

    @Test
    fun `RoadflarePresence produces STATUS_AVAILABLE with null location and no vehicle or mint`() {
        val args = AvailabilitySpec.RoadflarePresence.toPublishArgs()

        assertEquals(DriverAvailabilityEvent.STATUS_AVAILABLE, args.status)
        assertNull(args.location)
        assertNull(args.vehicle)
        assertNull(args.mintUrl)
        assertEquals(listOf("cashu"), args.paymentMethods)
    }

    // --- OfflineWithLocation ---

    @Test
    fun `OfflineWithLocation produces STATUS_OFFLINE with location and no vehicle or mint`() {
        val loc = Location(40.7, -74.0)
        val args = AvailabilitySpec.OfflineWithLocation(loc).toPublishArgs()

        assertEquals(DriverAvailabilityEvent.STATUS_OFFLINE, args.status)
        assertEquals(loc, args.location)
        assertNull(args.vehicle)
        assertNull(args.mintUrl)
        assertEquals(listOf("cashu"), args.paymentMethods)
    }

    // --- OfflineLocationless ---

    @Test
    fun `OfflineLocationless produces STATUS_OFFLINE with null location and no vehicle or mint`() {
        val args = AvailabilitySpec.OfflineLocationless.toPublishArgs()

        assertEquals(DriverAvailabilityEvent.STATUS_OFFLINE, args.status)
        assertNull(args.location)
        assertNull(args.vehicle)
        assertNull(args.mintUrl)
        assertEquals(listOf("cashu"), args.paymentMethods)
    }

    // --- Cross-variant invariants ---

    @Test
    fun `offline variants never produce STATUS_AVAILABLE`() {
        val offlineSpecs = listOf(
            AvailabilitySpec.OfflineLocationless,
            AvailabilitySpec.OfflineWithLocation(Location(0.0, 0.0))
        )
        offlineSpecs.forEach { spec ->
            assertNotEquals("$spec should not be AVAILABLE",
                DriverAvailabilityEvent.STATUS_AVAILABLE, spec.toPublishArgs().status)
        }
    }

    @Test
    fun `online variants never produce STATUS_OFFLINE`() {
        val onlineSpecs = listOf(
            AvailabilitySpec.RoadflarePresence,
            AvailabilitySpec.Available(Location(0.0, 0.0), null, null, listOf("cashu"))
        )
        onlineSpecs.forEach { spec ->
            assertNotEquals("$spec should not be OFFLINE",
                DriverAvailabilityEvent.STATUS_OFFLINE, spec.toPublishArgs().status)
        }
    }

    @Test
    fun `Available rejects empty paymentMethods`() {
        assertThrows(IllegalArgumentException::class.java) {
            AvailabilitySpec.Available(Location(0.0, 0.0), null, null, emptyList())
        }
    }

    @Test
    fun `all spec subclasses are covered`() {
        val allSpecs: List<AvailabilitySpec> = listOf(
            AvailabilitySpec.Available(Location(0.0, 0.0), null, null, listOf("cashu")),
            AvailabilitySpec.RoadflarePresence,
            AvailabilitySpec.OfflineWithLocation(Location(0.0, 0.0)),
            AvailabilitySpec.OfflineLocationless
        )
        assertEquals(4, allSpecs.size)
        allSpecs.forEach { it.toPublishArgs() }
    }
}
