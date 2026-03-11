package com.drivestr.app.service

import com.drivestr.app.presence.DriverPresenceGate
import com.drivestr.app.presence.PresenceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GateForStatusTest {

    // ========================================
    // gateForStatus (DriverStatus → DriverPresenceGate)
    // ========================================

    @Test
    fun `gateForStatus maps Available to AVAILABLE`() {
        assertEquals(DriverPresenceGate.AVAILABLE, gateForStatus(DriverStatus.Available(0)))
        assertEquals(DriverPresenceGate.AVAILABLE, gateForStatus(DriverStatus.Available(5)))
    }

    @Test
    fun `gateForStatus maps RoadflareOnly to ROADFLARE_ONLY`() {
        assertEquals(DriverPresenceGate.ROADFLARE_ONLY, gateForStatus(DriverStatus.RoadflareOnly))
    }

    @Test
    fun `gateForStatus maps ride statuses to IN_RIDE`() {
        assertEquals(DriverPresenceGate.IN_RIDE, gateForStatus(DriverStatus.EnRouteToPickup(null)))
        assertEquals(DriverPresenceGate.IN_RIDE, gateForStatus(DriverStatus.ArrivedAtPickup("Alice")))
        assertEquals(DriverPresenceGate.IN_RIDE, gateForStatus(DriverStatus.RideInProgress(null)))
    }

    @Test
    fun `gateForStatus maps overlay statuses to AVAILABLE`() {
        assertEquals(DriverPresenceGate.AVAILABLE, gateForStatus(DriverStatus.NewRequest(1, "$5.00", "2.3 km")))
        assertEquals(DriverPresenceGate.AVAILABLE, gateForStatus(DriverStatus.Cancelled))
    }

    // ========================================
    // toDriverStatus (PresenceMode → DriverStatus)
    // ========================================

    @Test
    fun `toDriverStatus maps AVAILABLE to Available with zero count`() {
        assertEquals(DriverStatus.Available(0), PresenceMode.AVAILABLE.toDriverStatus())
    }

    @Test
    fun `toDriverStatus maps ROADFLARE_ONLY to RoadflareOnly`() {
        assertEquals(DriverStatus.RoadflareOnly, PresenceMode.ROADFLARE_ONLY.toDriverStatus())
    }

    @Test
    fun `toDriverStatus maps ride modes to correct DriverStatus subtypes`() {
        assert(PresenceMode.EN_ROUTE.toDriverStatus() is DriverStatus.EnRouteToPickup)
        assert(PresenceMode.AT_PICKUP.toDriverStatus() is DriverStatus.ArrivedAtPickup)
        assert(PresenceMode.IN_RIDE.toDriverStatus() is DriverStatus.RideInProgress)
    }

    @Test
    fun `toDriverStatus throws for OFF`() {
        assertThrows(IllegalStateException::class.java) {
            PresenceMode.OFF.toDriverStatus()
        }
    }
}
