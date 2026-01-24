package com.ridestr.common.payment

import android.content.Context
import android.util.Log
import com.ridestr.common.payment.cashu.CashuBackend
import com.ridestr.common.payment.cashu.CashuBackend.ProofStateResult
import com.ridestr.common.payment.cashu.CashuCrypto
import com.ridestr.common.payment.cashu.CashuProof
import com.ridestr.common.payment.cashu.MintCapabilities
import com.ridestr.common.payment.cashu.MintTokensResult
import com.ridestr.common.payment.cashu.Nip60WalletSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Unified wallet interface - hides Cashu/Lightning implementation details.
 * Users see: balance, send, receive. No mention of tokens, mints, etc.
 *
 * UI Terminology:
 * - "Balance" not "Tokens"
 * - "Wallet" not "Mint"
 * - "Send/Receive" not "Melt/Mint"
 * - "Transaction" not "Proof"
 */
class WalletService(
    private val context: Context,
    private val walletKeyManager: WalletKeyManager
) {
    companion object {
        private const val TAG = "WalletService"

        // Default mints with NUT-14 support
        val DEFAULT_MINTS = listOf(
            MintOption(
                name = "Minibits",
                description = "Popular, widely used (~1% fees)",
                url = "https://mint.minibits.cash/Bitcoin",
                recommended = true
            )
        )
    }

    private val _walletStorage = WalletStorage(context)
    private val cashuBackend = CashuBackend(context, walletKeyManager, _walletStorage)

    // Expose walletStorage for NIP-60 counter backup
    val walletStorage: WalletStorage get() = _walletStorage

    // Lazy init for NIP-60 sync (requires NostrService which may not be available at init)
    private var nip60Sync: Nip60WalletSync? = null

    // Mutex to prevent concurrent sync operations
    private val syncMutex = Mutex()

    // === State ===

    private val _balance = MutableStateFlow(WalletBalance(availableSats = 0))
    val balance: StateFlow<WalletBalance> = _balance.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _currentMintName = MutableStateFlow<String?>(null)
    val currentMintName: StateFlow<String?> = _currentMintName.asStateFlow()

    private val _transactions = MutableStateFlow<List<PaymentTransaction>>(emptyList())
    val transactions: StateFlow<List<PaymentTransaction>> = _transactions.asStateFlow()

    private val _diagnostics = MutableStateFlow<WalletDiagnostics?>(null)
    val diagnostics: StateFlow<WalletDiagnostics?> = _diagnostics.asStateFlow()

    // Recovery tokens notification - UI can observe this to show recovery banner
    private val _hasRecoveryTokens = MutableStateFlow(false)
    val hasRecoveryTokens: StateFlow<Boolean> = _hasRecoveryTokens.asStateFlow()

    init {
        // Load cached balance
        _balance.value = walletStorage.getCachedBalance()
        _transactions.value = walletStorage.getTransactions()

        // Try to restore connection from saved mint URL
        val savedMintUrl = walletStorage.getMintUrl()
        if (savedMintUrl != null) {
            _currentMintName.value = DEFAULT_MINTS.find { it.url == savedMintUrl }?.name ?: "Custom"
        }

        // Check for existing recovery tokens
        _hasRecoveryTokens.value = walletStorage.getRecoveryTokens().isNotEmpty()
    }

    /**
     * Auto-connect to saved mint URL if available.
     * Call this from a coroutine scope after creating the service.
     */
    suspend fun autoConnect(): Boolean {
        val savedMintUrl = walletStorage.getMintUrl()
        if (savedMintUrl != null) {
            Log.d(TAG, "Auto-connecting to saved mint: $savedMintUrl")
            return connect(savedMintUrl)
        }
        Log.d(TAG, "No saved mint URL, skipping auto-connect")
        return false
    }

    // === PUBLIC API (Simple wallet operations) ===

    /**
     * Get current balance in satoshis.
     */
    fun getBalance(): Long = _balance.value.availableSats

    /**
     * Get formatted balance string (e.g., "1,234 sats").
     */
    fun getBalanceFormatted(): String {
        val sats = _balance.value.availableSats
        return "%,d sats".format(sats)
    }

    /**
     * Check if balance is sufficient for an amount.
     */
    fun hasSufficientFunds(amountSats: Long): Boolean {
        return _balance.value.hasSufficientFunds(amountSats)
    }

    /**
     * Verify a mint's capabilities before connecting.
     *
     * @param mintUrl The mint URL to verify
     * @return MintCapabilities if successful, null if verification failed
     */
    suspend fun verifyMint(mintUrl: String): MintCapabilities? {
        return cashuBackend.verifyMintCapabilities(mintUrl)
    }

    /**
     * Connect to a wallet provider (mint).
     *
     * @param mintUrl The mint URL
     * @return true if connected successfully and mint supports escrow
     */
    suspend fun connect(mintUrl: String): Boolean {
        val success = cashuBackend.connect(mintUrl)
        if (success) {
            walletStorage.saveMintUrl(mintUrl)
            _isConnected.value = true
            _currentMintName.value = DEFAULT_MINTS.find { it.url.trimEnd('/') == mintUrl.trimEnd('/') }?.name ?: "Custom"
            Log.d(TAG, "Connected to wallet provider: $mintUrl")

            // Verify hash_to_curve implementation against NUT-00 test vectors
            val htcPassed = CashuCrypto.verifyHashToCurveTestVectors()
            if (!htcPassed) {
                Log.e(TAG, "CRITICAL: hash_to_curve implementation does not match NUT-00 spec!")
            } else {
                Log.d(TAG, "hash_to_curve verification PASSED")
            }

            // CRITICAL: Check for and recover pending blinded operations first
            try {
                val pendingOps = walletStorage.getRecoverableBlindedOps()
                if (pendingOps.isNotEmpty()) {
                    Log.d(TAG, "Found ${pendingOps.size} pending operations to recover on connect")
                    val recoveryResults = recoverPendingOperations()
                    val successCount = recoveryResults.count { it.success }
                    Log.d(TAG, "Recovered $successCount/${recoveryResults.size} pending operations")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error recovering pending operations on connect: ${e.message}")
            }

            // Check for and refund any expired HTLCs on connect
            try {
                walletStorage.cleanupExpiredDeposits()
                walletStorage.cleanupResolvedHtlcs()
                val refundable = walletStorage.getRefundableHtlcs()
                if (refundable.isNotEmpty()) {
                    Log.d(TAG, "Found ${refundable.size} expired HTLCs to refund on connect")
                    val results = refundExpiredHtlcs()
                    val successCount = results.count { it.success }
                    if (successCount > 0) {
                        Log.d(TAG, "Auto-refunded $successCount expired HTLCs on connect")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking expired HTLCs on connect: ${e.message}")
            }

            // Sync wallet after connecting
            try {
                val syncResult = syncWallet()
                Log.d(TAG, "Wallet sync on connect: ${syncResult.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Error syncing wallet on connect: ${e.message}")
            }

            // Recalculate pendingSats from active HTLCs (in case cache was stale)
            recalculatePendingSats()
        }
        return success
    }

    /**
     * Recalculate pendingSats from active pending HTLCs.
     * Called on connect to ensure cached pendingSats is accurate.
     */
    private fun recalculatePendingSats() {
        val activeHtlcs = walletStorage.getPendingHtlcs().filter { it.isActive() }
        val calculatedPending = activeHtlcs.sumOf { it.amountSats }
        val currentPending = _balance.value.pendingSats

        if (calculatedPending != currentPending) {
            Log.d(TAG, "Recalculating pendingSats: was $currentPending, now $calculatedPending (${activeHtlcs.size} active HTLCs)")
            _balance.value = _balance.value.copy(pendingSats = calculatedPending)
            walletStorage.cacheBalance(_balance.value)
        }
    }

    /**
     * Disconnect from current wallet provider.
     */
    fun disconnect() {
        cashuBackend.disconnect()
        _isConnected.value = false
        _currentMintName.value = null
        Log.d(TAG, "Disconnected from wallet provider")
    }

    /**
     * Reset all wallet data.
     * This clears:
     * - Local proof storage (cdk-kotlin SQLite database)
     * - Cached balance and transactions
     * - Mint URL and connection state
     * - Pending deposits
     *
     * WARNING: This will delete all wallet proofs! User will lose any funds
     * stored locally that haven't been synced to NIP-60.
     *
     * Use this when:
     * - Wallet data is corrupted
     * - Balance doesn't match actual proofs
     * - Starting fresh after failed deposits
     */
    fun resetWallet() {
        Log.w(TAG, "Resetting all wallet data - this will delete local proofs!")

        // Disconnect first
        disconnect()

        // Clear all storage (mint URL, cached balance, transactions, pending deposits)
        walletStorage.clearAll()

        // Delete cdk-kotlin SQLite database
        try {
            val dbFile = context.getDatabasePath("cashu_proofs.db")
            if (dbFile.exists()) {
                dbFile.delete()
                Log.d(TAG, "Deleted cdk-kotlin database: ${dbFile.absolutePath}")
            }
            // Also delete WAL and SHM files if they exist
            val walFile = context.getDatabasePath("cashu_proofs.db-wal")
            val shmFile = context.getDatabasePath("cashu_proofs.db-shm")
            walFile.delete()
            shmFile.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting cdk-kotlin database", e)
        }

        // Clear wallet key and mnemonic
        walletKeyManager.clearWalletKey()

        // Reset state
        _balance.value = WalletBalance(0, 0, System.currentTimeMillis())
        _transactions.value = emptyList()
        _diagnostics.value = null

        Log.d(TAG, "Wallet reset complete")
    }

    /**
     * Delete all NIP-60 wallet data from Nostr relays.
     * This removes proof events (Kind 7375) and wallet metadata (Kind 17375).
     *
     * Use this to:
     * - Clear wallet sync data from relays
     * - Start fresh with NIP-60 sync
     * - Privacy cleanup before switching accounts
     *
     * @return Number of events deleted, or -1 on error
     */
    suspend fun deleteNip60Data(): Int {
        val sync = nip60Sync
        if (sync == null) {
            Log.e(TAG, "deleteNip60Data: NIP-60 sync not initialized")
            return -1
        }

        return sync.deleteAllProofsFromNostr()
    }

    /**
     * Check if NIP-60 sync is available.
     */
    fun hasNip60Sync(): Boolean = nip60Sync != null

    /**
     * Change the mint URL and verify existing proofs against the new mint.
     *
     * This is useful when the mint URL changes (e.g., cloudflare tunnel restart)
     * but the mint itself is the same (same proofs are valid).
     *
     * Flow:
     * 1. Connect to new mint URL
     * 2. Get all local proofs from cdk-kotlin
     * 3. Verify proofs with new mint using NUT-07
     * 4. If valid, update stored mint URL and resync to NIP-60
     *
     * @param newMintUrl The new mint URL to switch to
     * @return ChangeMintResult with success status and details
     */
    suspend fun changeMintUrl(newMintUrl: String): ChangeMintResult {
        Log.d(TAG, "=== CHANGING MINT URL ===")
        Log.d(TAG, "New URL: $newMintUrl")

        val oldMintUrl = cashuBackend.getCurrentMintUrl()
        Log.d(TAG, "Old URL: $oldMintUrl")

        // Step 1: Disconnect from old mint
        disconnect()

        // Step 2: Connect to new mint
        val connected = cashuBackend.connect(newMintUrl)
        if (!connected) {
            Log.e(TAG, "Failed to connect to new mint: $newMintUrl")
            // Try to reconnect to old mint
            if (oldMintUrl != null) {
                cashuBackend.connect(oldMintUrl)
                walletStorage.saveMintUrl(oldMintUrl)
                _isConnected.value = true
            }
            return ChangeMintResult(
                success = false,
                message = "Failed to connect to new mint. Check URL and try again.",
                verifiedBalance = 0,
                spentProofs = 0,
                unspentProofs = 0
            )
        }

        walletStorage.saveMintUrl(newMintUrl)
        _isConnected.value = true
        _currentMintName.value = DEFAULT_MINTS.find { it.url.trimEnd('/') == newMintUrl.trimEnd('/') }?.name ?: "Custom"
        Log.d(TAG, "Connected to new mint")

        // Step 3: Let syncWallet() handle ALL proof verification, URL migration, and NIP-60 sync
        // This replaces the old logic of: get local proofs → verify → update balance → resync NIP-60
        val syncResult = syncWallet()

        // Step 4: Update wallet metadata with new mint URL (for cross-device restore)
        // This ensures another device gets the correct mint URL when importing the key
        // forceOverwrite=true because user explicitly requested mint change
        if (syncResult.success) {
            nip60Sync?.publishWalletMetadata(newMintUrl, forceOverwrite = true)
            Log.d(TAG, "Published updated wallet metadata with new mint URL")
        }

        val message = if (syncResult.verifiedBalance > 0) {
            "Mint changed successfully. ${syncResult.message}"
        } else if (syncResult.spentCount > 0) {
            "Mint changed but ${syncResult.spentCount} proofs were marked as spent."
        } else {
            "Mint changed. ${syncResult.message}"
        }

        return ChangeMintResult(
            success = syncResult.success,
            message = message,
            verifiedBalance = syncResult.verifiedBalance,
            spentProofs = syncResult.spentCount,
            unspentProofs = 0  // syncWallet tracks verified proofs, not unspent count
        )
    }

    /**
     * Result of changing mint URL.
     */
    data class ChangeMintResult(
        val success: Boolean,
        val message: String,
        val verifiedBalance: Long,
        val spentProofs: Int,
        val unspentProofs: Int
    )

    /**
     * Get the wallet's public key for P2PK escrow operations.
     * This is separate from the Nostr identity key.
     *
     * @return Wallet public key as hex string, or null if not available
     */
    fun getWalletPubKey(): String? = walletKeyManager.getWalletPubKeyHex()

    /**
     * Set the NIP-60 sync instance (call after NostrService is available).
     */
    fun setNip60Sync(sync: Nip60WalletSync) {
        this.nip60Sync = sync
    }

    // === Single Sync Function (The ONE function that handles everything) ===

    /**
     * Sync wallet to a consistent state. This is THE sync function.
     * Call this on: connect(), changeMintUrl(), refreshBalance(), pull-to-refresh
     *
     * NIP-60 IS the wallet. cdk-kotlin is just a helper for mint API calls.
     *
     * What it does:
     * 1. Fetch NIP-60 proofs (THE wallet)
     * 2. Include any locally cached unverified proofs
     * 3. Verify all proofs with mint (NUT-07)
     * 4. Handle unverified proofs (cache for later if mint down)
     * 5. Clean up stale/spent proofs from NIP-60
     * 6. Update displayed balance
     */
    suspend fun syncWallet(): SyncResult = syncMutex.withLock {
        Log.d(TAG, "=== SYNC WALLET ===")

        val sync = nip60Sync
        if (sync == null) {
            Log.e(TAG, "syncWallet: NIP-60 not initialized")
            return@withLock SyncResult(false, "NIP-60 not initialized")
        }

        val mintUrl = cashuBackend.getCurrentMintUrl()
        if (mintUrl == null) {
            Log.e(TAG, "syncWallet: Not connected to mint")
            return@withLock SyncResult(false, "Not connected to mint")
        }

        try {
            // Step 0: Cleanup expired/stale deposits
            walletStorage.cleanupExpiredDeposits()

            // Also check for stale deposits with unknown quotes at mint
            // IMPORTANT: Only remove deposits with DEFINITIVE "not found" response (404)
            // Do NOT remove on network errors - the deposit may still be valid!
            val pendingDeposits = walletStorage.getPendingDeposits().filter { !it.minted && !it.isExpired() }
            for (deposit in pendingDeposits) {
                val quoteResult = cashuBackend.checkMintQuoteWithResult(deposit.quoteId)
                when (quoteResult) {
                    is MintQuoteResult.Found -> {
                        // Quote exists - try to claim if paid
                        if (quoteResult.quote.isPaid()) {
                            Log.d(TAG, "syncWallet: Found paid deposit ${deposit.quoteId} - attempting to claim")
                            try {
                                val claimResult = checkDepositStatus(deposit.quoteId)
                                if (claimResult.isSuccess && claimResult.getOrNull() == true) {
                                    Log.d(TAG, "syncWallet: Successfully claimed deposit ${deposit.quoteId}")
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "syncWallet: Failed to claim deposit ${deposit.quoteId}: ${e.message}")
                            }
                        }
                    }
                    is MintQuoteResult.NotFound -> {
                        // Quote definitively doesn't exist - safe to remove
                        Log.w(TAG, "syncWallet: Removing stale deposit ${deposit.quoteId} (404 - not found at mint)")
                        walletStorage.removePendingDeposit(deposit.quoteId)
                    }
                    is MintQuoteResult.Error -> {
                        // Network error - DON'T remove, quote may still be valid
                        Log.w(TAG, "syncWallet: Keeping deposit ${deposit.quoteId} (error: ${quoteResult.message})")
                    }
                }
            }

            // Step 1: Fetch proofs from ALL sources
            // Source A: NIP-60 (Nostr relays)
            val nip60Proofs = sync.fetchProofs(forceRefresh = true)
            val nip60Balance = nip60Proofs.sumOf { it.amount }
            val nip60Secrets = nip60Proofs.map { it.secret }.toSet()
            Log.d(TAG, "NIP-60: ${nip60Proofs.size} proofs ($nip60Balance sats)")

            // Source B: Local cdk-kotlin storage (may have proofs not yet synced to NIP-60)
            val localProofs = cashuBackend.getLocalProofs() ?: emptyList()
            val localBalance = localProofs.sumOf { it.amount }
            Log.d(TAG, "Local: ${localProofs.size} proofs ($localBalance sats)")

            // Source C: Unverified cache (proofs saved when mint was offline)
            val unverifiedCache = walletStorage.getUnverifiedProofs()
            Log.d(TAG, "Unverified cache: ${unverifiedCache.size} proofs")

            // Find local proofs NOT in NIP-60 (these need to be published)
            val localOnlyProofs = localProofs.filter { it.secret !in nip60Secrets }
            if (localOnlyProofs.isNotEmpty()) {
                Log.d(TAG, "Found ${localOnlyProofs.size} local proofs NOT in NIP-60 (${localOnlyProofs.sumOf { it.amount }} sats)")
            }

            if (nip60Proofs.isEmpty() && localProofs.isEmpty() && unverifiedCache.isEmpty()) {
                Log.d(TAG, "No proofs found from any source - balance is 0")
                _balance.value = WalletBalance(
                    availableSats = 0,
                    pendingSats = _balance.value.pendingSats,
                    lastUpdated = System.currentTimeMillis()
                )
                walletStorage.cacheBalance(_balance.value)
                walletStorage.clearUnverifiedProofs()
                updateDiagnostics()
                return@withLock SyncResult(
                    success = true,
                    message = "Synced: 0 sats (no proofs)",
                    verifiedBalance = 0,
                    unverifiedBalance = 0,
                    spentCount = 0,
                    mintReachable = true
                )
            }

            // Step 2: Merge all proofs and verify with mint
            // Combine NIP-60 proofs + local-only proofs (dedupe by secret)
            val nip60CashuProofs = nip60Proofs.map { it.toCashuProof() }
            val allProofsToVerify = (nip60CashuProofs + localOnlyProofs).distinctBy { it.secret }
            Log.d(TAG, "Verifying ${allProofsToVerify.size} total proofs at current mint: $mintUrl")

            val currentMintResult = cashuBackend.verifyProofsBalance(allProofsToVerify)

            if (currentMintResult != null) {
                val (_, spentSecrets) = currentMintResult

                // Separate valid from spent (for ALL proofs)
                val validProofs = allProofsToVerify.filter { it.secret !in spentSecrets }
                val validBalance = validProofs.sumOf { it.amount }
                val spentProofs = nip60Proofs.filter { it.secret in spentSecrets }
                val spentLocalProofs = localOnlyProofs.filter { it.secret in spentSecrets }

                Log.d(TAG, "Current mint verified: $validBalance sats valid, ${spentProofs.size} NIP-60 spent, ${spentLocalProofs.size} local spent")

                // Step 2b: Publish verified local-only proofs to NIP-60
                val validLocalOnlyProofs = localOnlyProofs.filter { it.secret !in spentSecrets }
                if (validLocalOnlyProofs.isNotEmpty()) {
                    val localOnlyBalance = validLocalOnlyProofs.sumOf { it.amount }
                    Log.d(TAG, "Publishing ${validLocalOnlyProofs.size} local proofs to NIP-60 ($localOnlyBalance sats)")
                    val newEventId = sync.publishProofs(validLocalOnlyProofs, mintUrl)
                    if (newEventId != null) {
                        Log.d(TAG, "Published local proofs to NIP-60: $newEventId")
                    } else {
                        Log.e(TAG, "Failed to publish local proofs to NIP-60 - saving recovery token")
                        saveRecoveryTokenFallback(validLocalOnlyProofs, mintUrl, "local_sync_nip60_failed")
                    }
                }

                // SAFETY: Only delete proofs that are EXPLICITLY marked SPENT by the mint
                // Do NOT delete proofs the mint doesn't recognize (they might have different Y computation)
                if (spentProofs.isNotEmpty()) {
                    Log.w(TAG, "Found ${spentProofs.size} proofs marked SPENT - will delete from NIP-60")
                    val spentEventIds = spentProofs.map { it.eventId }.distinct()
                    val spentSecrets = spentProofs.map { it.secret }.toSet()

                    // CRITICAL: One event can contain MANY proofs!
                    // Find remaining proofs in affected events that are NOT spent
                    val remainingProofsToRepublish = nip60Proofs.filter { proof ->
                        proof.eventId in spentEventIds && proof.secret !in spentSecrets
                    }

                    if (remainingProofsToRepublish.isNotEmpty()) {
                        Log.d(TAG, "Republishing ${remainingProofsToRepublish.size} remaining proofs before delete")
                        val cashuProofs = remainingProofsToRepublish.map { it.toCashuProof() }
                        // Include del array to atomically mark old events as consumed
                        val newEventId = sync.publishProofs(cashuProofs, mintUrl, spentEventIds)
                        if (newEventId == null) {
                            // Fallback: save recovery token for manual recovery
                            saveRecoveryTokenFallback(cashuProofs, mintUrl, "nip60_republish_failed_syncwallet")
                        } else {
                            Log.d(TAG, "Republished remaining proofs to new event: $newEventId (del: ${spentEventIds.size} events)")
                        }
                    }

                    // NIP-09 deletion as backup (del arrays are primary)
                    sync.deleteProofEvents(spentEventIds)
                    sync.clearCache()
                    Log.d(TAG, "Deleted ${spentEventIds.size} spent proof events")
                }

                // Check if any NIP-60 proofs have outdated mint URL and need re-publishing
                val validNip60Proofs = nip60Proofs.filter { it.secret !in spentSecrets }
                val proofsWithOldUrl = validNip60Proofs.filter {
                    normalizeUrl(it.mintUrl) != normalizeUrl(mintUrl)
                }

                if (proofsWithOldUrl.isNotEmpty()) {
                    Log.d(TAG, "Updating ${proofsWithOldUrl.size} proofs with new mint URL")
                    // Re-publish proofs with correct mint URL
                    sync.republishProofsWithNewMint(proofsWithOldUrl, mintUrl)
                }

                // Calculate final balance: verified NIP-60 proofs + verified local-only proofs
                // validBalance already includes both sources (calculated above)
                val localOnlyBalance = validLocalOnlyProofs.sumOf { it.amount }
                val syncedBalance = validBalance  // This is the total from all verified sources

                Log.d(TAG, "Final balance: $syncedBalance sats (NIP-60: ${validBalance - localOnlyBalance}, local-only: $localOnlyBalance)")

                // Update balance
                _balance.value = WalletBalance(
                    availableSats = syncedBalance,
                    pendingSats = _balance.value.pendingSats,
                    lastUpdated = System.currentTimeMillis()
                )
                walletStorage.cacheBalance(_balance.value)
                walletStorage.clearUnverifiedProofs()
                updateDiagnostics()

                val publishedMsg = if (validLocalOnlyProofs.isNotEmpty()) " (published ${validLocalOnlyProofs.size} local)" else ""
                val updatedMsg = if (proofsWithOldUrl.isNotEmpty()) " (updated ${proofsWithOldUrl.size} URLs)" else ""
                val spentMsg = if (spentProofs.isNotEmpty()) ", removed ${spentProofs.size} spent" else ""
                return@withLock SyncResult(
                    success = true,
                    message = "Synced: $syncedBalance sats$publishedMsg$updatedMsg$spentMsg",
                    verifiedBalance = syncedBalance,
                    unverifiedBalance = 0,
                    spentCount = spentProofs.size,
                    mintReachable = true
                )
            }

            // Step 3: Current mint didn't work - try other mints from NIP-60
            val uniqueMintUrls = nip60Proofs.map { it.mintUrl }.distinct()
                .filter { normalizeUrl(it) != normalizeUrl(mintUrl) }

            if (uniqueMintUrls.isNotEmpty()) {
                Log.d(TAG, "Current mint failed. Trying ${uniqueMintUrls.size} other mint(s)...")

                for (otherMintUrl in uniqueMintUrls) {
                    val proofsForMint = nip60Proofs.filter {
                        normalizeUrl(it.mintUrl) == normalizeUrl(otherMintUrl)
                    }
                    val proofsBalance = proofsForMint.sumOf { it.amount }

                    Log.d(TAG, "Trying mint: $otherMintUrl (${proofsForMint.size} proofs, $proofsBalance sats)")

                    val verifyResult = cashuBackend.verifyProofsBalance(
                        proofsForMint.map { it.toCashuProof() },
                        otherMintUrl
                    )

                    if (verifyResult != null) {
                        val (_, spentSecrets) = verifyResult
                        val validProofs = proofsForMint.filter { it.secret !in spentSecrets }
                        val validBalance = validProofs.sumOf { it.amount }

                        if (validBalance > 0) {
                            Log.d(TAG, "✓ Found $validBalance sats at $otherMintUrl - auto-switching")

                            // Clean up spent proofs
                            val spentEventIds = proofsForMint
                                .filter { it.secret in spentSecrets }
                                .map { it.eventId }
                                .distinct()
                            if (spentEventIds.isNotEmpty()) {
                                // CRITICAL: One event can contain MANY proofs!
                                // Find remaining proofs in affected events that are NOT spent
                                val remainingProofsToRepublish = nip60Proofs.filter { proof ->
                                    proof.eventId in spentEventIds && proof.secret !in spentSecrets
                                }

                                if (remainingProofsToRepublish.isNotEmpty()) {
                                    Log.d(TAG, "Republishing ${remainingProofsToRepublish.size} remaining proofs before delete")
                                    val cashuProofs = remainingProofsToRepublish.map { it.toCashuProof() }
                                    // Include del array to atomically mark old events as consumed
                                    val newEventId = sync.publishProofs(cashuProofs, otherMintUrl, spentEventIds)
                                    if (newEventId == null) {
                                        // Fallback: save recovery token for manual recovery
                                        saveRecoveryTokenFallback(cashuProofs, otherMintUrl, "nip60_republish_failed_mintswitch")
                                    } else {
                                        Log.d(TAG, "Republished remaining proofs to new event: $newEventId (del: ${spentEventIds.size} events)")
                                    }
                                }

                                // NIP-09 deletion as backup (del arrays are primary)
                                sync.deleteProofEvents(spentEventIds)
                                sync.clearCache()
                            }

                            // Switch to this mint
                            cashuBackend.disconnect()
                            val connected = cashuBackend.connect(otherMintUrl)
                            if (connected) {
                                walletStorage.saveMintUrl(otherMintUrl)
                                _currentMintName.value = DEFAULT_MINTS.find {
                                    normalizeUrl(it.url) == normalizeUrl(otherMintUrl)
                                }?.name ?: "Custom"
                                _isConnected.value = true

                                _balance.value = WalletBalance(
                                    availableSats = validBalance,
                                    pendingSats = _balance.value.pendingSats,
                                    lastUpdated = System.currentTimeMillis()
                                )
                                walletStorage.cacheBalance(_balance.value)
                                walletStorage.clearUnverifiedProofs()
                                updateDiagnostics()

                                val mintName = otherMintUrl.substringAfter("://").substringBefore("/")
                                return@withLock SyncResult(
                                    success = true,
                                    message = "Auto-switched to $mintName: $validBalance sats verified",
                                    verifiedBalance = validBalance,
                                    unverifiedBalance = 0,
                                    spentCount = spentEventIds.size,
                                    mintReachable = true
                                )
                            }
                        } else {
                            Log.d(TAG, "✗ All proofs at $otherMintUrl are spent")
                        }
                    } else {
                        Log.d(TAG, "✗ Could not reach mint: $otherMintUrl")
                    }
                }
            }

            // No mints worked - trust combined balance anyway (better than losing funds)
            // Include both NIP-60 and local proofs
            val totalUnverifiedBalance = nip60Balance + localBalance
            Log.w(TAG, "Could not verify proofs at any mint - trusting combined balance ($totalUnverifiedBalance sats)")
            _balance.value = WalletBalance(
                availableSats = totalUnverifiedBalance,  // Trust stored proofs instead of setting to 0
                pendingSats = _balance.value.pendingSats,
                lastUpdated = System.currentTimeMillis()
            )
            walletStorage.cacheBalance(_balance.value)
            updateDiagnostics()

            return@withLock SyncResult(
                success = true,  // Changed to true - we have a balance, just unverified
                message = "Synced: $totalUnverifiedBalance sats (unverified - mint unreachable)",
                verifiedBalance = 0,
                unverifiedBalance = totalUnverifiedBalance,
                spentCount = 0,
                mintReachable = false
            )
        } catch (e: Exception) {
            Log.e(TAG, "syncWallet failed: ${e.message}", e)
            return@withLock SyncResult(false, "Error: ${e.message}")
        }
    }

    /**
     * Normalize mint URL for comparison (remove trailing slash, lowercase).
     */
    private fun normalizeUrl(url: String): String {
        return url.trimEnd('/').lowercase()
    }

    /**
     * Merge NIP-60 proofs with locally cached unverified proofs.
     * NIP-60 takes precedence (source of truth), unverified cache fills gaps.
     */
    private fun mergeProofsBySecret(
        nip60Proofs: List<com.ridestr.common.payment.cashu.Nip60Proof>,
        unverifiedCache: List<UnverifiedProof>,
        currentMintUrl: String
    ): List<CashuProof> {
        val bySecret = mutableMapOf<String, CashuProof>()
        val normalizedCurrent = normalizeUrl(currentMintUrl)

        // Log unique mint URLs for debugging
        val uniqueMintUrls = nip60Proofs.map { it.mintUrl }.distinct()
        Log.d(TAG, "Current mint URL: '$currentMintUrl' (normalized: '$normalizedCurrent')")
        Log.d(TAG, "NIP-60 proof mint URLs: ${uniqueMintUrls.map { "'$it' -> '${normalizeUrl(it)}'" }}")

        // Unverified cache first (lower priority) - only for current mint
        for (p in unverifiedCache) {
            if (normalizeUrl(p.mintUrl) == normalizedCurrent) {
                bySecret[p.secret] = CashuProof(p.amount, p.id, p.secret, p.c)
            }
        }

        // NIP-60 proofs override (higher priority - the actual wallet)
        var matchedCount = 0
        var skippedCount = 0
        for (p in nip60Proofs) {
            if (normalizeUrl(p.mintUrl) == normalizedCurrent) {
                bySecret[p.secret] = p.toCashuProof()
                matchedCount++
            } else {
                skippedCount++
            }
        }

        Log.d(TAG, "Proof matching: $matchedCount matched, $skippedCount skipped (different mint)")

        return bySecret.values.toList()
    }

    /**
     * Cache unverified proofs locally when mint is offline.
     */
    private fun cacheUnverifiedProofs(proofs: List<CashuProof>, mintUrl: String) {
        if (proofs.isEmpty()) return

        val unverified = proofs.map { p ->
            UnverifiedProof(
                amount = p.amount,
                secret = p.secret,
                c = p.C,
                id = p.id,
                mintUrl = mintUrl,
                cachedAt = System.currentTimeMillis()
            )
        }
        walletStorage.saveUnverifiedProofs(unverified)
        Log.d(TAG, "Cached ${proofs.size} unverified proofs (${proofs.sumOf { it.amount }} sats)")
    }

    /**
     * Save proofs as a recovery token when NIP-60 publish fails.
     * These can be manually recovered later via Developer Options.
     *
     * @param proofs The proofs to save
     * @param mintUrl The mint URL
     * @param reason Why recovery is needed (for debugging)
     */
    private suspend fun saveRecoveryTokenFallback(proofs: List<CashuProof>, mintUrl: String, reason: String) {
        if (proofs.isEmpty()) return

        // Create cashuA token string
        val proofsArray = org.json.JSONArray()
        proofs.forEach { proof ->
            proofsArray.put(proof.toJson())
        }

        val tokenJson = org.json.JSONObject().apply {
            put("token", org.json.JSONArray().put(org.json.JSONObject().apply {
                put("mint", mintUrl)
                put("proofs", proofsArray)
            }))
            put("unit", "sat")
        }

        val tokenStr = "cashuA" + android.util.Base64.encodeToString(
            tokenJson.toString().toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
        )

        val totalAmount = proofs.sumOf { it.amount }
        val recoveryToken = RecoveryToken(
            id = java.util.UUID.randomUUID().toString(),
            token = tokenStr,
            amount = totalAmount,
            mintUrl = mintUrl,
            createdAt = System.currentTimeMillis(),
            reason = reason
        )

        walletStorage.saveRecoveryToken(recoveryToken)
        Log.w(TAG, "⚠️ Saved recovery token: $totalAmount sats (reason: $reason)")
        Log.w(TAG, "   Tap 'Recover Funds' in Wallet Settings to reclaim")

        // Notify UI that recovery tokens exist
        _hasRecoveryTokens.value = true

        // Also store in cdk-kotlin as additional backup
        cashuBackend.storeRecoveredProofs(proofs, mintUrl)
    }

    // === Escrow operations (for ride payments) ===

    /**
     * Lock funds for a ride escrow using NIP-60 proofs.
     *
     * Flow:
     * 1. Select proofs from NIP-60 (source of truth)
     * 2. Swap proofs at mint for HTLC-locked proofs
     * 3. Delete spent NIP-60 events
     * 4. Publish change proofs back to NIP-60
     *
     * @param amountSats Amount to lock in satoshis
     * @param paymentHash SHA256 hash that locks the funds
     * @param driverPubKey Driver's wallet public key for P2PK condition
     * @param expirySeconds Time until escrow expires (default 2 hours)
     * @return EscrowLock if successful, null on failure
     */
    suspend fun lockForRide(
        amountSats: Long,
        paymentHash: String,
        driverPubKey: String,
        expirySeconds: Long = 7200L
    ): EscrowLock? {
        if (!_isConnected.value) {
            Log.e(TAG, "Cannot lock funds - not connected to wallet provider")
            return null
        }

        val sync = nip60Sync
        if (sync == null) {
            Log.e(TAG, "Cannot lock funds - NIP-60 sync not initialized")
            return null
        }

        val mintUrl = cashuBackend.getCurrentMintUrl()
        if (mintUrl == null) {
            Log.e(TAG, "Cannot lock funds - mint URL not available")
            return null
        }

        Log.d(TAG, "=== LOCKING FUNDS FOR RIDE ===")
        Log.d(TAG, "Amount: $amountSats sats, paymentHash: ${paymentHash.take(16)}...")

        // Step 1: Select proofs from NIP-60
        var selection = sync.selectProofsForSpending(amountSats, mintUrl)

        // If NIP-60 insufficient, try syncing local cdk proofs first
        if (selection == null) {
            val cdkBalance = cashuBackend.getCdkBalance() ?: 0L
            Log.w(TAG, "NIP-60 insufficient for $amountSats - cdk has $cdkBalance, attempting auto-sync...")

            if (cdkBalance >= amountSats) {
                // cdk has enough - sync local proofs to NIP-60
                val syncResult = resyncProofsToNip60()
                if (syncResult.success) {
                    Log.d(TAG, "Auto-synced ${syncResult.proofsPublished} proofs to NIP-60, retrying selection...")
                    // Clear cache and retry
                    sync.clearCache()
                    selection = sync.selectProofsForSpending(amountSats, mintUrl)
                }
            }
        }

        if (selection == null) {
            Log.e(TAG, "Insufficient proofs for escrow: need $amountSats (after auto-sync attempt)")
            return null
        }

        Log.d(TAG, "Selected ${selection.proofs.size} proofs (${selection.totalAmount} sats, change: ${selection.changeAmount})")

        // Step 1.5: Check for corrupted C fields in NIP-60 proofs
        // Old NIP-60 events may have stored C as raw bytes, resulting in wrong length
        var proofsToVerify = selection.proofs.map { it.toCashuProof() }
        val hasCorruptedC = proofsToVerify.any { it.C.length != 66 }

        if (hasCorruptedC) {
            Log.w(TAG, "Detected corrupted C fields in NIP-60 proofs (wrong length) - forcing resync from local")
            val oldEventIds = selection.proofs.map { it.eventId }.distinct()

            // Delete corrupted NIP-60 events
            sync.deleteProofEvents(oldEventIds)
            sync.clearCache()

            // Try to get fresh proofs from cdk-kotlin
            val localProofs = cashuBackend.getLocalProofs()
            if (localProofs != null && localProofs.isNotEmpty()) {
                val localTotal = localProofs.sumOf { it.amount }
                Log.d(TAG, "Got ${localProofs.size} fresh proofs from cdk-kotlin ($localTotal sats)")

                // Republish fresh proofs to NIP-60
                val eventId = sync.publishProofs(localProofs, mintUrl)
                Log.d(TAG, "Republished fresh proofs to NIP-60: $eventId")

                // Retry selection with fresh proofs
                sync.clearCache()
                val freshSelection = sync.selectProofsForSpending(amountSats, mintUrl)
                if (freshSelection != null) {
                    proofsToVerify = freshSelection.proofs.map { it.toCashuProof() }
                    // Update selection reference for later use
                    selection = freshSelection
                    Log.d(TAG, "Fresh selection: ${freshSelection.proofs.size} proofs")
                }
            }
        }

        // Step 1.6: Verify selected proofs with mint (NUT-07) to catch stale proofs
        Log.d(TAG, "Verifying ${proofsToVerify.size} proofs with mint before HTLC swap...")
        val verifyResult = cashuBackend.verifyProofsBalance(proofsToVerify)

        if (verifyResult != null) {
            val (verifiedAmount, spentSecrets) = verifyResult
            Log.d(TAG, "Verification result: $verifiedAmount sats verified, ${spentSecrets.size} spent")

            if (spentSecrets.isNotEmpty()) {
                Log.w(TAG, "Found ${spentSecrets.size} SPENT proofs in NIP-60 selection - cleaning up stale events")

                // Find event IDs for spent proofs and delete them
                val spentEventIds = selection.proofs
                    .filter { it.secret in spentSecrets }
                    .map { it.eventId }
                    .distinct()

                // CRITICAL: One event can contain MANY proofs!
                // Find remaining proofs in affected events that are NOT spent
                val allProofs = sync.fetchProofs(forceRefresh = true)
                val remainingProofsToRepublish = allProofs.filter { proof ->
                    proof.eventId in spentEventIds && proof.secret !in spentSecrets
                }

                if (remainingProofsToRepublish.isNotEmpty()) {
                    Log.d(TAG, "Republishing ${remainingProofsToRepublish.size} remaining proofs before delete")
                    val cashuProofs = remainingProofsToRepublish.map { it.toCashuProof() }
                    // Include del array to atomically mark old events as consumed
                    val newEventId = sync.publishProofs(cashuProofs, mintUrl, spentEventIds)
                    if (newEventId == null) {
                        // Fallback: save recovery token for manual recovery
                        saveRecoveryTokenFallback(cashuProofs, mintUrl, "nip60_republish_failed_lockforride")
                    } else {
                        Log.d(TAG, "Republished remaining proofs to new event: $newEventId (del: ${spentEventIds.size} events)")
                    }
                }

                Log.d(TAG, "Deleting ${spentEventIds.size} stale NIP-60 events")
                // NIP-09 deletion as backup (del arrays are primary)
                sync.deleteProofEvents(spentEventIds)
                sync.clearCache()

                // Retry selection with cleaned cache
                Log.d(TAG, "Retrying proof selection after cleanup...")
                selection = sync.selectProofsForSpending(amountSats, mintUrl)

                if (selection == null) {
                    Log.e(TAG, "Insufficient proofs after removing stale ones (need $amountSats sats)")
                    return null
                }

                Log.d(TAG, "Retry selected ${selection.proofs.size} proofs (${selection.totalAmount} sats)")

                // Verify the new selection too
                val retryProofs = selection.proofs.map { it.toCashuProof() }
                val retryResult = cashuBackend.verifyProofsBalance(retryProofs)
                if (retryResult == null) {
                    Log.e(TAG, "Failed to verify retried proof selection")
                    return null
                }
                val (retryVerified, retrySpent) = retryResult
                if (retrySpent.isNotEmpty()) {
                    Log.e(TAG, "Retried selection still has ${retrySpent.size} spent proofs - giving up")
                    return null
                }
                Log.d(TAG, "Retried selection verified: $retryVerified sats")
            }
        } else {
            Log.e(TAG, "NUT-07 verification failed - cannot proceed without verification")
            return null
        }

        // Convert to CashuProof for mint operations
        val inputProofs = selection.proofs.map { it.toCashuProof() }

        val riderPubKey = walletKeyManager.getWalletPubKeyHex()
        if (riderPubKey == null) {
            Log.e(TAG, "lockForRide: no wallet pubkey available for refund")
            return null
        }
        val locktime = System.currentTimeMillis() / 1000 + expirySeconds

        // Step 2: Create HTLC token via mint swap
        val result = cashuBackend.createHtlcTokenFromProofs(
            inputProofs = inputProofs,
            paymentHash = paymentHash,
            amountSats = amountSats,
            driverPubKey = driverPubKey,
            locktime = locktime,
            riderPubKey = riderPubKey
        )

        if (result == null) {
            Log.e(TAG, "Failed to create HTLC token from proofs")
            return null
        }

        val htlcToken = result.htlcToken
        val changeProofs = result.changeProofs
        val htlcPendingOpId = result.pendingOpId

        // Step 3: CRITICAL - Republish remaining proofs BEFORE deleting events!
        // One event can contain MANY proofs. If we delete the event, we lose ALL proofs in it.
        val spentSecrets = selection.proofs.map { it.secret }.toSet()
        val affectedEventIds = selection.eventIds

        // Fetch all current proofs to find remaining (not spent) proofs in affected events
        val allProofs = sync.fetchProofs(forceRefresh = true)
        val remainingProofsToRepublish = allProofs.filter { proof ->
            proof.eventId in affectedEventIds && proof.secret !in spentSecrets
        }

        if (remainingProofsToRepublish.isNotEmpty()) {
            Log.d(TAG, "IMPORTANT: ${remainingProofsToRepublish.size} remaining proofs in affected events need republishing")
            Log.d(TAG, "Remaining: ${remainingProofsToRepublish.sumOf { it.amount }} sats")

            // Republish remaining proofs to a NEW event before deleting old
            // NIP-60: Include `del` array with original event IDs being replaced
            val cashuProofs = remainingProofsToRepublish.map { it.toCashuProof() }
            val newEventId = sync.publishProofs(cashuProofs, mintUrl, affectedEventIds)
            if (newEventId != null) {
                Log.d(TAG, "Republished remaining proofs to new event: $newEventId (with del: ${affectedEventIds.size} events)")
            } else {
                Log.e(TAG, "CRITICAL: Failed to republish remaining proofs!")
                Log.e(TAG, "Remaining amount: ${remainingProofsToRepublish.sumOf { it.amount }} sats")
                // Save recovery token for manual recovery
                saveRecoveryTokenFallback(cashuProofs, mintUrl, "nip60_republish_failed_lockforride_remaining")
            }
        }

        // NOW safe to delete old events (remaining proofs are in new event, or del array references them)
        Log.d(TAG, "Deleting ${affectedEventIds.size} old NIP-60 events (NIP-09 backup)")
        sync.deleteProofEvents(affectedEventIds)

        // Step 4: Publish change proofs to NIP-60 with retry
        // NIP-60: Include `del` with affected event IDs
        if (changeProofs.isNotEmpty()) {
            var changeEventId: String? = null
            var publishAttempts = 0
            val maxAttempts = 3
            val changeAmount = changeProofs.sumOf { it.amount }

            while (changeEventId == null && publishAttempts < maxAttempts) {
                publishAttempts++
                changeEventId = sync.publishProofs(changeProofs, mintUrl, affectedEventIds)
                if (changeEventId == null && publishAttempts < maxAttempts) {
                    Log.w(TAG, "Change publish failed, retrying...")
                    kotlinx.coroutines.delay(1000)
                }
            }

            if (changeEventId != null) {
                Log.d(TAG, "Published ${changeProofs.size} change proofs ($changeAmount sats) to NIP-60: $changeEventId (with del)")
            } else {
                Log.e(TAG, "Failed to publish change proofs after $maxAttempts attempts")
                // Save recovery token for manual recovery
                saveRecoveryTokenFallback(changeProofs, mintUrl, "nip60_change_failed_lockforride")
            }
        }

        // NOW safe to clear pending HTLC lock operation - proofs are persisted (NIP-60 or RecoveryToken)
        if (htlcPendingOpId != null) {
            walletStorage.removePendingBlindedOp(htlcPendingOpId)
            Log.d(TAG, "Cleared pending HTLC lock operation: $htlcPendingOpId")
        }

        val escrowId = PaymentCrypto.generatePreimage().take(16)  // 8 bytes as hex
        val lock = EscrowLock(
            escrowId = escrowId,
            htlcToken = htlcToken,
            amountSats = amountSats,
            expiresAt = locktime
        )

        // Save pending HTLC for potential refund if driver never claims
        val pendingHtlc = PendingHtlc(
            escrowId = escrowId,
            htlcToken = htlcToken,
            amountSats = amountSats,
            locktime = locktime,
            riderPubKey = riderPubKey,
            paymentHash = paymentHash
        )
        walletStorage.savePendingHtlc(pendingHtlc)
        Log.d(TAG, "Saved pending HTLC for refund tracking: $escrowId, expires at $locktime")

        // Update balance - ADD to pendingSats (in case of multiple concurrent HTLCs)
        val newBalance = _balance.value.availableSats - amountSats
        val newPending = _balance.value.pendingSats + amountSats
        _balance.value = _balance.value.copy(
            availableSats = newBalance,
            pendingSats = newPending,
            lastUpdated = System.currentTimeMillis()
        )
        walletStorage.cacheBalance(_balance.value)

        // Record transaction
        addTransaction(PaymentTransaction(
            id = escrowId,
            type = TransactionType.ESCROW_LOCK,
            amountSats = amountSats,
            timestamp = System.currentTimeMillis(),
            rideId = null,
            counterpartyPubKey = driverPubKey,
            status = "Locked for ride (HTLC)"
        ))

        Log.d(TAG, "=== FUNDS LOCKED ===")
        Log.d(TAG, "EscrowId: $escrowId, HTLC token created, balance: $newBalance sats")
        return lock
    }

    /**
     * Claim an HTLC payment using the preimage.
     *
     * @param htlcToken The HTLC token containing locked funds
     * @param preimage The 64-char hex preimage that unlocks the HTLC
     * @param paymentHash Optional payment hash to verify preimage (recommended)
     * @return SettlementResult if successful, null on failure
     */
    suspend fun claimHtlcPayment(
        htlcToken: String,
        preimage: String,
        paymentHash: String? = null
    ): SettlementResult? {
        if (!_isConnected.value) {
            Log.e(TAG, "Cannot claim HTLC - not connected to wallet provider")
            return null
        }

        // Verify preimage if payment hash is provided
        if (paymentHash != null) {
            if (!PaymentCrypto.verifyPreimage(preimage, paymentHash)) {
                Log.e(TAG, "Preimage verification failed - hash mismatch")
                return null
            }
        }

        Log.d(TAG, "=== CLAIMING HTLC PAYMENT ===")

        // Use the new method that returns proofs for NIP-60 publishing
        val claimResult = cashuBackend.claimHtlcTokenWithProofs(
            htlcToken = htlcToken,
            preimage = preimage
        )

        if (claimResult == null) {
            Log.e(TAG, "Failed to claim HTLC token")
            return null
        }

        val result = SettlementResult(
            amountSats = claimResult.amountSats,
            settlementProof = claimResult.settlementProof,
            timestamp = System.currentTimeMillis()
        )

        // Publish received proofs to NIP-60 for cross-device sync
        var proofsPersisted = false
        if (claimResult.receivedProofs.isNotEmpty()) {
            val sync = nip60Sync
            if (sync != null) {
                val eventId = sync.publishProofs(claimResult.receivedProofs, claimResult.mintUrl)
                if (eventId != null) {
                    Log.d(TAG, "Published ${claimResult.receivedProofs.size} received proofs to NIP-60: $eventId")
                    proofsPersisted = true
                } else {
                    Log.e(TAG, "CRITICAL: Failed to publish HTLC claim proofs to NIP-60!")
                    // Save recovery token for manual recovery
                    saveRecoveryTokenFallback(claimResult.receivedProofs, claimResult.mintUrl, "nip60_htlc_claim_failed")
                    proofsPersisted = true  // RecoveryToken counts as persisted
                }
            } else {
                // No NIP-60 sync, save to RecoveryToken
                Log.w(TAG, "No NIP-60 sync available, saving to RecoveryToken")
                saveRecoveryTokenFallback(claimResult.receivedProofs, claimResult.mintUrl, "no_nip60_sync")
                proofsPersisted = true
            }

            // NOW safe to clear pending op - proofs are persisted
            if (proofsPersisted && claimResult.pendingOpId != null) {
                walletStorage.removePendingBlindedOp(claimResult.pendingOpId)
                Log.d(TAG, "Cleared pending HTLC claim operation: ${claimResult.pendingOpId}")
            }

            // Note about cdk-kotlin
            Log.d(TAG, "Note: Proofs in NIP-60 only - cdk-kotlin withdrawals need NIP-60 melt implementation")
        } else if (claimResult.pendingOpId != null) {
            // No proofs but operation completed, safe to clear
            walletStorage.removePendingBlindedOp(claimResult.pendingOpId)
            Log.d(TAG, "Cleared pending HTLC claim operation (no proofs): ${claimResult.pendingOpId}")
        }

        // Refresh balance from NIP-60 (ensures consistency)
        val sync = nip60Sync
        val newBalance = if (sync != null) {
            sync.clearCache()
            val freshProofs = sync.fetchProofs(forceRefresh = true)
            val totalBalance = freshProofs.sumOf { it.amount }
            _balance.value = WalletBalance(
                availableSats = totalBalance,
                pendingSats = _balance.value.pendingSats,
                lastUpdated = System.currentTimeMillis()
            )
            walletStorage.cacheBalance(_balance.value)
            updateDiagnostics()
            totalBalance
        } else {
            // Fallback: local update only
            val localBalance = _balance.value.availableSats + claimResult.amountSats
            _balance.value = _balance.value.copy(
                availableSats = localBalance,
                lastUpdated = System.currentTimeMillis()
            )
            walletStorage.cacheBalance(_balance.value)
            localBalance
        }

        // Record transaction
        addTransaction(PaymentTransaction(
            id = "htlc-${System.currentTimeMillis()}",
            type = TransactionType.ESCROW_RECEIVE,
            amountSats = claimResult.amountSats,
            timestamp = System.currentTimeMillis(),
            rideId = null,
            counterpartyPubKey = null,
            status = "Ride payment received (HTLC settled)"
        ))

        Log.d(TAG, "=== HTLC CLAIMED ===")
        Log.d(TAG, "Received ${claimResult.amountSats} sats, refreshed balance: $newBalance")

        return result
    }

    // === HTLC Refund Operations ===

    /**
     * Check for and refund any expired HTLC escrows.
     *
     * Call this on wallet initialization to reclaim funds from rides where
     * the driver never claimed the payment.
     *
     * @return List of successfully refunded HTLCs with amounts
     */
    suspend fun refundExpiredHtlcs(): List<HtlcRefundInfo> {
        val refundable = walletStorage.getRefundableHtlcs()
        if (refundable.isEmpty()) {
            Log.d(TAG, "No expired HTLCs to refund")
            return emptyList()
        }

        Log.d(TAG, "=== REFUNDING ${refundable.size} EXPIRED HTLCs ===")
        val results = mutableListOf<HtlcRefundInfo>()

        for (htlc in refundable) {
            try {
                Log.d(TAG, "Refunding HTLC: ${htlc.escrowId}, ${htlc.amountSats} sats")

                val refundResult = cashuBackend.refundExpiredHtlc(
                    htlcToken = htlc.htlcToken,
                    riderPubKey = htlc.riderPubKey
                )

                if (refundResult != null) {
                    // Publish refunded proofs to NIP-60 with retry
                    val sync = nip60Sync
                    if (sync != null && refundResult.refundedProofs.isNotEmpty()) {
                        var eventId: String? = null
                        var publishAttempts = 0
                        val maxAttempts = 3

                        while (eventId == null && publishAttempts < maxAttempts) {
                            publishAttempts++
                            eventId = sync.publishProofs(refundResult.refundedProofs, refundResult.mintUrl)
                            if (eventId == null && publishAttempts < maxAttempts) {
                                Log.w(TAG, "Refund proof publish failed, retrying...")
                                kotlinx.coroutines.delay(1000)
                            }
                        }

                        if (eventId != null) {
                            Log.d(TAG, "Published ${refundResult.refundedProofs.size} refunded proofs to NIP-60: $eventId")
                        } else {
                            Log.e(TAG, "Failed to publish refunded proofs after $maxAttempts attempts")
                            // Save recovery token for manual recovery
                            saveRecoveryTokenFallback(refundResult.refundedProofs, refundResult.mintUrl, "nip60_refund_failed")
                        }
                    }

                    // NOW safe to clear pending refund operation - proofs are persisted (NIP-60 or RecoveryToken)
                    if (refundResult.pendingOpId != null) {
                        walletStorage.removePendingBlindedOp(refundResult.pendingOpId)
                        Log.d(TAG, "Cleared pending HTLC refund operation: ${refundResult.pendingOpId}")
                    }

                    // Update HTLC status
                    walletStorage.updateHtlcStatus(htlc.escrowId, PendingHtlcStatus.REFUNDED)

                    // Update balance
                    val newBalance = _balance.value.availableSats + refundResult.amountSats
                    _balance.value = _balance.value.copy(
                        availableSats = newBalance,
                        pendingSats = (_balance.value.pendingSats - htlc.amountSats).coerceAtLeast(0),
                        lastUpdated = System.currentTimeMillis()
                    )
                    walletStorage.cacheBalance(_balance.value)

                    // Record transaction
                    addTransaction(PaymentTransaction(
                        id = "refund-${htlc.escrowId}",
                        type = TransactionType.ESCROW_REFUND,
                        amountSats = refundResult.amountSats,
                        timestamp = System.currentTimeMillis(),
                        rideId = htlc.rideId,
                        counterpartyPubKey = null,
                        status = "HTLC expired - funds refunded"
                    ))

                    results.add(HtlcRefundInfo(
                        escrowId = htlc.escrowId,
                        amountSats = refundResult.amountSats,
                        success = true
                    ))

                    Log.d(TAG, "Refunded ${refundResult.amountSats} sats from HTLC ${htlc.escrowId}")
                } else {
                    Log.e(TAG, "Failed to refund HTLC ${htlc.escrowId}")
                    walletStorage.updateHtlcStatus(htlc.escrowId, PendingHtlcStatus.FAILED)
                    results.add(HtlcRefundInfo(
                        escrowId = htlc.escrowId,
                        amountSats = htlc.amountSats,
                        success = false
                    ))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refunding HTLC ${htlc.escrowId}: ${e.message}", e)
                results.add(HtlcRefundInfo(
                    escrowId = htlc.escrowId,
                    amountSats = htlc.amountSats,
                    success = false
                ))
            }
        }

        val successCount = results.count { it.success }
        val totalRefunded = results.filter { it.success }.sumOf { it.amountSats }
        Log.d(TAG, "=== HTLC REFUND COMPLETE: $successCount/${refundable.size} successful, $totalRefunded sats refunded ===")

        return results
    }

    /**
     * Mark an HTLC as claimed by the driver.
     * Call this when the driver successfully claims the HTLC.
     */
    fun markHtlcClaimed(escrowId: String) {
        walletStorage.updateHtlcStatus(escrowId, PendingHtlcStatus.CLAIMED)
        Log.d(TAG, "Marked HTLC as claimed: $escrowId")
    }

    /**
     * Get pending HTLCs that haven't been claimed or refunded yet.
     */
    fun getPendingHtlcs(): List<PendingHtlc> {
        return walletStorage.getPendingHtlcs().filter { it.isActive() }
    }

    /**
     * Get HTLCs that are ready for refund (locktime expired).
     */
    fun getRefundableHtlcs(): List<PendingHtlc> {
        return walletStorage.getRefundableHtlcs()
    }

    /**
     * Find a pending HTLC by payment hash.
     * Useful when the escrowId is not available but paymentHash is known.
     */
    fun findHtlcByPaymentHash(paymentHash: String): PendingHtlc? {
        return walletStorage.getPendingHtlcs().find { it.paymentHash == paymentHash }
    }

    /**
     * Mark an HTLC as claimed by payment hash (when escrowId is not available).
     * Call this when the driver successfully claims the HTLC.
     *
     * @return true if HTLC was found and marked, false if not found
     */
    fun markHtlcClaimedByPaymentHash(paymentHash: String): Boolean {
        val htlc = findHtlcByPaymentHash(paymentHash)
        if (htlc != null) {
            walletStorage.updateHtlcStatus(htlc.escrowId, PendingHtlcStatus.CLAIMED)

            // Clear pendingSats since the HTLC is now claimed (funds are gone)
            val newPending = (_balance.value.pendingSats - htlc.amountSats).coerceAtLeast(0)
            _balance.value = _balance.value.copy(
                pendingSats = newPending,
                lastUpdated = System.currentTimeMillis()
            )
            walletStorage.cacheBalance(_balance.value)

            Log.d(TAG, "Marked HTLC as claimed by paymentHash: ${paymentHash.take(16)}..., cleared ${htlc.amountSats} sats from pending")
            return true
        }
        Log.w(TAG, "No pending HTLC found for paymentHash: ${paymentHash.take(16)}...")
        return false
    }

    /**
     * Info about a refunded HTLC.
     */
    data class HtlcRefundInfo(
        val escrowId: String,
        val amountSats: Long,
        val success: Boolean
    )

    /**
     * Get raw diagnostic info about wallet state.
     * This does NOT delete or modify anything - safe to call.
     */
    suspend fun getRawDiagnostics(): String {
        val sb = StringBuilder()
        sb.appendLine("=== RAW WALLET DIAGNOSTICS ===")

        // 1. NIP-60 proofs
        val sync = nip60Sync
        if (sync != null) {
            try {
                val nip60Proofs = sync.fetchProofs(forceRefresh = true)
                val nip60Total = nip60Proofs.sumOf { it.amount }
                val uniqueMints = nip60Proofs.map { it.mintUrl }.distinct()
                sb.appendLine("NIP-60: ${nip60Proofs.size} proofs, $nip60Total sats")
                sb.appendLine("Mints in NIP-60: ${uniqueMints.size}")
                uniqueMints.forEach { mint ->
                    val proofsAtMint = nip60Proofs.filter { it.mintUrl == mint }
                    val balanceAtMint = proofsAtMint.sumOf { it.amount }
                    sb.appendLine("  - ${mint.substringAfter("://").take(30)}: ${proofsAtMint.size} proofs, $balanceAtMint sats")
                }
            } catch (e: Exception) {
                sb.appendLine("NIP-60: Error - ${e.message}")
            }
        } else {
            sb.appendLine("NIP-60: Not initialized")
        }

        // 2. cdk-kotlin local proofs
        val cdkBalance = cashuBackend.getCdkBalance()
        val localProofs = cashuBackend.getLocalProofs()
        sb.appendLine("cdk-kotlin: ${localProofs?.size ?: 0} proofs, ${cdkBalance ?: 0} sats")

        // 3. Current mint
        val mintUrl = cashuBackend.getCurrentMintUrl()
        sb.appendLine("Current mint: ${mintUrl?.substringAfter("://")?.take(40) ?: "none"}")

        // 4. Pending HTLCs
        val pendingHtlcs = walletStorage.getPendingHtlcs()
        val refundableHtlcs = walletStorage.getRefundableHtlcs()
        sb.appendLine("Pending HTLCs: ${pendingHtlcs.size} (${pendingHtlcs.sumOf { it.amountSats }} sats)")
        sb.appendLine("Refundable HTLCs: ${refundableHtlcs.size} (${refundableHtlcs.sumOf { it.amountSats }} sats)")

        // 5. Pending deposits
        val pendingDeposits = walletStorage.getPendingDeposits()
        sb.appendLine("Pending deposits: ${pendingDeposits.size}")

        // 6. Unverified cache
        val unverifiedProofs = walletStorage.getUnverifiedProofs()
        sb.appendLine("Unverified cache: ${unverifiedProofs.size} proofs, ${unverifiedProofs.sumOf { it.amount }} sats")

        // 7. Displayed balance
        sb.appendLine("Displayed balance: ${_balance.value.availableSats} sats")

        return sb.toString()
    }

    // === Pending Operation Recovery ===

    /**
     * Check for and recover from pending blinded operations.
     *
     * This handles operations that were interrupted (crash, network error, etc.)
     * after we sent the request but before we received/processed the response.
     *
     * Recovery strategy:
     * 1. For each pending operation, check input proof states (NUT-07)
     * 2. If inputs are UNSPENT: operation failed, inputs are safe, mark FAILED
     * 3. If inputs are SPENT: operation succeeded, outputs should exist
     * 4. If inputs are PENDING: operation still in progress, wait
     *
     * Note: For melts that went PENDING, change proofs may be lost because
     * GET /melt/quote doesn't return signed change outputs.
     */
    suspend fun recoverPendingOperations(): List<PendingOpRecoveryResult> {
        val results = mutableListOf<PendingOpRecoveryResult>()

        val pendingOps = walletStorage.getRecoverableBlindedOps()
        if (pendingOps.isEmpty()) {
            Log.d(TAG, "No pending operations to recover")
            return results
        }

        Log.d(TAG, "=== RECOVERING ${pendingOps.size} PENDING OPERATIONS ===")

        for (op in pendingOps) {
            try {
                Log.d(TAG, "Checking operation ${op.id}: ${op.operationType}, ${op.amountSats} sats")

                // For operations with no inputs (like MINT), we can't verify state
                if (op.inputSecrets.isEmpty()) {
                    // For mint operations, check the quote status
                    if (op.operationType == BlindedOperationType.MINT && op.quoteId != null) {
                        val quote = cashuBackend.checkMintQuote(op.quoteId)
                        if (quote != null) {
                            when (quote.state) {
                                MintQuoteState.ISSUED -> {
                                    // Already minted - tokens should be in wallet
                                    Log.d(TAG, "Mint quote ${op.quoteId} already ISSUED - marking as RECOVERED")
                                    walletStorage.updateBlindedOpStatus(op.id, PendingOperationStatus.RECOVERED)
                                    results.add(PendingOpRecoveryResult(op.id, op.operationType, true, "Already minted"))
                                }
                                MintQuoteState.PAID -> {
                                    // Paid but not minted - we could try to mint again
                                    Log.d(TAG, "Mint quote ${op.quoteId} is PAID - could retry mint")
                                    results.add(PendingOpRecoveryResult(op.id, op.operationType, false, "Paid but not minted - retry needed"))
                                }
                                MintQuoteState.UNPAID -> {
                                    // Not paid - safe to remove
                                    Log.d(TAG, "Mint quote ${op.quoteId} is UNPAID - marking as FAILED")
                                    walletStorage.updateBlindedOpStatus(op.id, PendingOperationStatus.FAILED)
                                    results.add(PendingOpRecoveryResult(op.id, op.operationType, true, "Quote not paid"))
                                }
                            }
                        }
                    }
                    continue
                }

                // Check input proof states
                val inputProofs = op.inputSecrets.map { secret ->
                    CashuProof(amount = 0, id = "", secret = secret, C = "")
                }
                val verifyResult = cashuBackend.verifyProofsBalance(inputProofs, op.mintUrl)

                if (verifyResult == null) {
                    Log.w(TAG, "Could not verify input states for ${op.id} - mint may be unreachable")
                    results.add(PendingOpRecoveryResult(op.id, op.operationType, false, "Could not verify - mint unreachable"))
                    continue
                }

                val (_, spentSecrets) = verifyResult
                val allSpent = op.inputSecrets.all { it in spentSecrets }
                val noneSpent = op.inputSecrets.none { it in spentSecrets }

                when {
                    allSpent -> {
                        // Operation succeeded - inputs were consumed
                        Log.d(TAG, "Inputs for ${op.id} are SPENT - operation succeeded")

                        // For melts, check if we can recover change
                        if (op.operationType == BlindedOperationType.MELT && op.quoteId != null) {
                            val quote = cashuBackend.checkMeltQuote(op.quoteId)
                            if (quote?.state == MeltQuoteState.PAID) {
                                Log.d(TAG, "Melt ${op.quoteId} is PAID - change may be lost (no C_ values)")
                                // Can't recover change without C_ values from mint response
                            }
                        }

                        walletStorage.updateBlindedOpStatus(op.id, PendingOperationStatus.RECOVERED)
                        results.add(PendingOpRecoveryResult(op.id, op.operationType, true,
                            "Inputs spent - operation succeeded (outputs may need manual recovery)"))
                    }
                    noneSpent -> {
                        // Operation failed - inputs are still safe
                        Log.d(TAG, "Inputs for ${op.id} are UNSPENT - operation failed, inputs safe")
                        walletStorage.updateBlindedOpStatus(op.id, PendingOperationStatus.FAILED)
                        walletStorage.removePendingBlindedOp(op.id)
                        results.add(PendingOpRecoveryResult(op.id, op.operationType, true, "Inputs still available"))
                    }
                    else -> {
                        // Partial spend - shouldn't happen in normal operation
                        Log.w(TAG, "Partial spend for ${op.id} - ${spentSecrets.size}/${op.inputSecrets.size} spent")
                        results.add(PendingOpRecoveryResult(op.id, op.operationType, false, "Partial spend - investigate"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error recovering operation ${op.id}: ${e.message}", e)
                results.add(PendingOpRecoveryResult(op.id, op.operationType, false, "Error: ${e.message}"))
            }
        }

        // Clean up expired operations
        walletStorage.cleanupExpiredBlindedOps()

        Log.d(TAG, "Recovery complete: ${results.count { it.success }}/${results.size} successful")
        return results
    }

    /**
     * Result of attempting to recover a pending blinded operation.
     */
    data class PendingOpRecoveryResult(
        val operationId: String,
        val operationType: BlindedOperationType,
        val success: Boolean,
        val message: String
    )

    // === Deposit/Withdraw Operations ===

    /**
     * Request a deposit by generating a Lightning invoice.
     *
     * IMPORTANT: This saves a PendingDeposit BEFORE returning the invoice.
     * This prevents fund loss if the app crashes after payment but before minting.
     *
     * @param amountSats Amount to deposit in satoshis
     * @return Result containing MintQuote with Lightning invoice
     */
    suspend fun requestDeposit(amountSats: Long): Result<MintQuote> {
        if (!_isConnected.value) {
            return Result.failure(Exception("Not connected to wallet provider"))
        }

        val quote = cashuBackend.getMintQuote(amountSats)
            ?: return Result.failure(Exception("Failed to get deposit quote"))

        // Save pending deposit BEFORE showing invoice to user
        // This allows recovery if app crashes after payment
        val pendingDeposit = PendingDeposit(
            quoteId = quote.quote,
            amount = amountSats,
            invoice = quote.request,
            createdAt = System.currentTimeMillis(),
            expiry = quote.expiry,
            minted = false
        )
        walletStorage.savePendingDeposit(pendingDeposit)

        Log.d(TAG, "Created deposit quote for $amountSats sats, invoice=${quote.request.take(30)}...")
        return Result.success(quote)
    }

    /**
     * Check if a deposit has been paid.
     *
     * @param quoteId The mint quote ID
     * @return Result containing true if paid, false if still pending
     */
    suspend fun checkDepositStatus(quoteId: String): Result<Boolean> {
        val quote = cashuBackend.checkMintQuote(quoteId)
            ?: return Result.failure(Exception("Failed to check deposit status"))

        if (quote.isPaid()) {
            // CRITICAL: Require NIP-60 sync BEFORE minting to prevent funds getting stuck in cdk-kotlin
            val sync = nip60Sync
            val mintUrl = cashuBackend.getCurrentMintUrl()

            if (sync == null) {
                Log.e(TAG, "NIP-60 not initialized - cannot complete deposit safely")
                return Result.failure(Exception("Wallet sync not ready. Please wait and try again."))
            }

            if (mintUrl == null) {
                Log.e(TAG, "Mint URL not available")
                return Result.failure(Exception("Not connected to mint"))
            }

            // Mint the tokens (this stores in cdk-kotlin)
            val mintResult = cashuBackend.mintTokens(quoteId, quote.amount)
            if (mintResult != null && mintResult.success) {
                // Publish proofs to NIP-60 - THIS MUST SUCCEED for NIP-60 primary architecture
                if (mintResult.proofs.isNotEmpty()) {
                    // Try publishing with exponential backoff retry (relay connection can be flaky)
                    var eventId: String? = null
                    val delays = listOf(1000L, 2000L, 4000L)  // Exponential backoff: 1s, 2s, 4s

                    for ((attempt, delay) in delays.withIndex()) {
                        Log.d(TAG, "Publishing proofs to NIP-60 (attempt ${attempt + 1}/${delays.size})")
                        eventId = sync.publishProofs(mintResult.proofs, mintUrl)
                        if (eventId != null) break
                        if (attempt < delays.lastIndex) {
                            Log.w(TAG, "NIP-60 publish failed, retrying in ${delay}ms...")
                            kotlinx.coroutines.delay(delay)
                        }
                    }

                    if (eventId != null) {
                        Log.d(TAG, "Published ${mintResult.proofs.size} proofs to NIP-60: $eventId")
                    } else {
                        // NIP-60 publish failed after retries - save recovery token
                        Log.e(TAG, "CRITICAL: Failed to publish proofs to NIP-60 after all attempts!")
                        Log.e(TAG, "Saving recovery token for ${mintResult.proofs.sumOf { it.amount }} sats")
                        saveRecoveryTokenFallback(mintResult.proofs, mintUrl, "deposit_nip60_failed")
                        // Still mark deposit as complete - tokens are safely saved for recovery
                    }
                }

                // Update balance IMMEDIATELY with the minted amount
                // Don't wait for NIP-60 round-trip - we know exactly what we just minted
                val mintedAmount = mintResult.proofs.sumOf { it.amount }
                val currentBalance = _balance.value.availableSats
                val newBalance = currentBalance + mintedAmount

                _balance.value = WalletBalance(
                    availableSats = newBalance,
                    pendingSats = _balance.value.pendingSats,
                    lastUpdated = System.currentTimeMillis()
                )
                walletStorage.cacheBalance(_balance.value)

                Log.d(TAG, "Deposit complete: $mintedAmount sats minted, balance updated: $currentBalance → $newBalance sats")

                // Clear NIP-60 cache so next sync fetches fresh data
                sync.clearCache()
                updateDiagnostics()

                // Mark pending deposit as minted and remove it
                walletStorage.markDepositMinted(quoteId)
                walletStorage.removePendingDeposit(quoteId)

                // Record transaction
                addTransaction(PaymentTransaction(
                    id = quoteId,
                    type = TransactionType.DEPOSIT,
                    amountSats = quote.amount,
                    timestamp = System.currentTimeMillis(),
                    rideId = null,
                    counterpartyPubKey = null,
                    status = "Deposited via Lightning"
                ))

                Log.d(TAG, "Deposit complete: ${quote.amount} sats")
                return Result.success(true)
            } else {
                Log.e(TAG, "Minting failed for quote $quoteId - tokens may need recovery")
            }
        }
        return Result.success(false)
    }

    /**
     * Mint tokens for a deposit that was paid via Lightning.
     * Used for cross-mint bridge payments where the driver needs to claim tokens
     * after the rider paid their deposit invoice.
     *
     * @param quoteId The mint quote ID from getDepositInvoice()
     * @param amountSats The amount in sats to mint
     * @return MintTokensResult with success status and proofs
     */
    suspend fun mintTokens(quoteId: String, amountSats: Long): MintTokensResult? {
        if (!_isConnected.value) {
            Log.e(TAG, "mintTokens: not connected to wallet")
            return null
        }

        val sync = nip60Sync
        val mintUrl = cashuBackend.getCurrentMintUrl()

        if (sync == null) {
            Log.e(TAG, "mintTokens: NIP-60 sync not ready")
            return null
        }

        if (mintUrl == null) {
            Log.e(TAG, "mintTokens: mint URL not available")
            return null
        }

        try {
            Log.d(TAG, "Minting tokens for cross-mint deposit: quoteId=$quoteId, amount=$amountSats")

            // Mint the tokens via cdk-kotlin
            val mintResult = cashuBackend.mintTokens(quoteId, amountSats)
            if (mintResult == null || !mintResult.success) {
                Log.e(TAG, "mintTokens: cdk-kotlin mint failed")
                // Try HTTP-based recovery as fallback
                val recoveredProofs = cashuBackend.recoverDeposit(quoteId, amountSats)
                if (recoveredProofs != null && recoveredProofs.isNotEmpty()) {
                    Log.d(TAG, "Recovered ${recoveredProofs.size} proofs via HTTP fallback")
                    // Publish recovered proofs to NIP-60
                    publishProofsToNip60(recoveredProofs, mintUrl, sync)
                    refreshBalance()
                    return MintTokensResult(
                        success = true,
                        proofs = recoveredProofs,
                        totalSats = recoveredProofs.sumOf { it.amount }
                    )
                }
                return null
            }

            // Publish proofs to NIP-60
            if (mintResult.proofs.isNotEmpty()) {
                publishProofsToNip60(mintResult.proofs, mintUrl, sync)
            }

            // Update balance IMMEDIATELY with the minted amount
            // Don't wait for NIP-60 round-trip - we know exactly what we just minted
            val mintedAmount = mintResult.proofs.sumOf { it.amount }
            val currentBalance = _balance.value.availableSats
            val newBalance = currentBalance + mintedAmount

            _balance.value = WalletBalance(
                availableSats = newBalance,
                pendingSats = _balance.value.pendingSats,
                lastUpdated = System.currentTimeMillis()
            )
            walletStorage.cacheBalance(_balance.value)

            // Clear NIP-60 cache so next sync fetches fresh data
            sync.clearCache()
            updateDiagnostics()

            Log.d(TAG, "Cross-mint deposit complete: $mintedAmount sats minted, balance: $currentBalance → $newBalance")

            // Record transaction
            addTransaction(PaymentTransaction(
                id = quoteId,
                type = TransactionType.ESCROW_RECEIVE,
                amountSats = amountSats,
                timestamp = System.currentTimeMillis(),
                rideId = null,
                counterpartyPubKey = null,
                status = "Cross-mint payment received"
            ))

            return mintResult
        } catch (e: Exception) {
            Log.e(TAG, "Exception in mintTokens: ${e.message}", e)
            return null
        }
    }

    /**
     * Helper to publish proofs to NIP-60 with retry.
     */
    private suspend fun publishProofsToNip60(
        proofs: List<CashuProof>,
        mintUrl: String,
        sync: Nip60WalletSync
    ): String? {
        var eventId: String? = null
        var attempts = 0
        val maxAttempts = 3

        while (eventId == null && attempts < maxAttempts) {
            attempts++
            Log.d(TAG, "Publishing proofs to NIP-60 (attempt $attempts/$maxAttempts)")
            eventId = sync.publishProofs(proofs, mintUrl)

            if (eventId == null && attempts < maxAttempts) {
                Log.w(TAG, "NIP-60 publish failed, retrying in 2 seconds...")
                kotlinx.coroutines.delay(2000)
            }
        }

        if (eventId != null) {
            Log.d(TAG, "Published ${proofs.size} proofs to NIP-60: $eventId")
        } else {
            Log.e(TAG, "CRITICAL: Failed to publish proofs to NIP-60 after $maxAttempts attempts!")
        }

        return eventId
    }

    /**
     * Get a melt quote for withdrawing funds.
     *
     * @param bolt11 Lightning invoice to pay
     * @return Result containing MeltQuote with fee info
     */
    suspend fun getMeltQuote(bolt11: String): Result<MeltQuote> {
        if (!_isConnected.value) {
            return Result.failure(Exception("Not connected to wallet provider"))
        }

        val quote = cashuBackend.getMeltQuote(bolt11)
            ?: return Result.failure(Exception("Failed to get withdrawal quote"))

        return Result.success(quote)
    }

    /**
     * Execute a withdrawal.
     * NIP-60 PRIMARY: Uses proofs from Nostr relays, publishes change back to NIP-60.
     *
     * @param quote The melt quote to execute
     * @return Result indicating success or failure
     */
    suspend fun executeWithdraw(quote: MeltQuote): Result<Unit> {
        if (!_isConnected.value) {
            return Result.failure(Exception("Not connected to wallet provider"))
        }

        val sync = nip60Sync
        val mintUrl = cashuBackend.getCurrentMintUrl()

        if (sync == null) {
            return Result.failure(Exception("Wallet sync not ready"))
        }

        if (mintUrl == null) {
            return Result.failure(Exception("Not connected to mint"))
        }

        // NIP-60 PRIMARY: Select proofs from Nostr relays
        val selection = sync.selectProofsForSpending(quote.totalAmount, mintUrl)
        if (selection == null) {
            Log.e(TAG, "Insufficient funds in NIP-60 for withdrawal")
            return Result.failure(Exception("Insufficient funds"))
        }

        var proofs = selection.proofs.map { it.toCashuProof() }
        val selectedAmount = selection.totalAmount
        val affectedEventIds = selection.proofs.map { it.eventId }.distinct()
        Log.d(TAG, "Selected ${proofs.size} proofs from NIP-60 ($selectedAmount sats) for ${quote.totalAmount} sats withdrawal")

        // === PRE-SWAP FOR EXACT AMOUNT (Prevents change loss on pending melt) ===
        // If we have more than needed, swap to get exact amount + remaining.
        // Swap returns ALL outputs immediately, so no change can be lost.
        var swapPendingOpId: String? = null
        if (selectedAmount > quote.totalAmount) {
            Log.d(TAG, "Pre-swap: need exact ${quote.totalAmount} sats, have $selectedAmount sats")

            val swapResult = cashuBackend.swapToExactAmount(proofs, quote.totalAmount, mintUrl)
            if (swapResult == null) {
                Log.e(TAG, "Pre-swap failed - falling back to standard melt with change")
                // Fall through to standard melt (may lose change if pending)
            } else {
                swapPendingOpId = swapResult.pendingOpId

                // Publish remaining proofs to NIP-60 BEFORE melt (already received from swap)
                // NIP-60: Include `del` array with original event IDs being replaced
                if (swapResult.remainingProofs.isNotEmpty()) {
                    val remainingAmount = swapResult.remainingProofs.sumOf { it.amount }
                    Log.d(TAG, "Pre-swap: publishing $remainingAmount sats remaining to NIP-60 (replacing ${affectedEventIds.size} events)")

                    var remainingEventId: String? = null
                    val delays = listOf(1000L, 2000L, 4000L)  // Exponential backoff
                    for ((attempt, delay) in delays.withIndex()) {
                        remainingEventId = sync.publishProofs(swapResult.remainingProofs, mintUrl, affectedEventIds)
                        if (remainingEventId != null) break
                        if (attempt < delays.lastIndex) {
                            Log.w(TAG, "Remaining proofs publish failed, retrying in ${delay}ms...")
                            kotlinx.coroutines.delay(delay)
                        }
                    }

                    if (remainingEventId != null) {
                        Log.d(TAG, "Pre-swap: remaining proofs published to NIP-60: $remainingEventId")
                    } else {
                        Log.e(TAG, "CRITICAL: Failed to publish remaining proofs after pre-swap!")
                        saveRecoveryTokenFallback(swapResult.remainingProofs, mintUrl, "preswap_remaining_failed")
                        // Continue anyway - we still have exact proofs for melt
                    }
                }

                // Use exact proofs for melt - NO CHANGE NEEDED
                proofs = swapResult.exactProofs
                Log.d(TAG, "Pre-swap complete: using ${proofs.size} exact proofs (${proofs.sumOf { it.amount }} sats)")
            }
        }

        // Execute melt with proofs (either exact from swap, or original with potential change)
        val result = cashuBackend.meltWithProofs(quote.quote, proofs, quote.totalAmount)

        if (result == null) {
            return Result.failure(Exception("Withdrawal failed - mint error"))
        }

        // Handle pending state - payment may complete later
        if (result.isPending) {
            Log.w(TAG, "Withdrawal is PENDING - Lightning payment still in progress")
            Log.w(TAG, "Proofs are locked at mint. Payment may complete later.")
            // DON'T delete proofs yet - they might be released if payment fails
            // But warn user that funds are temporarily unavailable
            return Result.failure(Exception("Withdrawal pending - check balance in a few minutes"))
        }

        if (result.paid) {
            // Track events to delete - only delete AFTER all proofs are safely published
            val eventsToDelete = mutableListOf<String>()
            var allPublishesSucceeded = true

            // === Handle remaining proofs from original NIP-60 events ===
            // (Only needed if we didn't pre-swap, since pre-swap already published remaining)
            if (swapPendingOpId == null) {
                // Standard melt path - need to republish remaining proofs from affected events
                val spentSecrets = selection.proofs.map { it.secret }.toSet()

                // Fetch all current proofs to find remaining (not spent) proofs in affected events
                val allProofs = sync.fetchProofs(forceRefresh = true)
                val remainingProofsToRepublish = allProofs.filter { proof ->
                    proof.eventId in affectedEventIds && proof.secret !in spentSecrets
                }

                if (remainingProofsToRepublish.isNotEmpty()) {
                    Log.d(TAG, "Republishing ${remainingProofsToRepublish.size} remaining proofs (${remainingProofsToRepublish.sumOf { it.amount }} sats)")

                    val cashuProofs = remainingProofsToRepublish.map { it.toCashuProof() }
                    var newEventId: String? = null
                    val delays = listOf(1000L, 2000L, 4000L)  // Exponential backoff

                    for ((attempt, delay) in delays.withIndex()) {
                        // NIP-60: Include `del` array with original event IDs being replaced
                        newEventId = sync.publishProofs(cashuProofs, mintUrl, affectedEventIds)
                        if (newEventId != null) break
                        if (attempt < delays.lastIndex) {
                            Log.w(TAG, "Remaining proofs publish failed, retrying in ${delay}ms...")
                            kotlinx.coroutines.delay(delay)
                        }
                    }

                    if (newEventId != null) {
                        Log.d(TAG, "Republished remaining proofs: $newEventId (with del: ${affectedEventIds.size} events)")
                        eventsToDelete.addAll(affectedEventIds)
                    } else {
                        Log.e(TAG, "CRITICAL: Failed to republish remaining proofs!")
                        saveRecoveryTokenFallback(cashuProofs, mintUrl, "nip60_republish_failed_withdraw")
                        allPublishesSucceeded = false
                        // DON'T delete old events - we need those proofs!
                    }
                } else {
                    // No remaining proofs in affected events - change proofs will carry the del reference
                    eventsToDelete.addAll(affectedEventIds)
                }
            } else {
                // Pre-swap path - remaining proofs already published, original events can be deleted
                eventsToDelete.addAll(affectedEventIds)

                // Clean up swap pending operation
                walletStorage.removePendingBlindedOp(swapPendingOpId)
                Log.d(TAG, "Cleared pre-swap pending operation: $swapPendingOpId")
            }

            // === Handle change proofs from melt (if not pre-swapped) ===
            // NIP-60: Include `del` with affected event IDs (especially if no remaining proofs)
            if (result.change.isNotEmpty()) {
                val changeAmount = result.change.sumOf { it.amount }
                Log.d(TAG, "Publishing $changeAmount sats change proofs to NIP-60")

                var changeEventId: String? = null
                val delays = listOf(1000L, 2000L, 4000L)  // Exponential backoff

                for ((attempt, delay) in delays.withIndex()) {
                    // Include del array - ensures old events are marked as consumed
                    changeEventId = sync.publishProofs(result.change, mintUrl, affectedEventIds)
                    if (changeEventId != null) break
                    if (attempt < delays.lastIndex) {
                        Log.w(TAG, "Change publish failed, retrying in ${delay}ms...")
                        kotlinx.coroutines.delay(delay)
                    }
                }

                if (changeEventId != null) {
                    Log.d(TAG, "Published change proofs ($changeAmount sats): $changeEventId (with del: ${affectedEventIds.size} events)")
                } else {
                    Log.e(TAG, "CRITICAL: Failed to publish change proofs!")
                    saveRecoveryTokenFallback(result.change, mintUrl, "nip60_change_failed_withdraw")
                    allPublishesSucceeded = false
                }
            }

            // === Atomic deletion: Only delete old events AFTER all publishes succeeded ===
            if (eventsToDelete.isNotEmpty()) {
                if (allPublishesSucceeded) {
                    sync.deleteProofEvents(eventsToDelete)
                    Log.d(TAG, "Deleted ${eventsToDelete.size} old NIP-60 proof events")
                } else {
                    Log.w(TAG, "Skipping deletion of ${eventsToDelete.size} events - some publishes failed")
                    // Events will be cleaned up on next sync when proofs are verified
                }
            }

            // Clear melt pending operation (if any)
            if (result.pendingOpId != null) {
                walletStorage.removePendingBlindedOp(result.pendingOpId)
                Log.d(TAG, "Cleared melt pending operation: ${result.pendingOpId}")
            }

            // Clear cache and refresh balance from NIP-60
            sync.clearCache()
            val newProofs = sync.fetchProofs(forceRefresh = true)
            val newBalance = newProofs.sumOf { it.amount }

            _balance.value = WalletBalance(
                availableSats = newBalance,
                pendingSats = _balance.value.pendingSats,
                lastUpdated = System.currentTimeMillis()
            )
            walletStorage.cacheBalance(_balance.value)
            updateDiagnostics()

            // Record transaction (fees from feeReserve)
            addTransaction(PaymentTransaction(
                id = quote.quote,
                type = TransactionType.WITHDRAWAL,
                amountSats = quote.amount,
                feeSats = quote.feeReserve,
                timestamp = System.currentTimeMillis(),
                rideId = null,
                counterpartyPubKey = null,
                status = "Paid via Lightning"
            ))

            Log.d(TAG, "Withdrawal complete: ${quote.amount} sats, new balance: $newBalance sats")
            return Result.success(Unit)
        }

        return Result.failure(Exception("Payment not confirmed"))
    }

    // === Cross-Mint Bridge Payment ===

    /**
     * Bridge payment to an external mint via Lightning.
     * Used when rider and driver use different Cashu mints.
     *
     * Flow:
     * 1. Get melt quote from rider's mint for the driver's invoice
     * 2. Select proofs from NIP-60
     * 3. Execute melt (pay Lightning invoice)
     * 4. Handle change proofs
     * 5. Clean up spent NIP-60 events safely
     *
     * @param driverInvoice BOLT11 invoice from driver's mint
     * @return BridgeResult with success status and fee details
     */
    suspend fun bridgePayment(driverInvoice: String, rideId: String? = null): BridgeResult {
        if (!_isConnected.value) {
            return BridgeResult(success = false, error = "Not connected to wallet")
        }

        val sync = nip60Sync
        val mintUrl = cashuBackend.getCurrentMintUrl()

        if (sync == null) {
            return BridgeResult(success = false, error = "Wallet sync not ready")
        }

        if (mintUrl == null) {
            return BridgeResult(success = false, error = "Not connected to mint")
        }

        // Generate unique ID for this bridge payment
        val bridgePaymentId = java.util.UUID.randomUUID().toString()

        // Create and save pending bridge payment for tracking/recovery
        val pendingBridge = PendingBridgePayment(
            id = bridgePaymentId,
            rideId = rideId ?: "unknown",
            driverInvoice = driverInvoice,
            amountSats = 0,  // Will update after quote
            status = BridgePaymentStatus.STARTED,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        walletStorage.savePendingBridgePayment(pendingBridge)
        Log.d(TAG, "=== BRIDGE PAYMENT STARTED (id=$bridgePaymentId) ===")

        try {
            // Step 1: Get melt quote from rider's mint for the driver's invoice
            Log.d(TAG, "[BRIDGE $bridgePaymentId] Getting melt quote...")
            val quote = cashuBackend.getMeltQuote(driverInvoice)
            if (quote == null) {
                walletStorage.updateBridgePaymentStatus(
                    bridgePaymentId, BridgePaymentStatus.FAILED,
                    errorMessage = "Failed to get melt quote from mint"
                )
                return BridgeResult(success = false, error = "Failed to get melt quote")
            }

            val totalNeeded = quote.totalAmount  // amount + feeReserve
            Log.d(TAG, "[BRIDGE $bridgePaymentId] Quote received: amount=${quote.amount}, fees=${quote.feeReserve}, total=$totalNeeded sats")

            // Update tracking with quote info (including the actual amount)
            walletStorage.updateBridgePaymentStatus(
                bridgePaymentId, BridgePaymentStatus.MELT_QUOTE_OBTAINED,
                meltQuoteId = quote.quote,
                amountSats = quote.amount,
                feeReserveSats = quote.feeReserve
            )

            // Step 2: Select proofs from NIP-60
            val selection = sync.selectProofsForSpending(totalNeeded, mintUrl)
            if (selection == null) {
                walletStorage.updateBridgePaymentStatus(
                    bridgePaymentId, BridgePaymentStatus.FAILED,
                    errorMessage = "Insufficient funds. Need $totalNeeded sats"
                )
                return BridgeResult(
                    success = false,
                    error = "Insufficient funds. Need $totalNeeded sats (including ${quote.feeReserve} sats fees)"
                )
            }

            val proofs = selection.proofs.map { it.toCashuProof() }
            val proofSecrets = selection.proofs.map { it.secret }
            Log.d(TAG, "[BRIDGE $bridgePaymentId] Selected ${proofs.size} proofs (${selection.totalAmount} sats)")

            // Update tracking with proofs being used (for recovery)
            walletStorage.updateBridgePaymentStatus(
                bridgePaymentId, BridgePaymentStatus.MELT_EXECUTED,
                proofsUsed = proofSecrets
            )

            // Step 3: Execute melt (pay the Lightning invoice)
            Log.d(TAG, "[BRIDGE $bridgePaymentId] Executing melt...")
            val result = cashuBackend.meltWithProofs(quote.quote, proofs, totalNeeded)
            if (result == null) {
                walletStorage.updateBridgePaymentStatus(
                    bridgePaymentId, BridgePaymentStatus.FAILED,
                    errorMessage = "Melt operation failed - mint error"
                )
                return BridgeResult(success = false, error = "Melt operation failed - mint error")
            }

            if (!result.paid) {
                walletStorage.updateBridgePaymentStatus(
                    bridgePaymentId, BridgePaymentStatus.FAILED,
                    errorMessage = "Lightning payment failed - routing error or invoice expired"
                )
                return BridgeResult(success = false, error = "Lightning payment failed")
            }

            // Lightning payment confirmed!
            Log.d(TAG, "[BRIDGE $bridgePaymentId] Lightning payment confirmed! Preimage: ${result.paymentPreimage?.take(16)}...")
            walletStorage.updateBridgePaymentStatus(
                bridgePaymentId, BridgePaymentStatus.LIGHTNING_CONFIRMED,
                lightningPreimage = result.paymentPreimage
            )

            // Step 4: Safe NIP-60 cleanup - republish remaining proofs before deleting
            val spentSecrets = selection.proofs.map { it.secret }.toSet()
            val affectedEventIds = selection.proofs.map { it.eventId }.distinct()

            // Fetch all current proofs to find remaining (not spent) proofs in affected events
            val allProofs = sync.fetchProofs(forceRefresh = true)
            val remainingProofsToRepublish = allProofs.filter { proof ->
                proof.eventId in affectedEventIds && proof.secret !in spentSecrets
            }

            if (remainingProofsToRepublish.isNotEmpty()) {
                Log.d(TAG, "Republishing ${remainingProofsToRepublish.size} remaining proofs before cleanup")
                val cashuProofs = remainingProofsToRepublish.map { it.toCashuProof() }
                var newEventId: String? = null
                var attempts = 0
                while (newEventId == null && attempts < 3) {
                    attempts++
                    // Include del array to atomically mark old events as consumed
                    newEventId = sync.publishProofs(cashuProofs, mintUrl, affectedEventIds)
                    if (newEventId == null && attempts < 3) {
                        kotlinx.coroutines.delay(2000)
                    }
                }
                if (newEventId != null) {
                    Log.d(TAG, "Republished remaining proofs to: $newEventId (del: ${affectedEventIds.size} events)")
                } else {
                    Log.e(TAG, "CRITICAL: Failed to republish remaining proofs!")
                    // Save recovery token for manual recovery
                    saveRecoveryTokenFallback(cashuProofs, mintUrl, "nip60_republish_failed_bridgepayment")
                }
            }

            // NIP-09 deletion as backup (del arrays are primary)
            sync.deleteProofEvents(affectedEventIds)

            // Step 5: Publish change proofs with retry
            var changePublished = false
            if (result.change.isNotEmpty()) {
                val changeAmount = result.change.sumOf { it.amount }
                var changeEventId: String? = null
                var publishAttempts = 0

                while (changeEventId == null && publishAttempts < 3) {
                    publishAttempts++
                    Log.d(TAG, "[BRIDGE $bridgePaymentId] Publishing change proofs (attempt $publishAttempts/3)")
                    // Include del array to atomically mark old events as consumed
                    changeEventId = sync.publishProofs(result.change, mintUrl, affectedEventIds)
                    if (changeEventId == null && publishAttempts < 3) {
                        kotlinx.coroutines.delay(2000)
                    }
                }

                if (changeEventId != null) {
                    Log.d(TAG, "[BRIDGE $bridgePaymentId] Published change: ${result.change.size} proofs ($changeAmount sats)")
                    changePublished = true
                } else {
                    Log.e(TAG, "[BRIDGE $bridgePaymentId] CRITICAL: Failed to publish change proofs!")
                    // Save recovery token for manual recovery
                    saveRecoveryTokenFallback(result.change, mintUrl, "nip60_change_failed_bridgepayment")
                    changePublished = true  // Recovery token saved, consider it handled
                }
            } else {
                changePublished = true  // No change needed
            }

            // Mark change proofs as received
            walletStorage.updateBridgePaymentStatus(
                bridgePaymentId, BridgePaymentStatus.LIGHTNING_CONFIRMED,
                changeProofsReceived = changePublished
            )

            // NOW safe to clear pending melt operation - proofs are persisted (NIP-60 or RecoveryToken)
            if (result.pendingOpId != null) {
                walletStorage.removePendingBlindedOp(result.pendingOpId)
                Log.d(TAG, "[BRIDGE $bridgePaymentId] Cleared pending melt operation: ${result.pendingOpId}")
            }

            // Refresh balance
            sync.clearCache()
            val newProofs = sync.fetchProofs(forceRefresh = true)
            val newBalance = newProofs.sumOf { it.amount }

            _balance.value = WalletBalance(
                availableSats = newBalance,
                pendingSats = _balance.value.pendingSats,
                lastUpdated = System.currentTimeMillis()
            )
            walletStorage.cacheBalance(_balance.value)
            updateDiagnostics()

            // Mark bridge payment as complete
            walletStorage.updateBridgePaymentStatus(
                bridgePaymentId, BridgePaymentStatus.COMPLETE,
                lightningPreimage = result.paymentPreimage
            )
            Log.d(TAG, "[BRIDGE $bridgePaymentId] COMPLETE: ${quote.amount} sats + ${quote.feeReserve} sats fees")

            // Record transaction (fees from feeReserve)
            addTransaction(PaymentTransaction(
                id = bridgePaymentId,
                type = TransactionType.BRIDGE_PAYMENT,
                amountSats = quote.amount,
                feeSats = quote.feeReserve,
                timestamp = System.currentTimeMillis(),
                rideId = rideId,
                counterpartyPubKey = null,
                status = "Cross-mint bridge to driver"
            ))

            return BridgeResult(
                success = true,
                amountSats = quote.amount,
                feesSats = quote.feeReserve,
                preimage = result.paymentPreimage
            )
        } catch (e: Exception) {
            Log.e(TAG, "[BRIDGE $bridgePaymentId] Exception: ${e.message}", e)
            walletStorage.updateBridgePaymentStatus(
                bridgePaymentId, BridgePaymentStatus.FAILED,
                errorMessage = e.message ?: "Unknown error"
            )
            return BridgeResult(success = false, error = e.message ?: "Unknown error")
        }
    }

    /**
     * Resolve a Lightning address to a BOLT11 invoice.
     *
     * @param address Lightning address (user@domain.com)
     * @param amountSats Amount in satoshis (required for LNURL-pay to generate invoice)
     * @return Result containing the resolved BOLT11 invoice
     */
    suspend fun resolveLnAddress(address: String, amountSats: Long): Result<String> {
        val invoice = cashuBackend.resolveLnAddress(address, amountSats)
            ?: return Result.failure(Exception("Failed to resolve Lightning address"))
        return Result.success(invoice)
    }

    // === NIP-60 Wallet Sync ===

    /**
     * Check if a NIP-60 wallet exists for this user.
     */
    suspend fun hasExistingNip60Wallet(): Boolean {
        return nip60Sync?.hasExistingWallet() ?: false
    }

    /**
     * Restore wallet from NIP-60 Nostr events.
     */
    suspend fun restoreFromNip60(): Boolean {
        val sync = nip60Sync ?: return false
        val state = sync.restoreFromNostr()
        if (state != null) {
            _balance.value = state.balance
            walletStorage.cacheBalance(state.balance)
            if (state.mintUrl != null) {
                connect(state.mintUrl)
            }
            Log.d(TAG, "Restored wallet from NIP-60: ${state.balance.availableSats} sats")
            return true
        }
        return false
    }

    /**
     * Recover wallet from seed using NUT-09 /v1/restore endpoint.
     * This is the nuclear option when NIP-60 data is lost but you have the mnemonic.
     *
     * NUT-13: Deterministic secrets allow scanning the mint for all proofs
     * NUT-09: Restore endpoint returns signatures for previously-signed outputs
     *
     * Process:
     * 1. Get all active keysets from mint
     * 2. For each keyset, iterate through counter values (0, 100, 200, ...)
     * 3. Generate deterministic blinded messages for each batch
     * 4. Call /v1/restore to get any signatures mint has
     * 5. Unblind signatures to get proofs
     * 6. Filter unspent proofs via NUT-07
     * 7. Publish unspent proofs to NIP-60
     *
     * @return RecoveryResult with success status and recovered amount
     */
    suspend fun recoverFromSeed(): SeedRecoveryResult {
        Log.d(TAG, "=== RECOVER FROM SEED (NUT-09/NUT-13) ===")

        val sync = nip60Sync
        if (sync == null) {
            Log.e(TAG, "recoverFromSeed: NIP-60 not initialized")
            return SeedRecoveryResult(false, "NIP-60 not initialized", 0)
        }

        val seed = cashuBackend.getSeed()
        if (seed == null) {
            Log.e(TAG, "recoverFromSeed: No seed available (wallet not connected?)")
            return SeedRecoveryResult(false, "Wallet not connected", 0)
        }

        val mintUrl = cashuBackend.getCurrentMintUrl()
        if (mintUrl == null) {
            Log.e(TAG, "recoverFromSeed: No mint URL")
            return SeedRecoveryResult(false, "Not connected to mint", 0)
        }

        try {
            // Get all active keysets
            val keysetIds = cashuBackend.getActiveKeysetIds()
            if (keysetIds.isEmpty()) {
                Log.w(TAG, "recoverFromSeed: No active keysets found")
                return SeedRecoveryResult(false, "No active keysets at mint", 0)
            }

            Log.d(TAG, "Found ${keysetIds.size} active keysets: $keysetIds")

            var totalRecovered = 0L
            var totalProofsFound = 0

            for (keysetId in keysetIds) {
                // Fetch full keyset
                val keyset = cashuBackend.fetchKeyset(keysetId)
                if (keyset == null) {
                    Log.w(TAG, "Could not fetch keyset $keysetId, skipping")
                    continue
                }

                Log.d(TAG, "Scanning keyset $keysetId...")

                var counter = 0L
                var emptyBatches = 0
                // BATCH_SIZE * 21 denominations must be <= 1000 (mint limit)
                // 45 * 21 = 945 items per request
                val BATCH_SIZE = 45
                val MAX_EMPTY_BATCHES = 3  // Stop after 3 consecutive empty batches

                while (emptyBatches < MAX_EMPTY_BATCHES) {
                    // Generate batch of deterministic secrets for all denominations
                    // IMPORTANT: Run crypto derivation on background thread to avoid ANR
                    val preMintSecrets = withContext(Dispatchers.Default) {
                        val secrets = mutableListOf<com.ridestr.common.payment.cashu.PreMintSecret>()

                        // For each counter value, try all standard denominations (1, 2, 4, 8, ..., up to 2^20)
                        for (i in 0 until BATCH_SIZE) {
                            val currentCounter = counter + i
                            // Try common denominations
                            for (power in 0..20) {
                                val amount = 1L shl power  // 1, 2, 4, 8, ..., 1048576
                                val pms = com.ridestr.common.payment.cashu.CashuCrypto.derivePreMintSecret(
                                    seed, keysetId, currentCounter * 21 + power, amount
                                )
                                if (pms != null) {
                                    secrets.add(pms)
                                }
                            }
                        }
                        secrets
                    }

                    Log.d(TAG, "Trying ${preMintSecrets.size} blinded messages at counter $counter")

                    // Call restore endpoint
                    val restoredProofs = cashuBackend.restoreProofs(preMintSecrets, keyset)

                    if (restoredProofs.isEmpty()) {
                        emptyBatches++
                        Log.d(TAG, "No proofs found at counter $counter (empty batches: $emptyBatches)")
                    } else {
                        emptyBatches = 0  // Reset on successful find

                        // Filter to unspent only
                        val unspentProofs = cashuBackend.filterUnspentProofs(restoredProofs)
                        Log.d(TAG, "Found ${restoredProofs.size} proofs, ${unspentProofs.size} unspent")

                        if (unspentProofs.isNotEmpty()) {
                            // Publish to NIP-60
                            val eventId = sync.publishProofs(unspentProofs, mintUrl)
                            if (eventId != null) {
                                val batchTotal = unspentProofs.sumOf { it.amount }
                                totalRecovered += batchTotal
                                totalProofsFound += unspentProofs.size
                                Log.d(TAG, "Published ${unspentProofs.size} proofs ($batchTotal sats) to NIP-60")
                            }
                        }
                    }

                    counter += BATCH_SIZE
                }

                // Update counter storage to highest scanned value
                _walletStorage.setCounter(keysetId, counter)
                Log.d(TAG, "Keyset $keysetId scan complete, counter set to $counter")
            }

            // Update balance
            val newBalance = WalletBalance(
                availableSats = _balance.value.availableSats + totalRecovered,
                pendingSats = 0,
                lastUpdated = System.currentTimeMillis()
            )
            _balance.value = newBalance
            _walletStorage.cacheBalance(newBalance)

            // Sync counters to NIP-60 metadata
            // forceOverwrite=true because user explicitly requested seed recovery
            sync.publishWalletMetadata(mintUrl, forceOverwrite = true)

            Log.d(TAG, "=== RECOVERY COMPLETE: $totalRecovered sats in $totalProofsFound proofs ===")
            return SeedRecoveryResult(
                success = true,
                message = "Recovered $totalRecovered sats",
                recoveredSats = totalRecovered,
                proofCount = totalProofsFound
            )
        } catch (e: Exception) {
            Log.e(TAG, "recoverFromSeed failed: ${e.message}", e)
            return SeedRecoveryResult(false, "Recovery failed: ${e.message}", 0)
        }
    }

    /**
     * Sync wallet metadata to NIP-60 Nostr events.
     * This is a manual action, so forceOverwrite=true to update metadata.
     */
    suspend fun syncToNip60() {
        val mintUrl = cashuBackend.getCurrentMintUrl()
        if (mintUrl != null) {
            nip60Sync?.publishWalletMetadata(mintUrl, forceOverwrite = true)
        }
    }

    /**
     * Sync NIP-60 proofs with the actual mint state.
     *
     * This is called after operations that spend proofs (like HTLC creation) to ensure
     * NIP-60 accurately reflects the current wallet state. It:
     * 1. Gets the current balance from cdk-kotlin (which is authoritative for local proofs)
     * 2. If NIP-60 balance is higher (stale proofs), deletes all NIP-60 events and republishes
     *
     * IMPORTANT: This prevents balance double-counting where NIP-60 shows spent proofs.
     */
    suspend fun syncNip60WithMint(): Boolean {
        val sync = nip60Sync ?: return false
        val mintUrl = cashuBackend.getCurrentMintUrl() ?: return false

        try {
            // Get verified balance from mint (checks proof states via NUT-07)
            val verifiedBalance = cashuBackend.getVerifiedBalance()
            if (verifiedBalance == null) {
                Log.e(TAG, "syncNip60WithMint: could not get verified balance")
                return false
            }

            // Get NIP-60 balance
            val nip60Balance = sync.getBalance().availableSats

            Log.d(TAG, "Sync check: verified=$verifiedBalance, nip60=$nip60Balance")

            // If NIP-60 is higher than verified, it may have stale (spent) proofs
            // SAFETY: Only delete proofs from the CURRENT mint that are verified spent
            // This preserves proofs from OTHER mints (cross-app NIP-60 portability)
            if (nip60Balance > verifiedBalance) {
                Log.w(TAG, "NIP-60 balance ($nip60Balance) > verified ($verifiedBalance) - checking for stale proofs...")

                val existingProofs = sync.fetchProofs(forceRefresh = true)

                // Separate proofs by mint - ONLY touch proofs from current mint
                val currentMintProofs = existingProofs.filter { it.mintUrl == mintUrl }
                val otherMintProofs = existingProofs.filter { it.mintUrl != mintUrl }

                if (otherMintProofs.isNotEmpty()) {
                    val otherMintBalance = otherMintProofs.sumOf { it.amount }
                    Log.w(TAG, "NIP-60 SAFETY: Preserving ${otherMintProofs.size} proofs ($otherMintBalance sats) from other mints")
                }

                // Only verify and delete spent proofs from CURRENT mint
                if (currentMintProofs.isNotEmpty()) {
                    val secrets = currentMintProofs.map { it.secret }
                    val stateMap = cashuBackend.verifyProofStatesBySecret(secrets)

                    if (stateMap != null) {
                        // Find proofs that are actually spent at the current mint
                        val spentProofs = currentMintProofs.filter { proof ->
                            stateMap[proof.secret] == CashuBackend.ProofStateResult.SPENT
                        }

                        if (spentProofs.isNotEmpty()) {
                            val spentBalance = spentProofs.sumOf { it.amount }
                            val eventIdsToDelete = spentProofs.map { it.eventId }.distinct()
                            sync.deleteProofEvents(eventIdsToDelete)
                            Log.d(TAG, "Deleted ${eventIdsToDelete.size} events with ${spentProofs.size} spent proofs ($spentBalance sats) from current mint")
                        } else {
                            Log.d(TAG, "No spent proofs found at current mint - balance mismatch may be from other mints")
                        }
                    } else {
                        Log.w(TAG, "Could not verify proof states - skipping deletion to be safe")
                    }
                }
            }

            // Update cached balance to verified amount
            _balance.value = WalletBalance(
                availableSats = verifiedBalance,
                pendingSats = 0,
                lastUpdated = System.currentTimeMillis()
            )
            walletStorage.cacheBalance(_balance.value)

            Log.d(TAG, "NIP-60 synced, verified balance: $verifiedBalance sats")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "syncNip60WithMint failed: ${e.message}", e)
            return false
        }
    }

    /**
     * Publish current cdk-kotlin proofs to NIP-60.
     * Called after receiving new proofs (deposits, HTLC claims).
     */
    suspend fun publishProofsToNip60(proofs: List<CashuProof>): String? {
        val sync = nip60Sync ?: return null
        val mintUrl = cashuBackend.getCurrentMintUrl() ?: return null

        return try {
            val eventId = sync.publishProofs(proofs, mintUrl)
            Log.d(TAG, "Published ${proofs.size} proofs to NIP-60: $eventId")
            eventId
        } catch (e: Exception) {
            Log.e(TAG, "publishProofsToNip60 failed: ${e.message}", e)
            null
        }
    }

    /**
     * Republish verified proofs to NIP-60.
     *
     * This is a smart resync that:
     * 1. Collects proofs from ALL sources (local cdk-kotlin + NIP-60)
     * 2. Deduplicates by secret (unique identifier)
     * 3. Verifies each proof with the mint (NUT-07 checkstate)
     * 4. Keeps only UNSPENT proofs
     * 5. Deletes old NIP-60 events
     * 6. Publishes verified proofs fresh to NIP-60
     *
     * @return ResyncResult indicating success or failure with details
     */
    suspend fun resyncProofsToNip60(): ResyncResult {
        val sync = nip60Sync
        if (sync == null) {
            Log.e(TAG, "resyncProofsToNip60: NIP-60 sync not initialized")
            return ResyncResult(
                success = false,
                message = "NIP-60 sync not available",
                proofsDeleted = 0,
                proofsPublished = 0,
                newBalance = 0
            )
        }

        val mintUrl = cashuBackend.getCurrentMintUrl()
        if (mintUrl == null) {
            Log.e(TAG, "resyncProofsToNip60: mint not connected")
            return ResyncResult(
                success = false,
                message = "Not connected to mint",
                proofsDeleted = 0,
                proofsPublished = 0,
                newBalance = 0
            )
        }

        Log.d(TAG, "=== SMART RESYNC: COLLECTING FROM ALL SOURCES ===")

        try {
            // Step 1: Collect proofs from ALL sources
            Log.d(TAG, "Step 1: Collecting proofs from all sources...")

            // Source A: Local cdk-kotlin database
            val localProofs = cashuBackend.getLocalProofs() ?: emptyList()
            Log.d(TAG, "  Local (cdk-kotlin): ${localProofs.size} proofs, ${localProofs.sumOf { it.amount }} sats")

            // Source B: NIP-60 Nostr events
            val nip60Proofs = sync.fetchProofs(forceRefresh = true)
            val nip60EventIds = nip60Proofs.map { it.eventId }.distinct()
            Log.d(TAG, "  NIP-60 (Nostr): ${nip60Proofs.size} proofs, ${nip60Proofs.sumOf { it.amount }} sats")

            // Step 2: Check if local proof extraction is working
            // SAFETY CHECK: If local total doesn't match cdk balance, don't trust ANY proofs
            val cdkBalance = cashuBackend.getCdkBalance() ?: 0L
            val localTotal = localProofs.sumOf { it.amount }
            val nip60Total = nip60Proofs.sumOf { it.amount }

            Log.d(TAG, "Step 2: Safety check...")
            Log.d(TAG, "  cdk-kotlin balance (trusted): $cdkBalance sats")
            Log.d(TAG, "  Local proof extraction total: $localTotal sats (${localProofs.size} proofs)")
            Log.d(TAG, "  NIP-60 total: $nip60Total sats (${nip60Proofs.size} proofs)")

            // CRITICAL: If we have balance but can't extract local proofs, abort!
            // This means getLocalProofs() failed and we'd only use NIP-60 (which has wrong amounts)
            if (cdkBalance > 0 && localProofs.isEmpty()) {
                Log.e(TAG, "⚠️ CANNOT ACCESS LOCAL PROOFS!")
                Log.e(TAG, "cdk-kotlin has $cdkBalance sats but getLocalProofs() returned empty")
                Log.e(TAG, "Aborting resync to prevent data loss.")

                // Restore correct balance from cdk-kotlin
                _balance.value = WalletBalance(
                    availableSats = cdkBalance,
                    pendingSats = 0,
                    lastUpdated = System.currentTimeMillis()
                )
                walletStorage.cacheBalance(_balance.value)

                return ResyncResult(
                    success = false,
                    message = "Cannot access local proofs (database issue). Balance restored to $cdkBalance sats.",
                    proofsDeleted = 0,
                    proofsPublished = 0,
                    newBalance = cdkBalance
                )
            }

            // If local extraction doesn't match cdk balance, warn but continue
            // This can happen if cdk-kotlin uses different units (msats vs sats)
            // Better to sync what we have than lose everything
            if (localProofs.isNotEmpty() && localTotal != cdkBalance) {
                Log.w(TAG, "⚠️ Proof amount mismatch detected")
                Log.w(TAG, "Local extraction: $localTotal sats (${localProofs.size} proofs)")
                Log.w(TAG, "cdk-kotlin balance: $cdkBalance (may be in different units)")
                Log.w(TAG, "Proceeding with extracted proofs anyway...")
                // Note: cdk-kotlin might store in msats (1 sat = 1000 msats)
                // Our extracted proofs should be correct, the reported balance might just be in different units
            }

            // Determine source of truth for proofs
            Log.d(TAG, "Step 2b: Selecting authoritative proof source...")

            // PRIORITY: Local proofs from cdk-kotlin are the freshest/most accurate
            // NIP-60 proofs may be stale or have different amounts
            // Only use NIP-60 when local is empty (fresh install restoring from backup)
            val allUniqueProofs: List<CashuProof>

            if (localProofs.isNotEmpty()) {
                // Local is authoritative - use ONLY local proofs
                // (Don't require balance match - units may differ)
                Log.d(TAG, "  Using LOCAL proofs as source of truth (${localProofs.size} proofs, $localTotal sats)")
                if (nip60Proofs.isNotEmpty()) {
                    Log.d(TAG, "  NIP-60 proofs will be replaced (had $nip60Total sats)")
                }
                allUniqueProofs = localProofs
            } else if (nip60Proofs.isNotEmpty()) {
                // Fresh install - restore from NIP-60
                Log.d(TAG, "  Using NIP-60 proofs (no local proofs available)")
                allUniqueProofs = nip60Proofs.map { it.toCashuProof() }
            } else {
                // No proofs anywhere
                Log.d(TAG, "  No proofs found in either source")
                allUniqueProofs = emptyList()
            }

            val mergedTotal = allUniqueProofs.sumOf { it.amount }
            Log.d(TAG, "  Selected: ${allUniqueProofs.size} proofs, $mergedTotal sats")

            if (allUniqueProofs.isEmpty()) {
                Log.d(TAG, "No proofs found from any source - nothing to sync")

                // Still delete old NIP-60 events if any
                if (nip60EventIds.isNotEmpty()) {
                    sync.deleteProofEvents(nip60EventIds)
                }

                return ResyncResult(
                    success = true,
                    message = "No proofs found (balance is 0)",
                    proofsDeleted = nip60EventIds.size,
                    proofsPublished = 0,
                    newBalance = 0
                )
            }

            // Step 3: Verify ALL proofs with the mint (NUT-07)
            Log.d(TAG, "Step 3: Verifying ${allUniqueProofs.size} proofs with mint...")

            val secrets = allUniqueProofs.map { it.secret }
            val proofStates = cashuBackend.verifyProofStatesBySecret(secrets)

            val verifiedProofs: List<CashuProof>
            var spentCount = 0
            var pendingCount = 0

            if (proofStates != null) {
                verifiedProofs = allUniqueProofs.filter { proof ->
                    when (proofStates[proof.secret]) {
                        CashuBackend.ProofStateResult.UNSPENT -> true
                        CashuBackend.ProofStateResult.PENDING -> {
                            pendingCount++
                            true // Include pending - they might become unspent
                        }
                        CashuBackend.ProofStateResult.SPENT -> {
                            spentCount++
                            false
                        }
                        null -> {
                            true // Include if we couldn't verify
                        }
                    }
                }

                Log.d(TAG, "  Verification: ${verifiedProofs.size} unspent, $pendingCount pending, $spentCount spent")
            } else {
                // Verification failed - include all proofs but warn
                Log.w(TAG, "  Mint verification failed - including all proofs without verification")
                verifiedProofs = allUniqueProofs
            }

            val verifiedTotal = verifiedProofs.sumOf { it.amount }
            Log.d(TAG, "  Verified: ${verifiedProofs.size} proofs, $verifiedTotal sats")

            if (verifiedProofs.isEmpty()) {
                Log.d(TAG, "All proofs are spent - clearing NIP-60")

                if (nip60EventIds.isNotEmpty()) {
                    sync.deleteProofEvents(nip60EventIds)
                }

                // Update balance to 0
                _balance.value = WalletBalance(0, 0, System.currentTimeMillis())
                walletStorage.cacheBalance(_balance.value)

                return ResyncResult(
                    success = true,
                    message = "All $spentCount proofs were spent - balance is 0",
                    proofsDeleted = nip60EventIds.size,
                    proofsPublished = 0,
                    newBalance = 0
                )
            }

            // Step 4: Publish verified proofs to NIP-60 with del array (atomic replacement)
            Log.d(TAG, "Step 4: Publishing ${verifiedProofs.size} verified proofs to NIP-60...")
            // Include del array to atomically mark old events as consumed
            val eventId = sync.publishProofs(verifiedProofs, mintUrl, nip60EventIds)

            // Step 5: NIP-09 deletion as backup (del arrays are primary)
            Log.d(TAG, "Step 5: Deleting ${nip60EventIds.size} old NIP-60 events (NIP-09 backup)...")
            if (nip60EventIds.isNotEmpty()) {
                sync.deleteProofEvents(nip60EventIds)
            }

            if (eventId == null) {
                Log.e(TAG, "Failed to publish proofs to NIP-60")
                return ResyncResult(
                    success = false,
                    message = "Failed to publish to relays (proofs still valid locally)",
                    proofsDeleted = nip60EventIds.size,
                    proofsPublished = 0,
                    newBalance = verifiedTotal
                )
            }

            // Clear NIP-60 cache to force refresh
            sync.clearCache()

            // Update balance
            _balance.value = WalletBalance(
                availableSats = verifiedTotal,
                pendingSats = 0,
                lastUpdated = System.currentTimeMillis()
            )
            walletStorage.cacheBalance(_balance.value)

            // Update diagnostics
            updateDiagnostics()

            Log.d(TAG, "=== SMART RESYNC COMPLETE ===")
            Log.d(TAG, "Published ${verifiedProofs.size} verified proofs ($verifiedTotal sats)")
            if (spentCount > 0) {
                Log.d(TAG, "Excluded $spentCount spent proofs")
            }

            val message = buildString {
                append("Synced ${verifiedProofs.size} proofs ($verifiedTotal sats)")
                if (spentCount > 0) append(", removed $spentCount spent")
                if (pendingCount > 0) append(", $pendingCount pending")
            }

            return ResyncResult(
                success = true,
                message = message,
                proofsDeleted = nip60EventIds.size,
                proofsPublished = verifiedProofs.size,
                newBalance = verifiedTotal
            )
        } catch (e: Exception) {
            Log.e(TAG, "resyncProofsToNip60 failed: ${e.message}", e)
            return ResyncResult(
                success = false,
                message = "Error: ${e.message}",
                proofsDeleted = 0,
                proofsPublished = 0,
                newBalance = 0
            )
        }
    }

    // === Internal ===

    private fun addTransaction(tx: PaymentTransaction) {
        val updated = listOf(tx) + _transactions.value.take(99)  // Keep last 100
        _transactions.value = updated
        walletStorage.saveTransaction(tx)
    }

    /**
     * Update balance (for testing or manual refresh).
     */
    fun updateBalance(newBalance: WalletBalance) {
        _balance.value = newBalance
        walletStorage.cacheBalance(newBalance)
    }

    /**
     * Refresh wallet balance.
     * This is called on pull-to-refresh in the wallet UI.
     *
     * Checks for pending deposits that need recovery and attempts to complete them.
     * When HTLC/proof storage is fully implemented, this will also
     * check proof states with the mint for an accurate balance.
     *
     * @return true if refresh was successful
     */
    suspend fun refreshBalance(): Boolean {
        if (!_isConnected.value) {
            Log.d(TAG, "Cannot refresh - not connected to wallet provider")
            return false
        }

        try {
            // Clean up expired pending deposits (can't be claimed anyway)
            walletStorage.cleanupExpiredDeposits()

            // Clean up old resolved HTLCs
            walletStorage.cleanupResolvedHtlcs()

            // Check for and refund any expired HTLCs
            val refundableHtlcs = walletStorage.getRefundableHtlcs()
            if (refundableHtlcs.isNotEmpty()) {
                Log.d(TAG, "Found ${refundableHtlcs.size} expired HTLCs to refund")
                val refundResults = refundExpiredHtlcs()
                val successCount = refundResults.count { it.success }
                if (successCount > 0) {
                    Log.d(TAG, "Refunded $successCount expired HTLCs")
                }
            }

            // Recover pending deposits
            recoverPendingDeposits()

            // Sync everything using the single sync function
            val syncResult = syncWallet()

            // Reload transactions
            _transactions.value = walletStorage.getTransactions()

            Log.d(TAG, "Wallet refreshed: ${syncResult.message}")
            return syncResult.success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh balance", e)
            return false
        }
    }

    /**
     * Recover pending deposits that were paid but not minted.
     */
    private suspend fun recoverPendingDeposits() {
        val pendingDeposits = walletStorage.getPendingDeposits()

        for (deposit in pendingDeposits) {
            if (deposit.minted) {
                // Already minted, just clean up
                walletStorage.removePendingDeposit(deposit.quoteId)
                continue
            }

            if (deposit.isExpired()) {
                Log.w(TAG, "Pending deposit expired - removing: ${deposit.quoteId}")
                walletStorage.removePendingDeposit(deposit.quoteId)
                continue
            }

            // Check if this deposit was paid
            Log.d(TAG, "Checking pending deposit: ${deposit.quoteId}, ${deposit.amount} sats")
            val quote = cashuBackend.checkMintQuote(deposit.quoteId)

            if (quote?.isPaid() == true) {
                Log.d(TAG, "Found paid but unminted deposit: ${deposit.quoteId}")

                // Try to complete the mint via cdk-kotlin first
                val mintResult = cashuBackend.mintTokens(deposit.quoteId, deposit.amount)
                var proofsToPublish: List<CashuProof>? = mintResult?.proofs

                // If cdk-kotlin fails (Unknown quote), try HTTP-based recovery
                if (mintResult == null) {
                    Log.w(TAG, "cdk-kotlin mint failed, trying HTTP recovery...")
                    proofsToPublish = cashuBackend.recoverDeposit(deposit.quoteId, deposit.amount)
                }

                val success = proofsToPublish != null && proofsToPublish.isNotEmpty()

                if (success) {
                    walletStorage.markDepositMinted(deposit.quoteId)
                    walletStorage.removePendingDeposit(deposit.quoteId)

                    // Publish proofs to NIP-60 for cross-device sync
                    val mintUrl = cashuBackend.getCurrentMintUrl()
                    if (proofsToPublish != null && mintUrl != null) {
                        val eventId = nip60Sync?.publishProofs(proofsToPublish, mintUrl)
                        if (eventId != null) {
                            Log.d(TAG, "Published ${proofsToPublish.size} proofs to NIP-60: $eventId")
                        }
                    }

                    // Record transaction
                    addTransaction(PaymentTransaction(
                        id = deposit.quoteId,
                        type = TransactionType.DEPOSIT,
                        amountSats = deposit.amount,
                        timestamp = System.currentTimeMillis(),
                        rideId = null,
                        counterpartyPubKey = null,
                        status = "Recovered deposit"
                    ))

                    Log.d(TAG, "Successfully recovered deposit: ${deposit.amount} sats")
                } else {
                    Log.e(TAG, "Failed to recover deposit: ${deposit.quoteId}")
                }
            }
        }
    }

    /**
     * Get count of pending (unclaimed) deposits.
     * Used by developer options to show if there are deposits to recover.
     */
    fun getPendingDepositCount(): Int {
        return walletStorage.getPendingDeposits().count { !it.minted && !it.isExpired() }
    }

    /**
     * Get list of unclaimed deposits for display in developer options.
     * Filters out already minted and expired deposits.
     */
    fun getUnclaimedDeposits(): List<PendingDeposit> {
        return walletStorage.getPendingDeposits().filter { !it.minted && !it.isExpired() }
    }

    /**
     * Manually claim all unclaimed deposits.
     * Called from developer options UI.
     *
     * @return ClaimResult with count of claimed deposits and total sats
     */
    suspend fun claimUnclaimedDeposits(): ClaimResult {
        if (!_isConnected.value) {
            return ClaimResult(success = false, error = "Not connected to wallet")
        }

        val pendingDeposits = walletStorage.getPendingDeposits().filter { !it.minted && !it.isExpired() }

        if (pendingDeposits.isEmpty()) {
            return ClaimResult(success = true, claimedCount = 0, totalSats = 0)
        }

        Log.d(TAG, "=== CLAIMING UNCLAIMED DEPOSITS ===")
        Log.d(TAG, "Found ${pendingDeposits.size} pending deposits to check")
        pendingDeposits.forEachIndexed { i, d ->
            Log.d(TAG, "  Deposit ${i+1}: quoteId=${d.quoteId}")
            Log.d(TAG, "    amount=${d.amount} sats, created=${d.createdAt}, expiry=${d.expiry}")
            Log.d(TAG, "    invoice=${d.invoice.take(50)}...")
        }

        var claimedCount = 0
        var totalSats = 0L
        val errors = mutableListOf<String>()

        val currentMint = cashuBackend.getCurrentMintUrl()
        Log.d(TAG, "Current mint URL: $currentMint")

        for (deposit in pendingDeposits) {
            try {
                Log.d(TAG, "Checking deposit with mint: ${deposit.quoteId}")

                // Check if this deposit was paid
                val quote = cashuBackend.checkMintQuote(deposit.quoteId)
                Log.d(TAG, "  Mint response: ${quote?.state ?: "null (quote not found)"}")

                if (quote?.isPaid() == true) {
                    Log.d(TAG, "Deposit ${deposit.quoteId} is paid, claiming tokens...")

                    // Try to mint tokens
                    val mintResult = cashuBackend.mintTokens(deposit.quoteId, deposit.amount)
                    var proofsToPublish: List<CashuProof>? = mintResult?.proofs

                    // Fallback to HTTP recovery
                    if (mintResult == null) {
                        Log.w(TAG, "cdk-kotlin mint failed, trying HTTP recovery...")
                        proofsToPublish = cashuBackend.recoverDeposit(deposit.quoteId, deposit.amount)
                    }

                    if (proofsToPublish != null && proofsToPublish.isNotEmpty()) {
                        // Publish to NIP-60
                        val mintUrl = cashuBackend.getCurrentMintUrl()
                        if (mintUrl != null) {
                            publishProofsToNip60(proofsToPublish, mintUrl, nip60Sync!!)
                        }

                        walletStorage.markDepositMinted(deposit.quoteId)
                        walletStorage.removePendingDeposit(deposit.quoteId)

                        // Record transaction
                        addTransaction(PaymentTransaction(
                            id = deposit.quoteId,
                            type = TransactionType.DEPOSIT,
                            amountSats = deposit.amount,
                            timestamp = System.currentTimeMillis(),
                            rideId = null,
                            counterpartyPubKey = null,
                            status = "Claimed from developer options"
                        ))

                        claimedCount++
                        totalSats += deposit.amount
                        Log.d(TAG, "Successfully claimed ${deposit.amount} sats from deposit ${deposit.quoteId}")
                    } else {
                        errors.add("${deposit.quoteId}: mint failed")
                    }
                } else if (quote == null) {
                    // Quote not found at mint - this deposit is stale, remove it
                    Log.w(TAG, "Quote ${deposit.quoteId} not found at mint, removing stale deposit")
                    walletStorage.removePendingDeposit(deposit.quoteId)
                    errors.add("${deposit.quoteId}: quote not found (removed stale deposit)")
                } else {
                    errors.add("${deposit.quoteId}: not paid (state: ${quote.state})")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error claiming deposit ${deposit.quoteId}: ${e.message}", e)
                errors.add("${deposit.quoteId}: ${e.message}")
            }
        }

        // Refresh balance after claiming
        if (claimedCount > 0) {
            nip60Sync?.clearCache()
            refreshBalance()
        }

        return ClaimResult(
            success = errors.isEmpty(),
            claimedCount = claimedCount,
            totalSats = totalSats,
            error = if (errors.isNotEmpty()) errors.joinToString("; ") else null
        )
    }

    /**
     * Manually claim a deposit by quote ID.
     *
     * Use this to recover funds when:
     * - The pending deposit record was lost
     * - Imported key to a new device
     * - App crashed and lost track of the deposit
     *
     * @param quoteId The mint quote ID (from the original deposit)
     * @return ClaimResult with success status and amount
     */
    suspend fun claimDepositByQuoteId(quoteId: String): ClaimResult {
        if (!_isConnected.value) {
            return ClaimResult(success = false, error = "Not connected to wallet")
        }

        val sync = nip60Sync
        val mintUrl = cashuBackend.getCurrentMintUrl()

        if (sync == null) {
            return ClaimResult(success = false, error = "Wallet sync not ready")
        }

        if (mintUrl == null) {
            return ClaimResult(success = false, error = "Not connected to mint")
        }

        Log.d(TAG, "=== MANUAL DEPOSIT CLAIM ===")
        Log.d(TAG, "Attempting to claim quote: $quoteId")

        try {
            // Check quote status at mint
            val quoteResult = cashuBackend.checkMintQuoteWithResult(quoteId)

            when (quoteResult) {
                is MintQuoteResult.NotFound -> {
                    return ClaimResult(success = false, error = "Quote not found at mint. It may have expired or been claimed already.")
                }
                is MintQuoteResult.Error -> {
                    return ClaimResult(success = false, error = "Cannot reach mint: ${quoteResult.message}")
                }
                is MintQuoteResult.Found -> {
                    val quote = quoteResult.quote
                    Log.d(TAG, "Quote found: state=${quote.state}, amount=${quote.amount} sats")

                    if (!quote.isPaid()) {
                        return ClaimResult(
                            success = false,
                            error = "Quote not paid yet (state: ${quote.state}). Pay the invoice first."
                        )
                    }

                    // Quote is paid - mint the tokens
                    Log.d(TAG, "Quote is paid, minting ${quote.amount} sats...")

                    var proofsToPublish: List<com.ridestr.common.payment.cashu.CashuProof>? = null

                    // Try cdk-kotlin first
                    val mintResult = cashuBackend.mintTokens(quoteId, quote.amount)
                    if (mintResult != null && mintResult.success) {
                        proofsToPublish = mintResult.proofs
                        Log.d(TAG, "Minted ${proofsToPublish.size} proofs via cdk-kotlin")
                    } else {
                        // Fallback to HTTP recovery
                        Log.w(TAG, "cdk-kotlin mint failed, trying HTTP recovery...")
                        proofsToPublish = cashuBackend.recoverDeposit(quoteId, quote.amount)
                        if (proofsToPublish != null) {
                            Log.d(TAG, "Recovered ${proofsToPublish.size} proofs via HTTP")
                        }
                    }

                    if (proofsToPublish == null || proofsToPublish.isEmpty()) {
                        // Tokens may have already been minted - check if quote is ISSUED
                        if (quote.state == MintQuoteState.ISSUED) {
                            return ClaimResult(
                                success = false,
                                error = "Tokens already issued for this quote. They may be in your wallet already - try Sync Wallet."
                            )
                        }
                        return ClaimResult(success = false, error = "Failed to mint tokens")
                    }

                    // Publish to NIP-60
                    var eventId: String? = null
                    val delays = listOf(1000L, 2000L, 4000L)

                    for ((attempt, delay) in delays.withIndex()) {
                        eventId = sync.publishProofs(proofsToPublish, mintUrl)
                        if (eventId != null) break
                        if (attempt < delays.lastIndex) {
                            kotlinx.coroutines.delay(delay)
                        }
                    }

                    if (eventId != null) {
                        Log.d(TAG, "Published ${proofsToPublish.size} proofs to NIP-60: $eventId")
                    } else {
                        // Save recovery token as fallback
                        saveRecoveryTokenFallback(proofsToPublish, mintUrl, "manual_claim_nip60_failed")
                        return ClaimResult(
                            success = false,
                            error = "Minted but failed to sync. Funds saved for recovery."
                        )
                    }

                    // Remove any pending deposit record for this quote
                    walletStorage.removePendingDeposit(quoteId)

                    // Record transaction
                    addTransaction(PaymentTransaction(
                        id = quoteId,
                        type = TransactionType.DEPOSIT,
                        amountSats = quote.amount,
                        timestamp = System.currentTimeMillis(),
                        rideId = null,
                        counterpartyPubKey = null,
                        status = "Manually claimed"
                    ))

                    // Update balance IMMEDIATELY with the minted amount
                    val mintedAmount = proofsToPublish.sumOf { it.amount }
                    val currentBalance = _balance.value.availableSats
                    val newBalance = currentBalance + mintedAmount

                    _balance.value = WalletBalance(
                        availableSats = newBalance,
                        pendingSats = _balance.value.pendingSats,
                        lastUpdated = System.currentTimeMillis()
                    )
                    walletStorage.cacheBalance(_balance.value)

                    // Clear NIP-60 cache so next sync fetches fresh data
                    sync.clearCache()
                    updateDiagnostics()

                    Log.d(TAG, "Successfully claimed $mintedAmount sats from quote $quoteId, balance: $currentBalance → $newBalance")

                    return ClaimResult(
                        success = true,
                        claimedCount = 1,
                        totalSats = quote.amount
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error claiming deposit $quoteId: ${e.message}", e)
            return ClaimResult(success = false, error = "Error: ${e.message}")
        }
    }

    /**
     * Get count of saved recovery tokens.
     * Used by UI to show if there are tokens to recover.
     */
    fun getRecoveryTokenCount(): Int = walletStorage.getRecoveryTokenCount()

    /**
     * Get total sats in recovery tokens.
     */
    fun getRecoveryTokenTotal(): Long = walletStorage.getRecoveryTokenTotal()

    /**
     * Get list of recovery tokens for display.
     */
    fun getRecoveryTokens(): List<RecoveryToken> = walletStorage.getRecoveryTokens()

    /**
     * Recover saved tokens by publishing them to NIP-60.
     * These are tokens that were saved when NIP-60 publish previously failed.
     *
     * @return ClaimResult with count recovered and total sats
     */
    suspend fun recoverSavedTokens(): ClaimResult {
        if (!_isConnected.value) {
            return ClaimResult(success = false, error = "Not connected to wallet")
        }

        val sync = nip60Sync
        if (sync == null) {
            return ClaimResult(success = false, error = "Wallet sync not ready")
        }

        val tokens = walletStorage.getRecoveryTokens()
        if (tokens.isEmpty()) {
            return ClaimResult(success = true, claimedCount = 0, totalSats = 0)
        }

        Log.d(TAG, "=== RECOVERING SAVED TOKENS ===")
        Log.d(TAG, "Found ${tokens.size} recovery tokens to process")

        var recoveredCount = 0
        var totalSats = 0L
        val errors = mutableListOf<String>()

        for (token in tokens) {
            try {
                Log.d(TAG, "Processing recovery token: ${token.id}")
                Log.d(TAG, "  Amount: ${token.amount} sats, reason: ${token.reason}")

                // Decode the cashuA token to get proofs
                val proofs = decodeTokenToProofs(token.token)
                if (proofs == null) {
                    Log.e(TAG, "Failed to decode token ${token.id}")
                    errors.add("Failed to decode token")
                    continue
                }

                // Verify proofs are still valid at mint before publishing
                val secrets = proofs.map { it.secret }
                val stateMap = cashuBackend.verifyProofStatesBySecret(secrets)
                if (stateMap != null) {
                    val spentSecrets = stateMap.filterValues { it == CashuBackend.ProofStateResult.SPENT }.keys
                    if (spentSecrets.isNotEmpty()) {
                        Log.w(TAG, "Token ${token.id} contains ${spentSecrets.size} spent proofs - skipping")
                        // Remove token since proofs are spent
                        walletStorage.removeRecoveryToken(token.id)
                        continue
                    }
                }

                // Publish to NIP-60
                val eventId = sync.publishProofs(proofs, token.mintUrl)
                if (eventId != null) {
                    Log.d(TAG, "✅ Recovered token ${token.id}: ${token.amount} sats -> NIP-60 event $eventId")
                    walletStorage.removeRecoveryToken(token.id)
                    recoveredCount++
                    totalSats += token.amount
                } else {
                    Log.e(TAG, "Failed to publish token ${token.id} to NIP-60")
                    errors.add("NIP-60 publish failed for ${token.amount} sats")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error recovering token ${token.id}: ${e.message}", e)
                errors.add("Error: ${e.message}")
            }
        }

        // Refresh balance after recovery
        if (recoveredCount > 0) {
            sync.clearCache()
            refreshBalance()
        }

        // Update recovery tokens flag
        _hasRecoveryTokens.value = walletStorage.getRecoveryTokens().isNotEmpty()

        Log.d(TAG, "Recovery complete: $recoveredCount tokens, $totalSats sats")

        return ClaimResult(
            success = errors.isEmpty(),
            claimedCount = recoveredCount,
            totalSats = totalSats,
            error = if (errors.isNotEmpty()) errors.joinToString("; ") else null
        )
    }

    /**
     * Decode a cashuA token string to a list of CashuProof objects.
     */
    private fun decodeTokenToProofs(tokenStr: String): List<CashuProof>? {
        try {
            if (!tokenStr.startsWith("cashuA")) {
                Log.e(TAG, "Invalid token format - doesn't start with cashuA")
                return null
            }

            val base64Part = tokenStr.substring(6)
            val jsonBytes = android.util.Base64.decode(base64Part, android.util.Base64.URL_SAFE)
            val json = org.json.JSONObject(String(jsonBytes))

            val tokenArray = json.getJSONArray("token")
            val proofs = mutableListOf<CashuProof>()

            for (i in 0 until tokenArray.length()) {
                val tokenObj = tokenArray.getJSONObject(i)
                val proofsArray = tokenObj.getJSONArray("proofs")

                for (j in 0 until proofsArray.length()) {
                    val proofObj = proofsArray.getJSONObject(j)
                    proofs.add(CashuProof(
                        amount = proofObj.getLong("amount"),
                        id = proofObj.getString("id"),
                        secret = proofObj.getString("secret"),
                        C = proofObj.getString("C")
                    ))
                }
            }

            return proofs
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode token: ${e.message}", e)
            return null
        }
    }

    /**
     * Save a pending deposit for potential recovery.
     * Used when creating deposit invoices (including cross-mint).
     */
    fun savePendingDeposit(quoteId: String, amount: Long, invoice: String, expiry: Long = 0) {
        val deposit = PendingDeposit(
            quoteId = quoteId,
            amount = amount,
            invoice = invoice,
            createdAt = System.currentTimeMillis() / 1000,
            expiry = expiry,
            minted = false
        )
        walletStorage.savePendingDeposit(deposit)
        Log.d(TAG, "Saved pending deposit: quoteId=$quoteId, amount=$amount")
    }

    /**
     * Clear all stale pending deposits.
     * Called manually from developer options when deposits are confirmed to be unpaid/abandoned.
     *
     * @return Number of deposits cleared
     */
    fun clearStaleDeposits(): Int {
        val deposits = walletStorage.getPendingDeposits().filter { !it.minted }
        val count = deposits.size
        deposits.forEach { deposit ->
            walletStorage.removePendingDeposit(deposit.quoteId)
            Log.d(TAG, "Cleared stale deposit: ${deposit.quoteId}, ${deposit.amount} sats")
        }
        Log.d(TAG, "Cleared $count stale deposits")
        return count
    }

    // === Pending Bridge Payment Methods ===

    /**
     * Get count of in-progress bridge payments.
     */
    fun getPendingBridgePaymentCount(): Int {
        return walletStorage.getInProgressBridgePayments().size
    }

    /**
     * Get all pending bridge payments (in-progress or failed).
     */
    fun getPendingBridgePayments(): List<PendingBridgePayment> {
        return walletStorage.getPendingBridgePayments()
    }

    /**
     * Get in-progress bridge payments only.
     */
    fun getInProgressBridgePayments(): List<PendingBridgePayment> {
        return walletStorage.getInProgressBridgePayments()
    }

    /**
     * Clear completed/failed bridge payments from storage.
     * Keeps only in-progress payments.
     *
     * @return Number of payments cleared
     */
    fun clearCompletedBridgePayments(): Int {
        val all = walletStorage.getPendingBridgePayments()
        val toRemove = all.filter { !it.isInProgress() }
        toRemove.forEach { payment ->
            walletStorage.removePendingBridgePayment(payment.id)
        }
        Log.d(TAG, "Cleared ${toRemove.size} completed/failed bridge payments")
        return toRemove.size
    }

    /**
     * Get a specific pending bridge payment by ID.
     */
    fun getBridgePayment(paymentId: String): PendingBridgePayment? {
        return walletStorage.getPendingBridgePayments().find { it.id == paymentId }
    }

    /**
     * Get the saved mint URL.
     */
    fun getSavedMintUrl(): String? = walletStorage.getMintUrl()

    /**
     * Get the current connected mint URL.
     * Returns null if not connected to a mint.
     */
    fun getCurrentMintUrl(): String? = cashuBackend.getCurrentMintUrl()

    /**
     * Get a deposit invoice from the connected mint.
     * Used by driver for cross-mint bridge - rider pays this invoice via Lightning.
     *
     * @param amountSats Amount to deposit in satoshis
     * @return MintQuote with invoice, or null if failed
     */
    suspend fun getDepositInvoice(amountSats: Long): MintQuote? {
        val mintUrl = cashuBackend.getCurrentMintUrl()
        if (mintUrl == null) {
            Log.e(TAG, "Cannot get deposit invoice - not connected to mint")
            return null
        }
        return cashuBackend.getMintQuoteAtMint(amountSats, mintUrl)
    }

    /**
     * Fetch and update wallet diagnostics.
     * Shows NIP-60 balance (the wallet) and any unverified proofs.
     * Note: cdk-kotlin balance is NOT shown - it's an implementation detail.
     */
    suspend fun updateDiagnostics() {
        try {
            val displayedBalance = _balance.value.availableSats
            val cachedBalance = walletStorage.getCachedBalance().availableSats
            val nip60Balance = nip60Sync?.getBalance()
            val nip60ProofCount = nip60Sync?.let {
                val proofs = it.fetchProofs(forceRefresh = false)
                proofs.size
            }
            val pendingDeposits = walletStorage.getPendingDeposits().size
            val unverifiedProofs = walletStorage.getUnverifiedProofs()
            val unverifiedBalance = unverifiedProofs.sumOf { it.amount }
            val unverifiedCount = unverifiedProofs.size

            val issues = mutableListOf<String>()

            // Check for unverified proofs (mint was offline)
            val mintReachable = unverifiedCount == 0 || displayedBalance > 0

            if (unverifiedCount > 0 && displayedBalance == 0L) {
                issues.add("Mint offline: $unverifiedBalance sats need verification")
            }

            if (nip60Balance != null) {
                if (nip60Balance.availableSats == 0L && displayedBalance > 0) {
                    issues.add("Nostr not synced (0 sats on relays)")
                } else if (nip60Balance.availableSats != displayedBalance && unverifiedCount == 0) {
                    // Check if this is due to mint mismatch
                    val currentMint = cashuBackend.getCurrentMintUrl()
                    val proofs = nip60Sync?.fetchProofs(forceRefresh = false) ?: emptyList()
                    val currentMintProofs = if (currentMint != null) {
                        proofs.filter { normalizeUrl(it.mintUrl) == normalizeUrl(currentMint) }
                    } else {
                        proofs
                    }
                    val otherMintProofs = proofs.filter {
                        currentMint == null || normalizeUrl(it.mintUrl) != normalizeUrl(currentMint)
                    }

                    if (otherMintProofs.isNotEmpty() && currentMintProofs.isEmpty()) {
                        // All proofs are on a different mint
                        val otherMint = otherMintProofs.first().mintUrl
                            .substringAfter("://").substringBefore("/")
                        issues.add("⚠️ Funds on different mint: $otherMint")
                        issues.add("Change mint in Settings → Wallet")
                    } else {
                        issues.add("Nostr balance: ${nip60Balance.availableSats} sats")
                    }
                }
            } else {
                if (displayedBalance > 0) {
                    issues.add("NIP-60 sync not available")
                }
            }

            if (pendingDeposits > 0) {
                issues.add("$pendingDeposits pending deposit(s)")
            }

            val isNip60Synced = nip60Balance != null &&
                    nip60Balance.availableSats == displayedBalance

            _diagnostics.value = WalletDiagnostics(
                displayedBalance = displayedBalance,
                nip60Balance = nip60Balance?.availableSats,
                nip60ProofCount = nip60ProofCount,
                cachedBalance = cachedBalance,
                unverifiedBalance = unverifiedBalance,
                unverifiedCount = unverifiedCount,
                isNip60Synced = isNip60Synced,
                lastNip60Sync = nip60Balance?.lastUpdated,
                pendingDeposits = pendingDeposits,
                mintReachable = mintReachable,
                issues = issues
            )

            Log.d(TAG, "Diagnostics: displayed=$displayedBalance, nip60=${nip60Balance?.availableSats}, unverified=$unverifiedBalance, issues=${issues.size}")
        } catch (e: CancellationException) {
            // Scope was cancelled (e.g., user navigated away) - not an error
            Log.d(TAG, "Diagnostics update cancelled (scope left)")
        } catch (e: IllegalStateException) {
            // Compose scope exception - user navigated away
            if (e.message?.contains("left the composition") == true) {
                Log.d(TAG, "Diagnostics update cancelled (Compose scope disposed)")
            } else {
                Log.e(TAG, "Failed to update diagnostics", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update diagnostics", e)
        }
    }

    /**
     * Verify wallet balance with the mint and refresh diagnostics.
     * Checks both local cdk-kotlin proofs AND NIP-60 proofs.
     *
     * @deprecated Use [syncWallet] instead. This method is no longer exposed in UI
     * and may be removed in a future version. syncWallet() provides comprehensive
     * wallet synchronization including URL migration and spent proof cleanup.
     *
     * @return Verified balance in sats, or -1 on error
     */
    @Deprecated("Use syncWallet() instead", ReplaceWith("syncWallet()"))
    suspend fun verifyAndRefreshBalance(): Long {
        if (!_isConnected.value) {
            Log.e(TAG, "Cannot verify - not connected to wallet")
            return -1
        }

        val sync = nip60Sync

        try {
            Log.d(TAG, "Verifying wallet balance with mint...")

            // First try local cdk-kotlin proofs
            val localVerified = cashuBackend.getVerifiedBalance()
            if (localVerified != null && localVerified > 0) {
                Log.d(TAG, "Verified local balance: $localVerified sats")
                _balance.value = WalletBalance(
                    availableSats = localVerified,
                    pendingSats = 0,
                    lastUpdated = System.currentTimeMillis()
                )
                walletStorage.cacheBalance(_balance.value)
                updateDiagnostics()
                return localVerified
            }

            // If local is empty, verify NIP-60 proofs
            if (sync != null) {
                Log.d(TAG, "Local proofs empty, verifying NIP-60 proofs...")
                val nip60Proofs = sync.fetchProofs(forceRefresh = true)
                if (nip60Proofs.isNotEmpty()) {
                    val cashuProofs = nip60Proofs.map { it.toCashuProof() }
                    val result = cashuBackend.verifyProofsBalance(cashuProofs)

                    if (result != null) {
                        val (verifiedBalance, spentSecrets) = result
                        Log.d(TAG, "Verified NIP-60 balance: $verifiedBalance sats (${spentSecrets.size} spent)")

                        // Clean up spent proof events
                        if (spentSecrets.isNotEmpty()) {
                            val spentEventIds = nip60Proofs
                                .filter { it.secret in spentSecrets }
                                .map { it.eventId }
                                .distinct()

                            // CRITICAL: One event can contain MANY proofs!
                            // Find remaining proofs in affected events that are NOT spent
                            val remainingProofsToRepublish = nip60Proofs.filter { proof ->
                                proof.eventId in spentEventIds && proof.secret !in spentSecrets
                            }

                            if (remainingProofsToRepublish.isNotEmpty()) {
                                Log.d(TAG, "Republishing ${remainingProofsToRepublish.size} remaining proofs before delete")
                                val cashuProofsToRepublish = remainingProofsToRepublish.map { it.toCashuProof() }
                                val currentMintUrl = cashuBackend.getCurrentMintUrl() ?: remainingProofsToRepublish.first().mintUrl
                                // Include del array to atomically mark old events as consumed
                                val newEventId = sync.publishProofs(cashuProofsToRepublish, currentMintUrl, spentEventIds)
                                if (newEventId == null) {
                                    // Save recovery token for manual recovery
                                    saveRecoveryTokenFallback(cashuProofsToRepublish, currentMintUrl, "nip60_republish_failed_smartresync")
                                } else {
                                    Log.d(TAG, "Republished remaining proofs to new event: $newEventId (del: ${spentEventIds.size} events)")
                                }
                            }

                            Log.d(TAG, "Deleting ${spentEventIds.size} spent NIP-60 events")
                            // NIP-09 deletion as backup (del arrays are primary)
                            sync.deleteProofEvents(spentEventIds)
                            sync.clearCache()
                        }

                        _balance.value = WalletBalance(
                            availableSats = verifiedBalance,
                            pendingSats = 0,
                            lastUpdated = System.currentTimeMillis()
                        )
                        walletStorage.cacheBalance(_balance.value)
                        updateDiagnostics()
                        return verifiedBalance
                    }
                }
            }

            // Fallback to cdk-kotlin balance if verification fails
            val cdkBalance = cashuBackend.getCdkBalance() ?: 0L
            Log.d(TAG, "Fallback to cdk-kotlin balance: $cdkBalance sats")
            _balance.value = WalletBalance(
                availableSats = cdkBalance,
                pendingSats = 0,
                lastUpdated = System.currentTimeMillis()
            )
            walletStorage.cacheBalance(_balance.value)
            updateDiagnostics()
            return cdkBalance
        } catch (e: Exception) {
            Log.e(TAG, "Verify failed: ${e.message}", e)
            return -1
        }
    }

    // ========================================
    // Manual Recovery
    // ========================================

    /**
     * Manually recover a deposit using a quote ID.
     * Use this when you have a quote ID from a paid Lightning invoice
     * that failed to mint tokens.
     *
     * @param quoteId The quote ID (from the invoice generation)
     * @param amountSats The amount paid in satoshis
     * @return RecoveryResult indicating success or failure with details
     */
    suspend fun manualRecoverDeposit(quoteId: String, amountSats: Long): RecoveryResult {
        if (!_isConnected.value) {
            return RecoveryResult(false, "Wallet not connected to mint")
        }

        Log.d(TAG, "Manual recovery requested: quote=$quoteId, amount=$amountSats")

        try {
            // First check the quote status
            val quote = cashuBackend.checkMintQuote(quoteId)
            if (quote == null) {
                return RecoveryResult(false, "Quote not found on mint. It may have expired or never existed.")
            }

            if (quote.state == MintQuoteState.ISSUED) {
                return RecoveryResult(false, "Tokens already issued for this quote. Check your balance.")
            }

            if (quote.state != MintQuoteState.PAID) {
                return RecoveryResult(false, "Quote not paid yet (state: ${quote.state}). Pay the invoice first.")
            }

            // Verify amount matches
            if (quote.amount != amountSats) {
                Log.w(TAG, "Amount mismatch: provided=$amountSats, quote=${quote.amount}")
                // Try with the quote's amount instead
            }

            val actualAmount = quote.amount

            // Try cdk-kotlin first (in case the quote is now trackable)
            val mintResult = cashuBackend.mintTokens(quoteId, actualAmount)
            var proofsToPublish: List<CashuProof>? = mintResult?.proofs

            // Fallback to HTTP recovery
            if (mintResult == null) {
                Log.d(TAG, "cdk-kotlin mint failed, using HTTP recovery...")
                proofsToPublish = cashuBackend.recoverDeposit(quoteId, actualAmount)
            }

            val success = proofsToPublish != null && proofsToPublish.isNotEmpty()

            return if (success) {
                // Publish proofs to NIP-60 for cross-device sync
                val mintUrl = cashuBackend.getCurrentMintUrl()
                if (proofsToPublish != null && mintUrl != null) {
                    val eventId = nip60Sync?.publishProofs(proofsToPublish, mintUrl)
                    Log.d(TAG, "Published ${proofsToPublish.size} proofs to NIP-60, event: $eventId")
                }

                // Update balance
                refreshBalance()

                // Record transaction
                addTransaction(PaymentTransaction(
                    id = quoteId,
                    type = TransactionType.DEPOSIT,
                    amountSats = actualAmount,
                    timestamp = System.currentTimeMillis(),
                    rideId = null,
                    counterpartyPubKey = null,
                    status = "Manual recovery"
                ))

                RecoveryResult(true, "Successfully recovered $actualAmount sats!")
            } else {
                RecoveryResult(false, "Recovery failed. The mint may have already issued tokens, or there was a cryptographic error.")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Manual recovery failed: ${e.message}", e)
            return RecoveryResult(false, "Error: ${e.message}")
        }
    }

    /**
     * Get list of pending deposits that might need recovery.
     */
    fun getPendingDeposits(): List<PendingDeposit> {
        return walletStorage.getPendingDeposits()
    }

    /**
     * Add a pending deposit manually (for recovery purposes).
     */
    fun addPendingDeposit(quoteId: String, amount: Long, invoice: String = "") {
        val deposit = PendingDeposit(
            quoteId = quoteId,
            amount = amount,
            invoice = invoice,
            createdAt = System.currentTimeMillis(),
            expiry = 0,  // Unknown
            minted = false
        )
        walletStorage.savePendingDeposit(deposit)
        Log.d(TAG, "Added pending deposit for recovery: $quoteId, $amount sats")
    }
}

/**
 * Result of a recovery attempt.
 */
data class RecoveryResult(
    val success: Boolean,
    val message: String
)

/**
 * Result of NIP-60 resync operation.
 */
data class ResyncResult(
    val success: Boolean,
    val message: String,
    val proofsDeleted: Int,
    val proofsPublished: Int,
    val newBalance: Long
)

/**
 * Mint option for wallet setup UI.
 */
data class MintOption(
    val name: String,
    val description: String,
    val url: String,
    val recommended: Boolean
)

/**
 * Result of a syncWallet() operation.
 */
data class SyncResult(
    val success: Boolean,
    val message: String,
    val verifiedBalance: Long = 0,
    val unverifiedBalance: Long = 0,
    val spentCount: Int = 0,
    val mintReachable: Boolean = true
)

/**
 * Result of NUT-13/NUT-09 seed-based recovery.
 */
data class SeedRecoveryResult(
    val success: Boolean,
    val message: String,
    val recoveredSats: Long,
    val proofCount: Int = 0
)
