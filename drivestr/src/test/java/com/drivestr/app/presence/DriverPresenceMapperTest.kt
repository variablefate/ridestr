package com.drivestr.app.presence

import com.drivestr.app.service.DriverStatus
import com.ridestr.common.nostr.events.RoadflareLocationEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DriverPresenceMapperTest {

    // ========================================
    // Channel 1: roadflareStatus (DriverStage → RoadFlare status string)
    // ========================================

    @Test
    fun `roadflareStatus maps OFFLINE to OFFLINE`() {
        assertEquals(RoadflareLocationEvent.Status.OFFLINE, DriverPresenceMapper.roadflareStatus(DriverStage.OFFLINE))
    }

    @Test
    fun `roadflareStatus maps online stages to ONLINE`() {
        assertEquals(RoadflareLocationEvent.Status.ONLINE, DriverPresenceMapper.roadflareStatus(DriverStage.ROADFLARE_ONLY))
        assertEquals(RoadflareLocationEvent.Status.ONLINE, DriverPresenceMapper.roadflareStatus(DriverStage.AVAILABLE))
    }

    @Test
    fun `roadflareStatus maps ride stages to ON_RIDE`() {
        val rideStages = listOf(
            DriverStage.RIDE_ACCEPTED,
            DriverStage.EN_ROUTE_TO_PICKUP,
            DriverStage.ARRIVED_AT_PICKUP,
            DriverStage.IN_RIDE,
            DriverStage.RIDE_COMPLETED
        )
        rideStages.forEach { stage ->
            assertEquals("$stage should map to ON_RIDE", RoadflareLocationEvent.Status.ON_RIDE, DriverPresenceMapper.roadflareStatus(stage))
        }
    }

    @Test
    fun `roadflareStatus is exhaustive over all DriverStage values`() {
        DriverStage.entries.forEach { stage ->
            // Should not throw — exhaustive when expression
            DriverPresenceMapper.roadflareStatus(stage)
        }
    }

    // ========================================
    // Channel 2: serviceBaseStatus (DriverStage → DriverStatus?)
    // ========================================

    @Test
    fun `serviceBaseStatus returns null for non-service stages`() {
        assertNull(DriverPresenceMapper.serviceBaseStatus(DriverStage.OFFLINE))
        assertNull(DriverPresenceMapper.serviceBaseStatus(DriverStage.RIDE_ACCEPTED))
        assertNull(DriverPresenceMapper.serviceBaseStatus(DriverStage.RIDE_COMPLETED))
    }

    @Test
    fun `serviceBaseStatus maps ROADFLARE_ONLY to RoadflareOnly`() {
        assertEquals(DriverStatus.RoadflareOnly, DriverPresenceMapper.serviceBaseStatus(DriverStage.ROADFLARE_ONLY))
    }

    @Test
    fun `serviceBaseStatus maps AVAILABLE to Available with zero count`() {
        val result = DriverPresenceMapper.serviceBaseStatus(DriverStage.AVAILABLE)
        assertEquals(DriverStatus.Available(0), result)
    }

    @Test
    fun `serviceBaseStatus maps ride stages to correct DriverStatus subtypes`() {
        assert(DriverPresenceMapper.serviceBaseStatus(DriverStage.EN_ROUTE_TO_PICKUP) is DriverStatus.EnRouteToPickup)
        assert(DriverPresenceMapper.serviceBaseStatus(DriverStage.ARRIVED_AT_PICKUP) is DriverStatus.ArrivedAtPickup)
        assert(DriverPresenceMapper.serviceBaseStatus(DriverStage.IN_RIDE) is DriverStatus.RideInProgress)
    }

    // ========================================
    // Channel 3: listenerGateStatus (DriverStatus → DriverPresenceGate)
    // ========================================

    @Test
    fun `listenerGateStatus maps Available to AVAILABLE`() {
        assertEquals(DriverPresenceGate.AVAILABLE, DriverPresenceMapper.listenerGateStatus(DriverStatus.Available(0)))
        assertEquals(DriverPresenceGate.AVAILABLE, DriverPresenceMapper.listenerGateStatus(DriverStatus.Available(5)))
    }

    @Test
    fun `listenerGateStatus maps RoadflareOnly to ROADFLARE_ONLY`() {
        assertEquals(DriverPresenceGate.ROADFLARE_ONLY, DriverPresenceMapper.listenerGateStatus(DriverStatus.RoadflareOnly))
    }

    @Test
    fun `listenerGateStatus maps ride statuses to IN_RIDE`() {
        assertEquals(DriverPresenceGate.IN_RIDE, DriverPresenceMapper.listenerGateStatus(DriverStatus.EnRouteToPickup(null)))
        assertEquals(DriverPresenceGate.IN_RIDE, DriverPresenceMapper.listenerGateStatus(DriverStatus.ArrivedAtPickup("Alice")))
        assertEquals(DriverPresenceGate.IN_RIDE, DriverPresenceMapper.listenerGateStatus(DriverStatus.RideInProgress(null)))
    }

    @Test
    fun `listenerGateStatus maps overlay statuses to AVAILABLE`() {
        assertEquals(DriverPresenceGate.AVAILABLE, DriverPresenceMapper.listenerGateStatus(DriverStatus.NewRequest(1, "$5.00", "2.3 km")))
        assertEquals(DriverPresenceGate.AVAILABLE, DriverPresenceMapper.listenerGateStatus(DriverStatus.Cancelled))
    }

    // ========================================
    // Cross-channel consistency
    // ========================================

    @Test
    fun `AVAILABLE stages never produce ON_RIDE roadflare status`() {
        val availableStages = listOf(DriverStage.AVAILABLE, DriverStage.ROADFLARE_ONLY)
        availableStages.forEach { stage ->
            val rfStatus = DriverPresenceMapper.roadflareStatus(stage)
            assert(rfStatus != RoadflareLocationEvent.Status.ON_RIDE) {
                "$stage should not produce ON_RIDE roadflare status"
            }
        }
    }

    @Test
    fun `ride stages never produce ONLINE roadflare status`() {
        val rideStages = listOf(
            DriverStage.RIDE_ACCEPTED,
            DriverStage.EN_ROUTE_TO_PICKUP,
            DriverStage.ARRIVED_AT_PICKUP,
            DriverStage.IN_RIDE,
            DriverStage.RIDE_COMPLETED
        )
        rideStages.forEach { stage ->
            val rfStatus = DriverPresenceMapper.roadflareStatus(stage)
            assert(rfStatus != RoadflareLocationEvent.Status.ONLINE) {
                "$stage should not produce ONLINE roadflare status"
            }
        }
    }
}
