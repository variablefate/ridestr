package com.ridestr.common.payment.cashu

import android.content.Context
import com.ridestr.common.payment.WalletKeyManager
import com.ridestr.common.payment.WalletKeypair
import com.ridestr.common.payment.WalletStorage
import com.ridestr.common.payment.cashu.CashuBackend.HtlcClaimOutcome
import com.ridestr.common.payment.cashu.CashuBackend.HtlcSwapOutcome
import com.ridestr.common.payment.cashu.CashuBackend.HtlcLockResult
import com.ridestr.common.payment.cashu.CashuBackend.HtlcClaimResult
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for CashuBackend error handling and typed outcome classes.
 *
 * These tests verify:
 * 1. HtlcSwapOutcome sealed class completeness and exhaustiveness
 * 2. HtlcClaimOutcome sealed class completeness and exhaustiveness
 * 3. FakeMintApi can inject different response types
 * 4. MintApi.Result variants map correctly to outcomes
 * 5. Full integration tests with real CashuBackend instantiation (Phase 6)
 *
 * The typed outcomes enable:
 * - Proper error propagation to callers (no more lost error context)
 * - Test injection via FakeMintApi
 * - Compile-time exhaustiveness checking via sealed classes
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CashuBackendErrorTest {

    // ==============================
    // Test Data
    // ==============================

    companion object {
        private const val TEST_MINT_URL = "https://mint.test.example"

        // Valid keyset for blinding operations
        // Note: Placeholder pubkeys are acceptable for error-path tests because
        // tests fail at FakeMintApi BEFORE any curve validation/unblinding.
        private val TEST_KEYSET = MintKeyset(
            id = "009a1f293253e41e",
            unit = "sat",
            keys = mapOf(
                1L to "02a9acc1e48c25eeeb9289b5031cc57da9fe72f3fe2861d264bdc074209b107ba2",
                2L to "020000000000000000000000000000000000000000000000000000000000000001",
                4L to "020000000000000000000000000000000000000000000000000000000000000002",
                8L to "020000000000000000000000000000000000000000000000000000000000000003",
                16L to "020000000000000000000000000000000000000000000000000000000000000004",
                32L to "020000000000000000000000000000000000000000000000000000000000000005",
                64L to "020000000000000000000000000000000000000000000000000000000000000006",
                128L to "020000000000000000000000000000000000000000000000000000000000000007"
            )
        )

        // CRITICAL: Preimage and hash must match or claimHtlcTokenWithProofs returns early
        // at line 1021-1024 with "Preimage does not match payment_hash"
        private const val TEST_PREIMAGE = "0000000000000000000000000000000000000000000000000000000000000000"
        // SHA256 of 32 zero bytes - pre-computed to avoid native library calls at class init
        private const val TEST_PAYMENT_HASH = "66687aadf862bd776c8fc18b8e9f8e20089714856ee233b3902a591d0d5f2925"

        // BIP-39 test mnemonic (do not use in production!)
        private const val TEST_MNEMONIC = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
    }

    private lateinit var testSeed: ByteArray

    // ==============================
    // MockK Setup
    // ==============================

    @MockK(relaxed = true)
    private lateinit var mockKeyManager: WalletKeyManager

    @MockK(relaxed = true)
    private lateinit var mockStorage: WalletStorage

    private lateinit var fakeMintApi: FakeMintApi
    private lateinit var backend: CashuBackend

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        fakeMintApi = FakeMintApi()

        // Pre-computed BIP-39 seed for "abandon ... about" mnemonic
        // Using hardcoded bytes to avoid native library calls during test setup
        testSeed = ByteArray(64) { 0x42 }  // Placeholder seed for test

        // Default: return a mocked keypair for most tests (avoids native secp256k1)
        val mockKeypair = mockk<WalletKeypair> {
            every { publicKeyHex } returns "02" + "a".repeat(64)
            every { privateKeyHex } returns "0123456789abcdef".repeat(4)
            every { signSchnorr(any()) } returns "a".repeat(128)  // Valid 64-byte hex signature
        }
        every { mockKeyManager.getOrCreateWalletKeypair() } returns mockKeypair

        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>()
        backend = CashuBackend(context, mockKeyManager, mockStorage, fakeMintApi)

        // CRITICAL: Inject test state to bypass ALL HTTP
        backend.setTestState(TEST_MINT_URL, TEST_KEYSET, testSeed)
    }

    // ==============================
    // Helper Functions
    // ==============================

    /**
     * Create a valid HTLC token for claim tests.
     * Uses the matching preimage/hash pair defined in companion object.
     */
    private fun createValidHtlcToken(): String {
        // Create HTLC secret with the MATCHING payment hash
        val htlcSecret = """["HTLC",{"nonce":"abc123","data":"$TEST_PAYMENT_HASH"}]"""

        // HtlcProof is NESTED inside CashuTokenCodec object
        val htlcProof = CashuTokenCodec.HtlcProof(
            amount = 64,
            id = TEST_KEYSET.id,
            secret = htlcSecret,
            C = "02a9acc1e48c25eeeb9289b5031cc57da9fe72f3fe2861d264bdc074209b107ba2"
        )

        return CashuTokenCodec.encodeHtlcProofsAsToken(listOf(htlcProof), TEST_MINT_URL)
    }

    /**
     * Create a minimal test proof for swap tests.
     */
    private fun testProof(amount: Long = 64) = CashuProof(
        amount = amount,
        id = TEST_KEYSET.id,
        secret = "test_secret_${System.nanoTime()}",
        C = "02a9acc1e48c25eeeb9289b5031cc57da9fe72f3fe2861d264bdc074209b107ba2"
    )

    // ==============================
    // HtlcSwapOutcome Tests (Unit)
    // ==============================

    @Test
    fun `HtlcSwapOutcome Success wraps HtlcLockResult`() {
        val lockResult = HtlcLockResult(
            htlcToken = "cashuAtest...",
            changeProofs = emptyList(),
            pendingOpId = "test-op-id"
        )
        val outcome: HtlcSwapOutcome = HtlcSwapOutcome.Success(lockResult)

        assertTrue(outcome is HtlcSwapOutcome.Success)
        assertEquals(lockResult, (outcome as HtlcSwapOutcome.Success).result)
        assertEquals("cashuAtest...", outcome.result.htlcToken)
    }

    @Test
    fun `HtlcSwapOutcome Failure MintUnreachable is distinct`() {
        val outcome: HtlcSwapOutcome = HtlcSwapOutcome.Failure.MintUnreachable

        assertTrue(outcome is HtlcSwapOutcome.Failure)
        assertTrue(outcome is HtlcSwapOutcome.Failure.MintUnreachable)
    }

    @Test
    fun `HtlcSwapOutcome Failure SwapRejected is distinct`() {
        val outcome: HtlcSwapOutcome = HtlcSwapOutcome.Failure.SwapRejected

        assertTrue(outcome is HtlcSwapOutcome.Failure)
        assertTrue(outcome is HtlcSwapOutcome.Failure.SwapRejected)
    }

    @Test
    fun `HtlcSwapOutcome Failure Other stores message`() {
        val message = "Custom error message"
        val outcome: HtlcSwapOutcome = HtlcSwapOutcome.Failure.Other(message)

        assertTrue(outcome is HtlcSwapOutcome.Failure)
        assertTrue(outcome is HtlcSwapOutcome.Failure.Other)
        assertEquals(message, (outcome as HtlcSwapOutcome.Failure.Other).message)
    }

    /**
     * Compile-time exhaustiveness check for HtlcSwapOutcome.
     * If a new variant is added without handling it here, this test won't compile.
     */
    @Test
    fun `HtlcSwapOutcome when expression is exhaustive`() {
        val outcomes = listOf<HtlcSwapOutcome>(
            HtlcSwapOutcome.Success(HtlcLockResult("token", emptyList(), "op-id")),
            HtlcSwapOutcome.Failure.MintUnreachable,
            HtlcSwapOutcome.Failure.SwapRejected,
            HtlcSwapOutcome.Failure.Other("msg")
        )

        for (outcome in outcomes) {
            // No else branch = compile-time exhaustiveness check
            val description = when (outcome) {
                is HtlcSwapOutcome.Success -> "success"
                is HtlcSwapOutcome.Failure.MintUnreachable -> "mint unreachable"
                is HtlcSwapOutcome.Failure.SwapRejected -> "swap rejected"
                is HtlcSwapOutcome.Failure.Other -> "other: ${outcome.message}"
            }
            assertFalse(description.isBlank())
        }
    }

    // ==============================
    // HtlcClaimOutcome Tests (Unit)
    // ==============================

    @Test
    fun `HtlcClaimOutcome Success wraps HtlcClaimResult`() {
        val claimResult = HtlcClaimResult(
            settlementProof = "proof123",
            receivedProofs = emptyList(),
            amountSats = 1000,
            mintUrl = "https://mint.example.com",
            pendingOpId = "test-op-id"
        )
        val outcome: HtlcClaimOutcome = HtlcClaimOutcome.Success(claimResult)

        assertTrue(outcome is HtlcClaimOutcome.Success)
        assertEquals(claimResult, (outcome as HtlcClaimOutcome.Success).result)
        assertEquals(1000L, outcome.result.amountSats)
    }

    @Test
    fun `HtlcClaimOutcome Failure TokenParseFailed is distinct`() {
        val outcome: HtlcClaimOutcome = HtlcClaimOutcome.Failure.TokenParseFailed

        assertTrue(outcome is HtlcClaimOutcome.Failure)
        assertTrue(outcome is HtlcClaimOutcome.Failure.TokenParseFailed)
    }

    @Test
    fun `HtlcClaimOutcome Failure SignatureFailed is distinct`() {
        val outcome: HtlcClaimOutcome = HtlcClaimOutcome.Failure.SignatureFailed

        assertTrue(outcome is HtlcClaimOutcome.Failure)
        assertTrue(outcome is HtlcClaimOutcome.Failure.SignatureFailed)
    }

    @Test
    fun `HtlcClaimOutcome Failure MintUnreachable is distinct`() {
        val outcome: HtlcClaimOutcome = HtlcClaimOutcome.Failure.MintUnreachable

        assertTrue(outcome is HtlcClaimOutcome.Failure)
        assertTrue(outcome is HtlcClaimOutcome.Failure.MintUnreachable)
    }

    @Test
    fun `HtlcClaimOutcome Failure MintRejected is distinct`() {
        val outcome: HtlcClaimOutcome = HtlcClaimOutcome.Failure.MintRejected

        assertTrue(outcome is HtlcClaimOutcome.Failure)
        assertTrue(outcome is HtlcClaimOutcome.Failure.MintRejected)
    }

    @Test
    fun `HtlcClaimOutcome Failure Other stores message`() {
        val message = "Custom claim error"
        val outcome: HtlcClaimOutcome = HtlcClaimOutcome.Failure.Other(message)

        assertTrue(outcome is HtlcClaimOutcome.Failure)
        assertTrue(outcome is HtlcClaimOutcome.Failure.Other)
        assertEquals(message, (outcome as HtlcClaimOutcome.Failure.Other).message)
    }

    /**
     * Compile-time exhaustiveness check for HtlcClaimOutcome.
     * If a new variant is added without handling it here, this test won't compile.
     */
    @Test
    fun `HtlcClaimOutcome when expression is exhaustive`() {
        val outcomes = listOf<HtlcClaimOutcome>(
            HtlcClaimOutcome.Success(HtlcClaimResult("proof", emptyList(), 100, "url", "op-id")),
            HtlcClaimOutcome.Failure.TokenParseFailed,
            HtlcClaimOutcome.Failure.SignatureFailed,
            HtlcClaimOutcome.Failure.MintUnreachable,
            HtlcClaimOutcome.Failure.MintRejected,
            HtlcClaimOutcome.Failure.Other("msg")
        )

        for (outcome in outcomes) {
            // No else branch = compile-time exhaustiveness check
            val description = when (outcome) {
                is HtlcClaimOutcome.Success -> "success"
                is HtlcClaimOutcome.Failure.TokenParseFailed -> "token parse failed"
                is HtlcClaimOutcome.Failure.SignatureFailed -> "signature failed"
                is HtlcClaimOutcome.Failure.MintUnreachable -> "mint unreachable"
                is HtlcClaimOutcome.Failure.MintRejected -> "mint rejected"
                is HtlcClaimOutcome.Failure.Other -> "other: ${outcome.message}"
            }
            assertFalse(description.isBlank())
        }
    }

    // ==============================
    // MintApi.Result Tests (Unit)
    // ==============================

    @Test
    fun `MintApi Result Success contains body`() {
        val body = """{"signatures":[]}"""
        val result: MintApi.Result = MintApi.Result.Success(body)

        assertTrue(result is MintApi.Result.Success)
        assertEquals(body, (result as MintApi.Result.Success).body)
    }

    @Test
    fun `MintApi Result HttpError contains code and body`() {
        val result: MintApi.Result = MintApi.Result.HttpError(400, "Bad request")

        assertTrue(result is MintApi.Result.HttpError)
        assertEquals(400, (result as MintApi.Result.HttpError).code)
        assertEquals("Bad request", result.body)
    }

    @Test
    fun `MintApi Result TransportFailure contains cause`() {
        val result: MintApi.Result = MintApi.Result.TransportFailure("Connection timeout")

        assertTrue(result is MintApi.Result.TransportFailure)
        assertEquals("Connection timeout", (result as MintApi.Result.TransportFailure).cause)
    }

    /**
     * Compile-time exhaustiveness check for MintApi.Result.
     */
    @Test
    fun `MintApi Result when expression is exhaustive`() {
        val results = listOf<MintApi.Result>(
            MintApi.Result.Success("body"),
            MintApi.Result.HttpError(500, "error"),
            MintApi.Result.TransportFailure("timeout")
        )

        for (result in results) {
            val description = when (result) {
                is MintApi.Result.Success -> "success: ${result.body}"
                is MintApi.Result.HttpError -> "http error: ${result.code}"
                is MintApi.Result.TransportFailure -> "transport: ${result.cause}"
            }
            assertFalse(description.isBlank())
        }
    }

    // ==============================
    // FakeMintApi Tests (Unit)
    // ==============================

    @Test
    fun `FakeMintApi returns queued success response`() {
        val fakeMintApi = FakeMintApi()
        val expectedBody = """{"signatures":[{"amount":1,"id":"test","C_":"abc"}]}"""
        fakeMintApi.queueSwapSuccess(expectedBody)

        val result = runBlocking {
            fakeMintApi.postSwap("https://mint.example.com", """{"inputs":[],"outputs":[]}""")
        }

        assertTrue(result is MintApi.Result.Success)
        assertEquals(expectedBody, (result as MintApi.Result.Success).body)
        assertEquals(1, fakeMintApi.swapCalls.size)
    }

    @Test
    fun `FakeMintApi returns queued HTTP error`() {
        val fakeMintApi = FakeMintApi()
        fakeMintApi.queueSwapHttpError(400, "Token already spent")

        val result = runBlocking {
            fakeMintApi.postSwap("https://mint.example.com", """{}""")
        }

        assertTrue(result is MintApi.Result.HttpError)
        assertEquals(400, (result as MintApi.Result.HttpError).code)
        assertEquals("Token already spent", result.body)
    }

    @Test
    fun `FakeMintApi returns queued transport failure`() {
        val fakeMintApi = FakeMintApi()
        fakeMintApi.queueSwapTransportFailure("DNS resolution failed")

        val result = runBlocking {
            fakeMintApi.postSwap("https://mint.example.com", """{}""")
        }

        assertTrue(result is MintApi.Result.TransportFailure)
        assertEquals("DNS resolution failed", (result as MintApi.Result.TransportFailure).cause)
    }

    @Test
    fun `FakeMintApi returns transport failure when no response queued`() {
        val fakeMintApi = FakeMintApi()
        // No response queued

        val result = runBlocking {
            fakeMintApi.postSwap("https://mint.example.com", """{}""")
        }

        assertTrue(result is MintApi.Result.TransportFailure)
        assertTrue((result as MintApi.Result.TransportFailure).cause.contains("No response configured"))
    }

    @Test
    fun `FakeMintApi consumes responses in order`() {
        val fakeMintApi = FakeMintApi()
        fakeMintApi.queueSwapSuccess("""{"first":true}""")
        fakeMintApi.queueSwapHttpError(500, "server error")
        fakeMintApi.queueSwapTransportFailure("timeout")

        runBlocking {
            val first = fakeMintApi.postSwap("url", "body1")
            assertTrue(first is MintApi.Result.Success)

            val second = fakeMintApi.postSwap("url", "body2")
            assertTrue(second is MintApi.Result.HttpError)

            val third = fakeMintApi.postSwap("url", "body3")
            assertTrue(third is MintApi.Result.TransportFailure)
        }

        assertEquals(3, fakeMintApi.swapCalls.size)
    }

    @Test
    fun `FakeMintApi records call details`() {
        val fakeMintApi = FakeMintApi()
        fakeMintApi.queueSwapSuccess("{}")

        runBlocking {
            fakeMintApi.postSwap("https://mint.example.com/v1/swap", """{"test":"body"}""")
        }

        assertEquals(1, fakeMintApi.swapCalls.size)
        assertEquals("https://mint.example.com/v1/swap", fakeMintApi.swapCalls[0].mintUrl)
        assertEquals("""{"test":"body"}""", fakeMintApi.swapCalls[0].requestBody)
    }

    @Test
    fun `FakeMintApi reset clears all state`() {
        val fakeMintApi = FakeMintApi()
        fakeMintApi.queueSwapSuccess("{}")
        fakeMintApi.queueCheckStateSuccess("{}")

        runBlocking {
            fakeMintApi.postSwap("url", "body")
        }

        assertEquals(1, fakeMintApi.swapCalls.size)
        assertEquals(1, fakeMintApi.checkStateResponses.size)

        fakeMintApi.reset()

        assertEquals(0, fakeMintApi.swapCalls.size)
        assertEquals(0, fakeMintApi.swapResponses.size)
        assertEquals(0, fakeMintApi.checkStateCalls.size)
        assertEquals(0, fakeMintApi.checkStateResponses.size)
    }

    @Test
    fun `FakeMintApi handles checkState separately from swap`() {
        val fakeMintApi = FakeMintApi()
        fakeMintApi.queueSwapSuccess("""{"swap":"response"}""")
        fakeMintApi.queueCheckStateSuccess("""{"states":[]}""")

        runBlocking {
            val swapResult = fakeMintApi.postSwap("url", "swap-body")
            val checkResult = fakeMintApi.postCheckState("url", "check-body")

            assertTrue(swapResult is MintApi.Result.Success)
            assertEquals("""{"swap":"response"}""", (swapResult as MintApi.Result.Success).body)

            assertTrue(checkResult is MintApi.Result.Success)
            assertEquals("""{"states":[]}""", (checkResult as MintApi.Result.Success).body)
        }

        assertEquals(1, fakeMintApi.swapCalls.size)
        assertEquals(1, fakeMintApi.checkStateCalls.size)
    }

    // ==============================
    // Error Mapping Pattern Tests (Unit)
    // ==============================

    /**
     * Demonstrates the error mapping pattern from MintApi.Result to HtlcSwapOutcome.
     * This pattern is used in CashuBackend.createHtlcTokenFromProofs().
     */
    @Test
    fun `MintApi Result maps to HtlcSwapOutcome failure variants`() {
        // TransportFailure -> MintUnreachable
        val transportFailure = MintApi.Result.TransportFailure("timeout")
        val swapOutcome1 = mapSwapFailure(transportFailure)
        assertTrue(swapOutcome1 is HtlcSwapOutcome.Failure.MintUnreachable)

        // HttpError -> SwapRejected
        val httpError = MintApi.Result.HttpError(400, "bad request")
        val swapOutcome2 = mapSwapFailure(httpError)
        assertTrue(swapOutcome2 is HtlcSwapOutcome.Failure.SwapRejected)
    }

    /**
     * Demonstrates the error mapping pattern from MintApi.Result to HtlcClaimOutcome.
     * This pattern is used in CashuBackend.claimHtlcTokenWithProofs().
     */
    @Test
    fun `MintApi Result maps to HtlcClaimOutcome failure variants`() {
        // TransportFailure -> MintUnreachable
        val transportFailure = MintApi.Result.TransportFailure("connection refused")
        val claimOutcome1 = mapClaimFailure(transportFailure)
        assertTrue(claimOutcome1 is HtlcClaimOutcome.Failure.MintUnreachable)

        // HttpError -> MintRejected
        val httpError = MintApi.Result.HttpError(400, "token already spent")
        val claimOutcome2 = mapClaimFailure(httpError)
        assertTrue(claimOutcome2 is HtlcClaimOutcome.Failure.MintRejected)
    }

    // Helper functions demonstrating the mapping pattern
    private fun mapSwapFailure(result: MintApi.Result): HtlcSwapOutcome.Failure {
        return when (result) {
            is MintApi.Result.HttpError -> HtlcSwapOutcome.Failure.SwapRejected
            is MintApi.Result.TransportFailure -> HtlcSwapOutcome.Failure.MintUnreachable
            is MintApi.Result.Success -> throw IllegalArgumentException("Success is not a failure")
        }
    }

    private fun mapClaimFailure(result: MintApi.Result): HtlcClaimOutcome.Failure {
        return when (result) {
            is MintApi.Result.HttpError -> HtlcClaimOutcome.Failure.MintRejected
            is MintApi.Result.TransportFailure -> HtlcClaimOutcome.Failure.MintUnreachable
            is MintApi.Result.Success -> throw IllegalArgumentException("Success is not a failure")
        }
    }

    // ==============================
    // Integration Tests (Phase 6)
    // ==============================

    /**
     * Test: Invalid token format triggers TokenParseFailed.
     * This path doesn't require any HTTP calls - fails immediately at parsing.
     */
    @Test
    fun `claimHtlcTokenWithProofs returns TokenParseFailed for invalid token`() = runBlocking {
        // State already injected in setup() via setTestState() - no HTTP calls needed
        val outcome = backend.claimHtlcTokenWithProofs("not_a_valid_cashu_token", TEST_PREIMAGE)

        assertTrue(
            "Expected TokenParseFailed but got: $outcome",
            outcome is HtlcClaimOutcome.Failure.TokenParseFailed
        )
    }

    /**
     * Test: Transport failure during swap triggers MintUnreachable.
     */
    @Test
    fun `claimHtlcTokenWithProofs returns MintUnreachable on transport failure`() = runBlocking {
        // Queue transport failure for the swap call
        fakeMintApi.queueSwapTransportFailure("Connection timeout")

        val validToken = createValidHtlcToken()
        val outcome = backend.claimHtlcTokenWithProofs(validToken, TEST_PREIMAGE)

        assertTrue(
            "Expected MintUnreachable but got: $outcome",
            outcome is HtlcClaimOutcome.Failure.MintUnreachable
        )
    }

    /**
     * Test: HTTP error during swap triggers MintRejected.
     */
    @Test
    fun `claimHtlcTokenWithProofs returns MintRejected on HTTP error`() = runBlocking {
        // Queue HTTP error for the swap call
        fakeMintApi.queueSwapHttpError(400, """{"detail": "Invalid witness"}""")

        val validToken = createValidHtlcToken()
        val outcome = backend.claimHtlcTokenWithProofs(validToken, TEST_PREIMAGE)

        assertTrue(
            "Expected MintRejected but got: $outcome",
            outcome is HtlcClaimOutcome.Failure.MintRejected
        )
    }

    /**
     * Test: Signing failure triggers SignatureFailed.
     * Uses MockK to make signSchnorr() return null.
     */
    @Test
    fun `claimHtlcTokenWithProofs returns SignatureFailed when signing fails`() = runBlocking {
        // Override the default keypair with a mock that returns null from signSchnorr
        val mockKeypair = mockk<WalletKeypair> {
            every { signSchnorr(any()) } returns null
            every { publicKeyHex } returns "02" + "a".repeat(64)
        }
        every { mockKeyManager.getOrCreateWalletKeypair() } returns mockKeypair

        val validToken = createValidHtlcToken()
        val outcome = backend.claimHtlcTokenWithProofs(validToken, TEST_PREIMAGE)

        assertTrue(
            "Expected SignatureFailed but got: $outcome",
            outcome is HtlcClaimOutcome.Failure.SignatureFailed
        )
    }

    /**
     * Test: Transport failure during createHtlcTokenFromProofs triggers MintUnreachable.
     */
    @Test
    fun `createHtlcTokenFromProofs returns MintUnreachable on transport failure`() = runBlocking {
        fakeMintApi.queueSwapTransportFailure("DNS failure")

        val inputProofs = listOf(testProof(64))
        val outcome = backend.createHtlcTokenFromProofs(
            inputProofs = inputProofs,
            paymentHash = TEST_PAYMENT_HASH,
            amountSats = 64,
            driverPubKey = "02" + "b".repeat(64),
            riderPubKey = "02" + "a".repeat(64),
            locktime = System.currentTimeMillis() / 1000 + 3600
        )

        assertTrue(
            "Expected MintUnreachable but got: $outcome",
            outcome is HtlcSwapOutcome.Failure.MintUnreachable
        )
    }

    /**
     * Test: HTTP error during createHtlcTokenFromProofs triggers SwapRejected.
     */
    @Test
    fun `createHtlcTokenFromProofs returns SwapRejected on HTTP error`() = runBlocking {
        fakeMintApi.queueSwapHttpError(400, """{"detail": "Token already spent"}""")

        val inputProofs = listOf(testProof(64))
        val outcome = backend.createHtlcTokenFromProofs(
            inputProofs = inputProofs,
            paymentHash = TEST_PAYMENT_HASH,
            amountSats = 64,
            driverPubKey = "02" + "b".repeat(64),
            riderPubKey = "02" + "a".repeat(64),
            locktime = System.currentTimeMillis() / 1000 + 3600
        )

        assertTrue(
            "Expected SwapRejected but got: $outcome",
            outcome is HtlcSwapOutcome.Failure.SwapRejected
        )
    }
}
