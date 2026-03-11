package com.drivestr.app.presence

import com.ridestr.common.nostr.events.RoadflareLocationEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
    // Channel 2: presenceMode (DriverStage → PresenceMode)
    // ========================================

    @Test
    fun `presenceMode returns OFF for non-service stages`() {
        assertEquals(PresenceMode.OFF, DriverPresenceMapper.presenceMode(DriverStage.OFFLINE))
        assertEquals(PresenceMode.OFF, DriverPresenceMapper.presenceMode(DriverStage.RIDE_ACCEPTED))
        assertEquals(PresenceMode.OFF, DriverPresenceMapper.presenceMode(DriverStage.RIDE_COMPLETED))
    }

    @Test
    fun `presenceMode maps ROADFLARE_ONLY`() {
        assertEquals(PresenceMode.ROADFLARE_ONLY, DriverPresenceMapper.presenceMode(DriverStage.ROADFLARE_ONLY))
    }

    @Test
    fun `presenceMode maps AVAILABLE`() {
        assertEquals(PresenceMode.AVAILABLE, DriverPresenceMapper.presenceMode(DriverStage.AVAILABLE))
    }

    @Test
    fun `presenceMode maps ride stages`() {
        assertEquals(PresenceMode.EN_ROUTE, DriverPresenceMapper.presenceMode(DriverStage.EN_ROUTE_TO_PICKUP))
        assertEquals(PresenceMode.AT_PICKUP, DriverPresenceMapper.presenceMode(DriverStage.ARRIVED_AT_PICKUP))
        assertEquals(PresenceMode.IN_RIDE, DriverPresenceMapper.presenceMode(DriverStage.IN_RIDE))
    }

    @Test
    fun `presenceMode is exhaustive over all DriverStage values`() {
        DriverStage.entries.forEach { stage ->
            // Should not throw — exhaustive when expression
            DriverPresenceMapper.presenceMode(stage)
        }
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

    @Test
    fun `OFF presenceMode stages never produce ONLINE roadflare status`() {
        val offStages = DriverStage.entries.filter {
            DriverPresenceMapper.presenceMode(it) == PresenceMode.OFF
        }
        offStages.forEach { stage ->
            assertNotEquals("$stage (OFF) should not be ONLINE",
                RoadflareLocationEvent.Status.ONLINE, DriverPresenceMapper.roadflareStatus(stage))
        }
    }
}
