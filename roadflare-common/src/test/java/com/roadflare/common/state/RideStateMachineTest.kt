package com.roadflare.common.state

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RideStateMachineTest {

    private lateinit var sm: RideStateMachine

    private val riderPubkey = "rider1"
    private val driverPubkey = "driver1"

    @Before
    fun setup() {
        sm = RideStateMachine()
    }

    @Test
    fun `CREATED to ACCEPTED on Accept event by driver`() = runBlocking {
        val ctx = makeContext()
        val result = sm.processEvent(
            RideState.CREATED, ctx,
            RideEvent.Accept(inputterPubkey = driverPubkey, driverPubkey = driverPubkey)
        )
        assertTrue("Should succeed: $result", result is TransitionResult.Success)
        assertEquals(RideState.ACCEPTED, (result as TransitionResult.Success).toState)
    }

    @Test
    fun `ACCEPTED to CONFIRMED on Confirm event by rider`() = runBlocking {
        val ctx = makeContext(hasDriver = true)
        val result = sm.processEvent(
            RideState.ACCEPTED, ctx,
            RideEvent.Confirm(inputterPubkey = riderPubkey, confirmationEventId = "conf1")
        )
        assertTrue("Should succeed: $result", result is TransitionResult.Success)
        assertEquals(RideState.CONFIRMED, (result as TransitionResult.Success).toState)
    }

    @Test
    fun `CONFIRMED to EN_ROUTE on StartRoute event by driver`() = runBlocking {
        val ctx = makeContext(hasDriver = true)
        val result = sm.processEvent(
            RideState.CONFIRMED, ctx,
            RideEvent.StartRoute(inputterPubkey = driverPubkey)
        )
        assertTrue("Should succeed: $result", result is TransitionResult.Success)
        assertEquals(RideState.EN_ROUTE, (result as TransitionResult.Success).toState)
    }

    @Test
    fun `EN_ROUTE to ARRIVED on Arrive event by driver`() = runBlocking {
        val ctx = makeContext(hasDriver = true)
        val result = sm.processEvent(
            RideState.EN_ROUTE, ctx,
            RideEvent.Arrive(inputterPubkey = driverPubkey)
        )
        assertTrue("Should succeed: $result", result is TransitionResult.Success)
        assertEquals(RideState.ARRIVED, (result as TransitionResult.Success).toState)
    }

    @Test
    fun `any active state to CANCELLED on Cancel event`() = runBlocking {
        val cancellableStates = listOf(
            RideState.CREATED,
            RideState.ACCEPTED,
            RideState.CONFIRMED,
            RideState.EN_ROUTE,
            RideState.ARRIVED,
            RideState.IN_PROGRESS
        )
        for (state in cancellableStates) {
            val ctx = makeContext(hasDriver = state != RideState.CREATED)
            val result = sm.processEvent(
                state, ctx,
                RideEvent.Cancel(inputterPubkey = riderPubkey, reason = "test cancel")
            )
            assertTrue(
                "Cancel should work from $state, got $result",
                result is TransitionResult.Success
            )
            assertEquals(
                RideState.CANCELLED,
                (result as TransitionResult.Success).toState
            )
        }
    }

    @Test
    fun `RideState isTerminal correctly identifies terminal states`() {
        assertTrue(RideState.COMPLETED.isTerminal())
        assertTrue(RideState.CANCELLED.isTerminal())
        assertFalse(RideState.CREATED.isTerminal())
        assertFalse(RideState.IN_PROGRESS.isTerminal())
    }

    @Test
    fun `RideState canCancel correctly identifies cancellable states`() {
        assertTrue(RideState.CREATED.canCancel())
        assertTrue(RideState.ACCEPTED.canCancel())
        assertTrue(RideState.CONFIRMED.canCancel())
        assertTrue(RideState.EN_ROUTE.canCancel())
        assertTrue(RideState.ARRIVED.canCancel())
        assertTrue(RideState.IN_PROGRESS.canCancel())
        assertFalse(RideState.COMPLETED.canCancel())
        assertFalse(RideState.CANCELLED.canCancel())
    }

    @Test
    fun `RideStage fromRideState derives correct stages`() {
        assertEquals(RideStage.IDLE, RideStage.fromRideState(RideState.CREATED))
        assertEquals(RideStage.REQUESTING, RideStage.fromRideState(RideState.CREATED, hasSentOffers = true))
        assertEquals(RideStage.CHOOSING_DRIVER, RideStage.fromRideState(RideState.CREATED, hasAcceptances = true))
        assertEquals(RideStage.CHOOSING_DRIVER, RideStage.fromRideState(RideState.ACCEPTED))
        assertEquals(RideStage.MATCHED, RideStage.fromRideState(RideState.CONFIRMED))
        assertEquals(RideStage.DRIVER_EN_ROUTE, RideStage.fromRideState(RideState.EN_ROUTE))
        assertEquals(RideStage.DRIVER_ARRIVED, RideStage.fromRideState(RideState.ARRIVED))
        assertEquals(RideStage.IN_RIDE, RideStage.fromRideState(RideState.IN_PROGRESS))
        assertEquals(RideStage.COMPLETED, RideStage.fromRideState(RideState.COMPLETED))
        assertEquals(RideStage.CANCELLED, RideStage.fromRideState(RideState.CANCELLED))
    }

    private fun makeContext(hasDriver: Boolean = false): RideContext {
        return RideContext(
            riderPubkey = riderPubkey,
            driverPubkey = if (hasDriver) driverPubkey else null,
            inputterPubkey = riderPubkey,
            offerEventId = "offer1"
        )
    }
}
