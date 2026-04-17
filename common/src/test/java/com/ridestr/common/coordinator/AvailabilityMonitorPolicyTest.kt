package com.ridestr.common.coordinator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvailabilityMonitorPolicyTest {

    // --- onAvailabilityEvent ---

    @Test
    fun `availability offline while waiting for acceptance defers check`() {
        val action = AvailabilityMonitorPolicy.onAvailabilityEvent(
            isWaitingForAcceptance = true,
            isAvailable = false,
            eventCreatedAt = 1000L,
            lastSeenTimestamp = 999L
        )
        assertEquals(AvailabilityMonitorPolicy.Action.DEFER_CHECK, action)
    }

    @Test
    fun `availability offline when not waiting for acceptance is ignored`() {
        val action = AvailabilityMonitorPolicy.onAvailabilityEvent(
            isWaitingForAcceptance = false,
            isAvailable = false,
            eventCreatedAt = 1000L,
            lastSeenTimestamp = 999L
        )
        assertEquals(AvailabilityMonitorPolicy.Action.IGNORE, action)
    }

    @Test
    fun `stale availability event is ignored regardless of waiting flag`() {
        val action = AvailabilityMonitorPolicy.onAvailabilityEvent(
            isWaitingForAcceptance = true,
            isAvailable = false,
            eventCreatedAt = 500L,
            lastSeenTimestamp = 999L
        )
        assertEquals(AvailabilityMonitorPolicy.Action.IGNORE, action)
    }

    @Test
    fun `availability online while waiting is ignored`() {
        val action = AvailabilityMonitorPolicy.onAvailabilityEvent(
            isWaitingForAcceptance = true,
            isAvailable = true,
            eventCreatedAt = 1000L,
            lastSeenTimestamp = 999L
        )
        assertEquals(AvailabilityMonitorPolicy.Action.IGNORE, action)
    }

    @Test
    fun `equal timestamps pass the stale guard`() {
        // Callers typically update `lastSeenTimestamp` to `eventCreatedAt` before invoking the
        // policy, producing equal values — the policy must treat that as "current, not stale".
        val action = AvailabilityMonitorPolicy.onAvailabilityEvent(
            isWaitingForAcceptance = true,
            isAvailable = false,
            eventCreatedAt = 1000L,
            lastSeenTimestamp = 1000L
        )
        assertEquals(AvailabilityMonitorPolicy.Action.DEFER_CHECK, action)
    }

    // --- onDeletionEvent ---

    @Test
    fun `deletion while waiting for acceptance defers check`() {
        val action = AvailabilityMonitorPolicy.onDeletionEvent(
            isWaitingForAcceptance = true,
            deletionTimestamp = 1000L,
            lastSeenTimestamp = 999L
        )
        assertEquals(AvailabilityMonitorPolicy.Action.DEFER_CHECK, action)
    }

    @Test
    fun `deletion when not waiting for acceptance is ignored`() {
        val action = AvailabilityMonitorPolicy.onDeletionEvent(
            isWaitingForAcceptance = false,
            deletionTimestamp = 1000L,
            lastSeenTimestamp = 999L
        )
        assertEquals(AvailabilityMonitorPolicy.Action.IGNORE, action)
    }

    @Test
    fun `stale deletion is ignored`() {
        val action = AvailabilityMonitorPolicy.onDeletionEvent(
            isWaitingForAcceptance = true,
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
}
