package com.ridestr.common.sync

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ridestr.common.data.SavedLocation
import com.ridestr.common.data.SavedLocationRepository
import com.ridestr.common.data.Vehicle
import com.ridestr.common.data.VehicleRepository
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.ProfileFetchResult
import com.ridestr.common.nostr.events.ProfileBackupData
import com.ridestr.common.nostr.events.SettingsBackup
import com.ridestr.common.nostr.relay.RelayManager
import com.ridestr.common.settings.DisplayCurrency
import com.ridestr.common.settings.SettingsRepository
import com.vitorpamplona.quartz.nip01Core.signers.NostrSigner
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
class ProfileSyncAdapterTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testScope: TestScope
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var nostrService: NostrService
    private lateinit var vehicleRepository: VehicleRepository
    private lateinit var savedLocationRepository: SavedLocationRepository
    private lateinit var mockSigner: NostrSigner
    private lateinit var mockRelayManager: RelayManager

    @Before
    fun setup() {
        testScope = TestScope(UnconfinedTestDispatcher())
        val dataStore = PreferenceDataStoreFactory.create(scope = testScope.backgroundScope) {
            tempFolder.newFile("test.preferences_pb")
        }
        settingsRepository = SettingsRepository(dataStore, testScope.backgroundScope)

        nostrService = mockk(relaxed = true)
        vehicleRepository = mockk(relaxed = true)
        savedLocationRepository = mockk(relaxed = true)
        mockSigner = mockk(relaxed = true)
        mockRelayManager = mockk(relaxed = true)
    }

    @After
    fun teardown() {
        settingsRepository.close()
    }

    private fun sampleVehicle(
        id: String,
        make: String = "Toyota",
        model: String = "Prius",
        year: Int = 2024,
        color: String = "Blue"
    ): Vehicle = Vehicle(
        id = id,
        make = make,
        model = model,
        year = year,
        color = color,
        licensePlate = "${id.uppercase()}-PLATE"
    )

    private fun sampleLocation(id: String, name: String): SavedLocation = SavedLocation(
        id = id,
        lat = 36.1699,
        lon = -115.1398,
        displayName = name,
        addressLine = "$name Address",
        locality = "Las Vegas"
    )

    // ========================================
    // hasLocalData() tests
    // ========================================

    @Test
    fun `hasLocalData returns true when only settings differ from defaults`() = testScope.runTest {
        settingsRepository.awaitInitialLoad()

        // Change a setting so it differs from defaults
        settingsRepository.toggleDisplayCurrency()

        every { vehicleRepository.hasVehicles() } returns false
        every { savedLocationRepository.hasLocations() } returns false

        val adapter = ProfileSyncAdapter(
            vehicleRepository = vehicleRepository,
            savedLocationRepository = savedLocationRepository,
            settingsRepository = settingsRepository,
            nostrService = nostrService
        )

        assertTrue("hasLocalData should be true when settings differ from defaults", adapter.hasLocalData())
    }

    @Test
    fun `hasLocalData returns false when all data is default and empty`() = testScope.runTest {
        settingsRepository.awaitInitialLoad()

        every { vehicleRepository.hasVehicles() } returns false
        every { savedLocationRepository.hasLocations() } returns false

        val adapter = ProfileSyncAdapter(
            vehicleRepository = vehicleRepository,
            savedLocationRepository = savedLocationRepository,
            settingsRepository = settingsRepository,
            nostrService = nostrService
        )

        assertFalse("hasLocalData should be false when everything is default", adapter.hasLocalData())
    }

    // ========================================
    // hasNostrBackup() tests
    // ========================================

    @Test
    fun `hasNostrBackup detects settings-only backups`() = testScope.runTest {
        settingsRepository.awaitInitialLoad()

        val settingsOnlyBackup = ProfileBackupData(
            eventId = "test-event",
            vehicles = emptyList(),
            savedLocations = emptyList(),
            settings = SettingsBackup(displayCurrency = DisplayCurrency.SATS),
            updatedAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis()
        )
        coEvery { nostrService.fetchProfileBackup() } returns settingsOnlyBackup

        val adapter = ProfileSyncAdapter(
            vehicleRepository = vehicleRepository,
            savedLocationRepository = savedLocationRepository,
            settingsRepository = settingsRepository,
            nostrService = nostrService
        )

        assertTrue(
            "hasNostrBackup should detect settings-only backups",
            adapter.hasNostrBackup("pubkey", mockRelayManager)
        )
    }

    @Test
    fun `fetchFromNostr settings-only backup marks settingsRestored true`() = testScope.runTest {
        settingsRepository.awaitInitialLoad()

        val settingsOnlyBackup = ProfileBackupData(
            eventId = "settings-only",
            vehicles = emptyList(),
            savedLocations = emptyList(),
            settings = SettingsBackup(displayCurrency = DisplayCurrency.SATS),
            updatedAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis()
        )
        coEvery { nostrService.fetchProfileBackup() } returns settingsOnlyBackup

        val adapter = ProfileSyncAdapter(
            vehicleRepository = null,
            savedLocationRepository = null,
            settingsRepository = settingsRepository,
            nostrService = nostrService
        )

        val result = adapter.fetchFromNostr(mockSigner, mockRelayManager)

        assertTrue(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assertEquals(1, success.itemCount)
        assertTrue(success.metadata is SyncMetadata.Profile)
        val metadata = success.metadata as SyncMetadata.Profile
        assertTrue(metadata.settingsRestored)
        assertEquals(DisplayCurrency.SATS, settingsRepository.getDisplayCurrency())
    }

    @Test
    fun `fetchFromNostr default settings backup marks settingsRestored false`() = testScope.runTest {
        settingsRepository.awaitInitialLoad()

        val defaultSettingsBackup = ProfileBackupData(
            eventId = "default-settings",
            vehicles = emptyList(),
            savedLocations = emptyList(),
            settings = SettingsBackup(),
            updatedAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis()
        )
        coEvery { nostrService.fetchProfileBackup() } returns defaultSettingsBackup

        val adapter = ProfileSyncAdapter(
            vehicleRepository = null,
            savedLocationRepository = null,
            settingsRepository = settingsRepository,
            nostrService = nostrService
        )

        val result = adapter.fetchFromNostr(mockSigner, mockRelayManager)

        assertTrue(result is SyncResult.Success)
        val success = result as SyncResult.Success
        assertEquals(1, success.itemCount)
        assertTrue(success.metadata is SyncMetadata.Profile)
        val metadata = success.metadata as SyncMetadata.Profile
        assertFalse(metadata.settingsRestored)
        assertEquals(DisplayCurrency.USD, settingsRepository.getDisplayCurrency())
    }

    // ========================================
    // publishToNostr() cross-app safety tests
    // ========================================

    @Test
    fun `publishToNostr skips on FetchFailed when null repo`() = testScope.runTest {
        settingsRepository.awaitInitialLoad()

        coEvery { nostrService.fetchProfileBackupResult() } returns
                ProfileFetchResult.FetchFailed("Timeout")

        // null vehicleRepository simulates rider app (no vehicles)
        val adapter = ProfileSyncAdapter(
            vehicleRepository = null,
            savedLocationRepository = savedLocationRepository,
            settingsRepository = settingsRepository,
            nostrService = nostrService
        )

        val result = adapter.publishToNostr(mockSigner, mockRelayManager)

        assertNull("Should skip publish on FetchFailed with null repo", result)
        coVerify(exactly = 0) { nostrService.publishProfileBackup(any(), any(), any()) }
    }

    @Test
    fun `publishToNostr publishes on NotFound when null repo`() = testScope.runTest {
        settingsRepository.awaitInitialLoad()

        coEvery { nostrService.fetchProfileBackupResult() } returns ProfileFetchResult.NotFound
        every { savedLocationRepository.savedLocations } returns MutableStateFlow(emptyList())
        coEvery { nostrService.publishProfileBackup(any(), any(), any()) } returns "event-123"

        // null vehicleRepository — first-time backup scenario
        val adapter = ProfileSyncAdapter(
            vehicleRepository = null,
            savedLocationRepository = savedLocationRepository,
            settingsRepository = settingsRepository,
            nostrService = nostrService
        )

        val result = adapter.publishToNostr(mockSigner, mockRelayManager)

        assertTrue("Should publish on NotFound even with null repo", result != null)
        coVerify(exactly = 1) { nostrService.publishProfileBackup(any(), any(), any()) }
    }

    @Test
    fun `publishToNostr preserves existing vehicles when vehicleRepository is null and profile exists`() = testScope.runTest {
        settingsRepository.awaitInitialLoad()
        settingsRepository.toggleDisplayCurrency()

        val remoteVehicle = sampleVehicle("remote-vehicle")
        val localLocation = sampleLocation("local-location", "Home")
        val existingProfile = ProfileBackupData(
            eventId = "existing-rider-profile",
            vehicles = listOf(remoteVehicle),
            savedLocations = listOf(sampleLocation("remote-location", "Old Home")),
            settings = SettingsBackup(),
            updatedAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis()
        )

        val vehicleSlot = slot<List<Vehicle>>()
        val locationSlot = slot<List<SavedLocation>>()
        val settingsSlot = slot<SettingsBackup>()

        coEvery { nostrService.fetchProfileBackupResult() } returns ProfileFetchResult.Found(existingProfile)
        every { savedLocationRepository.savedLocations } returns MutableStateFlow(listOf(localLocation))
        coEvery {
            nostrService.publishProfileBackup(capture(vehicleSlot), capture(locationSlot), capture(settingsSlot))
        } returns "event-found-rider"

        val adapter = ProfileSyncAdapter(
            vehicleRepository = null,
            savedLocationRepository = savedLocationRepository,
            settingsRepository = settingsRepository,
            nostrService = nostrService
        )

        val result = adapter.publishToNostr(mockSigner, mockRelayManager)

        assertEquals("event-found-rider", result)
        assertEquals(listOf(remoteVehicle), vehicleSlot.captured)
        assertEquals(listOf(localLocation), locationSlot.captured)
        assertEquals(DisplayCurrency.SATS, settingsSlot.captured.displayCurrency)
    }

    @Test
    fun `publishToNostr preserves existing locations when savedLocationRepository is null and profile exists`() = testScope.runTest {
        settingsRepository.awaitInitialLoad()
        settingsRepository.toggleDisplayCurrency()

        val localVehicle = sampleVehicle("local-vehicle")
        val remoteLocation = sampleLocation("remote-location", "Office")
        val existingProfile = ProfileBackupData(
            eventId = "existing-driver-profile",
            vehicles = listOf(sampleVehicle("remote-vehicle")),
            savedLocations = listOf(remoteLocation),
            settings = SettingsBackup(),
            updatedAt = System.currentTimeMillis(),
            createdAt = System.currentTimeMillis()
        )

        val vehicleSlot = slot<List<Vehicle>>()
        val locationSlot = slot<List<SavedLocation>>()
        val settingsSlot = slot<SettingsBackup>()

        coEvery { nostrService.fetchProfileBackupResult() } returns ProfileFetchResult.Found(existingProfile)
        every { vehicleRepository.vehicles } returns MutableStateFlow(listOf(localVehicle))
        coEvery {
            nostrService.publishProfileBackup(capture(vehicleSlot), capture(locationSlot), capture(settingsSlot))
        } returns "event-found-driver"

        val adapter = ProfileSyncAdapter(
            vehicleRepository = vehicleRepository,
            savedLocationRepository = null,
            settingsRepository = settingsRepository,
            nostrService = nostrService
        )

        val result = adapter.publishToNostr(mockSigner, mockRelayManager)

        assertEquals("event-found-driver", result)
        assertEquals(listOf(localVehicle), vehicleSlot.captured)
        assertEquals(listOf(remoteLocation), locationSlot.captured)
        assertEquals(DisplayCurrency.SATS, settingsSlot.captured.displayCurrency)
    }

    @Test
    fun `publishToNostr publishes on FetchFailed when both repos present`() = testScope.runTest {
        settingsRepository.awaitInitialLoad()

        coEvery { nostrService.fetchProfileBackupResult() } returns
                ProfileFetchResult.FetchFailed("Timeout")
        every { vehicleRepository.vehicles } returns MutableStateFlow(emptyList())
        every { savedLocationRepository.savedLocations } returns MutableStateFlow(emptyList())
        coEvery { nostrService.publishProfileBackup(any(), any(), any()) } returns "event-456"

        // Both repos present — all data is local, safe to publish even on fetch failure
        val adapter = ProfileSyncAdapter(
            vehicleRepository = vehicleRepository,
            savedLocationRepository = savedLocationRepository,
            settingsRepository = settingsRepository,
            nostrService = nostrService
        )

        val result = adapter.publishToNostr(mockSigner, mockRelayManager)

        assertTrue("Should publish on FetchFailed when both repos present", result != null)
        coVerify(exactly = 1) { nostrService.publishProfileBackup(any(), any(), any()) }
    }
}
