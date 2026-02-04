package com.ridestr.common.payment.cashu

import com.ridestr.common.payment.WalletBalance

/**
 * Interface for NIP-60 wallet storage operations.
 *
 * This interface abstracts the NIP-60 Nostr event operations used by WalletService,
 * enabling test doubles for contract testing of proof conservation invariants.
 *
 * NIP-60 is the SOURCE OF TRUTH for wallet balance. These methods manage:
 * - Proof storage (Kind 7375 events)
 * - Wallet metadata (Kind 17375 events)
 * - Balance queries and proof selection
 *
 * Implementation: [Nip60WalletSync]
 * Test Double: FakeNip60Store (in test sources)
 *
 * @see <a href="https://github.com/nostr-protocol/nips/blob/master/60.md">NIP-60</a>
 */
interface Nip60Store {

    // ═══════════════════════════════════════════════════════════════════════════════
    // PROOF OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Publish proofs to Nostr as Kind 7375 events.
     * Content is NIP-44 encrypted to self.
     *
     * NIP-60 COMPLIANT: When proofs replace old proofs (spending), include the
     * old event IDs in the `deletedEventIds`. This is atomic - one event both creates
     * new proofs AND marks old ones as consumed.
     *
     * @param proofs List of Cashu proofs to publish
     * @param mintUrl The mint URL these proofs are from
     * @param deletedEventIds Event IDs being replaced/consumed by this publish (for `del` array)
     * @return Event ID if successful, null otherwise
     */
    suspend fun publishProofs(
        proofs: List<CashuProof>,
        mintUrl: String,
        deletedEventIds: List<String> = emptyList()
    ): String?

    /**
     * Fetch all unspent proofs from Nostr.
     * Queries Kind 7375 events authored by this user.
     *
     * NIP-60 COMPLIANT: Parses `del` arrays from all events to determine which
     * events have been consumed/replaced. Proofs from deleted events are filtered out.
     *
     * @param forceRefresh If true, bypass cache
     * @return List of proofs with their event IDs (only unspent/valid proofs)
     */
    suspend fun fetchProofs(forceRefresh: Boolean = false): List<Nip60Proof>

    /**
     * Get proofs for spending.
     * Returns proofs that sum to at least the requested amount.
     *
     * @param amountSats Amount needed
     * @param mintUrl Optional mint filter
     * @return Pair of (proofs to spend, change amount) or null if insufficient
     */
    suspend fun selectProofsForSpending(
        amountSats: Long,
        mintUrl: String? = null
    ): ProofSelection?

    /**
     * Delete token events after spending proofs.
     * Uses NIP-09 deletion.
     *
     * @param eventIds Event IDs to delete
     * @return true if deletion events were published
     */
    suspend fun deleteProofEvents(eventIds: List<String>): Boolean

    /**
     * Re-publish proofs with a new mint URL.
     * Used when a mint's URL has changed but the proofs are still valid.
     * Deletes the old events and publishes new ones with the updated mint URL.
     *
     * @param proofs Proofs with outdated mint URL
     * @param newMintUrl The correct/new mint URL
     */
    suspend fun republishProofsWithNewMint(
        proofs: List<Nip60Proof>,
        newMintUrl: String
    )

    /**
     * Clear cached proofs (call when switching accounts or after sync).
     */
    fun clearCache()

    // ═══════════════════════════════════════════════════════════════════════════════
    // WALLET METADATA
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Publish wallet metadata (Kind 17375) in NIP-60 standard format.
     * Stores mint URL, wallet private key, and mnemonic for full backup.
     *
     * SECURITY: The entire content is NIP-44 encrypted to the user's own pubkey.
     * Only the user with their nsec can decrypt this data.
     *
     * @param mintUrl The mint URL to store in metadata
     * @param walletName Display name for the wallet
     * @param forceOverwrite If false, will not overwrite existing metadata from a different mint
     *                       (protects cross-app NIP-60 portability)
     */
    suspend fun publishWalletMetadata(
        mintUrl: String,
        walletName: String = "Ridestr Wallet",
        forceOverwrite: Boolean = false
    ): Boolean

    // ═══════════════════════════════════════════════════════════════════════════════
    // DIAGNOSTICS & ONBOARDING
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Calculate total balance from NIP-60 proofs.
     * This is the SOURCE OF TRUTH for wallet balance.
     */
    suspend fun getBalance(): WalletBalance

    /**
     * Check if user has an existing NIP-60 wallet.
     * Checks for EITHER wallet metadata (Kind 17375) OR proof events (Kind 7375).
     * This ensures we detect existing wallets even if metadata wasn't published.
     */
    suspend fun hasExistingWallet(): Boolean

    /**
     * Restore wallet state from Nostr.
     * Fetches wallet metadata (including wallet key and mnemonic) and all unspent proofs.
     *
     * IMPORTANT: This restores the wallet private key and mnemonic from the NIP-60 backup.
     * Without this, HTLC claims would fail on a new device (different wallet key).
     */
    suspend fun restoreFromNostr(): WalletState?

    // ═══════════════════════════════════════════════════════════════════════════════
    // NUCLEAR RESET
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Delete all NIP-60 proof events (Kind 7375) from relays.
     * This is destructive - only use when user wants to clear wallet from Nostr.
     *
     * NOTE: This deletes the proof EVENTS, not the proofs themselves.
     * The funds still exist if the proofs are held locally.
     *
     * @return Number of events deleted
     */
    suspend fun deleteAllProofsFromNostr(): Int
}
