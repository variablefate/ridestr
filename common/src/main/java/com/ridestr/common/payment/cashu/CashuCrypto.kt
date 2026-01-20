package com.ridestr.common.payment.cashu

import android.util.Log
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Cashu cryptographic utilities for NUT-00 blinding protocol.
 *
 * Reference: https://github.com/cashubtc/nuts/blob/main/00.md
 *
 * This implements the client-side cryptography needed for:
 * - Generating secrets and blinding factors
 * - Creating blinded messages (B_)
 * - Unblinding signatures to get proofs (C)
 */
object CashuCrypto {
    private const val TAG = "CashuCrypto"

    // secp256k1 curve parameters
    private val P = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)
    private val Gx = BigInteger("79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798", 16)
    private val Gy = BigInteger("483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8", 16)

    // Domain separator for hash_to_curve (from NUT-00)
    private const val DOMAIN_SEPARATOR = "Secp256k1_HashToCurve_Cashu_"

    private val secureRandom = SecureRandom()

    /**
     * Generate a random 32-byte secret as hex string.
     */
    fun generateSecret(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return bytes.toHexString()
    }

    /**
     * Generate a random blinding factor (scalar).
     */
    fun generateBlindingFactor(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        // Ensure it's less than curve order N
        val scalar = BigInteger(1, bytes).mod(N)
        return scalar.toByteArray32().toHexString()
    }

    /**
     * Hash to curve - converts a secret to a curve point Y.
     * Uses the try-and-increment method with domain separator.
     *
     * @param secret The secret string (hex for plain secrets, JSON for HTLC/P2PK)
     * @return Compressed public key (33 bytes) as hex, or null on failure
     */
    fun hashToCurve(secret: String): String? {
        // Convert secret string to UTF-8 bytes
        val secretBytes = secret.toByteArray(Charsets.UTF_8)
        return hashToCurveBytes(secretBytes)
    }

    /**
     * Hash to curve with raw byte input (for testing against reference vectors).
     */
    fun hashToCurveBytes(message: ByteArray, debug: Boolean = false): String? {
        return try {
            val domainBytes = DOMAIN_SEPARATOR.toByteArray(Charsets.UTF_8)

            // NUT-00 specifies DOUBLE SHA256:
            // 1. msg_hash = SHA256(DOMAIN_SEPARATOR || message)
            // 2. final = SHA256(msg_hash || counter)
            val msgHash = sha256(domainBytes + message)

            if (debug) {
                Log.d(TAG, "hashToCurve DEBUG: domain='$DOMAIN_SEPARATOR' (${domainBytes.size} bytes)")
                Log.d(TAG, "hashToCurve DEBUG: message=${message.size} bytes")
                Log.d(TAG, "hashToCurve DEBUG: msgHash (first SHA256)=${msgHash.toHexString()}")
            }

            // Try incrementing counter until we find a valid x-coordinate
            // NUT-00: counter is 4 bytes, little-endian
            for (counter in 0 until 65536) {
                val counterBytes = byteArrayOf(
                    (counter and 0xFF).toByte(),
                    ((counter shr 8) and 0xFF).toByte(),
                    0, 0  // Upper bytes are 0 for counter < 65536
                )
                // Second SHA256: hash of (msgHash || counter)
                val hash = sha256(msgHash + counterBytes)

                if (debug && counter == 0) {
                    Log.d(TAG, "hashToCurve DEBUG: counter=0 hash (second SHA256)=${hash.toHexString()}")
                }

                // Try to lift x to curve
                // x must be < P to be valid - don't use mod(P) as that changes the value!
                val x = BigInteger(1, hash)
                if (x >= P) continue  // x out of range, try next counter

                val point = liftX(x)
                if (point != null) {
                    val result = compressPoint(point).toHexString()
                    if (debug) {
                        Log.d(TAG, "hashToCurve DEBUG: success at counter=$counter, x=${x.toString(16).take(16)}..., result=$result")
                    }
                    return result
                }
            }

            Log.e(TAG, "hashToCurve: failed to find valid point after 65536 iterations")
            null
        } catch (e: Exception) {
            Log.e(TAG, "hashToCurve failed: ${e.message}", e)
            null
        }
    }

    /**
     * Create a blinded message B_ = Y + r*G
     *
     * @param Y The hash_to_curve point (compressed, 33 bytes hex)
     * @param r The blinding factor (32 bytes hex)
     * @return Blinded message B_ (compressed, 33 bytes hex), or null on failure
     */
    fun blindMessage(Y: String, r: String): String? {
        return try {
            val yPoint = decompressPoint(Y.hexToByteArray()) ?: return null
            val rScalar = BigInteger(1, r.hexToByteArray())

            // r*G
            val rG = scalarMultiply(Pair(Gx, Gy), rScalar)

            // Y + r*G
            val B_ = pointAdd(yPoint, rG)

            compressPoint(B_).toHexString()
        } catch (e: Exception) {
            Log.e(TAG, "blindMessage failed: ${e.message}", e)
            null
        }
    }

    /**
     * Unblind a signature: C = C_ - r*K
     *
     * @param C_ The blinded signature from mint (compressed, 33 bytes hex)
     * @param r The blinding factor used (32 bytes hex)
     * @param K The mint's public key for this amount (compressed, 33 bytes hex)
     * @return Unblinded signature C (compressed, 33 bytes hex), or null on failure
     */
    fun unblindSignature(C_: String, r: String, K: String): String? {
        return try {
            val cBlindedPoint = decompressPoint(C_.hexToByteArray()) ?: return null
            val kPoint = decompressPoint(K.hexToByteArray()) ?: return null
            val rScalar = BigInteger(1, r.hexToByteArray())

            // r*K
            val rK = scalarMultiply(kPoint, rScalar)

            // C_ - r*K = C_ + (-r*K)
            val negRK = Pair(rK.first, P.subtract(rK.second).mod(P))
            val C = pointAdd(cBlindedPoint, negRK)

            compressPoint(C).toHexString()
        } catch (e: Exception) {
            Log.e(TAG, "unblindSignature failed: ${e.message}", e)
            null
        }
    }

    // ========================================
    // EC Point Operations
    // ========================================

    /**
     * Lift x-coordinate to curve point (try both y values, pick even).
     */
    private fun liftX(x: BigInteger): Pair<BigInteger, BigInteger>? {
        // y² = x³ + 7 (mod p)
        val x3 = x.modPow(BigInteger.valueOf(3), P)
        val y2 = x3.add(BigInteger.valueOf(7)).mod(P)

        // y = y2^((p+1)/4) mod p (works because p ≡ 3 mod 4)
        val exp = P.add(BigInteger.ONE).divide(BigInteger.valueOf(4))
        val y = y2.modPow(exp, P)

        // Verify
        if (y.modPow(BigInteger.valueOf(2), P) != y2) {
            return null // Not a valid x-coordinate
        }

        // Return point with even y (standard convention)
        val yEven = if (y.testBit(0)) P.subtract(y) else y
        return Pair(x, yEven)
    }

    /**
     * Point addition on secp256k1.
     */
    private fun pointAdd(p1: Pair<BigInteger, BigInteger>, p2: Pair<BigInteger, BigInteger>): Pair<BigInteger, BigInteger> {
        if (p1.first == BigInteger.ZERO && p1.second == BigInteger.ZERO) return p2
        if (p2.first == BigInteger.ZERO && p2.second == BigInteger.ZERO) return p1

        val (x1, y1) = p1
        val (x2, y2) = p2

        val lambda = if (x1 == x2 && y1 == y2) {
            // Point doubling
            val num = BigInteger.valueOf(3).multiply(x1.modPow(BigInteger.valueOf(2), P)).mod(P)
            val denom = BigInteger.valueOf(2).multiply(y1).modInverse(P)
            num.multiply(denom).mod(P)
        } else {
            // Point addition
            val num = y2.subtract(y1).mod(P)
            val denom = x2.subtract(x1).modInverse(P)
            num.multiply(denom).mod(P)
        }

        val x3 = lambda.modPow(BigInteger.valueOf(2), P).subtract(x1).subtract(x2).mod(P)
        val y3 = lambda.multiply(x1.subtract(x3)).subtract(y1).mod(P)

        return Pair(x3, y3)
    }

    /**
     * Scalar multiplication using double-and-add.
     */
    private fun scalarMultiply(point: Pair<BigInteger, BigInteger>, scalar: BigInteger): Pair<BigInteger, BigInteger> {
        var result = Pair(BigInteger.ZERO, BigInteger.ZERO)
        var temp = point
        var k = scalar

        while (k > BigInteger.ZERO) {
            if (k.testBit(0)) {
                result = pointAdd(result, temp)
            }
            temp = pointAdd(temp, temp)
            k = k.shiftRight(1)
        }

        return result
    }

    /**
     * Compress a point to 33 bytes (02/03 prefix + x).
     */
    private fun compressPoint(point: Pair<BigInteger, BigInteger>): ByteArray {
        val prefix = if (point.second.testBit(0)) 0x03.toByte() else 0x02.toByte()
        val xBytes = point.first.toByteArray32()
        return byteArrayOf(prefix) + xBytes
    }

    /**
     * Decompress a 33-byte compressed point.
     */
    private fun decompressPoint(compressed: ByteArray): Pair<BigInteger, BigInteger>? {
        if (compressed.size != 33) return null

        val prefix = compressed[0].toInt() and 0xFF
        if (prefix != 0x02 && prefix != 0x03) return null

        val x = BigInteger(1, compressed.copyOfRange(1, 33))
        val point = liftX(x) ?: return null

        // Adjust y based on prefix
        val yIsOdd = prefix == 0x03
        val y = if (point.second.testBit(0) != yIsOdd) {
            P.subtract(point.second)
        } else {
            point.second
        }

        return Pair(x, y)
    }

    // ========================================
    // Utility Functions
    // ========================================

    private fun sha256(data: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(data)
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToByteArray(): ByteArray {
        check(length % 2 == 0) { "Hex string must have even length" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun BigInteger.toByteArray32(): ByteArray {
        val bytes = this.toByteArray()
        return when {
            bytes.size == 32 -> bytes
            bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
            else -> ByteArray(32 - bytes.size) + bytes
        }
    }
}

/**
 * Pre-mint secrets for a single proof.
 * Contains the secret, blinding factor, and computed Y value.
 */
data class PreMintSecret(
    val amount: Long,
    val secret: String,          // 32-byte hex
    val blindingFactor: String,  // 32-byte hex (r)
    val Y: String,               // Compressed point hex (hash_to_curve(secret))
    val B_: String               // Blinded message hex (Y + r*G)
)

/**
 * A complete Cashu proof.
 */
data class CashuProof(
    val amount: Long,
    val id: String,      // Keyset ID
    val secret: String,  // The original secret
    val C: String        // Unblinded signature (compressed point hex)
) {
    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("amount", amount)
        put("id", id)
        put("secret", secret)
        put("C", C)
    }
}
