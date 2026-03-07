package com.roadflare.rider

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Flare
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ridestr.common.nostr.relay.RelayConnectionState
import com.ridestr.common.ui.AccountBottomSheet
import com.ridestr.common.ui.AccountSafetyScreen
import com.ridestr.common.ui.DeveloperOptionsScreen
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

    // FULLY DERIVED — no mutableStateOf, no LaunchedEffect, no imperative assignments
    val currentScreen = when {
        onboardingState.isLoading || tilesSetupCompleted == null || hasAnyTileLoaded == null -> AppScreen.LOADING
        !onboardingState.isLoggedIn -> AppScreen.ONBOARDING
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
                onComplete = { onboardingViewModel.refreshState() }
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
            TopAppBar(
                title = { Text(tabs[selectedTab].title) },
                actions = {
                    IconButton(onClick = { showAccountSheet = true }) {
                        Icon(
                            Icons.Default.AccountCircle,
                            contentDescription = "Account"
                        )
                    }
                    RelaySignalIndicator(
                        connectedCount = connectionStates.count { it.value == RelayConnectionState.CONNECTED },
                        totalRelays = connectionStates.size,
                        onClick = { secondaryScreen = SecondaryScreen.RelayManagement }
                    )
                }
            )
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
