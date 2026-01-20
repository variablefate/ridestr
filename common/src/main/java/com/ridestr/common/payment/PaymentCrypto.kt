package com.ridestr.common.payment

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Cryptographic utilities for payment rail escrow.
 *
 * Handles preimage generation and payment hash computation for HTLC-based escrow.
 * Used by both Cashu (Nut-14) and Lightning (HODL invoices) payment flows.
 *
 * Flow:
 * 1. Rider generates 32-byte random preimage
 * 2. Rider computes payment_hash = SHA256(preimage)
 * 3. payment_hash sent with ride offer (encrypted)
 * 4. Driver creates HTLC locked to payment_hash
 * 5. After PIN verification, rider shares preimage
 * 6. At dropoff, driver uses preimage to settle HTLC
 */
object PaymentCrypto {
    private const val PREIMAGE_LENGTH = 32  // 256 bits

    /**
     * Generate a cryptographically secure random preimage.
     *
     * @return 64-character hex string representing 32 random bytes
     */
    fun generatePreimage(): String {
        val bytes = ByteArray(PREIMAGE_LENGTH)
        SecureRandom().nextBytes(bytes)
        return bytes.toHexString()
    }

    /**
     * Compute SHA256 hash of a preimage to get payment_hash.
     *
     * @param preimage 64-character hex string (32 bytes)
     * @return 64-character hex string representing SHA256 hash
     */
    fun computePaymentHash(preimage: String): String {
        val preimageBytes = preimage.hexToByteArray()
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(preimageBytes).toHexString()
    }

    /**
     * Verify that a preimage matches an expected payment hash.
     *
     * @param preimage 64-character hex string
     * @param paymentHash 64-character hex string
     * @return true if SHA256(preimage) equals paymentHash
     */
    fun verifyPreimage(preimage: String, paymentHash: String): Boolean {
        return try {
            computePaymentHash(preimage).equals(paymentHash, ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Validate that a string is a valid 64-character hex preimage/hash.
     */
    fun isValidHex(value: String): Boolean {
        return value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }

    /**
     * Convert ByteArray to lowercase hex string.
     */
    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    /**
     * Convert hex string to ByteArray.
     *
     * @throws IllegalArgumentException if string is not valid hex or odd length
     */
    private fun String.hexToByteArray(): ByteArray {
        require(length % 2 == 0) { "Hex string must have even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}
