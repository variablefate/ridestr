package com.ridestr.common.state

import com.ridestr.common.nostr.events.Location

/**
 * Context object containing all information needed for state machine
 * guard evaluation and action execution.
 *
 * This follows the AtoB pattern of providing a rich context to guards/actions
 * rather than embedding authorization logic in events.
 */
data class RideContext(
    // === Participant Identity ===

    /** Public key of the rider (offer creator) */
    val riderPubkey: String,

    /** Public key of the driver (may be null until acceptance) */
    val driverPubkey: String? = null,

    /** The pubkey of whoever is triggering the current transition (inputterPubKey pattern) */
    val inputterPubkey: String,

    // === Ride Identification ===

    /** The offer event ID (Kind 3173) - ride identifier before confirmation */
    val offerEventId: String? = null,

    /** The acceptance event ID (Kind 3174) */
    val acceptanceEventId: String? = null,

    /** The confirmation event ID (Kind 3175) - primary ride identifier after confirmation */
    val confirmationEventId: String? = null,

    // === Location Data ===

    /** Approximate pickup location (privacy-preserving) */
    val approxPickup: Location? = null,

    /** Precise pickup location (revealed after confirmation) */
    val precisePickup: Location? = null,

    /** Destination location */
    val destination: Location? = null,

    // === Payment Data ===

    /** Fare estimate in satoshis */
    val fareEstimateSats: Long = 0,

    /** Payment hash for HTLC escrow (32-byte hex) */
    val paymentHash: String? = null,

    /** Driver's wallet pubkey for P2PK (separate from Nostr key) */
    val driverWalletPubkey: String? = null,

    /** Rider's mint URL */
    val riderMintUrl: String? = null,

    /** Driver's mint URL */
    val driverMintUrl: String? = null,

    /** Payment method (cashu, lightning, fiat_cash) */
    val paymentMethod: String? = "cashu",

    /** Whether escrow was successfully locked */
    val escrowLocked: Boolean = false,

    /** Escrow token (HTLC token for settlement) */
    val escrowToken: String? = null,

    /** HTLC preimage (64-char hex, revealed for settlement) */
    val preimage: String? = null,

    // === PIN Verification ===

    /** The 4-digit PIN for pickup verification */
    val pickupPin: String? = null,

    /** Number of PIN verification attempts */
    val pinAttempts: Int = 0,

    /** Whether PIN has been verified */
    val pinVerified: Boolean = false,

    /** Maximum PIN attempts before lockout */
    val maxPinAttempts: Int = 3,

    // === Timing ===

    /** Timestamp when offer was created */
    val offerCreatedAt: Long = 0,

    /** Timestamp when driver accepted */
    val acceptedAt: Long = 0,

    /** Timestamp when rider confirmed */
    val confirmedAt: Long = 0,

    /** Confirmation timeout in milliseconds */
    val confirmationTimeoutMs: Long = 30_000,

    /** PIN verification timeout in milliseconds */
    val pinVerificationTimeoutMs: Long = 30_000,

    // === Cancellation ===

    /** Reason for cancellation (if applicable) */
    val cancellationReason: String? = null,

    /** Who initiated cancellation */
    val cancelledByPubkey: String? = null
) {
    /**
     * Check if the inputter is the rider.
     */
    fun isInputterRider(): Boolean = inputterPubkey == riderPubkey

    /**
     * Check if the inputter is the driver.
     */
    fun isInputterDriver(): Boolean = driverPubkey != null && inputterPubkey == driverPubkey

    /**
     * Check if the inputter is either the rider or driver.
     */
    fun isInputterParticipant(): Boolean = isInputterRider() || isInputterDriver()

    /**
     * Check if both rider and driver are using the same mint.
     */
    fun isSameMint(): Boolean {
        return riderMintUrl != null &&
               driverMintUrl != null &&
               riderMintUrl == driverMintUrl
    }

    /**
     * Check if PIN brute force limit has been reached.
     */
    fun isPinBruteForceLimitReached(): Boolean = pinAttempts >= maxPinAttempts

    /**
     * Check if payment is properly set up for settlement.
     */
    fun canSettle(): Boolean {
        return escrowLocked && preimage != null && escrowToken != null
    }

    /**
     * Create a copy with updated driver information (after acceptance).
     */
    fun withDriver(
        driverPubkey: String,
        driverWalletPubkey: String? = null,
        driverMintUrl: String? = null,
        acceptanceEventId: String? = null
    ): RideContext = copy(
        driverPubkey = driverPubkey,
        driverWalletPubkey = driverWalletPubkey,
        driverMintUrl = driverMintUrl,
        acceptanceEventId = acceptanceEventId,
        acceptedAt = System.currentTimeMillis()
    )

    /**
     * Create a copy with confirmation data.
     */
    fun withConfirmation(
        confirmationEventId: String,
        precisePickup: Location? = null,
        escrowLocked: Boolean = false,
        escrowToken: String? = null,
        paymentHash: String? = null
    ): RideContext = copy(
        confirmationEventId = confirmationEventId,
        precisePickup = precisePickup,
        escrowLocked = escrowLocked,
        escrowToken = escrowToken,
        paymentHash = paymentHash,
        confirmedAt = System.currentTimeMillis()
    )

    /**
     * Create a copy with PIN verification result.
     */
    fun withPinAttempt(verified: Boolean): RideContext = copy(
        pinAttempts = pinAttempts + 1,
        pinVerified = verified
    )

    /**
     * Create a copy with preimage for settlement.
     */
    fun withPreimage(preimage: String): RideContext = copy(
        preimage = preimage
    )

    companion object {
        /**
         * Create initial context for a new ride offer.
         */
        fun forOffer(
            riderPubkey: String,
            approxPickup: Location,
            destination: Location,
            fareEstimateSats: Long,
            offerEventId: String,
            paymentHash: String? = null,
            riderMintUrl: String? = null,
            paymentMethod: String = "cashu"
        ): RideContext = RideContext(
            riderPubkey = riderPubkey,
            inputterPubkey = riderPubkey,
            offerEventId = offerEventId,
            approxPickup = approxPickup,
            destination = destination,
            fareEstimateSats = fareEstimateSats,
            paymentHash = paymentHash,
            riderMintUrl = riderMintUrl,
            paymentMethod = paymentMethod,
            offerCreatedAt = System.currentTimeMillis()
        )
    }
}
