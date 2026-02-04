package com.ridestr.common.payment

/**
 * Result type for HTLC lock operations.
 * Provides rich error information for caller decision-making and UI feedback.
 *
 * NOTE: SwapFailed has no code/detail fields because CashuBackend currently
 * logs HTTP errors but doesn't return them. Future enhancement can add
 * HtlcLockOutcome sealed class to CashuBackend (like HtlcRefundOutcome).
 */
sealed class LockResult {
    data class Success(val escrowLock: EscrowLock) : LockResult()

    sealed class Failure : LockResult() {
        abstract val message: String

        data class NotConnected(override val message: String = "Wallet not connected") : Failure()
        data class InsufficientBalance(
            val required: Long,
            val available: Long,
            override val message: String = "Insufficient balance"
        ) : Failure()
        data class ProofsSpent(
            val spentCount: Int,
            val totalSelected: Int,
            override val message: String = "Selected proofs already spent"
        ) : Failure()
        data class MintUnreachable(
            val mintUrl: String,
            override val message: String = "Cannot reach mint"
        ) : Failure()
        data class SwapFailed(
            override val message: String = "HTLC swap failed"
            // code/detail not available from CashuBackend (logged only)
        ) : Failure()
        data class NoWalletKey(override val message: String = "Wallet key not available") : Failure()
        data class NipSyncNotInitialized(override val message: String = "NIP-60 sync not initialized") : Failure()
        data class MintUrlNotAvailable(override val message: String = "Mint URL not available") : Failure()
        data class VerificationFailed(override val message: String = "NUT-07 verification failed") : Failure()
        data class Other(override val message: String) : Failure()
    }
}

/**
 * Result type for HTLC claim operations.
 *
 * NOTE: Named HtlcClaimResult to avoid collision with the existing ClaimResult
 * data class in PaymentModels.kt (used for deposit claiming).
 *
 * NOTE: MintRejected doesn't include code/detail because CashuBackend
 * logs HTTP errors but doesn't return them to callers.
 */
sealed class HtlcClaimResult {
    data class Success(val settlement: SettlementResult) : HtlcClaimResult()

    sealed class Failure : HtlcClaimResult() {
        abstract val message: String

        data class NotConnected(override val message: String = "Wallet not connected") : Failure()
        data class PreimageMismatch(
            val paymentHash: String,
            override val message: String = "Preimage does not match payment hash"
        ) : Failure()
        data class TokenParseFailed(override val message: String = "Failed to parse HTLC token") : Failure()
        data class MintRejected(
            override val message: String = "Mint rejected claim"
            // code/detail not available from CashuBackend (logged only)
        ) : Failure()
        data class MintUnreachable(
            override val message: String = "Cannot reach mint"
        ) : Failure()
        data class SignatureFailed(override val message: String = "Failed to sign proof") : Failure()
        data class Other(override val message: String) : Failure()
    }
}
