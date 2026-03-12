package com.ridestr.rider.viewmodels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvailabilityMonitorPolicyTest {

    // --- onAvailabilityEvent ---

    @Test
    fun `availability offline during WAITING_FOR_ACCEPTANCE defers check`() {
        val action = AvailabilityMonitorPolicy.onAvailabilityEvent(
            stage = RideStage.WAITING_FOR_ACCEPTANCE,
            isAvailable = false,
            eventCreatedAt = 1000L,
            lastSeenTimestamp = 999L
        )
        assertEquals(AvailabilityMonitorPolicy.Action.DEFER_CHECK, action)
    }

    @Test
    fun `availability offline during DRIVER_ACCEPTED is ignored`() {
        val action = AvailabilityMonitorPolicy.onAvailabilityEvent(
            stage = RideStage.DRIVER_ACCEPTED,
            isAvailable = false,
            eventCreatedAt = 1000L,
            lastSeenTimestamp = 999L
        )
        assertEquals(AvailabilityMonitorPolicy.Action.IGNORE, action)
    }

    @Test
    fun `stale availability event is ignored regardless of stage`() {
        val action = AvailabilityMonitorPolicy.onAvailabilityEvent(
            stage = RideStage.WAITING_FOR_ACCEPTANCE,
            isAvailable = false,
            eventCreatedAt = 500L,
            lastSeenTimestamp = 999L
        )
        assertEquals(AvailabilityMonitorPolicy.Action.IGNORE, action)
    }

    @Test
    fun `availability online during WAITING_FOR_ACCEPTANCE is ignored`() {
        val action = AvailabilityMonitorPolicy.onAvailabilityEvent(
            stage = RideStage.WAITING_FOR_ACCEPTANCE,
            isAvailable = true,
            eventCreatedAt = 1000L,
            lastSeenTimestamp = 999L
        )
        assertEquals(AvailabilityMonitorPolicy.Action.IGNORE, action)
    }

    // --- onDeletionEvent ---

    @Test
    fun `deletion during WAITING_FOR_ACCEPTANCE defers check`() {
        val action = AvailabilityMonitorPolicy.onDeletionEvent(
            stage = RideStage.WAITING_FOR_ACCEPTANCE,
            deletionTimestamp = 1000L,
            lastSeenTimestamp = 999L
        )
        assertEquals(AvailabilityMonitorPolicy.Action.DEFER_CHECK, action)
    }

    @Test
    fun `deletion during DRIVER_ACCEPTED is ignored`() {
        val action = AvailabilityMonitorPolicy.onDeletionEvent(
            stage = RideStage.DRIVER_ACCEPTED,
            deletionTimestamp = 1000L,
            lastSeenTimestamp = 999L
        )
        assertEquals(AvailabilityMonitorPolicy.Action.IGNORE, action)
    }

    @Test
    fun `stale deletion is ignored`() {
        val action = AvailabilityMonitorPolicy.onDeletionEvent(
            stage = RideStage.WAITING_FOR_ACCEPTANCE,
            deletionTimestamp = 500L,
            lastSeenTimestamp = 999L
        )
        assertEquals(AvailabilityMonitorPolicy.Action.IGNORE, action)
    }

    // --- seedTimestamp ---

    @Test
    fun `seed uses initial timestamp when provided`() {
        assertEquals(1234L, AvailabilityMonitorPolicy.seedTimestamp(1234L))
    }

    @Test
    fun `seed uses current time when no anchor`() {
        val before = System.currentTimeMillis() / 1000
        val seed = AvailabilityMonitorPolicy.seedTimestamp(0L)
        val after = System.currentTimeMillis() / 1000
        assertTrue("seed=$seed should be between $before and $after", seed in before..after)
    }

    // --- Stage coverage ---

    @Test
    fun `all non-waiting stages are ignored for both event types`() {
        val nonWaitingStages = listOf(
            RideStage.IDLE,
            RideStage.BROADCASTING_REQUEST,
            RideStage.DRIVER_ACCEPTED,
            RideStage.RIDE_CONFIRMED,
            RideStage.DRIVER_ARRIVED,
            RideStage.IN_PROGRESS,
            RideStage.COMPLETED
        )
        for (stage in nonWaitingStages) {
            assertEquals(
                "onAvailabilityEvent should IGNORE for stage $stage",
                AvailabilityMonitorPolicy.Action.IGNORE,
                AvailabilityMonitorPolicy.onAvailabilityEvent(
                    stage = stage,
                    isAvailable = false,
                    eventCreatedAt = 1000L,
                    lastSeenTimestamp = 999L
                )
            )
            assertEquals(
                "onDeletionEvent should IGNORE for stage $stage",
                AvailabilityMonitorPolicy.Action.IGNORE,
                AvailabilityMonitorPolicy.onDeletionEvent(
                    stage = stage,
                    deletionTimestamp = 1000L,
                    lastSeenTimestamp = 999L
                )
            )
        }
    }
}
