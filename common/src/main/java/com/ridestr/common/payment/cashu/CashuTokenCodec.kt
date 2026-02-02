package com.ridestr.common.payment.cashu

import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stateless utilities for Cashu token encoding/decoding and HTLC secret parsing.
 *
 * This object contains pure functions for:
 * - Encoding proofs (plain and HTLC) as Cashu tokens (cashuA format)
 * - Parsing Cashu tokens to extract proofs
 * - Extracting data from NUT-10/14 HTLC secrets (payment hash, locktime, refund keys)
 *
 * All methods are stateless and can be called from any context.
 */
object CashuTokenCodec {

    private const val TAG = "CashuTokenCodec"

    /**
     * Representation of a proof with HTLC secret format.
     */
    data class HtlcProof(
        val amount: Long,
        val id: String,
        val secret: String,  // NUT-10 JSON format: ["HTLC", {...}]
        val C: String
    )

    // ==================== Token Encoding ====================

    /**
     * Encode HTLC proofs as a Cashu token (cashuA format).
     *
     * @param proofs List of HTLC proofs to encode
     * @param mintUrl The mint URL to include in the token
     * @return cashuA-prefixed Base64 token string
     */
    fun encodeHtlcProofsAsToken(proofs: List<HtlcProof>, mintUrl: String): String {
        val proofsArray = JSONArray()
        proofs.forEach { proof ->
            proofsArray.put(JSONObject().apply {
                put("amount", proof.amount)
                put("id", proof.id)
                put("secret", proof.secret)
                put("C", proof.C)
            })
        }

        val tokenJson = JSONObject().apply {
            put("token", JSONArray().put(JSONObject().apply {
                put("mint", mintUrl)
                put("proofs", proofsArray)
            }))
            put("unit", "sat")
        }

        return "cashuA" + Base64.encodeToString(
            tokenJson.toString().toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP
        )
    }

    /**
     * Encode plain proofs as a Cashu token (cashuA format).
     *
     * @param proofs List of plain proofs to encode
     * @param mintUrl The mint URL to include in the token
     * @return cashuA-prefixed Base64 token string
     */
    fun encodeProofsAsToken(proofs: List<CashuProof>, mintUrl: String): String {
        val proofsArray = JSONArray()
        proofs.forEach { proof ->
            proofsArray.put(proof.toJson())
        }

        val tokenJson = JSONObject().apply {
            put("token", JSONArray().put(JSONObject().apply {
                put("mint", mintUrl)
                put("proofs", proofsArray)
            }))
            put("unit", "sat")
        }

        return "cashuA" + Base64.encodeToString(
            tokenJson.toString().toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP
        )
    }

    // ==================== Token Decoding ====================

    /**
     * Parse a Cashu token to extract HTLC proofs.
     *
     * Supports both cashuA and cashuB token formats.
     *
     * @param token The cashuA/cashuB prefixed token string
     * @return Pair of (proofs, mintUrl) or null if invalid
     */
    fun parseHtlcToken(token: String): Pair<List<HtlcProof>, String>? {
        return try {
            val base64 = when {
                token.startsWith("cashuA") -> token.removePrefix("cashuA")
                token.startsWith("cashuB") -> token.removePrefix("cashuB")
                else -> {
                    Log.e(TAG, "Invalid token prefix: ${token.take(10)}")
                    return null
                }
            }

            val jsonStr = String(Base64.decode(base64, Base64.URL_SAFE))
            val json = JSONObject(jsonStr)

            val tokenArray = json.getJSONArray("token")
            if (tokenArray.length() == 0) return null

            val firstMint = tokenArray.getJSONObject(0)
            val mintUrl = firstMint.getString("mint")
            val proofsArray = firstMint.getJSONArray("proofs")

            val proofs = mutableListOf<HtlcProof>()
            for (i in 0 until proofsArray.length()) {
                val p = proofsArray.getJSONObject(i)
                proofs.add(HtlcProof(
                    amount = p.getLong("amount"),
                    id = p.getString("id"),
                    secret = p.getString("secret"),
                    C = p.getString("C")
                ))
            }

            Pair(proofs, mintUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse HTLC token: ${e.message}")
            null
        }
    }

    // ==================== HTLC Secret Parsing ====================

    /**
     * Extract payment_hash from NUT-10 HTLC secret.
     *
     * Format: ["HTLC", {"nonce": "...", "data": "<payment_hash>", "tags": [...]}]
     *
     * @param secret The HTLC secret JSON string
     * @return The payment hash (64-char hex), or null if not found/invalid
     */
    fun extractPaymentHashFromSecret(secret: String): String? {
        return try {
            val arr = JSONArray(secret)
            if (arr.length() >= 2 && arr.getString(0) == "HTLC") {
                val obj = arr.getJSONObject(1)
                obj.getString("data")
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract payment_hash from secret: ${e.message}")
            null
        }
    }

    /**
     * Extract locktime from NUT-10/14 HTLC secret.
     *
     * Format: ["HTLC", {"nonce": "...", "data": "...", "tags": [["locktime", "12345"], ...]}]
     *
     * @param secret The HTLC secret JSON string
     * @return The locktime as Unix timestamp, or null if not found/invalid
     */
    fun extractLocktimeFromSecret(secret: String): Long? {
        return try {
            val arr = JSONArray(secret)
            if (arr.length() >= 2 && arr.getString(0) == "HTLC") {
                val obj = arr.getJSONObject(1)
                val tags = obj.getJSONArray("tags")
                var locktime: Long? = null
                for (i in 0 until tags.length()) {
                    val tag = tags.getJSONArray(i)
                    if (tag.length() >= 2 && tag.getString(0) == "locktime") {
                        locktime = tag.getString(1).toLongOrNull()
                        break
                    }
                }
                locktime
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract locktime from secret: ${e.message}")
            null
        }
    }

    /**
     * Extract refund public keys from NUT-10/14 HTLC secret.
     *
     * Format: ["HTLC", {"nonce": "...", "data": "...", "tags": [["refund", "key1", "key2"], ...]}]
     *
     * @param secret The HTLC secret JSON string
     * @return List of refund public keys (hex), or empty list if none found
     */
    fun extractRefundKeysFromSecret(secret: String): List<String> {
        return try {
            val arr = JSONArray(secret)
            if (arr.length() >= 2 && arr.getString(0) == "HTLC") {
                val obj = arr.getJSONObject(1)
                val tags = obj.getJSONArray("tags")
                var refundKeys: List<String> = emptyList()
                for (i in 0 until tags.length()) {
                    val tag = tags.getJSONArray(i)
                    if (tag.length() >= 2 && tag.getString(0) == "refund") {
                        val keys = mutableListOf<String>()
                        for (j in 1 until tag.length()) {
                            keys.add(tag.getString(j))
                        }
                        refundKeys = keys
                        break
                    }
                }
                refundKeys
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract refund keys from secret: ${e.message}")
            emptyList()
        }
    }
}
