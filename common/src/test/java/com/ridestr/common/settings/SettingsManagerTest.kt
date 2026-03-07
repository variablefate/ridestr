package com.ridestr.common.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for SettingsManager property defaults, setter→StateFlow propagation,
 * aliases, and relay semantics that roadflare-rider depends on.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsManagerTest {

    private lateinit var context: Context
    private lateinit var sm: SettingsManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        SettingsManager.clearInstance()
        context.getSharedPreferences("ridestr_settings", Context.MODE_PRIVATE).edit().clear().commit()
        sm = SettingsManager.getInstance(context)
    }

    @After
    fun teardown() {
        SettingsManager.clearInstance()
        context.getSharedPreferences("ridestr_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // --- New property defaults (Phase 2 additions) ---

    @Test
    fun `tilesSetupCompleted defaults to false`() {
        assertFalse(sm.tilesSetupCompleted.value)
    }

    @Test
    fun `onboardingCompleted defaults to false`() {
        assertFalse(sm.onboardingCompleted.value)
    }

    // --- Setter propagation ---

    @Test
    fun `setTilesSetupCompleted updates StateFlow immediately`() {
        sm.setTilesSetupCompleted(true)
        assertTrue(sm.tilesSetupCompleted.value)
    }

    @Test
    fun `setTilesSetupCompleted false after true`() {
        sm.setTilesSetupCompleted(true)
        sm.setTilesSetupCompleted(false)
        assertFalse(sm.tilesSetupCompleted.value)
    }

    @Test
    fun `setOnboardingCompleted updates StateFlow immediately`() {
        sm.setOnboardingCompleted(true)
        assertTrue(sm.onboardingCompleted.value)
    }

    // --- Aliases ---

    @Test
    fun `notificationSound alias returns same as notificationSoundEnabled`() {
        assertSame(sm.notificationSoundEnabled, sm.notificationSound)
    }

    @Test
    fun `notificationVibration alias returns same as notificationVibrationEnabled`() {
        assertSame(sm.notificationVibrationEnabled, sm.notificationVibration)
    }

    @Test
    fun `notificationSound alias reflects setter changes`() {
        sm.setNotificationSoundEnabled(false)
        assertFalse(sm.notificationSound.value)
        sm.setNotificationSoundEnabled(true)
        assertTrue(sm.notificationSound.value)
    }

    // --- Relay semantics ---

    @Test
    fun `customRelays empty returns empty list not defaults`() {
        assertTrue(sm.customRelays.value.isEmpty())
    }

    @Test
    fun `getEffectiveRelays returns defaults when customRelays empty`() {
        val effective = sm.getEffectiveRelays()
        assertEquals(SettingsManager.DEFAULT_RELAYS, effective)
    }

    @Test
    fun `getEffectiveRelays returns custom relays when set`() {
        sm.addRelay("wss://custom.relay")
        val effective = sm.getEffectiveRelays()
        assertTrue(effective.contains("wss://custom.relay"))
        assertNotEquals(SettingsManager.DEFAULT_RELAYS, effective)
    }

    @Test
    fun `addRelay to empty initializes from defaults then appends`() {
        sm.addRelay("wss://new.relay")
        val relays = sm.customRelays.value
        // Should contain all defaults plus the new relay
        assertTrue(relays.containsAll(SettingsManager.DEFAULT_RELAYS))
        assertTrue(relays.contains("wss://new.relay"))
        assertEquals(SettingsManager.DEFAULT_RELAYS.size + 1, relays.size)
    }

    @Test
    fun `resetRelaysToDefault clears custom relays`() {
        sm.addRelay("wss://custom.relay")
        sm.resetRelaysToDefault()
        assertTrue(sm.customRelays.value.isEmpty())
        assertEquals(SettingsManager.DEFAULT_RELAYS, sm.getEffectiveRelays())
    }

    // --- roadflarePaymentMethods ---

    @Test
    fun `roadflarePaymentMethods defaults to empty`() {
        assertTrue(sm.roadflarePaymentMethods.value.isEmpty())
    }

    @Test
    fun `roadflarePaymentMethods persists and restores after getInstance reset`() {
        sm.setRoadflarePaymentMethods(listOf("venmo", "cash_app"))
        assertEquals(listOf("venmo", "cash_app"), sm.roadflarePaymentMethods.value)

        // Reset singleton, get fresh instance (reads from SharedPreferences)
        SettingsManager.clearInstance()
        val fresh = SettingsManager.getInstance(context)
        assertEquals(listOf("venmo", "cash_app"), fresh.roadflarePaymentMethods.value)
    }

    // --- clearAllData ---

    @Test
    fun `clearAllData resets tilesSetupCompleted`() {
        sm.setTilesSetupCompleted(true)
        sm.clearAllData()
        assertFalse(sm.tilesSetupCompleted.value)
    }

    @Test
    fun `clearAllData resets roadflarePaymentMethods`() {
        sm.setRoadflarePaymentMethods(listOf("venmo"))
        sm.clearAllData()
        assertTrue(sm.roadflarePaymentMethods.value.isEmpty())
    }
}
