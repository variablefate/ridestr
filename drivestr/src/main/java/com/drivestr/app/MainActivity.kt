package com.drivestr.app

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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import com.drivestr.app.ui.screens.DebugScreen
import com.drivestr.app.ui.screens.DriverModeScreen
import com.drivestr.app.ui.screens.KeyBackupScreen
import com.drivestr.app.ui.screens.OnboardingScreen
import com.drivestr.app.ui.screens.ProfileSetupScreen
import com.drivestr.app.ui.screens.SettingsContent
import com.drivestr.app.ui.screens.VehiclesScreen
import com.drivestr.app.ui.screens.VehicleSetupScreen
import com.drivestr.app.ui.screens.WalletScreen
import com.drivestr.app.ui.screens.EarningsScreen
import com.ridestr.common.data.RideHistoryRepository
import com.ridestr.common.nostr.events.RideHistoryEntry
import com.ridestr.common.ui.RideDetailScreen
import kotlinx.coroutines.launch
import com.ridestr.common.data.VehicleRepository
import com.ridestr.common.routing.NostrTileDiscoveryService
import com.ridestr.common.routing.TileDownloadService
import com.ridestr.common.routing.TileManager
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
import com.drivestr.app.viewmodels.DriverViewModel
import com.drivestr.app.viewmodels.OnboardingViewModel
import com.drivestr.app.viewmodels.ProfileViewModel
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.relay.RelayConnectionState
import com.ridestr.common.notification.NotificationHelper
import com.ridestr.common.ui.theme.RidestrTheme
import com.ridestr.common.ui.WalletDetailScreen
import com.ridestr.common.ui.WalletSetupScreen
import com.ridestr.common.ui.ProfileSyncScreen
import com.ridestr.common.payment.WalletKeyManager
import com.ridestr.common.payment.WalletService
import com.ridestr.common.payment.cashu.Nip60WalletSync
import com.ridestr.common.sync.ProfileSyncManager
import com.ridestr.common.sync.ProfileSyncState
import com.ridestr.common.sync.RestoredProfileData
import com.ridestr.common.sync.Nip60WalletSyncAdapter
import com.ridestr.common.sync.RideHistorySyncAdapter
import com.ridestr.common.sync.ProfileSyncAdapter
import com.drivestr.app.service.DriverOnlineService
import com.ridestr.common.bitcoin.BitcoinPriceService

/**
 * Bottom navigation tabs for the main screen.
 */
enum class Tab {
    DRIVE,      // Main driver mode
    WALLET,     // Lightning address, earnings summary & payments
    VEHICLES,   // Vehicle management
    SETTINGS    // Settings & developer options
}

/**
 * App navigation screens (for modals and onboarding).
 */
enum class Screen {
    ONBOARDING,
    PROFILE_SYNC,       // Sync profile data on key import (before profile setup)
    PROFILE_SETUP,
    VEHICLE_SETUP,      // Vehicle onboarding for drivers without vehicles
    WALLET_SETUP,       // Wallet onboarding (after vehicle, before location)
    LOCATION_PERMISSION,
    TILE_SETUP,
    MAIN,           // Shows bottom navigation with tabs
    WALLET_DETAIL,  // Full wallet interface (deposit, withdraw, etc.)
    WALLET_SETTINGS,// Wallet management settings
    EARNINGS,       // Full earnings history (navigated from wallet)
    RIDE_DETAIL,    // Detail view for single ride
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
                DrivestrApp()
            }
        }
    }
}

@Composable
fun DrivestrApp() {
    val context = LocalContext.current
    val onboardingViewModel: OnboardingViewModel = viewModel()
    val uiState by onboardingViewModel.uiState.collectAsState()

    // Initialize notification channels once on startup
    LaunchedEffect(Unit) {
        NotificationHelper.createDriverChannels(context)
    }

    // Settings manager (created first to get custom relays)
    val settingsManager = remember { SettingsManager(context) }

    // NostrService for relay connections (uses custom relays from settings)
    val nostrService = remember { NostrService(context, settingsManager.getEffectiveRelays()) }

    // Vehicle repository for multi-vehicle support
    val vehicleRepository = remember { VehicleRepository.getInstance(context) }

    // Ride history repository
    val rideHistoryRepository = remember { RideHistoryRepository.getInstance(context) }

    // Bitcoin price service for currency conversion (singleton)
    val bitcoinPriceService = remember { BitcoinPriceService.getInstance() }

    // Start price auto-refresh when app starts
    LaunchedEffect(Unit) {
        bitcoinPriceService.startAutoRefresh()
    }

    // Wallet services for payment rails
    val walletKeyManager = remember { WalletKeyManager(context) }
    val walletService = remember { WalletService(context, walletKeyManager) }

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

    // Auto-connect wallet to saved mint
    LaunchedEffect(Unit) {
        walletService.autoConnect()
    }

    // ProfileSyncManager for coordinated profile data sync
    val profileSyncManager = remember {
        ProfileSyncManager.getInstance(context, settingsManager.getEffectiveRelays())
    }

    // Register sync adapters (driver app: includes vehicles, no saved locations)
    LaunchedEffect(Unit) {
        profileSyncManager.registerSyncable(Nip60WalletSyncAdapter(nip60Sync))
        profileSyncManager.registerSyncable(ProfileSyncAdapter(
            vehicleRepository = vehicleRepository,
            savedLocationRepository = null,  // Driver app doesn't use saved locations
            settingsManager = settingsManager,
            nostrService = nostrService
        ))
        profileSyncManager.registerSyncable(RideHistorySyncAdapter(rideHistoryRepository, nostrService))
    }

    // Observable sync state for potential UI feedback
    val syncState by profileSyncManager.syncState.collectAsState()

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

    // Current location for tile recommendations
    var currentLocation by remember { mutableStateOf<Location?>(null) }

    // Relay state
    val connectionStates by nostrService.connectionStates.collectAsState()
    val recentEvents by nostrService.relayManager.events.collectAsState()
    val notices by nostrService.relayManager.notices.collectAsState()

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

    // Connect to Nostr if starting at MAIN screen
    LaunchedEffect(Unit) {
        if (currentScreen == Screen.MAIN) {
            nostrService.connect()
        }
    }

    // Track if we're doing a key import (vs new key generation) for sync flow
    var isKeyImport by remember { mutableStateOf(false) }

    // Note: Profile sync is now handled by the PROFILE_SYNC screen during onboarding,
    // not by a background LaunchedEffect. This ensures the sync completes before
    // the user sees onboarding screens that depend on synced data (like vehicles).

    // Auto-backup vehicles to Nostr when they change (with debounce)
    val vehicles by vehicleRepository.vehicles.collectAsState()
    var lastVehicleBackupHash by remember { mutableStateOf(0) }
    LaunchedEffect(vehicles, uiState.isLoggedIn) {
        if (!uiState.isLoggedIn) return@LaunchedEffect
        if (vehicles.isEmpty()) return@LaunchedEffect

        // Use hash to detect actual changes (not just recomposition)
        val currentHash = vehicles.hashCode()
        if (currentHash == lastVehicleBackupHash) return@LaunchedEffect

        // Debounce: wait 2 seconds after last change before backing up
        kotlinx.coroutines.delay(2000)

        // Check hash again after debounce (in case more changes happened)
        if (vehicles.hashCode() == currentHash) {
            lastVehicleBackupHash = currentHash
            android.util.Log.d("MainActivity", "Auto-backing up vehicles to Nostr...")
            profileSyncManager.backupProfileData()
        }
    }

    // Auto-backup settings to Nostr when they change (with debounce)
    val settingsHash by settingsManager.syncableSettingsHash.collectAsState(initial = 0)
    var lastSettingsBackupHash by remember { mutableStateOf(0) }
    LaunchedEffect(settingsHash, uiState.isLoggedIn) {
        if (!uiState.isLoggedIn) return@LaunchedEffect
        if (settingsHash == 0) return@LaunchedEffect
        val currentHash = settingsHash
        if (currentHash == lastSettingsBackupHash) return@LaunchedEffect
        // Debounce: wait 2 seconds before backing up
        kotlinx.coroutines.delay(2000)
        // After debounce, check if settingsHash changed (would trigger new LaunchedEffect)
        // If we get here and weren't cancelled, proceed with backup
        lastSettingsBackupHash = currentHash
        android.util.Log.d("MainActivity", "Auto-backing up settings to Nostr...")
        profileSyncManager.backupProfileData()
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
                val hasVehicles = vehicleRepository.vehicles.collectAsState().value.isNotEmpty()

                OnboardingScreen(
                    viewModel = onboardingViewModel,
                    onComplete = { wasKeyImport ->
                        nostrService.connect()

                        if (wasKeyImport) {
                            // Key import - need to sync profile from Nostr first
                            // CRITICAL: Refresh KeyManagers so they can read the imported key
                            nostrService.keyManager.refreshFromStorage()
                            profileSyncManager.keyManager.refreshFromStorage()
                            isKeyImport = true
                            currentScreen = Screen.PROFILE_SYNC
                        } else {
                            // New key generation - no profile to sync, go straight to setup
                            isKeyImport = false
                            currentScreen = when {
                                !uiState.isProfileCompleted -> Screen.PROFILE_SETUP
                                !hasVehicles -> Screen.VEHICLE_SETUP
                                !settingsManager.isWalletSetupDone() -> Screen.WALLET_SETUP
                                else -> Screen.LOCATION_PERMISSION
                            }
                        }
                    },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.PROFILE_SYNC -> {
                // Check for Ridestr data and sync if found (wallet handled separately by WalletSetupScreen)
                LaunchedEffect(Unit) {
                    profileSyncManager.checkAndSyncRidestrData()
                }

                ProfileSyncScreen(
                    syncState = syncState,
                    isDriverApp = true,
                    onComplete = { restoredData ->
                        profileSyncManager.resetSyncState()
                        // After sync, check what was restored and navigate appropriately
                        val hasVehicles = restoredData.vehicleCount > 0 ||
                                vehicleRepository.vehicles.value.isNotEmpty()

                        currentScreen = when {
                            !uiState.isProfileCompleted -> Screen.PROFILE_SETUP
                            !hasVehicles -> Screen.VEHICLE_SETUP
                            !settingsManager.isWalletSetupDone() -> Screen.WALLET_SETUP
                            else -> Screen.LOCATION_PERMISSION
                        }
                    },
                    onSkip = {
                        profileSyncManager.resetSyncState()
                        // Skip sync, proceed with normal onboarding
                        val hasVehicles = vehicleRepository.vehicles.value.isNotEmpty()
                        currentScreen = when {
                            !uiState.isProfileCompleted -> Screen.PROFILE_SETUP
                            !hasVehicles -> Screen.VEHICLE_SETUP
                            !settingsManager.isWalletSetupDone() -> Screen.WALLET_SETUP
                            else -> Screen.LOCATION_PERMISSION
                        }
                    },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.PROFILE_SETUP -> {
                val profileViewModel: ProfileViewModel = viewModel()
                val hasVehicles = vehicleRepository.vehicles.collectAsState().value.isNotEmpty()

                ProfileSetupScreen(
                    viewModel = profileViewModel,
                    onComplete = {
                        // Start tile discovery early (before location permission)
                        tileDiscoveryService.startDiscovery()
                        // After profile, check if driver has vehicles
                        currentScreen = when {
                            !hasVehicles -> Screen.VEHICLE_SETUP
                            !settingsManager.isWalletSetupDone() -> Screen.WALLET_SETUP
                            else -> Screen.LOCATION_PERMISSION
                        }
                    },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.VEHICLE_SETUP -> {
                VehicleSetupScreen(
                    vehicleRepository = vehicleRepository,
                    onComplete = {
                        // After adding vehicle, continue to wallet setup
                        currentScreen = if (settingsManager.isWalletSetupDone()) {
                            Screen.LOCATION_PERMISSION
                        } else {
                            Screen.WALLET_SETUP
                        }
                    },
                    onSkip = {
                        // Allow skipping vehicle setup (can add later)
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
                    isDriverApp = true,  // Driver app messaging
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
                    vehicleRepository = vehicleRepository,
                    rideHistoryRepository = rideHistoryRepository,
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

                        // Clear ride history
                        rideHistoryRepository.clearAllHistory()

                        // Clear saved locations (favorites/recents)
                        com.ridestr.common.data.SavedLocationRepository.getInstance(context).clearAll()

                        // Clear vehicle repository
                        vehicleRepository.clearAll()

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
                    onOpenEarnings = {
                        currentScreen = Screen.EARNINGS
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
                    onSyncProfile = {
                        profileSyncManager.checkAndSyncRidestrData()
                    },
                    onRefreshVehicles = {
                        profileSyncManager.checkAndSyncRidestrData()
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
                val useManualDriverLocation by settingsManager.useManualDriverLocation.collectAsState()
                val manualDriverLat by settingsManager.manualDriverLat.collectAsState()
                val manualDriverLon by settingsManager.manualDriverLon.collectAsState()

                DebugScreen(
                    npub = onboardingViewModel.getKeyManager().getNpub(),
                    pubKeyHex = onboardingViewModel.getKeyManager().getPubKeyHex(),
                    connectionStates = connectionStates,
                    recentEvents = recentEvents,
                    notices = notices,
                    useGeocodingSearch = useGeocodingSearch,
                    useManualDriverLocation = useManualDriverLocation,
                    manualDriverLat = manualDriverLat,
                    manualDriverLon = manualDriverLon,
                    onToggleGeocodingSearch = { settingsManager.toggleUseGeocodingSearch() },
                    onToggleManualDriverLocation = { settingsManager.toggleUseManualDriverLocation() },
                    onSetManualDriverLocation = { lat, lon -> settingsManager.setManualDriverLocation(lat, lon) },
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
                    isDriverApp = true,
                    onOpenDebug = { currentScreen = Screen.DEBUG },
                    onBack = { currentScreen = Screen.MAIN },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.ACCOUNT_SAFETY -> {
                val driverViewModel: DriverViewModel = viewModel()
                AccountSafetyScreen(
                    nostrService = nostrService,
                    onBack = { currentScreen = Screen.MAIN },
                    onLocalStateClear = { driverViewModel.clearLocalRideState() },
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
                    settingsManager = settingsManager,
                    isDriverApp = true,
                    onBack = { currentScreen = Screen.MAIN },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.EARNINGS -> {
                EarningsScreen(
                    rideHistoryRepository = rideHistoryRepository,
                    settingsManager = settingsManager,
                    nostrService = nostrService,
                    priceService = bitcoinPriceService,
                    onBack = { currentScreen = Screen.MAIN },
                    onRideClick = { ride ->
                        selectedRide = ride
                        currentScreen = Screen.RIDE_DETAIL
                    },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.RIDE_DETAIL -> {
                val displayCurrency by settingsManager.displayCurrency.collectAsState()
                val btcPrice by bitcoinPriceService.btcPriceUsd.collectAsState()
                val coroutineScope = rememberCoroutineScope()

                selectedRide?.let { ride ->
                    RideDetailScreen(
                        ride = ride,
                        displayCurrency = displayCurrency,
                        btcPriceUsd = btcPrice,
                        settingsManager = settingsManager,
                        isRiderApp = false,
                        onBack = {
                            currentScreen = Screen.EARNINGS
                            selectedRide = null
                        },
                        onDelete = {
                            rideHistoryRepository.deleteRide(ride.rideId)
                            coroutineScope.launch {
                                rideHistoryRepository.backupToNostr(nostrService)
                            }
                            currentScreen = Screen.EARNINGS
                            selectedRide = null
                        },
                        onTip = null,  // Drivers don't tip riders
                        modifier = Modifier.padding(innerPadding)
                    )
                } ?: run {
                    currentScreen = Screen.EARNINGS
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
    vehicleRepository: VehicleRepository,
    rideHistoryRepository: RideHistoryRepository,
    walletService: WalletService?,
    onLogout: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenAccountSafety: () -> Unit,
    onOpenRelaySettings: () -> Unit,
    onOpenTiles: () -> Unit,
    onOpenDevOptions: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenEarnings: () -> Unit,
    onSetupWallet: () -> Unit,
    onOpenWalletDetail: () -> Unit,
    onOpenWalletSettings: () -> Unit,
    onSyncProfile: (suspend () -> Unit)? = null,
    onRefreshVehicles: (suspend () -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val connectedCount = connectionStates.values.count { it == RelayConnectionState.CONNECTED }
    val totalRelays = connectionStates.size
    val isConnected = connectedCount > 0

    // Current tab state
    var currentTab by remember { mutableStateOf(Tab.DRIVE) }

    // Account bottom sheet state
    var showAccountSheet by remember { mutableStateOf(false) }

    // Vehicle repository state
    val vehicles by vehicleRepository.vehicles.collectAsState()

    // Driver ViewModel (persists across tab switches)
    val driverViewModel: DriverViewModel = viewModel()
    val driverUiState by driverViewModel.uiState.collectAsState()
    val autoOpenNavigation by settingsManager.autoOpenNavigation.collectAsState()

    // Wire up wallet service for HTLC escrow settlement
    LaunchedEffect(walletService) {
        driverViewModel.setWalletService(walletService)
    }

    val context = LocalContext.current

    // Bitcoin price for fare display
    val btcPriceUsd by driverViewModel.bitcoinPriceService.btcPriceUsd.collectAsState()

    // Ensure relay connections when app returns to foreground
    // Also clear any stacked notification alerts
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        driverViewModel.onResume()
        DriverOnlineService.clearAlerts(context)
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
                            Tab.DRIVE -> "Driver Mode"
                            Tab.WALLET -> "Wallet"
                            Tab.VEHICLES -> "Vehicles"
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
                    icon = {
                        Icon(
                            painterResource(R.drawable.ic_drive),
                            contentDescription = null,
                            modifier = Modifier.size(26.dp)
                        )
                    },
                    label = { Text("Drive") },
                    selected = currentTab == Tab.DRIVE,
                    onClick = { currentTab = Tab.DRIVE }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.AccountBalanceWallet, contentDescription = null) },
                    label = { Text("Wallet") },
                    selected = currentTab == Tab.WALLET,
                    onClick = { currentTab = Tab.WALLET }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.DriveEta, contentDescription = null) },
                    label = { Text("Vehicles") },
                    selected = currentTab == Tab.VEHICLES,
                    onClick = { currentTab = Tab.VEHICLES }
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
            Tab.DRIVE -> {
                DriverModeScreen(
                    viewModel = driverViewModel,
                    settingsManager = settingsManager,
                    vehicleRepository = vehicleRepository,
                    autoOpenNavigation = autoOpenNavigation,
                    modifier = Modifier.padding(innerPadding)
                )
            }
            Tab.WALLET -> {
                WalletScreen(
                    rideHistoryRepository = rideHistoryRepository,
                    settingsManager = settingsManager,
                    priceService = driverViewModel.bitcoinPriceService,
                    walletService = walletService,
                    onSetupWallet = onSetupWallet,
                    onOpenWalletDetail = onOpenWalletDetail,
                    onViewEarningsDetails = onOpenEarnings,
                    modifier = Modifier.padding(innerPadding)
                )
            }
            Tab.VEHICLES -> {
                val alwaysAskVehicle by settingsManager.alwaysAskVehicle.collectAsState()
                val activeVehicleId by settingsManager.activeVehicleId.collectAsState()
                VehiclesScreen(
                    vehicles = vehicles,
                    alwaysAskVehicle = alwaysAskVehicle,
                    activeVehicleId = activeVehicleId,
                    driverStage = driverUiState.stage,
                    onAddVehicle = { vehicleRepository.addVehicle(it) },
                    onUpdateVehicle = { vehicleRepository.updateVehicle(it) },
                    onDeleteVehicle = { vehicleRepository.deleteVehicle(it) },
                    onSetPrimary = {
                        vehicleRepository.setPrimaryVehicle(it)
                        settingsManager.setActiveVehicleId(it)  // Keep activeVehicleId in sync
                    },
                    onRefresh = onRefreshVehicles,
                    modifier = Modifier.padding(innerPadding)
                )
            }
            Tab.SETTINGS -> {
                SettingsContent(
                    settingsManager = settingsManager,
                    hasMultipleVehicles = vehicles.size > 1,
                    driverStage = driverUiState.stage,
                    onOpenTiles = onOpenTiles,
                    onOpenDevOptions = onOpenDevOptions,
                    onOpenWalletSettings = onOpenWalletSettings,
                    onSyncProfile = onSyncProfile,
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
