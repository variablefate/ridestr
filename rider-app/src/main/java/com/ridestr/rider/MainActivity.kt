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
import com.ridestr.rider.ui.screens.TipScreen
import com.ridestr.common.ui.RideDetailScreen
import com.ridestr.common.nostr.events.RideHistoryEntry
import com.ridestr.common.routing.NostrTileDiscoveryService
import com.ridestr.common.routing.TileDownloadService
import com.ridestr.common.routing.TileManager
import com.ridestr.common.routing.ValhallaRoutingService
import com.ridestr.common.settings.SettingsManager
import com.ridestr.common.ui.AccountBottomSheet
import com.ridestr.common.ui.AccountSafetyScreen
import com.ridestr.common.ui.DeveloperOptionsScreen
import com.ridestr.common.ui.RelayManagementScreen
import com.ridestr.common.ui.WalletSettingsScreen
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
import com.ridestr.common.data.RideHistoryRepository
import com.ridestr.common.notification.NotificationHelper
import com.ridestr.common.ui.theme.RidestrTheme
import com.ridestr.common.ui.WalletDetailScreen
import com.ridestr.common.ui.WalletSetupScreen
import com.ridestr.common.payment.WalletKeyManager
import com.ridestr.common.payment.WalletService
import com.ridestr.common.payment.cashu.Nip60WalletSync
import com.ridestr.common.sync.ProfileSyncManager
import com.ridestr.common.sync.ProfileSyncState
import com.ridestr.common.sync.Nip60WalletSyncAdapter
import com.ridestr.common.sync.RideHistorySyncAdapter
import com.ridestr.common.sync.SavedLocationSyncAdapter
import com.ridestr.common.data.SavedLocationRepository
import com.ridestr.rider.service.RiderActiveService
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import com.ridestr.common.bitcoin.BitcoinPriceService

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
    WALLET_SETUP,       // Wallet onboarding (after profile, before location)
    LOCATION_PERMISSION,
    TILE_SETUP,
    MAIN,           // Shows bottom navigation with tabs
    WALLET_DETAIL,  // Full wallet interface (deposit, withdraw, etc.)
    WALLET_SETTINGS,// Wallet management settings
    RIDE_DETAIL,    // Detail view for single ride
    TIP,            // Tip driver screen
    DEBUG,
    BACKUP_KEYS,
    TILES,
    DEV_OPTIONS,
    ACCOUNT_SAFETY,
    RELAY_SETTINGS
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
    // Cached tiles are loaded immediately; discovery runs on key import or pull-to-refresh
    val tileDiscoveryService = remember { NostrTileDiscoveryService(context, nostrService.relayManager) }

    // Sync discovered regions to TileManager for routing
    val discoveredRegions by tileDiscoveryService.discoveredRegions.collectAsState()
    LaunchedEffect(discoveredRegions) {
        if (discoveredRegions.isNotEmpty()) {
            tileManager.updateDiscoveredRegions(discoveredRegions)
        }
    }

    // Routing service
    val routingService = remember { ValhallaRoutingService(context) }

    // Wallet services for payment rails
    val walletKeyManager = remember { WalletKeyManager(context) }
    val walletService = remember { WalletService(context, walletKeyManager) }

    // Bitcoin price service for USD display
    val bitcoinPriceService = remember { BitcoinPriceService() }

    // NIP-60 wallet sync for cross-device portability
    // IMPORTANT: Wire to walletService immediately to avoid race condition with lockForRide()
    val nip60Sync = remember {
        Nip60WalletSync(
            relayManager = nostrService.relayManager,
            keyManager = nostrService.keyManager,
            walletKeyManager = walletKeyManager
        ).also { sync ->
            walletService.setNip60Sync(sync)
        }
    }

    // ProfileSyncManager for coordinated profile data sync
    val profileSyncManager = remember {
        ProfileSyncManager.getInstance(context, settingsManager.getEffectiveRelays())
    }

    // Ride history repository (for sync adapter)
    val rideHistoryRepo = remember { RideHistoryRepository.getInstance(context) }
    val savedLocationRepo = remember { SavedLocationRepository.getInstance(context) }

    // Register sync adapters
    LaunchedEffect(Unit) {
        profileSyncManager.registerSyncable(Nip60WalletSyncAdapter(nip60Sync))
        profileSyncManager.registerSyncable(RideHistorySyncAdapter(rideHistoryRepo, nostrService))
        profileSyncManager.registerSyncable(SavedLocationSyncAdapter(savedLocationRepo, nostrService))
    }

    // Observable sync state for potential UI feedback
    val syncState by profileSyncManager.syncState.collectAsState()

    // Start Bitcoin price auto-refresh for USD display
    LaunchedEffect(Unit) {
        bitcoinPriceService.startAutoRefresh()
    }

    // Auto-connect wallet to saved mint
    LaunchedEffect(Unit) {
        walletService.autoConnect()
    }

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

    // Selected ride for detail screen
    var selectedRide by remember { mutableStateOf<RideHistoryEntry?>(null) }

    // Lightning address for tip screen
    var tipLightningAddress by remember { mutableStateOf<String?>(null) }

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

    // Sync all profile data from Nostr on login (for fresh installs importing existing key)
    // Uses ProfileSyncManager for coordinated, ordered sync of wallet, history, and locations
    LaunchedEffect(uiState.isLoggedIn) {
        if (uiState.isLoggedIn) {
            // Only sync if local data is empty (fresh install with imported key)
            val needsSync = !rideHistoryRepo.hasRides() && !savedLocationRepo.hasLocations()
            if (needsSync) {
                // CRITICAL: Refresh KeyManagers from storage
                // OnboardingViewModel has a separate KeyManager instance that imported the keys,
                // so other KeyManagers need to reload from SharedPreferences
                nostrService.keyManager.refreshFromStorage()
                profileSyncManager.keyManager.refreshFromStorage()

                android.util.Log.d("MainActivity", "Starting ProfileSyncManager sync (fresh install)")
                profileSyncManager.onKeyImported()
            }
        }
    }

    // Check if already logged in on first composition (only if not already at MAIN)
    // IMPORTANT: Don't navigate if showBackupReminder is true - let OnboardingScreen handle that
    LaunchedEffect(uiState.isLoggedIn, uiState.isProfileCompleted, uiState.showBackupReminder) {
        if (uiState.isLoggedIn && !uiState.showBackupReminder && currentScreen == Screen.ONBOARDING) {
            nostrService.connect()

            // Determine next screen based on completion status
            currentScreen = when {
                // If onboarding fully completed, go to main
                onboardingCompleted -> Screen.MAIN
                // If profile not complete, go to profile setup
                !uiState.isProfileCompleted -> Screen.PROFILE_SETUP
                // If wallet setup not done, go to wallet setup
                !settingsManager.isWalletSetupDone() -> Screen.WALLET_SETUP
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
                        // After profile, go to wallet setup
                        currentScreen = if (settingsManager.isWalletSetupDone()) {
                            Screen.LOCATION_PERMISSION
                        } else {
                            Screen.WALLET_SETUP
                        }
                    },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.WALLET_SETUP -> {
                WalletSetupScreen(
                    walletService = walletService,
                    onComplete = {
                        settingsManager.setWalletSetupCompleted(true)
                        currentScreen = Screen.LOCATION_PERMISSION
                    },
                    onSkip = {
                        settingsManager.setWalletSetupSkipped(true)
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
                    nostrService = nostrService,
                    userProfile = userProfile,
                    walletService = walletService,
                    onLogout = {
                        // Disconnect from Nostr
                        nostrService.disconnect()

                        // Clear Nostr key and profile
                        onboardingViewModel.logout()

                        // Clear all settings (relays, preferences, onboarding, wallet setup)
                        settingsManager.clearAllData()

                        // Clear wallet data and key
                        walletService.resetWallet()
                        walletKeyManager.clearWalletKey()

                        // Clear ride history (singleton)
                        RideHistoryRepository.getInstance(context).clearAllHistory()

                        // Clear saved locations (favorites/recents)
                        com.ridestr.common.data.SavedLocationRepository.getInstance(context).clearAll()

                        currentScreen = Screen.ONBOARDING
                    },
                    onOpenProfile = {
                        currentScreen = Screen.PROFILE_SETUP
                    },
                    onOpenBackup = {
                        currentScreen = Screen.BACKUP_KEYS
                    },
                    onOpenAccountSafety = {
                        currentScreen = Screen.ACCOUNT_SAFETY
                    },
                    onOpenRelaySettings = {
                        currentScreen = Screen.RELAY_SETTINGS
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
                    onOpenRideDetail = { ride ->
                        selectedRide = ride
                        currentScreen = Screen.RIDE_DETAIL
                    },
                    onSetupWallet = {
                        currentScreen = Screen.WALLET_SETUP
                    },
                    onOpenWalletDetail = {
                        currentScreen = Screen.WALLET_DETAIL
                    },
                    onOpenWalletSettings = {
                        currentScreen = Screen.WALLET_SETTINGS
                    },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.WALLET_DETAIL -> {
                if (walletService != null) {
                    WalletDetailScreen(
                        walletService = walletService,
                        settingsManager = settingsManager,
                        priceService = bitcoinPriceService,
                        onBack = { currentScreen = Screen.MAIN },
                        modifier = Modifier.padding(innerPadding)
                    )
                } else {
                    // Fallback if wallet not set up
                    currentScreen = Screen.WALLET_SETUP
                }
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
                    walletService = walletService,
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.ACCOUNT_SAFETY -> {
                val riderViewModel: RiderViewModel = viewModel()
                AccountSafetyScreen(
                    nostrService = nostrService,
                    onBack = { currentScreen = Screen.MAIN },
                    onLocalStateClear = { riderViewModel.clearLocalRideState() },
                    walletService = walletService,
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.RELAY_SETTINGS -> {
                val connectedCount = connectionStates.values.count { it == RelayConnectionState.CONNECTED }
                val totalRelays = connectionStates.size

                RelayManagementScreen(
                    settingsManager = settingsManager,
                    connectedCount = connectedCount,
                    totalRelays = totalRelays,
                    onBack = { currentScreen = Screen.MAIN },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.WALLET_SETTINGS -> {
                WalletSettingsScreen(
                    walletService = walletService,
                    onBack = { currentScreen = Screen.MAIN },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.RIDE_DETAIL -> {
                val rideHistoryRepo = remember { RideHistoryRepository.getInstance(context) }
                val displayCurrency by settingsManager.displayCurrency.collectAsState()
                val riderViewModel: RiderViewModel = viewModel()
                val btcPrice by riderViewModel.bitcoinPriceService.btcPriceUsd.collectAsState()
                val coroutineScope = rememberCoroutineScope()

                selectedRide?.let { ride ->
                    RideDetailScreen(
                        ride = ride,
                        displayCurrency = displayCurrency,
                        btcPriceUsd = btcPrice,
                        settingsManager = settingsManager,
                        isRiderApp = true,
                        onBack = {
                            currentScreen = Screen.MAIN
                            selectedRide = null
                        },
                        onDelete = {
                            rideHistoryRepo.deleteRide(ride.rideId)
                            coroutineScope.launch {
                                rideHistoryRepo.backupToNostr(nostrService)
                            }
                            currentScreen = Screen.MAIN
                            selectedRide = null
                        },
                        onTip = { lightningAddress ->
                            tipLightningAddress = lightningAddress
                            currentScreen = Screen.TIP
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                } ?: run {
                    // Fallback if no ride selected
                    currentScreen = Screen.MAIN
                }
            }

            Screen.TIP -> {
                val rideHistoryRepo = remember { RideHistoryRepository.getInstance(context) }
                val riderViewModel: RiderViewModel = viewModel()
                val displayCurrency by settingsManager.displayCurrency.collectAsState()
                val btcPrice by riderViewModel.bitcoinPriceService.btcPriceUsd.collectAsState()

                tipLightningAddress?.let { address ->
                    TipScreen(
                        lightningAddress = address,
                        displayCurrency = displayCurrency,
                        btcPriceUsd = btcPrice,
                        settingsManager = settingsManager,
                        onBack = { currentScreen = Screen.RIDE_DETAIL },
                        onTipSent = { tipAmount ->
                            // Update the ride entry with tip amount
                            selectedRide?.let { ride ->
                                rideHistoryRepo.updateRide(ride.rideId) { entry ->
                                    entry.copy(tipSats = entry.tipSats + tipAmount)
                                }
                                // Update selected ride for detail screen
                                selectedRide = rideHistoryRepo.rides.value.find { it.rideId == ride.rideId }
                            }
                            currentScreen = Screen.RIDE_DETAIL
                        },
                        modifier = Modifier.padding(innerPadding)
                    )
                } ?: run {
                    currentScreen = Screen.RIDE_DETAIL
                }
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
    nostrService: NostrService,
    userProfile: UserProfile?,
    walletService: WalletService?,
    onLogout: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAccountSafety: () -> Unit,
    onOpenRelaySettings: () -> Unit,
    onOpenTiles: () -> Unit,
    onOpenDevOptions: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenRideDetail: (RideHistoryEntry) -> Unit,
    onSetupWallet: () -> Unit,
    onOpenWalletDetail: () -> Unit,
    onOpenWalletSettings: () -> Unit,
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

    // Wire up wallet service for balance checks
    LaunchedEffect(walletService) {
        riderViewModel.setWalletService(walletService)
    }

    val context = LocalContext.current

    // Ride history repository
    val rideHistoryRepository = remember { RideHistoryRepository.getInstance(context) }

    // Bitcoin price for fare display
    val btcPriceUsd by riderViewModel.bitcoinPriceService.btcPriceUsd.collectAsState()

    // Ensure relay connections when app returns to foreground
    // Also clear any stacked notification alerts
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        riderViewModel.onResume()
        RiderActiveService.clearAlerts(context)
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
                            totalRelays = totalRelays,
                            onClick = onOpenRelaySettings
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
                    onOpenWallet = { currentTab = Tab.WALLET },
                    modifier = Modifier.padding(innerPadding)
                )
            }
            Tab.WALLET -> {
                WalletScreen(
                    rideHistoryRepository = rideHistoryRepository,
                    settingsManager = settingsManager,
                    priceService = riderViewModel.bitcoinPriceService,
                    walletService = walletService,
                    onSetupWallet = onSetupWallet,
                    onOpenWalletDetail = onOpenWalletDetail,
                    onViewHistory = { currentTab = Tab.HISTORY },
                    modifier = Modifier.padding(innerPadding)
                )
            }
            Tab.HISTORY -> {
                HistoryScreen(
                    rideHistoryRepository = rideHistoryRepository,
                    settingsManager = settingsManager,
                    nostrService = nostrService,
                    priceService = riderViewModel.bitcoinPriceService,
                    onRideClick = onOpenRideDetail,
                    modifier = Modifier.padding(innerPadding)
                )
            }
            Tab.SETTINGS -> {
                SettingsContent(
                    settingsManager = settingsManager,
                    onOpenTiles = onOpenTiles,
                    onOpenDevOptions = onOpenDevOptions,
                    onOpenWalletSettings = onOpenWalletSettings,
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
            onAccountSafety = onOpenAccountSafety,
            onRelaySettings = onOpenRelaySettings,
            onLogout = onLogout,
            onDismiss = { showAccountSheet = false }
        )
    }
}
