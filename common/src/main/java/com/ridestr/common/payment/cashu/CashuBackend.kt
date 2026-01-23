package com.ridestr.common.payment.cashu

import android.content.Context
import android.util.Log
import com.ridestr.common.payment.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
// cdk-kotlin imports with aliases to avoid conflicts with local types
import org.cashudevkit.Wallet as CdkWallet
import org.cashudevkit.WalletDatabase as CdkWalletDatabase
import org.cashudevkit.WalletSqliteDatabase as CdkWalletSqliteDatabase
import org.cashudevkit.WalletConfig as CdkWalletConfig
import org.cashudevkit.CurrencyUnit as CdkCurrencyUnit
import org.cashudevkit.Amount as CdkAmount
import org.cashudevkit.SplitTarget as CdkSplitTarget
import org.cashudevkit.Token as CdkToken
import org.cashudevkit.ReceiveOptions as CdkReceiveOptions
import org.cashudevkit.SendOptions as CdkSendOptions
import org.cashudevkit.MintQuote as CdkMintQuote
import org.cashudevkit.MeltQuote as CdkMeltQuote
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Backend implementation for Cashu mint operations.
 *
 * Handles:
 * - Mint capability verification (NUT-06)
 * - Melt quotes (NUT-05)
 * - Proof state checks (NUT-07)
 * - HTLC proof creation and claiming (NUT-14)
 *
 * Reference implementations:
 * - cdk-cli: https://github.com/cashubtc/cdk
 * - nutshell: https://github.com/cashubtc/nutshell
 */
class CashuBackend(
    private val context: Context,
    private val walletKeyManager: WalletKeyManager,
    private val walletStorage: WalletStorage
) {
    companion object {
        private const val TAG = "CashuBackend"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // Required NUTs for escrow functionality
        private val REQUIRED_NUTS = setOf(14, 5, 7)  // HTLC, Melt, Proof state
    }

    // cdk-kotlin wallet instance for real Cashu operations
    private var cdkWallet: CdkWallet? = null
    private var cdkDatabase: CdkWalletSqliteDatabase? = null

    // NUT-13: BIP-39 seed for deterministic secret derivation
    private var walletSeed: ByteArray? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private var currentMintUrl: String? = null
    private var mintCapabilities: MintCapabilities? = null

    // NUT-17: WebSocket for real-time state updates
    private var webSocket: CashuWebSocket? = null

    /**
     * Verify mint capabilities via NUT-06 info endpoint.
     * Checks for NUT-14 (HTLC), NUT-05 (Melt), NUT-07 (Proof state check).
     *
     * @param mintUrl The mint URL (e.g., "https://mint.minibits.cash/Bitcoin")
     * @return MintCapabilities if successful, null on failure
     */
    suspend fun verifyMintCapabilities(mintUrl: String): MintCapabilities? = withContext(Dispatchers.IO) {
        try {
            val normalizedUrl = mintUrl.trimEnd('/')
            val request = Request.Builder()
                .url("$normalizedUrl/v1/info")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Mint info request failed: ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)

                // Parse supported NUTs from the "nuts" object
                val supportedNuts = mutableSetOf<Int>()
                var supportsWebSocket = false
                val webSocketCommands = mutableSetOf<String>()

                if (json.has("nuts")) {
                    val nutsObj = json.getJSONObject("nuts")
                    val keys = nutsObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        try {
                            supportedNuts.add(key.toInt())
                        } catch (e: NumberFormatException) {
                            // Skip non-numeric keys
                        }
                    }

                    // Parse NUT-17 WebSocket capabilities
                    // Format: { "17": { "supported": [{ "method": "bolt11", "unit": "sat", "commands": [...] }] } }
                    if (nutsObj.has("17")) {
                        try {
                            val nut17 = nutsObj.getJSONObject("17")
                            if (nut17.has("supported")) {
                                val supported = nut17.getJSONArray("supported")
                                for (i in 0 until supported.length()) {
                                    val entry = supported.getJSONObject(i)
                                    // We only care about bolt11/sat for now
                                    if (entry.optString("method") == "bolt11" &&
                                        entry.optString("unit") == "sat") {
                                        supportsWebSocket = true
                                        val commands = entry.optJSONArray("commands")
                                        if (commands != null) {
                                            for (j in 0 until commands.length()) {
                                                webSocketCommands.add(commands.getString(j))
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to parse NUT-17 capabilities: ${e.message}")
                        }
                    }
                }

                val capabilities = MintCapabilities(
                    mintUrl = normalizedUrl,
                    name = json.optString("name").takeIf { it.isNotBlank() },
                    version = json.optString("version").takeIf { it.isNotBlank() },
                    description = json.optString("description").takeIf { it.isNotBlank() },
                    supportedNuts = supportedNuts,
                    supportsHtlc = 14 in supportedNuts,
                    supportsMelt = 5 in supportedNuts,
                    supportsProofState = 7 in supportedNuts,
                    supportsWebSocket = supportsWebSocket,
                    webSocketCommands = webSocketCommands
                )

                Log.d(TAG, "Mint $normalizedUrl capabilities: NUTs=${supportedNuts}, escrow=${capabilities.supportsEscrow()}, ws=${supportsWebSocket}${if (webSocketCommands.isNotEmpty()) " (${webSocketCommands.joinToString()})" else ""}")
                capabilities
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify mint: $mintUrl", e)
            null
        }
    }

    /**
     * Connect to a mint after verifying capabilities.
     *
     * @param mintUrl The mint URL
     * @return true if connected and mint supports escrow, false otherwise
     */
    suspend fun connect(mintUrl: String): Boolean {
        val capabilities = verifyMintCapabilities(mintUrl)
        if (capabilities == null) {
            Log.e(TAG, "Failed to connect to mint: $mintUrl")
            return false
        }

        // Connect even if escrow isn't supported - just log a warning
        if (!capabilities.supportsEscrow()) {
            Log.w(TAG, "Mint doesn't support escrow (missing NUT-14/5/7): $mintUrl - ride payments may not work")
        }

        currentMintUrl = mintUrl.trimEnd('/')
        mintCapabilities = capabilities

        // Initialize cdk-kotlin wallet for real Cashu operations
        try {
            val dbPath = context.getDatabasePath("cashu_proofs.db").absolutePath
            // Ensure parent directory exists
            context.getDatabasePath("cashu_proofs.db").parentFile?.mkdirs()

            cdkDatabase = CdkWalletSqliteDatabase(dbPath)

            val mnemonic = walletKeyManager.getOrCreateMnemonic()

            // NUT-13: Derive BIP-39 seed for deterministic secret generation
            walletSeed = CashuCrypto.mnemonicToSeed(mnemonic)
            Log.d(TAG, "Derived wallet seed from mnemonic (${walletSeed!!.size} bytes)")

            val walletConfig = CdkWalletConfig(16u) // targetProofCount

            cdkWallet = CdkWallet(
                mintUrl = mintUrl.trimEnd('/'),
                unit = CdkCurrencyUnit.Sat,
                mnemonic = mnemonic,
                db = cdkDatabase!!,
                config = walletConfig
            )
            Log.d(TAG, "cdk-kotlin wallet initialized for $mintUrl")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize cdk-kotlin wallet: ${e.message}", e)
            // Continue without cdk-kotlin for backward compatibility
            // The stub methods will still work for UI flow
        }

        // NUT-17: Initialize WebSocket if mint supports it
        if (capabilities.supportsWebSocket) {
            try {
                webSocket = CashuWebSocket(mintUrl.trimEnd('/'), client).also { ws ->
                    ws.connect()
                    Log.d(TAG, "WebSocket initialized for real-time state updates")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize WebSocket (will fall back to polling): ${e.message}")
                webSocket = null
            }
        } else {
            Log.d(TAG, "Mint does not support NUT-17 WebSocket, will use polling")
        }

        Log.d(TAG, "Connected to mint: $mintUrl (escrow=${capabilities.supportsEscrow()}, ws=${webSocket != null})")
        return true
    }

    /**
     * Get current mint URL.
     */
    fun getCurrentMintUrl(): String? = currentMintUrl

    /**
     * Get mint capabilities (NUT-06 info).
     */
    fun getMintCapabilities(): MintCapabilities? = mintCapabilities

    /**
     * Get the WebSocket instance for NUT-17 real-time subscriptions.
     * Returns null if mint doesn't support WebSocket or connection failed.
     */
    fun getWebSocket(): CashuWebSocket? = webSocket

    /**
     * Check if WebSocket is connected and available.
     */
    fun isWebSocketConnected(): Boolean = webSocket?.isConnected() == true

    /**
     * Check if WebSocket supports a specific subscription kind.
     */
    fun supportsWebSocketKind(kind: SubscriptionKind): Boolean {
        return mintCapabilities?.supportsWebSocketKind(kind.value) == true && webSocket != null
    }

    /**
     * Get the wallet seed (for NUT-09 recovery).
     */
    fun getSeed(): ByteArray? = walletSeed

    /**
     * Generate deterministic PreMintSecrets for a list of amounts.
     * Uses NUT-13 derivation from wallet seed and increments counters.
     *
     * @param amounts List of amounts for the proofs
     * @param keysetId The keyset ID to use for derivation
     * @return List of PreMintSecrets, or null if seed not available
     */
    private fun generateDeterministicPreMints(
        amounts: List<Long>,
        keysetId: String
    ): List<PreMintSecret>? {
        val seed = walletSeed ?: run {
            Log.e(TAG, "generateDeterministicPreMints: wallet seed not available, falling back to random")
            return null
        }

        return amounts.map { amount ->
            // Get next counter and increment atomically
            val counter = walletStorage.getCounter(keysetId)
            walletStorage.setCounter(keysetId, counter + 1)

            // Derive deterministic PreMintSecret
            CashuCrypto.derivePreMintSecret(seed, keysetId, counter, amount)
                ?: throw Exception("Failed to derive PreMintSecret for counter $counter")
        }
    }

    /**
     * Generate PreMintSecrets for amounts - deterministic if seed available, random fallback.
     * This is the primary method for creating outputs.
     *
     * @param amounts List of amounts for the proofs
     * @param keysetId The keyset ID
     * @return List of PreMintSecrets
     */
    private fun generatePreMintSecrets(
        amounts: List<Long>,
        keysetId: String
    ): List<PreMintSecret> {
        // Use deterministic derivation (NUT-13) - no fallback to random
        val deterministicResult = generateDeterministicPreMints(amounts, keysetId)
        if (deterministicResult != null) {
            Log.d(TAG, "Generated ${deterministicResult.size} deterministic PreMintSecrets for keyset $keysetId")
            return deterministicResult
        }

        // No seed available - fail loudly instead of creating non-recoverable proofs
        Log.e(TAG, "CRITICAL: Cannot generate PreMintSecrets - wallet seed not available!")
        throw IllegalStateException(
            "Wallet seed not initialized. Cannot create recoverable proofs. " +
            "Ensure wallet is fully connected before attempting mint operations."
        )
    }

    /**
     * Check if connected to a mint.
     */
    fun isConnected(): Boolean = currentMintUrl != null

    /**
     * Check if connected mint supports escrow (NUT-14/5/7).
     */
    fun supportsEscrow(): Boolean = mintCapabilities?.supportsEscrow() == true

    /**
     * Get a melt quote from the mint (NUT-05).
     * Uses cdk-kotlin's native meltQuote() to ensure quote is tracked for later melting.
     *
     * CRITICAL: Must use wallet.meltQuote() NOT HTTP, because wallet.melt() only
     * recognizes quotes created through the wallet's own meltQuote() method.
     *
     * @param bolt11 The Lightning invoice to pay
     * @return MeltQuote if successful, null on failure
     */
    suspend fun getMeltQuote(bolt11: String): MeltQuote? = withContext(Dispatchers.IO) {
        val wallet = cdkWallet
        if (wallet == null) {
            Log.e(TAG, "getMeltQuote: cdk-kotlin wallet not initialized")
            return@withContext null
        }

        try {
            Log.d(TAG, "Requesting melt quote for bolt11 via cdk-kotlin: ${bolt11.take(50)}...")

            // Use cdk-kotlin's native meltQuote - this registers the quote internally
            // so wallet.melt() will recognize it later
            val cdkQuote = wallet.meltQuote(bolt11, null)

            Log.d(TAG, "Got melt quote via cdk-kotlin: ${cdkQuote.id}, amount: ${cdkQuote.amount}, fee: ${cdkQuote.feeReserve}")

            // Convert cdk-kotlin quote state to our MeltQuoteState type
            val stateStr = cdkQuote.state.toString().uppercase()
            val state = when {
                "PAID" in stateStr -> MeltQuoteState.PAID
                "PENDING" in stateStr -> MeltQuoteState.PENDING
                else -> MeltQuoteState.UNPAID
            }

            // Extract amount as Long - cdk-kotlin Amount uses extractAmount helper
            val amount = extractAmount(cdkQuote.amount)
            val feeReserve = extractAmount(cdkQuote.feeReserve)

            MeltQuote(
                quote = cdkQuote.id,
                request = bolt11,
                amount = amount,
                unit = "sat",
                feeReserve = feeReserve,
                state = state,
                expiry = cdkQuote.expiry?.toLong() ?: 0L,
                paymentPreimage = null  // Will be filled after melt
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get melt quote via cdk-kotlin: ${e.message}", e)
            null
        }
    }

    /**
     * Check melt quote status via HTTP.
     * NUT-05: GET /v1/melt/quote/bolt11/{quote_id}
     *
     * @param quoteId The melt quote ID to check
     * @return MeltQuote with updated state, or null on failure
     */
    suspend fun checkMeltQuote(quoteId: String): MeltQuote? = withContext(Dispatchers.IO) {
        val mintUrl = currentMintUrl ?: run {
            Log.e(TAG, "checkMeltQuote: No mint URL configured")
            return@withContext null
        }

        try {
            val request = Request.Builder()
                .url("$mintUrl/v1/melt/quote/bolt11/$quoteId")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Check melt quote failed: ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                Log.d(TAG, "Check melt quote $quoteId response: $body")
                val json = JSONObject(body)

                val stateStr = json.getString("state").uppercase()
                val state = when {
                    "PAID" in stateStr -> MeltQuoteState.PAID
                    "PENDING" in stateStr -> MeltQuoteState.PENDING
                    else -> MeltQuoteState.UNPAID
                }

                Log.d(TAG, "Melt quote $quoteId state: $stateStr -> $state")

                MeltQuote(
                    quote = json.getString("quote"),
                    request = json.optString("request", ""),
                    amount = json.getLong("amount"),
                    unit = json.optString("unit", "sat"),
                    feeReserve = json.optLong("fee_reserve", 0L),
                    state = state,
                    expiry = if (json.isNull("expiry")) 0L else json.optLong("expiry", 0L),
                    paymentPreimage = if (json.has("payment_preimage") && !json.isNull("payment_preimage"))
                        json.getString("payment_preimage") else null
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check melt quote: ${e.message}", e)
            null
        }
    }

    /**
     * Wait for a melt quote to reach a terminal state (PAID or UNPAID).
     *
     * Uses NUT-17 WebSocket subscription if available for instant notifications,
     * otherwise falls back to polling with exponential backoff.
     *
     * @param quoteId The melt quote ID to monitor
     * @param timeoutMs Maximum time to wait (default 60 seconds)
     * @return The final MeltQuote state, or null on timeout/error
     */
    suspend fun waitForMeltQuoteState(
        quoteId: String,
        timeoutMs: Long = 60_000L
    ): MeltQuote? = withContext(Dispatchers.IO) {
        val ws = webSocket

        // Try WebSocket subscription if available
        if (ws != null && ws.isConnected() &&
            mintCapabilities?.supportsWebSocketKind(SubscriptionKind.BOLT11_MELT_QUOTE.value) == true) {

            Log.d(TAG, "Using WebSocket for melt quote $quoteId")

            val result = CompletableDeferred<MeltQuote?>()

            // Store original callback to restore later
            val originalCallback = ws.onMeltQuoteUpdate

            // Subscribe to melt quote updates
            val subId = ws.subscribe(SubscriptionKind.BOLT11_MELT_QUOTE, listOf(quoteId))

            if (subId != null) {
                try {
                    // Set up notification handler
                    ws.onMeltQuoteUpdate = { id, payload ->
                        if (id == quoteId) {
                            Log.d(TAG, "WebSocket: melt quote $id -> ${payload.state}")
                            when (payload.state) {
                                WsMeltQuoteState.PAID, WsMeltQuoteState.FAILED -> {
                                    // Convert WebSocket payload to MeltQuote
                                    // Note: WebSocket payload doesn't have full quote info,
                                    // so we need to fetch the complete quote
                                    GlobalScope.launch(Dispatchers.IO) {
                                        val quote = checkMeltQuote(quoteId)
                                        result.complete(quote)
                                    }
                                }
                                WsMeltQuoteState.PENDING, WsMeltQuoteState.UNPAID -> {
                                    // Still waiting
                                    Log.d(TAG, "WebSocket: melt quote still ${payload.state}")
                                }
                            }
                        }
                    }

                    // Wait for result with timeout
                    val quote = withTimeoutOrNull(timeoutMs) {
                        result.await()
                    }

                    if (quote != null) {
                        Log.d(TAG, "WebSocket: got melt quote result: ${quote.state}")
                        return@withContext quote
                    }

                    Log.w(TAG, "WebSocket: melt quote timeout after ${timeoutMs}ms")
                } finally {
                    // Cleanup
                    ws.onMeltQuoteUpdate = originalCallback
                    ws.unsubscribe(subId)
                }
            } else {
                Log.w(TAG, "WebSocket: failed to subscribe to melt quote, falling back to polling")
            }
        }

        // Fallback to polling with progressive backoff
        Log.d(TAG, "Using polling for melt quote $quoteId")

        val pollDelays = listOf(500L, 1000L, 2000L, 3000L, 4000L) + List(9) { 5000L }
        var elapsedMs = 0L

        for ((attempt, pollDelay) in pollDelays.withIndex()) {
            if (elapsedMs >= timeoutMs) break

            delay(pollDelay)
            elapsedMs += pollDelay

            Log.d(TAG, "Polling melt quote status (attempt ${attempt + 1}/${pollDelays.size})...")

            val quote = checkMeltQuote(quoteId)
            if (quote != null) {
                when (quote.state) {
                    MeltQuoteState.PAID, MeltQuoteState.UNPAID -> {
                        Log.d(TAG, "Melt quote reached terminal state: ${quote.state}")
                        return@withContext quote
                    }
                    MeltQuoteState.PENDING -> {
                        // Continue polling
                    }
                }
            }
        }

        Log.w(TAG, "Melt quote polling timed out after ${elapsedMs}ms")
        null
    }

    /**
     * Wait for a mint quote (deposit) to reach PAID or ISSUED state.
     *
     * Uses NUT-17 WebSocket subscription if available for instant notifications,
     * otherwise falls back to polling with exponential backoff.
     *
     * @param quoteId The mint quote ID to monitor
     * @param timeoutMs Maximum time to wait (default 60 seconds)
     * @return The final MintQuote state, or null on timeout/error
     */
    suspend fun waitForMintQuoteState(
        quoteId: String,
        timeoutMs: Long = 60_000L
    ): MintQuote? = withContext(Dispatchers.IO) {
        val ws = webSocket

        // Try WebSocket subscription if available
        if (ws != null && ws.isConnected() &&
            mintCapabilities?.supportsWebSocketKind(SubscriptionKind.BOLT11_MINT_QUOTE.value) == true) {

            Log.d(TAG, "Using WebSocket for mint quote $quoteId")

            val result = CompletableDeferred<MintQuote?>()

            // Store original callback to restore later
            val originalCallback = ws.onMintQuoteUpdate

            // Subscribe to mint quote updates
            val subId = ws.subscribe(SubscriptionKind.BOLT11_MINT_QUOTE, listOf(quoteId))

            if (subId != null) {
                try {
                    // Set up notification handler
                    ws.onMintQuoteUpdate = { id, payload ->
                        if (id == quoteId) {
                            Log.d(TAG, "WebSocket: mint quote $id -> ${payload.state}")
                            when (payload.state) {
                                WsMintQuoteState.PAID, WsMintQuoteState.ISSUED -> {
                                    // Fetch full quote data
                                    GlobalScope.launch(Dispatchers.IO) {
                                        val quote = checkMintQuote(quoteId)
                                        result.complete(quote)
                                    }
                                }
                                WsMintQuoteState.EXPIRED -> {
                                    // Quote expired - complete with null
                                    Log.w(TAG, "WebSocket: mint quote $id expired")
                                    result.complete(null)
                                }
                                WsMintQuoteState.UNPAID -> {
                                    // Still waiting for payment
                                    Log.d(TAG, "WebSocket: mint quote still UNPAID")
                                }
                            }
                        }
                    }

                    // Wait for result with timeout
                    val quote = withTimeoutOrNull(timeoutMs) {
                        result.await()
                    }

                    if (quote != null) {
                        Log.d(TAG, "WebSocket: got mint quote result: ${quote.state}")
                        return@withContext quote
                    }

                    Log.w(TAG, "WebSocket: mint quote timeout after ${timeoutMs}ms")
                } finally {
                    // Cleanup
                    ws.onMintQuoteUpdate = originalCallback
                    ws.unsubscribe(subId)
                }
            } else {
                Log.w(TAG, "WebSocket: failed to subscribe to mint quote, falling back to polling")
            }
        }

        // Fallback to polling with progressive backoff
        Log.d(TAG, "Using polling for mint quote $quoteId")

        val pollDelays = listOf(500L, 1000L, 2000L, 3000L, 4000L) + List(9) { 5000L }
        var elapsedMs = 0L

        for ((attempt, pollDelay) in pollDelays.withIndex()) {
            if (elapsedMs >= timeoutMs) break

            delay(pollDelay)
            elapsedMs += pollDelay

            Log.d(TAG, "Polling mint quote status (attempt ${attempt + 1}/${pollDelays.size})...")

            val quote = checkMintQuote(quoteId)
            if (quote != null) {
                when (quote.state) {
                    MintQuoteState.PAID, MintQuoteState.ISSUED -> {
                        Log.d(TAG, "Mint quote reached terminal state: ${quote.state}")
                        return@withContext quote
                    }
                    MintQuoteState.UNPAID -> {
                        // Continue polling
                    }
                }
            }
        }

        Log.w(TAG, "Mint quote polling timed out after ${elapsedMs}ms")
        null
    }

    /**
     * Check proof states via NUT-07.
     *
     * @param Ys List of Y values (hash_to_curve(secret)) to check
     * @return List of proof states, or null on failure
     */
    suspend fun checkProofStates(Ys: List<String>): List<ProofStateCheck>? = withContext(Dispatchers.IO) {
        val mintUrl = currentMintUrl ?: return@withContext null

        try {
            val requestBody = JSONObject().apply {
                put("Ys", JSONArray(Ys))
            }.toString()

            val request = Request.Builder()
                .url("$mintUrl/v1/checkstate")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Check state request failed: ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val statesArray = json.getJSONArray("states")

                // Log first response Y for comparison
                if (statesArray.length() > 0) {
                    val firstState = statesArray.getJSONObject(0)
                    Log.d(TAG, "checkProofStates: First Y in response: ${firstState.getString("Y").take(40)}... state: ${firstState.getString("state")}")
                }

                val states = mutableListOf<ProofStateCheck>()
                for (i in 0 until statesArray.length()) {
                    val stateObj = statesArray.getJSONObject(i)
                    states.add(ProofStateCheck(
                        Y = stateObj.getString("Y"),
                        state = ProofState.valueOf(stateObj.getString("state").uppercase()),
                        witness = stateObj.optString("witness").takeIf { it.isNotBlank() }
                    ))
                }
                states
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check proof states", e)
            null
        }
    }

    // ========================================
    // NUT-14 HTLC Operations (REAL IMPLEMENTATION)
    // Reference: https://github.com/cashubtc/nuts/blob/main/14.md
    // Note: Primary HTLC creation is now via createHtlcTokenFromProofs() which takes NIP-60 proofs
    // ========================================

    /**
     * Claim an HTLC-locked token using the preimage.
     *
     * This performs an actual NUT-14 swap at the mint:
     * 1. Parse the Cashu token to extract HTLC-locked proofs
     * 2. Add witness with preimage to each proof
     * 3. POST /v1/swap to convert HTLC proofs + witness → plain proofs
     * 4. Store plain proofs in wallet
     *
     * @param htlcToken The HTLC Cashu token to claim
     * @param preimage The 64-char hex preimage that hashes to the payment_hash
     * @return Settlement proof JSON if successful, null on failure
     */
    suspend fun claimHtlcToken(
        htlcToken: String,
        preimage: String
    ): String? = withContext(Dispatchers.IO) {
        val mintUrl = currentMintUrl

        if (mintUrl == null) {
            Log.e(TAG, "claimHtlcToken: mint not initialized")
            return@withContext null
        }

        // Validate preimage format
        if (preimage.length != 64 || !preimage.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            Log.e(TAG, "claimHtlcToken: invalid preimage format (expected 64-char hex)")
            return@withContext null
        }

        Log.d(TAG, "=== CLAIMING HTLC TOKEN ===")

        try {
            // Step 1: Parse token to extract HTLC proofs
            val parseResult = parseHtlcToken(htlcToken)
            if (parseResult == null) {
                Log.e(TAG, "claimHtlcToken: failed to parse HTLC token")
                return@withContext null
            }

            val (htlcProofs, tokenMintUrl) = parseResult
            val totalAmount = htlcProofs.sumOf { it.amount }
            Log.d(TAG, "Parsed ${htlcProofs.size} HTLC proofs totaling $totalAmount sats")

            // Step 2: Verify preimage matches payment_hash from first proof
            val expectedHash = extractPaymentHashFromSecret(htlcProofs.first().secret)
            if (expectedHash == null) {
                Log.e(TAG, "claimHtlcToken: could not extract payment_hash from secret")
                return@withContext null
            }

            val computedHash = PaymentCrypto.computePaymentHash(preimage)
            if (!computedHash.equals(expectedHash, ignoreCase = true)) {
                Log.e(TAG, "claimHtlcToken: preimage does not match payment_hash")
                Log.e(TAG, "Expected: ${expectedHash.take(16)}, Got: ${computedHash.take(16)}")
                return@withContext null
            }
            Log.d(TAG, "Preimage verified against payment_hash")

            // Step 3: Get keyset for creating new plain outputs
            val keyset = getActiveKeyset()
            if (keyset == null) {
                Log.e(TAG, "claimHtlcToken: failed to get active keyset")
                return@withContext null
            }

            // Step 4: Create plain output secrets (NUT-13 deterministic)
            val outputAmounts = splitAmount(totalAmount)
            val outputPremints = generatePreMintSecrets(outputAmounts, keyset.id)

            // Step 5: Build swap request with HTLC inputs + per-proof witness
            // Each proof needs its own signature: SHA256(secret || C) signed with wallet key
            val inputsArray = JSONArray()
            htlcProofs.forEach { proof ->
                // Create P2PK signature for this proof
                val sig = signP2pkProof(proof.secret, proof.C)
                val witnessJson = JSONObject().apply {
                    put("preimage", preimage)
                    put("signatures", JSONArray().apply {
                        if (sig != null) put(sig)
                    })
                }.toString()

                inputsArray.put(JSONObject().apply {
                    put("amount", proof.amount)
                    put("id", proof.id)
                    put("secret", proof.secret)
                    put("C", proof.C)
                    put("witness", witnessJson)
                })
            }
            Log.d(TAG, "Created witnesses with P2PK signatures for ${htlcProofs.size} proofs")

            val outputsArray = JSONArray()
            outputPremints.forEach { pms ->
                outputsArray.put(JSONObject().apply {
                    put("amount", pms.amount)
                    put("id", keyset.id)
                    put("B_", pms.B_)
                })
            }

            val swapRequest = JSONObject().apply {
                put("inputs", inputsArray)
                put("outputs", outputsArray)
            }.toString()

            Log.d(TAG, "Sending claim swap with ${htlcProofs.size} HTLC inputs, ${outputPremints.size} outputs")

            // CRITICAL: Save pending operation BEFORE sending request
            val operationId = java.util.UUID.randomUUID().toString()
            val pendingOp = PendingBlindedOperation(
                id = operationId,
                operationType = BlindedOperationType.CLAIM_HTLC,
                mintUrl = tokenMintUrl.trimEnd('/'),
                quoteId = null,
                inputSecrets = htlcProofs.map { it.secret },
                outputPremints = outputPremints.map { pms ->
                    SerializedPreMint(
                        amount = pms.amount,
                        secret = pms.secret,
                        blindingFactor = pms.blindingFactor,
                        Y = pms.Y,
                        B_ = pms.B_
                    )
                },
                amountSats = totalAmount,
                createdAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000L), // 24 hours
                status = PendingOperationStatus.STARTED
            )
            walletStorage.savePendingBlindedOp(pendingOp)
            Log.d(TAG, "Saved pending HTLC claim operation: $operationId ($totalAmount sats)")

            // Step 7: Execute swap at mint
            val targetMint = tokenMintUrl.trimEnd('/')
            val request = Request.Builder()
                .url("$targetMint/v1/swap")
                .post(swapRequest.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val signatures = client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                Log.d(TAG, "Claim swap response: ${response.code}")

                if (!response.isSuccessful) {
                    Log.e(TAG, "Claim swap failed: ${response.code} - $responseBody")
                    // Keep pending operation for recovery
                    return@withContext null
                }

                val json = JSONObject(responseBody ?: "{}")
                json.getJSONArray("signatures")
            }

            // Step 8: Unblind signatures to get plain proofs
            // CRITICAL: Use keyset ID from response, not from getActiveKeyset()
            val plainProofs = mutableListOf<CashuProof>()
            val keysetCache = mutableMapOf<String, MintKeyset>()
            // Pre-cache the active keyset we already have
            keysetCache[keyset.id] = keyset

            for (i in 0 until outputPremints.size) {
                val sig = signatures.getJSONObject(i)
                val pms = outputPremints[i]
                val C_ = sig.getString("C_")
                val responseKeysetId = sig.getString("id")
                // CRITICAL: Use amount from response for key lookup (like CDK does)
                val responseAmount = sig.getLong("amount")

                // Get keyset (use cached if available)
                val sigKeyset = keysetCache.getOrPut(responseKeysetId) {
                    fetchKeyset(responseKeysetId) ?: throw Exception("Failed to fetch keyset $responseKeysetId")
                }

                val mintPubKey = sigKeyset.keys[responseAmount]
                    ?: throw Exception("No key for amount $responseAmount in keyset $responseKeysetId")
                val C = CashuCrypto.unblindSignature(C_, pms.blindingFactor, mintPubKey)
                    ?: throw Exception("Failed to unblind signature")

                plainProofs.add(CashuProof(responseAmount, responseKeysetId, pms.secret, C))
            }

            Log.d(TAG, "=== HTLC CLAIMED SUCCESSFULLY ===")
            Log.d(TAG, "Received ${plainProofs.size} plain proofs totaling $totalAmount sats")

            // SUCCESS: Remove pending operation
            walletStorage.updateBlindedOpStatus(operationId, PendingOperationStatus.COMPLETED)
            walletStorage.removePendingBlindedOp(operationId)
            Log.d(TAG, "Removed pending HTLC claim operation: $operationId")

            // Return settlement proof JSON (for backward compatibility)
            JSONObject().apply {
                put("preimage", preimage)
                put("paymentHash", expectedHash)
                put("amount", totalAmount)
                put("timestamp", System.currentTimeMillis())
                put("mintUrl", targetMint)
                put("proofCount", plainProofs.size)
                put("status", "SETTLED")
            }.toString()
        } catch (e: Exception) {
            Log.e(TAG, "claimHtlcToken failed: ${e.message}", e)
            // Keep pending operation for recovery
            null
        }
    }

    /**
     * Claim an HTLC token and return the full result including received proofs.
     *
     * Use this method when you need to publish the received proofs to NIP-60.
     *
     * @param htlcToken The HTLC Cashu token to claim
     * @param preimage The 64-char hex preimage that unlocks the HTLC
     * @return HtlcClaimResult with settlement proof and received proofs, or null on failure
     */
    suspend fun claimHtlcTokenWithProofs(
        htlcToken: String,
        preimage: String
    ): HtlcClaimResult? = withContext(Dispatchers.IO) {
        val mintUrl = currentMintUrl

        if (mintUrl == null) {
            Log.e(TAG, "claimHtlcTokenWithProofs: mint not initialized")
            return@withContext null
        }

        // Validate preimage
        if (preimage.length != 64 || !preimage.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
            Log.e(TAG, "claimHtlcTokenWithProofs: invalid preimage format")
            return@withContext null
        }

        Log.d(TAG, "=== CLAIMING HTLC (with proofs) ===")

        try {
            // Parse token
            val parseResult = parseHtlcToken(htlcToken)
            if (parseResult == null) {
                Log.e(TAG, "Failed to parse HTLC token - parseHtlcToken returned null")
                return@withContext null
            }
            val (htlcProofs, tokenMintUrl) = parseResult
            val totalAmount = htlcProofs.sumOf { it.amount }
            Log.d(TAG, "Parsed token: ${htlcProofs.size} proofs, $totalAmount sats")

            // Verify preimage
            val expectedHash = extractPaymentHashFromSecret(htlcProofs.first().secret)
            if (expectedHash == null) {
                Log.e(TAG, "Failed to extract payment_hash from HTLC secret")
                return@withContext null
            }
            val computedHash = PaymentCrypto.computePaymentHash(preimage)
            if (!computedHash.equals(expectedHash, ignoreCase = true)) {
                Log.e(TAG, "Preimage does not match payment_hash")
                return@withContext null
            }

            // Get keyset
            val keyset = getActiveKeyset()
            if (keyset == null) {
                Log.e(TAG, "Failed to get active keyset from mint")
                return@withContext null
            }
            Log.d(TAG, "Using keyset: ${keyset.id}")

            // Create output secrets (NUT-13 deterministic)
            val outputAmounts = splitAmount(totalAmount)
            val outputPremints = generatePreMintSecrets(outputAmounts, keyset.id)

            // Build swap request with per-proof witness signatures
            val inputsArray = JSONArray()
            htlcProofs.forEach { proof ->
                // Create P2PK signature for this proof
                val sig = signP2pkProof(proof.secret, proof.C)
                val witnessJson = JSONObject().apply {
                    put("preimage", preimage)
                    put("signatures", JSONArray().apply {
                        if (sig != null) put(sig)
                    })
                }.toString()

                inputsArray.put(JSONObject().apply {
                    put("amount", proof.amount)
                    put("id", proof.id)
                    put("secret", proof.secret)
                    put("C", proof.C)
                    put("witness", witnessJson)
                })
            }
            Log.d(TAG, "Created witnesses with P2PK signatures for ${htlcProofs.size} proofs")

            val outputsArray = JSONArray()
            outputPremints.forEach { pms ->
                outputsArray.put(JSONObject().apply {
                    put("amount", pms.amount)
                    put("id", keyset.id)
                    put("B_", pms.B_)
                })
            }

            val swapRequest = JSONObject().apply {
                put("inputs", inputsArray)
                put("outputs", outputsArray)
            }.toString()

            Log.d(TAG, "Swap request to mint (${swapRequest.length} chars)")

            // CRITICAL: Save pending operation BEFORE sending request
            val operationId = java.util.UUID.randomUUID().toString()
            val targetMint = tokenMintUrl.trimEnd('/')
            val pendingOp = PendingBlindedOperation(
                id = operationId,
                operationType = BlindedOperationType.CLAIM_HTLC,
                mintUrl = targetMint,
                quoteId = null,
                inputSecrets = htlcProofs.map { it.secret },
                outputPremints = outputPremints.map { pms ->
                    SerializedPreMint(
                        amount = pms.amount,
                        secret = pms.secret,
                        blindingFactor = pms.blindingFactor,
                        Y = pms.Y,
                        B_ = pms.B_
                    )
                },
                amountSats = totalAmount,
                createdAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000L), // 24 hours
                status = PendingOperationStatus.STARTED
            )
            walletStorage.savePendingBlindedOp(pendingOp)
            Log.d(TAG, "Saved pending HTLC claim operation: $operationId ($totalAmount sats)")

            // Execute swap
            Log.d(TAG, "Executing swap at: $targetMint/v1/swap")
            val request = Request.Builder()
                .url("$targetMint/v1/swap")
                .post(swapRequest.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val signatures = client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                Log.d(TAG, "Swap response: ${response.code}")
                if (!response.isSuccessful) {
                    Log.e(TAG, "Claim swap failed: ${response.code} - $responseBody")
                    // Keep pending operation for recovery
                    return@withContext null
                }
                Log.d(TAG, "Swap successful, parsing signatures...")
                JSONObject(responseBody ?: "{}").getJSONArray("signatures")
            }

            // Unblind signatures - CRITICAL: Use keyset ID from response
            val plainProofs = mutableListOf<CashuProof>()
            val keysetCache = mutableMapOf<String, MintKeyset>()
            keysetCache[keyset.id] = keyset

            for (i in 0 until outputPremints.size) {
                val sig = signatures.getJSONObject(i)
                val pms = outputPremints[i]
                val C_ = sig.getString("C_")
                val responseKeysetId = sig.getString("id")
                // CRITICAL: Use amount from response for key lookup (like CDK does)
                val responseAmount = sig.getLong("amount")

                val sigKeyset = keysetCache.getOrPut(responseKeysetId) {
                    fetchKeyset(responseKeysetId) ?: throw Exception("Failed to fetch keyset $responseKeysetId")
                }

                val mintPubKey = sigKeyset.keys[responseAmount] ?: throw Exception("No key for $responseAmount")
                val C = CashuCrypto.unblindSignature(C_, pms.blindingFactor, mintPubKey)
                    ?: throw Exception("Unblind failed")
                plainProofs.add(CashuProof(responseAmount, responseKeysetId, pms.secret, C))
            }

            Log.d(TAG, "=== HTLC CLAIMED (with proofs) ===")
            Log.d(TAG, "Received ${plainProofs.size} proofs totaling $totalAmount sats")

            // Mark completed but do NOT remove - caller must persist proofs first
            walletStorage.updateBlindedOpStatus(operationId, PendingOperationStatus.COMPLETED)
            Log.d(TAG, "Pending HTLC claim op $operationId ready - caller must clear after NIP-60 publish")

            val settlementProof = JSONObject().apply {
                put("preimage", preimage)
                put("paymentHash", expectedHash)
                put("amount", totalAmount)
                put("timestamp", System.currentTimeMillis())
                put("mintUrl", targetMint)
                put("proofCount", plainProofs.size)
                put("status", "SETTLED")
            }.toString()

            HtlcClaimResult(
                settlementProof = settlementProof,
                receivedProofs = plainProofs,
                amountSats = totalAmount,
                mintUrl = targetMint,
                pendingOpId = operationId
            )
        } catch (e: Exception) {
            Log.e(TAG, "claimHtlcTokenWithProofs failed: ${e.message}", e)
            // Keep pending operation for recovery
            null
        }
    }

    /**
     * Refund an expired HTLC token back to plain proofs.
     *
     * After locktime expires, the rider can reclaim funds using their refund signature.
     * This requires no preimage - only the rider's P2PK signature.
     *
     * @param htlcToken The HTLC Cashu token to refund
     * @param riderPubKey The rider's wallet public key (must match refund tag in secret)
     * @return HtlcRefundResult with refunded proofs, or null on failure
     */
    suspend fun refundExpiredHtlc(
        htlcToken: String,
        riderPubKey: String
    ): HtlcRefundResult? = withContext(Dispatchers.IO) {
        val mintUrl = currentMintUrl

        if (mintUrl == null) {
            Log.e(TAG, "refundExpiredHtlc: mint not initialized")
            return@withContext null
        }

        Log.d(TAG, "=== REFUNDING EXPIRED HTLC ===")

        try {
            // Step 1: Parse token to extract HTLC proofs
            val parseResult = parseHtlcToken(htlcToken)
            if (parseResult == null) {
                Log.e(TAG, "refundExpiredHtlc: failed to parse HTLC token")
                return@withContext null
            }

            val (htlcProofs, tokenMintUrl) = parseResult
            val totalAmount = htlcProofs.sumOf { it.amount }
            Log.d(TAG, "Parsed ${htlcProofs.size} HTLC proofs totaling $totalAmount sats")

            // Step 2: Verify locktime has expired
            val locktime = extractLocktimeFromSecret(htlcProofs.first().secret)
            if (locktime == null) {
                Log.e(TAG, "refundExpiredHtlc: no locktime found in HTLC secret")
                return@withContext null
            }

            val now = System.currentTimeMillis() / 1000
            if (now <= locktime) {
                Log.e(TAG, "refundExpiredHtlc: locktime not expired (now=$now, locktime=$locktime)")
                return@withContext null
            }
            Log.d(TAG, "Locktime expired: now=$now > locktime=$locktime")

            // Step 3: Verify rider pubkey matches refund tag
            val refundKeys = extractRefundKeysFromSecret(htlcProofs.first().secret)
            if (refundKeys.isEmpty() || !refundKeys.contains(riderPubKey)) {
                Log.e(TAG, "refundExpiredHtlc: rider pubkey not in refund tags")
                Log.e(TAG, "Expected one of: ${refundKeys.take(2)}, got: ${riderPubKey.take(16)}...")
                return@withContext null
            }
            Log.d(TAG, "Rider pubkey verified in refund tags")

            // Step 4: Get keyset for creating new plain outputs
            val keyset = getActiveKeyset()
            if (keyset == null) {
                Log.e(TAG, "refundExpiredHtlc: failed to get active keyset")
                return@withContext null
            }

            // Step 5: Create plain output secrets (NUT-13 deterministic)
            val outputAmounts = splitAmount(totalAmount)
            val outputPremints = generatePreMintSecrets(outputAmounts, keyset.id)

            // Step 6: Build swap request with refund witness
            // Refund path: signature only (no preimage needed)
            val inputsArray = JSONArray()
            htlcProofs.forEach { proof ->
                // Create P2PK signature for refund
                val sig = signP2pkProof(proof.secret, proof.C)
                val witnessJson = JSONObject().apply {
                    // No preimage for refund path
                    put("signatures", JSONArray().apply {
                        if (sig != null) put(sig)
                    })
                }.toString()

                inputsArray.put(JSONObject().apply {
                    put("amount", proof.amount)
                    put("id", proof.id)
                    put("secret", proof.secret)
                    put("C", proof.C)
                    put("witness", witnessJson)
                })
            }
            Log.d(TAG, "Created refund witnesses for ${htlcProofs.size} proofs")

            val outputsArray = JSONArray()
            outputPremints.forEach { pms ->
                outputsArray.put(JSONObject().apply {
                    put("amount", pms.amount)
                    put("id", keyset.id)
                    put("B_", pms.B_)
                })
            }

            val swapRequest = JSONObject().apply {
                put("inputs", inputsArray)
                put("outputs", outputsArray)
            }.toString()

            Log.d(TAG, "Sending refund swap with ${htlcProofs.size} HTLC inputs, ${outputPremints.size} outputs")

            // CRITICAL: Save pending operation BEFORE sending request
            val operationId = java.util.UUID.randomUUID().toString()
            val targetMint = tokenMintUrl.trimEnd('/')
            val pendingOp = PendingBlindedOperation(
                id = operationId,
                operationType = BlindedOperationType.REFUND_HTLC,
                mintUrl = targetMint,
                quoteId = null,
                inputSecrets = htlcProofs.map { it.secret },
                outputPremints = outputPremints.map { pms ->
                    SerializedPreMint(
                        amount = pms.amount,
                        secret = pms.secret,
                        blindingFactor = pms.blindingFactor,
                        Y = pms.Y,
                        B_ = pms.B_
                    )
                },
                amountSats = totalAmount,
                createdAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000L), // 24 hours
                status = PendingOperationStatus.STARTED
            )
            walletStorage.savePendingBlindedOp(pendingOp)
            Log.d(TAG, "Saved pending HTLC refund operation: $operationId ($totalAmount sats)")

            // Step 7: Execute swap at mint
            val request = Request.Builder()
                .url("$targetMint/v1/swap")
                .post(swapRequest.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val signatures = client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                Log.d(TAG, "Refund swap response: ${response.code}")

                if (!response.isSuccessful) {
                    Log.e(TAG, "Refund swap failed: ${response.code} - $responseBody")
                    // Keep pending operation for recovery
                    return@withContext null
                }

                val json = JSONObject(responseBody ?: "{}")
                json.getJSONArray("signatures")
            }

            // Step 8: Unblind signatures to get plain proofs - CRITICAL: Use keyset ID from response
            val plainProofs = mutableListOf<CashuProof>()
            val keysetCache = mutableMapOf<String, MintKeyset>()
            keysetCache[keyset.id] = keyset

            for (i in 0 until outputPremints.size) {
                val sig = signatures.getJSONObject(i)
                val pms = outputPremints[i]
                val C_ = sig.getString("C_")
                val responseKeysetId = sig.getString("id")
                // CRITICAL: Use amount from response for key lookup (like CDK does)
                val responseAmount = sig.getLong("amount")

                val sigKeyset = keysetCache.getOrPut(responseKeysetId) {
                    fetchKeyset(responseKeysetId) ?: throw Exception("Failed to fetch keyset $responseKeysetId")
                }

                val C = CashuCrypto.unblindSignature(
                    C_ = C_,
                    r = pms.blindingFactor,
                    K = sigKeyset.keys[responseAmount] ?: throw Exception("No key for amount $responseAmount")
                ) ?: throw Exception("Unblind failed for amount $responseAmount")

                plainProofs.add(CashuProof(
                    amount = responseAmount,
                    id = responseKeysetId,
                    secret = pms.secret,
                    C = C
                ))
            }

            Log.d(TAG, "Refund successful! Received ${plainProofs.size} proofs, total $totalAmount sats")

            // Mark as COMPLETED but do NOT remove - caller must clear after NIP-60 publish
            walletStorage.updateBlindedOpStatus(operationId, PendingOperationStatus.COMPLETED)
            Log.d(TAG, "Marked pending HTLC refund operation COMPLETED: $operationId (caller must clear after NIP-60 persist)")

            HtlcRefundResult(
                refundedProofs = plainProofs,
                amountSats = totalAmount,
                mintUrl = targetMint,
                pendingOpId = operationId
            )
        } catch (e: Exception) {
            Log.e(TAG, "refundExpiredHtlc failed: ${e.message}", e)
            // Keep pending operation for recovery
            null
        }
    }

    /**
     * Result of refunding an expired HTLC token.
     */
    data class HtlcRefundResult(
        val refundedProofs: List<CashuProof>,
        val amountSats: Long,
        val mintUrl: String,
        val pendingOpId: String? = null  // ID of pending op - caller must clear after NIP-60 publish
    )

    /**
     * Result of creating an HTLC lock (escrow).
     */
    data class HtlcLockResult(
        val htlcToken: String,          // The HTLC-locked token
        val changeProofs: List<CashuProof>,  // Change proofs back to rider
        val pendingOpId: String? = null  // ID of pending op - caller must clear after NIP-60 publish
    )

    // ========================================
    // HTLC Helper Methods
    // ========================================

    /**
     * HTLC proof structure (proof with NUT-10/14 secret format).
     */
    data class HtlcProof(
        val amount: Long,
        val id: String,
        val secret: String,  // NUT-10 JSON format: ["HTLC", {...}]
        val C: String
    )

    /**
     * Result of claiming an HTLC token.
     */
    data class HtlcClaimResult(
        val settlementProof: String,  // JSON proof
        val receivedProofs: List<CashuProof>,  // Plain proofs received
        val amountSats: Long,
        val mintUrl: String,
        val pendingOpId: String? = null  // ID of pending op - caller must clear after NIP-60 publish
    )

    /**
     * Create NUT-10 HTLC secret as JSON string.
     * Format: ["HTLC", {"nonce": "...", "data": "payment_hash", "tags": [...]}]
     */
    private fun createHtlcSecretJson(
        paymentHash: String,
        driverPubKey: String,
        locktime: Long? = null,
        riderPubKey: String? = null
    ): String {
        val nonce = PaymentCrypto.generatePreimage().take(32)  // 16 bytes as hex

        val tagsArray = JSONArray()

        // Add pubkeys tag (driver can spend with preimage + signature)
        if (driverPubKey.isNotBlank()) {
            tagsArray.put(JSONArray().apply {
                put("pubkeys")
                put(driverPubKey)
            })
        }

        // Add locktime tag (for refund path)
        if (locktime != null) {
            tagsArray.put(JSONArray().apply {
                put("locktime")
                put(locktime.toString())
            })
        }

        // Add refund tag (rider can reclaim after locktime)
        if (riderPubKey != null) {
            tagsArray.put(JSONArray().apply {
                put("refund")
                put(riderPubKey)
            })
        }

        val secretObj = JSONObject().apply {
            put("nonce", nonce)
            put("data", paymentHash)
            put("tags", tagsArray)
        }

        return JSONArray().apply {
            put("HTLC")
            put(secretObj)
        }.toString()
    }

    /**
     * Extract payment_hash from NUT-10 HTLC secret.
     */
    private fun extractPaymentHashFromSecret(secret: String): String? {
        return try {
            val arr = JSONArray(secret)
            if (arr.length() >= 2 && arr.getString(0) == "HTLC") {
                val obj = arr.getJSONObject(1)
                obj.getString("data")
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract payment_hash from secret: ${e.message}")
            null
        }
    }

    /**
     * Extract locktime from NUT-10/14 HTLC secret.
     * Format: ["HTLC", {"nonce": "...", "data": "...", "tags": [["locktime", "12345"], ...]}]
     */
    private fun extractLocktimeFromSecret(secret: String): Long? {
        return try {
            val arr = JSONArray(secret)
            if (arr.length() >= 2 && arr.getString(0) == "HTLC") {
                val obj = arr.getJSONObject(1)
                val tags = obj.getJSONArray("tags")
                var locktime: Long? = null
                for (i in 0 until tags.length()) {
                    val tag = tags.getJSONArray(i)
                    if (tag.length() >= 2 && tag.getString(0) == "locktime") {
                        locktime = tag.getString(1).toLongOrNull()
                        break
                    }
                }
                locktime
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract locktime from secret: ${e.message}")
            null
        }
    }

    /**
     * Extract refund public keys from NUT-10/14 HTLC secret.
     * Format: ["HTLC", {"nonce": "...", "data": "...", "tags": [["refund", "key1", "key2"], ...]}]
     */
    private fun extractRefundKeysFromSecret(secret: String): List<String> {
        return try {
            val arr = JSONArray(secret)
            if (arr.length() >= 2 && arr.getString(0) == "HTLC") {
                val obj = arr.getJSONObject(1)
                val tags = obj.getJSONArray("tags")
                var refundKeys: List<String> = emptyList()
                for (i in 0 until tags.length()) {
                    val tag = tags.getJSONArray(i)
                    if (tag.length() >= 2 && tag.getString(0) == "refund") {
                        val keys = mutableListOf<String>()
                        for (j in 1 until tag.length()) {
                            keys.add(tag.getString(j))
                        }
                        refundKeys = keys
                        break
                    }
                }
                refundKeys
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract refund keys from secret: ${e.message}")
            emptyList()
        }
    }

    /**
     * Compute P2PK signature for a proof.
     * Message = SHA256(secret) per NUT-11 spec.
     * Note: Only the secret is signed, NOT secret+C.
     *
     * @param secret The HTLC secret JSON string
     * @param proofC The proof's signature point (for logging only)
     * @return 64-byte Schnorr signature as hex, or null on failure
     */
    private fun signP2pkProof(secret: String, proofC: String): String? {
        return try {
            val keypair = walletKeyManager.getOrCreateWalletKeypair()
            val walletPubKey = keypair.publicKeyHex
            // NUT-11: Sign SHA256(secret) only - do NOT include C
            val messageHash = MessageDigest.getInstance("SHA-256")
                .digest(secret.toByteArray())

            val signature = keypair.signSchnorr(messageHash)
            if (signature == null) {
                Log.e(TAG, "signP2pkProof: signSchnorr returned null!")
            }
            signature
        } catch (e: Exception) {
            Log.e(TAG, "signP2pkProof failed: ${e.message}", e)
            null
        }
    }

    /**
     * Encode HTLC proofs as a Cashu token (cashuA format).
     */
    private fun encodeHtlcProofsAsToken(proofs: List<HtlcProof>, mintUrl: String): String {
        val proofsArray = JSONArray()
        proofs.forEach { proof ->
            proofsArray.put(JSONObject().apply {
                put("amount", proof.amount)
                put("id", proof.id)
                put("secret", proof.secret)
                put("C", proof.C)
            })
        }

        val tokenJson = JSONObject().apply {
            put("token", JSONArray().put(JSONObject().apply {
                put("mint", mintUrl)
                put("proofs", proofsArray)
            }))
            put("unit", "sat")
        }

        return "cashuA" + android.util.Base64.encodeToString(
            tokenJson.toString().toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
        )
    }

    /**
     * Parse a Cashu token to extract HTLC proofs.
     * @return Pair of (proofs, mintUrl) or null if invalid
     */
    private fun parseHtlcToken(token: String): Pair<List<HtlcProof>, String>? {
        return try {
            val base64 = when {
                token.startsWith("cashuA") -> token.removePrefix("cashuA")
                token.startsWith("cashuB") -> token.removePrefix("cashuB")
                else -> {
                    Log.e(TAG, "Invalid token prefix: ${token.take(10)}")
                    return null
                }
            }

            val jsonStr = String(android.util.Base64.decode(base64, android.util.Base64.URL_SAFE))
            val json = JSONObject(jsonStr)

            val tokenArray = json.getJSONArray("token")
            if (tokenArray.length() == 0) return null

            val firstMint = tokenArray.getJSONObject(0)
            val mintUrl = firstMint.getString("mint")
            val proofsArray = firstMint.getJSONArray("proofs")

            val proofs = mutableListOf<HtlcProof>()
            for (i in 0 until proofsArray.length()) {
                val p = proofsArray.getJSONObject(i)
                proofs.add(HtlcProof(
                    amount = p.getLong("amount"),
                    id = p.getString("id"),
                    secret = p.getString("secret"),
                    C = p.getString("C")
                ))
            }

            Pair(proofs, mintUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse HTLC token: ${e.message}")
            null
        }
    }

    /**
     * Create HTLC token from provided proofs.
     *
     * This is the main entry point for HTLC creation when you have proofs from NIP-60.
     * It swaps the provided plain proofs for HTLC-locked proofs at the mint.
     *
     * @param inputProofs The plain proofs to convert to HTLC (from NIP-60)
     * @param paymentHash SHA256 hash of the preimage (64-char hex)
     * @param driverPubKey Driver's wallet public key for P2PK condition
     * @param locktime Unix timestamp for refund path (optional)
     * @param riderPubKey Rider's wallet public key for refund (optional)
     * @return Pair of (HTLC token, change proofs) or null on failure
     */
    suspend fun createHtlcTokenFromProofs(
        inputProofs: List<CashuProof>,
        paymentHash: String,
        amountSats: Long,
        driverPubKey: String,
        locktime: Long? = null,
        riderPubKey: String? = null
    ): HtlcLockResult? = withContext(Dispatchers.IO) {
        val mintUrl = currentMintUrl
        if (mintUrl == null) {
            Log.e(TAG, "createHtlcTokenFromProofs: currentMintUrl is null!")
            return@withContext null
        }

        if (inputProofs.isEmpty()) {
            Log.e(TAG, "createHtlcTokenFromProofs: no input proofs provided")
            return@withContext null
        }

        val inputAmount = inputProofs.sumOf { it.amount }
        if (inputAmount < amountSats) {
            Log.e(TAG, "createHtlcTokenFromProofs: insufficient proofs ($inputAmount < $amountSats)")
            return@withContext null
        }

        Log.d(TAG, "=== CREATING HTLC FROM NIP-60 PROOFS ===")
        Log.d(TAG, "Input: ${inputProofs.size} proofs ($inputAmount sats), HTLC amount: $amountSats sats")

        try {
            // Get keyset for outputs
            val keyset = getActiveKeyset()
            if (keyset == null) {
                Log.e(TAG, "createHtlcTokenFromProofs: getActiveKeyset() returned null!")
                return@withContext null
            }

            // Calculate change
            val changeAmount = inputAmount - amountSats

            // Create HTLC outputs
            val htlcAmounts = splitAmount(amountSats)
            val htlcOutputs = htlcAmounts.map { amount ->
                val htlcSecret = createHtlcSecretJson(paymentHash, driverPubKey, locktime, riderPubKey)
                val blindingFactor = CashuCrypto.generateBlindingFactor()
                val Y = CashuCrypto.hashToCurve(htlcSecret) ?: throw Exception("hashToCurve failed")
                val B_ = CashuCrypto.blindMessage(Y, blindingFactor) ?: throw Exception("blindMessage failed")
                Triple(PreMintSecret(amount, htlcSecret, blindingFactor, Y, B_), htlcSecret, true)
            }

            // Create change outputs (plain proofs back to rider, NUT-13 deterministic)
            val changeOutputs = if (changeAmount > 0) {
                val changeAmounts = splitAmount(changeAmount)
                val changePremints = generatePreMintSecrets(changeAmounts, keyset.id)
                changePremints.map { pms ->
                    Triple(pms, pms.secret, false)
                }
            } else emptyList()

            // Build swap request
            val inputsArray = JSONArray()
            inputProofs.forEach { proof -> inputsArray.put(proof.toJson()) }

            val outputsArray = JSONArray()
            (htlcOutputs + changeOutputs).forEach { (pms, _, _) ->
                outputsArray.put(JSONObject().apply {
                    put("amount", pms.amount)
                    put("id", keyset.id)
                    put("B_", pms.B_)
                })
            }

            val swapRequest = JSONObject().apply {
                put("inputs", inputsArray)
                put("outputs", outputsArray)
            }.toString()

            Log.d(TAG, "Swap: ${inputProofs.size} inputs → ${htlcOutputs.size} HTLC + ${changeOutputs.size} change outputs")

            // CRITICAL: Save pending operation BEFORE sending request
            val operationId = java.util.UUID.randomUUID().toString()
            val allOutputPremints = (htlcOutputs + changeOutputs).map { (pms, _, _) ->
                SerializedPreMint(
                    amount = pms.amount,
                    secret = pms.secret,
                    blindingFactor = pms.blindingFactor,
                    Y = pms.Y,
                    B_ = pms.B_
                )
            }
            val pendingOp = PendingBlindedOperation(
                id = operationId,
                operationType = BlindedOperationType.LOCK_HTLC,
                mintUrl = mintUrl,
                quoteId = null,
                inputSecrets = inputProofs.map { it.secret },
                outputPremints = allOutputPremints,
                amountSats = inputAmount, // Total input amount
                createdAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000L), // 24 hours
                status = PendingOperationStatus.STARTED
            )
            walletStorage.savePendingBlindedOp(pendingOp)
            Log.d(TAG, "Saved pending HTLC lock operation: $operationId ($inputAmount sats)")

            // Execute swap
            val request = Request.Builder()
                .url("$mintUrl/v1/swap")
                .post(swapRequest.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val signatures = client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                if (!response.isSuccessful) {
                    Log.e(TAG, "HTLC swap failed: ${response.code} - $responseBody")
                    // Keep pending operation for recovery
                    return@withContext null
                }
                JSONObject(responseBody ?: "{}").getJSONArray("signatures")
            }

            // Unblind HTLC proofs - CRITICAL: Use keyset ID and amount from response
            val htlcProofs = mutableListOf<HtlcProof>()
            val keysetCache = mutableMapOf<String, MintKeyset>()
            keysetCache[keyset.id] = keyset

            for (i in 0 until htlcOutputs.size) {
                val (pms, htlcSecret, _) = htlcOutputs[i]
                val sig = signatures.getJSONObject(i)
                val C_ = sig.getString("C_")
                val responseKeysetId = sig.getString("id")
                // CRITICAL: Use amount from response for key lookup (like CDK does)
                val responseAmount = sig.getLong("amount")

                val sigKeyset = keysetCache.getOrPut(responseKeysetId) {
                    fetchKeyset(responseKeysetId) ?: throw Exception("Failed to fetch keyset $responseKeysetId")
                }

                val mintPubKey = sigKeyset.keys[responseAmount] ?: throw Exception("No key for $responseAmount")
                val C = CashuCrypto.unblindSignature(C_, pms.blindingFactor, mintPubKey)
                    ?: throw Exception("Unblind failed")
                htlcProofs.add(HtlcProof(responseAmount, responseKeysetId, htlcSecret, C))
            }

            // Unblind change proofs - CRITICAL: Use keyset ID and amount from response
            val changeProofs = mutableListOf<CashuProof>()
            for (i in 0 until changeOutputs.size) {
                val sigIndex = htlcOutputs.size + i
                val (pms, secret, _) = changeOutputs[i]
                val sig = signatures.getJSONObject(sigIndex)
                val C_ = sig.getString("C_")
                val responseKeysetId = sig.getString("id")
                // CRITICAL: Use amount from response for key lookup (like CDK does)
                val responseAmount = sig.getLong("amount")

                val sigKeyset = keysetCache.getOrPut(responseKeysetId) {
                    fetchKeyset(responseKeysetId) ?: throw Exception("Failed to fetch keyset $responseKeysetId")
                }

                val mintPubKey = sigKeyset.keys[responseAmount] ?: throw Exception("No key for $responseAmount")
                val C = CashuCrypto.unblindSignature(C_, pms.blindingFactor, mintPubKey)
                    ?: throw Exception("Unblind failed")
                changeProofs.add(CashuProof(responseAmount, responseKeysetId, secret, C))
            }

            // Encode HTLC token
            val htlcToken = encodeHtlcProofsAsToken(htlcProofs, mintUrl)

            Log.d(TAG, "=== HTLC CREATED ===")
            Log.d(TAG, "HTLC: ${htlcProofs.size} proofs ($amountSats sats)")
            Log.d(TAG, "Change: ${changeProofs.size} proofs ($changeAmount sats)")

            // Mark as COMPLETED but do NOT remove - caller must clear after NIP-60 publish
            walletStorage.updateBlindedOpStatus(operationId, PendingOperationStatus.COMPLETED)
            Log.d(TAG, "Marked pending HTLC lock operation COMPLETED: $operationId (caller must clear after NIP-60 persist)")

            HtlcLockResult(htlcToken, changeProofs, operationId)
        } catch (e: Exception) {
            Log.e(TAG, "createHtlcTokenFromProofs failed: ${e.message}", e)
            // Keep pending operation for recovery
            null
        }
    }

    /**
     * Encode plain proofs as a Cashu token.
     */
    private fun encodeProofsAsToken(proofs: List<CashuProof>, mintUrl: String): String {
        val proofsArray = JSONArray()
        proofs.forEach { proof ->
            proofsArray.put(proof.toJson())
        }

        val tokenJson = JSONObject().apply {
            put("token", JSONArray().put(JSONObject().apply {
                put("mint", mintUrl)
                put("proofs", proofsArray)
            }))
            put("unit", "sat")
        }

        return "cashuA" + android.util.Base64.encodeToString(
            tokenJson.toString().toByteArray(),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP
        )
    }

    /**
     * Receive a token by swapping with the mint for fresh proofs.
     * Since cdk-kotlin doesn't expose direct receive, we swap proofs.
     */
    private suspend fun receiveToken(token: String): Boolean {
        val mintUrl = currentMintUrl ?: return false

        return try {
            // Parse the token to get proofs
            val parsed = parseHtlcToken(token) ?: return false
            val proofs = parsed.first.map { hp ->
                CashuProof(hp.amount, hp.id, hp.secret, hp.C)
            }

            // Swap proofs with mint for fresh proofs that we control
            val newProofs = swapProofsWithMint(proofs, mintUrl)
            if (newProofs != null && newProofs.isNotEmpty()) {
                Log.d(TAG, "Token received: ${newProofs.sumOf { it.amount }} sats via swap")
                // Store as lastRecoveredToken for balance tracking
                lastRecoveredToken = token
                lastRecoveredAmount = newProofs.sumOf { it.amount }
                true
            } else {
                Log.e(TAG, "receiveToken: swap failed")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "receiveToken failed: ${e.message}", e)
            false
        }
    }

    /**
     * Receive proofs into cdk-kotlin's tracking by swapping them with the mint.
     * This ensures proofs can be used for melt operations.
     *
     * Note: This does a swap operation which creates fresh proofs. The original
     * proofs are spent and new ones are created that cdk-kotlin will track.
     *
     * @param proofs The proofs to receive
     * @param mintUrl The mint URL
     * @return true if successful
     */
    suspend fun receiveProofsIntoCdk(proofs: List<CashuProof>, mintUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val totalAmount = proofs.sumOf { it.amount }
            Log.d(TAG, "receiveProofsIntoCdk: swapping ${proofs.size} proofs ($totalAmount sats) for cdk tracking")

            // Swap proofs with mint to get fresh proofs
            // This creates new proofs that we control
            val swappedProofs = swapProofsWithMint(proofs, mintUrl)

            if (swappedProofs != null && swappedProofs.isNotEmpty()) {
                val swappedAmount = swappedProofs.sumOf { it.amount }
                Log.d(TAG, "receiveProofsIntoCdk: swapped for $swappedAmount sats (${swappedProofs.size} proofs)")

                // Store swapped proofs so they're available for balance refresh
                lastRecoveredToken = encodeProofsAsToken(swappedProofs, mintUrl)
                lastRecoveredAmount = swappedAmount
                true
            } else {
                Log.e(TAG, "receiveProofsIntoCdk: swap failed")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "receiveProofsIntoCdk failed: ${e.message}", e)
            // Proofs are still valid in NIP-60, just not easily spendable via cdk
            false
        }
    }

    /**
     * Create HTLC secret in NUT-10 format (for backward compatibility).
     */
    fun createHtlcSecret(
        paymentHash: String,
        driverPubKey: String,
        locktime: Long? = null,
        riderPubKey: String? = null
    ): HtlcSecret {
        val nonce = PaymentCrypto.generatePreimage().take(32)
        return HtlcSecret(
            nonce = nonce,
            data = paymentHash,
            pubkeys = listOf(driverPubKey),
            locktime = locktime,
            refundPubkeys = riderPubKey?.let { listOf(it) } ?: emptyList()
        )
    }

    // ========================================
    // NUT-07 Proof State Verification (Extended)
    // Reference: https://github.com/cashubtc/nuts/blob/main/07.md
    // ========================================

    /**
     * Proof state result from NUT-07 check (extended version).
     */
    enum class ProofStateResult {
        UNSPENT,  // Proof is valid and spendable
        PENDING,  // Proof is being processed (in a swap)
        SPENT     // Proof has been spent
    }

    /**
     * Verify proof states with the mint using secrets.
     * Computes Y values internally from the secrets.
     *
     * @param secrets List of proof secrets to check
     * @return Map of secret to ProofStateResult, or null on error
     */
    suspend fun verifyProofStatesBySecret(secrets: List<String>): Map<String, ProofStateResult>? = withContext(Dispatchers.IO) {
        val mintUrl = currentMintUrl ?: return@withContext null

        if (secrets.isEmpty()) return@withContext emptyMap()

        Log.d(TAG, "Verifying ${secrets.size} proof states with mint")

        try {
            // Compute Y values (hash_to_curve of each secret)
            val yValues = secrets.mapNotNull { secret ->
                CashuCrypto.hashToCurve(secret)?.let { secret to it }
            }

            if (yValues.size != secrets.size) {
                Log.e(TAG, "Failed to compute Y for some secrets")
                return@withContext null
            }

            // Use existing checkProofStates method with Y values
            val stateChecks = checkProofStates(yValues.map { it.second })
                ?: return@withContext null

            // Map results back to secrets
            val result = mutableMapOf<String, ProofStateResult>()
            for (i in 0 until stateChecks.size) {
                val originalSecret = yValues[i].first
                val state = stateChecks[i].state
                result[originalSecret] = when (state) {
                    ProofState.UNSPENT -> ProofStateResult.UNSPENT
                    ProofState.PENDING -> ProofStateResult.PENDING
                    ProofState.SPENT -> ProofStateResult.SPENT
                }
            }

            Log.d(TAG, "Proof states: ${result.values.groupingBy { it }.eachCount()}")
            result
        } catch (e: Exception) {
            Log.e(TAG, "verifyProofStatesBySecret failed: ${e.message}", e)
            null
        }
    }

    /**
     * Get verified balance by checking proof states with the mint (NUT-07).
     *
     * This is the authoritative balance - it verifies each proof is still
     * spendable by querying the mint's /v1/checkstate endpoint.
     *
     * @return Verified balance in sats, or null on error
     */
    suspend fun getVerifiedBalance(): Long? = withContext(Dispatchers.IO) {
        // Get local proofs from cdk-kotlin
        val localProofs = getLocalProofs()
        if (localProofs == null) {
            Log.d(TAG, "getVerifiedBalance: No local proofs available, falling back to cdk balance")
            return@withContext getCdkBalance()
        }
        if (localProofs.isEmpty()) {
            Log.d(TAG, "getVerifiedBalance: No local proofs to verify")
            return@withContext 0L
        }

        // Verify proof states with mint using NUT-07
        val secrets = localProofs.map { it.secret }
        val stateMap = verifyProofStatesBySecret(secrets)
        if (stateMap == null) {
            Log.w(TAG, "getVerifiedBalance: NUT-07 verification failed, falling back to cdk balance")
            return@withContext getCdkBalance()
        }

        // Sum only UNSPENT proofs (exclude SPENT and PENDING)
        val verifiedBalance = localProofs
            .filter { stateMap[it.secret] == ProofStateResult.UNSPENT }
            .sumOf { it.amount }

        val cdkBalance = getCdkBalance() ?: 0L
        if (verifiedBalance != cdkBalance) {
            Log.w(TAG, "getVerifiedBalance: NUT-07 verified=$verifiedBalance sats differs from cdk=$cdkBalance sats")
        } else {
            Log.d(TAG, "getVerifiedBalance: NUT-07 verified balance: $verifiedBalance sats")
        }

        verifiedBalance
    }

    /**
     * Verify a list of proofs with a mint and return the verified balance.
     * Used for verifying NIP-60 proofs.
     *
     * @param proofs List of proofs (with secret and amount fields)
     * @param mintUrl Optional mint URL to verify against. If null, uses current connected mint.
     * @return Pair of (verified balance, list of spent secrets) or null on error
     */
    suspend fun verifyProofsBalance(proofs: List<CashuProof>, mintUrl: String? = null): Pair<Long, List<String>>? = withContext(Dispatchers.IO) {
        if (proofs.isEmpty()) {
            Log.d(TAG, "verifyProofsBalance: No proofs to verify")
            return@withContext 0L to emptyList()
        }

        // Use provided mint URL or fall back to current mint
        val targetMint = mintUrl?.trimEnd('/') ?: currentMintUrl
        if (targetMint == null) {
            Log.e(TAG, "verifyProofsBalance: No mint URL available")
            return@withContext null
        }

        Log.d(TAG, "verifyProofsBalance: Verifying ${proofs.size} proofs at $targetMint")

        try {
            // Compute Y values (hash_to_curve of each secret)
            val yValues = proofs.mapNotNull { proof ->
                CashuCrypto.hashToCurve(proof.secret)?.let { proof.secret to it }
            }

            if (yValues.size != proofs.size) {
                Log.e(TAG, "verifyProofsBalance: Failed to compute Y for some secrets")
                return@withContext null
            }

            // Log first few Y values for debugging
            if (yValues.isNotEmpty()) {
                Log.d(TAG, "verifyProofsBalance: First computed Y: ${yValues.first().second}")
                Log.d(TAG, "verifyProofsBalance: First secret: ${yValues.first().first.take(32)}...")
            }

            // Make API call to mint
            val requestBody = JSONObject().apply {
                put("Ys", JSONArray(yValues.map { it.second }))
            }.toString()

            val request = Request.Builder()
                .url("$targetMint/v1/checkstate")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "verifyProofsBalance: Request to $targetMint failed: ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val statesArray = json.getJSONArray("states")

                // Log first Y from mint response for comparison
                if (statesArray.length() > 0) {
                    val firstState = statesArray.getJSONObject(0)
                    Log.d(TAG, "verifyProofsBalance: First mint Y: ${firstState.getString("Y")}")
                    Log.d(TAG, "verifyProofsBalance: First mint state: ${firstState.getString("state")}")
                }

                // Map Y back to secrets
                val yToSecret = yValues.associate { it.second to it.first }

                val spentSecrets = mutableListOf<String>()
                val unspentSecrets = mutableListOf<String>()
                val pendingSecrets = mutableListOf<String>()
                var matchedCount = 0

                for (i in 0 until statesArray.length()) {
                    val stateObj = statesArray.getJSONObject(i)
                    val Y = stateObj.getString("Y")
                    val state = stateObj.getString("state").uppercase()

                    yToSecret[Y]?.let { secret ->
                        matchedCount++
                        when (state) {
                            "SPENT" -> spentSecrets.add(secret)
                            "UNSPENT" -> unspentSecrets.add(secret)
                            "PENDING" -> pendingSecrets.add(secret)
                        }
                    }
                }

                Log.d(TAG, "verifyProofsBalance: matched=$matchedCount/${proofs.size}, UNSPENT=${unspentSecrets.size}, SPENT=${spentSecrets.size}, PENDING=${pendingSecrets.size}")

                // If we couldn't match ANY proofs, our hashToCurve doesn't match the mint's
                // Return null to indicate verification failure (don't trust the proofs)
                if (matchedCount == 0 && proofs.isNotEmpty()) {
                    Log.e(TAG, "verifyProofsBalance: CRITICAL - No Y values matched! hashToCurve mismatch with mint")
                    return@withContext null
                }

                // Calculate verified balance (unspent proofs only)
                val verifiedBalance = proofs
                    .filter { it.secret in unspentSecrets }
                    .sumOf { it.amount }

                // Calculate pending balance for logging (DON'T treat as spent - they may become unspent)
                val pendingBalance = proofs
                    .filter { it.secret in pendingSecrets }
                    .sumOf { it.amount }

                if (pendingSecrets.isNotEmpty()) {
                    Log.w(TAG, "verifyProofsBalance: ${pendingSecrets.size} proofs ($pendingBalance sats) are PENDING - in-progress operation")
                }

                Log.d(TAG, "verifyProofsBalance: $targetMint verified balance = $verifiedBalance sats, spent=${spentSecrets.size}, pending=${pendingSecrets.size}")
                verifiedBalance to spentSecrets
            }
        } catch (e: Exception) {
            Log.e(TAG, "verifyProofsBalance failed for $targetMint: ${e.message}")
            null
        }
    }

    // ========================================
    // API Discovery for HTLC Implementation
    // ========================================

    /**
     * Debug function to discover cdk-kotlin HTLC API.
     * Run this and check Logcat to see what classes/methods are available.
     *
     * @return Map of class names to their available methods/fields
     */
    suspend fun debugCdkKotlinApi(): Map<String, List<String>> = withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, List<String>>()

        val wallet = cdkWallet
        if (wallet == null) {
            results["error"] = listOf("cdkWallet not initialized - connect to mint first")
            Log.e(TAG, "=== CDK-KOTLIN API DISCOVERY: Wallet not initialized ===")
            return@withContext results
        }

        Log.d(TAG, "=== CDK-KOTLIN API DISCOVERY START ===")

        // 1. Inspect Wallet class methods
        try {
            val walletClass = wallet::class.java
            results["Wallet class"] = listOf(walletClass.name)

            // Get all methods
            val allMethods = walletClass.methods.map { method ->
                "${method.name}(${method.parameterTypes.joinToString(", ") { it.simpleName }}): ${method.returnType.simpleName}"
            }.sorted()
            results["Wallet.allMethods"] = allMethods

            // Filter for send/receive methods specifically
            val sendMethods = walletClass.methods
                .filter { it.name.contains("send", ignoreCase = true) }
                .map { "${it.name}(${it.parameterTypes.joinToString(", ") { p -> p.simpleName }}): ${it.returnType.simpleName}" }
            results["Wallet.send*"] = sendMethods.ifEmpty { listOf("No send methods found") }

            val receiveMethods = walletClass.methods
                .filter { it.name.contains("receive", ignoreCase = true) }
                .map { "${it.name}(${it.parameterTypes.joinToString(", ") { p -> p.simpleName }}): ${it.returnType.simpleName}" }
            results["Wallet.receive*"] = receiveMethods.ifEmpty { listOf("No receive methods found") }

            val prepareMethods = walletClass.methods
                .filter { it.name.contains("prepare", ignoreCase = true) }
                .map { "${it.name}(${it.parameterTypes.joinToString(", ") { p -> p.simpleName }}): ${it.returnType.simpleName}" }
            results["Wallet.prepare*"] = prepareMethods.ifEmpty { listOf("No prepare methods found") }
        } catch (e: Exception) {
            results["Wallet inspection error"] = listOf(e.message ?: "Unknown error")
        }

        // 2. Check for HTLC-related classes
        val htlcClasses = listOf(
            "org.cashudevkit.SpendingConditions",
            "org.cashudevkit.HTLCConditions",
            "org.cashudevkit.P2PKConditions",
            "org.cashudevkit.Conditions",
            "org.cashudevkit.ReceiveOptions",
            "org.cashudevkit.SendOptions",
            "org.cashudevkit.Token",
            "org.cashudevkit.Proof",
            "org.cashudevkit.PreMintSecrets",
            "org.cashudevkit.Secret",
            "org.cashudevkit.Witness"
        )

        htlcClasses.forEach { className ->
            try {
                val clazz = Class.forName(className)
                val shortName = className.substringAfterLast(".")

                // Get constructors
                val constructors = clazz.constructors.map { ctor ->
                    "constructor(${ctor.parameterTypes.joinToString(", ") { it.simpleName }})"
                }

                // Get fields
                val fields = clazz.declaredFields.map { field ->
                    "${field.name}: ${field.type.simpleName}"
                }

                // Get methods (excluding Object methods)
                val methods = clazz.declaredMethods
                    .filter { !it.name.startsWith("access$") }
                    .map { "${it.name}(${it.parameterTypes.joinToString(", ") { p -> p.simpleName }}): ${it.returnType.simpleName}" }

                results["$shortName.constructors"] = constructors.ifEmpty { listOf("No constructors") }
                results["$shortName.fields"] = fields.ifEmpty { listOf("No fields") }
                results["$shortName.methods"] = methods.ifEmpty { listOf("No methods") }

                // Check for companion object (Kotlin sealed class variants)
                try {
                    val companionField = clazz.getDeclaredField("Companion")
                    val companionClass = companionField.type
                    val companionMethods = companionClass.methods
                        .filter { it.declaringClass == companionClass }
                        .map { "${it.name}(${it.parameterTypes.joinToString(", ") { p -> p.simpleName }}): ${it.returnType.simpleName}" }
                    if (companionMethods.isNotEmpty()) {
                        results["$shortName.Companion"] = companionMethods
                    }
                } catch (e: NoSuchFieldException) {
                    // No companion object
                }

                // Check for nested classes (sealed class variants)
                val nestedClasses = clazz.declaredClasses.map { it.simpleName }
                if (nestedClasses.isNotEmpty()) {
                    results["$shortName.nestedClasses"] = nestedClasses
                }

            } catch (e: ClassNotFoundException) {
                results[className.substringAfterLast(".")] = listOf("NOT FOUND")
            }
        }

        // 3. Log all results
        Log.d(TAG, "=== CDK-KOTLIN API DISCOVERY RESULTS ===")
        results.forEach { (key, values) ->
            Log.d(TAG, "--- $key ---")
            values.forEach { value ->
                Log.d(TAG, "    $value")
            }
        }
        Log.d(TAG, "=== CDK-KOTLIN API DISCOVERY END ===")

        results
    }

    /**
     * Disconnect from current mint.
     */
    fun disconnect() {
        // Cleanup WebSocket
        try {
            webSocket?.disconnect()
            webSocket = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing WebSocket", e)
        }

        // Cleanup cdk-kotlin resources
        try {
            cdkWallet = null
            cdkDatabase = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing cdk-kotlin wallet", e)
        }
        currentMintUrl = null
        mintCapabilities = null
    }

    /**
     * Get the actual balance from cdk-kotlin proofs stored in the database.
     * Returns null if cdk-kotlin wallet is not initialized.
     */
    suspend fun getCdkBalance(): Long? = withContext(Dispatchers.IO) {
        val wallet = cdkWallet ?: return@withContext null
        try {
            val balance = wallet.totalBalance()
            val sats = extractAmount(balance)
            Log.d(TAG, "cdk-kotlin balance: $sats sats")
            sats
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get cdk-kotlin balance: ${e.message}", e)
            null
        }
    }

    /**
     * Get all local proofs from cdk-kotlin's database.
     *
     * This uses reflection to access the wallet's proofs since the exact API
     * may vary between cdk-kotlin versions.
     *
     * @return List of CashuProof with correct amounts, or null if not available
     */
    suspend fun getLocalProofs(): List<CashuProof>? = withContext(Dispatchers.IO) {
        val wallet = cdkWallet ?: return@withContext null

        try {
            // Try to get proofs via reflection (cdk-kotlin may have getProofs, proofs, or similar)
            val walletClass = wallet::class.java

            // Try common method names for getting proofs
            val methodNames = listOf("getProofs", "proofs", "getAllProofs", "listProofs")

            for (methodName in methodNames) {
                try {
                    val method = walletClass.getMethod(methodName)
                    val result = method.invoke(wallet)

                    if (result is List<*> && result.isNotEmpty()) {
                        Log.d(TAG, "Found ${result.size} proofs via $methodName()")

                        val cashuProofs = result.mapNotNull { proof ->
                            try {
                                convertCdkProofToCashuProof(proof!!)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to convert proof: ${e.message}")
                                null
                            }
                        }

                        if (cashuProofs.isNotEmpty()) {
                            Log.d(TAG, "Converted ${cashuProofs.size} proofs, total: ${cashuProofs.sumOf { it.amount }} sats")
                            return@withContext cashuProofs
                        }
                    }
                } catch (e: NoSuchMethodException) {
                    // Try next method name
                } catch (e: Exception) {
                    Log.w(TAG, "Error calling $methodName: ${e.message}")
                }
            }

            // If direct methods don't work, try getting from database directly
            Log.d(TAG, "Direct methods not found, trying database query...")

            // cdk-kotlin stores proofs in SQLite, try to query directly
            val dbPath = context.getDatabasePath("cashu_proofs.db").absolutePath
            val proofs = queryProofsFromDatabase(dbPath)
            if (proofs != null && proofs.isNotEmpty()) {
                Log.d(TAG, "Got ${proofs.size} proofs from database, total: ${proofs.sumOf { it.amount }} sats")
                return@withContext proofs
            }

            Log.w(TAG, "Could not get proofs from cdk-kotlin")
            null
        } catch (e: Exception) {
            Log.e(TAG, "getLocalProofs failed: ${e.message}", e)
            null
        }
    }

    /**
     * Convert a cdk-kotlin proof object to our CashuProof format using reflection.
     */
    private fun convertCdkProofToCashuProof(cdkProof: Any): CashuProof {
        val proofClass = cdkProof::class.java

        // Extract amount
        val amountField = proofClass.getDeclaredField("amount").apply { isAccessible = true }
        val amountValue = amountField.get(cdkProof)
        val amount = when (amountValue) {
            is Long -> amountValue
            is Int -> amountValue.toLong()
            is Number -> amountValue.toLong()
            else -> extractAmount(amountValue as CdkAmount)
        }

        // Extract keyset ID
        val idField = try {
            proofClass.getDeclaredField("keysetId").apply { isAccessible = true }
        } catch (e: NoSuchFieldException) {
            proofClass.getDeclaredField("id").apply { isAccessible = true }
        }
        val id = idField.get(cdkProof) as String

        // Extract secret
        val secretField = proofClass.getDeclaredField("secret").apply { isAccessible = true }
        val secret = secretField.get(cdkProof) as String

        // Extract C (signature) - may be String or ByteArray
        val cField = try {
            proofClass.getDeclaredField("c").apply { isAccessible = true }
        } catch (e: NoSuchFieldException) {
            proofClass.getDeclaredField("C").apply { isAccessible = true }
        }
        val cValue = cField.get(cdkProof)
        val c = when (cValue) {
            is String -> {
                // Check if it looks like raw bytes encoded as string (not hex)
                if (cValue.length == 33 || (cValue.isNotEmpty() && !cValue.all { it.isLetterOrDigit() })) {
                    // Convert raw bytes to hex
                    cValue.toByteArray(Charsets.ISO_8859_1).joinToString("") { "%02x".format(it) }
                } else {
                    cValue
                }
            }
            is ByteArray -> cValue.joinToString("") { "%02x".format(it) }
            else -> throw IllegalStateException("Unexpected C field type: ${cValue?.javaClass}")
        }

        return CashuProof(amount, id, secret, c)
    }

    /**
     * Query proofs directly from the cdk-kotlin SQLite database.
     */
    private fun queryProofsFromDatabase(dbPath: String): List<CashuProof>? {
        return try {
            val dbFile = java.io.File(dbPath)
            if (!dbFile.exists()) {
                Log.d(TAG, "Database file not found: $dbPath")
                return null
            }

            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                dbPath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )

            // First, discover what tables exist
            val tables = mutableListOf<String>()
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { cursor ->
                while (cursor.moveToNext()) {
                    tables.add(cursor.getString(0))
                }
            }
            Log.d(TAG, "Database tables: $tables")

            // Check the 'key' table - this might have the actual amounts per keyset
            try {
                db.rawQuery("SELECT * FROM key LIMIT 10", null).use { cursor ->
                    Log.d(TAG, "=== KEY TABLE (keyset denominations) ===")
                    Log.d(TAG, "Key table has ${cursor.count} rows")
                    if (cursor.moveToFirst()) {
                        val colCount = cursor.columnCount
                        for (i in 0 until colCount) {
                            Log.d(TAG, "  Column $i: ${cursor.getColumnName(i)}")
                        }
                        cursor.moveToPosition(-1)
                        var rowNum = 0
                        while (cursor.moveToNext() && rowNum < 10) {
                            rowNum++
                            val rowData = StringBuilder("Key row $rowNum: ")
                            for (i in 0 until colCount) {
                                val name = cursor.getColumnName(i)
                                val value = when (cursor.getType(i)) {
                                    android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i).toString()
                                    android.database.Cursor.FIELD_TYPE_STRING -> cursor.getString(i)?.take(20)
                                    android.database.Cursor.FIELD_TYPE_BLOB -> "<blob>"
                                    else -> "null"
                                }
                                rowData.append("$name=$value, ")
                            }
                            Log.d(TAG, rowData.toString())
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Could not read key table: ${e.message}")
            }

            // Check proof distribution by mint_url and amounts
            try {
                db.rawQuery("SELECT mint_url, amount, COUNT(*) as cnt FROM proof GROUP BY mint_url, amount", null).use { cursor ->
                    Log.d(TAG, "=== PROOF DISTRIBUTION ===")
                    while (cursor.moveToNext()) {
                        val mintUrl = cursor.getString(0)
                        val amount = cursor.getLong(1)
                        val count = cursor.getLong(2)
                        Log.d(TAG, "  mint=$mintUrl, amount=$amount, count=$count")
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "Could not get proof distribution: ${e.message}")
            }

            val proofs = mutableListOf<CashuProof>()

            // Try common table names that cdk-kotlin might use
            val tableNames = listOf("proof", "proofs", "cashu_proofs", "wallet_proofs") + tables

            for (tableName in tableNames.distinct()) {
                if (tableName.startsWith("sqlite_") || tableName == "android_metadata") continue

                try {
                    // First, get the table schema
                    val schema = mutableListOf<Pair<String, String>>()
                    db.rawQuery("PRAGMA table_info($tableName)", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val colName = cursor.getString(1)
                            val colType = cursor.getString(2)
                            schema.add(colName to colType)
                        }
                    }
                    Log.d(TAG, "Table '$tableName' schema: $schema")

                    // Check if this looks like a proofs table
                    val colNames = schema.map { it.first.lowercase() }
                    val hasAmount = colNames.any { it.contains("amount") }
                    val hasSecret = colNames.any { it.contains("secret") }

                    if (!hasAmount && !hasSecret) {
                        Log.d(TAG, "Table '$tableName' doesn't look like proofs table, skipping")
                        continue
                    }

                    Log.d(TAG, "=== PROOF TABLE FOUND: '$tableName' ===")
                    Log.d(TAG, "Columns: ${schema.map { "${it.first}(${it.second})" }}")

                    // Query only UNSPENT proofs (to match what cdk-kotlin counts)
                    db.rawQuery("SELECT * FROM $tableName WHERE state = 'UNSPENT'", null).use { cursor ->
                        val columnCount = cursor.columnCount
                        Log.d(TAG, "Table '$tableName' has $columnCount columns, ${cursor.count} rows")

                        // Log column names and types
                        for (i in 0 until columnCount) {
                            val colName = cursor.getColumnName(i)
                            Log.d(TAG, "  Column $i: '$colName'")
                        }

                        if (cursor.moveToFirst()) {
                            // Try to read first row to see data types
                            val rowData = mutableMapOf<String, Any?>()
                            for (i in 0 until columnCount) {
                                val colName = cursor.getColumnName(i)
                                val type = cursor.getType(i)
                                val value: Any? = when (type) {
                                    android.database.Cursor.FIELD_TYPE_NULL -> null
                                    android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                                    android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                                    android.database.Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                                    android.database.Cursor.FIELD_TYPE_BLOB -> {
                                        val blob = cursor.getBlob(i)
                                        // Try to decode as UTF-8 string
                                        try {
                                            String(blob, Charsets.UTF_8)
                                        } catch (e: Exception) {
                                            "<BLOB ${blob.size} bytes>"
                                        }
                                    }
                                    else -> "<unknown type $type>"
                                }
                                rowData[colName] = value
                            }
                            Log.d(TAG, "First row data: $rowData")

                            // Now try to extract proofs
                            cursor.moveToPosition(-1) // Reset cursor
                            var rowNum = 0
                            while (cursor.moveToNext()) {
                                rowNum++
                                try {
                                    val amount = findColumnValue(cursor, listOf("amount")) as? Long ?: continue
                                    val keysetId = findColumnValue(cursor, listOf("keyset_id", "id", "keysetid")) as? String ?: continue
                                    val secret = findColumnValue(cursor, listOf("secret")) as? String ?: continue
                                    val c = findColumnValue(cursor, listOf("c", "C", "signature")) as? String ?: continue

                                    proofs.add(CashuProof(amount, keysetId, secret, c))
                                } catch (e: Exception) {
                                    Log.w(TAG, "Row $rowNum: Failed to extract proof: ${e.message}")
                                }
                            }

                            if (proofs.isNotEmpty()) {
                                Log.d(TAG, "Extracted ${proofs.size} proofs from table '$tableName'")
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Error reading table '$tableName': ${e.message}")
                }
            }

            db.close()
            proofs.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "Database query failed: ${e.message}", e)
            null
        }
    }

    /**
     * Find a column value by trying multiple possible column names.
     */
    private fun findColumnValue(cursor: android.database.Cursor, possibleNames: List<String>): Any? {
        for (name in possibleNames) {
            val idx = cursor.getColumnIndex(name)
            if (idx >= 0) {
                return when (cursor.getType(idx)) {
                    android.database.Cursor.FIELD_TYPE_NULL -> null
                    android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(idx)
                    android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(idx)
                    android.database.Cursor.FIELD_TYPE_STRING -> cursor.getString(idx)
                    android.database.Cursor.FIELD_TYPE_BLOB -> {
                        val blob = cursor.getBlob(idx)
                        // If it's a signature (C field, 33 bytes) or similar, hex-encode it
                        // Otherwise try UTF-8 decoding for text fields
                        if (blob.size == 33 || name.lowercase() in listOf("c", "signature")) {
                            // Hex-encode the blob (for compressed pubkeys/signatures)
                            blob.joinToString("") { "%02x".format(it) }
                        } else {
                            try {
                                String(blob, Charsets.UTF_8)
                            } catch (e: Exception) {
                                // Fallback to hex if UTF-8 fails
                                blob.joinToString("") { "%02x".format(it) }
                            }
                        }
                    }
                    else -> null
                }
            }
        }
        return null
    }

    // ========================================
    // NUT-04 Mint Operations (Deposits)
    // Reference: https://github.com/cashubtc/nuts/blob/main/04.md
    // ========================================

    /**
     * Request a mint quote for depositing via Lightning.
     * Uses cdk-kotlin's native mintQuote() to ensure quote is tracked for later minting.
     *
     * CRITICAL: Must use wallet.mintQuote() NOT HTTP, because wallet.mint() only
     * recognizes quotes created through the wallet's own mintQuote() method.
     *
     * @param amountSats Amount to mint in satoshis
     * @return MintQuote with Lightning invoice, or null on failure
     */
    suspend fun getMintQuote(amountSats: Long): MintQuote? = withContext(Dispatchers.IO) {
        val wallet = cdkWallet
        if (wallet == null) {
            Log.e(TAG, "getMintQuote: cdk-kotlin wallet not initialized")
            return@withContext null
        }

        try {
            Log.d(TAG, "Requesting mint quote for $amountSats sats via cdk-kotlin")

            // Use cdk-kotlin's native mintQuote - this registers the quote internally
            // so wallet.mint() will recognize it later
            val cdkQuote = wallet.mintQuote(
                CdkAmount(amountSats.toULong()),
                null  // No description parameter in cdk-kotlin
            )

            Log.d(TAG, "Got mint quote via cdk-kotlin: ${cdkQuote.id}, invoice: ${cdkQuote.request.take(50)}...")

            // Convert cdk-kotlin quote state to our MintQuoteState type
            // Use string parsing for robustness across cdk-kotlin versions
            val stateStr = cdkQuote.state.toString().uppercase()
            val state = when {
                "PAID" in stateStr -> MintQuoteState.PAID
                "ISSUED" in stateStr -> MintQuoteState.ISSUED
                else -> MintQuoteState.UNPAID  // UNPAID or PENDING
            }

            MintQuote(
                quote = cdkQuote.id,
                request = cdkQuote.request,
                amount = amountSats,
                state = state,
                expiry = cdkQuote.expiry?.toLong() ?: 0L
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get mint quote via cdk-kotlin", e)
            null
        }
    }

    /**
     * Get mint quote from an EXTERNAL mint (not the one cdk-kotlin is connected to).
     * Used for cross-mint bridge: get deposit invoice from driver's mint.
     *
     * NUT-04: POST /v1/mint/quote/bolt11
     * Request: {"amount": <sats>, "unit": "sat"}
     * Response: {"quote": "<id>", "request": "<bolt11>", "state": "UNPAID", "expiry": <unix_ts>}
     *
     * @param amountSats Amount in satoshis
     * @param mintUrl The external mint URL
     * @return MintQuote with Lightning invoice, or null on failure
     */
    suspend fun getMintQuoteAtMint(amountSats: Long, mintUrl: String): MintQuote? = withContext(Dispatchers.IO) {
        try {
            val normalizedUrl = mintUrl.trimEnd('/')
            Log.d(TAG, "Requesting mint quote from external mint: $normalizedUrl for $amountSats sats")

            val requestBody = JSONObject().apply {
                put("amount", amountSats)
                put("unit", "sat")
            }.toString()

            val request = Request.Builder()
                .url("$normalizedUrl/v1/mint/quote/bolt11")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "No error body"
                    Log.e(TAG, "External mint quote failed: ${response.code} - $errorBody")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)

                val state = when (json.optString("state", "UNPAID").uppercase()) {
                    "PAID" -> MintQuoteState.PAID
                    "ISSUED" -> MintQuoteState.ISSUED
                    else -> MintQuoteState.UNPAID
                }

                val quote = MintQuote(
                    quote = json.getString("quote"),
                    request = json.getString("request"),
                    amount = amountSats,
                    state = state,
                    expiry = json.optLong("expiry", 0L)
                )

                Log.d(TAG, "Got mint quote from external mint: ${quote.quote}, invoice: ${quote.request.take(50)}...")
                quote
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get mint quote from external mint: $mintUrl", e)
            null
        }
    }

    /**
     * Parse quote state string to enum, handling various formats.
     */
    private fun parseQuoteState(stateStr: String): MintQuoteState {
        return try {
            MintQuoteState.valueOf(stateStr.uppercase())
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Unknown mint quote state: '$stateStr', treating as UNPAID")
            MintQuoteState.UNPAID
        }
    }

    /**
     * Check mint quote status.
     * NUT-04: GET /v1/mint/quote/bolt11/{quote_id}
     *
     * @param quoteId The quote ID to check
     * @return Updated MintQuote, or null on failure
     */
    suspend fun checkMintQuote(quoteId: String): MintQuote? = withContext(Dispatchers.IO) {
        when (val result = checkMintQuoteWithResult(quoteId)) {
            is MintQuoteResult.Found -> result.quote
            is MintQuoteResult.NotFound -> null
            is MintQuoteResult.Error -> null
        }
    }

    /**
     * Check mint quote status with detailed result.
     * Distinguishes between "quote not found" (404) and network errors.
     *
     * Use this when you need to know WHY a quote check failed:
     * - NotFound = quote definitely doesn't exist at mint (safe to clean up)
     * - Error = network/parsing error (DON'T clean up - quote may exist)
     */
    suspend fun checkMintQuoteWithResult(quoteId: String): MintQuoteResult = withContext(Dispatchers.IO) {
        val mintUrl = currentMintUrl ?: run {
            Log.e(TAG, "checkMintQuote: No mint URL configured")
            return@withContext MintQuoteResult.Error("No mint URL configured")
        }

        try {
            val request = Request.Builder()
                .url("$mintUrl/v1/mint/quote/bolt11/$quoteId")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Check mint quote failed: ${response.code}")
                    // 404 = quote definitively doesn't exist
                    // Other errors (500, timeout, etc.) = don't know
                    return@withContext if (response.code == 404) {
                        Log.d(TAG, "Quote $quoteId not found at mint (404)")
                        MintQuoteResult.NotFound
                    } else {
                        MintQuoteResult.Error("HTTP ${response.code}")
                    }
                }

                val body = response.body?.string()
                if (body == null) {
                    return@withContext MintQuoteResult.Error("Empty response body")
                }

                Log.d(TAG, "Check quote $quoteId response: $body")
                val json = JSONObject(body)

                val stateStr = json.getString("state")
                val state = parseQuoteState(stateStr)
                Log.d(TAG, "Quote $quoteId state: '$stateStr' -> $state (isPaid=${state == MintQuoteState.PAID || state == MintQuoteState.ISSUED})")

                val quote = MintQuote(
                    quote = json.getString("quote"),
                    request = json.optString("request", ""),
                    amount = json.getLong("amount"),
                    state = state,
                    // Handle null expiry - some mints return null instead of a timestamp
                    expiry = if (json.isNull("expiry")) 0L else json.optLong("expiry", 0L)
                )
                MintQuoteResult.Found(quote)
            }
        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "Timeout checking mint quote: ${e.message}")
            MintQuoteResult.Error("Network timeout")
        } catch (e: java.io.IOException) {
            Log.e(TAG, "Network error checking mint quote: ${e.message}")
            MintQuoteResult.Error("Network error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check mint quote: ${e.message}", e)
            MintQuoteResult.Error("Error: ${e.message}")
        }
    }

    /**
     * Mint tokens after Lightning payment confirmed.
     * NUT-04: POST /v1/mint/bolt11
     *
     * Uses cdk-kotlin Wallet to:
     * 1. Generate blinded messages (cryptographic operation)
     * 2. Send to mint and receive blinded signatures
     * 3. Unblind to get valid Cashu proofs
     * 4. Store proofs securely in SQLite database
     *
     * @param quoteId The quote ID
     * @param amountSats The amount to mint
     * @return MintTokensResult with proofs if successful, null on failure
     */
    suspend fun mintTokens(quoteId: String, amountSats: Long): MintTokensResult? = withContext(Dispatchers.IO) {
        val wallet = cdkWallet
        if (wallet == null) {
            Log.e(TAG, "mintTokens: cdk-kotlin wallet not initialized")
            Log.w(TAG, "⚠️ mintTokens($quoteId, $amountSats): No wallet - proofs NOT created")
            return@withContext null
        }

        try {
            Log.d(TAG, "Minting tokens for quote $quoteId, amount $amountSats sats")

            // Mint the tokens using cdk-kotlin - handles all cryptography
            // wallet.mint() requires quoteId, amountSplitTarget, and spendingConditions
            val cdkProofs = wallet.mint(
                quoteId,
                CdkSplitTarget.None,  // Use default split target
                null  // No spending conditions for regular mint
            )

            val proofCount = cdkProofs.size
            if (proofCount > 0) {

                // Convert cdk-kotlin proofs to our CashuProof format
                val cashuProofs = cdkProofs.map { proof ->
                    // Extract amount from cdk-kotlin Amount type
                    // cdk-kotlin 0.14.3 Amount is an inline class wrapping ULong
                    val amount = extractAmount(proof.amount)
                    Log.d(TAG, "Proof amount: $amount sats (keysetId=${proof.keysetId})")

                    CashuProof(
                        amount = amount,
                        id = proof.keysetId,
                        secret = proof.secret,
                        C = proof.c
                    )
                }

                val totalMinted = cashuProofs.sumOf { it.amount }
                Log.d(TAG, "✅ Minted $proofCount proofs totaling $totalMinted sats")

                return@withContext MintTokensResult(
                    success = true,
                    proofs = cashuProofs,
                    totalSats = totalMinted
                )
            }

            Log.e(TAG, "mintTokens returned empty proofs for quote $quoteId")
            null
        } catch (e: Exception) {
            Log.e(TAG, "mintTokens failed for quote $quoteId: ${e.message}", e)
            null
        }
    }

    // ========================================
    // NUT-05 Melt Operations (Withdrawals)
    // Reference: https://github.com/cashubtc/nuts/blob/main/05.md
    // ========================================

    /**
     * Execute melt - burn tokens to pay Lightning invoice.
     * NUT-05: POST /v1/melt/bolt11
     *
     * Uses cdk-kotlin Wallet to:
     * 1. Select proofs summing to required amount + fee
     * 2. Submit proofs to mint for melting
     * 3. Mint pays Lightning invoice and returns preimage
     *
     * @param quoteId The melt quote ID
     * @return MeltResult if successful, null on failure
     */
    suspend fun meltTokens(quoteId: String): MeltResult? = withContext(Dispatchers.IO) {
        val wallet = cdkWallet
        if (wallet == null) {
            Log.e(TAG, "meltTokens: cdk-kotlin wallet not initialized")
            return@withContext null
        }

        try {
            Log.d(TAG, "Melting tokens for quote $quoteId")

            // Execute the melt - cdk-kotlin handles proof selection and payment
            val result = wallet.melt(quoteId)

            // The result has state and preimage properties
            // state is a MeltQuoteState enum (UNPAID, PENDING, PAID)
            val isPaid = result.state.toString().uppercase() == "PAID"
            val preimage = result.preimage
            Log.d(TAG, "✅ Melt complete: state=${result.state}")
            MeltResult(
                paid = isPaid,
                paymentPreimage = preimage
            )
        } catch (e: Exception) {
            Log.e(TAG, "meltTokens failed for quote $quoteId: ${e.message}", e)
            null
        }
    }

    /**
     * Execute melt using provided proofs directly (bypasses cdk-kotlin).
     * Use this when proofs are in NIP-60 but not in cdk-kotlin's local database.
     *
     * NUT-05: POST /v1/melt/bolt11
     *
     * CRITICAL: If inputs exceed quote amount, we MUST provide blinded outputs for change.
     * Without outputs, the mint cannot return change and overpayment is LOST.
     *
     * @param quoteId The melt quote ID (from getMeltQuote)
     * @param proofs The proofs to spend
     * @param amountNeeded Total amount needed (quote amount + fee reserve)
     * @return MeltResult if successful, null on failure
     */
    suspend fun meltWithProofs(
        quoteId: String,
        proofs: List<CashuProof>,
        amountNeeded: Long
    ): MeltResult? = withContext(Dispatchers.IO) {
        val mintUrl = currentMintUrl
        if (mintUrl == null) {
            Log.e(TAG, "meltWithProofs: mint not connected")
            return@withContext null
        }

        val totalInputAmount = proofs.sumOf { it.amount }
        if (totalInputAmount < amountNeeded) {
            Log.e(TAG, "meltWithProofs: insufficient proofs ($totalInputAmount < $amountNeeded)")
            return@withContext null
        }

        val expectedChange = totalInputAmount - amountNeeded

        try {
            Log.d(TAG, "=== MELT WITH PROOFS (NUT-05) ===")
            Log.d(TAG, "Quote: $quoteId")
            Log.d(TAG, "Inputs: ${proofs.size} proofs = $totalInputAmount sats")
            Log.d(TAG, "Amount needed: $amountNeeded sats")
            Log.d(TAG, "Expected change: $expectedChange sats")

            val keysetIds = proofs.map { it.id }.toSet()
            Log.d(TAG, "Proof keyset IDs: $keysetIds, count=${proofs.size}")

            // Fetch active keyset to compare
            val activeKeyset = getActiveKeyset()
            Log.d(TAG, "Mint's active keyset ID: ${activeKeyset?.id ?: "UNAVAILABLE"}")
            if (activeKeyset != null && !keysetIds.contains(activeKeyset.id)) {
                Log.w(TAG, "⚠️ WARNING: Proof keyset IDs don't match mint's active keyset!")
            }

            // Build inputs array
            val inputsArray = JSONArray()
            proofs.forEach { proof -> inputsArray.put(proof.toJson()) }

            // NUT-05 CRITICAL: If we have change, we MUST provide blinded outputs
            // Without outputs, the mint cannot give change back!
            val changePremints = mutableListOf<PreMintSecret>()
            var outputsArray: JSONArray? = null

            if (expectedChange > 0) {
                Log.d(TAG, "Creating blinded outputs for $expectedChange sats change")

                // Get keyset for creating outputs
                val keyset = getActiveKeyset()
                if (keyset == null) {
                    Log.e(TAG, "CRITICAL: Cannot get keyset for change outputs - change will be LOST!")
                    // Continue anyway - better to lose change than fail entire withdrawal
                } else {
                    // Split change into denominations (powers of 2)
                    val changeAmounts = splitAmount(expectedChange)
                    Log.d(TAG, "Change denominations: $changeAmounts")

                    // Create blinded messages (NUT-13 deterministic)
                    try {
                        changePremints.addAll(generatePreMintSecrets(changeAmounts, keyset.id))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to generate change PreMintSecrets: ${e.message}")
                    }

                    if (changePremints.isNotEmpty()) {
                        outputsArray = JSONArray()
                        changePremints.forEach { pms ->
                            outputsArray!!.put(JSONObject().apply {
                                put("amount", pms.amount)
                                put("id", keyset.id)
                                put("B_", pms.B_)
                            })
                        }
                        Log.d(TAG, "Created ${changePremints.size} blinded outputs for change")
                    }
                }
            }

            // Build melt request
            val meltRequest = JSONObject().apply {
                put("quote", quoteId)
                put("inputs", inputsArray)
                if (outputsArray != null) {
                    put("outputs", outputsArray)
                }
            }.toString()

            Log.d(TAG, "Melt request to $mintUrl/v1/melt/bolt11 (outputs=${outputsArray?.length() ?: 0})")

            // CRITICAL: Save pending operation BEFORE sending request
            // If we crash after mint processes but before we unblind, we can recover
            val operationId = java.util.UUID.randomUUID().toString()
            if (changePremints.isNotEmpty()) {
                val pendingOp = PendingBlindedOperation(
                    id = operationId,
                    operationType = BlindedOperationType.MELT,
                    mintUrl = mintUrl,
                    quoteId = quoteId,
                    inputSecrets = proofs.map { it.secret },
                    outputPremints = changePremints.map { pms ->
                        SerializedPreMint(
                            amount = pms.amount,
                            secret = pms.secret,
                            blindingFactor = pms.blindingFactor,
                            Y = pms.Y,
                            B_ = pms.B_
                        )
                    },
                    amountSats = expectedChange,
                    createdAt = System.currentTimeMillis(),
                    expiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000L), // 24 hours
                    status = PendingOperationStatus.STARTED
                )
                walletStorage.savePendingBlindedOp(pendingOp)
                Log.d(TAG, "Saved pending melt operation: $operationId ($expectedChange sats change)")
            }

            val request = Request.Builder()
                .url("$mintUrl/v1/melt/bolt11")
                .post(meltRequest.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            // Handle non-successful responses - check for pending states
            if (!response.isSuccessful) {
                Log.w(TAG, "Melt response: ${response.code} - $responseBody")

                // Parse error response to check for pending states
                try {
                    val errorJson = JSONObject(responseBody ?: "{}")
                    val errorCode = errorJson.optInt("code", 0)
                    val errorDetail = errorJson.optString("detail", "")

                    // NUT-05 error codes:
                    // 20005 = "Quote pending" - Lightning payment in progress
                    // 11002 = "Token pending" - Proofs locked for in-progress operation
                    // 11000 = "Proofs are pending" - Same as above
                    if (errorCode == 20005 || errorCode == 11002 || errorCode == 11000 ||
                        errorDetail.contains("pending", ignoreCase = true)) {
                        Log.d(TAG, "Melt is PENDING (code=$errorCode) - waiting for completion...")

                        // Mark operation as pending
                        if (changePremints.isNotEmpty()) {
                            walletStorage.updateBlindedOpStatus(operationId, PendingOperationStatus.PENDING)
                        }

                        // NUT-17: Use WebSocket if available, fall back to polling
                        val quoteStatus = waitForMeltQuoteState(quoteId, 60_000L)

                        if (quoteStatus != null) {
                            Log.d(TAG, "Melt quote status: ${quoteStatus.state}")
                            when (quoteStatus.state) {
                                MeltQuoteState.PAID -> {
                                    Log.d(TAG, "✅ Melt completed successfully!")
                                    // CRITICAL: Change proofs are LOST in this path because
                                    // GET /melt/quote doesn't return signed change outputs.
                                    // Keep pending operation for potential future recovery.
                                    if (changePremints.isNotEmpty()) {
                                        Log.w(TAG, "⚠️ CHANGE LOST: $expectedChange sats change cannot be recovered (no C_ values)")
                                        // Don't remove pending op - keep premints in case spec adds recovery
                                    }
                                    return@withContext MeltResult(
                                        paid = true,
                                        paymentPreimage = quoteStatus.paymentPreimage,
                                        change = emptyList() // Change lost - can't unblind without C_ values
                                    )
                                }
                                MeltQuoteState.UNPAID -> {
                                    // Lightning payment failed - proofs should be released
                                    Log.w(TAG, "Melt failed - Lightning payment unsuccessful")
                                    // Mark as failed - input proofs should be UNSPENT
                                    if (changePremints.isNotEmpty()) {
                                        walletStorage.updateBlindedOpStatus(operationId, PendingOperationStatus.FAILED)
                                    }
                                    return@withContext null
                                }
                                MeltQuoteState.PENDING -> {
                                    // Timed out waiting - keep operation as PENDING for later recovery
                                    Log.w(TAG, "Melt still pending after ~60s - payment may complete later")
                                    return@withContext MeltResult(paid = false, paymentPreimage = null, change = emptyList(), isPending = true)
                                }
                            }
                        }

                        // Timed out or error - keep operation as PENDING for later recovery
                        Log.w(TAG, "Melt quote check timed out - payment may complete later")
                        return@withContext MeltResult(paid = false, paymentPreimage = null, change = emptyList(), isPending = true)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse melt error response: ${e.message}")
                }

                // Melt failed - keep pending operation for recovery check
                // (recovery will verify if inputs are SPENT or UNSPENT)
                Log.e(TAG, "Melt failed: ${response.code} - $responseBody")
                return@withContext null
            }

            val json = JSONObject(responseBody ?: "{}")
            val paid = json.optBoolean("paid", false)
            val preimage = if (json.has("payment_preimage")) json.getString("payment_preimage") else null

            // Parse and unblind change signatures (NUT-05/NUT-08)
            // The mint returns blinded signatures (C_) that we must unblind
            // CRITICAL: Use the keyset ID from the response, NOT from getActiveKeyset()!
            // The mint may use a different keyset than the "active" one.
            val changeProofs = mutableListOf<CashuProof>()
            if (json.has("change") && changePremints.isNotEmpty()) {
                val changeArray = json.getJSONArray("change")
                Log.d(TAG, "Received ${changeArray.length()} change signatures to unblind")

                // Cache keysets we've fetched to avoid repeated requests
                val keysetCache = mutableMapOf<String, MintKeyset?>()

                for (i in 0 until minOf(changeArray.length(), changePremints.size)) {
                    try {
                        val sig = changeArray.getJSONObject(i)
                        val C_ = sig.getString("C_")
                        // CRITICAL FIX: Read keyset ID from response, not from getActiveKeyset()
                        val responseKeysetId = sig.getString("id")
                        // CRITICAL FIX: Read amount from response for key lookup (like CDK does)
                        val responseAmount = sig.getLong("amount")
                        val pms = changePremints[i]

                        // Get keyset for this specific keyset ID (may be different from active)
                        val keyset = if (keysetCache.containsKey(responseKeysetId)) {
                            keysetCache[responseKeysetId]
                        } else {
                            fetchKeyset(responseKeysetId).also { keysetCache[responseKeysetId] = it }
                        }

                        if (keyset == null) {
                            Log.w(TAG, "Skipping change $i - keyset $responseKeysetId unavailable")
                            continue
                        }

                        // Get mint's public key for this amount from RESPONSE (not premint!)
                        val mintPubKey = keyset.keys[responseAmount]
                        if (mintPubKey == null) {
                            Log.w(TAG, "No keyset key for amount $responseAmount in keyset $responseKeysetId")
                            continue
                        }

                        // Unblind: C = C_ - r*K
                        val C = CashuCrypto.unblindSignature(C_, pms.blindingFactor, mintPubKey)
                        if (C == null) {
                            Log.w(TAG, "Failed to unblind change signature $i")
                            continue
                        }

                        // Use amount AND keyset ID from the response (CRITICAL!)
                        changeProofs.add(CashuProof(
                            amount = responseAmount,
                            id = responseKeysetId,
                            secret = pms.secret,
                            C = C
                        ))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to process change signature $i: ${e.message}")
                    }
                }

                if (changeProofs.isNotEmpty()) {
                    val recoveredChange = changeProofs.sumOf { it.amount }
                    Log.d(TAG, "✅ Unblinded ${changeProofs.size} change proofs ($recoveredChange sats)")
                }
            }

            val finalChangeAmount = changeProofs.sumOf { it.amount }
            if (expectedChange > 0 && finalChangeAmount < expectedChange) {
                Log.w(TAG, "⚠️ Change loss: expected $expectedChange sats, recovered $finalChangeAmount sats")
            }

            Log.d(TAG, "✅ Melt complete: paid=$paid, change=${changeProofs.size} proofs ($finalChangeAmount sats)")

            // Mark as completed but do NOT remove yet - caller must persist proofs first!
            // This prevents fund loss if crash occurs before NIP-60 publish.
            val pendingId = if (changePremints.isNotEmpty()) {
                walletStorage.updateBlindedOpStatus(operationId, PendingOperationStatus.COMPLETED)
                Log.d(TAG, "Pending melt op $operationId ready - caller must clear after NIP-60 publish")
                operationId
            } else null

            MeltResult(
                paid = paid,
                paymentPreimage = preimage,
                change = changeProofs,
                pendingOpId = pendingId
            )
        } catch (e: Exception) {
            Log.e(TAG, "meltWithProofs failed: ${e.message}", e)
            // Keep pending operation for recovery - don't remove it
            null
        }
    }

    // ========================================
    // Deposit Recovery (for failed mints)
    // ========================================

    // Cached keysets from mint
    private var mintKeysets: Map<String, MintKeyset>? = null

    /**
     * Fetch mint keysets (public keys for each denomination).
     * Required for unblinding signatures during recovery.
     *
     * NUT-01/02: GET /v1/keysets and /v1/keys/{keyset_id}
     */
    suspend fun getActiveKeyset(): MintKeyset? = withContext(Dispatchers.IO) {
        val mintUrl = currentMintUrl ?: return@withContext null

        try {
            // First get list of keysets
            val keysetsRequest = Request.Builder()
                .url("$mintUrl/v1/keysets")
                .get()
                .build()

            val activeKeysetId = client.newCall(keysetsRequest).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val keysets = json.getJSONArray("keysets")

                // Find active keyset for sat unit
                for (i in 0 until keysets.length()) {
                    val ks = keysets.getJSONObject(i)
                    if (ks.optBoolean("active", true) && ks.optString("unit", "sat") == "sat") {
                        return@use ks.getString("id")
                    }
                }
                null
            } ?: return@withContext null

            // Fetch keys for active keyset
            val keysRequest = Request.Builder()
                .url("$mintUrl/v1/keys/$activeKeysetId")
                .get()
                .build()

            client.newCall(keysRequest).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val keysetsArray = json.getJSONArray("keysets")
                if (keysetsArray.length() == 0) return@withContext null

                val keysetJson = keysetsArray.getJSONObject(0)
                val keysJson = keysetJson.getJSONObject("keys")

                val keys = mutableMapOf<Long, String>()
                val keyIterator = keysJson.keys()
                while (keyIterator.hasNext()) {
                    val amountStr = keyIterator.next()
                    val pubkey = keysJson.getString(amountStr)
                    // Skip amounts that overflow Long (e.g., 2^63 used by some mints)
                    val amount = amountStr.toLongOrNull()
                    if (amount != null && amount > 0) {
                        keys[amount] = pubkey
                    }
                }

                MintKeyset(
                    id = activeKeysetId,
                    unit = "sat",
                    keys = keys
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch keyset: ${e.message}", e)
            null
        }
    }

    /**
     * Fetch a specific keyset by its ID.
     * Used when we need to unblind signatures using a specific keyset,
     * not necessarily the current "active" one.
     *
     * @param keysetId The keyset ID to fetch
     * @return MintKeyset if successful, null otherwise
     */
    internal suspend fun fetchKeyset(keysetId: String): MintKeyset? = withContext(Dispatchers.IO) {
        val mintUrl = currentMintUrl ?: return@withContext null

        try {
            Log.d(TAG, "Fetching keyset: $keysetId")

            val keysRequest = Request.Builder()
                .url("$mintUrl/v1/keys/$keysetId")
                .get()
                .build()

            client.newCall(keysRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to fetch keyset $keysetId: ${response.code}")
                    return@withContext null
                }

                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val keysetsArray = json.getJSONArray("keysets")
                if (keysetsArray.length() == 0) return@withContext null

                val keysetJson = keysetsArray.getJSONObject(0)
                val keysJson = keysetJson.getJSONObject("keys")

                val keys = mutableMapOf<Long, String>()
                val keyIterator = keysJson.keys()
                while (keyIterator.hasNext()) {
                    val amountStr = keyIterator.next()
                    val pubkey = keysJson.getString(amountStr)
                    val amount = amountStr.toLongOrNull()
                    if (amount != null && amount > 0) {
                        keys[amount] = pubkey
                    }
                }

                Log.d(TAG, "Fetched keyset $keysetId with ${keys.size} keys")
                MintKeyset(
                    id = keysetId,
                    unit = "sat",
                    keys = keys
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch keyset $keysetId: ${e.message}", e)
            null
        }
    }

    /**
     * Recover a deposit that failed to mint.
     *
     * This bypasses cdk-kotlin's quote tracking and mints directly via HTTP.
     * Use this when wallet.mint() fails with "Unknown quote" but the Lightning
     * payment was confirmed.
     *
     * @param quoteId The quote ID from the original deposit
     * @param amountSats The amount that was paid
     * @return List of recovered CashuProofs if successful, null otherwise
     */
    suspend fun recoverDeposit(quoteId: String, amountSats: Long): List<CashuProof>? = withContext(Dispatchers.IO) {
        val mintUrl = currentMintUrl ?: run {
            Log.e(TAG, "recoverDeposit: mint not connected")
            return@withContext null
        }

        Log.d(TAG, "=== DEPOSIT RECOVERY START ===")
        Log.d(TAG, "Quote ID: $quoteId")
        Log.d(TAG, "Amount: $amountSats sats")

        try {
            // Step 1: Verify quote is PAID
            val quote = checkMintQuote(quoteId)
            if (quote == null) {
                Log.e(TAG, "recoverDeposit: quote not found on mint")
                return@withContext null
            }

            Log.d(TAG, "Quote state: ${quote.state}")
            if (quote.state != MintQuoteState.PAID) {
                Log.e(TAG, "recoverDeposit: quote is not PAID (state=${quote.state})")
                return@withContext null
            }

            // Step 2: Get mint keyset for unblinding
            val keyset = getActiveKeyset()
            if (keyset == null) {
                Log.e(TAG, "recoverDeposit: failed to get mint keyset")
                return@withContext null
            }
            Log.d(TAG, "Using keyset: ${keyset.id}")

            // Step 3: Split amount into powers of 2 (Cashu denominations)
            val amounts = splitAmount(amountSats)
            Log.d(TAG, "Split into ${amounts.size} outputs: $amounts")

            // Step 4: Generate pre-mint secrets (NUT-13 deterministic if seed available)
            val preMintSecrets = generatePreMintSecrets(amounts, keyset.id)

            // Step 5: Create blinded outputs for mint request
            val outputsArray = JSONArray()
            preMintSecrets.forEach { pms ->
                outputsArray.put(JSONObject().apply {
                    put("amount", pms.amount)
                    put("id", keyset.id)
                    put("B_", pms.B_)
                })
            }

            // Step 6: POST /v1/mint/bolt11
            val mintRequestBody = JSONObject().apply {
                put("quote", quoteId)
                put("outputs", outputsArray)
            }.toString()

            // CRITICAL: Save pending operation BEFORE sending request
            val operationId = java.util.UUID.randomUUID().toString()
            val pendingOp = PendingBlindedOperation(
                id = operationId,
                operationType = BlindedOperationType.MINT,
                mintUrl = mintUrl,
                quoteId = quoteId,
                inputSecrets = emptyList(), // No inputs for mint operation
                outputPremints = preMintSecrets.map { pms ->
                    SerializedPreMint(
                        amount = pms.amount,
                        secret = pms.secret,
                        blindingFactor = pms.blindingFactor,
                        Y = pms.Y,
                        B_ = pms.B_
                    )
                },
                amountSats = amountSats,
                createdAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000L), // 24 hours
                status = PendingOperationStatus.STARTED
            )
            walletStorage.savePendingBlindedOp(pendingOp)
            Log.d(TAG, "Saved pending mint operation: $operationId ($amountSats sats)")

            val mintRequest = Request.Builder()
                .url("$mintUrl/v1/mint/bolt11")
                .post(mintRequestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val blindedSignatures = client.newCall(mintRequest).execute().use { response ->
                val responseBody = response.body?.string()
                Log.d(TAG, "Mint response: ${response.code} - $responseBody")

                if (!response.isSuccessful) {
                    Log.e(TAG, "recoverDeposit: mint request failed: ${response.code}")
                    // Keep pending operation for recovery
                    return@withContext null
                }

                val json = JSONObject(responseBody ?: "{}")
                json.getJSONArray("signatures")
            }

            // Step 7: Unblind signatures to get proofs - CRITICAL: Use keyset ID from response
            // Use minOf to handle edge case where mint returns different count than expected
            val proofs = mutableListOf<CashuProof>()
            val sigCount = minOf(blindedSignatures.length(), preMintSecrets.size)
            if (blindedSignatures.length() != preMintSecrets.size) {
                Log.w(TAG, "Signature count mismatch: got ${blindedSignatures.length()}, expected ${preMintSecrets.size}")
            }

            val keysetCache = mutableMapOf<String, MintKeyset>()
            keysetCache[keyset.id] = keyset

            for (i in 0 until sigCount) {
                val sig = blindedSignatures.getJSONObject(i)
                val pms = preMintSecrets[i]

                val C_ = sig.getString("C_")
                val responseKeysetId = sig.getString("id")
                // CRITICAL: Use amount from response for key lookup (like CDK does)
                val responseAmount = sig.getLong("amount")

                val sigKeyset = keysetCache.getOrPut(responseKeysetId) {
                    fetchKeyset(responseKeysetId) ?: throw Exception("Failed to fetch keyset $responseKeysetId")
                }

                val mintPubKey = sigKeyset.keys[responseAmount]
                    ?: throw Exception("No key for amount $responseAmount")

                val C = CashuCrypto.unblindSignature(C_, pms.blindingFactor, mintPubKey)
                    ?: throw Exception("Failed to unblind signature")

                proofs.add(CashuProof(
                    amount = responseAmount,
                    id = responseKeysetId,
                    secret = pms.secret,
                    C = C
                ))
            }

            Log.d(TAG, "Unblinded ${proofs.size} proofs")

            // Step 8: Store proofs (best effort, NIP-60 will be primary storage)
            storeRecoveredProofs(proofs, mintUrl)

            val totalAmount = proofs.sumOf { it.amount }
            Log.d(TAG, "✅ DEPOSIT RECOVERY SUCCESSFUL: $totalAmount sats (${proofs.size} proofs)")

            // SUCCESS: Remove pending operation
            walletStorage.updateBlindedOpStatus(operationId, PendingOperationStatus.COMPLETED)
            walletStorage.removePendingBlindedOp(operationId)
            Log.d(TAG, "Removed pending mint operation: $operationId")

            proofs
        } catch (e: Exception) {
            Log.e(TAG, "recoverDeposit failed: ${e.message}", e)
            // Keep pending operation for recovery
            null
        }
    }

    /**
     * Store recovered proofs.
     *
     * Creates a Cashu token from the proofs that can be:
     * 1. Stored via cdk-kotlin wallet.receive() if API is compatible
     * 2. Saved to NIP-60 for backup
     * 3. Used in an external Cashu wallet
     *
     * Even if cdk-kotlin storage fails, the proofs ARE valid and funds are recovered.
     */
    internal suspend fun storeRecoveredProofs(proofs: List<CashuProof>, mintUrl: String): Boolean {
        try {
            // Encode proofs as Cashu token (v3 format)
            val proofsArray = JSONArray()
            proofs.forEach { proof ->
                proofsArray.put(proof.toJson())
            }

            val tokenJson = JSONObject().apply {
                put("token", JSONArray().put(JSONObject().apply {
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
            Log.d(TAG, "Recovery token created ($totalAmount sats)")

            // Store token for later retrieval/backup
            // TODO: Implement NIP-60 storage here
            lastRecoveredToken = tokenStr
            lastRecoveredAmount = totalAmount

            // NOTE: Do NOT call swapProofsWithMint() here!
            // The proofs are valid and will be published to NIP-60 by the caller.
            // Swapping would SPEND the original proofs at the mint, causing them
            // to be deleted when verification runs. The swap proofs would never
            // reach NIP-60, resulting in fund loss.

            // Proofs ARE valid - they will be published to NIP-60 by caller
            Log.d(TAG, "✅ Funds recovered ($totalAmount sats) - token saved for manual import")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process recovered proofs: ${e.message}", e)
            return false
        }
    }

    // Temporary storage for last recovered token (for UI display)
    var lastRecoveredToken: String? = null
        private set
    var lastRecoveredAmount: Long = 0
        private set

    /**
     * Result of swapping proofs to an exact amount.
     * Contains both the exact proofs for the target amount and remaining proofs (change).
     */
    data class SwapToExactResult(
        val exactProofs: List<CashuProof>,      // Proofs totaling exactly the target amount
        val remainingProofs: List<CashuProof>,  // Change proofs (already received from swap)
        val pendingOpId: String? = null         // Pending operation ID for cleanup
    )

    /**
     * Swap proofs to get an exact amount, returning both exact and remaining proofs.
     *
     * This is used before melt operations to eliminate change loss risk:
     * - Swap returns ALL outputs immediately (unlike melt which can lose change if pending)
     * - After swap, melt with exact amount = no change needed = no change to lose
     *
     * @param proofs Input proofs (must total >= exactAmount)
     * @param exactAmount The exact amount needed for subsequent melt
     * @param mintUrl The mint URL for the swap
     * @return SwapToExactResult with exact and remaining proofs, or null on failure
     */
    suspend fun swapToExactAmount(
        proofs: List<CashuProof>,
        exactAmount: Long,
        mintUrl: String
    ): SwapToExactResult? = withContext(Dispatchers.IO) {
        try {
            val keyset = getActiveKeyset() ?: run {
                Log.e(TAG, "swapToExactAmount: no active keyset")
                return@withContext null
            }

            val totalAmount = proofs.sumOf { it.amount }
            if (totalAmount < exactAmount) {
                Log.e(TAG, "swapToExactAmount: insufficient funds ($totalAmount < $exactAmount)")
                return@withContext null
            }

            if (totalAmount == exactAmount) {
                // Already exact, no swap needed
                Log.d(TAG, "swapToExactAmount: already exact amount, no swap needed")
                return@withContext SwapToExactResult(
                    exactProofs = proofs,
                    remainingProofs = emptyList()
                )
            }

            val remainingAmount = totalAmount - exactAmount
            Log.d(TAG, "swapToExactAmount: $totalAmount -> $exactAmount exact + $remainingAmount remaining")

            // Create outputs for exact amount + remaining (NUT-13 deterministic)
            val exactAmounts = splitAmount(exactAmount)
            val remainingAmounts = splitAmount(remainingAmount)
            val allAmounts = exactAmounts + remainingAmounts

            val preMintSecrets = generatePreMintSecrets(allAmounts, keyset.id)

            // Build swap request
            val inputsArray = JSONArray()
            proofs.forEach { proof -> inputsArray.put(proof.toJson()) }

            val outputsArray = JSONArray()
            preMintSecrets.forEach { pms ->
                outputsArray.put(JSONObject().apply {
                    put("amount", pms.amount)
                    put("id", keyset.id)
                    put("B_", pms.B_)
                })
            }

            val swapRequest = JSONObject().apply {
                put("inputs", inputsArray)
                put("outputs", outputsArray)
            }.toString()

            // CRITICAL: Save pending operation BEFORE sending request
            val operationId = java.util.UUID.randomUUID().toString()
            val pendingOp = PendingBlindedOperation(
                id = operationId,
                operationType = BlindedOperationType.SWAP,
                mintUrl = mintUrl,
                quoteId = null,
                inputSecrets = proofs.map { it.secret },
                outputPremints = preMintSecrets.map { pms ->
                    SerializedPreMint(
                        amount = pms.amount,
                        secret = pms.secret,
                        blindingFactor = pms.blindingFactor,
                        Y = pms.Y,
                        B_ = pms.B_
                    )
                },
                amountSats = totalAmount,
                createdAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000L), // 24 hours
                status = PendingOperationStatus.STARTED
            )
            walletStorage.savePendingBlindedOp(pendingOp)
            Log.d(TAG, "Saved pending swap-to-exact operation: $operationId")

            val request = Request.Builder()
                .url("$mintUrl/v1/swap")
                .post(swapRequest.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val signatures = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Swap-to-exact failed: ${response.code}")
                    // Keep pending operation for recovery
                    return@withContext null
                }
                val body = response.body?.string() ?: return@withContext null
                JSONObject(body).getJSONArray("signatures")
            }

            // Unblind signatures
            val allNewProofs = mutableListOf<CashuProof>()
            val keysetCache = mutableMapOf<String, MintKeyset>()
            keysetCache[keyset.id] = keyset

            for (i in 0 until signatures.length()) {
                val sig = signatures.getJSONObject(i)
                val pms = preMintSecrets[i]
                val C_ = sig.getString("C_")
                val responseKeysetId = sig.getString("id")
                val responseAmount = sig.getLong("amount")

                val sigKeyset = keysetCache.getOrPut(responseKeysetId) {
                    fetchKeyset(responseKeysetId) ?: continue
                }

                val mintPubKey = sigKeyset.keys[responseAmount] ?: continue
                val C = CashuCrypto.unblindSignature(C_, pms.blindingFactor, mintPubKey) ?: continue
                allNewProofs.add(CashuProof(responseAmount, responseKeysetId, pms.secret, C))
            }

            // Split proofs into exact and remaining
            val exactProofs = allNewProofs.take(exactAmounts.size)
            val remainingProofs = allNewProofs.drop(exactAmounts.size)

            val exactTotal = exactProofs.sumOf { it.amount }
            val remainingTotal = remainingProofs.sumOf { it.amount }

            Log.d(TAG, "Swap-to-exact complete: $exactTotal exact + $remainingTotal remaining")

            if (exactTotal != exactAmount) {
                Log.e(TAG, "CRITICAL: Exact proofs don't match expected amount! $exactTotal != $exactAmount")
                // Keep pending operation
                return@withContext null
            }

            // SUCCESS: Update pending operation status (caller will remove after NIP-60 publish)
            walletStorage.updateBlindedOpStatus(operationId, PendingOperationStatus.COMPLETED)
            Log.d(TAG, "Swap-to-exact operation completed: $operationId")

            return@withContext SwapToExactResult(
                exactProofs = exactProofs,
                remainingProofs = remainingProofs,
                pendingOpId = operationId
            )
        } catch (e: Exception) {
            Log.e(TAG, "Swap-to-exact failed: ${e.message}", e)
            return@withContext null
        }
    }

    /**
     * Swap recovered proofs with mint to get fresh proofs.
     * This allows cdk-kotlin to track the new proofs.
     */
    private suspend fun swapProofsWithMint(proofs: List<CashuProof>, mintUrl: String): List<CashuProof>? {
        // POST /v1/swap with inputs (old proofs) and outputs (new blinded messages)
        // The mint verifies inputs and returns blinded signatures for outputs
        // We unblind to get new proofs that we can track

        try {
            val keyset = getActiveKeyset() ?: return null
            val totalAmount = proofs.sumOf { it.amount }

            // Create new outputs for the swap (NUT-13 deterministic)
            val amounts = splitAmount(totalAmount)
            val preMintSecrets = generatePreMintSecrets(amounts, keyset.id)

            // Build swap request
            val inputsArray = JSONArray()
            proofs.forEach { proof ->
                inputsArray.put(proof.toJson())
            }

            val outputsArray = JSONArray()
            preMintSecrets.forEach { pms ->
                outputsArray.put(JSONObject().apply {
                    put("amount", pms.amount)
                    put("id", keyset.id)
                    put("B_", pms.B_)
                })
            }

            val swapRequest = JSONObject().apply {
                put("inputs", inputsArray)
                put("outputs", outputsArray)
            }.toString()

            // CRITICAL: Save pending operation BEFORE sending request
            val operationId = java.util.UUID.randomUUID().toString()
            val pendingOp = PendingBlindedOperation(
                id = operationId,
                operationType = BlindedOperationType.SWAP,
                mintUrl = mintUrl,
                quoteId = null,
                inputSecrets = proofs.map { it.secret },
                outputPremints = preMintSecrets.map { pms ->
                    SerializedPreMint(
                        amount = pms.amount,
                        secret = pms.secret,
                        blindingFactor = pms.blindingFactor,
                        Y = pms.Y,
                        B_ = pms.B_
                    )
                },
                amountSats = totalAmount,
                createdAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + (24 * 60 * 60 * 1000L), // 24 hours
                status = PendingOperationStatus.STARTED
            )
            walletStorage.savePendingBlindedOp(pendingOp)
            Log.d(TAG, "Saved pending swap operation: $operationId ($totalAmount sats)")

            val request = Request.Builder()
                .url("$mintUrl/v1/swap")
                .post(swapRequest.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val signatures = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "Swap failed: ${response.code}")
                    // Keep pending operation for recovery
                    return null
                }
                val body = response.body?.string() ?: return null
                JSONObject(body).getJSONArray("signatures")
            }

            // Unblind signatures - CRITICAL: Use keyset ID and amount from response
            val newProofs = mutableListOf<CashuProof>()
            val keysetCache = mutableMapOf<String, MintKeyset>()
            keysetCache[keyset.id] = keyset

            for (i in 0 until signatures.length()) {
                val sig = signatures.getJSONObject(i)
                val pms = preMintSecrets[i]
                val C_ = sig.getString("C_")
                val responseKeysetId = sig.getString("id")
                // CRITICAL: Use amount from response for key lookup (like CDK does)
                val responseAmount = sig.getLong("amount")

                val sigKeyset = keysetCache.getOrPut(responseKeysetId) {
                    fetchKeyset(responseKeysetId) ?: continue
                }

                val mintPubKey = sigKeyset.keys[responseAmount] ?: continue
                val C = CashuCrypto.unblindSignature(C_, pms.blindingFactor, mintPubKey) ?: continue
                newProofs.add(CashuProof(responseAmount, responseKeysetId, pms.secret, C))
            }

            Log.d(TAG, "Swapped ${proofs.size} proofs for ${newProofs.size} new proofs")

            // SUCCESS: Remove pending operation - we have the new proofs
            walletStorage.updateBlindedOpStatus(operationId, PendingOperationStatus.COMPLETED)
            walletStorage.removePendingBlindedOp(operationId)
            Log.d(TAG, "Removed pending swap operation: $operationId")

            // Now create a fresh quote and mint using cdk-kotlin to store these proofs properly
            // This is a workaround since we can't directly import proofs into cdk-kotlin
            // For now, just return the new proofs - they're valid but not in the wallet DB
            return newProofs
        } catch (e: Exception) {
            Log.e(TAG, "Swap failed: ${e.message}", e)
            // Keep pending operation for recovery
            return null
        }
    }

    // ==================== NUT-09 RESTORE ====================

    /**
     * Restore proofs from mint using NUT-09 /v1/restore endpoint.
     * This allows recovery of funds when you know the deterministic secrets.
     *
     * NUT-09 spec: https://github.com/cashubtc/nuts/blob/main/09.md
     *
     * Request: {"outputs": [{"amount": n, "id": "keyset_id", "B_": "blinded_message"}, ...]}
     * Response: {"outputs": [{"amount": n, "id": "keyset_id", "B_": "..."}, ...],
     *            "signatures": [{"amount": n, "id": "keyset_id", "C_": "..."}, ...]}
     *
     * The mint returns signatures for any outputs it has previously signed.
     *
     * @param preMintSecrets List of deterministic secrets with their blinding factors
     * @param keyset The keyset to use for restoration
     * @return List of recovered proofs (unspent status not yet verified)
     */
    suspend fun restoreProofs(
        preMintSecrets: List<PreMintSecret>,
        keyset: MintKeyset
    ): List<CashuProof> = withContext(Dispatchers.IO) {
        val mintUrl = currentMintUrl ?: run {
            Log.e(TAG, "restoreProofs: No mint URL configured")
            return@withContext emptyList()
        }

        if (preMintSecrets.isEmpty()) {
            return@withContext emptyList()
        }

        try {
            Log.d(TAG, "Restoring ${preMintSecrets.size} potential proofs from keyset ${keyset.id}")

            // Build blinded messages
            val outputs = JSONArray()
            for (pms in preMintSecrets) {
                // Calculate Y = hashToCurve(secret) and B_ = Y + r*G
                val Y = CashuCrypto.hashToCurve(pms.secret) ?: continue
                val B_ = CashuCrypto.blindMessage(Y, pms.blindingFactor) ?: continue

                outputs.put(JSONObject().apply {
                    put("amount", pms.amount)
                    put("id", keyset.id)
                    put("B_", B_)
                })
            }

            if (outputs.length() == 0) {
                Log.w(TAG, "restoreProofs: No valid blinded messages generated")
                return@withContext emptyList()
            }

            val requestBody = JSONObject().apply {
                put("outputs", outputs)
            }.toString()

            val request = Request.Builder()
                .url("$mintUrl/v1/restore")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val (returnedOutputs, signatures) = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "No error body"
                    Log.e(TAG, "Restore failed: ${response.code} - $errorBody")
                    return@withContext emptyList()
                }

                val body = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)

                val outputsArr = json.optJSONArray("outputs") ?: JSONArray()
                val sigsArr = json.optJSONArray("signatures") ?: JSONArray()

                Pair(outputsArr, sigsArr)
            }

            Log.d(TAG, "Restore returned ${signatures.length()} signatures for ${returnedOutputs.length()} outputs")

            if (signatures.length() == 0) {
                return@withContext emptyList()
            }

            // Build a map from B_ -> PreMintSecret for matching
            val b_ToSecret = mutableMapOf<String, PreMintSecret>()
            for (pms in preMintSecrets) {
                val Y = CashuCrypto.hashToCurve(pms.secret) ?: continue
                val B_ = CashuCrypto.blindMessage(Y, pms.blindingFactor) ?: continue
                b_ToSecret[B_] = pms
            }

            // Match returned outputs to our secrets and unblind
            val recoveredProofs = mutableListOf<CashuProof>()
            val keysetCache = mutableMapOf<String, MintKeyset>()
            keysetCache[keyset.id] = keyset

            for (i in 0 until signatures.length()) {
                val sig = signatures.getJSONObject(i)
                val C_ = sig.getString("C_")
                val responseKeysetId = sig.getString("id")
                val responseAmount = sig.getLong("amount")

                // Get the corresponding output to find our PreMintSecret
                if (i >= returnedOutputs.length()) continue
                val output = returnedOutputs.getJSONObject(i)
                val B_ = output.getString("B_")

                val pms = b_ToSecret[B_] ?: continue

                // Get keyset for this signature
                val sigKeyset = keysetCache.getOrPut(responseKeysetId) {
                    fetchKeyset(responseKeysetId) ?: continue
                }

                // Get mint's public key for this amount
                val mintPubKey = sigKeyset.keys[responseAmount]
                if (mintPubKey == null) {
                    Log.w(TAG, "No public key for amount $responseAmount in keyset $responseKeysetId")
                    continue
                }

                // Unblind the signature: C = C_ - r*K
                val C = CashuCrypto.unblindSignature(C_, pms.blindingFactor, mintPubKey)
                if (C == null) {
                    Log.w(TAG, "Failed to unblind signature for amount $responseAmount")
                    continue
                }

                recoveredProofs.add(CashuProof(responseAmount, responseKeysetId, pms.secret, C))
            }

            Log.d(TAG, "Recovered ${recoveredProofs.size} proofs")
            recoveredProofs
        } catch (e: Exception) {
            Log.e(TAG, "restoreProofs failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Filter proofs to only unspent ones using NUT-07.
     *
     * @param proofs List of proofs to check
     * @return List of proofs that are still unspent
     */
    suspend fun filterUnspentProofs(proofs: List<CashuProof>): List<CashuProof> = withContext(Dispatchers.IO) {
        if (proofs.isEmpty()) return@withContext emptyList()

        val secrets = proofs.map { it.secret }
        val stateMap = verifyProofStatesBySecret(secrets)

        if (stateMap == null) {
            Log.w(TAG, "filterUnspentProofs: Could not verify proof states, returning all")
            return@withContext proofs
        }

        proofs.filter { proof ->
            stateMap[proof.secret] == ProofStateResult.UNSPENT
        }
    }

    /**
     * Get all active keysets from the mint.
     * NUT-02: GET /v1/keysets
     *
     * @return List of keyset IDs, or empty list on failure
     */
    suspend fun getActiveKeysetIds(): List<String> = withContext(Dispatchers.IO) {
        val mintUrl = currentMintUrl ?: return@withContext emptyList()

        try {
            val request = Request.Builder()
                .url("$mintUrl/v1/keysets")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "getActiveKeysetIds failed: ${response.code}")
                    return@withContext emptyList()
                }

                val body = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)
                val keysetsArray = json.optJSONArray("keysets") ?: return@withContext emptyList()

                val ids = mutableListOf<String>()
                for (i in 0 until keysetsArray.length()) {
                    val keyset = keysetsArray.getJSONObject(i)
                    val id = keyset.getString("id")
                    val active = keyset.optBoolean("active", true)
                    if (active) {
                        ids.add(id)
                    }
                }

                Log.d(TAG, "Found ${ids.size} active keysets")
                ids
            }
        } catch (e: Exception) {
            Log.e(TAG, "getActiveKeysetIds failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Split amount into powers of 2 (Cashu denomination strategy).
     * E.g., 13 -> [1, 4, 8]
     */
    private fun splitAmount(amount: Long): List<Long> {
        val result = mutableListOf<Long>()
        var remaining = amount
        var power = 1L

        while (remaining > 0) {
            if (remaining and 1L == 1L) {
                result.add(power)
            }
            remaining = remaining shr 1
            power = power shl 1
        }

        return result
    }

    /**
     * Resolve Lightning Address to BOLT11 invoice.
     * Implements LNURL-pay protocol.
     *
     * @param address Lightning address (user@domain.com)
     * @param amountSats Amount in satoshis (required for invoice generation)
     * @return BOLT11 invoice string, or null on failure
     */
    suspend fun resolveLnAddress(address: String, amountSats: Long? = null): String? = withContext(Dispatchers.IO) {
        try {
            val parts = address.split("@")
            if (parts.size != 2) return@withContext null

            val (username, domain) = parts
            val lnurlUrl = "https://$domain/.well-known/lnurlp/$username"

            // Step 1: Fetch LNURL-pay metadata
            val metadataRequest = Request.Builder()
                .url(lnurlUrl)
                .get()
                .build()

            val callbackUrl = client.newCall(metadataRequest).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                json.getString("callback")
            }

            // Step 2: Request invoice (if amount provided)
            if (amountSats != null) {
                val invoiceUrl = "$callbackUrl?amount=${amountSats * 1000}" // milliSats
                val invoiceRequest = Request.Builder()
                    .url(invoiceUrl)
                    .get()
                    .build()

                client.newCall(invoiceRequest).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string() ?: return@withContext null
                    val json = JSONObject(body)
                    json.getString("pr") // BOLT11 payment request
                }
            } else {
                // Return callback URL for later use
                callbackUrl
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve LN address: $address", e)
            null
        }
    }

    /**
     * Extract Long value from cdk-kotlin Amount type.
     *
     * CRITICAL: This function must correctly extract the sats value from uniffi-generated Amount.
     * CdkAmount might be:
     * 1. A typealias for ULong (in which case we can use it directly)
     * 2. A wrapper class with fields/methods
     */
    private fun extractAmount(amount: CdkAmount): Long {
        // FIRST: Check if CdkAmount is actually just ULong (typealias)
        // In this case, we can convert directly without reflection
        if (amount::class == ULong::class) {
            try {
                // Use reflection to call toLong() since we can't cast directly
                val toLongMethod = amount::class.java.getMethod("toLong")
                val sats = toLongMethod.invoke(amount) as Long
                Log.d(TAG, "extractAmount: ULong class detected -> $sats sats")
                return sats
            } catch (e: Exception) {
                Log.d(TAG, "extractAmount: ULong conversion failed: ${e.message}")
            }
        }

        val amountClass = amount::class.java
        val className = amountClass.name

        Log.d(TAG, "=== EXTRACT AMOUNT DEBUG ===")
        Log.d(TAG, "Amount class: $className")
        Log.d(TAG, "Amount toString(): ${amount.toString()}")
        Log.d(TAG, "Amount::class.simpleName: ${amount::class.simpleName}")

        // Log all fields for debugging
        val allFields = amountClass.declaredFields
        Log.d(TAG, "Amount has ${allFields.size} fields:")
        allFields.forEach { field ->
            field.isAccessible = true
            try {
                val value = field.get(amount)
                Log.d(TAG, "  Field '${field.name}' (${field.type.simpleName}): $value")
            } catch (e: Exception) {
                Log.d(TAG, "  Field '${field.name}' (${field.type.simpleName}): <error: ${e.message}>")
            }
        }

        // Log all methods for debugging
        val allMethods = amountClass.declaredMethods.filter { !it.name.startsWith("access$") }
        Log.d(TAG, "Amount has ${allMethods.size} methods:")
        allMethods.forEach { method ->
            Log.d(TAG, "  Method '${method.name}(${method.parameterTypes.joinToString { it.simpleName }}): ${method.returnType.simpleName}'")
        }

        // Strategy 1: Try calling toULong() method (uniffi often generates this)
        try {
            val toULongMethod = amountClass.getMethod("toULong")
            val result = toULongMethod.invoke(amount)
            if (result is ULong) {
                val sats = result.toLong()
                Log.d(TAG, "Strategy 1 (toULong): SUCCESS -> $sats sats")
                return sats
            }
        } catch (e: Exception) {
            Log.d(TAG, "Strategy 1 (toULong): failed - ${e.message}")
        }

        // Strategy 2: Try calling toLong() method
        try {
            val toLongMethod = amountClass.getMethod("toLong")
            val result = toLongMethod.invoke(amount) as Long
            Log.d(TAG, "Strategy 2 (toLong): SUCCESS -> $result sats")
            return result
        } catch (e: Exception) {
            Log.d(TAG, "Strategy 2 (toLong): failed - ${e.message}")
        }

        // Strategy 3: Try calling getValue() method
        try {
            val getValueMethod = amountClass.getMethod("getValue")
            val result = getValueMethod.invoke(amount)
            val sats = when (result) {
                is ULong -> result.toLong()
                is Long -> result
                is Number -> result.toLong()
                else -> null
            }
            if (sats != null) {
                Log.d(TAG, "Strategy 3 (getValue): SUCCESS -> $sats sats")
                return sats
            }
        } catch (e: Exception) {
            Log.d(TAG, "Strategy 3 (getValue): failed - ${e.message}")
        }

        // Strategy 4: Try 'value' field directly
        try {
            val valueField = amountClass.getDeclaredField("value")
            valueField.isAccessible = true
            val result = valueField.get(amount)
            val sats = when (result) {
                is ULong -> result.toLong()
                is Long -> result
                is Number -> result.toLong()
                else -> null
            }
            if (sats != null) {
                Log.d(TAG, "Strategy 4 (value field): SUCCESS -> $sats sats")
                return sats
            }
        } catch (e: Exception) {
            Log.d(TAG, "Strategy 4 (value field): failed - ${e.message}")
        }

        // Strategy 5: Try first numeric field
        for (field in allFields) {
            field.isAccessible = true
            try {
                val value = field.get(amount)
                val sats = when (value) {
                    is ULong -> value.toLong()
                    is Long -> value
                    is Int -> value.toLong()
                    is Number -> value.toLong()
                    else -> continue
                }
                Log.d(TAG, "Strategy 5 (first numeric field '${field.name}'): SUCCESS -> $sats sats")
                return sats
            } catch (e: Exception) {
                continue
            }
        }

        // Strategy 6: Parse from toString() - LAST RESORT
        val amountStr = amount.toString()
        Log.d(TAG, "Strategy 6 (parse toString): trying to parse '$amountStr'")

        // Try to find a number in the string
        val numberPattern = Regex("\\d+")
        val matches = numberPattern.findAll(amountStr).toList()

        if (matches.isNotEmpty()) {
            // If multiple numbers, take the largest (likely the actual amount)
            val largest = matches.maxByOrNull { it.value.toLongOrNull() ?: 0L }
            val sats = largest?.value?.toLongOrNull()
            if (sats != null && sats > 0) {
                Log.d(TAG, "Strategy 6 (parse toString): SUCCESS -> $sats sats (from '$amountStr')")
                return sats
            }
        }

        Log.e(TAG, "=== ALL EXTRACTION STRATEGIES FAILED ===")
        Log.e(TAG, "Could not extract amount from: $amount (class: $className)")
        return 0L
    }
}

/**
 * Result of mint operation.
 */
data class MintTokensResult(
    val success: Boolean,
    val proofs: List<CashuProof>,
    val totalSats: Long
)

/**
 * Result of melt operation.
 */
data class MeltResult(
    val paid: Boolean,
    val paymentPreimage: String?,
    val change: List<CashuProof> = emptyList(),  // Change proofs from overpayment
    val isPending: Boolean = false,  // True if melt is still pending after timeout
    val pendingOpId: String? = null  // ID of pending op - caller must clear after NIP-60 publish
)

/**
 * Mint capability information from NUT-06 /v1/info endpoint.
 */
data class MintCapabilities(
    val mintUrl: String,
    val name: String?,
    val version: String?,
    val description: String?,
    val supportedNuts: Set<Int>,
    val supportsHtlc: Boolean,      // NUT-14
    val supportsMelt: Boolean,      // NUT-05
    val supportsProofState: Boolean, // NUT-07
    val supportsWebSocket: Boolean = false, // NUT-17
    val webSocketCommands: Set<String> = emptySet() // e.g., ["bolt11_mint_quote", "bolt11_melt_quote", "proof_state"]
) {
    /**
     * Check if mint supports all required NUTs for escrow functionality.
     */
    fun supportsEscrow(): Boolean = supportsHtlc && supportsMelt && supportsProofState

    /**
     * Check if mint supports WebSocket subscription for a specific kind.
     */
    fun supportsWebSocketKind(kind: String): Boolean = supportsWebSocket && kind in webSocketCommands

    /**
     * Get user-friendly description of missing capabilities.
     */
    fun getMissingCapabilities(): String {
        val missing = mutableListOf<String>()
        if (!supportsHtlc) missing.add("HTLC (NUT-14)")
        if (!supportsMelt) missing.add("Melt (NUT-05)")
        if (!supportsProofState) missing.add("Proof State (NUT-07)")
        return if (missing.isEmpty()) "None" else missing.joinToString(", ")
    }
}

/**
 * Mint keyset containing public keys for each denomination.
 */
data class MintKeyset(
    val id: String,
    val unit: String,
    val keys: Map<Long, String>  // amount -> compressed pubkey hex
)
