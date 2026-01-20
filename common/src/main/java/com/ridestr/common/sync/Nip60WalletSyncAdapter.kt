package com.ridestr.common.sync

import android.util.Log
import com.ridestr.common.nostr.relay.RelayManager
import com.ridestr.common.payment.cashu.Nip60WalletSync
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import kotlinx.coroutines.delay

/**
 * Adapter that wraps existing Nip60WalletSync to implement SyncableProfileData.
 *
 * This is the highest priority sync (order=0) because:
 * - Wallet is needed for ride payments
 * - User's funds should be restored first
 *
 * Uses existing NIP-60 event kinds:
 * - 7375: Unspent proofs
 * - 7376: Spending history
 * - 17375: Wallet metadata
 */
class Nip60WalletSyncAdapter(
    private val nip60Sync: Nip60WalletSync
) : SyncableProfileData {

    companion object {
        private const val TAG = "Nip60WalletSyncAdapter"
    }

    override val kind: Int = Nip60WalletSync.KIND_TOKEN  // 7375
    override val dTag: String = ""  // Not replaceable, uses multiple events
    override val syncOrder: Int = 0  // Highest priority
    override val displayName: String = "Wallet"

    override suspend fun fetchFromNostr(
        signer: NostrSigner,
        relayManager: RelayManager
    ): SyncResult {
        return try {
            Log.d(TAG, "Fetching wallet from Nostr...")

            val walletState = nip60Sync.restoreFromNostr()

            if (walletState != null && walletState.proofCount > 0) {
                Log.d(TAG, "Restored wallet: ${walletState.proofCount} proofs, ${walletState.balance.availableSats} sats")
                SyncResult.Success(walletState.proofCount)
            } else if (walletState != null) {
                Log.d(TAG, "Wallet metadata found but no proofs")
                SyncResult.NoData("Wallet found but empty (0 proofs)")
            } else {
                Log.d(TAG, "No wallet found on Nostr")
                SyncResult.NoData("No wallet backup found")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching wallet from Nostr", e)
            SyncResult.Error("Failed to restore wallet: ${e.message}", e)
        }
    }

    override suspend fun publishToNostr(
        signer: NostrSigner,
        relayManager: RelayManager
    ): String? {
        // Wallet proofs are published by Nip60WalletSync when proofs change
        // This is a no-op since wallet sync happens through WalletService
        Log.d(TAG, "Wallet backup is handled by WalletService - skipping")
        return null
    }

    override fun hasLocalData(): Boolean {
        // Check if there are cached proofs
        // This is a simplified check - actual proofs are in cdk-kotlin
        return true  // Always assume wallet might have data
    }

    override fun clearLocalData() {
        // Clear NIP-60 cache
        nip60Sync.clearCache()
        Log.d(TAG, "Cleared NIP-60 wallet cache")
    }

    override suspend fun hasNostrBackup(
        pubKeyHex: String,
        relayManager: RelayManager
    ): Boolean {
        return try {
            nip60Sync.hasExistingWallet()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking for wallet backup", e)
            false
        }
    }
}
