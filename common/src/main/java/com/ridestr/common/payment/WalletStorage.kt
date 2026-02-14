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
        private const val KEY_PENDING_BRIDGE_PAYMENTS = "pending_bridge_payments"
        private const val KEY_RECOVERY_TOKENS = "recovery_tokens"
        private const val KEY_PENDING_BLINDED_OPS = "pending_blinded_operations"
        private const val KEY_KEYSET_COUNTERS = "keyset_counters"
    }

    private val prefs: SharedPreferences
    private var isUsingUnencryptedFallback = false

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
            isUsingUnencryptedFallback = true
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        }
    }

    /**
     * Check if storage is using unencrypted fallback.
     * This happens when EncryptedSharedPreferences fails to initialize
     * (e.g., on emulators, rooted devices, or devices without hardware-backed keystore).
     */
    fun isUsingFallback(): Boolean = isUsingUnencryptedFallback

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
            htlc.preimage?.let { put("preimage", it) }
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
                preimage = json.optString("preimage").takeIf { it.isNotBlank() },
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

    // === Pending Bridge Payments (cross-mint payment tracking) ===

    /**
     * Save a pending bridge payment when starting cross-mint flow.
     * This tracks the entire flow from melt quote to Lightning confirmation.
     */
    fun savePendingBridgePayment(payment: PendingBridgePayment) {
        val payments = getPendingBridgePayments().toMutableList()

        // Replace if same id exists, otherwise add
        val existingIndex = payments.indexOfFirst { it.id == payment.id }
        if (existingIndex >= 0) {
            payments[existingIndex] = payment
        } else {
            payments.add(payment)
        }

        val jsonArray = JSONArray()
        for (p in payments) {
            jsonArray.put(bridgePaymentToJson(p))
        }
        prefs.edit().putString(KEY_PENDING_BRIDGE_PAYMENTS, jsonArray.toString()).apply()
        Log.d(TAG, "Saved pending bridge payment: ${payment.id}, status=${payment.status}")
    }

    /**
     * Get all pending bridge payments.
     */
    fun getPendingBridgePayments(): List<PendingBridgePayment> {
        val json = prefs.getString(KEY_PENDING_BRIDGE_PAYMENTS, null) ?: return emptyList()

        return try {
            val jsonArray = JSONArray(json)
            val payments = mutableListOf<PendingBridgePayment>()
            for (i in 0 until jsonArray.length()) {
                val payment = bridgePaymentFromJson(jsonArray.getJSONObject(i))
                if (payment != null) payments.add(payment)
            }
            payments
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse pending bridge payments", e)
            emptyList()
        }
    }

    /**
     * Get only in-progress bridge payments (not complete or failed).
     */
    fun getInProgressBridgePayments(): List<PendingBridgePayment> {
        return getPendingBridgePayments().filter { it.isInProgress() }
    }

    /**
     * Update the status of a pending bridge payment.
     */
    fun updateBridgePaymentStatus(
        paymentId: String,
        status: BridgePaymentStatus,
        meltQuoteId: String? = null,
        amountSats: Long? = null,
        feeReserveSats: Long? = null,
        proofsUsed: List<String>? = null,
        changeProofsReceived: Boolean? = null,
        lightningPreimage: String? = null,
        errorMessage: String? = null
    ) {
        val payments = getPendingBridgePayments().map { payment ->
            if (payment.id == paymentId) {
                payment.copy(
                    status = status,
                    updatedAt = System.currentTimeMillis(),
                    meltQuoteId = meltQuoteId ?: payment.meltQuoteId,
                    amountSats = amountSats ?: payment.amountSats,
                    feeReserveSats = feeReserveSats ?: payment.feeReserveSats,
                    proofsUsed = proofsUsed ?: payment.proofsUsed,
                    changeProofsReceived = changeProofsReceived ?: payment.changeProofsReceived,
                    lightningPreimage = lightningPreimage ?: payment.lightningPreimage,
                    errorMessage = if (status == BridgePaymentStatus.FAILED) errorMessage else payment.errorMessage
                )
            } else payment
        }

        val jsonArray = JSONArray()
        for (p in payments) {
            jsonArray.put(bridgePaymentToJson(p))
        }
        prefs.edit().putString(KEY_PENDING_BRIDGE_PAYMENTS, jsonArray.toString()).apply()
        Log.d(TAG, "Updated bridge payment status: $paymentId -> $status")
    }

    /**
     * Remove a pending bridge payment (after completion or manual cleanup).
     */
    fun removePendingBridgePayment(paymentId: String) {
        val payments = getPendingBridgePayments().filter { it.id != paymentId }

        val jsonArray = JSONArray()
        for (p in payments) {
            jsonArray.put(bridgePaymentToJson(p))
        }
        prefs.edit().putString(KEY_PENDING_BRIDGE_PAYMENTS, jsonArray.toString()).apply()
        Log.d(TAG, "Removed pending bridge payment: $paymentId")
    }

    /**
     * Clean up old completed/failed bridge payments (older than 7 days).
     */
    fun cleanupOldBridgePayments() {
        val cutoff = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)  // 7 days ago
        val payments = getPendingBridgePayments().filter { payment ->
            // Keep if still in progress OR created recently
            payment.isInProgress() || payment.createdAt > cutoff
        }

        val jsonArray = JSONArray()
        for (p in payments) {
            jsonArray.put(bridgePaymentToJson(p))
        }
        prefs.edit().putString(KEY_PENDING_BRIDGE_PAYMENTS, jsonArray.toString()).apply()
    }

    private fun bridgePaymentToJson(payment: PendingBridgePayment): JSONObject {
        return JSONObject().apply {
            put("id", payment.id)
            put("ride_id", payment.rideId)
            put("driver_invoice", payment.driverInvoice)
            put("amount_sats", payment.amountSats)
            put("fee_reserve_sats", payment.feeReserveSats)
            payment.meltQuoteId?.let { put("melt_quote_id", it) }
            put("status", payment.status.name)
            put("created_at", payment.createdAt)
            put("updated_at", payment.updatedAt)
            if (payment.proofsUsed.isNotEmpty()) {
                put("proofs_used", JSONArray(payment.proofsUsed))
            }
            put("change_proofs_received", payment.changeProofsReceived)
            payment.lightningPreimage?.let { put("lightning_preimage", it) }
            payment.errorMessage?.let { put("error_message", it) }
        }
    }

    private fun bridgePaymentFromJson(json: JSONObject): PendingBridgePayment? {
        return try {
            val proofsUsed = mutableListOf<String>()
            val proofsArray = json.optJSONArray("proofs_used")
            if (proofsArray != null) {
                for (i in 0 until proofsArray.length()) {
                    proofsUsed.add(proofsArray.getString(i))
                }
            }

            PendingBridgePayment(
                id = json.getString("id"),
                rideId = json.getString("ride_id"),
                driverInvoice = json.getString("driver_invoice"),
                amountSats = json.getLong("amount_sats"),
                feeReserveSats = json.optLong("fee_reserve_sats", 0),
                meltQuoteId = json.optString("melt_quote_id").takeIf { it.isNotBlank() },
                status = try {
                    BridgePaymentStatus.valueOf(json.optString("status", "STARTED"))
                } catch (e: Exception) {
                    BridgePaymentStatus.STARTED
                },
                createdAt = json.optLong("created_at", System.currentTimeMillis()),
                updatedAt = json.optLong("updated_at", System.currentTimeMillis()),
                proofsUsed = proofsUsed,
                changeProofsReceived = json.optBoolean("change_proofs_received", false),
                lightningPreimage = json.optString("lightning_preimage").takeIf { it.isNotBlank() },
                errorMessage = json.optString("error_message").takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse pending bridge payment", e)
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
            .remove(KEY_PENDING_BRIDGE_PAYMENTS)
            .remove(KEY_RECOVERY_TOKENS)
            .remove(KEY_PENDING_BLINDED_OPS)
            .remove(KEY_KEYSET_COUNTERS)
            .apply()
        Log.d(TAG, "Cleared all wallet storage")
    }

    // === Pending Blinded Operations ===
    // Critical for fund recovery when operations fail midway

    /**
     * Save a pending blinded operation BEFORE sending the request.
     * This stores the blinding factors needed to recover outputs if the operation
     * succeeds on the mint but we don't receive the response.
     */
    fun savePendingBlindedOp(op: PendingBlindedOperation) {
        val ops = getPendingBlindedOps().toMutableList()

        // Replace if same id exists, otherwise add
        val existingIndex = ops.indexOfFirst { it.id == op.id }
        if (existingIndex >= 0) {
            ops[existingIndex] = op
        } else {
            ops.add(op)
        }

        val jsonArray = JSONArray()
        for (o in ops) {
            jsonArray.put(blindedOpToJson(o))
        }
        prefs.edit().putString(KEY_PENDING_BLINDED_OPS, jsonArray.toString()).apply()
        Log.d(TAG, "Saved pending blinded op: ${op.id}, type=${op.operationType}, ${op.amountSats} sats")
    }

    /**
     * Get all pending blinded operations.
     */
    fun getPendingBlindedOps(): List<PendingBlindedOperation> {
        val json = prefs.getString(KEY_PENDING_BLINDED_OPS, null) ?: return emptyList()

        return try {
            val jsonArray = JSONArray(json)
            val ops = mutableListOf<PendingBlindedOperation>()
            for (i in 0 until jsonArray.length()) {
                val op = blindedOpFromJson(jsonArray.getJSONObject(i))
                if (op != null) ops.add(op)
            }
            ops
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse pending blinded ops", e)
            emptyList()
        }
    }

    /**
     * Get pending operations that need recovery (not completed/recovered, not expired).
     */
    fun getRecoverableBlindedOps(): List<PendingBlindedOperation> {
        val now = System.currentTimeMillis()
        return getPendingBlindedOps().filter { op ->
            op.status != PendingOperationStatus.COMPLETED &&
            op.status != PendingOperationStatus.RECOVERED &&
            op.expiresAt > now
        }
    }

    /**
     * Update the status of a pending blinded operation.
     */
    fun updateBlindedOpStatus(opId: String, status: PendingOperationStatus) {
        val ops = getPendingBlindedOps().map { op ->
            if (op.id == opId) op.copy(status = status)
            else op
        }

        val jsonArray = JSONArray()
        for (o in ops) {
            jsonArray.put(blindedOpToJson(o))
        }
        prefs.edit().putString(KEY_PENDING_BLINDED_OPS, jsonArray.toString()).apply()
        Log.d(TAG, "Updated blinded op status: $opId -> $status")
    }

    /**
     * Remove a pending blinded operation (after confirmed completion or recovery).
     */
    fun removePendingBlindedOp(opId: String) {
        val ops = getPendingBlindedOps().filter { it.id != opId }

        val jsonArray = JSONArray()
        for (o in ops) {
            jsonArray.put(blindedOpToJson(o))
        }
        prefs.edit().putString(KEY_PENDING_BLINDED_OPS, jsonArray.toString()).apply()
        Log.d(TAG, "Removed pending blinded op: $opId")
    }

    /**
     * Clean up old expired operations (older than 24 hours past expiry).
     */
    fun cleanupExpiredBlindedOps() {
        val cutoff = System.currentTimeMillis() - (24 * 60 * 60 * 1000L)  // 24 hours ago
        val ops = getPendingBlindedOps().filter { op ->
            // Keep if not expired OR completed/recovered recently
            op.expiresAt > cutoff ||
            (op.status == PendingOperationStatus.COMPLETED || op.status == PendingOperationStatus.RECOVERED)
        }

        val jsonArray = JSONArray()
        for (o in ops) {
            jsonArray.put(blindedOpToJson(o))
        }
        prefs.edit().putString(KEY_PENDING_BLINDED_OPS, jsonArray.toString()).apply()
    }

    private fun blindedOpToJson(op: PendingBlindedOperation): JSONObject {
        return JSONObject().apply {
            put("id", op.id)
            put("operation_type", op.operationType.name)
            put("mint_url", op.mintUrl)
            op.quoteId?.let { put("quote_id", it) }
            put("input_secrets", JSONArray(op.inputSecrets))
            put("output_premints", JSONArray().also { arr ->
                op.outputPremints.forEach { pm ->
                    arr.put(JSONObject().apply {
                        put("amount", pm.amount)
                        put("secret", pm.secret)
                        put("blinding_factor", pm.blindingFactor)
                        put("Y", pm.Y)
                        put("B_", pm.B_)
                    })
                }
            })
            put("amount_sats", op.amountSats)
            put("created_at", op.createdAt)
            put("expires_at", op.expiresAt)
            put("status", op.status.name)
        }
    }

    private fun blindedOpFromJson(json: JSONObject): PendingBlindedOperation? {
        return try {
            val inputSecrets = mutableListOf<String>()
            val inputArray = json.getJSONArray("input_secrets")
            for (i in 0 until inputArray.length()) {
                inputSecrets.add(inputArray.getString(i))
            }

            val outputPremints = mutableListOf<SerializedPreMint>()
            val outputArray = json.getJSONArray("output_premints")
            for (i in 0 until outputArray.length()) {
                val pmJson = outputArray.getJSONObject(i)
                outputPremints.add(SerializedPreMint(
                    amount = pmJson.getLong("amount"),
                    secret = pmJson.getString("secret"),
                    blindingFactor = pmJson.getString("blinding_factor"),
                    Y = pmJson.getString("Y"),
                    B_ = pmJson.getString("B_")
                ))
            }

            PendingBlindedOperation(
                id = json.getString("id"),
                operationType = BlindedOperationType.valueOf(json.getString("operation_type")),
                mintUrl = json.getString("mint_url"),
                quoteId = json.optString("quote_id").takeIf { it.isNotBlank() },
                inputSecrets = inputSecrets,
                outputPremints = outputPremints,
                amountSats = json.getLong("amount_sats"),
                createdAt = json.getLong("created_at"),
                expiresAt = json.getLong("expires_at"),
                status = try {
                    PendingOperationStatus.valueOf(json.optString("status", "STARTED"))
                } catch (e: Exception) {
                    PendingOperationStatus.STARTED
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse pending blinded op", e)
            null
        }
    }

    // === Recovery Tokens ===
    // Fallback storage for proofs when NIP-60 publish fails

    /**
     * Save a recovery token when NIP-60 publish fails.
     * These can be manually recovered later.
     */
    fun saveRecoveryToken(token: RecoveryToken) {
        val tokens = getRecoveryTokens().toMutableList()
        tokens.add(token)
        val jsonArray = JSONArray()
        tokens.forEach { t -> jsonArray.put(recoveryTokenToJson(t)) }
        prefs.edit().putString(KEY_RECOVERY_TOKENS, jsonArray.toString()).apply()
        Log.d(TAG, "Saved recovery token: ${token.amount} sats (${token.reason})")
    }

    /**
     * Get all saved recovery tokens.
     */
    fun getRecoveryTokens(): List<RecoveryToken> {
        val json = prefs.getString(KEY_RECOVERY_TOKENS, null) ?: return emptyList()
        return try {
            val jsonArray = JSONArray(json)
            (0 until jsonArray.length()).mapNotNull { i ->
                recoveryTokenFromJson(jsonArray.getJSONObject(i))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse recovery tokens", e)
            emptyList()
        }
    }

    /**
     * Remove a recovery token after successful import.
     */
    fun removeRecoveryToken(id: String) {
        val tokens = getRecoveryTokens().filter { it.id != id }
        val jsonArray = JSONArray()
        tokens.forEach { t -> jsonArray.put(recoveryTokenToJson(t)) }
        prefs.edit().putString(KEY_RECOVERY_TOKENS, jsonArray.toString()).apply()
        Log.d(TAG, "Removed recovery token: $id")
    }

    /**
     * Get count of pending recovery tokens.
     */
    fun getRecoveryTokenCount(): Int = getRecoveryTokens().size

    /**
     * Get total sats in recovery tokens.
     */
    fun getRecoveryTokenTotal(): Long = getRecoveryTokens().sumOf { it.amount }

    private fun recoveryTokenToJson(token: RecoveryToken): JSONObject {
        return JSONObject().apply {
            put("id", token.id)
            put("token", token.token)
            put("amount", token.amount)
            put("mint_url", token.mintUrl)
            put("created_at", token.createdAt)
            put("reason", token.reason)
        }
    }

    private fun recoveryTokenFromJson(json: JSONObject): RecoveryToken? {
        return try {
            RecoveryToken(
                id = json.getString("id"),
                token = json.getString("token"),
                amount = json.getLong("amount"),
                mintUrl = json.getString("mint_url"),
                createdAt = json.getLong("created_at"),
                reason = json.optString("reason", "unknown")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse recovery token", e)
            null
        }
    }

    // === JSON Serialization ===

    private fun transactionToJson(tx: PaymentTransaction): JSONObject {
        return JSONObject().apply {
            put("id", tx.id)
            put("type", tx.type.name)
            put("amount_sats", tx.amountSats)
            put("fee_sats", tx.feeSats)
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
                feeSats = json.optLong("fee_sats", 0),
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

    // === NUT-13 Keyset Counters ===

    /**
     * Get the current counter for a keyset.
     * Returns 0 if no counter is stored (first use of this keyset).
     *
     * @param keysetId The keyset ID
     * @return Current counter value
     */
    fun getCounter(keysetId: String): Long {
        val counters = loadCounters()
        return counters[keysetId] ?: 0L
    }

    /**
     * Set the counter for a keyset.
     *
     * @param keysetId The keyset ID
     * @param counter The counter value
     */
    fun setCounter(keysetId: String, counter: Long) {
        val counters = loadCounters().toMutableMap()
        counters[keysetId] = counter
        saveCounters(counters)
        Log.d(TAG, "Set counter for keyset $keysetId to $counter")
    }

    /**
     * Increment the counter for a keyset and return the NEW value.
     * Thread-safe via synchronized block.
     *
     * @param keysetId The keyset ID
     * @return The NEW counter value (after increment)
     */
    @Synchronized
    fun incrementCounter(keysetId: String): Long {
        val counters = loadCounters().toMutableMap()
        val currentValue = counters[keysetId] ?: 0L
        val newValue = currentValue + 1
        counters[keysetId] = newValue
        saveCounters(counters)
        Log.d(TAG, "Incremented counter for keyset $keysetId: $currentValue -> $newValue")
        return newValue
    }

    /**
     * Get all keyset counters.
     * Used for NIP-60 backup.
     *
     * @return Map of keysetId to counter value
     */
    fun getAllCounters(): Map<String, Long> {
        return loadCounters()
    }

    /**
     * Restore counters from NIP-60 backup.
     * Only updates counters that are higher than current local values
     * to prevent counter reuse.
     *
     * @param counters Map of keysetId to counter value from backup
     */
    fun restoreCounters(counters: Map<String, Long>) {
        val current = loadCounters().toMutableMap()
        var updated = false

        for ((keysetId, backupCounter) in counters) {
            val localCounter = current[keysetId] ?: 0L
            if (backupCounter > localCounter) {
                current[keysetId] = backupCounter
                updated = true
                Log.d(TAG, "Restored counter for keyset $keysetId: $localCounter -> $backupCounter")
            }
        }

        if (updated) {
            saveCounters(current)
        }
    }

    /**
     * Clear all counters. Use with caution - only for wallet reset.
     */
    fun clearCounters() {
        prefs.edit().remove(KEY_KEYSET_COUNTERS).apply()
        Log.d(TAG, "Cleared all keyset counters")
    }

    private fun loadCounters(): Map<String, Long> {
        val json = prefs.getString(KEY_KEYSET_COUNTERS, null) ?: return emptyMap()
        return try {
            val jsonObj = JSONObject(json)
            val result = mutableMapOf<String, Long>()
            jsonObj.keys().forEach { key ->
                result[key] = jsonObj.getLong(key)
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load keyset counters", e)
            emptyMap()
        }
    }

    private fun saveCounters(counters: Map<String, Long>) {
        val json = JSONObject().apply {
            counters.forEach { (keysetId, counter) ->
                put(keysetId, counter)
            }
        }
        prefs.edit().putString(KEY_KEYSET_COUNTERS, json.toString()).apply()
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

/**
 * Recovery token for proofs that couldn't be published to NIP-60.
 * Stored locally for manual recovery when NIP-60 or network issues occur.
 */
data class RecoveryToken(
    val id: String,              // UUID for this recovery token
    val token: String,           // cashuA... token string (can be imported to any wallet)
    val amount: Long,            // Total sats in token
    val mintUrl: String,         // Mint URL these proofs are from
    val createdAt: Long,         // When this was saved
    val reason: String           // Why recovery was needed (e.g., "nip60_publish_failed")
)

/**
 * Pending blinded operation - stores blinding factors for recovery.
 *
 * CRITICAL: This is saved BEFORE sending any operation that creates blinded outputs.
 * If the operation succeeds on the mint but we lose the response (crash, network error),
 * we can use these blinding factors to recover the outputs.
 *
 * Recovery flow:
 * 1. Check input proof states (NUT-07)
 * 2. If inputs are SPENT: operation succeeded, try to recover outputs
 * 3. If inputs are UNSPENT: operation failed, inputs are still safe
 * 4. If inputs are PENDING: wait and retry
 */
data class PendingBlindedOperation(
    val id: String,                              // UUID for this operation
    val operationType: BlindedOperationType,     // What kind of operation
    val mintUrl: String,                         // Mint URL
    val quoteId: String? = null,                 // For melt/mint operations (to poll status)
    val inputSecrets: List<String>,              // Secrets of input proofs (to check state)
    val outputPremints: List<SerializedPreMint>, // Our blinding factors (to recover outputs)
    val amountSats: Long,                        // Total amount involved
    val createdAt: Long,                         // When operation started
    val expiresAt: Long,                         // When to stop recovery attempts
    val status: PendingOperationStatus           // Current status
)

/**
 * Serialized premint data for storage.
 * Contains all data needed to unblind a mint signature into a valid proof.
 */
data class SerializedPreMint(
    val amount: Long,           // Requested amount
    val secret: String,         // The secret (will be proof's secret)
    val blindingFactor: String, // r value - CRITICAL for unblinding
    val Y: String,              // hash_to_curve(secret) - for verification
    val B_: String              // Blinded message sent to mint
)

/**
 * Types of operations that create blinded outputs.
 */
enum class BlindedOperationType {
    MELT,           // Withdrawal with change outputs
    SWAP,           // Token swap (split/merge)
    MINT,           // Deposit (mint from quote)
    CLAIM_HTLC,     // Driver claiming HTLC payment
    REFUND_HTLC,    // Rider refunding expired HTLC
    LOCK_HTLC       // Rider locking funds for HTLC
}

/**
 * Status of a pending blinded operation.
 */
enum class PendingOperationStatus {
    STARTED,        // Operation saved, request being sent
    PENDING,        // Mint returned pending status (e.g., Lightning payment in progress)
    COMPLETED,      // Successfully completed, safe to delete
    FAILED,         // Confirmed failed, inputs verified UNSPENT
    RECOVERED       // Outputs recovered from stored premints
}
