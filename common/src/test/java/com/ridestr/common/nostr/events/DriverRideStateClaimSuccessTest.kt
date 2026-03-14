package com.ridestr.common.nostr.events

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for the `claimSuccess` field on DriverRideAction.Status.
 *
 * Covers:
 * 1. claimSuccess=true round-trip through toJson/fromJson
 * 2. claimSuccess=false round-trip through toJson/fromJson
 * 3. claimSuccess=null omits key from JSON and parses back as null
 * 4. Backward compatibility: old JSON without claim_success key parses as null
 */
@RunWith(RobolectricTestRunner::class)
class DriverRideStateClaimSuccessTest {

    @Test
    fun `claimSuccess true round-trips through toJson and fromJson`() {
        val action = DriverRideStateEvent.createStatusAction(
            status = DriverStatusType.COMPLETED,
            claimSuccess = true
        )

        val json = action.toJson()
        assertTrue("JSON should contain claim_success", json.has("claim_success"))
        assertTrue("claim_success should be true in JSON", json.getBoolean("claim_success"))

        val parsed = DriverRideAction.fromJson(json)
        assertNotNull("Should parse successfully", parsed)
        assertTrue("Should be Status action", parsed is DriverRideAction.Status)
        val status = parsed as DriverRideAction.Status
        assertEquals(true, status.claimSuccess)
        assertEquals(DriverStatusType.COMPLETED, status.status)
    }

    @Test
    fun `claimSuccess false round-trips through toJson and fromJson`() {
        val action = DriverRideStateEvent.createStatusAction(
            status = DriverStatusType.COMPLETED,
            claimSuccess = false
        )

        val json = action.toJson()
        assertTrue("JSON should contain claim_success", json.has("claim_success"))
        assertFalse("claim_success should be false in JSON", json.getBoolean("claim_success"))

        val parsed = DriverRideAction.fromJson(json)
        assertNotNull("Should parse successfully", parsed)
        assertTrue("Should be Status action", parsed is DriverRideAction.Status)
        val status = parsed as DriverRideAction.Status
        assertEquals(false, status.claimSuccess)
    }

    @Test
    fun `claimSuccess null omits key from JSON and parses back as null`() {
        val action = DriverRideStateEvent.createStatusAction(
            status = DriverStatusType.COMPLETED
        )

        val json = action.toJson()
        assertFalse("JSON should NOT contain claim_success when null", json.has("claim_success"))

        val parsed = DriverRideAction.fromJson(json)
        assertNotNull("Should parse successfully", parsed)
        assertTrue("Should be Status action", parsed is DriverRideAction.Status)
        val status = parsed as DriverRideAction.Status
        assertNull("claimSuccess should be null", status.claimSuccess)
    }

    @Test
    fun `old event without claim_success key parses claimSuccess as null`() {
        val json = JSONObject().apply {
            put("action", DriverRideStateEvent.ActionType.STATUS)
            put("status", DriverStatusType.COMPLETED)
            put("at", 1000000000L)
            put("final_fare", 5000L)
        }

        val parsed = DriverRideAction.fromJson(json)
        assertNotNull("Should parse successfully", parsed)
        assertTrue("Should be Status action", parsed is DriverRideAction.Status)
        val status = parsed as DriverRideAction.Status
        assertNull("claimSuccess should be null for old events", status.claimSuccess)
        assertEquals(DriverStatusType.COMPLETED, status.status)
        assertEquals(5000L, status.finalFare)
    }
}
