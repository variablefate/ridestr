package com.ridestr.common.payment

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/**
 * Unit tests for PaymentCrypto - pure cryptographic functions for HTLC payment flows.
 *
 * These tests verify:
 * - Preimage generation produces valid 32-byte hex strings
 * - Payment hash computation matches known SHA256 test vectors
 * - Preimage verification round-trips correctly
 * - Hex validation edge cases
 */
class PaymentCryptoTest {

    // ==================== generatePreimage() Tests ====================

    @Test
    fun `generatePreimage returns 64 character hex string`() {
        val preimage = PaymentCrypto.generatePreimage()
        assertEquals("Preimage should be 64 hex characters (32 bytes)", 64, preimage.length)
    }

    @Test
    fun `generatePreimage returns valid lowercase hex`() {
        val preimage = PaymentCrypto.generatePreimage()
        assertTrue(
            "Preimage should be lowercase hex",
            preimage.all { it in '0'..'9' || it in 'a'..'f' }
        )
    }

    @Test
    fun `generatePreimage returns different values on each call`() {
        val preimages = List(100) { PaymentCrypto.generatePreimage() }.toSet()
        assertEquals("100 generated preimages should all be unique", 100, preimages.size)
    }

    @Test
    fun `generatePreimage passes isValidHex check`() {
        val preimage = PaymentCrypto.generatePreimage()
        assertTrue("Generated preimage should pass validation", PaymentCrypto.isValidHex(preimage))
    }

    // ==================== computePaymentHash() Tests ====================

    @Test
    fun `computePaymentHash with known test vector`() {
        // Known SHA256 test vector: SHA256(all zeros) = specific hash
        val allZerosPreimage = "0000000000000000000000000000000000000000000000000000000000000000"
        val expectedHash = sha256Hex(hexToBytes(allZerosPreimage))

        val actualHash = PaymentCrypto.computePaymentHash(allZerosPreimage)

        assertEquals("SHA256 of 32 zero bytes", expectedHash.lowercase(), actualHash.lowercase())
    }

    @Test
    fun `computePaymentHash with another test vector`() {
        // SHA256 of 0x01 repeated 32 times
        val onesPreimage = "0101010101010101010101010101010101010101010101010101010101010101"
        val expectedHash = sha256Hex(hexToBytes(onesPreimage))

        val actualHash = PaymentCrypto.computePaymentHash(onesPreimage)

        assertEquals(expectedHash.lowercase(), actualHash.lowercase())
    }

    @Test
    fun `computePaymentHash returns 64 character lowercase hex`() {
        val preimage = PaymentCrypto.generatePreimage()
        val hash = PaymentCrypto.computePaymentHash(preimage)

        assertEquals("Hash should be 64 characters", 64, hash.length)
        assertTrue("Hash should be lowercase hex", hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `computePaymentHash is deterministic`() {
        val preimage = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        val hash1 = PaymentCrypto.computePaymentHash(preimage)
        val hash2 = PaymentCrypto.computePaymentHash(preimage)

        assertEquals("Same preimage should produce same hash", hash1, hash2)
    }

    @Test
    fun `computePaymentHash different preimages produce different hashes`() {
        val preimage1 = "0000000000000000000000000000000000000000000000000000000000000001"
        val preimage2 = "0000000000000000000000000000000000000000000000000000000000000002"

        val hash1 = PaymentCrypto.computePaymentHash(preimage1)
        val hash2 = PaymentCrypto.computePaymentHash(preimage2)

        assertNotEquals("Different preimages should produce different hashes", hash1, hash2)
    }

    // ==================== verifyPreimage() Tests ====================

    @Test
    fun `verifyPreimage returns true for valid preimage-hash pair`() {
        val preimage = PaymentCrypto.generatePreimage()
        val hash = PaymentCrypto.computePaymentHash(preimage)

        assertTrue("Correct preimage should verify", PaymentCrypto.verifyPreimage(preimage, hash))
    }

    @Test
    fun `verifyPreimage returns false for wrong preimage`() {
        val preimage = PaymentCrypto.generatePreimage()
        val hash = PaymentCrypto.computePaymentHash(preimage)

        val wrongPreimage = PaymentCrypto.generatePreimage()
        assertFalse("Wrong preimage should not verify", PaymentCrypto.verifyPreimage(wrongPreimage, hash))
    }

    @Test
    fun `verifyPreimage returns false for invalid hex`() {
        assertFalse(
            "Invalid hex preimage should return false",
            PaymentCrypto.verifyPreimage("not_hex", "abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234abcd1234")
        )
    }

    @Test
    fun `verifyPreimage is case insensitive for hash`() {
        val preimage = PaymentCrypto.generatePreimage()
        val hashLower = PaymentCrypto.computePaymentHash(preimage)
        val hashUpper = hashLower.uppercase()

        assertTrue("Should verify with uppercase hash", PaymentCrypto.verifyPreimage(preimage, hashUpper))
        assertTrue("Should verify with lowercase hash", PaymentCrypto.verifyPreimage(preimage, hashLower))
    }

    @Test
    fun `verifyPreimage round trip with known value`() {
        val knownPreimage = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val computedHash = PaymentCrypto.computePaymentHash(knownPreimage)

        assertTrue(
            "Computed hash should verify with original preimage",
            PaymentCrypto.verifyPreimage(knownPreimage, computedHash)
        )
    }

    // ==================== isValidHex() Tests ====================

    @Test
    fun `isValidHex returns true for valid 64 char lowercase hex`() {
        val validHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        assertTrue(PaymentCrypto.isValidHex(validHex))
    }

    @Test
    fun `isValidHex returns true for valid 64 char uppercase hex`() {
        val validHex = "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"
        assertTrue(PaymentCrypto.isValidHex(validHex))
    }

    @Test
    fun `isValidHex returns true for mixed case hex`() {
        val validHex = "0123456789AbCdEf0123456789aBcDeF0123456789AbCdEf0123456789aBcDeF"
        assertTrue(PaymentCrypto.isValidHex(validHex))
    }

    @Test
    fun `isValidHex returns false for too short`() {
        val shortHex = "0123456789abcdef"
        assertFalse("16 char hex should be invalid", PaymentCrypto.isValidHex(shortHex))
    }

    @Test
    fun `isValidHex returns false for too long`() {
        val longHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef00"
        assertFalse("66 char hex should be invalid", PaymentCrypto.isValidHex(longHex))
    }

    @Test
    fun `isValidHex returns false for empty string`() {
        assertFalse(PaymentCrypto.isValidHex(""))
    }

    @Test
    fun `isValidHex returns false for non-hex characters`() {
        val invalidHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789ghijkl"
        assertFalse("String with g-l should be invalid", PaymentCrypto.isValidHex(invalidHex))
    }

    @Test
    fun `isValidHex returns false for string with spaces`() {
        val invalidHex = "0123456789abcdef 0123456789abcdef0123456789abcdef0123456789abcde"
        assertFalse("String with space should be invalid", PaymentCrypto.isValidHex(invalidHex))
    }

    @Test
    fun `isValidHex returns false for 63 characters`() {
        val invalidHex = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcde"
        assertEquals(63, invalidHex.length)
        assertFalse("63 char string should be invalid", PaymentCrypto.isValidHex(invalidHex))
    }

    // ==================== Helper Functions ====================

    /**
     * Helper to compute SHA256 hex independently for test verification.
     */
    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    /**
     * Helper to convert hex string to bytes.
     */
    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
