package com.ridestr.common.ui

import com.ridestr.common.nostr.events.Location
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocationSearchFieldLogicTest {

    private val someLocation = Location(36.0, -115.0)

    @Test
    fun `dropdown closed for 1-2 char input without showMyLocation`() {
        assertFalse(shouldShowLocationDropdown(isFocused = true, selectedLocation = null, valueLength = 1, showMyLocation = false))
        assertFalse(shouldShowLocationDropdown(isFocused = true, selectedLocation = null, valueLength = 2, showMyLocation = false))
    }

    @Test
    fun `dropdown open for 3+ char input`() {
        assertTrue(shouldShowLocationDropdown(isFocused = true, selectedLocation = null, valueLength = 3, showMyLocation = false))
        assertTrue(shouldShowLocationDropdown(isFocused = true, selectedLocation = null, valueLength = 10, showMyLocation = false))
    }

    @Test
    fun `dropdown open for showMyLocation even with empty input`() {
        assertTrue(shouldShowLocationDropdown(isFocused = true, selectedLocation = null, valueLength = 0, showMyLocation = true))
    }

    @Test
    fun `dropdown closed when location already selected`() {
        assertFalse(shouldShowLocationDropdown(isFocused = true, selectedLocation = someLocation, valueLength = 5, showMyLocation = false))
    }

    @Test
    fun `dropdown closed when not focused`() {
        assertFalse(shouldShowLocationDropdown(isFocused = false, selectedLocation = null, valueLength = 5, showMyLocation = false))
    }
}
