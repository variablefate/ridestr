package com.ridestr.common.state

import com.ridestr.common.nostr.events.Location

/**
 * Events that trigger state transitions in the ride state machine.
 *
 * Each event corresponds to a user action or system event that can
 * cause the ride to progress to a new state. Events carry the pubkey
 * of whoever triggered them (inputterPubkey) for guard evaluation.
 */
sealed class RideEvent(
    /** The pubkey of whoever triggered this event */
    open val inputterPubkey: String
) {
    /**
     * Driver accepts a ride offer.
     * Transition: CREATED → ACCEPTED
     * Triggered by: Driver receiving Kind 3173, sending Kind 3174
     */
    data class Accept(
        override val inputterPubkey: String,
        val driverPubkey: String,
        val walletPubkey: String? = null,
        val mintUrl: String? = null,
        val paymentMethod: String? = null
    ) : RideEvent(inputterPubkey)

    /**
     * Rider confirms the ride with precise location.
     * Transition: ACCEPTED → CONFIRMED
     * Triggered by: Rider sending Kind 3175
     */
    data class Confirm(
        override val inputterPubkey: String,
        val confirmationEventId: String,
        val precisePickup: Location? = null,
        val escrowToken: String? = null,
        val paymentHash: String? = null
    ) : RideEvent(inputterPubkey)

    /**
     * Driver starts route to pickup.
     * Transition: CONFIRMED → EN_ROUTE
     * Triggered by: Driver beginning navigation
     */
    data class StartRoute(
        override val inputterPubkey: String
    ) : RideEvent(inputterPubkey)

    /**
     * Driver arrives at pickup location.
     * Transition: EN_ROUTE → ARRIVED
     * Triggered by: Driver marking arrival
     */
    data class Arrive(
        override val inputterPubkey: String
    ) : RideEvent(inputterPubkey)

    /**
     * Driver submits PIN for verification.
     * Does not cause transition, but updates context.
     * Triggered by: Driver entering PIN from rider
     */
    data class SubmitPin(
        override val inputterPubkey: String,
        val pinEncrypted: String
    ) : RideEvent(inputterPubkey)

    /**
     * Rider verifies the submitted PIN.
     * Transition: ARRIVED → IN_PROGRESS (if verified)
     * Triggered by: Rider's app processing PIN submission
     */
    data class VerifyPin(
        override val inputterPubkey: String,
        val verified: Boolean,
        val attempt: Int
    ) : RideEvent(inputterPubkey)

    /**
     * Driver starts the ride after PIN verification.
     * Transition: ARRIVED → IN_PROGRESS
     * Triggered by: Successful PIN verification
     */
    data class StartRide(
        override val inputterPubkey: String
    ) : RideEvent(inputterPubkey)

    /**
     * Driver completes the ride at destination.
     * Transition: IN_PROGRESS → COMPLETED
     * Triggered by: Driver marking ride complete
     */
    data class Complete(
        override val inputterPubkey: String,
        val finalFareSats: Long? = null
    ) : RideEvent(inputterPubkey)

    /**
     * Either party cancels the ride.
     * Transition: Any cancellable state → CANCELLED
     * Triggered by: Rider or driver initiating cancellation
     */
    data class Cancel(
        override val inputterPubkey: String,
        val reason: String? = null
    ) : RideEvent(inputterPubkey)

    /**
     * Rider shares preimage for HTLC settlement.
     * Does not cause state transition, but enables payment.
     * Triggered by: Rider after PIN verification (SAME_MINT path)
     */
    data class SharePreimage(
        override val inputterPubkey: String,
        val preimageEncrypted: String,
        val escrowTokenEncrypted: String? = null
    ) : RideEvent(inputterPubkey)

    /**
     * Cross-mint bridge payment completed.
     * Does not cause state transition, but confirms payment.
     * Triggered by: Rider after Lightning bridge payment
     */
    data class BridgeComplete(
        override val inputterPubkey: String,
        val preimage: String,
        val amountSats: Long,
        val feesSats: Long
    ) : RideEvent(inputterPubkey)

    /**
     * Rider reveals precise location to driver.
     * Does not cause state transition.
     * Triggered by: Rider when driver approaches
     */
    data class RevealLocation(
        override val inputterPubkey: String,
        val locationType: String, // "pickup" or "destination"
        val locationEncrypted: String
    ) : RideEvent(inputterPubkey)

    /**
     * Confirmation timeout expired.
     * Transition: ACCEPTED → CANCELLED
     * Triggered by: System timer after 30 seconds
     */
    data class ConfirmationTimeout(
        override val inputterPubkey: String
    ) : RideEvent(inputterPubkey)

    /**
     * PIN verification timeout expired.
     * Does not cause immediate transition.
     * Triggered by: System timer after 30 seconds at ARRIVED
     */
    data class PinTimeout(
        override val inputterPubkey: String
    ) : RideEvent(inputterPubkey)

    /**
     * Get the event type name for logging and transition lookup.
     */
    val eventType: String
        get() = when (this) {
            is Accept -> "ACCEPT"
            is Confirm -> "CONFIRM"
            is StartRoute -> "START_ROUTE"
            is Arrive -> "ARRIVE"
            is SubmitPin -> "SUBMIT_PIN"
            is VerifyPin -> "VERIFY_PIN"
            is StartRide -> "START_RIDE"
            is Complete -> "COMPLETE"
            is Cancel -> "CANCEL"
            is SharePreimage -> "SHARE_PREIMAGE"
            is BridgeComplete -> "BRIDGE_COMPLETE"
            is RevealLocation -> "REVEAL_LOCATION"
            is ConfirmationTimeout -> "CONFIRMATION_TIMEOUT"
            is PinTimeout -> "PIN_TIMEOUT"
        }
}
