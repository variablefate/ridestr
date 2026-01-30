package com.ridestr.common.payment

// ============================================
// NIP-60 Cashu Wallet Event Kinds
// https://github.com/nostr-protocol/nips/blob/master/60.md
// ============================================

/**
 * Nostr event kinds for NIP-60 Cashu wallets.
 */
object Nip60EventKinds {
    /** Wallet metadata event (replaceable) - stores mint info and wallet config */
    const val WALLET = 17375

    /** Token event - stores unspent proofs (encrypted) */
    const val TOKEN = 7375

    /** Spending history event - optional transaction history */
    const val HISTORY = 7376
}

/**
 * Type of escrow mechanism used for payment.
 */
enum class EscrowType {
    /** Cashu Nut-14 Hash Time-Locked Contract */
    CASHU_NUT14,

    /** Lightning HODL invoice with custom payment hash */
    LIGHTNING_HODL
}

/**
 * Current status of payment in the ride lifecycle.
 */
enum class PaymentStatus {
    /** No payment initiated */
    NONE,

    /** Waiting for driver to create escrow invoice */
    AWAITING_ESCROW,

    /** Funds locked in HTLC (rider paid into escrow) */
    ESCROW_LOCKED,

    /** Preimage shared with driver after PIN verification */
    PREIMAGE_SHARED,

    /** Settlement in progress */
    SETTLING,

    /** Payment successfully settled to driver */
    SETTLED,

    /** Payment refunded to rider (timeout or cancellation) */
    REFUNDED,

    /** Payment failed */
    FAILED
}

/**
 * Details about an active escrow.
 *
 * @property escrowType Type of escrow mechanism (Cashu or Lightning)
 * @property paymentHash SHA256 hash that locks the HTLC
 * @property amountSats Amount locked in satoshis
 * @property escrowInvoice The HTLC token (Cashu) or BOLT11 invoice (Lightning)
 * @property escrowExpiry Unix timestamp when escrow expires
 * @property preimage The secret that unlocks the HTLC (only known to rider initially)
 * @property settlementProof Proof of successful settlement
 */
data class EscrowDetails(
    val escrowType: EscrowType,
    val paymentHash: String,
    val amountSats: Long,
    val escrowInvoice: String? = null,
    val escrowExpiry: Long? = null,
    val preimage: String? = null,
    val settlementProof: String? = null
)

/**
 * Current wallet balance.
 *
 * @property availableSats Spendable balance in satoshis
 * @property pendingSats Balance locked in pending transactions
 * @property lastUpdated Timestamp of last balance update
 */
data class WalletBalance(
    val availableSats: Long,
    val pendingSats: Long = 0,
    val lastUpdated: Long = System.currentTimeMillis()
) {
    /** Total balance including pending */
    val totalSats: Long get() = availableSats + pendingSats

    /** Check if balance is sufficient for an amount */
    fun hasSufficientFunds(amountSats: Long): Boolean = availableSats >= amountSats
}

/**
 * Wallet diagnostics for debugging balance discrepancies.
 * Shows the current state of different balance sources.
 *
 * Note: cdk-kotlin balance is NOT shown because NIP-60 is the wallet.
 * cdk-kotlin is only used for mint API calls (deposit/withdraw).
 */
data class WalletDiagnostics(
    val displayedBalance: Long,      // What's shown in UI (verified or unverified)
    val nip60Balance: Long?,         // Proofs synced to Nostr (null = not fetched)
    val nip60ProofCount: Int?,       // Number of proofs synced to NIP-60
    val cachedBalance: Long,         // From WalletStorage cache
    val unverifiedBalance: Long,     // Proofs that couldn't be verified (mint offline)
    val unverifiedCount: Int,        // Number of unverified proofs cached locally
    val isNip60Synced: Boolean,      // Whether NIP-60 has proofs matching display
    val lastNip60Sync: Long?,        // Timestamp of last NIP-60 fetch
    val pendingDeposits: Int,        // Number of pending deposits
    val mintReachable: Boolean,      // Whether mint was reachable on last sync
    val issues: List<String>         // List of detected issues
) {
    /** Whether there are any issues with the wallet state */
    val hasIssues: Boolean get() = issues.isNotEmpty()

    /** Quick summary for info icon tooltip */
    val summary: String get() = when {
        !mintReachable && unverifiedBalance > 0 -> "Mint offline ($unverifiedBalance sats unverified)"
        issues.isEmpty() -> "Wallet synced"
        issues.size == 1 -> issues.first()
        else -> "${issues.size} sync issues"
    }
}

/**
 * Tracks a deposit that was initiated but not yet fully minted.
 * Saved BEFORE showing invoice so we can recover if app crashes after payment.
 *
 * @property quoteId The mint quote ID from NUT-04
 * @property amount Amount in satoshis
 * @property invoice The Lightning invoice (for display/retry)
 * @property createdAt Timestamp when deposit was started
 * @property expiry Quote expiry timestamp (from mint)
 * @property minted Whether proofs have been successfully minted
 */
data class PendingDeposit(
    val quoteId: String,
    val amount: Long,
    val invoice: String,
    val createdAt: Long,
    val expiry: Long,  // 0 means no expiry or unknown
    val minted: Boolean = false
) {
    /** Check if the quote has expired (0 expiry = never expires / unknown) */
    fun isExpired(): Boolean = expiry > 0 && System.currentTimeMillis() / 1000 > expiry

    /** Check if this deposit needs recovery (paid but not minted) */
    fun needsRecovery(): Boolean = !minted && !isExpired()
}

/**
 * Result of claiming unclaimed deposits.
 *
 * @property success Whether all claims succeeded (no errors)
 * @property claimedCount Number of deposits successfully claimed
 * @property totalSats Total sats claimed
 * @property error Error message if any claims failed
 */
data class ClaimResult(
    val success: Boolean,
    val claimedCount: Int = 0,
    val totalSats: Long = 0,
    val error: String? = null
)

/**
 * Result of locking funds for a ride.
 *
 * @property escrowId Unique identifier for this escrow
 * @property htlcToken The locked HTLC token or invoice
 * @property amountSats Amount locked
 * @property expiresAt Unix timestamp when lock expires
 */
data class EscrowLock(
    val escrowId: String,
    val htlcToken: String,
    val amountSats: Long,
    val expiresAt: Long
)

/**
 * Result of a cross-mint bridge payment.
 *
 * @property success Whether the payment was successful
 * @property amountSats Amount paid (not including fees)
 * @property feesSats Fees paid (melt fee + Lightning routing)
 * @property preimage Lightning payment preimage (proof of payment)
 * @property error Error message if payment failed
 */
data class BridgeResult(
    val success: Boolean,
    val amountSats: Long = 0,
    val feesSats: Long = 0,
    val preimage: String? = null,
    val error: String? = null
)

/**
 * Pending HTLC escrow that needs tracking for potential refund.
 *
 * Saved when rider locks funds so they can reclaim after locktime expires
 * if driver never claims.
 *
 * @property escrowId Unique identifier for this escrow
 * @property htlcToken The locked HTLC token (cashuA... format)
 * @property amountSats Amount locked in satoshis
 * @property locktime Unix timestamp after which refund is available
 * @property riderPubKey Rider's wallet public key (for signing refund)
 * @property paymentHash The HTLC payment hash
 * @property rideId Associated ride ID (for UI display)
 * @property createdAt Timestamp when HTLC was created
 * @property status Current status of the HTLC
 */
data class PendingHtlc(
    val escrowId: String,
    val htlcToken: String,
    val amountSats: Long,
    val locktime: Long,
    val riderPubKey: String,
    val paymentHash: String,
    val preimage: String? = null,  // For refund if mint requires it (NUT-14 future-proofing)
    val rideId: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val status: PendingHtlcStatus = PendingHtlcStatus.LOCKED
) {
    /** Check if the locktime has passed and refund is available.
     *  Adds 120-second buffer to account for clock skew between device and mint. */
    fun isRefundable(): Boolean = System.currentTimeMillis() / 1000 > locktime + 120

    /** Check if this HTLC is still active (not claimed or refunded) */
    fun isActive(): Boolean = status == PendingHtlcStatus.LOCKED
}

/**
 * Status of a pending HTLC.
 */
enum class PendingHtlcStatus {
    /** HTLC is locked, waiting for driver to claim or locktime to expire */
    LOCKED,
    /** Driver claimed the HTLC with preimage */
    CLAIMED,
    /** Rider refunded the HTLC after locktime expired */
    REFUNDED,
    /** HTLC failed (e.g., swap error) */
    FAILED
}

/**
 * Result of successful settlement.
 *
 * @property amountSats Amount settled
 * @property settlementProof Cryptographic proof of settlement
 * @property timestamp When settlement occurred
 */
data class SettlementResult(
    val amountSats: Long,
    val settlementProof: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Mint quote state per NUT-04.
 */
enum class MintQuoteState {
    /** Quote created, waiting for Lightning payment */
    UNPAID,
    /** Lightning payment received, tokens not yet minted */
    PAID,
    /** Tokens have been issued */
    ISSUED
}

/**
 * Mint quote from Cashu mint (NUT-04).
 * Used for depositing funds via Lightning.
 *
 * @property quote Unique quote identifier from mint
 * @property request Lightning invoice (BOLT11) to pay for minting
 * @property amount Amount in satoshis
 * @property state Current state of the quote
 * @property expiry Unix timestamp when quote expires
 */
data class MintQuote(
    val quote: String,
    val request: String,
    val amount: Long,
    val state: MintQuoteState,
    val expiry: Long
) {
    /** Check if the quote is still valid */
    fun isExpired(): Boolean = System.currentTimeMillis() / 1000 > expiry

    /** Check if payment received and tokens can be minted */
    fun isPaid(): Boolean = state == MintQuoteState.PAID || state == MintQuoteState.ISSUED
}

/**
 * Result of checking a mint quote - distinguishes between definitive "not found" vs network errors.
 */
sealed class MintQuoteResult {
    /** Quote found at mint */
    data class Found(val quote: MintQuote) : MintQuoteResult()

    /** Quote definitively doesn't exist at mint (404 or explicit error) */
    data object NotFound : MintQuoteResult()

    /** Network or parsing error - quote may or may not exist */
    data class Error(val message: String) : MintQuoteResult()
}

/**
 * Melt quote state per NUT-05.
 */
enum class MeltQuoteState {
    /** Quote created, payment not yet initiated */
    UNPAID,
    /** Payment is being processed */
    PENDING,
    /** Payment completed successfully */
    PAID
}

/**
 * Melt quote from Cashu mint (NUT-05/NUT-23).
 * Used to verify mint liquidity before ride starts.
 *
 * Field names match the Cashu specification:
 * https://github.com/cashubtc/nuts/blob/main/05.md
 * https://github.com/cashubtc/nuts/blob/main/23.md
 *
 * @property quote Unique quote identifier from mint
 * @property request The original bolt11 payment request
 * @property amount Amount to be paid in the specified unit
 * @property unit Unit of the amount (default: "sat")
 * @property feeReserve Fee reserve required for Lightning payment
 * @property state Current state of the quote (UNPAID, PENDING, PAID)
 * @property expiry Unix timestamp when quote expires
 * @property paymentPreimage Preimage returned when payment succeeds (null until paid)
 */
data class MeltQuote(
    val quote: String,
    val request: String,
    val amount: Long,
    val unit: String = "sat",
    val feeReserve: Long,
    val state: MeltQuoteState,
    val expiry: Long,
    val paymentPreimage: String? = null
) {
    /** Total amount including fee reserve */
    val totalAmount: Long get() = amount + feeReserve

    /** Check if the quote is still valid */
    fun isExpired(): Boolean = System.currentTimeMillis() / 1000 > expiry

    /** Check if payment completed */
    fun isPaid(): Boolean = state == MeltQuoteState.PAID
}

// ============================================
// NUT-14 HTLC Structures (Cashu Specification)
// https://github.com/cashubtc/nuts/blob/main/14.md
// ============================================

/**
 * NUT-10 Secret format for HTLC spending conditions.
 *
 * Structure per spec:
 * ["HTLC", { "nonce": "<random>", "data": "<payment_hash>", "tags": [...] }]
 *
 * @property nonce Unique random string to prevent replay
 * @property data The payment_hash (SHA256 of preimage) as 64-char hex
 * @property pubkeys Public keys that must sign to spend (P2PK component)
 * @property locktime Unix timestamp after which refund path is available
 * @property refundPubkeys Public keys that can spend after locktime (refund path)
 */
data class HtlcSecret(
    val nonce: String,
    val data: String,  // payment_hash (64-char hex)
    val pubkeys: List<String> = emptyList(),
    val locktime: Long? = null,
    val refundPubkeys: List<String> = emptyList()
) {
    companion object {
        const val KIND = "HTLC"
    }

    /**
     * Serialize to NUT-10 JSON array format.
     */
    fun toJsonArray(): String {
        val tags = mutableListOf<String>()
        if (pubkeys.isNotEmpty()) {
            tags.add("""["pubkeys"${pubkeys.joinToString("") { ",\"$it\"" }}]""")
        }
        if (locktime != null) {
            tags.add("""["locktime","$locktime"]""")
        }
        if (refundPubkeys.isNotEmpty()) {
            tags.add("""["refund"${refundPubkeys.joinToString("") { ",\"$it\"" }}]""")
        }
        val tagsJson = if (tags.isEmpty()) "" else ""","tags":[${tags.joinToString(",")}]"""
        return """["$KIND",{"nonce":"$nonce","data":"$data"$tagsJson}]"""
    }
}

/**
 * NUT-14 Witness for spending an HTLC-locked proof.
 *
 * To spend via the hash lock path:
 * - Provide the preimage that hashes to Secret.data
 * - Provide signatures from all keys in Secret.tags.pubkeys
 *
 * @property preimage The 64-char hex preimage that SHA256-hashes to the payment_hash
 * @property signatures Signatures from required pubkeys (hex-encoded)
 */
data class HtlcWitness(
    val preimage: String,
    val signatures: List<String> = emptyList()
) {
    /**
     * Serialize to JSON for Proof.witness field.
     */
    fun toJson(): String {
        val sigsJson = signatures.joinToString(",") { "\"$it\"" }
        return """{"preimage":"$preimage","signatures":[$sigsJson]}"""
    }
}

/**
 * Cashu Proof structure with optional HTLC witness.
 *
 * @property id Keyset ID
 * @property amount Amount in satoshis
 * @property secret The secret (either plain or NUT-10 format for HTLC)
 * @property C The signature from the mint
 * @property witness Optional witness data for spending conditions
 */
data class CashuProof(
    val id: String,
    val amount: Long,
    val secret: String,
    val C: String,
    val witness: String? = null
) {
    /**
     * Convert proof to JSON object for Cashu token serialization.
     */
    fun toJson(): org.json.JSONObject {
        return org.json.JSONObject().apply {
            put("amount", amount)
            put("id", id)
            put("secret", secret)
            put("C", C)
            witness?.let { put("witness", it) }
        }
    }
}

/**
 * Proof state from NUT-07 check.
 */
enum class ProofState {
    UNSPENT,
    PENDING,
    SPENT
}

/**
 * Response from NUT-07 proof state check.
 *
 * @property Y The Y value (hash_to_curve(secret)) that was checked
 * @property state Current state of the proof
 * @property witness Witness data if proof was spent with spending conditions
 */
data class ProofStateCheck(
    val Y: String,
    val state: ProofState,
    val witness: String? = null
)

/**
 * Transaction record for wallet history.
 *
 * @property id Unique transaction identifier
 * @property type Type of transaction
 * @property amountSats Amount in satoshis (not including fees)
 * @property feeSats Mint/network fees paid for this transaction
 * @property timestamp When transaction occurred
 * @property rideId Associated ride ID (if applicable)
 * @property counterpartyPubKey Other party's Nostr pubkey
 * @property status Transaction status description
 */
data class PaymentTransaction(
    val id: String,
    val type: TransactionType,
    val amountSats: Long,
    val feeSats: Long = 0,
    val timestamp: Long,
    val rideId: String? = null,
    val counterpartyPubKey: String? = null,
    val status: String
) {
    /** Total amount deducted from wallet (amount + fees for outgoing transactions) */
    val totalSats: Long get() = amountSats + feeSats
}

/**
 * Type of wallet transaction.
 */
enum class TransactionType {
    /** Funds locked for ride escrow (rider) */
    ESCROW_LOCK,

    /** Funds received in escrow (driver) */
    ESCROW_RECEIVE,

    /** Escrow settled to driver */
    SETTLEMENT,

    /** Funds refunded to rider */
    REFUND,

    /** HTLC escrow refunded after locktime expired */
    ESCROW_REFUND,

    /** Funds received from external source */
    DEPOSIT,

    /** Funds sent to external destination */
    WITHDRAWAL,

    /** Cross-mint bridge payment (rider pays driver's deposit invoice) */
    BRIDGE_PAYMENT
}

// ============================================
// Cross-Mint Bridge Payment Tracking
// ============================================

/**
 * Status of a pending bridge payment.
 */
enum class BridgePaymentStatus {
    /** Bridge payment initiated, getting melt quote */
    STARTED,
    /** Melt quote obtained, about to execute melt */
    MELT_QUOTE_OBTAINED,
    /** Melt executed, waiting for Lightning to route */
    MELT_EXECUTED,
    /** Lightning payment confirmed by mint */
    LIGHTNING_CONFIRMED,
    /** BridgeComplete published to driver */
    COMPLETE,
    /** Payment failed at some stage */
    FAILED
}

/**
 * Tracks a cross-mint bridge payment in progress.
 * Stored locally to survive app restart and allow recovery/debugging.
 *
 * Bridge payment flow:
 * 1. STARTED - Driver shares deposit invoice, rider begins bridge
 * 2. MELT_QUOTE_OBTAINED - Got quote from rider's mint (feeReserve known)
 * 3. MELT_EXECUTED - Proofs sent to mint for melting
 * 4. LIGHTNING_CONFIRMED - Mint confirms Lightning payment succeeded
 * 5. COMPLETE - BridgeComplete published to driver
 *
 * If app crashes/closes between stages, this record lets us:
 * - Check melt quote status with mint
 * - Recover change proofs if melt partially completed
 * - Show user what happened
 *
 * @property id Unique identifier for this bridge payment
 * @property rideId Associated ride ID
 * @property driverInvoice The Lightning invoice from driver's mint
 * @property amountSats Amount being paid (not including fees)
 * @property feeReserveSats Fee reserve from melt quote (0 if not yet obtained)
 * @property meltQuoteId Quote ID from rider's mint (null until quote obtained)
 * @property status Current status
 * @property createdAt Timestamp when bridge started
 * @property updatedAt Timestamp of last status update
 * @property proofsUsed Secrets of proofs sent to mint (for recovery)
 * @property changeProofsReceived Whether change proofs were received and saved
 * @property lightningPreimage Preimage from successful Lightning payment
 * @property errorMessage Error message if failed
 */
data class PendingBridgePayment(
    val id: String,
    val rideId: String,
    val driverInvoice: String,
    val amountSats: Long,
    val feeReserveSats: Long = 0,
    val meltQuoteId: String? = null,
    val status: BridgePaymentStatus = BridgePaymentStatus.STARTED,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val proofsUsed: List<String> = emptyList(),  // Proof secrets for recovery
    val changeProofsReceived: Boolean = false,
    val lightningPreimage: String? = null,
    val errorMessage: String? = null
) {
    /** Check if this bridge payment is still in progress */
    fun isInProgress(): Boolean = status != BridgePaymentStatus.COMPLETE && status != BridgePaymentStatus.FAILED

    /** Check if this bridge payment failed */
    fun isFailed(): Boolean = status == BridgePaymentStatus.FAILED

    /** Human-readable status description */
    fun statusDescription(): String = when (status) {
        BridgePaymentStatus.STARTED -> "Starting bridge payment..."
        BridgePaymentStatus.MELT_QUOTE_OBTAINED -> "Got melt quote, executing..."
        BridgePaymentStatus.MELT_EXECUTED -> "Waiting for Lightning confirmation..."
        BridgePaymentStatus.LIGHTNING_CONFIRMED -> "Lightning paid, publishing result..."
        BridgePaymentStatus.COMPLETE -> "Bridge payment complete"
        BridgePaymentStatus.FAILED -> "Bridge payment failed: ${errorMessage ?: "unknown error"}"
    }
}
