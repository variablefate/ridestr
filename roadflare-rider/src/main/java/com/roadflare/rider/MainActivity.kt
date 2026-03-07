package com.roadflare.rider

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ridestr.common.data.FollowedDriversRepository
import com.ridestr.common.data.RideHistoryRepository
import com.ridestr.common.data.SavedLocationRepository
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.RoadflareKeyShareEvent
import com.ridestr.common.nostr.relay.RelayConnectionState
import com.ridestr.common.settings.SettingsManager
import com.ridestr.common.sync.FollowedDriversSyncAdapter
import com.ridestr.common.sync.ProfileSyncAdapter
import com.ridestr.common.sync.ProfileSyncManager
import com.ridestr.common.sync.RideHistorySyncAdapter
import com.ridestr.common.ui.AccountBottomSheet
import com.ridestr.common.ui.AccountSafetyScreen
import com.ridestr.common.ui.DeveloperOptionsScreen
import com.ridestr.common.ui.ProfileSyncScreen
import com.ridestr.common.ui.RelayManagementScreen
import com.ridestr.common.ui.RelaySignalIndicator
import com.ridestr.common.ui.TileManagementScreen
import com.ridestr.common.ui.TileSetupScreen
import com.ridestr.common.ui.screens.KeyBackupScreen
import com.roadflare.rider.ui.theme.RoadFlareTheme
import com.roadflare.rider.ui.screens.AddDriverScreen
import com.roadflare.rider.ui.screens.DriverNetworkTab
import com.roadflare.rider.ui.screens.HistoryScreen
import com.roadflare.rider.ui.screens.OnboardingScreen
import com.roadflare.rider.ui.screens.ProfileSetupScreen
import com.roadflare.rider.ui.screens.RoadFlareTab
import com.roadflare.rider.ui.screens.SettingsContent
import com.roadflare.rider.viewmodels.AppStateViewModel
import com.roadflare.rider.viewmodels.OnboardingViewModel
import com.roadflare.rider.viewmodels.ProfileViewModel
import com.roadflare.rider.viewmodels.RiderViewModel
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RoadFlareTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RoadFlareApp()
                }
            }
        }
    }
}

private enum class AppScreen {
    LOADING,
    ONBOARDING,
    PROFILE_SYNC,
    PROFILE_SETUP,
    TILE_SETUP,
    MAIN
}

@Composable
fun RoadFlareApp() {
    val onboardingViewModel: OnboardingViewModel = viewModel()
    val onboardingState by onboardingViewModel.uiState.collectAsState()
    val appStateViewModel: AppStateViewModel = viewModel()
    val tilesSetupCompleted by appStateViewModel.tilesSetupCompleted.collectAsState()
    val hasAnyTileLoaded by appStateViewModel.hasAnyTileLoaded.collectAsState()

    val context = LocalContext.current
    val appContext = context.applicationContext

    // Shared service references
    val nostrService = remember { NostrService.getInstance(appContext) }
    val settingsManager = remember { SettingsManager.getInstance(appContext) }
    val followedDriversRepo = remember { FollowedDriversRepository.getInstance(appContext) }
    val savedLocationRepo = remember { SavedLocationRepository.getInstance(appContext) }
    val rideHistoryRepo = remember { RideHistoryRepository.getInstance(appContext) }

    val profileSyncManager = remember {
        ProfileSyncManager.getInstance(appContext, settingsManager.getEffectiveRelays()).also { psm ->
            psm.registerSyncable(ProfileSyncAdapter(
                vehicleRepository = null,
                savedLocationRepository = savedLocationRepo,
                settingsManager = settingsManager,
                nostrService = nostrService
            ))
            psm.registerSyncable(RideHistorySyncAdapter(rideHistoryRepo, nostrService))
            psm.registerSyncable(FollowedDriversSyncAdapter(followedDriversRepo, nostrService))
        }
    }

    // Reactive Nostr connect + key refresh when logged in
    LaunchedEffect(onboardingState.isLoggedIn) {
        if (onboardingState.isLoggedIn) {
            nostrService.keyManager.refreshFromStorage()
            profileSyncManager.keyManager.refreshFromStorage()
            nostrService.connect()
            nostrService.fetchAndCacheUserDisplayName()
        }
    }

    // Subscribe to RoadFlare key share events (Kind 3186)
    LaunchedEffect(onboardingState.isLoggedIn) {
        if (!onboardingState.isLoggedIn) return@LaunchedEffect

        nostrService.keyManager.refreshFromStorage()
        profileSyncManager.keyManager.refreshFromStorage()
        nostrService.ensureConnected()
        nostrService.relayManager.awaitConnected(tag = "keyShareSub")

        val asyncScope = this

        val subId = nostrService.subscribeToRoadflareKeyShares { event, relayUrl ->
            asyncScope.launch {
                try {
                    val signer = nostrService.keyManager.getSigner() ?: return@launch

                    val data = RoadflareKeyShareEvent.parseAndDecrypt(signer, event) ?: return@launch

                    if (data.driverPubKey != event.pubKey) {
                        android.util.Log.w("RoadFlareApp", "Kind 3186 driverPubKey mismatch: payload=${data.driverPubKey.take(8)} != event=${event.pubKey.take(8)}")
                        return@launch
                    }

                    val existingDriver = followedDriversRepo.drivers.value.find { it.pubkey == data.driverPubKey }
                    if (existingDriver == null) {
                        android.util.Log.d("RoadFlareApp", "Kind 3186 from unknown driver ${data.driverPubKey.take(8)}, ignoring")
                        return@launch
                    }

                    val updatedKey = data.roadflareKey.copy(keyUpdatedAt = data.keyUpdatedAt)
                    followedDriversRepo.updateDriverKey(data.driverPubKey, updatedKey)

                    nostrService.publishRoadflareKeyAck(
                        driverPubKey = data.driverPubKey,
                        keyVersion = data.roadflareKey.version,
                        keyUpdatedAt = data.keyUpdatedAt
                    )
                    profileSyncManager.backupFollowedDrivers()
                } catch (e: Exception) {
                    android.util.Log.e("RoadFlareApp", "Kind 3186 processing error", e)
                }
            }
        }

        try { awaitCancellation() } finally { subId?.let { nostrService.closeSubscription(it) } }
    }

    // Auto-backup saved locations when they change (debounced 2s)
    val savedLocations by savedLocationRepo.savedLocations.collectAsState()
    var lastLocationBackupHash by remember { mutableStateOf(emptyList<Any>().hashCode()) }
    LaunchedEffect(savedLocations, onboardingState.isLoggedIn) {
        if (!onboardingState.isLoggedIn) return@LaunchedEffect
        val currentHash = savedLocations.hashCode()
        if (currentHash == lastLocationBackupHash) return@LaunchedEffect
        kotlinx.coroutines.delay(2000)
        if (savedLocations.hashCode() == currentHash) {
            lastLocationBackupHash = currentHash
            profileSyncManager.backupProfileData(force = true)
        }
    }

    // Auto-backup settings when they change (debounced 2s)
    val settingsHash by settingsManager.syncableSettingsHash.collectAsState(initial = 0)
    var lastSettingsBackupHash by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(settingsHash, onboardingState.isLoggedIn) {
        if (!onboardingState.isLoggedIn || settingsHash == 0) return@LaunchedEffect
        if (lastSettingsBackupHash == null) {
            lastSettingsBackupHash = settingsHash
            return@LaunchedEffect
        }
        if (settingsHash == lastSettingsBackupHash) return@LaunchedEffect
        kotlinx.coroutines.delay(2000)
        lastSettingsBackupHash = settingsHash
        profileSyncManager.backupProfileData(force = true)
    }

    // Relay sync — keep ProfileSyncManager and NostrService relay URLs current
    val customRelays by settingsManager.customRelays.collectAsState()
    LaunchedEffect(customRelays, onboardingState.isLoggedIn) {
        if (!onboardingState.isLoggedIn) return@LaunchedEffect
        val effectiveRelays = settingsManager.getEffectiveRelays()
        profileSyncManager.updateRelays(effectiveRelays)
        val currentNostr = nostrService.relayManager.getRelayUrls().toSet()
        val target = effectiveRelays.toSet()
        (currentNostr - target).forEach { nostrService.relayManager.removeRelay(it) }
        (target - currentNostr).forEach { nostrService.relayManager.addRelay(it) }
        if (currentNostr != target) nostrService.relayManager.ensureConnected()
    }

    // FULLY DERIVED — no mutableStateOf, no imperative assignments
    val currentScreen = when {
        onboardingState.isLoading || tilesSetupCompleted == null || hasAnyTileLoaded == null -> AppScreen.LOADING
        !onboardingState.isLoggedIn || onboardingState.showBackupReminder -> AppScreen.ONBOARDING
        onboardingState.needsProfileSync -> AppScreen.PROFILE_SYNC
        !onboardingState.isProfileCompleted -> AppScreen.PROFILE_SETUP
        tilesSetupCompleted == false && hasAnyTileLoaded == false -> AppScreen.TILE_SETUP
        else -> AppScreen.MAIN
    }

    when (currentScreen) {
        AppScreen.LOADING -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        AppScreen.ONBOARDING -> {
            OnboardingScreen(
                viewModel = onboardingViewModel,
                onComplete = { /* Derivation handles routing via LaunchedEffect */ }
            )
        }
        AppScreen.PROFILE_SYNC -> {
            val syncState by profileSyncManager.syncState.collectAsState()

            LaunchedEffect(Unit) {
                nostrService.keyManager.refreshFromStorage()
                profileSyncManager.keyManager.refreshFromStorage()
                profileSyncManager.checkAndSyncRidestrData()
            }

            ProfileSyncScreen(
                syncState = syncState,
                isDriverApp = false,
                onComplete = { _ ->
                    profileSyncManager.resetSyncState()
                    onboardingViewModel.markProfileSyncCompleted()
                },
                onSkip = {
                    profileSyncManager.resetSyncState()
                    onboardingViewModel.markProfileSyncCompleted()
                }
            )
        }
        AppScreen.PROFILE_SETUP -> {
            val profileViewModel: ProfileViewModel = viewModel()
            ProfileSetupScreen(
                viewModel = profileViewModel,
                onComplete = { onboardingViewModel.refreshState() }
            )
        }
        AppScreen.TILE_SETUP -> {
            TileSetupScreen(
                tileManager = appStateViewModel.tileManager,
                downloadService = appStateViewModel.tileDownloadService,
                discoveryService = appStateViewModel.nostrTileDiscoveryService,
                currentLocation = null,
                onComplete = { appStateViewModel.markTilesSetupCompleted() },
                onSkip = { appStateViewModel.markTilesSetupCompleted() }
            )
        }
        AppScreen.MAIN -> {
            MainTabScreen()
        }
    }
}

private sealed class SecondaryScreen {
    data object None : SecondaryScreen()
    data object AddDriver : SecondaryScreen()
    data object RelayManagement : SecondaryScreen()
    data object DeveloperOptions : SecondaryScreen()
    data object TileManagement : SecondaryScreen()
    data object ProfileEdit : SecondaryScreen()
    data object KeyBackup : SecondaryScreen()
    data object AccountSafety : SecondaryScreen()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTabScreen() {
    val viewModel: RiderViewModel = viewModel()
    val onboardingViewModel: OnboardingViewModel = viewModel()
    val onboardingState by onboardingViewModel.uiState.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    var secondaryScreen by remember { mutableStateOf<SecondaryScreen>(SecondaryScreen.None) }
    var showAccountSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val riderName by viewModel.nostrService.userDisplayName.collectAsState()

    val context = LocalContext.current
    val profileSyncManager = remember { ProfileSyncManager.getInstance(context.applicationContext) }

    val tabs = listOf(
        TabItem("Driver Network", Icons.Default.People),
        TabItem("RoadFlare", Icons.Default.Flare),
        TabItem("History", Icons.Default.History),
        TabItem("Settings", Icons.Default.Settings)
    )

    // Handle secondary screens
    when (val screen = secondaryScreen) {
        is SecondaryScreen.AddDriver -> {
            AddDriverScreen(
                followedDriversRepository = viewModel.followedDriversRepository,
                onNavigateBack = { secondaryScreen = SecondaryScreen.None },
                onDriverAdded = { driver ->
                    scope.launch { profileSyncManager.backupFollowedDrivers() }
                },
                onSendFollowNotification = { driverPubKey ->
                    viewModel.nostrService.publishRoadflareFollowNotify(
                        driverPubKey = driverPubKey,
                        riderName = riderName.ifEmpty { "Rider" },
                        action = "follow"
                    )
                }
            )
            return
        }
        is SecondaryScreen.RelayManagement -> {
            val connectionStates by viewModel.nostrService.relayManager.connectionStates.collectAsState()
            RelayManagementScreen(
                settingsManager = viewModel.settingsManager,
                connectedCount = connectionStates.count { it.value == RelayConnectionState.CONNECTED },
                totalRelays = connectionStates.size,
                connectionStates = connectionStates,
                onBack = { secondaryScreen = SecondaryScreen.None },
                onReconnect = { viewModel.nostrService.relayManager.ensureConnected() }
            )
            return
        }
        is SecondaryScreen.DeveloperOptions -> {
            DeveloperOptionsScreen(
                settingsManager = viewModel.settingsManager,
                isDriverApp = false,
                nostrService = viewModel.nostrService,
                onOpenRelaySettings = { secondaryScreen = SecondaryScreen.RelayManagement },
                onBack = { secondaryScreen = SecondaryScreen.None }
            )
            return
        }
        is SecondaryScreen.TileManagement -> {
            TileManagementScreen(
                tileManager = viewModel.tileManager,
                downloadService = viewModel.tileDownloadService,
                discoveryService = viewModel.nostrTileDiscoveryService,
                onBack = { secondaryScreen = SecondaryScreen.None }
            )
            return
        }
        is SecondaryScreen.ProfileEdit -> {
            val profileViewModel: ProfileViewModel = viewModel()
            ProfileSetupScreen(
                viewModel = profileViewModel,
                onComplete = { secondaryScreen = SecondaryScreen.None },
                isEditMode = true,
                canSkip = false,
                onBack = { secondaryScreen = SecondaryScreen.None }
            )
            return
        }
        is SecondaryScreen.KeyBackup -> {
            KeyBackupScreen(
                npub = onboardingState.npub,
                nsec = onboardingViewModel.getNsecForBackup(),
                onBack = { secondaryScreen = SecondaryScreen.None }
            )
            return
        }
        is SecondaryScreen.AccountSafety -> {
            AccountSafetyScreen(
                nostrService = viewModel.nostrService,
                onBack = { secondaryScreen = SecondaryScreen.None }
            )
            return
        }
        SecondaryScreen.None -> { /* fall through to main tab content */ }
    }

    Scaffold(
        topBar = {
            val connectionStates by viewModel.nostrService.relayManager.connectionStates.collectAsState()
            Surface(
                modifier = Modifier.fillMaxWidth().statusBarsPadding(),
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = tabs[selectedTab].title,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RelaySignalIndicator(
                            connectedCount = connectionStates.count { it.value == RelayConnectionState.CONNECTED },
                            totalRelays = connectionStates.size,
                            onClick = { secondaryScreen = SecondaryScreen.RelayManagement }
                        )
                        IconButton(onClick = { showAccountSheet = true }) {
                            Icon(Icons.Default.Person, contentDescription = "Account")
                        }
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) },
                        selected = selectedTab == index,
                        onClick = { selectedTab = index }
                    )
                }
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            0 -> DriverNetworkTab(
                followedDriversRepository = viewModel.followedDriversRepository,
                nostrService = viewModel.nostrService,
                settingsManager = viewModel.settingsManager,
                onAddDriver = { secondaryScreen = SecondaryScreen.AddDriver },
                onDriverRemoved = {
                    scope.launch { profileSyncManager.backupFollowedDrivers() }
                },
                modifier = Modifier.padding(innerPadding)
            )
            1 -> RoadFlareTab(
                viewModel = viewModel,
                modifier = Modifier.padding(innerPadding)
            )
            2 -> HistoryScreen(
                rideHistoryRepository = viewModel.rideHistoryRepository,
                settingsManager = viewModel.settingsManager,
                modifier = Modifier.padding(innerPadding)
            )
            3 -> SettingsContent(
                settingsManager = viewModel.settingsManager,
                onOpenTiles = { secondaryScreen = SecondaryScreen.TileManagement },
                onOpenDevOptions = { secondaryScreen = SecondaryScreen.DeveloperOptions },
                onSyncProfile = {
                    profileSyncManager.checkAndSyncRidestrData()
                },
                modifier = Modifier.padding(innerPadding)
            )
        }
    }

    // Account bottom sheet
    if (showAccountSheet) {
        val sheetConnectionStates by viewModel.nostrService.relayManager.connectionStates.collectAsState()
        val connectedCount = sheetConnectionStates.count { it.value == RelayConnectionState.CONNECTED }
        AccountBottomSheet(
            npub = onboardingState.npub,
            relayStatus = "$connectedCount/${sheetConnectionStates.size} relays",
            isConnected = connectedCount > 0,
            onEditProfile = { showAccountSheet = false; secondaryScreen = SecondaryScreen.ProfileEdit },
            onBackupKeys = { showAccountSheet = false; secondaryScreen = SecondaryScreen.KeyBackup },
            onAccountSafety = { showAccountSheet = false; secondaryScreen = SecondaryScreen.AccountSafety },
            onRelaySettings = { showAccountSheet = false; secondaryScreen = SecondaryScreen.RelayManagement },
            onLogout = {
                showAccountSheet = false
                scope.launch {
                    viewModel.performLogout()
                    onboardingViewModel.logout()
                }
            },
            onDismiss = { showAccountSheet = false }
        )
    }
}

private data class TabItem(
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)
