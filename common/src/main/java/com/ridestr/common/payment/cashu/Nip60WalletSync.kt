package com.ridestr.common.payment.cashu

import android.util.Log
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.keys.KeyManager
import com.ridestr.common.nostr.relay.RelayManager
import com.ridestr.common.payment.WalletBalance
import com.ridestr.common.payment.WalletKeyManager
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException as KotlinCancellationException
import org.json.JSONArray
import org.json.JSONObject

/**
 * NIP-60 Cashu Wallet Sync
 *
 * Stores wallet state as Nostr events for cross-device portability and recovery.
 * This is the SOURCE OF TRUTH for wallet balance - not cdk-kotlin's local database.
 *
 * Event Kinds (per NIP-60):
 * - Kind 7375: Unspent proofs (encrypted to user pubkey)
 * - Kind 7376: Spending history (optional, for audit trail)
 * - Kind 17375: Wallet metadata (selected mint, encrypted wallet key, preferences)
 *
 * Benefits:
 * - Wallet portable across devices via Nostr relays
 * - Recovery possible from nsec alone
 * - Balance always accurate regardless of local state
 * - No app-specific cloud backup needed
 *
 * Reference: https://github.com/nostr-protocol/nips/blob/master/60.md
 */
class Nip60WalletSync(
    private val relayManager: RelayManager,
    private val keyManager: KeyManager,
    private val walletKeyManager: WalletKeyManager,
    private val walletStorage: com.ridestr.common.payment.WalletStorage? = null
) {
    companion object {
        private const val TAG = "Nip60WalletSync"

        // NIP-60 Event Kinds
        const val KIND_WALLET = 17375        // Wallet metadata (replaceable)
        const val KIND_TOKEN = 7375          // Unspent proofs
        const val KIND_HISTORY = 7376        // Spending history (optional)

        // d-tag for replaceable wallet event
        private const val WALLET_D_TAG = "cashu-wallet"

        // Query timeout - reduced from 8s to 2s (EOSE usually arrives within 500ms)
        // This is a quick fix; proper solution would exit on EOSE receipt
        private const val QUERY_TIMEOUT_MS = 2000L

        // Relay connection timeout (wait for at least one relay)
        private const val RELAY_CONNECT_TIMEOUT_MS = 15000L
    }

    /**
     * Wait for at least one relay to connect.
     * @return true if connected, false if timeout
     */
    private suspend fun waitForRelayConnection(): Boolean {
        var waitedMs = 0L
        val checkInterval = 500L

        while (!relayManager.isConnected() && waitedMs < RELAY_CONNECT_TIMEOUT_MS) {
            Log.d(TAG, "Waiting for relay connection... (${waitedMs}ms / ${RELAY_CONNECT_TIMEOUT_MS}ms)")
            delay(checkInterval)
            waitedMs += checkInterval
        }

        val connected = relayManager.isConnected()
        if (connected) {
            Log.d(TAG, "Relay connected after ${waitedMs}ms (${relayManager.connectedCount()} relays)")
        } else {
            Log.w(TAG, "Relay connection timeout after ${RELAY_CONNECT_TIMEOUT_MS}ms")
        }
        return connected
    }

    // Cached proofs from last fetch
    private var cachedProofs = mutableListOf<Nip60Proof>()
    private var lastFetchTime = 0L

    /**
     * Publish proofs to Nostr as Kind 7375 events.
     * Content is NIP-44 encrypted to self.
     *
     * NIP-60 COMPLIANT: When proofs replace old proofs (spending), include the
     * old event IDs in the `del` array. This is atomic - one event both creates
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
    ): String? = withContext(Dispatchers.IO) {
        val signer = keyManager.getSigner()
        val myPubKey = keyManager.getPubKeyHex()

        if (signer == null || myPubKey == null) {
            Log.e(TAG, "publishProofs: Not logged in")
            return@withContext null
        }

        if (!waitForRelayConnection()) {
            Log.e(TAG, "publishProofs: No relays connected - proofs NOT backed up to Nostr!")
            return@withContext null
        }

        val delInfo = if (deletedEventIds.isNotEmpty()) " (replacing ${deletedEventIds.size} events)" else ""
        Log.d(TAG, "Publishing ${proofs.size} proofs (${proofs.sumOf { it.amount }} sats)$delInfo to ${relayManager.connectedCount()} relays")

        try {
            // Build content as per NIP-60
            val proofsArray = JSONArray()
            proofs.forEach { proof ->
                proofsArray.put(JSONObject().apply {
                    put("id", proof.id)
                    put("amount", proof.amount)
                    put("secret", proof.secret)
                    put("C", proof.C)
                })
            }

            val content = JSONObject().apply {
                put("mint", mintUrl)
                put("proofs", proofsArray)
                // NIP-60: Include `del` array with event IDs being replaced
                if (deletedEventIds.isNotEmpty()) {
                    put("del", JSONArray(deletedEventIds))
                    Log.d(TAG, "Including del array: ${deletedEventIds.joinToString(", ") { it.take(8) }}...")
                }
            }.toString()

            // Encrypt to self using NIP-44
            val encryptedContent = signer.nip44Encrypt(content, myPubKey)

            // Create Kind 7375 event
            val tags = arrayOf(
                arrayOf("a", "$KIND_WALLET:$myPubKey:$WALLET_D_TAG")  // Reference to wallet
            )

            val event = signer.sign<Event>(
                createdAt = System.currentTimeMillis() / 1000,
                kind = KIND_TOKEN,
                tags = tags,
                content = encryptedContent
            )

            // Publish to relays
            relayManager.publish(event)

            Log.d(TAG, "Published ${proofs.size} proofs (${proofs.sumOf { it.amount }} sats) as event ${event.id}")
            event.id
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish proofs: ${e.message}", e)
            null
        }
    }

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
    ) = withContext(Dispatchers.IO) {
        if (proofs.isEmpty()) return@withContext

        Log.d(TAG, "Republishing ${proofs.size} proofs with new mint URL: $newMintUrl")

        // Group proofs by old event ID for deletion
        val oldEventIds = proofs.map { it.eventId }.distinct()

        // Convert to CashuProof for re-publishing
        val cashuProofs = proofs.map { it.toCashuProof() }

        // Publish with new mint URL first (ensure backup exists before deleting old)
        val newEventId = publishProofs(cashuProofs, newMintUrl)

        if (newEventId != null) {
            // Successfully published new events - delete old ones
            Log.d(TAG, "Published proofs with new URL, deleting ${oldEventIds.size} old events")
            deleteProofEvents(oldEventIds)
            clearCache()
        } else {
            Log.e(TAG, "Failed to republish proofs - keeping old events")
        }
    }

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
    suspend fun fetchProofs(forceRefresh: Boolean = false): List<Nip60Proof> = withContext(Dispatchers.IO) {
        val signer = keyManager.getSigner()
        val myPubKey = keyManager.getPubKeyHex()

        if (signer == null || myPubKey == null) {
            Log.e(TAG, "fetchProofs: Not logged in")
            return@withContext emptyList()
        }

        // Return cached if recent
        if (!forceRefresh && cachedProofs.isNotEmpty() &&
            System.currentTimeMillis() - lastFetchTime < 30000) {
            Log.d(TAG, "Returning cached proofs: ${cachedProofs.size} items")
            return@withContext cachedProofs.toList()
        }

        // Wait for relay connection
        if (!waitForRelayConnection()) {
            Log.e(TAG, "fetchProofs: No relays connected - returning cached or empty")
            return@withContext cachedProofs.toList()
        }

        Log.d(TAG, "Fetching Kind 7375 token events for ${myPubKey.take(16)}... from ${relayManager.connectedCount()} relays")

        val proofs = mutableListOf<Nip60Proof>()
        val seenEventIds = mutableSetOf<String>()  // Track seen events to avoid duplicate parsing
        val deletedEventIds = mutableSetOf<String>()  // NIP-60: Track events marked as deleted via `del` arrays

        // EOSE-aware waiting: complete when first relay sends EOSE
        val eoseReceived = CompletableDeferred<String>()

        try {
            val subscriptionId = relayManager.subscribe(
                kinds = listOf(KIND_TOKEN),
                authors = listOf(myPubKey),
                limit = 100,
                onEvent = { event, relayUrl ->
                    // Skip if we've already parsed this event (received from another relay)
                    synchronized(seenEventIds) {
                        if (event.id in seenEventIds) {
                            return@subscribe
                        }
                        seenEventIds.add(event.id)
                    }

                    // Decrypt and parse each token event
                    try {
                        val decrypted = runBlocking { signer.nip44Decrypt(event.content, myPubKey) }
                        val json = JSONObject(decrypted)
                        val mintUrl = json.getString("mint")
                        val proofsArray = json.getJSONArray("proofs")

                        // NIP-60: Parse `del` array if present - these events are consumed
                        if (json.has("del")) {
                            val delArray = json.getJSONArray("del")
                            synchronized(deletedEventIds) {
                                for (i in 0 until delArray.length()) {
                                    val deletedId = delArray.getString(i)
                                    deletedEventIds.add(deletedId)
                                }
                            }
                            if (delArray.length() > 0) {
                                Log.d(TAG, "Event ${event.id.take(8)} deletes ${delArray.length()} previous events")
                            }
                        }

                        for (i in 0 until proofsArray.length()) {
                            val p = proofsArray.getJSONObject(i)
                            proofs.add(Nip60Proof(
                                eventId = event.id,
                                mintUrl = mintUrl,
                                id = p.getString("id"),
                                amount = p.getLong("amount"),
                                secret = p.getString("secret"),
                                C = p.getString("C"),
                                createdAt = event.createdAt
                            ))
                        }
                        Log.d(TAG, "Parsed ${proofsArray.length()} proofs from event ${event.id.take(8)}")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse token event ${event.id}: ${e.message}")
                    }
                },
                onEose = { relayUrl ->
                    // Complete on first EOSE - don't wait for all relays
                    eoseReceived.complete(relayUrl)
                }
            )

            // Wait for EOSE or timeout (whichever comes first)
            val eoseRelay = withTimeoutOrNull(QUERY_TIMEOUT_MS) {
                eoseReceived.await()
            }
            if (eoseRelay != null) {
                Log.d(TAG, "EOSE received from $eoseRelay - closing subscription early")
            } else {
                Log.d(TAG, "Timeout waiting for EOSE - closing subscription")
            }
            relayManager.closeSubscription(subscriptionId)

            // NIP-60: Filter out proofs from events that are in `del` arrays
            val rawTotal = proofs.sumOf { it.amount }
            val rawEventIds = proofs.map { it.eventId }.distinct()
            Log.d(TAG, "Raw: ${proofs.size} proofs from ${rawEventIds.size} events, $rawTotal sats")

            val activeProofs = if (deletedEventIds.isNotEmpty()) {
                val beforeCount = proofs.size
                val filtered = proofs.filter { it.eventId !in deletedEventIds }
                val deletedCount = beforeCount - filtered.size
                val deletedSats = proofs.filter { it.eventId in deletedEventIds }.sumOf { it.amount }
                if (deletedCount > 0) {
                    Log.d(TAG, "NIP-60 del: Filtered out $deletedCount proofs ($deletedSats sats) from ${deletedEventIds.size} deleted events")
                }
                filtered
            } else {
                proofs
            }

            // Deduplicate by secret (unique identifier for each proof)
            // Keep the proof from the most recent event to handle stale events
            val uniqueProofs = activeProofs
                .groupBy { it.secret }
                .map { (_, duplicates) -> duplicates.maxByOrNull { it.createdAt } ?: duplicates.first() }

            val duplicateCount = activeProofs.size - uniqueProofs.size
            val uniqueTotal = uniqueProofs.sumOf { it.amount }
            if (duplicateCount > 0) {
                Log.w(TAG, "Deduplicated $duplicateCount duplicate proofs")
            }

            // Update cache
            cachedProofs = uniqueProofs.toMutableList()
            lastFetchTime = System.currentTimeMillis()

            Log.d(TAG, "Fetched ${uniqueProofs.size} unspent proofs, $uniqueTotal sats")
            uniqueProofs
        } catch (e: CancellationException) {
            // Scope was cancelled (e.g., user navigated away) - not an error
            Log.d(TAG, "Fetch proofs cancelled (scope left composition)")
            cachedProofs.toList()  // Return cached data
        } catch (e: IllegalStateException) {
            // Compose scope exception - user navigated away
            if (e.message?.contains("left the composition") == true) {
                Log.d(TAG, "Fetch proofs cancelled (Compose scope disposed)")
                cachedProofs.toList()
            } else {
                Log.e(TAG, "Failed to fetch proofs: ${e.message}", e)
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch proofs: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Calculate total balance from NIP-60 proofs.
     * This is the SOURCE OF TRUTH for wallet balance.
     */
    suspend fun getBalance(): WalletBalance {
        val proofs = fetchProofs()
        val totalSats = proofs.sumOf { it.amount }

        return WalletBalance(
            availableSats = totalSats,
            pendingSats = 0,
            lastUpdated = System.currentTimeMillis()
        )
    }

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
    ): ProofSelection? {
        val allProofs = fetchProofs()
        val eligibleProofs = if (mintUrl != null) {
            allProofs.filter { it.mintUrl == mintUrl }
        } else {
            allProofs
        }

        // Sort by amount ascending for optimal selection
        val sorted = eligibleProofs.sortedBy { it.amount }

        val selected = mutableListOf<Nip60Proof>()
        var total = 0L

        for (proof in sorted) {
            if (total >= amountSats) break
            selected.add(proof)
            total += proof.amount
        }

        if (total < amountSats) {
            Log.e(TAG, "Insufficient balance: have $total, need $amountSats")
            return null
        }

        val change = total - amountSats
        Log.d(TAG, "Selected ${selected.size} proofs ($total sats) for $amountSats (change: $change)")

        return ProofSelection(
            proofs = selected,
            totalAmount = total,
            changeAmount = change
        )
    }

    /**
     * Delete token events after spending proofs.
     * Uses NIP-09 deletion.
     *
     * @param eventIds Event IDs to delete
     * @return true if deletion events were published
     */
    suspend fun deleteProofEvents(eventIds: List<String>): Boolean = withContext(Dispatchers.IO) {
        val signer = keyManager.getSigner() ?: return@withContext false

        if (eventIds.isEmpty()) return@withContext true

        try {
            // Create NIP-09 deletion event
            val tags = eventIds.map { arrayOf("e", it) }.toTypedArray()

            val event = signer.sign<Event>(
                createdAt = System.currentTimeMillis() / 1000,
                kind = 5,  // NIP-09 deletion
                tags = tags,
                content = "spent"
            )

            relayManager.publish(event)

            // Remove from cache
            cachedProofs.removeAll { it.eventId in eventIds }

            Log.d(TAG, "Requested deletion of ${eventIds.size} token events via NIP-09")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete token events: ${e.message}", e)
            false
        }
    }

    /**
     * Record a spending transaction in history (Kind 7376).
     * Optional but useful for audit trail.
     */
    suspend fun recordSpend(
        amountSats: Long,
        spentEventIds: List<String>,
        newEventId: String?,
        description: String? = null
    ): Boolean = withContext(Dispatchers.IO) {
        val signer = keyManager.getSigner()
        val myPubKey = keyManager.getPubKeyHex()

        if (signer == null || myPubKey == null) return@withContext false

        try {
            val content = JSONObject().apply {
                put("direction", "out")
                put("amount", amountSats)
                put("unit", "sat")
                description?.let { put("memo", it) }
            }.toString()

            val encryptedContent = signer.nip44Encrypt(content, myPubKey)

            // Tags reference destroyed and created events
            val tags = mutableListOf<Array<String>>()
            spentEventIds.forEach { tags.add(arrayOf("e", it, "", "destroyed")) }
            newEventId?.let { tags.add(arrayOf("e", it, "", "created")) }

            val event = signer.sign<Event>(
                createdAt = System.currentTimeMillis() / 1000,
                kind = KIND_HISTORY,
                tags = tags.toTypedArray(),
                content = encryptedContent
            )

            relayManager.publish(event)
            Log.d(TAG, "Recorded spending history: $amountSats sats")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to record spend: ${e.message}", e)
            false
        }
    }

    /**
     * Publish wallet metadata (Kind 17375) in NIP-60 standard format.
     * Stores mint URL, wallet private key, and mnemonic for full backup.
     *
     * SECURITY: The entire content is NIP-44 encrypted to the user's own pubkey.
     * Only the user with their nsec can decrypt this data.
     *
     * NIP-60 Content structure (array of tag-like pairs, encrypted):
     * [
     *   ["privkey", "hex_private_key"],     // For P2PK HTLC claims
     *   ["mint", "https://mint.example.com"],
     *   ["mnemonic", "word1 word2 ..."]     // Custom extension for cdk-kotlin seed
     * ]
     */
    suspend fun publishWalletMetadata(
        mintUrl: String,
        walletName: String = "Ridestr Wallet"
    ): Boolean = withContext(Dispatchers.IO) {
        val signer = keyManager.getSigner()
        val myPubKey = keyManager.getPubKeyHex()

        if (signer == null || myPubKey == null) {
            Log.e(TAG, "publishWalletMetadata: Not logged in")
            return@withContext false
        }

        // Wait for relay connection
        if (!waitForRelayConnection()) {
            Log.e(TAG, "publishWalletMetadata: No relays connected - metadata NOT backed up!")
            return@withContext false
        }

        try {
            // Get wallet private key for backup (used for P2PK HTLC signatures)
            val walletPrivKey = walletKeyManager.exportPrivateKeyForBackup()

            // Get mnemonic for cdk-kotlin seed backup
            val mnemonic = if (walletKeyManager.hasMnemonic()) {
                walletKeyManager.getOrCreateMnemonic()
            } else {
                null
            }

            // Build NIP-60 standard format: array of tag-like pairs
            val contentArray = JSONArray()

            // Add privkey (NIP-60 standard field)
            if (walletPrivKey != null) {
                contentArray.put(JSONArray().apply {
                    put("privkey")
                    put(walletPrivKey)
                })
                Log.d(TAG, "Including privkey in backup (pubkey: ${walletKeyManager.getWalletPubKeyHex()?.take(16)}...)")
            }

            // Add mint (NIP-60 standard field)
            contentArray.put(JSONArray().apply {
                put("mint")
                put(mintUrl)
            })

            // Add mnemonic (custom extension for cdk-kotlin recovery)
            if (mnemonic != null) {
                contentArray.put(JSONArray().apply {
                    put("mnemonic")
                    put(mnemonic)
                })
                Log.d(TAG, "Including mnemonic in backup")
            }

            // Add NUT-13 keyset counters (for deterministic secret recovery)
            val counters = walletStorage?.getAllCounters()
            if (!counters.isNullOrEmpty()) {
                // Format: ["counters", "keysetId1:value1", "keysetId2:value2", ...]
                contentArray.put(JSONArray().apply {
                    put("counters")
                    counters.forEach { (keysetId, value) ->
                        put("$keysetId:$value")
                    }
                })
                Log.d(TAG, "Including ${counters.size} keyset counters in backup")
            }

            val content = contentArray.toString()

            // NIP-44 encrypt entire content to user's own pubkey
            // This ensures only the user can decrypt their wallet backup
            val encryptedContent = signer.nip44Encrypt(content, myPubKey)

            val tags = arrayOf(
                arrayOf("d", WALLET_D_TAG)  // Makes it replaceable
            )

            val event = signer.sign<Event>(
                createdAt = System.currentTimeMillis() / 1000,
                kind = KIND_WALLET,
                tags = tags,
                content = encryptedContent
            )

            relayManager.publish(event)
            Log.d(TAG, "Published wallet metadata (NIP-60 format) for $mintUrl to ${relayManager.connectedCount()} relays")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish wallet metadata: ${e.message}", e)
            false
        }
    }

    /**
     * Check if user has an existing NIP-60 wallet.
     * Checks for EITHER wallet metadata (Kind 17375) OR proof events (Kind 7375).
     * This ensures we detect existing wallets even if metadata wasn't published.
     */
    suspend fun hasExistingWallet(): Boolean = withContext(Dispatchers.IO) {
        val myPubKey = keyManager.getPubKeyHex() ?: run {
            Log.e(TAG, "hasExistingWallet: Not logged in (no pubkey)")
            return@withContext false
        }

        Log.d(TAG, "Checking for existing NIP-60 wallet for pubkey: ${myPubKey.take(16)}...")

        // Wait for relay connection before querying
        if (!waitForRelayConnection()) {
            Log.e(TAG, "hasExistingWallet: No relays connected - cannot check")
            return@withContext false
        }

        var foundMetadata = false
        var foundProofs = false

        // EOSE-aware waiting: complete when first relay sends EOSE for either subscription
        val eoseReceived = CompletableDeferred<String>()

        // Check for wallet metadata (Kind 17375)
        val metaSubId = relayManager.subscribe(
            kinds = listOf(KIND_WALLET),
            authors = listOf(myPubKey),
            tags = mapOf("d" to listOf(WALLET_D_TAG)),
            limit = 1,
            onEvent = { event, relayUrl ->
                Log.d(TAG, "Found wallet metadata event ${event.id} from $relayUrl")
                foundMetadata = true
            },
            onEose = { relayUrl ->
                eoseReceived.complete(relayUrl)
            }
        )

        // Also check for proof events (Kind 7375)
        val proofSubId = relayManager.subscribe(
            kinds = listOf(KIND_TOKEN),
            authors = listOf(myPubKey),
            limit = 1,
            onEvent = { event, relayUrl ->
                Log.d(TAG, "Found proof event ${event.id} from $relayUrl")
                foundProofs = true
            },
            onEose = { relayUrl ->
                eoseReceived.complete(relayUrl)
            }
        )

        // Wait for EOSE or timeout (whichever comes first)
        val eoseRelay = withTimeoutOrNull(QUERY_TIMEOUT_MS) {
            eoseReceived.await()
        }
        if (eoseRelay != null) {
            Log.d(TAG, "EOSE received from $eoseRelay - closing subscriptions early")
        }
        relayManager.closeSubscription(metaSubId)
        relayManager.closeSubscription(proofSubId)

        val found = foundMetadata || foundProofs
        Log.d(TAG, "hasExistingWallet result: $found (metadata=$foundMetadata, proofs=$foundProofs)")
        found
    }

    /**
     * Restore wallet state from Nostr.
     * Fetches wallet metadata (including wallet key and mnemonic) and all unspent proofs.
     *
     * IMPORTANT: This restores the wallet private key and mnemonic from the NIP-60 backup.
     * Without this, HTLC claims would fail on a new device (different wallet key).
     *
     * Supports both formats:
     * - NIP-60 standard: [["privkey", "..."], ["mint", "..."], ["mnemonic", "..."]]
     * - Legacy JSON object: {"mints": [...], "wallet_privkey": "...", "mnemonic": "..."}
     */
    suspend fun restoreFromNostr(): WalletState? = withContext(Dispatchers.IO) {
        val signer = keyManager.getSigner()
        val myPubKey = keyManager.getPubKeyHex()

        if (signer == null || myPubKey == null) {
            Log.e(TAG, "restoreFromNostr: Not logged in")
            return@withContext null
        }

        Log.d(TAG, "Restoring wallet from Nostr for pubkey: ${myPubKey.take(16)}...")

        // Wait for relay connection
        if (!waitForRelayConnection()) {
            Log.e(TAG, "restoreFromNostr: No relays connected - cannot restore")
            return@withContext null
        }

        Log.d(TAG, "Connected to ${relayManager.connectedCount()} relays, fetching wallet data...")

        // 1. Fetch wallet metadata (includes mint URL, wallet key, mnemonic)
        var mintUrl: String? = null
        var walletPrivKey: String? = null
        var mnemonic: String? = null
        var counters: Map<String, Long> = emptyMap()

        // EOSE-aware waiting
        val eoseReceived = CompletableDeferred<String>()

        val metaSubId = relayManager.subscribe(
            kinds = listOf(KIND_WALLET),
            authors = listOf(myPubKey),
            tags = mapOf("d" to listOf(WALLET_D_TAG)),
            limit = 1,
            onEvent = { event, relayUrl ->
                try {
                    Log.d(TAG, "Received metadata event ${event.id} from $relayUrl")
                    val decrypted = runBlocking { signer.nip44Decrypt(event.content, myPubKey) }

                    // Try NIP-60 standard format first (array of tag-like pairs)
                    val parsed = parseWalletMetadata(decrypted)
                    mintUrl = parsed.mintUrl
                    walletPrivKey = parsed.privkey
                    mnemonic = parsed.mnemonic
                    counters = parsed.counters

                    Log.d(TAG, "Decrypted wallet metadata: mint=$mintUrl, hasWalletKey=${walletPrivKey != null}, hasMnemonic=${mnemonic != null}, counters=${counters.size}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse wallet metadata: ${e.message}")
                }
            },
            onEose = { relayUrl ->
                eoseReceived.complete(relayUrl)
            }
        )

        // Wait for EOSE or timeout (whichever comes first)
        val eoseRelay = withTimeoutOrNull(QUERY_TIMEOUT_MS) {
            eoseReceived.await()
        }
        if (eoseRelay != null) {
            Log.d(TAG, "EOSE received from $eoseRelay - closing metadata subscription early")
        }
        relayManager.closeSubscription(metaSubId)

        // 2. Restore wallet key if found (CRITICAL for HTLC claims on new device)
        if (walletPrivKey != null) {
            val imported = walletKeyManager.importPrivateKey(walletPrivKey!!)
            if (imported) {
                Log.d(TAG, "Restored wallet key from NIP-60 backup (pubkey: ${walletKeyManager.getWalletPubKeyHex()?.take(16)}...)")
            } else {
                Log.e(TAG, "Failed to import wallet key from backup")
            }
        } else {
            Log.w(TAG, "No wallet key found in NIP-60 metadata - HTLC claims may fail on this device")
        }

        // 3. Restore mnemonic if found (for cdk-kotlin deterministic keys)
        if (mnemonic != null) {
            val imported = walletKeyManager.importMnemonic(mnemonic!!)
            if (imported) {
                Log.d(TAG, "Restored mnemonic from NIP-60 backup")
            } else {
                Log.e(TAG, "Failed to import mnemonic from backup")
            }
        }

        // 4. Restore NUT-13 keyset counters if found
        if (counters.isNotEmpty() && walletStorage != null) {
            walletStorage.restoreCounters(counters)
            Log.d(TAG, "Restored ${counters.size} keyset counters from NIP-60 backup")
        }

        // 5. Fetch all proofs
        val proofs = fetchProofs(forceRefresh = true)
        val totalBalance = proofs.sumOf { it.amount }

        // If no mint URL found in metadata, use first proof's mint
        if (mintUrl == null && proofs.isNotEmpty()) {
            mintUrl = proofs.first().mintUrl
        }

        Log.d(TAG, "Restored wallet: ${proofs.size} proofs, $totalBalance sats, mint=$mintUrl")

        WalletState(
            mintUrl = mintUrl,
            balance = WalletBalance(availableSats = totalBalance),
            proofCount = proofs.size
        )
    }

    /**
     * Parse wallet metadata from decrypted content.
     * Supports both NIP-60 array format and legacy JSON object format.
     */
    private fun parseWalletMetadata(decrypted: String): WalletMetadataParsed {
        var mintUrl: String? = null
        var privkey: String? = null
        var mnemonic: String? = null
        val counters = mutableMapOf<String, Long>()

        val trimmed = decrypted.trim()

        // Try NIP-60 standard format: array of tag-like pairs
        if (trimmed.startsWith("[")) {
            try {
                val array = JSONArray(trimmed)
                for (i in 0 until array.length()) {
                    val pair = array.optJSONArray(i) ?: continue
                    if (pair.length() < 2) continue

                    when (pair.getString(0)) {
                        "privkey" -> privkey = pair.getString(1).takeIf { it.isNotBlank() }
                        "mint" -> mintUrl = mintUrl ?: pair.getString(1) // Take first mint
                        "mnemonic" -> mnemonic = pair.getString(1).takeIf { it.isNotBlank() }
                        "counters" -> {
                            // Parse counters: ["counters", "keysetId1:value1", "keysetId2:value2", ...]
                            for (j in 1 until pair.length()) {
                                val counterStr = pair.optString(j) ?: continue
                                val parts = counterStr.split(":")
                                if (parts.size == 2) {
                                    val keysetId = parts[0]
                                    val value = parts[1].toLongOrNull()
                                    if (value != null) {
                                        counters[keysetId] = value
                                    }
                                }
                            }
                            Log.d(TAG, "Parsed ${counters.size} keyset counters from backup")
                        }
                    }
                }
                Log.d(TAG, "Parsed NIP-60 standard format metadata")
                return WalletMetadataParsed(mintUrl, privkey, mnemonic, counters)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse as NIP-60 array format: ${e.message}")
            }
        }

        // Fall back to legacy JSON object format
        if (trimmed.startsWith("{")) {
            try {
                val json = JSONObject(trimmed)

                // Extract mint URL (legacy format uses "mints" array)
                val mints = json.optJSONArray("mints")
                mintUrl = mints?.optString(0)

                // Extract wallet private key (legacy used "wallet_privkey")
                val legacyPrivKey = if (json.has("wallet_privkey")) json.getString("wallet_privkey") else null
                val stdPrivKey = if (json.has("privkey")) json.getString("privkey") else null
                privkey = legacyPrivKey?.takeIf { it.isNotBlank() }
                    ?: stdPrivKey?.takeIf { it.isNotBlank() }

                // Extract mnemonic
                val mnemonicVal = if (json.has("mnemonic")) json.getString("mnemonic") else null
                mnemonic = mnemonicVal?.takeIf { it.isNotBlank() }

                Log.d(TAG, "Parsed legacy JSON object format metadata")
                return WalletMetadataParsed(mintUrl, privkey, mnemonic)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse as legacy JSON object: ${e.message}")
            }
        }

        Log.w(TAG, "Could not parse wallet metadata in any known format")
        return WalletMetadataParsed(null, null, null)
    }

    /**
     * Parsed wallet metadata from Kind 17375 event.
     */
    private data class WalletMetadataParsed(
        val mintUrl: String?,
        val privkey: String?,
        val mnemonic: String?,
        val counters: Map<String, Long> = emptyMap()
    )

    /**
     * Delete all NIP-60 proof events (Kind 7375) from relays.
     * This is destructive - only use when user wants to clear wallet from Nostr.
     *
     * NOTE: This deletes the proof EVENTS, not the proofs themselves.
     * The funds still exist if the proofs are held locally.
     *
     * @return Number of events deleted
     */
    suspend fun deleteAllProofsFromNostr(): Int = withContext(Dispatchers.IO) {
        val signer = keyManager.getSigner()
        val myPubKey = keyManager.getPubKeyHex()

        if (signer == null || myPubKey == null) {
            Log.e(TAG, "deleteAllProofsFromNostr: Not logged in")
            return@withContext 0
        }

        Log.w(TAG, "Deleting all NIP-60 proofs from Nostr...")

        // Fetch all proof event IDs
        val eventIds = mutableListOf<String>()
        val subscriptionId = relayManager.subscribe(
            kinds = listOf(KIND_TOKEN),
            authors = listOf(myPubKey),
            limit = 1000,
            onEvent = { event, _ ->
                synchronized(eventIds) {
                    eventIds.add(event.id)
                }
            }
        )

        delay(3000)
        relayManager.closeSubscription(subscriptionId)

        if (eventIds.isEmpty()) {
            Log.d(TAG, "No NIP-60 proofs found to delete")
            return@withContext 0
        }

        Log.d(TAG, "Found ${eventIds.size} NIP-60 proof events to delete")

        // Create NIP-09 deletion events in batches
        var deletedCount = 0
        eventIds.chunked(50).forEach { batch ->
            try {
                // Create deletion event (Kind 5)
                val tags = batch.map { id -> arrayOf("e", id) } +
                        listOf(arrayOf("k", KIND_TOKEN.toString()))

                val event = signer.sign<Event>(
                    createdAt = System.currentTimeMillis() / 1000,
                    kind = 5,  // NIP-09 deletion
                    tags = tags.toTypedArray(),
                    content = "User requested wallet data deletion"
                )

                relayManager.publish(event)
                deletedCount += batch.size
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting batch: ${e.message}")
            }
        }

        // Also delete wallet metadata (Kind 17375)
        try {
            val metaSubId = relayManager.subscribe(
                kinds = listOf(KIND_WALLET),
                authors = listOf(myPubKey),
                limit = 10,
                onEvent = { event, _ ->
                    try {
                        val deletionEvent = runBlocking {
                            signer.sign<Event>(
                                createdAt = System.currentTimeMillis() / 1000,
                                kind = 5,
                                tags = arrayOf(
                                    arrayOf("e", event.id),
                                    arrayOf("k", KIND_WALLET.toString())
                                ),
                                content = "User requested wallet data deletion"
                            )
                        }
                        relayManager.publish(deletionEvent)
                        deletedCount++
                        Log.d(TAG, "Deleted wallet metadata event: ${event.id}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error deleting metadata: ${e.message}")
                    }
                }
            )
            delay(2000)
            relayManager.closeSubscription(metaSubId)
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up metadata: ${e.message}")
        }

        // Clear local cache
        clearCache()

        Log.d(TAG, "Deleted $deletedCount NIP-60 events from Nostr")
        deletedCount
    }

    /**
     * Clear cached proofs (call when switching accounts or after sync).
     */
    fun clearCache() {
        cachedProofs.clear()
        lastFetchTime = 0
    }
}

/**
 * Proof stored in NIP-60 with metadata.
 */
data class Nip60Proof(
    val eventId: String,    // Kind 7375 event ID (for deletion)
    val mintUrl: String,
    val id: String,         // Keyset ID
    val amount: Long,
    val secret: String,
    val C: String,          // Unblinded signature
    val createdAt: Long
) {
    /**
     * Convert to CashuProof, fixing C field if it contains raw bytes instead of hex.
     * Some old NIP-60 events stored C as raw bytes; this normalizes them.
     */
    fun toCashuProof(): CashuProof {
        val normalizedC = if (isValidHex(C) && C.length == 66) {
            // Already properly hex-encoded 33-byte compressed point
            C
        } else {
            // Raw bytes stored as string - convert to hex
            C.toByteArray(Charsets.ISO_8859_1).joinToString("") { "%02x".format(it) }
        }
        return CashuProof(amount, id, secret, normalizedC)
    }

    private fun isValidHex(s: String): Boolean {
        return s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
}

/**
 * Result of proof selection for spending.
 */
data class ProofSelection(
    val proofs: List<Nip60Proof>,
    val totalAmount: Long,
    val changeAmount: Long
) {
    val eventIds: List<String> get() = proofs.map { it.eventId }.distinct()
}

/**
 * Wallet state restored from NIP-60.
 */
data class WalletState(
    val mintUrl: String?,
    val balance: WalletBalance,
    val proofCount: Int
)
