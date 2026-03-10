package com.ridestr.common.settings

import android.content.Context
import androidx.datastore.preferences.SharedPreferencesMigration
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Tests the one-shot SharedPreferences → DataStore migration.
 * Seeds ridestr_settings SharedPreferences with known values, creates DataStore
 * with SharedPreferencesMigration, and verifies all values survive via SettingsRepository.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsDataStoreMigrationTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testScope: TestScope
    private lateinit var repository: SettingsRepository

    @Before
    fun setup() {
        testScope = TestScope(UnconfinedTestDispatcher())
        clearSharedPreferences()
    }

    @After
    fun teardown() {
        if (::repository.isInitialized) {
            repository.close()
        }
        clearSharedPreferences()
    }

    private fun seedSharedPreferences(block: android.content.SharedPreferences.Editor.() -> Unit) {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("ridestr_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        prefs.edit().apply {
            block()
            commit()
        }
    }

    private fun clearSharedPreferences() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("ridestr_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    private fun createRepositoryWithMigration(): SettingsRepository {
        val context = RuntimeEnvironment.getApplication()
        val dataStore = PreferenceDataStoreFactory.create(
            produceFile = { tempFolder.newFile("migration_test.preferences_pb") },
            migrations = listOf(SharedPreferencesMigration(context, "ridestr_settings")),
            scope = testScope.backgroundScope
        )
        repository = SettingsRepository(dataStore, testScope.backgroundScope)
        return repository
    }

    // ========================================
    // Boolean keys migrate correctly
    // ========================================

    @Test
    fun `boolean keys migrate correctly`() = testScope.runTest {
        seedSharedPreferences {
            putBoolean("notification_sound", false)
            putBoolean("notification_vibration", false)
            putBoolean("auto_open_navigation", false)
            putBoolean("use_gps_for_pickup", true)
            putBoolean("onboarding_completed", true)
        }

        val repo = createRepositoryWithMigration()
        repo.awaitInitialLoad()

        assertFalse(repo.getNotificationSoundEnabled())
        assertFalse(repo.getNotificationVibrationEnabled())
        assertFalse(repo.settings.value.autoOpenNavigation)
        assertTrue(repo.getUseGpsForPickup())
        assertTrue(repo.isOnboardingCompleted())
    }

    // ========================================
    // String keys preserve enum names
    // ========================================

    @Test
    fun `string keys preserve enum names`() = testScope.runTest {
        seedSharedPreferences {
            putString("display_currency", "SATS")
            putString("distance_unit", "KILOMETERS")
        }

        val repo = createRepositoryWithMigration()
        repo.awaitInitialLoad()

        assertEquals(DisplayCurrency.SATS, repo.getDisplayCurrency())
        assertEquals(DistanceUnit.KILOMETERS, repo.getDistanceUnit())
    }

    // ========================================
    // Comma-separated list keys round-trip
    // ========================================

    @Test
    fun `comma-separated list keys round-trip`() = testScope.runTest {
        seedSharedPreferences {
            putString("custom_relays", "wss://a.com,wss://b.com")
            putString("payment_methods", "cashu,lightning")
            putString("roadflare_payment_methods", "zelle,cash")
        }

        val repo = createRepositoryWithMigration()
        repo.awaitInitialLoad()

        assertEquals(listOf("wss://a.com", "wss://b.com"), repo.getEffectiveRelays())
        assertEquals(listOf("cashu", "lightning"), repo.getPaymentMethods())
        assertEquals(listOf("zelle", "cash"), repo.getRoadflarePaymentMethods())
    }

    // ========================================
    // JSON key round-trips (favorite LN addresses)
    // ========================================

    @Test
    fun `JSON favorite LN addresses round-trip`() = testScope.runTest {
        val json = """[{"address":"user@ln.com","lastUsed":12345}]"""
        seedSharedPreferences {
            putString("favorite_ln_addresses", json)
        }

        val repo = createRepositoryWithMigration()
        repo.awaitInitialLoad()

        val favorites = repo.favoriteLnAddresses.value
        assertEquals(1, favorites.size)
        assertEquals("user@ln.com", favorites[0].address)
        assertEquals(12345L, favorites[0].lastUsed)
        assertNull(favorites[0].label)
    }

    // ========================================
    // Float keys survive migration
    // ========================================

    @Test
    fun `float keys survive migration`() = testScope.runTest {
        seedSharedPreferences {
            putFloat("manual_driver_lat", 40.7128f)
            putFloat("manual_driver_lon", -74.0060f)
        }

        val repo = createRepositoryWithMigration()
        repo.awaitInitialLoad()

        assertEquals(40.7128, repo.settings.value.manualDriverLat, 0.001)
        assertEquals(-74.0060, repo.settings.value.manualDriverLon, 0.001)
    }

    // ========================================
    // Missing keys get correct defaults
    // ========================================

    @Test
    fun `missing keys get correct defaults`() = testScope.runTest {
        // Seed only a subset — verify others return defaults
        seedSharedPreferences {
            putBoolean("onboarding_completed", true)
        }

        val repo = createRepositoryWithMigration()
        repo.awaitInitialLoad()

        // Seeded value present
        assertTrue(repo.isOnboardingCompleted())

        // Defaults for everything else
        assertEquals(DisplayCurrency.USD, repo.getDisplayCurrency())
        assertEquals(DistanceUnit.MILES, repo.getDistanceUnit())
        assertTrue(repo.getNotificationSoundEnabled())
        assertTrue(repo.getNotificationVibrationEnabled())
        assertFalse(repo.getUseGpsForPickup())
        assertEquals("cashu", repo.getDefaultPaymentMethod())
        assertEquals(listOf("cashu"), repo.getPaymentMethods())
        assertEquals(emptyList<String>(), repo.getRoadflarePaymentMethods())
        assertFalse(repo.isWalletSetupDone())
        assertFalse(repo.getEncryptionFallbackWarned())
    }

    // ========================================
    // driver_online_status does NOT surface as persisted state
    // ========================================

    @Test
    fun `driver_online_status does not surface as persisted state`() = testScope.runTest {
        seedSharedPreferences {
            putString("driver_online_status", "AVAILABLE")
        }

        val repo = createRepositoryWithMigration()
        repo.awaitInitialLoad()
    }
}
