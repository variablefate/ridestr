package com.ridestr.common.payment.harness

import com.ridestr.common.payment.cashu.CashuProof
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for FakeNip60Store test double.
 *
 * These tests verify that FakeNip60Store correctly implements
 * the Nip60Store contract and provides proper test infrastructure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class FakeNip60StoreTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var fake: FakeNip60Store

    companion object {
        private const val TEST_MINT_URL = "https://test.mint.example"
        private const val TEST_KEYSET_ID = "009a1f293253e41e"

        fun testProof(amount: Long): CashuProof = CashuProof(
            amount = amount,
            id = TEST_KEYSET_ID,
            secret = "secret_${System.nanoTime()}",
            C = "02a9acc1e48c25eeeb9289b5031cc57da9fe72f3fe2861d264bdc074209b107ba2"
        )
    }

    @Before
    fun setup() {
        fake = FakeNip60Store()
        fake.testMintUrl = TEST_MINT_URL
    }

    @After
    fun teardown() {
        fake.reset()
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // publishProofs Tests
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    fun `publishProofs returns event ID on success`() = runTest(mainDispatcherRule.testDispatcher) {
        val proofs = listOf(testProof(64), testProof(32))

        val eventId = fake.publishProofs(proofs, TEST_MINT_URL)

        assertNotNull("Should return event ID", eventId)
        assertTrue("Event ID should start with 'event-'", eventId!!.startsWith("event-"))
    }

    @Test
    fun `publishProofs stores proofs by event ID`() = runTest(mainDispatcherRule.testDispatcher) {
        val proofs = listOf(testProof(64), testProof(32))

        val eventId = fake.publishProofs(proofs, TEST_MINT_URL)

        assertTrue("Proofs should be stored", fake.proofsByEventId.containsKey(eventId))
        assertEquals("Should store all proofs", 2, fake.proofsByEventId[eventId]!!.size)
    }

    @Test
    fun `publishProofs returns null when shouldFailPublish is true`() = runTest(mainDispatcherRule.testDispatcher) {
        fake.shouldFailPublish = true

        val eventId = fake.publishProofs(listOf(testProof(64)), TEST_MINT_URL)

        assertNull("Should return null on failure", eventId)
        assertTrue("Should not store proofs on failure", fake.proofsByEventId.isEmpty())
    }

    @Test
    fun `publishProofs increments attempt count`() = runTest(mainDispatcherRule.testDispatcher) {
        fake.publishProofs(listOf(testProof(64)), TEST_MINT_URL)
        fake.publishProofs(listOf(testProof(32)), TEST_MINT_URL)

        assertEquals("Should count 2 attempts", 2, fake.publishAttemptCount)
    }

    @Test
    fun `publishProofs logs with del array info when provided`() = runTest(mainDispatcherRule.testDispatcher) {
        val deletedIds = listOf("abc12345", "def67890")

        fake.publishProofs(listOf(testProof(64)), TEST_MINT_URL, deletedIds)

        assertTrue("Should log del array info",
            fake.callLog.any { it.contains("withDel") })
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // fetchProofs Tests
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    fun `fetchProofs returns empty list when no proofs`() = runTest(mainDispatcherRule.testDispatcher) {
        val proofs = fake.fetchProofs()

        assertTrue("Should return empty list", proofs.isEmpty())
    }

    @Test
    fun `fetchProofs returns all stored proofs`() = runTest(mainDispatcherRule.testDispatcher) {
        fake.seedProofs(listOf(testProof(64), testProof(32)), TEST_MINT_URL)

        val proofs = fake.fetchProofs()

        assertEquals("Should return 2 proofs", 2, proofs.size)
        assertEquals("Total should be 96", 96L, proofs.sumOf { it.amount })
    }

    @Test
    fun `fetchProofs excludes deleted events`() = runTest(mainDispatcherRule.testDispatcher) {
        val eventId = fake.seedProofs(listOf(testProof(64)), TEST_MINT_URL)
        fake.seedProofs(listOf(testProof(32)), TEST_MINT_URL)

        fake.deleteProofEvents(listOf(eventId))
        val proofs = fake.fetchProofs()

        assertEquals("Should only return undeleted proofs", 1, proofs.size)
        assertEquals("Should return the 32 sat proof", 32L, proofs[0].amount)
    }

    @Test
    fun `fetchProofs returns empty when shouldFailFetch is true`() = runTest(mainDispatcherRule.testDispatcher) {
        fake.seedProofs(listOf(testProof(64)), TEST_MINT_URL)
        fake.shouldFailFetch = true

        val proofs = fake.fetchProofs()

        assertTrue("Should return empty list on failure", proofs.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // selectProofsForSpending Tests
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    fun `selectProofsForSpending returns null when insufficient funds`() = runTest(mainDispatcherRule.testDispatcher) {
        fake.seedProofs(listOf(testProof(50)), TEST_MINT_URL)

        val selection = fake.selectProofsForSpending(100, TEST_MINT_URL)

        assertNull("Should return null for insufficient funds", selection)
    }

    @Test
    fun `selectProofsForSpending returns correct selection`() = runTest(mainDispatcherRule.testDispatcher) {
        fake.seedProofs(listOf(testProof(64), testProof(32), testProof(16)), TEST_MINT_URL)

        val selection = fake.selectProofsForSpending(50, TEST_MINT_URL)

        assertNotNull("Should return selection", selection)
        assertTrue("Total should be >= 50", selection!!.totalAmount >= 50)
        assertEquals("Change should be correct", selection.totalAmount - 50, selection.changeAmount)
    }

    @Test
    fun `selectProofsForSpending filters by mint URL`() = runTest(mainDispatcherRule.testDispatcher) {
        fake.seedProofs(listOf(testProof(100)), "https://mint-a.example")
        fake.seedProofs(listOf(testProof(50)), "https://mint-b.example")

        val selectionA = fake.selectProofsForSpending(75, "https://mint-a.example")
        val selectionB = fake.selectProofsForSpending(75, "https://mint-b.example")

        assertNotNull("Should find proofs from mint A", selectionA)
        assertNull("Should not have enough from mint B", selectionB)
    }

    @Test
    fun `selectProofsForSpending selects from all mints when no filter`() = runTest(mainDispatcherRule.testDispatcher) {
        fake.seedProofs(listOf(testProof(40)), "https://mint-a.example")
        fake.seedProofs(listOf(testProof(40)), "https://mint-b.example")

        val selection = fake.selectProofsForSpending(75, null)

        assertNotNull("Should select from both mints", selection)
        assertTrue("Total should be >= 75", selection!!.totalAmount >= 75)
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // deleteProofEvents Tests
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    fun `deleteProofEvents removes proofs from storage`() = runTest(mainDispatcherRule.testDispatcher) {
        val eventId = fake.seedProofs(listOf(testProof(64)), TEST_MINT_URL)

        fake.deleteProofEvents(listOf(eventId))

        assertFalse("Event should be removed", fake.proofsByEventId.containsKey(eventId))
        assertTrue("Event should be in deleted set", fake.deletedEventIds.contains(eventId))
    }

    @Test
    fun `deleteProofEvents logs with event ID prefix`() = runTest(mainDispatcherRule.testDispatcher) {
        val eventId = "abc123456789"
        fake.proofsByEventId[eventId] = listOf(testProof(64))

        fake.deleteProofEvents(listOf(eventId))

        assertTrue("Should log delete with event ID prefix",
            fake.callLog.any { it.startsWith("deleteProofEvents:abc12345") })
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // getBalance Tests
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    fun `getBalance returns correct total`() = runTest(mainDispatcherRule.testDispatcher) {
        fake.seedProofs(listOf(testProof(64), testProof(32), testProof(16)), TEST_MINT_URL)

        val balance = fake.getBalance()

        assertEquals("Balance should be 112", 112L, balance.availableSats)
        assertEquals("Pending should be 0", 0L, balance.pendingSats)
    }

    @Test
    fun `getBalance returns zero when no proofs`() = runTest(mainDispatcherRule.testDispatcher) {
        val balance = fake.getBalance()

        assertEquals("Balance should be 0", 0L, balance.availableSats)
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Wallet Metadata Tests
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    fun `publishWalletMetadata sets metadata state`() = runTest(mainDispatcherRule.testDispatcher) {
        val result = fake.publishWalletMetadata(TEST_MINT_URL)

        assertTrue("Should return true", result)
        assertTrue("Metadata should be marked as published", fake.metadataPublished)
        assertEquals("Mint URL should be stored", TEST_MINT_URL, fake.metadataMintUrl)
    }

    @Test
    fun `publishWalletMetadata returns false when shouldFailMetadata is true`() = runTest(mainDispatcherRule.testDispatcher) {
        fake.shouldFailMetadata = true

        val result = fake.publishWalletMetadata(TEST_MINT_URL)

        assertFalse("Should return false", result)
        assertFalse("Metadata should not be marked as published", fake.metadataPublished)
    }

    @Test
    fun `hasExistingWallet returns true when metadata published`() = runTest(mainDispatcherRule.testDispatcher) {
        fake.publishWalletMetadata(TEST_MINT_URL)

        assertTrue("Should detect existing wallet", fake.hasExistingWallet())
    }

    @Test
    fun `hasExistingWallet returns true when proofs exist`() = runTest(mainDispatcherRule.testDispatcher) {
        fake.seedProofs(listOf(testProof(64)), TEST_MINT_URL)

        assertTrue("Should detect existing wallet from proofs", fake.hasExistingWallet())
    }

    @Test
    fun `hasExistingWallet returns false when empty`() = runTest(mainDispatcherRule.testDispatcher) {
        assertFalse("Should return false for empty wallet", fake.hasExistingWallet())
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // restoreFromNostr Tests
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    fun `restoreFromNostr returns valid WalletState`() = runTest(mainDispatcherRule.testDispatcher) {
        fake.seedProofs(listOf(testProof(100)), TEST_MINT_URL)

        val state = fake.restoreFromNostr()

        assertNotNull("Should return WalletState", state)
        assertEquals("Mint URL should match", TEST_MINT_URL, state!!.mintUrl)
        assertEquals("Balance should match", 100L, state.balance.availableSats)
        assertEquals("Proof count should match", 1, state.proofCount)
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // deleteAllProofsFromNostr Tests
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    fun `deleteAllProofsFromNostr clears all data`() = runTest(mainDispatcherRule.testDispatcher) {
        fake.seedProofs(listOf(testProof(64)), TEST_MINT_URL)
        fake.publishWalletMetadata(TEST_MINT_URL)

        val count = fake.deleteAllProofsFromNostr()

        assertEquals("Should return count of deleted events", 1, count)
        assertTrue("Proofs should be cleared", fake.proofsByEventId.isEmpty())
        assertFalse("Metadata flag should be cleared", fake.metadataPublished)
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Call Log Tests
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    fun `call log records all operations`() = runTest(mainDispatcherRule.testDispatcher) {
        fake.publishProofs(listOf(testProof(64)), TEST_MINT_URL)
        fake.fetchProofs()
        fake.clearCache()

        // publishProofs, fetchProofs:false, clearCache = 3 entries
        assertEquals("Should have 3 entries", 3, fake.callLog.size)
        assertEquals("First should be publishProofs", "publishProofs", fake.callLog[0])
    }

    @Test
    fun `getPublishCallCount returns correct count`() = runTest(mainDispatcherRule.testDispatcher) {
        fake.publishProofs(listOf(testProof(64)), TEST_MINT_URL)
        fake.publishProofs(listOf(testProof(32)), TEST_MINT_URL)
        fake.fetchProofs()

        assertEquals("Should count 2 publish calls", 2, fake.getPublishCallCount())
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Order Verification Tests
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    fun `verifyRepublishBeforeDelete returns true for correct order`() = runTest(mainDispatcherRule.testDispatcher) {
        fake.publishProofs(listOf(testProof(64)), TEST_MINT_URL)
        fake.deleteProofEvents(listOf("some-id"))

        assertTrue("Should verify correct order", fake.verifyRepublishBeforeDelete())
    }

    @Test
    fun `verifyRepublishBeforeDelete returns false for wrong order`() = runTest(mainDispatcherRule.testDispatcher) {
        fake.deleteProofEvents(listOf("some-id"))
        fake.publishProofs(listOf(testProof(64)), TEST_MINT_URL)

        assertFalse("Should detect wrong order", fake.verifyRepublishBeforeDelete())
    }

    @Test
    fun `verifyRepublishBeforeDelete returns false when missing operations`() = runTest(mainDispatcherRule.testDispatcher) {
        // Only publish, no delete
        fake.publishProofs(listOf(testProof(64)), TEST_MINT_URL)

        assertFalse("Should return false when delete missing", fake.verifyRepublishBeforeDelete())
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // Reset Tests
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    fun `reset clears all state`() = runTest(mainDispatcherRule.testDispatcher) {
        // Setup state
        fake.seedProofs(listOf(testProof(100)), TEST_MINT_URL)
        fake.publishWalletMetadata(TEST_MINT_URL)
        fake.shouldFailPublish = true
        fake.publishAttemptCount = 5

        // Reset
        fake.reset()

        // Verify all cleared
        assertTrue("Proofs cleared", fake.proofsByEventId.isEmpty())
        assertTrue("Mint URLs cleared", fake.mintUrlByEventId.isEmpty())
        assertTrue("Deleted IDs cleared", fake.deletedEventIds.isEmpty())
        assertFalse("Metadata flag cleared", fake.metadataPublished)
        assertNull("Metadata mint URL cleared", fake.metadataMintUrl)
        assertTrue("Call log cleared", fake.callLog.isEmpty())
        assertFalse("Failure flag cleared", fake.shouldFailPublish)
        assertFalse("Fetch failure flag cleared", fake.shouldFailFetch)
        assertFalse("Metadata failure flag cleared", fake.shouldFailMetadata)
        assertEquals("Attempt count cleared", 0, fake.publishAttemptCount)
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // seedProofs Helper Tests
    // ═══════════════════════════════════════════════════════════════════════════════

    @Test
    fun `seedProofs creates event without logging`() = runTest(mainDispatcherRule.testDispatcher) {
        fake.seedProofs(listOf(testProof(64)), TEST_MINT_URL)

        // seedProofs should NOT add to call log (it's for test setup)
        assertTrue("Call log should be empty", fake.callLog.isEmpty())
        assertEquals("Should have 1 event", 1, fake.proofsByEventId.size)
    }

    @Test
    fun `seedProofs returns event ID for deletion`() = runTest(mainDispatcherRule.testDispatcher) {
        val eventId = fake.seedProofs(listOf(testProof(64)), TEST_MINT_URL)

        assertTrue("Event ID should start with 'seed-event-'", eventId.startsWith("seed-event-"))
        assertTrue("Event should be stored", fake.proofsByEventId.containsKey(eventId))
    }
}
