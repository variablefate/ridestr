package com.ridestr.common.payment

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for HtlcResult sealed classes (LockResult and HtlcClaimResult).
 *
 * These tests verify:
 * 1. Sealed class exhaustiveness (compile-time check via when expression without else)
 * 2. Failure message defaults are meaningful
 * 3. All failure variants can be constructed with expected values
 */
class HtlcResultTest {

    // ==============================
    // LockResult Tests
    // ==============================

    @Test
    fun `LockResult Success contains escrow lock`() {
        val escrowLock = EscrowLock(
            escrowId = "test-id",
            htlcToken = "cashuAtest...",
            amountSats = 1000,
            expiresAt = System.currentTimeMillis() / 1000 + 900
        )
        val result: LockResult = LockResult.Success(escrowLock)

        assertTrue(result is LockResult.Success)
        assertEquals(escrowLock, (result as LockResult.Success).escrowLock)
    }

    @Test
    fun `LockResult NotConnected has meaningful default message`() {
        val failure = LockResult.Failure.NotConnected()
        assertFalse(failure.message.isBlank())
        assertEquals("Wallet not connected", failure.message)
    }

    @Test
    fun `LockResult InsufficientBalance stores required and available amounts`() {
        val failure = LockResult.Failure.InsufficientBalance(
            required = 1000,
            available = 500
        )
        assertEquals(1000L, failure.required)
        assertEquals(500L, failure.available)
        assertFalse(failure.message.isBlank())
    }

    @Test
    fun `LockResult ProofsSpent stores spent and total counts`() {
        val failure = LockResult.Failure.ProofsSpent(
            spentCount = 3,
            totalSelected = 5
        )
        assertEquals(3, failure.spentCount)
        assertEquals(5, failure.totalSelected)
        assertFalse(failure.message.isBlank())
    }

    @Test
    fun `LockResult MintUnreachable stores mint URL`() {
        val mintUrl = "https://mint.example.com"
        val failure = LockResult.Failure.MintUnreachable(mintUrl)
        assertEquals(mintUrl, failure.mintUrl)
        assertFalse(failure.message.isBlank())
    }

    @Test
    fun `LockResult SwapFailed has meaningful default message`() {
        val failure = LockResult.Failure.SwapFailed()
        assertFalse(failure.message.isBlank())
        assertEquals("HTLC swap failed", failure.message)
    }

    @Test
    fun `LockResult NoWalletKey has meaningful default message`() {
        val failure = LockResult.Failure.NoWalletKey()
        assertFalse(failure.message.isBlank())
        assertEquals("Wallet key not available", failure.message)
    }

    @Test
    fun `LockResult NipSyncNotInitialized has meaningful default message`() {
        val failure = LockResult.Failure.NipSyncNotInitialized()
        assertFalse(failure.message.isBlank())
        assertEquals("NIP-60 sync not initialized", failure.message)
    }

    @Test
    fun `LockResult MintUrlNotAvailable has meaningful default message`() {
        val failure = LockResult.Failure.MintUrlNotAvailable()
        assertFalse(failure.message.isBlank())
        assertEquals("Mint URL not available", failure.message)
    }

    @Test
    fun `LockResult VerificationFailed has meaningful default message`() {
        val failure = LockResult.Failure.VerificationFailed()
        assertFalse(failure.message.isBlank())
        assertEquals("NUT-07 verification failed", failure.message)
    }

    @Test
    fun `LockResult Other stores custom message`() {
        val customMessage = "Custom error message"
        val failure = LockResult.Failure.Other(customMessage)
        assertEquals(customMessage, failure.message)
    }

    /**
     * Compile-time exhaustiveness check for LockResult.
     * If a new failure variant is added without handling it here, this test won't compile.
     * The `when` expression without `else` enforces exhaustive handling.
     */
    @Test
    fun `LockResult when expression is exhaustive`() {
        val results = listOf<LockResult>(
            LockResult.Success(EscrowLock("id", "token", 100, 0)),
            LockResult.Failure.NotConnected(),
            LockResult.Failure.InsufficientBalance(100, 50),
            LockResult.Failure.ProofsSpent(1, 2),
            LockResult.Failure.MintUnreachable("url"),
            LockResult.Failure.SwapFailed(),
            LockResult.Failure.NoWalletKey(),
            LockResult.Failure.NipSyncNotInitialized(),
            LockResult.Failure.MintUrlNotAvailable(),
            LockResult.Failure.VerificationFailed(),
            LockResult.Failure.Other("msg")
        )

        for (result in results) {
            // No else branch = compile-time exhaustiveness check
            val description = when (result) {
                is LockResult.Success -> "success"
                is LockResult.Failure.NotConnected -> "not connected"
                is LockResult.Failure.InsufficientBalance -> "insufficient balance"
                is LockResult.Failure.ProofsSpent -> "proofs spent"
                is LockResult.Failure.MintUnreachable -> "mint unreachable"
                is LockResult.Failure.SwapFailed -> "swap failed"
                is LockResult.Failure.NoWalletKey -> "no wallet key"
                is LockResult.Failure.NipSyncNotInitialized -> "nip sync not initialized"
                is LockResult.Failure.MintUrlNotAvailable -> "mint url not available"
                is LockResult.Failure.VerificationFailed -> "verification failed"
                is LockResult.Failure.Other -> "other"
            }
            assertFalse(description.isBlank())
        }
    }

    // ==============================
    // HtlcClaimResult Tests
    // ==============================

    @Test
    fun `HtlcClaimResult Success contains settlement`() {
        val settlement = SettlementResult(
            amountSats = 1000,
            settlementProof = "proof123",
            timestamp = System.currentTimeMillis()
        )
        val result: HtlcClaimResult = HtlcClaimResult.Success(settlement)

        assertTrue(result is HtlcClaimResult.Success)
        assertEquals(settlement, (result as HtlcClaimResult.Success).settlement)
    }

    @Test
    fun `HtlcClaimResult NotConnected has meaningful default message`() {
        val failure = HtlcClaimResult.Failure.NotConnected()
        assertFalse(failure.message.isBlank())
        assertEquals("Wallet not connected", failure.message)
    }

    @Test
    fun `HtlcClaimResult PreimageMismatch stores payment hash`() {
        val paymentHash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val failure = HtlcClaimResult.Failure.PreimageMismatch(paymentHash)
        assertEquals(paymentHash, failure.paymentHash)
        assertFalse(failure.message.isBlank())
    }

    @Test
    fun `HtlcClaimResult TokenParseFailed has meaningful default message`() {
        val failure = HtlcClaimResult.Failure.TokenParseFailed()
        assertFalse(failure.message.isBlank())
        assertEquals("Failed to parse HTLC token", failure.message)
    }

    @Test
    fun `HtlcClaimResult MintRejected has meaningful default message`() {
        val failure = HtlcClaimResult.Failure.MintRejected()
        assertFalse(failure.message.isBlank())
        assertEquals("Mint rejected claim", failure.message)
    }

    @Test
    fun `HtlcClaimResult SignatureFailed has meaningful default message`() {
        val failure = HtlcClaimResult.Failure.SignatureFailed()
        assertFalse(failure.message.isBlank())
        assertEquals("Failed to sign proof", failure.message)
    }

    @Test
    fun `HtlcClaimResult Other stores custom message`() {
        val customMessage = "Custom claim error"
        val failure = HtlcClaimResult.Failure.Other(customMessage)
        assertEquals(customMessage, failure.message)
    }

    /**
     * Compile-time exhaustiveness check for HtlcClaimResult.
     * If a new failure variant is added without handling it here, this test won't compile.
     */
    @Test
    fun `HtlcClaimResult when expression is exhaustive`() {
        val results = listOf<HtlcClaimResult>(
            HtlcClaimResult.Success(SettlementResult(100, "proof", 0)),
            HtlcClaimResult.Failure.NotConnected(),
            HtlcClaimResult.Failure.PreimageMismatch("hash"),
            HtlcClaimResult.Failure.TokenParseFailed(),
            HtlcClaimResult.Failure.MintRejected(),
            HtlcClaimResult.Failure.SignatureFailed(),
            HtlcClaimResult.Failure.Other("msg")
        )

        for (result in results) {
            // No else branch = compile-time exhaustiveness check
            val description = when (result) {
                is HtlcClaimResult.Success -> "success"
                is HtlcClaimResult.Failure.NotConnected -> "not connected"
                is HtlcClaimResult.Failure.PreimageMismatch -> "preimage mismatch"
                is HtlcClaimResult.Failure.TokenParseFailed -> "token parse failed"
                is HtlcClaimResult.Failure.MintRejected -> "claim failed"
                is HtlcClaimResult.Failure.SignatureFailed -> "signature failed"
                is HtlcClaimResult.Failure.Other -> "other"
            }
            assertFalse(description.isBlank())
        }
    }

    // ==============================
    // Integration Tests (pattern matching)
    // ==============================

    @Test
    fun `LockResult Failure base class pattern matching works`() {
        val failures: List<LockResult.Failure> = listOf(
            LockResult.Failure.NotConnected(),
            LockResult.Failure.InsufficientBalance(100, 50),
            LockResult.Failure.ProofsSpent(1, 2),
            LockResult.Failure.SwapFailed(),
            LockResult.Failure.NoWalletKey(),
            LockResult.Failure.Other("test")
        )

        for (failure in failures) {
            // Can access message through base class
            assertFalse(failure.message.isBlank())
        }
    }

    @Test
    fun `HtlcClaimResult Failure base class pattern matching works`() {
        val failures: List<HtlcClaimResult.Failure> = listOf(
            HtlcClaimResult.Failure.NotConnected(),
            HtlcClaimResult.Failure.PreimageMismatch("hash"),
            HtlcClaimResult.Failure.MintRejected(),
            HtlcClaimResult.Failure.Other("test")
        )

        for (failure in failures) {
            // Can access message through base class
            assertFalse(failure.message.isBlank())
        }
    }
}
