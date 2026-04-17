package com.drivestr.app.viewmodels

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * JSON contract tests for driver HTLC persistence.
 *
 * Tests the serialization/deserialization contract that saveRideState() and
 * restoreRideState() rely on. Exercises the same JSON parsing patterns used in
 * DriverViewModel without depending on Android context or ViewModel internals.
 *
 * Key invariants:
 * - activePaymentHash, activePreimage, activeEscrowToken round-trip through JSON
 * - canSettleEscrow is DERIVED from field presence (preimage + escrowToken), never persisted
 * - Null escrow from preimage share preserves existing token (merge-not-overwrite)
 * - Old JSON without HTLC fields restores to null defaults gracefully
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RideStatePersistenceTest {

    // -- Helpers matching DriverViewModel's exact save/restore patterns --

    /** Mirrors saveRideState() — only writes non-null HTLC fields */
    private fun buildJsonWithHtlcFields(
        activePaymentHash: String? = null,
        activePreimage: String? = null,
        activeEscrowToken: String? = null
    ): JSONObject {
        return JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("stage", "RIDING")
            activePaymentHash?.let { put("activePaymentHash", it) }
            activePreimage?.let { put("activePreimage", it) }
            activeEscrowToken?.let { put("activeEscrowToken", it) }
        }
    }

    /** Mirrors restoreRideState() parsing — exact same conditional pattern */
    private data class RestoredHtlcState(
        val activePaymentHash: String?,
        val activePreimage: String?,
        val activeEscrowToken: String?,
        val canSettleEscrow: Boolean
    )

    private fun restoreHtlcFields(data: JSONObject): RestoredHtlcState {
        val activePaymentHash = if (data.has("activePaymentHash")) data.getString("activePaymentHash") else null
        val activePreimage = if (data.has("activePreimage")) data.getString("activePreimage") else null
        val activeEscrowToken = if (data.has("activeEscrowToken")) data.getString("activeEscrowToken") else null
        // Derive canSettleEscrow from field presence — never persist the boolean
        val canSettleEscrow = activePreimage != null && activeEscrowToken != null
        return RestoredHtlcState(activePaymentHash, activePreimage, activeEscrowToken, canSettleEscrow)
    }

    // -- Test 1: HTLC fields round-trip --

    @Test
    fun `HTLC fields round-trip through JSON serialization`() {
        val paymentHash = "abc123def456paymenthash"
        val preimage = "secretpreimage789"
        val escrowToken = "cashuAescrowTokenPayload"

        val json = buildJsonWithHtlcFields(
            activePaymentHash = paymentHash,
            activePreimage = preimage,
            activeEscrowToken = escrowToken
        )

        val restored = restoreHtlcFields(json)

        assertEquals("Payment hash should round-trip", paymentHash, restored.activePaymentHash)
        assertEquals("Preimage should round-trip", preimage, restored.activePreimage)
        assertEquals("Escrow token should round-trip", escrowToken, restored.activeEscrowToken)
        assertTrue("canSettleEscrow should be true when both preimage and escrow present", restored.canSettleEscrow)
    }

    @Test
    fun `HTLC fields round-trip with only payment hash`() {
        val json = buildJsonWithHtlcFields(activePaymentHash = "onlyhash")

        val restored = restoreHtlcFields(json)

        assertEquals("onlyhash", restored.activePaymentHash)
        assertNull("Preimage should be null", restored.activePreimage)
        assertNull("Escrow token should be null", restored.activeEscrowToken)
        assertFalse("canSettleEscrow should be false without preimage", restored.canSettleEscrow)
    }

    // -- Test 2: canSettleEscrow derived correctly --

    @Test
    fun `canSettleEscrow true when both preimage and escrowToken present`() {
        val json = buildJsonWithHtlcFields(
            activePaymentHash = "hash",
            activePreimage = "preimage",
            activeEscrowToken = "token"
        )

        val restored = restoreHtlcFields(json)

        assertTrue("canSettleEscrow should be true", restored.canSettleEscrow)
    }

    @Test
    fun `canSettleEscrow false when preimage missing`() {
        val json = buildJsonWithHtlcFields(
            activePaymentHash = "hash",
            activePreimage = null,
            activeEscrowToken = "token"
        )

        val restored = restoreHtlcFields(json)

        assertFalse("canSettleEscrow should be false without preimage", restored.canSettleEscrow)
    }

    @Test
    fun `canSettleEscrow false when escrowToken missing`() {
        val json = buildJsonWithHtlcFields(
            activePaymentHash = "hash",
            activePreimage = "preimage",
            activeEscrowToken = null
        )

        val restored = restoreHtlcFields(json)

        assertFalse("canSettleEscrow should be false without escrow token", restored.canSettleEscrow)
    }

    @Test
    fun `canSettleEscrow is NOT read from JSON even if persisted`() {
        // Simulate a hypothetical JSON that has canSettleEscrow explicitly set to true,
        // but is missing the escrow token. The restore logic must derive the value,
        // not trust the persisted boolean.
        val json = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("stage", "RIDING")
            put("activePaymentHash", "hash")
            put("activePreimage", "preimage")
            // No activeEscrowToken
            put("canSettleEscrow", true) // Rogue field — should be ignored
        }

        val restored = restoreHtlcFields(json)

        assertFalse(
            "canSettleEscrow must be derived from field presence, not read from JSON",
            restored.canSettleEscrow
        )
    }

    // -- Test 3: escrowToken merge-not-overwrite --

    @Test
    fun `null escrowToken from preimage share preserves existing token`() {
        // Simulate: confirmation saved escrowToken to session, then preimage share
        // arrives with null escrowToken (it was in the encrypted content but missing).
        // The merge pattern: effectiveEscrowToken = escrowToken ?: session.activeEscrowToken
        val existingEscrowToken = "tokenFromConfirmation"
        val incomingEscrowToken: String? = null

        val effectiveEscrowToken = incomingEscrowToken ?: existingEscrowToken

        assertEquals(
            "Existing escrow token should be preserved when incoming is null",
            "tokenFromConfirmation",
            effectiveEscrowToken
        )

        // Verify canSettleEscrow would be true with the merged result
        val preimage = "validPreimage"
        val canSettle = preimage != null && effectiveEscrowToken != null
        assertTrue("canSettleEscrow should be true with merged escrow token", canSettle)
    }

    @Test
    fun `non-null escrowToken from preimage share replaces existing token`() {
        // When preimage share has a fresh escrow token, it should replace the old one
        val existingEscrowToken = "oldTokenFromConfirmation"
        val incomingEscrowToken = "freshTokenFromPreimageShare"

        val effectiveEscrowToken = incomingEscrowToken ?: existingEscrowToken

        assertEquals(
            "Fresh escrow token should replace existing when non-null",
            "freshTokenFromPreimageShare",
            effectiveEscrowToken
        )
    }

    @Test
    fun `merge pattern with both null results in null`() {
        val existingEscrowToken: String? = null
        val incomingEscrowToken: String? = null

        val effectiveEscrowToken = incomingEscrowToken ?: existingEscrowToken

        assertNull("Both null should result in null", effectiveEscrowToken)

        val preimage = "preimage"
        val canSettle = preimage != null && effectiveEscrowToken != null
        assertFalse("canSettleEscrow should be false when effective token is null", canSettle)
    }

    @Test
    fun `merge then persist then restore preserves merged token`() {
        // End-to-end: confirmation sets escrowToken → preimage share arrives with null →
        // merge preserves token → save to JSON → restore from JSON → token intact
        val existingEscrowToken = "tokenFromConfirmation"
        val incomingEscrowToken: String? = null
        val preimage = "preimageFromShare"

        // Merge step (as in handlePreimageShare)
        val effectiveEscrowToken = incomingEscrowToken ?: existingEscrowToken

        // Save step (as in saveRideState)
        val json = buildJsonWithHtlcFields(
            activePaymentHash = "somehash",
            activePreimage = preimage,
            activeEscrowToken = effectiveEscrowToken
        )

        // Restore step (as in restoreRideState)
        val restored = restoreHtlcFields(json)

        assertEquals("Payment hash should survive round-trip", "somehash", restored.activePaymentHash)
        assertEquals("Preimage should survive round-trip", preimage, restored.activePreimage)
        assertEquals("Merged escrow token should survive round-trip", "tokenFromConfirmation", restored.activeEscrowToken)
        assertTrue("canSettleEscrow should be true after round-trip", restored.canSettleEscrow)
    }

    // -- Test 4: backward compatibility --

    @Test
    fun `restore JSON without HTLC fields yields null defaults`() {
        // Simulate JSON from an older version that didn't persist HTLC fields
        val json = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("stage", "RIDING")
            // No HTLC fields at all
        }

        val restored = restoreHtlcFields(json)

        assertNull("activePaymentHash should be null for legacy JSON", restored.activePaymentHash)
        assertNull("activePreimage should be null for legacy JSON", restored.activePreimage)
        assertNull("activeEscrowToken should be null for legacy JSON", restored.activeEscrowToken)
        assertFalse("canSettleEscrow should be false for legacy JSON", restored.canSettleEscrow)
    }

    @Test
    fun `restore JSON with only some HTLC fields handles partial upgrade`() {
        // Simulate a crash mid-ride before all fields were populated
        val json = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("stage", "RIDING")
            put("activePaymentHash", "partialHash")
            // No preimage or escrowToken yet
        }

        val restored = restoreHtlcFields(json)

        assertEquals("partialHash", restored.activePaymentHash)
        assertNull("activePreimage should be null", restored.activePreimage)
        assertNull("activeEscrowToken should be null", restored.activeEscrowToken)
        assertFalse("canSettleEscrow should be false with partial fields", restored.canSettleEscrow)
    }

    @Test
    fun `authoritative fiat fare round-trips through saved ride JSON`() {
        val json = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("stage", "RIDING")
            put("offer_fiat_amount", "12.50")
            put("offer_fiat_currency", "USD")
        }

        val restoredAmount = if (json.has("offer_fiat_amount")) json.getString("offer_fiat_amount") else null
        val restoredCurrency = if (json.has("offer_fiat_currency")) json.getString("offer_fiat_currency") else null

        assertEquals("12.50", restoredAmount)
        assertEquals("USD", restoredCurrency)
    }

    @Test
    fun `incomplete authoritative fiat fare payload restores as null`() {
        val json = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("stage", "RIDING")
            put("offer_fiat_amount", "12.50")
        }

        val restoredAmount = if (json.has("offer_fiat_amount")) json.getString("offer_fiat_amount") else null
        val restoredCurrency = if (json.has("offer_fiat_currency")) json.getString("offer_fiat_currency") else null
        val restoredFiatFare = if (restoredAmount != null && restoredCurrency != null) {
            restoredAmount to restoredCurrency
        } else {
            null
        }

        assertNull(restoredFiatFare)
    }
}
