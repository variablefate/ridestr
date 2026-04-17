package com.ridestr.common.coordinator

import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.events.PaymentPath
import com.ridestr.common.nostr.events.RideAcceptanceData
import com.ridestr.common.payment.harness.MainDispatcherRule
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [PaymentCoordinator] public API surface. Focused on the guards and state
 * accessors that four rounds of review passes introduced or clarified:
 *
 * - Cancellation-event dedup roundtrip (prevents cross-ride contamination).
 * - `restoreRideState` / `getLastProcessedDriverActionCount` roundtrip (process-death safety).
 * - Reset / cancel idempotency.
 * - `retryEscrowLock` no-op when no retry is pending.
 *
 * The confirmation coroutine itself hits NostrService + WalletService and is covered by
 * integration-style tests in the ViewModel layer; these tests deliberately pin only the
 * observable contracts that live entirely inside the coordinator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PaymentCoordinatorTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var nostrService: NostrService
    private lateinit var scope: CoroutineScope
    private lateinit var coord: PaymentCoordinator

    @Before
    fun setUp() {
        nostrService = mockk(relaxed = true)
        scope = TestScope(mainDispatcherRule.testDispatcher)
        coord = PaymentCoordinator(nostrService, scope)
    }

    // ── Cancellation dedup ───────────────────────────────────────────────────

    @Test
    fun `markCancellationProcessed records the event id`() {
        val eventId = "abc123"
        assertFalse(coord.isCancellationProcessed(eventId))

        coord.markCancellationProcessed(eventId)
        assertTrue(coord.isCancellationProcessed(eventId))
    }

    @Test
    fun `isCancellationProcessed is false for unknown ids`() {
        coord.markCancellationProcessed("known")
        assertFalse(coord.isCancellationProcessed("unknown"))
    }

    @Test
    fun `reset clears the cancellation dedup set so new rides start fresh`() {
        coord.markCancellationProcessed("old-ride-cancel")
        assertTrue(coord.isCancellationProcessed("old-ride-cancel"))

        coord.reset()

        assertFalse(
            "After reset, the old cancellation id must NOT be marked processed — a new ride " +
                "with a coincidentally-matching id needs to be able to fire its handler.",
            coord.isCancellationProcessed("old-ride-cancel")
        )
    }

    // ── Process-death roundtrip ──────────────────────────────────────────────

    @Test
    fun `restoreRideState roundtrips lastProcessedDriverActionCount`() {
        coord.restoreRideState(
            confirmationEventId = "ride-1",
            paymentPath = PaymentPath.SAME_MINT,
            paymentHash = "hash",
            preimage = "preimage",
            escrowToken = "token",
            pickupPin = "1234",
            pinVerified = false,
            destination = null,
            postConfirmDeadlineMs = 0L,
            lastProcessedDriverActionCount = 7
        )

        assertEquals(
            "Counter persisted pre-death must be restored so Kind 30180 history replay is skipped.",
            7,
            coord.getLastProcessedDriverActionCount()
        )
    }

    @Test
    fun `restoreRideState defaults lastProcessedDriverActionCount to zero`() {
        coord.restoreRideState(
            confirmationEventId = "ride-1",
            paymentPath = PaymentPath.SAME_MINT,
            paymentHash = null,
            preimage = null,
            escrowToken = null,
            pickupPin = null,
            pinVerified = false,
            destination = null
            // lastProcessedDriverActionCount omitted — should default to 0
        )
        assertEquals(0, coord.getLastProcessedDriverActionCount())
    }

    @Test
    fun `reset clears the restored lastProcessedDriverActionCount`() {
        coord.restoreRideState(
            confirmationEventId = "ride-1",
            paymentPath = PaymentPath.SAME_MINT,
            paymentHash = null, preimage = null, escrowToken = null,
            pickupPin = null, pinVerified = false, destination = null,
            lastProcessedDriverActionCount = 42
        )
        assertEquals(42, coord.getLastProcessedDriverActionCount())

        coord.reset()

        assertEquals(
            "reset() must clear the counter so the next ride does not skip legitimate " +
                "driver actions from its own Kind 30180.",
            0,
            coord.getLastProcessedDriverActionCount()
        )
    }

    // ── Idempotency ──────────────────────────────────────────────────────────

    @Test
    fun `reset is idempotent — multiple calls do not throw`() {
        coord.reset()
        coord.reset()
        coord.reset()
        // If this returns, idempotency holds.
    }

    @Test
    fun `onRideCancelled is idempotent when no active ride`() {
        coord.onRideCancelled()
        coord.onRideCancelled()
        // No throw = pass.
    }

    @Test
    fun `onRideCancelled clears cancellation dedup via reset path`() {
        coord.markCancellationProcessed("some-event")
        assertTrue(coord.isCancellationProcessed("some-event"))

        coord.onRideCancelled()

        assertFalse(coord.isCancellationProcessed("some-event"))
    }

    // ── retryEscrowLock guard ────────────────────────────────────────────────

    @Test
    fun `retryEscrowLock is a no-op when no retry is pending`() {
        // No prior onAcceptanceReceived — pendingRetryAcceptance / pendingRetryInputs are null.
        // The method must simply return without crashing or transitioning state.
        coord.retryEscrowLock()
        coord.retryEscrowLock()
        // If we got here, the stale-UI-interaction guard works.
    }

    // ── Restore sets active payment hash ─────────────────────────────────────

    @Test
    fun `onRideCancelled with explicit paymentHash does not throw`() {
        // The contract allows the caller to override activePaymentHash. Verify the method
        // accepts the override path without error even when walletService is null.
        coord.onRideCancelled(paymentHash = "override-hash")
    }

    // ── Restore + retry sanity ───────────────────────────────────────────────

    @Test
    fun `sequential restore calls overwrite state`() {
        coord.restoreRideState(
            confirmationEventId = "ride-1",
            paymentPath = PaymentPath.SAME_MINT,
            paymentHash = null, preimage = null, escrowToken = null,
            pickupPin = "1111", pinVerified = false, destination = null,
            lastProcessedDriverActionCount = 3
        )
        coord.restoreRideState(
            confirmationEventId = "ride-2",
            paymentPath = PaymentPath.CROSS_MINT,
            paymentHash = null, preimage = null, escrowToken = null,
            pickupPin = "2222", pinVerified = true, destination = null,
            lastProcessedDriverActionCount = 9
        )

        // The second restore wins for the counter; behaviour is "last write wins", not accumulate.
        assertEquals(9, coord.getLastProcessedDriverActionCount())
    }

    // ── Helper: make an acceptance (kept for any future test that needs it) ──

    @Suppress("unused")
    private fun acceptance(eventId: String = "acc-1") = RideAcceptanceData(
        eventId = eventId,
        driverPubKey = "drvr",
        offerEventId = "offer",
        riderPubKey = "rider",
        status = "accepted",
        createdAt = 0L,
        mintUrl = "https://mint.example",
        paymentMethod = "cashu",
        walletPubKey = "02" + "00".repeat(32)
    )

    @Suppress("unused")
    private fun inputs() = ConfirmationInputs(
        pickupLocation = Location(0.0, 0.0),
        destination = Location(0.0, 0.0),
        fareAmountSats = 0L,
        paymentHash = null,
        preimage = null,
        riderMintUrl = "https://mint.example",
        isRoadflareRide = false,
        driverApproxLocation = null
    )
}
