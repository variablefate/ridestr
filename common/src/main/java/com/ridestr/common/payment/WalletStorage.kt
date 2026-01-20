package com.ridestr.common.payment

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

/**
 * Local storage for wallet data.
 * Uses EncryptedSharedPreferences for security.
 *
 * Stores:
 * - Cached balance (for offline display)
 * - Selected mint URL
 * - Transaction history
 * - Pending proofs (for NIP-60 sync queue)
 */
class WalletStorage(private val context: Context) {

    companion object {
        private const val TAG = "WalletStorage"
        private const val PREFS_FILE = "ridestr_wallet_storage"

        // Keys
        private const val KEY_MINT_URL = "mint_url"
        private const val KEY_BALANCE_AVAILABLE = "balance_available"
        private const val KEY_BALANCE_PENDING = "balance_pending"
        private const val KEY_BALANCE_UPDATED = "balance_updated"
        private const val KEY_TRANSACTIONS = "transactions"
        private const val KEY_WALLET_SETUP_COMPLETED = "wallet_setup_completed"
        private const val KEY_PENDING_DEPOSITS = "pending_deposits"
        private const val KEY_PENDING_HTLCS = "pending_htlcs"
        private const val KEY_UNVERIFIED_PROOFS = "unverified_proofs"
    }

    private val prefs: SharedPreferences

    init {
        prefs = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create encrypted prefs, falling back to regular prefs", e)
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    // === Mint URL ===

    fun saveMintUrl(url: String) {
        prefs.edit().putString(KEY_MINT_URL, url).apply()
    }

    fun getMintUrl(): String? {
        return prefs.getString(KEY_MINT_URL, null)
    }

    fun clearMintUrl() {
        prefs.edit().remove(KEY_MINT_URL).apply()
    }

    // === Balance Cache ===

    fun cacheBalance(balance: WalletBalance) {
        prefs.edit()
            .putLong(KEY_BALANCE_AVAILABLE, balance.availableSats)
            .putLong(KEY_BALANCE_PENDING, balance.pendingSats)
            .putLong(KEY_BALANCE_UPDATED, balance.lastUpdated)
            .apply()
    }

    fun getCachedBalance(): WalletBalance {
        return WalletBalance(
            availableSats = prefs.getLong(KEY_BALANCE_AVAILABLE, 0),
            pendingSats = prefs.getLong(KEY_BALANCE_PENDING, 0),
            lastUpdated = prefs.getLong(KEY_BALANCE_UPDATED, 0)
        )
    }

    // === Transaction History ===

    fun saveTransaction(tx: PaymentTransaction) {
        val transactions = getTransactions().toMutableList()
        transactions.add(0, tx)  // Add to front (newest first)

        // Keep last 100 transactions
        val limited = transactions.take(100)

        val jsonArray = JSONArray()
        for (t in limited) {
            jsonArray.put(transactionToJson(t))
        }

        prefs.edit().putString(KEY_TRANSACTIONS, jsonArray.toString()).apply()
    }

    fun getTransactions(): List<PaymentTransaction> {
        val json = prefs.getString(KEY_TRANSACTIONS, null) ?: return emptyList()

        return try {
            val jsonArray = JSONArray(json)
            val transactions = mutableListOf<PaymentTransaction>()
            for (i in 0 until jsonArray.length()) {
                val tx = transactionFromJson(jsonArray.getJSONObject(i))
                if (tx != null) transactions.add(tx)
            }
            transactions
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse transactions", e)
            emptyList()
        }
    }

    fun clearTransactions() {
        prefs.edit().remove(KEY_TRANSACTIONS).apply()
    }

    // === Wallet Setup ===

    fun setWalletSetupCompleted(completed: Boolean) {
        prefs.edit().putBoolean(KEY_WALLET_SETUP_COMPLETED, completed).apply()
    }

    fun isWalletSetupCompleted(): Boolean {
        return prefs.getBoolean(KEY_WALLET_SETUP_COMPLETED, false)
    }

    // === Pending Deposits (prevents fund loss if app crashes during minting) ===

    /**
     * Save a pending deposit before showing the Lightning invoice.
     * This allows recovery if the app crashes after payment but before minting.
     */
    fun savePendingDeposit(deposit: PendingDeposit) {
        val deposits = getPendingDeposits().toMutableList()

        // Replace if same quoteId exists, otherwise add
        val existingIndex = deposits.indexOfFirst { it.quoteId == deposit.quoteId }
        if (existingIndex >= 0) {
            deposits[existingIndex] = deposit
        } else {
            deposits.add(deposit)
        }

        val jsonArray = JSONArray()
        for (d in deposits) {
            jsonArray.put(pendingDepositToJson(d))
        }
        prefs.edit().putString(KEY_PENDING_DEPOSITS, jsonArray.toString()).apply()
        Log.d(TAG, "Saved pending deposit: ${deposit.quoteId}, ${deposit.amount} sats")
    }

    /**
     * Get all pending deposits (paid but not yet minted).
     */
    fun getPendingDeposits(): List<PendingDeposit> {
        val json = prefs.getString(KEY_PENDING_DEPOSITS, null) ?: return emptyList()

        return try {
            val jsonArray = JSONArray(json)
            val deposits = mutableListOf<PendingDeposit>()
            for (i in 0 until jsonArray.length()) {
                val deposit = pendingDepositFromJson(jsonArray.getJSONObject(i))
                if (deposit != null) deposits.add(deposit)
            }
            deposits
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse pending deposits", e)
            emptyList()
        }
    }

    /**
     * Mark a pending deposit as minted (proofs received).
     */
    fun markDepositMinted(quoteId: String) {
        val deposits = getPendingDeposits().map { deposit ->
            if (deposit.quoteId == quoteId) deposit.copy(minted = true)
            else deposit
        }

        val jsonArray = JSONArray()
        for (d in deposits) {
            jsonArray.put(pendingDepositToJson(d))
        }
        prefs.edit().putString(KEY_PENDING_DEPOSITS, jsonArray.toString()).apply()
        Log.d(TAG, "Marked deposit as minted: $quoteId")
    }

    /**
     * Remove a pending deposit (after successful mint or expiry).
     */
    fun removePendingDeposit(quoteId: String) {
        val deposits = getPendingDeposits().filter { it.quoteId != quoteId }

        val jsonArray = JSONArray()
        for (d in deposits) {
            jsonArray.put(pendingDepositToJson(d))
        }
        prefs.edit().putString(KEY_PENDING_DEPOSITS, jsonArray.toString()).apply()
        Log.d(TAG, "Removed pending deposit: $quoteId")
    }

    /**
     * Clean up expired pending deposits (quote expired, can't be claimed).
     */
    fun cleanupExpiredDeposits() {
        val nowSeconds = System.currentTimeMillis() / 1000
        val deposits = getPendingDeposits().filter { deposit ->
            // Keep if:
            // - expiry is 0 (unknown/no expiry), OR
            // - not expired yet (with 5 minute grace period)
            deposit.expiry == 0L || deposit.expiry > nowSeconds - 300
        }

        val jsonArray = JSONArray()
        for (d in deposits) {
            jsonArray.put(pendingDepositToJson(d))
        }
        prefs.edit().putString(KEY_PENDING_DEPOSITS, jsonArray.toString()).apply()
    }

    private fun pendingDepositToJson(deposit: PendingDeposit): JSONObject {
        return JSONObject().apply {
            put("quote_id", deposit.quoteId)
            put("amount", deposit.amount)
            put("invoice", deposit.invoice)
            put("created_at", deposit.createdAt)
            put("expiry", deposit.expiry)
            put("minted", deposit.minted)
        }
    }

    private fun pendingDepositFromJson(json: JSONObject): PendingDeposit? {
        return try {
            PendingDeposit(
                quoteId = json.getString("quote_id"),
                amount = json.getLong("amount"),
                invoice = json.getString("invoice"),
                createdAt = json.getLong("created_at"),
                // Use optLong for robustness - 0 means no expiry
                expiry = json.optLong("expiry", 0L),
                minted = json.optBoolean("minted", false)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse pending deposit", e)
            null
        }
    }

    // === Pending HTLCs (for refund if driver never claims) ===

    /**
     * Save a pending HTLC after locking funds for a ride.
     * This allows refund if driver never claims and locktime expires.
     */
    fun savePendingHtlc(htlc: PendingHtlc) {
        val htlcs = getPendingHtlcs().toMutableList()

        // Replace if same escrowId exists, otherwise add
        val existingIndex = htlcs.indexOfFirst { it.escrowId == htlc.escrowId }
        if (existingIndex >= 0) {
            htlcs[existingIndex] = htlc
        } else {
            htlcs.add(htlc)
        }

        val jsonArray = JSONArray()
        for (h in htlcs) {
            jsonArray.put(pendingHtlcToJson(h))
        }
        prefs.edit().putString(KEY_PENDING_HTLCS, jsonArray.toString()).apply()
        Log.d(TAG, "Saved pending HTLC: ${htlc.escrowId}, ${htlc.amountSats} sats, locktime=${htlc.locktime}")
    }

    /**
     * Get all pending HTLCs (locked but not yet claimed or refunded).
     */
    fun getPendingHtlcs(): List<PendingHtlc> {
        val json = prefs.getString(KEY_PENDING_HTLCS, null) ?: return emptyList()

        return try {
            val jsonArray = JSONArray(json)
            val htlcs = mutableListOf<PendingHtlc>()
            for (i in 0 until jsonArray.length()) {
                val htlc = pendingHtlcFromJson(jsonArray.getJSONObject(i))
                if (htlc != null) htlcs.add(htlc)
            }
            htlcs
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse pending HTLCs", e)
            emptyList()
        }
    }

    /**
     * Get only refundable HTLCs (locktime expired and still active).
     */
    fun getRefundableHtlcs(): List<PendingHtlc> {
        return getPendingHtlcs().filter { it.isRefundable() && it.isActive() }
    }

    /**
     * Update the status of a pending HTLC.
     */
    fun updateHtlcStatus(escrowId: String, status: PendingHtlcStatus) {
        val htlcs = getPendingHtlcs().map { htlc ->
            if (htlc.escrowId == escrowId) htlc.copy(status = status)
            else htlc
        }

        val jsonArray = JSONArray()
        for (h in htlcs) {
            jsonArray.put(pendingHtlcToJson(h))
        }
        prefs.edit().putString(KEY_PENDING_HTLCS, jsonArray.toString()).apply()
        Log.d(TAG, "Updated HTLC status: $escrowId -> $status")
    }

    /**
     * Remove a pending HTLC (after successful claim or refund).
     */
    fun removePendingHtlc(escrowId: String) {
        val htlcs = getPendingHtlcs().filter { it.escrowId != escrowId }

        val jsonArray = JSONArray()
        for (h in htlcs) {
            jsonArray.put(pendingHtlcToJson(h))
        }
        prefs.edit().putString(KEY_PENDING_HTLCS, jsonArray.toString()).apply()
        Log.d(TAG, "Removed pending HTLC: $escrowId")
    }

    /**
     * Clean up old resolved HTLCs (claimed or refunded more than 7 days ago).
     */
    fun cleanupResolvedHtlcs() {
        val cutoff = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)  // 7 days ago
        val htlcs = getPendingHtlcs().filter { htlc ->
            // Keep if still active OR created recently (even if resolved)
            htlc.isActive() || htlc.createdAt > cutoff
        }

        val jsonArray = JSONArray()
        for (h in htlcs) {
            jsonArray.put(pendingHtlcToJson(h))
        }
        prefs.edit().putString(KEY_PENDING_HTLCS, jsonArray.toString()).apply()
    }

    private fun pendingHtlcToJson(htlc: PendingHtlc): JSONObject {
        return JSONObject().apply {
            put("escrow_id", htlc.escrowId)
            put("htlc_token", htlc.htlcToken)
            put("amount_sats", htlc.amountSats)
            put("locktime", htlc.locktime)
            put("rider_pubkey", htlc.riderPubKey)
            put("payment_hash", htlc.paymentHash)
            htlc.rideId?.let { put("ride_id", it) }
            put("created_at", htlc.createdAt)
            put("status", htlc.status.name)
        }
    }

    private fun pendingHtlcFromJson(json: JSONObject): PendingHtlc? {
        return try {
            PendingHtlc(
                escrowId = json.getString("escrow_id"),
                htlcToken = json.getString("htlc_token"),
                amountSats = json.getLong("amount_sats"),
                locktime = json.getLong("locktime"),
                riderPubKey = json.getString("rider_pubkey"),
                paymentHash = json.getString("payment_hash"),
                rideId = json.optString("ride_id").takeIf { it.isNotBlank() },
                createdAt = json.optLong("created_at", System.currentTimeMillis()),
                status = try {
                    PendingHtlcStatus.valueOf(json.optString("status", "LOCKED"))
                } catch (e: Exception) {
                    PendingHtlcStatus.LOCKED
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse pending HTLC", e)
            null
        }
    }

    // === Unverified Proof Cache (for when mint is offline) ===

    /**
     * Save unverified proofs when mint is offline.
     * These will be verified on next syncWallet() call when mint is reachable.
     */
    fun saveUnverifiedProofs(proofs: List<UnverifiedProof>) {
        val jsonArray = JSONArray()
        for (proof in proofs) {
            val obj = JSONObject()
            obj.put("amount", proof.amount)
            obj.put("secret", proof.secret)
            obj.put("C", proof.c)
            obj.put("id", proof.id)
            obj.put("mint_url", proof.mintUrl)
            obj.put("cached_at", proof.cachedAt)
            jsonArray.put(obj)
        }
        prefs.edit().putString(KEY_UNVERIFIED_PROOFS, jsonArray.toString()).apply()
        Log.d(TAG, "Saved ${proofs.size} unverified proofs to cache")
    }

    /**
     * Get cached unverified proofs.
     */
    fun getUnverifiedProofs(): List<UnverifiedProof> {
        val json = prefs.getString(KEY_UNVERIFIED_PROOFS, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(json)
            (0 until jsonArray.length()).mapNotNull { i ->
                try {
                    val obj = jsonArray.getJSONObject(i)
                    UnverifiedProof(
                        amount = obj.getLong("amount"),
                        secret = obj.getString("secret"),
                        c = obj.getString("C"),
                        id = obj.getString("id"),
                        mintUrl = obj.getString("mint_url"),
                        cachedAt = obj.optLong("cached_at", System.currentTimeMillis())
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse unverified proof at $i", e)
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse unverified proofs", e)
            emptyList()
        }
    }

    /**
     * Clear unverified proofs cache (after successful verification).
     */
    fun clearUnverifiedProofs() {
        prefs.edit().remove(KEY_UNVERIFIED_PROOFS).apply()
        Log.d(TAG, "Cleared unverified proofs cache")
    }

    /**
     * Get the count of cached unverified proofs.
     */
    fun getUnverifiedProofCount(): Int {
        return getUnverifiedProofs().size
    }

    /**
     * Get the total amount of unverified proofs in sats.
     */
    fun getUnverifiedProofTotal(): Long {
        return getUnverifiedProofs().sumOf { it.amount }
    }

    // === Clear All ===

    fun clearAll() {
        prefs.edit()
            .remove(KEY_MINT_URL)
            .remove(KEY_BALANCE_AVAILABLE)
            .remove(KEY_BALANCE_PENDING)
            .remove(KEY_BALANCE_UPDATED)
            .remove(KEY_TRANSACTIONS)
            .remove(KEY_WALLET_SETUP_COMPLETED)
            .remove(KEY_PENDING_DEPOSITS)
            .remove(KEY_PENDING_HTLCS)
            .remove(KEY_UNVERIFIED_PROOFS)
            .apply()
        Log.d(TAG, "Cleared all wallet storage")
    }

    // === JSON Serialization ===

    private fun transactionToJson(tx: PaymentTransaction): JSONObject {
        return JSONObject().apply {
            put("id", tx.id)
            put("type", tx.type.name)
            put("amount_sats", tx.amountSats)
            put("timestamp", tx.timestamp)
            tx.rideId?.let { put("ride_id", it) }
            tx.counterpartyPubKey?.let { put("counterparty", it) }
            put("status", tx.status)
        }
    }

    private fun transactionFromJson(json: JSONObject): PaymentTransaction? {
        return try {
            PaymentTransaction(
                id = json.getString("id"),
                type = TransactionType.valueOf(json.getString("type")),
                amountSats = json.getLong("amount_sats"),
                timestamp = json.getLong("timestamp"),
                rideId = json.optString("ride_id").takeIf { it.isNotBlank() },
                counterpartyPubKey = json.optString("counterparty").takeIf { it.isNotBlank() },
                status = json.getString("status")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse transaction", e)
            null
        }
    }
}

/**
 * Proof that couldn't be verified because mint was offline.
 * Cached locally for retry on next syncWallet() call.
 */
data class UnverifiedProof(
    val amount: Long,
    val secret: String,
    val c: String,       // Unblinded signature (C value)
    val id: String,      // Keyset ID
    val mintUrl: String,
    val cachedAt: Long = System.currentTimeMillis()
)
