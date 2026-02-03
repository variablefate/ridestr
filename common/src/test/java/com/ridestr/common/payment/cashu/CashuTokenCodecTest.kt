package com.ridestr.common.payment.cashu

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

/**
 * Unit tests for CashuTokenCodec - stateless token encoding/decoding utilities.
 *
 * These tests verify:
 * - HTLC token encoding produces valid cashuA format
 * - Token decoding correctly extracts proofs and mint URL
 * - Round-trip encoding/decoding preserves all data
 * - HTLC secret parsing extracts payment hash, locktime, refund keys
 * - Edge cases and malformed input handling
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CashuTokenCodecTest {

    // ==================== Token Encoding Tests ====================

    @Test
    fun `encodeHtlcProofsAsToken produces cashuA prefixed token`() {
        val proofs = listOf(
            CashuTokenCodec.HtlcProof(
                amount = 1,
                id = "00abc123",
                secret = """["HTLC",{"nonce":"test","data":"abcd1234"}]""",
                C = "02deadbeef"
            )
        )

        val token = CashuTokenCodec.encodeHtlcProofsAsToken(proofs, "https://mint.example.com")

        assertTrue("Token should start with cashuA", token.startsWith("cashuA"))
    }

    @Test
    fun `encodeHtlcProofsAsToken produces valid Base64`() {
        val proofs = listOf(
            CashuTokenCodec.HtlcProof(
                amount = 10,
                id = "00testid",
                secret = """["HTLC",{"nonce":"n","data":"hash"}]""",
                C = "02abcdef"
            )
        )

        val token = CashuTokenCodec.encodeHtlcProofsAsToken(proofs, "https://mint.test")
        val base64Part = token.removePrefix("cashuA")

        // Should not throw - valid Base64 URL-safe
        val decoded = Base64.getUrlDecoder().decode(base64Part)
        assertTrue("Decoded bytes should not be empty", decoded.isNotEmpty())
    }

    @Test
    fun `encodeHtlcProofsAsToken includes unit field`() {
        val proofs = listOf(
            CashuTokenCodec.HtlcProof(amount = 1, id = "id", secret = "s", C = "C")
        )

        val token = CashuTokenCodec.encodeHtlcProofsAsToken(proofs, "https://mint.test")
        val json = decodeTokenJson(token)

        assertEquals("Unit should be 'sat'", "sat", json.getString("unit"))
    }

    @Test
    fun `encodeHtlcProofsAsToken includes correct mint URL`() {
        val mintUrl = "https://testnet.cashu.space"
        val proofs = listOf(
            CashuTokenCodec.HtlcProof(amount = 100, id = "keysetid", secret = "secret", C = "C")
        )

        val token = CashuTokenCodec.encodeHtlcProofsAsToken(proofs, mintUrl)
        val json = decodeTokenJson(token)
        val tokenArray = json.getJSONArray("token")
        val firstMint = tokenArray.getJSONObject(0)

        assertEquals("Mint URL should match", mintUrl, firstMint.getString("mint"))
    }

    @Test
    fun `encodeProofsAsToken encodes plain CashuProof correctly`() {
        val proofs = listOf(
            CashuProof(
                amount = 64,
                id = "009a1f293253e41e",
                secret = "plainHexSecret123",
                C = "02c0ffee"
            )
        )

        val token = CashuTokenCodec.encodeProofsAsToken(proofs, "https://mint.cash")

        assertTrue("Token should start with cashuA", token.startsWith("cashuA"))
        val json = decodeTokenJson(token)
        val tokenArray = json.getJSONArray("token")
        val proofsArray = tokenArray.getJSONObject(0).getJSONArray("proofs")

        assertEquals("Should have 1 proof", 1, proofsArray.length())
        assertEquals("Amount should match", 64, proofsArray.getJSONObject(0).getLong("amount"))
    }

    // ==================== Token Decoding Tests ====================

    @Test
    fun `parseHtlcToken decodes valid cashuA token`() {
        val mintUrl = "https://mint.example.com"
        val originalProofs = listOf(
            CashuTokenCodec.HtlcProof(
                amount = 8,
                id = "00keysetabc",
                secret = """["HTLC",{"nonce":"nonce123","data":"paymenthash123"}]""",
                C = "02signature"
            )
        )

        val token = CashuTokenCodec.encodeHtlcProofsAsToken(originalProofs, mintUrl)
        val result = CashuTokenCodec.parseHtlcToken(token)

        assertNotNull("Parse should succeed", result)
        val (proofs, parsedMint) = result!!

        assertEquals("Mint URL should match", mintUrl, parsedMint)
        assertEquals("Should have 1 proof", 1, proofs.size)
        assertEquals("Amount should match", 8, proofs[0].amount)
        assertEquals("Keyset ID should match", "00keysetabc", proofs[0].id)
        assertEquals("Secret should match", originalProofs[0].secret, proofs[0].secret)
        assertEquals("C should match", "02signature", proofs[0].C)
    }

    @Test
    fun `parseHtlcToken handles multiple proofs`() {
        val proofs = listOf(
            CashuTokenCodec.HtlcProof(amount = 1, id = "id1", secret = "s1", C = "c1"),
            CashuTokenCodec.HtlcProof(amount = 2, id = "id2", secret = "s2", C = "c2"),
            CashuTokenCodec.HtlcProof(amount = 4, id = "id3", secret = "s3", C = "c3")
        )

        val token = CashuTokenCodec.encodeHtlcProofsAsToken(proofs, "https://mint")
        val result = CashuTokenCodec.parseHtlcToken(token)

        assertNotNull(result)
        assertEquals("Should have 3 proofs", 3, result!!.first.size)
        assertEquals("Sum of amounts", 7, result.first.sumOf { it.amount })
    }

    @Test
    fun `parseHtlcToken returns null for invalid prefix`() {
        val result = CashuTokenCodec.parseHtlcToken("invalidPrefix12345")
        assertNull("Should return null for invalid prefix", result)
    }

    @Test
    fun `parseHtlcToken returns null for invalid Base64`() {
        val result = CashuTokenCodec.parseHtlcToken("cashuA!!!invalidbase64!!!")
        assertNull("Should return null for invalid Base64", result)
    }

    @Test
    fun `parseHtlcToken returns null for empty token array`() {
        val json = JSONObject().apply {
            put("token", JSONArray())
            put("unit", "sat")
        }
        val token = "cashuA" + Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toString().toByteArray())

        val result = CashuTokenCodec.parseHtlcToken(token)
        assertNull("Should return null for empty token array", result)
    }

    // ==================== Round-Trip Tests ====================

    @Test
    fun `round trip preserves HTLC proof data`() {
        val original = CashuTokenCodec.HtlcProof(
            amount = 256,
            id = "00deadbeef123456",
            secret = """["HTLC",{"nonce":"abc","data":"def123456789abcdef123456789abcdef123456789abcdef123456789abcdef12","tags":[["locktime","1700000000"],["refund","02pubkey"]]}]""",
            C = "02c0ffeebabe12345678901234567890123456789012345678901234567890123456"
        )
        val mintUrl = "https://testnet.cashu.space/api/v1"

        val token = CashuTokenCodec.encodeHtlcProofsAsToken(listOf(original), mintUrl)
        val parsed = CashuTokenCodec.parseHtlcToken(token)

        assertNotNull(parsed)
        val (proofs, parsedMint) = parsed!!

        assertEquals(mintUrl, parsedMint)
        assertEquals(1, proofs.size)
        assertEquals(original.amount, proofs[0].amount)
        assertEquals(original.id, proofs[0].id)
        assertEquals(original.secret, proofs[0].secret)
        assertEquals(original.C, proofs[0].C)
    }

    @Test
    fun `round trip with power of 2 amounts`() {
        val amounts = listOf(1L, 2L, 4L, 8L, 16L, 32L, 64L, 128L, 256L, 512L, 1024L)
        val proofs = amounts.mapIndexed { idx, amount ->
            CashuTokenCodec.HtlcProof(
                amount = amount,
                id = "keyset$idx",
                secret = "secret$idx",
                C = "C$idx"
            )
        }

        val token = CashuTokenCodec.encodeHtlcProofsAsToken(proofs, "https://mint")
        val parsed = CashuTokenCodec.parseHtlcToken(token)

        assertNotNull(parsed)
        assertEquals(amounts.size, parsed!!.first.size)
        parsed.first.forEachIndexed { idx, proof ->
            assertEquals("Amount at index $idx", amounts[idx], proof.amount)
        }
    }

    // ==================== HTLC Secret Parsing Tests ====================

    @Test
    fun `extractPaymentHashFromSecret extracts data field`() {
        val paymentHash = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        val secret = """["HTLC",{"nonce":"randomnonce","data":"$paymentHash","tags":[]}]"""

        val result = CashuTokenCodec.extractPaymentHashFromSecret(secret)

        assertEquals("Payment hash should match", paymentHash, result)
    }

    @Test
    fun `extractPaymentHashFromSecret returns null for non-HTLC secret`() {
        val plainSecret = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        val result = CashuTokenCodec.extractPaymentHashFromSecret(plainSecret)

        assertNull("Should return null for plain hex secret", result)
    }

    @Test
    fun `extractPaymentHashFromSecret returns null for P2PK secret`() {
        val p2pkSecret = """["P2PK",{"nonce":"n","data":"pubkey"}]"""

        val result = CashuTokenCodec.extractPaymentHashFromSecret(p2pkSecret)

        assertNull("Should return null for P2PK secret", result)
    }

    @Test
    fun `extractPaymentHashFromSecret returns null for malformed JSON`() {
        val malformed = "not valid json at all"

        val result = CashuTokenCodec.extractPaymentHashFromSecret(malformed)

        assertNull("Should return null for malformed JSON", result)
    }

    @Test
    fun `extractPaymentHashFromSecret handles empty array`() {
        val emptyArray = "[]"

        val result = CashuTokenCodec.extractPaymentHashFromSecret(emptyArray)

        assertNull("Should return null for empty array", result)
    }

    // ==================== Locktime Extraction Tests ====================

    @Test
    fun `extractLocktimeFromSecret extracts locktime tag`() {
        val locktime = 1700000000L
        val secret = """["HTLC",{"nonce":"n","data":"hash","tags":[["locktime","$locktime"]]}]"""

        val result = CashuTokenCodec.extractLocktimeFromSecret(secret)

        assertEquals("Locktime should match", locktime, result)
    }

    @Test
    fun `extractLocktimeFromSecret returns null when no locktime tag`() {
        val secret = """["HTLC",{"nonce":"n","data":"hash","tags":[["refund","key"]]}]"""

        val result = CashuTokenCodec.extractLocktimeFromSecret(secret)

        assertNull("Should return null when no locktime tag", result)
    }

    @Test
    fun `extractLocktimeFromSecret returns null for empty tags`() {
        val secret = """["HTLC",{"nonce":"n","data":"hash","tags":[]}]"""

        val result = CashuTokenCodec.extractLocktimeFromSecret(secret)

        assertNull("Should return null for empty tags", result)
    }

    @Test
    fun `extractLocktimeFromSecret handles large timestamp`() {
        val largeLocktime = 2147483648L  // Beyond 32-bit signed int
        val secret = """["HTLC",{"nonce":"n","data":"hash","tags":[["locktime","$largeLocktime"]]}]"""

        val result = CashuTokenCodec.extractLocktimeFromSecret(secret)

        assertEquals("Should handle large timestamp", largeLocktime, result)
    }

    @Test
    fun `extractLocktimeFromSecret returns null for non-numeric locktime`() {
        val secret = """["HTLC",{"nonce":"n","data":"hash","tags":[["locktime","not_a_number"]]}]"""

        val result = CashuTokenCodec.extractLocktimeFromSecret(secret)

        assertNull("Should return null for non-numeric locktime", result)
    }

    // ==================== Refund Keys Extraction Tests ====================

    @Test
    fun `extractRefundKeysFromSecret extracts single refund key`() {
        val pubkey = "02abcdef1234567890abcdef1234567890abcdef1234567890abcdef1234567890"
        val secret = """["HTLC",{"nonce":"n","data":"hash","tags":[["refund","$pubkey"]]}]"""

        val result = CashuTokenCodec.extractRefundKeysFromSecret(secret)

        assertEquals("Should have 1 refund key", 1, result.size)
        assertEquals("Key should match", pubkey, result[0])
    }

    @Test
    fun `extractRefundKeysFromSecret extracts multiple refund keys`() {
        val key1 = "02key1"
        val key2 = "02key2"
        val key3 = "02key3"
        val secret = """["HTLC",{"nonce":"n","data":"hash","tags":[["refund","$key1","$key2","$key3"]]}]"""

        val result = CashuTokenCodec.extractRefundKeysFromSecret(secret)

        assertEquals("Should have 3 refund keys", 3, result.size)
        assertEquals(key1, result[0])
        assertEquals(key2, result[1])
        assertEquals(key3, result[2])
    }

    @Test
    fun `extractRefundKeysFromSecret returns empty list when no refund tag`() {
        val secret = """["HTLC",{"nonce":"n","data":"hash","tags":[["locktime","123"]]}]"""

        val result = CashuTokenCodec.extractRefundKeysFromSecret(secret)

        assertTrue("Should return empty list", result.isEmpty())
    }

    @Test
    fun `extractRefundKeysFromSecret returns empty list for plain secret`() {
        val plainSecret = "deadbeef"

        val result = CashuTokenCodec.extractRefundKeysFromSecret(plainSecret)

        assertTrue("Should return empty list for plain secret", result.isEmpty())
    }

    @Test
    fun `extractRefundKeysFromSecret handles combined tags`() {
        val secret = """["HTLC",{"nonce":"n","data":"hash","tags":[["locktime","1700000000"],["refund","02pubkey1","02pubkey2"],["sigflag","SIG_ALL"]]}]"""

        val locktimeResult = CashuTokenCodec.extractLocktimeFromSecret(secret)
        val refundResult = CashuTokenCodec.extractRefundKeysFromSecret(secret)

        assertEquals("Locktime should be extracted", 1700000000L, locktimeResult)
        assertEquals("Should have 2 refund keys", 2, refundResult.size)
    }

    // ==================== Edge Cases ====================

    @Test
    fun `encode empty proof list`() {
        val token = CashuTokenCodec.encodeHtlcProofsAsToken(emptyList(), "https://mint")

        assertTrue(token.startsWith("cashuA"))
        val json = decodeTokenJson(token)
        val proofsArray = json.getJSONArray("token").getJSONObject(0).getJSONArray("proofs")
        assertEquals("Empty proofs should produce empty array", 0, proofsArray.length())
    }

    @Test
    fun `handle special characters in mint URL`() {
        val specialUrl = "https://mint.example.com:8080/api/v1?param=value&other=123"
        val proofs = listOf(
            CashuTokenCodec.HtlcProof(amount = 1, id = "id", secret = "s", C = "C")
        )

        val token = CashuTokenCodec.encodeHtlcProofsAsToken(proofs, specialUrl)
        val parsed = CashuTokenCodec.parseHtlcToken(token)

        assertNotNull(parsed)
        assertEquals("Special URL should be preserved", specialUrl, parsed!!.second)
    }

    @Test
    fun `handle JSON special characters in secret`() {
        val secretWithQuotes = """["HTLC",{"nonce":"has\"quotes","data":"hash","tags":[]}]"""
        val proofs = listOf(
            CashuTokenCodec.HtlcProof(amount = 1, id = "id", secret = secretWithQuotes, C = "C")
        )

        val token = CashuTokenCodec.encodeHtlcProofsAsToken(proofs, "https://mint")
        val parsed = CashuTokenCodec.parseHtlcToken(token)

        assertNotNull(parsed)
        assertEquals("Secret with quotes should be preserved", secretWithQuotes, parsed!!.first[0].secret)
    }

    // ==================== Helper Functions ====================

    /**
     * Decode token to JSON object for test verification.
     */
    private fun decodeTokenJson(token: String): JSONObject {
        val base64 = token.removePrefix("cashuA").removePrefix("cashuB")
        val jsonBytes = Base64.getUrlDecoder().decode(base64)
        return JSONObject(String(jsonBytes))
    }
}
