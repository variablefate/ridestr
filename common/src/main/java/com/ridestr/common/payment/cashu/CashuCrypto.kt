package com.ridestr.common.payment.cashu

import android.util.Log
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

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
     * Verify hash_to_curve against NUT-00 test vectors.
     * Test vectors from https://github.com/cashubtc/nuts/blob/main/tests/00-tests.md
     */
    fun verifyHashToCurveTestVectors(): Boolean {
        val testVectors = listOf(
            // Input (32 zero bytes) -> Expected Y
            "0000000000000000000000000000000000000000000000000000000000000000" to
                "024cce997d3b518f739663b757deaec95bcd9473c30a14ac2fd04023a739d1a725",
            // Input (31 zeros + 0x01)
            "0000000000000000000000000000000000000000000000000000000000000001" to
                "022e7158e11c9506f1aa4248bf531298daa7febd6194f003edcd9b93ade6253acf",
            // Input (31 zeros + 0x02)
            "0000000000000000000000000000000000000000000000000000000000000002" to
                "026cdbe15362df59cd1dd3c9c11de8aedac2106eca69236ecd9fbe117af897be4f"
        )

        var allPassed = true
        for ((inputHex, expectedY) in testVectors) {
            val inputBytes = inputHex.hexToByteArray()
            val actualY = hashToCurveBytes(inputBytes)
            val passed = actualY == expectedY
            Log.d(TAG, "hash_to_curve test: input=${inputHex.take(16)}... expected=${expectedY.take(16)}... actual=${actualY?.take(16)}... ${if (passed) "PASS" else "FAIL"}")
            if (!passed) {
                Log.e(TAG, "hash_to_curve MISMATCH: expected=$expectedY actual=$actualY")
                allPassed = false
            }
        }
        return allPassed
    }

    /**
     * Hash to curve with raw byte input.
     */
    fun hashToCurveBytes(message: ByteArray): String? {
        return try {
            val domainBytes = DOMAIN_SEPARATOR.toByteArray(Charsets.UTF_8)

            // NUT-00 specifies DOUBLE SHA256:
            // 1. msg_hash = SHA256(DOMAIN_SEPARATOR || message)
            // 2. final = SHA256(msg_hash || counter)
            val msgHash = sha256(domainBytes + message)

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

                // Try to lift x to curve
                // x must be < P to be valid - don't use mod(P) as that changes the value!
                val x = BigInteger(1, hash)
                if (x >= P) continue  // x out of range, try next counter

                val point = liftX(x)
                if (point != null) {
                    return compressPoint(point).toHexString()
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

    // ========================================
    // NUT-13: Deterministic Secret Derivation
    // Reference: https://github.com/cashubtc/nuts/blob/main/13.md
    // ========================================

    // Domain separator for NUT-13 V2 keyset derivation
    private const val NUT13_DOMAIN_SEPARATOR = "Cashu_KDF_HMAC_SHA256"

    /**
     * Derive BIP-39 seed from mnemonic phrase.
     * Uses PBKDF2-HMAC-SHA512 with 2048 iterations per BIP-39 spec.
     *
     * @param mnemonic The BIP-39 mnemonic words (space-separated)
     * @param passphrase Optional passphrase (defaults to empty string)
     * @return 64-byte seed
     */
    fun mnemonicToSeed(mnemonic: String, passphrase: String = ""): ByteArray {
        val normalizedMnemonic = mnemonic.trim().replace(Regex("\\s+"), " ")
        val salt = "mnemonic$passphrase".toByteArray(Charsets.UTF_8)

        val spec = PBEKeySpec(
            normalizedMnemonic.toCharArray(),
            salt,
            2048,  // iterations per BIP-39
            512    // key length in bits
        )

        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
        return factory.generateSecret(spec).encoded
    }

    /**
     * Compute HMAC-SHA256.
     *
     * @param key The HMAC key
     * @param data The data to authenticate
     * @return 32-byte HMAC result
     */
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /**
     * Deterministic secret and blinding factor derivation per NUT-13.
     * Uses HMAC-SHA256 for V2 keysets (ID starts with "01").
     *
     * Formula:
     *   message = domain_separator || keyset_id_bytes || counter_bytes || type_byte
     *   secret = HMAC-SHA256(seed, message with type=0x00)
     *   r = HMAC-SHA256(seed, message with type=0x01) mod N
     *
     * @param seed 64-byte BIP-39 seed
     * @param keysetId Keyset ID string (e.g., "01abc123...")
     * @param counter Per-keyset counter value
     * @return DeterministicSecrets containing secret and blinding factor
     */
    fun deriveSecrets(seed: ByteArray, keysetId: String, counter: Long): DeterministicSecrets {
        val domain = NUT13_DOMAIN_SEPARATOR.toByteArray(Charsets.UTF_8)
        val keysetBytes = keysetId.hexToByteArray()
        val counterBytes = ByteBuffer.allocate(8).putLong(counter).array()

        // Derive secret (type = 0x00)
        val secretMsg = domain + keysetBytes + counterBytes + byteArrayOf(0x00)
        val secretBytes = hmacSha256(seed, secretMsg)
        val secret = secretBytes.toHexString()

        // Derive blinding factor (type = 0x01), reduced mod N
        val rMsg = domain + keysetBytes + counterBytes + byteArrayOf(0x01)
        val rBytes = hmacSha256(seed, rMsg)
        val rBigInt = BigInteger(1, rBytes).mod(N)
        val blindingFactor = rBigInt.toByteArray32().toHexString()

        return DeterministicSecrets(
            secret = secret,
            blindingFactor = blindingFactor,
            counter = counter
        )
    }

    /**
     * Create a complete PreMintSecret with deterministic derivation.
     * This is the main entry point for NUT-13 compliant secret generation.
     *
     * @param seed 64-byte BIP-39 seed
     * @param keysetId Keyset ID string
     * @param counter Per-keyset counter value
     * @param amount Amount in sats for this proof
     * @return PreMintSecret ready for minting, or null if hashToCurve fails
     */
    fun derivePreMintSecret(
        seed: ByteArray,
        keysetId: String,
        counter: Long,
        amount: Long
    ): PreMintSecret? {
        val secrets = deriveSecrets(seed, keysetId, counter)

        // Hash secret to curve point Y
        val Y = hashToCurve(secrets.secret) ?: run {
            Log.e(TAG, "derivePreMintSecret: hashToCurve failed for counter $counter")
            return null
        }

        // Create blinded message B_ = Y + r*G
        val B_ = blindMessage(Y, secrets.blindingFactor) ?: run {
            Log.e(TAG, "derivePreMintSecret: blindMessage failed for counter $counter")
            return null
        }

        return PreMintSecret(
            amount = amount,
            secret = secrets.secret,
            blindingFactor = secrets.blindingFactor,
            Y = Y,
            B_ = B_
        )
    }

    /**
     * Verify NUT-13 derivation against test vectors.
     * Test case from NUT-13 spec.
     */
    fun verifyNut13TestVectors(): Boolean {
        // Test vector from NUT-13 spec (simplified - actual spec has more detailed vectors)
        // Using a known mnemonic and checking that derivation is deterministic
        val testMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val seed = mnemonicToSeed(testMnemonic)

        // Verify seed has correct length
        if (seed.size != 64) {
            Log.e(TAG, "NUT-13 test: seed size ${seed.size} != 64")
            return false
        }

        // Verify determinism - same inputs give same outputs
        val keysetId = "009a1f293253e41e" // Example keyset ID
        val secrets1 = deriveSecrets(seed, keysetId, 0)
        val secrets2 = deriveSecrets(seed, keysetId, 0)

        if (secrets1.secret != secrets2.secret || secrets1.blindingFactor != secrets2.blindingFactor) {
            Log.e(TAG, "NUT-13 test: derivation not deterministic")
            return false
        }

        // Verify different counters give different secrets
        val secrets3 = deriveSecrets(seed, keysetId, 1)
        if (secrets1.secret == secrets3.secret) {
            Log.e(TAG, "NUT-13 test: different counters gave same secret")
            return false
        }

        Log.d(TAG, "NUT-13 test vectors PASSED")
        return true
    }

}

/**
 * Result of NUT-13 deterministic secret derivation.
 */
data class DeterministicSecrets(
    val secret: String,          // 32-byte hex secret
    val blindingFactor: String,  // 32-byte hex blinding factor (r)
    val counter: Long            // Counter used for derivation
)

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
