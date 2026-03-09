package com.ridestr.common.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ridestr.common.nostr.events.SettingsBackup
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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testScope: TestScope
    private lateinit var repository: SettingsRepository

    @Before
    fun setup() {
        testScope = TestScope(UnconfinedTestDispatcher())
        val dataStore = PreferenceDataStoreFactory.create(scope = testScope.backgroundScope) {
            tempFolder.newFile("test.preferences_pb")
        }
        repository = SettingsRepository(dataStore, testScope.backgroundScope)
    }

    @After
    fun teardown() {
        repository.close()  // Cancel scope → kills init collector + all stateIn() subscriptions
    }

    // ========================================
    // 1. Default values
    // ========================================

    @Test
    fun `default values match expected defaults`() = testScope.runTest {
        repository.awaitInitialLoad()

        assertEquals(DisplayCurrency.USD, repository.getDisplayCurrency())
        assertEquals(DistanceUnit.MILES, repository.getDistanceUnit())
        assertTrue(repository.getNotificationSoundEnabled())
        assertTrue(repository.getNotificationVibrationEnabled())
        assertFalse(repository.getUseGpsForPickup())
        assertEquals("cashu", repository.getDefaultPaymentMethod())
        assertEquals(listOf("cashu"), repository.getPaymentMethods())
        assertEquals(emptyList<String>(), repository.getRoadflarePaymentMethods())
        assertFalse(repository.isOnboardingCompleted())
        assertFalse(repository.isWalletSetupDone())
        assertFalse(repository.getEncryptionFallbackWarned())
        assertNull(repository.driverOnlineStatus.value)
    }

    // ========================================
    // 2. Setters write correctly
    // ========================================

    @Test
    fun `setNotificationSoundEnabled writes and updates`() = testScope.runTest {
        repository.awaitInitialLoad()

        repository.setNotificationSoundEnabled(false)
        assertFalse(repository.getNotificationSoundEnabled())

        repository.setNotificationSoundEnabled(true)
        assertTrue(repository.getNotificationSoundEnabled())
    }

    @Test
    fun `setOnboardingCompleted writes correctly`() = testScope.runTest {
        repository.awaitInitialLoad()

        repository.setOnboardingCompleted(true)
        assertTrue(repository.isOnboardingCompleted())
    }

    // ========================================
    // 3. toggleDisplayCurrency cycles SATS/USD
    // ========================================

    @Test
    fun `toggleDisplayCurrency cycles between SATS and USD`() = testScope.runTest {
        repository.awaitInitialLoad()

        assertEquals(DisplayCurrency.USD, repository.getDisplayCurrency())
        repository.toggleDisplayCurrency()
        assertEquals(DisplayCurrency.SATS, repository.getDisplayCurrency())
        repository.toggleDisplayCurrency()
        assertEquals(DisplayCurrency.USD, repository.getDisplayCurrency())
    }

    // ========================================
    // 4. toggleDistanceUnit cycles MILES/KILOMETERS
    // ========================================

    @Test
    fun `toggleDistanceUnit cycles between MILES and KILOMETERS`() = testScope.runTest {
        repository.awaitInitialLoad()

        assertEquals(DistanceUnit.MILES, repository.getDistanceUnit())
        repository.toggleDistanceUnit()
        assertEquals(DistanceUnit.KILOMETERS, repository.getDistanceUnit())
        repository.toggleDistanceUnit()
        assertEquals(DistanceUnit.MILES, repository.getDistanceUnit())
    }

    // ========================================
    // 5. Payment method coercion: empty → ["cashu"]
    // ========================================

    @Test
    fun `empty payment methods list defaults to cashu`() = testScope.runTest {
        repository.awaitInitialLoad()

        repository.setPaymentMethods(emptyList())
        assertEquals(listOf("cashu"), repository.getPaymentMethods())
    }

    // ========================================
    // 6. Default payment method coercion: non-cashu → "cashu"
    // ========================================

    @Test
    fun `non-cashu default payment method is coerced to cashu`() = testScope.runTest {
        repository.awaitInitialLoad()

        repository.setDefaultPaymentMethod("lightning")
        assertEquals("cashu", repository.getDefaultPaymentMethod())
    }

    // ========================================
    // 7. RoadFlare payment methods: blank trim + distinct
    // ========================================

    @Test
    fun `roadflare payment methods trims blanks and deduplicates`() = testScope.runTest {
        repository.awaitInitialLoad()

        repository.setRoadflarePaymentMethods(listOf("zelle", "", "zelle", "cash"))
        val methods = repository.getRoadflarePaymentMethods()
        assertEquals(listOf("zelle", "cash"), methods)
    }

    // ========================================
    // 8. Relay add/remove/reset
    // ========================================

    @Test
    fun `addRelay auto-prepends wss and preserves ws`() = testScope.runTest {
        repository.awaitInitialLoad()

        repository.addRelay("relay.example.com")
        val relays = repository.getEffectiveRelays()
        assertTrue(relays.contains("wss://relay.example.com"))

        repository.addRelay("ws://insecure.relay.com")
        val relays2 = repository.getEffectiveRelays()
        assertTrue(relays2.contains("ws://insecure.relay.com"))
    }

    @Test
    fun `resetRelaysToDefault clears custom relays`() = testScope.runTest {
        repository.awaitInitialLoad()

        repository.addRelay("wss://custom.relay.com")
        assertTrue(repository.isUsingCustomRelays())

        repository.resetRelaysToDefault()
        assertFalse(repository.isUsingCustomRelays())
        assertEquals(SettingsRepository.DEFAULT_RELAYS, repository.getEffectiveRelays())
    }

    // ========================================
    // 9. Relay add/remove seeds from DEFAULT_RELAYS when empty
    // ========================================

    @Test
    fun `addRelay seeds from defaults when custom list is empty`() = testScope.runTest {
        repository.awaitInitialLoad()

        assertFalse(repository.isUsingCustomRelays())
        repository.addRelay("wss://new.relay.com")
        val relays = repository.getEffectiveRelays()
        // Should contain defaults + the new one
        assertTrue(relays.containsAll(SettingsRepository.DEFAULT_RELAYS))
        assertTrue(relays.contains("wss://new.relay.com"))
    }

    @Test
    fun `removeRelay seeds from defaults when custom list is empty`() = testScope.runTest {
        repository.awaitInitialLoad()

        // Remove one of the defaults — should result in defaults minus that one
        repository.removeRelay("wss://nos.lol")
        val relays = repository.getEffectiveRelays()
        assertFalse(relays.contains("wss://nos.lol"))
        assertTrue(relays.contains("wss://relay.damus.io"))
    }

    // ========================================
    // 10. Favorite LN address operations
    // ========================================

    @Test
    fun `addFavoriteLnAddress adds and deduplicates`() = testScope.runTest {
        repository.awaitInitialLoad()

        repository.addFavoriteLnAddress("user@ln.com", "My Address")
        assertEquals(1, repository.favoriteLnAddresses.value.size)

        // Adding same address (case insensitive) should not duplicate
        repository.addFavoriteLnAddress("USER@LN.COM", "Different Label")
        assertEquals(1, repository.favoriteLnAddresses.value.size)
    }

    @Test
    fun `removeFavoriteLnAddress removes by address`() = testScope.runTest {
        repository.awaitInitialLoad()

        repository.addFavoriteLnAddress("user@ln.com")
        assertEquals(1, repository.favoriteLnAddresses.value.size)

        repository.removeFavoriteLnAddress("user@ln.com")
        assertEquals(0, repository.favoriteLnAddresses.value.size)
    }

    // ========================================
    // 11. toBackupData returns correct SettingsBackup
    // ========================================

    @Test
    fun `toBackupData returns correct defaults`() = testScope.runTest {
        repository.awaitInitialLoad()

        val backup = repository.toBackupData()
        assertEquals(DisplayCurrency.USD, backup.displayCurrency)
        assertEquals(DistanceUnit.MILES, backup.distanceUnit)
        assertTrue(backup.notificationSoundEnabled)
        assertTrue(backup.notificationVibrationEnabled)
        assertTrue(backup.autoOpenNavigation)
        assertTrue(backup.alwaysAskVehicle)
        assertEquals(emptyList<String>(), backup.customRelays)
        assertEquals(listOf("cashu"), backup.paymentMethods)
        assertEquals("cashu", backup.defaultPaymentMethod)
        assertNull(backup.mintUrl)
    }

    // ========================================
    // 12. restoreFromBackup atomically restores
    // ========================================

    @Test
    fun `restoreFromBackup restores all settings atomically`() = testScope.runTest {
        repository.awaitInitialLoad()

        val backup = SettingsBackup(
            displayCurrency = DisplayCurrency.SATS,
            distanceUnit = DistanceUnit.KILOMETERS,
            notificationSoundEnabled = false,
            notificationVibrationEnabled = false,
            autoOpenNavigation = false,
            alwaysAskVehicle = false,
            customRelays = listOf("wss://custom.relay"),
            paymentMethods = listOf("cashu", "lightning"),
            defaultPaymentMethod = "cashu",
            mintUrl = "https://mint.example.com",
            roadflarePaymentMethods = listOf("zelle")
        )

        repository.restoreFromBackup(backup)

        assertEquals(DisplayCurrency.SATS, repository.getDisplayCurrency())
        assertEquals(DistanceUnit.KILOMETERS, repository.getDistanceUnit())
        assertFalse(repository.getNotificationSoundEnabled())
        assertFalse(repository.getNotificationVibrationEnabled())
        assertEquals(listOf("wss://custom.relay"), repository.getEffectiveRelays())
    }

    // ========================================
    // 13. clearAllData resets to defaults
    // ========================================

    @Test
    fun `clearAllData resets all settings to defaults`() = testScope.runTest {
        repository.awaitInitialLoad()

        repository.toggleDisplayCurrency()
        repository.setNotificationSoundEnabled(false)
        repository.setDriverOnlineStatus("AVAILABLE")

        repository.clearAllData()

        assertEquals(DisplayCurrency.USD, repository.getDisplayCurrency())
        assertTrue(repository.getNotificationSoundEnabled())
        assertNull(repository.driverOnlineStatus.value)
    }

    // ========================================
    // 15. getEffectiveRelays returns custom or defaults
    // ========================================

    @Test
    fun `getEffectiveRelays returns defaults when no custom relays`() = testScope.runTest {
        repository.awaitInitialLoad()

        assertEquals(SettingsRepository.DEFAULT_RELAYS, repository.getEffectiveRelays())
    }

    @Test
    fun `getEffectiveRelays returns custom relays when set`() = testScope.runTest {
        repository.awaitInitialLoad()

        repository.addRelay("wss://my.relay.com")
        val relays = repository.getEffectiveRelays()
        assertTrue(relays.contains("wss://my.relay.com"))
    }

    // ========================================
    // 16. sanitizeActiveVehicleId clears orphaned IDs
    // ========================================

    @Test
    fun `sanitizeActiveVehicleId clears orphaned ID`() = testScope.runTest {
        repository.awaitInitialLoad()

        repository.setActiveVehicleId("vehicle-123")
        repository.sanitizeActiveVehicleId(setOf("vehicle-456", "vehicle-789"))
        assertNull(repository.settings.value.activeVehicleId)
    }

    @Test
    fun `sanitizeActiveVehicleId keeps valid ID`() = testScope.runTest {
        repository.awaitInitialLoad()

        repository.setActiveVehicleId("vehicle-123")
        repository.sanitizeActiveVehicleId(setOf("vehicle-123", "vehicle-456"))
        assertEquals("vehicle-123", repository.settings.value.activeVehicleId)
    }

    // ========================================
    // 17. driverOnlineStatus is synchronous (non-persisted)
    // ========================================

    @Test
    fun `driverOnlineStatus is synchronous and non-persisted`() = testScope.runTest {
        repository.awaitInitialLoad()

        assertNull(repository.driverOnlineStatus.value)
        repository.setDriverOnlineStatus("AVAILABLE")
        assertEquals("AVAILABLE", repository.driverOnlineStatus.value)
        repository.setDriverOnlineStatus(null)
        assertNull(repository.driverOnlineStatus.value)
    }

    // ========================================
    // 18. driverOnlineStatus not included in backup
    // ========================================

    @Test
    fun `driverOnlineStatus not included in backup or restore`() = testScope.runTest {
        repository.awaitInitialLoad()

        repository.setDriverOnlineStatus("AVAILABLE")
        val backup = repository.toBackupData()
        // SettingsBackup has no driverOnlineStatus field

        repository.clearAllData()
        assertNull(repository.driverOnlineStatus.value)

        repository.restoreFromBackup(backup)
        assertNull(repository.driverOnlineStatus.value)
    }

    // ========================================
    // 19. Synchronous getters work after awaitInitialLoad
    // ========================================

    @Test
    fun `synchronous getters work after awaitInitialLoad`() = testScope.runTest {
        repository.awaitInitialLoad()

        assertFalse(repository.isOnboardingCompleted())
        assertFalse(repository.isWalletSetupDone())

        repository.setOnboardingCompleted(true)
        assertTrue(repository.isOnboardingCompleted())

        repository.setWalletSetupCompleted(true)
        assertTrue(repository.isWalletSetupDone())
    }

    // ========================================
    // 20. tilesSetupCompleted
    // ========================================

    @Test
    fun `tilesSetupCompleted defaults to false`() = testScope.runTest {
        repository.awaitInitialLoad()

        assertFalse(repository.isTilesSetupCompleted())
        assertFalse(repository.tilesSetupCompleted.value)
    }

    @Test
    fun `setTilesSetupCompleted updates correctly`() = testScope.runTest {
        repository.awaitInitialLoad()

        repository.setTilesSetupCompleted(true)
        assertTrue(repository.isTilesSetupCompleted())
    }

    // ========================================
    // 21. hasCustomSettings
    // ========================================

    @Test
    fun `hasCustomSettings returns false for defaults`() = testScope.runTest {
        repository.awaitInitialLoad()

        assertFalse(repository.hasCustomSettings())
    }

    @Test
    fun `hasCustomSettings returns true after changing syncable setting`() = testScope.runTest {
        repository.awaitInitialLoad()

        repository.toggleDisplayCurrency()
        assertTrue(repository.hasCustomSettings())
    }

    // ========================================
    // 22. SettingsUiState combined flow
    // ========================================

    @Test
    fun `SettingsUiState reflects all user-facing settings`() = testScope.runTest {
        repository.awaitInitialLoad()

        repository.toggleDisplayCurrency()
        repository.setNotificationSoundEnabled(false)
        repository.setWalletSetupCompleted(true)

        val uiState = repository.settings.value
        assertEquals(DisplayCurrency.SATS, uiState.displayCurrency)
        assertFalse(uiState.notificationSoundEnabled)
        assertTrue(uiState.walletSetupCompleted)
    }
}
