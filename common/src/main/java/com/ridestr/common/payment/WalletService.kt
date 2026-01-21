package com.ridestr.common.payment

import android.content.Context
import android.util.Log
import com.ridestr.common.payment.cashu.CashuBackend
import com.ridestr.common.payment.cashu.CashuBackend.ProofStateResult
import com.ridestr.common.payment.cashu.CashuProof
import com.ridestr.common.payment.cashu.MintCapabilities
import com.ridestr.common.payment.cashu.Nip60WalletSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
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
                name = "The Milk Road",
                description = "Recommended default",
                url = "https://mint.themilkroad.org",
                recommended = true
            ),
            MintOption(
                name = "LN Server",
                description = "Best rated on cashumints.space",
                url = "https://mint.lnserver.com/",
                recommended = false
            ),
            MintOption(
                name = "Minibits",
                description = "Popular, widely used",
                url = "https://mint.minibits.cash/Bitcoin",
                recommended = false
            )
        )
    }

    private val cashuBackend = CashuBackend(context, walletKeyManager)
    private val walletStorage = WalletStorage(context)

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

    init {
        // Load cached balance
        _balance.value = walletStorage.getCachedBalance()
        _transactions.value = walletStorage.getTransactions()

        // Try to restore connection from saved mint URL
        val savedMintUrl = walletStorage.getMintUrl()
        if (savedMintUrl != null) {
            _currentMintName.value = DEFAULT_MINTS.find { it.url == savedMintUrl }?.name ?: "Custom"
        }
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
        }
        return success
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
        if (syncResult.success) {
            nip60Sync?.publishWalletMetadata(newMintUrl)
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
            // Step 1: Fetch ALL NIP-60 proofs (regardless of stored mint URL)
            val nip60Proofs = sync.fetchProofs(forceRefresh = true)
            val unverifiedCache = walletStorage.getUnverifiedProofs()
            val allNip60Balance = nip60Proofs.sumOf { it.amount }

            Log.d(TAG, "Fetched ${nip60Proofs.size} NIP-60 proofs ($allNip60Balance sats), ${unverifiedCache.size} cached")

            if (nip60Proofs.isEmpty() && unverifiedCache.isEmpty()) {
                Log.d(TAG, "No proofs found - balance is 0")
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

            // Step 2: Try ALL proofs at CURRENT mint first (mint URL may have changed)
            val allCashuProofs = nip60Proofs.map { it.toCashuProof() }
            Log.d(TAG, "Trying ${allCashuProofs.size} proofs at current mint: $mintUrl")

            val currentMintResult = cashuBackend.verifyProofsBalance(allCashuProofs)

            if (currentMintResult != null) {
                val (_, spentSecrets) = currentMintResult
                val validProofs = nip60Proofs.filter { it.secret !in spentSecrets }
                val validBalance = validProofs.sumOf { it.amount }
                val spentProofs = nip60Proofs.filter { it.secret in spentSecrets }

                Log.d(TAG, "Current mint verified: $validBalance sats valid, ${spentProofs.size} spent")

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
                        val newEventId = sync.publishProofs(cashuProofs, mintUrl)
                        if (newEventId == null) {
                            // Fallback: store in cdk-kotlin to prevent loss
                            Log.w(TAG, "Failed to republish remaining proofs to NIP-60, storing in cdk-kotlin")
                            cashuBackend.storeRecoveredProofs(cashuProofs, mintUrl)
                        } else {
                            Log.d(TAG, "Republished remaining proofs to new event: $newEventId")
                        }
                    }

                    sync.deleteProofEvents(spentEventIds)
                    sync.clearCache()
                    Log.d(TAG, "Deleted ${spentEventIds.size} spent proof events")
                }

                // Check if any have outdated mint URL and need re-publishing
                val proofsWithOldUrl = validProofs.filter {
                    normalizeUrl(it.mintUrl) != normalizeUrl(mintUrl)
                }

                if (proofsWithOldUrl.isNotEmpty()) {
                    Log.d(TAG, "Updating ${proofsWithOldUrl.size} proofs with new mint URL")
                    // Re-publish proofs with correct mint URL
                    sync.republishProofsWithNewMint(proofsWithOldUrl, mintUrl)
                }

                // Use NIP-60 total as balance (trust what's stored, don't rely on verification)
                // This prevents losing funds due to hashToCurve mismatches in verification
                val nip60Balance = nip60Proofs.sumOf { it.amount } - spentProofs.sumOf { it.amount }

                // Update balance
                _balance.value = WalletBalance(
                    availableSats = nip60Balance,
                    pendingSats = _balance.value.pendingSats,
                    lastUpdated = System.currentTimeMillis()
                )
                walletStorage.cacheBalance(_balance.value)
                walletStorage.clearUnverifiedProofs()
                updateDiagnostics()

                val updatedMsg = if (proofsWithOldUrl.isNotEmpty()) " (updated ${proofsWithOldUrl.size} proof URLs)" else ""
                val spentMsg = if (spentProofs.isNotEmpty()) ", removed ${spentProofs.size} spent" else ""
                return@withLock SyncResult(
                    success = true,
                    message = "Synced: $nip60Balance sats$updatedMsg$spentMsg",
                    verifiedBalance = nip60Balance,
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
                                    val newEventId = sync.publishProofs(cashuProofs, otherMintUrl)
                                    if (newEventId == null) {
                                        // Fallback: store in cdk-kotlin to prevent loss
                                        Log.w(TAG, "Failed to republish remaining proofs to NIP-60, storing in cdk-kotlin")
                                        cashuBackend.storeRecoveredProofs(cashuProofs, otherMintUrl)
                                    } else {
                                        Log.d(TAG, "Republished remaining proofs to new event: $newEventId")
                                    }
                                }

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

            // No mints worked - trust NIP-60 balance anyway (better than losing funds)
            Log.w(TAG, "Could not verify proofs at any mint - trusting NIP-60 balance")
            _balance.value = WalletBalance(
                availableSats = allNip60Balance,  // Trust NIP-60 instead of setting to 0
                pendingSats = _balance.value.pendingSats,
                lastUpdated = System.currentTimeMillis()
            )
            walletStorage.cacheBalance(_balance.value)
            updateDiagnostics()

            return@withLock SyncResult(
                success = true,  // Changed to true - we have a balance, just unverified
                message = "Synced: $allNip60Balance sats (unverified - mint unreachable)",
                verifiedBalance = 0,
                unverifiedBalance = allNip60Balance,
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
                    val newEventId = sync.publishProofs(cashuProofs, mintUrl)
                    if (newEventId == null) {
                        // Fallback: store in cdk-kotlin to prevent loss
                        Log.w(TAG, "Failed to republish remaining proofs to NIP-60, storing in cdk-kotlin")
                        cashuBackend.storeRecoveredProofs(cashuProofs, mintUrl)
                    } else {
                        Log.d(TAG, "Republished remaining proofs to new event: $newEventId")
                    }
                }

                Log.d(TAG, "Deleting ${spentEventIds.size} stale NIP-60 events")
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

        val (htlcToken, changeProofs) = result

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
            val cashuProofs = remainingProofsToRepublish.map { it.toCashuProof() }
            val newEventId = sync.publishProofs(cashuProofs, mintUrl)
            if (newEventId != null) {
                Log.d(TAG, "Republished remaining proofs to new event: $newEventId")
            } else {
                Log.e(TAG, "CRITICAL: Failed to republish remaining proofs! They may be lost.")
                Log.e(TAG, "Remaining amount: ${remainingProofsToRepublish.sumOf { it.amount }} sats")
                // Store in cdk-kotlin as emergency fallback
                try {
                    cashuBackend.storeRecoveredProofs(cashuProofs, mintUrl)
                } catch (e: Exception) {
                    Log.e(TAG, "cdk-kotlin fallback also failed: ${e.message}")
                }
            }
        }

        // NOW safe to delete old events (remaining proofs are in new event)
        Log.d(TAG, "Deleting ${affectedEventIds.size} old NIP-60 events")
        sync.deleteProofEvents(affectedEventIds)

        // Step 4: Publish change proofs to NIP-60 with retry
        if (changeProofs.isNotEmpty()) {
            var changeEventId: String? = null
            var publishAttempts = 0
            val maxAttempts = 3
            val changeAmount = changeProofs.sumOf { it.amount }

            while (changeEventId == null && publishAttempts < maxAttempts) {
                publishAttempts++
                changeEventId = sync.publishProofs(changeProofs, mintUrl)
                if (changeEventId == null && publishAttempts < maxAttempts) {
                    Log.w(TAG, "Change publish failed, retrying...")
                    kotlinx.coroutines.delay(1000)
                }
            }

            if (changeEventId != null) {
                Log.d(TAG, "Published ${changeProofs.size} change proofs ($changeAmount sats) to NIP-60: $changeEventId")
            } else {
                Log.e(TAG, "Failed to publish change proofs after $maxAttempts attempts - using cdk-kotlin fallback")
                try {
                    cashuBackend.storeRecoveredProofs(changeProofs, mintUrl)
                } catch (e: Exception) {
                    Log.e(TAG, "cdk-kotlin fallback failed: ${e.message}")
                }
            }
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

        // Update balance
        val newBalance = _balance.value.availableSats - amountSats
        _balance.value = _balance.value.copy(
            availableSats = newBalance,
            pendingSats = amountSats,
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
        if (claimResult.receivedProofs.isNotEmpty()) {
            val sync = nip60Sync
            if (sync != null) {
                val eventId = sync.publishProofs(claimResult.receivedProofs, claimResult.mintUrl)
                Log.d(TAG, "Published ${claimResult.receivedProofs.size} received proofs to NIP-60: $eventId")
            }

            // TODO: cdk-kotlin doesn't have an API to import external proofs
            // Withdrawals will use proofs from NIP-60 directly once implemented
            // For now, balance shows in NIP-60 but cdk-kotlin melt won't find them
            Log.d(TAG, "Note: Proofs in NIP-60 only - cdk-kotlin withdrawals need NIP-60 melt implementation")
        }

        // Update balance
        val newBalance = _balance.value.availableSats + claimResult.amountSats
        _balance.value = _balance.value.copy(
            availableSats = newBalance,
            lastUpdated = System.currentTimeMillis()
        )
        walletStorage.cacheBalance(_balance.value)

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
        Log.d(TAG, "Received ${claimResult.amountSats} sats, new balance: $newBalance")

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
                    // Publish refunded proofs to NIP-60
                    val sync = nip60Sync
                    if (sync != null && refundResult.refundedProofs.isNotEmpty()) {
                        val eventId = sync.publishProofs(refundResult.refundedProofs, refundResult.mintUrl)
                        Log.d(TAG, "Published ${refundResult.refundedProofs.size} refunded proofs to NIP-60: $eventId")
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
            Log.d(TAG, "Marked HTLC as claimed by paymentHash: ${paymentHash.take(16)}...")
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
                    // Try publishing with retry (relay connection can be flaky)
                    var eventId: String? = null
                    var publishAttempts = 0
                    val maxAttempts = 3

                    while (eventId == null && publishAttempts < maxAttempts) {
                        publishAttempts++
                        Log.d(TAG, "Publishing proofs to NIP-60 (attempt $publishAttempts/$maxAttempts)")
                        eventId = sync.publishProofs(mintResult.proofs, mintUrl)

                        if (eventId == null && publishAttempts < maxAttempts) {
                            Log.w(TAG, "NIP-60 publish failed, retrying in 2 seconds...")
                            kotlinx.coroutines.delay(2000)
                        }
                    }

                    if (eventId != null) {
                        Log.d(TAG, "Published ${mintResult.proofs.size} proofs to NIP-60: $eventId")
                    } else {
                        // NIP-60 publish failed after retries but proofs are in cdk-kotlin
                        // This is a degraded state but not a total failure
                        Log.e(TAG, "CRITICAL: Failed to publish proofs to NIP-60 after $maxAttempts attempts!")
                        Log.e(TAG, "Proofs are in cdk-kotlin local storage. Use 'Recover Local Funds' to recover.")
                    }
                }

                // Refresh balance from NIP-60 (source of truth)
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

                Log.d(TAG, "Deposit complete: ${quote.amount} sats minted, NIP-60 balance: $newBalance sats")

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

        val proofs = selection.proofs.map { it.toCashuProof() }
        Log.d(TAG, "Selected ${proofs.size} proofs from NIP-60 (${selection.totalAmount} sats) for ${quote.totalAmount} sats withdrawal")

        // Execute melt with NIP-60 proofs
        val result = cashuBackend.meltWithProofs(quote.quote, proofs, quote.totalAmount)

        if (result == null) {
            return Result.failure(Exception("Withdrawal failed - mint error"))
        }

        if (result.paid) {
            // CRITICAL: Don't just delete events - republish remaining proofs first!
            // One event can contain MANY proofs. If we delete the event, we lose ALL proofs in it.
            // We must: 1) Find remaining proofs in affected events, 2) Republish them, 3) Delete old events
            val spentSecrets = selection.proofs.map { it.secret }.toSet()
            val affectedEventIds = selection.proofs.map { it.eventId }.distinct()

            // Fetch all current proofs to find remaining (not spent) proofs in affected events
            val allProofs = sync.fetchProofs(forceRefresh = true)
            val remainingProofsToRepublish = allProofs.filter { proof ->
                proof.eventId in affectedEventIds && proof.secret !in spentSecrets
            }

            if (remainingProofsToRepublish.isNotEmpty()) {
                Log.d(TAG, "IMPORTANT: ${remainingProofsToRepublish.size} remaining proofs in affected events need republishing")
                Log.d(TAG, "Remaining: ${remainingProofsToRepublish.sumOf { it.amount }} sats")

                // Republish remaining proofs to a NEW event before deleting old
                val cashuProofs = remainingProofsToRepublish.map { it.toCashuProof() }
                val newEventId = sync.publishProofs(cashuProofs, mintUrl)
                if (newEventId != null) {
                    Log.d(TAG, "Republished remaining proofs to new event: $newEventId")
                } else {
                    Log.e(TAG, "CRITICAL: Failed to republish remaining proofs! They may be lost.")
                    Log.e(TAG, "Remaining amount: ${remainingProofsToRepublish.sumOf { it.amount }} sats")
                    // Store in cdk-kotlin as emergency fallback
                    try {
                        cashuBackend.storeRecoveredProofs(cashuProofs, mintUrl)
                    } catch (e: Exception) {
                        Log.e(TAG, "cdk-kotlin fallback also failed: ${e.message}")
                    }
                }
            }

            // NOW safe to delete old events (remaining proofs are in new event)
            sync.deleteProofEvents(affectedEventIds)
            Log.d(TAG, "Deleted ${affectedEventIds.size} old NIP-60 proof events")

            // Publish change proofs to NIP-60 with retry (CRITICAL - don't lose change!)
            if (result.change.isNotEmpty()) {
                var changeEventId: String? = null
                var publishAttempts = 0
                val maxAttempts = 3
                val changeAmount = result.change.sumOf { it.amount }

                while (changeEventId == null && publishAttempts < maxAttempts) {
                    publishAttempts++
                    Log.d(TAG, "Publishing change proofs to NIP-60 (attempt $publishAttempts/$maxAttempts)")
                    changeEventId = sync.publishProofs(result.change, mintUrl)

                    if (changeEventId == null && publishAttempts < maxAttempts) {
                        Log.w(TAG, "Change publish failed, retrying in 2 seconds...")
                        kotlinx.coroutines.delay(2000)
                    }
                }

                if (changeEventId != null) {
                    Log.d(TAG, "Published ${result.change.size} change proofs ($changeAmount sats) to NIP-60: $changeEventId")
                } else {
                    Log.e(TAG, "CRITICAL: Failed to publish change proofs to NIP-60 after $maxAttempts attempts!")
                    Log.e(TAG, "Change amount: $changeAmount sats - stored in cdk-kotlin only. Use 'Recover Local Funds' to recover.")
                    // Store change in cdk-kotlin as fallback (even though proofs came from NIP-60)
                    try {
                        cashuBackend.storeRecoveredProofs(result.change, mintUrl)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to store change in cdk-kotlin fallback: ${e.message}")
                    }
                }
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

            // Record transaction
            addTransaction(PaymentTransaction(
                id = quote.quote,
                type = TransactionType.WITHDRAWAL,
                amountSats = quote.amount,
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
     * Sync wallet metadata to NIP-60 Nostr events.
     */
    suspend fun syncToNip60() {
        val mintUrl = cashuBackend.getCurrentMintUrl()
        if (mintUrl != null) {
            nip60Sync?.publishWalletMetadata(mintUrl)
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

            // If NIP-60 is higher than verified, it has stale (spent) proofs
            if (nip60Balance > verifiedBalance) {
                Log.w(TAG, "NIP-60 has stale proofs! Clearing and re-syncing...")

                // Delete all existing NIP-60 proof events
                val existingProofs = sync.fetchProofs(forceRefresh = true)
                val eventIds = existingProofs.map { it.eventId }.distinct()
                if (eventIds.isNotEmpty()) {
                    sync.deleteProofEvents(eventIds)
                    Log.d(TAG, "Deleted ${eventIds.size} stale NIP-60 proof events")
                }

                // Note: We don't republish here because the proofs have been spent.
                // The remaining balance (change proofs) will be in cdk-kotlin.
                // They'll get published to NIP-60 on next deposit or manual sync.
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

            // Step 4: Delete old NIP-60 events
            Log.d(TAG, "Step 4: Deleting ${nip60EventIds.size} old NIP-60 events...")
            if (nip60EventIds.isNotEmpty()) {
                sync.deleteProofEvents(nip60EventIds)
            }

            // Step 5: Publish verified proofs to NIP-60
            Log.d(TAG, "Step 5: Publishing ${verifiedProofs.size} verified proofs to NIP-60...")
            val eventId = sync.publishProofs(verifiedProofs, mintUrl)

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
            // Clean up expired pending deposits first
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
                Log.w(TAG, "Pending deposit expired: ${deposit.quoteId}")
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
     * Get the saved mint URL.
     */
    fun getSavedMintUrl(): String? = walletStorage.getMintUrl()

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
                                val newEventId = sync.publishProofs(cashuProofsToRepublish, currentMintUrl)
                                if (newEventId == null) {
                                    // Fallback: store in cdk-kotlin to prevent loss
                                    Log.w(TAG, "Failed to republish remaining proofs to NIP-60, storing in cdk-kotlin")
                                    cashuBackend.storeRecoveredProofs(cashuProofsToRepublish, currentMintUrl)
                                } else {
                                    Log.d(TAG, "Republished remaining proofs to new event: $newEventId")
                                }
                            }

                            Log.d(TAG, "Deleting ${spentEventIds.size} spent NIP-60 events")
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
