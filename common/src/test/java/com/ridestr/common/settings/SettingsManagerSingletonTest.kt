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
 * Tests for SettingsManager singleton pattern.
 *
 * Verifies that getInstance() returns a shared instance so that settings changes
 * made in one component (e.g., UI) are immediately visible to another (e.g., ViewModel).
 * This prevents the stale-settings bug where multiple SettingsManager instances
 * diverge because each has its own MutableStateFlow cache.
 */
@RunWith(RobolectricTestRunner::class)
class SettingsManagerSingletonTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        SettingsManager.clearInstance()
        // Clear persisted state so tests don't leak values via SharedPreferences
        context.getSharedPreferences("ridestr_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun teardown() {
        SettingsManager.clearInstance()
        context.getSharedPreferences("ridestr_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `getInstance returns same instance`() {
        val a = SettingsManager.getInstance(context)
        val b = SettingsManager.getInstance(context)
        assertSame(a, b)
    }

    @Test
    fun `singleton shares state across callers`() {
        val a = SettingsManager.getInstance(context)
        val b = SettingsManager.getInstance(context)
        a.setRoadflarePaymentMethods(listOf("strike", "venmo"))
        assertEquals(listOf("strike", "venmo"), b.roadflarePaymentMethods.value)
    }

    @Test
    fun `direct constructor creates independent instance with separate state`() {
        val singleton = SettingsManager.getInstance(context)
        singleton.setRoadflarePaymentMethods(listOf("strike"))
        val separate = SettingsManager(context) // internal — allowed in same module
        separate.setRoadflarePaymentMethods(listOf("cash_app"))
        // Singleton is unaffected by the separate instance's write
        assertEquals(listOf("strike"), singleton.roadflarePaymentMethods.value)
    }
}
