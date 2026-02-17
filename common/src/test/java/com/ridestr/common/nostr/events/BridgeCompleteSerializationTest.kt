package com.ridestr.common.nostr.events

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for BridgeComplete serialization and parsing.
 *
 * Covers:
 * 1. toJson() writes preimage_encrypted, NOT plaintext preimage
 * 2. fromJson() with preimage_encrypted → isEncrypted == true
 * 3. fromJson() with legacy preimage → isEncrypted == false
 * 4. fromJson() with neither field → returns null (rejected)
 * 5. fromJson() with both fields → isEncrypted == true, preimageEncrypted preferred
 */
@RunWith(RobolectricTestRunner::class)
class BridgeCompleteSerializationTest {

    @Test
    fun `toJson writes preimage_encrypted not plaintext preimage`() {
        val action = RiderRideAction.BridgeComplete(
            preimageEncrypted = "encrypted_data_here",
            amountSats = 15000,
            feesSats = 150,
            at = 1234567890
        )

        val json = action.toJson()

        assertTrue("Should have preimage_encrypted", json.has("preimage_encrypted"))
        assertEquals("encrypted_data_here", json.getString("preimage_encrypted"))
        assertFalse("Should NOT have plaintext preimage", json.has("preimage"))
        assertEquals("bridge_complete", json.getString("action"))
        assertEquals(15000L, json.getLong("amount"))
        assertEquals(150L, json.getLong("fees"))
        assertEquals(1234567890L, json.getLong("at"))
    }

    @Test
    fun `parse new format with preimage_encrypted sets isEncrypted true`() {
        val json = JSONObject().apply {
            put("action", "bridge_complete")
            put("preimage_encrypted", "encrypted_preimage")
            put("amount", 10000)
            put("fees", 100)
            put("at", 1000000000)
        }

        val result = RiderRideAction.fromJson(json)

        assertNotNull("Should parse successfully", result)
        assertTrue("Should be BridgeComplete", result is RiderRideAction.BridgeComplete)
        val bridge = result as RiderRideAction.BridgeComplete
        assertTrue("isEncrypted should be true", bridge.isEncrypted)
        assertEquals("encrypted_preimage", bridge.preimageEncrypted)
        assertNull("Legacy preimage should be null", bridge.preimage)
        assertEquals(10000L, bridge.amountSats)
        assertEquals(100L, bridge.feesSats)
    }

    @Test
    fun `parse legacy format with plaintext preimage sets isEncrypted false`() {
        val json = JSONObject().apply {
            put("action", "bridge_complete")
            put("preimage", "plaintext_preimage_hex")
            put("amount", 8000)
            put("fees", 50)
            put("at", 1000000000)
        }

        val result = RiderRideAction.fromJson(json)

        assertNotNull("Should parse successfully", result)
        assertTrue("Should be BridgeComplete", result is RiderRideAction.BridgeComplete)
        val bridge = result as RiderRideAction.BridgeComplete
        assertFalse("isEncrypted should be false", bridge.isEncrypted)
        assertNull("preimageEncrypted should be null", bridge.preimageEncrypted)
        assertEquals("plaintext_preimage_hex", bridge.preimage)
    }

    @Test
    fun `parse with neither preimage field returns null`() {
        val json = JSONObject().apply {
            put("action", "bridge_complete")
            put("amount", 5000)
            put("fees", 25)
            put("at", 1000000000)
        }

        val result = RiderRideAction.fromJson(json)

        assertNull("Should reject action with no preimage fields", result)
    }

    @Test
    fun `parse with both fields prefers preimageEncrypted`() {
        val json = JSONObject().apply {
            put("action", "bridge_complete")
            put("preimage_encrypted", "encrypted_value")
            put("preimage", "plaintext_value")
            put("amount", 12000)
            put("fees", 120)
            put("at", 1000000000)
        }

        val result = RiderRideAction.fromJson(json)

        assertNotNull("Should parse successfully", result)
        val bridge = result as RiderRideAction.BridgeComplete
        assertTrue("isEncrypted should be true when both present", bridge.isEncrypted)
        assertEquals("encrypted_value", bridge.preimageEncrypted)
        assertEquals("plaintext_value", bridge.preimage)
    }

    @Test
    fun `createBridgeCompleteAction sets preimageEncrypted correctly`() {
        val action = RiderRideStateEvent.createBridgeCompleteAction(
            preimageEncrypted = "nip44_encrypted",
            amountSats = 20000,
            feesSats = 200
        )

        assertTrue(action.isEncrypted)
        assertEquals("nip44_encrypted", action.preimageEncrypted)
        assertNull(action.preimage)
        assertEquals(20000L, action.amountSats)
        assertEquals(200L, action.feesSats)
    }

    @Test
    fun `round-trip serialization preserves encrypted format`() {
        val original = RiderRideStateEvent.createBridgeCompleteAction(
            preimageEncrypted = "encrypted_round_trip",
            amountSats = 9999,
            feesSats = 99
        )

        val json = original.toJson()
        val parsed = RiderRideAction.fromJson(json)

        assertNotNull(parsed)
        val bridge = parsed as RiderRideAction.BridgeComplete
        assertTrue(bridge.isEncrypted)
        assertEquals("encrypted_round_trip", bridge.preimageEncrypted)
        assertEquals(9999L, bridge.amountSats)
        assertEquals(99L, bridge.feesSats)
    }

    @Test
    fun `empty string preimage_encrypted is treated as absent`() {
        val json = JSONObject().apply {
            put("action", "bridge_complete")
            put("preimage_encrypted", "")
            put("preimage", "")
            put("amount", 1000)
            put("fees", 10)
            put("at", 1000000000)
        }

        val result = RiderRideAction.fromJson(json)

        assertNull("Empty strings should be treated as absent", result)
    }
}
