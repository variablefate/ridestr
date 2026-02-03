package com.ridestr.common.payment.cashu

import com.ridestr.common.payment.cashu.CashuBackend.HtlcClaimOutcome
import com.ridestr.common.payment.cashu.CashuBackend.HtlcSwapOutcome
import com.ridestr.common.payment.cashu.CashuBackend.HtlcLockResult
import com.ridestr.common.payment.cashu.CashuBackend.HtlcClaimResult
import org.junit.Assert.*
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
    // HtlcSwapOutcome Tests
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
    // HtlcClaimOutcome Tests
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
    // MintApi.Result Tests
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
    // FakeMintApi Tests
    // ==============================

    @Test
    fun `FakeMintApi returns queued success response`() {
        val fakeMintApi = FakeMintApi()
        val expectedBody = """{"signatures":[{"amount":1,"id":"test","C_":"abc"}]}"""
        fakeMintApi.queueSwapSuccess(expectedBody)

        val result = kotlinx.coroutines.runBlocking {
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

        val result = kotlinx.coroutines.runBlocking {
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

        val result = kotlinx.coroutines.runBlocking {
            fakeMintApi.postSwap("https://mint.example.com", """{}""")
        }

        assertTrue(result is MintApi.Result.TransportFailure)
        assertEquals("DNS resolution failed", (result as MintApi.Result.TransportFailure).cause)
    }

    @Test
    fun `FakeMintApi returns transport failure when no response queued`() {
        val fakeMintApi = FakeMintApi()
        // No response queued

        val result = kotlinx.coroutines.runBlocking {
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

        kotlinx.coroutines.runBlocking {
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

        kotlinx.coroutines.runBlocking {
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

        kotlinx.coroutines.runBlocking {
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

        kotlinx.coroutines.runBlocking {
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
    // Error Mapping Pattern Tests
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
}
