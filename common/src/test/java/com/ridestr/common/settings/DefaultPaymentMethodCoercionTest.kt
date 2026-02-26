package com.ridestr.common.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ridestr.common.nostr.events.PaymentMethod
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for Phase 1.4 coercion of defaultPaymentMethod in SettingsManager.
 *
 * Non-cashu values must be coerced to cashu at both:
 * - Init time (stale prefs from pre-coercion app versions or foreign Nostr client backups)
 * - Setter time (all callers that go through setDefaultPaymentMethod)
 */
@RunWith(RobolectricTestRunner::class)
class DefaultPaymentMethodCoercionTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `setDefaultPaymentMethod coerces non-cashu to cashu`() {
        val settings = SettingsManager(context)
        settings.setDefaultPaymentMethod("zelle")
        assertEquals(PaymentMethod.CASHU.value, settings.defaultPaymentMethod.value)
    }

    @Test
    fun `setDefaultPaymentMethod preserves cashu`() {
        val settings = SettingsManager(context)
        settings.setDefaultPaymentMethod("cashu")
        assertEquals("cashu", settings.defaultPaymentMethod.value)
    }

    @Test
    fun `setDefaultPaymentMethod coerces blank to cashu`() {
        val settings = SettingsManager(context)
        settings.setDefaultPaymentMethod("")
        assertEquals(PaymentMethod.CASHU.value, settings.defaultPaymentMethod.value)
    }

    @Test
    fun `init coerces stale non-cashu pref to cashu`() {
        // Write a stale non-cashu value directly to SharedPreferences (simulates pre-coercion version)
        val prefs = context.getSharedPreferences("ridestr_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("default_payment_method", "venmo").commit()

        // Create a new SettingsManager — init-time coercion should kick in
        val settings = SettingsManager(context)
        assertEquals(PaymentMethod.CASHU.value, settings.defaultPaymentMethod.value)

        // Verify the pref was also persisted as cashu (not just in-memory)
        val persistedValue = prefs.getString("default_payment_method", null)
        assertEquals(PaymentMethod.CASHU.value, persistedValue)
    }

    @Test
    fun `init preserves cashu pref`() {
        val prefs = context.getSharedPreferences("ridestr_settings", Context.MODE_PRIVATE)
        prefs.edit().putString("default_payment_method", "cashu").commit()

        val settings = SettingsManager(context)
        assertEquals("cashu", settings.defaultPaymentMethod.value)
    }
}
