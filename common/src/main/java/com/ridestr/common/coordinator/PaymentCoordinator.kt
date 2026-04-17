package com.ridestr.common.coordinator

import android.util.Log
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.DriverRideAction
import com.ridestr.common.nostr.events.DriverRideStateData
import com.ridestr.common.nostr.events.DriverStatusType
import com.ridestr.common.nostr.events.Location
import com.ridestr.common.nostr.events.PaymentPath
import com.ridestr.common.nostr.events.RideAcceptanceData
import com.ridestr.common.nostr.events.RiderRideAction
import com.ridestr.common.nostr.events.RiderRideStateEvent
import com.ridestr.common.payment.BridgePaymentStatus
import com.ridestr.common.payment.LockResult
import com.ridestr.common.payment.MeltQuoteState
import com.ridestr.common.payment.WalletService
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "PaymentCoordinator"

/**
 * Events emitted by [PaymentCoordinator] describing outcomes of payment and confirmation flows.
 */
sealed class PaymentEvent {
    // ── Ride confirmation ─────────────────────────────────────────────────────

    /**
     * Ride confirmed successfully. ViewModel should persist these fields, start subscriptions,
     * and stop the confirmation spinner.
     */
    data class Confirmed(
        val confirmationEventId: String,
        val pickupPin: String,
        val paymentPath: PaymentPath,
        val driverMintUrl: String?,
        val postConfirmDeadlineMs: Long,
        val escrowToken: String?,
        /** True if the precise pickup was sent immediately (driver close or RoadFlare ride). */
        val precisePickupShared: Boolean
    ) : PaymentEvent()

    /** Nostr `confirmRide()` call failed or threw. ViewModel should reset to DRIVER_ACCEPTED. */
    data class ConfirmationFailed(val message: String) : PaymentEvent()

    /**
     * The coordinator published a confirmation event but a NEW ride's acceptance is now active
     * (cross-ride race). ViewModel should issue a targeted Kind 3179 cancellation for
     * [publishedEventId] only — do NOT run author-wide NIP-09 cleanup (that would delete the
     * new ride's live events).
     */
    data class ConfirmationStale(
        val publishedEventId: String,
        val driverPubKey: String
    ) : PaymentEvent()

    /**
     * The coordinator published a confirmation event but the rider cancelled the ride while we
     * were suspended at `confirmRide()` (same-ride cancel). ViewModel should publish a targeted
     * Kind 3179 for [publishedEventId] AND run author-wide NIP-09 cleanup — no other ride is
     * active, so author-wide cleanup is safe. This replicates the original "Case 2" guard from
     * RiderViewModel.autoConfirmRide().
     */
    data class ConfirmationCancelledBySelf(
        val publishedEventId: String,
        val driverPubKey: String
    ) : PaymentEvent()

    /**
     * HTLC escrow lock failed for a SAME_MINT ride. ViewModel must show a retry/cancel dialog
     * with the given [deadlineMs]; call [PaymentCoordinator.retryEscrowLock] or
     * [PaymentCoordinator.onRideCancelled] based on user choice.
     */
    data class EscrowLockFailed(
        val userMessage: String?,
        val deadlineMs: Long
    ) : PaymentEvent()

    // ── Driver state (AtoB pattern) ───────────────────────────────────────────

    /**
     * Driver published a status update (EN_ROUTE_PICKUP, ARRIVED, IN_PROGRESS, etc.).
     * ViewModel derives the rider's UI stage from [status] via `riderStageFromDriverStatus()`.
     */
    data class DriverStatusUpdated(
        val status: String,
        val driverState: DriverRideStateData,
        val confirmationEventId: String
    ) : PaymentEvent()

    /**
     * Driver broadcast COMPLETED status. HTLC has been processed on the coordinator side.
     * ViewModel should close subscriptions, save ride history, and transition to COMPLETED stage.
     */
    data class DriverCompleted(
        val finalFareSats: Long?,
        /** True = driver confirmed claim succeeded; false = failed; null = legacy driver. */
        val claimSuccess: Boolean?
    ) : PaymentEvent()

    /**
     * Driver broadcast CANCELLED status via Kind 30180. ViewModel should release HTLC
     * protection, close subscriptions, save cancelled history, and return to IDLE.
     */
    data class DriverCancelled(val reason: String?) : PaymentEvent()

    // ── PIN verification ──────────────────────────────────────────────────────

    /**
     * PIN was correct. Payment was executed (SAME_MINT: preimage shared; CROSS_MINT: bridge
     * started or completed). ViewModel should update UI to show ride is starting and trigger
     * precise destination reveal via [PaymentCoordinator.revealLocation].
     */
    data class PinVerified(
        val confirmationEventId: String,
        val driverPubKey: String
    ) : PaymentEvent()

    /** PIN was wrong. ViewModel should show remaining attempts. */
    data class PinRejected(val attemptCount: Int, val maxAttempts: Int) : PaymentEvent()

    /**
     * Maximum PIN attempts reached — ride cancelled for security. ViewModel should clear all
     * subscriptions, publish cancellation, and return to IDLE.
     */
    object MaxPinAttemptsReached : PaymentEvent()

    // ── Cross-mint bridge payment ─────────────────────────────────────────────

    /** Bridge payment started (spinner visible). */
    object BridgeInProgress : PaymentEvent()

    /** Bridge payment resolved synchronously (amount confirmed). */
    data class BridgeCompleted(val amountSats: Long) : PaymentEvent()

    /** Bridge payment failed. ViewModel should cancel the ride. */
    data class BridgeFailed(val message: String) : PaymentEvent()

    /**
     * Bridge payment is PENDING (Lightning still routing). ViewModel should show info message
     * after a delay and NOT cancel the ride — polling will resolve it or time out.
     */
    object BridgePendingStarted : PaymentEvent()

    // ── Timeouts ──────────────────────────────────────────────────────────────

    /**
     * No driver status update arrived within [POST_CONFIRM_ACK_TIMEOUT_MS] after confirmation.
     * ViewModel should call [PaymentCoordinator.onRideCancelled] and handle as a driver cancel.
     */
    object PostConfirmAckTimeout : PaymentEvent()

    /**
     * Escrow retry deadline expired while the dialog was still showing.
     * ViewModel should call [PaymentCoordinator.onRideCancelled] and cancel the ride.
     */
    object EscrowRetryDeadlineExpired : PaymentEvent()

    // ── Deposit invoice ───────────────────────────────────────────────────────

    /** Driver shared their mint deposit invoice for cross-mint bridge payment. */
    data class DepositInvoiceReceived(val invoice: String, val amount: Long) : PaymentEvent()
}

/**
 * Inputs passed to [PaymentCoordinator.onAcceptanceReceived] describing the ride being confirmed.
 * All values are captured from ViewModel state at the moment of acceptance, so they remain
 * stable across coroutine suspension boundaries.
 *
 * @property pickupLocation  Rider's precise pickup location.
 * @property destination     Precise destination, revealed after PIN verification.
 * @property fareAmountSats  Fare in satoshis for HTLC locking (0 or negative skips locking).
 * @property paymentHash     HTLC payment hash (null for non-Cashu rides).
 * @property preimage        HTLC preimage generated by rider (null for non-Cashu rides).
 * @property riderMintUrl    Rider's current Cashu mint URL (determines payment path).
 * @property isRoadflareRide True → send precise pickup immediately (trusted driver network).
 * @property driverApproxLocation Driver's last known location for proximity check.
 */
data class ConfirmationInputs(
    val pickupLocation: Location,
    val destination: Location?,
    val fareAmountSats: Long,
    val paymentHash: String?,
    val preimage: String?,
    val riderMintUrl: String?,
    val isRoadflareRide: Boolean,
    val driverApproxLocation: Location?
)

/**
 * Coordinates the rider-side payment and ride confirmation flows extracted from RiderViewModel:
 *
 * - Kind 3175 ride confirmation with optional HTLC escrow locking
 * - Escrow retry/cancel dialog lifecycle
 * - Kind 30181 rider ride state publishing (location reveals, PIN verification, preimage share)
 * - Kind 30180 driver ride state processing (status updates, PIN submissions, deposit invoices)
 * - Cross-mint Lightning bridge payment with pending poll
 * - Post-confirm ack timeout (60 s — guards against driver going silent after confirmation)
 * - Ride history HTLC marking on completion
 *
 * **Lifecycle:** create in a ViewModel init block (or pass `viewModelScope`), call
 * [onAcceptanceReceived] when a driver acceptance arrives, call [reset] when a ride ends,
 * call [destroy] from `onCleared()`.
 *
 * **Thread safety:** [confirmationInFlight] is an AtomicBoolean CAS gate. [riderStateHistory]
 * uses a mutex to serialise concurrent history mutations across IO threads. Both guards are
 * preserved exactly as in the original RiderViewModel to prevent multi-relay race conditions.
 *
 * **DI note:** constructor injection is manual (no Hilt) until the Hilt migration tracked in
 * Issue #52.
 */
// TODO(#52): convert to @Singleton @Inject
class PaymentCoordinator(
    private val nostrService: NostrService,
    private val scope: CoroutineScope
) {

    // WalletService is wired post-construction — wallet initialisation happens after ViewModel
    // creation. Pattern mirrors RiderViewModel.setWalletService().
    var walletService: WalletService? = null

    companion object {
        internal const val MAX_PIN_ATTEMPTS = 3

        /** Safety margin — lockForRide() may consume 5-10 s before failing. */
        internal const val ESCROW_RETRY_DEADLINE_MS = 15_000L

        /** Must be > driver's CONFIRMATION_TIMEOUT_MS (30 s). */
        internal const val POST_CONFIRM_ACK_TIMEOUT_MS = 60_000L

        private const val BRIDGE_POLL_TIMEOUT_MS = 10 * 60_000L
        private const val BRIDGE_POLL_INTERVAL_MS = 30_000L
        private const val BRIDGE_ALERT_DELAY_MS = 8_000L

        /** Ensures distinct NIP-33 timestamp between sequential Kind 30181 events. */
        private const val DISTINCT_TIMESTAMP_DELAY_MS = 1_100L

        /** HTLC expiry in seconds — 15 minutes for in-progress ride. */
        private const val HTLC_EXPIRY_SECONDS = 900L
    }

    // ── Events ────────────────────────────────────────────────────────────────

    private val _events = MutableSharedFlow<PaymentEvent>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Hot stream of [PaymentEvent] describing protocol outcomes.
     * Collectors receive events only while subscribed.
     */
    val events: SharedFlow<PaymentEvent> = _events.asSharedFlow()

    // ── Race guards ───────────────────────────────────────────────────────────

    /**
     * Thread-safe CAS gate: exactly one confirmation coroutine runs per ride.
     * Multi-relay delivery of Kind 3174 acceptance can call onAcceptanceReceived() concurrently
     * from different IO threads — only the first compareAndSet(false, true) caller proceeds.
     * Reset to false on confirmation failure, escrow failure, and in reset().
     */
    private val confirmationInFlight = AtomicBoolean(false)

    // ── Kind 30181 rider ride state ───────────────────────────────────────────

    /**
     * Accumulates RiderRideActions during the ride (location reveals, PIN verifications,
     * preimage shares, bridge complete). Published as the consolidated history array in
     * every Kind 30181 event.
     *
     * THREAD SAFETY: synchronizedList wrapper + [historyMutex] prevent concurrent add+publish
     * from racing when coroutines publish different action types simultaneously.
     */
    private val riderStateHistory: MutableList<RiderRideAction> =
        Collections.synchronizedList(mutableListOf())
    private val historyMutex = Mutex()

    /** Tracks which driver state event we last processed (AtoB chain integrity). */
    private var lastReceivedDriverStateId: String? = null

    /** How many driver history actions have been processed (prevents re-processing on re-delivery). */
    private var lastProcessedDriverActionCount = 0

    /**
     * Informational phase for Kind 30181. The driver ignores this field (it processes the
     * history array), but it aids debugging and cross-app log correlation.
     */
    private var currentRiderPhase = RiderRideStateEvent.Phase.AWAITING_DRIVER

    // ── Event deduplication ───────────────────────────────────────────────────

    /** Prevents stale queued driver state events from affecting new rides. */
    private val processedDriverStateEventIds = mutableSetOf<String>()

    /** Prevents stale queued cancellation events from affecting new rides. */
    private val processedCancellationEventIds = mutableSetOf<String>()

    // ── Active ride context ───────────────────────────────────────────────────

    /** Acceptance eventId captured when onAcceptanceReceived() is called; cleared on reset(). */
    private var currentAcceptanceEventId: String? = null

    /** Confirmation eventId set when Kind 3175 is published successfully. */
    private var activeConfirmationEventId: String? = null

    private var activePaymentPath: PaymentPath? = null
    private var activePreimage: String? = null
    private var activePaymentHash: String? = null
    private var activeEscrowToken: String? = null
    private var activePickupPin: String? = null
    private var activePinAttempts = 0
    private var activePinVerified = false
    private var activeDestination: Location? = null
    private var driverDepositInvoice: String? = null

    // ── Escrow retry state ────────────────────────────────────────────────────

    private var pendingRetryAcceptance: RideAcceptanceData? = null
    private var pendingRetryInputs: ConfirmationInputs? = null

    // ── Jobs ──────────────────────────────────────────────────────────────────

    private var escrowRetryDeadlineJob: Job? = null
    private var postConfirmAckTimeoutJob: Job? = null
    private var bridgePendingPollJob: Job? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Begin the ride confirmation flow for a driver acceptance.
     *
     * Atomically gates via [confirmationInFlight] so only one coroutine proceeds on multi-relay
     * delivery. Runs HTLC lock (SAME_MINT only), publishes Kind 3175, then emits
     * [PaymentEvent.Confirmed] or [PaymentEvent.EscrowLockFailed].
     *
     * @param acceptance Decoded driver acceptance (Kind 3174).
     * @param inputs     Ride inputs captured from ViewModel state at acceptance time.
     */
    fun onAcceptanceReceived(acceptance: RideAcceptanceData, inputs: ConfirmationInputs) {
        if (!confirmationInFlight.compareAndSet(false, true)) {
            Log.d(TAG, "Ignoring duplicate onAcceptanceReceived — confirmation already in flight")
            return
        }
        currentAcceptanceEventId = acceptance.eventId
        activePaymentHash = inputs.paymentHash
        activePreimage = inputs.preimage
        activeDestination = inputs.destination
        pendingRetryAcceptance = acceptance
        pendingRetryInputs = inputs
        runConfirmation(acceptance, inputs)
    }

    /**
     * Retry the HTLC escrow lock after the user taps "Retry" in the escrow failure dialog.
     *
     * Cancels the auto-cancel deadline timer, clears the dialog state, and re-runs the
     * confirmation flow. The CAS gate was reset when the lock failed, so this call succeeds.
     * A rapid double-tap on "Retry" is guarded by the same CAS: the second tap sees
     * [confirmationInFlight] already true and becomes a no-op.
     *
     * No-op if there is no pending retry acceptance (guards against stale UI interactions).
     */
    fun retryEscrowLock() {
        val acceptance = pendingRetryAcceptance ?: return
        val inputs = pendingRetryInputs ?: return
        if (!confirmationInFlight.compareAndSet(false, true)) {
            Log.d(TAG, "Ignoring duplicate retryEscrowLock — confirmation already in flight")
            return
        }
        escrowRetryDeadlineJob?.cancel()
        escrowRetryDeadlineJob = null
        runConfirmation(acceptance, inputs)
    }

    /**
     * Notify the coordinator that the ride was cancelled (by rider or driver).
     *
     * Releases HTLC protection so the HTLC can be auto-refunded after expiry, cancels all
     * internal jobs, and clears ride context. Safe to call multiple times.
     *
     * @param paymentHash Override for the payment hash; defaults to [activePaymentHash].
     */
    fun onRideCancelled(paymentHash: String? = activePaymentHash) {
        paymentHash?.let { walletService?.clearHtlcRideProtected(it) }
        cancelAllJobs()
        resetInternalState()
    }

    /**
     * Process an incoming driver ride state event (Kind 30180).
     *
     * Deduplicates by eventId, validates the event's confirmation ID against [confirmationEventId],
     * then processes only new history actions (those beyond [lastProcessedDriverActionCount]).
     * Dispatches PIN submissions, status updates, and deposit invoice shares as [PaymentEvent]
     * emissions. See CLAUDE.md "AtoB Pattern" for driver-as-source-of-truth design.
     *
     * @param driverState         Decoded driver ride state.
     * @param confirmationEventId Expected confirmation event ID for the current ride.
     * @param driverPubKey        Driver's Nostr identity pubkey (hex).
     */
    fun onDriverRideStateReceived(
        driverState: DriverRideStateData,
        confirmationEventId: String,
        driverPubKey: String
    ) {
        if (driverState.eventId in processedDriverStateEventIds) {
            Log.w(TAG, "Ignoring already-processed driver state: ${driverState.eventId.take(8)}")
            return
        }

        lastReceivedDriverStateId = driverState.eventId

        if (driverState.confirmationEventId != confirmationEventId) {
            Log.w(
                TAG, "Driver state confId mismatch: " +
                    "event=${driverState.confirmationEventId.take(8)}, " +
                    "expected=${confirmationEventId.take(8)}"
            )
            return
        }

        processedDriverStateEventIds.add(driverState.eventId)

        val newActions = driverState.history.drop(lastProcessedDriverActionCount)
        lastProcessedDriverActionCount = driverState.history.size

        if (newActions.isEmpty()) {
            Log.d(TAG, "No new actions in driver state ${driverState.eventId.take(8)}")
            return
        }

        Log.d(TAG, "Processing ${newActions.size} new driver action(s)")
        newActions.forEach { action ->
            when (action) {
                is DriverRideAction.Status ->
                    handleDriverStatus(action, driverState, confirmationEventId, driverPubKey)
                is DriverRideAction.PinSubmit ->
                    handlePinSubmission(action, confirmationEventId, driverPubKey)
                is DriverRideAction.Settlement ->
                    Log.d(TAG, "Settlement confirmation: ${action.settledAmount} sats")
                is DriverRideAction.DepositInvoiceShare ->
                    handleDepositInvoice(action)
            }
        }
    }

    /**
     * Mark a cancellation event (Kind 3179) as processed to prevent re-handling across rides.
     *
     * The ViewModel is responsible for validation (confirmation ID match, active stage check)
     * before calling this method.
     *
     * @param cancellationEventId The Kind 3179 event's eventId.
     */
    fun markCancellationProcessed(cancellationEventId: String) {
        processedCancellationEventIds.add(cancellationEventId)
    }

    /**
     * Returns true if this cancellation event has already been processed.
     * Used by ViewModel for deduplication before calling [onRideCancelled].
     */
    fun isCancellationProcessed(cancellationEventId: String): Boolean =
        cancellationEventId in processedCancellationEventIds

    /**
     * Publish a location reveal action to the driver via Kind 30181.
     *
     * Mutex-safe: serialised with concurrent history updates from PIN verification and preimage
     * share to prevent NIP-33 timestamp collisions. Caller should delay 1.1 s before calling
     * if a previous Kind 30181 event was just published.
     *
     * @param confirmationEventId Ride confirmation event ID.
     * @param driverPubKey        Driver's Nostr identity pubkey.
     * @param locationType        [RiderRideStateEvent.LocationType.PICKUP] or [DESTINATION].
     * @param location            Precise location to encrypt and share.
     * @return                    Published event ID, or null on failure.
     */
    suspend fun revealLocation(
        confirmationEventId: String,
        driverPubKey: String,
        locationType: String,
        location: Location
    ): String? {
        val encrypted = nostrService.encryptLocationForRiderState(location, driverPubKey)
        if (encrypted == null) {
            Log.e(TAG, "Failed to encrypt location for $locationType reveal")
            return null
        }
        val action = RiderRideStateEvent.createLocationRevealAction(locationType, encrypted)
        return historyMutex.withLock {
            riderStateHistory.add(action)
            nostrService.publishRiderRideState(
                confirmationEventId = confirmationEventId,
                driverPubKey = driverPubKey,
                currentPhase = currentRiderPhase,
                history = riderStateHistory.toList(),
                lastTransitionId = lastReceivedDriverStateId
            )
        }
    }

    /**
     * Restore in-progress state after process death. Call when the ViewModel restores a
     * persisted ride session so the coordinator's deduplication and PIN state align.
     *
     * @param confirmationEventId  Persisted confirmation event ID.
     * @param paymentPath          Persisted payment path.
     * @param paymentHash          Persisted payment hash.
     * @param preimage             Persisted preimage.
     * @param escrowToken          Persisted escrow token (null for non-SAME_MINT rides).
     * @param pickupPin            Persisted pickup PIN (null if already verified).
     * @param pinVerified          Whether PIN was already verified before process death.
     * @param destination          Destination location for post-PIN reveal.
     * @param postConfirmDeadlineMs Persisted post-confirm ack deadline, or 0 to skip timeout.
     * @param lastProcessedDriverActionCount
     *     Persisted count of driver actions processed pre-death. Kind 30180 is parametric
     *     replaceable, so on re-delivery after restart the event will often carry a new eventId
     *     (not blocked by the dedup set). Seeding this counter prevents replay of history actions
     *     (EN_ROUTE_PICKUP → ARRIVED etc.) that produces UI flicker.
     */
    fun restoreRideState(
        confirmationEventId: String,
        paymentPath: PaymentPath,
        paymentHash: String?,
        preimage: String?,
        escrowToken: String?,
        pickupPin: String?,
        pinVerified: Boolean,
        destination: Location?,
        postConfirmDeadlineMs: Long = 0L,
        lastProcessedDriverActionCount: Int = 0
    ) {
        activeConfirmationEventId = confirmationEventId
        currentAcceptanceEventId = confirmationEventId  // best-effort (acceptance not persisted)
        activePaymentPath = paymentPath
        activePaymentHash = paymentHash
        activePreimage = preimage
        activeEscrowToken = escrowToken
        activePickupPin = pickupPin
        activePinVerified = pinVerified
        activeDestination = destination
        this.lastProcessedDriverActionCount = lastProcessedDriverActionCount
        if (postConfirmDeadlineMs > System.currentTimeMillis()) {
            startPostConfirmAckTimeout(postConfirmDeadlineMs, confirmationEventId)
        }
        Log.d(TAG, "Restored ride state for confirmation ${confirmationEventId.take(8)}")
    }

    /**
     * The count of driver actions processed so far for the active ride. The ViewModel persists
     * this in the saved-ride JSON and passes it back via [restoreRideState] on process restart
     * to prevent re-processing the replayed Kind 30180 history.
     */
    fun getLastProcessedDriverActionCount(): Int = lastProcessedDriverActionCount

    /**
     * Resume the poll for a cross-mint bridge payment that was in flight when the app was
     * process-killed. The ViewModel finds the pending [bridgePaymentId] via
     * `walletService.getInProgressBridgePayments()` during restore and hands it back here so
     * the coordinator can continue polling the mint until the melt quote resolves (PAID /
     * UNPAID / 10 min timeout).
     *
     * Safe to call multiple times; each call replaces any prior poll job for the same ride.
     */
    fun resumeBridgePoll(bridgePaymentId: String, rideId: String, driverPubKey: String) {
        startBridgePendingPoll(bridgePaymentId, rideId, driverPubKey)
    }

    /**
     * Clear all ride context and cancel in-flight jobs. Call at every ride boundary
     * (completion, cancellation, new ride start).
     *
     * Does NOT cancel [scope] — the coordinator remains usable for the next ride.
     */
    fun reset() {
        cancelAllJobs()
        resetInternalState()
    }

    /**
     * Permanently tear down the coordinator. Call from the owning ViewModel's `onCleared()`.
     */
    fun destroy() {
        cancelAllJobs()
    }

    // ── Private: confirmation flow ────────────────────────────────────────────

    private fun runConfirmation(acceptance: RideAcceptanceData, inputs: ConfirmationInputs) {
        scope.launch {
            try {
                val pickup = inputs.pickupLocation
                val driverAlreadyClose = inputs.driverApproxLocation
                    ?.let { pickup.isWithinMile(it) } == true

                // RoadFlare rides use precise pickup immediately (trusted driver network).
                // Non-RoadFlare: send approximate until driver is within 1 mile (revealPrecisePickup).
                val pickupToSend = if (driverAlreadyClose || inputs.isRoadflareRide) {
                    pickup
                } else {
                    pickup.approximate()
                }

                val paymentMethod = acceptance.paymentMethod ?: "cashu"
                val paymentPath = PaymentPath.determine(
                    inputs.riderMintUrl, acceptance.mintUrl, paymentMethod
                )
                Log.d(TAG, "PaymentPath: $paymentPath (rider=${inputs.riderMintUrl}, driver=${acceptance.mintUrl})")

                // Cashu NUT-11 requires a compressed pubkey (33 bytes = 66 hex chars).
                // If the driver sent an x-only pubkey (32 bytes = 64 hex), prefix with "02".
                val rawDriverKey = acceptance.walletPubKey ?: acceptance.driverPubKey
                val driverP2pkKey = if (rawDriverKey.length == 64) "02$rawDriverKey" else rawDriverKey

                val rideCorrelationId = acceptance.eventId.take(8)
                var escrowFailureMsg: String? = null

                val escrowToken: String? = if (paymentPath == PaymentPath.SAME_MINT) {
                    val paymentHash = inputs.paymentHash
                    val fareAmount = inputs.fareAmountSats
                    if (paymentHash != null && fareAmount > 0) {
                        try {
                            Log.d(TAG, "[$rideCorrelationId] Locking HTLC: fareAmount=$fareAmount, hash=${paymentHash.take(16)}...")
                            when (val result = walletService?.lockForRide(
                                amountSats = fareAmount,
                                paymentHash = paymentHash,
                                driverPubKey = driverP2pkKey,
                                expirySeconds = HTLC_EXPIRY_SECONDS,
                                preimage = inputs.preimage
                            )) {
                                is LockResult.Success -> {
                                    Log.d(TAG, "[$rideCorrelationId] HTLC lock SUCCESS")
                                    result.escrowLock.htlcToken
                                }
                                is LockResult.Failure -> {
                                    Log.e(TAG, "[$rideCorrelationId] HTLC lock FAILED: ${result.message}")
                                    escrowFailureMsg = escrowFailureMessage(result)
                                    null
                                }
                                null -> {
                                    Log.e(TAG, "[$rideCorrelationId] WalletService unavailable")
                                    escrowFailureMsg = "Wallet not available."
                                    null
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "[$rideCorrelationId] Exception in lockForRide", e)
                            escrowFailureMsg = "Payment setup failed unexpectedly."
                            null
                        }
                    } else {
                        Log.w(TAG, "[$rideCorrelationId] Cannot lock escrow: hash=$paymentHash, fare=$fareAmount")
                        escrowFailureMsg = "Payment information missing."
                        null
                    }
                } else null  // Cross-mint and fiat rides skip escrow lock

                // SAME_MINT with no escrow token: block the ride — do NOT publish confirmation.
                if (escrowToken == null && paymentPath == PaymentPath.SAME_MINT) {
                    // Stale-ride guard: if acceptance changed while we were in lockForRide(), ignore.
                    if (currentAcceptanceEventId != acceptance.eventId) {
                        Log.w(TAG, "[$rideCorrelationId] Stale escrow failure — ride already changed")
                        return@launch
                    }
                    Log.e(TAG, "[$rideCorrelationId] ESCROW LOCK FAILED — blocking confirmation")
                    confirmationInFlight.set(false)  // Allow retry
                    val deadline = System.currentTimeMillis() + ESCROW_RETRY_DEADLINE_MS
                    _events.emit(PaymentEvent.EscrowLockFailed(escrowFailureMsg, deadline))
                    startEscrowRetryDeadline(deadline)
                    return@launch
                }

                val eventId = nostrService.confirmRide(
                    acceptance = acceptance,
                    precisePickup = pickupToSend,
                    paymentHash = inputs.paymentHash,
                    escrowToken = escrowToken
                )

                if (eventId != null) {
                    // Post-suspension stale-ride guard. Two cases that share the same symptom
                    // (currentAcceptanceEventId != acceptance.eventId) but need different cleanup:
                    //  - Case 1 (cross-ride):  a NEW ride's acceptance is active → targeted cancel
                    //                          only; never run author-wide NIP-09 cleanup because
                    //                          it would delete the new ride's live events.
                    //  - Case 2 (same-ride cancel): the rider cancelled and coordinator state was
                    //                          reset (currentAcceptanceEventId == null) → safe to
                    //                          run author-wide cleanup; no other ride is active.
                    if (currentAcceptanceEventId != acceptance.eventId) {
                        if (currentAcceptanceEventId == null) {
                            Log.w(TAG, "[$rideCorrelationId] Ride cancelled during confirmRide() suspension")
                            _events.emit(PaymentEvent.ConfirmationCancelledBySelf(eventId, acceptance.driverPubKey))
                        } else {
                            Log.w(TAG, "[$rideCorrelationId] Stale confirmation — acceptance changed post-suspend")
                            _events.emit(PaymentEvent.ConfirmationStale(eventId, acceptance.driverPubKey))
                        }
                        // CAS lock state at this point:
                        //  - Case 1 (cross-ride): confirmationInFlight stays true; the new ride
                        //    acquired it via its own compareAndSet, and we must NOT clear it here.
                        //  - Case 2 (self-cancel): resetInternalState() already set
                        //    confirmationInFlight to false when the rider cancelled — so it is
                        //    already clear. Don't touch it either way.
                        return@launch
                    }

                    // Protect HTLC from auto-refund now that the ride is confirmed.
                    // If the app dies before saveRideState(), the HTLC is still protected
                    // and funds are safe until the ride is resolved.
                    inputs.paymentHash?.let { walletService?.setHtlcRideProtected(it) }

                    val pin = String.format("%04d", kotlin.random.Random.nextInt(10000))
                    activeConfirmationEventId = eventId
                    activePaymentPath = paymentPath
                    activeEscrowToken = escrowToken
                    activePickupPin = pin
                    activePinAttempts = 0
                    activePinVerified = false

                    val postConfirmDeadline = System.currentTimeMillis() + POST_CONFIRM_ACK_TIMEOUT_MS

                    Log.d(TAG, "[$rideCorrelationId] Ride confirmed: ${eventId.take(8)}, PIN generated")
                    _events.emit(
                        PaymentEvent.Confirmed(
                            confirmationEventId = eventId,
                            pickupPin = pin,
                            paymentPath = paymentPath,
                            driverMintUrl = acceptance.mintUrl,
                            postConfirmDeadlineMs = postConfirmDeadline,
                            escrowToken = escrowToken,
                            precisePickupShared = driverAlreadyClose || inputs.isRoadflareRide
                        )
                    )

                    startPostConfirmAckTimeout(postConfirmDeadline, eventId)
                } else {
                    confirmationInFlight.set(false)
                    _events.emit(PaymentEvent.ConfirmationFailed("Failed to confirm ride"))
                }
            } catch (e: CancellationException) {
                throw e  // Preserve structured concurrency
            } catch (e: Exception) {
                Log.e(TAG, "Confirmation failed unexpectedly: ${e.message}", e)
                // Only reset the CAS lock if this coroutine still owns the current ride.
                // A stale Ride A coroutine throwing must NOT clear the lock that Ride B holds.
                if (currentAcceptanceEventId == acceptance.eventId) {
                    confirmationInFlight.set(false)
                    _events.emit(PaymentEvent.ConfirmationFailed("Failed to confirm ride: ${e.message}"))
                }
            }
        }
    }

    // ── Private: driver state handling ───────────────────────────────────────

    private fun handleDriverStatus(
        action: DriverRideAction.Status,
        driverState: DriverRideStateData,
        confirmationEventId: String,
        driverPubKey: String
    ) {
        // First driver status cancels the post-confirm ack timeout (driver acknowledged the ride).
        postConfirmAckTimeoutJob?.cancel()
        postConfirmAckTimeoutJob = null

        Log.d(TAG, "Driver status: ${action.status}")

        when (action.status) {
            DriverStatusType.ARRIVED -> {
                currentRiderPhase = RiderRideStateEvent.Phase.AWAITING_PIN
                scope.launch {
                    _events.emit(PaymentEvent.DriverStatusUpdated(action.status, driverState, confirmationEventId))
                }
            }
            DriverStatusType.IN_PROGRESS -> {
                currentRiderPhase = RiderRideStateEvent.Phase.IN_RIDE
                scope.launch {
                    _events.emit(PaymentEvent.DriverStatusUpdated(action.status, driverState, confirmationEventId))
                }
            }
            DriverStatusType.COMPLETED -> {
                scope.launch { handleCompletion(driverState, driverPubKey) }
            }
            DriverStatusType.CANCELLED -> {
                // HTLC protection released so wallet can auto-refund after expiry.
                activePaymentHash?.let { walletService?.clearHtlcRideProtected(it) }
                scope.launch { _events.emit(PaymentEvent.DriverCancelled(null)) }
            }
            else -> {
                // EN_ROUTE_PICKUP and any future statuses: ViewModel derives UI stage.
                scope.launch {
                    _events.emit(PaymentEvent.DriverStatusUpdated(action.status, driverState, confirmationEventId))
                }
            }
        }
    }

    private suspend fun handleCompletion(driverState: DriverRideStateData, driverPubKey: String) {
        val finalFareSats = driverState.finalFare?.toLong()

        // Determine HTLC outcome from driver's claimSuccess field.
        val completedAction = driverState.history
            .filterIsInstance<DriverRideAction.Status>()
            .lastOrNull { it.status == DriverStatusType.COMPLETED }
        val claimSuccess = completedAction?.claimSuccess

        val paymentHash = activePaymentHash
        if (paymentHash != null) {
            when {
                claimSuccess == true -> {
                    val marked = walletService?.markHtlcClaimedByPaymentHash(paymentHash) ?: false
                    if (marked) Log.d(TAG, "HTLC marked claimed for ride completion")
                }
                claimSuccess == false -> {
                    // Driver claim failed — unlock so wallet can refund the rider.
                    walletService?.clearHtlcRideProtected(paymentHash)
                    Log.w(TAG, "Driver claim failed — HTLC unlocked for rider refund")
                }
                else -> {
                    // Legacy driver without claimSuccess field.
                    // Conservative: for SAME_MINT rides keep LOCKED (money safety).
                    // Cross-mint / fiat: no HTLC to worry about.
                    Log.w(TAG, "Legacy driver (no claimSuccess) — HTLC left locked for safety")
                }
            }
        }

        try {
            walletService?.refreshBalance()
            Log.d(TAG, "Wallet balance refreshed after ride completion")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh balance after completion: ${e.message}")
        }

        _events.emit(PaymentEvent.DriverCompleted(finalFareSats, claimSuccess))
    }

    private fun handlePinSubmission(
        action: DriverRideAction.PinSubmit,
        confirmationEventId: String,
        driverPubKey: String
    ) {
        // After app restart, subscription replays full history — skip already-verified PIN.
        if (activePinVerified) {
            Log.d(TAG, "PIN already verified, ignoring duplicate submission")
            return
        }
        val expectedPin = activePickupPin ?: return

        scope.launch {
            val decryptedPin = nostrService.decryptPinFromDriverState(action.pinEncrypted, driverPubKey)
            if (decryptedPin == null) {
                Log.e(TAG, "Failed to decrypt PIN from driver state")
                return@launch
            }

            Log.d(TAG, "Received PIN submission from driver (attempt ${activePinAttempts + 1})")
            val newAttempts = activePinAttempts + 1
            val isCorrect = decryptedPin == expectedPin

            // Publish PIN verification result before branching (driver needs ACK immediately).
            val verificationEventId = publishPinVerification(
                confirmationEventId = confirmationEventId,
                driverPubKey = driverPubKey,
                verified = isCorrect,
                attempt = newAttempts
            )

            if (isCorrect) {
                // CRITICAL: Set verified flag immediately to prevent double-processing if
                // this action arrives a second time (duplicate relay delivery).
                activePinVerified = true

                // Delay to ensure distinct NIP-33 timestamp from PIN verification event.
                delay(DISTINCT_TIMESTAMP_DELAY_MS)

                // Branch based on payment path.
                when (activePaymentPath) {
                    PaymentPath.SAME_MINT -> {
                        val preimage = activePreimage
                        val escrowToken = activeEscrowToken
                        Log.d(TAG, "SAME_MINT: sharing preimage, preimage=${preimage != null}")
                        if (preimage != null) {
                            sharePreimageWithDriver(confirmationEventId, driverPubKey, preimage, escrowToken)
                        } else {
                            Log.w(TAG, "SAME_MINT PIN correct but no preimage — escrow was not set up")
                        }
                    }
                    PaymentPath.CROSS_MINT -> {
                        val invoice = driverDepositInvoice
                        Log.d(TAG, "CROSS_MINT: executing bridge, invoice=${invoice != null}")
                        if (invoice != null) {
                            executeBridgePayment(confirmationEventId, driverPubKey, invoice)
                        } else {
                            Log.w(TAG, "CROSS_MINT PIN correct but no deposit invoice received from driver")
                        }
                    }
                    PaymentPath.FIAT_CASH -> {
                        Log.d(TAG, "FIAT_CASH: no digital payment required")
                    }
                    PaymentPath.NO_PAYMENT -> {
                        Log.w(TAG, "NO_PAYMENT: ride proceeding without payment setup")
                    }
                    null -> Log.w(TAG, "Unknown payment path — cannot process payment after PIN")
                }

                _events.emit(PaymentEvent.PinVerified(confirmationEventId, driverPubKey))

                // Delay before destination reveal to ensure distinct NIP-33 timestamp.
                delay(DISTINCT_TIMESTAMP_DELAY_MS)

                // Reveal precise destination to driver now that ride is starting.
                val dest = activeDestination
                if (dest != null) {
                    val eventId = revealLocation(
                        confirmationEventId = confirmationEventId,
                        driverPubKey = driverPubKey,
                        locationType = RiderRideStateEvent.LocationType.DESTINATION,
                        location = dest
                    )
                    if (eventId != null) {
                        Log.d(TAG, "Revealed precise destination: ${eventId.take(8)}")
                    } else {
                        Log.e(TAG, "Failed to reveal precise destination")
                    }
                }
            } else {
                activePinAttempts = newAttempts
                Log.w(TAG, "PIN incorrect! Attempt $newAttempts of $MAX_PIN_ATTEMPTS")

                if (newAttempts >= MAX_PIN_ATTEMPTS) {
                    Log.e(TAG, "Max PIN attempts reached — cancelling ride for security")
                    activePaymentHash?.let { walletService?.clearHtlcRideProtected(it) }
                    _events.emit(PaymentEvent.MaxPinAttemptsReached)
                } else {
                    _events.emit(PaymentEvent.PinRejected(newAttempts, MAX_PIN_ATTEMPTS))
                }
            }
        }
    }

    private fun handleDepositInvoice(action: DriverRideAction.DepositInvoiceShare) {
        Log.d(TAG, "Storing deposit invoice for bridge: ${action.invoice.take(20)}... (${action.amount} sats)")
        driverDepositInvoice = action.invoice
        scope.launch { _events.emit(PaymentEvent.DepositInvoiceReceived(action.invoice, action.amount)) }
    }

    // ── Private: Kind 30181 publishing helpers ────────────────────────────────

    private suspend fun publishPinVerification(
        confirmationEventId: String,
        driverPubKey: String,
        verified: Boolean,
        attempt: Int
    ): String? {
        val pinAction = RiderRideStateEvent.createPinVerifyAction(
            verified = verified,
            attempt = attempt
        )
        return historyMutex.withLock {
            riderStateHistory.add(pinAction)
            if (verified) {
                currentRiderPhase = RiderRideStateEvent.Phase.VERIFIED
            }
            nostrService.publishRiderRideState(
                confirmationEventId = confirmationEventId,
                driverPubKey = driverPubKey,
                currentPhase = currentRiderPhase,
                history = riderStateHistory.toList(),
                lastTransitionId = lastReceivedDriverStateId
            )
        }
    }

    private suspend fun sharePreimageWithDriver(
        confirmationEventId: String,
        driverPubKey: String,
        preimage: String,
        escrowToken: String? = null
    ) {
        try {
            val encryptedPreimage = nostrService.encryptForUser(preimage, driverPubKey)
            if (encryptedPreimage == null) {
                Log.e(TAG, "Failed to encrypt preimage for driver")
                return
            }

            val encryptedEscrowToken = escrowToken?.let {
                nostrService.encryptForUser(it, driverPubKey)
            }

            val preimageAction = RiderRideStateEvent.createPreimageShareAction(
                preimageEncrypted = encryptedPreimage,
                escrowTokenEncrypted = encryptedEscrowToken
            )

            val eventId = historyMutex.withLock {
                riderStateHistory.add(preimageAction)
                nostrService.publishRiderRideState(
                    confirmationEventId = confirmationEventId,
                    driverPubKey = driverPubKey,
                    currentPhase = currentRiderPhase,
                    history = riderStateHistory.toList(),
                    lastTransitionId = lastReceivedDriverStateId
                )
            }

            if (eventId != null) {
                Log.d(TAG, "Shared encrypted preimage with driver")
                if (escrowToken != null) Log.d(TAG, "Also shared HTLC escrow token")
            } else {
                Log.e(TAG, "Failed to publish preimage share event")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sharing preimage: ${e.message}", e)
        }
    }

    // ── Private: cross-mint bridge payment ───────────────────────────────────

    private suspend fun executeBridgePayment(
        confirmationEventId: String,
        driverPubKey: String,
        depositInvoice: String
    ) {
        Log.d(TAG, "=== EXECUTING CROSS-MINT BRIDGE === invoice=${depositInvoice.take(30)}...")
        _events.emit(PaymentEvent.BridgeInProgress)

        try {
            val result = walletService?.bridgePayment(depositInvoice, rideId = confirmationEventId)

            when {
                result?.success == true -> {
                    if (result.error != null) {
                        Log.w(TAG, "Bridge succeeded with wallet sync warning: ${result.error}")
                    }
                    Log.d(TAG, "Bridge payment successful: ${result.amountSats} sats + ${result.feesSats} fees")

                    bridgePendingPollJob?.cancel()
                    bridgePendingPollJob = null

                    val rawPreimage = result.preimage
                    if (rawPreimage == null) {
                        Log.e(TAG, "[BRIDGE_PUBLISH_FAIL] Bridge succeeded but no preimage returned")
                        return
                    }

                    val encryptedPreimage = nostrService.encryptForUser(rawPreimage, driverPubKey)
                    if (encryptedPreimage == null) {
                        Log.e(TAG, "[BRIDGE_PUBLISH_FAIL] Failed to encrypt bridge preimage")
                        return
                    }

                    val bridgeAction = RiderRideStateEvent.createBridgeCompleteAction(
                        preimageEncrypted = encryptedPreimage,
                        amountSats = result.amountSats,
                        feesSats = result.feesSats
                    )

                    val eventId = historyMutex.withLock {
                        riderStateHistory.add(bridgeAction)
                        nostrService.publishRiderRideState(
                            confirmationEventId = confirmationEventId,
                            driverPubKey = driverPubKey,
                            currentPhase = currentRiderPhase,
                            history = riderStateHistory.toList(),
                            lastTransitionId = lastReceivedDriverStateId
                        )
                    }

                    if (eventId != null) {
                        Log.d(TAG, "Published BridgeComplete action: ${eventId.take(8)}")
                        _events.emit(PaymentEvent.BridgeCompleted(result.amountSats))
                    } else {
                        Log.e(TAG, "Failed to publish BridgeComplete action")
                    }
                }

                result?.isPending == true -> {
                    // Lightning still routing — do NOT cancel. Poll until resolved.
                    Log.w(TAG, "Bridge payment PENDING — Lightning still routing. NOT cancelling ride.")
                    _events.emit(PaymentEvent.BridgePendingStarted)

                    val currentRideId = confirmationEventId
                    val bridgePaymentId = walletService?.getInProgressBridgePayments()
                        ?.find { it.rideId == currentRideId }?.id

                    if (bridgePaymentId != null) {
                        startBridgePendingPoll(bridgePaymentId, currentRideId, driverPubKey)
                    } else {
                        Log.e(TAG, "Bridge pending but no payment record found")
                    }
                }

                else -> {
                    Log.e(TAG, "Bridge payment failed: ${result?.error} — emitting failure")
                    bridgePendingPollJob?.cancel()
                    bridgePendingPollJob = null
                    _events.emit(PaymentEvent.BridgeFailed(result?.error ?: "Unknown error"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during bridge payment: ${e.message}", e)
            bridgePendingPollJob?.cancel()
            bridgePendingPollJob = null
            _events.emit(PaymentEvent.BridgeFailed(e.message ?: "Unknown error"))
        }
    }

    private fun startBridgePendingPoll(
        bridgePaymentId: String,
        rideId: String,
        driverPubKey: String
    ) {
        bridgePendingPollJob?.cancel()
        bridgePendingPollJob = scope.launch {
            val startMs = System.currentTimeMillis()
            while (isActive && System.currentTimeMillis() - startMs < BRIDGE_POLL_TIMEOUT_MS) {
                delay(BRIDGE_POLL_INTERVAL_MS)

                // Abort if the ride changed (user cancelled or new ride started).
                if (activeConfirmationEventId != rideId) {
                    Log.d(TAG, "Bridge poll: ride changed, stopping poll")
                    return@launch
                }

                val quote = walletService?.checkBridgeMeltQuote(bridgePaymentId)
                Log.d(TAG, "Bridge poll: state=${quote?.state}, preimage=${quote?.paymentPreimage?.take(8)}")

                when (quote?.state) {
                    MeltQuoteState.PAID -> {
                        Log.d(TAG, "Bridge poll: PAID — handling success")
                        handleBridgeSuccessFromPoll(bridgePaymentId, quote.paymentPreimage, rideId, driverPubKey)
                        return@launch
                    }
                    MeltQuoteState.UNPAID -> {
                        Log.e(TAG, "Bridge poll: UNPAID/expired — emitting failure")
                        walletService?.walletStorage?.updateBridgePaymentStatus(
                            bridgePaymentId, BridgePaymentStatus.FAILED,
                            errorMessage = "Lightning route expired"
                        )
                        _events.emit(PaymentEvent.BridgeFailed("Lightning route expired"))
                        return@launch
                    }
                    MeltQuoteState.PENDING, null -> { /* still pending — continue polling */ }
                }
            }

            // 10-minute timeout.
            Log.w(TAG, "Bridge poll: 10-minute timeout")
            walletService?.walletStorage?.updateBridgePaymentStatus(
                bridgePaymentId, BridgePaymentStatus.FAILED,
                errorMessage = "Payment timed out after 10 minutes"
            )
            _events.emit(PaymentEvent.BridgeFailed("Payment timed out after 10 minutes"))
        }
    }

    private suspend fun handleBridgeSuccessFromPoll(
        bridgePaymentId: String,
        preimage: String?,
        rideId: String,
        driverPubKey: String
    ) {
        val payment = walletService?.getBridgePayment(bridgePaymentId)
        Log.d(TAG, "Bridge resolved via poll: ${payment?.amountSats} sats, preimage=${preimage?.take(8)}")

        bridgePendingPollJob?.cancel()
        bridgePendingPollJob = null

        walletService?.walletStorage?.updateBridgePaymentStatus(
            bridgePaymentId, BridgePaymentStatus.COMPLETE,
            lightningPreimage = preimage
        )

        if (preimage == null) {
            Log.e(TAG, "[BRIDGE_PUBLISH_FAIL] Poll success but no preimage")
            return
        }

        val encryptedPreimage = nostrService.encryptForUser(preimage, driverPubKey)
        if (encryptedPreimage == null) {
            Log.e(TAG, "[BRIDGE_PUBLISH_FAIL] Failed to encrypt preimage from poll")
            return
        }

        val bridgeAction = RiderRideStateEvent.createBridgeCompleteAction(
            preimageEncrypted = encryptedPreimage,
            amountSats = payment?.amountSats ?: 0,
            feesSats = payment?.feeReserveSats ?: 0
        )

        val eventId = historyMutex.withLock {
            riderStateHistory.add(bridgeAction)
            nostrService.publishRiderRideState(
                confirmationEventId = rideId,
                driverPubKey = driverPubKey,
                currentPhase = currentRiderPhase,
                history = riderStateHistory.toList(),
                lastTransitionId = lastReceivedDriverStateId
            )
        }

        if (eventId != null) {
            Log.d(TAG, "Published BridgeComplete from poll: ${eventId.take(8)}")
            _events.emit(PaymentEvent.BridgeCompleted(payment?.amountSats ?: 0))
        } else {
            Log.e(TAG, "Failed to publish BridgeComplete from poll")
        }
    }

    // ── Private: timeout management ───────────────────────────────────────────

    private fun startEscrowRetryDeadline(deadlineMs: Long) {
        escrowRetryDeadlineJob?.cancel()
        escrowRetryDeadlineJob = scope.launch {
            val delayMs = deadlineMs - System.currentTimeMillis()
            if (delayMs > 0) delay(delayMs)
            _events.emit(PaymentEvent.EscrowRetryDeadlineExpired)
            Log.w(TAG, "Escrow retry deadline expired")
        }
    }

    private fun startPostConfirmAckTimeout(deadlineMs: Long, expectedConfirmationId: String) {
        postConfirmAckTimeoutJob?.cancel()
        postConfirmAckTimeoutJob = scope.launch {
            val delayMs = deadlineMs - System.currentTimeMillis()
            if (delayMs > 0) delay(delayMs)
            // Only emit if the ride hasn't changed or been cancelled since the timeout was set.
            if (activeConfirmationEventId == expectedConfirmationId) {
                Log.w(TAG, "No driver response after confirmation — emitting PostConfirmAckTimeout")
                _events.emit(PaymentEvent.PostConfirmAckTimeout)
            }
        }
    }

    // ── Private: helpers ──────────────────────────────────────────────────────

    private fun escrowFailureMessage(result: LockResult.Failure): String = when (result) {
        is LockResult.Failure.NotConnected ->
            "Wallet not connected. Please reconnect your wallet."
        is LockResult.Failure.InsufficientBalance ->
            "Not enough funds: need ${result.required} sats, have ${result.available} sats."
        is LockResult.Failure.ProofsSpent ->
            "Some wallet funds are already spent. Please refresh your wallet."
        is LockResult.Failure.MintUnreachable ->
            "Cannot reach payment mint. Check your connection."
        is LockResult.Failure.SwapFailed ->
            "Payment setup rejected by mint. Please try again."
        is LockResult.Failure.NoWalletKey ->
            "Wallet key not available. Reconnect your wallet."
        is LockResult.Failure.NipSyncNotInitialized ->
            "Wallet sync not ready. Wait a moment and try again."
        is LockResult.Failure.MintUrlNotAvailable ->
            "Mint URL not configured. Check wallet settings."
        is LockResult.Failure.VerificationFailed ->
            "Could not verify wallet funds. Please try again."
        is LockResult.Failure.Other ->
            "Payment setup failed: ${result.message}"
    }

    private fun cancelAllJobs() {
        escrowRetryDeadlineJob?.cancel()
        escrowRetryDeadlineJob = null
        postConfirmAckTimeoutJob?.cancel()
        postConfirmAckTimeoutJob = null
        bridgePendingPollJob?.cancel()
        bridgePendingPollJob = null
    }

    private fun resetInternalState() {
        confirmationInFlight.set(false)
        riderStateHistory.clear()
        lastReceivedDriverStateId = null
        lastProcessedDriverActionCount = 0
        currentRiderPhase = RiderRideStateEvent.Phase.AWAITING_DRIVER
        processedDriverStateEventIds.clear()
        processedCancellationEventIds.clear()
        currentAcceptanceEventId = null
        activeConfirmationEventId = null
        activePaymentPath = null
        activePreimage = null
        activePaymentHash = null
        activeEscrowToken = null
        activePickupPin = null
        activePinAttempts = 0
        activePinVerified = false
        activeDestination = null
        driverDepositInvoice = null
        pendingRetryAcceptance = null
        pendingRetryInputs = null
        Log.d(TAG, "Internal state reset")
    }
}
