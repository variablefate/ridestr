package com.ridestr.common.payment.cashu

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.math.BigInteger
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Unit tests for CashuCrypto - secp256k1 cryptographic operations for Cashu protocol.
 *
 * These tests verify:
 * - NUT-00 hash_to_curve test vectors (CRITICAL for proof validity)
 * - NUT-13 deterministic secret derivation (CRITICAL for wallet recovery)
 * - BIP-39 mnemonic to seed derivation
 * - HMAC-SHA256 computation
 * - Blinding and unblinding operations
 *
 * Test vectors sourced from:
 * - https://github.com/cashubtc/nuts/blob/main/tests/00-tests.md
 * - https://github.com/cashubtc/nuts/blob/main/13.md
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CashuCryptoTest {

    // ==================== NUT-00 hash_to_curve Tests ====================

    @Test
    fun `hashToCurve with 32 zero bytes matches NUT-00 test vector`() {
        // Test vector from NUT-00 spec
        val inputHex = "0000000000000000000000000000000000000000000000000000000000000000"
        val expectedY = "024cce997d3b518f739663b757deaec95bcd9473c30a14ac2fd04023a739d1a725"

        val inputBytes = hexToBytes(inputHex)
        val actualY = CashuCrypto.hashToCurveBytes(inputBytes)

        assertEquals("hash_to_curve(zeros) should match NUT-00 test vector", expectedY, actualY)
    }

    @Test
    fun `hashToCurve with 31 zeros + 0x01 matches NUT-00 test vector`() {
        val inputHex = "0000000000000000000000000000000000000000000000000000000000000001"
        val expectedY = "022e7158e11c9506f1aa4248bf531298daa7febd6194f003edcd9b93ade6253acf"

        val inputBytes = hexToBytes(inputHex)
        val actualY = CashuCrypto.hashToCurveBytes(inputBytes)

        assertEquals("hash_to_curve(0x01) should match NUT-00 test vector", expectedY, actualY)
    }

    @Test
    fun `hashToCurve with 31 zeros + 0x02 matches NUT-00 test vector`() {
        val inputHex = "0000000000000000000000000000000000000000000000000000000000000002"
        val expectedY = "026cdbe15362df59cd1dd3c9c11de8aedac2106eca69236ecd9fbe117af897be4f"

        val inputBytes = hexToBytes(inputHex)
        val actualY = CashuCrypto.hashToCurveBytes(inputBytes)

        assertEquals("hash_to_curve(0x02) should match NUT-00 test vector", expectedY, actualY)
    }

    @Test
    fun `verifyHashToCurveTestVectors passes internal verification`() {
        // CashuCrypto has built-in test vector verification
        assertTrue("Built-in hash_to_curve test vectors should pass", CashuCrypto.verifyHashToCurveTestVectors())
    }

    @Test
    fun `hashToCurve with string input produces valid compressed point`() {
        val secret = "test_secret_123"
        val result = CashuCrypto.hashToCurve(secret)

        assertNotNull("hashToCurve should return non-null for valid input", result)
        assertEquals("Result should be 66 hex chars (33 bytes compressed)", 66, result!!.length)
        assertTrue("Should start with 02 or 03 (compressed point)", result.startsWith("02") || result.startsWith("03"))
    }

    @Test
    fun `hashToCurve is deterministic`() {
        val secret = "deterministic_test_secret"
        val result1 = CashuCrypto.hashToCurve(secret)
        val result2 = CashuCrypto.hashToCurve(secret)

        assertEquals("Same input should produce same output", result1, result2)
    }

    @Test
    fun `hashToCurve different inputs produce different outputs`() {
        val result1 = CashuCrypto.hashToCurve("secret1")
        val result2 = CashuCrypto.hashToCurve("secret2")

        assertNotEquals("Different inputs should produce different outputs", result1, result2)
    }

    // ==================== NUT-13 Deterministic Secret Derivation Tests ====================

    @Test
    fun `verifyNut13TestVectors passes internal verification`() {
        assertTrue("Built-in NUT-13 test vectors should pass", CashuCrypto.verifyNut13TestVectors())
    }

    @Test
    fun `deriveSecrets is deterministic`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val seed = CashuCrypto.mnemonicToSeed(mnemonic)
        val keysetId = "009a1f293253e41e"

        val secrets1 = CashuCrypto.deriveSecrets(seed, keysetId, 0)
        val secrets2 = CashuCrypto.deriveSecrets(seed, keysetId, 0)

        assertEquals("Secret should be deterministic", secrets1.secret, secrets2.secret)
        assertEquals("Blinding factor should be deterministic", secrets1.blindingFactor, secrets2.blindingFactor)
    }

    @Test
    fun `deriveSecrets different counters produce different secrets`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val seed = CashuCrypto.mnemonicToSeed(mnemonic)
        val keysetId = "009a1f293253e41e"

        val secrets0 = CashuCrypto.deriveSecrets(seed, keysetId, 0)
        val secrets1 = CashuCrypto.deriveSecrets(seed, keysetId, 1)
        val secrets2 = CashuCrypto.deriveSecrets(seed, keysetId, 2)

        assertNotEquals("Counter 0 and 1 should differ", secrets0.secret, secrets1.secret)
        assertNotEquals("Counter 1 and 2 should differ", secrets1.secret, secrets2.secret)
        assertNotEquals("Counter 0 and 2 should differ", secrets0.secret, secrets2.secret)
    }

    @Test
    fun `deriveSecrets different keysets produce different secrets`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val seed = CashuCrypto.mnemonicToSeed(mnemonic)

        val secretsA = CashuCrypto.deriveSecrets(seed, "009a1f293253e41e", 0)
        val secretsB = CashuCrypto.deriveSecrets(seed, "00deadbeef123456", 0)

        assertNotEquals("Different keysets should produce different secrets", secretsA.secret, secretsB.secret)
    }

    @Test
    fun `deriveSecrets returns 64 char hex strings`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val seed = CashuCrypto.mnemonicToSeed(mnemonic)
        val keysetId = "009a1f293253e41e"

        val secrets = CashuCrypto.deriveSecrets(seed, keysetId, 0)

        assertEquals("Secret should be 64 hex chars", 64, secrets.secret.length)
        assertEquals("Blinding factor should be 64 hex chars", 64, secrets.blindingFactor.length)
        assertTrue("Secret should be valid hex", isValidHex(secrets.secret))
        assertTrue("Blinding factor should be valid hex", isValidHex(secrets.blindingFactor))
    }

    @Test
    fun `deriveSecrets blinding factor is reduced mod N`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val seed = CashuCrypto.mnemonicToSeed(mnemonic)
        val keysetId = "009a1f293253e41e"

        // secp256k1 curve order N
        val N = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141", 16)

        // Test multiple counters to ensure blinding factor is always < N
        for (counter in 0L..100L) {
            val secrets = CashuCrypto.deriveSecrets(seed, keysetId, counter)
            val rValue = BigInteger(1, hexToBytes(secrets.blindingFactor))

            assertTrue("Blinding factor at counter $counter should be < N", rValue < N)
        }
    }

    // ==================== BIP-39 Mnemonic to Seed Tests ====================

    @Test
    fun `mnemonicToSeed returns 64 bytes`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val seed = CashuCrypto.mnemonicToSeed(mnemonic)

        assertEquals("Seed should be 64 bytes", 64, seed.size)
    }

    @Test
    fun `mnemonicToSeed is deterministic`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val seed1 = CashuCrypto.mnemonicToSeed(mnemonic)
        val seed2 = CashuCrypto.mnemonicToSeed(mnemonic)

        assertArrayEquals("Same mnemonic should produce same seed", seed1, seed2)
    }

    @Test
    fun `mnemonicToSeed with BIP-39 test vector`() {
        // BIP-39 test vector: "abandon" x 11 + "about" with empty passphrase
        // Expected seed (first 16 bytes for brevity): 5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val seed = CashuCrypto.mnemonicToSeed(mnemonic)

        // Verify first few bytes match known BIP-39 test vector
        val seedHex = seed.joinToString("") { "%02x".format(it) }
        assertTrue("Seed should start with expected bytes", seedHex.startsWith("5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1"))
    }

    @Test
    fun `mnemonicToSeed normalizes whitespace`() {
        val mnemonic1 = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val mnemonic2 = "  abandon  abandon   abandon abandon abandon abandon abandon abandon abandon abandon abandon about  "

        val seed1 = CashuCrypto.mnemonicToSeed(mnemonic1)
        val seed2 = CashuCrypto.mnemonicToSeed(mnemonic2)

        assertArrayEquals("Whitespace normalization should produce same seed", seed1, seed2)
    }

    @Test
    fun `mnemonicToSeed different mnemonics produce different seeds`() {
        val mnemonic1 = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val mnemonic2 = "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong"

        val seed1 = CashuCrypto.mnemonicToSeed(mnemonic1)
        val seed2 = CashuCrypto.mnemonicToSeed(mnemonic2)

        assertFalse("Different mnemonics should produce different seeds", seed1.contentEquals(seed2))
    }

    @Test
    fun `mnemonicToSeed with passphrase differs from without`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"

        val seedNoPass = CashuCrypto.mnemonicToSeed(mnemonic, "")
        val seedWithPass = CashuCrypto.mnemonicToSeed(mnemonic, "password123")

        assertFalse("Seed with passphrase should differ", seedNoPass.contentEquals(seedWithPass))
    }

    // ==================== HMAC-SHA256 Tests ====================

    @Test
    fun `hmacSha256 with known test vector`() {
        // RFC 4231 Test Case 1
        val key = ByteArray(20) { 0x0b.toByte() }
        val data = "Hi There".toByteArray(Charsets.UTF_8)
        val expectedHex = "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7"

        val result = CashuCrypto.hmacSha256(key, data)
        val resultHex = result.joinToString("") { "%02x".format(it) }

        assertEquals("HMAC-SHA256 should match RFC 4231 test vector", expectedHex, resultHex)
    }

    @Test
    fun `hmacSha256 returns 32 bytes`() {
        val result = CashuCrypto.hmacSha256("key".toByteArray(), "data".toByteArray())
        assertEquals("HMAC-SHA256 should return 32 bytes", 32, result.size)
    }

    @Test
    fun `hmacSha256 is deterministic`() {
        val key = "test_key".toByteArray()
        val data = "test_data".toByteArray()

        val result1 = CashuCrypto.hmacSha256(key, data)
        val result2 = CashuCrypto.hmacSha256(key, data)

        assertArrayEquals("Same inputs should produce same HMAC", result1, result2)
    }

    // ==================== Blinding Operations Tests ====================

    @Test
    fun `generateSecret returns 64 char hex string`() {
        val secret = CashuCrypto.generateSecret()

        assertEquals("Secret should be 64 hex chars", 64, secret.length)
        assertTrue("Secret should be valid hex", isValidHex(secret))
    }

    @Test
    fun `generateSecret is random`() {
        val secrets = List(100) { CashuCrypto.generateSecret() }.toSet()
        assertEquals("100 generated secrets should all be unique", 100, secrets.size)
    }

    @Test
    fun `generateBlindingFactor returns 64 char hex string`() {
        val r = CashuCrypto.generateBlindingFactor()

        assertEquals("Blinding factor should be 64 hex chars", 64, r.length)
        assertTrue("Blinding factor should be valid hex", isValidHex(r))
    }

    @Test
    fun `blindMessage returns 66 char compressed point`() {
        val Y = CashuCrypto.hashToCurve("test_secret")
        val r = CashuCrypto.generateBlindingFactor()

        val B_ = CashuCrypto.blindMessage(Y!!, r)

        assertNotNull("blindMessage should return non-null", B_)
        assertEquals("Blinded message should be 66 hex chars", 66, B_!!.length)
        assertTrue("Should be compressed point", B_.startsWith("02") || B_.startsWith("03"))
    }

    @Test
    fun `blindMessage is deterministic`() {
        val Y = CashuCrypto.hashToCurve("test_secret")!!
        val r = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        val B_1 = CashuCrypto.blindMessage(Y, r)
        val B_2 = CashuCrypto.blindMessage(Y, r)

        assertEquals("Same inputs should produce same blinded message", B_1, B_2)
    }

    @Test
    fun `blindMessage different r values produce different outputs`() {
        val Y = CashuCrypto.hashToCurve("test_secret")!!
        val r1 = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val r2 = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210"

        val B_1 = CashuCrypto.blindMessage(Y, r1)
        val B_2 = CashuCrypto.blindMessage(Y, r2)

        assertNotEquals("Different r values should produce different B_", B_1, B_2)
    }

    // ==================== derivePreMintSecret Integration Tests ====================

    @Test
    fun `derivePreMintSecret returns complete PreMintSecret`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val seed = CashuCrypto.mnemonicToSeed(mnemonic)
        val keysetId = "009a1f293253e41e"

        val preMint = CashuCrypto.derivePreMintSecret(seed, keysetId, 0, 64)

        assertNotNull("derivePreMintSecret should return non-null", preMint)
        assertEquals("Amount should match", 64, preMint!!.amount)
        assertEquals("Secret should be 64 hex chars", 64, preMint.secret.length)
        assertEquals("Blinding factor should be 64 hex chars", 64, preMint.blindingFactor.length)
        assertEquals("Y should be 66 hex chars", 66, preMint.Y.length)
        assertEquals("B_ should be 66 hex chars", 66, preMint.B_.length)
    }

    @Test
    fun `derivePreMintSecret is deterministic`() {
        val mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val seed = CashuCrypto.mnemonicToSeed(mnemonic)
        val keysetId = "009a1f293253e41e"

        val preMint1 = CashuCrypto.derivePreMintSecret(seed, keysetId, 5, 128)
        val preMint2 = CashuCrypto.derivePreMintSecret(seed, keysetId, 5, 128)

        assertEquals("Secret should be deterministic", preMint1!!.secret, preMint2!!.secret)
        assertEquals("Blinding factor should be deterministic", preMint1.blindingFactor, preMint2.blindingFactor)
        assertEquals("Y should be deterministic", preMint1.Y, preMint2.Y)
        assertEquals("B_ should be deterministic", preMint1.B_, preMint2.B_)
    }

    // ==================== Helper Functions ====================

    private fun hexToBytes(hex: String): ByteArray {
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun isValidHex(value: String): Boolean {
        return value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
}
