package com.ridestr.rider.service

import com.ridestr.rider.presence.RiderPresenceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToRiderStatusTest {

    @Test
    fun `SEARCHING maps to Searching`() {
        val status = RiderPresenceMode.SEARCHING.toRiderStatus()
        assertTrue(status is RiderStatus.Searching)
    }

    @Test
    fun `DRIVER_ACCEPTED maps to DriverAccepted with name`() {
        val status = RiderPresenceMode.DRIVER_ACCEPTED.toRiderStatus("Alice")
        assertEquals(RiderStatus.DriverAccepted("Alice"), status)
    }

    @Test
    fun `DRIVER_EN_ROUTE maps to DriverEnRoute with name`() {
        val status = RiderPresenceMode.DRIVER_EN_ROUTE.toRiderStatus("Bob")
        assertEquals(RiderStatus.DriverEnRoute("Bob"), status)
    }

    @Test
    fun `DRIVER_ARRIVED maps to DriverArrived with name`() {
        val status = RiderPresenceMode.DRIVER_ARRIVED.toRiderStatus("Carol")
        assertEquals(RiderStatus.DriverArrived("Carol"), status)
    }

    @Test
    fun `IN_RIDE maps to RideInProgress with name`() {
        val status = RiderPresenceMode.IN_RIDE.toRiderStatus("Dave")
        assertEquals(RiderStatus.RideInProgress("Dave"), status)
    }

    @Test
    fun `toRiderStatus with null driverName`() {
        val status = RiderPresenceMode.DRIVER_EN_ROUTE.toRiderStatus(null)
        assertEquals(RiderStatus.DriverEnRoute(null), status)
    }

    @Test
    fun `toRiderStatus default driverName is null`() {
        val status = RiderPresenceMode.DRIVER_EN_ROUTE.toRiderStatus()
        assertEquals(RiderStatus.DriverEnRoute(null), status)
    }

    @Test
    fun `toRiderStatus is exhaustive over all RiderPresenceMode values`() {
        RiderPresenceMode.entries.forEach { mode ->
            // Should not throw — exhaustive when expression
            mode.toRiderStatus("test")
        }
    }
}
