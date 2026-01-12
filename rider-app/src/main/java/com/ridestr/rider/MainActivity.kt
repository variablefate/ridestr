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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
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
import com.ridestr.rider.ui.screens.KeyBackupScreen
import com.ridestr.rider.ui.screens.OnboardingScreen
import com.ridestr.rider.ui.screens.ProfileSetupScreen
import com.ridestr.rider.ui.screens.SettingsScreen
import com.ridestr.common.routing.NostrTileDiscoveryService
import com.ridestr.common.routing.TileDownloadService
import com.ridestr.common.routing.TileManager
import com.ridestr.common.routing.ValhallaRoutingService
import com.ridestr.common.settings.SettingsManager
import com.ridestr.common.ui.LocationPermissionScreen
import com.ridestr.common.ui.TileManagementScreen
import com.ridestr.common.ui.TileSetupScreen
import com.ridestr.rider.viewmodels.RiderViewModel
import com.ridestr.rider.viewmodels.OnboardingViewModel
import com.ridestr.rider.viewmodels.ProfileViewModel
import com.ridestr.common.nostr.NostrService
import com.ridestr.common.nostr.relay.RelayConnectionState
import com.ridestr.common.notification.NotificationHelper
import com.ridestr.common.ui.theme.RidestrTheme
import kotlinx.coroutines.tasks.await

/**
 * App navigation screens.
 */
enum class Screen {
    ONBOARDING,
    PROFILE_SETUP,
    LOCATION_PERMISSION,
    TILE_SETUP,
    MAIN,
    DEBUG,
    BACKUP_KEYS,
    SETTINGS,
    TILES
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

    // NostrService for relay connections (shared instance)
    val nostrService = remember { NostrService(context) }

    // Settings manager
    val settingsManager = remember { SettingsManager(context) }

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

    // Navigation state
    var currentScreen by remember { mutableStateOf(Screen.ONBOARDING) }

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

    // Check if already logged in on first composition
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
                MainScreen(
                    keyManager = onboardingViewModel.getKeyManager(),
                    connectionStates = connectionStates,
                    settingsManager = settingsManager,
                    onLogout = {
                        nostrService.disconnect()
                        onboardingViewModel.logout()
                        settingsManager.resetOnboarding()
                        currentScreen = Screen.ONBOARDING
                    },
                    onOpenProfile = {
                        currentScreen = Screen.PROFILE_SETUP
                    },
                    onOpenDebug = {
                        currentScreen = Screen.DEBUG
                    },
                    onOpenBackup = {
                        currentScreen = Screen.BACKUP_KEYS
                    },
                    onOpenSettings = {
                        currentScreen = Screen.SETTINGS
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

            Screen.SETTINGS -> {
                SettingsScreen(
                    settingsManager = settingsManager,
                    onBack = { currentScreen = Screen.MAIN },
                    onOpenTiles = { currentScreen = Screen.TILES },
                    modifier = Modifier.padding(innerPadding)
                )
            }

            Screen.TILES -> {
                TileManagementScreen(
                    tileManager = tileManager,
                    downloadService = tileDownloadService,
                    discoveryService = tileDiscoveryService,
                    onBack = { currentScreen = Screen.SETTINGS },
                    modifier = Modifier.padding(innerPadding)
                )
            }
        }
    }
}

@Composable
fun MainScreen(
    keyManager: com.ridestr.common.nostr.keys.KeyManager,
    connectionStates: Map<String, RelayConnectionState>,
    settingsManager: SettingsManager,
    onLogout: () -> Unit,
    onOpenProfile: () -> Unit,
    onOpenDebug: () -> Unit,
    onOpenBackup: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val connectedCount = connectionStates.values.count { it == RelayConnectionState.CONNECTED }
    val totalRelays = connectionStates.size

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Header card with identity and utilities
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = keyManager.getNpub()?.let { "${it.take(16)}..." } ?: "Unknown",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                        )
                        Text(
                            text = "Relays: $connectedCount/$totalRelays",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (connectedCount > 0) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.error
                        )
                    }
                    Row {
                        IconButton(onClick = onOpenBackup) {
                            Icon(Icons.Default.Key, contentDescription = "Backup Keys")
                        }
                        IconButton(onClick = onOpenProfile) {
                            Icon(Icons.Default.Person, contentDescription = "Edit Profile")
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                        IconButton(onClick = onOpenDebug) {
                            Icon(Icons.Default.BugReport, contentDescription = "Debug")
                        }
                    }
                }

                TextButton(
                    onClick = onLogout,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Logout", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        // Rider mode content (no mode switching in this app)
        val riderViewModel: RiderViewModel = viewModel()

        // Ensure relay connections when app returns to foreground
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            riderViewModel.onResume()
        }

        RiderModeScreen(
            viewModel = riderViewModel,
            settingsManager = settingsManager,
            modifier = Modifier.weight(1f)
        )
    }
}
