package com.ridestr.rider

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import com.ridestr.rider.ui.screens.DebugScreen
import com.ridestr.rider.ui.screens.RiderModeScreen
import com.ridestr.rider.ui.screens.HistoryScreen
import com.ridestr.rider.ui.screens.KeyBackupScreen
import com.ridestr.rider.ui.screens.OnboardingScreen
import com.ridestr.rider.ui.screens.ProfileSetupScreen
import com.ridestr.rider.ui.screens.SettingsContent
import com.ridestr.rider.ui.screens.WalletScreen
import com.ridestr.common.routing.NostrTileDiscoveryService
import com.ridestr.common.routing.TileDownloadService
import com.ridestr.common.routing.TileManager
import com.ridestr.common.routing.ValhallaRoutingService
import com.ridestr.common.settings.SettingsManager
import com.ridestr.common.ui.AccountBottomSheet
import com.ridestr.common.ui.DeveloperOptionsScreen
import com.ridestr.common.ui.RelaySignalIndicator
import com.ridestr.common.ui.LocationPermissionScreen
import com.ridestr.common.ui.TileManagementScreen
import com.ridestr.common.ui.TileSetupScreen
import com.ridestr.rider.viewmodels.RiderViewModel
import com.ridestr.rider.viewmodels.OnboardingViewModel
import com.ridestr.rider.viewmodels.ProfileViewModel
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.events.UserProfile
import com.ridestr.common.nostr.relay.RelayConnectionState
import com.ridestr.common.notification.NotificationHelper
import com.ridestr.common.ui.theme.RidestrTheme
import kotlinx.coroutines.tasks.await

/**
 * Bottom navigation tabs for the main screen.
 */
enum class Tab {
    RIDE,       // Main rider mode
    WALLET,     // Payment info
    HISTORY,    // Ride history
    SETTINGS    // Settings & developer options
}

/**
 * App navigation screens (for modals and onboarding).
 */
enum class Screen {
    ONBOARDING,
    PROFILE_SETUP,
    LOCATION_PERMISSION,
    TILE_SETUP,
    MAIN,           // Shows bottom navigation with tabs
    DEBUG,
    BACKUP_KEYS,
    TILES,
    DEV_OPTIONS
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RidestrTheme {
                RidestrApp()
            }
        }
    }
}

@Composable
fun RidestrApp() {
    val context = LocalContext.current
    val onboardingViewModel: OnboardingViewModel = viewModel()
    val uiState by onboardingViewModel.uiState.collectAsState()

    // Initialize notification channels once on startup
    LaunchedEffect(Unit) {
        NotificationHelper.createRiderChannels(context)
    }

    // Settings manager (created first to get custom relays)
    val settingsManager = remember { SettingsManager(context) }

    // NostrService for relay connections (uses custom relays from settings)
    val nostrService = remember { NostrService(context, settingsManager.getEffectiveRelays()) }

    // Tile management (singleton to share with ViewModels)
    val tileManager = remember { TileManager.getInstance(context) }
    val tileDownloadService = remember { TileDownloadService(context, tileManager) }

    // Tile discovery from Nostr (kind 1063 events from official pubkey)
    val tileDiscoveryService = remember { NostrTileDiscoveryService(nostrService.relayManager) }

    // Sync discovered regions to TileManager for routing
    val discoveredRegions by tileDiscoveryService.discoveredRegions.collectAsState()
    LaunchedEffect(discoveredRegions) {
        if (discoveredRegions.isNotEmpty()) {
            tileManager.updateDiscoveredRegions(discoveredRegions)
        }
    }

    // Routing service
    val routingService = remember { ValhallaRoutingService(context) }

    // Current location for tile recommendations
    var currentLocation by remember { mutableStateOf<Location?>(null) }

    // Relay state
    val connectionStates by nostrService.connectionStates.collectAsState()
    val recentEvents by nostrService.relayManager.events.collectAsState()
    val notices by nostrService.relayManager.notices.collectAsState()

    // User profile (for lightning address in wallet)
    var userProfile by remember { mutableStateOf<UserProfile?>(null) }

    // Check if location permission already granted
    val hasLocationPermission = remember {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    // Check if onboarding was completed (has key + profile + did tile setup)
    val onboardingCompleted = remember { settingsManager.isOnboardingCompleted() }

    // Check if tiles are already downloaded (skip tile setup if so)
    val hasTilesDownloaded by tileManager.downloadedRegions.collectAsState()

    // Navigation state - start at MAIN if onboarding is complete to avoid flash
    var currentScreen by remember {
        val initialScreen = if (onboardingCompleted && uiState.isLoggedIn) {
            Screen.MAIN
        } else {
            Screen.ONBOARDING
        }
        mutableStateOf(initialScreen)
    }

    // Connect to Nostr if starting at MAIN screen
    LaunchedEffect(Unit) {
        if (currentScreen == Screen.MAIN) {
            nostrService.connect()
        }
    }

    // Subscribe to own profile for lightning address
    LaunchedEffect(uiState.isLoggedIn) {
        if (uiState.isLoggedIn) {
            nostrService.subscribeToOwnProfile { profile ->
                userProfile = profile
            }
        }
    }

    // Check if already logged in on first composition (only if not already at MAIN)
    LaunchedEffect(uiState.isLoggedIn, uiState.isProfileCompleted) {
        if (uiState.isLoggedIn && currentScreen == Screen.ONBOARDING) {
            nostrService.connect()

            // Determine next screen based on completion status
            currentScreen = when {
                // If onboarding fully completed, go to main
                onboardingCompleted -> Screen.MAIN
                // If profile not complete, go to profile setup
                !uiState.isProfileCompleted -> Screen.PROFILE_SETUP
                // If no location permission, request it
                !hasLocationPermission -> Screen.LOCATION_PERMISSION
                // Skip tile setup if already have tiles downloaded
                hasTilesDownloaded.isNotEmpty() -> {
                    settingsManager.setOnboardingCompleted(true)
                    Screen.MAIN
                }
                // Otherwise, show tile setup
                else -> Screen.TILE_SETUP
            }
        }
    }

    Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
        when (currentScreen) {
            Screen.ONBOARDING -> {
                OnboardingScreen(
                    viewModel = onboardingViewModel,
                    onComplete = {
                        nostrService.connect()
                        // After login, go to profile setup or location permission
                        currentScreen = if (uiState.isProfileCompleted) {
                            Screen.LOCATION_PERMISSION
                        } else {
                            Screen.PROFILE_SETUP
                        }
                    },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.PROFILE_SETUP -> {
                val profileViewModel: ProfileViewModel = viewModel()
                ProfileSetupScreen(
                    viewModel = profileViewModel,
                    onComplete = {
                        // Start tile discovery early (before location permission)
                        tileDiscoveryService.startDiscovery()
                        // After profile, go to location permission
                        currentScreen = Screen.LOCATION_PERMISSION
                    },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.LOCATION_PERMISSION -> {
                // Start discovery if not already started (for returning users who skip profile)
                LaunchedEffect(Unit) {
                    if (!tileDiscoveryService.isDiscovering.value) {
                        tileDiscoveryService.startDiscovery()
                    }
                }

                LocationPermissionScreen(
                    isDriverApp = false,
                    onPermissionGranted = {
                        // Get current location for tile recommendations
                        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
                        try {
                            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                                currentLocation = location
                            }
                        } catch (e: SecurityException) {
                            // Permission should be granted at this point
                        }
                        // Skip tile setup if tiles already downloaded
                        if (hasTilesDownloaded.isNotEmpty()) {
                            settingsManager.setOnboardingCompleted(true)
                            currentScreen = Screen.MAIN
                        } else {
                            currentScreen = Screen.TILE_SETUP
                        }
                    },
                    onSkip = {
                        // Skip tile setup if tiles already downloaded
                        if (hasTilesDownloaded.isNotEmpty()) {
                            settingsManager.setOnboardingCompleted(true)
                            currentScreen = Screen.MAIN
                        } else {
                            currentScreen = Screen.TILE_SETUP
                        }
                    },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.TILE_SETUP -> {
                TileSetupScreen(
                    tileManager = tileManager,
                    downloadService = tileDownloadService,
                    discoveryService = tileDiscoveryService,
                    currentLocation = currentLocation,
                    onComplete = {
                        // Mark onboarding as completed
                        settingsManager.setOnboardingCompleted(true)
                        currentScreen = Screen.MAIN
                    },
                    onSkip = {
                        // Allow skipping tile setup
                        settingsManager.setOnboardingCompleted(true)
                        currentScreen = Screen.MAIN
                    },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.MAIN -> {
                // Ensure tile discovery is started for returning users who skip onboarding
                LaunchedEffect(Unit) {
                    if (!tileDiscoveryService.isDiscovering.value) {
                        tileDiscoveryService.startDiscovery()
                    }
                }

                MainScreen(
                    keyManager = onboardingViewModel.getKeyManager(),
                    connectionStates = connectionStates,
                    settingsManager = settingsManager,
                    userProfile = userProfile,
                    onLogout = {
                        nostrService.disconnect()
                        onboardingViewModel.logout()
                        settingsManager.resetOnboarding()
                        currentScreen = Screen.ONBOARDING
                    },
                    onOpenProfile = {
                        currentScreen = Screen.PROFILE_SETUP
                    },
                    onOpenBackup = {
                        currentScreen = Screen.BACKUP_KEYS
                    },
                    onOpenTiles = {
                        currentScreen = Screen.TILES
                    },
                    onOpenDevOptions = {
                        currentScreen = Screen.DEV_OPTIONS
                    },
                    onOpenDebug = {
                        currentScreen = Screen.DEBUG
                    },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.DEBUG -> {
                val useGeocodingSearch by settingsManager.useGeocodingSearch.collectAsState()

                DebugScreen(
                    npub = onboardingViewModel.getKeyManager().getNpub(),
                    pubKeyHex = onboardingViewModel.getKeyManager().getPubKeyHex(),
                    connectionStates = connectionStates,
                    recentEvents = recentEvents,
                    notices = notices,
                    useGeocodingSearch = useGeocodingSearch,
                    routingService = routingService,
                    onToggleGeocodingSearch = { settingsManager.toggleUseGeocodingSearch() },
                    onConnect = { nostrService.connect() },
                    onDisconnect = { nostrService.disconnect() },
                    onBack = { currentScreen = Screen.MAIN },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.BACKUP_KEYS -> {
                KeyBackupScreen(
                    npub = onboardingViewModel.getKeyManager().getNpub(),
                    nsec = onboardingViewModel.getNsecForBackup(),
                    onBack = { currentScreen = Screen.MAIN },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.TILES -> {
                TileManagementScreen(
                    tileManager = tileManager,
                    downloadService = tileDownloadService,
                    discoveryService = tileDiscoveryService,
                    onBack = { currentScreen = Screen.MAIN },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.DEV_OPTIONS -> {
                DeveloperOptionsScreen(
                    settingsManager = settingsManager,
                    isDriverApp = false,
                    onOpenDebug = { currentScreen = Screen.DEBUG },
                    onBack = { currentScreen = Screen.MAIN },
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    keyManager: com.ridestr.common.nostr.keys.KeyManager,
    connectionStates: Map<String, RelayConnectionState>,
    settingsManager: SettingsManager,
    userProfile: UserProfile?,
    onLogout: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenTiles: () -> Unit,
    onOpenDevOptions: () -> Unit,
    onOpenDebug: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connectedCount = connectionStates.values.count { it == RelayConnectionState.CONNECTED }
    val totalRelays = connectionStates.size
    val isConnected = connectedCount > 0

    // Current tab state
    var currentTab by remember { mutableStateOf(Tab.RIDE) }

    // Account bottom sheet state
    var showAccountSheet by remember { mutableStateOf(false) }

    // Rider ViewModel (persists across tab switches)
    val riderViewModel: RiderViewModel = viewModel()

    // Ensure relay connections when app returns to foreground
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        riderViewModel.onResume()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            // Compact header with dynamic title, signal bars, and account button
            Surface(
                modifier = Modifier.fillMaxWidth(),
                tonalElevation = 1.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Dynamic title based on current tab
                    Text(
                        text = when (currentTab) {
                            Tab.RIDE -> "Rider Mode"
                            Tab.WALLET -> "Wallet"
                            Tab.HISTORY -> "History"
                            Tab.SETTINGS -> "Settings"
                        },
                        style = MaterialTheme.typography.titleLarge
                    )

                    // Right side: Signal indicator + Account button
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RelaySignalIndicator(
                            connectedCount = connectedCount,
                            totalRelays = totalRelays
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
                NavigationBarItem(
                    icon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                    label = { Text("Ride") },
                    selected = currentTab == Tab.RIDE,
                    onClick = { currentTab = Tab.RIDE }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = null) },
                    label = { Text("Wallet") },
                    selected = currentTab == Tab.WALLET,
                    onClick = { currentTab = Tab.WALLET }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text("History") },
                    selected = currentTab == Tab.HISTORY,
                    onClick = { currentTab = Tab.HISTORY }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                    selected = currentTab == Tab.SETTINGS,
                    onClick = { currentTab = Tab.SETTINGS }
                )
            }
        }
    ) { innerPadding ->
        // Tab content
        when (currentTab) {
            Tab.RIDE -> {
                RiderModeScreen(
                    viewModel = riderViewModel,
                    settingsManager = settingsManager,
                    onOpenTiles = onOpenTiles,
                    modifier = Modifier.padding(innerPadding)
                )
            }
            Tab.WALLET -> {
                WalletScreen(
                    lightningAddress = userProfile?.lud16,
                    onEditLightningAddress = onOpenProfile,
                    modifier = Modifier.padding(innerPadding)
                )
            }
            Tab.HISTORY -> {
                HistoryScreen(
                    modifier = Modifier.padding(innerPadding)
                )
            }
            Tab.SETTINGS -> {
                SettingsContent(
                    settingsManager = settingsManager,
                    onOpenTiles = onOpenTiles,
                    onOpenDevOptions = onOpenDevOptions,
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }

    // Account bottom sheet
    if (showAccountSheet) {
        AccountBottomSheet(
            npub = keyManager.getNpub(),
            relayStatus = "$connectedCount/$totalRelays relays",
            isConnected = isConnected,
            onEditProfile = onOpenProfile,
            onBackupKeys = onOpenBackup,
            onLogout = onLogout,
            onDismiss = { showAccountSheet = false }
        )
    }
}
