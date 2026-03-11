package com.ridestr.rider.presence

import com.ridestr.rider.viewmodels.RideStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RiderPresenceMapperTest {

    @Test
    fun `presenceMode returns null for non-service stages`() {
        assertNull(RiderPresenceMapper.presenceMode(RideStage.IDLE))
        assertNull(RiderPresenceMapper.presenceMode(RideStage.BROADCASTING_REQUEST))
        assertNull(RiderPresenceMapper.presenceMode(RideStage.WAITING_FOR_ACCEPTANCE))
        assertNull(RiderPresenceMapper.presenceMode(RideStage.COMPLETED))
    }

    @Test
    fun `presenceMode maps DRIVER_ACCEPTED`() {
        assertEquals(RiderPresenceMode.DRIVER_ACCEPTED,
            RiderPresenceMapper.presenceMode(RideStage.DRIVER_ACCEPTED))
    }

    @Test
    fun `presenceMode maps RIDE_CONFIRMED to DRIVER_EN_ROUTE`() {
        assertEquals(RiderPresenceMode.DRIVER_EN_ROUTE,
            RiderPresenceMapper.presenceMode(RideStage.RIDE_CONFIRMED))
    }

    @Test
    fun `presenceMode maps DRIVER_ARRIVED`() {
        assertEquals(RiderPresenceMode.DRIVER_ARRIVED,
            RiderPresenceMapper.presenceMode(RideStage.DRIVER_ARRIVED))
    }

    @Test
    fun `presenceMode maps IN_PROGRESS to IN_RIDE`() {
        assertEquals(RiderPresenceMode.IN_RIDE,
            RiderPresenceMapper.presenceMode(RideStage.IN_PROGRESS))
    }

    @Test
    fun `presenceMode is exhaustive over all RideStage values`() {
        RideStage.entries.forEach { stage ->
            // Should not throw — exhaustive when expression
            RiderPresenceMapper.presenceMode(stage)
        }
    }
}
