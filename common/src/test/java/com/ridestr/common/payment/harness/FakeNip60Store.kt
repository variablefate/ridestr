package com.ridestr.common.payment.harness

import com.ridestr.common.payment.WalletBalance
import com.ridestr.common.payment.cashu.CashuProof
import com.ridestr.common.payment.cashu.Nip60Proof
import com.ridestr.common.payment.cashu.Nip60Store
import com.ridestr.common.payment.cashu.ProofSelection
import com.ridestr.common.payment.cashu.WalletState
import java.util.UUID

/**
 * Fake implementation of Nip60Store for contract testing.
 *
 * Provides:
 * - In-memory proof storage with event ID tracking
 * - Call log for order verification (e.g., "republish before delete" invariant)
 * - Failure injection for error path testing
 * - Configurable test mint URL
 *
 * Usage:
 * ```kotlin
 * val fakeNip60 = FakeNip60Store()
 *
 * // Configure for test
 * fakeNip60.testMintUrl = "https://test.mint.example"
 *
 * // Inject into WalletService
 * walletService.setNip60Sync(fakeNip60)
 *
 * // Run operation that publishes proofs
 * walletService.someOperation()
 *
 * // Verify behavior
 * assertTrue(fakeNip60.callLog.contains("publishProofs"))
 * assertEquals(1, fakeNip60.proofsByEventId.size)
 * ```
 */
class FakeNip60Store : Nip60Store {

    // ═══════════════════════════════════════════════════════════════════════════════
    // STATE TRACKING
    // ═══════════════════════════════════════════════════════════════════════════════

    /** Proofs stored by event ID. Keys are event IDs, values are proof lists. */
    val proofsByEventId = mutableMapOf<String, List<CashuProof>>()

    /** Mint URL associated with each event. */
    val mintUrlByEventId = mutableMapOf<String, String>()

    /** Event IDs that have been deleted via deleteProofEvents(). */
    val deletedEventIds = mutableSetOf<String>()

    /** Whether wallet metadata has been published. */
    var metadataPublished = false

    /** The mint URL used for metadata. */
    var metadataMintUrl: String? = null

    /** Default mint URL for test operations. */
    var testMintUrl: String = "https://test.mint.example"

    // ═══════════════════════════════════════════════════════════════════════════════
    // CALL LOG - CRITICAL for order verification tests
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Log of all method calls for order verification.
     *
     * Each entry is formatted as: "methodName" or "methodName:details"
     * Examples:
     * - "publishProofs"
     * - "deleteProofEvents:abc123,def456"
     * - "selectProofsForSpending:100"
     *
     * CRITICAL: Used to verify "republish before delete" invariant:
     * ```kotlin
     * val publishIndex = callLog.indexOfFirst { it == "publishProofs" }
     * val deleteIndex = callLog.indexOfFirst { it.startsWith("deleteProofEvents") }
     * assertTrue(publishIndex < deleteIndex, "Must republish BEFORE deleting")
     * ```
     */
    val callLog = mutableListOf<String>()

    // ═══════════════════════════════════════════════════════════════════════════════
    // FAILURE INJECTION
    // ═══════════════════════════════════════════════════════════════════════════════

    /** If true, publishProofs() returns null (simulates relay failure). */
    var shouldFailPublish = false

    /** If true, fetchProofs() returns empty list. */
    var shouldFailFetch = false

    /** If true, publishWalletMetadata() returns false. */
    var shouldFailMetadata = false

    /** Count of publish attempts (for retry verification). */
    var publishAttemptCount = 0

    // ═══════════════════════════════════════════════════════════════════════════════
    // Nip60Store IMPLEMENTATION
    // ═══════════════════════════════════════════════════════════════════════════════

    override suspend fun publishProofs(
        proofs: List<CashuProof>,
        mintUrl: String,
        deletedEventIds: List<String>
    ): String? {
        callLog.add("publishProofs")
        publishAttemptCount++

        if (shouldFailPublish) {
            return null
        }

        val eventId = "event-${UUID.randomUUID()}"
        proofsByEventId[eventId] = proofs.toList()
        mintUrlByEventId[eventId] = mintUrl

        // Handle atomicity: if deletedEventIds provided, track them
        // (In real NIP-60, these go in the del array of the new event)
        if (deletedEventIds.isNotEmpty()) {
            callLog.add("publishProofs:withDel:${deletedEventIds.joinToString(",") { it.take(8) }}")
        }

        return eventId
    }

    override suspend fun fetchProofs(forceRefresh: Boolean): List<Nip60Proof> {
        callLog.add("fetchProofs:$forceRefresh")

        if (shouldFailFetch) {
            return emptyList()
        }

        // Convert stored proofs to Nip60Proof format
        // Filter out deleted event IDs
        return proofsByEventId
            .filter { (eventId, _) -> eventId !in deletedEventIds }
            .flatMap { (eventId, proofs) ->
                val mintUrl = mintUrlByEventId[eventId] ?: testMintUrl
                proofs.map { proof ->
                    Nip60Proof(
                        eventId = eventId,
                        mintUrl = mintUrl,
                        id = proof.id,
                        amount = proof.amount,
                        secret = proof.secret,
                        C = proof.C,
                        createdAt = System.currentTimeMillis() / 1000
                    )
                }
            }
    }

    override suspend fun selectProofsForSpending(
        amountSats: Long,
        mintUrl: String?
    ): ProofSelection? {
        callLog.add("selectProofsForSpending:$amountSats")

        val allProofs = fetchProofs()

        // Filter by mint URL if specified (matches real Nip60WalletSync behavior)
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
            return null // Insufficient funds
        }

        return ProofSelection(
            proofs = selected,
            totalAmount = total,
            changeAmount = total - amountSats
        )
    }

    override suspend fun deleteProofEvents(eventIds: List<String>): Boolean {
        callLog.add("deleteProofEvents:${eventIds.joinToString(",") { it.take(8) }}")

        deletedEventIds.addAll(eventIds)
        eventIds.forEach { id ->
            proofsByEventId.remove(id)
            mintUrlByEventId.remove(id)
        }

        return true
    }

    override suspend fun republishProofsWithNewMint(
        proofs: List<Nip60Proof>,
        newMintUrl: String
    ) {
        callLog.add("republishProofsWithNewMint:$newMintUrl")

        if (proofs.isEmpty()) return

        // Group proofs by old event ID for deletion
        val oldEventIds = proofs.map { it.eventId }.distinct()

        // Convert to CashuProof for re-publishing
        val cashuProofs = proofs.map { it.toCashuProof() }

        // Publish with new mint URL first
        val newEventId = publishProofs(cashuProofs, newMintUrl)

        if (newEventId != null) {
            // Successfully published new events - delete old ones
            deleteProofEvents(oldEventIds)
        }
    }

    override fun clearCache() {
        callLog.add("clearCache")
        // In real implementation this clears a local cache
        // For fake, we keep proofsByEventId as the "truth"
    }

    override suspend fun publishWalletMetadata(
        mintUrl: String,
        walletName: String,
        forceOverwrite: Boolean
    ): Boolean {
        callLog.add("publishWalletMetadata:$mintUrl")

        if (shouldFailMetadata) {
            return false
        }

        metadataPublished = true
        metadataMintUrl = mintUrl
        return true
    }

    override suspend fun getBalance(): WalletBalance {
        callLog.add("getBalance")

        val proofs = fetchProofs()
        val totalSats = proofs.sumOf { it.amount }

        return WalletBalance(
            availableSats = totalSats,
            pendingSats = 0,
            lastUpdated = System.currentTimeMillis()
        )
    }

    override suspend fun hasExistingWallet(): Boolean {
        callLog.add("hasExistingWallet")
        return metadataPublished || proofsByEventId.isNotEmpty()
    }

    override suspend fun restoreFromNostr(): WalletState? {
        callLog.add("restoreFromNostr")

        // Return a valid WalletState matching the test mint URL
        val proofs = fetchProofs()
        val balance = getBalance()

        return WalletState(
            mintUrl = testMintUrl,
            balance = balance,
            proofCount = proofs.size
        )
    }

    override suspend fun deleteAllProofsFromNostr(): Int {
        callLog.add("deleteAllProofsFromNostr")

        val count = proofsByEventId.size
        proofsByEventId.clear()
        mintUrlByEventId.clear()
        deletedEventIds.clear()
        metadataPublished = false
        metadataMintUrl = null

        return count
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // TEST HELPERS
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * Reset all state for reuse between tests.
     */
    fun reset() {
        proofsByEventId.clear()
        mintUrlByEventId.clear()
        deletedEventIds.clear()
        metadataPublished = false
        metadataMintUrl = null
        callLog.clear()
        shouldFailPublish = false
        shouldFailFetch = false
        shouldFailMetadata = false
        publishAttemptCount = 0
    }

    /**
     * Seed initial proofs for tests that need pre-existing wallet state.
     *
     * @param proofs Proofs to add
     * @param mintUrl Mint URL for these proofs
     * @return The generated event ID
     */
    fun seedProofs(proofs: List<CashuProof>, mintUrl: String = testMintUrl): String {
        val eventId = "seed-event-${UUID.randomUUID()}"
        proofsByEventId[eventId] = proofs.toList()
        mintUrlByEventId[eventId] = mintUrl
        return eventId
    }

    /**
     * Get the count of publishProofs calls in callLog.
     * Useful for verifying retry behavior.
     */
    fun getPublishCallCount(): Int {
        return callLog.count { it == "publishProofs" }
    }

    /**
     * Get the index of the first "publishProofs" call.
     * Returns -1 if not found.
     */
    fun getFirstPublishIndex(): Int {
        return callLog.indexOfFirst { it == "publishProofs" }
    }

    /**
     * Get the index of the first "deleteProofEvents" call.
     * Returns -1 if not found.
     */
    fun getFirstDeleteIndex(): Int {
        return callLog.indexOfFirst { it.startsWith("deleteProofEvents") }
    }

    /**
     * Verify that publishProofs was called before deleteProofEvents.
     * This is the critical "republish before delete" invariant.
     */
    fun verifyRepublishBeforeDelete(): Boolean {
        val publishIndex = getFirstPublishIndex()
        val deleteIndex = getFirstDeleteIndex()

        if (publishIndex == -1 || deleteIndex == -1) {
            return false // Both must have been called
        }

        return publishIndex < deleteIndex
    }
}
